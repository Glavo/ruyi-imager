// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.flash;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.glavo.ruyi.imager.core.ProgressEvent;
import org.glavo.ruyi.imager.core.ProgressReporter;
import org.glavo.ruyi.imager.core.SdkMessages;
import org.glavo.ruyi.imager.logging.LogRedactor;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// dd image writer backed by the Rust `dd-flasher` helper process.
@NotNullByDefault
public final class ProcessDdImageWriter implements DdImageWriter {
    /// Shared JSON mapper for helper events.
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /// Logger for helper process operations.
    private static final Logger LOGGER = LoggerFactory.getLogger(ProcessDdImageWriter.class);

    /// Maximum diagnostic output captured from helper stderr or launcher streams.
    private static final int MAX_CAPTURED_DIAGNOSTIC_BYTES = 64 * 1024;

    /// Helper executable path or command name.
    private final String executable;

    /// Helper command prefix used before dd-flasher wire arguments.
    private final @Unmodifiable List<String> commandPrefix;

    /// Creates a writer using the configured or bundled helper executable.
    ///
    /// @return process-backed dd image writer.
    public static ProcessDdImageWriter createDefault() {
        return new ProcessDdImageWriter(DDFlasherExecutableLocator.resolve());
    }

    /// Creates a writer using an explicit helper executable.
    ///
    /// @param executable helper executable path or command name.
    public ProcessDdImageWriter(String executable) {
        this(List.of(executable));
    }

    /// Creates a writer using an explicit helper command prefix.
    ///
    /// @param commandPrefix helper command prefix before wire arguments.
    ProcessDdImageWriter(@Unmodifiable List<String> commandPrefix) {
        if (commandPrefix.isEmpty()) {
            throw new IllegalArgumentException("Command prefix must not be empty.");
        }
        this.executable = commandPrefix.getFirst();
        this.commandPrefix = List.copyOf(commandPrefix);
    }

    /// Writes a source image to a target path.
    ///
    /// @param source source image path.
    /// @param target target path.
    /// @param totalBytes source size.
    /// @param targetRemovable whether the target was identified as removable.
    /// @param message progress message.
    /// @param reporter progress reporter.
    /// @throws IOException when the image cannot be written.
    @Override
    public void write(
            Path source,
            Path target,
            long totalBytes,
            boolean targetRemovable,
            String message,
            ProgressReporter reporter) throws IOException {
        if (!run("write", "flash", source, target, totalBytes, targetRemovable, message, reporter)) {
            throw new IOException(SdkMessages.get("core.dd.writeFailed"));
        }
    }

    /// Verifies target bytes against a source image.
    ///
    /// @param source source image path.
    /// @param target target path.
    /// @param totalBytes source size.
    /// @param targetRemovable whether the target was identified as removable.
    /// @param message progress message.
    /// @param reporter progress reporter.
    /// @return whether the target bytes match the source image.
    /// @throws IOException when files cannot be read.
    @Override
    public boolean verify(
            Path source,
            Path target,
            long totalBytes,
            boolean targetRemovable,
            String message,
            ProgressReporter reporter) throws IOException {
        return run("verify", "verify", source, target, totalBytes, targetRemovable, message, reporter);
    }

    /// Runs one helper operation.
    ///
    /// @param operation helper operation.
    /// @param stage SDK progress stage.
    /// @param source source image path.
    /// @param target target path.
    /// @param totalBytes source size.
    /// @param targetRemovable whether the target was identified as removable.
    /// @param message progress message.
    /// @param reporter progress reporter.
    /// @return operation success result.
    /// @throws IOException when helper execution or output parsing fails.
    private boolean run(
            String operation,
            String stage,
            Path source,
            Path target,
            long totalBytes,
            boolean targetRemovable,
            String message,
            ProgressReporter reporter) throws IOException {
        List<String> arguments = arguments(operation, source, target, totalBytes, targetRemovable);
        if (DDFlasherElevation.shouldElevate(target)) {
            return runElevated(operation, stage, arguments, message, reporter);
        }

        List<String> command = command(arguments);
        LOGGER.atInfo().log(() -> "Running dd-flasher helper. command=" + LogRedactor.redactCommand(command));
        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException exception) {
            throw new IOException(SdkMessages.get("core.dd.missingExecutable", executable), exception);
        }

        HelperEventState state = new HelperEventState(stage, message, reporter);
        ProcessStreamCollector stderr = ProcessStreamCollector.start(process.getErrorStream(), "dd-flasher-stderr");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                state.accept(line);
            }

            int exitCode = waitFor(process, command);
            stderr.await(command);
            return finish(operation, state, exitCode, stderr.text());
        } catch (IOException exception) {
            destroyForcibly(process);
            stderr.awaitQuietly();
            throw exception;
        }
    }

    /// Runs one helper operation through a platform elevation launcher.
    ///
    /// @param operation helper operation.
    /// @param stage SDK progress stage.
    /// @param arguments helper arguments excluding executable.
    /// @param message progress message.
    /// @param reporter progress reporter.
    /// @return operation success result.
    /// @throws IOException when helper execution or output parsing fails.
    private boolean runElevated(
            String operation,
            String stage,
            List<String> arguments,
            String message,
            ProgressReporter reporter) throws IOException {
        Path eventLog = Files.createTempFile("ruyi-imager-dd-flasher-", ".ndjson");
        ArrayList<String> elevatedArguments = new ArrayList<>(arguments.size() + 3);
        elevatedArguments.addAll(arguments);
        elevatedArguments.add("--event-log");
        elevatedArguments.add(eventLog.toString());
        elevatedArguments.add("--no-stdout");

        List<String> command;
        try {
            command = DDFlasherElevation.elevatedCommand(
                    executable,
                    elevatedArguments,
                    System.getProperty("os.name", ""));
        } catch (IOException exception) {
            deleteEventLog(eventLog);
            throw new IOException(SdkMessages.get("core.dd.elevationFailed", executable), exception);
        }
        LOGGER.atInfo().log(() -> "Running elevated dd-flasher helper. command=" + LogRedactor.redactCommand(command));

        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException exception) {
            deleteEventLog(eventLog);
            throw new IOException(SdkMessages.get("core.dd.elevationFailed", commandText(command)), exception);
        }

        HelperEventState state = new HelperEventState(stage, message, reporter);
        ProcessStreamCollector stdout = ProcessStreamCollector.start(process.getInputStream(), "dd-flasher-elevated-stdout");
        ProcessStreamCollector stderr = ProcessStreamCollector.start(process.getErrorStream(), "dd-flasher-elevated-stderr");
        long offset = 0L;
        int exitCode;
        try {
            while (!process.waitFor(100L, TimeUnit.MILLISECONDS)) {
                offset = readEventLog(eventLog, offset, state);
            }
            exitCode = process.exitValue();
            readEventLog(eventLog, offset, state);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            stdout.awaitQuietly();
            stderr.awaitQuietly();
            throw new IOException(SdkMessages.get("core.dd.interrupted", commandText(command)), exception);
        } catch (IOException exception) {
            destroyForcibly(process);
            stdout.awaitQuietly();
            stderr.awaitQuietly();
            throw exception;
        } finally {
            deleteEventLog(eventLog);
        }
        stdout.await(command);
        stderr.await(command);

        return finish(operation, state, exitCode, diagnosticText(stdout, stderr));
    }

    /// Finishes one helper run after the process has exited.
    ///
    /// @param operation helper operation.
    /// @param state parsed helper events.
    /// @param exitCode process exit code.
    /// @param diagnosticText captured process diagnostic text.
    /// @return operation success result.
    /// @throws IOException when helper execution failed.
    private boolean finish(
            String operation,
            HelperEventState state,
            int exitCode,
            String diagnosticText) throws IOException {
        if (exitCode != 0) {
            String messageText = state.helperError == null ? diagnosticText : state.helperError;
            throw new IOException(SdkMessages.get("core.dd.commandFailed", exitCode, messageText));
        }
        if (!state.completed) {
            throw new IOException(SdkMessages.get("core.dd.missingResult"));
        }
        boolean result = state.success;
        LOGGER.atInfo().log(() -> "dd-flasher helper completed. operation=" + operation + ", success=" + result);
        return result;
    }

    /// Builds helper arguments.
    ///
    /// @param operation helper operation.
    /// @param source source image path.
    /// @param target target path.
    /// @param totalBytes source size.
    /// @param targetRemovable whether the target was identified as removable.
    /// @return helper arguments.
    private List<String> arguments(
            String operation,
            Path source,
            Path target,
            long totalBytes,
            boolean targetRemovable) {
        ArrayList<String> command = new ArrayList<>(9);
        command.add(operation);
        command.add("--source");
        command.add(source.toString());
        command.add("--target");
        command.add(target.toString());
        command.add("--total-bytes");
        command.add(Long.toString(totalBytes));
        command.add("--removable");
        command.add(Boolean.toString(targetRemovable));
        return command;
    }

    /// Builds a helper command line.
    ///
    /// @param arguments helper arguments excluding executable.
    /// @return helper command line.
    private List<String> command(List<String> arguments) {
        ArrayList<String> command = new ArrayList<>(commandPrefix.size() + arguments.size());
        command.addAll(commandPrefix);
        command.addAll(arguments);
        return command;
    }

    /// Parses one helper event line.
    ///
    /// @param line helper output line.
    /// @return parsed event.
    /// @throws IOException when the line is not valid JSON.
    private static JsonNode parseEvent(String line) throws IOException {
        try {
            return MAPPER.readTree(line);
        } catch (IOException exception) {
            throw new IOException(SdkMessages.get("core.dd.invalidOutput", line), exception);
        }
    }

    /// Waits for a helper process to exit.
    ///
    /// @param process helper process.
    /// @param command command line.
    /// @return process exit code.
    /// @throws IOException when the wait is interrupted.
    private static int waitFor(Process process, List<String> command) throws IOException {
        try {
            return process.waitFor();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException(SdkMessages.get("core.dd.interrupted", commandText(command)), exception);
        }
    }

    /// Reads newly appended helper events from an event log.
    ///
    /// @param eventLog event log path.
    /// @param offset byte offset already consumed.
    /// @param state parsed helper event state.
    /// @return next byte offset.
    /// @throws IOException when the event log cannot be read or parsed.
    private static long readEventLog(Path eventLog, long offset, HelperEventState state) throws IOException {
        if (!Files.exists(eventLog)) {
            return offset;
        }

        try (SeekableByteChannel channel = Files.newByteChannel(eventLog, StandardOpenOption.READ)) {
            if (offset >= channel.size()) {
                return offset;
            }
            channel.position(offset);

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            while (channel.read(buffer) > 0) {
                buffer.flip();
                output.write(buffer.array(), 0, buffer.limit());
                buffer.clear();
            }

            byte[] bytes = output.toByteArray();
            int end = lastLineBreak(bytes);
            if (end < 0) {
                return offset;
            }

            String text = new String(bytes, 0, end + 1, StandardCharsets.UTF_8);
            for (String line : text.split("\\R")) {
                state.accept(line);
            }
            return offset + end + 1L;
        }
    }

    /// Returns the last line-break byte index.
    ///
    /// @param bytes byte array.
    /// @return last line-break index, or -1 when absent.
    private static int lastLineBreak(byte[] bytes) {
        for (int index = bytes.length - 1; index >= 0; index--) {
            if (bytes[index] == '\n') {
                return index;
            }
        }
        return -1;
    }

    /// Deletes one temporary event log.
    ///
    /// @param eventLog temporary event log.
    private static void deleteEventLog(Path eventLog) {
        try {
            Files.deleteIfExists(eventLog);
        } catch (IOException exception) {
            LOGGER.debug("Failed to delete dd-flasher event log.", exception);
        }
    }

    /// Destroys a helper process that is no longer needed.
    ///
    /// @param process helper process.
    private static void destroyForcibly(Process process) {
        if (process.isAlive()) {
            process.destroyForcibly();
        }
    }

    /// Returns the preferred diagnostic text from elevated launcher output.
    ///
    /// @param stdout captured standard output.
    /// @param stderr captured standard error.
    /// @return diagnostic text.
    private static String diagnosticText(ProcessStreamCollector stdout, ProcessStreamCollector stderr) {
        String errorText = stderr.text();
        if (!SdkMessages.get("core.dd.noOutput").equals(errorText)) {
            return errorText;
        }
        return stdout.text();
    }

    /// Formats a command line for user-facing diagnostics.
    ///
    /// @param command command arguments.
    /// @return command text.
    private static String commandText(List<String> command) {
        return String.join(" ", LogRedactor.redactCommand(command));
    }

    /// Concurrently drains one process stream into a bounded diagnostic buffer.
    private static final class ProcessStreamCollector {
        /// Captured diagnostic bytes.
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();

        /// Background collector thread.
        private final Thread thread;

        /// Source stream consumed by the collector.
        private final InputStream input;

        /// Whether output was truncated.
        private boolean truncated;

        /// Failure raised while reading the stream.
        private @Nullable IOException failure;

        /// Starts a stream collector.
        ///
        /// @param input input stream to drain.
        /// @param name background thread name.
        /// @return started collector.
        private static ProcessStreamCollector start(InputStream input, String name) {
            ProcessStreamCollector collector = new ProcessStreamCollector(input, name);
            collector.thread.start();
            return collector;
        }

        /// Creates a stream collector.
        ///
        /// @param input input stream to drain.
        /// @param name background thread name.
        private ProcessStreamCollector(InputStream input, String name) {
            this.input = input;
            this.thread = new Thread(this::drain, name);
            this.thread.setDaemon(true);
        }

        /// Drains the stream until EOF or read failure.
        private void drain() {
            byte[] buffer = new byte[8192];
            try (InputStream stream = input) {
                int read;
                while ((read = stream.read(buffer)) >= 0) {
                    append(buffer, read);
                }
            } catch (IOException exception) {
                failure = exception;
            }
        }

        /// Appends bytes to the bounded diagnostic buffer.
        ///
        /// @param buffer source byte buffer.
        /// @param length number of bytes read.
        private void append(byte[] buffer, int length) {
            if (length <= 0) {
                return;
            }
            int allowed = MAX_CAPTURED_DIAGNOSTIC_BYTES - output.size();
            if (allowed > 0) {
                output.write(buffer, 0, Math.min(allowed, length));
            }
            if (length > allowed) {
                truncated = true;
            }
        }

        /// Waits for the collector to finish.
        ///
        /// @param command helper command for interruption diagnostics.
        /// @throws IOException when waiting is interrupted or stream reading fails.
        private void await(List<String> command) throws IOException {
            try {
                thread.join();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException(SdkMessages.get("core.dd.interrupted", commandText(command)), exception);
            }
            if (failure != null) {
                throw failure;
            }
        }

        /// Waits for the collector during exceptional cleanup.
        private void awaitQuietly() {
            try {
                thread.join(TimeUnit.SECONDS.toMillis(1L));
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }

        /// Returns captured diagnostic text.
        ///
        /// @return diagnostic text or the no-output fallback.
        private String text() {
            String text = output.toString(StandardCharsets.UTF_8).strip();
            if (text.isBlank()) {
                return SdkMessages.get("core.dd.noOutput");
            }
            return truncated ? text + System.lineSeparator() + "[truncated]" : text;
        }
    }

    /// Mutable parsed helper event state.
    private static final class HelperEventState {
        /// SDK progress stage.
        private final String stage;

        /// SDK progress message.
        private final String message;

        /// SDK progress reporter.
        private final ProgressReporter reporter;

        /// Last helper error.
        private @Nullable String helperError;

        /// Whether a completion event was received.
        private boolean completed;

        /// Completion success value.
        private boolean success;

        /// Creates the event state.
        ///
        /// @param stage SDK progress stage.
        /// @param message SDK progress message.
        /// @param reporter SDK progress reporter.
        private HelperEventState(String stage, String message, ProgressReporter reporter) {
            this.stage = stage;
            this.message = message;
            this.reporter = reporter;
        }

        /// Applies one helper event line.
        ///
        /// @param line helper event line.
        /// @throws IOException when the event cannot be parsed.
        private void accept(String line) throws IOException {
            if (line.isBlank()) {
                return;
            }

            JsonNode event = parseEvent(line);
            String type = event.path("type").asText();
            switch (type) {
                case "progress" -> reporter.report(new ProgressEvent(
                        stage,
                        message,
                        event.path("currentBytes").asLong(),
                        event.path("totalBytes").asLong()));
                case "complete" -> {
                    completed = true;
                    success = event.path("success").asBoolean(false);
                }
                case "error" -> helperError = event.path("message").asText(SdkMessages.get("core.dd.unknownFailure"));
                default -> throw new IOException(SdkMessages.get("core.dd.unexpectedEvent", type));
            }
        }
    }
}

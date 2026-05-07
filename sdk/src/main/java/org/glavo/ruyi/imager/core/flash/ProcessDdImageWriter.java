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

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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

    /// Helper executable path or command name.
    private final String executable;

    /// Creates a writer using the configured or bundled helper executable.
    ///
    /// @return process-backed dd image writer.
    public static ProcessDdImageWriter createDefault() {
        return new ProcessDdImageWriter(DdFlasherExecutableLocator.resolve());
    }

    /// Creates a writer using an explicit helper executable.
    ///
    /// @param executable helper executable path or command name.
    public ProcessDdImageWriter(String executable) {
        this.executable = executable;
    }

    /// Writes a source image to a target path.
    ///
    /// @param source source image path.
    /// @param target target path.
    /// @param totalBytes source size.
    /// @param message progress message.
    /// @param reporter progress reporter.
    /// @throws IOException when the image cannot be written.
    @Override
    public void write(
            Path source,
            Path target,
            long totalBytes,
            String message,
            ProgressReporter reporter) throws IOException {
        if (!run("write", "flash", source, target, totalBytes, message, reporter)) {
            throw new IOException(SdkMessages.get("core.dd.writeFailed"));
        }
    }

    /// Verifies target bytes against a source image.
    ///
    /// @param source source image path.
    /// @param target target path.
    /// @param totalBytes source size.
    /// @param message progress message.
    /// @param reporter progress reporter.
    /// @return whether the target bytes match the source image.
    /// @throws IOException when files cannot be read.
    @Override
    public boolean verify(
            Path source,
            Path target,
            long totalBytes,
            String message,
            ProgressReporter reporter) throws IOException {
        return run("verify", "verify", source, target, totalBytes, message, reporter);
    }

    /// Runs one helper operation.
    ///
    /// @param operation helper operation.
    /// @param stage SDK progress stage.
    /// @param source source image path.
    /// @param target target path.
    /// @param totalBytes source size.
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
            String message,
            ProgressReporter reporter) throws IOException {
        List<String> arguments = arguments(operation, source, target, totalBytes);
        if (DdFlasherElevation.shouldElevate(target)) {
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
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                state.accept(line);
            }
        }

        int exitCode = waitFor(process, command);
        return finish(operation, command, process, state, exitCode);
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
            command = DdFlasherElevation.elevatedCommand(
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
            throw new IOException(SdkMessages.get("core.dd.interrupted", commandText(command)), exception);
        } finally {
            deleteEventLog(eventLog);
        }

        return finish(operation, command, process, state, exitCode);
    }

    /// Finishes one helper run after the process has exited.
    ///
    /// @param operation helper operation.
    /// @param command launcher command.
    /// @param process exited process.
    /// @param state parsed helper events.
    /// @param exitCode process exit code.
    /// @return operation success result.
    /// @throws IOException when helper execution failed.
    private boolean finish(
            String operation,
            List<String> command,
            Process process,
            HelperEventState state,
            int exitCode) throws IOException {
        if (exitCode != 0) {
            String messageText = state.helperError == null ? readErrorText(process) : state.helperError;
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
    /// @return helper arguments.
    private List<String> arguments(String operation, Path source, Path target, long totalBytes) {
        ArrayList<String> command = new ArrayList<>(7);
        command.add(operation);
        command.add("--source");
        command.add(source.toString());
        command.add("--target");
        command.add(target.toString());
        command.add("--total-bytes");
        command.add(Long.toString(totalBytes));
        return command;
    }

    /// Builds a helper command line.
    ///
    /// @param arguments helper arguments excluding executable.
    /// @return helper command line.
    private List<String> command(List<String> arguments) {
        ArrayList<String> command = new ArrayList<>(arguments.size() + 1);
        command.add(executable);
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

    /// Reads helper stderr as fallback diagnostic text.
    ///
    /// @param process helper process.
    /// @return diagnostic text.
    private static String readErrorText(Process process) {
        try {
            String text = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            return text.isBlank() ? SdkMessages.get("core.dd.noOutput") : text;
        } catch (IOException _) {
            return SdkMessages.get("core.dd.noOutput");
        }
    }

    /// Formats a command line for user-facing diagnostics.
    ///
    /// @param command command arguments.
    /// @return command text.
    private static String commandText(List<String> command) {
        return String.join(" ", LogRedactor.redactCommand(command));
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

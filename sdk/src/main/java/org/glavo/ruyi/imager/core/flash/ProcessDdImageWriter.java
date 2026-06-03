// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.flash;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.glavo.ruyi.imager.core.ProgressEvent;
import org.glavo.ruyi.imager.core.ProgressReporter;
import org.glavo.ruyi.imager.core.SdkMessages;
import org.glavo.ruyi.imager.core.device.BlockDevice;
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
import java.util.Locale;
import java.util.Map;
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

    /// Returns whether the helper can lock and dismount a mounted block target itself.
    ///
    /// @param target target block device.
    /// @return whether this writer can handle the mounted target safely.
    @Override
    public boolean canWriteMountedTarget(BlockDevice target) {
        return isWindows() && windowsPhysicalDriveTarget(target.path());
    }

    /// Writes a source image to a target path.
    ///
    /// @param source source image path.
    /// @param target target path.
    /// @param targetDisplayName human-readable target display name.
    /// @param totalBytes source size.
    /// @param targetRemovable whether the target was identified as removable.
    /// @param message progress message.
    /// @param reporter progress reporter.
    /// @throws IOException when the image cannot be written.
    @Override
    public void write(
            Path source,
            Path target,
            String targetDisplayName,
            long totalBytes,
            boolean targetRemovable,
            String message,
            ProgressReporter reporter) throws IOException {
        if (!run(
                "write",
                progressSinks("write", "flash", message),
                source,
                target,
                targetDisplayName,
                totalBytes,
                targetRemovable,
                reporter)) {
            throw new IOException(SdkMessages.get("core.dd.writeFailed"));
        }
    }

    /// Verifies target bytes against a source image.
    ///
    /// @param source source image path.
    /// @param target target path.
    /// @param targetDisplayName human-readable target display name.
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
            String targetDisplayName,
            long totalBytes,
            boolean targetRemovable,
            String message,
            ProgressReporter reporter) throws IOException {
        return run(
                "verify",
                progressSinks("verify", "verify", message),
                source,
                target,
                targetDisplayName,
                totalBytes,
                targetRemovable,
                reporter);
    }

    /// Writes a source image to a target path and verifies the written bytes through one helper process.
    ///
    /// @param source source image path.
    /// @param target target path.
    /// @param targetDisplayName human-readable target display name.
    /// @param totalBytes source size.
    /// @param targetRemovable whether the target was identified as removable.
    /// @param writeMessage progress message for writing.
    /// @param verifyMessage progress message for verification.
    /// @param reporter progress reporter.
    /// @return whether the target bytes match the source image after writing.
    /// @throws IOException when the image cannot be written or read.
    @Override
    public boolean writeAndVerify(
            Path source,
            Path target,
            String targetDisplayName,
            long totalBytes,
            boolean targetRemovable,
            String writeMessage,
            String verifyMessage,
            ProgressReporter reporter) throws IOException {
        return run(
                "write-verify",
                Map.of(
                        "write", new ProgressSink("flash", writeMessage),
                        "verify", new ProgressSink("verify", verifyMessage)),
                source,
                target,
                targetDisplayName,
                totalBytes,
                targetRemovable,
                reporter);
    }

    /// Runs one helper operation.
    ///
    /// @param operation helper operation.
    /// @param progressSinks progress sinks keyed by helper operation.
    /// @param source source image path.
    /// @param target target path.
    /// @param targetDisplayName human-readable target display name.
    /// @param totalBytes source size.
    /// @param targetRemovable whether the target was identified as removable.
    /// @param reporter progress reporter.
    /// @return operation success result.
    /// @throws IOException when helper execution or output parsing fails.
    private boolean run(
            String operation,
            Map<String, ProgressSink> progressSinks,
            Path source,
            Path target,
            String targetDisplayName,
            long totalBytes,
            boolean targetRemovable,
            ProgressReporter reporter) throws IOException {
        String helperTargetDisplayName = validatedTargetDisplayName(targetDisplayName);
        List<String> arguments = arguments(
                operation,
                source,
                target,
                helperTargetDisplayName,
                totalBytes,
                targetRemovable);
        if (DDFlasherElevation.shouldElevate(target)) {
            return runElevated(operation, progressSinks, helperTargetDisplayName, arguments, reporter);
        }

        List<String> command = command(arguments);
        LOGGER.atInfo().log(() -> "Running dd-flasher helper. targetDisplayName="
                + LogRedactor.redactText(helperTargetDisplayName)
                + ", command="
                + LogRedactor.redactCommand(command));
        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException exception) {
            throw new IOException(SdkMessages.get("core.dd.missingExecutable", executable), exception);
        }

        HelperEventState state = new HelperEventState(progressSinks, reporter);
        ProcessEventCollector stdout = ProcessEventCollector.start(process.getInputStream(), state, "dd-flasher-stdout");
        ProcessStreamCollector stderr = ProcessStreamCollector.start(process.getErrorStream(), "dd-flasher-stderr");
        try {
            int exitCode = waitFor(process, command);
            stdout.await(command);
            stderr.await(command);
            return finish(operation, state, exitCode, stderr.text());
        } catch (IOException exception) {
            destroyForcibly(process);
            stdout.awaitQuietly();
            stderr.awaitQuietly();
            throw exception;
        }
    }

    /// Runs one helper operation through a platform elevation launcher.
    ///
    /// @param operation helper operation.
    /// @param progressSinks progress sinks keyed by helper operation.
    /// @param targetDisplayName human-readable target display name.
    /// @param arguments helper arguments excluding executable.
    /// @param reporter progress reporter.
    /// @return operation success result.
    /// @throws IOException when helper execution or output parsing fails.
    private boolean runElevated(
            String operation,
            Map<String, ProgressSink> progressSinks,
            String targetDisplayName,
            List<String> arguments,
            ProgressReporter reporter) throws IOException {
        Path eventLog = Files.createTempFile("ruyi-imager-dd-flasher-", ".ndjson");
        Path cancelFile;
        try {
            cancelFile = temporarySignalPath("ruyi-imager-dd-flasher-cancel-", ".signal");
        } catch (IOException exception) {
            deleteEventLog(eventLog);
            throw exception;
        }
        ArrayList<String> elevatedArguments = new ArrayList<>(arguments.size() + 5);
        elevatedArguments.addAll(arguments);
        elevatedArguments.add("--event-log");
        elevatedArguments.add(eventLog.toString());
        elevatedArguments.add("--cancel-file");
        elevatedArguments.add(cancelFile.toString());
        elevatedArguments.add("--no-stdout");

        List<String> command;
        try {
            command = DDFlasherElevation.elevatedCommand(
                    executable,
                    elevatedArguments,
                    System.getProperty("os.name", ""));
        } catch (IOException exception) {
            deleteEventLog(eventLog);
            deleteCancelFile(cancelFile);
            throw new IOException(SdkMessages.get("core.dd.elevationFailed", executable), exception);
        }
        LOGGER.atInfo().log(() -> "Running elevated dd-flasher helper. targetDisplayName="
                + LogRedactor.redactText(targetDisplayName)
                + ", command="
                + LogRedactor.redactCommand(command));

        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException exception) {
            deleteEventLog(eventLog);
            deleteCancelFile(cancelFile);
            throw new IOException(SdkMessages.get("core.dd.elevationFailed", commandText(command)), exception);
        }

        HelperEventState state = new HelperEventState(progressSinks, reporter);
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
            signalCancelFile(cancelFile);
            try {
                if (!process.waitFor(10L, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException cleanupException) {
                exception.addSuppressed(cleanupException);
                process.destroyForcibly();
            }
            Thread.currentThread().interrupt();
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
            deleteCancelFile(cancelFile);
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
    /// @param targetDisplayName human-readable target display name.
    /// @param totalBytes source size.
    /// @param targetRemovable whether the target was identified as removable.
    /// @return helper arguments.
    private List<String> arguments(
            String operation,
            Path source,
            Path target,
            String targetDisplayName,
            long totalBytes,
            boolean targetRemovable) {
        ArrayList<String> command = new ArrayList<>(11);
        command.add(operation);
        command.add("--source");
        command.add(source.toString());
        command.add("--target");
        command.add(helperTargetArgument(target));
        command.add("--target-display-name");
        command.add(targetDisplayName);
        command.add("--total-bytes");
        command.add(Long.toString(totalBytes));
        command.add("--removable");
        command.add(Boolean.toString(targetRemovable));
        return command;
    }

    /// Validates the target display name passed to the helper.
    ///
    /// @param targetDisplayName target display name.
    /// @return validated target display name.
    private static String validatedTargetDisplayName(String targetDisplayName) {
        if (targetDisplayName.isBlank()) {
            throw new IllegalArgumentException("Target display name must not be blank.");
        }
        if (targetDisplayName.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Target display name must not contain control characters.");
        }
        return targetDisplayName.strip();
    }

    /// Converts a target path to the string passed to the helper.
    ///
    /// @param target target path.
    /// @return helper target argument.
    static String helperTargetArgument(Path target) {
        String text = target.toString();
        int end = windowsPhysicalDriveEnd(text);
        if (end < 0) {
            return text;
        }
        return text.substring(0, end);
    }

    /// Returns whether a path identifies a Windows physical drive.
    ///
    /// @param target target path.
    /// @return whether the target is a Windows physical drive.
    static boolean windowsPhysicalDriveTarget(Path target) {
        return windowsPhysicalDriveEnd(target.toString()) >= 0;
    }

    /// Returns the end of a Windows physical drive path after trimming trailing separators.
    ///
    /// @param text target path text.
    /// @return physical drive path end, or `-1` when the path is not a physical drive.
    private static int windowsPhysicalDriveEnd(String text) {
        String prefix = "\\\\.\\PHYSICALDRIVE";
        if (!text.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return -1;
        }
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == '\\') {
            end--;
        }
        if (end == prefix.length()) {
            return -1;
        }
        for (int index = prefix.length(); index < end; index++) {
            if (!Character.isDigit(text.charAt(index))) {
                return -1;
            }
        }
        return end;
    }

    /// Returns whether the current operating system is Windows.
    ///
    /// @return whether the current operating system is Windows.
    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
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

    /// Builds a single helper operation progress mapping.
    ///
    /// @param operation helper operation name.
    /// @param stage SDK progress stage.
    /// @param message SDK progress message.
    /// @return progress sink mapping.
    private static Map<String, ProgressSink> progressSinks(String operation, String stage, String message) {
        return Map.of(operation, new ProgressSink(stage, message));
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

    /// Creates a temporary signal path that does not currently exist.
    ///
    /// @param prefix temporary file prefix.
    /// @param suffix temporary file suffix.
    /// @return non-existing temporary path.
    /// @throws IOException when the path cannot be reserved.
    private static Path temporarySignalPath(String prefix, String suffix) throws IOException {
        Path path = Files.createTempFile(prefix, suffix);
        Files.delete(path);
        return path;
    }

    /// Creates a helper cancellation signal file.
    ///
    /// @param cancelFile cancellation signal path.
    private static void signalCancelFile(Path cancelFile) {
        try {
            Files.writeString(
                    cancelFile,
                    "cancel",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (IOException exception) {
            LOGGER.debug("Failed to signal dd-flasher cancellation.", exception);
        }
    }

    /// Deletes one temporary cancellation signal file.
    ///
    /// @param cancelFile cancellation signal path.
    private static void deleteCancelFile(Path cancelFile) {
        try {
            Files.deleteIfExists(cancelFile);
        } catch (IOException exception) {
            LOGGER.debug("Failed to delete dd-flasher cancellation signal.", exception);
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

    /// Concurrently drains helper stdout and parses NDJSON events.
    private static final class ProcessEventCollector {
        /// Background collector thread.
        private final Thread thread;

        /// Source stream consumed by the collector.
        private final InputStream input;

        /// Mutable event parser state.
        private final HelperEventState state;

        /// Failure raised while reading or parsing the stream.
        private @Nullable IOException failure;

        /// Starts an event collector.
        ///
        /// @param input input stream to drain.
        /// @param state parsed helper event state.
        /// @param name background thread name.
        /// @return started collector.
        private static ProcessEventCollector start(InputStream input, HelperEventState state, String name) {
            ProcessEventCollector collector = new ProcessEventCollector(input, state, name);
            collector.thread.start();
            return collector;
        }

        /// Creates an event collector.
        ///
        /// @param input input stream to drain.
        /// @param state parsed helper event state.
        /// @param name background thread name.
        private ProcessEventCollector(InputStream input, HelperEventState state, String name) {
            this.input = input;
            this.state = state;
            this.thread = new Thread(this::drain, name);
            this.thread.setDaemon(true);
        }

        /// Drains and parses the stream until EOF or read failure.
        private void drain() {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    state.accept(line);
                }
            } catch (IOException exception) {
                failure = exception;
            }
        }

        /// Waits for the collector to finish.
        ///
        /// @param command helper command for interruption diagnostics.
        /// @throws IOException when waiting is interrupted or stream parsing fails.
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
        /// SDK progress sinks keyed by helper operation.
        private final Map<String, ProgressSink> progressSinks;

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
        /// @param progressSinks SDK progress sinks keyed by helper operation.
        /// @param reporter SDK progress reporter.
        private HelperEventState(Map<String, ProgressSink> progressSinks, ProgressReporter reporter) {
            this.progressSinks = progressSinks;
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
                case "progress" -> {
                    String operation = event.path("operation").asText();
                    @Nullable ProgressSink sink = progressSinks.get(operation);
                    if (sink == null && progressSinks.size() == 1) {
                        sink = progressSinks.values().iterator().next();
                    }
                    if (sink == null) {
                        throw new IOException(SdkMessages.get("core.dd.unexpectedEvent", "progress:" + operation));
                    }
                    reporter.report(new ProgressEvent(
                            sink.stage(),
                            sink.message(),
                            event.path("currentBytes").asLong(),
                            event.path("totalBytes").asLong()));
                }
                case "complete" -> {
                    completed = true;
                    success = event.path("success").asBoolean(false);
                }
                case "error" -> helperError = event.path("message").asText(SdkMessages.get("core.dd.unknownFailure"));
                default -> throw new IOException(SdkMessages.get("core.dd.unexpectedEvent", type));
            }
        }
    }

    /// SDK progress sink for one helper operation.
    ///
    /// @param stage SDK progress stage.
    /// @param message SDK progress message.
    @NotNullByDefault
    private record ProgressSink(String stage, String message) {
    }
}

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
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
        List<String> command = command(operation, source, target, totalBytes);
        LOGGER.atInfo().log(() -> "Running dd-flasher helper. command=" + LogRedactor.redactCommand(command));
        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException exception) {
            throw new IOException(SdkMessages.get("core.dd.missingExecutable", executable), exception);
        }

        @Nullable String helperError = null;
        boolean completed = false;
        boolean success = false;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
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

        int exitCode;
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException(SdkMessages.get("core.dd.interrupted", commandText(command)), exception);
        }

        if (exitCode != 0) {
            String messageText = helperError == null ? readErrorText(process) : helperError;
            throw new IOException(SdkMessages.get("core.dd.commandFailed", exitCode, messageText));
        }
        if (!completed) {
            throw new IOException(SdkMessages.get("core.dd.missingResult"));
        }
        boolean result = success;
        LOGGER.atInfo().log(() -> "dd-flasher helper completed. operation=" + operation + ", success=" + result);
        return result;
    }

    /// Builds a helper command line.
    ///
    /// @param operation helper operation.
    /// @param source source image path.
    /// @param target target path.
    /// @param totalBytes source size.
    /// @return helper command line.
    private List<String> command(String operation, Path source, Path target, long totalBytes) {
        ArrayList<String> command = new ArrayList<>(8);
        command.add(executable);
        command.add(operation);
        command.add("--source");
        command.add(source.toString());
        command.add("--target");
        command.add(target.toString());
        command.add("--total-bytes");
        command.add(Long.toString(totalBytes));
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
}

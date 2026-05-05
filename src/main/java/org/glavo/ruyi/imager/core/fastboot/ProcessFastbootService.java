// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.fastboot;

import org.glavo.ruyi.imager.core.OperationResult;
import org.glavo.ruyi.imager.core.ProgressEvent;
import org.glavo.ruyi.imager.core.ProgressReporter;
import org.glavo.ruyi.imager.i18n.Messages;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/// Fastboot service backed by the platform `fastboot` executable.
@NotNullByDefault
public final class ProcessFastbootService implements FastbootService {
    /// Timeout used for fastboot device enumeration.
    private static final Duration DEVICES_TIMEOUT = Duration.ofSeconds(15);

    /// Timeout used for one fastboot flashing command.
    private static final Duration FLASH_TIMEOUT = Duration.ofMinutes(30);

    /// Maximum command output included in user-visible failures.
    private static final int MAX_OUTPUT_CHARS = 4000;

    /// Preferred partition flashing order for common Ruyi fastboot images.
    private static final @Unmodifiable List<String> PARTITION_ORDER = List.of("uboot", "boot", "root", "disk", "live");

    /// Fastboot executable name or path.
    private final String executable;

    /// Creates a service using `fastboot` from `PATH`.
    public ProcessFastbootService() {
        this("fastboot");
    }

    /// Creates a service using an explicit fastboot executable.
    ///
    /// @param executable executable name or path.
    public ProcessFastbootService(String executable) {
        this.executable = executable;
    }

    /// Lists devices currently visible to fastboot.
    ///
    /// @return immutable fastboot device list.
    /// @throws IOException when fastboot cannot be executed.
    @Override
    public @Unmodifiable List<FastbootDevice> listDevices() throws IOException {
        CommandResult result = runCommand(List.of(executable, "devices"), DEVICES_TIMEOUT);
        if (result.timedOut()) {
            throw new IOException(Messages.get("core.fastboot.timeout", commandText(List.of(executable, "devices"))));
        }
        if (result.exitCode() != 0) {
            throw new IOException(Messages.get(
                    "core.fastboot.commandFailed",
                    result.exitCode(),
                    commandText(List.of(executable, "devices")),
                    outputSummary(result.output())));
        }
        return parseDevices(result.output());
    }

    /// Flashes materialized image partitions through fastboot.
    ///
    /// @param strategy Ruyi provision strategy.
    /// @param partitions materialized partition images keyed by target partition name.
    /// @param device target fastboot device.
    /// @param reporter progress reporter.
    /// @return operation result.
    /// @throws IOException when fastboot cannot be executed.
    @Override
    public OperationResult flash(
            String strategy,
            @Unmodifiable Map<String, Path> partitions,
            FastbootDevice device,
            ProgressReporter reporter) throws IOException {
        if (partitions.isEmpty()) {
            return OperationResult.failure(Messages.get("core.fastboot.noPartitions"));
        }

        if ("fastboot-v1".equals(strategy)) {
            return flashStandard(partitions, device, reporter);
        }
        if ("fastboot-v1(lpi4a-uboot)".equals(strategy)) {
            return flashLpi4aUboot(partitions, device, reporter);
        }

        return OperationResult.failure(Messages.get("core.fastboot.unsupportedStrategy", strategy));
    }

    /// Parses `fastboot devices` output.
    ///
    /// @param output command output.
    /// @return immutable fastboot device list.
    static @Unmodifiable List<FastbootDevice> parseDevices(String output) {
        ArrayList<FastbootDevice> devices = new ArrayList<>();
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            String[] parts = trimmed.split("\\s+", 2);
            String serial = parts[0];
            String state = parts.length > 1 ? parts[1].trim() : "fastboot";
            devices.add(new FastbootDevice(serial, serial, state));
        }
        return List.copyOf(devices);
    }

    /// Flashes a standard fastboot-v1 image.
    ///
    /// @param partitions materialized partition images.
    /// @param device target fastboot device.
    /// @param reporter progress reporter.
    /// @return operation result.
    /// @throws IOException when fastboot cannot be executed.
    private OperationResult flashStandard(
            @Unmodifiable Map<String, Path> partitions,
            FastbootDevice device,
            ProgressReporter reporter) throws IOException {
        for (Map.Entry<String, Path> entry : orderedPartitions(partitions)) {
            String partition = entry.getKey();
            reporter.report(ProgressEvent.indeterminate(
                    "fastboot",
                    Messages.get("core.fastboot.flashingPartition", partition)));
            OperationResult result = runFastboot(device, List.of("flash", partition, entry.getValue().toString()));
            if (!result.success()) {
                return result;
            }
        }
        return OperationResult.success(Messages.get("core.fastboot.success"));
    }

    /// Flashes an LPi4A U-Boot image using the documented RAM handoff sequence.
    ///
    /// @param partitions materialized partition images.
    /// @param device target fastboot device.
    /// @param reporter progress reporter.
    /// @return operation result.
    /// @throws IOException when fastboot cannot be executed.
    private OperationResult flashLpi4aUboot(
            @Unmodifiable Map<String, Path> partitions,
            FastbootDevice device,
            ProgressReporter reporter) throws IOException {
        @Nullable Path uboot = partitions.get("uboot");
        if (uboot == null) {
            return OperationResult.failure(Messages.get("core.fastboot.missingPartition", "uboot"));
        }

        reporter.report(ProgressEvent.indeterminate("fastboot", Messages.get("core.fastboot.loadingLpi4aUboot")));
        OperationResult ramResult = runFastboot(device, List.of("flash", "ram", uboot.toString()));
        if (!ramResult.success()) {
            return ramResult;
        }

        reporter.report(ProgressEvent.indeterminate("fastboot", Messages.get("core.fastboot.rebooting")));
        OperationResult rebootResult = runFastboot(device, List.of("reboot"));
        if (!rebootResult.success()) {
            return rebootResult;
        }

        reporter.report(ProgressEvent.indeterminate("fastboot", Messages.get("core.fastboot.flashingPartition", "uboot")));
        OperationResult ubootResult = runFastboot(device, List.of("flash", "uboot", uboot.toString()));
        if (!ubootResult.success()) {
            return ubootResult;
        }

        return OperationResult.success(Messages.get("core.fastboot.success"));
    }

    /// Runs one fastboot command for a target device.
    ///
    /// @param device target fastboot device.
    /// @param arguments fastboot arguments after the serial selector.
    /// @return operation result.
    /// @throws IOException when fastboot cannot be executed.
    private OperationResult runFastboot(FastbootDevice device, @Unmodifiable List<String> arguments) throws IOException {
        ArrayList<String> command = new ArrayList<>(arguments.size() + 3);
        command.add(executable);
        command.add("-s");
        command.add(device.serial());
        command.addAll(arguments);

        CommandResult result = runCommand(List.copyOf(command), FLASH_TIMEOUT);
        String commandText = commandText(command);
        if (result.timedOut()) {
            return OperationResult.failure(Messages.get("core.fastboot.timeout", commandText));
        }
        if (result.exitCode() != 0) {
            return OperationResult.failure(Messages.get(
                    "core.fastboot.commandFailed",
                    result.exitCode(),
                    commandText,
                    outputSummary(result.output())));
        }
        return OperationResult.success(Messages.get("core.fastboot.commandSucceeded", commandText));
    }

    /// Runs a process and captures its combined output.
    ///
    /// @param command command line.
    /// @param timeout command timeout.
    /// @return command result.
    /// @throws IOException when the process cannot be started.
    private static CommandResult runCommand(@Unmodifiable List<String> command, Duration timeout) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new IOException(Messages.get("core.fastboot.missingExecutable", command.getFirst()), e);
        }

        StringBuilder output = new StringBuilder();
        Thread reader = new Thread(
                () -> readProcessOutput(process, output),
                "ruyi-imager-fastboot-output");
        reader.setDaemon(true);
        reader.start();

        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException(Messages.get("core.fastboot.interrupted", commandText(command)), e);
        }

        if (!finished) {
            process.destroyForcibly();
            joinReader(reader);
            return new CommandResult(-1, output.toString(), true);
        }

        joinReader(reader);
        return new CommandResult(process.exitValue(), output.toString(), false);
    }

    /// Reads process output into a shared buffer.
    ///
    /// @param process source process.
    /// @param output output buffer.
    private static void readProcessOutput(Process process, StringBuilder output) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(),
                StandardCharsets.UTF_8))) {
            while (true) {
                @Nullable String line = reader.readLine();
                if (line == null) {
                    break;
                }
                synchronized (output) {
                    output.append(line).append(System.lineSeparator());
                }
            }
        } catch (IOException e) {
            synchronized (output) {
                output.append(e.getMessage()).append(System.lineSeparator());
            }
        }
    }

    /// Waits briefly for the process output reader.
    ///
    /// @param reader reader thread.
    /// @throws IOException when interrupted while waiting.
    private static void joinReader(Thread reader) throws IOException {
        try {
            reader.join(1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(Messages.get("core.fastboot.outputInterrupted"), e);
        }
    }

    /// Orders partitions into the common fastboot sequence.
    ///
    /// @param partitions partition map.
    /// @return ordered entries.
    private static @Unmodifiable List<Map.Entry<String, Path>> orderedPartitions(@Unmodifiable Map<String, Path> partitions) {
        ArrayList<Map.Entry<String, Path>> entries = new ArrayList<>(partitions.entrySet());
        entries.sort(Comparator
                .comparingInt((Map.Entry<String, Path> entry) -> partitionOrder(entry.getKey()))
                .thenComparing(Map.Entry::getKey));
        return List.copyOf(entries);
    }

    /// Returns the preferred order index for one partition.
    ///
    /// @param partition partition name.
    /// @return order index.
    private static int partitionOrder(String partition) {
        int index = PARTITION_ORDER.indexOf(partition);
        return index < 0 ? PARTITION_ORDER.size() : index;
    }

    /// Formats a command line for diagnostics.
    ///
    /// @param command command arguments.
    /// @return command text.
    private static String commandText(@Unmodifiable List<String> command) {
        return String.join(" ", command);
    }

    /// Trims process output for user-visible errors.
    ///
    /// @param output raw process output.
    /// @return trimmed output.
    private static String outputSummary(String output) {
        String trimmed = output.trim();
        if (trimmed.isEmpty()) {
            return Messages.get("core.fastboot.noOutput");
        }
        if (trimmed.length() <= MAX_OUTPUT_CHARS) {
            return trimmed;
        }
        return trimmed.substring(0, MAX_OUTPUT_CHARS) + "...";
    }

    /// Captured command result.
    ///
    /// @param exitCode process exit code.
    /// @param output combined process output.
    /// @param timedOut whether the process timed out.
    @NotNullByDefault
    private record CommandResult(int exitCode, String output, boolean timedOut) {
    }
}

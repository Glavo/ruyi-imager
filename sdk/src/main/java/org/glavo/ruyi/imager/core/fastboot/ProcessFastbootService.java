// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.fastboot;

import org.glavo.ruyi.imager.core.OperationResult;
import org.glavo.ruyi.imager.core.ProgressEvent;
import org.glavo.ruyi.imager.core.ProgressReporter;
import org.glavo.ruyi.imager.core.SdkMessages;
import org.glavo.ruyi.imager.logging.LogRedactor;
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
import java.util.logging.Logger;

/// Fastboot service backed by a bundled or platform `fastboot` executable.
@NotNullByDefault
public final class ProcessFastbootService implements FastbootService {
    /// Logger for fastboot process operations.
    private static final Logger LOGGER = Logger.getLogger(ProcessFastbootService.class.getName());

    /// Timeout used for fastboot device enumeration.
    private static final Duration DEVICES_TIMEOUT = Duration.ofSeconds(15);

    /// Timeout used for one fastboot device polling command.
    private static final Duration DEVICE_POLL_TIMEOUT = Duration.ofSeconds(3);

    /// Maximum time to wait for a board to reconnect after rebooting.
    private static final Duration RECONNECT_TIMEOUT = Duration.ofSeconds(45);

    /// Delay between reconnect polling attempts.
    private static final Duration RECONNECT_POLL_INTERVAL = Duration.ofSeconds(1);

    /// Timeout used for one fastboot flashing command.
    private static final Duration FLASH_TIMEOUT = Duration.ofMinutes(30);

    /// Maximum command output included in user-visible failures.
    private static final int MAX_OUTPUT_CHARS = 4000;

    /// Preferred partition flashing order for common Ruyi fastboot images.
    private static final @Unmodifiable List<String> PARTITION_ORDER = List.of("uboot", "boot", "root", "disk", "live");

    /// Fastboot executable name or path.
    private final String executable;

    /// Command runner used for fastboot process execution.
    private final CommandRunner runner;

    /// Creates a service using bundled fastboot when available, otherwise `fastboot` from `PATH`.
    public ProcessFastbootService() {
        this(FastbootExecutableLocator.resolve());
    }

    /// Creates a service using an explicit fastboot executable.
    ///
    /// @param executable executable name or path.
    public ProcessFastbootService(String executable) {
        this(executable, ProcessFastbootService::runProcessCommand);
    }

    /// Creates a service with a custom command runner.
    ///
    /// @param executable executable name or path.
    /// @param runner command runner.
    ProcessFastbootService(String executable, CommandRunner runner) {
        this.executable = executable;
        this.runner = runner;
    }

    /// Lists devices currently visible to fastboot.
    ///
    /// @return immutable fastboot device list.
    /// @throws IOException when fastboot cannot be executed.
    @Override
    public @Unmodifiable List<FastbootDevice> listDevices() throws IOException {
        LOGGER.info(() -> "Listing fastboot devices. executable=" + executable);
        CommandResult result = runner.run(List.of(executable, "devices"), DEVICES_TIMEOUT);
        if (result.timedOut()) {
            LOGGER.warning("fastboot devices timed out.");
            throw new IOException(SdkMessages.get("core.fastboot.timeout", commandText(List.of(executable, "devices"))));
        }
        if (result.exitCode() != 0) {
            LOGGER.warning(() -> "fastboot devices failed. exitCode="
                    + result.exitCode()
                    + ", output="
                    + LogRedactor.redactOutput(result.output(), MAX_OUTPUT_CHARS));
            throw new IOException(SdkMessages.get(
                    "core.fastboot.commandFailed",
                    result.exitCode(),
                    commandText(List.of(executable, "devices")),
                    outputSummary(result.output())));
        }
        @Unmodifiable List<FastbootDevice> devices = parseDevices(result.output());
        LOGGER.info(() -> "fastboot devices listed. count=" + devices.size());
        return devices;
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
            return OperationResult.failure(SdkMessages.get("core.fastboot.noPartitions"));
        }

        if ("fastboot-v1".equals(strategy)) {
            return flashStandard(partitions, device, reporter);
        }
        if ("fastboot-v1(lpi4a-uboot)".equals(strategy)) {
            return flashLpi4aUboot(partitions, device, reporter);
        }

        return OperationResult.failure(SdkMessages.get("core.fastboot.unsupportedStrategy", strategy));
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
        @Unmodifiable List<Map.Entry<String, Path>> ordered = orderedPartitions(partitions);
        int totalSteps = ordered.size();
        for (int i = 0; i < ordered.size(); i++) {
            Map.Entry<String, Path> entry = ordered.get(i);
            String partition = entry.getKey();
            String message = SdkMessages.get("core.fastboot.flashingPartition", partition);
            reporter.report(progress(message, i, totalSteps));
            LOGGER.info(() -> "Flashing fastboot partition. serial="
                    + device.serial()
                    + ", partition="
                    + partition
                    + ", source="
                    + entry.getValue());
            OperationResult result = runFastboot(device, List.of("flash", partition, entry.getValue().toString()));
            if (!result.success()) {
                return result;
            }
            reporter.report(progress(message, i + 1, totalSteps));
        }
        return OperationResult.success(SdkMessages.get("core.fastboot.success"));
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
            return OperationResult.failure(SdkMessages.get("core.fastboot.missingPartition", "uboot"));
        }

        int totalSteps = 4;
        reporter.report(progress(SdkMessages.get("core.fastboot.loadingLpi4aUboot"), 0, totalSteps));
        OperationResult ramResult = runFastboot(device, List.of("flash", "ram", uboot.toString()));
        if (!ramResult.success()) {
            return ramResult;
        }
        reporter.report(progress(SdkMessages.get("core.fastboot.loadingLpi4aUboot"), 1, totalSteps));

        reporter.report(progress(SdkMessages.get("core.fastboot.rebooting"), 1, totalSteps));
        OperationResult rebootResult = runFastboot(device, List.of("reboot"));
        if (!rebootResult.success()) {
            return rebootResult;
        }
        reporter.report(progress(SdkMessages.get("core.fastboot.rebooting"), 2, totalSteps));

        OperationResult reconnectResult = waitForReconnect(device, reporter, 2, totalSteps);
        if (!reconnectResult.success()) {
            return reconnectResult;
        }

        String flashMessage = SdkMessages.get("core.fastboot.flashingPartition", "uboot");
        reporter.report(progress(flashMessage, 3, totalSteps));
        OperationResult ubootResult = runFastboot(device, List.of("flash", "uboot", uboot.toString()));
        if (!ubootResult.success()) {
            return ubootResult;
        }
        reporter.report(progress(flashMessage, 4, totalSteps));

        return OperationResult.success(SdkMessages.get("core.fastboot.success"));
    }

    /// Waits for a device to reconnect after a fastboot reboot.
    ///
    /// @param device target fastboot device.
    /// @param reporter progress reporter.
    /// @param completedSteps completed progress steps.
    /// @param totalSteps total progress steps.
    /// @return operation result.
    /// @throws IOException when fastboot cannot be executed or waiting is interrupted.
    private OperationResult waitForReconnect(
            FastbootDevice device,
            ProgressReporter reporter,
            int completedSteps,
            int totalSteps) throws IOException {
        long deadlineNanos = System.nanoTime() + RECONNECT_TIMEOUT.toNanos();
        String message = SdkMessages.get("core.fastboot.waitingReconnect", device.serial());
        while (System.nanoTime() < deadlineNanos) {
            reporter.report(progress(message, completedSteps, totalSteps));
            LOGGER.fine(() -> "Polling fastboot reconnect. serial=" + device.serial());
            CommandResult result = runner.run(List.of(executable, "devices"), DEVICE_POLL_TIMEOUT);
            if (!result.timedOut() && result.exitCode() == 0 && containsDevice(parseDevices(result.output()), device.serial())) {
                LOGGER.info(() -> "Fastboot device reconnected. serial=" + device.serial());
                reporter.report(progress(message, completedSteps + 1, totalSteps));
                return OperationResult.success(SdkMessages.get("core.fastboot.reconnected", device.serial()));
            }
            sleepReconnectPoll();
        }
        LOGGER.warning(() -> "Timed out waiting for fastboot reconnect. serial=" + device.serial());
        return OperationResult.failure(SdkMessages.get("core.fastboot.reconnectTimedOut", device.serial()));
    }

    /// Returns whether a parsed fastboot device list contains one serial.
    ///
    /// @param devices parsed devices.
    /// @param serial expected device serial.
    /// @return whether the serial is present.
    private static boolean containsDevice(@Unmodifiable List<FastbootDevice> devices, String serial) {
        for (FastbootDevice device : devices) {
            if (device.serial().equals(serial)) {
                return true;
            }
        }
        return false;
    }

    /// Sleeps between reconnect polling attempts.
    ///
    /// @throws IOException when interrupted.
    private static void sleepReconnectPoll() throws IOException {
        try {
            Thread.sleep(RECONNECT_POLL_INTERVAL.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(SdkMessages.get("core.fastboot.reconnectInterrupted"), e);
        }
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

        LOGGER.info(() -> "Running fastboot command. command=" + LogRedactor.redactCommand(command));
        CommandResult result = runner.run(List.copyOf(command), FLASH_TIMEOUT);
        String commandText = commandText(command);
        if (result.timedOut()) {
            LOGGER.warning(() -> "fastboot command timed out. command=" + LogRedactor.redactCommand(command));
            return OperationResult.failure(SdkMessages.get("core.fastboot.timeout", commandText));
        }
        if (result.exitCode() != 0) {
            LOGGER.warning(() -> "fastboot command failed. command="
                    + LogRedactor.redactCommand(command)
                    + ", exitCode="
                    + result.exitCode()
                    + ", output="
                    + LogRedactor.redactOutput(result.output(), MAX_OUTPUT_CHARS));
            return OperationResult.failure(SdkMessages.get(
                    "core.fastboot.commandFailed",
                    result.exitCode(),
                    commandText,
                    outputSummary(result.output())));
        }
        LOGGER.info(() -> "fastboot command completed. command=" + LogRedactor.redactCommand(command));
        return OperationResult.success(SdkMessages.get("core.fastboot.commandSucceeded", commandText));
    }

    /// Runs a process and captures its combined output.
    ///
    /// @param command command line.
    /// @param timeout command timeout.
    /// @return command result.
    /// @throws IOException when the process cannot be started.
    private static CommandResult runProcessCommand(@Unmodifiable List<String> command, Duration timeout) throws IOException {
        LOGGER.fine(() -> "Starting process. command=" + LogRedactor.redactCommand(command) + ", timeout=" + timeout);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            LOGGER.warning(() -> "Failed to start process. command=" + LogRedactor.redactCommand(command));
            throw new IOException(SdkMessages.get("core.fastboot.missingExecutable", command.getFirst()), e);
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
            destroyProcess(process, reader);
            LOGGER.warning(() -> "Interrupted while running process. command=" + LogRedactor.redactCommand(command));
            throw new IOException(SdkMessages.get("core.fastboot.interrupted", commandText(command)), e);
        }

        if (!finished) {
            destroyProcess(process, reader);
            LOGGER.warning(() -> "Process timed out. command=" + LogRedactor.redactCommand(command));
            return new CommandResult(-1, output.toString(), true);
        }

        joinReader(reader);
        CommandResult result = new CommandResult(process.exitValue(), output.toString(), false);
        LOGGER.fine(() -> "Process completed. command="
                + LogRedactor.redactCommand(command)
                + ", exitCode="
                + result.exitCode()
                + ", output="
                + LogRedactor.redactOutput(result.output(), MAX_OUTPUT_CHARS));
        return result;
    }

    /// Destroys a process and waits briefly for output cleanup.
    ///
    /// @param process process to destroy.
    /// @param reader output reader thread.
    /// @throws IOException when interrupted while waiting for output cleanup.
    private static void destroyProcess(Process process, Thread reader) throws IOException {
        process.destroyForcibly();
        joinReader(reader);
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
            throw new IOException(SdkMessages.get("core.fastboot.outputInterrupted"), e);
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

    /// Creates a determinate fastboot progress event.
    ///
    /// @param message progress message.
    /// @param currentStep completed step count.
    /// @param totalSteps total step count.
    /// @return progress event.
    private static ProgressEvent progress(String message, int currentStep, int totalSteps) {
        return new ProgressEvent("fastboot", message, (long) currentStep, (long) totalSteps);
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
            return SdkMessages.get("core.fastboot.noOutput");
        }
        if (trimmed.length() <= MAX_OUTPUT_CHARS) {
            return LogRedactor.redactText(trimmed);
        }
        return LogRedactor.redactOutput(trimmed, MAX_OUTPUT_CHARS);
    }

    /// Runs one command with a timeout.
    @FunctionalInterface
    @NotNullByDefault
    interface CommandRunner {
        /// Executes a command.
        ///
        /// @param command command line.
        /// @param timeout command timeout.
        /// @return command result.
        /// @throws IOException when the command cannot be executed.
        CommandResult run(@Unmodifiable List<String> command, Duration timeout) throws IOException;
    }

    /// Captured command result.
    ///
    /// @param exitCode process exit code.
    /// @param output combined process output.
    /// @param timedOut whether the process timed out.
    @NotNullByDefault
    record CommandResult(int exitCode, String output, boolean timedOut) {
    }
}

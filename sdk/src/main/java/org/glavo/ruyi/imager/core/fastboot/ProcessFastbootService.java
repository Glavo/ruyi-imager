// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.fastboot;

import org.glavo.ruyi.imager.core.OperationResult;
import org.glavo.ruyi.imager.core.ProgressEvent;
import org.glavo.ruyi.imager.core.ProgressReporter;
import org.glavo.ruyi.imager.core.ProvisionStrategies;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Fastboot service backed by a bundled or platform `fastboot` executable.
@NotNullByDefault
public final class ProcessFastbootService implements FastbootService {
    /// Logger for fastboot process operations.
    private static final Logger LOGGER = LoggerFactory.getLogger(ProcessFastbootService.class);

    /// Timeout used for fastboot device enumeration.
    private static final Duration DEVICES_TIMEOUT = Duration.ofSeconds(15);

    /// Timeout used for long-running fastboot flashing commands.
    private static final Duration FLASH_TIMEOUT = Duration.ofHours(4);

    /// Delay between SpacemiT K1 bootloader handoff stages.
    private static final Duration SPACEMIT_K1_HANDOFF_DELAY = Duration.ofSeconds(1);

    /// Delay after LPi4A jumps from RAM-loaded U-Boot into target U-Boot fastboot.
    private static final Duration LPI4A_UBOOT_HANDOFF_DELAY = Duration.ofSeconds(1);

    /// Maximum command output included in user-visible failures.
    private static final int MAX_OUTPUT_CHARS = 4000;

    /// Required SpacemiT K1 eMMC partitions in the flashing order used by Ruyi.
    private static final @Unmodifiable List<String> SPACEMIT_K1_PARTITION_ORDER =
            List.of("gpt", "bootinfo", "fsbl", "env", "opensbi", "uboot", "bootfs", "rootfs");

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
        LOGGER.atInfo().log(() -> "Listing fastboot devices. executable=" + executable);
        CommandResult result = runner.run(List.of(executable, "devices"), DEVICES_TIMEOUT);
        if (result.timedOut()) {
            LOGGER.warn("fastboot devices timed out.");
            throw new IOException(SdkMessages.get("core.fastboot.timeout", commandText(List.of(executable, "devices"))));
        }
        if (result.exitCode() != 0) {
            LOGGER.atWarn().log(() -> "fastboot devices failed. exitCode="
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
        LOGGER.atInfo().log(() -> "fastboot devices listed. count=" + devices.size());
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

        if (ProvisionStrategies.FASTBOOT_V1.equals(strategy)) {
            return flashStandard(partitions, device, reporter);
        }
        if (ProvisionStrategies.FASTBOOT_LPI4A_UBOOT_V1.equals(strategy)) {
            return flashLpi4aUboot(partitions, device, reporter);
        }
        if (ProvisionStrategies.SPACEMIT_K1_V1.equals(strategy)) {
            return flashSpacemitK1(partitions, device, reporter);
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
        @Unmodifiable List<Map.Entry<String, Path>> ordered = List.copyOf(partitions.entrySet());
        int totalSteps = ordered.size();
        for (int i = 0; i < ordered.size(); i++) {
            Map.Entry<String, Path> entry = ordered.get(i);
            String partition = entry.getKey();
            String message = SdkMessages.get("core.fastboot.flashingPartition", partition);
            reporter.report(progress(message, i, totalSteps));
            LOGGER.atInfo().log(() -> "Flashing fastboot partition. serial="
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

    /// Flashes a SpacemiT K1 eMMC image using the Bianbu fastboot handoff sequence.
    ///
    /// @param partitions materialized partition images.
    /// @param device target fastboot device.
    /// @param reporter progress reporter.
    /// @return operation result.
    /// @throws IOException when fastboot cannot be executed.
    private OperationResult flashSpacemitK1(
            @Unmodifiable Map<String, Path> partitions,
            FastbootDevice device,
            ProgressReporter reporter) throws IOException {
        @Nullable String missingPartition = missingPartition(partitions, SPACEMIT_K1_PARTITION_ORDER);
        if (missingPartition != null) {
            return OperationResult.failure(SdkMessages.get("core.fastboot.missingPartition", missingPartition));
        }

        Path fsbl = Objects.requireNonNull(partitions.get("fsbl"));
        Path uboot = Objects.requireNonNull(partitions.get("uboot"));
        int totalSteps = SPACEMIT_K1_PARTITION_ORDER.size() + 6;
        int completedSteps = 0;

        String stageFsblMessage = SdkMessages.get("core.fastboot.spacemit.stageFsbl");
        reporter.report(progress(stageFsblMessage, completedSteps, totalSteps));
        OperationResult result = runFastboot(device, List.of("stage", fsbl.toString()));
        if (!result.success()) {
            return result;
        }
        reporter.report(progress(stageFsblMessage, ++completedSteps, totalSteps));

        String continueFsblMessage = SdkMessages.get("core.fastboot.spacemit.continueFsbl");
        reporter.report(progress(continueFsblMessage, completedSteps, totalSteps));
        result = runFastboot(device, List.of("continue"));
        if (!result.success()) {
            return result;
        }
        reporter.report(progress(continueFsblMessage, ++completedSteps, totalSteps));

        String waitFsblMessage = SdkMessages.get("core.fastboot.spacemit.waitFsbl");
        reporter.report(progress(waitFsblMessage, completedSteps, totalSteps));
        sleepSpacemitK1Handoff();
        reporter.report(progress(waitFsblMessage, ++completedSteps, totalSteps));

        String stageUbootMessage = SdkMessages.get("core.fastboot.spacemit.stageUboot");
        reporter.report(progress(stageUbootMessage, completedSteps, totalSteps));
        result = runFastboot(device, List.of("stage", uboot.toString()));
        if (!result.success()) {
            return result;
        }
        reporter.report(progress(stageUbootMessage, ++completedSteps, totalSteps));

        String continueUbootMessage = SdkMessages.get("core.fastboot.spacemit.continueUboot");
        reporter.report(progress(continueUbootMessage, completedSteps, totalSteps));
        result = runFastboot(device, List.of("continue"));
        if (!result.success()) {
            return result;
        }
        reporter.report(progress(continueUbootMessage, ++completedSteps, totalSteps));

        String waitUbootMessage = SdkMessages.get("core.fastboot.spacemit.waitUboot");
        reporter.report(progress(waitUbootMessage, completedSteps, totalSteps));
        sleepSpacemitK1Handoff();
        reporter.report(progress(waitUbootMessage, ++completedSteps, totalSteps));

        for (String partition : SPACEMIT_K1_PARTITION_ORDER) {
            Path image = Objects.requireNonNull(partitions.get(partition));
            String flashMessage = SdkMessages.get("core.fastboot.flashingPartition", partition);
            reporter.report(progress(flashMessage, completedSteps, totalSteps));
            result = runFastboot(device, List.of("flash", partition, image.toString()));
            if (!result.success()) {
                return result;
            }
            reporter.report(progress(flashMessage, ++completedSteps, totalSteps));
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

        String waitMessage = SdkMessages.get("core.fastboot.waitingReconnect", device.serial());
        reporter.report(progress(waitMessage, 2, totalSteps));
        sleepLpi4aUbootHandoff();
        reporter.report(progress(waitMessage, 3, totalSteps));

        String flashMessage = SdkMessages.get("core.fastboot.flashingPartition", "uboot");
        reporter.report(progress(flashMessage, 3, totalSteps));
        OperationResult ubootResult = runFastbootAnyDevice(List.of("flash", "uboot", uboot.toString()));
        if (!ubootResult.success()) {
            return ubootResult;
        }
        reporter.report(progress(flashMessage, 4, totalSteps));

        return OperationResult.success(SdkMessages.get("core.fastboot.success"));
    }

    /// Sleeps between SpacemiT K1 handoff stages.
    ///
    /// @throws IOException when interrupted.
    private static void sleepSpacemitK1Handoff() throws IOException {
        try {
            Thread.sleep(SPACEMIT_K1_HANDOFF_DELAY.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(SdkMessages.get("core.fastboot.spacemit.handoffInterrupted"), e);
        }
    }

    /// Sleeps after LPi4A reboot enters the RAM-loaded U-Boot.
    ///
    /// @throws IOException when interrupted.
    private static void sleepLpi4aUbootHandoff() throws IOException {
        try {
            Thread.sleep(LPI4A_UBOOT_HANDOFF_DELAY.toMillis());
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

        LOGGER.atInfo().log(() -> "Running fastboot command. command=" + LogRedactor.redactCommand(command));
        CommandResult result = runner.run(List.copyOf(command), FLASH_TIMEOUT);
        String commandText = commandText(command);
        if (result.timedOut()) {
            LOGGER.atWarn().log(() -> "fastboot command timed out. command=" + LogRedactor.redactCommand(command));
            return OperationResult.failure(SdkMessages.get("core.fastboot.timeout", commandText));
        }
        if (result.exitCode() != 0) {
            LOGGER.atWarn().log(() -> "fastboot command failed. command="
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
        LOGGER.atInfo().log(() -> "fastboot command completed. command=" + LogRedactor.redactCommand(command));
        return OperationResult.success(SdkMessages.get("core.fastboot.commandSucceeded", commandText));
    }

    /// Runs one fastboot command without a serial selector.
    ///
    /// @param arguments fastboot arguments.
    /// @return operation result.
    /// @throws IOException when fastboot cannot be executed.
    private OperationResult runFastbootAnyDevice(@Unmodifiable List<String> arguments) throws IOException {
        ArrayList<String> command = new ArrayList<>(arguments.size() + 1);
        command.add(executable);
        command.addAll(arguments);

        LOGGER.atInfo().log(() -> "Running fastboot command. command=" + LogRedactor.redactCommand(command));
        CommandResult result = runner.run(List.copyOf(command), FLASH_TIMEOUT);
        String commandText = commandText(command);
        if (result.timedOut()) {
            LOGGER.atWarn().log(() -> "fastboot command timed out. command=" + LogRedactor.redactCommand(command));
            return OperationResult.failure(SdkMessages.get("core.fastboot.timeout", commandText));
        }
        if (result.exitCode() != 0) {
            LOGGER.atWarn().log(() -> "fastboot command failed. command="
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
        LOGGER.atInfo().log(() -> "fastboot command completed. command=" + LogRedactor.redactCommand(command));
        return OperationResult.success(SdkMessages.get("core.fastboot.commandSucceeded", commandText));
    }

    /// Runs a process and captures its combined output.
    ///
    /// @param command command line.
    /// @param timeout command timeout.
    /// @return command result.
    /// @throws IOException when the process cannot be started.
    private static CommandResult runProcessCommand(@Unmodifiable List<String> command, Duration timeout) throws IOException {
        LOGGER.atDebug().log(() -> "Starting process. command=" + LogRedactor.redactCommand(command) + ", timeout=" + timeout);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            LOGGER.atWarn().log(() -> "Failed to start process. command=" + LogRedactor.redactCommand(command));
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
            LOGGER.atWarn().log(() -> "Interrupted while running process. command=" + LogRedactor.redactCommand(command));
            throw new IOException(SdkMessages.get("core.fastboot.interrupted", commandText(command)), e);
        }

        if (!finished) {
            destroyProcess(process, reader);
            LOGGER.atWarn().log(() -> "Process timed out. command=" + LogRedactor.redactCommand(command));
            return new CommandResult(-1, output.toString(), true);
        }

        joinReader(reader);
        CommandResult result = new CommandResult(process.exitValue(), output.toString(), false);
        LOGGER.atDebug().log(() -> "Process completed. command="
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

    /// Finds the first missing required partition.
    ///
    /// @param partitions partition map.
    /// @param requiredPartitions required partition names.
    /// @return first missing partition name, or null when all are present.
    private static @Nullable String missingPartition(
            @Unmodifiable Map<String, Path> partitions,
            @Unmodifiable List<String> requiredPartitions) {
        for (String partition : requiredPartitions) {
            if (!partitions.containsKey(partition)) {
                return partition;
            }
        }
        return null;
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

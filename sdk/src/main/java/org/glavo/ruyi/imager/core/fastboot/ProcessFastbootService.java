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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    /// Timeout used for one fastboot device polling command.
    private static final Duration DEVICE_POLL_TIMEOUT = Duration.ofSeconds(3);

    /// Maximum time to wait for LPi4A to expose U-Boot fastboot after reboot.
    private static final Duration LPI4A_UBOOT_RECONNECT_TIMEOUT = Duration.ofSeconds(45);

    /// Delay between LPi4A U-Boot fastboot polling attempts.
    private static final Duration LPI4A_UBOOT_RECONNECT_POLL_INTERVAL = Duration.ofSeconds(1);

    /// Timeout used for long-running fastboot flashing commands.
    private static final Duration FLASH_TIMEOUT = Duration.ofHours(4);

    /// Delay between SpacemiT K1 bootloader handoff stages.
    private static final Duration SPACEMIT_K1_HANDOFF_DELAY = Duration.ofSeconds(1);

    /// Delay after LPi4A jumps from RAM-loaded U-Boot into target U-Boot fastboot.
    private static final Duration LPI4A_UBOOT_HANDOFF_DELAY = Duration.ofSeconds(1);

    /// Maximum command output included in user-visible failures.
    private static final int MAX_OUTPUT_CHARS = 4000;

    /// Progress units used for one high-level fastboot command step.
    private static final long FASTBOOT_PROGRESS_UNITS = 1000L;

    /// Fastboot output reported when the selected device does not support the LPi4A RAM handoff target.
    private static final String LPI4A_RAM_TARGET_MISSING_OUTPUT = "remote: 'cannot find partition'";

    /// Pattern for fastboot sparse send completion output.
    private static final Pattern FASTBOOT_SENDING_SPARSE_PATTERN =
            Pattern.compile("^Sending\\s+sparse\\s+'([^']+)'\\s+(\\d+)/(\\d+).*");

    /// Pattern for fastboot non-sparse send completion output.
    private static final Pattern FASTBOOT_SENDING_PATTERN =
            Pattern.compile("^Sending\\s+'([^']+)'.*");

    /// Pattern for fastboot write output.
    private static final Pattern FASTBOOT_WRITING_PATTERN =
            Pattern.compile("^Writing\\s+'([^']+)'.*");

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
        this(executable, ProcessCommandRunner.INSTANCE);
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
            OperationResult result = runFastboot(
                    device,
                    List.of("flash", partition, entry.getValue().toString()),
                    new FastbootCommandProgress(reporter, i, totalSteps));
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
        OperationResult result = runFastboot(
                device,
                List.of("stage", fsbl.toString()),
                new FastbootCommandProgress(reporter, completedSteps, totalSteps));
        if (!result.success()) {
            return result;
        }
        reporter.report(progress(stageFsblMessage, ++completedSteps, totalSteps));

        String continueFsblMessage = SdkMessages.get("core.fastboot.spacemit.continueFsbl");
        reporter.report(progress(continueFsblMessage, completedSteps, totalSteps));
        result = runFastboot(
                device,
                List.of("continue"),
                new FastbootCommandProgress(reporter, completedSteps, totalSteps));
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
        result = runFastboot(
                device,
                List.of("stage", uboot.toString()),
                new FastbootCommandProgress(reporter, completedSteps, totalSteps));
        if (!result.success()) {
            return result;
        }
        reporter.report(progress(stageUbootMessage, ++completedSteps, totalSteps));

        String continueUbootMessage = SdkMessages.get("core.fastboot.spacemit.continueUboot");
        reporter.report(progress(continueUbootMessage, completedSteps, totalSteps));
        result = runFastboot(
                device,
                List.of("continue"),
                new FastbootCommandProgress(reporter, completedSteps, totalSteps));
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
            result = runFastboot(
                    device,
                    List.of("flash", partition, image.toString()),
                    new FastbootCommandProgress(reporter, completedSteps, totalSteps));
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
        @Unmodifiable Set<String> preHandoffOtherSerials = readOtherFastbootSerials(device);

        reporter.report(progress(SdkMessages.get("core.fastboot.loadingLpi4aUboot"), 0, totalSteps));
        OperationResult ramResult = runFastboot(
                device,
                List.of("flash", "ram", uboot.toString()),
                new FastbootCommandProgress(reporter, 0, totalSteps));
        if (!ramResult.success()) {
            if (isLpi4aRamTargetMissing(ramResult.message())) {
                return OperationResult.failure(SdkMessages.get(
                        "core.fastboot.lpi4aRamTargetMissing",
                        ramResult.message()));
            }
            return ramResult;
        }
        reporter.report(progress(SdkMessages.get("core.fastboot.loadingLpi4aUboot"), 1, totalSteps));
        preHandoffOtherSerials = mergeSerials(preHandoffOtherSerials, readOtherFastbootSerials(device));

        reporter.report(progress(SdkMessages.get("core.fastboot.rebooting"), 1, totalSteps));
        OperationResult rebootResult = runFastboot(
                device,
                List.of("reboot"),
                new FastbootCommandProgress(reporter, 1, totalSteps));
        if (!rebootResult.success()) {
            return rebootResult;
        }
        reporter.report(progress(SdkMessages.get("core.fastboot.rebooting"), 2, totalSteps));

        String waitMessage = SdkMessages.get("core.fastboot.waitingReconnect", device.serial());
        reporter.report(progress(waitMessage, 2, totalSteps));
        sleepLpi4aUbootHandoff();
        ResolvedFastbootDevice resolvedDevice =
                resolveLpi4aUbootDevice(device, preHandoffOtherSerials, reporter, 2, totalSteps);
        if (!resolvedDevice.success()) {
            return OperationResult.failure(resolvedDevice.message());
        }
        reporter.report(progress(waitMessage, 3, totalSteps));

        String flashMessage = SdkMessages.get("core.fastboot.flashingPartition", "uboot");
        reporter.report(progress(flashMessage, 3, totalSteps));
        OperationResult ubootResult = runFastboot(
                Objects.requireNonNull(resolvedDevice.device()),
                List.of("flash", "uboot", uboot.toString()),
                new FastbootCommandProgress(reporter, 3, totalSteps));
        if (!ubootResult.success()) {
            return ubootResult;
        }
        reporter.report(progress(flashMessage, 4, totalSteps));

        return OperationResult.success(SdkMessages.get("core.fastboot.success"));
    }

    /// Resolves the fastboot device visible after LPi4A jumps into RAM-loaded U-Boot.
    ///
    /// @param originalDevice device selected before the handoff.
    /// @param preHandoffOtherSerials serials that were already visible before the handoff except the selected device.
    /// @param reporter progress reporter.
    /// @param completedSteps completed progress steps.
    /// @param totalSteps total progress steps.
    /// @return resolved device result.
    /// @throws IOException when fastboot cannot be executed or waiting is interrupted.
    private ResolvedFastbootDevice resolveLpi4aUbootDevice(
            FastbootDevice originalDevice,
            @Unmodifiable Set<String> preHandoffOtherSerials,
            ProgressReporter reporter,
            int completedSteps,
            int totalSteps) throws IOException {
        long deadlineNanos = System.nanoTime() + LPI4A_UBOOT_RECONNECT_TIMEOUT.toNanos();
        String message = SdkMessages.get("core.fastboot.waitingReconnect", originalDevice.serial());
        while (System.nanoTime() < deadlineNanos) {
            reporter.report(progress(message, completedSteps, totalSteps));
            LOGGER.atDebug().log(() -> "Polling LPi4A U-Boot fastboot device. serial=" + originalDevice.serial());
            CommandResult result = runner.run(List.of(executable, "devices"), DEVICE_POLL_TIMEOUT);
            if (result.timedOut()) {
                sleepLpi4aUbootReconnectPoll();
                continue;
            }
            if (result.exitCode() != 0) {
                return ResolvedFastbootDevice.failure(SdkMessages.get(
                        "core.fastboot.commandFailed",
                        result.exitCode(),
                        commandText(List.of(executable, "devices")),
                        outputSummary(result.output())));
            }

            @Unmodifiable List<FastbootDevice> devices = parseDevices(result.output());
            @Nullable FastbootDevice sameSerial = findDeviceBySerial(devices, originalDevice.serial());
            if (sameSerial != null) {
                LOGGER.atInfo().log(() -> "LPi4A U-Boot fastboot device kept the selected serial. serial=" + sameSerial.serial());
                return ResolvedFastbootDevice.success(sameSerial);
            }

            @Unmodifiable List<FastbootDevice> changedSerialCandidates =
                    changedSerialCandidates(devices, preHandoffOtherSerials);
            if (changedSerialCandidates.size() == 1) {
                FastbootDevice changedDevice = changedSerialCandidates.getFirst();
                LOGGER.atInfo().log(() -> "LPi4A U-Boot fastboot device changed serial. oldSerial="
                        + originalDevice.serial()
                        + ", newSerial="
                        + changedDevice.serial());
                return ResolvedFastbootDevice.success(changedDevice);
            }
            if (changedSerialCandidates.size() > 1) {
                LOGGER.atWarn().log(() -> "Multiple fastboot devices are visible after LPi4A U-Boot handoff. count="
                        + changedSerialCandidates.size());
                return ResolvedFastbootDevice.failure(
                        SdkMessages.get("core.fastboot.reconnectAmbiguous", changedSerialCandidates.size()));
            }

            sleepLpi4aUbootReconnectPoll();
        }

        LOGGER.atWarn().log(() -> "Timed out waiting for LPi4A U-Boot fastboot device. serial=" + originalDevice.serial());
        return ResolvedFastbootDevice.failure(SdkMessages.get("core.fastboot.reconnectTimedOut", originalDevice.serial()));
    }

    /// Reads fastboot serials that were present before LPi4A U-Boot handoff except the selected device.
    ///
    /// @param selectedDevice selected LPi4A fastboot device.
    /// @return immutable serial set to exclude after serial-changing handoff.
    /// @throws IOException when fastboot cannot be executed or device enumeration fails.
    private @Unmodifiable Set<String> readOtherFastbootSerials(FastbootDevice selectedDevice) throws IOException {
        LOGGER.atInfo().log(() -> "Capturing pre-handoff fastboot devices. serial=" + selectedDevice.serial());
        CommandResult result = runner.run(List.of(executable, "devices"), DEVICE_POLL_TIMEOUT);
        if (result.timedOut()) {
            LOGGER.warn("Pre-handoff fastboot devices command timed out.");
            throw new IOException(SdkMessages.get("core.fastboot.timeout", commandText(List.of(executable, "devices"))));
        }
        if (result.exitCode() != 0) {
            LOGGER.atWarn().log(() -> "Pre-handoff fastboot devices command failed. exitCode="
                    + result.exitCode()
                    + ", output="
                    + LogRedactor.redactOutput(result.output(), MAX_OUTPUT_CHARS));
            throw new IOException(SdkMessages.get(
                    "core.fastboot.commandFailed",
                    result.exitCode(),
                    commandText(List.of(executable, "devices")),
                    outputSummary(result.output())));
        }

        HashSet<String> serials = new HashSet<>();
        for (FastbootDevice visibleDevice : parseDevices(result.output())) {
            if (!visibleDevice.serial().equals(selectedDevice.serial())) {
                serials.add(visibleDevice.serial());
            }
        }
        @Unmodifiable Set<String> excludedSerials = Set.copyOf(serials);
        LOGGER.atInfo().log(() -> "Captured pre-handoff non-target fastboot devices. count=" + excludedSerials.size());
        return excludedSerials;
    }

    /// Merges immutable serial sets.
    ///
    /// @param first first serial set.
    /// @param second second serial set.
    /// @return immutable merged serial set.
    private static @Unmodifiable Set<String> mergeSerials(
            @Unmodifiable Set<String> first,
            @Unmodifiable Set<String> second) {
        HashSet<String> merged = new HashSet<>(first);
        merged.addAll(second);
        return Set.copyOf(merged);
    }

    /// Filters devices to serials that were not already visible before LPi4A U-Boot handoff.
    ///
    /// @param devices currently visible devices.
    /// @param preHandoffOtherSerials serials to exclude.
    /// @return immutable changed-serial candidates.
    private static @Unmodifiable List<FastbootDevice> changedSerialCandidates(
            @Unmodifiable List<FastbootDevice> devices,
            @Unmodifiable Set<String> preHandoffOtherSerials) {
        ArrayList<FastbootDevice> candidates = new ArrayList<>();
        for (FastbootDevice device : devices) {
            if (!preHandoffOtherSerials.contains(device.serial())) {
                candidates.add(device);
            }
        }
        return List.copyOf(candidates);
    }

    /// Finds a fastboot device by serial.
    ///
    /// @param devices devices to inspect.
    /// @param serial serial to find.
    /// @return matching device, or null when absent.
    private static @Nullable FastbootDevice findDeviceBySerial(@Unmodifiable List<FastbootDevice> devices, String serial) {
        for (FastbootDevice device : devices) {
            if (device.serial().equals(serial)) {
                return device;
            }
        }
        return null;
    }

    /// Returns whether a failed LPi4A `flash ram` command reported a missing RAM target.
    ///
    /// @param message fastboot failure message.
    /// @return whether the selected fastboot device rejected the RAM handoff target.
    private static boolean isLpi4aRamTargetMissing(String message) {
        return message.contains(LPI4A_RAM_TARGET_MISSING_OUTPUT);
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

    /// Sleeps between LPi4A U-Boot fastboot polling attempts.
    ///
    /// @throws IOException when interrupted.
    private static void sleepLpi4aUbootReconnectPoll() throws IOException {
        try {
            Thread.sleep(LPI4A_UBOOT_RECONNECT_POLL_INTERVAL.toMillis());
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
        return runFastboot(device, arguments, null);
    }

    /// Runs one fastboot command for a target device and streams output into progress when available.
    ///
    /// @param device target fastboot device.
    /// @param arguments fastboot arguments after the serial selector.
    /// @param commandProgress progress tracker for this fastboot command, or null when not tracked.
    /// @return operation result.
    /// @throws IOException when fastboot cannot be executed.
    private OperationResult runFastboot(
            FastbootDevice device,
            @Unmodifiable List<String> arguments,
            @Nullable FastbootCommandProgress commandProgress) throws IOException {
        ArrayList<String> command = new ArrayList<>(arguments.size() + 3);
        command.add(executable);
        command.add("-s");
        command.add(device.serial());
        command.addAll(arguments);

        LOGGER.atInfo().log(() -> "Running fastboot command. command=" + LogRedactor.redactCommand(command));
        CommandResult result = runner.run(
                List.copyOf(command),
                FLASH_TIMEOUT,
                commandProgress == null ? _ -> {
                } : commandProgress::acceptOutput);
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
    private static CommandResult runProcessCommand(
            @Unmodifiable List<String> command,
            Duration timeout,
            Consumer<String> outputListener) throws IOException {
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
                () -> readProcessOutput(process, output, outputListener),
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
    /// @param outputListener listener receiving each output line.
    private static void readProcessOutput(Process process, StringBuilder output, Consumer<String> outputListener) {
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
                notifyOutputListener(outputListener, line);
            }
        } catch (IOException e) {
            String errorMessage = Objects.toString(e.getMessage(), e.toString());
            synchronized (output) {
                output.append(errorMessage).append(System.lineSeparator());
            }
            notifyOutputListener(outputListener, errorMessage);
        }
    }

    /// Notifies a process output listener without letting listener failures stop output draining.
    ///
    /// @param outputListener listener to notify.
    /// @param line output line.
    private static void notifyOutputListener(Consumer<String> outputListener, String line) {
        try {
            outputListener.accept(line);
        } catch (RuntimeException exception) {
            LOGGER.debug("Fastboot output listener failed.", exception);
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
        return new ProgressEvent("fastboot", message, progressUnits(currentStep), progressUnits(totalSteps));
    }

    /// Converts a high-level fastboot step count into progress units.
    ///
    /// @param steps high-level step count.
    /// @return progress units.
    private static long progressUnits(long steps) {
        return steps * FASTBOOT_PROGRESS_UNITS;
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

        /// Executes a command and streams process output when supported.
        ///
        /// @param command command line.
        /// @param timeout command timeout.
        /// @param outputListener listener receiving command output.
        /// @return command result.
        /// @throws IOException when the command cannot be executed.
        default CommandResult run(
                @Unmodifiable List<String> command,
                Duration timeout,
                Consumer<String> outputListener) throws IOException {
            CommandResult result = run(command, timeout);
            outputListener.accept(result.output());
            return result;
        }
    }

    /// Real process command runner with streaming output support.
    @NotNullByDefault
    private enum ProcessCommandRunner implements CommandRunner {
        /// Singleton process command runner.
        INSTANCE;

        /// Executes a process command.
        ///
        /// @param command command line.
        /// @param timeout command timeout.
        /// @return command result.
        /// @throws IOException when the command cannot be executed.
        @Override
        public CommandResult run(@Unmodifiable List<String> command, Duration timeout) throws IOException {
            return runProcessCommand(command, timeout, _ -> {
            });
        }

        /// Executes a process command and streams output.
        ///
        /// @param command command line.
        /// @param timeout command timeout.
        /// @param outputListener listener receiving command output.
        /// @return command result.
        /// @throws IOException when the command cannot be executed.
        @Override
        public CommandResult run(
                @Unmodifiable List<String> command,
                Duration timeout,
                Consumer<String> outputListener) throws IOException {
            return runProcessCommand(command, timeout, outputListener);
        }
    }

    /// Parses fastboot command output and reports sub-step progress.
    @NotNullByDefault
    private static final class FastbootCommandProgress {
        /// Reporter receiving parsed progress updates.
        private final ProgressReporter reporter;

        /// Base progress units for this command.
        private final long baseUnits;

        /// Total progress units for the whole fastboot operation.
        private final long totalUnits;

        /// Progress units assigned to this command.
        private final long stepUnits;

        /// Progress units assigned to the sending phase.
        private final long sendingUnits;

        /// Sparse partition currently being sent.
        private @Nullable String sparsePartition;

        /// Current sparse chunk number.
        private long sparseCurrentChunk;

        /// Total sparse chunk count.
        private long sparseTotalChunks;

        /// Creates a command progress parser.
        ///
        /// @param reporter progress reporter.
        /// @param completedSteps high-level steps completed before this command.
        /// @param totalSteps high-level steps in the whole operation.
        private FastbootCommandProgress(ProgressReporter reporter, int completedSteps, int totalSteps) {
            this.reporter = reporter;
            this.baseUnits = progressUnits(completedSteps);
            this.totalUnits = progressUnits(totalSteps);
            this.stepUnits = FASTBOOT_PROGRESS_UNITS;
            this.sendingUnits = FASTBOOT_PROGRESS_UNITS / 2L;
        }

        /// Parses command output.
        ///
        /// @param output command output chunk or complete output.
        private void acceptOutput(String output) {
            for (String line : output.split("\\R")) {
                acceptLine(line);
            }
        }

        /// Parses one command output line.
        ///
        /// @param rawLine raw output line.
        private void acceptLine(String rawLine) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                return;
            }

            Matcher sparseMatcher = FASTBOOT_SENDING_SPARSE_PATTERN.matcher(line);
            if (sparseMatcher.matches()) {
                reportSparseSending(sparseMatcher);
                return;
            }

            Matcher sendingMatcher = FASTBOOT_SENDING_PATTERN.matcher(line);
            if (sendingMatcher.matches()) {
                report(
                        SdkMessages.get("core.fastboot.sendingPartition", sendingMatcher.group(1)),
                        baseUnits + sendingUnits);
                return;
            }

            Matcher writingMatcher = FASTBOOT_WRITING_PATTERN.matcher(line);
            if (writingMatcher.matches()) {
                String partition = writingMatcher.group(1);
                long currentUnits = writingProgressUnits(partition, line.contains("OKAY"));
                report(SdkMessages.get("core.fastboot.writingPartition", partition), currentUnits);
            }
        }

        /// Reports parsed sparse sending progress.
        ///
        /// @param matcher sparse sending output matcher.
        private void reportSparseSending(Matcher matcher) {
            long currentChunk;
            long totalChunks;
            try {
                currentChunk = Long.parseLong(matcher.group(2));
                totalChunks = Long.parseLong(matcher.group(3));
            } catch (NumberFormatException exception) {
                LOGGER.debug("Failed to parse fastboot sparse progress.", exception);
                return;
            }
            if (totalChunks <= 0L) {
                return;
            }

            long boundedChunk = Math.max(0L, Math.min(currentChunk, totalChunks));
            sparsePartition = matcher.group(1);
            sparseCurrentChunk = boundedChunk;
            sparseTotalChunks = totalChunks;
            long currentUnits = baseUnits + sparseSendingProgressUnits(boundedChunk, totalChunks);
            report(
                    SdkMessages.get(
                            "core.fastboot.sendingSparsePartition",
                            sparsePartition,
                            boundedChunk,
                            totalChunks),
                    currentUnits);
        }

        /// Computes sparse sending progress units for a chunk.
        ///
        /// @param currentChunk current sparse chunk number.
        /// @param totalChunks total sparse chunk count.
        /// @return command-relative progress units.
        private long sparseSendingProgressUnits(long currentChunk, long totalChunks) {
            if (currentChunk <= 0L || totalChunks <= 0L) {
                return 0L;
            }
            return stepUnits * ((currentChunk * 2L) - 1L) / (totalChunks * 2L);
        }

        /// Computes writing progress units.
        ///
        /// @param partition partition being written.
        /// @param complete whether the write line reports completion.
        /// @return absolute progress units.
        private long writingProgressUnits(String partition, boolean complete) {
            if (sparsePartition != null && sparsePartition.equals(partition) && sparseTotalChunks > 0L) {
                if (complete) {
                    return baseUnits + (stepUnits * sparseCurrentChunk / sparseTotalChunks);
                }
                return baseUnits + sparseSendingProgressUnits(sparseCurrentChunk, sparseTotalChunks);
            }
            return complete ? baseUnits + stepUnits : baseUnits + sendingUnits;
        }

        /// Reports one parsed progress event.
        ///
        /// @param message progress message.
        /// @param currentUnits current progress units.
        private void report(String message, long currentUnits) {
            reporter.report(new ProgressEvent("fastboot", message, currentUnits, totalUnits));
        }
    }

    /// Result of resolving the LPi4A U-Boot fastboot target after handoff.
    ///
    /// @param device resolved device when successful.
    /// @param message failure message when unsuccessful.
    @NotNullByDefault
    private record ResolvedFastbootDevice(@Nullable FastbootDevice device, String message) {
        /// Creates a successful resolution result.
        ///
        /// @param device resolved fastboot device.
        /// @return successful resolution result.
        private static ResolvedFastbootDevice success(FastbootDevice device) {
            return new ResolvedFastbootDevice(device, "");
        }

        /// Creates a failed resolution result.
        ///
        /// @param message failure message.
        /// @return failed resolution result.
        private static ResolvedFastbootDevice failure(String message) {
            return new ResolvedFastbootDevice(null, message);
        }

        /// Returns whether resolution succeeded.
        ///
        /// @return whether a device was resolved.
        private boolean success() {
            return device != null;
        }
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

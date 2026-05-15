// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.device;

import org.glavo.ruyi.imager.core.ProgressEvent;
import org.glavo.ruyi.imager.core.ProgressReporter;
import org.glavo.ruyi.imager.core.flash.BlockDevicePreparer;
import org.glavo.ruyi.imager.core.ProcessOutputCapture;
import org.glavo.ruyi.imager.core.SdkMessages;
import org.glavo.ruyi.imager.logging.LogRedactor;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Windows block-device preparer that dismounts target disk volumes before writing.
@NotNullByDefault
public final class WindowsBlockDevicePreparer implements BlockDevicePreparer {
    /// Logger for Windows block-device preparation.
    private static final Logger LOGGER = LoggerFactory.getLogger(WindowsBlockDevicePreparer.class);

    /// Maximum time allowed for preparing one Windows disk.
    private static final Duration PREPARE_TIMEOUT = Duration.ofSeconds(30);

    /// Pattern for Windows block-device ids.
    private static final Pattern WINDOWS_DISK_ID = Pattern.compile("windows-disk-(\\d+)");

    /// Pattern for Windows raw physical drive paths.
    private static final Pattern PHYSICAL_DRIVE_PATH = Pattern.compile("(?i).*PHYSICALDRIVE(\\d+)$");

    /// PowerShell script body used after `$diskNumber` is assigned.
    private static final String PREPARE_SCRIPT_BODY = """
            $ErrorActionPreference = 'Stop'
            [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
            $OutputEncoding = [System.Text.Encoding]::UTF8

            $disk = Get-Disk -Number $diskNumber
            if ($disk.IsBoot -or $disk.IsSystem) {
                throw 'Refusing to prepare a system disk.'
            }
            if ($disk.IsReadOnly) {
                throw 'Refusing to prepare a read-only disk.'
            }

            foreach ($partition in (Get-Partition -DiskNumber $diskNumber)) {
                try {
                    $volume = $partition | Get-Volume -ErrorAction Stop
                    if ($null -ne $volume) {
                        $volume | Dismount-Volume -Force -Confirm:$false -ErrorAction Stop
                    }
                } catch {
                }

                foreach ($accessPath in @($partition.AccessPaths)) {
                    if ($accessPath) {
                        try {
                            Remove-PartitionAccessPath `
                                -DiskNumber $diskNumber `
                                -PartitionNumber $partition.PartitionNumber `
                                -AccessPath $accessPath `
                                -ErrorAction Stop
                        } catch {
                        }
                    }
                }
            }

            Write-Output 'prepared'
            """;

    /// Command runner used to execute PowerShell.
    private final CommandRunner runner;

    /// Creates the production Windows preparer.
    public WindowsBlockDevicePreparer() {
        this(WindowsBlockDevicePreparer::runProcess);
    }

    /// Creates a Windows preparer with an injectable command runner.
    ///
    /// @param runner command runner.
    WindowsBlockDevicePreparer(CommandRunner runner) {
        this.runner = runner;
    }

    /// Returns whether a mounted Windows disk can be automatically dismounted before writing.
    ///
    /// @param target target block device.
    /// @return whether this target can be prepared.
    @Override
    public boolean canPrepareMounted(BlockDevice target) {
        return target.mounted() && target.removable() && diskNumber(target) != null;
    }

    /// Prepares a mounted Windows physical disk for writing.
    ///
    /// @param target target block device.
    /// @param reporter progress reporter.
    /// @return target metadata with mount state cleared when preparation succeeds.
    /// @throws IOException when PowerShell preparation fails.
    @Override
    public BlockDevice prepare(BlockDevice target, ProgressReporter reporter) throws IOException {
        if (!target.mounted()) {
            LOGGER.atDebug().log(() -> "Windows target is already unmounted. target=" + target.path());
            return target;
        }

        if (!target.removable()) {
            LOGGER.atInfo().log(() -> "Windows mounted target is not removable; leaving mounted. target=" + target.path());
            return target;
        }

        @Nullable Integer diskNumber = diskNumber(target);
        if (diskNumber == null) {
            LOGGER.atInfo().log(() -> "Windows target disk number could not be resolved. target=" + target.path());
            return target;
        }

        reporter.report(new ProgressEvent(
                "prepare",
                SdkMessages.get("core.flash.preparingTarget", target.displayName()),
                0L,
                1L));

        CommandResult result;
        try {
            LOGGER.atInfo().log(() -> "Preparing Windows disk for writing. diskNumber=" + diskNumber);
            result = runner.run(command(diskNumber), PREPARE_TIMEOUT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.atWarn().log(() -> "Windows disk preparation interrupted. diskNumber=" + diskNumber);
            throw new IOException(SdkMessages.get("core.device.windowsPrepareInterrupted"), e);
        }

        if (result.timedOut()) {
            LOGGER.atWarn().log(() -> "Windows disk preparation timed out. diskNumber=" + diskNumber);
            throw new IOException(SdkMessages.get("core.device.windowsPrepareTimedOut"));
        }
        if (result.exitCode() != 0) {
            String message = result.error().isBlank()
                    ? SdkMessages.get("core.device.powershellExit", result.exitCode())
                    : result.error().strip();
            LOGGER.atWarn().log(() -> "Windows disk preparation failed. diskNumber="
                    + diskNumber
                    + ", exitCode="
                    + result.exitCode()
                    + ", output="
                    + LogRedactor.redactOutput(result.output(), 1000)
                    + ", error="
                    + LogRedactor.redactOutput(result.error(), 1000));
            throw new IOException(SdkMessages.get("core.device.windowsPrepareFailed", diskNumber, message));
        }

        LOGGER.atInfo().log(() -> "Windows disk prepared. diskNumber=" + diskNumber);
        reporter.report(new ProgressEvent(
                "prepare",
                SdkMessages.get("core.flash.preparedTarget", target.displayName()),
                1L,
                1L));
        return unmounted(target);
    }

    /// Builds the PowerShell command for preparing one disk.
    ///
    /// @param diskNumber Windows disk number.
    /// @return immutable command argument list.
    private static @Unmodifiable List<String> command(int diskNumber) {
        return List.of(
                "powershell.exe",
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy",
                "Bypass",
                "-Command",
                "$diskNumber = " + diskNumber + System.lineSeparator() + PREPARE_SCRIPT_BODY);
    }

    /// Runs one command with a timeout.
    ///
    /// @param command command argument list.
    /// @param timeout process timeout.
    /// @return command result.
    /// @throws IOException when the process cannot start or streams cannot be read.
    /// @throws InterruptedException when waiting is interrupted.
    private static CommandResult runProcess(
            @Unmodifiable List<String> command,
            Duration timeout) throws IOException, InterruptedException {
        ProcessOutputCapture.Result result = ProcessOutputCapture.run(command, timeout);
        return new CommandResult(result.exitCode(), result.output(), result.error(), result.timedOut());
    }

    /// Returns target metadata with mounted state and mount points cleared.
    ///
    /// @param target target metadata.
    /// @return unmounted target metadata.
    private static BlockDevice unmounted(BlockDevice target) {
        return new BlockDevice(
                target.id(),
                target.displayName(),
                target.path(),
                target.sizeBytes(),
                target.removable(),
                target.system(),
                false,
                target.readOnly(),
                target.model(),
                target.busType(),
                List.of());
    }

    /// Extracts the Windows disk number from target metadata.
    ///
    /// @param target target metadata.
    /// @return disk number, or null when the target is not a recognized Windows disk.
    private static @Nullable Integer diskNumber(BlockDevice target) {
        @Nullable Integer idDiskNumber = diskNumber(target.id(), WINDOWS_DISK_ID);
        if (idDiskNumber != null) {
            return idDiskNumber;
        }
        return diskNumber(target.path().toString(), PHYSICAL_DRIVE_PATH);
    }

    /// Extracts a disk number with the supplied pattern.
    ///
    /// @param value value to match.
    /// @param pattern pattern with one integer group.
    /// @return parsed disk number, or null.
    private static @Nullable Integer diskNumber(String value, Pattern pattern) {
        Matcher matcher = pattern.matcher(value);
        if (!matcher.matches()) {
            return null;
        }
        try {
            return Integer.valueOf(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /// Runs an external command.
    @FunctionalInterface
    interface CommandRunner {
        /// Runs one command with a timeout.
        ///
        /// @param command command argument list.
        /// @param timeout command timeout.
        /// @return command result.
        /// @throws IOException when execution fails.
        /// @throws InterruptedException when waiting is interrupted.
        CommandResult run(@Unmodifiable List<String> command, Duration timeout) throws IOException, InterruptedException;
    }

    /// External command result.
    ///
    /// @param exitCode process exit code.
    /// @param output standard output text.
    /// @param error standard error text.
    /// @param timedOut whether the process exceeded its timeout.
    record CommandResult(int exitCode, String output, String error, boolean timedOut) {
    }
}

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.flash;

import org.glavo.ruyi.imager.core.ProcessOutputCapture;
import org.glavo.ruyi.imager.core.ProgressEvent;
import org.glavo.ruyi.imager.core.ProgressReporter;
import org.glavo.ruyi.imager.core.SdkMessages;
import org.glavo.ruyi.imager.core.device.BlockDevice;
import org.glavo.ruyi.imager.logging.LogRedactor;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// macOS block-device preparer that unmounts mounted removable target disks.
@NotNullByDefault
public final class MacOSBlockDevicePreparer implements BlockDevicePreparer {
    /// Logger for macOS target preparation.
    private static final Logger LOGGER = LoggerFactory.getLogger(MacOSBlockDevicePreparer.class);

    /// Maximum time allowed for one preparation command.
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(30);

    /// Maximum diagnostic text included in user-facing command failures.
    private static final int MAX_DIAGNOSTIC_CHARS = 1000;

    /// Command runner used by production code and tests.
    private final CommandRunner commandRunner;

    /// Creates a macOS preparer backed by real external commands.
    public MacOSBlockDevicePreparer() {
        this(ProcessOutputCapture::run);
    }

    /// Creates a macOS preparer with an injected command runner.
    ///
    /// @param commandRunner command runner.
    MacOSBlockDevicePreparer(CommandRunner commandRunner) {
        this.commandRunner = Objects.requireNonNull(commandRunner, "commandRunner");
    }

    /// Returns whether macOS mounted target preparation is available.
    ///
    /// @param target target block device.
    /// @return whether this target can be prepared.
    @Override
    public boolean canPrepareMounted(BlockDevice target) {
        String path = target.path().toString().replace('\\', '/');
        return target.mounted()
                && target.removable()
                && !target.system()
                && target.id().startsWith("macos-disk-")
                && (path.startsWith("/dev/disk") || path.startsWith("/dev/rdisk"));
    }

    /// Returns whether this target should be prepared.
    ///
    /// @param target target block device.
    /// @return whether preparation should run.
    @Override
    public boolean shouldPrepare(BlockDevice target) {
        return canPrepareMounted(target);
    }

    /// Unmounts a mounted macOS target disk.
    ///
    /// @param target target block device.
    /// @param reporter progress reporter.
    /// @return target metadata with mounted state cleared.
    /// @throws IOException when the disk cannot be unmounted.
    @Override
    public BlockDevice prepare(BlockDevice target, ProgressReporter reporter) throws IOException {
        if (!canPrepareMounted(target)) {
            return target;
        }

        reporter.report(ProgressEvent.indeterminate(
                "prepare",
                SdkMessages.get("core.flash.preparingTarget", target.displayName())));

        List<String> command = List.of("diskutil", "unmountDisk", target.path().toString());
        LOGGER.atInfo().log(() -> "Preparing macOS block target. target="
                + target.path()
                + ", command="
                + LogRedactor.redactCommand(command));
        runUnmountCommand(command, target);

        reporter.report(new ProgressEvent(
                "prepare",
                SdkMessages.get("core.flash.preparedTarget", target.displayName()),
                1L,
                1L));
        return unmountedTarget(target);
    }

    /// Runs one unmount command and validates its exit status.
    ///
    /// @param command command line.
    /// @param target target block device.
    /// @throws IOException when the command fails.
    private void runUnmountCommand(@Unmodifiable List<String> command, BlockDevice target) throws IOException {
        ProcessOutputCapture.Result result = run(command);
        if (result.timedOut()) {
            throw new IOException(SdkMessages.get("core.flash.commandTimedOut", LogRedactor.redactCommand(command)));
        }
        if (result.exitCode() != 0) {
            throw new IOException(SdkMessages.get(
                    "core.flash.unmountFailed",
                    target.displayName(),
                    diagnosticText(command, result)));
        }
    }

    /// Runs one external command with the macOS preparation timeout.
    ///
    /// @param command command line.
    /// @return captured command result.
    /// @throws IOException when the process cannot be started.
    private ProcessOutputCapture.Result run(@Unmodifiable List<String> command) throws IOException {
        try {
            return commandRunner.run(command, COMMAND_TIMEOUT);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException(SdkMessages.get("core.flash.unmountFailed", LogRedactor.redactCommand(command), "interrupted"), exception);
        }
    }

    /// Returns an unmounted copy of a target.
    ///
    /// @param target original target.
    /// @return copied target with mounted state cleared.
    private static BlockDevice unmountedTarget(BlockDevice target) {
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
                target.hardwareId(),
                List.of());
    }

    /// Formats a process diagnostic.
    ///
    /// @param command command line.
    /// @param result process result.
    /// @return diagnostic text.
    private static String diagnosticText(
            @Unmodifiable List<String> command,
            ProcessOutputCapture.Result result) {
        String text = result.error().isBlank() ? result.output() : result.error();
        if (text.isBlank()) {
            return SdkMessages.get("core.device.commandExit", LogRedactor.redactCommand(command), result.exitCode());
        }
        return LogRedactor.redactOutput(text.strip(), MAX_DIAGNOSTIC_CHARS);
    }

    /// Runs an external command.
    @FunctionalInterface
    @NotNullByDefault
    interface CommandRunner {
        /// Runs one command with a timeout.
        ///
        /// @param command command line.
        /// @param timeout command timeout.
        /// @return captured process result.
        /// @throws IOException when the process cannot start or streams cannot be read.
        /// @throws InterruptedException when command waiting is interrupted.
        ProcessOutputCapture.Result run(@Unmodifiable List<String> command, Duration timeout)
                throws IOException, InterruptedException;
    }
}

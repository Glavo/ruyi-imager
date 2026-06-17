// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.flash;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Linux block-device preparer that unmounts mounted removable target partitions.
@NotNullByDefault
public final class LinuxBlockDevicePreparer implements BlockDevicePreparer {
    /// Logger for Linux target preparation.
    private static final Logger LOGGER = LoggerFactory.getLogger(LinuxBlockDevicePreparer.class);

    /// JSON mapper used for `lsblk` output.
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /// Maximum time allowed for one preparation command.
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(30);

    /// Maximum diagnostic text included in user-facing command failures.
    private static final int MAX_DIAGNOSTIC_CHARS = 1000;

    /// Command runner used by production code and tests.
    private final CommandRunner commandRunner;

    /// Creates a Linux preparer backed by real external commands.
    public LinuxBlockDevicePreparer() {
        this(ProcessOutputCapture::run);
    }

    /// Creates a Linux preparer with an injected command runner.
    ///
    /// @param commandRunner command runner.
    LinuxBlockDevicePreparer(CommandRunner commandRunner) {
        this.commandRunner = Objects.requireNonNull(commandRunner, "commandRunner");
    }

    /// Returns whether Linux mounted target preparation is available.
    ///
    /// @param target target block device.
    /// @return whether this target can be prepared.
    @Override
    public boolean canPrepareMounted(BlockDevice target) {
        return target.mounted()
                && target.removable()
                && !target.system()
                && target.id().startsWith("linux-disk-")
                && target.path().toString().replace('\\', '/').startsWith("/dev/");
    }

    /// Returns whether this target should be prepared.
    ///
    /// @param target target block device.
    /// @return whether preparation should run.
    @Override
    public boolean shouldPrepare(BlockDevice target) {
        return canPrepareMounted(target);
    }

    /// Unmounts all mounted partitions under the selected Linux disk.
    ///
    /// @param target target block device.
    /// @param reporter progress reporter.
    /// @return target metadata with mounted state cleared.
    /// @throws IOException when mounted partitions cannot be discovered or unmounted.
    @Override
    public BlockDevice prepare(BlockDevice target, ProgressReporter reporter) throws IOException {
        if (!canPrepareMounted(target)) {
            return target;
        }

        reporter.report(new ProgressEvent(
                "prepare",
                SdkMessages.get("core.flash.preparingTarget", target.displayName()),
                0L,
                1L));

        @Unmodifiable List<MountEntry> entries = mountedEntries(target.path());
        LOGGER.atInfo().log(() -> "Preparing Linux block target. target="
                + target.path()
                + ", mountedEntries="
                + entries.size());
        for (MountEntry entry : entries) {
            unmountEntry(target, entry);
        }

        reporter.report(new ProgressEvent(
                "prepare",
                SdkMessages.get("core.flash.preparedTarget", target.displayName()),
                1L,
                1L));
        return unmountedTarget(target);
    }

    /// Enumerates mounted block nodes under a target disk.
    ///
    /// @param target target disk path.
    /// @return mounted block entries.
    /// @throws IOException when `lsblk` cannot enumerate the target.
    private @Unmodifiable List<MountEntry> mountedEntries(Path target) throws IOException {
        List<String> command = List.of(
                "lsblk",
                "--json",
                "--bytes",
                "--output",
                "PATH,TYPE,MOUNTPOINT,MOUNTPOINTS",
                target.toString());
        LOGGER.atDebug().log(() -> "Enumerating Linux mounted target entries. command="
                + LogRedactor.redactCommand(command));
        ProcessOutputCapture.Result result = run(command);
        if (result.timedOut()) {
            throw new IOException(SdkMessages.get("core.device.enumerationTimedOut", "Linux"));
        }
        if (result.exitCode() != 0) {
            throw new IOException(SdkMessages.get("core.device.enumerationFailed", "Linux", diagnosticText(command, result)));
        }
        return parseMountedEntries(result.output());
    }

    /// Parses mounted entries from `lsblk` JSON.
    ///
    /// @param json JSON text.
    /// @return mounted block entries.
    /// @throws IOException when the JSON text is invalid.
    static @Unmodifiable List<MountEntry> parseMountedEntries(String json) throws IOException {
        if (json.isBlank()) {
            return List.of();
        }

        JsonNode root = MAPPER.readTree(json);
        if (root == null || root.isNull()) {
            return List.of();
        }

        @Nullable JsonNode blockDevices = root.get("blockdevices");
        if (blockDevices == null || !blockDevices.isArray()) {
            return List.of();
        }

        ArrayList<MountEntry> entries = new ArrayList<>();
        Iterator<JsonNode> iterator = blockDevices.elements();
        while (iterator.hasNext()) {
            collectMountedEntries(iterator.next(), entries);
        }
        return List.copyOf(entries);
    }

    /// Collects mounted entries recursively from one `lsblk` node.
    ///
    /// @param node current JSON node.
    /// @param entries mutable entry list.
    private static void collectMountedEntries(JsonNode node, ArrayList<MountEntry> entries) {
        @Unmodifiable List<String> mountPoints = mountPoints(node);
        if (!mountPoints.isEmpty()) {
            entries.add(new MountEntry(nullableTextValue(node, "path"), mountPoints));
        }

        @Nullable JsonNode children = node.get("children");
        if (children != null && children.isArray()) {
            Iterator<JsonNode> iterator = children.elements();
            while (iterator.hasNext()) {
                collectMountedEntries(iterator.next(), entries);
            }
        }
    }

    /// Unmounts one mounted block entry.
    ///
    /// @param target target disk.
    /// @param entry mounted entry.
    /// @throws IOException when the entry cannot be unmounted.
    private void unmountEntry(BlockDevice target, MountEntry entry) throws IOException {
        @Nullable IOException primaryFailure = null;
        @Nullable String path = entry.path();
        if (path != null) {
            List<String> command = List.of("udisksctl", "unmount", "-b", path);
            try {
                runUnmountCommand(command);
                LOGGER.atInfo().log(() -> "Unmounted Linux block entry with udisksctl. target="
                        + target.path()
                        + ", entry="
                        + path);
                return;
            } catch (IOException exception) {
                primaryFailure = exception;
                LOGGER.atWarn().log(() -> "udisksctl failed while unmounting Linux block entry. target="
                        + target.path()
                        + ", entry="
                        + path
                        + ", error="
                        + LogRedactor.redactText(exceptionMessage(exception)));
            }
        }

        if (entry.mountPoints().isEmpty()) {
            throw unmountFailure(entry, primaryFailure);
        }

        for (String mountPoint : mountPointsForUnmount(entry.mountPoints())) {
            List<String> command = List.of("umount", mountPoint);
            try {
                runUnmountCommand(command);
                LOGGER.atInfo().log(() -> "Unmounted Linux mount point. target="
                        + target.path()
                        + ", mountPoint="
                        + mountPoint);
            } catch (IOException exception) {
                if (primaryFailure != null) {
                    exception.addSuppressed(primaryFailure);
                }
                throw unmountFailure(entry, exception);
            }
        }
    }

    /// Runs one unmount command and validates its exit status.
    ///
    /// @param command command line.
    /// @throws IOException when the command fails.
    private void runUnmountCommand(@Unmodifiable List<String> command) throws IOException {
        LOGGER.atDebug().log(() -> "Running Linux unmount command. command=" + LogRedactor.redactCommand(command));
        ProcessOutputCapture.Result result = run(command);
        if (result.timedOut()) {
            throw new IOException(SdkMessages.get("core.flash.commandTimedOut", LogRedactor.redactCommand(command)));
        }
        if (result.exitCode() != 0) {
            throw new IOException(diagnosticText(command, result));
        }
    }

    /// Runs one external command with the Linux preparation timeout.
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

    /// Builds a failure for one mounted entry.
    ///
    /// @param entry mounted entry.
    /// @param cause command failure.
    /// @return unmount failure.
    private static IOException unmountFailure(MountEntry entry, @Nullable IOException cause) {
        String reason = cause == null ? "no mount path is available" : exceptionMessage(cause);
        return new IOException(SdkMessages.get("core.flash.unmountFailed", entry.displayName(), reason), cause);
    }

    /// Returns a safe exception message.
    ///
    /// @param exception source exception.
    /// @return exception message or fallback text.
    private static String exceptionMessage(IOException exception) {
        @Nullable String message = exception.getMessage();
        return message == null ? exception.getClass().getSimpleName() : message;
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

    /// Sorts mount points so nested paths are unmounted first.
    ///
    /// @param mountPoints mount points.
    /// @return sorted mount points.
    private static @Unmodifiable List<String> mountPointsForUnmount(@Unmodifiable List<String> mountPoints) {
        return mountPoints.stream()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
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

    /// Collects mount points from one `lsblk` node.
    ///
    /// @param node JSON node.
    /// @return mount point list.
    private static @Unmodifiable List<String> mountPoints(JsonNode node) {
        ArrayList<String> result = new ArrayList<>();
        addMountPointField(node, "mountpoint", result);
        addMountPointField(node, "mountpoints", result);
        return List.copyOf(result);
    }

    /// Adds mount point values from one JSON field.
    ///
    /// @param node JSON object.
    /// @param fieldName field name.
    /// @param result mutable result list.
    private static void addMountPointField(JsonNode node, String fieldName, ArrayList<String> result) {
        @Nullable JsonNode value = node.get(fieldName);
        if (value == null || value.isNull()) {
            return;
        }
        if (value.isArray()) {
            Iterator<JsonNode> iterator = value.elements();
            while (iterator.hasNext()) {
                addMountPointValue(iterator.next(), result);
            }
            return;
        }
        addMountPointValue(value, result);
    }

    /// Adds one mount point JSON value to a list.
    ///
    /// @param value JSON value.
    /// @param result mutable result list.
    private static void addMountPointValue(JsonNode value, ArrayList<String> result) {
        if (value.isNull()) {
            return;
        }

        String text = value.asText();
        if (!text.isBlank()) {
            String stripped = text.strip();
            if (!result.contains(stripped)) {
                result.add(stripped);
            }
        }
    }

    /// Reads a nullable non-blank string field.
    ///
    /// @param node JSON object.
    /// @param fieldName field name.
    /// @return trimmed field value, or null.
    private static @Nullable String nullableTextValue(JsonNode node, String fieldName) {
        @Nullable JsonNode value = node.get(fieldName);
        if (value == null || value.isNull()) {
            return null;
        }

        String text = value.asText();
        if (text.isBlank()) {
            return null;
        }
        return text.strip();
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

    /// Mounted block entry discovered from `lsblk`.
    ///
    /// @param path block device path when available.
    /// @param mountPoints mounted filesystem paths.
    @NotNullByDefault
    record MountEntry(@Nullable String path, @Unmodifiable List<String> mountPoints) {
        /// Copies mutable mount point input.
        MountEntry {
            mountPoints = List.copyOf(mountPoints);
        }

        /// Returns a display name for diagnostics.
        ///
        /// @return path or mount point label.
        String displayName() {
            if (path != null) {
                return path;
            }
            return String.join(", ", mountPoints);
        }
    }
}

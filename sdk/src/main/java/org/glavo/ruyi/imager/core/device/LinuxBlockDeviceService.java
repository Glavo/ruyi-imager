// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.device;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.glavo.ruyi.imager.core.ProcessOutputCapture;
import org.glavo.ruyi.imager.core.SdkMessages;
import org.glavo.ruyi.imager.logging.LogRedactor;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Linux block-device enumerator backed by read-only `lsblk` JSON output.
@NotNullByDefault
public final class LinuxBlockDeviceService implements BlockDeviceService {
    /// Logger for Linux device enumeration.
    private static final Logger LOGGER = LoggerFactory.getLogger(LinuxBlockDeviceService.class);

    /// JSON mapper used for `lsblk` output.
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /// Maximum time allowed for one device enumeration.
    private static final Duration ENUMERATION_TIMEOUT = Duration.ofSeconds(20);

    /// Lists physical disks through `lsblk` and converts them to block devices.
    ///
    /// @return immutable list of physical disks.
    /// @throws IOException when `lsblk` or JSON parsing fails.
    @Override
    public @Unmodifiable List<BlockDevice> listDevices() throws IOException {
        LOGGER.info("Enumerating Linux block devices with lsblk.");
        List<String> command = List.of(
                "lsblk",
                "--json",
                "--bytes",
                "--output",
                "NAME,KNAME,PATH,TYPE,SIZE,RM,RO,MOUNTPOINT,MOUNTPOINTS,MODEL,TRAN,SERIAL,WWN,HOTPLUG");

        ProcessOutputCapture.Result result;
        try {
            result = ProcessOutputCapture.run(command, ENUMERATION_TIMEOUT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Linux block-device enumeration interrupted.");
            throw new IOException(SdkMessages.get("core.device.enumerationInterrupted", "Linux"), e);
        }

        if (result.timedOut()) {
            LOGGER.warn("Linux block-device enumeration timed out.");
            throw new IOException(SdkMessages.get("core.device.enumerationTimedOut", "Linux"));
        }

        String output = result.output();
        String error = result.error();
        if (result.exitCode() != 0) {
            String message = error.isBlank() ? SdkMessages.get("core.device.commandExit", "lsblk", result.exitCode()) : error.strip();
            LOGGER.atWarn().log(() -> "Linux block-device enumeration failed. exitCode="
                    + result.exitCode()
                    + ", error="
                    + LogRedactor.redactOutput(error, 1000));
            throw new IOException(SdkMessages.get("core.device.enumerationFailed", "Linux", message));
        }

        @Unmodifiable List<BlockDevice> devices = parseDevices(output);
        LOGGER.atInfo().log(() -> "Linux block devices enumerated. count=" + devices.size());
        return devices;
    }

    /// Parses JSON emitted by `lsblk --json`.
    ///
    /// @param json JSON text.
    /// @return immutable parsed block-device list.
    /// @throws IOException when the JSON text is invalid.
    static @Unmodifiable List<BlockDevice> parseDevices(String json) throws IOException {
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

        ArrayList<BlockDevice> devices = new ArrayList<>();
        Iterator<JsonNode> iterator = blockDevices.elements();
        while (iterator.hasNext()) {
            @Nullable BlockDevice device = parseDevice(iterator.next());
            if (device != null) {
                devices.add(device);
            }
        }
        return List.copyOf(devices);
    }

    /// Parses one `lsblk` disk object.
    ///
    /// @param node JSON disk object.
    /// @return parsed block device, or null for non-disk objects.
    private static @Nullable BlockDevice parseDevice(JsonNode node) {
        if (!"disk".equals(textValue(node, "type", ""))) {
            return null;
        }

        String name = textValue(node, "kname", textValue(node, "name", ""));
        if (name.isBlank()) {
            return null;
        }

        String pathText = textValue(node, "path", "/dev/" + name);
        long sizeBytes = longValue(node, "size", 0L);
        boolean readOnly = booleanValue(node, "ro", false);
        @Nullable String model = nullableTextValue(node, "model");
        @Nullable String busType = nullableTextValue(node, "tran");
        boolean removable = removable(node, busType);
        @Nullable String hardwareId = hardwareId(node);
        @Unmodifiable List<String> mountPoints = mountPoints(node);
        boolean mounted = !mountPoints.isEmpty();
        boolean system = isSystemMount(mountPoints);

        return new BlockDevice(
                "linux-disk-" + sanitizeIdentifier(name),
                displayName(pathText, model, busType, sizeBytes),
                Path.of(pathText),
                sizeBytes,
                removable,
                system,
                mounted,
                readOnly,
                model,
                busType,
                hardwareId,
                mountPoints);
    }

    /// Returns whether a Linux disk should be treated as removable.
    ///
    /// @param node JSON disk object.
    /// @param busType transport type.
    /// @return whether the disk is removable.
    private static boolean removable(JsonNode node, @Nullable String busType) {
        if (booleanValue(node, "rm", false)) {
            return true;
        }
        return booleanValue(node, "hotplug", false) && hotplugRemovableTransport(busType);
    }

    /// Returns whether HOTPLUG may supplement RM for a known removable transport.
    ///
    /// @param busType transport type.
    /// @return whether HOTPLUG may indicate a removable target.
    private static boolean hotplugRemovableTransport(@Nullable String busType) {
        return busType != null && "usb".equalsIgnoreCase(busType);
    }

    /// Builds a stable hardware identity from `lsblk` disk metadata.
    ///
    /// @param node JSON disk object.
    /// @return hardware identity, or null when no stable identity is available.
    private static @Nullable String hardwareId(JsonNode node) {
        @Nullable String serial = nullableTextValue(node, "serial");
        @Nullable String wwn = nullableTextValue(node, "wwn");
        if (serial == null && wwn == null) {
            return null;
        }

        StringBuilder builder = new StringBuilder();
        if (serial != null) {
            builder.append("serial=").append(serial);
        }
        if (wwn != null) {
            if (!builder.isEmpty()) {
                builder.append(';');
            }
            builder.append("wwn=").append(wwn);
        }
        return builder.toString();
    }

    /// Builds a human-readable disk label.
    ///
    /// @param pathText disk path.
    /// @param model disk model.
    /// @param busType bus type.
    /// @param sizeBytes disk size in bytes.
    /// @return display label.
    private static String displayName(
            String pathText,
            @Nullable String model,
            @Nullable String busType,
            long sizeBytes) {
        StringBuilder builder = new StringBuilder(pathText);
        if (model != null) {
            builder.append(" - ").append(model);
        }

        ArrayList<String> details = new ArrayList<>(2);
        if (sizeBytes > 0L) {
            details.add(formatSize(sizeBytes));
        }
        if (busType != null) {
            details.add(busType);
        }

        if (!details.isEmpty()) {
            builder.append(" (");
            for (int i = 0; i < details.size(); i++) {
                if (i > 0) {
                    builder.append(", ");
                }
                builder.append(details.get(i));
            }
            builder.append(')');
        }
        return builder.toString();
    }

    /// Collects mount points from a disk and all children.
    ///
    /// @param node disk or partition node.
    /// @return immutable mount point list.
    private static @Unmodifiable List<String> mountPoints(JsonNode node) {
        ArrayList<String> result = new ArrayList<>();
        collectMountPoints(node, result);
        return List.copyOf(result);
    }

    /// Recursively collects mount points from a node.
    ///
    /// @param node current node.
    /// @param result mutable result list.
    private static void collectMountPoints(JsonNode node, ArrayList<String> result) {
        addMountPointField(node, "mountpoint", result);
        addMountPointField(node, "mountpoints", result);

        @Nullable JsonNode children = node.get("children");
        if (children != null && children.isArray()) {
            Iterator<JsonNode> iterator = children.elements();
            while (iterator.hasNext()) {
                collectMountPoints(iterator.next(), result);
            }
        }
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

    /// Checks whether any mount point indicates a system disk.
    ///
    /// @param mountPoints mount point list.
    /// @return whether the disk hosts the system root or a separate boot filesystem.
    private static boolean isSystemMount(List<String> mountPoints) {
        return mountPoints.contains("/")
                || mountPoints.contains("/boot")
                || mountPoints.contains("/boot/efi")
                || mountPoints.contains("/efi");
    }

    /// Sanitizes a platform disk name for a stable id.
    ///
    /// @param value raw platform name.
    /// @return sanitized identifier.
    private static String sanitizeIdentifier(String value) {
        StringBuilder builder = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = Character.toLowerCase(value.charAt(i));
            if (Character.isLetterOrDigit(ch) || ch == '.' || ch == '_' || ch == '-') {
                builder.append(ch);
            } else {
                builder.append('-');
            }
        }
        return builder.toString();
    }

    /// Formats a byte size for display.
    ///
    /// @param sizeBytes size in bytes.
    /// @return formatted size.
    private static String formatSize(long sizeBytes) {
        double value = sizeBytes;
        String @Unmodifiable [] units = {"B", "KiB", "MiB", "GiB", "TiB"};
        int unitIndex = 0;
        while (value >= 1024.0 && unitIndex + 1 < units.length) {
            value /= 1024.0;
            unitIndex++;
        }

        if (unitIndex == 0) {
            return Long.toString(sizeBytes) + " B";
        }
        return String.format(Locale.ROOT, "%.1f %s", value, units[unitIndex]);
    }

    /// Reads a string field with a default fallback.
    ///
    /// @param node JSON object.
    /// @param fieldName field name.
    /// @param defaultValue fallback value.
    /// @return field value or fallback.
    private static String textValue(JsonNode node, String fieldName, String defaultValue) {
        @Nullable String value = nullableTextValue(node, fieldName);
        return value == null ? defaultValue : value;
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

    /// Reads a long field.
    ///
    /// @param node JSON object.
    /// @param fieldName field name.
    /// @param defaultValue fallback value.
    /// @return parsed long.
    private static long longValue(JsonNode node, String fieldName, long defaultValue) {
        @Nullable JsonNode value = node.get(fieldName);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        if (value.canConvertToLong()) {
            return value.asLong();
        }

        try {
            return Long.parseLong(value.asText().strip());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /// Reads a boolean field.
    ///
    /// @param node JSON object.
    /// @param fieldName field name.
    /// @param defaultValue fallback value.
    /// @return parsed boolean.
    private static boolean booleanValue(JsonNode node, String fieldName, boolean defaultValue) {
        @Nullable JsonNode value = node.get(fieldName);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        if (value.canConvertToInt()) {
            return value.asInt() != 0;
        }
        return Boolean.parseBoolean(value.asText());
    }
}

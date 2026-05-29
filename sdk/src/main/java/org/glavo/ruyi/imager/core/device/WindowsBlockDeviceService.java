// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.device;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.glavo.ruyi.imager.core.PowerShellScripts;
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

/// Windows block-device enumerator backed by read-only CIM queries.
@NotNullByDefault
public final class WindowsBlockDeviceService implements BlockDeviceService {
    /// Logger for Windows device enumeration.
    private static final Logger LOGGER = LoggerFactory.getLogger(WindowsBlockDeviceService.class);

    /// JSON mapper used for PowerShell output.
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /// Maximum time allowed for one device enumeration.
    private static final Duration ENUMERATION_TIMEOUT = Duration.ofSeconds(20);

    /// Lists physical disks through PowerShell CIM and converts them to block devices.
    ///
    /// @return immutable list of physical disks.
    /// @throws IOException when PowerShell or JSON parsing fails.
    @Override
    public @Unmodifiable List<BlockDevice> listDevices() throws IOException {
        LOGGER.info("Enumerating Windows block devices with PowerShell CIM.");
        List<String> command = List.of(
                "powershell.exe",
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                PowerShellScripts.path("list-windows-block-devices.ps1").toString());

        ProcessOutputCapture.Result result;
        try {
            result = ProcessOutputCapture.run(command, ENUMERATION_TIMEOUT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Windows block-device enumeration interrupted.");
            throw new IOException(SdkMessages.get("core.device.windowsInterrupted"), e);
        }

        if (result.timedOut()) {
            LOGGER.warn("Windows block-device enumeration timed out.");
            throw new IOException(SdkMessages.get("core.device.windowsTimedOut"));
        }

        String output = result.output();
        String error = result.error();
        if (result.exitCode() != 0) {
            String message = error.isBlank() ? SdkMessages.get("core.device.powershellExit", result.exitCode()) : error.strip();
            LOGGER.atWarn().log(() -> "Windows block-device enumeration failed. exitCode="
                    + result.exitCode()
                    + ", error="
                    + LogRedactor.redactOutput(error, 1000));
            throw new IOException(SdkMessages.get("core.device.windowsEnumerationFailed", message));
        }

        @Unmodifiable List<BlockDevice> devices = parseDevices(output);
        LOGGER.atInfo().log(() -> "Windows block devices enumerated. count=" + devices.size());
        return devices;
    }

    /// Parses JSON emitted by the Windows enumeration script.
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

        ArrayList<BlockDevice> devices = new ArrayList<>();
        if (root.isArray()) {
            Iterator<JsonNode> iterator = root.elements();
            while (iterator.hasNext()) {
                @Nullable BlockDevice device = parseDevice(iterator.next());
                if (device != null) {
                    devices.add(device);
                }
            }
        } else {
            @Nullable BlockDevice device = parseDevice(root);
            if (device != null) {
                devices.add(device);
            }
        }

        return List.copyOf(devices);
    }

    /// Parses one JSON disk object.
    ///
    /// @param node JSON object.
    /// @return parsed device, or null when the object lacks an index.
    private static @Nullable BlockDevice parseDevice(JsonNode node) {
        int index = intValue(node, "index", -1);
        if (index < 0) {
            return null;
        }

        String defaultDeviceId = "\\\\.\\PHYSICALDRIVE" + index;
        String deviceId = textValue(node, "deviceId", defaultDeviceId);
        long sizeBytes = longValue(node, "sizeBytes", 0L);
        @Nullable String model = nullableTextValue(node, "model");
        @Nullable String busType = nullableTextValue(node, "busType");
        boolean removable = booleanValue(node, "removable", false);
        boolean system = booleanValue(node, "system", false);
        boolean mounted = booleanValue(node, "mounted", false);
        boolean readOnly = booleanValue(node, "readOnly", false);
        @Nullable String hardwareId = nullableTextValue(node, "hardwareId");
        @Unmodifiable List<String> mountPoints = stringListValue(node, "mountPoints");

        return new BlockDevice(
                "windows-disk-" + index,
                displayName(index, model, busType, sizeBytes),
                Path.of(deviceId),
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

    /// Reads a string list field.
    ///
    /// @param node JSON object.
    /// @param fieldName field name.
    /// @return immutable string list.
    private static @Unmodifiable List<String> stringListValue(JsonNode node, String fieldName) {
        @Nullable JsonNode value = node.get(fieldName);
        if (value == null || value.isNull()) {
            return List.of();
        }

        ArrayList<String> result = new ArrayList<>();
        if (value.isArray()) {
            Iterator<JsonNode> iterator = value.elements();
            while (iterator.hasNext()) {
                addStringListValue(result, iterator.next());
            }
        } else {
            addStringListValue(result, value);
        }
        return List.copyOf(result);
    }

    /// Adds one JSON string value to a list.
    ///
    /// @param result mutable result list.
    /// @param value JSON value.
    private static void addStringListValue(ArrayList<String> result, JsonNode value) {
        if (value.isNull()) {
            return;
        }

        String text = value.asText();
        if (!text.isBlank()) {
            result.add(text.strip());
        }
    }

    /// Builds a human-readable disk label.
    ///
    /// @param index disk index.
    /// @param model disk model.
    /// @param busType bus type.
    /// @param sizeBytes disk size in bytes.
    /// @return display label.
    private static String displayName(
            int index,
            @Nullable String model,
            @Nullable String busType,
            long sizeBytes) {
        StringBuilder builder = new StringBuilder("Disk ");
        builder.append(index);
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

    /// Formats a byte size for display.
    ///
    /// @param sizeBytes size in bytes.
    /// @return formatted size.
    private static String formatSize(long sizeBytes) {
        double value = sizeBytes;
        String[] units = {"B", "KiB", "MiB", "GiB", "TiB"};
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

    /// Reads an integer field.
    ///
    /// @param node JSON object.
    /// @param fieldName field name.
    /// @param defaultValue fallback value.
    /// @return parsed integer.
    private static int intValue(JsonNode node, String fieldName, int defaultValue) {
        @Nullable JsonNode value = node.get(fieldName);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        return value.canConvertToInt() ? value.asInt() : defaultValue;
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
        return value.isBoolean() ? value.asBoolean() : Boolean.parseBoolean(value.asText());
    }
}

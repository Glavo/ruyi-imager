// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.device;

import org.glavo.ruyi.imager.i18n.Messages;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/// macOS block-device enumerator backed by read-only `diskutil` plist output.
@NotNullByDefault
public final class MacOSBlockDeviceService implements BlockDeviceService {
    /// Maximum time allowed for one `diskutil` invocation.
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(20);

    /// Lists physical disks through `diskutil` and converts them to block devices.
    ///
    /// @return immutable list of physical disks.
    /// @throws IOException when `diskutil` or plist parsing fails.
    @Override
    public @Unmodifiable List<BlockDevice> listDevices() throws IOException {
        String listPlist = runDiskutil("list", "-plist");
        ArrayList<String> identifiers = diskIdentifiers(listPlist);
        LinkedHashMap<String, String> infoPlists = new LinkedHashMap<>();
        for (String identifier : identifiers) {
            infoPlists.put(identifier, runDiskutil("info", "-plist", identifier));
        }
        return parseDevices(listPlist, infoPlists);
    }

    /// Parses `diskutil list -plist` and matching `diskutil info -plist` payloads.
    ///
    /// @param listPlist plist text emitted by `diskutil list -plist`.
    /// @param infoPlists plist texts keyed by disk identifier.
    /// @return immutable parsed block-device list.
    /// @throws IOException when plist parsing fails.
    static @Unmodifiable List<BlockDevice> parseDevices(
            String listPlist,
            @Unmodifiable Map<String, String> infoPlists) throws IOException {
        @Unmodifiable Map<String, Object> root = readPlistDictionary(listPlist);
        @Nullable List<Object> disks = objectList(root.get("AllDisksAndPartitions"));
        if (disks == null) {
            return List.of();
        }

        ArrayList<BlockDevice> result = new ArrayList<>();
        for (Object value : disks) {
            @Nullable Map<String, Object> disk = objectMap(value);
            if (disk == null) {
                continue;
            }

            @Nullable String identifier = stringValue(disk, "DeviceIdentifier");
            if (identifier == null || identifier.isBlank()) {
                continue;
            }

            @Unmodifiable Map<String, Object> info = Map.of();
            @Nullable String infoPlist = infoPlists.get(identifier);
            if (infoPlist != null && !infoPlist.isBlank()) {
                info = readPlistDictionary(infoPlist);
            }

            result.add(parseDevice(identifier, disk, info));
        }
        return List.copyOf(result);
    }

    /// Extracts whole-disk identifiers from `diskutil list -plist`.
    ///
    /// @param listPlist plist text.
    /// @return mutable identifier list.
    /// @throws IOException when plist parsing fails.
    private static ArrayList<String> diskIdentifiers(String listPlist) throws IOException {
        @Unmodifiable Map<String, Object> root = readPlistDictionary(listPlist);
        ArrayList<String> result = new ArrayList<>();
        @Nullable List<Object> disks = objectList(root.get("AllDisksAndPartitions"));
        if (disks == null) {
            return result;
        }

        for (Object value : disks) {
            @Nullable Map<String, Object> disk = objectMap(value);
            if (disk != null) {
                @Nullable String identifier = stringValue(disk, "DeviceIdentifier");
                if (identifier != null && !identifier.isBlank()) {
                    result.add(identifier);
                }
            }
        }
        return result;
    }

    /// Parses one disk object.
    ///
    /// @param identifier disk identifier.
    /// @param disk disk object from `diskutil list`.
    /// @param info disk object from `diskutil info`.
    /// @return parsed block device.
    private static BlockDevice parseDevice(
            String identifier,
            @Unmodifiable Map<String, Object> disk,
            @Unmodifiable Map<String, Object> info) {
        String pathText = stringValue(info, "DeviceNode", "/dev/" + identifier);
        long sizeBytes = longValue(info, "TotalSize", longValue(disk, "Size", 0L));
        @Nullable String model = firstNonNull(
                stringValue(info, "MediaName"),
                stringValue(info, "DeviceModel"),
                stringValue(disk, "Content"));
        @Nullable String busType = stringValue(info, "BusProtocol");
        boolean removable = booleanValue(info, "RemovableMedia", false)
                || booleanValue(info, "Ejectable", false);
        boolean readOnly = booleanValue(info, "ReadOnlyMedia", false)
                || !booleanValue(info, "Writable", true);
        @Unmodifiable List<String> mountPoints = mountPoints(disk, info);
        boolean mounted = !mountPoints.isEmpty();
        boolean system = isSystemMount(mountPoints);

        return new BlockDevice(
                "macos-disk-" + sanitizeIdentifier(identifier),
                displayName(identifier, model, busType, sizeBytes),
                Path.of(pathText),
                sizeBytes,
                removable,
                system,
                mounted,
                readOnly,
                model,
                busType,
                mountPoints);
    }

    /// Runs `diskutil` with a fixed timeout.
    ///
    /// @param arguments `diskutil` arguments.
    /// @return stdout text.
    /// @throws IOException when the command fails.
    private static String runDiskutil(String... arguments) throws IOException {
        ArrayList<String> command = new ArrayList<>(arguments.length + 1);
        command.add("diskutil");
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).start();

        boolean completed;
        try {
            completed = process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException(Messages.get("core.device.enumerationInterrupted", "macOS"), e);
        }

        if (!completed) {
            process.destroyForcibly();
            throw new IOException(Messages.get("core.device.enumerationTimedOut", "macOS"));
        }

        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.exitValue();
        if (exitCode != 0) {
            String message = error.isBlank()
                    ? Messages.get("core.device.commandExit", "diskutil", exitCode)
                    : error.strip();
            throw new IOException(Messages.get("core.device.enumerationFailed", "macOS", message));
        }
        return output;
    }

    /// Builds a human-readable disk label.
    ///
    /// @param identifier disk identifier.
    /// @param model disk model.
    /// @param busType bus type.
    /// @param sizeBytes disk size in bytes.
    /// @return display label.
    private static String displayName(
            String identifier,
            @Nullable String model,
            @Nullable String busType,
            long sizeBytes) {
        StringBuilder builder = new StringBuilder(identifier);
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

    /// Collects mount points from disk list and disk info objects.
    ///
    /// @param disk disk list object.
    /// @param info disk info object.
    /// @return immutable mount point list.
    private static @Unmodifiable List<String> mountPoints(
            @Unmodifiable Map<String, Object> disk,
            @Unmodifiable Map<String, Object> info) {
        ArrayList<String> result = new ArrayList<>();
        collectMountPoints(disk, result);
        collectMountPoints(info, result);
        return List.copyOf(result);
    }

    /// Recursively collects mount points from a plist object.
    ///
    /// @param value plist value.
    /// @param result mutable result list.
    private static void collectMountPoints(@Nullable Object value, ArrayList<String> result) {
        if (value instanceof Map<?, ?> map) {
            @Nullable Object mountPoint = map.get("MountPoint");
            if (mountPoint instanceof String text && !text.isBlank() && !result.contains(text.strip())) {
                result.add(text.strip());
            }
            for (Object nested : map.values()) {
                collectMountPoints(nested, result);
            }
            return;
        }
        if (value instanceof List<?> list) {
            for (Object nested : list) {
                collectMountPoints(nested, result);
            }
        }
    }

    /// Checks whether any mount point indicates a system disk.
    ///
    /// @param mountPoints mount point list.
    /// @return whether the disk hosts macOS system volumes.
    private static boolean isSystemMount(List<String> mountPoints) {
        for (String mountPoint : mountPoints) {
            if ("/".equals(mountPoint) || mountPoint.startsWith("/System/Volumes")) {
                return true;
            }
        }
        return false;
    }

    /// Reads a plist root dictionary.
    ///
    /// @param plist plist XML text.
    /// @return root dictionary.
    /// @throws IOException when parsing fails or the plist root is not a dictionary.
    private static @Unmodifiable Map<String, Object> readPlistDictionary(String plist) throws IOException {
        @Nullable Object root = readPlist(plist);
        @Nullable @Unmodifiable Map<String, Object> dictionary = objectMap(root);
        if (dictionary == null) {
            throw new IOException(Messages.get("core.device.enumerationFailed", "macOS", "Invalid plist root."));
        }
        return dictionary;
    }

    /// Reads a plist XML value.
    ///
    /// @param plist plist XML text.
    /// @return parsed plist value.
    /// @throws IOException when parsing fails.
    private static @Nullable Object readPlist(String plist) throws IOException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setIgnoringComments(true);
        factory.setCoalescing(true);
        setParserFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
        setParserFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
        setParserFeature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        try {
            Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(plist)));
            Element root = document.getDocumentElement();
            if (root == null || !"plist".equals(root.getTagName())) {
                return null;
            }

            @Nullable Element child = firstElementChild(root);
            return child == null ? null : readPlistElement(child);
        } catch (ParserConfigurationException | SAXException exception) {
            throw new IOException(Messages.get("core.device.enumerationFailed", "macOS", exception.getMessage()), exception);
        }
    }

    /// Sets an XML parser feature when supported by the current parser.
    ///
    /// @param factory document builder factory.
    /// @param feature feature URI.
    /// @param value feature value.
    private static void setParserFeature(DocumentBuilderFactory factory, String feature, boolean value) {
        try {
            factory.setFeature(feature, value);
        } catch (ParserConfigurationException _) {
            // Some JAXP providers do not expose every hardening feature.
        }
    }

    /// Reads one plist element.
    ///
    /// @param element plist element.
    /// @return parsed value.
    private static @Nullable Object readPlistElement(Element element) {
        return switch (element.getTagName()) {
            case "dict" -> readDictionaryElement(element);
            case "array" -> readArrayElement(element);
            case "string", "data", "date" -> element.getTextContent();
            case "integer" -> parseLong(element.getTextContent());
            case "real" -> parseDouble(element.getTextContent());
            case "true" -> Boolean.TRUE;
            case "false" -> Boolean.FALSE;
            default -> null;
        };
    }

    /// Reads a plist dictionary element.
    ///
    /// @param element dictionary element.
    /// @return parsed dictionary.
    private static @Unmodifiable Map<String, Object> readDictionaryElement(Element element) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        @Nullable String pendingKey = null;
        for (Element child : elementChildren(element)) {
            if ("key".equals(child.getTagName())) {
                pendingKey = child.getTextContent();
            } else if (pendingKey != null) {
                @Nullable Object value = readPlistElement(child);
                if (value != null) {
                    result.put(pendingKey, value);
                }
                pendingKey = null;
            }
        }
        return Collections.unmodifiableMap(result);
    }

    /// Reads a plist array element.
    ///
    /// @param element array element.
    /// @return parsed array.
    private static @Unmodifiable List<Object> readArrayElement(Element element) {
        ArrayList<Object> result = new ArrayList<>();
        for (Element child : elementChildren(element)) {
            @Nullable Object value = readPlistElement(child);
            if (value != null) {
                result.add(value);
            }
        }
        return List.copyOf(result);
    }

    /// Returns the first element child.
    ///
    /// @param element parent element.
    /// @return first element child, or null.
    private static @Nullable Element firstElementChild(Element element) {
        for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element childElement) {
                return childElement;
            }
        }
        return null;
    }

    /// Returns all element children.
    ///
    /// @param element parent element.
    /// @return immutable child element list.
    private static @Unmodifiable List<Element> elementChildren(Element element) {
        ArrayList<Element> result = new ArrayList<>();
        for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element childElement) {
                result.add(childElement);
            }
        }
        return List.copyOf(result);
    }

    /// Casts a plist object to a map.
    ///
    /// @param value plist value.
    /// @return object map, or null.
    @SuppressWarnings("unchecked")
    private static @Nullable @Unmodifiable Map<String, Object> objectMap(@Nullable Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : null;
    }

    /// Casts a plist object to a list.
    ///
    /// @param value plist value.
    /// @return object list, or null.
    @SuppressWarnings("unchecked")
    private static @Nullable List<Object> objectList(@Nullable Object value) {
        return value instanceof List<?> ? (List<Object>) value : null;
    }

    /// Reads a nullable string value from a plist map.
    ///
    /// @param map plist map.
    /// @param key map key.
    /// @return string value, or null.
    private static @Nullable String stringValue(@Unmodifiable Map<String, Object> map, String key) {
        @Nullable Object value = map.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            return null;
        }
        return text.strip();
    }

    /// Reads a string value from a plist map with a fallback.
    ///
    /// @param map plist map.
    /// @param key map key.
    /// @param defaultValue fallback value.
    /// @return string value or fallback.
    private static String stringValue(@Unmodifiable Map<String, Object> map, String key, String defaultValue) {
        @Nullable String value = stringValue(map, key);
        return value == null ? defaultValue : value;
    }

    /// Reads a long value from a plist map.
    ///
    /// @param map plist map.
    /// @param key map key.
    /// @param defaultValue fallback value.
    /// @return long value or fallback.
    private static long longValue(@Unmodifiable Map<String, Object> map, String key, long defaultValue) {
        @Nullable Object value = map.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text.strip());
            } catch (NumberFormatException _) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    /// Reads a boolean value from a plist map.
    ///
    /// @param map plist map.
    /// @param key map key.
    /// @param defaultValue fallback value.
    /// @return boolean value or fallback.
    private static boolean booleanValue(@Unmodifiable Map<String, Object> map, String key, boolean defaultValue) {
        @Nullable Object value = map.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            return Boolean.parseBoolean(text);
        }
        return defaultValue;
    }

    /// Returns the first non-null value.
    ///
    /// @param first first candidate value.
    /// @param second second candidate value.
    /// @param third third candidate value.
    /// @return first non-null value, or null.
    private static @Nullable String firstNonNull(
            @Nullable String first,
            @Nullable String second,
            @Nullable String third) {
        if (first != null) {
            return first;
        }
        if (second != null) {
            return second;
        }
        if (third != null) {
            return third;
        }
        return null;
    }

    /// Parses a long plist value.
    ///
    /// @param text value text.
    /// @return parsed value, or null.
    private static @Nullable Long parseLong(String text) {
        try {
            return Long.parseLong(text.strip());
        } catch (NumberFormatException _) {
            return null;
        }
    }

    /// Parses a double plist value.
    ///
    /// @param text value text.
    /// @return parsed value, or null.
    private static @Nullable Double parseDouble(String text) {
        try {
            return Double.parseDouble(text.strip());
        } catch (NumberFormatException _) {
            return null;
        }
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
}

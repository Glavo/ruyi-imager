// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.image;

import org.glavo.ruyi.imager.core.ProgressEvent;
import org.glavo.ruyi.imager.core.ProgressReporter;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/// Materializes downloaded Ruyi distfiles into flashable image artifacts.
@NotNullByDefault
public final class RuyiImageMaterializer {
    /// Unpack methods that are recognized but not implemented in Java yet.
    private static final Set<String> UNSUPPORTED_METHODS = Set.of(
            "tar.bz2",
            "tar.lz4",
            "tar.xz",
            "tar.zst",
            "bz2",
            "lz4",
            "xz",
            "zst",
            "deb");

    /// Materializes all downloaded distfiles for an image.
    ///
    /// @param image image metadata.
    /// @param downloadedDistfiles downloaded distfile paths in image distfile order.
    /// @param artifactDirectory output artifact directory.
    /// @param reporter progress reporter.
    /// @return single partition file path, or the artifact directory for multi-partition images.
    /// @throws IOException when an artifact cannot be materialized or validated.
    public Path materialize(
            ImageEntry image,
            @Unmodifiable List<Path> downloadedDistfiles,
            Path artifactDirectory,
            ProgressReporter reporter) throws IOException {
        Files.createDirectories(artifactDirectory);
        if (downloadedDistfiles.size() != image.distfiles().size()) {
            throw new IOException("Downloaded distfile count does not match image metadata.");
        }

        for (int i = 0; i < image.distfiles().size(); i++) {
            RuyiDistfile distfile = image.distfiles().get(i);
            Path source = downloadedDistfiles.get(i);
            reporter.report(ProgressEvent.indeterminate("materialize", "Materializing " + distfile.name() + "."));
            materializeDistfile(distfile, source, artifactDirectory);
        }

        List<Path> partitions = resolvePartitionPaths(image.partitionMap(), artifactDirectory);
        if (partitions.isEmpty()) {
            throw new IOException("Image has no partition map: " + image.atom());
        }

        for (Path partition : partitions) {
            if (!Files.isRegularFile(partition)) {
                throw new IOException("Image partition artifact is missing: " + partition);
            }
        }

        return partitions.size() == 1 ? partitions.getFirst() : artifactDirectory;
    }

    /// Materializes one distfile.
    ///
    /// @param distfile distfile metadata.
    /// @param source downloaded distfile path.
    /// @param artifactDirectory output artifact directory.
    /// @throws IOException when the distfile cannot be materialized.
    private static void materializeDistfile(RuyiDistfile distfile, Path source, Path artifactDirectory) throws IOException {
        String method = resolveUnpackMethod(distfile);
        if ("raw".equals(method)) {
            copyRaw(source, artifactDirectory.resolve(distfile.name()));
            return;
        }
        if ("gz".equals(method)) {
            decompressGzip(source, artifactDirectory.resolve(removeLastExtension(distfile.name())));
            return;
        }
        if ("zip".equals(method)) {
            extractZip(source, artifactDirectory, distfile.stripComponents());
            return;
        }
        if ("tar".equals(method)) {
            try (InputStream input = Files.newInputStream(source)) {
                extractTar(input, artifactDirectory, distfile.stripComponents());
            }
            return;
        }
        if ("tar.gz".equals(method)) {
            try (InputStream input = new GZIPInputStream(Files.newInputStream(source))) {
                extractTar(input, artifactDirectory, distfile.stripComponents());
            }
            return;
        }
        if ("tar.auto".equals(method)) {
            extractAutoTar(distfile, source, artifactDirectory);
            return;
        }
        if (UNSUPPORTED_METHODS.contains(method)) {
            throw new IOException("Unsupported Ruyi unpack method: " + method + " for " + distfile.name());
        }

        copyRaw(source, artifactDirectory.resolve(distfile.name()));
    }

    /// Resolves the effective unpack method.
    ///
    /// @param distfile distfile metadata.
    /// @return effective unpack method.
    private static String resolveUnpackMethod(RuyiDistfile distfile) {
        @Nullable String declared = distfile.unpack();
        if (declared != null && !declared.isBlank() && !"auto".equals(declared)) {
            return declared;
        }

        String name = distfile.name().toLowerCase(Locale.ROOT);
        if (name.endsWith(".tgz")) {
            return "tar.gz";
        }
        if (name.matches(".*\\.tar(\\.gz|\\.bz2|\\.lz4|\\.xz|\\.zst)?$")) {
            return name.substring(name.indexOf(".tar") + 1);
        }
        if (name.endsWith(".deb")) {
            return "deb";
        }
        if (name.endsWith(".zip")) {
            return "zip";
        }
        if (name.endsWith(".gz")) {
            return "gz";
        }
        if (name.endsWith(".bz2")) {
            return "bz2";
        }
        if (name.endsWith(".lz4")) {
            return "lz4";
        }
        if (name.endsWith(".xz")) {
            return "xz";
        }
        if (name.endsWith(".zst")) {
            return "zst";
        }
        return "raw";
    }

    /// Copies a raw file into the artifact directory.
    ///
    /// @param source source path.
    /// @param target target path.
    /// @throws IOException when the copy fails.
    private static void copyRaw(Path source, Path target) throws IOException {
        @Nullable Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    /// Decompresses a bare gzip stream.
    ///
    /// @param source source path.
    /// @param target target path.
    /// @throws IOException when decompression fails.
    private static void decompressGzip(Path source, Path target) throws IOException {
        @Nullable Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (InputStream input = new GZIPInputStream(Files.newInputStream(source));
             OutputStream output = Files.newOutputStream(target)) {
            input.transferTo(output);
        }
    }

    /// Extracts a zip archive into the artifact directory.
    ///
    /// @param source source path.
    /// @param artifactDirectory output artifact directory.
    /// @param stripComponents leading path components to strip from each entry.
    /// @throws IOException when extraction fails or the archive attempts path traversal.
    private static void extractZip(Path source, Path artifactDirectory, int stripComponents) throws IOException {
        Path normalizedRoot = artifactDirectory.toAbsolutePath().normalize();
        try (ZipInputStream input = new ZipInputStream(Files.newInputStream(source))) {
            while (true) {
                @Nullable ZipEntry entry = input.getNextEntry();
                if (entry == null) {
                    break;
                }

                @Nullable String strippedName = strippedArchiveEntryName(entry.getName(), stripComponents);
                if (strippedName == null) {
                    input.closeEntry();
                    continue;
                }
                Path target = resolveArchiveTarget(normalizedRoot, strippedName, "Zip");

                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    @Nullable Path parent = target.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    try (OutputStream output = Files.newOutputStream(target)) {
                        input.transferTo(output);
                    }
                }
                input.closeEntry();
            }
        }
    }

    /// Extracts a tar archive into the artifact directory.
    ///
    /// @param input tar input stream.
    /// @param artifactDirectory output artifact directory.
    /// @param stripComponents leading path components to strip from each entry.
    /// @throws IOException when extraction fails or the archive attempts path traversal.
    private static void extractTar(InputStream input, Path artifactDirectory, int stripComponents) throws IOException {
        Path normalizedRoot = artifactDirectory.toAbsolutePath().normalize();
        byte[] header = new byte[512];
        @Nullable String pendingLongName = null;
        while (true) {
            int headerBytes = input.readNBytes(header, 0, header.length);
            if (headerBytes == 0) {
                break;
            }
            if (headerBytes != header.length) {
                throw new IOException("Truncated tar header.");
            }
            if (isZeroBlock(header)) {
                break;
            }

            long size = parseTarOctal(header, 124, 12);
            char type = (char) header[156];
            String entryName = pendingLongName == null ? tarEntryName(header) : pendingLongName;
            pendingLongName = null;

            if (type == 'L') {
                pendingLongName = readTarString(input, size);
                skipTarPadding(input, size);
                continue;
            }

            @Nullable String strippedName = strippedArchiveEntryName(entryName, stripComponents);
            if (strippedName != null) {
                if (type == '5') {
                    Files.createDirectories(resolveArchiveTarget(normalizedRoot, strippedName, "Tar"));
                } else if (type == 0 || type == '0') {
                    Path target = resolveArchiveTarget(normalizedRoot, strippedName, "Tar");
                    @Nullable Path parent = target.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    try (OutputStream output = Files.newOutputStream(target)) {
                        copyExactly(input, output, size);
                    }
                    skipTarPadding(input, size);
                    continue;
                }
            }

            input.skipNBytes(size);
            skipTarPadding(input, size);
        }
    }

    /// Extracts a tar archive with compression inferred from the distfile name.
    ///
    /// @param distfile distfile metadata.
    /// @param source source path.
    /// @param artifactDirectory output artifact directory.
    /// @throws IOException when extraction fails.
    private static void extractAutoTar(RuyiDistfile distfile, Path source, Path artifactDirectory) throws IOException {
        String name = distfile.name().toLowerCase(Locale.ROOT);
        if (name.endsWith(".tar.gz") || name.endsWith(".tgz")) {
            try (InputStream input = new GZIPInputStream(Files.newInputStream(source))) {
                extractTar(input, artifactDirectory, distfile.stripComponents());
            }
            return;
        }

        if (name.endsWith(".tar")) {
            try (InputStream input = Files.newInputStream(source)) {
                extractTar(input, artifactDirectory, distfile.stripComponents());
            }
            return;
        }

        throw new IOException("Unsupported Ruyi unpack method: tar.auto for " + distfile.name());
    }

    /// Resolves an archive target safely under the artifact directory.
    ///
    /// @param normalizedRoot normalized artifact directory.
    /// @param entryName stripped archive entry name.
    /// @param archiveKind archive type for error messages.
    /// @return target path.
    /// @throws IOException when the entry escapes the artifact directory.
    private static Path resolveArchiveTarget(Path normalizedRoot, String entryName, String archiveKind) throws IOException {
        Path target = normalizedRoot.resolve(entryName).normalize();
        if (!target.startsWith(normalizedRoot)) {
            throw new IOException(archiveKind + " entry escapes artifact directory: " + entryName);
        }
        return target;
    }

    /// Strips leading archive path components.
    ///
    /// @param entryName archive entry name.
    /// @param stripComponents component count to strip.
    /// @return stripped entry name, or null when the entry is fully stripped.
    private static @Nullable String strippedArchiveEntryName(String entryName, int stripComponents) {
        String normalizedName = entryName.replace('\\', '/');
        ArrayList<String> parts = new ArrayList<>();
        for (String part : normalizedName.split("/")) {
            if (!part.isEmpty() && !".".equals(part)) {
                parts.add(part);
            }
        }
        if (parts.size() <= stripComponents) {
            return null;
        }

        StringBuilder builder = new StringBuilder();
        for (int i = stripComponents; i < parts.size(); i++) {
            if (builder.length() > 0) {
                builder.append('/');
            }
            builder.append(parts.get(i));
        }
        return builder.toString();
    }

    /// Checks whether a tar block is all zero bytes.
    ///
    /// @param block tar block.
    /// @return whether the block is empty.
    private static boolean isZeroBlock(byte[] block) {
        for (byte value : block) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }

    /// Reads a tar entry name from a header.
    ///
    /// @param header tar header.
    /// @return entry name.
    private static String tarEntryName(byte[] header) {
        String name = tarString(header, 0, 100);
        String prefix = tarString(header, 345, 155);
        return prefix.isEmpty() ? name : prefix + "/" + name;
    }

    /// Reads a NUL-terminated tar string field.
    ///
    /// @param header tar header.
    /// @param offset field offset.
    /// @param length field length.
    /// @return string field.
    private static String tarString(byte[] header, int offset, int length) {
        int end = offset;
        int limit = offset + length;
        while (end < limit && header[end] != 0) {
            end++;
        }
        return new String(header, offset, end - offset, StandardCharsets.UTF_8).strip();
    }

    /// Parses an octal tar numeric field.
    ///
    /// @param header tar header.
    /// @param offset field offset.
    /// @param length field length.
    /// @return parsed value.
    private static long parseTarOctal(byte[] header, int offset, int length) {
        long value = 0L;
        int limit = offset + length;
        for (int i = offset; i < limit; i++) {
            byte current = header[i];
            if (current == 0 || current == ' ') {
                continue;
            }
            if (current < '0' || current > '7') {
                break;
            }
            value = (value << 3) + (current - '0');
        }
        return value;
    }

    /// Reads a tar data section as a string.
    ///
    /// @param input tar input stream.
    /// @param size entry size.
    /// @return entry data string.
    /// @throws IOException when the entry is too large or truncated.
    private static String readTarString(InputStream input, long size) throws IOException {
        if (size > Integer.MAX_VALUE) {
            throw new IOException("Tar string entry is too large.");
        }
        byte[] bytes = input.readNBytes(Math.toIntExact(size));
        if (bytes.length != size) {
            throw new IOException("Truncated tar string entry.");
        }

        int length = bytes.length;
        while (length > 0 && bytes[length - 1] == 0) {
            length--;
        }
        return new String(bytes, 0, length, StandardCharsets.UTF_8);
    }

    /// Copies exactly one tar entry payload.
    ///
    /// @param input tar input stream.
    /// @param output output stream.
    /// @param size bytes to copy.
    /// @throws IOException when the input is truncated.
    private static void copyExactly(InputStream input, OutputStream output, long size) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long remaining = size;
        while (remaining > 0L) {
            int read = input.read(buffer, 0, Math.toIntExact(Math.min(buffer.length, remaining)));
            if (read < 0) {
                throw new IOException("Truncated tar entry.");
            }
            output.write(buffer, 0, read);
            remaining -= read;
        }
    }

    /// Skips tar padding after an entry payload.
    ///
    /// @param input tar input stream.
    /// @param size entry size.
    /// @throws IOException when padding is truncated.
    private static void skipTarPadding(InputStream input, long size) throws IOException {
        long padding = (512L - (size % 512L)) % 512L;
        if (padding > 0L) {
            input.skipNBytes(padding);
        }
    }

    /// Resolves partition paths safely under the artifact directory.
    ///
    /// @param partitionMap partition map.
    /// @param artifactDirectory artifact directory.
    /// @return immutable partition paths.
    /// @throws IOException when a partition path escapes the artifact directory.
    private static @Unmodifiable List<Path> resolvePartitionPaths(
            @Unmodifiable Map<String, String> partitionMap,
            Path artifactDirectory) throws IOException {
        Path normalizedRoot = artifactDirectory.toAbsolutePath().normalize();
        ArrayList<Path> result = new ArrayList<>();
        for (String relativePath : partitionMap.values()) {
            Path path = normalizedRoot.resolve(relativePath).normalize();
            if (!path.startsWith(normalizedRoot)) {
                throw new IOException("Partition path escapes artifact directory: " + relativePath);
            }
            result.add(path);
        }
        return List.copyOf(result);
    }

    /// Removes the final file extension from a file name.
    ///
    /// @param name file name.
    /// @return file name without the final extension.
    private static String removeLastExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }
}

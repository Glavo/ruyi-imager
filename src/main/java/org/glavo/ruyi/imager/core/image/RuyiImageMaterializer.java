// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.image;

import kala.compress.archivers.tar.TarArchiveEntry;
import kala.compress.archivers.tar.TarArchiveInputStream;
import kala.compress.compressors.CompressorException;
import kala.compress.compressors.CompressorStreamFactory;
import org.glavo.ruyi.imager.core.ProgressEvent;
import org.glavo.ruyi.imager.core.ProgressReporter;
import org.glavo.ruyi.imager.i18n.Messages;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
    /// Compressor stream factory with optional compressor modules loaded through ServiceLoader.
    private static final CompressorStreamFactory COMPRESSOR_STREAMS =
            CompressorStreamFactory.DEFAULT.withInstalledProviders();

    /// Unpack methods that are recognized but not implemented in Java yet.
    private static final @Unmodifiable Set<String> UNSUPPORTED_METHODS = Set.of("deb");

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
            throw new IOException(Messages.get("core.materialize.countMismatch"));
        }

        for (int i = 0; i < image.distfiles().size(); i++) {
            RuyiDistfile distfile = image.distfiles().get(i);
            Path source = downloadedDistfiles.get(i);
            reporter.report(ProgressEvent.indeterminate("materialize", Messages.get("core.materialize.materializing", distfile.name())));
            materializeDistfile(distfile, source, artifactDirectory);
        }

        List<Path> partitions = resolvePartitionPaths(image.partitionMap(), artifactDirectory);
        if (partitions.isEmpty()) {
            throw new IOException(Messages.get("core.materialize.noPartitionMap", image.atom()));
        }

        for (Path partition : partitions) {
            if (!Files.isRegularFile(partition)) {
                throw new IOException(Messages.get("core.materialize.partitionMissing", partition));
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
        if (method.startsWith("tar.")) {
            try (InputStream input = compressedInputStream(source, compressorNameForMethod(method.substring("tar.".length())))) {
                extractTar(input, artifactDirectory, distfile.stripComponents());
            }
            return;
        }
        if (isSingleStreamCompression(method)) {
            decompressCompressed(source, artifactDirectory.resolve(removeLastExtension(distfile.name())), method);
            return;
        }
        if (UNSUPPORTED_METHODS.contains(method)) {
            throw new IOException(Messages.get("core.materialize.unsupportedMethod", method, distfile.name()));
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

    /// Decompresses a bare single-stream compressed distfile.
    ///
    /// @param source source path.
    /// @param target target path.
    /// @param method compression method.
    /// @throws IOException when decompression fails.
    private static void decompressCompressed(Path source, Path target, String method) throws IOException {
        @Nullable Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (InputStream input = compressedInputStream(source, compressorNameForMethod(method));
             OutputStream output = Files.newOutputStream(target)) {
            input.transferTo(output);
        }
    }

    /// Opens a compressed input stream with a Kala Compress compressor.
    ///
    /// @param source source path.
    /// @param compressorName Kala Compress compressor name.
    /// @return opened decompressor stream.
    /// @throws IOException when the stream cannot be opened.
    private static InputStream compressedInputStream(Path source, String compressorName) throws IOException {
        InputStream input = Files.newInputStream(source);
        try {
            return COMPRESSOR_STREAMS.createCompressorInputStream(compressorName, input);
        } catch (CompressorException exception) {
            try {
                input.close();
            } catch (IOException suppressed) {
                exception.addSuppressed(suppressed);
            }
            throw new IOException(
                    Messages.get("core.materialize.unsupportedMethod", compressorName, source.getFileName()),
                    exception);
        }
    }

    /// Returns whether an unpack method is a bare single-stream compression.
    ///
    /// @param method unpack method.
    /// @return whether the method is a supported bare compressor.
    private static boolean isSingleStreamCompression(String method) {
        return "bz2".equals(method) || "lz4".equals(method) || "xz".equals(method) || "zst".equals(method);
    }

    /// Maps an unpack method suffix to a Kala Compress compressor name.
    ///
    /// @param method unpack method suffix.
    /// @return Kala Compress compressor name.
    /// @throws IOException when the method is unknown.
    private static String compressorNameForMethod(String method) throws IOException {
        return switch (method) {
            case "bz2" -> CompressorStreamFactory.BZIP2;
            case "lz4" -> CompressorStreamFactory.LZ4_FRAMED;
            case "xz" -> CompressorStreamFactory.XZ;
            case "zst" -> CompressorStreamFactory.ZSTANDARD;
            default -> throw new IOException(Messages.get("core.materialize.unsupportedMethod", method, method));
        };
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
        try (TarArchiveInputStream tarInput = new TarArchiveInputStream(input)) {
            @Nullable TarArchiveEntry entry;
            while ((entry = tarInput.getNextEntry()) != null) {
                @Nullable String strippedName = strippedArchiveEntryName(entry.getName(), stripComponents);
                if (strippedName == null) {
                    continue;
                }

                Path target = resolveArchiveTarget(normalizedRoot, strippedName, "Tar");
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                if (!entry.isFile() || !tarInput.canReadEntryData(entry)) {
                    continue;
                }

                @Nullable Path parent = target.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                try (OutputStream output = Files.newOutputStream(target)) {
                    tarInput.transferTo(output);
                }
            }
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

        if (name.matches(".*\\.tar\\.(bz2|lz4|xz|zst)$")) {
            String method = name.substring(name.lastIndexOf('.') + 1);
            try (InputStream input = compressedInputStream(source, compressorNameForMethod(method))) {
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

        throw new IOException(Messages.get("core.materialize.unsupportedMethod", "tar.auto", distfile.name()));
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
            throw new IOException(Messages.get("core.materialize.archiveEscape", archiveKind, entryName));
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
                throw new IOException(Messages.get("core.materialize.partitionEscape", relativePath));
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

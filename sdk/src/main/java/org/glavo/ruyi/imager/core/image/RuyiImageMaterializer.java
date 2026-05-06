// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.image;

import kala.compress.archivers.tar.TarArchiveEntry;
import kala.compress.archivers.tar.TarArchiveInputStream;
import kala.compress.compressors.CompressorException;
import kala.compress.compressors.CompressorStreamFactory;
import org.glavo.ruyi.imager.core.ProgressEvent;
import org.glavo.ruyi.imager.core.ProgressReporter;
import org.glavo.ruyi.imager.core.SdkMessages;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/// Materializes downloaded Ruyi distfiles into flashable image artifacts.
@NotNullByDefault
public final class RuyiImageMaterializer {
    /// Logger for image artifact materialization.
    private static final Logger LOGGER = Logger.getLogger(RuyiImageMaterializer.class.getName());

    /// Compressor stream factory with optional compressor modules loaded through ServiceLoader.
    private static final CompressorStreamFactory COMPRESSOR_STREAMS =
            CompressorStreamFactory.DEFAULT.withInstalledProviders();

    /// Supported unpack methods shown in unsupported-method diagnostics.
    private static final @Unmodifiable List<String> SUPPORTED_METHODS = List.of(
            "raw",
            "gz",
            "bz2",
            "lz4",
            "xz",
            "zst",
            "zip",
            "tar",
            "tar.gz",
            "tar.bz2",
            "tar.lz4",
            "tar.xz",
            "tar.zst",
            "tar.auto",
            "deb");

    /// Unix ar global header used by Debian packages.
    private static final byte @Unmodifiable [] AR_GLOBAL_HEADER =
            "!<arch>\n".getBytes(StandardCharsets.US_ASCII);

    /// Fixed ar member header size.
    private static final int AR_MEMBER_HEADER_SIZE = 60;

    /// Offset of the ar member size field.
    private static final int AR_MEMBER_SIZE_OFFSET = 48;

    /// Length of the ar member size field.
    private static final int AR_MEMBER_SIZE_LENGTH = 10;

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
        LOGGER.info(() -> "Materializing image. atom="
                + image.atom()
                + ", distfiles="
                + downloadedDistfiles.size()
                + ", artifactDirectory="
                + artifactDirectory);
        if (downloadedDistfiles.size() != image.distfiles().size()) {
            LOGGER.warning(() -> "Materialized distfile count mismatch. atom="
                    + image.atom()
                    + ", expected="
                    + image.distfiles().size()
                    + ", actual="
                    + downloadedDistfiles.size());
            throw new IOException(SdkMessages.get("core.materialize.countMismatch"));
        }

        for (int i = 0; i < image.distfiles().size(); i++) {
            RuyiDistfile distfile = image.distfiles().get(i);
            Path source = downloadedDistfiles.get(i);
            reporter.report(ProgressEvent.indeterminate("materialize", SdkMessages.get("core.materialize.materializing", distfile.name())));
            materializeDistfile(distfile, source, artifactDirectory);
        }

        List<Path> partitions = resolvePartitionPaths(image.partitionMap(), artifactDirectory);
        if (partitions.isEmpty()) {
            throw new IOException(SdkMessages.get("core.materialize.noPartitionMap", image.atom()));
        }

        for (Path partition : partitions) {
            if (!Files.isRegularFile(partition)) {
                LOGGER.warning(() -> "Materialized partition is missing. atom="
                        + image.atom()
                        + ", partition="
                        + partition);
                throw new IOException(SdkMessages.get("core.materialize.partitionMissing", partition));
            }
        }

        Path result = partitions.size() == 1 ? partitions.getFirst() : artifactDirectory;
        LOGGER.info(() -> "Image materialized. atom=" + image.atom() + ", result=" + result);
        return result;
    }

    /// Materializes one distfile.
    ///
    /// @param distfile distfile metadata.
    /// @param source downloaded distfile path.
    /// @param artifactDirectory output artifact directory.
    /// @throws IOException when the distfile cannot be materialized.
    private static void materializeDistfile(RuyiDistfile distfile, Path source, Path artifactDirectory) throws IOException {
        String method = resolveUnpackMethod(distfile);
        LOGGER.info(() -> "Materializing distfile. name="
                + distfile.name()
                + ", method="
                + method
                + ", source="
                + source
                + ", artifactDirectory="
                + artifactDirectory);
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
                extractTar(input, artifactDirectory, distfile);
            }
            return;
        }
        if ("tar.gz".equals(method)) {
            try (InputStream input = new GZIPInputStream(Files.newInputStream(source))) {
                extractTar(input, artifactDirectory, distfile);
            }
            return;
        }
        if ("tar.auto".equals(method)) {
            extractAutoTar(distfile, source, artifactDirectory);
            return;
        }
        if ("deb".equals(method)) {
            extractDeb(distfile, source, artifactDirectory);
            return;
        }
        if (method.startsWith("tar.")) {
            @Nullable String compressorName = compressorNameForMethod(method.substring("tar.".length()));
            if (compressorName == null) {
                throw unsupportedMethod(method, distfile, source, artifactDirectory);
            }
            try (InputStream input = compressedInputStream(source, compressorName)) {
                extractTar(input, artifactDirectory, distfile);
            }
            return;
        }
        if (isSingleStreamCompression(method)) {
            decompressCompressed(
                    distfile,
                    source,
                    artifactDirectory.resolve(removeLastExtension(distfile.name())),
                    artifactDirectory,
                    method);
            return;
        }
        if (isExplicitUnpackMethod(distfile.unpack())) {
            throw unsupportedMethod(method, distfile, source, artifactDirectory);
        }

        copyRaw(source, artifactDirectory.resolve(distfile.name()));
    }

    /// Resolves the effective unpack method.
    ///
    /// @param distfile distfile metadata.
    /// @return effective unpack method.
    private static String resolveUnpackMethod(RuyiDistfile distfile) {
        @Nullable String declared = distfile.unpack();
        if (isExplicitUnpackMethod(declared)) {
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

    /// Returns whether an unpack method was explicitly declared by package metadata.
    ///
    /// @param method unpack method.
    /// @return whether the method is explicit and should not fall back to raw copying.
    private static boolean isExplicitUnpackMethod(@Nullable String method) {
        return method != null && !method.isBlank() && !"auto".equals(method);
    }

    /// Copies a raw file into the artifact directory.
    ///
    /// @param source source path.
    /// @param target target path.
    /// @throws IOException when the copy fails.
    private static void copyRaw(Path source, Path target) throws IOException {
        LOGGER.fine(() -> "Copying raw distfile. source=" + source + ", target=" + target);
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
        LOGGER.fine(() -> "Decompressing gzip distfile. source=" + source + ", target=" + target);
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
    /// @param distfile distfile metadata.
    /// @param source source path.
    /// @param target target path.
    /// @param artifactDirectory output artifact directory.
    /// @param method compression method.
    /// @throws IOException when decompression fails.
    private static void decompressCompressed(
            RuyiDistfile distfile,
            Path source,
            Path target,
            Path artifactDirectory,
            String method) throws IOException {
        LOGGER.fine(() -> "Decompressing compressed distfile. method="
                + method
                + ", source="
                + source
                + ", target="
                + target);
        @Nullable Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        @Nullable String compressorName = compressorNameForMethod(method);
        if (compressorName == null) {
            throw unsupportedMethod(method, distfile, source, artifactDirectory);
        }
        try (InputStream input = compressedInputStream(source, compressorName);
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
        return compressedInputStream(input, compressorName, source);
    }

    /// Opens a compressed input stream with a Kala Compress compressor.
    ///
    /// @param input backing compressed stream.
    /// @param compressorName Kala Compress compressor name.
    /// @param source source shown in diagnostics.
    /// @return opened decompressor stream.
    /// @throws IOException when the stream cannot be opened.
    private static InputStream compressedInputStream(
            InputStream input,
            String compressorName,
            Object source) throws IOException {
        try {
            return COMPRESSOR_STREAMS.createCompressorInputStream(compressorName, input);
        } catch (CompressorException exception) {
            try {
                input.close();
            } catch (IOException suppressed) {
                exception.addSuppressed(suppressed);
            }
            throw new IOException(
                    SdkMessages.get("core.materialize.compressorFailed", compressorName, source),
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
    /// @return Kala Compress compressor name, or null when the method is unknown.
    private static @Nullable String compressorNameForMethod(String method) {
        return switch (method) {
            case "bz2" -> CompressorStreamFactory.BZIP2;
            case "lz4" -> CompressorStreamFactory.LZ4_FRAMED;
            case "xz" -> CompressorStreamFactory.XZ;
            case "zst" -> CompressorStreamFactory.ZSTANDARD;
            default -> null;
        };
    }

    /// Extracts a zip archive into the artifact directory.
    ///
    /// @param source source path.
    /// @param artifactDirectory output artifact directory.
    /// @param stripComponents leading path components to strip from each entry.
    /// @throws IOException when extraction fails or the archive attempts path traversal.
    private static void extractZip(Path source, Path artifactDirectory, int stripComponents) throws IOException {
        LOGGER.fine(() -> "Extracting zip distfile. source="
                + source
                + ", artifactDirectory="
                + artifactDirectory
                + ", stripComponents="
                + stripComponents);
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
    /// @param distfile distfile metadata.
    /// @throws IOException when extraction fails or the archive attempts path traversal.
    private static void extractTar(InputStream input, Path artifactDirectory, RuyiDistfile distfile) throws IOException {
        LOGGER.fine(() -> "Extracting tar distfile. name="
                + distfile.name()
                + ", artifactDirectory="
                + artifactDirectory
                + ", stripComponents="
                + distfile.stripComponents()
                + ", prefixes="
                + distfile.prefixesToUnpack());
        extractTarEntries(input, artifactDirectory, distfile.stripComponents(), checkedPrefixes(distfile));
    }

    /// Extracts tar entries into the artifact directory.
    ///
    /// @param input tar input stream.
    /// @param artifactDirectory output artifact directory.
    /// @param stripComponents leading path components to strip from each entry.
    /// @param prefixesToUnpack archive path prefixes to extract.
    /// @throws IOException when extraction fails or the archive attempts path traversal.
    private static void extractTarEntries(
            InputStream input,
            Path artifactDirectory,
            int stripComponents,
            @Unmodifiable List<String> prefixesToUnpack) throws IOException {
        Path normalizedRoot = artifactDirectory.toAbsolutePath().normalize();
        try (TarArchiveInputStream tarInput = new TarArchiveInputStream(input)) {
            @Nullable TarArchiveEntry entry;
            while ((entry = tarInput.getNextEntry()) != null) {
                String entryName = entry.getName();
                if (!matchesArchivePrefixes(entryName, prefixesToUnpack)) {
                    continue;
                }

                @Nullable String strippedName = strippedArchiveEntryName(entryName, stripComponents);
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

    /// Validates tar path prefixes from package metadata.
    ///
    /// @param distfile distfile metadata.
    /// @return validated prefixes.
    /// @throws IOException when a prefix is unsafe.
    private static @Unmodifiable List<String> checkedPrefixes(RuyiDistfile distfile) throws IOException {
        for (String prefix : distfile.prefixesToUnpack()) {
            if (prefix.startsWith("-")) {
                throw new IOException(SdkMessages.get("core.materialize.invalidPrefix", distfile.name(), prefix));
            }
        }
        return distfile.prefixesToUnpack();
    }

    /// Returns whether a tar entry should be extracted under the configured prefixes.
    ///
    /// @param entryName archive entry name.
    /// @param prefixesToUnpack archive path prefixes to extract.
    /// @return whether the entry should be extracted.
    private static boolean matchesArchivePrefixes(String entryName, @Unmodifiable List<String> prefixesToUnpack) {
        if (prefixesToUnpack.isEmpty()) {
            return true;
        }

        String normalizedEntryName = entryName.replace('\\', '/');
        for (String prefix : prefixesToUnpack) {
            String normalizedPrefix = prefix.replace('\\', '/');
            String nestedPrefix = normalizedPrefix.endsWith("/") ? normalizedPrefix : normalizedPrefix + "/";
            if (normalizedPrefix.isEmpty()
                    || normalizedEntryName.equals(normalizedPrefix)
                    || normalizedEntryName.startsWith(nestedPrefix)) {
                return true;
            }
        }
        return false;
    }

    /// Extracts a tar archive with compression inferred from the distfile name.
    ///
    /// @param distfile distfile metadata.
    /// @param source source path.
    /// @param artifactDirectory output artifact directory.
    /// @throws IOException when extraction fails.
    private static void extractAutoTar(RuyiDistfile distfile, Path source, Path artifactDirectory) throws IOException {
        LOGGER.fine(() -> "Detecting tar compression. name=" + distfile.name() + ", source=" + source);
        String name = distfile.name().toLowerCase(Locale.ROOT);
        if (name.endsWith(".tar.gz") || name.endsWith(".tgz")) {
            try (InputStream input = new GZIPInputStream(Files.newInputStream(source))) {
                extractTar(input, artifactDirectory, distfile);
            }
            return;
        }

        if (name.matches(".*\\.tar\\.(bz2|lz4|xz|zst)$")) {
            String method = name.substring(name.lastIndexOf('.') + 1);
            @Nullable String compressorName = compressorNameForMethod(method);
            if (compressorName == null) {
                throw unsupportedMethod("tar.auto", distfile, source, artifactDirectory);
            }
            try (InputStream input = compressedInputStream(source, compressorName)) {
                extractTar(input, artifactDirectory, distfile);
            }
            return;
        }

        if (name.endsWith(".tar")) {
            try (InputStream input = Files.newInputStream(source)) {
                extractTar(input, artifactDirectory, distfile);
            }
            return;
        }

        throw unsupportedMethod("tar.auto", distfile, source, artifactDirectory);
    }

    /// Extracts the data tarball inside a Debian package.
    ///
    /// @param distfile distfile metadata.
    /// @param source downloaded deb path.
    /// @param artifactDirectory output artifact directory.
    /// @throws IOException when the deb package is invalid or the data tarball cannot be extracted.
    private static void extractDeb(RuyiDistfile distfile, Path source, Path artifactDirectory) throws IOException {
        LOGGER.fine(() -> "Extracting Debian distfile. name=" + distfile.name() + ", source=" + source);
        try (InputStream input = Files.newInputStream(source)) {
            byte[] globalHeader = input.readNBytes(AR_GLOBAL_HEADER.length);
            if (!Arrays.equals(globalHeader, AR_GLOBAL_HEADER)) {
                throw invalidDeb(source);
            }

            while (true) {
                @Nullable ArMember member = readArMember(input, source);
                if (member == null) {
                    break;
                }

                BoundedInputStream entryInput = new BoundedInputStream(input, member.size());
                if (member.name().startsWith("data.tar")) {
                    LOGGER.info(() -> "Found Debian data archive. source=" + source + ", member=" + member.name());
                    extractDebDataTar(distfile, source, artifactDirectory, member.name(), entryInput);
                    return;
                }

                entryInput.skipRemaining();
                skipArPadding(input, member.size());
            }
        }

        throw invalidDeb(source);
    }

    /// Extracts one `data.tar*` member from a Debian package.
    ///
    /// @param distfile outer distfile metadata.
    /// @param source outer deb path.
    /// @param artifactDirectory output artifact directory.
    /// @param memberName ar member name.
    /// @param input member stream.
    /// @throws IOException when the member cannot be extracted.
    private static void extractDebDataTar(
            RuyiDistfile distfile,
            Path source,
            Path artifactDirectory,
            String memberName,
            InputStream input) throws IOException {
        String method = tarMethodForDebData(memberName);
        if ("tar".equals(method)) {
            extractTar(input, artifactDirectory, distfile);
            return;
        }
        if ("tar.gz".equals(method)) {
            try (InputStream gzipInput = new GZIPInputStream(input)) {
                extractTar(gzipInput, artifactDirectory, distfile);
            }
            return;
        }

        @Nullable String compressorName = compressorNameForMethod(method.substring("tar.".length()));
        if (compressorName == null) {
            throw unsupportedMethod("deb:" + memberName, distfile, source, artifactDirectory);
        }
        try (InputStream compressedInput = compressedInputStream(input, compressorName, source + "!" + memberName)) {
            extractTar(compressedInput, artifactDirectory, distfile);
        }
    }

    /// Resolves the tar unpack method for a deb `data.tar*` member.
    ///
    /// @param memberName ar member name.
    /// @return tar unpack method.
    private static String tarMethodForDebData(String memberName) {
        String name = memberName.toLowerCase(Locale.ROOT);
        if (name.endsWith(".tar")) {
            return "tar";
        }
        if (name.endsWith(".tar.gz") || name.endsWith(".tgz")) {
            return "tar.gz";
        }
        if (name.matches(".*\\.tar\\.(bz2|lz4|xz|zst)$")) {
            return "tar." + name.substring(name.lastIndexOf('.') + 1);
        }
        return "tar.auto";
    }

    /// Reads one ar member header.
    ///
    /// @param input deb package stream.
    /// @param source source shown in diagnostics.
    /// @return ar member, or null at end of archive.
    /// @throws IOException when the ar header is invalid.
    private static @Nullable ArMember readArMember(InputStream input, Path source) throws IOException {
        byte[] header = input.readNBytes(AR_MEMBER_HEADER_SIZE);
        if (header.length == 0) {
            return null;
        }
        if (header.length != AR_MEMBER_HEADER_SIZE || header[58] != '`' || header[59] != '\n') {
            throw invalidDeb(source);
        }

        String name = new String(header, 0, 16, StandardCharsets.US_ASCII).strip();
        if (name.endsWith("/")) {
            name = name.substring(0, name.length() - 1);
        }
        long size = arDecimal(header, AR_MEMBER_SIZE_OFFSET, AR_MEMBER_SIZE_LENGTH, source);
        return new ArMember(name, size);
    }

    /// Parses one decimal ar header field.
    ///
    /// @param header ar member header.
    /// @param offset field offset.
    /// @param length field length.
    /// @param source source shown in diagnostics.
    /// @return parsed value.
    /// @throws IOException when the field is invalid.
    private static long arDecimal(byte @Unmodifiable [] header, int offset, int length, Path source) throws IOException {
        String value = new String(header, offset, length, StandardCharsets.US_ASCII).strip();
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            IOException exception = invalidDeb(source);
            exception.addSuppressed(e);
            throw exception;
        }
    }

    /// Skips the ar padding byte after odd-sized members.
    ///
    /// @param input deb package stream.
    /// @param size member size.
    /// @throws IOException when the stream ends early.
    private static void skipArPadding(InputStream input, long size) throws IOException {
        if ((size & 1L) != 0L) {
            skipFully(input, 1L);
        }
    }

    /// Skips a known byte count or fails on early EOF.
    ///
    /// @param input input stream.
    /// @param size byte count.
    /// @throws IOException when the stream ends early.
    private static void skipFully(InputStream input, long size) throws IOException {
        long remaining = size;
        while (remaining > 0L) {
            long skipped = input.skip(remaining);
            if (skipped > 0L) {
                remaining -= skipped;
                continue;
            }
            if (input.read() < 0) {
                throw new EOFException();
            }
            remaining--;
        }
    }

    /// Creates an invalid Debian package exception.
    ///
    /// @param source source path.
    /// @return invalid deb exception.
    private static IOException invalidDeb(Path source) {
        return new IOException(SdkMessages.get("core.materialize.invalidDeb", source.toAbsolutePath().normalize()));
    }

    /// Creates an unsupported unpack method exception with manual recovery paths.
    ///
    /// @param method unsupported unpack method.
    /// @param distfile distfile metadata.
    /// @param source downloaded distfile path.
    /// @param artifactDirectory output artifact directory.
    /// @return unsupported unpack method exception.
    private static IOException unsupportedMethod(
            String method,
            RuyiDistfile distfile,
            Path source,
            Path artifactDirectory) {
        return new IOException(SdkMessages.get(
                "core.materialize.unsupportedMethod",
                method,
                distfile.name(),
                String.join(", ", SUPPORTED_METHODS),
                source.toAbsolutePath().normalize(),
                artifactDirectory.toAbsolutePath().normalize()));
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
            throw new IOException(SdkMessages.get("core.materialize.archiveEscape", archiveKind, entryName));
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
                throw new IOException(SdkMessages.get("core.materialize.partitionEscape", relativePath));
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

    /// One Unix ar member.
    ///
    /// @param name member name.
    /// @param size member byte size.
    @NotNullByDefault
    private record ArMember(String name, long size) {
    }

    /// Input stream wrapper that exposes at most one ar member body.
    @NotNullByDefault
    private static final class BoundedInputStream extends InputStream {
        /// Backing package stream.
        private final InputStream input;

        /// Remaining bytes in the current member.
        private long remaining;

        /// Creates a bounded stream.
        ///
        /// @param input backing package stream.
        /// @param remaining available byte count.
        private BoundedInputStream(InputStream input, long remaining) {
            this.input = input;
            this.remaining = remaining;
        }

        /// Reads one byte from the current member.
        ///
        /// @return byte value, or -1 when the member is fully consumed.
        /// @throws IOException when the backing stream ends early.
        @Override
        public int read() throws IOException {
            if (remaining == 0L) {
                return -1;
            }
            int value = input.read();
            if (value < 0) {
                throw new EOFException();
            }
            remaining--;
            return value;
        }

        /// Reads bytes from the current member.
        ///
        /// @param buffer destination buffer.
        /// @param offset destination offset.
        /// @param length maximum bytes to read.
        /// @return bytes read, or -1 when the member is fully consumed.
        /// @throws IOException when the backing stream ends early.
        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (remaining == 0L) {
                return -1;
            }
            int requested = (int) Math.min(length, remaining);
            int read = input.read(buffer, offset, requested);
            if (read < 0) {
                throw new EOFException();
            }
            remaining -= read;
            return read;
        }

        /// Skips any unread bytes in the current member.
        ///
        /// @throws IOException when the backing stream ends early.
        private void skipRemaining() throws IOException {
            skipFully(this, remaining);
        }
    }
}

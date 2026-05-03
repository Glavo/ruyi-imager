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
            "tar",
            "tar.auto",
            "tar.gz",
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
            extractZip(source, artifactDirectory);
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
    /// @throws IOException when extraction fails or the archive attempts path traversal.
    private static void extractZip(Path source, Path artifactDirectory) throws IOException {
        Path normalizedRoot = artifactDirectory.toAbsolutePath().normalize();
        try (ZipInputStream input = new ZipInputStream(Files.newInputStream(source))) {
            while (true) {
                @Nullable ZipEntry entry = input.getNextEntry();
                if (entry == null) {
                    break;
                }

                Path target = normalizedRoot.resolve(entry.getName()).normalize();
                if (!target.startsWith(normalizedRoot)) {
                    throw new IOException("Zip entry escapes artifact directory: " + entry.getName());
                }

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

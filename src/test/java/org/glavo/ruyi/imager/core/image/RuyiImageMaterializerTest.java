// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.image;

import org.glavo.ruyi.imager.core.ProgressReporter;
import org.glavo.ruyi.imager.core.StrategySupport;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests for Ruyi image artifact materialization.
@NotNullByDefault
public final class RuyiImageMaterializerTest {
    /// Progress reporter that ignores progress events.
    private static final ProgressReporter NO_PROGRESS = _ -> {
    };

    /// Verifies raw distfiles are copied into the artifact directory.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when fixture files cannot be written or read.
    @Test
    public void materializesRawDistfile(@TempDir Path temporaryDirectory) throws Exception {
        byte[] content = "raw image".getBytes(StandardCharsets.UTF_8);
        Path source = temporaryDirectory.resolve("downloads").resolve("image.raw");
        Files.createDirectories(source.getParent());
        Files.write(source, content);

        Path artifactDirectory = temporaryDirectory.resolve("artifacts");
        ImageEntry image = image("image.raw", null, "image.raw");
        Path result = new RuyiImageMaterializer().materialize(image, List.of(source), artifactDirectory, NO_PROGRESS);

        assertEquals(artifactDirectory.resolve("image.raw").toAbsolutePath().normalize(), result);
        assertArrayEquals(content, Files.readAllBytes(result));
    }

    /// Verifies bare gzip distfiles are decompressed.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when fixture files cannot be written or read.
    @Test
    public void materializesGzipDistfile(@TempDir Path temporaryDirectory) throws Exception {
        byte[] content = "gzip image".getBytes(StandardCharsets.UTF_8);
        Path source = temporaryDirectory.resolve("downloads").resolve("image.raw.gz");
        Files.createDirectories(source.getParent());
        try (GZIPOutputStream output = new GZIPOutputStream(Files.newOutputStream(source))) {
            output.write(content);
        }

        Path artifactDirectory = temporaryDirectory.resolve("artifacts");
        ImageEntry image = image("image.raw.gz", null, "image.raw");
        Path result = new RuyiImageMaterializer().materialize(image, List.of(source), artifactDirectory, NO_PROGRESS);

        assertEquals(artifactDirectory.resolve("image.raw").toAbsolutePath().normalize(), result);
        assertArrayEquals(content, Files.readAllBytes(result));
    }

    /// Verifies zip distfiles are extracted into the artifact directory.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when fixture files cannot be written or read.
    @Test
    public void materializesZipDistfile(@TempDir Path temporaryDirectory) throws Exception {
        byte[] content = "zip image".getBytes(StandardCharsets.UTF_8);
        Path source = temporaryDirectory.resolve("downloads").resolve("image.zip");
        Files.createDirectories(source.getParent());
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(source))) {
            output.putNextEntry(new ZipEntry("images/image.raw"));
            output.write(content);
            output.closeEntry();
        }

        Path artifactDirectory = temporaryDirectory.resolve("artifacts");
        ImageEntry image = image("image.zip", null, "images/image.raw");
        Path result = new RuyiImageMaterializer().materialize(image, List.of(source), artifactDirectory, NO_PROGRESS);

        assertEquals(artifactDirectory.resolve("images").resolve("image.raw").toAbsolutePath().normalize(), result);
        assertArrayEquals(content, Files.readAllBytes(result));
    }

    /// Verifies tar distfiles are extracted into the artifact directory.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when fixture files cannot be written or read.
    @Test
    public void materializesTarDistfile(@TempDir Path temporaryDirectory) throws Exception {
        byte[] content = "tar image".getBytes(StandardCharsets.UTF_8);
        Path source = temporaryDirectory.resolve("downloads").resolve("image.tar");
        Files.createDirectories(source.getParent());
        writeTar(source, "images/image.raw", content);

        Path artifactDirectory = temporaryDirectory.resolve("artifacts");
        ImageEntry image = image("image.tar", null, "images/image.raw");
        Path result = new RuyiImageMaterializer().materialize(image, List.of(source), artifactDirectory, NO_PROGRESS);

        assertEquals(artifactDirectory.resolve("images").resolve("image.raw").toAbsolutePath().normalize(), result);
        assertArrayEquals(content, Files.readAllBytes(result));
    }

    /// Verifies tar.gz distfiles and strip-components are handled.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when fixture files cannot be written or read.
    @Test
    public void materializesTarGzDistfileWithStripComponents(@TempDir Path temporaryDirectory) throws Exception {
        byte[] content = "tar gzip image".getBytes(StandardCharsets.UTF_8);
        Path source = temporaryDirectory.resolve("downloads").resolve("image.tar.gz");
        Files.createDirectories(source.getParent());
        try (GZIPOutputStream output = new GZIPOutputStream(Files.newOutputStream(source))) {
            writeTar(output, "root/images/image.raw", content);
        }

        Path artifactDirectory = temporaryDirectory.resolve("artifacts");
        ImageEntry image = image("image.tar.gz", "tar.gz", 1, "images/image.raw");
        Path result = new RuyiImageMaterializer().materialize(image, List.of(source), artifactDirectory, NO_PROGRESS);

        assertEquals(artifactDirectory.resolve("images").resolve("image.raw").toAbsolutePath().normalize(), result);
        assertArrayEquals(content, Files.readAllBytes(result));
    }

    /// Verifies tar path traversal entries are rejected.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when fixture files cannot be written.
    @Test
    public void rejectsTarPathTraversal(@TempDir Path temporaryDirectory) throws Exception {
        Path source = temporaryDirectory.resolve("downloads").resolve("image.tar");
        Files.createDirectories(source.getParent());
        writeTar(source, "../image.raw", new byte[]{1});

        ImageEntry image = image("image.tar", null, "image.raw");

        assertThrows(IOException.class, () -> new RuyiImageMaterializer().materialize(
                image,
                List.of(source),
                temporaryDirectory.resolve("artifacts"),
                NO_PROGRESS));
    }

    /// Verifies unsupported archive formats fail explicitly.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when fixture files cannot be written.
    @Test
    public void rejectsUnsupportedArchiveFormat(@TempDir Path temporaryDirectory) throws Exception {
        Path source = temporaryDirectory.resolve("downloads").resolve("image.tar.xz");
        Files.createDirectories(source.getParent());
        Files.write(source, new byte[]{1, 2, 3});

        ImageEntry image = image("image.tar.xz", null, "image.raw");

        assertThrows(IOException.class, () -> new RuyiImageMaterializer().materialize(
                image,
                List.of(source),
                temporaryDirectory.resolve("artifacts"),
                NO_PROGRESS));
    }

    /// Creates a minimal image entry.
    ///
    /// @param distfileName distfile name.
    /// @param unpack unpack method.
    /// @param partitionPath partition image path.
    /// @return image entry.
    private static ImageEntry image(String distfileName, @Nullable String unpack, String partitionPath) {
        return image(distfileName, unpack, 0, partitionPath);
    }

    /// Creates a minimal image entry.
    ///
    /// @param distfileName distfile name.
    /// @param unpack unpack method.
    /// @param stripComponents archive path components to strip.
    /// @param partitionPath partition image path.
    /// @return image entry.
    private static ImageEntry image(
            String distfileName,
            @Nullable String unpack,
            int stripComponents,
            String partitionPath) {
        RuyiDistfile distfile = new RuyiDistfile(
                distfileName,
                List.of(URI.create("https://example.invalid/" + distfileName)),
                null,
                Map.of(),
                false,
                true,
                stripComponents,
                unpack);
        return new ImageEntry(
                "ruyisdk",
                "board-image",
                "test-board",
                "1.0.0",
                null,
                "board-image/test-board(1.0.0)",
                "Test image",
                "test-board",
                "generic",
                "dd-v1",
                Map.of("disk", partitionPath),
                List.of(distfile),
                StrategySupport.SUPPORTED);
    }

    /// Writes a one-file tar archive.
    ///
    /// @param target target archive path.
    /// @param entryName archive entry name.
    /// @param content entry content.
    /// @throws IOException when writing fails.
    private static void writeTar(Path target, String entryName, byte[] content) throws IOException {
        try (OutputStream output = Files.newOutputStream(target)) {
            writeTar(output, entryName, content);
        }
    }

    /// Writes a one-file tar archive.
    ///
    /// @param output target stream.
    /// @param entryName archive entry name.
    /// @param content entry content.
    /// @throws IOException when writing fails.
    private static void writeTar(OutputStream output, String entryName, byte[] content) throws IOException {
        byte[] header = new byte[512];
        byte[] name = entryName.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(name, 0, header, 0, Math.min(name.length, 100));
        writeOctal(header, 100, 8, 0644);
        writeOctal(header, 108, 8, 0);
        writeOctal(header, 116, 8, 0);
        writeOctal(header, 124, 12, content.length);
        writeOctal(header, 136, 12, 0);
        for (int i = 148; i < 156; i++) {
            header[i] = ' ';
        }
        header[156] = '0';
        byte[] magic = "ustar".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(magic, 0, header, 257, magic.length);
        header[263] = '0';
        header[264] = '0';

        long checksum = 0L;
        for (byte value : header) {
            checksum += Byte.toUnsignedInt(value);
        }
        writeOctal(header, 148, 8, checksum);

        output.write(header);
        output.write(content);
        int padding = (512 - (content.length % 512)) % 512;
        if (padding > 0) {
            output.write(new byte[padding]);
        }
        output.write(new byte[1024]);
    }

    /// Writes an octal tar header field.
    ///
    /// @param header tar header.
    /// @param offset field offset.
    /// @param length field length.
    /// @param value numeric value.
    private static void writeOctal(byte[] header, int offset, int length, long value) {
        String text = String.format(Locale.ROOT, "%0" + (length - 1) + "o", value);
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        int start = Math.max(0, bytes.length - (length - 1));
        int count = bytes.length - start;
        System.arraycopy(bytes, start, header, offset + length - 1 - count, count);
        header[offset + length - 1] = 0;
    }
}

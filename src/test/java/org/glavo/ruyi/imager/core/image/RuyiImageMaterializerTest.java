// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.image;

import kala.compress.compressors.CompressorException;
import kala.compress.compressors.CompressorOutputStream;
import kala.compress.compressors.CompressorStreamFactory;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for Ruyi image artifact materialization.
@NotNullByDefault
public final class RuyiImageMaterializerTest {
    /// Progress reporter that ignores progress events.
    private static final ProgressReporter NO_PROGRESS = _ -> {
    };

    /// Compressor stream factory with optional compressor modules loaded through ServiceLoader.
    private static final CompressorStreamFactory COMPRESSOR_STREAMS =
            CompressorStreamFactory.DEFAULT.withInstalledProviders();

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

    /// Verifies tar.xz distfiles are extracted into the artifact directory.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when fixture files cannot be written or read.
    @Test
    public void materializesTarXzDistfile(@TempDir Path temporaryDirectory) throws Exception {
        byte[] content = "tar xz image".getBytes(StandardCharsets.UTF_8);
        Path source = temporaryDirectory.resolve("downloads").resolve("image.tar.xz");
        Files.createDirectories(source.getParent());
        writeCompressedTar(source, CompressorStreamFactory.XZ, "images/image.raw", content);

        Path artifactDirectory = temporaryDirectory.resolve("artifacts");
        ImageEntry image = image("image.tar.xz", null, "images/image.raw");
        Path result = new RuyiImageMaterializer().materialize(image, List.of(source), artifactDirectory, NO_PROGRESS);

        assertEquals(artifactDirectory.resolve("images").resolve("image.raw").toAbsolutePath().normalize(), result);
        assertArrayEquals(content, Files.readAllBytes(result));
    }

    /// Verifies declared tar.bz2 distfiles are extracted into the artifact directory.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when fixture files cannot be written or read.
    @Test
    public void materializesDeclaredTarBz2Distfile(@TempDir Path temporaryDirectory) throws Exception {
        byte[] content = "tar bzip2 image".getBytes(StandardCharsets.UTF_8);
        Path source = temporaryDirectory.resolve("downloads").resolve("image.tbz2");
        Files.createDirectories(source.getParent());
        writeCompressedTar(source, CompressorStreamFactory.BZIP2, "images/image.raw", content);

        Path artifactDirectory = temporaryDirectory.resolve("artifacts");
        ImageEntry image = image("image.tbz2", "tar.bz2", "images/image.raw");
        Path result = new RuyiImageMaterializer().materialize(image, List.of(source), artifactDirectory, NO_PROGRESS);

        assertEquals(artifactDirectory.resolve("images").resolve("image.raw").toAbsolutePath().normalize(), result);
        assertArrayEquals(content, Files.readAllBytes(result));
    }

    /// Verifies tar.zst distfiles are extracted into the artifact directory.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when fixture files cannot be written or read.
    @Test
    public void materializesTarZstDistfile(@TempDir Path temporaryDirectory) throws Exception {
        byte[] content = "tar zstd image".getBytes(StandardCharsets.UTF_8);
        Path source = temporaryDirectory.resolve("downloads").resolve("image.tar.zst");
        Files.createDirectories(source.getParent());
        writeCompressedTar(source, CompressorStreamFactory.ZSTANDARD, "images/image.raw", content);

        Path artifactDirectory = temporaryDirectory.resolve("artifacts");
        ImageEntry image = image("image.tar.zst", null, "images/image.raw");
        Path result = new RuyiImageMaterializer().materialize(image, List.of(source), artifactDirectory, NO_PROGRESS);

        assertEquals(artifactDirectory.resolve("images").resolve("image.raw").toAbsolutePath().normalize(), result);
        assertArrayEquals(content, Files.readAllBytes(result));
    }

    /// Verifies bare xz distfiles are decompressed.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when fixture files cannot be written or read.
    @Test
    public void materializesBareXzDistfile(@TempDir Path temporaryDirectory) throws Exception {
        byte[] content = "xz image".getBytes(StandardCharsets.UTF_8);
        Path source = temporaryDirectory.resolve("downloads").resolve("image.raw.xz");
        Files.createDirectories(source.getParent());
        try (OutputStream output = compressorOutputStream(Files.newOutputStream(source), CompressorStreamFactory.XZ)) {
            output.write(content);
        }

        Path artifactDirectory = temporaryDirectory.resolve("artifacts");
        ImageEntry image = image("image.raw.xz", null, "image.raw");
        Path result = new RuyiImageMaterializer().materialize(image, List.of(source), artifactDirectory, NO_PROGRESS);

        assertEquals(artifactDirectory.resolve("image.raw").toAbsolutePath().normalize(), result);
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
        Path source = temporaryDirectory.resolve("downloads").resolve("image.deb");
        Files.createDirectories(source.getParent());
        Files.write(source, new byte[]{1, 2, 3});

        Path artifactDirectory = temporaryDirectory.resolve("artifacts");
        ImageEntry image = image("image.deb", null, "image.raw");

        IOException exception = assertThrows(IOException.class, () -> new RuyiImageMaterializer().materialize(
                image,
                List.of(source),
                artifactDirectory,
                NO_PROGRESS));
        String message = exception.getMessage();
        assertTrue(message.contains("deb"), message);
        assertTrue(message.contains("image.deb"), message);
        assertTrue(message.contains("raw"), message);
        assertTrue(message.contains("tar.zst"), message);
        assertTrue(message.contains(source.toAbsolutePath().normalize().toString()), message);
        assertTrue(message.contains(artifactDirectory.toAbsolutePath().normalize().toString()), message);
    }

    /// Verifies unknown declared unpack methods are not silently copied as raw files.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when fixture files cannot be written.
    @Test
    public void rejectsUnknownDeclaredUnpackMethod(@TempDir Path temporaryDirectory) throws Exception {
        Path source = temporaryDirectory.resolve("downloads").resolve("image.bin");
        Files.createDirectories(source.getParent());
        Files.write(source, new byte[]{1, 2, 3});

        Path artifactDirectory = temporaryDirectory.resolve("artifacts");
        ImageEntry image = image("image.bin", "custom", "image.bin");

        IOException exception = assertThrows(IOException.class, () -> new RuyiImageMaterializer().materialize(
                image,
                List.of(source),
                artifactDirectory,
                NO_PROGRESS));
        String message = exception.getMessage();
        assertTrue(message.contains("custom"), message);
        assertTrue(message.contains("image.bin"), message);
        assertTrue(message.contains(source.toAbsolutePath().normalize().toString()), message);
        assertTrue(message.contains(artifactDirectory.toAbsolutePath().normalize().toString()), message);
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
                "Test Manufacturer",
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

    /// Writes a one-file compressed tar archive.
    ///
    /// @param target target archive path.
    /// @param compressorName Kala Compress compressor name.
    /// @param entryName archive entry name.
    /// @param content entry content.
    /// @throws IOException when writing fails.
    private static void writeCompressedTar(
            Path target,
            String compressorName,
            String entryName,
            byte[] content) throws IOException {
        try (OutputStream output = compressorOutputStream(Files.newOutputStream(target), compressorName)) {
            writeTar(output, entryName, content);
        }
    }

    /// Opens a compressed output stream.
    ///
    /// @param output backing output stream.
    /// @param compressorName Kala Compress compressor name.
    /// @return compressor output stream.
    /// @throws IOException when the compressor cannot be created.
    private static CompressorOutputStream<?> compressorOutputStream(
            OutputStream output,
            String compressorName) throws IOException {
        try {
            return COMPRESSOR_STREAMS.createCompressorOutputStream(compressorName, output);
        } catch (CompressorException exception) {
            try {
                output.close();
            } catch (IOException suppressed) {
                exception.addSuppressed(suppressed);
            }
            throw new IOException(exception);
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

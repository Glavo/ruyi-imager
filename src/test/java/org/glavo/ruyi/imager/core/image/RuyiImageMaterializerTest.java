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
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
        RuyiDistfile distfile = new RuyiDistfile(
                distfileName,
                List.of(URI.create("https://example.invalid/" + distfileName)),
                null,
                Map.of(),
                false,
                true,
                0,
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
}

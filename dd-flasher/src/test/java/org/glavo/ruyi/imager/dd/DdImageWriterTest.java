// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.dd;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the default dd image writer.
@NotNullByDefault
public final class DdImageWriterTest {
    /// Writes an image, verifies it, and emits phase-specific progress events.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when fixture files cannot be written or read.
    @Test
    public void writesAndVerifiesRawImage(@TempDir Path temporaryDirectory) throws Exception {
        byte[] imageBytes = imageBytes(1024);
        Path image = temporaryDirectory.resolve("image.raw");
        Path target = temporaryDirectory.resolve("target.raw");
        Files.write(image, imageBytes);
        Files.write(target, new byte[2048]);

        DdImageWriter writer = DdImageWriter.fileChannel();
        ArrayList<DdProgressEvent> events = new ArrayList<>();
        writer.write(image, target, imageBytes.length, events::add);

        assertArrayEquals(imageBytes, Arrays.copyOf(Files.readAllBytes(target), imageBytes.length));
        assertTrue(events.stream().anyMatch(event -> event.operation() == DdOperation.WRITE));

        events.clear();
        assertTrue(writer.verify(image, target, imageBytes.length, events::add));
        assertTrue(events.stream().anyMatch(event -> event.operation() == DdOperation.VERIFY));
    }

    /// Returns false when verification detects a mismatched target.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when fixture files cannot be written or read.
    @Test
    public void rejectsMismatchedTarget(@TempDir Path temporaryDirectory) throws Exception {
        Path image = temporaryDirectory.resolve("image.raw");
        Path target = temporaryDirectory.resolve("target.raw");
        Files.write(image, new byte[]{1, 2, 3, 4});
        Files.write(target, new byte[]{1, 2, 3, 9});

        assertFalse(DdImageWriter.fileChannel().verify(image, target, 4L, DdProgressReporter.none()));
    }

    /// Creates deterministic image bytes.
    ///
    /// @param size byte count.
    /// @return image bytes.
    private static byte[] imageBytes(int size) {
        byte[] bytes = new byte[size];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) i;
        }
        return bytes;
    }
}

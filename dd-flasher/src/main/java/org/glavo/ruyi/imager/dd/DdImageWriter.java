// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.dd;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.file.Path;

/// Writes and verifies raw images with dd-style sequential I/O.
@NotNullByDefault
public interface DdImageWriter {
    /// Returns the default `FileChannel`-based raw image writer.
    ///
    /// @return default raw image writer.
    static DdImageWriter fileChannel() {
        return FileChannelDdImageWriter.INSTANCE;
    }

    /// Writes a source image to a target path.
    ///
    /// @param source source image path.
    /// @param target target path.
    /// @param totalBytes source size.
    /// @param reporter progress reporter.
    /// @throws IOException when the image cannot be written.
    void write(
            Path source,
            Path target,
            long totalBytes,
            DdProgressReporter reporter) throws IOException;

    /// Verifies target bytes against a source image.
    ///
    /// @param source source image path.
    /// @param target target path.
    /// @param totalBytes source size.
    /// @param reporter progress reporter.
    /// @return whether the target bytes match the source image.
    /// @throws IOException when files cannot be read.
    boolean verify(
            Path source,
            Path target,
            long totalBytes,
            DdProgressReporter reporter) throws IOException;
}

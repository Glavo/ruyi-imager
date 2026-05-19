// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.flash;

import org.glavo.ruyi.imager.core.ProgressReporter;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.file.Path;

/// Adapter interface used by the SDK to run dd-style raw image writes.
@NotNullByDefault
public interface DdImageWriter {
    /// Returns the default process-backed Rust dd helper adapter.
    ///
    /// @return default dd image writer.
    static DdImageWriter process() {
        return ProcessDdImageWriter.createDefault();
    }

    /// Writes a source image to a target path.
    ///
    /// @param source source image path.
    /// @param target target path.
    /// @param totalBytes source size.
    /// @param targetRemovable whether the target was identified as removable.
    /// @param message progress message.
    /// @param reporter progress reporter.
    /// @throws IOException when the image cannot be written.
    void write(
            Path source,
            Path target,
            long totalBytes,
            boolean targetRemovable,
            String message,
            ProgressReporter reporter) throws IOException;

    /// Verifies target bytes against a source image.
    ///
    /// @param source source image path.
    /// @param target target path.
    /// @param totalBytes source size.
    /// @param targetRemovable whether the target was identified as removable.
    /// @param message progress message.
    /// @param reporter progress reporter.
    /// @return whether the target bytes match the source image.
    /// @throws IOException when files cannot be read.
    boolean verify(
            Path source,
            Path target,
            long totalBytes,
            boolean targetRemovable,
            String message,
            ProgressReporter reporter) throws IOException;
}

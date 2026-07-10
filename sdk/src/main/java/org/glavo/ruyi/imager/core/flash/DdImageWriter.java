// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.flash;

import org.glavo.ruyi.imager.core.ProgressEvent;
import org.glavo.ruyi.imager.core.ProgressReporter;
import org.glavo.ruyi.imager.core.device.BlockDevice;
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

    /// Returns whether this writer can safely handle a mounted target itself.
    ///
    /// @param target target block device.
    /// @return whether mounted target validation may be deferred to this writer.
    default boolean canWriteMountedTarget(BlockDevice target) {
        return false;
    }

    /// Writes a source image to a target path.
    ///
    /// @param source source image path.
    /// @param target selected target block device.
    /// @param totalBytes source size.
    /// @param message progress message.
    /// @param reporter progress reporter.
    /// @throws IOException when the image cannot be written.
    void write(
            Path source,
            BlockDevice target,
            long totalBytes,
            String message,
            ProgressReporter reporter) throws IOException;

    /// Writes a source image to a target path and verifies the written bytes.
    ///
    /// @param source source image path.
    /// @param target selected target block device.
    /// @param totalBytes source size.
    /// @param writeMessage progress message for writing.
    /// @param verifyMessage progress message for verification.
    /// @param reporter progress reporter.
    /// @return whether the target bytes match the source image after writing.
    /// @throws IOException when the image cannot be written or read.
    default boolean writeAndVerify(
            Path source,
            BlockDevice target,
            long totalBytes,
            String writeMessage,
            String verifyMessage,
            ProgressReporter reporter) throws IOException {
        write(source, target, totalBytes, writeMessage, reporter);
        reporter.report(new ProgressEvent("verify", verifyMessage, 0L, totalBytes));
        return verify(source, target, totalBytes, verifyMessage, reporter);
    }

    /// Verifies target bytes against a source image.
    ///
    /// @param source source image path.
    /// @param target selected target block device.
    /// @param totalBytes source size.
    /// @param message progress message.
    /// @param reporter progress reporter.
    /// @return whether the target bytes match the source image.
    /// @throws IOException when files cannot be read.
    boolean verify(
            Path source,
            BlockDevice target,
            long totalBytes,
            String message,
            ProgressReporter reporter) throws IOException;
}

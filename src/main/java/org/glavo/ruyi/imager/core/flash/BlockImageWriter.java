// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.flash;

import org.glavo.ruyi.imager.core.ProgressEvent;
import org.glavo.ruyi.imager.core.ProgressReporter;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.logging.Logger;

/// Writes and verifies raw block images.
@NotNullByDefault
public interface BlockImageWriter {
    /// Returns the default `FileChannel`-based block image writer.
    ///
    /// @return default block image writer.
    static BlockImageWriter fileChannel() {
        return FileChannelBlockImageWriter.INSTANCE;
    }

    /// Writes a source image to a target path.
    ///
    /// @param source source image path.
    /// @param target target path.
    /// @param totalBytes source size.
    /// @param message progress message.
    /// @param reporter progress reporter.
    /// @throws IOException when the image cannot be written.
    void write(
            Path source,
            Path target,
            long totalBytes,
            String message,
            ProgressReporter reporter) throws IOException;

    /// Verifies target bytes against a source image.
    ///
    /// @param source source image path.
    /// @param target target path.
    /// @param totalBytes source size.
    /// @param message progress message.
    /// @param reporter progress reporter.
    /// @return whether the target bytes match the source image.
    /// @throws IOException when files cannot be read.
    boolean verify(
            Path source,
            Path target,
            long totalBytes,
            String message,
            ProgressReporter reporter) throws IOException;
}

/// `FileChannel`-based block image writer.
@NotNullByDefault
final class FileChannelBlockImageWriter implements BlockImageWriter {
    /// Logger for raw block image I/O.
    private static final Logger LOGGER = Logger.getLogger(FileChannelBlockImageWriter.class.getName());

    /// Shared writer instance.
    static final BlockImageWriter INSTANCE = new FileChannelBlockImageWriter();

    /// Copy buffer size used for writing and verification.
    private static final int BUFFER_SIZE = 1024 * 1024;

    /// Creates the file-channel writer.
    private FileChannelBlockImageWriter() {
    }

    /// Writes a source image to a target path.
    ///
    /// @param source source image path.
    /// @param target target path.
    /// @param totalBytes source size.
    /// @param message progress message.
    /// @param reporter progress reporter.
    /// @throws IOException when the image cannot be written.
    @Override
    public void write(
            Path source,
            Path target,
            long totalBytes,
            String message,
            ProgressReporter reporter) throws IOException {
        LOGGER.info(() -> "Opening block image write channels. source="
                + source
                + ", target="
                + target
                + ", bytes="
                + totalBytes);
        try (FileChannel input = FileChannel.open(source, StandardOpenOption.READ);
             FileChannel output = FileChannel.open(target, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.allocateDirect(BUFFER_SIZE);
            long writtenBytes = 0L;
            while (true) {
                buffer.clear();
                int read = input.read(buffer);
                if (read < 0) {
                    break;
                }
                buffer.flip();
                while (buffer.hasRemaining()) {
                    writtenBytes += output.write(buffer);
                }
                reporter.report(new ProgressEvent("flash", message, writtenBytes, totalBytes));
            }
            output.force(true);
        }
        LOGGER.info(() -> "Block image write channels closed. target=" + target);
    }

    /// Verifies target bytes against a source image.
    ///
    /// @param source source image path.
    /// @param target target path.
    /// @param totalBytes source size.
    /// @param message progress message.
    /// @param reporter progress reporter.
    /// @return whether the target bytes match the source image.
    /// @throws IOException when files cannot be read.
    @Override
    public boolean verify(
            Path source,
            Path target,
            long totalBytes,
            String message,
            ProgressReporter reporter) throws IOException {
        LOGGER.info(() -> "Opening block image verify channels. source="
                + source
                + ", target="
                + target
                + ", bytes="
                + totalBytes);
        try (FileChannel input = FileChannel.open(source, StandardOpenOption.READ);
             FileChannel output = FileChannel.open(target, StandardOpenOption.READ)) {
            ByteBuffer inputBuffer = ByteBuffer.allocateDirect(BUFFER_SIZE);
            ByteBuffer outputBuffer = ByteBuffer.allocateDirect(BUFFER_SIZE);
            long verifiedBytes = 0L;
            while (verifiedBytes < totalBytes) {
                int chunkSize = Math.toIntExact(Math.min(BUFFER_SIZE, totalBytes - verifiedBytes));
                inputBuffer.clear();
                inputBuffer.limit(chunkSize);
                outputBuffer.clear();
                outputBuffer.limit(chunkSize);

                int expectedRead = readFully(input, inputBuffer);
                int actualRead = readFully(output, outputBuffer);
                if (expectedRead != actualRead) {
                    LOGGER.warning(() -> "Block image verification length mismatch. target="
                            + target
                            + ", expectedRead="
                            + expectedRead
                            + ", actualRead="
                            + actualRead);
                    return false;
                }
                if (expectedRead != chunkSize) {
                    break;
                }

                inputBuffer.flip();
                outputBuffer.flip();
                if (!inputBuffer.equals(outputBuffer)) {
                    long mismatchOffset = verifiedBytes;
                    LOGGER.warning(() -> "Block image verification content mismatch. target="
                            + target
                            + ", offset="
                            + mismatchOffset);
                    return false;
                }

                verifiedBytes += expectedRead;
                reporter.report(new ProgressEvent("verify", message, verifiedBytes, totalBytes));
            }
            LOGGER.info(() -> "Block image verification channels closed. target=" + target);
            return true;
        }
    }

    /// Reads bytes until the buffer is full or the channel reaches EOF.
    ///
    /// @param channel file channel.
    /// @param buffer destination buffer.
    /// @return bytes read.
    /// @throws IOException when the channel cannot be read.
    private static int readFully(FileChannel channel, ByteBuffer buffer) throws IOException {
        int total = 0;
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer);
            if (read < 0) {
                break;
            }
            total += read;
        }
        return total;
    }
}

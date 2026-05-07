// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.dd;

import org.jetbrains.annotations.NotNullByDefault;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

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

/// `FileChannel`-based dd image writer.
@NotNullByDefault
final class FileChannelDdImageWriter implements DdImageWriter {
    /// Logger for raw image I/O.
    private static final Logger LOGGER = LoggerFactory.getLogger(FileChannelDdImageWriter.class);

    /// Shared writer instance.
    static final DdImageWriter INSTANCE = new FileChannelDdImageWriter();

    /// Copy buffer size used for writing and verification.
    private static final int BUFFER_SIZE = 1024 * 1024;

    /// Creates the file-channel writer.
    private FileChannelDdImageWriter() {
    }

    /// Writes a source image to a target path.
    ///
    /// @param source source image path.
    /// @param target target path.
    /// @param totalBytes source size.
    /// @param reporter progress reporter.
    /// @throws IOException when the image cannot be written.
    @Override
    public void write(
            Path source,
            Path target,
            long totalBytes,
            DdProgressReporter reporter) throws IOException {
        LOGGER.atInfo().log(() -> "Opening dd write channels. source="
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
                reporter.report(new DdProgressEvent(DdOperation.WRITE, writtenBytes, totalBytes));
            }
            output.force(true);
        }
        LOGGER.atInfo().log(() -> "dd write channels closed. target=" + target);
    }

    /// Verifies target bytes against a source image.
    ///
    /// @param source source image path.
    /// @param target target path.
    /// @param totalBytes source size.
    /// @param reporter progress reporter.
    /// @return whether the target bytes match the source image.
    /// @throws IOException when files cannot be read.
    @Override
    public boolean verify(
            Path source,
            Path target,
            long totalBytes,
            DdProgressReporter reporter) throws IOException {
        LOGGER.atInfo().log(() -> "Opening dd verify channels. source="
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
                    LOGGER.atWarn().log(() -> "dd verification length mismatch. target="
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
                    LOGGER.atWarn().log(() -> "dd verification content mismatch. target="
                            + target
                            + ", offset="
                            + mismatchOffset);
                    return false;
                }

                verifiedBytes += expectedRead;
                reporter.report(new DdProgressEvent(DdOperation.VERIFY, verifiedBytes, totalBytes));
            }
            LOGGER.atInfo().log(() -> "dd verification channels closed. target=" + target);
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

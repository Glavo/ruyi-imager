// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.flash;

import org.glavo.ruyi.imager.core.OperationResult;
import org.glavo.ruyi.imager.core.ProgressEvent;
import org.glavo.ruyi.imager.core.ProgressReporter;
import org.glavo.ruyi.imager.core.device.BlockDevice;
import org.glavo.ruyi.imager.core.image.ImageCatalogService;
import org.glavo.ruyi.imager.core.image.ImageEntry;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/// Platform-neutral flash service for raw `dd-v1` style writes.
@NotNullByDefault
public final class LocalFlashService implements FlashService {
    /// Copy buffer size used for writing and verification.
    private static final int BUFFER_SIZE = 1024 * 1024;

    /// Image catalog service used to materialize Ruyi images.
    private final ImageCatalogService images;

    /// Creates the local flash service.
    ///
    /// @param images image catalog service.
    public LocalFlashService(ImageCatalogService images) {
        this.images = images;
    }

    /// Executes a raw image flash request.
    ///
    /// @param request flash request.
    /// @param reporter progress reporter.
    /// @return operation result.
    /// @throws IOException when files cannot be read or written.
    @Override
    public OperationResult flash(FlashRequest request, ProgressReporter reporter) throws IOException {
        BlockDevice target = request.target();
        @Nullable String safetyError = validateTarget(target);
        if (safetyError != null) {
            return OperationResult.failure(safetyError);
        }

        Path source = resolveSource(request, reporter);
        long sourceSize = Files.size(source);
        if (target.sizeBytes() > 0L && sourceSize > target.sizeBytes()) {
            return OperationResult.failure("Image is larger than target device.");
        }
        if (Files.isSameFile(source, target.path())) {
            return OperationResult.failure("Refusing to flash an image onto itself.");
        }

        reporter.report(new ProgressEvent("flash", "Writing image to target.", 0L, sourceSize));
        writeImage(source, target.path(), sourceSize, reporter);

        if (request.verify()) {
            reporter.report(new ProgressEvent("verify", "Verifying written image.", 0L, sourceSize));
            if (!verifyImage(source, target.path(), sourceSize, reporter)) {
                return OperationResult.failure("Written image failed verification.");
            }
        }

        return OperationResult.success("Image flashed successfully.");
    }

    /// Validates target safety flags.
    ///
    /// @param target target block device.
    /// @return failure message, or null when target is acceptable.
    private static @Nullable String validateTarget(BlockDevice target) {
        if (target.system()) {
            return "Refusing to write to a system disk.";
        }
        if (target.readOnly()) {
            return "Refusing to write to a read-only device.";
        }
        if (!Files.exists(target.path())) {
            return "Target device path does not exist: " + target.path();
        }
        return null;
    }

    /// Resolves the source image path.
    ///
    /// @param request flash request.
    /// @param reporter progress reporter.
    /// @return source image path.
    /// @throws IOException when source image cannot be resolved.
    private Path resolveSource(FlashRequest request, ProgressReporter reporter) throws IOException {
        @Nullable Path localImage = request.localImage();
        if (localImage != null) {
            if (!Files.isRegularFile(localImage)) {
                throw new IOException("Local image does not exist: " + localImage);
            }
            return localImage;
        }

        @Nullable ImageEntry image = request.image();
        if (image == null) {
            throw new IOException("No image source was selected.");
        }
        if (!"dd-v1".equals(image.strategy())) {
            throw new IOException("Only dd-v1 images can be flashed to a block device target.");
        }
        if (image.partitionMap().size() != 1) {
            throw new IOException("dd-v1 image requires exactly one target mapping in this CLI flow.");
        }
        return images.downloadImage(image, reporter);
    }

    /// Writes the source image to the target path.
    ///
    /// @param source source image path.
    /// @param target target path.
    /// @param totalBytes source size.
    /// @param reporter progress reporter.
    /// @throws IOException when the image cannot be written.
    private static void writeImage(Path source, Path target, long totalBytes, ProgressReporter reporter) throws IOException {
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
                reporter.report(new ProgressEvent("flash", "Writing image to target.", writtenBytes, totalBytes));
            }
            output.force(true);
        }
    }

    /// Verifies target bytes against the source image.
    ///
    /// @param source source image path.
    /// @param target target path.
    /// @param totalBytes source size.
    /// @param reporter progress reporter.
    /// @return whether the target bytes match the source image.
    /// @throws IOException when files cannot be read.
    private static boolean verifyImage(Path source, Path target, long totalBytes, ProgressReporter reporter) throws IOException {
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
                    return false;
                }
                if (expectedRead != chunkSize) {
                    break;
                }

                inputBuffer.flip();
                outputBuffer.flip();
                if (!inputBuffer.equals(outputBuffer)) {
                    return false;
                }

                verifiedBytes += expectedRead;
                reporter.report(new ProgressEvent("verify", "Verifying written image.", verifiedBytes, totalBytes));
            }
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

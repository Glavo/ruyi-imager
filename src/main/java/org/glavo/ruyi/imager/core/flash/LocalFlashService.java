// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.flash;

import org.glavo.ruyi.imager.core.OperationResult;
import org.glavo.ruyi.imager.core.ProgressEvent;
import org.glavo.ruyi.imager.core.ProgressReporter;
import org.glavo.ruyi.imager.core.device.BlockDevice;
import org.glavo.ruyi.imager.core.fastboot.FastbootDevice;
import org.glavo.ruyi.imager.core.fastboot.FastbootService;
import org.glavo.ruyi.imager.core.fastboot.ProcessFastbootService;
import org.glavo.ruyi.imager.core.image.ImageCatalogService;
import org.glavo.ruyi.imager.core.image.ImageEntry;
import org.glavo.ruyi.imager.i18n.Messages;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/// Flash service that dispatches `dd-v1` writes and fastboot strategies.
@NotNullByDefault
public final class LocalFlashService implements FlashService {
    /// Copy buffer size used for writing and verification.
    private static final int BUFFER_SIZE = 1024 * 1024;

    /// Image catalog service used to materialize Ruyi images.
    private final ImageCatalogService images;

    /// Fastboot backend used for fastboot provision strategies.
    private final FastbootService fastboot;

    /// Creates the local flash service.
    ///
    /// @param images image catalog service.
    public LocalFlashService(ImageCatalogService images) {
        this(images, new ProcessFastbootService());
    }

    /// Creates the local flash service.
    ///
    /// @param images image catalog service.
    /// @param fastboot fastboot backend.
    public LocalFlashService(ImageCatalogService images, FastbootService fastboot) {
        this.images = images;
        this.fastboot = fastboot;
    }

    /// Executes an image flash request.
    ///
    /// @param request flash request.
    /// @param reporter progress reporter.
    /// @return operation result.
    /// @throws IOException when files cannot be read or written.
    @Override
    public OperationResult flash(FlashRequest request, ProgressReporter reporter) throws IOException {
        @Nullable Path localImage = request.localImage();
        if (localImage != null) {
            return flashBlockImage(localImage, request.target(), request.verify(), reporter);
        }

        @Nullable ImageEntry image = request.image();
        if (image == null) {
            return OperationResult.failure(Messages.get("core.flash.noSource"));
        }

        if ("dd-v1".equals(image.strategy())) {
            Path materialized = images.downloadImage(image, reporter);
            @Unmodifiable Map<String, Path> partitions = resolvePartitionPaths(image, materialized);
            if (partitions.size() == 1 && request.target().blockDevice() != null) {
                return flashBlockImage(partitions.values().iterator().next(), request.target(), request.verify(), reporter);
            }
            return flashBlockPartitions(partitions, request.target(), request.verify(), reporter);
        }

        if (isFastbootStrategy(image.strategy())) {
            return flashFastbootImage(image, request.target(), reporter);
        }

        return OperationResult.failure(Messages.get("core.flash.unsupportedStrategy", image.strategy()));
    }

    /// Writes one raw image to a block device target.
    ///
    /// @param source source image path.
    /// @param target selected target.
    /// @param verify whether post-write verification should run.
    /// @param reporter progress reporter.
    /// @return operation result.
    /// @throws IOException when files cannot be read or written.
    private static OperationResult flashBlockImage(
            Path source,
            FlashTarget target,
            boolean verify,
            ProgressReporter reporter) throws IOException {
        @Nullable BlockDevice blockDevice = target.blockDevice();
        if (blockDevice == null) {
            return OperationResult.failure(Messages.get("core.flash.blockTargetRequired"));
        }

        @Nullable String validationError = validateBlockImage(source, blockDevice);
        if (validationError != null) {
            return OperationResult.failure(validationError);
        }

        return writeValidatedBlockImage(
                source,
                blockDevice,
                verify,
                Messages.get("core.flash.writing"),
                Messages.get("core.flash.verifying"),
                reporter);
    }

    /// Writes multiple materialized partition images to partition-specific block targets.
    ///
    /// @param partitions materialized partition images keyed by partition name.
    /// @param target selected target map.
    /// @param verify whether post-write verification should run.
    /// @param reporter progress reporter.
    /// @return operation result.
    /// @throws IOException when files cannot be read or written.
    private static OperationResult flashBlockPartitions(
            @Unmodifiable Map<String, Path> partitions,
            FlashTarget target,
            boolean verify,
            ProgressReporter reporter) throws IOException {
        @Unmodifiable Map<String, BlockDevice> blockTargets = target.blockDevices();
        if (blockTargets.isEmpty()) {
            return OperationResult.failure(Messages.get(
                    "core.flash.partitionTargetsRequired",
                    partitionNames(partitions)));
        }

        for (String partition : blockTargets.keySet()) {
            if (!partitions.containsKey(partition)) {
                return OperationResult.failure(Messages.get("core.flash.unknownPartitionTarget", partition));
            }
        }

        Set<Path> targetPaths = new HashSet<>();
        for (Map.Entry<String, Path> entry : partitions.entrySet()) {
            String partition = entry.getKey();
            @Nullable BlockDevice blockDevice = blockTargets.get(partition);
            if (blockDevice == null) {
                return OperationResult.failure(Messages.get("core.flash.missingPartitionTarget", partition));
            }

            Path normalizedTargetPath = blockDevice.path().toAbsolutePath().normalize();
            if (!targetPaths.add(normalizedTargetPath)) {
                return OperationResult.failure(Messages.get("core.flash.duplicatePartitionTarget", normalizedTargetPath));
            }

            @Nullable String validationError = validateBlockImage(entry.getValue(), blockDevice);
            if (validationError != null) {
                return OperationResult.failure(Messages.get(
                        "core.flash.partitionTargetInvalid",
                        partition,
                        validationError));
            }
        }

        for (Map.Entry<String, Path> entry : partitions.entrySet()) {
            String partition = entry.getKey();
            BlockDevice blockDevice = blockTargets.get(partition);
            OperationResult result = writeValidatedBlockImage(
                    entry.getValue(),
                    blockDevice,
                    verify,
                    Messages.get("core.flash.writingPartition", partition),
                    Messages.get("core.flash.verifyingPartition", partition),
                    reporter);
            if (!result.success()) {
                return result;
            }
        }

        return OperationResult.success(Messages.get("core.flash.success"));
    }

    /// Validates one source image and target block device before writing.
    ///
    /// @param source source image path.
    /// @param blockDevice target block device.
    /// @return failure message, or null when source and target are acceptable.
    /// @throws IOException when source or target metadata cannot be read.
    private static @Nullable String validateBlockImage(Path source, BlockDevice blockDevice) throws IOException {
        @Nullable String safetyError = validateTarget(blockDevice);
        if (safetyError != null) {
            return safetyError;
        }
        if (!Files.isRegularFile(source)) {
            return Messages.get("core.flash.localImageMissing", source);
        }
        long sourceSize = Files.size(source);
        if (blockDevice.sizeBytes() > 0L && sourceSize > blockDevice.sizeBytes()) {
            return Messages.get("core.flash.imageTooLarge");
        }
        if (Files.isSameFile(source, blockDevice.path())) {
            return Messages.get("core.flash.selfWrite");
        }
        return null;
    }

    /// Writes one already validated raw image to a block device target.
    ///
    /// @param source source image path.
    /// @param blockDevice target block device.
    /// @param verify whether post-write verification should run.
    /// @param writeMessage progress message for writing.
    /// @param verifyMessage progress message for verification.
    /// @param reporter progress reporter.
    /// @return operation result.
    /// @throws IOException when files cannot be read or written.
    private static OperationResult writeValidatedBlockImage(
            Path source,
            BlockDevice blockDevice,
            boolean verify,
            String writeMessage,
            String verifyMessage,
            ProgressReporter reporter) throws IOException {
        long sourceSize = Files.size(source);

        reporter.report(new ProgressEvent("flash", writeMessage, 0L, sourceSize));
        writeImage(source, blockDevice.path(), sourceSize, writeMessage, reporter);

        if (verify) {
            reporter.report(new ProgressEvent("verify", verifyMessage, 0L, sourceSize));
            if (!verifyImage(source, blockDevice.path(), sourceSize, verifyMessage, reporter)) {
                return OperationResult.failure(Messages.get("core.flash.verifyFailed"));
            }
        }

        return OperationResult.success(Messages.get("core.flash.success"));
    }

    /// Formats partition names for diagnostics.
    ///
    /// @param partitions partition image map.
    /// @return comma-separated partition names.
    private static String partitionNames(@Unmodifiable Map<String, Path> partitions) {
        return String.join(", ", partitions.keySet());
    }

    /// Flashes one materialized image through fastboot.
    ///
    /// @param image image metadata.
    /// @param target selected target.
    /// @param reporter progress reporter.
    /// @return operation result.
    /// @throws IOException when image materialization or fastboot execution fails.
    private OperationResult flashFastbootImage(
            ImageEntry image,
            FlashTarget target,
            ProgressReporter reporter) throws IOException {
        @Nullable FastbootDevice fastbootDevice = target.fastbootDevice();
        if (fastbootDevice == null) {
            return OperationResult.failure(Messages.get("core.fastboot.targetRequired"));
        }

        Path materialized = images.downloadImage(image, reporter);
        @Unmodifiable Map<String, Path> partitions = resolvePartitionPaths(image, materialized);
        return fastboot.flash(image.strategy(), partitions, fastbootDevice, reporter);
    }

    /// Validates target safety flags.
    ///
    /// @param target target block device.
    /// @return failure message, or null when target is acceptable.
    private static @Nullable String validateTarget(BlockDevice target) {
        if (target.system()) {
            return Messages.get("core.flash.refuseSystem");
        }
        if (target.mounted()) {
            return Messages.get("core.flash.refuseMounted");
        }
        if (target.readOnly()) {
            return Messages.get("core.flash.refuseReadOnly");
        }
        if (!Files.exists(target.path())) {
            return Messages.get("core.flash.targetMissing", target.path());
        }
        return null;
    }

    /// Resolves materialized partition paths.
    ///
    /// @param image image metadata.
    /// @param materialized materialized image file or artifact directory.
    /// @return partition image paths keyed by partition name.
    /// @throws IOException when partition paths cannot be resolved safely.
    private static @Unmodifiable Map<String, Path> resolvePartitionPaths(ImageEntry image, Path materialized) throws IOException {
        if (image.partitionMap().isEmpty()) {
            throw new IOException(Messages.get("core.materialize.noPartitionMap", image.atom()));
        }

        if (image.partitionMap().size() == 1 && Files.isRegularFile(materialized)) {
            Map.Entry<String, String> entry = image.partitionMap().entrySet().iterator().next();
            return Map.of(entry.getKey(), materialized);
        }

        if (!Files.isDirectory(materialized)) {
            throw new IOException(Messages.get("core.materialize.partitionMissing", materialized));
        }

        Path normalizedRoot = materialized.toAbsolutePath().normalize();
        LinkedHashMap<String, Path> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : image.partitionMap().entrySet()) {
            Path path = normalizedRoot.resolve(entry.getValue()).normalize();
            if (!path.startsWith(normalizedRoot)) {
                throw new IOException(Messages.get("core.materialize.partitionEscape", entry.getValue()));
            }
            if (!Files.isRegularFile(path)) {
                throw new IOException(Messages.get("core.materialize.partitionMissing", path));
            }
            result.put(entry.getKey(), path);
        }
        return Collections.unmodifiableMap(result);
    }

    /// Returns whether a strategy is handled by fastboot.
    ///
    /// @param strategy strategy name.
    /// @return whether this is a supported fastboot strategy.
    private static boolean isFastbootStrategy(String strategy) {
        return "fastboot-v1".equals(strategy) || "fastboot-v1(lpi4a-uboot)".equals(strategy);
    }

    /// Writes the source image to the target path.
    ///
    /// @param source source image path.
    /// @param target target path.
    /// @param totalBytes source size.
    /// @param message progress message.
    /// @param reporter progress reporter.
    /// @throws IOException when the image cannot be written.
    private static void writeImage(
            Path source,
            Path target,
            long totalBytes,
            String message,
            ProgressReporter reporter) throws IOException {
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
    }

    /// Verifies target bytes against the source image.
    ///
    /// @param source source image path.
    /// @param target target path.
    /// @param totalBytes source size.
    /// @param message progress message.
    /// @param reporter progress reporter.
    /// @return whether the target bytes match the source image.
    /// @throws IOException when files cannot be read.
    private static boolean verifyImage(
            Path source,
            Path target,
            long totalBytes,
            String message,
            ProgressReporter reporter) throws IOException {
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
                reporter.report(new ProgressEvent("verify", message, verifiedBytes, totalBytes));
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

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
import org.glavo.ruyi.imager.core.SdkMessages;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Flash service that dispatches `dd-v1` writes and fastboot strategies.
@NotNullByDefault
public final class LocalFlashService implements FlashService {
    /// Logger for flash orchestration.
    private static final Logger LOGGER = LoggerFactory.getLogger(LocalFlashService.class);

    /// Image catalog service used to materialize Ruyi images.
    private final ImageCatalogService images;

    /// Fastboot backend used for fastboot provision strategies.
    private final FastbootService fastboot;

    /// Block-device preparation hook used before destructive writes.
    private final BlockDevicePreparer blockDevicePreparer;

    /// dd-style raw image writer backend.
    private final DdImageWriter ddImageWriter;

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
        this(images, fastboot, BlockDevicePreparer.none());
    }

    /// Creates the local flash service.
    ///
    /// @param images image catalog service.
    /// @param fastboot fastboot backend.
    /// @param blockDevicePreparer block-device preparation hook.
    public LocalFlashService(
            ImageCatalogService images,
            FastbootService fastboot,
            BlockDevicePreparer blockDevicePreparer) {
        this(images, fastboot, blockDevicePreparer, DdImageWriter.process());
    }

    /// Creates the local flash service.
    ///
    /// @param images image catalog service.
    /// @param fastboot fastboot backend.
    /// @param blockDevicePreparer block-device preparation hook.
    /// @param ddImageWriter dd-style raw image writer backend.
    public LocalFlashService(
            ImageCatalogService images,
            FastbootService fastboot,
            BlockDevicePreparer blockDevicePreparer,
            DdImageWriter ddImageWriter) {
        this.images = images;
        this.fastboot = fastboot;
        this.blockDevicePreparer = blockDevicePreparer;
        this.ddImageWriter = ddImageWriter;
    }

    /// Executes an image flash request.
    ///
    /// @param request flash request.
    /// @param reporter progress reporter.
    /// @return operation result.
    /// @throws IOException when files cannot be read or written.
    @Override
    public OperationResult flash(FlashRequest request, ProgressReporter reporter) throws IOException {
        LOGGER.atInfo().log(() -> "Flash request started. source="
                + sourceSummary(request)
                + ", target="
                + targetSummary(request.target())
                + ", verify="
                + request.verify());
        @Nullable Path localImage = request.localImage();
        if (localImage != null) {
            return flashBlockImage(localImage, request.target(), request.verify(), reporter);
        }

        @Nullable ImageEntry image = request.image();
        if (image == null) {
            LOGGER.warn("Flash request has no image source.");
            return OperationResult.failure(SdkMessages.get("core.flash.noSource"));
        }

        if ("dd-v1".equals(image.strategy())) {
            LOGGER.atInfo().log(() -> "Dispatching dd-v1 flash. atom=" + image.atom());
            Path materialized = images.downloadImage(image, reporter);
            @Unmodifiable Map<String, Path> partitions = resolvePartitionPaths(image, materialized);
            if (partitions.size() == 1 && request.target().blockDevice() != null) {
                return flashBlockImage(partitions.values().iterator().next(), request.target(), request.verify(), reporter);
            }
            return flashBlockPartitions(partitions, request.target(), request.verify(), reporter);
        }

        if (isFastbootStrategy(image.strategy())) {
            LOGGER.atInfo().log(() -> "Dispatching fastboot flash. atom=" + image.atom() + ", strategy=" + image.strategy());
            return flashFastbootImage(image, request.target(), reporter);
        }

        LOGGER.atWarn().log(() -> "Unsupported flash strategy. atom=" + image.atom() + ", strategy=" + image.strategy());
        return OperationResult.failure(SdkMessages.get("core.flash.unsupportedStrategy", image.strategy()));
    }

    /// Writes one raw image to a block device target.
    ///
    /// @param source source image path.
    /// @param target selected target.
    /// @param verify whether post-write verification should run.
    /// @param reporter progress reporter.
    /// @return operation result.
    /// @throws IOException when files cannot be read or written.
    private OperationResult flashBlockImage(
            Path source,
            FlashTarget target,
            boolean verify,
            ProgressReporter reporter) throws IOException {
        @Nullable BlockDevice blockDevice = target.blockDevice();
        if (blockDevice == null) {
            LOGGER.warn("Block image flash requested without a block target.");
            return OperationResult.failure(SdkMessages.get("core.flash.blockTargetRequired"));
        }

        @Nullable String validationError = validateBlockImage(
                source,
                blockDevice,
                blockDevicePreparer.canPrepareMounted(blockDevice));
        if (validationError != null) {
            String message = validationError;
            LOGGER.atWarn().log(() -> "Block target validation failed before preparation. source="
                    + source
                    + ", target="
                    + blockDevice.path()
                    + ", message="
                    + message);
            return OperationResult.failure(validationError);
        }
        BlockDevice preparedBlockDevice = prepareBlockTarget(blockDevice, reporter);

        validationError = validateBlockImage(source, preparedBlockDevice, false);
        if (validationError != null) {
            String message = validationError;
            LOGGER.atWarn().log(() -> "Block target validation failed after preparation. source="
                    + source
                    + ", target="
                    + preparedBlockDevice.path()
                    + ", message="
                    + message);
            return OperationResult.failure(validationError);
        }

        return writeValidatedBlockImage(
                source,
                preparedBlockDevice,
                verify,
                SdkMessages.get("core.flash.writing"),
                SdkMessages.get("core.flash.verifying"),
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
    private OperationResult flashBlockPartitions(
            @Unmodifiable Map<String, Path> partitions,
            FlashTarget target,
            boolean verify,
            ProgressReporter reporter) throws IOException {
        @Unmodifiable Map<String, BlockDevice> blockTargets = target.blockDevices();
        if (blockTargets.isEmpty()) {
            LOGGER.atWarn().log(() -> "Partition flash requested without targets. partitions=" + partitionNames(partitions));
            return OperationResult.failure(SdkMessages.get(
                    "core.flash.partitionTargetsRequired",
                    partitionNames(partitions)));
        }

        for (String partition : blockTargets.keySet()) {
            if (!partitions.containsKey(partition)) {
                return OperationResult.failure(SdkMessages.get("core.flash.unknownPartitionTarget", partition));
            }
        }

        Set<Path> targetPaths = new HashSet<>();
        LinkedHashMap<String, BlockDevice> preparedTargets = new LinkedHashMap<>();
        for (Map.Entry<String, Path> entry : partitions.entrySet()) {
            String partition = entry.getKey();
            @Nullable BlockDevice blockDevice = blockTargets.get(partition);
            if (blockDevice == null) {
                LOGGER.atWarn().log(() -> "Missing partition target. partition=" + partition);
                return OperationResult.failure(SdkMessages.get("core.flash.missingPartitionTarget", partition));
            }

            Path normalizedTargetPath = blockDevice.path().toAbsolutePath().normalize();
            if (!targetPaths.add(normalizedTargetPath)) {
                return OperationResult.failure(SdkMessages.get("core.flash.duplicatePartitionTarget", normalizedTargetPath));
            }

            @Nullable String validationError = validateBlockImage(
                    entry.getValue(),
                    blockDevice,
                    blockDevicePreparer.canPrepareMounted(blockDevice));
            if (validationError != null) {
                String message = validationError;
                LOGGER.atWarn().log(() -> "Partition target validation failed before preparation. partition="
                        + partition
                        + ", target="
                        + blockDevice.path()
                        + ", message="
                        + message);
                return OperationResult.failure(SdkMessages.get(
                        "core.flash.partitionTargetInvalid",
                        partition,
                        validationError));
            }

            BlockDevice preparedBlockDevice = prepareBlockTarget(blockDevice, reporter);
            validationError = validateBlockImage(entry.getValue(), preparedBlockDevice, false);
            if (validationError != null) {
                String message = validationError;
                LOGGER.atWarn().log(() -> "Partition target validation failed after preparation. partition="
                        + partition
                        + ", target="
                        + preparedBlockDevice.path()
                        + ", message="
                        + message);
                return OperationResult.failure(SdkMessages.get(
                        "core.flash.partitionTargetInvalid",
                        partition,
                        validationError));
            }

            preparedTargets.put(partition, preparedBlockDevice);
        }

        for (Map.Entry<String, Path> entry : partitions.entrySet()) {
            String partition = entry.getKey();
            BlockDevice blockDevice = preparedTargets.get(partition);
            OperationResult result = writeValidatedBlockImage(
                    entry.getValue(),
                    blockDevice,
                    verify,
                    SdkMessages.get("core.flash.writingPartition", partition),
                    SdkMessages.get("core.flash.verifyingPartition", partition),
                    reporter);
            if (!result.success()) {
                return result;
            }
        }

        return OperationResult.success(SdkMessages.get("core.flash.success"));
    }

    /// Validates one source image and target block device before writing.
    ///
    /// @param source source image path.
    /// @param blockDevice target block device.
    /// @return failure message, or null when source and target are acceptable.
    /// @throws IOException when source or target metadata cannot be read.
    private static @Nullable String validateBlockImage(
            Path source,
            BlockDevice blockDevice,
            boolean allowMounted) throws IOException {
        @Nullable String safetyError = validateTarget(blockDevice, allowMounted);
        if (safetyError != null) {
            return safetyError;
        }
        if (!Files.isRegularFile(source)) {
            return SdkMessages.get("core.flash.localImageMissing", source);
        }
        long sourceSize = Files.size(source);
        if (blockDevice.sizeBytes() > 0L && sourceSize > blockDevice.sizeBytes()) {
            return SdkMessages.get("core.flash.imageTooLarge");
        }
        if (isSamePath(source, blockDevice.path())) {
            return SdkMessages.get("core.flash.selfWrite");
        }
        return null;
    }

    /// Returns whether two paths refer to the same filesystem object when both paths can be resolved.
    ///
    /// @param source source path.
    /// @param target target path.
    /// @return whether both paths are known to refer to the same object.
    private static boolean isSamePath(Path source, Path target) {
        try {
            return Files.isSameFile(source, target);
        } catch (IOException _) {
            return false;
        }
    }

    /// Prepares a block target when it is currently mounted.
    ///
    /// @param blockDevice target block device.
    /// @param reporter progress reporter.
    /// @return prepared target metadata.
    /// @throws IOException when the target cannot be prepared.
    private BlockDevice prepareBlockTarget(BlockDevice blockDevice, ProgressReporter reporter) throws IOException {
        if (!blockDevice.mounted()) {
            return blockDevice;
        }
        LOGGER.atInfo().log(() -> "Preparing mounted block target. target=" + blockDevice.path());
        return blockDevicePreparer.prepare(blockDevice, reporter);
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
    private OperationResult writeValidatedBlockImage(
            Path source,
            BlockDevice blockDevice,
            boolean verify,
            String writeMessage,
            String verifyMessage,
            ProgressReporter reporter) throws IOException {
        long sourceSize = Files.size(source);
        LOGGER.atInfo().log(() -> "Writing block image. source="
                + source
                + ", target="
                + blockDevice.path()
                + ", bytes="
                + sourceSize
                + ", verify="
                + verify);

        reporter.report(new ProgressEvent("flash", writeMessage, 0L, sourceSize));
        ddImageWriter.write(source, blockDevice.path(), sourceSize, blockDevice.removable(), writeMessage, reporter);
        LOGGER.atInfo().log(() -> "Block image write completed. target=" + blockDevice.path());

        if (verify) {
            reporter.report(new ProgressEvent("verify", verifyMessage, 0L, sourceSize));
            if (!ddImageWriter.verify(
                    source,
                    blockDevice.path(),
                    sourceSize,
                    blockDevice.removable(),
                    verifyMessage,
                    reporter)) {
                LOGGER.atWarn().log(() -> "Block image verification failed. target=" + blockDevice.path());
                return OperationResult.failure(SdkMessages.get("core.flash.verifyFailed"));
            }
            LOGGER.atInfo().log(() -> "Block image verification completed. target=" + blockDevice.path());
        }

        return OperationResult.success(SdkMessages.get("core.flash.success"));
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
            LOGGER.atWarn().log(() -> "Fastboot flash requested without fastboot target. atom=" + image.atom());
            return OperationResult.failure(SdkMessages.get("core.fastboot.targetRequired"));
        }

        Path materialized = images.downloadImage(image, reporter);
        @Unmodifiable Map<String, Path> partitions = resolvePartitionPaths(image, materialized);
        return fastboot.flash(image.strategy(), partitions, fastbootDevice, reporter);
    }

    /// Summarizes a flash request source for logs.
    ///
    /// @param request flash request.
    /// @return source summary.
    private static String sourceSummary(FlashRequest request) {
        @Nullable ImageEntry image = request.image();
        if (image != null) {
            return "catalog:" + image.atom() + ":" + image.strategy();
        }
        @Nullable Path localImage = request.localImage();
        return localImage == null ? "<none>" : "local:" + localImage;
    }

    /// Summarizes a flash target for logs.
    ///
    /// @param target flash target.
    /// @return target summary.
    private static String targetSummary(FlashTarget target) {
        @Nullable BlockDevice blockDevice = target.blockDevice();
        if (blockDevice != null) {
            return "block:" + blockDevice.id() + ":" + blockDevice.path();
        }
        @Nullable FastbootDevice fastbootDevice = target.fastbootDevice();
        if (fastbootDevice != null) {
            return "fastboot:" + fastbootDevice.serial();
        }
        if (!target.blockDevices().isEmpty()) {
            return "partitions:" + target.blockDevices().keySet();
        }
        return "<none>";
    }

    /// Validates target safety flags.
    ///
    /// @param target target block device.
    /// @param allowMounted whether mounted targets may pass this validation pass.
    /// @return failure message, or null when target is acceptable.
    private static @Nullable String validateTarget(BlockDevice target, boolean allowMounted) {
        if (target.system()) {
            return SdkMessages.get("core.flash.refuseSystem");
        }
        if (!target.removable()) {
            return SdkMessages.get("core.flash.refuseNonRemovable");
        }
        if (target.sizeBytes() <= 0L && !target.fileBacked()) {
            return SdkMessages.get("core.flash.refuseUnknownTargetSize");
        }
        if (target.mounted() && !allowMounted) {
            return mountedTargetMessage(target);
        }
        if (target.readOnly()) {
            return SdkMessages.get("core.flash.refuseReadOnly");
        }
        return null;
    }

    /// Builds a refusal message for mounted targets.
    ///
    /// @param target mounted target.
    /// @return refusal message.
    private static String mountedTargetMessage(BlockDevice target) {
        if (!target.mountPoints().isEmpty()) {
            return SdkMessages.get("core.flash.refuseMountedWithPoints", String.join(", ", target.mountPoints()));
        }
        return SdkMessages.get("core.flash.refuseMounted");
    }

    /// Resolves materialized partition paths.
    ///
    /// @param image image metadata.
    /// @param materialized materialized image file or artifact directory.
    /// @return partition image paths keyed by partition name.
    /// @throws IOException when partition paths cannot be resolved safely.
    private static @Unmodifiable Map<String, Path> resolvePartitionPaths(ImageEntry image, Path materialized) throws IOException {
        if (image.partitionMap().isEmpty()) {
            throw new IOException(SdkMessages.get("core.materialize.noPartitionMap", image.atom()));
        }

        if (image.partitionMap().size() == 1 && Files.isRegularFile(materialized)) {
            Map.Entry<String, String> entry = image.partitionMap().entrySet().iterator().next();
            return Map.of(entry.getKey(), materialized);
        }

        if (!Files.isDirectory(materialized)) {
            throw new IOException(SdkMessages.get("core.materialize.partitionMissing", materialized));
        }

        Path normalizedRoot = materialized.toAbsolutePath().normalize();
        LinkedHashMap<String, Path> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : image.partitionMap().entrySet()) {
            Path path = normalizedRoot.resolve(entry.getValue()).normalize();
            if (!path.startsWith(normalizedRoot)) {
                throw new IOException(SdkMessages.get("core.materialize.partitionEscape", entry.getValue()));
            }
            if (!Files.isRegularFile(path)) {
                throw new IOException(SdkMessages.get("core.materialize.partitionMissing", path));
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

}

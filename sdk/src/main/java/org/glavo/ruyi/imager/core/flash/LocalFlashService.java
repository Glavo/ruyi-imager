// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.flash;

import org.glavo.ruyi.imager.core.OperationResult;
import org.glavo.ruyi.imager.core.ProgressEvent;
import org.glavo.ruyi.imager.core.ProgressReporter;
import org.glavo.ruyi.imager.core.ProvisionStrategies;
import org.glavo.ruyi.imager.core.device.BlockDevice;
import org.glavo.ruyi.imager.core.device.BlockDeviceService;
import org.glavo.ruyi.imager.core.fastboot.FastbootDevice;
import org.glavo.ruyi.imager.core.fastboot.FastbootService;
import org.glavo.ruyi.imager.core.fastboot.ProcessFastbootService;
import org.glavo.ruyi.imager.core.image.ImageCatalogService;
import org.glavo.ruyi.imager.core.image.ImageComponent;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    /// Block-device service used to re-resolve destructive write targets.
    private final @Nullable BlockDeviceService devices;

    /// Block-device preparation hook used before destructive writes.
    private final BlockDevicePreparer blockDevicePreparer;

    /// dd-style raw image writer backend.
    private final DdImageWriter ddImageWriter;

    /// Creates the local flash service.
    ///
    /// @param images image catalog service.
    public LocalFlashService(ImageCatalogService images) {
        this(images, (BlockDeviceService) null, new ProcessFastbootService());
    }

    /// Creates the local flash service.
    ///
    /// @param images image catalog service.
    /// @param devices block-device service used to re-resolve targets.
    public LocalFlashService(ImageCatalogService images, BlockDeviceService devices) {
        this(images, devices, new ProcessFastbootService());
    }

    /// Creates the local flash service.
    ///
    /// @param images image catalog service.
    /// @param fastboot fastboot backend.
    public LocalFlashService(ImageCatalogService images, FastbootService fastboot) {
        this(images, null, fastboot, BlockDevicePreparer.none());
    }

    /// Creates the local flash service.
    ///
    /// @param images image catalog service.
    /// @param devices block-device service used to re-resolve targets.
    /// @param fastboot fastboot backend.
    public LocalFlashService(
            ImageCatalogService images,
            @Nullable BlockDeviceService devices,
            FastbootService fastboot) {
        this(images, devices, fastboot, BlockDevicePreparer.none());
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
        this(images, null, fastboot, blockDevicePreparer, DdImageWriter.process());
    }

    /// Creates the local flash service.
    ///
    /// @param images image catalog service.
    /// @param devices block-device service used to re-resolve targets.
    /// @param fastboot fastboot backend.
    /// @param blockDevicePreparer block-device preparation hook.
    public LocalFlashService(
            ImageCatalogService images,
            @Nullable BlockDeviceService devices,
            FastbootService fastboot,
            BlockDevicePreparer blockDevicePreparer) {
        this(images, devices, fastboot, blockDevicePreparer, DdImageWriter.process());
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
        this(images, null, fastboot, blockDevicePreparer, ddImageWriter);
    }

    /// Creates the local flash service.
    ///
    /// @param images image catalog service.
    /// @param devices block-device service used to re-resolve targets.
    /// @param fastboot fastboot backend.
    /// @param blockDevicePreparer block-device preparation hook.
    /// @param ddImageWriter dd-style raw image writer backend.
    public LocalFlashService(
            ImageCatalogService images,
            @Nullable BlockDeviceService devices,
            FastbootService fastboot,
            BlockDevicePreparer blockDevicePreparer,
            DdImageWriter ddImageWriter) {
        this.images = images;
        this.devices = devices;
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

        if (ProvisionStrategies.isDD(image.strategy())) {
            LOGGER.atInfo().log(() -> "Dispatching dd-v1 flash. atom=" + image.atom());
            Path materialized = images.downloadImage(image, reporter);
            @Unmodifiable Map<String, Path> partitions = resolvePartitionPaths(image, materialized);
            if (partitions.size() == 1 && request.target().blockDevice() != null) {
                return flashBlockImage(partitions.values().iterator().next(), request.target(), request.verify(), reporter);
            }
            return flashBlockPartitions(partitions, request.target(), request.verify(), reporter);
        }

        if (ProvisionStrategies.isFastboot(image.strategy())) {
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

        @Nullable BlockDevice refreshedBlockDevice = refreshBlockTarget(blockDevice);
        if (refreshedBlockDevice == null) {
            return targetChangedFailure(blockDevice);
        }
        blockDevice = refreshedBlockDevice;

        @Nullable String validationError = validateBlockImage(
                source,
                blockDevice,
                canPrepareMountedTarget(blockDevice));
        if (validationError != null) {
            String message = validationError;
            Path targetPath = blockDevice.path();
            LOGGER.atWarn().log(() -> "Block target validation failed before preparation. source="
                    + source
                    + ", target="
                    + targetPath
                    + ", message="
                    + message);
            return OperationResult.failure(validationError);
        }
        BlockDevice preparedBlockDevice = prepareBlockTarget(blockDevice, reporter);

        refreshedBlockDevice = refreshBlockTarget(preparedBlockDevice);
        if (refreshedBlockDevice == null) {
            return targetChangedFailure(preparedBlockDevice);
        }
        preparedBlockDevice = refreshedBlockDevice;

        validationError = validateBlockImage(source, preparedBlockDevice, canWriteMountedTarget(preparedBlockDevice));
        if (validationError != null) {
            String message = validationError;
            Path targetPath = preparedBlockDevice.path();
            LOGGER.atWarn().log(() -> "Block target validation failed after preparation. source="
                    + source
                    + ", target="
                    + targetPath
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
            @Nullable BlockDevice refreshedBlockDevice = refreshBlockTarget(blockDevice);
            if (refreshedBlockDevice == null) {
                return targetChangedFailure(blockDevice);
            }
            blockDevice = refreshedBlockDevice;

            Path normalizedTargetPath = blockDevice.path().toAbsolutePath().normalize();
            if (!targetPaths.add(normalizedTargetPath)) {
                return OperationResult.failure(SdkMessages.get("core.flash.duplicatePartitionTarget", normalizedTargetPath));
            }

            @Nullable String validationError = validateBlockImage(
                    entry.getValue(),
                    blockDevice,
                    canPrepareMountedTarget(blockDevice));
            if (validationError != null) {
                String message = validationError;
                Path targetPath = blockDevice.path();
                LOGGER.atWarn().log(() -> "Partition target validation failed before preparation. partition="
                        + partition
                        + ", target="
                        + targetPath
                        + ", message="
                        + message);
                return OperationResult.failure(SdkMessages.get(
                        "core.flash.partitionTargetInvalid",
                        partition,
                        validationError));
            }

            BlockDevice preparedBlockDevice = prepareBlockTarget(blockDevice, reporter);
            refreshedBlockDevice = refreshBlockTarget(preparedBlockDevice);
            if (refreshedBlockDevice == null) {
                return targetChangedFailure(preparedBlockDevice);
            }
            preparedBlockDevice = refreshedBlockDevice;
            validationError = validateBlockImage(
                    entry.getValue(),
                    preparedBlockDevice,
                    canWriteMountedTarget(preparedBlockDevice));
            if (validationError != null) {
                String message = validationError;
                Path targetPath = preparedBlockDevice.path();
                LOGGER.atWarn().log(() -> "Partition target validation failed after preparation. partition="
                        + partition
                        + ", target="
                        + targetPath
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

    /// Prepares a block target unless the writer can handle target locking itself.
    ///
    /// @param blockDevice target block device.
    /// @param reporter progress reporter.
    /// @return prepared target metadata.
    /// @throws IOException when the target cannot be prepared.
    private BlockDevice prepareBlockTarget(BlockDevice blockDevice, ProgressReporter reporter) throws IOException {
        if (canWriteMountedTarget(blockDevice)) {
            LOGGER.atInfo().log(() -> "Skipping block target preparation; dd writer handles mounted target. target="
                    + blockDevice.path()
                    + ", mounted="
                    + blockDevice.mounted());
            reportPreparedTarget(blockDevice, reporter);
            return blockDevice;
        }
        if (!blockDevicePreparer.shouldPrepare(blockDevice)) {
            LOGGER.atInfo().log(() -> "Skipping block target preparation; target is already ready. target="
                    + blockDevice.path()
                    + ", mounted="
                    + blockDevice.mounted());
            reportPreparedTarget(blockDevice, reporter);
            return blockDevice;
        }
        LOGGER.atInfo().log(() -> "Preparing block target. target="
                + blockDevice.path()
                + ", mounted="
                + blockDevice.mounted());
        return blockDevicePreparer.prepare(blockDevice, reporter);
    }

    /// Reports that a block target is ready for writing.
    ///
    /// @param blockDevice target block device.
    /// @param reporter progress reporter.
    private static void reportPreparedTarget(BlockDevice blockDevice, ProgressReporter reporter) {
        reporter.report(new ProgressEvent(
                "prepare",
                SdkMessages.get("core.flash.preparedTarget", blockDevice.displayName()),
                1L,
                1L));
    }

    /// Returns whether mounted target validation can be deferred to the writer.
    ///
    /// @param blockDevice target block device.
    /// @return whether the writer can safely handle a mounted target.
    private boolean canWriteMountedTarget(BlockDevice blockDevice) {
        return ddImageWriter.canWriteMountedTarget(blockDevice);
    }

    /// Returns whether a mounted target can be made writable before or during the write.
    ///
    /// @param blockDevice target block device.
    /// @return whether mounted target preparation is available.
    private boolean canPrepareMountedTarget(BlockDevice blockDevice) {
        return blockDevicePreparer.canPrepareMounted(blockDevice) || canWriteMountedTarget(blockDevice);
    }

    /// Re-resolves a selected block target before destructive writes.
    ///
    /// @param selected selected target metadata.
    /// @return current target metadata, or null when the target no longer matches.
    /// @throws IOException when target enumeration fails.
    private @Nullable BlockDevice refreshBlockTarget(BlockDevice selected) throws IOException {
        if (devices == null || selected.fileBacked()) {
            return selected;
        }

        @Nullable BlockDevice current = devices.findDevice(selected.id());
        if (current == null) {
            LOGGER.atWarn().log(() -> "Selected block target is no longer visible. id="
                    + selected.id()
                    + ", path="
                    + selected.path());
            return null;
        }
        if (!sameBlockTarget(selected, current)) {
            LOGGER.atWarn().log(() -> "Selected block target identity changed. selected="
                    + targetIdentitySummary(selected)
                    + ", current="
                    + targetIdentitySummary(current));
            return null;
        }
        return current;
    }

    /// Builds a target-change failure result.
    ///
    /// @param selected selected target metadata.
    /// @return failure result.
    private static OperationResult targetChangedFailure(BlockDevice selected) {
        return OperationResult.failure(SdkMessages.get("core.flash.targetChanged", selected.displayName()));
    }

    /// Returns whether two target snapshots describe the same block device.
    ///
    /// @param selected selected target snapshot.
    /// @param current current target snapshot.
    /// @return whether the target identity still matches.
    private static boolean sameBlockTarget(BlockDevice selected, BlockDevice current) {
        if (!selected.id().equals(current.id())) {
            return false;
        }
        if (!normalizedDevicePath(selected.path()).equals(normalizedDevicePath(current.path()))) {
            return false;
        }
        if ((selected.hardwareId() != null || current.hardwareId() != null)
                && !Objects.equals(selected.hardwareId(), current.hardwareId())) {
            return false;
        }
        if (selected.sizeBytes() > 0L && current.sizeBytes() > 0L && selected.sizeBytes() != current.sizeBytes()) {
            return false;
        }
        return sameKnownText(selected.model(), current.model()) && sameKnownText(selected.busType(), current.busType());
    }

    /// Normalizes a platform device path for identity comparison.
    ///
    /// @param path device path.
    /// @return normalized path text.
    private static String normalizedDevicePath(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }

    /// Compares optional text fields when both values are known.
    ///
    /// @param selected selected value.
    /// @param current current value.
    /// @return whether the values are compatible.
    private static boolean sameKnownText(@Nullable String selected, @Nullable String current) {
        return selected == null || current == null || selected.equals(current);
    }

    /// Summarizes block target identity metadata for logs.
    ///
    /// @param target target metadata.
    /// @return identity summary.
    private static String targetIdentitySummary(BlockDevice target) {
        return target.id()
                + ":"
                + target.path()
                + ":"
                + target.sizeBytes()
                + ":"
                + target.hardwareId()
                + ":"
                + target.model()
                + ":"
                + target.busType();
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
        @Nullable BlockDevice refreshedBlockDevice = refreshBlockTarget(blockDevice);
        if (refreshedBlockDevice == null) {
            return targetChangedFailure(blockDevice);
        }
        blockDevice = refreshedBlockDevice;

        @Nullable String validationError = validateBlockImage(source, blockDevice, canWriteMountedTarget(blockDevice));
        if (validationError != null) {
            String message = validationError;
            Path targetPath = blockDevice.path();
            LOGGER.atWarn().log(() -> "Block target validation failed before write. source="
                    + source
                    + ", target="
                    + targetPath
                    + ", message="
                    + message);
            return OperationResult.failure(validationError);
        }

        BlockDevice writeTarget = blockDevice;
        long sourceSize = Files.size(source);
        String targetDisplayName = writeTarget.displayName();
        LOGGER.atInfo().log(() -> "Writing block image. source="
                + source
                + ", target="
                + writeTarget.path()
                + ", targetDisplayName="
                + targetDisplayName
                + ", bytes="
                + sourceSize
                + ", verify="
                + verify);

        reporter.report(new ProgressEvent("flash", writeMessage, 0L, sourceSize));
        if (verify) {
            if (!ddImageWriter.writeAndVerify(
                    source,
                    writeTarget,
                    sourceSize,
                    writeMessage,
                    verifyMessage,
                    reporter)) {
                LOGGER.atWarn().log(() -> "Block image verification failed. target=" + writeTarget.path());
                return OperationResult.failure(SdkMessages.get("core.flash.verifyFailed"));
            }
            LOGGER.atInfo().log(() -> "Block image write and verification completed. target=" + writeTarget.path());
        } else {
            ddImageWriter.write(
                    source,
                    writeTarget,
                    sourceSize,
                    writeMessage,
                    reporter);
            LOGGER.atInfo().log(() -> "Block image write completed. target=" + writeTarget.path());
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
        @Unmodifiable List<ImageComponent> components = image.components();
        if (components.size() <= 1) {
            @Unmodifiable Map<String, Path> partitions = resolvePartitionPaths(image, materialized);
            return fastboot.flash(image.strategy(), partitions, fastbootDevice, reporter);
        }

        for (ImageComponent component : components) {
            if (!ProvisionStrategies.isFastboot(component.strategy())) {
                return OperationResult.failure(SdkMessages.get("core.fastboot.unsupportedStrategy", component.strategy()));
            }

            LOGGER.atInfo().log(() -> "Dispatching fastboot image component. atom="
                    + image.atom()
                    + ", component="
                    + component.atom()
                    + ", strategy="
                    + component.strategy());
            @Unmodifiable Map<String, Path> partitions =
                    resolvePartitionPaths(component.atom(), component.partitionMap(), materialized);
            OperationResult result = fastboot.flash(component.strategy(), partitions, fastbootDevice, reporter);
            if (!result.success()) {
                return result;
            }
        }
        return OperationResult.success(SdkMessages.get("core.fastboot.success"));
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
        return resolvePartitionPaths(image.atom(), image.partitionMap(), materialized);
    }

    /// Resolves materialized partition paths.
    ///
    /// @param atom image or component atom used in diagnostics.
    /// @param partitionMap partition map.
    /// @param materialized materialized image file or artifact directory.
    /// @return partition image paths keyed by partition name.
    /// @throws IOException when partition paths cannot be resolved safely.
    private static @Unmodifiable Map<String, Path> resolvePartitionPaths(
            String atom,
            @Unmodifiable Map<String, String> partitionMap,
            Path materialized) throws IOException {
        if (partitionMap.isEmpty()) {
            throw new IOException(SdkMessages.get("core.materialize.noPartitionMap", atom));
        }

        if (partitionMap.size() == 1 && Files.isRegularFile(materialized)) {
            Map.Entry<String, String> entry = partitionMap.entrySet().iterator().next();
            return Map.of(entry.getKey(), resolveSinglePartitionFile(entry.getValue(), materialized));
        }

        if (!Files.isDirectory(materialized)) {
            throw new IOException(SdkMessages.get("core.materialize.partitionMissing", materialized));
        }

        Path normalizedRoot = materialized.toAbsolutePath().normalize();
        Path realRoot = normalizedRoot.toRealPath();
        LinkedHashMap<String, Path> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : partitionMap.entrySet()) {
            Path path = normalizedRoot.resolve(entry.getValue()).normalize();
            if (!path.startsWith(normalizedRoot)) {
                throw new IOException(SdkMessages.get("core.materialize.partitionEscape", entry.getValue()));
            }
            if (!Files.isRegularFile(path)) {
                throw new IOException(SdkMessages.get("core.materialize.partitionMissing", path));
            }
            Path realPath = path.toRealPath();
            if (!realPath.startsWith(realRoot)) {
                throw new IOException(SdkMessages.get("core.materialize.partitionEscape", entry.getValue()));
            }
            result.put(entry.getKey(), realPath);
        }
        return Collections.unmodifiableMap(result);
    }

    /// Resolves one materialized partition file without allowing symlink escape from its parent.
    ///
    /// @param partitionPath partition path from image metadata.
    /// @param materialized materialized partition file.
    /// @return real partition file path.
    /// @throws IOException when the path cannot be resolved safely.
    private static Path resolveSinglePartitionFile(String partitionPath, Path materialized) throws IOException {
        Path normalizedPath = materialized.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalizedPath)) {
            throw new IOException(SdkMessages.get("core.materialize.partitionMissing", normalizedPath));
        }

        @Nullable Path normalizedParent = normalizedPath.getParent();
        if (normalizedParent == null) {
            return normalizedPath.toRealPath();
        }

        Path realParent = normalizedParent.toRealPath();
        Path realPath = normalizedPath.toRealPath();
        if (!realPath.startsWith(realParent)) {
            throw new IOException(SdkMessages.get("core.materialize.partitionEscape", partitionPath));
        }
        return realPath;
    }

}

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.flash;

import org.glavo.ruyi.imager.core.OperationResult;
import org.glavo.ruyi.imager.core.ProgressEvent;
import org.glavo.ruyi.imager.core.ProgressReporter;
import org.glavo.ruyi.imager.core.StrategySupport;
import org.glavo.ruyi.imager.core.device.BlockDevice;
import org.glavo.ruyi.imager.core.device.BlockDeviceService;
import org.glavo.ruyi.imager.core.fastboot.FastbootDevice;
import org.glavo.ruyi.imager.core.fastboot.FastbootService;
import org.glavo.ruyi.imager.core.image.ImageCatalog;
import org.glavo.ruyi.imager.core.image.ImageCatalogService;
import org.glavo.ruyi.imager.core.image.ImageComponent;
import org.glavo.ruyi.imager.core.image.ImageEntry;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Tests for local flash writes.
@NotNullByDefault
public final class LocalFlashServiceTest {
    /// Progress reporter that ignores progress events.
    private static final ProgressReporter NO_PROGRESS = _ -> {
    };

    /// Writes a local image into a simulated target file.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when fixture files cannot be written or read.
    @Test
    public void flashesLocalImageToSimulatedTarget(@TempDir Path temporaryDirectory) throws Exception {
        byte[] imageBytes = imageBytes(1024);
        Path image = temporaryDirectory.resolve("image.raw");
        Path target = temporaryDirectory.resolve("target.raw");
        Files.write(image, imageBytes);
        Files.write(target, new byte[2048]);

        OperationResult result = flashService(new EmptyImageCatalogService()).flash(
                new FlashRequest(null, image, target(target, 2048, false, false), true),
                NO_PROGRESS);

        assertTrue(result.success(), result.message());
        assertArrayEquals(imageBytes, Arrays.copyOf(Files.readAllBytes(target), imageBytes.length));
    }

    /// Writes a local image through an injected dd image writer.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when fixture files cannot be written or read.
    @Test
    public void usesInjectedDdImageWriter(@TempDir Path temporaryDirectory) throws Exception {
        Path image = temporaryDirectory.resolve("image.raw");
        Path target = temporaryDirectory.resolve("target.raw");
        Files.write(image, new byte[]{1, 2, 3, 4});
        Files.write(target, new byte[16]);
        CapturingDdImageWriter writer = new CapturingDdImageWriter(true);

        OperationResult result = new LocalFlashService(
                new EmptyImageCatalogService(),
                new CapturingFastbootService(),
                BlockDevicePreparer.none(),
                writer).flash(
                new FlashRequest(null, image, target(target, 16, false, false), true),
                NO_PROGRESS);

        assertTrue(result.success(), result.message());
        assertEquals(image, writer.writeSource);
        assertEquals(target, writer.writeTarget);
        assertEquals("Test Target", writer.writeTargetDisplayName);
        assertEquals(4L, writer.writeTotalBytes);
        assertTrue(writer.writeTargetRemovable);
        assertEquals(image, writer.verifySource);
        assertEquals(target, writer.verifyTarget);
        assertEquals("Test Target", writer.verifyTargetDisplayName);
        assertTrue(writer.verifyTargetRemovable);
    }

    /// Writes a local image through a fake writer without verifying.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when the source fixture cannot be written.
    @Test
    public void skipsVerificationWhenRequested(@TempDir Path temporaryDirectory) throws Exception {
        Path image = temporaryDirectory.resolve("image.raw");
        Path target = temporaryDirectory.resolve("target.raw");
        Files.write(image, new byte[]{1, 2, 3, 4});
        CapturingDdImageWriter writer = new CapturingDdImageWriter(true);

        OperationResult result = new LocalFlashService(
                new EmptyImageCatalogService(),
                new CapturingFastbootService(),
                BlockDevicePreparer.none(),
                writer).flash(
                new FlashRequest(null, image, target(target, 16, false, false), false),
                NO_PROGRESS);

        assertTrue(result.success(), result.message());
        assertEquals(1, writer.writeCalls.size());
        assertEquals(0, writer.verifyCalls.size());
        assertEquals(image, writer.writeCalls.getFirst().source());
        assertEquals(target, writer.writeCalls.getFirst().target());
        assertEquals("Test Target", writer.writeCalls.getFirst().targetDisplayName());
        assertEquals(4L, writer.writeCalls.getFirst().totalBytes());
        assertTrue(writer.writeCalls.getFirst().targetRemovable());
    }

    /// Refuses to write when the selected block target no longer matches a fresh enumeration.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when fixture files cannot be written.
    @Test
    public void refusesChangedBlockTargetBeforeWriting(@TempDir Path temporaryDirectory) throws Exception {
        Path image = temporaryDirectory.resolve("image.raw");
        Path target = temporaryDirectory.resolve("target.raw");
        Files.write(image, new byte[]{1, 2, 3, 4});
        Files.write(target, new byte[64]);
        BlockDevice selected = target(target, 32, false, false, false, true, "USB");
        BlockDevice current = target(target, 64, false, false, false, true, "USB");
        CapturingDdImageWriter writer = new CapturingDdImageWriter(true);

        OperationResult result = new LocalFlashService(
                new EmptyImageCatalogService(),
                new FixedBlockDeviceService(List.of(current)),
                new CapturingFastbootService(),
                BlockDevicePreparer.none(),
                writer).flash(
                new FlashRequest(null, image, selected, false),
                NO_PROGRESS);

        assertFalse(result.success());
        assertTrue(result.message().startsWith("Selected target device changed or is no longer available:"));
        assertEquals(0, writer.writeCalls.size());
    }

    /// Reports a failed post-write verification without touching the target file in the writer.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when the source fixture cannot be written.
    @Test
    public void reportsVerificationFailureFromDdWriter(@TempDir Path temporaryDirectory) throws Exception {
        Path image = temporaryDirectory.resolve("image.raw");
        Path target = temporaryDirectory.resolve("target.raw");
        Files.write(image, new byte[]{1, 2, 3, 4});
        CapturingDdImageWriter writer = new CapturingDdImageWriter(false);

        OperationResult result = new LocalFlashService(
                new EmptyImageCatalogService(),
                new CapturingFastbootService(),
                BlockDevicePreparer.none(),
                writer).flash(
                new FlashRequest(null, image, target(target, 16, false, false), true),
                NO_PROGRESS);

        assertFalse(result.success());
        assertEquals("Written image failed verification.", result.message());
        assertEquals(1, writer.writeCalls.size());
        assertEquals(1, writer.verifyCalls.size());
        assertEquals(image, writer.verifyCalls.getFirst().source());
        assertEquals(target, writer.verifyCalls.getFirst().target());
    }

    /// Writes a materialized Ruyi dd-v1 image into a simulated target file.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when fixture files cannot be written or read.
    @Test
    public void flashesRuyiDdImageToSimulatedTarget(@TempDir Path temporaryDirectory) throws Exception {
        byte[] imageBytes = imageBytes(512);
        Path materializedImage = temporaryDirectory.resolve("artifact.raw");
        Path target = temporaryDirectory.resolve("target.raw");
        Files.write(materializedImage, imageBytes);
        Files.write(target, new byte[1024]);

        ImageEntry image = imageEntry("dd-v1");
        OperationResult result = flashService(new FixedImageCatalogService(materializedImage)).flash(
                new FlashRequest(image, null, target(target, 1024, false, false), true),
                NO_PROGRESS);

        assertTrue(result.success(), result.message());
        assertArrayEquals(imageBytes, Arrays.copyOf(Files.readAllBytes(target), imageBytes.length));
    }

    /// Writes a multi-partition Ruyi dd-v1 image into mapped simulated target files.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when fixture files cannot be written or read.
    @Test
    public void flashesMultiPartitionRuyiDdImageToMappedTargets(@TempDir Path temporaryDirectory) throws Exception {
        byte[] bootBytes = imageBytes(128);
        byte[] rootBytes = imageBytes(256);
        Path artifactDirectory = temporaryDirectory.resolve("artifact");
        Files.createDirectories(artifactDirectory);
        Files.write(artifactDirectory.resolve("boot.img"), bootBytes);
        Files.write(artifactDirectory.resolve("root.img"), rootBytes);

        Path bootTarget = temporaryDirectory.resolve("boot-target.raw");
        Path rootTarget = temporaryDirectory.resolve("root-target.raw");
        Files.write(bootTarget, new byte[512]);
        Files.write(rootTarget, new byte[512]);

        ImageEntry image = imageEntry("dd-v1", Map.of("boot", "boot.img", "root", "root.img"));
        OperationResult result = flashService(new FixedImageCatalogService(artifactDirectory)).flash(
                new FlashRequest(image, null, FlashTarget.blockDevices(Map.of(
                        "boot", target(bootTarget, 512, false, false),
                        "root", target(rootTarget, 512, false, false))), true),
                NO_PROGRESS);

        assertTrue(result.success(), result.message());
        assertArrayEquals(bootBytes, Arrays.copyOf(Files.readAllBytes(bootTarget), bootBytes.length));
        assertArrayEquals(rootBytes, Arrays.copyOf(Files.readAllBytes(rootTarget), rootBytes.length));
    }

    /// Writes a multi-partition image through a fake writer in partition-map order.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when fixture files cannot be written.
    @Test
    public void multiPartitionDdImageUsesFakeWriterInPartitionOrder(@TempDir Path temporaryDirectory) throws Exception {
        Path artifactDirectory = temporaryDirectory.resolve("artifact");
        Files.createDirectories(artifactDirectory);
        Path boot = artifactDirectory.resolve("boot.img");
        Path root = artifactDirectory.resolve("root.img");
        Files.write(boot, new byte[]{1, 2, 3});
        Files.write(root, new byte[]{4, 5, 6, 7});

        Path bootTarget = temporaryDirectory.resolve("boot-target.raw");
        Path rootTarget = temporaryDirectory.resolve("root-target.raw");
        LinkedHashMap<String, String> partitionMap = new LinkedHashMap<>();
        partitionMap.put("boot", "boot.img");
        partitionMap.put("root", "root.img");
        LinkedHashMap<String, BlockDevice> targetMap = new LinkedHashMap<>();
        targetMap.put("boot", target(bootTarget, 32, false, false));
        targetMap.put("root", target(rootTarget, 32, false, false));
        CapturingDdImageWriter writer = new CapturingDdImageWriter(true);

        OperationResult result = new LocalFlashService(
                new FixedImageCatalogService(artifactDirectory),
                new CapturingFastbootService(),
                BlockDevicePreparer.none(),
                writer).flash(
                new FlashRequest(
                        imageEntry("dd-v1", Collections.unmodifiableMap(partitionMap)),
                        null,
                        FlashTarget.blockDevices(Collections.unmodifiableMap(targetMap)),
                        true),
                NO_PROGRESS);

        assertTrue(result.success(), result.message());
        assertEquals(List.of(boot, root), writer.writeCalls.stream().map(DdCall::source).toList());
        assertEquals(List.of(bootTarget, rootTarget), writer.writeCalls.stream().map(DdCall::target).toList());
        assertEquals(
                List.of("Test Target", "Test Target"),
                writer.writeCalls.stream().map(DdCall::targetDisplayName).toList());
        assertEquals(List.of(3L, 4L), writer.writeCalls.stream().map(DdCall::totalBytes).toList());
        assertEquals(List.of(true, true), writer.writeCalls.stream().map(DdCall::targetRemovable).toList());
        assertEquals(List.of(boot, root), writer.verifyCalls.stream().map(DdCall::source).toList());
    }

    /// Refuses a materialized partition path that resolves outside the artifact directory through a symbolic link.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when fixture files cannot be written.
    @Test
    public void refusesMaterializedPartitionSymlinkEscapingArtifactDirectory(@TempDir Path temporaryDirectory) throws Exception {
        Path artifactDirectory = temporaryDirectory.resolve("artifact");
        Files.createDirectories(artifactDirectory);
        Path externalImage = temporaryDirectory.resolve("external.img");
        Files.write(externalImage, new byte[]{1, 2, 3});
        Path symlink = artifactDirectory.resolve("boot.img");
        try {
            Files.createSymbolicLink(symlink, externalImage);
        } catch (IOException | SecurityException | UnsupportedOperationException exception) {
            assumeTrue(false, "Symbolic links are not available: " + exception);
        }

        Path target = temporaryDirectory.resolve("target.raw");
        Files.write(target, new byte[32]);
        CapturingDdImageWriter writer = new CapturingDdImageWriter(true);
        ImageEntry image = imageEntry("dd-v1", Map.of("boot", "boot.img"));

        IOException exception = assertThrows(IOException.class, () -> new LocalFlashService(
                new FixedImageCatalogService(artifactDirectory),
                new CapturingFastbootService(),
                BlockDevicePreparer.none(),
                writer).flash(
                new FlashRequest(image, null, FlashTarget.blockDevice(target(target, 32, false, false)), false),
                NO_PROGRESS));

        assertEquals("Partition path escapes artifact directory: boot.img", exception.getMessage());
        assertEquals(0, writer.writeCalls.size());
    }

    /// Refuses a single materialized file that resolves outside its parent through a symbolic link.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when fixture files cannot be written.
    @Test
    public void refusesSingleMaterializedPartitionSymlinkEscapingParent(@TempDir Path temporaryDirectory) throws Exception {
        Path artifactDirectory = temporaryDirectory.resolve("artifact");
        Files.createDirectories(artifactDirectory);
        Path externalImage = temporaryDirectory.resolve("external.img");
        Files.write(externalImage, new byte[]{1, 2, 3});
        Path symlink = artifactDirectory.resolve("disk.img");
        try {
            Files.createSymbolicLink(symlink, externalImage);
        } catch (IOException | SecurityException | UnsupportedOperationException exception) {
            assumeTrue(false, "Symbolic links are not available: " + exception);
        }

        Path target = temporaryDirectory.resolve("target.raw");
        Files.write(target, new byte[32]);
        CapturingDdImageWriter writer = new CapturingDdImageWriter(true);
        ImageEntry image = imageEntry("dd-v1", Map.of("disk", "disk.img"));

        IOException exception = assertThrows(IOException.class, () -> new LocalFlashService(
                new FixedImageCatalogService(symlink),
                new CapturingFastbootService(),
                BlockDevicePreparer.none(),
                writer).flash(
                new FlashRequest(image, null, FlashTarget.blockDevice(target(target, 32, false, false)), false),
                NO_PROGRESS));

        assertEquals("Partition path escapes artifact directory: disk.img", exception.getMessage());
        assertEquals(0, writer.writeCalls.size());
    }

    /// Refuses a multi-partition Ruyi dd-v1 image when only a single block target is provided.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when fixture files cannot be written.
    @Test
    public void refusesMultiPartitionRuyiDdImageWithoutMappedTargets(@TempDir Path temporaryDirectory) throws Exception {
        Path artifactDirectory = temporaryDirectory.resolve("artifact");
        Files.createDirectories(artifactDirectory);
        Files.write(artifactDirectory.resolve("boot.img"), new byte[]{1});
        Files.write(artifactDirectory.resolve("root.img"), new byte[]{2});

        Path target = temporaryDirectory.resolve("target.raw");
        Files.write(target, new byte[16]);

        ImageEntry image = imageEntry("dd-v1", Map.of("boot", "boot.img", "root", "root.img"));
        OperationResult result = new LocalFlashService(new FixedImageCatalogService(artifactDirectory)).flash(
                new FlashRequest(image, null, target(target, 16, false, false), false),
                NO_PROGRESS);

        assertFalse(result.success());
    }

    /// Refuses a partition target assigned to an unknown partition before invoking the writer.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when fixture files cannot be written.
    @Test
    public void refusesUnknownPartitionTargetBeforeWriting(@TempDir Path temporaryDirectory) throws Exception {
        Path artifactDirectory = temporaryDirectory.resolve("artifact");
        Files.createDirectories(artifactDirectory);
        Files.write(artifactDirectory.resolve("boot.img"), new byte[]{1});
        ImageEntry image = imageEntry("dd-v1", Map.of("boot", "boot.img"));
        CapturingDdImageWriter writer = new CapturingDdImageWriter(true);

        OperationResult result = new LocalFlashService(
                new FixedImageCatalogService(artifactDirectory),
                new CapturingFastbootService(),
                BlockDevicePreparer.none(),
                writer).flash(
                new FlashRequest(
                        image,
                        null,
                        FlashTarget.blockDevices(Map.of(
                                "boot", target(temporaryDirectory.resolve("boot.raw"), 8, false, false),
                                "root", target(temporaryDirectory.resolve("root.raw"), 8, false, false))),
                        false),
                NO_PROGRESS);

        assertFalse(result.success());
        assertEquals("Partition target was provided for an unknown partition: root", result.message());
        assertEquals(0, writer.writeCalls.size());
    }

    /// Refuses a missing partition target before invoking the writer.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when fixture files cannot be written.
    @Test
    public void refusesMissingPartitionTargetBeforeWriting(@TempDir Path temporaryDirectory) throws Exception {
        Path artifactDirectory = temporaryDirectory.resolve("artifact");
        Files.createDirectories(artifactDirectory);
        Files.write(artifactDirectory.resolve("boot.img"), new byte[]{1});
        Files.write(artifactDirectory.resolve("root.img"), new byte[]{2});
        ImageEntry image = imageEntry("dd-v1", Map.of("boot", "boot.img", "root", "root.img"));
        CapturingDdImageWriter writer = new CapturingDdImageWriter(true);

        OperationResult result = new LocalFlashService(
                new FixedImageCatalogService(artifactDirectory),
                new CapturingFastbootService(),
                BlockDevicePreparer.none(),
                writer).flash(
                new FlashRequest(
                        image,
                        null,
                        FlashTarget.blockDevices(Map.of(
                                "boot", target(temporaryDirectory.resolve("boot.raw"), 8, false, false))),
                        false),
                NO_PROGRESS);

        assertFalse(result.success());
        assertEquals("Missing target device for partition: root", result.message());
        assertEquals(0, writer.writeCalls.size());
    }

    /// Refuses duplicate partition target paths before invoking the writer.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when fixture files cannot be written.
    @Test
    public void refusesDuplicatePartitionTargetBeforeWriting(@TempDir Path temporaryDirectory) throws Exception {
        Path artifactDirectory = temporaryDirectory.resolve("artifact");
        Files.createDirectories(artifactDirectory);
        Files.write(artifactDirectory.resolve("boot.img"), new byte[]{1});
        Files.write(artifactDirectory.resolve("root.img"), new byte[]{2});
        ImageEntry image = imageEntry("dd-v1", Map.of("boot", "boot.img", "root", "root.img"));
        Path duplicatedTarget = temporaryDirectory.resolve("shared.raw");
        CapturingDdImageWriter writer = new CapturingDdImageWriter(true);

        OperationResult result = new LocalFlashService(
                new FixedImageCatalogService(artifactDirectory),
                new CapturingFastbootService(),
                BlockDevicePreparer.none(),
                writer).flash(
                new FlashRequest(
                        image,
                        null,
                        FlashTarget.blockDevices(Map.of(
                                "boot", target(duplicatedTarget, 8, false, false),
                                "root", target(duplicatedTarget, 8, false, false))),
                        false),
                NO_PROGRESS);

        assertFalse(result.success());
        assertTrue(result.message().startsWith("Partition target is assigned more than once:"));
        assertEquals(0, writer.writeCalls.size());
    }

    /// Flashes a materialized Ruyi fastboot image through a fastboot backend.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when fixture files cannot be written or read.
    @Test
    public void flashesRuyiFastbootImageWithMaterializedPartitions(@TempDir Path temporaryDirectory) throws Exception {
        Path artifactDirectory = temporaryDirectory.resolve("artifact");
        Files.createDirectories(artifactDirectory);
        Path boot = artifactDirectory.resolve("boot.ext4");
        Path root = artifactDirectory.resolve("root.ext4");
        Files.write(boot, new byte[]{1, 2, 3});
        Files.write(root, new byte[]{4, 5, 6});

        ImageEntry image = imageEntry("fastboot-v1", Map.of("boot", "boot.ext4", "root", "root.ext4"));
        CapturingFastbootService fastboot = new CapturingFastbootService();
        FastbootDevice device = new FastbootDevice("test-fastboot", "test-fastboot", "fastboot");

        OperationResult result = new LocalFlashService(
                new FixedImageCatalogService(artifactDirectory),
                fastboot).flash(
                new FlashRequest(image, null, FlashTarget.fastbootDevice(device), false),
                NO_PROGRESS);

        assertTrue(result.success(), result.message());
        assertEquals("fastboot-v1", fastboot.strategy);
        assertEquals(device, fastboot.device);
        assertEquals(boot, fastboot.partitions.get("boot"));
        assertEquals(root, fastboot.partitions.get("root"));
    }

    /// Flashes a fastboot image combo by preserving each component strategy.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when fixture files cannot be written or read.
    @Test
    public void flashesFastbootImageComboWithComponentStrategies(@TempDir Path temporaryDirectory) throws Exception {
        Path artifactDirectory = temporaryDirectory.resolve("artifact");
        Files.createDirectories(artifactDirectory);
        Path uboot = artifactDirectory.resolve("u-boot-with-spl.bin");
        Path boot = artifactDirectory.resolve("boot.ext4");
        Path root = artifactDirectory.resolve("root.ext4");
        Files.write(uboot, new byte[]{1});
        Files.write(boot, new byte[]{2});
        Files.write(root, new byte[]{3});

        LinkedHashMap<String, String> ubootPartitions = new LinkedHashMap<>();
        ubootPartitions.put("uboot", "u-boot-with-spl.bin");
        LinkedHashMap<String, String> systemPartitions = new LinkedHashMap<>();
        systemPartitions.put("boot", "boot.ext4");
        systemPartitions.put("root", "root.ext4");
        LinkedHashMap<String, String> combinedPartitions = new LinkedHashMap<>();
        combinedPartitions.putAll(ubootPartitions);
        combinedPartitions.putAll(systemPartitions);

        ImageEntry image = new ImageEntry(
                "ruyisdk",
                "image-combo",
                "revyos-sipeed-lpi4a@8g",
                "0.20251226.0",
                null,
                "image-combo/revyos-sipeed-lpi4a@8g(0.20251226.0)",
                "RevyOS for Sipeed LicheePi 4A (8G RAM)",
                "Sipeed",
                "sipeed-lpi4a",
                "8g",
                "fastboot-v1(lpi4a-uboot)",
                combinedPartitions,
                List.of(),
                StrategySupport.SUPPORTED,
                List.of(
                        new ImageComponent(
                                "board-image",
                                "uboot-revyos-sipeed-lpi4a-8g",
                                "0.20251226.0",
                                "board-image/uboot-revyos-sipeed-lpi4a-8g(0.20251226.0)",
                                "fastboot-v1(lpi4a-uboot)",
                                ubootPartitions,
                                List.of()),
                        new ImageComponent(
                                "board-image",
                                "revyos-sipeed-lpi4a",
                                "0.20251226.0",
                                "board-image/revyos-sipeed-lpi4a(0.20251226.0)",
                                "fastboot-v1",
                                systemPartitions,
                                List.of())));
        CapturingFastbootService fastboot = new CapturingFastbootService();
        FastbootDevice device = new FastbootDevice("test-fastboot", "test-fastboot", "fastboot");

        OperationResult result = new LocalFlashService(
                new FixedImageCatalogService(artifactDirectory),
                fastboot).flash(
                new FlashRequest(image, null, FlashTarget.fastbootDevice(device), false),
                NO_PROGRESS);

        assertTrue(result.success(), result.message());
        assertEquals(2, fastboot.calls.size());
        assertEquals("fastboot-v1(lpi4a-uboot)", fastboot.calls.get(0).strategy());
        assertEquals(Map.of("uboot", uboot), fastboot.calls.get(0).partitions());
        assertEquals("fastboot-v1", fastboot.calls.get(1).strategy());
        assertEquals(boot, fastboot.calls.get(1).partitions().get("boot"));
        assertEquals(root, fastboot.calls.get(1).partitions().get("root"));
    }

    /// Refuses to flash fastboot images to block device targets.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when fixture files cannot be written.
    @Test
    public void refusesFastbootImageWithBlockTarget(@TempDir Path temporaryDirectory) throws Exception {
        Path artifactDirectory = temporaryDirectory.resolve("artifact");
        Files.createDirectories(artifactDirectory);
        Files.write(artifactDirectory.resolve("boot.ext4"), new byte[]{1});
        Path target = temporaryDirectory.resolve("target.raw");
        Files.write(target, new byte[8]);

        ImageEntry image = imageEntry("fastboot-v1", Map.of("boot", "boot.ext4"));
        OperationResult result = new LocalFlashService(new FixedImageCatalogService(artifactDirectory)).flash(
                new FlashRequest(image, null, target(target, 8, false, false), false),
                NO_PROGRESS);

        assertFalse(result.success());
    }

    /// Refuses to write to a target marked as a system disk.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when fixture files cannot be written.
    @Test
    public void refusesSystemTarget(@TempDir Path temporaryDirectory) throws Exception {
        Path image = temporaryDirectory.resolve("image.raw");
        Path target = temporaryDirectory.resolve("target.raw");
        Files.write(image, new byte[]{1});
        Files.write(target, new byte[8]);

        OperationResult result = new LocalFlashService(new EmptyImageCatalogService()).flash(
                new FlashRequest(null, image, target(target, 8, true, false), false),
                NO_PROGRESS);

        assertFalse(result.success());
    }

    /// Refuses to write to a target marked read-only.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when fixture files cannot be written.
    @Test
    public void refusesReadOnlyTarget(@TempDir Path temporaryDirectory) throws Exception {
        Path image = temporaryDirectory.resolve("image.raw");
        Path target = temporaryDirectory.resolve("target.raw");
        Files.write(image, new byte[]{1});
        Files.write(target, new byte[8]);

        OperationResult result = new LocalFlashService(new EmptyImageCatalogService()).flash(
                new FlashRequest(null, image, target(target, 8, false, true), false),
                NO_PROGRESS);

        assertFalse(result.success());
    }

    /// Refuses to write to a target marked non-removable.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when fixture files cannot be written.
    @Test
    public void refusesNonRemovableTarget(@TempDir Path temporaryDirectory) throws Exception {
        Path image = temporaryDirectory.resolve("image.raw");
        Path target = temporaryDirectory.resolve("target.raw");
        Files.write(image, new byte[]{1});
        Files.write(target, new byte[8]);
        CapturingDdImageWriter writer = new CapturingDdImageWriter(true);

        OperationResult result = new LocalFlashService(
                new EmptyImageCatalogService(),
                new CapturingFastbootService(),
                BlockDevicePreparer.none(),
                writer).flash(
                new FlashRequest(null, image, target(target, 8, false, false, false, false), false),
                NO_PROGRESS);

        assertFalse(result.success());
        assertEquals("Refusing to write to a non-removable device.", result.message());
        assertEquals(0, writer.writeCalls.size());
    }

    /// Refuses a real block-device target whose size is unknown.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when fixture files cannot be written.
    @Test
    public void refusesUnknownSizeRealTarget(@TempDir Path temporaryDirectory) throws Exception {
        Path image = temporaryDirectory.resolve("image.raw");
        Path target = temporaryDirectory.resolve("target.raw");
        Files.write(image, new byte[]{1});
        Files.write(target, new byte[8]);
        CapturingDdImageWriter writer = new CapturingDdImageWriter(true);

        OperationResult result = new LocalFlashService(
                new EmptyImageCatalogService(),
                new CapturingFastbootService(),
                BlockDevicePreparer.none(),
                writer).flash(
                new FlashRequest(null, image, target(target, 0, false, false, false, true, "USB"), false),
                NO_PROGRESS);

        assertFalse(result.success());
        assertEquals("Refusing to write to a real block device with unknown size.", result.message());
        assertEquals(0, writer.writeCalls.size());
    }

    /// Allows unknown-size file-backed targets used by tests and simulations.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when fixture files cannot be written.
    @Test
    public void allowsUnknownSizeFileTarget(@TempDir Path temporaryDirectory) throws Exception {
        byte[] imageBytes = new byte[]{1, 2, 3, 4};
        Path image = temporaryDirectory.resolve("image.raw");
        Path target = temporaryDirectory.resolve("target.raw");
        Files.write(image, imageBytes);
        Files.write(target, new byte[8]);

        OperationResult result = flashService(new EmptyImageCatalogService()).flash(
                new FlashRequest(null, image, target(target, 0, false, false), false),
                NO_PROGRESS);

        assertTrue(result.success(), result.message());
        assertArrayEquals(imageBytes, Arrays.copyOf(Files.readAllBytes(target), imageBytes.length));
    }

    /// Refuses to write to a target with mounted volumes.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when fixture files cannot be written.
    @Test
    public void refusesMountedTarget(@TempDir Path temporaryDirectory) throws Exception {
        Path image = temporaryDirectory.resolve("image.raw");
        Path target = temporaryDirectory.resolve("target.raw");
        Files.write(image, new byte[]{1});
        Files.write(target, new byte[8]);

        OperationResult result = new LocalFlashService(new EmptyImageCatalogService()).flash(
                new FlashRequest(null, image, new BlockDevice(
                        "test",
                        "Test Target",
                        target,
                        8L,
                        true,
                        false,
                        true,
                        false,
                        "Test",
                        "file",
                        List.of("/mnt/test")), false),
                NO_PROGRESS);

        assertFalse(result.success());
        assertEquals("Refusing to write to a mounted device: /mnt/test", result.message());
    }

    /// Prepares a mounted target before writing when the preparer clears the mount state.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when fixture files cannot be written or read.
    @Test
    public void preparesMountedTargetBeforeWriting(@TempDir Path temporaryDirectory) throws Exception {
        byte[] imageBytes = imageBytes(16);
        Path image = temporaryDirectory.resolve("image.raw");
        Path target = temporaryDirectory.resolve("target.raw");
        Files.write(image, imageBytes);
        Files.write(target, new byte[32]);
        FixedBlockDevicePreparer preparer = new FixedBlockDevicePreparer(true);

        OperationResult result = new LocalFlashService(
                new EmptyImageCatalogService(),
                new CapturingFastbootService(),
                preparer,
                new CopyingDdImageWriter()).flash(
                new FlashRequest(null, image, target(target, 32, false, true, false), true),
                NO_PROGRESS);

        assertTrue(result.success(), result.message());
        assertEquals(1, preparer.calls);
        assertArrayEquals(imageBytes, Arrays.copyOf(Files.readAllBytes(target), imageBytes.length));
    }

    /// Prepares an unmounted target when the platform preparer still requires preparation.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when fixture files cannot be written or read.
    @Test
    public void preparesUnmountedTargetWhenPreparerRequests(@TempDir Path temporaryDirectory) throws Exception {
        byte[] imageBytes = imageBytes(16);
        Path image = temporaryDirectory.resolve("image.raw");
        Path target = temporaryDirectory.resolve("target.raw");
        Files.write(image, imageBytes);
        Files.write(target, new byte[32]);
        AlwaysPreparingBlockDevicePreparer preparer = new AlwaysPreparingBlockDevicePreparer();

        OperationResult result = new LocalFlashService(
                new EmptyImageCatalogService(),
                new CapturingFastbootService(),
                preparer,
                new CopyingDdImageWriter()).flash(
                new FlashRequest(null, image, target(target, 32, false, false, false), false),
                NO_PROGRESS);

        assertTrue(result.success(), result.message());
        assertEquals(1, preparer.calls);
        assertArrayEquals(imageBytes, Arrays.copyOf(Files.readAllBytes(target), imageBytes.length));
    }

    /// Reports the prepare stage as complete when no platform preparation is required.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when fixture files cannot be written.
    @Test
    public void reportsPrepareCompleteWhenPreparationIsSkipped(@TempDir Path temporaryDirectory) throws Exception {
        Path image = temporaryDirectory.resolve("image.raw");
        Path target = temporaryDirectory.resolve("target.raw");
        Files.write(image, new byte[]{1, 2, 3, 4});
        Files.write(target, new byte[32]);
        CapturingDdImageWriter writer = new CapturingDdImageWriter(true);
        ArrayList<ProgressEvent> events = new ArrayList<>();

        OperationResult result = new LocalFlashService(
                new EmptyImageCatalogService(),
                new CapturingFastbootService(),
                BlockDevicePreparer.none(),
                writer).flash(
                new FlashRequest(null, image, target(target, 32, false, false, false), true),
                events::add);

        assertTrue(result.success(), result.message());
        assertEquals(1, writer.writeCalls.size());
        assertEquals(1, writer.verifyCalls.size());
        assertEquals("prepare", events.getFirst().stage());
        assertEquals(1L, events.getFirst().currentBytes());
        assertEquals(1L, events.getFirst().totalBytes());
    }

    /// Lets the dd writer handle mounted targets without invoking the block-device preparer.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when fixture files cannot be written.
    @Test
    public void mountedTargetCapableDdWriterSkipsBlockDevicePreparer(@TempDir Path temporaryDirectory) throws Exception {
        Path image = temporaryDirectory.resolve("image.raw");
        Path target = temporaryDirectory.resolve("target.raw");
        Files.write(image, new byte[]{1, 2, 3, 4});
        Files.write(target, new byte[32]);
        AlwaysPreparingBlockDevicePreparer preparer = new AlwaysPreparingBlockDevicePreparer();
        CapturingDdImageWriter writer = new CapturingDdImageWriter(true, true);
        ArrayList<ProgressEvent> events = new ArrayList<>();

        OperationResult result = new LocalFlashService(
                new EmptyImageCatalogService(),
                new CapturingFastbootService(),
                preparer,
                writer).flash(
                new FlashRequest(null, image, target(target, 32, false, true, false), true),
                events::add);

        assertTrue(result.success(), result.message());
        assertEquals(0, preparer.calls);
        assertEquals(1, writer.writeCalls.size());
        assertEquals(1, writer.verifyCalls.size());
        assertTrue(events.stream().anyMatch(event ->
                "prepare".equals(event.stage())
                        && event.currentBytes() == 1L
                        && event.totalBytes() == 1L));
    }

    /// Refuses a mounted target when preparation leaves it mounted.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when fixture files cannot be written.
    @Test
    public void refusesMountedTargetWhenPreparationLeavesMounted(@TempDir Path temporaryDirectory) throws Exception {
        Path image = temporaryDirectory.resolve("image.raw");
        Path target = temporaryDirectory.resolve("target.raw");
        Files.write(image, new byte[]{1});
        Files.write(target, new byte[8]);
        FixedBlockDevicePreparer preparer = new FixedBlockDevicePreparer(false);

        OperationResult result = new LocalFlashService(
                new EmptyImageCatalogService(),
                new CapturingFastbootService(),
                preparer).flash(
                new FlashRequest(null, image, target(target, 8, false, true, false), false),
                NO_PROGRESS);

        assertFalse(result.success());
        assertEquals(1, preparer.calls);
    }

    /// Refuses an image that is larger than the target.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when fixture files cannot be written.
    @Test
    public void refusesOversizedImage(@TempDir Path temporaryDirectory) throws Exception {
        Path image = temporaryDirectory.resolve("image.raw");
        Path target = temporaryDirectory.resolve("target.raw");
        Files.write(image, new byte[16]);
        Files.write(target, new byte[8]);

        OperationResult result = new LocalFlashService(new EmptyImageCatalogService()).flash(
                new FlashRequest(null, image, target(target, 8, false, false), false),
                NO_PROGRESS);

        assertFalse(result.success());
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

    /// Creates a flash service with an in-process test dd writer.
    ///
    /// @param images image catalog service.
    /// @return test flash service.
    private static LocalFlashService flashService(ImageCatalogService images) {
        return new LocalFlashService(
                images,
                new CapturingFastbootService(),
                BlockDevicePreparer.none(),
                new CopyingDdImageWriter());
    }

    /// Creates a test target device.
    ///
    /// @param path target path.
    /// @param sizeBytes target size.
    /// @param system whether target is a system disk.
    /// @param readOnly whether target is read-only.
    /// @return target device.
    private static BlockDevice target(Path path, long sizeBytes, boolean system, boolean readOnly) {
        return target(path, sizeBytes, system, false, readOnly);
    }

    /// Creates a test target device.
    ///
    /// @param path target path.
    /// @param sizeBytes target size.
    /// @param system whether target is a system disk.
    /// @param mounted whether target has mounted volumes.
    /// @param readOnly whether target is read-only.
    /// @return target device.
    private static BlockDevice target(
            Path path,
            long sizeBytes,
            boolean system,
            boolean mounted,
            boolean readOnly) {
        return target(path, sizeBytes, system, mounted, readOnly, true);
    }

    /// Creates a test target device.
    ///
    /// @param path target path.
    /// @param sizeBytes target size.
    /// @param system whether target is a system disk.
    /// @param mounted whether target has mounted volumes.
    /// @param readOnly whether target is read-only.
    /// @param removable whether target is removable.
    /// @return target device.
    private static BlockDevice target(
            Path path,
            long sizeBytes,
            boolean system,
            boolean mounted,
            boolean readOnly,
            boolean removable) {
        return target(path, sizeBytes, system, mounted, readOnly, removable, "file");
    }

    /// Creates a test target device.
    ///
    /// @param path target path.
    /// @param sizeBytes target size.
    /// @param system whether target is a system disk.
    /// @param mounted whether target has mounted volumes.
    /// @param readOnly whether target is read-only.
    /// @param removable whether target is removable.
    /// @param busType target bus type.
    /// @return target device.
    private static BlockDevice target(
            Path path,
            long sizeBytes,
            boolean system,
            boolean mounted,
            boolean readOnly,
            boolean removable,
            @Nullable String busType) {
        return new BlockDevice("test", "Test Target", path, sizeBytes, removable, system, mounted, readOnly, "Test", busType);
    }

    /// Returns test target metadata with mount state cleared.
    ///
    /// @param target original target metadata.
    /// @return unmounted target metadata.
    private static BlockDevice unmounted(BlockDevice target) {
        return new BlockDevice(
                target.id(),
                target.displayName(),
                target.path(),
                target.sizeBytes(),
                target.removable(),
                target.system(),
                false,
                target.readOnly(),
                target.model(),
                target.busType(),
                target.hardwareId(),
                target.mountPoints());
    }

    /// Creates a test image entry.
    ///
    /// @param strategy provision strategy.
    /// @return test image entry.
    private static ImageEntry imageEntry(String strategy) {
        return imageEntry(strategy, Map.of("disk", "artifact.raw"));
    }

    /// Creates a test image entry.
    ///
    /// @param strategy provision strategy.
    /// @param partitionMap partition map.
    /// @return test image entry.
    private static ImageEntry imageEntry(String strategy, @Unmodifiable Map<String, String> partitionMap) {
        return new ImageEntry(
                "ruyisdk",
                "board-image",
                "test-board",
                "1.0.0",
                null,
                "board-image/test-board(1.0.0)",
                "Test image",
                "Test Manufacturer",
                "test-board",
                "generic",
                strategy,
                partitionMap,
                List.of(),
                StrategySupport.SUPPORTED);
    }

    /// Block-device service returning fixed test devices.
    @NotNullByDefault
    private static final class FixedBlockDeviceService implements BlockDeviceService {
        /// Fixed block-device list.
        private final @Unmodifiable List<BlockDevice> devices;

        /// Creates the fixed service.
        ///
        /// @param devices fixed devices.
        private FixedBlockDeviceService(@Unmodifiable List<BlockDevice> devices) {
            this.devices = List.copyOf(devices);
        }

        /// Lists the fixed devices.
        ///
        /// @return fixed devices.
        @Override
        public @Unmodifiable List<BlockDevice> listDevices() {
            return devices;
        }
    }

    /// Fastboot service that captures flash requests.
    @NotNullByDefault
    private static final class CapturingFastbootService implements FastbootService {
        /// Captured strategy.
        private @Nullable String strategy;

        /// Captured partition map.
        private @Unmodifiable Map<String, Path> partitions = Map.of();

        /// Captured device.
        private @Nullable FastbootDevice device;

        /// Captured flash calls.
        private final ArrayList<FastbootCall> calls = new ArrayList<>();

        /// Lists no devices.
        ///
        /// @return empty fastboot device list.
        @Override
        public @Unmodifiable List<FastbootDevice> listDevices() {
            return List.of();
        }

        /// Captures one fastboot flash request.
        ///
        /// @param strategy Ruyi provision strategy.
        /// @param partitions materialized partition images keyed by target partition name.
        /// @param device target fastboot device.
        /// @param reporter progress reporter.
        /// @return success result.
        @Override
        public OperationResult flash(
                String strategy,
                @Unmodifiable Map<String, Path> partitions,
                FastbootDevice device,
                ProgressReporter reporter) {
            this.strategy = strategy;
            this.partitions = Map.copyOf(partitions);
            this.device = device;
            this.calls.add(new FastbootCall(strategy, Map.copyOf(partitions), device));
            return OperationResult.success("Fastboot complete.");
        }
    }

    /// Captured fastboot flash call.
    ///
    /// @param strategy Ruyi provision strategy.
    /// @param partitions materialized partition images keyed by target partition name.
    /// @param device target fastboot device.
    @NotNullByDefault
    private record FastbootCall(
            String strategy,
            @Unmodifiable Map<String, Path> partitions,
            FastbootDevice device) {
    }

    /// Test preparer that can optionally clear mount state.
    @NotNullByDefault
    private static final class FixedBlockDevicePreparer implements BlockDevicePreparer {
        /// Whether this preparer should return an unmounted target.
        private final boolean clearMounted;

        /// Number of prepare calls.
        private int calls;

        /// Creates a fixed block-device preparer.
        ///
        /// @param clearMounted whether this preparer should return an unmounted target.
        private FixedBlockDevicePreparer(boolean clearMounted) {
            this.clearMounted = clearMounted;
        }

        /// Prepares one target for the test.
        ///
        /// @param target target block device.
        /// @param reporter progress reporter.
        /// @return original or unmounted target metadata.
        @Override
        public BlockDevice prepare(BlockDevice target, ProgressReporter reporter) {
            calls++;
            return clearMounted ? unmounted(target) : target;
        }

        /// Returns whether this test preparer accepts mounted targets.
        ///
        /// @param target target block device.
        /// @return always true.
        @Override
        public boolean canPrepareMounted(BlockDevice target) {
            return true;
        }
    }

    /// Test preparer that requests preparation for every target.
    @NotNullByDefault
    private static final class AlwaysPreparingBlockDevicePreparer implements BlockDevicePreparer {
        /// Number of prepare calls.
        private int calls;

        /// Always requests target preparation.
        ///
        /// @param target target block device.
        /// @return always true.
        @Override
        public boolean shouldPrepare(BlockDevice target) {
            return true;
        }

        /// Captures one preparation request and returns the original target.
        ///
        /// @param target target block device.
        /// @param reporter progress reporter.
        /// @return original target.
        @Override
        public BlockDevice prepare(BlockDevice target, ProgressReporter reporter) {
            calls++;
            return target;
        }
    }

    /// Test dd image writer that captures write and verification calls.
    @NotNullByDefault
    private static final class CapturingDdImageWriter implements DdImageWriter {
        /// Verification result returned by this writer.
        private final boolean verifyResult;

        /// Whether this writer accepts mounted targets.
        private final boolean mountedTargetSupport;

        /// Captured write calls.
        private final ArrayList<DdCall> writeCalls = new ArrayList<>();

        /// Captured verification calls.
        private final ArrayList<DdCall> verifyCalls = new ArrayList<>();

        /// Captured write source.
        private @Nullable Path writeSource;

        /// Captured write target.
        private @Nullable Path writeTarget;

        /// Captured write target display name.
        private @Nullable String writeTargetDisplayName;

        /// Captured write byte count.
        private long writeTotalBytes;

        /// Captured write target removable flag.
        private boolean writeTargetRemovable;

        /// Captured verification source.
        private @Nullable Path verifySource;

        /// Captured verification target.
        private @Nullable Path verifyTarget;

        /// Captured verification target display name.
        private @Nullable String verifyTargetDisplayName;

        /// Captured verification target removable flag.
        private boolean verifyTargetRemovable;

        /// Creates the capturing writer.
        ///
        /// @param verifyResult verification result returned by this writer.
        private CapturingDdImageWriter(boolean verifyResult) {
            this(verifyResult, false);
        }

        /// Creates the capturing writer.
        ///
        /// @param verifyResult verification result returned by this writer.
        /// @param mountedTargetSupport whether this writer accepts mounted targets.
        private CapturingDdImageWriter(boolean verifyResult, boolean mountedTargetSupport) {
            this.verifyResult = verifyResult;
            this.mountedTargetSupport = mountedTargetSupport;
        }

        /// Returns whether this writer accepts mounted targets.
        ///
        /// @param target target block device.
        /// @return configured mounted target support.
        @Override
        public boolean canWriteMountedTarget(BlockDevice target) {
            return mountedTargetSupport;
        }

        /// Captures one block-image write.
        ///
        /// @param source source image path.
        /// @param target target path.
        /// @param targetDisplayName human-readable target display name.
        /// @param totalBytes source size.
        /// @param targetRemovable whether the target was identified as removable.
        /// @param message progress message.
        /// @param reporter progress reporter.
        @Override
        public void write(
                Path source,
                Path target,
                String targetDisplayName,
                long totalBytes,
                boolean targetRemovable,
                String message,
                ProgressReporter reporter) {
            this.writeSource = source;
            this.writeTarget = target;
            this.writeTargetDisplayName = targetDisplayName;
            this.writeTotalBytes = totalBytes;
            this.writeTargetRemovable = targetRemovable;
            this.writeCalls.add(new DdCall(source, target, targetDisplayName, totalBytes, targetRemovable, message));
        }

        /// Captures one block-image verification.
        ///
        /// @param source source image path.
        /// @param target target path.
        /// @param targetDisplayName human-readable target display name.
        /// @param totalBytes source size.
        /// @param targetRemovable whether the target was identified as removable.
        /// @param message progress message.
        /// @param reporter progress reporter.
        /// @return configured verification result.
        @Override
        public boolean verify(
                Path source,
                Path target,
                String targetDisplayName,
                long totalBytes,
                boolean targetRemovable,
                String message,
                ProgressReporter reporter) {
            this.verifySource = source;
            this.verifyTarget = target;
            this.verifyTargetDisplayName = targetDisplayName;
            this.verifyTargetRemovable = targetRemovable;
            this.verifyCalls.add(new DdCall(source, target, targetDisplayName, totalBytes, targetRemovable, message));
            return verifyResult;
        }
    }

    /// Captured dd writer call.
    ///
    /// @param source source image path.
    /// @param target target path.
    /// @param targetDisplayName human-readable target display name.
    /// @param totalBytes source size.
    /// @param targetRemovable whether the target was identified as removable.
    /// @param message progress message.
    @NotNullByDefault
    private record DdCall(
            Path source,
            Path target,
            String targetDisplayName,
            long totalBytes,
            boolean targetRemovable,
            String message) {
    }

    /// Test dd writer that copies bytes inside the JVM.
    @NotNullByDefault
    private static final class CopyingDdImageWriter implements DdImageWriter {
        /// Writes source bytes to the target without truncating the target.
        ///
        /// @param source source image path.
        /// @param target target path.
        /// @param targetDisplayName human-readable target display name.
        /// @param totalBytes source size.
        /// @param targetRemovable whether the target was identified as removable.
        /// @param message progress message.
        /// @param reporter progress reporter.
        /// @throws IOException when files cannot be read or written.
        @Override
        public void write(
                Path source,
                Path target,
                String targetDisplayName,
                long totalBytes,
                boolean targetRemovable,
                String message,
                ProgressReporter reporter) throws IOException {
            try (FileChannel input = FileChannel.open(source, StandardOpenOption.READ);
                 FileChannel output = FileChannel.open(target, StandardOpenOption.WRITE)) {
                long written = 0L;
                while (written < totalBytes) {
                    long transferred = input.transferTo(written, totalBytes - written, output);
                    if (transferred <= 0L) {
                        throw new IOException("No bytes were transferred.");
                    }
                    written += transferred;
                }
            }
        }

        /// Verifies source bytes against the target.
        ///
        /// @param source source image path.
        /// @param target target path.
        /// @param targetDisplayName human-readable target display name.
        /// @param totalBytes source size.
        /// @param targetRemovable whether the target was identified as removable.
        /// @param message progress message.
        /// @param reporter progress reporter.
        /// @return whether target bytes match source bytes.
        /// @throws IOException when files cannot be read.
        @Override
        public boolean verify(
                Path source,
                Path target,
                String targetDisplayName,
                long totalBytes,
                boolean targetRemovable,
                String message,
                ProgressReporter reporter) throws IOException {
            try (FileChannel input = FileChannel.open(source, StandardOpenOption.READ);
                 FileChannel output = FileChannel.open(target, StandardOpenOption.READ)) {
                ByteBuffer inputBuffer = ByteBuffer.allocate(Math.toIntExact(totalBytes));
                ByteBuffer outputBuffer = ByteBuffer.allocate(Math.toIntExact(totalBytes));
                input.read(inputBuffer);
                output.read(outputBuffer);
                inputBuffer.flip();
                outputBuffer.flip();
                return inputBuffer.equals(outputBuffer);
            }
        }
    }

    /// Image catalog service that has no images.
    @NotNullByDefault
    private static final class EmptyImageCatalogService implements ImageCatalogService {
        /// Lists no images.
        ///
        /// @return empty catalog.
        @Override
        public ImageCatalog listImages() {
            return new ImageCatalog(List.of());
        }

        /// Refuses downloads.
        ///
        /// @param image image to download.
        /// @param reporter progress reporter.
        /// @return never returns.
        /// @throws IOException always thrown.
        @Override
        public Path downloadImage(ImageEntry image, ProgressReporter reporter) throws IOException {
            throw new IOException("No images are available.");
        }
    }

    /// Image catalog service returning a fixed materialized path.
    @NotNullByDefault
    private static final class FixedImageCatalogService implements ImageCatalogService {
        /// Materialized image path.
        private final Path path;

        /// Creates the fixed image service.
        ///
        /// @param path materialized image path.
        private FixedImageCatalogService(Path path) {
            this.path = path;
        }

        /// Lists no images.
        ///
        /// @return empty catalog.
        @Override
        public ImageCatalog listImages() {
            return new ImageCatalog(List.of());
        }

        /// Returns the fixed image path.
        ///
        /// @param image image to download.
        /// @param reporter progress reporter.
        /// @return fixed image path.
        @Override
        public Path downloadImage(ImageEntry image, ProgressReporter reporter) {
            return path;
        }
    }
}

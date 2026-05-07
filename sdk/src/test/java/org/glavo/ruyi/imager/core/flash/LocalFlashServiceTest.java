// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.flash;

import org.glavo.ruyi.imager.core.OperationResult;
import org.glavo.ruyi.imager.core.ProgressReporter;
import org.glavo.ruyi.imager.core.StrategySupport;
import org.glavo.ruyi.imager.core.device.BlockDevice;
import org.glavo.ruyi.imager.core.fastboot.FastbootDevice;
import org.glavo.ruyi.imager.core.fastboot.FastbootService;
import org.glavo.ruyi.imager.core.image.ImageCatalog;
import org.glavo.ruyi.imager.core.image.ImageCatalogService;
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
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertEquals(4L, writer.writeTotalBytes);
        assertEquals(image, writer.verifySource);
        assertEquals(target, writer.verifyTarget);
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
                new FlashRequest(null, image, target(target, 8, false, true, false), false),
                NO_PROGRESS);

        assertFalse(result.success());
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
        return new BlockDevice("test", "Test Target", path, sizeBytes, true, system, mounted, readOnly, "Test", "file");
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

    /// Fastboot service that captures flash requests.
    @NotNullByDefault
    private static final class CapturingFastbootService implements FastbootService {
        /// Captured strategy.
        private @Nullable String strategy;

        /// Captured partition map.
        private @Unmodifiable Map<String, Path> partitions = Map.of();

        /// Captured device.
        private @Nullable FastbootDevice device;

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
            return OperationResult.success("Fastboot complete.");
        }
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
    }

    /// Test dd image writer that captures write and verification calls.
    @NotNullByDefault
    private static final class CapturingDdImageWriter implements DdImageWriter {
        /// Verification result returned by this writer.
        private final boolean verifyResult;

        /// Captured write source.
        private @Nullable Path writeSource;

        /// Captured write target.
        private @Nullable Path writeTarget;

        /// Captured write byte count.
        private long writeTotalBytes;

        /// Captured verification source.
        private @Nullable Path verifySource;

        /// Captured verification target.
        private @Nullable Path verifyTarget;

        /// Creates the capturing writer.
        ///
        /// @param verifyResult verification result returned by this writer.
        private CapturingDdImageWriter(boolean verifyResult) {
            this.verifyResult = verifyResult;
        }

        /// Captures one block-image write.
        ///
        /// @param source source image path.
        /// @param target target path.
        /// @param totalBytes source size.
        /// @param reporter progress reporter.
        @Override
        public void write(
                Path source,
                Path target,
                long totalBytes,
                String message,
                ProgressReporter reporter) {
            this.writeSource = source;
            this.writeTarget = target;
            this.writeTotalBytes = totalBytes;
        }

        /// Captures one block-image verification.
        ///
        /// @param source source image path.
        /// @param target target path.
        /// @param totalBytes source size.
        /// @param reporter progress reporter.
        /// @return configured verification result.
        @Override
        public boolean verify(
                Path source,
                Path target,
                long totalBytes,
                String message,
                ProgressReporter reporter) {
            this.verifySource = source;
            this.verifyTarget = target;
            return verifyResult;
        }
    }

    /// Test dd writer that copies bytes inside the JVM.
    @NotNullByDefault
    private static final class CopyingDdImageWriter implements DdImageWriter {
        /// Writes source bytes to the target without truncating the target.
        ///
        /// @param source source image path.
        /// @param target target path.
        /// @param totalBytes source size.
        /// @param message progress message.
        /// @param reporter progress reporter.
        /// @throws IOException when files cannot be read or written.
        @Override
        public void write(
                Path source,
                Path target,
                long totalBytes,
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
        /// @param totalBytes source size.
        /// @param message progress message.
        /// @param reporter progress reporter.
        /// @return whether target bytes match source bytes.
        /// @throws IOException when files cannot be read.
        @Override
        public boolean verify(
                Path source,
                Path target,
                long totalBytes,
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

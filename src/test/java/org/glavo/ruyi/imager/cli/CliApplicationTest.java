// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.glavo.ruyi.imager.core.AppDirectories;
import org.glavo.ruyi.imager.core.AppServices;
import org.glavo.ruyi.imager.core.OperationResult;
import org.glavo.ruyi.imager.core.ProgressReporter;
import org.glavo.ruyi.imager.core.StrategySupport;
import org.glavo.ruyi.imager.core.device.BlockDevice;
import org.glavo.ruyi.imager.core.device.BlockDeviceService;
import org.glavo.ruyi.imager.core.fastboot.FastbootDevice;
import org.glavo.ruyi.imager.core.fastboot.FastbootService;
import org.glavo.ruyi.imager.core.flash.LocalFlashService;
import org.glavo.ruyi.imager.core.image.ImageCatalog;
import org.glavo.ruyi.imager.core.image.ImageCatalogService;
import org.glavo.ruyi.imager.core.image.ImageEntry;
import org.glavo.ruyi.imager.i18n.Messages;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Integration tests for the CLI command tree.
@NotNullByDefault
public final class CliApplicationTest {
    /// JSON mapper used to inspect CLI output.
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /// Verifies stable JSON output for candidate devices.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when fixture files cannot be written or JSON cannot be parsed.
    @Test
    public void deviceListJsonUsesStringPaths(@TempDir Path temporaryDirectory) throws Exception {
        Path target = temporaryDirectory.resolve("target.raw");
        Files.write(target, new byte[32]);
        BlockDevice device = blockDevice("test-device", target, 32L, false, true, true);

        CliResult result = runCli(services(temporaryDirectory, List.of(device)), "device", "list", "--json");

        assertEquals(0, result.exitCode(), result.stderr());
        JsonNode root = MAPPER.readTree(result.stdout());
        JsonNode deviceNode = root.path("devices").path(0);
        assertEquals("device-list", root.path("type").asText());
        assertEquals("test-device", deviceNode.path("id").asText());
        assertEquals(target.toString(), deviceNode.path("path").asText());
        assertTrue(deviceNode.path("removable").asBoolean());
        assertTrue(deviceNode.path("mounted").asBoolean());
        assertTrue(deviceNode.path("readOnly").asBoolean());
    }

    /// Verifies stable JSON output for fastboot devices.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when JSON cannot be parsed.
    @Test
    public void deviceListFastbootJsonUsesSerials(@TempDir Path temporaryDirectory) throws Exception {
        FastbootDevice device = new FastbootDevice("test-fastboot", "test-fastboot", "fastboot");

        CliResult result = runCli(
                services(
                        temporaryDirectory,
                        new FixedBlockDeviceService(List.of()),
                        new EmptyImageCatalogService(),
                        new CapturingFastbootService(device)),
                "device",
                "list",
                "--fastboot",
                "--json");

        assertEquals(0, result.exitCode(), result.stderr());
        JsonNode root = MAPPER.readTree(result.stdout());
        JsonNode deviceNode = root.path("devices").path(0);
        assertEquals("fastboot-device-list", root.path("type").asText());
        assertEquals("test-fastboot", deviceNode.path("id").asText());
        assertEquals("test-fastboot", deviceNode.path("serial").asText());
        assertEquals("fastboot", deviceNode.path("state").asText());
    }

    /// Verifies that flashing refuses to continue without explicit confirmation.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when fixture files cannot be written or JSON cannot be parsed.
    @Test
    public void flashRefusesWithoutYesBeforeResolvingDevice(@TempDir Path temporaryDirectory) throws Exception {
        Path image = temporaryDirectory.resolve("image.raw");
        Files.write(image, new byte[]{1, 2, 3, 4});

        CliResult result = runCli(
                services(temporaryDirectory, new ThrowingBlockDeviceService()),
                "flash",
                "--local-image",
                image.toString(),
                "--device",
                "test-device",
                "--json");

        assertNotEquals(0, result.exitCode());
        JsonNode root = MAPPER.readTree(result.stdout());
        assertEquals("error", root.path("type").asText());
        assertEquals("Refusing to write without --yes.", root.path("message").asText());
    }

    /// Verifies that CLI JSON error messages use the configured Simplified Chinese locale.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when fixture files cannot be written or JSON cannot be parsed.
    @Test
    public void flashErrorUsesConfiguredChineseLocale(@TempDir Path temporaryDirectory) throws Exception {
        Path image = temporaryDirectory.resolve("image.raw");
        Files.write(image, new byte[]{1, 2, 3, 4});

        CliResult result = runCliWithLocale(
                "zh-CN",
                services(temporaryDirectory, new ThrowingBlockDeviceService()),
                "flash",
                "--local-image",
                image.toString(),
                "--device",
                "test-device",
                "--json");

        assertNotEquals(0, result.exitCode());
        JsonNode root = MAPPER.readTree(result.stdout());
        assertEquals("error", root.path("type").asText());
        assertEquals(messageForLocale("zh-CN", "cli.error.refusingWithoutYes"), root.path("message").asText());
    }

    /// Flashes a local image into a simulated target through the public CLI.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when fixture files cannot be written, flashed, or read.
    @Test
    public void flashLocalImageWritesSimulatedTarget(@TempDir Path temporaryDirectory) throws Exception {
        byte[] imageBytes = imageBytes(128);
        Path image = temporaryDirectory.resolve("image.raw");
        Path target = temporaryDirectory.resolve("target.raw");
        Files.write(image, imageBytes);
        Files.write(target, new byte[256]);
        BlockDevice device = blockDevice("test-device", target, 256L, false, false, false);

        CliResult result = runCli(
                services(temporaryDirectory, List.of(device)),
                "flash",
                "--local-image",
                image.toString(),
                "--device",
                "test-device",
                "--yes",
                "--skip-verify",
                "--json");

        assertEquals(0, result.exitCode(), result.stderr());
        String[] lines = result.stdout().strip().split("\\R");
        JsonNode finalEvent = MAPPER.readTree(lines[lines.length - 1]);
        assertEquals("complete", finalEvent.path("type").asText());
        assertTrue(finalEvent.path("success").asBoolean());
        assertArrayEquals(imageBytes, Arrays.copyOf(Files.readAllBytes(target), imageBytes.length));
    }

    /// Flashes a catalog fastboot image through the public CLI.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when fixture files cannot be written or JSON cannot be parsed.
    @Test
    public void flashFastbootImageUsesFastbootSerial(@TempDir Path temporaryDirectory) throws Exception {
        Path artifactDirectory = temporaryDirectory.resolve("artifact");
        Files.createDirectories(artifactDirectory);
        Path boot = artifactDirectory.resolve("boot.img");
        Files.write(boot, new byte[]{1, 2, 3});

        ImageEntry image = imageEntry("fastboot-v1", Map.of("boot", "boot.img"));
        CapturingFastbootService fastboot = new CapturingFastbootService(
                new FastbootDevice("test-fastboot", "test-fastboot", "fastboot"));

        CliResult result = runCli(
                services(
                        temporaryDirectory,
                        new FixedBlockDeviceService(List.of()),
                        new FixedImageCatalogService(image, artifactDirectory),
                        fastboot),
                "flash",
                "--atom",
                image.atom(),
                "--device",
                "test-fastboot",
                "--yes",
                "--json");

        assertEquals(0, result.exitCode(), result.stderr());
        String[] lines = result.stdout().strip().split("\\R");
        JsonNode finalEvent = MAPPER.readTree(lines[lines.length - 1]);
        assertEquals("complete", finalEvent.path("type").asText());
        assertTrue(finalEvent.path("success").asBoolean());
        assertEquals("fastboot-v1", fastboot.strategy);
        assertEquals(boot, fastboot.partitions.get("boot"));
    }

    /// Creates shared test services.
    ///
    /// @param baseDirectory base directory for app state.
    /// @param devices fixed devices.
    /// @return app services.
    private static AppServices services(Path baseDirectory, List<BlockDevice> devices) {
        return services(baseDirectory, new FixedBlockDeviceService(devices));
    }

    /// Creates shared test services.
    ///
    /// @param baseDirectory base directory for app state.
    /// @param devices device service.
    /// @return app services.
    private static AppServices services(Path baseDirectory, BlockDeviceService devices) {
        ImageCatalogService images = new EmptyImageCatalogService();
        FastbootService fastboot = new EmptyFastbootService();
        return services(baseDirectory, devices, images, fastboot);
    }

    /// Creates shared test services.
    ///
    /// @param baseDirectory base directory for app state.
    /// @param devices device service.
    /// @param images image catalog service.
    /// @param fastboot fastboot service.
    /// @return app services.
    private static AppServices services(
            Path baseDirectory,
            BlockDeviceService devices,
            ImageCatalogService images,
            FastbootService fastboot) {
        return new AppServices(
                new AppDirectories(baseDirectory.resolve("config"), baseDirectory.resolve("cache")),
                _ -> OperationResult.success("Repositories updated."),
                images,
                devices,
                fastboot,
                new LocalFlashService(images, fastboot));
    }

    /// Creates a test block device.
    ///
    /// @param id device id.
    /// @param path target path.
    /// @param sizeBytes target size.
    /// @param system whether the target is a system disk.
    /// @param mounted whether the target has mounted volumes.
    /// @param readOnly whether the target is read-only.
    /// @return test device.
    private static BlockDevice blockDevice(
            String id,
            Path path,
            long sizeBytes,
            boolean system,
            boolean mounted,
            boolean readOnly) {
        return new BlockDevice(id, "Test Device", path, sizeBytes, true, system, mounted, readOnly, "Test", "file");
    }

    /// Runs the CLI and captures standard streams.
    ///
    /// @param services app services.
    /// @param args command arguments.
    /// @return captured result.
    private static CliResult runCli(AppServices services, String @Unmodifiable ... args) {
        return runCliWithLocale("en", services, args);
    }

    /// Runs the CLI with a configured locale and captures standard streams.
    ///
    /// @param locale locale tag.
    /// @param services app services.
    /// @param args command arguments.
    /// @return captured result.
    private static CliResult runCliWithLocale(String locale, AppServices services, String @Unmodifiable ... args) {
        Locale originalLocale = Messages.locale();
        Messages.setLocale(locale);
        try {
            return runCliWithCurrentLocale(services, args);
        } finally {
            Messages.setLocale(originalLocale);
        }
    }

    /// Runs the CLI using the current locale configuration and captures standard streams.
    ///
    /// @param services app services.
    /// @param args command arguments.
    /// @return captured result.
    private static CliResult runCliWithCurrentLocale(AppServices services, String @Unmodifiable ... args) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        try (PrintStream capturedOut = new PrintStream(stdout, true, StandardCharsets.UTF_8);
             PrintStream capturedErr = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            System.setOut(capturedOut);
            System.setErr(capturedErr);
            int exitCode = CliApplication.run(services, args);
            capturedOut.flush();
            capturedErr.flush();
            return new CliResult(
                    exitCode,
                    stdout.toString(StandardCharsets.UTF_8),
                    stderr.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    /// Reads one message with a temporary locale override.
    ///
    /// @param locale locale tag.
    /// @param key message key.
    /// @return localized message.
    private static String messageForLocale(String locale, String key) {
        Locale originalLocale = Messages.locale();
        Messages.setLocale(locale);
        try {
            return Messages.get(key);
        } finally {
            Messages.setLocale(originalLocale);
        }
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

    /// Captured CLI result.
    ///
    /// @param exitCode CLI exit code.
    /// @param stdout captured standard output.
    /// @param stderr captured standard error.
    @NotNullByDefault
    private record CliResult(int exitCode, String stdout, String stderr) {
    }

    /// Device service returning a fixed list.
    @NotNullByDefault
    private record FixedBlockDeviceService(@Unmodifiable List<BlockDevice> devices) implements BlockDeviceService {
        /// Lists fixed devices.
        ///
        /// @return immutable device list.
        @Override
        public @Unmodifiable List<BlockDevice> listDevices() {
            return List.copyOf(devices);
        }
    }

    /// Device service that fails if a command tries to enumerate devices.
    @NotNullByDefault
    private static final class ThrowingBlockDeviceService implements BlockDeviceService {
        /// Fails device enumeration.
        ///
        /// @return never returns.
        @Override
        public @Unmodifiable List<BlockDevice> listDevices() {
            throw new AssertionError("Device enumeration should not be reached.");
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

        /// Refuses image downloads.
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

    /// Image catalog service returning one fixed image.
    @NotNullByDefault
    private record FixedImageCatalogService(ImageEntry image, Path artifact) implements ImageCatalogService {
        /// Lists the fixed image.
        ///
        /// @return catalog containing the fixed image.
        @Override
        public ImageCatalog listImages() {
            return new ImageCatalog(List.of(image));
        }

        /// Returns the fixed materialized artifact path.
        ///
        /// @param image image to download.
        /// @param reporter progress reporter.
        /// @return fixed artifact path.
        @Override
        public Path downloadImage(ImageEntry image, ProgressReporter reporter) {
            return artifact;
        }
    }

    /// Fastboot service that has no devices.
    @NotNullByDefault
    private static final class EmptyFastbootService implements FastbootService {
        /// Lists no fastboot devices.
        ///
        /// @return empty fastboot device list.
        @Override
        public @Unmodifiable List<FastbootDevice> listDevices() {
            return List.of();
        }

        /// Refuses fastboot flashing.
        ///
        /// @param strategy Ruyi provision strategy.
        /// @param partitions materialized partition images keyed by target partition name.
        /// @param device target fastboot device.
        /// @param reporter progress reporter.
        /// @return failure result.
        @Override
        public OperationResult flash(
                String strategy,
                @Unmodifiable Map<String, Path> partitions,
                FastbootDevice device,
                ProgressReporter reporter) {
            return OperationResult.failure("No fastboot devices are available.");
        }
    }

    /// Fastboot service that captures flash requests.
    @NotNullByDefault
    private static final class CapturingFastbootService implements FastbootService {
        /// Fixed fastboot device.
        private final FastbootDevice device;

        /// Captured strategy.
        private String strategy = "";

        /// Captured partition map.
        private @Unmodifiable Map<String, Path> partitions = Map.of();

        /// Creates the capturing service.
        ///
        /// @param device fixed fastboot device.
        private CapturingFastbootService(FastbootDevice device) {
            this.device = device;
        }

        /// Lists the fixed fastboot device.
        ///
        /// @return fixed fastboot device list.
        @Override
        public @Unmodifiable List<FastbootDevice> listDevices() {
            return List.of(device);
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
            return OperationResult.success("Fastboot complete.");
        }
    }
}

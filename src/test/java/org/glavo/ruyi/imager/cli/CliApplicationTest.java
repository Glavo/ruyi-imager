// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.glavo.ruyi.imager.core.AppDirectories;
import org.glavo.ruyi.imager.core.AppServices;
import org.glavo.ruyi.imager.core.OperationResult;
import org.glavo.ruyi.imager.core.ProgressReporter;
import org.glavo.ruyi.imager.core.device.BlockDevice;
import org.glavo.ruyi.imager.core.device.BlockDeviceService;
import org.glavo.ruyi.imager.core.flash.LocalFlashService;
import org.glavo.ruyi.imager.core.image.ImageCatalog;
import org.glavo.ruyi.imager.core.image.ImageCatalogService;
import org.glavo.ruyi.imager.core.image.ImageEntry;
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
        return new AppServices(
                new AppDirectories(baseDirectory.resolve("config"), baseDirectory.resolve("cache")),
                _ -> OperationResult.success("Repositories updated."),
                images,
                devices,
                new LocalFlashService(images));
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
}

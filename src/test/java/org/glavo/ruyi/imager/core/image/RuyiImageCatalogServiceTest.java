// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.image;

import org.glavo.ruyi.imager.core.AppDirectories;
import org.glavo.ruyi.imager.core.StrategySupport;
import org.glavo.ruyi.imager.core.repo.RuyiRepositoryStore;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for Ruyi image catalog parsing.
@NotNullByDefault
public final class RuyiImageCatalogServiceTest {
    /// Verifies that provisionable manifests are listed as image entries.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when test fixture files cannot be created or read.
    @Test
    public void listsProvisionableImagesFromConfiguredLocalRepo(@TempDir Path temporaryDirectory) throws Exception {
        Path configDirectory = temporaryDirectory.resolve("config");
        Path cacheDirectory = temporaryDirectory.resolve("cache");
        Path repoDirectory = temporaryDirectory.resolve("repo");
        Files.createDirectories(configDirectory);
        writeConfig(configDirectory, repoDirectory);
        writeRepository(repoDirectory);

        RuyiRepositoryStore repositoryStore = new RuyiRepositoryStore(
                new AppDirectories(configDirectory, cacheDirectory));
        RuyiImageCatalogService service = new RuyiImageCatalogService(
                new AppDirectories(configDirectory, cacheDirectory),
                repositoryStore);

        ImageCatalog catalog = service.listImages();

        assertEquals(1, catalog.images().size());
        ImageEntry image = catalog.images().getFirst();
        assertEquals("ruyisdk", image.repoId());
        assertEquals("board-image", image.category());
        assertEquals("revyos-milkv-meles", image.name());
        assertEquals("1.2.3", image.version());
        assertEquals("revyos-meles", image.slug());
        assertEquals("board-image/revyos-milkv-meles(1.2.3)", image.atom());
        assertEquals("RevyOS image for Milk-V Meles", image.displayName());
        assertEquals("Milk-V", image.manufacturer());
        assertEquals("milkv-meles", image.board());
        assertEquals("generic", image.variant());
        assertEquals("dd-v1", image.strategy());
        assertEquals(StrategySupport.SUPPORTED, image.support());
        assertEquals("image.raw", image.partitionMap().get("disk"));

        RuyiDistfile distfile = image.distfiles().getFirst();
        assertEquals("image.raw", distfile.name());
        assertEquals(1024L, distfile.sizeBytes());
        assertEquals("0123456789abcdef", distfile.checksums().get("sha256"));
        assertEquals(List.of(URI.create("https://dist.example/dist/image.raw")), distfile.sourceUris());
        assertEquals(List.of("images"), distfile.prefixesToUnpack());
    }

    /// Verifies fetch_restriction declarations are rendered from repository messages.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when test fixture files cannot be created or read.
    @Test
    public void parsesFetchRestrictionInstructions(@TempDir Path temporaryDirectory) throws Exception {
        Path configDirectory = temporaryDirectory.resolve("config");
        Path cacheDirectory = temporaryDirectory.resolve("cache");
        Path repoDirectory = temporaryDirectory.resolve("repo");
        Files.createDirectories(configDirectory);
        writeConfig(configDirectory, repoDirectory);
        writeRepositoryConfig(repoDirectory);
        writeFetchRestrictedImageManifest(repoDirectory);

        RuyiImageCatalogService service = new RuyiImageCatalogService(
                new AppDirectories(configDirectory, cacheDirectory),
                new RuyiRepositoryStore(new AppDirectories(configDirectory, cacheDirectory)));

        RuyiDistfile distfile = service.listImages().images().getFirst().distfiles().getFirst();

        assertTrue(distfile.fetchRestricted());
        @Nullable RuyiFetchRestriction fetchRestriction = distfile.fetchRestriction();
        assertNotNull(fetchRestriction);
        String instructions = fetchRestriction.render(Path.of("downloads").resolve("manual.raw"), Locale.ENGLISH);
        assertTrue(instructions.contains("manual.raw"), instructions);
        assertTrue(instructions.contains("manual"), instructions);
    }

    /// Verifies Ruyi atom matching and latest stable version selection.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when test fixture files cannot be created or read.
    @Test
    public void resolvesRuyiAtomsAgainstLatestStableVersion(@TempDir Path temporaryDirectory) throws Exception {
        Path configDirectory = temporaryDirectory.resolve("config");
        Path cacheDirectory = temporaryDirectory.resolve("cache");
        Path repoDirectory = temporaryDirectory.resolve("repo");
        Files.createDirectories(configDirectory);
        writeConfig(configDirectory, repoDirectory);
        writeRepositoryConfig(repoDirectory);
        writeImageManifest(repoDirectory, "1.0.0", "Older RevyOS image", "revyos-meles-old", "old.raw");
        writeImageManifest(repoDirectory, "1.1.0", "Stable RevyOS image", "revyos-meles-stable", "stable.raw");
        writeImageManifest(repoDirectory, "1.2.0-rc.1", "Release candidate RevyOS image", "revyos-meles-rc", "rc.raw");

        RuyiImageCatalogService service = new RuyiImageCatalogService(
                new AppDirectories(configDirectory, cacheDirectory),
                new RuyiRepositoryStore(new AppDirectories(configDirectory, cacheDirectory)));

        assertEquals("1.1.0", service.findImage("board-image/revyos-milkv-meles").version());
        assertEquals("1.1.0", service.findImage("revyos-milkv-meles").version());
        assertEquals("1.1.0", service.findImage("name:board-image/revyos-milkv-meles").version());
        assertEquals("1.0.0", service.findImage("board-image/revyos-milkv-meles(1.0.0)").version());
        assertEquals("1.0.0", service.findImage("board-image/revyos-milkv-meles(>=1.0.0,<1.1.0)").version());
        assertEquals("1.1.0", service.findImage("slug:revyos-meles-stable").version());
        assertNull(service.findImage("slug:missing"));
    }

    /// Verifies known fastboot provision strategies are exposed as supported.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when test fixture files cannot be created or read.
    @Test
    public void classifiesFastbootImagesAsSupported(@TempDir Path temporaryDirectory) throws Exception {
        Path configDirectory = temporaryDirectory.resolve("config");
        Path cacheDirectory = temporaryDirectory.resolve("cache");
        Path repoDirectory = temporaryDirectory.resolve("repo");
        Files.createDirectories(configDirectory);
        writeConfig(configDirectory, repoDirectory);
        writeRepositoryConfig(repoDirectory);
        writeImageManifest(
                repoDirectory,
                "1.2.3",
                "Fastboot image for Milk-V Meles",
                "fastboot-meles",
                "image.raw",
                "fastboot-v1");

        RuyiImageCatalogService service = new RuyiImageCatalogService(
                new AppDirectories(configDirectory, cacheDirectory),
                new RuyiRepositoryStore(new AppDirectories(configDirectory, cacheDirectory)));

        ImageEntry image = service.listImages().images().getFirst();

        assertEquals("fastboot-v1", image.strategy());
        assertEquals(StrategySupport.SUPPORTED, image.support());
    }

    /// Verifies board-image manufacturer and variant are derived from the device id.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when test fixture files cannot be created or read.
    @Test
    public void derivesBoardManufacturerAndVariantFromDeviceId(@TempDir Path temporaryDirectory) throws Exception {
        Path configDirectory = temporaryDirectory.resolve("config");
        Path cacheDirectory = temporaryDirectory.resolve("cache");
        Path repoDirectory = temporaryDirectory.resolve("repo");
        Files.createDirectories(configDirectory);
        writeConfig(configDirectory, repoDirectory);
        writeRepositoryConfig(repoDirectory);
        writeImageManifest(
                repoDirectory,
                "uboot-revyos-milkv-meles-4g",
                "1.2.3",
                "U-Boot image for Milk-V Meles",
                "uboot-meles-4g",
                "uboot.bin",
                "fastboot-v1",
                "PLCT");

        RuyiImageCatalogService service = new RuyiImageCatalogService(
                new AppDirectories(configDirectory, cacheDirectory),
                new RuyiRepositoryStore(new AppDirectories(configDirectory, cacheDirectory)));

        @Nullable ImageEntry image = service.findImage("uboot-revyos-milkv-meles-4g");

        assertNotNull(image);
        assertEquals("Milk-V", image.manufacturer());
        assertEquals("milkv-meles", image.board());
        assertEquals("4g", image.variant());
    }

    /// Verifies board-image board names use device entities before suffix heuristics.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when test fixture files cannot be created or read.
    @Test
    public void derivesBoardFromDeviceEntityBeforePackageSuffix(@TempDir Path temporaryDirectory) throws Exception {
        Path configDirectory = temporaryDirectory.resolve("config");
        Path cacheDirectory = temporaryDirectory.resolve("cache");
        Path repoDirectory = temporaryDirectory.resolve("repo");
        Files.createDirectories(configDirectory);
        writeConfig(configDirectory, repoDirectory);
        writeRepositoryConfig(repoDirectory);
        writeDeviceEntity(repoDirectory, "sipeed-lpi4a");
        writeImageManifest(
                repoDirectory,
                "revyos-sipeed-lpi4a-headless",
                "1.2.3",
                "Headless RevyOS image for Sipeed LicheePi 4A",
                "revyos-lpi4a-headless",
                "image.raw",
                "dd-v1",
                "PLCT");

        RuyiImageCatalogService service = new RuyiImageCatalogService(
                new AppDirectories(configDirectory, cacheDirectory),
                new RuyiRepositoryStore(new AppDirectories(configDirectory, cacheDirectory)));

        @Nullable ImageEntry image = service.findImage("revyos-sipeed-lpi4a-headless");

        assertNotNull(image);
        assertEquals("Sipeed", image.manufacturer());
        assertEquals("sipeed-lpi4a", image.board());
        assertEquals("headless", image.variant());
    }


    /// Verifies lightweight distfile cache status inspection.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when test fixture files cannot be created or read.
    @Test
    public void reportsImageCacheStatus(@TempDir Path temporaryDirectory) throws Exception {
        Path configDirectory = temporaryDirectory.resolve("config");
        Path cacheDirectory = temporaryDirectory.resolve("cache");
        Path repoDirectory = temporaryDirectory.resolve("repo");
        Files.createDirectories(configDirectory);
        writeConfig(configDirectory, repoDirectory);
        writeRepository(repoDirectory);

        RuyiImageCatalogService service = new RuyiImageCatalogService(
                new AppDirectories(configDirectory, cacheDirectory),
                new RuyiRepositoryStore(new AppDirectories(configDirectory, cacheDirectory)));
        ImageEntry image = service.listImages().images().getFirst();

        ImageCacheStatus emptyStatus = service.cacheStatus(image);
        assertEquals(ImageCacheStatus.State.EMPTY, emptyStatus.state());
        assertEquals(0, emptyStatus.cachedDistfiles());
        assertEquals(1, emptyStatus.totalDistfiles());
        assertEquals(0L, emptyStatus.cachedBytes());
        assertEquals(1024L, emptyStatus.totalBytes());

        Path downloadDirectory = cacheDirectory
                .resolve("downloads")
                .resolve("ruyisdk")
                .resolve("board-image")
                .resolve("revyos-milkv-meles")
                .resolve("1.2.3");
        Files.createDirectories(downloadDirectory);
        Files.write(downloadDirectory.resolve("image.raw.part"), new byte[512]);

        ImageCacheStatus partialStatus = service.cacheStatus(image);
        assertEquals(ImageCacheStatus.State.PARTIAL, partialStatus.state());
        assertEquals(0, partialStatus.cachedDistfiles());
        assertEquals(512L, partialStatus.cachedBytes());

        Files.write(downloadDirectory.resolve("image.raw"), new byte[1024]);

        ImageCacheStatus completeStatus = service.cacheStatus(image);
        assertEquals(ImageCacheStatus.State.COMPLETE, completeStatus.state());
        assertEquals(1, completeStatus.cachedDistfiles());
        assertEquals(1024L, completeStatus.cachedBytes());
    }

    /// Writes the application config fixture.
    ///
    /// @param configDirectory config directory.
    /// @param repoDirectory local repository directory.
    /// @throws Exception when the config cannot be written.
    private static void writeConfig(Path configDirectory, Path repoDirectory) throws Exception {
        String repoPath = repoDirectory.toString().replace('\\', '/');
        Files.writeString(configDirectory.resolve("config.toml"), """
                [repo]
                local = "%s"
                """.formatted(repoPath));
    }

    /// Writes a minimal Ruyi repository fixture.
    ///
    /// @param repoDirectory repository directory.
    /// @throws Exception when fixture files cannot be written.
    private static void writeRepository(Path repoDirectory) throws Exception {
        writeRepositoryConfig(repoDirectory);
        writeImageManifest(repoDirectory, "1.2.3", "RevyOS image for Milk-V Meles", "revyos-meles", "image.raw");
    }

    /// Writes repository config.
    ///
    /// @param repoDirectory repository directory.
    /// @throws Exception when fixture files cannot be written.
    private static void writeRepositoryConfig(Path repoDirectory) throws Exception {
        Files.createDirectories(repoDirectory.resolve("packages").resolve("board-image").resolve("revyos-milkv-meles"));
        Files.writeString(repoDirectory.resolve("config.toml"), """
                ruyi-repo = "v1"

                [[mirrors]]
                id = "ruyi-dist"
                urls = ["https://dist.example/dist/"]
                """);
        Files.writeString(repoDirectory.resolve("messages.toml"), """
                ruyi-repo-messages = "v1"

                ["manual/image"]
                en_US = "Fetch this image manually to {{ dest_path }}."
                """);
    }

    /// Writes one device entity fixture.
    ///
    /// @param repoDirectory repository directory.
    /// @param deviceId device id.
    /// @throws Exception when fixture files cannot be written.
    private static void writeDeviceEntity(Path repoDirectory, String deviceId) throws Exception {
        Path deviceDirectory = repoDirectory.resolve("entities").resolve("device");
        Files.createDirectories(deviceDirectory);
        Files.writeString(
                deviceDirectory.resolve(deviceId + ".toml"),
                """
                        ruyi-entity = "v0"

                        [device]
                        id = "%s"
                        display_name = "%s"
                        """.formatted(deviceId, deviceId));
    }

    /// Writes a fetch-restricted image manifest fixture.
    ///
    /// @param repoDirectory repository directory.
    /// @throws Exception when fixture files cannot be written.
    private static void writeFetchRestrictedImageManifest(Path repoDirectory) throws Exception {
        Path packageDirectory = repoDirectory.resolve("packages").resolve("board-image").resolve("manual-milkv-meles");
        Files.createDirectories(packageDirectory);
        Files.writeString(
                packageDirectory.resolve("1.2.3.toml"),
                """
                        format = "v1"
                        kind = ["blob", "provisionable"]

                        [metadata]
                        desc = "Manual image for Milk-V Meles"
                        slug = "manual-meles"
                        vendor = { name = "PLCT", eula = "" }

                        [[distfiles]]
                        name = "manual.raw"
                        size = 1024
                        restrict = ["fetch"]

                        [distfiles.fetch_restriction]
                        msgid = "manual/image"

                        [distfiles.checksums]
                        sha256 = "0123456789abcdef"

                        [blob]
                        distfiles = ["manual.raw"]

                        [provisionable]
                        strategy = "dd-v1"

                        [provisionable.partition_map]
                        disk = "manual.raw"
                        """);
    }

    /// Writes one image manifest fixture.
    ///
    /// @param repoDirectory repository directory.
    /// @param version package version.
    /// @param description package description.
    /// @param slug package slug.
    /// @param distfileName distfile name.
    /// @throws Exception when fixture files cannot be written.
    private static void writeImageManifest(
            Path repoDirectory,
            String version,
            String description,
            String slug,
            String distfileName) throws Exception {
        writeImageManifest(repoDirectory, version, description, slug, distfileName, "dd-v1");
    }

    /// Writes one image manifest fixture.
    ///
    /// @param repoDirectory repository directory.
    /// @param version package version.
    /// @param description package description.
    /// @param slug package slug.
    /// @param distfileName distfile name.
    /// @param strategy provision strategy.
    /// @throws Exception when fixture files cannot be written.
    private static void writeImageManifest(
            Path repoDirectory,
            String version,
            String description,
            String slug,
            String distfileName,
            String strategy) throws Exception {
        writeImageManifest(
                repoDirectory,
                "revyos-milkv-meles",
                version,
                description,
                slug,
                distfileName,
                strategy,
                "FreeBSD");
    }

    /// Writes one image manifest fixture.
    ///
    /// @param repoDirectory repository directory.
    /// @param packageName package name.
    /// @param version package version.
    /// @param description package description.
    /// @param slug package slug.
    /// @param distfileName distfile name.
    /// @param strategy provision strategy.
    /// @param packageVendor package vendor name.
    /// @throws Exception when fixture files cannot be written.
    private static void writeImageManifest(
            Path repoDirectory,
            String packageName,
            String version,
            String description,
            String slug,
            String distfileName,
            String strategy,
            String packageVendor) throws Exception {
        Path packageDirectory = repoDirectory.resolve("packages").resolve("board-image").resolve(packageName);
        Files.createDirectories(packageDirectory);
        Files.writeString(
                packageDirectory.resolve(version + ".toml"),
                """
                        format = "v1"
                        kind = ["blob", "provisionable"]

                        [metadata]
                        desc = "%s"
                        slug = "%s"
                        vendor = { name = "%s", eula = "" }

                        [[distfiles]]
                        name = "%s"
                        size = 1024
                        prefixes_to_unpack = ["images"]

                        [distfiles.checksums]
                        sha256 = "0123456789abcdef"

                        [blob]
                        distfiles = ["%s"]

                        [provisionable]
                        strategy = "%s"

                        [provisionable.partition_map]
                        disk = "%s"
                        """.formatted(
                        description,
                        slug,
                        packageVendor,
                        distfileName,
                        distfileName,
                        strategy,
                        distfileName));
    }
}

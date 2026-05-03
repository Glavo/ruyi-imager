// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.image;

import org.glavo.ruyi.imager.core.AppDirectories;
import org.glavo.ruyi.imager.core.StrategySupport;
import org.glavo.ruyi.imager.core.repo.RuyiRepositoryStore;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
        assertEquals("Ruyi", image.manufacturer());
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
        Files.writeString(
                repoDirectory.resolve("packages").resolve("board-image").resolve("revyos-milkv-meles").resolve(version + ".toml"),
                """
                        format = "v1"
                        kind = ["blob", "provisionable"]

                        [metadata]
                        desc = "%s"
                        slug = "%s"
                        vendor = { name = "Ruyi", eula = "" }

                        [[distfiles]]
                        name = "%s"
                        size = 1024

                        [distfiles.checksums]
                        sha256 = "0123456789abcdef"

                        [blob]
                        distfiles = ["%s"]

                        [provisionable]
                        strategy = "dd-v1"

                        [provisionable.partition_map]
                        disk = "%s"
                        """.formatted(description, slug, distfileName, distfileName, distfileName));
    }
}

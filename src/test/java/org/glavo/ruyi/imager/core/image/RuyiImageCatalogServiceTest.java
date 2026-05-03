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
        assertEquals("board-image/revyos-milkv-meles(1.2.3)", image.atom());
        assertEquals("RevyOS image for Milk-V Meles", image.displayName());
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
        Files.createDirectories(repoDirectory.resolve("packages").resolve("board-image").resolve("revyos-milkv-meles"));
        Files.writeString(repoDirectory.resolve("config.toml"), """
                ruyi-repo = "v1"

                [[mirrors]]
                id = "ruyi-dist"
                urls = ["https://dist.example/dist/"]
                """);
        Files.writeString(
                repoDirectory.resolve("packages").resolve("board-image").resolve("revyos-milkv-meles").resolve("1.2.3.toml"),
                """
                        format = "v1"
                        kind = ["blob", "provisionable"]

                        [metadata]
                        desc = "RevyOS image for Milk-V Meles"
                        vendor = { name = "Ruyi", eula = "" }

                        [[distfiles]]
                        name = "image.raw"
                        size = 1024

                        [distfiles.checksums]
                        sha256 = "0123456789abcdef"

                        [blob]
                        distfiles = ["image.raw"]

                        [provisionable]
                        strategy = "dd-v1"

                        [provisionable.partition_map]
                        disk = "image.raw"
                        """);
    }
}

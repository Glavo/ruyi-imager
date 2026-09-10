// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.repo;

import org.eclipse.jgit.api.Git;
import org.glavo.ruyi.imager.core.AppDirectories;
import org.glavo.ruyi.imager.core.OperationResult;
import org.glavo.ruyi.imager.core.image.RuyiImageCatalogService;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests repository service behavior.
@NotNullByDefault
public final class RuyiRepositoryServiceTest {
    /// Verifies successful repository updates invalidate dependent metadata caches.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when fixture files cannot be written or read.
    @Test
    public void invalidatesCacheAfterSuccessfulUpdate(@TempDir Path temporaryDirectory) throws Exception {
        Path configDirectory = temporaryDirectory.resolve("config");
        Path cacheDirectory = temporaryDirectory.resolve("cache");
        Path repoDirectory = temporaryDirectory.resolve("repo");
        Files.createDirectories(configDirectory);
        Files.createDirectories(repoDirectory);
        Files.writeString(configDirectory.resolve("config.toml"), """
                [repo]
                local = "%s"
                """.formatted(repoDirectory.toString().replace('\\', '/')));
        Files.writeString(repoDirectory.resolve("config.toml"), """
                ruyi-repo = "v1"
                """);

        AtomicBoolean invalidated = new AtomicBoolean();
        RuyiRepositoryService service = new RuyiRepositoryService(
                new RuyiRepositoryStore(new AppDirectories(configDirectory, cacheDirectory)),
                () -> invalidated.set(true));

        OperationResult result = service.update(_ -> {
        });

        assertTrue(result.success(), result.message());
        assertTrue(invalidated.get());
    }

    /// Reloads cached images after the first repository changes but a later repository fails.
    ///
    /// @param directory isolated fixture directory.
    /// @throws Exception when local Git repositories cannot be created or updated.
    @Test
    public void invalidatesCatalogAfterPartialUpdate(@TempDir Path directory) throws Exception {
        Path config = Files.createDirectories(directory.resolve("config"));
        Path cache = directory.resolve("cache");
        Path remotePath = directory.resolve("remote");
        Path missing = directory.resolve("missing");
        Files.writeString(config.resolve("config.toml"), """
                [repo]
                remote = "%s"
                [[repos]]
                id = "missing"
                local = "%s"
                priority = -1
                """.formatted(remotePath.toUri(), missing.toString().replace('\\', '/')));
        try (Git remote = Git.init().setDirectory(remotePath.toFile())
                .setInitialBranch(RuyiRepositoryStore.DEFAULT_REPO_BRANCH).call()) {
            Files.writeString(remotePath.resolve("config.toml"), "ruyi-repo = \"v1\"\n");
            commitAll(remote);
            AppDirectories directories = new AppDirectories(config, cache);
            RuyiRepositoryStore store = new RuyiRepositoryStore(directories);
            assertThrows(IOException.class, () -> store.update(_ -> {}));
            RuyiImageCatalogService catalog = new RuyiImageCatalogService(directories, store);
            AtomicInteger invalidations = new AtomicInteger();
            RuyiRepositoryService service = new RuyiRepositoryService(store, () -> {
                invalidations.incrementAndGet();
                catalog.invalidateCache();
            });
            assertTrue(catalog.listImages().images().isEmpty());
            Path manifest = remotePath.resolve("packages/board-image/test/1.0.0.toml");
            Files.createDirectories(manifest.getParent());
            Files.writeString(manifest, """
                    format = "v1"
                    kind = ["blob", "provisionable"]
                    [metadata]
                    desc = "Updated image"
                    [provisionable]
                    strategy = "dd-v1"
                    [provisionable.partition_map]
                    disk = "image.raw"
                    """);
            commitAll(remote);

            IOException failure = assertThrows(IOException.class, () -> service.update(_ -> {}));

            assertTrue(failure.getMessage().contains(missing.toString()), failure.getMessage());
            assertTrue(Files.isRegularFile(cache.resolve("repos/ruyisdk/packages/board-image/test/1.0.0.toml")));
            assertEquals(1, invalidations.get());
            assertEquals("Updated image", catalog.listImages().images().getFirst().displayName());
        }
    }

    /// Preserves reporter failures while still invalidating caches and suppressing invalidation failures.
    ///
    /// @param failInvalidation whether the invalidator also throws.
    /// @param directory isolated fixture directory.
    /// @throws Exception when fixture files cannot be written.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void invalidatesAfterReporterFailure(boolean failInvalidation, @TempDir Path directory) throws Exception {
        Path config = Files.createDirectories(directory.resolve("config"));
        Path repo = Files.createDirectories(directory.resolve("repo"));
        Files.writeString(config.resolve("config.toml"), "[repo]\nlocal = \"%s\"\n"
                .formatted(repo.toString().replace('\\', '/')));
        Files.writeString(repo.resolve("config.toml"), "ruyi-repo = \"v1\"\n");
        IllegalStateException reporterFailure = new IllegalStateException("Reporter failed");
        IllegalStateException invalidationFailure = new IllegalStateException("Invalidation failed");
        AtomicInteger invalidations = new AtomicInteger();
        RuyiRepositoryService service = new RuyiRepositoryService(
                new RuyiRepositoryStore(new AppDirectories(config, directory.resolve("cache"))), () -> {
                    invalidations.incrementAndGet();
                    if (failInvalidation) {
                        throw invalidationFailure;
                    }
                });

        assertSame(reporterFailure, assertThrows(IllegalStateException.class,
                () -> service.update(_ -> { throw reporterFailure; })));
        assertEquals(1, invalidations.get());
        assertEquals(failInvalidation ? 1 : 0, reporterFailure.getSuppressed().length);
        if (failInvalidation) {
            assertSame(invalidationFailure, reporterFailure.getSuppressed()[0]);
        }
    }

    /// Commits all fixture files without relying on user Git identity configuration.
    ///
    /// @param git fixture repository.
    /// @throws Exception when staging or committing fails.
    private static void commitAll(Git git) throws Exception {
        git.add().addFilepattern(".").call();
        git.commit().setMessage("Update fixture metadata")
                .setAuthor("Test", "test@example.invalid")
                .setCommitter("Test", "test@example.invalid").call();
    }
}

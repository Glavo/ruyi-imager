// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.repo;

import org.glavo.ruyi.imager.core.AppDirectories;
import org.glavo.ruyi.imager.core.OperationResult;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

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
}

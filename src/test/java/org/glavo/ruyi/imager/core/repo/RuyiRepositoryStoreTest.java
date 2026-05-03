// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.repo;

import org.glavo.ruyi.imager.core.AppDirectories;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Tests for Ruyi repository configuration parsing.
@NotNullByDefault
public final class RuyiRepositoryStoreTest {
    /// Verifies default and overlay repository entries are parsed and sorted.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when fixture files cannot be created or read.
    @Test
    public void readsDefaultAndOverlayRepositories(@TempDir Path temporaryDirectory) throws Exception {
        Path configDirectory = temporaryDirectory.resolve("config");
        Path cacheDirectory = temporaryDirectory.resolve("cache");
        Path defaultRepo = temporaryDirectory.resolve("default-repo");
        Path overlayRepo = temporaryDirectory.resolve("overlay-repo");
        Files.createDirectories(configDirectory);

        Files.writeString(configDirectory.resolve("config.toml"), """
                [repo]
                local = "%s"
                branch = "stable"

                [[repos]]
                id = "local-overlay"
                name = "Local Overlay"
                local = "%s"
                priority = 100
                active = true
                """.formatted(pathString(defaultRepo), pathString(overlayRepo)));

        RuyiRepositoryStore store = new RuyiRepositoryStore(new AppDirectories(configDirectory, cacheDirectory));
        List<RuyiRepositoryEntry> entries = store.readEntries();

        assertEquals(2, entries.size());
        assertEquals("local-overlay", entries.get(0).id());
        assertEquals("Local Overlay", entries.get(0).name());
        assertEquals(100, entries.get(0).priority());
        assertEquals(overlayRepo, entries.get(0).localPath());
        assertEquals("ruyisdk", entries.get(1).id());
        assertEquals("stable", entries.get(1).branch());
        assertEquals(defaultRepo, entries.get(1).localPath());
    }

    /// Converts a path to a TOML-friendly string.
    ///
    /// @param path path to convert.
    /// @return path string.
    private static String pathString(Path path) {
        return path.toString().replace('\\', '/');
    }
}

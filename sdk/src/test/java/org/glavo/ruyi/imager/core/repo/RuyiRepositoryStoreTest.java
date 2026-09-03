// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.repo;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.glavo.ruyi.imager.core.AppDirectories;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tomlj.Toml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for Ruyi repository configuration parsing.
@NotNullByDefault
public final class RuyiRepositoryStoreTest {
    /// Verifies the built-in repository uses the mainland China mirror for the Shanghai time zone.
    @Test
    public void usesChinaMirrorForShanghaiTimeZone() {
        assertEquals(
                List.of(
                        RuyiRepositoryStore.CHINA_MAINLAND_REPO_REMOTE,
                        RuyiRepositoryStore.DEFAULT_REPO_REMOTE),
                RuyiRepositoryStore.defaultRepoRemotes(ZoneId.of("Asia/Shanghai")));
    }

    /// Verifies the built-in repository keeps the official remote outside the Shanghai time zone.
    @Test
    public void usesOfficialRemoteOutsideShanghaiTimeZone() {
        assertEquals(
                List.of(RuyiRepositoryStore.DEFAULT_REPO_REMOTE),
                RuyiRepositoryStore.defaultRepoRemotes(ZoneId.of("UTC")));
    }

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

    /// Verifies explicit default repository remote configuration overrides time-zone defaults.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when fixture files cannot be created or read.
    @Test
    public void configuredDefaultRemoteOverridesTimeZoneDefault(@TempDir Path temporaryDirectory) throws Exception {
        Path configDirectory = temporaryDirectory.resolve("config");
        Path cacheDirectory = temporaryDirectory.resolve("cache");
        Files.createDirectories(configDirectory);

        Files.writeString(configDirectory.resolve("config.toml"), """
                [repo]
                remote = "https://example.invalid/packages-index.git"
                """);

        RuyiRepositoryStore store = new RuyiRepositoryStore(new AppDirectories(configDirectory, cacheDirectory));
        List<RuyiRepositoryEntry> entries = store.readEntries();

        assertEquals(1, entries.size());
        assertEquals(List.of("https://example.invalid/packages-index.git"), entries.getFirst().remotes());
    }

    /// Verifies ordered repository source lists are parsed, deduplicated, and cleaned.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when fixture files cannot be created or read.
    @Test
    public void readsConfiguredRemoteList(@TempDir Path temporaryDirectory) throws Exception {
        Path configDirectory = temporaryDirectory.resolve("config");
        Path cacheDirectory = temporaryDirectory.resolve("cache");
        Files.createDirectories(configDirectory);

        Files.writeString(configDirectory.resolve("config.toml"), """
                [repo]
                remote = "https://legacy.example.invalid/packages-index.git"
                remotes = [
                    "https://mirror.example.invalid/packages-index.git",
                    " ",
                    "https://github.example.invalid/packages-index.git",
                    "https://mirror.example.invalid/packages-index.git",
                ]
                """);

        RuyiRepositoryStore store = new RuyiRepositoryStore(new AppDirectories(configDirectory, cacheDirectory));

        assertEquals(
                List.of(
                        "https://mirror.example.invalid/packages-index.git",
                        "https://github.example.invalid/packages-index.git"),
                store.readEntries().getFirst().remotes());
    }

    /// Verifies clone and pull operations retry the next source after a remote failure.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when fixture repositories cannot be created or synchronized.
    @Test
    public void retriesCloneAndPullWithFallbackSource(@TempDir Path temporaryDirectory) throws Exception {
        Path configDirectory = temporaryDirectory.resolve("config");
        Path cacheDirectory = temporaryDirectory.resolve("cache");
        Path remoteDirectory = temporaryDirectory.resolve("remote");
        Path missingRemote = temporaryDirectory.resolve("missing-remote");
        Files.createDirectories(configDirectory);

        try (Git remote = Git.init()
                .setDirectory(remoteDirectory.toFile())
                .setInitialBranch(RuyiRepositoryStore.DEFAULT_REPO_BRANCH)
                .call()) {
            commitRepositoryVersion(remote, "v1");
            writeRemoteConfig(configDirectory, missingRemote, remoteDirectory);

            RuyiRepositoryStore store = new RuyiRepositoryStore(new AppDirectories(configDirectory, cacheDirectory));
            ArrayList<String> statuses = new ArrayList<>();
            store.update(event -> statuses.add(event.message()));

            Path checkout = cacheDirectory.resolve("repos").resolve(RuyiRepositoryStore.DEFAULT_REPO_ID);
            assertEquals("v1", Toml.parse(checkout.resolve("config.toml")).getString("ruyi-repo"));
            assertTrue(statuses.stream().anyMatch(message -> message.contains("fallback source")));

            commitRepositoryVersion(remote, "v2");
            statuses.clear();
            store.update(event -> statuses.add(event.message()));

            assertEquals("v2", Toml.parse(checkout.resolve("config.toml")).getString("ruyi-repo"));
            assertTrue(statuses.stream().anyMatch(message -> message.contains("fallback source")));
            try (Git checkoutRepository = Git.open(checkout.toFile())) {
                assertEquals(
                        remoteDirectory.toUri().toString(),
                        checkoutRepository.getRepository().getConfig().getString("remote", "origin", "url"));
            }
        }
    }

    /// Verifies failed clone attempts do not expose or retain partial checkout directories.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when fixture files cannot be created or inspected.
    @Test
    public void cleansStagingDirectoriesWhenAllCloneSourcesFail(@TempDir Path temporaryDirectory) throws Exception {
        Path configDirectory = temporaryDirectory.resolve("config");
        Path cacheDirectory = temporaryDirectory.resolve("cache");
        Files.createDirectories(configDirectory);

        Path firstMissingRemote = temporaryDirectory.resolve("missing-remote-1");
        Path secondMissingRemote = temporaryDirectory.resolve("missing-remote-2");
        writeRemoteConfig(configDirectory, firstMissingRemote, secondMissingRemote);

        RuyiRepositoryStore store = new RuyiRepositoryStore(new AppDirectories(configDirectory, cacheDirectory));
        IOException exception = assertThrows(IOException.class, () -> store.update(_ -> {
        }));

        assertTrue(exception.getMessage().contains("all 2 configured sources"), exception.getMessage());
        Path repositoriesDirectory = cacheDirectory.resolve("repos");
        assertFalse(Files.exists(repositoriesDirectory.resolve(RuyiRepositoryStore.DEFAULT_REPO_ID)));
        try (var paths = Files.list(repositoriesDirectory)) {
            assertTrue(paths.findAny().isEmpty());
        }
    }

    /// Verifies missing default repository metadata is reported as unavailable.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when repository configuration cannot be read.
    @Test
    public void reportsMissingLocalMetadata(@TempDir Path temporaryDirectory) throws Exception {
        Path configDirectory = temporaryDirectory.resolve("config");
        Path cacheDirectory = temporaryDirectory.resolve("cache");

        RuyiRepositoryStore store = new RuyiRepositoryStore(new AppDirectories(configDirectory, cacheDirectory));

        assertFalse(store.hasLocalMetadata());
    }

    /// Verifies existing local repository metadata is reported as available.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when fixture files cannot be created or read.
    @Test
    public void reportsExistingLocalMetadata(@TempDir Path temporaryDirectory) throws Exception {
        Path configDirectory = temporaryDirectory.resolve("config");
        Path cacheDirectory = temporaryDirectory.resolve("cache");
        Path repoDirectory = temporaryDirectory.resolve("repo");
        Files.createDirectories(configDirectory);
        Files.createDirectories(repoDirectory);

        Files.writeString(configDirectory.resolve("config.toml"), """
                [repo]
                local = "%s"
                """.formatted(pathString(repoDirectory)));
        Files.writeString(repoDirectory.resolve("config.toml"), """
                ruyi-repo = "v1"
                """);

        RuyiRepositoryStore store = new RuyiRepositoryStore(new AppDirectories(configDirectory, cacheDirectory));

        assertTrue(store.hasLocalMetadata());
    }

    /// Converts a path to a TOML-friendly string.
    ///
    /// @param path path to convert.
    /// @return path string.
    private static String pathString(Path path) {
        return path.toString().replace('\\', '/');
    }

    /// Writes an ordered two-source default repository configuration.
    ///
    /// @param configDirectory application configuration directory.
    /// @param firstRemote first source path.
    /// @param secondRemote fallback source path.
    /// @throws IOException when the configuration cannot be written.
    private static void writeRemoteConfig(
            Path configDirectory,
            Path firstRemote,
            Path secondRemote) throws IOException {
        Files.writeString(configDirectory.resolve("config.toml"), """
                [repo]
                remotes = ["%s", "%s"]
                """.formatted(firstRemote.toUri(), secondRemote.toUri()));
    }

    /// Commits one metadata version to a fixture repository.
    ///
    /// @param git fixture repository.
    /// @param version metadata version text.
    /// @throws IOException when the metadata file cannot be written.
    /// @throws GitAPIException when the metadata cannot be committed.
    private static void commitRepositoryVersion(Git git, String version) throws IOException, GitAPIException {
        Path configFile = git.getRepository().getWorkTree().toPath().resolve("config.toml");
        Files.writeString(configFile, "ruyi-repo = \"" + version + "\"\n");
        git.add().addFilepattern("config.toml").call();
        git.commit()
                .setMessage("Set repository version to " + version)
                .setAuthor("Ruyi Imager Tests", "tests@example.invalid")
                .setCommitter("Ruyi Imager Tests", "tests@example.invalid")
                .call();
    }
}

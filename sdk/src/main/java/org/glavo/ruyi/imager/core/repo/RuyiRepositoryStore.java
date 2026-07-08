// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.repo;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.StoredConfig;
import org.glavo.ruyi.imager.core.AppDirectories;
import org.glavo.ruyi.imager.core.NetworkDefaults;
import org.glavo.ruyi.imager.core.OperationResult;
import org.glavo.ruyi.imager.core.ProgressEvent;
import org.glavo.ruyi.imager.core.ProgressReporter;
import org.glavo.ruyi.imager.core.SdkMessages;
import org.glavo.ruyi.imager.logging.LogRedactor;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseError;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Reads and synchronizes Ruyi metadata repositories.
@NotNullByDefault
public final class RuyiRepositoryStore {
    /// Logger for repository metadata operations.
    private static final Logger LOGGER = LoggerFactory.getLogger(RuyiRepositoryStore.class);

    /// Official Ruyi repository identifier.
    public static final String DEFAULT_REPO_ID = "ruyisdk";

    /// Official Ruyi repository name.
    public static final String DEFAULT_REPO_NAME = "RuyiSDK official repository";

    /// Official Ruyi packages index remote.
    public static final String DEFAULT_REPO_REMOTE = "https://github.com/ruyisdk/packages-index.git";

    /// Mainland China Ruyi packages index mirror remote.
    public static final String CHINA_MAINLAND_REPO_REMOTE = "https://mirror.iscas.ac.cn/git/ruyisdk/packages-index.git";

    /// Default branch for Ruyi metadata repositories.
    public static final String DEFAULT_REPO_BRANCH = "main";

    /// Built-in Ruyi dist mirror identifier.
    public static final String RUYI_DIST_MIRROR_ID = "ruyi-dist";

    /// Time zone identifier used as the mainland China default remote heuristic.
    private static final String CHINA_MAINLAND_TIME_ZONE = "Asia/Shanghai";

    /// Valid Ruyi repository id pattern.
    private static final Pattern REPO_ID_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9_-]*$");

    /// Application directories used to locate config and cache data.
    private final AppDirectories directories;

    /// Creates a repository store.
    ///
    /// @param directories application directories.
    public RuyiRepositoryStore(AppDirectories directories) {
        NetworkDefaults.enableSystemProxiesByDefault();
        this.directories = directories;
    }

    /// Reads all configured repositories.
    ///
    /// @return immutable repository list sorted by overlay priority.
    /// @throws IOException when the user config cannot be read.
    public @Unmodifiable List<RuyiRepositoryEntry> readEntries() throws IOException {
        Path configFile = directories.configDirectory().resolve("config.toml");
        if (!Files.isRegularFile(configFile)) {
            LOGGER.atInfo().log(() -> "Repository config is absent. Using default repository. path=" + configFile);
            return List.of(defaultEntry(null, null, null));
        }

        LOGGER.atInfo().log(() -> "Reading repository config. path=" + configFile);
        TomlParseResult config = parseToml(configFile);
        ArrayList<RuyiRepositoryEntry> entries = new ArrayList<>();
        entries.add(readDefaultEntry(config));

        HashSet<String> seenIds = new HashSet<>();
        seenIds.add(DEFAULT_REPO_ID);
        @Nullable TomlArray repos = config.getArray("repos");
        if (repos != null) {
            for (int i = 0; i < repos.size(); i++) {
                Object value = repos.get(i);
                if (value instanceof TomlTable table) {
                    @Nullable RuyiRepositoryEntry entry = readOverlayEntry(table, seenIds);
                    if (entry != null) {
                        entries.add(entry);
                        seenIds.add(entry.id());
                    }
                }
            }
        }

        entries.sort(Comparator.comparingInt(RuyiRepositoryEntry::priority)
                .reversed()
                .thenComparing(RuyiRepositoryEntry::id));
        LOGGER.atInfo().log(() -> "Repository config loaded. entries=" + entries.size());
        return List.copyOf(entries);
    }

    /// Reads enabled repositories.
    ///
    /// @return immutable active repository list.
    /// @throws IOException when the user config cannot be read.
    public @Unmodifiable List<RuyiRepositoryEntry> readActiveEntries() throws IOException {
        ArrayList<RuyiRepositoryEntry> result = new ArrayList<>();
        for (RuyiRepositoryEntry entry : readEntries()) {
            if (entry.active()) {
                result.add(entry);
            }
        }
        return List.copyOf(result);
    }

    /// Returns whether all active repositories have local metadata.
    ///
    /// @return true when every active repository has a local `config.toml`.
    /// @throws IOException when repository configuration cannot be read.
    public boolean hasLocalMetadata() throws IOException {
        for (RuyiRepositoryEntry entry : readActiveEntries()) {
            Path configFile = resolveRoot(entry).resolve("config.toml");
            if (!Files.isRegularFile(configFile)) {
                LOGGER.atInfo().log(() -> "Repository metadata is missing. id="
                        + entry.id()
                        + ", config="
                        + configFile);
                return false;
            }
        }
        return true;
    }

    /// Synchronizes enabled repositories.
    ///
    /// @param reporter progress reporter.
    /// @return update result.
    /// @throws IOException when local files or Git metadata cannot be updated.
    public OperationResult update(ProgressReporter reporter) throws IOException {
        Files.createDirectories(directories.configDirectory());
        Files.createDirectories(directories.cacheDirectory().resolve("repos"));

        List<RuyiRepositoryEntry> entries = readActiveEntries();
        LOGGER.atInfo().log(() -> "Updating active repositories. count=" + entries.size());
        for (RuyiRepositoryEntry entry : entries) {
            sync(entry, reporter);
        }

        LOGGER.info("Repository update completed.");
        return OperationResult.success(SdkMessages.get("core.repo.updated", entries.size()));
    }

    /// Reads repository metadata from the checkout.
    ///
    /// @param entry repository entry.
    /// @return repository metadata.
    /// @throws IOException when `config.toml` cannot be read.
    public RuyiRepositoryMetadata readMetadata(RuyiRepositoryEntry entry) throws IOException {
        Path root = entry.resolveRoot(directories.cacheDirectory());
        Path configFile = root.resolve("config.toml");
        TomlParseResult config = parseToml(configFile);
        Map<String, @Unmodifiable List<String>> mirrors = new HashMap<>();

        @Nullable String legacyDist = config.getString("dist");
        if (legacyDist != null) {
            mirrors.put(RUYI_DIST_MIRROR_ID, List.of(joinUrl(legacyDist, "dist/")));
        }

        readMirrors(config.getArray("mirrors"), mirrors);
        readMirrors(config.getArray("mirror"), mirrors);

        return new RuyiRepositoryMetadata(entry.id(), entry.name(), root, mirrors);
    }

    /// Resolves a repository's local root.
    ///
    /// @param entry repository entry.
    /// @return local repository root.
    public Path resolveRoot(RuyiRepositoryEntry entry) {
        return entry.resolveRoot(directories.cacheDirectory());
    }

    /// Reads default repository settings from `[repo]`.
    ///
    /// @param config user config.
    /// @return default repository entry.
    private static RuyiRepositoryEntry readDefaultEntry(TomlTable config) {
        @Nullable TomlTable repo = config.getTable("repo");
        if (repo == null) {
            return defaultEntry(null, null, null);
        }

        return defaultEntry(
                repo.getString("remote"),
                repo.getString("branch"),
                readAbsolutePath(repo.getString("local")));
    }

    /// Creates the default repository entry.
    ///
    /// @param remote configured remote URL.
    /// @param branch configured branch.
    /// @param local configured local path.
    /// @return default repository entry.
    private static RuyiRepositoryEntry defaultEntry(
            @Nullable String remote,
            @Nullable String branch,
            @Nullable Path local) {
        return new RuyiRepositoryEntry(
                DEFAULT_REPO_ID,
                DEFAULT_REPO_NAME,
                remote == null || remote.isBlank() ? defaultRepoRemote() : remote,
                branch == null || branch.isBlank() ? DEFAULT_REPO_BRANCH : branch,
                local,
                0,
                true);
    }

    /// Returns the default repository remote for the current system time zone.
    ///
    /// @return default repository remote.
    static String defaultRepoRemote() {
        return defaultRepoRemote(ZoneId.systemDefault());
    }

    /// Returns the default repository remote for one time zone.
    ///
    /// @param zoneId system time zone identifier.
    /// @return default repository remote.
    static String defaultRepoRemote(ZoneId zoneId) {
        return CHINA_MAINLAND_TIME_ZONE.equals(zoneId.getId())
                ? CHINA_MAINLAND_REPO_REMOTE
                : DEFAULT_REPO_REMOTE;
    }

    /// Reads one overlay repository entry.
    ///
    /// @param table repository table.
    /// @param seenIds already accepted repository ids.
    /// @return repository entry, or null when the table is invalid.
    private static @Nullable RuyiRepositoryEntry readOverlayEntry(TomlTable table, Set<String> seenIds) {
        @Nullable String id = table.getString("id");
        if (id == null || !REPO_ID_PATTERN.matcher(id).matches() || DEFAULT_REPO_ID.equals(id) || seenIds.contains(id)) {
            return null;
        }

        @Nullable String remote = table.getString("remote");
        @Nullable Path local = readAbsolutePath(table.getString("local"));
        if ((remote == null || remote.isBlank()) && local == null) {
            return null;
        }

        @Nullable String name = table.getString("name");
        @Nullable String branch = table.getString("branch");
        @Nullable Long priority = table.getLong("priority");
        @Nullable Boolean active = table.getBoolean("active");
        return new RuyiRepositoryEntry(
                id,
                name == null || name.isBlank() ? id : name,
                remote == null || remote.isBlank() ? null : remote,
                branch == null || branch.isBlank() ? DEFAULT_REPO_BRANCH : branch,
                local,
                priority == null ? 0 : Math.toIntExact(priority),
                active == null || active);
    }

    /// Synchronizes one repository.
    ///
    /// @param entry repository entry.
    /// @param reporter progress reporter.
    /// @throws IOException when local files or Git metadata cannot be updated.
    private void sync(RuyiRepositoryEntry entry, ProgressReporter reporter) throws IOException {
        Path root = resolveRoot(entry);
        @Nullable String remote = entry.remote();
        LOGGER.atInfo().log(() -> "Synchronizing repository. id="
                + entry.id()
                + ", root="
                + root
                + ", remote="
                + (remote == null ? "<local>" : LogRedactor.redactText(remote)));
        if (remote == null || remote.isBlank()) {
            reporter.report(ProgressEvent.indeterminate("repo", SdkMessages.get("core.repo.usingLocal", entry.id())));
            if (!Files.isRegularFile(root.resolve("config.toml"))) {
                throw new IOException(SdkMessages.get("core.repo.localMissingConfig", root));
            }
            return;
        }

        if (Files.notExists(root)) {
            cloneRepository(entry, root, remote, reporter);
        } else if (Files.isDirectory(root.resolve(".git"))) {
            pullRepository(entry, root, remote, reporter);
        } else if (Files.isRegularFile(root.resolve("config.toml"))) {
            reporter.report(ProgressEvent.indeterminate("repo", SdkMessages.get("core.repo.usingUnmanagedLocal", entry.id())));
        } else {
            throw new IOException(SdkMessages.get("core.repo.cacheNotGit", root));
        }

        readMetadata(entry);
    }

    /// Clones one repository.
    ///
    /// @param entry repository entry.
    /// @param root local checkout root.
    /// @param remote remote Git URL.
    /// @param reporter progress reporter.
    /// @throws IOException when Git clone fails.
    private static void cloneRepository(
            RuyiRepositoryEntry entry,
            Path root,
            String remote,
            ProgressReporter reporter) throws IOException {
        reporter.report(ProgressEvent.indeterminate("repo", SdkMessages.get("core.repo.cloning", entry.id())));
        LOGGER.atInfo().log(() -> "Cloning repository. id="
                + entry.id()
                + ", remote="
                + LogRedactor.redactText(remote)
                + ", branch="
                + entry.branch()
                + ", root="
                + root);
        @Nullable Path parent = root.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (Git ignored = Git.cloneRepository()
                .setURI(remote)
                .setDirectory(root.toFile())
                .setBranch(entry.branch())
                .call()) {
            reporter.report(ProgressEvent.indeterminate("repo", SdkMessages.get("core.repo.cloned", entry.id())));
        } catch (GitAPIException e) {
            LOGGER.warn("Repository clone failed. id=" + entry.id(), e);
            throw new IOException(SdkMessages.get("core.repo.cloneFailed", entry.id(), e.getMessage()), e);
        }
    }

    /// Pulls one existing repository.
    ///
    /// @param entry repository entry.
    /// @param root local checkout root.
    /// @param remote remote Git URL.
    /// @param reporter progress reporter.
    /// @throws IOException when Git pull fails.
    private static void pullRepository(
            RuyiRepositoryEntry entry,
            Path root,
            String remote,
            ProgressReporter reporter) throws IOException {
        reporter.report(ProgressEvent.indeterminate("repo", SdkMessages.get("core.repo.updating", entry.id())));
        LOGGER.atInfo().log(() -> "Pulling repository. id="
                + entry.id()
                + ", remote="
                + LogRedactor.redactText(remote)
                + ", branch="
                + entry.branch()
                + ", root="
                + root);
        try (Git git = Git.open(root.toFile())) {
            StoredConfig config = git.getRepository().getConfig();
            config.setString("remote", "origin", "url", remote);
            config.save();

            if (!git.pull()
                    .setRemote("origin")
                    .setRemoteBranchName(entry.branch())
                    .call()
                    .isSuccessful()) {
                throw new IOException(SdkMessages.get("core.repo.pullFailed", entry.id()));
            }
            reporter.report(ProgressEvent.indeterminate("repo", SdkMessages.get("core.repo.updatedOne", entry.id())));
        } catch (GitAPIException e) {
            LOGGER.warn("Repository update failed. id=" + entry.id(), e);
            throw new IOException(SdkMessages.get("core.repo.updateFailed", entry.id(), e.getMessage()), e);
        }
    }

    /// Reads mirror declarations from an array.
    ///
    /// @param array mirror declaration array.
    /// @param mirrors output mirror map.
    private static void readMirrors(@Nullable TomlArray array, Map<String, @Unmodifiable List<String>> mirrors) {
        if (array == null) {
            return;
        }

        for (int i = 0; i < array.size(); i++) {
            Object value = array.get(i);
            if (!(value instanceof TomlTable table)) {
                continue;
            }

            @Nullable String id = table.getString("id");
            @Nullable TomlArray urls = table.getArray("urls");
            if (id == null || id.isBlank() || urls == null) {
                continue;
            }
            mirrors.put(id, readStringArray(urls));
        }
    }

    /// Reads a string array.
    ///
    /// @param array TOML array.
    /// @return immutable string list.
    private static @Unmodifiable List<String> readStringArray(TomlArray array) {
        ArrayList<String> values = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            Object value = array.get(i);
            if (value instanceof String text) {
                values.add(text);
            }
        }
        return List.copyOf(values);
    }

    /// Reads an absolute path from a TOML string.
    ///
    /// @param value path string.
    /// @return normalized path, or null when absent or relative.
    private static @Nullable Path readAbsolutePath(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        Path path = Path.of(value);
        return path.isAbsolute() ? path.normalize() : null;
    }

    /// Parses a TOML file and reports syntax errors.
    ///
    /// @param path TOML file path.
    /// @return TOML parse result.
    /// @throws IOException when the file cannot be read or parsed.
    private static TomlParseResult parseToml(Path path) throws IOException {
        TomlParseResult result = Toml.parse(path);
        if (result.hasErrors()) {
            StringBuilder builder = new StringBuilder(SdkMessages.get("core.toml.parseFailed", path));
            for (TomlParseError error : result.errors()) {
                builder.append(System.lineSeparator()).append(error);
            }
            throw new IOException(builder.toString());
        }
        return result;
    }

    /// Joins a base URL and a relative path.
    ///
    /// @param base base URL.
    /// @param path relative path.
    /// @return joined URL.
    private static String joinUrl(String base, String path) {
        String normalizedBase = base.endsWith("/") ? base : base + "/";
        return java.net.URI.create(normalizedBase).resolve(path).toString();
    }
}

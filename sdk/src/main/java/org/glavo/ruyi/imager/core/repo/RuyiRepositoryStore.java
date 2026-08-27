// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.repo;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.RefNotAdvertisedException;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.api.errors.TransportException;
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
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
            mirrors.put(RUYI_DIST_MIRROR_ID, List.of(distMirrorUrl(legacyDist)));
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
                readConfiguredRemotes(repo),
                repo.getString("branch"),
                readAbsolutePath(repo.getString("local")));
    }

    /// Creates the default repository entry.
    ///
    /// @param remotes configured remote URLs, or null to use the system defaults.
    /// @param branch configured branch.
    /// @param local configured local path.
    /// @return default repository entry.
    private static RuyiRepositoryEntry defaultEntry(
            @Nullable List<String> remotes,
            @Nullable String branch,
            @Nullable Path local) {
        return new RuyiRepositoryEntry(
                DEFAULT_REPO_ID,
                DEFAULT_REPO_NAME,
                remotes == null ? defaultRepoRemotes() : remotes,
                branch == null || branch.isBlank() ? DEFAULT_REPO_BRANCH : branch,
                local,
                0,
                true);
    }

    /// Returns the default repository remotes for the current system time zone.
    ///
    /// @return ordered default repository remotes.
    static @Unmodifiable List<String> defaultRepoRemotes() {
        return defaultRepoRemotes(ZoneId.systemDefault());
    }

    /// Returns the default repository remotes for one time zone.
    ///
    /// @param zoneId system time zone identifier.
    /// @return ordered default repository remotes.
    static @Unmodifiable List<String> defaultRepoRemotes(ZoneId zoneId) {
        return CHINA_MAINLAND_TIME_ZONE.equals(zoneId.getId())
                ? List.of(CHINA_MAINLAND_REPO_REMOTE, DEFAULT_REPO_REMOTE)
                : List.of(DEFAULT_REPO_REMOTE);
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

        @Nullable List<String> remotes = readConfiguredRemotes(table);
        @Nullable Path local = readAbsolutePath(table.getString("local"));
        if ((remotes == null || remotes.isEmpty()) && local == null) {
            return null;
        }

        @Nullable String name = table.getString("name");
        @Nullable String branch = table.getString("branch");
        @Nullable Long priority = table.getLong("priority");
        @Nullable Boolean active = table.getBoolean("active");
        return new RuyiRepositoryEntry(
                id,
                name == null || name.isBlank() ? id : name,
                remotes == null ? List.of() : remotes,
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
        List<String> remotes = entry.remotes();
        LOGGER.atInfo().log(() -> "Synchronizing repository. id="
                + entry.id()
                + ", root="
                + root
                + ", remotes="
                + (remotes.isEmpty() ? "<local>" : LogRedactor.redactText(remotes.toString())));
        if (remotes.isEmpty()) {
            reporter.report(ProgressEvent.indeterminate("repo", SdkMessages.get("core.repo.usingLocal", entry.id())));
            if (!Files.isRegularFile(root.resolve("config.toml"))) {
                throw new IOException(SdkMessages.get("core.repo.localMissingConfig", root));
            }
            return;
        }

        if (Files.notExists(root)) {
            cloneRepository(entry, root, reporter);
        } else if (Files.isDirectory(root.resolve(".git"))) {
            pullRepository(entry, root, reporter);
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
    /// @param reporter progress reporter.
    /// @throws IOException when every configured Git source fails or local publication fails.
    private static void cloneRepository(
            RuyiRepositoryEntry entry,
            Path root,
            ProgressReporter reporter) throws IOException {
        reporter.report(ProgressEvent.indeterminate("repo", SdkMessages.get("core.repo.cloning", entry.id())));
        @Nullable Path parent = root.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        ArrayList<IOException> failures = new ArrayList<>();
        List<String> remotes = entry.remotes();
        for (int index = 0; index < remotes.size(); index++) {
            String remote = remotes.get(index);
            if (index > 0) {
                reporter.report(ProgressEvent.indeterminate(
                        "repo",
                        SdkMessages.get("core.repo.tryingFallback", entry.id())));
            }
            LOGGER.atInfo().log(() -> "Cloning repository. id="
                    + entry.id()
                    + ", remote="
                    + LogRedactor.redactText(remote)
                    + ", branch="
                    + entry.branch()
                    + ", root="
                    + root);
            try {
                cloneRepositoryFromRemote(entry, root, remote);
                reporter.report(ProgressEvent.indeterminate("repo", SdkMessages.get("core.repo.cloned", entry.id())));
                return;
            } catch (GitAPIException e) {
                IOException failure = new IOException(
                        SdkMessages.get(
                                "core.repo.cloneFailed",
                                entry.id(),
                                LogRedactor.redactText(String.valueOf(e.getMessage()))),
                        e);
                failures.add(failure);
                LOGGER.warn("Repository clone source failed. id="
                        + entry.id()
                        + ", remote="
                        + LogRedactor.redactText(remote)
                        + ", error="
                        + LogRedactor.redactText(String.valueOf(e.getMessage())));
            }
        }

        throw sourcesFailed("core.repo.cloneSourcesFailed", entry, failures);
    }

    /// Clones one source into a staging directory and publishes the completed checkout.
    ///
    /// @param entry repository entry.
    /// @param root final checkout root.
    /// @param remote remote Git URL.
    /// @throws IOException when staging cleanup or publication fails.
    /// @throws GitAPIException when Git clone fails.
    private static void cloneRepositoryFromRemote(
            RuyiRepositoryEntry entry,
            Path root,
            String remote) throws IOException, GitAPIException {
        @Nullable Path parent = root.getParent();
        @Nullable Path fileName = root.getFileName();
        if (parent == null || fileName == null) {
            throw new IOException("Repository root has no parent directory: " + root);
        }

        Path staging = parent.resolve("." + fileName + ".clone-" + UUID.randomUUID());
        try {
            try (Git ignored = Git.cloneRepository()
                    .setURI(remote)
                    .setDirectory(staging.toFile())
                    .setBranch(entry.branch())
                    .call()) {
                // Closing the repository releases files before the directory is moved.
            }
            moveDirectory(staging, root);
        } catch (IOException | GitAPIException | RuntimeException e) {
            try {
                deleteRecursively(staging);
            } catch (IOException cleanupException) {
                e.addSuppressed(cleanupException);
            }
            throw e;
        }
    }

    /// Pulls one existing repository, retrying source-related failures.
    ///
    /// @param entry repository entry.
    /// @param root local checkout root.
    /// @param reporter progress reporter.
    /// @throws IOException when the repository cannot be opened, configured, or updated.
    private static void pullRepository(
            RuyiRepositoryEntry entry,
            Path root,
            ProgressReporter reporter) throws IOException {
        reporter.report(ProgressEvent.indeterminate("repo", SdkMessages.get("core.repo.updating", entry.id())));
        ArrayList<IOException> failures = new ArrayList<>();
        try (Git git = Git.open(root.toFile())) {
            StoredConfig config = git.getRepository().getConfig();
            List<String> remotes = entry.remotes();
            for (int index = 0; index < remotes.size(); index++) {
                String remote = remotes.get(index);
                if (index > 0) {
                    reporter.report(ProgressEvent.indeterminate(
                            "repo",
                            SdkMessages.get("core.repo.tryingFallback", entry.id())));
                }
                LOGGER.atInfo().log(() -> "Pulling repository. id="
                        + entry.id()
                        + ", remote="
                        + LogRedactor.redactText(remote)
                        + ", branch="
                        + entry.branch()
                        + ", root="
                        + root);
                config.setString("remote", "origin", "url", remote);
                config.save();

                try {
                    if (!git.pull()
                            .setRemote("origin")
                            .setRemoteBranchName(entry.branch())
                            .call()
                            .isSuccessful()) {
                        throw new IOException(SdkMessages.get("core.repo.pullFailed", entry.id()));
                    }
                    reporter.report(ProgressEvent.indeterminate(
                            "repo",
                            SdkMessages.get("core.repo.updatedOne", entry.id())));
                    return;
                } catch (GitAPIException e) {
                    IOException failure = new IOException(
                            SdkMessages.get(
                                    "core.repo.updateFailed",
                                    entry.id(),
                                    LogRedactor.redactText(String.valueOf(e.getMessage()))),
                            e);
                    if (!isRetryableRemoteFailure(e)) {
                        throw failure;
                    }
                    failures.add(failure);
                    LOGGER.warn("Repository update source failed. id="
                            + entry.id()
                            + ", remote="
                            + LogRedactor.redactText(remote)
                            + ", error="
                            + LogRedactor.redactText(String.valueOf(e.getMessage())));
                }
            }
        }

        throw sourcesFailed("core.repo.updateSourcesFailed", entry, failures);
    }

    /// Returns whether a pull failure can depend on the selected remote source.
    ///
    /// @param exception pull failure.
    /// @return true when trying the next configured source may succeed safely.
    private static boolean isRetryableRemoteFailure(GitAPIException exception) {
        return exception instanceof TransportException
                || exception instanceof InvalidRemoteException
                || exception instanceof RefNotAdvertisedException
                || exception instanceof RefNotFoundException;
    }

    /// Combines failures from an exhausted source list.
    ///
    /// @param messageKey localized aggregate message key.
    /// @param entry repository entry.
    /// @param failures source failures in attempt order.
    /// @return combined failure.
    private static IOException sourcesFailed(
            String messageKey,
            RuyiRepositoryEntry entry,
            List<IOException> failures) {
        if (failures.size() == 1) {
            return failures.getFirst();
        }

        IOException lastFailure = failures.getLast();
        IOException result = new IOException(
                SdkMessages.get(messageKey, entry.id(), failures.size(), lastFailure.getMessage()),
                lastFailure);
        for (int index = 0; index < failures.size() - 1; index++) {
            result.addSuppressed(failures.get(index));
        }
        return result;
    }

    /// Reads an ordered source list from `remotes`, or the legacy `remote` value.
    ///
    /// @param table repository configuration table.
    /// @return configured remotes, or null when neither setting is present.
    private static @Nullable @Unmodifiable List<String> readConfiguredRemotes(TomlTable table) {
        @Nullable TomlArray remotes = table.getArray("remotes");
        if (remotes != null) {
            return readRemoteArray(remotes);
        }

        @Nullable String remote = table.getString("remote");
        return remote == null || remote.isBlank() ? null : List.of(remote);
    }

    /// Reads non-blank, distinct Git sources while preserving their configured order.
    ///
    /// @param array TOML array.
    /// @return immutable remote list.
    private static @Unmodifiable List<String> readRemoteArray(TomlArray array) {
        ArrayList<String> values = new ArrayList<>();
        for (int index = 0; index < array.size(); index++) {
            Object value = array.get(index);
            if (value instanceof String text && !text.isBlank() && !values.contains(text)) {
                values.add(text);
            }
        }
        return List.copyOf(values);
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

    /// Moves one completed directory into its final location.
    ///
    /// @param source source path.
    /// @param target target path.
    /// @throws IOException when the directory cannot be moved.
    private static void moveDirectory(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicException) {
            try {
                Files.move(source, target);
            } catch (IOException fallbackException) {
                fallbackException.addSuppressed(atomicException);
                throw fallbackException;
            }
        }
    }

    /// Deletes one directory tree without following symbolic links.
    ///
    /// @param directory directory to delete.
    /// @throws IOException when deletion fails.
    private static void deleteRecursively(Path directory) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }

        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            /// Deletes one file in the tree.
            ///
            /// @param file file path.
            /// @param attrs file attributes.
            /// @return traversal continuation.
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            /// Deletes a directory after its children have been deleted.
            ///
            /// @param dir directory path.
            /// @param exception traversal failure, or null.
            /// @return traversal continuation.
            @Override
            public FileVisitResult postVisitDirectory(Path dir, @Nullable IOException exception) throws IOException {
                if (exception != null) {
                    throw exception;
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
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

    /// Resolves the legacy distfile directory below a configured base URL.
    ///
    /// @param base base URL.
    /// @return distfile directory URL.
    private static String distMirrorUrl(String base) {
        String normalizedBase = base.endsWith("/") ? base : base + "/";
        return java.net.URI.create(normalizedBase).resolve("dist/").toString();
    }
}

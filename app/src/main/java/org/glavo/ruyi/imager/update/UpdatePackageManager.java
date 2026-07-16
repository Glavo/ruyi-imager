// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.update;

import org.glavo.ruyi.imager.core.AppDirectories;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Consumer;

/// Copies local update artifacts into an integrity-checked application cache.
@NotNullByDefault
public final class UpdatePackageManager {
    /// Update package cache directory name.
    private static final String CACHE_DIRECTORY_NAME = "updates";

    /// Copy buffer size.
    private static final int BUFFER_SIZE = 128 * 1024;

    /// Source update manifest path.
    private final Path manifest;

    /// Application cache directory.
    private final Path cacheDirectory;

    /// Current update platform.
    private final UpdatePlatform platform;

    /// Creates an update package manager.
    ///
    /// @param manifest       source update manifest path.
    /// @param cacheDirectory application cache directory.
    /// @param platform       current update platform.
    public UpdatePackageManager(Path manifest, Path cacheDirectory, UpdatePlatform platform) {
        this.manifest = manifest.toAbsolutePath().normalize();
        this.cacheDirectory = cacheDirectory.toAbsolutePath().normalize();
        this.platform = platform;
    }

    /// Creates a package manager for the current runtime.
    ///
    /// @param directories application directories.
    /// @param checker     configured update checker.
    /// @return package manager.
    public static UpdatePackageManager createDefault(AppDirectories directories, UpdateChecker checker) {
        return new UpdatePackageManager(checker.source(), directories.cacheDirectory(), UpdatePlatform.current());
    }

    /// Returns the platform artifact from a release, or null when none is published.
    ///
    /// @param release selected update release.
    /// @return matching artifact, or null.
    public @Nullable UpdateArtifact artifactFor(UpdateRelease release) {
        return release.artifactFor(platform);
    }

    /// Copies and verifies the platform installer package.
    ///
    /// @param release  selected update release.
    /// @param progress progress callback.
    /// @return verified prepared update.
    /// @throws IOException when no compatible artifact exists or verification fails.
    public PreparedUpdate prepare(UpdateRelease release, Consumer<UpdateProgress> progress) throws IOException {
        @Nullable UpdateArtifact artifact = artifactFor(release);
        if (artifact == null) {
            throw new IOException("No update installer is available for platform: " + platform.id());
        }

        Path source = resolveSource(artifact);
        if (!artifact.packageType().matchesFileName(source.getFileName().toString())) {
            throw new IOException("Update artifact file name does not match package type "
                    + artifact.packageType().token() + ": " + source);
        }
        if (Files.size(source) != artifact.size()) {
            throw new IOException("Update artifact size does not match the manifest: " + source);
        }

        Path destination = cacheDestination(artifact, source.getFileName().toString());
        if (Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)) {
            if (verifyFile(destination, artifact)) {
                progress.accept(new UpdateProgress(artifact.size(), artifact.size()));
                return new PreparedUpdate(release, artifact, destination);
            }
            Files.delete(destination);
        } else if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Update cache destination is not a regular file: " + destination);
        }

        Path temporary = Files.createTempFile(destination.getParent(), destination.getFileName() + ".", ".part");
        boolean moved = false;
        try {
            copyAndVerify(source, temporary, artifact, progress);
            moveAtomically(temporary, destination);
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
        if (!verifyFile(destination, artifact)) {
            Files.deleteIfExists(destination);
            throw new IOException("Cached update artifact failed verification after installation: " + destination);
        }
        return new PreparedUpdate(release, artifact, destination);
    }

    /// Resolves a local artifact without allowing path or symbolic-link escape.
    ///
    /// @param artifact selected artifact.
    /// @return real source path.
    /// @throws IOException when the source escapes the manifest directory or is not a regular file.
    private Path resolveSource(UpdateArtifact artifact) throws IOException {
        Path manifestReal = manifest.toRealPath();
        @Nullable Path sourceRoot = manifestReal.getParent();
        if (sourceRoot == null) {
            throw new IOException("Update manifest has no parent directory: " + manifest);
        }

        Path relative;
        try {
            relative = Path.of(artifact.source());
        } catch (InvalidPathException exception) {
            throw new IOException("Invalid update artifact source path: " + artifact.source(), exception);
        }
        if (relative.isAbsolute()) {
            throw new IOException("Update artifact source must be relative to the manifest: " + artifact.source());
        }
        Path candidate = sourceRoot.resolve(relative).normalize();
        if (!candidate.startsWith(sourceRoot)) {
            throw new IOException("Update artifact source escapes the manifest directory: " + artifact.source());
        }

        Path realSource = candidate.toRealPath();
        if (!realSource.startsWith(sourceRoot)
                || !Files.isRegularFile(realSource, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Update artifact is not a regular file inside the manifest directory: " + candidate);
        }
        return realSource;
    }

    /// Resolves and validates a content-addressed cache destination.
    ///
    /// @param artifact selected artifact.
    /// @param fileName source file name.
    /// @return safe cache destination.
    /// @throws IOException when a cache directory escapes through a symbolic link.
    private Path cacheDestination(UpdateArtifact artifact, String fileName) throws IOException {
        Path updateRoot = cacheDirectory.resolve(CACHE_DIRECTORY_NAME);
        Files.createDirectories(updateRoot);
        Path realRoot = updateRoot.toRealPath();
        Path artifactDirectory = realRoot.resolve(artifact.sha256());
        Files.createDirectories(artifactDirectory);
        Path realArtifactDirectory = artifactDirectory.toRealPath();
        if (!realArtifactDirectory.startsWith(realRoot)) {
            throw new IOException("Update cache directory escapes the application cache: " + artifactDirectory);
        }
        return realArtifactDirectory.resolve(fileName);
    }

    /// Copies one package while computing and validating its SHA-256 digest.
    ///
    /// @param source      real source file.
    /// @param destination temporary cache file.
    /// @param artifact    expected package metadata.
    /// @param progress    progress callback.
    /// @throws IOException when copying or verification fails.
    private static void copyAndVerify(
            Path source,
            Path destination,
            UpdateArtifact artifact,
            Consumer<UpdateProgress> progress) throws IOException {
        MessageDigest digest = sha256();
        long copied = 0L;
        progress.accept(new UpdateProgress(0L, artifact.size()));
        try (InputStream input = Files.newInputStream(source);
             OutputStream output = Files.newOutputStream(destination)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count == 0) {
                    continue;
                }
                copied += count;
                if (copied > artifact.size()) {
                    throw new IOException("Update artifact exceeds the size declared by the manifest: " + source);
                }
                digest.update(buffer, 0, count);
                output.write(buffer, 0, count);
                progress.accept(new UpdateProgress(copied, artifact.size()));
            }
        }
        if (copied != artifact.size()) {
            throw new IOException("Update artifact size changed while it was being read: " + source);
        }
        byte[] expectedDigest = HexFormat.of().parseHex(artifact.sha256());
        if (!MessageDigest.isEqual(digest.digest(), expectedDigest)) {
            throw new IOException("Update artifact SHA-256 does not match the manifest: " + source);
        }
    }

    /// Verifies an existing cached package.
    ///
    /// @param file     cached package file.
    /// @param artifact expected package metadata.
    /// @return whether size and SHA-256 match.
    /// @throws IOException when the file cannot be read.
    static boolean verifyFile(Path file, UpdateArtifact artifact) throws IOException {
        if (Files.size(file) != artifact.size()) {
            return false;
        }
        MessageDigest digest = sha256();
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) {
                    digest.update(buffer, 0, count);
                }
            }
        }
        byte[] expected = HexFormat.of().parseHex(artifact.sha256());
        return MessageDigest.isEqual(digest.digest(), expected);
    }

    /// Creates a SHA-256 message digest.
    ///
    /// @return SHA-256 digest.
    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    /// Moves a completed package into place, preferring an atomic replacement.
    ///
    /// @param source temporary package file.
    /// @param target final cache path.
    /// @throws IOException when the move fails.
    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}

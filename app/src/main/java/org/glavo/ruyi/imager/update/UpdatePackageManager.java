// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.update;

import org.glavo.ruyi.imager.core.AppDirectories;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.function.Consumer;

/// Retrieves local or HTTPS installers into a SHA-256-verified application cache.
@NotNullByDefault
public final class UpdatePackageManager {
    /// Update package cache directory name.
    private static final String CACHE_DIRECTORY_NAME = "updates";

    /// Copy buffer size.
    private static final int BUFFER_SIZE = 128 * 1024;

    /// Manifest location used to resolve installer references.
    private final UpdateSource manifest;

    /// Application cache directory.
    private final Path cacheDirectory;

    /// Installed build used to evaluate installation requirements.
    private final BuildInfo current;

    /// Local platform capabilities and installer preferences.
    private final UpdateTarget target;

    /// Creates an update package manager.
    ///
    /// @param manifest       source update manifest path.
    /// @param cacheDirectory application cache directory.
    /// @param platform       current update platform.
    public UpdatePackageManager(Path manifest, Path cacheDirectory, UpdatePlatform platform) {
        this(UpdateSource.of(manifest), cacheDirectory, BuildInfo.current(),
                new UpdateTarget(platform, platform == UpdatePlatform.current() ? UpdateTarget.systemVersion(platform) : "",
                        Arrays.stream(UpdatePackageType.values()).filter(platform::supports).toList()));
    }

    /// Creates a manager with the same eligibility context used by an update checker.
    ///
    /// @param manifest manifest location.
    /// @param cacheDirectory application cache directory.
    /// @param current installed application version.
    /// @param target supported platform and installer types.
    public UpdatePackageManager(UpdateSource manifest, Path cacheDirectory, BuildInfo current, UpdateTarget target) {
        this.manifest = manifest;
        this.cacheDirectory = cacheDirectory.toAbsolutePath().normalize();
        this.current = current;
        this.target = target;
    }

    /// Creates a package manager for the current runtime.
    ///
    /// @param directories application directories.
    /// @param checker     configured update checker.
    /// @return package manager.
    public static UpdatePackageManager createDefault(AppDirectories directories, UpdateChecker checker) {
        return new UpdatePackageManager(checker.source(), directories.cacheDirectory(), checker.current(), checker.target());
    }

    /// Returns the preferred compatible artifact, or null when none satisfies this target.
    ///
    /// @param release selected update release.
    /// @return matching artifact, or null.
    public @Nullable UpdateArtifact artifactFor(UpdateRelease release) {
        return target.select(release, current);
    }

    /// Downloads or copies the preferred compatible installer and verifies its size and SHA-256.
    /// A verified cached package can be reused without contacting its source. Failed transfers
    /// attempt to remove their temporary file, preserving cleanup failures as suppressed exceptions.
    /// Interruption is reported as [InterruptedIOException]. Progress callbacks run on the calling
    /// thread and report transferred bytes, not completion of integrity verification.
    ///
    /// @param release  selected update release.
    /// @param progress progress callback.
    /// @return verified prepared update.
    /// @throws IOException when no compatible artifact exists, retrieval or cache access fails, or verification fails.
    /// @throws RuntimeException when the progress callback throws a runtime exception; it is propagated unchanged.
    public PreparedUpdate prepare(UpdateRelease release, Consumer<UpdateProgress> progress) throws IOException {
        @Nullable UpdateArtifact artifact = artifactFor(release);
        if (artifact == null) {
            throw new IOException("No compatible update installer is available for platform: " + target.platform().id());
        }

        checkInterrupted();
        Path destination = cacheDestination(artifact, "installer" + artifact.packageType().fileSuffix());
        if (Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)) {
            if (verifyFile(destination, artifact)) {
                progress.accept(new UpdateProgress(artifact.size(), artifact.size()));
                checkInterrupted();
                return new PreparedUpdate(release, artifact, destination);
            }
            Files.delete(destination);
        } else if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Update cache destination is not a regular file: " + destination);
        }

        Path temporary = Files.createTempFile(destination.getParent(), destination.getFileName() + ".", ".part");
        try {
            URI source = manifest.resolve(artifact.source());
            if ("file".equalsIgnoreCase(source.getScheme())) {
                copyAndVerify(Path.of(source), temporary, artifact, progress);
            } else {
                UpdateTransport.systemDefault().download(source, temporary, artifact.size(), progress);
                if (!verifyFile(temporary, artifact)) {
                    throw new IOException("Downloaded update does not match its declared size and SHA-256: " + source);
                }
            }
            checkInterrupted();
            moveAtomically(temporary, destination);
        } catch (IOException | RuntimeException | Error failure) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
        if (!verifyFile(destination, artifact)) {
            Files.deleteIfExists(destination);
            throw new IOException("Cached update artifact failed verification after installation: " + destination);
        }
        return new PreparedUpdate(release, artifact, destination);
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
                checkInterrupted();
                if (count == 0) {
                    continue;
                }
                if (count > artifact.size() - copied) {
                    throw new IOException("Update artifact exceeds the size declared by the manifest: " + source);
                }
                copied += count;
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
        checkInterrupted();
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) != artifact.size()) {
            return false;
        }
        MessageDigest digest = sha256();
        long total = 0L;
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                checkInterrupted();
                if (count > artifact.size() - total) {
                    return false;
                }
                total += count;
                if (count > 0) {
                    digest.update(buffer, 0, count);
                }
            }
        }
        byte[] expected = HexFormat.of().parseHex(artifact.sha256());
        return total == artifact.size() && MessageDigest.isEqual(digest.digest(), expected);
    }

    /// Preserves thread interruption while aborting package reads and publication.
    ///
    /// @throws InterruptedIOException when cancellation was requested.
    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Update package preparation interrupted.");
        }
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

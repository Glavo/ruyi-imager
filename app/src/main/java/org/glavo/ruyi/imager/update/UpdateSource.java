// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.update;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

/// Locates an update manifest in a local file or at an HTTPS endpoint.
///
/// @param uri absolute file or HTTPS URI without credentials or a fragment.
@NotNullByDefault
public record UpdateSource(URI uri) {
    /// Maximum manifest size accepted before parsing.
    private static final int MAX_MANIFEST_BYTES = 1024 * 1024;

    /// Validates and normalizes the manifest location.
    public UpdateSource {
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            uri = Path.of(uri).toAbsolutePath().normalize().toUri();
        } else {
            requireHttps(uri);
            uri = uri.normalize();
        }
    }

    /// Creates a source from a local path.
    ///
    /// @param path manifest file.
    /// @return normalized local source.
    public static UpdateSource of(Path path) {
        return new UpdateSource(path.toAbsolutePath().normalize().toUri());
    }

    /// Parses a local path, file URI, or HTTPS URL, including Windows drive paths.
    ///
    /// @param value source location.
    /// @return parsed source.
    /// @throws IllegalArgumentException when the location uses an unsupported URI scheme.
    public static UpdateSource parse(String value) {
        if (value.isBlank()) {
            throw new IllegalArgumentException("Update source must not be blank.");
        }
        if (value.matches("^[A-Za-z]:[\\\\/].*") || !value.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*")) {
            return of(Path.of(value));
        }
        return new UpdateSource(URI.create(value));
    }

    /// Returns whether this source is a local file.
    public boolean isLocal() {
        return "file".equalsIgnoreCase(uri.getScheme());
    }

    /// Returns whether an automatic check can be attempted without a missing local file.
    /// Remote availability is determined only when the request is made.
    public boolean isAvailable() {
        return !isLocal() || Files.isRegularFile(Path.of(uri));
    }

    /// Reads a bounded manifest, honoring interruption before local reads.
    ///
    /// @return complete manifest bytes.
    /// @throws IOException when reading fails, is interrupted, or exceeds the size limit.
    byte[] read() throws IOException {
        if (!isLocal()) {
            return UpdateTransport.systemDefault().read(uri, MAX_MANIFEST_BYTES);
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Update manifest read interrupted.");
        }
        Path path = Path.of(uri);
        if (!Files.isRegularFile(path) || Files.size(path) > MAX_MANIFEST_BYTES) {
            throw new IOException("Update manifest is not a bounded regular file: " + path);
        }
        try (InputStream input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(MAX_MANIFEST_BYTES + 1);
            if (bytes.length > MAX_MANIFEST_BYTES) {
                throw new IOException("Update manifest exceeds the size limit: " + path);
            }
            return bytes;
        }
    }

    /// Resolves an artifact against the configured manifest location.
    /// Local relative paths must remain inside the real manifest directory. Remote references
    /// resolve against this URI, not the final redirect URL, and must remain HTTPS.
    ///
    /// @param source relative artifact reference or absolute HTTPS URL.
    /// @return real local file URI or resolved HTTPS URL.
    /// @throws IOException when the reference is invalid or escapes the local directory.
    URI resolve(String source) throws IOException {
        try {
            if (source.regionMatches(true, 0, "https:", 0, 6)) {
                URI remote = URI.create(source);
                requireHttps(remote);
                return remote;
            }
            if (!isLocal()) {
                URI resolved = uri.resolve(URI.create(source));
                requireHttps(resolved);
                return resolved;
            }
            @Nullable Path root = Path.of(uri).toRealPath().getParent();
            if (root == null) {
                throw new IOException("Update manifest has no parent directory: " + uri);
            }
            Path relative = Path.of(source);
            Path candidate = root.resolve(relative).normalize();
            if (relative.isAbsolute() || !candidate.startsWith(root)) {
                throw new IOException("Update artifact source escapes the manifest directory: " + source);
            }
            Path real = candidate.toRealPath();
            if (!real.startsWith(root) || !Files.isRegularFile(real)) {
                throw new IOException("Update artifact is not a regular file inside the manifest directory: " + source);
            }
            return real.toUri();
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid update artifact source: " + source, exception);
        }
    }

    /// Rejects remote locations that are not absolute HTTPS URLs.
    ///
    /// @param uri candidate URL.
    /// @throws IllegalArgumentException when the URL is unsupported or contains credentials or a fragment.
    private static void requireHttps(URI uri) {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getFragment() != null
                || uri.getPort() == 0 || uri.getPort() > 65535) {
            throw new IllegalArgumentException("Update URLs must use HTTPS with a valid host and port, without credentials or fragments.");
        }
    }

    /// Returns a local display path or the remote manifest URL.
    @Override
    public String toString() {
        return isLocal() ? Path.of(uri).toString() : uri.toString();
    }
}

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.repo;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

/// Configured Ruyi metadata repository.
///
/// @param id stable repository identifier.
/// @param name human-readable repository name.
/// @param remote Git remote URL, or null for local-only repositories.
/// @param branch Git branch to track.
/// @param localPath local checkout override, or null for the default cache path.
/// @param priority overlay priority; higher values shadow lower values.
/// @param active whether the repository is enabled.
@NotNullByDefault
public record RuyiRepositoryEntry(
        String id,
        String name,
        @Nullable String remote,
        String branch,
        @Nullable Path localPath,
        int priority,
        boolean active) {
    /// Resolves the local repository root.
    ///
    /// @param cacheDirectory application cache directory.
    /// @return local repository root.
    public Path resolveRoot(Path cacheDirectory) {
        Path path = localPath;
        if (path != null) {
            return path;
        }
        return cacheDirectory.resolve("repos").resolve(id);
    }
}

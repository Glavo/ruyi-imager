// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.repo;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.Path;
import java.util.List;

/// Configured Ruyi metadata repository.
///
/// @param id stable repository identifier.
/// @param name human-readable repository name.
/// @param remotes ordered Git remote URLs; an empty list denotes a local-only repository.
/// @param branch Git branch to track.
/// @param localPath local checkout override, or null for the default cache path.
/// @param priority overlay priority; higher values shadow lower values.
/// @param active whether the repository is enabled.
@NotNullByDefault
public record RuyiRepositoryEntry(
        String id,
        String name,
        @Unmodifiable List<String> remotes,
        String branch,
        @Nullable Path localPath,
        int priority,
        boolean active) {
    /// Creates a repository entry with an immutable remote list.
    public RuyiRepositoryEntry {
        remotes = List.copyOf(remotes);
    }

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

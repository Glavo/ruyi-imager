// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.image;

import org.glavo.ruyi.imager.core.StrategySupport;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.Map;

/// Image entry derived from Ruyi package metadata.
///
/// @param repoId source repository id.
/// @param category Ruyi package category.
/// @param name Ruyi package name.
/// @param version Ruyi package version.
/// @param slug deprecated Ruyi package slug when present.
/// @param atom exact Ruyi atom name.
/// @param displayName human-readable image name.
/// @param board target board name.
/// @param variant board or image variant name.
/// @param strategy Ruyi provisioning strategy name.
/// @param partitionMap image partition map.
/// @param distfiles distfiles required by the image.
/// @param support strategy support state.
@NotNullByDefault
public record ImageEntry(
        String repoId,
        String category,
        String name,
        String version,
        @Nullable String slug,
        String atom,
        String displayName,
        String board,
        String variant,
        String strategy,
        @Unmodifiable Map<String, String> partitionMap,
        @Unmodifiable List<RuyiDistfile> distfiles,
        StrategySupport support) {
    /// Copies collections into immutable instances.
    public ImageEntry {
        partitionMap = Map.copyOf(partitionMap);
        distfiles = List.copyOf(distfiles);
    }
}

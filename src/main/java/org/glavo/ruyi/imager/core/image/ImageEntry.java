// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.image;

import org.glavo.ruyi.imager.core.StrategySupport;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.net.URI;

/// Image entry derived from Ruyi package metadata.
///
/// @param atom Ruyi atom name.
/// @param displayName human-readable image name.
/// @param board target board name.
/// @param variant board or image variant name.
/// @param sourceUri source URI for the image artifact.
/// @param strategy Ruyi provisioning strategy name.
/// @param sizeBytes expected artifact size when known.
/// @param checksum expected checksum when known.
/// @param support strategy support state.
@NotNullByDefault
public record ImageEntry(
        String atom,
        String displayName,
        String board,
        String variant,
        URI sourceUri,
        String strategy,
        @Nullable Long sizeBytes,
        @Nullable String checksum,
        StrategySupport support) {
}

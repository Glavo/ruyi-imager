// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.image;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Lightweight cache status for one catalog image.
///
/// @param state overall cache state.
/// @param cachedDistfiles number of distfiles present in the local cache.
/// @param totalDistfiles total number of distfiles required by the image.
/// @param cachedBytes bytes currently present in complete or partial cached files.
/// @param totalBytes total expected bytes, or null when any distfile size is unknown.
@NotNullByDefault
public record ImageCacheStatus(
        State state,
        int cachedDistfiles,
        int totalDistfiles,
        long cachedBytes,
        @Nullable Long totalBytes) {
    /// Validates cache status counters.
    public ImageCacheStatus {
        if (cachedDistfiles < 0 || totalDistfiles < 0 || cachedDistfiles > totalDistfiles) {
            throw new IllegalArgumentException("Invalid distfile cache counters.");
        }
        if (cachedBytes < 0L) {
            throw new IllegalArgumentException("Invalid cached byte count.");
        }
    }

    /// Creates an unknown cache status.
    ///
    /// @param totalDistfiles total number of distfiles required by the image.
    /// @return unknown cache status.
    public static ImageCacheStatus unknown(int totalDistfiles) {
        return new ImageCacheStatus(State.UNKNOWN, 0, totalDistfiles, 0L, null);
    }

    /// Image cache state.
    @NotNullByDefault
    public enum State {
        /// All distfiles appear to be present in the local cache.
        COMPLETE,

        /// Some complete or partial distfile data appears to be present.
        PARTIAL,

        /// No complete or partial distfile data appears to be present.
        EMPTY,

        /// At least one missing distfile requires manual download.
        MANUAL_REQUIRED,

        /// The cache state could not be determined.
        UNKNOWN
    }
}

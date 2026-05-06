// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Progress event emitted by long-running services.
///
/// @param stage stable stage identifier.
/// @param message human-readable progress message.
/// @param currentBytes current byte count when available.
/// @param totalBytes total byte count when available.
@NotNullByDefault
public record ProgressEvent(
        String stage,
        String message,
        @Nullable Long currentBytes,
        @Nullable Long totalBytes) {
    /// Creates an indeterminate progress event.
    ///
    /// @param stage stable stage identifier.
    /// @param message human-readable progress message.
    /// @return progress event without byte counters.
    public static ProgressEvent indeterminate(String stage, String message) {
        return new ProgressEvent(stage, message, null, null);
    }
}

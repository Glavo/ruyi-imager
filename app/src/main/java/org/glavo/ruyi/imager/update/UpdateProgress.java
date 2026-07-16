// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.update;

import org.jetbrains.annotations.NotNullByDefault;

/// Reports progress while an update package is copied into the verified cache.
///
/// @param currentBytes copied byte count.
/// @param totalBytes   expected package size.
@NotNullByDefault
public record UpdateProgress(long currentBytes, long totalBytes) {
    /// Validates progress bounds.
    public UpdateProgress {
        if (currentBytes < 0L || totalBytes <= 0L || currentBytes > totalBytes) {
            throw new IllegalArgumentException("Invalid update package progress.");
        }
    }
}

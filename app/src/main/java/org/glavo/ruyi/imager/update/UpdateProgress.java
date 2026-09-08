// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.update;

import org.jetbrains.annotations.NotNullByDefault;

/// Reports bytes transferred while preparing an update package for verification.
///
/// @param currentBytes transferred byte count.
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

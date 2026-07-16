// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.update;

import org.jetbrains.annotations.NotNullByDefault;

/// Reports whether a manifest describes a build newer than the running application.
///
/// @param status    update comparison status.
/// @param current   running application build.
/// @param available build described by the update manifest.
@NotNullByDefault
public record UpdateCheckResult(Status status, BuildInfo current, UpdateManifest available) {
    /// Update comparison outcomes.
    @NotNullByDefault
    public enum Status {
        /// The manifest describes a newer build.
        UPDATE_AVAILABLE,

        /// The running build is at least as new as the manifest build.
        UP_TO_DATE
    }
}

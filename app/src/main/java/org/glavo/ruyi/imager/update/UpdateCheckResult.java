// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.update;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Reports whether a manifest describes a build newer than the running application.
///
/// @param status    update comparison status.
/// @param current   running application build.
/// @param available selected newer release, or null when no newer release exists.
/// @param artifact compatible installer, present only when an installable update was selected.
@NotNullByDefault
public record UpdateCheckResult(Status status, BuildInfo current,
                                @Nullable UpdateRelease available, @Nullable UpdateArtifact artifact) {
    /// Enforces the relationship between status and selected release and artifact.
    public UpdateCheckResult {
        if ((status == Status.UP_TO_DATE) != (available == null)
                || (status == Status.UPDATE_AVAILABLE) != (artifact != null)
                || (artifact != null && (available == null || !available.artifacts().contains(artifact)))) {
            throw new IllegalArgumentException("Inconsistent update selection result.");
        }
    }

    /// Update comparison outcomes.
    @NotNullByDefault
    public enum Status {
        /// A newer compatible build and installer are available.
        UPDATE_AVAILABLE,

        /// Newer releases exist, but none has a compatible installer for this client.
        NO_COMPATIBLE_UPDATE,

        /// No newer build exists in the requested channel.
        UP_TO_DATE
    }
}

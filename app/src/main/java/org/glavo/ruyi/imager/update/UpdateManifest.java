// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.update;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Describes the newest application build exposed by an update source.
///
/// @param schemaVersion update manifest schema version.
/// @param version       human-readable application version.
/// @param buildNumber   monotonically increasing build number.
/// @param releaseNotes  optional short release notes.
@NotNullByDefault
public record UpdateManifest(
        int schemaVersion,
        String version,
        long buildNumber,
        @Nullable String releaseNotes) {
    /// Supported manifest schema version.
    public static final int CURRENT_SCHEMA_VERSION = 1;

    /// Validates the update manifest.
    public UpdateManifest {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported update manifest schema version: " + schemaVersion);
        }
        if (version.isBlank()) {
            throw new IllegalArgumentException("Update version must not be blank.");
        }
        if (buildNumber < 0L) {
            throw new IllegalArgumentException("Update build number must not be negative.");
        }
        SemanticVersion.parse(version);
    }
}

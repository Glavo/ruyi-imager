// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.update;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/// Describes application releases exposed by an update source.
///
/// @param schemaVersion update manifest schema version.
/// @param releases      releases available from all update channels.
@NotNullByDefault
public record UpdateManifest(
        int schemaVersion,
        @Unmodifiable List<UpdateRelease> releases) {
    /// Supported manifest schema version.
    public static final int CURRENT_SCHEMA_VERSION = 1;

    /// Validates the update manifest.
    public UpdateManifest {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported update manifest schema version: " + schemaVersion);
        }
        releases = List.copyOf(releases);
        if (releases.isEmpty()) {
            throw new IllegalArgumentException("Update manifest must contain at least one release.");
        }
        Set<ReleaseIdentity> identities = new HashSet<>();
        for (UpdateRelease release : releases) {
            ReleaseIdentity identity = new ReleaseIdentity(
                    release.channel(),
                    SemanticVersion.parse(release.version()),
                    release.buildNumber());
            if (!identities.add(identity)) {
                throw new IllegalArgumentException(
                        "Update manifest contains ambiguous releases for channel: " + release.channel().token());
            }
        }
    }

    /// Semantic release identity used to reject manifest entries with equal update precedence.
    ///
    /// @param channel     release channel.
    /// @param version     semantic version precedence.
    /// @param buildNumber build number used after equal semantic precedence.
    @NotNullByDefault
    private record ReleaseIdentity(
            UpdateChannel channel,
            SemanticVersion version,
            long buildNumber) {
    }
}

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.update;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/// Describes one application release exposed by an update manifest.
///
/// @param channel      release channel.
/// @param version      semantic application version and update identity.
/// @param releaseNotes optional short release notes.
/// @param artifacts    platform installer artifacts.
@NotNullByDefault
public record UpdateRelease(
        UpdateChannel channel,
        String version,
        @Nullable String releaseNotes,
        @Unmodifiable List<UpdateArtifact> artifacts) {
    /// Validates and freezes release metadata.
    public UpdateRelease {
        version = version.strip();
        if (version.isEmpty()) {
            throw new IllegalArgumentException("Update version must not be blank.");
        }
        SemanticVersion.parse(version);
        artifacts = List.copyOf(artifacts);
        Set<UpdatePlatform> platforms = new HashSet<>();
        for (UpdateArtifact artifact : artifacts) {
            if (!platforms.add(artifact.platform())) {
                throw new IllegalArgumentException(
                        "Update release contains multiple artifacts for platform: " + artifact.platform().id());
            }
        }
    }

    /// Returns the installer artifact for a platform, or null when none is published.
    ///
    /// @param platform target runtime platform.
    /// @return matching installer artifact, or null.
    public @Nullable UpdateArtifact artifactFor(UpdatePlatform platform) {
        for (UpdateArtifact artifact : artifacts) {
            if (artifact.platform() == platform) {
                return artifact;
            }
        }
        return null;
    }
}

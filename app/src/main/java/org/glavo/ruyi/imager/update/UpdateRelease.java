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
/// @param version      application version and update identity.
/// @param releaseNotes optional short release notes.
/// @param artifacts    platform installer artifacts.
/// @param requirements conditions shared by every artifact in the release.
@NotNullByDefault
public record UpdateRelease(
        UpdateChannel channel,
        String version,
        @Nullable String releaseNotes,
        @Unmodifiable List<UpdateArtifact> artifacts,
        UpdateRequirements requirements) {
    /// Creates a release without additional installation requirements.
    ///
    /// @param channel update channel.
    /// @param version application version.
    /// @param releaseNotes optional release notes.
    /// @param artifacts platform installers.
    public UpdateRelease(UpdateChannel channel, String version, @Nullable String releaseNotes,
                         @Unmodifiable List<UpdateArtifact> artifacts) {
        this(channel, version, releaseNotes, artifacts, UpdateRequirements.NONE);
    }

    /// Validates and freezes release metadata.
    public UpdateRelease {
        if (version.isEmpty()) {
            throw new IllegalArgumentException("Update version must not be blank.");
        }
        ApplicationVersion.parse(version);
        artifacts = List.copyOf(artifacts);
        Set<String> variants = new HashSet<>();
        for (UpdateArtifact artifact : artifacts) {
            if (!variants.add(artifact.platform().id() + ":" + artifact.packageType().token())) {
                throw new IllegalArgumentException(
                        "Update release contains duplicate installer variants for platform: " + artifact.platform().id());
            }
        }
    }

}

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.update;

import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.Path;

/// Identifies a verified installer package ready for platform handoff.
///
/// @param release     selected application release.
/// @param artifact    selected platform artifact.
/// @param packageFile verified cached package file.
@NotNullByDefault
public record PreparedUpdate(UpdateRelease release, UpdateArtifact artifact, Path packageFile) {
    /// Normalizes the cached package path.
    public PreparedUpdate {
        packageFile = packageFile.toAbsolutePath().normalize();
    }
}

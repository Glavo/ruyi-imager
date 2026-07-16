// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.update;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Locale;

/// Describes one platform installer artifact in an update manifest.
///
/// @param platform    target runtime platform.
/// @param packageType installer package type.
/// @param source      relative local source path resolved from the manifest directory.
/// @param size        expected package size in bytes.
/// @param sha256      expected hexadecimal SHA-256 digest.
@NotNullByDefault
public record UpdateArtifact(
        UpdatePlatform platform,
        UpdatePackageType packageType,
        String source,
        long size,
        String sha256) {
    /// Validates and normalizes an update artifact.
    public UpdateArtifact {
        source = source.strip();
        sha256 = sha256.strip().toLowerCase(Locale.ROOT);
        if (source.isEmpty()) {
            throw new IllegalArgumentException("Update artifact source must not be blank.");
        }
        if (source.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Update artifact source contains a NUL character.");
        }
        if (size <= 0L) {
            throw new IllegalArgumentException("Update artifact size must be positive.");
        }
        if (!sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Update artifact SHA-256 must contain 64 hexadecimal characters.");
        }
        if (!platform.supports(packageType)) {
            throw new IllegalArgumentException(
                    "Update package type " + packageType.token() + " is not valid for " + platform.id() + '.');
        }
    }
}

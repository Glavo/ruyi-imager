// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.image;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.net.URI;
import java.util.List;
import java.util.Map;

/// Distfile declaration used by a Ruyi image package.
///
/// @param name distfile name.
/// @param sourceUris concrete HTTP or HTTPS source URIs.
/// @param sizeBytes expected size in bytes, or null when absent.
/// @param checksums expected checksums by algorithm.
/// @param fetchRestricted whether automatic fetching is restricted.
/// @param mirrorRestricted whether default mirrors are disabled.
/// @param fetchRestriction manual fetch instructions.
/// @param stripComponents archive path component count to strip.
/// @param prefixesToUnpack archive path prefixes to extract for tar-based distfiles.
/// @param unpack requested unpack method, or null for auto.
@NotNullByDefault
public record RuyiDistfile(
        String name,
        @Unmodifiable List<URI> sourceUris,
        @Nullable Long sizeBytes,
        @Unmodifiable Map<String, String> checksums,
        boolean fetchRestricted,
        boolean mirrorRestricted,
        @Nullable RuyiFetchRestriction fetchRestriction,
        int stripComponents,
        @Unmodifiable List<String> prefixesToUnpack,
        @Nullable String unpack) {
    /// Copies collections into immutable instances.
    public RuyiDistfile {
        sourceUris = List.copyOf(sourceUris);
        checksums = Map.copyOf(checksums);
        prefixesToUnpack = List.copyOf(prefixesToUnpack);
    }
}

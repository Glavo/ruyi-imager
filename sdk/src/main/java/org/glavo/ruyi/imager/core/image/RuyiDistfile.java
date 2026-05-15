// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.image;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.net.URI;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
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
        name = validateName(name);
        sourceUris = List.copyOf(sourceUris);
        checksums = Map.copyOf(checksums);
        prefixesToUnpack = List.copyOf(prefixesToUnpack);
    }

    /// Validates that a distfile name is a single safe cache file name.
    ///
    /// @param value distfile name from repository metadata.
    /// @return validated distfile name.
    private static String validateName(String value) {
        if (value.isBlank()) {
            throw new IllegalArgumentException("Distfile name must not be blank.");
        }
        if (value.indexOf('/') >= 0 || value.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("Distfile name must not contain path separators: " + value);
        }
        if (value.indexOf(':') >= 0 || ".".equals(value) || "..".equals(value)) {
            throw new IllegalArgumentException("Distfile name is not safe: " + value);
        }

        try {
            Path path = Path.of(value);
            if (path.isAbsolute() || path.getNameCount() != 1) {
                throw new IllegalArgumentException("Distfile name is not relative: " + value);
            }
        } catch (InvalidPathException exception) {
            throw new IllegalArgumentException("Distfile name is not a valid file name: " + value, exception);
        }
        return value;
    }
}

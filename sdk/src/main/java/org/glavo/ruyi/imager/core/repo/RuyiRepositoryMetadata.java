// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.repo;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/// Metadata read from a Ruyi repository `config.toml`.
///
/// @param id repository identifier.
/// @param name human-readable repository name.
/// @param root local repository root.
/// @param mirrors mirror base URLs by mirror id.
@NotNullByDefault
public record RuyiRepositoryMetadata(
        String id,
        String name,
        Path root,
        @Unmodifiable Map<String, @Unmodifiable List<String>> mirrors) {
    /// Creates an immutable metadata instance.
    public RuyiRepositoryMetadata {
        mirrors = copyMirrors(mirrors);
    }

    /// Resolves download URLs for one distfile declaration.
    ///
    /// @param distfileName distfile file name.
    /// @param declaredUrls URLs declared in the package manifest.
    /// @param mirrorRestricted whether default mirror URLs should be skipped.
    /// @return resolved concrete HTTP or HTTPS URLs.
    public @Unmodifiable List<URI> resolveDistfileUrls(
            String distfileName,
            @Unmodifiable List<String> declaredUrls,
            boolean mirrorRestricted) {
        ArrayList<URI> result = new ArrayList<>();
        if (!mirrorRestricted) {
            addExpandedUrl(result, "mirror://ruyi-dist/" + distfileName);
        }

        for (String declaredUrl : declaredUrls) {
            addExpandedUrl(result, declaredUrl);
        }
        return List.copyOf(result);
    }

    /// Copies mirror declarations into immutable collections.
    ///
    /// @param mirrors mirror declarations.
    /// @return immutable mirror declarations.
    private static @Unmodifiable Map<String, @Unmodifiable List<String>> copyMirrors(
            @Unmodifiable Map<String, @Unmodifiable List<String>> mirrors) {
        return mirrors.entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        entry -> List.copyOf(entry.getValue())));
    }

    /// Expands one manifest URL into concrete URLs.
    ///
    /// @param result mutable result list.
    /// @param value manifest URL value.
    private void addExpandedUrl(ArrayList<URI> result, String value) {
        URI uri = URI.create(value);
        @Nullable String scheme = uri.getScheme();
        if (scheme == null || scheme.isEmpty()) {
            addMirrorUrls(result, "ruyi-dist", value);
            return;
        }

        if ("mirror".equals(scheme)) {
            @Nullable String mirrorId = uri.getAuthority();
            if (mirrorId == null || mirrorId.isBlank()) {
                return;
            }
            @Nullable String path = uri.getPath();
            addMirrorUrls(result, mirrorId, path == null ? "" : path);
            return;
        }

        if ("http".equals(scheme) || "https".equals(scheme)) {
            result.add(uri);
        }
    }

    /// Expands a mirror-relative path.
    ///
    /// @param result mutable result list.
    /// @param mirrorId mirror identifier.
    /// @param path mirror-relative path.
    private void addMirrorUrls(ArrayList<URI> result, String mirrorId, String path) {
        @Nullable List<String> bases = mirrors.get(mirrorId);
        if (bases == null) {
            return;
        }

        String normalizedPath = path.startsWith("/") ? path.substring(1) : path;
        for (String base : bases) {
            result.add(URI.create(joinUrl(base, normalizedPath)));
        }
    }

    /// Joins a base URL and a relative path.
    ///
    /// @param base base URL.
    /// @param path relative path.
    /// @return joined URL.
    private static String joinUrl(String base, String path) {
        String normalizedBase = base.endsWith("/") ? base : base + "/";
        return URI.create(normalizedBase).resolve(path).toString();
    }
}

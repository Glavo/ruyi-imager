// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.image;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// Provisionable package component inside an image entry.
///
/// @param category Ruyi package category.
/// @param name Ruyi package name.
/// @param version Ruyi package version.
/// @param atom exact Ruyi atom name.
/// @param strategy Ruyi provisioning strategy name.
/// @param partitionMap component partition map.
/// @param distfiles distfiles required by this component.
@NotNullByDefault
public record ImageComponent(
        String category,
        String name,
        String version,
        String atom,
        String strategy,
        @Unmodifiable Map<String, String> partitionMap,
        @Unmodifiable List<RuyiDistfile> distfiles) {
    /// Copies collections into immutable instances.
    public ImageComponent {
        partitionMap = Collections.unmodifiableMap(new LinkedHashMap<>(partitionMap));
        distfiles = List.copyOf(distfiles);
    }
}

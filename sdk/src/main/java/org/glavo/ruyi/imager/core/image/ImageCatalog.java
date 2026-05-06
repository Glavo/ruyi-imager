// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.image;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;

/// Immutable image catalog snapshot.
///
/// @param images available images.
@NotNullByDefault
public record ImageCatalog(@Unmodifiable List<ImageEntry> images) {
    /// Copies catalog content into an immutable list.
    public ImageCatalog {
        images = List.copyOf(images);
    }
}

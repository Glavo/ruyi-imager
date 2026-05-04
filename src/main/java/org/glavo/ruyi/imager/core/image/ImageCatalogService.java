// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.image;

import org.glavo.ruyi.imager.core.ProgressReporter;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;

/// Reads image metadata and downloads image artifacts.
@NotNullByDefault
public interface ImageCatalogService {
    /// Lists images from the local metadata cache.
    ///
    /// @return image catalog snapshot.
    /// @throws IOException when local metadata cannot be read.
    ImageCatalog listImages() throws IOException;

    /// Finds one image by atom name.
    ///
    /// @param atom image atom name.
    /// @return matching image, or null when not found.
    /// @throws IOException when local metadata cannot be read.
    default @Nullable ImageEntry findImage(String atom) throws IOException {
        return RuyiImageSelector.find(listImages(), atom);
    }

    /// Returns a lightweight cache status for one image.
    ///
    /// @param image image to inspect.
    /// @return image cache status.
    /// @throws IOException when the cache cannot be inspected.
    default ImageCacheStatus cacheStatus(ImageEntry image) throws IOException {
        return ImageCacheStatus.unknown(image.distfiles().size());
    }

    /// Downloads an image artifact.
    ///
    /// @param image image to download.
    /// @param reporter progress reporter.
    /// @return local image path.
    /// @throws IOException when download or validation fails.
    Path downloadImage(ImageEntry image, ProgressReporter reporter) throws IOException;
}

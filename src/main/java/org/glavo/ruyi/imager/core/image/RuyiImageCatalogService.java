// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.image;

import org.glavo.ruyi.imager.core.AppDirectories;
import org.glavo.ruyi.imager.core.ProgressEvent;
import org.glavo.ruyi.imager.core.ProgressReporter;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/// Ruyi-backed image catalog service.
@NotNullByDefault
public final class RuyiImageCatalogService implements ImageCatalogService {
    /// Application directories used to find metadata and downloads.
    private final AppDirectories directories;

    /// Creates the catalog service.
    ///
    /// @param directories application directories.
    public RuyiImageCatalogService(AppDirectories directories) {
        this.directories = directories;
    }

    /// Lists images from the local metadata cache.
    ///
    /// @return currently known image catalog.
    @Override
    public ImageCatalog listImages() {
        return new ImageCatalog(List.of());
    }

    /// Downloads a selected image.
    ///
    /// @param image image to download.
    /// @param reporter progress reporter.
    /// @return local image path.
    /// @throws IOException when the download directory cannot be prepared.
    @Override
    public Path downloadImage(ImageEntry image, ProgressReporter reporter) throws IOException {
        Path downloadDirectory = directories.cacheDirectory().resolve("downloads");
        Files.createDirectories(downloadDirectory);
        reporter.report(ProgressEvent.indeterminate("download", "Ruyi image download backend is not implemented yet."));
        throw new UnsupportedOperationException("Ruyi image download backend is not implemented yet.");
    }
}

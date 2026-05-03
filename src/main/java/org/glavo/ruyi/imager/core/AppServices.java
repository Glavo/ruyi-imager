// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core;

import org.glavo.ruyi.imager.core.device.BlockDeviceService;
import org.glavo.ruyi.imager.core.device.PlatformBlockDeviceService;
import org.glavo.ruyi.imager.core.flash.FlashService;
import org.glavo.ruyi.imager.core.flash.LocalFlashService;
import org.glavo.ruyi.imager.core.image.ImageCatalogService;
import org.glavo.ruyi.imager.core.image.RuyiImageCatalogService;
import org.glavo.ruyi.imager.core.repo.RepositoryService;
import org.glavo.ruyi.imager.core.repo.RuyiRepositoryService;
import org.glavo.ruyi.imager.core.repo.RuyiRepositoryStore;
import org.jetbrains.annotations.NotNullByDefault;

/// Shared service graph used by the CLI and JavaFX front end.
///
/// @param directories application filesystem directories.
/// @param repository repository metadata service.
/// @param images image catalog and download service.
/// @param devices target device service.
/// @param flash flash orchestration service.
@NotNullByDefault
public record AppServices(
        AppDirectories directories,
        RepositoryService repository,
        ImageCatalogService images,
        BlockDeviceService devices,
        FlashService flash) {
    /// Creates the default production service graph.
    ///
    /// @return default services.
    public static AppServices createDefault() {
        AppDirectories directories = AppDirectories.defaults();
        RuyiRepositoryStore repositoryStore = new RuyiRepositoryStore(directories);
        RepositoryService repository = new RuyiRepositoryService(repositoryStore);
        ImageCatalogService images = new RuyiImageCatalogService(directories, repositoryStore);
        BlockDeviceService devices = new PlatformBlockDeviceService();
        FlashService flash = new LocalFlashService(images);
        return new AppServices(directories, repository, images, devices, flash);
    }
}

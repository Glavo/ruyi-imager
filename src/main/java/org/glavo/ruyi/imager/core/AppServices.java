// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core;

import org.glavo.ruyi.imager.core.device.BlockDeviceService;
import org.glavo.ruyi.imager.core.device.PlatformBlockDeviceService;
import org.glavo.ruyi.imager.core.device.WindowsBlockDevicePreparer;
import org.glavo.ruyi.imager.core.fastboot.FastbootService;
import org.glavo.ruyi.imager.core.fastboot.ProcessFastbootService;
import org.glavo.ruyi.imager.core.flash.BlockDevicePreparer;
import org.glavo.ruyi.imager.core.flash.FlashService;
import org.glavo.ruyi.imager.core.flash.LocalFlashService;
import org.glavo.ruyi.imager.core.image.ImageCatalogService;
import org.glavo.ruyi.imager.core.image.RuyiImageCatalogService;
import org.glavo.ruyi.imager.core.repo.RepositoryService;
import org.glavo.ruyi.imager.core.repo.RuyiRepositoryService;
import org.glavo.ruyi.imager.core.repo.RuyiRepositoryStore;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Locale;
import java.util.logging.Logger;

/// Shared service graph used by the CLI and JavaFX front end.
///
/// @param directories application filesystem directories.
/// @param repository repository metadata service.
/// @param images image catalog and download service.
/// @param devices target device service.
/// @param fastboot fastboot device and flashing service.
/// @param flash flash orchestration service.
@NotNullByDefault
public record AppServices(
        AppDirectories directories,
        RepositoryService repository,
        ImageCatalogService images,
        BlockDeviceService devices,
        FastbootService fastboot,
        FlashService flash) {
    /// Logger for service graph construction.
    private static final Logger LOGGER = Logger.getLogger(AppServices.class.getName());

    /// Creates the default production service graph.
    ///
    /// @return default services.
    public static AppServices createDefault() {
        LOGGER.info("Creating default application services.");
        AppDirectories directories = AppDirectories.defaults();
        RuyiRepositoryStore repositoryStore = new RuyiRepositoryStore(directories);
        RepositoryService repository = new RuyiRepositoryService(repositoryStore);
        ImageCatalogService images = new RuyiImageCatalogService(directories, repositoryStore);
        BlockDeviceService devices = new PlatformBlockDeviceService();
        FastbootService fastboot = new ProcessFastbootService();
        FlashService flash = new LocalFlashService(images, fastboot, createBlockDevicePreparer());
        LOGGER.info("Default application services created.");
        return new AppServices(directories, repository, images, devices, fastboot, flash);
    }

    /// Creates the platform block-device preparer.
    ///
    /// @return block-device preparer for the current operating system.
    private static BlockDevicePreparer createBlockDevicePreparer() {
        if (isWindows()) {
            return new WindowsBlockDevicePreparer();
        }
        return BlockDevicePreparer.none();
    }

    /// Returns whether the current operating system is Windows.
    ///
    /// @return whether the current operating system is Windows.
    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }
}

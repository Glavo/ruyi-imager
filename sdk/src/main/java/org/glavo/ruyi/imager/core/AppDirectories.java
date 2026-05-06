// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Filesystem locations used by Ruyi Imager.
///
/// @param configDirectory directory for user configuration.
/// @param cacheDirectory directory for downloaded metadata and images.
@NotNullByDefault
public record AppDirectories(Path configDirectory, Path cacheDirectory) {
    /// Logger for application directory resolution.
    private static final Logger LOGGER = LoggerFactory.getLogger(AppDirectories.class);

    /// Creates default application directories for the current platform.
    ///
    /// @return default application directories.
    public static AppDirectories defaults() {
        Path home = Path.of(System.getProperty("user.home"));
        @Nullable String osName = System.getProperty("os.name");
        boolean windows = osName != null && osName.toLowerCase().contains("win");
        AppDirectories directories;
        if (windows) {
            directories = new AppDirectories(
                    windowsBase("APPDATA", home.resolve("AppData").resolve("Roaming")).resolve("RuyiImager"),
                    windowsBase("LOCALAPPDATA", home.resolve("AppData").resolve("Local")).resolve("RuyiImager"));
        } else {
            directories = new AppDirectories(
                    xdgBase("XDG_CONFIG_HOME", home.resolve(".config")).resolve("ruyi-imager"),
                    xdgBase("XDG_CACHE_HOME", home.resolve(".cache")).resolve("ruyi-imager"));
        }
        LOGGER.atInfo().log(() -> "Resolved application directories. config="
                + directories.configDirectory()
                + ", cache="
                + directories.cacheDirectory());
        return directories;
    }

    /// Resolves a Windows known-folder environment variable.
    ///
    /// @param variable environment variable name.
    /// @param fallback fallback path.
    /// @return resolved base path.
    private static Path windowsBase(String variable, Path fallback) {
        @Nullable String value = System.getenv(variable);
        return value == null || value.isBlank() ? fallback : Path.of(value);
    }

    /// Resolves an XDG base directory environment variable.
    ///
    /// @param variable environment variable name.
    /// @param fallback fallback path.
    /// @return resolved base path.
    private static Path xdgBase(String variable, Path fallback) {
        @Nullable String value = System.getenv(variable);
        return value == null || value.isBlank() ? fallback : Path.of(value);
    }
}

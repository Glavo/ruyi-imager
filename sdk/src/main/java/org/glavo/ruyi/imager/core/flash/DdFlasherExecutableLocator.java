// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.flash;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/// Locates the Rust dd-flasher helper executable bundled with application distributions.
@NotNullByDefault
public final class DdFlasherExecutableLocator {
    /// System property used to override dd-flasher executable resolution.
    public static final String EXECUTABLE_PROPERTY = "ruyi.imager.ddFlasher.executable";

    /// Environment variable used to override dd-flasher executable resolution.
    public static final String EXECUTABLE_ENV = "RUYI_IMAGER_DD_FLASHER";

    /// System property used to override application home discovery.
    public static final String APP_HOME_PROPERTY = "ruyi.imager.appHome";

    /// Logger for dd-flasher executable resolution.
    private static final Logger LOGGER = LoggerFactory.getLogger(DdFlasherExecutableLocator.class);

    /// Fallback executable name resolved from PATH.
    private static final String PATH_EXECUTABLE = "dd-flasher";

    /// Prevents construction of the locator utility.
    private DdFlasherExecutableLocator() {
    }

    /// Resolves the best dd-flasher executable for the current process.
    ///
    /// @return bundled dd-flasher path, configured override, or PATH executable name.
    public static String resolve() {
        @Nullable String override = configuredExecutable();
        if (override != null) {
            LOGGER.atInfo().log(() -> "Using configured dd-flasher executable. executable=" + override);
            return override;
        }

        @Nullable Path appHome = appHome();
        if (appHome != null) {
            @Nullable Path bundled = bundledExecutable(
                    appHome,
                    System.getProperty("os.name", ""),
                    System.getProperty("os.arch", ""));
            if (bundled != null) {
                LOGGER.atInfo().log(() -> "Using bundled dd-flasher executable. executable=" + bundled);
                return bundled.toString();
            }
        }

        LOGGER.info("Using dd-flasher executable from PATH.");
        return PATH_EXECUTABLE;
    }

    /// Resolves a bundled dd-flasher executable under an application home.
    ///
    /// @param appHome application home directory.
    /// @param osName operating system name.
    /// @param osArch operating system architecture.
    /// @return bundled dd-flasher path, or null when unsupported or absent.
    static @Nullable Path bundledExecutable(Path appHome, String osName, String osArch) {
        @Nullable DdFlasherPlatform platform = platform(osName, osArch);
        if (platform == null) {
            return null;
        }

        Path candidate = appHome
                .resolve("tools")
                .resolve("dd-flasher")
                .resolve(platform.directory())
                .resolve(platform.executableName());
        if (!Files.isRegularFile(candidate)) {
            return null;
        }
        if (platform.windows() || Files.isExecutable(candidate) || candidate.toFile().setExecutable(true, false)) {
            return candidate;
        }
        return null;
    }

    /// Resolves a dd-flasher platform identifier from operating system properties.
    ///
    /// @param osName operating system name.
    /// @param osArch operating system architecture.
    /// @return supported platform, or null when no bundled binary is available.
    static @Nullable DdFlasherPlatform platform(String osName, String osArch) {
        @Nullable String arch = normalizedArch(osArch);
        if (arch == null) {
            return null;
        }

        String normalizedOs = osName.toLowerCase(Locale.ROOT);
        if (normalizedOs.contains("mac") || normalizedOs.contains("darwin")) {
            return new DdFlasherPlatform("macos-" + arch, "dd-flasher", false);
        }
        if (normalizedOs.startsWith("windows")) {
            return new DdFlasherPlatform("windows-" + arch, "dd-flasher.exe", true);
        }
        if (normalizedOs.contains("linux")) {
            return new DdFlasherPlatform("linux-" + arch, "dd-flasher", false);
        }
        return null;
    }

    /// Resolves a normalized architecture token.
    ///
    /// @param osArch operating system architecture.
    /// @return normalized architecture token, or null when unsupported.
    private static @Nullable String normalizedArch(String osArch) {
        return switch (osArch.toLowerCase(Locale.ROOT)) {
            case "amd64", "x86_64", "x86-64", "x64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> null;
        };
    }

    /// Reads configured executable overrides.
    ///
    /// @return configured executable, or null when none is set.
    private static @Nullable String configuredExecutable() {
        @Nullable String property = nonBlank(System.getProperty(EXECUTABLE_PROPERTY));
        if (property != null) {
            return property;
        }
        return nonBlank(System.getenv(EXECUTABLE_ENV));
    }

    /// Finds the current application home directory.
    ///
    /// @return application home, or null when running from an unsupported layout.
    private static @Nullable Path appHome() {
        @Nullable String property = nonBlank(System.getProperty(APP_HOME_PROPERTY));
        if (property != null) {
            return Path.of(property);
        }

        try {
            Path codeSource = Path.of(DdFlasherExecutableLocator.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI());
            @Nullable Path libDirectory = codeSource.getParent();
            if (libDirectory != null && "lib".equals(libDirectory.getFileName().toString())) {
                return libDirectory.getParent();
            }
        } catch (IllegalArgumentException | NullPointerException | SecurityException | URISyntaxException _) {
        }
        return null;
    }

    /// Returns trimmed non-blank text.
    ///
    /// @param value input value.
    /// @return trimmed text, or null when blank.
    private static @Nullable String nonBlank(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }

    /// Bundled dd-flasher platform metadata.
    ///
    /// @param directory distribution directory name.
    /// @param executableName executable file name.
    /// @param windows whether this platform is Windows.
    @NotNullByDefault
    record DdFlasherPlatform(String directory, String executableName, boolean windows) {
    }
}

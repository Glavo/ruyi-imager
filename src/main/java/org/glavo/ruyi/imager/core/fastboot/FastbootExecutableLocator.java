// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.fastboot;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/// Locates the fastboot executable bundled with application distributions.
@NotNullByDefault
public final class FastbootExecutableLocator {
    /// System property used to override fastboot executable resolution.
    public static final String EXECUTABLE_PROPERTY = "ruyi.imager.fastboot.executable";

    /// Environment variable used to override fastboot executable resolution.
    public static final String EXECUTABLE_ENV = "RUYI_IMAGER_FASTBOOT";

    /// System property used to override application home discovery.
    public static final String APP_HOME_PROPERTY = "ruyi.imager.appHome";

    /// Fallback executable name resolved from PATH.
    private static final String PATH_EXECUTABLE = "fastboot";

    /// Prevents construction of the locator utility.
    private FastbootExecutableLocator() {
    }

    /// Resolves the best fastboot executable for the current process.
    ///
    /// @return bundled fastboot path, configured override, or PATH executable name.
    public static String resolve() {
        @Nullable String override = configuredExecutable();
        if (override != null) {
            return override;
        }

        @Nullable Path appHome = appHome();
        if (appHome != null) {
            @Nullable Path bundled = bundledExecutable(
                    appHome,
                    System.getProperty("os.name", ""),
                    System.getProperty("os.arch", ""));
            if (bundled != null) {
                return bundled.toString();
            }
        }

        return PATH_EXECUTABLE;
    }

    /// Resolves a bundled fastboot executable under an application home.
    ///
    /// @param appHome application home directory.
    /// @param osName operating system name.
    /// @param osArch operating system architecture.
    /// @return bundled fastboot path, or null when unsupported or absent.
    static @Nullable Path bundledExecutable(Path appHome, String osName, String osArch) {
        @Nullable FastbootPlatform platform = platform(osName, osArch);
        if (platform == null) {
            return null;
        }

        Path candidate = appHome
                .resolve("tools")
                .resolve("fastboot")
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

    /// Resolves a fastboot platform identifier from operating system properties.
    ///
    /// @param osName operating system name.
    /// @param osArch operating system architecture.
    /// @return supported platform, or null when no bundled binary is available.
    static @Nullable FastbootPlatform platform(String osName, String osArch) {
        if (!isX8664(osArch)) {
            return null;
        }

        String normalizedOs = osName.toLowerCase(Locale.ROOT);
        if (normalizedOs.contains("mac") || normalizedOs.contains("darwin")) {
            return new FastbootPlatform("macos-x86_64", "fastboot", false);
        }
        if (normalizedOs.contains("win")) {
            return new FastbootPlatform("windows-x86_64", "fastboot.exe", true);
        }
        if (normalizedOs.contains("linux")) {
            return new FastbootPlatform("linux-x86_64", "fastboot", false);
        }
        return null;
    }

    /// Returns whether an architecture string identifies x86-64.
    ///
    /// @param osArch operating system architecture.
    /// @return whether this is an x86-64 architecture alias.
    private static boolean isX8664(String osArch) {
        return switch (osArch.toLowerCase(Locale.ROOT)) {
            case "amd64", "x86_64", "x86-64", "x64" -> true;
            default -> false;
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
            Path codeSource = Path.of(FastbootExecutableLocator.class
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

    /// Bundled fastboot platform metadata.
    ///
    /// @param directory distribution directory name.
    /// @param executableName executable file name.
    /// @param windows whether this platform is Windows.
    @NotNullByDefault
    record FastbootPlatform(String directory, String executableName, boolean windows) {
    }
}

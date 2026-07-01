// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.gradle;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Provides WiX packaging helpers.
@NotNullByDefault
public final class WixPackaging {
    /// Creates a WiX packaging helper namespace.
    private WixPackaging() {
    }

    /// Returns the WiX architecture name for a jlink target platform.
    ///
    /// @param platform jlink target platform token.
    /// @return WiX architecture name, or null when the platform cannot be packaged as MSI.
    public static @Nullable String wixArchitecture(String platform) {
        return switch (platform) {
            case "windows-x86_64" -> "x64";
            case "windows-aarch64" -> "arm64";
            default -> null;
        };
    }

    /// Returns the WiX architecture name or fails with a clear packaging error.
    ///
    /// @param platform jlink target platform token.
    /// @return WiX architecture name.
    public static String requireWixArchitecture(String platform) {
        String architecture = wixArchitecture(platform);
        if (architecture == null) {
            throw new IllegalStateException(
                    "MSI packaging is supported only for Windows jlink platforms, but was requested for " + platform);
        }
        return architecture;
    }

    /// Converts a Gradle project version into a Windows Installer product version.
    ///
    /// @param version Gradle project version.
    /// @return Windows Installer version in `major.minor.build` form.
    public static String msiVersion(String version) {
        String baseVersion = version.split("[+-]", 2)[0];
        String[] parts = baseVersion.split("\\.", -1);
        int major = versionPart(parts, 0, 255);
        int minor = versionPart(parts, 1, 255);
        int build = versionPart(parts, 2, 65535);
        return major + "." + minor + "." + build;
    }

    /// Reads one Windows Installer version part.
    ///
    /// @param parts version parts.
    /// @param index part index.
    /// @param max maximum allowed value.
    /// @return normalized version part.
    private static int versionPart(String[] parts, int index, int max) {
        if (index >= parts.length) {
            return 0;
        }

        String part = parts[index];
        int end = 0;
        while (end < part.length() && Character.isDigit(part.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return 0;
        }

        int value = Integer.parseInt(part.substring(0, end));
        if (value > max) {
            throw new IllegalArgumentException("MSI version part is too large: " + value);
        }
        return value;
    }
}

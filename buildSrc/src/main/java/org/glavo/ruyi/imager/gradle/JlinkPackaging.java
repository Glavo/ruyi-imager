// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.gradle;

import org.jetbrains.annotations.NotNullByDefault;

/// Provides packaging helpers for jlink distributions.
@NotNullByDefault
public final class JlinkPackaging {
    /// Creates a jlink packaging helper namespace.
    private JlinkPackaging() {
    }

    /// Returns the Debian architecture name or fails with a clear packaging error.
    ///
    /// @param platform jlink target platform token.
    /// @return Debian architecture name.
    public static String requireDebianArchitecture(String platform) {
        return switch (platform) {
            case "linux-x86_64" -> "amd64";
            case "linux-aarch64" -> "arm64";
            case "linux-riscv64" -> "riscv64";
            default -> throw new IllegalStateException(
                    "Debian packaging is supported only for Linux jlink platforms, but was requested for " + platform);
        };
    }

    /// Converts a Gradle project version into a Debian-compatible package version.
    ///
    /// @param version Gradle project version.
    /// @return Debian-compatible version string.
    public static String debianVersion(String version) {
        String text = version.trim();
        if (text.isEmpty()) {
            text = "0";
        }
        text = text.replace("-dev", "~dev");
        text = text.replace("-nightly.", "~nightly.");

        StringBuilder builder = new StringBuilder(text.length() + 2);
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isLetterOrDigit(ch)
                    || ch == '.'
                    || ch == '+'
                    || ch == '-'
                    || ch == ':'
                    || ch == '~') {
                builder.append(ch);
            } else {
                builder.append('+');
            }
        }
        if (!Character.isDigit(builder.charAt(0))) {
            builder.insert(0, "0~");
        }
        return builder.toString();
    }

    /// Returns whether a jlink archive path must be executable on Unix platforms.
    ///
    /// @param path archive entry path.
    /// @param platform jlink target platform token.
    /// @return whether the entry must be executable.
    public static boolean jlinkUnixExecutableArchivePath(String path, String platform) {
        String relativePath = normalizeArchivePath(path);
        if (relativePath.startsWith("ruyi-imager/")) {
            relativePath = relativePath.substring("ruyi-imager/".length());
        }

        return relativePath.equals("bin/ruyi-imager")
                || relativePath.equals("bin/ruyi-imager-cli")
                || relativePath.startsWith("runtime/bin/")
                || relativePath.equals("runtime/lib/jspawnhelper")
                || relativePath.equals("runtime/lib/jexec")
                || relativePath.equals("tools/fastboot/" + platform + "/fastboot")
                || relativePath.equals("tools/dd-flasher/" + platform + "/dd-flasher");
    }

    /// Returns whether a Debian package data path must be executable.
    ///
    /// @param path Debian data archive entry path.
    /// @param platform jlink target platform token.
    /// @return whether the entry must be executable.
    public static boolean jlinkDebExecutablePath(String path, String platform) {
        String relativePath = normalizeArchivePath(path);
        if (relativePath.equals("usr/bin/ruyi-imager") || relativePath.equals("usr/bin/ruyi-imager-cli")) {
            return true;
        }
        if (!relativePath.startsWith("opt/ruyi-imager/")) {
            return false;
        }
        return jlinkUnixExecutableArchivePath(relativePath.substring("opt/ruyi-imager/".length()), platform);
    }

    /// Converts a platform directory name into a Gradle task suffix.
    ///
    /// @param platformDirectory distribution platform directory.
    /// @return Gradle task suffix.
    public static String platformTaskSuffix(String platformDirectory) {
        String[] parts = platformDirectory.split("[_-]");
        StringBuilder builder = new StringBuilder(platformDirectory.length());
        for (String part : parts) {
            if (!part.isEmpty()) {
                builder.append(Character.toUpperCase(part.charAt(0)));
                builder.append(part.substring(1));
            }
        }
        return builder.toString();
    }

    /// Normalizes archive path separators.
    ///
    /// @param path archive path.
    /// @return normalized archive path.
    private static String normalizeArchivePath(String path) {
        return path.replace('\\', '/');
    }
}

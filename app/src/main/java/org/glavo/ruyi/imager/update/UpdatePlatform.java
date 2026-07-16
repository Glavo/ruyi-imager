// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.update;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Locale;

/// Platforms supported by packaged Ruyi Imager application updates.
@NotNullByDefault
public enum UpdatePlatform {
    /// 64-bit x86 Windows.
    WINDOWS_X86_64("windows-x86_64"),

    /// 64-bit Arm Windows.
    WINDOWS_AARCH64("windows-aarch64"),

    /// 64-bit x86 Linux.
    LINUX_X86_64("linux-x86_64"),

    /// 64-bit Arm Linux.
    LINUX_AARCH64("linux-aarch64"),

    /// 64-bit x86 macOS.
    MACOS_X86_64("macos-x86_64"),

    /// 64-bit Arm macOS.
    MACOS_AARCH64("macos-aarch64");

    /// Manifest platform identifier.
    private final String id;

    /// Creates an update platform.
    ///
    /// @param id manifest platform identifier.
    UpdatePlatform(String id) {
        this.id = id;
    }

    /// Returns the manifest platform identifier.
    ///
    /// @return platform identifier.
    public String id() {
        return id;
    }

    /// Detects the current runtime platform.
    ///
    /// @return current update platform.
    public static UpdatePlatform current() {
        return detect(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }

    /// Detects a runtime platform from Java system property values.
    ///
    /// @param osName operating system name.
    /// @param osArch operating system architecture.
    /// @return detected update platform.
    static UpdatePlatform detect(String osName, String osArch) {
        String normalizedOs = osName.toLowerCase(Locale.ROOT);
        String normalizedArch = osArch.toLowerCase(Locale.ROOT);
        boolean aarch64 = normalizedArch.equals("aarch64") || normalizedArch.equals("arm64");
        boolean x86_64 = normalizedArch.equals("amd64")
                || normalizedArch.equals("x86_64")
                || normalizedArch.equals("x64");
        if (!aarch64 && !x86_64) {
            throw new IllegalStateException("Unsupported update architecture: " + osArch);
        }

        if (normalizedOs.startsWith("windows")) {
            return aarch64 ? WINDOWS_AARCH64 : WINDOWS_X86_64;
        }
        if (normalizedOs.startsWith("linux")) {
            return aarch64 ? LINUX_AARCH64 : LINUX_X86_64;
        }
        if (normalizedOs.startsWith("mac") || normalizedOs.startsWith("darwin")) {
            return aarch64 ? MACOS_AARCH64 : MACOS_X86_64;
        }
        throw new IllegalStateException("Unsupported update operating system: " + osName);
    }

    /// Returns whether an installer package type is valid for this platform.
    ///
    /// @param packageType installer package type.
    /// @return whether the package type can be handed off on this platform.
    public boolean supports(UpdatePackageType packageType) {
        return switch (this) {
            case WINDOWS_X86_64, WINDOWS_AARCH64 -> packageType == UpdatePackageType.SETUP_EXE;
            case LINUX_X86_64, LINUX_AARCH64 -> packageType == UpdatePackageType.DEB;
            case MACOS_X86_64, MACOS_AARCH64 -> packageType == UpdatePackageType.PKG
                    || packageType == UpdatePackageType.DMG
                    || packageType == UpdatePackageType.TAR_GZ;
        };
    }

    /// Parses a manifest platform identifier.
    ///
    /// @param value manifest platform identifier.
    /// @return parsed platform.
    public static UpdatePlatform parse(String value) {
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        for (UpdatePlatform platform : values()) {
            if (platform.id.equals(normalized)) {
                return platform;
            }
        }
        throw new IllegalArgumentException("Unsupported update platform: " + value);
    }
}

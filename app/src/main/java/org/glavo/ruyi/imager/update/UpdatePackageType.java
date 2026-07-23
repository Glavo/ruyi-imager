// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.update;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Locale;

/// Installer package types that Ruyi Imager can hand off safely.
@NotNullByDefault
public enum UpdatePackageType {
    /// Windows WiX Burn setup executable.
    SETUP_EXE("setup-exe", ".exe"),

    /// Debian binary package opened with the system package handler.
    DEB("deb", ".deb"),

    /// macOS installer package.
    PKG("pkg", ".pkg"),

    /// macOS disk image opened by Launch Services.
    DMG("dmg", ".dmg");

    /// JSON token for this package type.
    private final String token;

    /// Required lowercase file suffix.
    private final String fileSuffix;

    /// Creates an installer package type.
    ///
    /// @param token      JSON token.
    /// @param fileSuffix required file suffix.
    UpdatePackageType(String token, String fileSuffix) {
        this.token = token;
        this.fileSuffix = fileSuffix;
    }

    /// Returns the JSON token for this package type.
    ///
    /// @return JSON token.
    public String token() {
        return token;
    }

    /// Returns whether a file name has the required package suffix.
    ///
    /// @param fileName package file name.
    /// @return whether the suffix matches.
    public boolean matchesFileName(String fileName) {
        return fileName.toLowerCase(Locale.ROOT).endsWith(fileSuffix);
    }

    /// Parses a package type token.
    ///
    /// @param value package type token.
    /// @return parsed package type.
    public static UpdatePackageType parse(String value) {
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        for (UpdatePackageType type : values()) {
            if (type.token.equals(normalized)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unsupported update package type: " + value);
    }
}

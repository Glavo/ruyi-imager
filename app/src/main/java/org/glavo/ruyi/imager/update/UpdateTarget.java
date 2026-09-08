// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.update;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

/// Describes the local installation capabilities used when selecting an update.
///
/// @param platform runtime operating system and architecture.
/// @param systemVersion numeric OS version, including the Windows build number, or empty when unknown.
/// @param packageTypes supported installer types in preference order; an empty list disables installation.
@NotNullByDefault
public record UpdateTarget(
        UpdatePlatform platform,
        String systemVersion,
        @Unmodifiable List<UpdatePackageType> packageTypes) {
    /// Records unavailable native version information without failing unrelated update checks.
    private static final Logger LOGGER = LoggerFactory.getLogger(UpdateTarget.class);

    /// Validates and snapshots package preferences.
    public UpdateTarget {
        packageTypes = List.copyOf(packageTypes);
        if (new HashSet<>(packageTypes).size() != packageTypes.size()
                || packageTypes.stream().anyMatch(type -> !platform.supports(type))) {
            throw new IllegalArgumentException("Invalid update package preferences for " + platform.id());
        }
    }

    /// Detects local update capabilities, requiring dpkg for Debian installer handoff.
    /// macOS prefers installer packages over disk images.
    public static UpdateTarget current() {
        UpdatePlatform platform = UpdatePlatform.current();
        List<UpdatePackageType> types = Arrays.stream(UpdatePackageType.values())
                .filter(platform::supports)
                .filter(type -> type != UpdatePackageType.DEB
                        || Files.isExecutable(Path.of("/usr/bin/dpkg"))
                        || Files.isExecutable(Path.of("/bin/dpkg")))
                .toList();
        return new UpdateTarget(platform, systemVersion(platform), types);
    }

    /// Reads the native Windows version or the Java OS version on other platforms.
    ///
    /// @param platform current runtime platform.
    /// @return version, or empty if native detection fails; version requirements then fail closed.
    static String systemVersion(UpdatePlatform platform) {
        if (platform == UpdatePlatform.WINDOWS_X86_64 || platform == UpdatePlatform.WINDOWS_AARCH64) {
            try {
                return WindowsSystemVersion.read();
            } catch (IOException | RuntimeException | LinkageError exception) {
                LOGGER.warn("Unable to determine the Windows version for update compatibility.", exception);
                return "";
            }
        }
        return System.getProperty("os.version", "");
    }

    /// Selects a compatible artifact independently of its position in the manifest.
    ///
    /// @param release candidate release.
    /// @param current installed build.
    /// @return preferred matching artifact, or null when the release cannot be installed.
    public @Nullable UpdateArtifact select(UpdateRelease release, BuildInfo current) {
        if (!release.requirements().matches(current, this)) {
            return null;
        }
        for (UpdatePackageType type : packageTypes) {
            for (UpdateArtifact artifact : release.artifacts()) {
                if (artifact.platform() == platform && artifact.packageType() == type
                        && artifact.requirements().matches(current, this)) {
                    return artifact;
                }
            }
        }
        return null;
    }
}

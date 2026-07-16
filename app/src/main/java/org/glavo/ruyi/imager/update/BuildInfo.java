// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.update;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/// Identifies one Ruyi Imager build.
///
/// @param version semantic application version.
@NotNullByDefault
public record BuildInfo(String version) {
    /// Generated build information resource.
    private static final String RESOURCE = "/org/glavo/ruyi/imager/update/build-info.properties";

    /// Build information for the running application.
    private static final BuildInfo CURRENT = loadCurrent();

    /// Validates build information.
    public BuildInfo {
        if (version.isBlank()) {
            throw new IllegalArgumentException("Application version must not be blank.");
        }
    }

    /// Returns build information for the running application.
    ///
    /// @return current build information.
    public static BuildInfo current() {
        return CURRENT;
    }

    /// Infers the build channel from the version metadata used by packaged builds.
    ///
    /// @return inferred build channel.
    public UpdateChannel inferredChannel() {
        @Nullable String prerelease = SemanticVersion.parse(version).prerelease();
        return prerelease != null && (prerelease.equals("nightly") || prerelease.startsWith("nightly."))
                ? UpdateChannel.NIGHTLY
                : UpdateChannel.STABLE;
    }

    /// Loads generated build information from the application resources.
    ///
    /// @return generated build information.
    private static BuildInfo loadCurrent() {
        Properties properties = new Properties();
        try (InputStream input = BuildInfo.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Missing application build information: " + RESOURCE);
            }
            properties.load(input);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read application build information: " + RESOURCE, exception);
        }

        String version = properties.getProperty("version", "").strip();
        try {
            return new BuildInfo(version);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Invalid application build information: " + RESOURCE, exception);
        }
    }
}

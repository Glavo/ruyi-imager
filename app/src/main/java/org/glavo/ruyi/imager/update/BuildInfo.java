// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.update;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/// Identifies one Ruyi Imager build.
///
/// @param version     human-readable application version.
/// @param buildNumber monotonically increasing build number, or zero for local builds.
@NotNullByDefault
public record BuildInfo(String version, long buildNumber) {
    /// Generated build information resource.
    private static final String RESOURCE = "/org/glavo/ruyi/imager/update/build-info.properties";

    /// Build information for the running application.
    private static final BuildInfo CURRENT = loadCurrent();

    /// Validates build information.
    public BuildInfo {
        if (version.isBlank()) {
            throw new IllegalArgumentException("Application version must not be blank.");
        }
        if (buildNumber < 0L) {
            throw new IllegalArgumentException("Application build number must not be negative.");
        }
    }

    /// Returns build information for the running application.
    ///
    /// @return current build information.
    public static BuildInfo current() {
        return CURRENT;
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
        String buildNumber = properties.getProperty("buildNumber", "").strip();
        try {
            return new BuildInfo(version, Long.parseLong(buildNumber));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Invalid application build information: " + RESOURCE, exception);
        }
    }
}

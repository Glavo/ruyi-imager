// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.update;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Conditions that must be understood and satisfied before an update can be installed.
///
/// @param minimumAppVersion minimum installed application version, or null for no minimum.
/// @param minimumSystemVersion minimum numeric OS version, or null for no minimum.
/// @param understood whether every condition in the manifest is understood by this client.
@NotNullByDefault
public record UpdateRequirements(
        @Nullable String minimumAppVersion,
        @Nullable String minimumSystemVersion,
        boolean understood) {
    /// Conditions used when a manifest omits requirements.
    public static final UpdateRequirements NONE = new UpdateRequirements(null, null, true);

    /// Validates known requirement values even when an unknown condition is also present.
    public UpdateRequirements {
        if (minimumAppVersion != null) {
            ApplicationVersion.parse(minimumAppVersion);
        }
        if (minimumSystemVersion != null
                && !minimumSystemVersion.matches("[0-9]{1,10}(?:\\.[0-9]{1,10}){0,3}")) {
            throw new IllegalArgumentException("Invalid minimum system version: " + minimumSystemVersion);
        }
    }

    /// Returns whether all conditions match an installed application and operating system.
    /// Numeric system components are compared with omitted components treated as zero;
    /// a suffix such as a Linux kernel distribution label is ignored.
    ///
    /// @param current installed build.
    /// @param target current platform and system version.
    /// @return false for unknown requirements or an unreadable required system version.
    public boolean matches(BuildInfo current, UpdateTarget target) {
        if (!understood || (minimumAppVersion != null
                && ApplicationVersion.parse(current.version()).compareTo(ApplicationVersion.parse(minimumAppVersion)) < 0)) {
            return false;
        }
        if (minimumSystemVersion == null) {
            return true;
        }
        var matcher = java.util.regex.Pattern.compile("^([0-9]{1,10}(?:\\.[0-9]{1,10}){0,3})(?:[^0-9.].*)?$")
                .matcher(target.systemVersion());
        if (!matcher.matches()) {
            return false;
        }
        String[] actual = matcher.group(1).split("\\.");
        String[] minimum = minimumSystemVersion.split("\\.");
        for (int index = 0; index < Math.max(actual.length, minimum.length); index++) {
            long left = index < actual.length ? Long.parseLong(actual[index]) : 0L;
            long right = index < minimum.length ? Long.parseLong(minimum[index]) : 0L;
            if (left != right) {
                return left > right;
            }
        }
        return true;
    }
}

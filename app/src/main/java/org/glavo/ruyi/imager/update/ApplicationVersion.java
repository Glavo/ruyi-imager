// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.update;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Parses and compares the application version ordering envelope.
///
/// @param major  major version number.
/// @param minor  minor version number.
/// @param patch  patch version number.
/// @param suffix opaque version suffix, or null when absent.
@NotNullByDefault
record ApplicationVersion(
        long major,
        long minor,
        long patch,
        @Nullable String suffix) implements Comparable<ApplicationVersion> {
    /// Accepted numeric version and optional opaque suffix syntax.
    private static final Pattern VERSION_PATTERN = Pattern.compile(
            "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"
                    + "(?:-([0-9A-Za-z][0-9A-Za-z._+~-]*))?$");

    /// Validates the parsed version representation.
    public ApplicationVersion {
        if (major < 0L || minor < 0L || patch < 0L) {
            throw new IllegalArgumentException("Application version numbers must not be negative.");
        }
        if (suffix != null && !VERSION_PATTERN.matcher("0.0.0-" + suffix).matches()) {
            throw new IllegalArgumentException("Invalid application version suffix: " + suffix);
        }
    }

    /// Parses an application version without interpreting suffix contents.
    ///
    /// @param value version text.
    /// @return parsed application version.
    static ApplicationVersion parse(String value) {
        Matcher matcher = VERSION_PATTERN.matcher(value);
        if (!matcher.matches()) {
            throw invalidVersion(value, null);
        }

        try {
            return new ApplicationVersion(
                    Long.parseLong(matcher.group(1)),
                    Long.parseLong(matcher.group(2)),
                    Long.parseLong(matcher.group(3)),
                    matcher.group(4));
        } catch (NumberFormatException exception) {
            throw invalidVersion(value, exception);
        }
    }

    /// Returns the update channel implied by a currently known suffix convention.
    ///
    /// @return nightly for known nightly suffixes, otherwise stable.
    UpdateChannel inferredChannel() {
        return suffix != null && (suffix.equals("nightly") || suffix.startsWith("nightly."))
                ? UpdateChannel.NIGHTLY
                : UpdateChannel.STABLE;
    }

    /// Compares numeric versions, then opaque suffixes using lexical order.
    ///
    /// @param other version to compare.
    /// @return comparison result.
    @Override
    public int compareTo(ApplicationVersion other) {
        int result = Long.compare(major, other.major);
        if (result != 0) {
            return result;
        }
        result = Long.compare(minor, other.minor);
        if (result != 0) {
            return result;
        }
        result = Long.compare(patch, other.patch);
        if (result != 0) {
            return result;
        }
        if (suffix == null) {
            return other.suffix == null ? 0 : 1;
        }
        if (other.suffix == null) {
            return -1;
        }
        return suffix.compareTo(other.suffix);
    }

    /// Creates a consistent invalid-version exception.
    ///
    /// @param value     rejected version text.
    /// @param exception parsing failure, or null when syntax validation failed.
    /// @return invalid-version exception.
    private static IllegalArgumentException invalidVersion(
            String value,
            @Nullable RuntimeException exception) {
        String message = "Invalid application version: " + value;
        return exception == null
                ? new IllegalArgumentException(message)
                : new IllegalArgumentException(message, exception);
    }
}

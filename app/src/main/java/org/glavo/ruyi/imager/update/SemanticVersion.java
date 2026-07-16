// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.update;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Parses and compares application versions using Semantic Versioning precedence.
///
/// @param major      major version.
/// @param minor      minor version.
/// @param patch      patch version.
/// @param prerelease prerelease identifiers, or null for a release.
@NotNullByDefault
record SemanticVersion(long major, long minor, long patch, @Nullable String prerelease)
        implements Comparable<SemanticVersion> {
    /// Accepted version syntax, including the two-component form used by local builds.
    private static final Pattern VERSION_PATTERN = Pattern.compile(
            "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:\\.(0|[1-9][0-9]*))?"
                    + "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?"
                    + "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$");

    /// Parses an application version.
    ///
    /// @param value version text.
    /// @return parsed version.
    static SemanticVersion parse(String value) {
        Matcher matcher = VERSION_PATTERN.matcher(value);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid application version: " + value);
        }

        try {
            @Nullable String patch = matcher.group(3);
            @Nullable String prerelease = matcher.group(4);
            validatePrerelease(prerelease, value);
            return new SemanticVersion(
                    Long.parseLong(matcher.group(1)),
                    Long.parseLong(matcher.group(2)),
                    patch == null ? 0L : Long.parseLong(patch),
                    prerelease);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid application version: " + value, exception);
        }
    }

    /// Compares Semantic Versioning precedence and ignores build metadata.
    ///
    /// @param other version to compare.
    /// @return comparison result.
    @Override
    public int compareTo(SemanticVersion other) {
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
        if (prerelease == null) {
            return other.prerelease == null ? 0 : 1;
        }
        if (other.prerelease == null) {
            return -1;
        }
        return comparePrerelease(prerelease, other.prerelease);
    }

    /// Compares dot-separated prerelease identifiers.
    ///
    /// @param left  left prerelease text.
    /// @param right right prerelease text.
    /// @return comparison result.
    private static int comparePrerelease(String left, String right) {
        String @Unmodifiable [] leftParts = left.split("\\.");
        String @Unmodifiable [] rightParts = right.split("\\.");
        int count = Math.min(leftParts.length, rightParts.length);
        for (int index = 0; index < count; index++) {
            String leftPart = leftParts[index];
            String rightPart = rightParts[index];
            boolean leftNumeric = numericIdentifier(leftPart);
            boolean rightNumeric = numericIdentifier(rightPart);
            int result;
            if (leftNumeric && rightNumeric) {
                result = compareNumericIdentifier(leftPart, rightPart);
            } else if (leftNumeric) {
                result = -1;
            } else if (rightNumeric) {
                result = 1;
            } else {
                result = leftPart.compareTo(rightPart);
            }
            if (result != 0) {
                return result;
            }
        }
        return Integer.compare(leftParts.length, rightParts.length);
    }

    /// Returns whether a prerelease identifier is numeric.
    ///
    /// @param value prerelease identifier.
    /// @return whether the identifier is numeric.
    private static boolean numericIdentifier(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (!Character.isDigit(value.charAt(index))) {
                return false;
            }
        }
        return !value.isEmpty();
    }

    /// Rejects numeric prerelease identifiers with leading zeroes.
    ///
    /// @param prerelease prerelease text, or null for a release.
    /// @param version    complete version text for error reporting.
    private static void validatePrerelease(@Nullable String prerelease, String version) {
        if (prerelease == null) {
            return;
        }
        String @Unmodifiable [] identifiers = prerelease.split("\\.");
        for (String identifier : identifiers) {
            if (numericIdentifier(identifier) && identifier.length() > 1 && identifier.charAt(0) == '0') {
                throw new IllegalArgumentException("Invalid application version: " + version);
            }
        }
    }

    /// Compares numeric identifiers without integer overflow.
    ///
    /// @param left  left numeric identifier.
    /// @param right right numeric identifier.
    /// @return comparison result.
    private static int compareNumericIdentifier(String left, String right) {
        int result = Integer.compare(left.length(), right.length());
        return result == 0 ? left.compareTo(right) : result;
    }
}

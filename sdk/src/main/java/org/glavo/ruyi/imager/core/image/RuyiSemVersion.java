// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.image;

import org.glavo.ruyi.imager.core.SdkMessages;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.regex.Pattern;

/// Minimal SemVer implementation for Ruyi package versions.
///
/// @param major major version.
/// @param minor minor version.
/// @param patch patch version.
/// @param prerelease prerelease label.
@NotNullByDefault
record RuyiSemVersion(long major, long minor, long patch, @Nullable String prerelease)
        implements Comparable<RuyiSemVersion> {
    /// Ruyi prerelease label pattern.
    private static final Pattern RUYI_PRERELEASE = Pattern.compile("^(?:alpha|beta|pre|rc).*");

    /// Parses a SemVer string.
    ///
    /// @param value version string.
    /// @return parsed version.
    public static RuyiSemVersion parse(String value) {
        String withoutBuild = stripBuild(value);
        @Nullable String prerelease = null;
        int prereleaseStart = withoutBuild.indexOf('-');
        String core = withoutBuild;
        if (prereleaseStart >= 0) {
            core = withoutBuild.substring(0, prereleaseStart);
            prerelease = withoutBuild.substring(prereleaseStart + 1);
        }

        String[] parts = core.split("\\.", -1);
        if (parts.length != 3) {
            throw new IllegalArgumentException(SdkMessages.get("core.image.invalidSemVer", value));
        }

        return new RuyiSemVersion(parseNumber(parts[0], value), parseNumber(parts[1], value), parseNumber(parts[2], value), prerelease);
    }

    /// Parses a SemVer string, returning null on failure.
    ///
    /// @param value version string.
    /// @return parsed version, or null when invalid.
    public static @Nullable RuyiSemVersion parseOrNull(String value) {
        try {
            return parse(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /// Checks whether a version satisfies a comma-separated Ruyi expression.
    ///
    /// @param version version string.
    /// @param expression version expression.
    /// @return whether the version satisfies every constraint.
    public static boolean matches(String version, String expression) {
        @Nullable RuyiSemVersion parsed = parseOrNull(version);
        if (parsed == null) {
            return false;
        }

        for (String rawConstraint : expression.split(",")) {
            String constraint = rawConstraint.strip();
            if (constraint.isEmpty()) {
                return false;
            }
            if (!parsed.matchesSingleConstraint(constraint)) {
                return false;
            }
        }
        return true;
    }

    /// Checks whether this version has a Ruyi prerelease label.
    ///
    /// @return whether the prerelease label starts with alpha, beta, pre, or rc.
    public boolean isRuyiPrerelease() {
        return prerelease != null && RUYI_PRERELEASE.matcher(prerelease).matches();
    }

    /// Compares this version to another version.
    ///
    /// @param other other version.
    /// @return comparison result.
    @Override
    public int compareTo(RuyiSemVersion other) {
        int majorComparison = Long.compare(major, other.major);
        if (majorComparison != 0) {
            return majorComparison;
        }

        int minorComparison = Long.compare(minor, other.minor);
        if (minorComparison != 0) {
            return minorComparison;
        }

        int patchComparison = Long.compare(patch, other.patch);
        if (patchComparison != 0) {
            return patchComparison;
        }

        return comparePrerelease(prerelease, other.prerelease);
    }

    /// Checks whether this version matches one version constraint.
    ///
    /// @param constraint version constraint.
    /// @return whether the constraint matches.
    private boolean matchesSingleConstraint(String constraint) {
        if (startsWithDigit(constraint)) {
            return compareTo(parse(constraint)) == 0;
        }

        for (String operator : List.of(">=", "<=", "==", ">", "<", "=")) {
            if (constraint.startsWith(operator)) {
                RuyiSemVersion other = parse(constraint.substring(operator.length()).strip());
                int comparison = compareTo(other);
                return switch (operator) {
                    case ">=" -> comparison >= 0;
                    case "<=" -> comparison <= 0;
                    case ">" -> comparison > 0;
                    case "<" -> comparison < 0;
                    case "==", "=" -> comparison == 0;
                    default -> false;
                };
            }
        }

        return false;
    }

    /// Compares prerelease labels according to SemVer ordering.
    ///
    /// @param first first prerelease label.
    /// @param second second prerelease label.
    /// @return comparison result.
    private static int comparePrerelease(@Nullable String first, @Nullable String second) {
        if (first == null && second == null) {
            return 0;
        }
        if (first == null) {
            return 1;
        }
        if (second == null) {
            return -1;
        }

        String[] firstParts = first.split("\\.");
        String[] secondParts = second.split("\\.");
        int count = Math.min(firstParts.length, secondParts.length);
        for (int i = 0; i < count; i++) {
            int comparison = comparePrereleasePart(firstParts[i], secondParts[i]);
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(firstParts.length, secondParts.length);
    }

    /// Compares two prerelease identifiers.
    ///
    /// @param first first identifier.
    /// @param second second identifier.
    /// @return comparison result.
    private static int comparePrereleasePart(String first, String second) {
        @Nullable Long firstNumber = parseNumberOrNull(first);
        @Nullable Long secondNumber = parseNumberOrNull(second);
        if (firstNumber != null && secondNumber != null) {
            return Long.compare(firstNumber, secondNumber);
        }
        if (firstNumber != null) {
            return -1;
        }
        if (secondNumber != null) {
            return 1;
        }
        return first.compareTo(second);
    }

    /// Strips SemVer build metadata.
    ///
    /// @param value version string.
    /// @return version without build metadata.
    private static String stripBuild(String value) {
        int buildStart = value.indexOf('+');
        return buildStart < 0 ? value : value.substring(0, buildStart);
    }

    /// Parses a SemVer numeric field.
    ///
    /// @param value numeric field.
    /// @param version full version string for error reporting.
    /// @return parsed number.
    private static long parseNumber(String value, String version) {
        if (value.isEmpty()) {
            throw new IllegalArgumentException(SdkMessages.get("core.image.invalidSemVer", version));
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(SdkMessages.get("core.image.invalidSemVer", version), e);
        }
    }

    /// Parses a number if possible.
    ///
    /// @param value input value.
    /// @return parsed number, or null when invalid.
    private static @Nullable Long parseNumberOrNull(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /// Checks whether a string starts with a digit.
    ///
    /// @param value input value.
    /// @return whether the first character is a digit.
    private static boolean startsWithDigit(String value) {
        return !value.isEmpty() && Character.isDigit(value.charAt(0));
    }
}

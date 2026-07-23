// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.update;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Parses and compares the valid WiX Burn version subset accepted by Ruyi Imager.
///
/// @param major         major version number in the unsigned 32-bit range.
/// @param minor         minor version number in the unsigned 32-bit range.
/// @param patch         patch version number in the unsigned 32-bit range.
/// @param revision      revision number in the unsigned 32-bit range.
/// @param prerelease    dot-separated prerelease identifiers, or null when absent.
/// @param buildMetadata build metadata ignored for ordering, or null when absent.
@NotNullByDefault
record ApplicationVersion(
        long major,
        long minor,
        long patch,
        long revision,
        @Nullable String prerelease,
        @Nullable String buildMetadata) implements Comparable<ApplicationVersion> {
    /// Largest numeric component accepted by WiX Burn.
    private static final long MAX_COMPONENT = 0xffff_ffffL;

    /// Strict syntax shared by the updater and WiX Burn condition expressions.
    private static final Pattern VERSION_PATTERN = Pattern.compile(
            "^[vV]?([0-9]+)"
                    + "(?:\\.([0-9]+))?"
                    + "(?:\\.([0-9]+))?"
                    + "(?:\\.([0-9]+))?"
                    + "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?"
                    + "(?:\\+([0-9A-Za-z_.+\\-]+))?$");

    /// Valid prerelease text stored by a parsed version.
    private static final Pattern PRERELEASE_PATTERN =
            Pattern.compile("^[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*$");

    /// Build metadata characters that WiX Burn condition expressions accept reliably.
    private static final Pattern BUILD_METADATA_PATTERN =
            Pattern.compile("^[0-9A-Za-z_.+\\-]+$");

    /// Validates the parsed version representation.
    public ApplicationVersion {
        validateComponent("major", major);
        validateComponent("minor", minor);
        validateComponent("patch", patch);
        validateComponent("revision", revision);
        if (prerelease != null && !PRERELEASE_PATTERN.matcher(prerelease).matches()) {
            throw new IllegalArgumentException("Invalid application version prerelease: " + prerelease);
        }
        if (buildMetadata != null && !BUILD_METADATA_PATTERN.matcher(buildMetadata).matches()) {
            throw new IllegalArgumentException("Invalid application version build metadata: " + buildMetadata);
        }
    }

    /// Parses a version in the accepted valid WiX Burn subset.
    ///
    /// Missing minor, patch, and revision components are represented as zero. A leading `v` or
    /// `V` is accepted. Build metadata is retained but does not affect comparison.
    ///
    /// @param value version text.
    /// @return parsed application version.
    /// @throws IllegalArgumentException when the version is not in the accepted Burn subset or a
    ///                                  numeric component exceeds the unsigned 32-bit range.
    static ApplicationVersion parse(String value) {
        Matcher matcher = VERSION_PATTERN.matcher(value);
        if (!matcher.matches()) {
            throw invalidVersion(value, null);
        }

        return new ApplicationVersion(
                parseComponent(value, matcher.group(1)),
                parseComponent(value, matcher.group(2)),
                parseComponent(value, matcher.group(3)),
                parseComponent(value, matcher.group(4)),
                matcher.group(5),
                matcher.group(6));
    }

    /// Returns the update channel implied by a currently known prerelease convention.
    ///
    /// @return nightly for a prerelease whose first identifier is `nightly`, otherwise stable.
    UpdateChannel inferredChannel() {
        if (prerelease == null) {
            return UpdateChannel.STABLE;
        }
        int separator = prerelease.indexOf('.');
        String firstIdentifier = separator < 0 ? prerelease : prerelease.substring(0, separator);
        return firstIdentifier.equalsIgnoreCase("nightly")
                ? UpdateChannel.NIGHTLY
                : UpdateChannel.STABLE;
    }

    /// Compares versions using WiX Burn precedence for valid versions.
    ///
    /// Numeric release components are compared first. A stable version has higher precedence than
    /// a prerelease with the same numeric components. Numeric prerelease identifiers compare
    /// numerically and precede text identifiers, text comparison is case-insensitive, and build
    /// metadata is ignored.
    ///
    /// @param other version to compare.
    /// @return a negative value, zero, or a positive value according to Burn precedence.
    /// @apiNote This natural ordering is intentionally inconsistent with record equality because
    /// prerelease spelling and build metadata remain part of the record state.
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
        result = Long.compare(revision, other.revision);
        if (result != 0) {
            return result;
        }
        return comparePrerelease(prerelease, other.prerelease);
    }

    /// Validates one unsigned 32-bit release component.
    ///
    /// @param name  component name used in an exception.
    /// @param value component value.
    /// @throws IllegalArgumentException when the value is outside the accepted range.
    private static void validateComponent(String name, long value) {
        if (value < 0L || value > MAX_COMPONENT) {
            throw new IllegalArgumentException(
                    "Application version " + name + " must be an unsigned 32-bit number: " + value);
        }
    }

    /// Parses one optional unsigned 32-bit release component.
    ///
    /// @param versionText complete version text used in an exception.
    /// @param component   numeric component text, or null when omitted.
    /// @return parsed component, or zero when omitted.
    /// @throws IllegalArgumentException when the component exceeds the accepted range.
    private static long parseComponent(String versionText, @Nullable String component) {
        if (component == null) {
            return 0L;
        }
        try {
            long value = Long.parseLong(component);
            if (value > MAX_COMPONENT) {
                throw invalidVersion(versionText, null);
            }
            return value;
        } catch (NumberFormatException exception) {
            throw invalidVersion(versionText, exception);
        }
    }

    /// Compares two optional prerelease sequences.
    ///
    /// @param left  left prerelease, or null for a stable version.
    /// @param right right prerelease, or null for a stable version.
    /// @return comparison result.
    private static int comparePrerelease(@Nullable String left, @Nullable String right) {
        if (left == null) {
            return right == null ? 0 : 1;
        }
        if (right == null) {
            return -1;
        }

        String[] leftIdentifiers = left.split("\\.");
        String[] rightIdentifiers = right.split("\\.");
        int sharedLength = Math.min(leftIdentifiers.length, rightIdentifiers.length);
        for (int index = 0; index < sharedLength; index++) {
            int result = comparePrereleaseIdentifier(leftIdentifiers[index], rightIdentifiers[index]);
            if (result != 0) {
                return result;
            }
        }
        return Integer.compare(leftIdentifiers.length, rightIdentifiers.length);
    }

    /// Compares two prerelease identifiers.
    ///
    /// @param left  left identifier.
    /// @param right right identifier.
    /// @return comparison result.
    private static int comparePrereleaseIdentifier(String left, String right) {
        boolean leftNumeric = isNumericIdentifier(left);
        boolean rightNumeric = isNumericIdentifier(right);
        if (leftNumeric && rightNumeric) {
            return compareNumericIdentifier(left, right);
        }
        if (leftNumeric != rightNumeric) {
            return leftNumeric ? -1 : 1;
        }
        return left.compareToIgnoreCase(right);
    }

    /// Returns whether Burn treats an identifier as an unsigned 32-bit numeric value.
    ///
    /// @param identifier prerelease identifier.
    /// @return whether the identifier contains only ASCII digits and fits in an unsigned 32-bit value.
    private static boolean isNumericIdentifier(String identifier) {
        long value = 0L;
        for (int index = 0; index < identifier.length(); index++) {
            char character = identifier.charAt(index);
            if (character < '0' || character > '9') {
                return false;
            }
            value = value * 10L + (character - '0');
            if (value > MAX_COMPONENT) {
                return false;
            }
        }
        return true;
    }

    /// Compares unsigned 32-bit decimal identifiers without parsing long leading-zero sequences.
    ///
    /// @param left  left numeric identifier.
    /// @param right right numeric identifier.
    /// @return comparison result.
    private static int compareNumericIdentifier(String left, String right) {
        int leftStart = firstSignificantDigit(left);
        int rightStart = firstSignificantDigit(right);
        int leftLength = left.length() - leftStart;
        int rightLength = right.length() - rightStart;
        int result = Integer.compare(leftLength, rightLength);
        if (result != 0) {
            return result;
        }
        return left.regionMatches(leftStart, right, rightStart, leftLength)
                ? 0
                : left.substring(leftStart).compareTo(right.substring(rightStart));
    }

    /// Finds the first significant digit while retaining one zero for an all-zero identifier.
    ///
    /// @param value numeric identifier.
    /// @return index of the first significant digit.
    private static int firstSignificantDigit(String value) {
        int index = 0;
        while (index + 1 < value.length() && value.charAt(index) == '0') {
            index++;
        }
        return index;
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

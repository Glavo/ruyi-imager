// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.update;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Parses and compares versions defined by the Ruyi Imager release policy.
///
/// @param major       major version number.
/// @param minor       minor version number.
/// @param patch       patch version number.
/// @param stage       release stage.
/// @param builtAt     nightly build time, or null for other stages.
/// @param commit      nightly Git commit prefix, or null for other stages.
@NotNullByDefault
record ApplicationVersion(
        long major,
        long minor,
        long patch,
        Stage stage,
        @Nullable Instant builtAt,
        @Nullable String commit) implements Comparable<ApplicationVersion> {
    /// Accepted stable, development, and nightly version syntax.
    private static final Pattern VERSION_PATTERN = Pattern.compile(
            "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"
                    + "(?:-(dev)|-nightly\\.([0-9]{8}T[0-9]{6}Z)\\.([0-9a-f]{7}))?$");

    /// Accepted abbreviated Git commit syntax.
    private static final Pattern COMMIT_PATTERN = Pattern.compile("^[0-9a-f]{7}$");

    /// Strict UTC timestamp parser used by nightly versions.
    private static final DateTimeFormatter NIGHTLY_TIMESTAMP_FORMATTER = new DateTimeFormatterBuilder()
            .appendPattern("uuuuMMdd'T'HHmmss'Z'")
            .toFormatter(Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT);

    /// Validates the parsed version representation.
    public ApplicationVersion {
        Objects.requireNonNull(stage, "Application version stage must not be null.");
        if (major < 0L || minor < 0L || patch < 0L) {
            throw new IllegalArgumentException("Application version numbers must not be negative.");
        }
        if (stage == Stage.NIGHTLY) {
            Instant timestamp = Objects.requireNonNull(builtAt, "Nightly build time must not be null.");
            String commitText = Objects.requireNonNull(commit, "Nightly commit must not be null.");
            if (timestamp.getNano() != 0 || !COMMIT_PATTERN.matcher(commitText).matches()) {
                throw new IllegalArgumentException("Invalid nightly build identity.");
            }
        } else if (builtAt != null || commit != null) {
            throw new IllegalArgumentException("Only nightly versions may contain build identity.");
        }
    }

    /// Parses an application version.
    ///
    /// @param value version text.
    /// @return parsed application version.
    static ApplicationVersion parse(String value) {
        Matcher matcher = VERSION_PATTERN.matcher(value);
        if (!matcher.matches()) {
            throw invalidVersion(value, null);
        }

        try {
            long major = Long.parseLong(matcher.group(1));
            long minor = Long.parseLong(matcher.group(2));
            long patch = Long.parseLong(matcher.group(3));
            if (matcher.group(4) != null) {
                return new ApplicationVersion(major, minor, patch, Stage.DEVELOPMENT, null, null);
            }

            @Nullable String timestamp = matcher.group(5);
            if (timestamp != null) {
                Instant builtAt = LocalDateTime.parse(timestamp, NIGHTLY_TIMESTAMP_FORMATTER)
                        .toInstant(ZoneOffset.UTC);
                return new ApplicationVersion(
                        major,
                        minor,
                        patch,
                        Stage.NIGHTLY,
                        builtAt,
                        Objects.requireNonNull(matcher.group(6)));
            }
            return new ApplicationVersion(major, minor, patch, Stage.STABLE, null, null);
        } catch (NumberFormatException | DateTimeException exception) {
            throw invalidVersion(value, exception);
        }
    }

    /// Returns the update channel implied by this version.
    ///
    /// @return nightly for nightly builds, otherwise stable.
    UpdateChannel inferredChannel() {
        return stage == Stage.NIGHTLY ? UpdateChannel.NIGHTLY : UpdateChannel.STABLE;
    }

    /// Returns whether this is a stable release version.
    ///
    /// @return whether this version identifies a stable release.
    boolean isStable() {
        return stage == Stage.STABLE;
    }

    /// Returns whether this is a nightly release version.
    ///
    /// @return whether this version identifies a nightly release.
    boolean isNightly() {
        return stage == Stage.NIGHTLY;
    }

    /// Compares versions according to the Ruyi Imager release policy.
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
        result = Integer.compare(stage.precedence(), other.stage.precedence());
        if (result != 0 || stage != Stage.NIGHTLY) {
            return result;
        }
        result = Objects.requireNonNull(builtAt).compareTo(Objects.requireNonNull(other.builtAt));
        return result != 0
                ? result
                : Objects.requireNonNull(commit).compareTo(Objects.requireNonNull(other.commit));
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

    /// Identifies the supported release stages in ascending precedence order.
    @NotNullByDefault
    enum Stage {
        /// Local development build.
        DEVELOPMENT(0),

        /// Timestamped nightly build.
        NIGHTLY(1),

        /// Stable release.
        STABLE(2);

        /// Ordering value within the same numeric version.
        private final int precedence;

        /// Creates a release stage.
        ///
        /// @param precedence ordering value within the same numeric version.
        Stage(int precedence) {
            this.precedence = precedence;
        }

        /// Returns the ordering value within the same numeric version.
        ///
        /// @return release-stage precedence.
        int precedence() {
            return precedence;
        }
    }
}

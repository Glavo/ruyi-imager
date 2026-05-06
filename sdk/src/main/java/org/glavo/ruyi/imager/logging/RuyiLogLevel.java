// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.logging;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.logging.Level;

/// Stable user-facing log levels mapped to JUL backend levels.
@NotNullByDefault
public enum RuyiLogLevel {
    /// Disables all application logging.
    OFF(Level.OFF),

    /// Records only failures.
    ERROR(Level.SEVERE),

    /// Records warnings and failures.
    WARN(Level.WARNING),

    /// Records normal operation milestones.
    INFO(Level.INFO),

    /// Records diagnostic details useful for troubleshooting.
    DEBUG(Level.FINE),

    /// Records the most detailed diagnostic events.
    TRACE(Level.FINEST);

    /// Backing JUL level.
    private final Level julLevel;

    /// Creates a log-level mapping.
    ///
    /// @param julLevel backing JUL level.
    RuyiLogLevel(Level julLevel) {
        this.julLevel = julLevel;
    }

    /// Returns the backing JUL level.
    ///
    /// @return backing JUL level.
    public Level julLevel() {
        return julLevel;
    }

    /// Parses a configured log level.
    ///
    /// @param value configured value.
    /// @return parsed level, or null when the value is absent or invalid.
    public static @Nullable RuyiLogLevel parse(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String normalized = value.strip().replace('-', '_').toUpperCase(Locale.ROOT);
        try {
            return RuyiLogLevel.valueOf(normalized);
        } catch (IllegalArgumentException _) {
            return null;
        }
    }
}

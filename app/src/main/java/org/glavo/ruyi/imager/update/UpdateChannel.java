// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.update;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Locale;

/// Update release channels exposed by an application update manifest.
@NotNullByDefault
public enum UpdateChannel {
    /// Stable releases intended for ordinary users.
    STABLE("stable"),

    /// Nightly releases produced from recent source commits.
    NIGHTLY("nightly");

    /// JSON and preference token for this channel.
    private final String token;

    /// Creates an update channel.
    ///
    /// @param token JSON and preference token.
    UpdateChannel(String token) {
        this.token = token;
    }

    /// Returns the stable external token for this channel.
    ///
    /// @return external channel token.
    public String token() {
        return token;
    }

    /// Parses a channel token without case sensitivity.
    ///
    /// @param value channel token.
    /// @return parsed channel.
    public static UpdateChannel parse(String value) {
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        for (UpdateChannel channel : values()) {
            if (channel.token.equals(normalized)) {
                return channel;
            }
        }
        throw new IllegalArgumentException("Unsupported update channel: " + value);
    }
}

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.fastboot;

import org.jetbrains.annotations.NotNullByDefault;

/// Device reported by the `fastboot devices` command.
///
/// @param id stable identifier used by CLI and GUI selections.
/// @param serial fastboot serial number.
/// @param state fastboot state reported by the executable.
@NotNullByDefault
public record FastbootDevice(String id, String serial, String state) {
    /// Returns a compact user-facing display name.
    ///
    /// @return display name.
    public String displayName() {
        return state.isBlank() ? serial : serial + " (" + state + ")";
    }
}

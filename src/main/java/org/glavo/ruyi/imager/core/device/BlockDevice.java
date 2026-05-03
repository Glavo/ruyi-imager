// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.device;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

/// Candidate block device for image flashing.
///
/// @param id stable identifier used by CLI and GUI selections.
/// @param displayName human-readable device name.
/// @param path platform path to the raw target.
/// @param sizeBytes total device size in bytes.
/// @param removable whether the device appears removable.
/// @param system whether the device appears to be a system disk.
/// @param readOnly whether the device is read-only.
/// @param model hardware model when available.
/// @param busType bus type when available.
@NotNullByDefault
public record BlockDevice(
        String id,
        String displayName,
        Path path,
        long sizeBytes,
        boolean removable,
        boolean system,
        boolean readOnly,
        @Nullable String model,
        @Nullable String busType) {
}

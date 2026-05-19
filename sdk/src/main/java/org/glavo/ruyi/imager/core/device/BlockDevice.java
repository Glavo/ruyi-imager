// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.device;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.Path;
import java.util.List;

/// Candidate block device for image flashing.
///
/// @param id stable identifier used by CLI and GUI selections.
/// @param displayName human-readable device name.
/// @param path platform path to the raw target.
/// @param sizeBytes total device size in bytes.
/// @param removable whether the device appears removable.
/// @param system whether the device appears to be a system disk.
/// @param mounted whether the device appears to have mounted volumes.
/// @param readOnly whether the device is read-only.
/// @param model hardware model when available.
/// @param busType bus type when available.
/// @param mountPoints mounted volume paths when available.
@NotNullByDefault
public record BlockDevice(
        String id,
        String displayName,
        Path path,
        long sizeBytes,
        boolean removable,
        boolean system,
        boolean mounted,
        boolean readOnly,
        @Nullable String model,
        @Nullable String busType,
        @Unmodifiable List<String> mountPoints) {
    /// Creates a block device without known mount points.
    ///
    /// @param id stable identifier used by CLI and GUI selections.
    /// @param displayName human-readable device name.
    /// @param path platform path to the raw target.
    /// @param sizeBytes total device size in bytes.
    /// @param removable whether the device appears removable.
    /// @param system whether the device appears to be a system disk.
    /// @param mounted whether the device appears to have mounted volumes.
    /// @param readOnly whether the device is read-only.
    /// @param model hardware model when available.
    /// @param busType bus type when available.
    public BlockDevice(
            String id,
            String displayName,
            Path path,
            long sizeBytes,
            boolean removable,
            boolean system,
            boolean mounted,
            boolean readOnly,
            @Nullable String model,
            @Nullable String busType) {
        this(id, displayName, path, sizeBytes, removable, system, mounted, readOnly, model, busType, List.of());
    }

    /// Copies mutable inputs into immutable collections.
    public BlockDevice {
        mountPoints = List.copyOf(mountPoints);
    }

    /// Returns whether this target is a file-backed fixture instead of a platform block device.
    ///
    /// @return whether this target is file-backed.
    public boolean fileBacked() {
        return "file".equalsIgnoreCase(busType);
    }
}

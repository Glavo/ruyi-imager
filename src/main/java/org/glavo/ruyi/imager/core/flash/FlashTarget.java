// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.flash;

import org.glavo.ruyi.imager.core.device.BlockDevice;
import org.glavo.ruyi.imager.core.fastboot.FastbootDevice;
import org.glavo.ruyi.imager.i18n.Messages;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Target selected for a flash operation.
///
/// @param blockDevice block device target for local writes.
/// @param fastbootDevice fastboot target for fastboot strategies.
@NotNullByDefault
public record FlashTarget(
        @Nullable BlockDevice blockDevice,
        @Nullable FastbootDevice fastbootDevice) {
    /// Validates that exactly one concrete target is selected.
    public FlashTarget {
        if ((blockDevice == null) == (fastbootDevice == null)) {
            throw new IllegalArgumentException(Messages.get("core.flash.exactlyOneTarget"));
        }
    }

    /// Creates a block device target.
    ///
    /// @param blockDevice target block device.
    /// @return flash target.
    public static FlashTarget blockDevice(BlockDevice blockDevice) {
        return new FlashTarget(blockDevice, null);
    }

    /// Creates a fastboot target.
    ///
    /// @param fastbootDevice target fastboot device.
    /// @return flash target.
    public static FlashTarget fastbootDevice(FastbootDevice fastbootDevice) {
        return new FlashTarget(null, fastbootDevice);
    }

    /// Returns whether the target is a block device.
    ///
    /// @return whether a block device is selected.
    public boolean isBlockDevice() {
        return blockDevice != null;
    }

    /// Returns whether the target is a fastboot device.
    ///
    /// @return whether a fastboot device is selected.
    public boolean isFastbootDevice() {
        return fastbootDevice != null;
    }
}

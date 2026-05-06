// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.flash;

import org.glavo.ruyi.imager.core.device.BlockDevice;
import org.glavo.ruyi.imager.core.fastboot.FastbootDevice;
import org.glavo.ruyi.imager.i18n.Messages;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Map;

/// Target selected for a flash operation.
///
/// @param blockDevice block device target for local writes.
/// @param blockDevices partition-specific block device targets for multi-partition local writes.
/// @param fastbootDevice fastboot target for fastboot strategies.
@NotNullByDefault
public record FlashTarget(
        @Nullable BlockDevice blockDevice,
        @Unmodifiable Map<String, BlockDevice> blockDevices,
        @Nullable FastbootDevice fastbootDevice) {
    /// Copies target maps and validates that exactly one concrete target mode is selected.
    public FlashTarget {
        blockDevices = Map.copyOf(blockDevices);
        int selectedModes = 0;
        if (blockDevice != null) {
            selectedModes++;
        }
        if (!blockDevices.isEmpty()) {
            selectedModes++;
        }
        if (fastbootDevice != null) {
            selectedModes++;
        }
        if (selectedModes != 1) {
            throw new IllegalArgumentException(Messages.get("core.flash.exactlyOneTarget"));
        }
    }

    /// Creates a block device target.
    ///
    /// @param blockDevice target block device.
    /// @return flash target.
    public static FlashTarget blockDevice(BlockDevice blockDevice) {
        return new FlashTarget(blockDevice, Map.of(), null);
    }

    /// Creates partition-specific block device targets.
    ///
    /// @param blockDevices target block devices keyed by partition name.
    /// @return flash target.
    public static FlashTarget blockDevices(@Unmodifiable Map<String, BlockDevice> blockDevices) {
        return new FlashTarget(null, blockDevices, null);
    }

    /// Creates a fastboot target.
    ///
    /// @param fastbootDevice target fastboot device.
    /// @return flash target.
    public static FlashTarget fastbootDevice(FastbootDevice fastbootDevice) {
        return new FlashTarget(null, Map.of(), fastbootDevice);
    }

    /// Returns whether the target uses block devices.
    ///
    /// @return whether one or more block devices are selected.
    public boolean isBlockDevice() {
        return blockDevice != null || !blockDevices.isEmpty();
    }

    /// Returns whether the target is a fastboot device.
    ///
    /// @return whether a fastboot device is selected.
    public boolean isFastbootDevice() {
        return fastbootDevice != null;
    }
}

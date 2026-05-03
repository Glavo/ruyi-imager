// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.device;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.util.List;

/// Enumerates and resolves candidate flash targets.
@NotNullByDefault
public interface BlockDeviceService {
    /// Lists candidate target devices.
    ///
    /// @return immutable list of candidate devices.
    /// @throws IOException when platform enumeration fails.
    @Unmodifiable
    List<BlockDevice> listDevices() throws IOException;

    /// Resolves one target device by id.
    ///
    /// @param id target device id.
    /// @return matching device, or null when not found.
    /// @throws IOException when platform enumeration fails.
    default @Nullable BlockDevice findDevice(String id) throws IOException {
        for (BlockDevice device : listDevices()) {
            if (device.id().equals(id)) {
                return device;
            }
        }
        return null;
    }
}

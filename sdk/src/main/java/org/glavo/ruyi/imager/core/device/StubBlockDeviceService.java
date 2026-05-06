// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.device;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;

/// Placeholder block device service used before platform backends are implemented.
@NotNullByDefault
public final class StubBlockDeviceService implements BlockDeviceService {
    /// Returns no devices until platform enumeration is implemented.
    ///
    /// @return empty device list.
    @Override
    public @Unmodifiable List<BlockDevice> listDevices() {
        return List.of();
    }
}

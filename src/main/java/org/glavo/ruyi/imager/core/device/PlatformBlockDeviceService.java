// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.device;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/// Selects the platform block-device backend for the current operating system.
@NotNullByDefault
public final class PlatformBlockDeviceService implements BlockDeviceService {
    /// Backend used by this service instance.
    private final BlockDeviceService delegate;

    /// Creates a platform service for the current operating system.
    public PlatformBlockDeviceService() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (osName.startsWith("windows")) {
            this.delegate = new WindowsBlockDeviceService();
        } else {
            this.delegate = new StubBlockDeviceService();
        }
    }

    /// Lists candidate target devices from the selected platform backend.
    ///
    /// @return immutable list of candidate devices.
    /// @throws IOException when platform enumeration fails.
    @Override
    public @Unmodifiable List<BlockDevice> listDevices() throws IOException {
        return delegate.listDevices();
    }
}

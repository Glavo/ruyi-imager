// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.device;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

/// Selects the platform block-device backend for the current operating system.
@NotNullByDefault
public final class PlatformBlockDeviceService implements BlockDeviceService {
    /// Logger for platform backend selection.
    private static final Logger LOGGER = Logger.getLogger(PlatformBlockDeviceService.class.getName());

    /// Backend used by this service instance.
    private final BlockDeviceService delegate;

    /// Creates a platform service for the current operating system.
    public PlatformBlockDeviceService() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (osName.startsWith("windows")) {
            this.delegate = new WindowsBlockDeviceService();
        } else if (osName.contains("linux")) {
            this.delegate = new LinuxBlockDeviceService();
        } else if (osName.contains("mac") || osName.contains("darwin")) {
            this.delegate = new MacOSBlockDeviceService();
        } else {
            this.delegate = new StubBlockDeviceService();
        }
        LOGGER.info(() -> "Selected block-device backend. osName="
                + osName
                + ", backend="
                + delegate.getClass().getSimpleName());
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

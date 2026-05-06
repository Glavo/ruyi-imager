// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.fastboot;

import org.glavo.ruyi.imager.core.OperationResult;
import org.glavo.ruyi.imager.core.ProgressReporter;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/// Enumerates fastboot devices and runs fastboot flashing strategies.
@NotNullByDefault
public interface FastbootService {
    /// Lists devices currently visible to fastboot.
    ///
    /// @return immutable fastboot device list.
    /// @throws IOException when fastboot cannot be executed.
    @Unmodifiable
    List<FastbootDevice> listDevices() throws IOException;

    /// Resolves one fastboot device by id.
    ///
    /// @param id target fastboot device id.
    /// @return matching fastboot device, or null when not found.
    /// @throws IOException when fastboot cannot be executed.
    default @Nullable FastbootDevice findDevice(String id) throws IOException {
        for (FastbootDevice device : listDevices()) {
            if (device.id().equals(id)) {
                return device;
            }
        }
        return null;
    }

    /// Flashes materialized image partitions through fastboot.
    ///
    /// @param strategy Ruyi provision strategy.
    /// @param partitions materialized partition images keyed by target partition name.
    /// @param device target fastboot device.
    /// @param reporter progress reporter.
    /// @return operation result.
    /// @throws IOException when fastboot cannot be executed.
    OperationResult flash(
            String strategy,
            @Unmodifiable Map<String, Path> partitions,
            FastbootDevice device,
            ProgressReporter reporter) throws IOException;
}

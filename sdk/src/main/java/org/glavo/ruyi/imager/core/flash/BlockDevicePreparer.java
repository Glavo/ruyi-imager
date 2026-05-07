// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.flash;

import org.glavo.ruyi.imager.core.ProgressReporter;
import org.glavo.ruyi.imager.core.device.BlockDevice;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;

/// Prepares a block device immediately before a destructive write.
@NotNullByDefault
public interface BlockDevicePreparer {
    /// Returns a preparer that leaves every block device unchanged.
    ///
    /// @return no-op preparer.
    static BlockDevicePreparer none() {
        return NoOpBlockDevicePreparer.INSTANCE;
    }

    /// Returns whether this preparer can safely prepare a mounted target before writing.
    ///
    /// @param target target block device.
    /// @return whether mounted target preparation is supported.
    default boolean canPrepareMounted(BlockDevice target) {
        return false;
    }

    /// Prepares a target block device for writing.
    ///
    /// @param target target block device.
    /// @param reporter progress reporter.
    /// @return prepared target metadata.
    /// @throws IOException when the target cannot be prepared.
    BlockDevice prepare(BlockDevice target, ProgressReporter reporter) throws IOException;
}

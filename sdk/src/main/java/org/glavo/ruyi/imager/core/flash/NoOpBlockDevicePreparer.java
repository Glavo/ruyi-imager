// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.flash;

import org.glavo.ruyi.imager.core.ProgressReporter;
import org.glavo.ruyi.imager.core.device.BlockDevice;
import org.jetbrains.annotations.NotNullByDefault;

/// Block device preparer that intentionally does nothing.
@NotNullByDefault
final class NoOpBlockDevicePreparer implements BlockDevicePreparer {
    /// Shared no-op preparer instance.
    static final BlockDevicePreparer INSTANCE = new NoOpBlockDevicePreparer();

    /// Creates the no-op preparer.
    private NoOpBlockDevicePreparer() {
    }

    /// Returns the original target unchanged.
    ///
    /// @param target target block device.
    /// @param reporter progress reporter.
    /// @return original target.
    @Override
    public BlockDevice prepare(BlockDevice target, ProgressReporter reporter) {
        return target;
    }
}

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.flash;

import org.glavo.ruyi.imager.core.ProgressReporter;
import org.glavo.ruyi.imager.core.device.BlockDevice;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Selects the platform block-device preparation backend for the current operating system.
@NotNullByDefault
public final class PlatformBlockDevicePreparer implements BlockDevicePreparer {
    /// Logger for platform preparer selection.
    private static final Logger LOGGER = LoggerFactory.getLogger(PlatformBlockDevicePreparer.class);

    /// Backend used by this preparer instance.
    private final BlockDevicePreparer delegate;

    /// Creates a platform preparer for the current operating system.
    public PlatformBlockDevicePreparer() {
        this(System.getProperty("os.name", ""));
    }

    /// Creates a platform preparer for an explicit operating system name.
    ///
    /// @param osName operating system name.
    PlatformBlockDevicePreparer(String osName) {
        String normalizedOs = osName.toLowerCase(Locale.ROOT);
        if (normalizedOs.contains("linux")) {
            this.delegate = new LinuxBlockDevicePreparer();
        } else {
            this.delegate = BlockDevicePreparer.none();
        }
        LOGGER.atInfo().log(() -> "Selected block-device preparer. osName="
                + normalizedOs
                + ", backend="
                + delegate.getClass().getSimpleName());
    }

    /// Returns whether the selected backend can prepare this mounted target.
    ///
    /// @param target target block device.
    /// @return whether mounted target preparation is supported.
    @Override
    public boolean canPrepareMounted(BlockDevice target) {
        return delegate.canPrepareMounted(target);
    }

    /// Returns whether the selected backend should prepare this target.
    ///
    /// @param target target block device.
    /// @return whether preparation should run.
    @Override
    public boolean shouldPrepare(BlockDevice target) {
        return delegate.shouldPrepare(target);
    }

    /// Prepares a target through the selected backend.
    ///
    /// @param target target block device.
    /// @param reporter progress reporter.
    /// @return prepared target metadata.
    /// @throws IOException when the target cannot be prepared.
    @Override
    public BlockDevice prepare(BlockDevice target, ProgressReporter reporter) throws IOException {
        return delegate.prepare(target, reporter);
    }
}

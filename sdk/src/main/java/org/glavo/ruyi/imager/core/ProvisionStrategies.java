// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core;

import org.jetbrains.annotations.NotNullByDefault;

/// Known Ruyi device provisioning strategy identifiers.
@NotNullByDefault
public final class ProvisionStrategies {
    /// Raw block-device image writing strategy.
    public static final String DD_V1 = "dd-v1";

    /// Standard fastboot partition flashing strategy.
    public static final String FASTBOOT_V1 = "fastboot-v1";

    /// LPi4A U-Boot fastboot handoff strategy.
    public static final String FASTBOOT_LPI4A_UBOOT_V1 = "fastboot-v1(lpi4a-uboot)";

    /// SpacemiT K1 fastboot handoff and eMMC flashing strategy used by Bianbu images.
    public static final String SPACEMIT_K1_V1 = "spacemit-k1-v1";

    /// Prevents construction of the strategy utility.
    private ProvisionStrategies() {
    }

    /// Returns whether a strategy writes through raw block-device access.
    ///
    /// @param strategy strategy name.
    /// @return whether this is a block-device strategy.
    public static boolean isDD(String strategy) {
        return DD_V1.equals(strategy);
    }

    /// Returns whether a strategy uses fastboot instead of host block devices.
    ///
    /// @param strategy strategy name.
    /// @return whether this is a fastboot strategy.
    public static boolean isFastboot(String strategy) {
        return FASTBOOT_V1.equals(strategy)
                || FASTBOOT_LPI4A_UBOOT_V1.equals(strategy)
                || SPACEMIT_K1_V1.equals(strategy);
    }

    /// Classifies support for a provision strategy.
    ///
    /// @param strategy strategy name.
    /// @return strategy support status.
    public static StrategySupport classify(String strategy) {
        return isDD(strategy) || isFastboot(strategy)
                ? StrategySupport.SUPPORTED
                : StrategySupport.UNKNOWN;
    }
}

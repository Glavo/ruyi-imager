// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.Set;

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

    /// Required SpacemiT K1 eMMC partitions in flashing order.
    public static final @Unmodifiable List<String> SPACEMIT_K1_PARTITION_ORDER =
            List.of("gpt", "bootinfo", "fsbl", "env", "opensbi", "uboot", "bootfs", "rootfs");

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

    /// Returns whether two strategies belong to the same supported flashing family.
    ///
    /// @param first first strategy.
    /// @param second second strategy.
    /// @return true for two DD strategies or two supported fastboot strategies.
    public static boolean canCombine(String first, String second) {
        return isDD(first) && isDD(second) || isFastboot(first) && isFastboot(second);
    }

    /// Checks a fastboot strategy and its required partition names without accessing a device.
    ///
    /// This checks metadata only; it does not verify image files or device compatibility.
    ///
    /// @param strategy provision strategy.
    /// @param partitions available partition names.
    /// @return a failure message, or null when the strategy and partition names are acceptable.
    public static @Nullable String fastbootPartitionError(String strategy, Set<String> partitions) {
        if (partitions.isEmpty()) {
            return SdkMessages.get("core.fastboot.noPartitions");
        }
        if (!isFastboot(strategy)) {
            return SdkMessages.get("core.fastboot.unsupportedStrategy", strategy);
        }
        @Unmodifiable List<String> required = switch (strategy) {
            case FASTBOOT_LPI4A_UBOOT_V1 -> List.of("uboot");
            case SPACEMIT_K1_V1 -> SPACEMIT_K1_PARTITION_ORDER;
            default -> List.of();
        };
        for (String partition : required) {
            if (!partitions.contains(partition)) {
                return SdkMessages.get("core.fastboot.missingPartition", partition);
            }
        }
        return null;
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

    /// Returns the Ruyi flashing priority for a strategy.
    ///
    /// @param strategy strategy name.
    /// @return flashing priority, where higher values run earlier.
    public static int priority(String strategy) {
        if (FASTBOOT_LPI4A_UBOOT_V1.equals(strategy)) {
            return 10;
        }
        return 0;
    }
}

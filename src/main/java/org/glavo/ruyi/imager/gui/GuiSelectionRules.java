// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.gui;

import org.glavo.ruyi.imager.core.StrategySupport;
import org.glavo.ruyi.imager.core.device.BlockDevice;
import org.glavo.ruyi.imager.core.flash.FlashTarget;
import org.glavo.ruyi.imager.core.image.ImageEntry;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/// Pure GUI selection rules shared by the JavaFX window and unit tests.
@NotNullByDefault
final class GuiSelectionRules {
    /// Prevents construction of the selection-rule utility.
    private GuiSelectionRules() {
    }

    /// Returns whether an image requires partition-specific block targets.
    ///
    /// @param image image entry.
    /// @return whether the image should use a partition target map.
    static boolean requiresPartitionTargets(@Nullable ImageEntry image) {
        return image != null && "dd-v1".equals(image.strategy()) && image.partitionMap().size() > 1;
    }

    /// Returns whether partition target selections are complete and writable.
    ///
    /// @param image selected image.
    /// @param target selected target.
    /// @return whether every image partition has a unique writable block target.
    static boolean partitionTargetsReady(ImageEntry image, FlashTarget target) {
        if (!partitionTargetKeysMatch(image, target)) {
            return false;
        }

        Set<Path> paths = new HashSet<>();
        for (String partition : image.partitionMap().keySet()) {
            @Nullable BlockDevice blockDevice = target.blockDevices().get(partition);
            if (blockDevice == null || !targetWritable(blockDevice)) {
                return false;
            }
            if (!paths.add(blockDevice.path().toAbsolutePath().normalize())) {
                return false;
            }
        }
        return true;
    }

    /// Returns whether selected partition target keys match image partition keys.
    ///
    /// @param image selected image.
    /// @param target selected target.
    /// @return whether the target map contains exactly the required partitions.
    static boolean partitionTargetKeysMatch(@Nullable ImageEntry image, FlashTarget target) {
        return image != null
                && target.blockDevice() == null
                && target.fastbootDevice() == null
                && target.blockDevices().keySet().equals(image.partitionMap().keySet());
    }

    /// Keeps a target only when it matches the selected image source.
    ///
    /// @param target current target.
    /// @param image selected catalog image.
    /// @param localImage selected local image.
    /// @return compatible target, or null when target type no longer matches.
    static @Nullable FlashTarget compatibleTarget(
            @Nullable FlashTarget target,
            @Nullable ImageEntry image,
            @Nullable Path localImage) {
        if (target == null) {
            return null;
        }
        if (localImage != null) {
            return target.blockDevice() == null ? null : target;
        }
        if (image != null && fastbootStrategy(image.strategy())) {
            return target.isFastbootDevice() ? target : null;
        }
        if (requiresPartitionTargets(image)) {
            return partitionTargetKeysMatch(image, target) ? target : null;
        }
        return target.blockDevice() == null ? null : target;
    }

    /// Returns whether the current GUI writer can flash a catalog image.
    ///
    /// @param image image entry.
    /// @return whether the image is flashable through the current local writer.
    static boolean catalogImageFlashable(ImageEntry image) {
        if (image.support() != StrategySupport.SUPPORTED) {
            return false;
        }
        if ("dd-v1".equals(image.strategy())) {
            return !image.partitionMap().isEmpty();
        }
        return fastbootStrategy(image.strategy()) && !image.partitionMap().isEmpty();
    }

    /// Returns whether a strategy uses fastboot.
    ///
    /// @param strategy strategy name.
    /// @return whether this is a fastboot strategy.
    static boolean fastbootStrategy(String strategy) {
        return "fastboot-v1".equals(strategy) || "fastboot-v1(lpi4a-uboot)".equals(strategy);
    }

    /// Returns whether a target can be written by the GUI.
    ///
    /// @param target target device.
    /// @return whether the target is not blocked by safety flags.
    static boolean targetWritable(BlockDevice target) {
        return !target.system() && !target.readOnly() && (!target.mounted() || targetPreparablyMounted(target));
    }

    /// Returns whether a mounted target can be prepared by the current writer.
    ///
    /// @param target target device.
    /// @return whether the mounted target can be prepared automatically.
    static boolean targetPreparablyMounted(BlockDevice target) {
        return target.mounted() && target.id().startsWith("windows-disk-");
    }
}

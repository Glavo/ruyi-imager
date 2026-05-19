// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.gui;

import org.glavo.ruyi.imager.core.StrategySupport;
import org.glavo.ruyi.imager.core.device.BlockDevice;
import org.glavo.ruyi.imager.core.fastboot.FastbootDevice;
import org.glavo.ruyi.imager.core.flash.FlashTarget;
import org.glavo.ruyi.imager.core.image.ImageEntry;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for JavaFX-independent GUI selection rules.
@NotNullByDefault
public final class GuiSelectionRulesTest {
    /// Verifies catalog image strategy support classification.
    @Test
    public void classifiesFlashableCatalogImages() {
        assertTrue(GuiSelectionRules.catalogImageFlashable(image("dd-v1", Map.of("disk", "image.raw"))));
        assertTrue(GuiSelectionRules.catalogImageFlashable(image("fastboot-v1", Map.of("boot", "boot.img"))));
        assertTrue(GuiSelectionRules.catalogImageFlashable(image(
                "fastboot-v1(lpi4a-uboot)",
                Map.of("ram", "uboot.img", "uboot", "uboot.img"))));
        assertFalse(GuiSelectionRules.catalogImageFlashable(image("dd-v1", Map.of())));
        assertFalse(GuiSelectionRules.catalogImageFlashable(image("vendor-custom-v1", Map.of("disk", "image.raw"))));
        assertFalse(GuiSelectionRules.catalogImageFlashable(image(
                "dd-v1",
                Map.of("disk", "image.raw"),
                StrategySupport.UNKNOWN)));
    }

    /// Verifies target mode compatibility when the selected image source changes.
    @Test
    public void keepsOnlyCompatibleTargetModes() {
        BlockDevice disk = device("disk", Path.of("disk.raw"), false, false, false);
        BlockDevice boot = device("boot", Path.of("boot.raw"), false, false, false);
        BlockDevice root = device("root", Path.of("root.raw"), false, false, false);
        FlashTarget blockTarget = FlashTarget.blockDevice(disk);
        FlashTarget partitionTarget = FlashTarget.blockDevices(Map.of("boot", boot, "root", root));
        FlashTarget fastbootTarget = FlashTarget.fastbootDevice(new FastbootDevice("fb", "fb", "fastboot"));
        ImageEntry singleDd = image("dd-v1", Map.of("disk", "image.raw"));
        ImageEntry partitionDd = image("dd-v1", Map.of("boot", "boot.img", "root", "root.img"));
        ImageEntry fastboot = image("fastboot-v1", Map.of("boot", "boot.img"));

        assertSame(blockTarget, GuiSelectionRules.compatibleTarget(blockTarget, null, Path.of("local.raw")));
        assertNull(GuiSelectionRules.compatibleTarget(fastbootTarget, null, Path.of("local.raw")));
        assertSame(blockTarget, GuiSelectionRules.compatibleTarget(blockTarget, singleDd, null));
        assertNull(GuiSelectionRules.compatibleTarget(partitionTarget, singleDd, null));
        assertSame(partitionTarget, GuiSelectionRules.compatibleTarget(partitionTarget, partitionDd, null));
        assertNull(GuiSelectionRules.compatibleTarget(blockTarget, partitionDd, null));
        assertSame(fastbootTarget, GuiSelectionRules.compatibleTarget(fastbootTarget, fastboot, null));
        assertNull(GuiSelectionRules.compatibleTarget(blockTarget, fastboot, null));
    }

    /// Verifies multi-partition target completeness, uniqueness, and safety checks.
    @Test
    public void validatesPartitionTargets() {
        ImageEntry image = image("dd-v1", Map.of("boot", "boot.img", "root", "root.img"));
        BlockDevice boot = device("boot", Path.of("boot.raw"), false, false, false);
        BlockDevice root = device("root", Path.of("root.raw"), false, false, false);
        BlockDevice duplicate = device("duplicate", Path.of("boot.raw"), false, false, false);
        BlockDevice mounted = device("mounted", Path.of("mounted.raw"), false, true, false);
        BlockDevice fixed = device("fixed", Path.of("fixed.raw"), false, false, false, false);

        assertTrue(GuiSelectionRules.requiresPartitionTargets(image));
        assertTrue(GuiSelectionRules.partitionTargetsReady(image, FlashTarget.blockDevices(Map.of(
                "boot", boot,
                "root", root))));
        assertFalse(GuiSelectionRules.partitionTargetsReady(image, FlashTarget.blockDevices(Map.of(
                "boot", boot,
                "root", duplicate))));
        assertFalse(GuiSelectionRules.partitionTargetsReady(image, FlashTarget.blockDevices(Map.of(
                "boot", boot,
                "root", mounted))));
        assertFalse(GuiSelectionRules.partitionTargetsReady(image, FlashTarget.blockDevices(Map.of(
                "boot", boot,
                "root", fixed))));
        assertFalse(GuiSelectionRules.partitionTargetKeysMatch(image, FlashTarget.blockDevices(Map.of("boot", boot))));
        assertFalse(GuiSelectionRules.partitionTargetKeysMatch(image, FlashTarget.blockDevice(boot)));
    }

    /// Verifies target safety flags used by GUI buttons and list cells.
    @Test
    public void rejectsUnsafeBlockTargets() {
        assertTrue(GuiSelectionRules.targetWritable(device("ready", Path.of("ready.raw"), false, false, false)));
        assertFalse(GuiSelectionRules.targetWritable(device("system", Path.of("system.raw"), true, false, false)));
        assertFalse(GuiSelectionRules.targetWritable(device("mounted", Path.of("mounted.raw"), false, true, false)));
        assertTrue(GuiSelectionRules.targetWritable(device(
                "windows-disk-2",
                Path.of("\\\\.\\PHYSICALDRIVE2"),
                false,
                true,
                false,
                true)));
        assertFalse(GuiSelectionRules.targetWritable(device(
                "windows-disk-3",
                Path.of("\\\\.\\PHYSICALDRIVE3"),
                false,
                true,
                false,
                false)));
        assertFalse(GuiSelectionRules.targetWritable(device(
                "unknown-size-usb",
                Path.of("/dev/sdz"),
                false,
                false,
                false,
                true,
                0L,
                "usb")));
        assertTrue(GuiSelectionRules.targetWritable(device(
                "unknown-size-file",
                Path.of("unknown-size.raw"),
                false,
                false,
                false,
                true,
                0L,
                "file")));
        assertFalse(GuiSelectionRules.targetWritable(device("fixed", Path.of("fixed.raw"), false, false, false, false)));
        assertFalse(GuiSelectionRules.targetWritable(device("readonly", Path.of("readonly.raw"), false, false, true)));
    }

    /// Verifies default GUI target selectors hide unsupported block targets.
    @Test
    public void filtersUnsupportedBlockTargets() {
        BlockDevice ready = device("ready", Path.of("ready.raw"), false, false, false);
        BlockDevice system = device("system", Path.of("system.raw"), true, false, false);
        BlockDevice mounted = device("mounted", Path.of("mounted.raw"), false, true, false);
        BlockDevice preparableMounted = device(
                "windows-disk-2",
                Path.of("\\\\.\\PHYSICALDRIVE2"),
                false,
                true,
                false,
                true);
        BlockDevice unknownSize = device(
                "unknown-size-usb",
                Path.of("/dev/sdz"),
                false,
                false,
                false,
                true,
                0L,
                "usb");
        BlockDevice fixed = device("fixed", Path.of("fixed.raw"), false, false, false, false);
        BlockDevice readonly = device("readonly", Path.of("readonly.raw"), false, false, true);

        assertEquals(
                List.of(ready, preparableMounted),
                GuiSelectionRules.supportedTargets(List.of(
                        ready,
                        system,
                        mounted,
                        preparableMounted,
                        unknownSize,
                        fixed,
                        readonly)));
    }

    /// Creates a test image entry.
    ///
    /// @param strategy provision strategy.
    /// @param partitionMap partition map.
    /// @return test image entry.
    private static ImageEntry image(String strategy, @Unmodifiable Map<String, String> partitionMap) {
        return image(strategy, partitionMap, StrategySupport.SUPPORTED);
    }

    /// Creates a test image entry.
    ///
    /// @param strategy provision strategy.
    /// @param partitionMap partition map.
    /// @param support strategy support state.
    /// @return test image entry.
    private static ImageEntry image(
            String strategy,
            @Unmodifiable Map<String, String> partitionMap,
            StrategySupport support) {
        return new ImageEntry(
                "ruyisdk",
                "board-image",
                "test-board",
                "1.0.0",
                null,
                "board-image/test-board(1.0.0)",
                "Test image",
                "Test Manufacturer",
                "test-board",
                "generic",
                strategy,
                partitionMap,
                List.of(),
                support);
    }

    /// Creates a test block device.
    ///
    /// @param id device id.
    /// @param path device path.
    /// @param system whether the device is a system disk.
    /// @param mounted whether the device is mounted.
    /// @param readOnly whether the device is read-only.
    /// @return test block device.
    private static BlockDevice device(
            String id,
            Path path,
            boolean system,
            boolean mounted,
            boolean readOnly) {
        return device(id, path, system, mounted, readOnly, true);
    }

    /// Creates a test block device.
    ///
    /// @param id device id.
    /// @param path device path.
    /// @param system whether the device is a system disk.
    /// @param mounted whether the device is mounted.
    /// @param readOnly whether the device is read-only.
    /// @param removable whether the device is removable.
    /// @return test block device.
    private static BlockDevice device(
            String id,
            Path path,
            boolean system,
            boolean mounted,
            boolean readOnly,
            boolean removable) {
        return device(id, path, system, mounted, readOnly, removable, 1024L, "file");
    }

    /// Creates a test block device.
    ///
    /// @param id device id.
    /// @param path device path.
    /// @param system whether the device is a system disk.
    /// @param mounted whether the device is mounted.
    /// @param readOnly whether the device is read-only.
    /// @param removable whether the device is removable.
    /// @param sizeBytes device size in bytes.
    /// @param busType device bus type.
    /// @return test block device.
    private static BlockDevice device(
            String id,
            Path path,
            boolean system,
            boolean mounted,
            boolean readOnly,
            boolean removable,
            long sizeBytes,
            String busType) {
        return new BlockDevice(
                id,
                "Test Device",
                path,
                sizeBytes,
                removable,
                system,
                mounted,
                readOnly,
                "Test",
                busType);
    }
}

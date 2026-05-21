// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.fastboot;

import org.glavo.ruyi.imager.core.OperationResult;
import org.glavo.ruyi.imager.core.ProgressEvent;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for process-backed fastboot helpers.
@NotNullByDefault
public final class ProcessFastbootServiceTest {
    /// Parses standard fastboot device output.
    @Test
    public void parsesFastbootDevicesOutput() {
        List<FastbootDevice> devices = ProcessFastbootService.parseDevices("""
                abc123\tfastboot
                def456 fastbootd
                """);

        assertEquals(2, devices.size());
        assertEquals("abc123", devices.get(0).serial());
        assertEquals("fastboot", devices.get(0).state());
        assertEquals("def456", devices.get(1).serial());
        assertEquals("fastbootd", devices.get(1).state());
    }

    /// Follows the Ruyi LPi4A U-Boot handoff sequence without reconnect polling.
    @Test
    public void lpi4aUbootSleepsBeforeFlashingUboot() throws Exception {
        ArrayList<List<String>> commands = new ArrayList<>();
        ProcessFastbootService service = new ProcessFastbootService("fastboot-test", (command, timeout) -> {
            assertEquals(Duration.ofHours(4), timeout);
            commands.add(List.copyOf(command));
            return new ProcessFastbootService.CommandResult(0, "OKAY\n", false);
        });

        ArrayList<ProgressEvent> progress = new ArrayList<>();
        OperationResult result = service.flash(
                "fastboot-v1(lpi4a-uboot)",
                Map.of("uboot", Path.of("uboot.img")),
                new FastbootDevice("abc123", "abc123", "fastboot"),
                progress::add);

        assertTrue(result.success(), result.message());
        assertEquals(List.of(
                List.of("fastboot-test", "-s", "abc123", "flash", "ram", "uboot.img"),
                List.of("fastboot-test", "-s", "abc123", "reboot"),
                List.of("fastboot-test", "flash", "uboot", "uboot.img")), commands);
        ProgressEvent last = progress.getLast();
        assertEquals(4L, last.currentBytes());
        assertEquals(4L, last.totalBytes());
    }

    /// Reports partition progress in metadata order for standard fastboot images.
    @Test
    public void standardFastbootReportsPartitionProgressInMetadataOrder() throws Exception {
        ArrayList<List<String>> commands = new ArrayList<>();
        ProcessFastbootService service = new ProcessFastbootService("fastboot-test", (command, timeout) -> {
            assertEquals(Duration.ofHours(4), timeout);
            commands.add(List.copyOf(command));
            return new ProcessFastbootService.CommandResult(0, "OKAY\n", false);
        });

        LinkedHashMap<String, Path> partitions = new LinkedHashMap<>();
        partitions.put("root", Path.of("root.img"));
        partitions.put("boot", Path.of("boot.img"));

        ArrayList<ProgressEvent> progress = new ArrayList<>();
        OperationResult result = service.flash(
                "fastboot-v1",
                partitions,
                new FastbootDevice("abc123", "abc123", "fastboot"),
                progress::add);

        assertTrue(result.success(), result.message());
        assertEquals(List.of(
                List.of("fastboot-test", "-s", "abc123", "flash", "root", "root.img"),
                List.of("fastboot-test", "-s", "abc123", "flash", "boot", "boot.img")), commands);
        assertEquals(0L, progress.get(0).currentBytes());
        assertEquals(2L, progress.get(0).totalBytes());
        ProgressEvent last = progress.getLast();
        assertEquals(2L, last.currentBytes());
        assertEquals(2L, last.totalBytes());
    }

    /// Runs the SpacemiT K1 handoff and partition flashing sequence used by Bianbu eMMC images.
    @Test
    public void spacemitK1StagesBootloadersBeforeFlashingPartitions() throws Exception {
        ArrayList<List<String>> commands = new ArrayList<>();
        ProcessFastbootService service = new ProcessFastbootService("fastboot-test", (command, _) -> {
            commands.add(List.copyOf(command));
            return new ProcessFastbootService.CommandResult(0, "OKAY\n", false);
        });

        ArrayList<ProgressEvent> progress = new ArrayList<>();
        OperationResult result = service.flash(
                "spacemit-k1-v1",
                Map.of(
                        "bootfs", Path.of("bootfs.ext4"),
                        "bootinfo", Path.of("bootinfo.bin"),
                        "env", Path.of("env.bin"),
                        "fsbl", Path.of("FSBL.bin"),
                        "gpt", Path.of("partition_universal.json"),
                        "opensbi", Path.of("fw_dynamic.itb"),
                        "rootfs", Path.of("rootfs.ext4"),
                        "uboot", Path.of("u-boot.itb")),
                new FastbootDevice("abc123", "abc123", "fastboot"),
                progress::add);

        assertTrue(result.success(), result.message());
        assertEquals(List.of(
                List.of("fastboot-test", "-s", "abc123", "stage", "FSBL.bin"),
                List.of("fastboot-test", "-s", "abc123", "continue"),
                List.of("fastboot-test", "-s", "abc123", "stage", "u-boot.itb"),
                List.of("fastboot-test", "-s", "abc123", "continue"),
                List.of("fastboot-test", "-s", "abc123", "flash", "gpt", "partition_universal.json"),
                List.of("fastboot-test", "-s", "abc123", "flash", "bootinfo", "bootinfo.bin"),
                List.of("fastboot-test", "-s", "abc123", "flash", "fsbl", "FSBL.bin"),
                List.of("fastboot-test", "-s", "abc123", "flash", "env", "env.bin"),
                List.of("fastboot-test", "-s", "abc123", "flash", "opensbi", "fw_dynamic.itb"),
                List.of("fastboot-test", "-s", "abc123", "flash", "uboot", "u-boot.itb"),
                List.of("fastboot-test", "-s", "abc123", "flash", "bootfs", "bootfs.ext4"),
                List.of("fastboot-test", "-s", "abc123", "flash", "rootfs", "rootfs.ext4")), commands);
        ProgressEvent last = progress.getLast();
        assertEquals(14L, last.currentBytes());
        assertEquals(14L, last.totalBytes());
    }
}

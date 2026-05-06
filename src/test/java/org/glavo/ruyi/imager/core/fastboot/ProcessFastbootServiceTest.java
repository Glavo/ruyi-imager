// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.fastboot;

import org.glavo.ruyi.imager.core.OperationResult;
import org.glavo.ruyi.imager.core.ProgressEvent;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
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

    /// Waits for an LPi4A device to reconnect before flashing U-Boot.
    @Test
    public void lpi4aUbootWaitsForReconnectBeforeFlashingUboot() throws Exception {
        ArrayList<List<String>> commands = new ArrayList<>();
        ProcessFastbootService service = new ProcessFastbootService("fastboot-test", (command, _) -> {
            commands.add(List.copyOf(command));
            if (command.equals(List.of("fastboot-test", "devices"))) {
                return new ProcessFastbootService.CommandResult(0, "abc123\tfastboot\n", false);
            }
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
                List.of("fastboot-test", "devices"),
                List.of("fastboot-test", "-s", "abc123", "flash", "uboot", "uboot.img")), commands);
        ProgressEvent last = progress.getLast();
        assertEquals(4L, last.currentBytes());
        assertEquals(4L, last.totalBytes());
    }

    /// Reports deterministic partition progress for standard fastboot images.
    @Test
    public void standardFastbootReportsPartitionProgress() throws Exception {
        ArrayList<List<String>> commands = new ArrayList<>();
        ProcessFastbootService service = new ProcessFastbootService("fastboot-test", (command, _) -> {
            commands.add(List.copyOf(command));
            return new ProcessFastbootService.CommandResult(0, "OKAY\n", false);
        });

        ArrayList<ProgressEvent> progress = new ArrayList<>();
        OperationResult result = service.flash(
                "fastboot-v1",
                Map.of("root", Path.of("root.img"), "boot", Path.of("boot.img")),
                new FastbootDevice("abc123", "abc123", "fastboot"),
                progress::add);

        assertTrue(result.success(), result.message());
        assertEquals(List.of(
                List.of("fastboot-test", "-s", "abc123", "flash", "boot", "boot.img"),
                List.of("fastboot-test", "-s", "abc123", "flash", "root", "root.img")), commands);
        assertEquals(0L, progress.get(0).currentBytes());
        assertEquals(2L, progress.get(0).totalBytes());
        ProgressEvent last = progress.getLast();
        assertEquals(2L, last.currentBytes());
        assertEquals(2L, last.totalBytes());
    }
}

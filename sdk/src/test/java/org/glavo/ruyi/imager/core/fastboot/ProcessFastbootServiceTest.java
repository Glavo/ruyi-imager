// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.fastboot;

import org.glavo.ruyi.imager.core.OperationResult;
import org.glavo.ruyi.imager.core.ProgressEvent;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /// Filters fastboot devices whose serial is not unique.
    ///
    /// @throws Exception when listing fails unexpectedly.
    @Test
    public void listDevicesFiltersDuplicateSerials() throws Exception {
        ProcessFastbootService service = new ProcessFastbootService("fastboot-test", (command, timeout) -> {
            assertEquals(List.of("fastboot-test", "devices"), command);
            assertEquals(Duration.ofSeconds(15), timeout);
            return new ProcessFastbootService.CommandResult(
                    0,
                    "same\tfastboot\nunique\tfastboot\nsame\tfastbootd\n",
                    false);
        });

        List<FastbootDevice> devices = service.listDevices();

        assertEquals(1, devices.size());
        assertEquals("unique", devices.getFirst().serial());
    }

    /// Keeps using the selected serial when LPi4A U-Boot exposes the same fastboot serial.
    @Test
    public void lpi4aUbootKeepsStableSerial() throws Exception {
        ArrayList<List<String>> commands = new ArrayList<>();
        int[] devicesCalls = {0};
        ProcessFastbootService service = new ProcessFastbootService("fastboot-test", (command, timeout) -> {
            commands.add(List.copyOf(command));
            if (command.equals(List.of("fastboot-test", "devices"))) {
                devicesCalls[0]++;
                assertEquals(Duration.ofSeconds(3), timeout);
                return new ProcessFastbootService.CommandResult(0, "abc123\tfastboot\nother\tfastboot\n", false);
            }
            assertEquals(Duration.ofHours(4), timeout);
            return new ProcessFastbootService.CommandResult(0, "OKAY\n", false);
        });

        ArrayList<ProgressEvent> progress = new ArrayList<>();
        OperationResult result = service.flash(
                "fastboot-v1(lpi4a-uboot)",
                Map.of("uboot", Path.of("uboot.img")),
                new FastbootDevice("abc123", "abc123", "fastboot"),
                progress::add);

        assertTrue(result.success(), result.message());
        assertEquals(3, devicesCalls[0]);
        assertEquals(List.of(
                List.of("fastboot-test", "devices"),
                List.of("fastboot-test", "-s", "abc123", "flash", "ram", "uboot.img"),
                List.of("fastboot-test", "devices"),
                List.of("fastboot-test", "-s", "abc123", "reboot"),
                List.of("fastboot-test", "devices"),
                List.of("fastboot-test", "-s", "abc123", "flash", "uboot", "uboot.img")), commands);
        ProgressEvent last = progress.getLast();
        assertEquals(4000L, last.currentBytes());
        assertEquals(4000L, last.totalBytes());
    }

    /// Switches to the only visible serial when LPi4A U-Boot changes the fastboot serial.
    @Test
    public void lpi4aUbootUsesUniqueChangedSerial() throws Exception {
        ArrayList<List<String>> commands = new ArrayList<>();
        int[] devicesCalls = {0};
        ProcessFastbootService service = new ProcessFastbootService("fastboot-test", (command, timeout) -> {
            commands.add(List.copyOf(command));
            if (command.equals(List.of("fastboot-test", "devices"))) {
                devicesCalls[0]++;
                assertEquals(Duration.ofSeconds(3), timeout);
                if (devicesCalls[0] <= 2) {
                    return new ProcessFastbootService.CommandResult(0, "abc123\tfastboot\nother\tfastboot\n", false);
                }
                return new ProcessFastbootService.CommandResult(0, "other\tfastboot\nnew456\tfastboot\n", false);
            }
            assertEquals(Duration.ofHours(4), timeout);
            return new ProcessFastbootService.CommandResult(0, "OKAY\n", false);
        });

        OperationResult result = service.flash(
                "fastboot-v1(lpi4a-uboot)",
                Map.of("uboot", Path.of("uboot.img")),
                new FastbootDevice("abc123", "abc123", "fastboot"),
                _ -> {
                });

        assertTrue(result.success(), result.message());
        assertEquals(3, devicesCalls[0]);
        assertEquals(List.of(
                List.of("fastboot-test", "devices"),
                List.of("fastboot-test", "-s", "abc123", "flash", "ram", "uboot.img"),
                List.of("fastboot-test", "devices"),
                List.of("fastboot-test", "-s", "abc123", "reboot"),
                List.of("fastboot-test", "devices"),
                List.of("fastboot-test", "-s", "new456", "flash", "uboot", "uboot.img")), commands);
    }

    /// Keeps waiting when only a pre-existing non-target serial is visible after LPi4A U-Boot handoff.
    @Test
    public void lpi4aUbootDoesNotUsePreExistingNonTargetSerial() throws Exception {
        ArrayList<List<String>> commands = new ArrayList<>();
        int[] devicesCalls = {0};
        ProcessFastbootService service = new ProcessFastbootService("fastboot-test", (command, timeout) -> {
            commands.add(List.copyOf(command));
            if (command.equals(List.of("fastboot-test", "devices"))) {
                devicesCalls[0]++;
                assertEquals(Duration.ofSeconds(3), timeout);
                if (devicesCalls[0] <= 2) {
                    return new ProcessFastbootService.CommandResult(0, "abc123\tfastboot\nother\tfastboot\n", false);
                }
                if (devicesCalls[0] == 3) {
                    return new ProcessFastbootService.CommandResult(0, "other\tfastboot\n", false);
                }
                return new ProcessFastbootService.CommandResult(0, "other\tfastboot\nnew456\tfastboot\n", false);
            }
            assertEquals(Duration.ofHours(4), timeout);
            return new ProcessFastbootService.CommandResult(0, "OKAY\n", false);
        });

        OperationResult result = service.flash(
                "fastboot-v1(lpi4a-uboot)",
                Map.of("uboot", Path.of("uboot.img")),
                new FastbootDevice("abc123", "abc123", "fastboot"),
                _ -> {
                });

        assertTrue(result.success(), result.message());
        assertEquals(4, devicesCalls[0]);
        assertEquals(List.of(
                List.of("fastboot-test", "devices"),
                List.of("fastboot-test", "-s", "abc123", "flash", "ram", "uboot.img"),
                List.of("fastboot-test", "devices"),
                List.of("fastboot-test", "-s", "abc123", "reboot"),
                List.of("fastboot-test", "devices"),
                List.of("fastboot-test", "devices"),
                List.of("fastboot-test", "-s", "new456", "flash", "uboot", "uboot.img")), commands);
    }

    /// Reports an actionable failure when the selected LPi4A fastboot device has no RAM handoff target.
    @Test
    public void lpi4aUbootReportsMissingRamTarget() throws Exception {
        ArrayList<List<String>> commands = new ArrayList<>();
        ProcessFastbootService service = new ProcessFastbootService("fastboot-test", (command, timeout) -> {
            commands.add(List.copyOf(command));
            if (command.equals(List.of("fastboot-test", "devices"))) {
                assertEquals(Duration.ofSeconds(3), timeout);
                return new ProcessFastbootService.CommandResult(0, "abc123\tfastboot\n", false);
            }
            assertEquals(Duration.ofHours(4), timeout);
            return new ProcessFastbootService.CommandResult(
                    1,
                    """
                            Sending 'ram' (969 KB) OKAY
                            Writing 'ram' FAILED (remote: 'cannot find partition')
                            fastboot: error: Command failed
                            """,
                    false);
        });

        OperationResult result = service.flash(
                "fastboot-v1(lpi4a-uboot)",
                Map.of("uboot", Path.of("uboot.img")),
                new FastbootDevice("abc123", "abc123", "fastboot"),
                _ -> {
                });

        assertFalse(result.success());
        assertTrue(result.message().contains("does not accept the LPi4A RAM U-Boot handoff"), result.message());
        assertEquals(List.of(
                List.of("fastboot-test", "devices"),
                List.of("fastboot-test", "-s", "abc123", "flash", "ram", "uboot.img")), commands);
    }

    /// Refuses to continue when LPi4A U-Boot changes serial and multiple fastboot devices are visible.
    @Test
    public void lpi4aUbootRefusesAmbiguousChangedSerial() throws Exception {
        ArrayList<List<String>> commands = new ArrayList<>();
        int[] devicesCalls = {0};
        ProcessFastbootService service = new ProcessFastbootService("fastboot-test", (command, timeout) -> {
            commands.add(List.copyOf(command));
            if (command.equals(List.of("fastboot-test", "devices"))) {
                devicesCalls[0]++;
                assertEquals(Duration.ofSeconds(3), timeout);
                if (devicesCalls[0] <= 2) {
                    return new ProcessFastbootService.CommandResult(0, "abc123\tfastboot\nother\tfastboot\n", false);
                }
                return new ProcessFastbootService.CommandResult(
                        0,
                        "other\tfastboot\nnew456\tfastboot\nnew789\tfastboot\n",
                        false);
            }
            assertEquals(Duration.ofHours(4), timeout);
            return new ProcessFastbootService.CommandResult(0, "OKAY\n", false);
        });

        OperationResult result = service.flash(
                "fastboot-v1(lpi4a-uboot)",
                Map.of("uboot", Path.of("uboot.img")),
                new FastbootDevice("abc123", "abc123", "fastboot"),
                _ -> {
                });

        assertFalse(result.success());
        assertEquals(3, devicesCalls[0]);
        assertEquals(List.of(
                List.of("fastboot-test", "devices"),
                List.of("fastboot-test", "-s", "abc123", "flash", "ram", "uboot.img"),
                List.of("fastboot-test", "devices"),
                List.of("fastboot-test", "-s", "abc123", "reboot"),
                List.of("fastboot-test", "devices")), commands);
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
        assertEquals(2000L, progress.get(0).totalBytes());
        ProgressEvent last = progress.getLast();
        assertEquals(2000L, last.currentBytes());
        assertEquals(2000L, last.totalBytes());
    }

    /// Reports parsed sparse fastboot output as sub-step progress.
    @Test
    public void standardFastbootReportsSparseOutputProgress() throws Exception {
        ProcessFastbootService service = new ProcessFastbootService("fastboot-test", (command, timeout) -> {
            assertEquals(Duration.ofHours(4), timeout);
            assertEquals(List.of("fastboot-test", "-s", "abc123", "flash", "root", "root.img"), command);
            return new ProcessFastbootService.CommandResult(
                    0,
                    """
                            Sending sparse 'root' 1/4 (262140 KB) OKAY [  1.000s]
                            Writing 'root' OKAY [  1.000s]
                            Sending sparse 'root' 2/4 (262140 KB) OKAY [  1.000s]
                            Writing 'root' OKAY [  1.000s]
                            Sending sparse 'root' 3/4 (262140 KB) OKAY [  1.000s]
                            Writing 'root' OKAY [  1.000s]
                            Sending sparse 'root' 4/4 (1000 KB) OKAY [  1.000s]
                            Writing 'root' OKAY [  1.000s]
                            """,
                    false);
        });

        ArrayList<ProgressEvent> progress = new ArrayList<>();
        OperationResult result = service.flash(
                "fastboot-v1",
                Map.of("root", Path.of("root.img")),
                new FastbootDevice("abc123", "abc123", "fastboot"),
                progress::add);

        assertTrue(result.success(), result.message());
        boolean sawSparseChunk = false;
        ArrayList<Long> writingProgress = new ArrayList<>();
        long previousProgress = -1L;
        for (ProgressEvent event : progress) {
            @Nullable Long currentBytes = event.currentBytes();
            if (currentBytes != null) {
                assertTrue(currentBytes >= previousProgress, event.toString());
                previousProgress = currentBytes;
            }
            if (event.message().contains("chunk 2/4")) {
                sawSparseChunk = true;
                assertEquals(375L, event.currentBytes());
                assertEquals(1000L, event.totalBytes());
            }
            if (event.message().equals("Writing fastboot partition root")) {
                assertTrue(currentBytes != null);
                writingProgress.add(currentBytes);
                assertEquals(1000L, event.totalBytes());
            }
        }
        assertTrue(sawSparseChunk);
        assertEquals(List.of(250L, 500L, 750L, 1000L), writingProgress);
    }

    /// Accepts fastboot flash commands that complete successfully despite a non-zero exit code.
    @Test
    public void standardFastbootAcceptsSuccessfulSparseFlashWithNonZeroExitCode() throws Exception {
        ProcessFastbootService service = new ProcessFastbootService("fastboot-test", (command, timeout) -> {
            assertEquals(Duration.ofHours(4), timeout);
            assertEquals(List.of("fastboot-test", "-s", "abc123", "flash", "root", "root.ext4"), command);
            return new ProcessFastbootService.CommandResult(
                    1,
                    """
                            Warning: skip copying root image avb footer (root partition size: 0, root image size: 4294967296).
                            Invalid sparse file format at header magic
                            Sending sparse 'root' 1/2 (1148 KB) OKAY [  0.304s]
                            Writing 'root' OKAY [  0.002s]
                            Sending sparse 'root' 2/2 (1148 KB) OKAY [  0.304s]
                            Writing 'root' OKAY [  0.002s]
                            Finished. Total time: 1.234s
                            """,
                    false);
        });

        ArrayList<ProgressEvent> progress = new ArrayList<>();
        OperationResult result = service.flash(
                "fastboot-v1",
                Map.of("root", Path.of("root.ext4")),
                new FastbootDevice("abc123", "abc123", "fastboot"),
                progress::add);

        assertTrue(result.success(), result.message());
        ProgressEvent last = progress.getLast();
        assertEquals(1000L, last.currentBytes());
        assertEquals(1000L, last.totalBytes());
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
        assertEquals(14000L, last.currentBytes());
        assertEquals(14000L, last.totalBytes());
    }
}

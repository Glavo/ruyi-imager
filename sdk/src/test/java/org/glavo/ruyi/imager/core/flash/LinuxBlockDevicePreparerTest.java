// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.flash;

import org.glavo.ruyi.imager.core.ProcessOutputCapture;
import org.glavo.ruyi.imager.core.ProgressEvent;
import org.glavo.ruyi.imager.core.device.BlockDevice;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for Linux block-device unmount preparation.
@NotNullByDefault
public final class LinuxBlockDevicePreparerTest {
    /// Verifies Linux mounted removable disks are considered preparable.
    @Test
    public void detectsPreparableLinuxTargets() {
        LinuxBlockDevicePreparer preparer = new LinuxBlockDevicePreparer(new RecordingRunner(List.of()));

        assertTrue(preparer.canPrepareMounted(target("linux-disk-sdb", Path.of("/dev/sdb"), true, false, true)));
        assertFalse(preparer.canPrepareMounted(target("linux-disk-sdb", Path.of("/dev/sdb"), true, false, false)));
        assertFalse(preparer.canPrepareMounted(target("linux-disk-sdb", Path.of("/dev/sdb"), false, false, true)));
        assertFalse(preparer.canPrepareMounted(target("linux-disk-sdb", Path.of("/dev/sdb"), true, true, true)));
        assertFalse(preparer.canPrepareMounted(target("windows-disk-2", Path.of("\\\\.\\PHYSICALDRIVE2"), true, false, true)));
    }

    /// Verifies mounted child block devices are unmounted through udisksctl.
    ///
    /// @throws Exception when preparation fails.
    @Test
    public void unmountsMountedChildrenWithUdisksctl() throws Exception {
        RecordingRunner runner = new RecordingRunner(List.of(
                success("""
                        {
                          "blockdevices": [
                            {
                              "path": "/dev/sdb",
                              "type": "disk",
                              "mountpoints": [null],
                              "children": [
                                {
                                  "path": "/dev/sdb1",
                                  "type": "part",
                                  "mountpoints": ["/media/alice/BOOT"]
                                },
                                {
                                  "path": "/dev/sdb2",
                                  "type": "part",
                                  "mountpoint": "/media/alice/rootfs"
                                }
                              ]
                            }
                          ]
                        }
                        """),
                success(""),
                success("")));
        LinuxBlockDevicePreparer preparer = new LinuxBlockDevicePreparer(runner);
        ArrayList<ProgressEvent> events = new ArrayList<>();

        BlockDevice prepared = preparer.prepare(
                target("linux-disk-sdb", Path.of("/dev/sdb"), true, false, true),
                events::add);
        String targetPath = Path.of("/dev/sdb").toString();

        assertFalse(prepared.mounted());
        assertEquals(List.of(), prepared.mountPoints());
        assertEquals(List.of(
                List.of("lsblk", "--json", "--bytes", "--output", "PATH,TYPE,MOUNTPOINT,MOUNTPOINTS", targetPath),
                List.of("udisksctl", "unmount", "-b", "/dev/sdb1"),
                List.of("udisksctl", "unmount", "-b", "/dev/sdb2")), runner.commands());
        assertEquals(List.of("prepare", "prepare"), events.stream().map(ProgressEvent::stage).toList());
        assertNull(events.getFirst().currentBytes());
        assertNull(events.getFirst().totalBytes());
        assertEquals(1L, events.getLast().currentBytes());
        assertEquals(1L, events.getLast().totalBytes());
    }

    /// Verifies mount point unmount is used when udisksctl fails.
    ///
    /// @throws Exception when preparation fails.
    @Test
    public void fallsBackToUmountWhenUdisksctlFails() throws Exception {
        RecordingRunner runner = new RecordingRunner(List.of(
                success("""
                        {
                          "blockdevices": [
                            {
                              "path": "/dev/sdb",
                              "type": "disk",
                              "children": [
                                {
                                  "path": "/dev/sdb1",
                                  "type": "part",
                                  "mountpoints": ["/media/alice/BOOT"]
                                }
                              ]
                            }
                          ]
                        }
                        """),
                failure("udisksctl refused"),
                success("")));
        LinuxBlockDevicePreparer preparer = new LinuxBlockDevicePreparer(runner);
        String targetPath = Path.of("/dev/sdb").toString();

        preparer.prepare(target("linux-disk-sdb", Path.of("/dev/sdb"), true, false, true), _ -> {
        });

        assertEquals(List.of(
                List.of("lsblk", "--json", "--bytes", "--output", "PATH,TYPE,MOUNTPOINT,MOUNTPOINTS", targetPath),
                List.of("udisksctl", "unmount", "-b", "/dev/sdb1"),
                List.of("umount", "/media/alice/BOOT")), runner.commands());
    }

    /// Verifies unmount failures are surfaced.
    @Test
    public void reportsUnmountFailure() {
        RecordingRunner runner = new RecordingRunner(List.of(
                success("""
                        {
                          "blockdevices": [
                            {
                              "path": "/dev/sdb",
                              "type": "disk",
                              "children": [
                                {
                                  "path": "/dev/sdb1",
                                  "type": "part",
                                  "mountpoints": ["/media/alice/BOOT"]
                                }
                              ]
                            }
                          ]
                        }
                        """),
                failure("udisksctl refused"),
                failure("umount refused")));
        LinuxBlockDevicePreparer preparer = new LinuxBlockDevicePreparer(runner);

        Exception exception = assertThrows(
                Exception.class,
                () -> preparer.prepare(target("linux-disk-sdb", Path.of("/dev/sdb"), true, false, true), _ -> {
                }));

        assertTrue(exception.getMessage().contains("Failed to unmount"));
    }

    /// Creates a test target.
    ///
    /// @param id target id.
    /// @param path target path.
    /// @param removable whether target is removable.
    /// @param system whether target is a system disk.
    /// @param mounted whether target is mounted.
    /// @return test target.
    private static BlockDevice target(
            String id,
            Path path,
            boolean removable,
            boolean system,
            boolean mounted) {
        return new BlockDevice(
                id,
                id,
                path,
                1024L,
                removable,
                system,
                mounted,
                false,
                "Test Disk",
                "usb",
                "serial=test",
                mounted ? List.of("/media/alice/BOOT") : List.of());
    }

    /// Creates a successful command result.
    ///
    /// @param output command output.
    /// @return command result.
    private static ProcessOutputCapture.Result success(String output) {
        return new ProcessOutputCapture.Result(0, output, "", false);
    }

    /// Creates a failed command result.
    ///
    /// @param error command error.
    /// @return command result.
    private static ProcessOutputCapture.Result failure(String error) {
        return new ProcessOutputCapture.Result(1, "", error, false);
    }

    /// Recording command runner for tests.
    @NotNullByDefault
    private static final class RecordingRunner implements LinuxBlockDevicePreparer.CommandRunner {
        /// Commands captured by this runner.
        private final ArrayList<@Unmodifiable List<String>> commands = new ArrayList<>();

        /// Results returned by this runner.
        private final ArrayList<ProcessOutputCapture.Result> results;

        /// Creates a runner with precomputed results.
        ///
        /// @param results command results.
        private RecordingRunner(List<ProcessOutputCapture.Result> results) {
            this.results = new ArrayList<>(results);
        }

        /// Runs one recorded command.
        ///
        /// @param command command line.
        /// @param timeout command timeout.
        /// @return next command result.
        @Override
        public ProcessOutputCapture.Result run(@Unmodifiable List<String> command, Duration timeout) {
            commands.add(List.copyOf(command));
            if (results.isEmpty()) {
                throw new AssertionError("Unexpected command: " + command);
            }
            return results.removeFirst();
        }

        /// Returns recorded commands.
        ///
        /// @return recorded commands.
        private @Unmodifiable List<@Unmodifiable List<String>> commands() {
            return List.copyOf(commands);
        }
    }
}

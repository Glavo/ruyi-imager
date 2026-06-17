// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.device;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for Linux `lsblk` JSON parsing.
@NotNullByDefault
public final class LinuxBlockDeviceServiceTest {
    /// Parses disk nodes and recursive mount points from `lsblk` JSON.
    ///
    /// @throws IOException when parsing fails.
    @Test
    public void parsesLsblkDiskArray() throws IOException {
        String json = """
                {
                  "blockdevices": [
                    {
                      "name": "nvme0n1",
                      "kname": "nvme0n1",
                      "path": "/dev/nvme0n1",
                      "type": "disk",
                      "size": 512110190592,
                      "rm": false,
                      "ro": false,
                      "model": "Internal NVMe",
                      "tran": "nvme",
                      "serial": "NVME123",
                      "wwn": "eui.1234",
                      "hotplug": false,
                      "mountpoints": [null],
                      "children": [
                        {
                          "name": "nvme0n1p1",
                          "type": "part",
                          "size": 1073741824,
                          "mountpoints": ["/boot"]
                        },
                        {
                          "name": "nvme0n1p2",
                          "type": "part",
                          "size": 511036448768,
                          "mountpoints": ["/"]
                        }
                      ]
                    },
                    {
                      "name": "sdb",
                      "kname": "sdb",
                      "path": "/dev/sdb",
                      "type": "disk",
                      "size": "31914983424",
                      "rm": 0,
                      "ro": 1,
                      "model": "USB Reader",
                      "tran": "usb",
                      "serial": "USB123",
                      "wwn": "0x5000000000000001",
                      "hotplug": true,
                      "mountpoint": null,
                      "children": [
                        {
                          "name": "sdb1",
                          "type": "part",
                          "mountpoints": ["/media/user/BOOT", "/media/user/rootfs"]
                        }
                      ]
                    },
                    {
                      "name": "loop0",
                      "path": "/dev/loop0",
                      "type": "loop",
                      "size": 1
                    }
                  ]
                }
                """;

        List<BlockDevice> devices = LinuxBlockDeviceService.parseDevices(json);

        assertEquals(2, devices.size());

        BlockDevice systemDisk = devices.get(0);
        assertEquals("linux-disk-nvme0n1", systemDisk.id());
        assertEquals("/dev/nvme0n1", normalizedPath(systemDisk));
        assertEquals(512110190592L, systemDisk.sizeBytes());
        assertTrue(systemDisk.system());
        assertTrue(systemDisk.mounted());
        assertFalse(systemDisk.readOnly());
        assertFalse(systemDisk.removable());
        assertEquals(List.of("/boot", "/"), systemDisk.mountPoints());
        assertEquals("Internal NVMe", systemDisk.model());
        assertEquals("nvme", systemDisk.busType());
        assertEquals("serial=NVME123;wwn=eui.1234", systemDisk.hardwareId());

        BlockDevice removableDisk = devices.get(1);
        assertEquals("linux-disk-sdb", removableDisk.id());
        assertEquals("/dev/sdb", normalizedPath(removableDisk));
        assertEquals(31914983424L, removableDisk.sizeBytes());
        assertFalse(removableDisk.system());
        assertTrue(removableDisk.mounted());
        assertTrue(removableDisk.readOnly());
        assertTrue(removableDisk.removable());
        assertEquals(List.of("/media/user/BOOT", "/media/user/rootfs"), removableDisk.mountPoints());
        assertTrue(removableDisk.displayName().contains("USB Reader"));
        assertEquals("serial=USB123;wwn=0x5000000000000001", removableDisk.hardwareId());
    }

    /// Ignores invalid or non-disk nodes.
    ///
    /// @throws IOException when parsing fails.
    @Test
    public void ignoresNonDiskNodes() throws IOException {
        List<BlockDevice> devices = LinuxBlockDeviceService.parseDevices("""
                {"blockdevices":[{"name":"loop0","type":"loop"},{"type":"disk"}]}
                """);

        assertTrue(devices.isEmpty());
    }

    /// Does not treat HOTPLUG alone as removable for non-USB transports.
    ///
    /// @throws IOException when parsing fails.
    @Test
    public void ignoresHotplugForNonUsbTransports() throws IOException {
        List<BlockDevice> devices = LinuxBlockDeviceService.parseDevices("""
                {
                  "blockdevices": [
                    {
                      "name": "sda",
                      "kname": "sda",
                      "path": "/dev/sda",
                      "type": "disk",
                      "size": 1024,
                      "rm": false,
                      "ro": false,
                      "model": "Hotplug SATA",
                      "tran": "sata",
                      "hotplug": true
                    }
                  ]
                }
                """);

        assertEquals(1, devices.size());
        assertFalse(devices.getFirst().removable());
    }

    /// Parses empty payloads as an empty list.
    ///
    /// @throws IOException when parsing fails.
    @Test
    public void parsesEmptyPayload() throws IOException {
        assertTrue(LinuxBlockDeviceService.parseDevices("").isEmpty());
        assertTrue(LinuxBlockDeviceService.parseDevices("{}").isEmpty());
    }

    /// Normalizes a platform path for cross-platform test assertions.
    ///
    /// @param device block device.
    /// @return normalized path text.
    private static String normalizedPath(BlockDevice device) {
        return device.path().toString().replace('\\', '/');
    }
}

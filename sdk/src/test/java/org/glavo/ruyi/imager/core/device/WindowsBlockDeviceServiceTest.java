// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.device;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for Windows disk enumeration JSON parsing.
@NotNullByDefault
public final class WindowsBlockDeviceServiceTest {
    /// Parses a normal multi-disk PowerShell JSON payload.
    ///
    /// @throws IOException when parsing fails.
    @Test
    public void parsesPowerShellDiskArray() throws IOException {
        String json = """
                [
                  {
                    "index": 0,
                    "deviceId": "\\\\\\\\.\\\\PHYSICALDRIVE0",
                    "model": "Internal NVMe",
                    "busType": "NVMe",
                    "mediaType": "Fixed hard disk media",
                    "sizeBytes": "512110190592",
                    "removable": false,
                    "system": true,
                    "mounted": true,
                    "hardwareId": "uniqueId=system",
                    "mountPoints": ["C:\\\\", "D:\\\\"],
                    "readOnly": false
                  },
                  {
                    "index": 2,
                    "deviceId": "\\\\\\\\.\\\\PHYSICALDRIVE2",
                    "model": "USB Reader",
                    "busType": "USB",
                    "mediaType": "Removable Media",
                    "sizeBytes": "31914983424",
                    "removable": true,
                    "system": false,
                    "mounted": false,
                    "hardwareId": "pnpDeviceId=USBSTOR\\\\DISK&VEN_TEST",
                    "readOnly": false
                  }
                ]
                """;

        List<BlockDevice> devices = WindowsBlockDeviceService.parseDevices(json);

        assertEquals(2, devices.size());

        BlockDevice systemDisk = devices.get(0);
        assertEquals("windows-disk-0", systemDisk.id());
        assertTrue(systemDisk.path().toString().startsWith("\\\\.\\PHYSICALDRIVE0"));
        assertEquals(512110190592L, systemDisk.sizeBytes());
        assertTrue(systemDisk.system());
        assertTrue(systemDisk.mounted());
        assertEquals(List.of("C:\\", "D:\\"), systemDisk.mountPoints());
        assertFalse(systemDisk.removable());
        assertEquals("Internal NVMe", systemDisk.model());
        assertEquals("NVMe", systemDisk.busType());
        assertEquals("uniqueId=system", systemDisk.hardwareId());

        BlockDevice removableDisk = devices.get(1);
        assertEquals("windows-disk-2", removableDisk.id());
        assertTrue(removableDisk.path().toString().startsWith("\\\\.\\PHYSICALDRIVE2"));
        assertTrue(removableDisk.removable());
        assertFalse(removableDisk.system());
        assertFalse(removableDisk.mounted());
        assertTrue(removableDisk.displayName().contains("USB Reader"));
        assertTrue(removableDisk.displayName().contains("USB"));
        assertEquals("pnpDeviceId=USBSTOR\\DISK&VEN_TEST", removableDisk.hardwareId());
    }

    /// Parses a single-object payload and fills missing optional fields.
    ///
    /// @throws IOException when parsing fails.
    @Test
    public void parsesSingleDiskObjectWithFallbacks() throws IOException {
        String json = """
                {
                  "index": 3,
                  "sizeBytes": 0,
                  "removable": false,
                  "system": false,
                  "mounted": false,
                  "readOnly": true
                }
                """;

        List<BlockDevice> devices = WindowsBlockDeviceService.parseDevices(json);

        assertEquals(1, devices.size());
        BlockDevice device = devices.getFirst();
        assertEquals("windows-disk-3", device.id());
        assertTrue(device.path().toString().startsWith("\\\\.\\PHYSICALDRIVE3"));
        assertEquals("Disk 3", device.displayName());
        assertFalse(device.mounted());
        assertTrue(device.mountPoints().isEmpty());
        assertTrue(device.readOnly());
        assertNull(device.model());
        assertNull(device.busType());
    }

    /// Ignores invalid disk objects without an index.
    ///
    /// @throws IOException when parsing fails.
    @Test
    public void ignoresObjectsWithoutDiskIndex() throws IOException {
        List<BlockDevice> devices = WindowsBlockDeviceService.parseDevices("""
                [{"model":"Broken"}]
                """);

        assertTrue(devices.isEmpty());
    }
}

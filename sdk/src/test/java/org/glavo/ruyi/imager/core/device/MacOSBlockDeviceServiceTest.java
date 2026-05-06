// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.device;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for macOS `diskutil` plist parsing.
@NotNullByDefault
public final class MacOSBlockDeviceServiceTest {
    /// Parses whole disks and partition mount points from `diskutil` plist payloads.
    ///
    /// @throws IOException when parsing fails.
    @Test
    public void parsesDiskutilPlists() throws IOException {
        String listPlist = """
                <?xml version="1.0" encoding="UTF-8"?>
                <plist version="1.0">
                <dict>
                  <key>AllDisksAndPartitions</key>
                  <array>
                    <dict>
                      <key>DeviceIdentifier</key>
                      <string>disk0</string>
                      <key>Size</key>
                      <integer>500277790720</integer>
                      <key>Content</key>
                      <string>GUID_partition_scheme</string>
                      <key>Partitions</key>
                      <array>
                        <dict>
                          <key>DeviceIdentifier</key>
                          <string>disk0s1</string>
                          <key>MountPoint</key>
                          <string>/System/Volumes/Data</string>
                        </dict>
                        <dict>
                          <key>DeviceIdentifier</key>
                          <string>disk0s2</string>
                          <key>MountPoint</key>
                          <string>/</string>
                        </dict>
                      </array>
                    </dict>
                    <dict>
                      <key>DeviceIdentifier</key>
                      <string>disk2</string>
                      <key>Size</key>
                      <integer>31914983424</integer>
                      <key>Content</key>
                      <string>FDisk_partition_scheme</string>
                      <key>Partitions</key>
                      <array>
                        <dict>
                          <key>DeviceIdentifier</key>
                          <string>disk2s1</string>
                          <key>MountPoint</key>
                          <string>/Volumes/BOOT</string>
                        </dict>
                      </array>
                    </dict>
                  </array>
                </dict>
                </plist>
                """;

        String disk0Info = """
                <?xml version="1.0" encoding="UTF-8"?>
                <plist version="1.0">
                <dict>
                  <key>DeviceIdentifier</key>
                  <string>disk0</string>
                  <key>DeviceNode</key>
                  <string>/dev/disk0</string>
                  <key>TotalSize</key>
                  <integer>500277790720</integer>
                  <key>MediaName</key>
                  <string>Apple SSD</string>
                  <key>BusProtocol</key>
                  <string>PCI-Express</string>
                  <key>Internal</key>
                  <true/>
                  <key>RemovableMedia</key>
                  <false/>
                  <key>ReadOnlyMedia</key>
                  <false/>
                  <key>Writable</key>
                  <true/>
                </dict>
                </plist>
                """;

        String disk2Info = """
                <?xml version="1.0" encoding="UTF-8"?>
                <plist version="1.0">
                <dict>
                  <key>DeviceIdentifier</key>
                  <string>disk2</string>
                  <key>DeviceNode</key>
                  <string>/dev/disk2</string>
                  <key>TotalSize</key>
                  <integer>31914983424</integer>
                  <key>MediaName</key>
                  <string>USB Reader</string>
                  <key>BusProtocol</key>
                  <string>USB</string>
                  <key>Internal</key>
                  <false/>
                  <key>RemovableMedia</key>
                  <true/>
                  <key>ReadOnlyMedia</key>
                  <true/>
                  <key>Writable</key>
                  <false/>
                </dict>
                </plist>
                """;

        List<BlockDevice> devices = MacOSBlockDeviceService.parseDevices(
                listPlist,
                Map.of("disk0", disk0Info, "disk2", disk2Info));

        assertEquals(2, devices.size());

        BlockDevice systemDisk = devices.get(0);
        assertEquals("macos-disk-disk0", systemDisk.id());
        assertEquals("/dev/disk0", normalizedPath(systemDisk));
        assertEquals(500277790720L, systemDisk.sizeBytes());
        assertTrue(systemDisk.system());
        assertTrue(systemDisk.mounted());
        assertFalse(systemDisk.readOnly());
        assertFalse(systemDisk.removable());
        assertEquals(List.of("/System/Volumes/Data", "/"), systemDisk.mountPoints());
        assertEquals("Apple SSD", systemDisk.model());
        assertEquals("PCI-Express", systemDisk.busType());

        BlockDevice removableDisk = devices.get(1);
        assertEquals("macos-disk-disk2", removableDisk.id());
        assertEquals("/dev/disk2", normalizedPath(removableDisk));
        assertEquals(31914983424L, removableDisk.sizeBytes());
        assertFalse(removableDisk.system());
        assertTrue(removableDisk.mounted());
        assertTrue(removableDisk.readOnly());
        assertTrue(removableDisk.removable());
        assertEquals(List.of("/Volumes/BOOT"), removableDisk.mountPoints());
        assertTrue(removableDisk.displayName().contains("USB Reader"));
    }

    /// Parses empty diskutil lists as an empty device list.
    ///
    /// @throws IOException when parsing fails.
    @Test
    public void parsesEmptyDiskList() throws IOException {
        List<BlockDevice> devices = MacOSBlockDeviceService.parseDevices("""
                <?xml version="1.0" encoding="UTF-8"?>
                <plist version="1.0">
                <dict>
                  <key>AllDisksAndPartitions</key>
                  <array/>
                </dict>
                </plist>
                """, Map.of());

        assertTrue(devices.isEmpty());
    }

    /// Normalizes a platform path for cross-platform test assertions.
    ///
    /// @param device block device.
    /// @return normalized path text.
    private static String normalizedPath(BlockDevice device) {
        return device.path().toString().replace('\\', '/');
    }
}

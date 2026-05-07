// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.flash;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for dd-flasher privilege elevation decisions and launch commands.
@NotNullByDefault
public final class DdFlasherElevationTest {
    /// Verifies automatic Windows UAC decisions for raw device paths.
    @Test
    public void elevatesWindowsRawDevices() {
        assertTrue(DdFlasherElevation.shouldElevate(
                Path.of("\\\\.\\PHYSICALDRIVE2"),
                "Windows 11",
                null,
                null,
                "Alice",
                null));
        assertFalse(DdFlasherElevation.shouldElevate(
                Path.of("target.raw"),
                "Windows 11",
                null,
                null,
                "Alice",
                null));
    }

    /// Verifies automatic Linux GUI elevation decisions for raw device paths.
    @Test
    public void elevatesLinuxRawDevicesInGraphicalSessions() {
        assertTrue(DdFlasherElevation.shouldElevate(
                Path.of("/dev/sdb"),
                "Linux",
                ":0",
                null,
                "alice",
                null));
        assertFalse(DdFlasherElevation.shouldElevate(
                Path.of("/dev/sdb"),
                "Linux",
                null,
                null,
                "alice",
                null));
        assertFalse(DdFlasherElevation.shouldElevate(
                Path.of("/dev/sdb"),
                "Linux",
                ":0",
                null,
                "root",
                null));
    }

    /// Verifies configured elevation modes override automatic decisions.
    @Test
    public void honorsConfiguredElevationModes() {
        assertTrue(DdFlasherElevation.shouldElevate(
                Path.of("target.raw"),
                "Linux",
                null,
                null,
                "alice",
                "always"));
        assertFalse(DdFlasherElevation.shouldElevate(
                Path.of("\\\\.\\PHYSICALDRIVE2"),
                "Windows 11",
                null,
                null,
                "Alice",
                "never"));
    }

    /// Verifies Linux elevation uses pkexec with the helper command unchanged.
    ///
    /// @throws Exception when the command cannot be built.
    @Test
    public void buildsLinuxPkexecCommand() throws Exception {
        assertEquals(
                List.of("pkexec", "/opt/ruyi/dd-flasher", "write", "--source", "image.raw"),
                DdFlasherElevation.elevatedCommand(
                        "/opt/ruyi/dd-flasher",
                        List.of("write", "--source", "image.raw"),
                        "Linux"));
    }

    /// Verifies Windows elevation uses a PowerShell UAC launcher.
    @Test
    public void buildsWindowsUacCommand() {
        List<String> command = DdFlasherElevation.windowsElevatedCommand(
                "C:\\Tools\\dd-flasher.exe",
                List.of("write", "--source", "C:\\Images\\o'clock.raw"));
        assertEquals("powershell.exe", command.getFirst());
        assertEquals("-EncodedCommand", command.get(4));

        String script = new String(Base64.getDecoder().decode(command.get(5)), StandardCharsets.UTF_16LE);
        assertTrue(script.contains("Start-Process"));
        assertTrue(script.contains("-Verb RunAs"));
        assertTrue(script.contains("'C:\\Tools\\dd-flasher.exe'"));
        assertTrue(script.contains("'C:\\Images\\o''clock.raw'"));
    }
}

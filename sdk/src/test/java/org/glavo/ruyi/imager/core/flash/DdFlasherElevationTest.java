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

    /// Verifies automatic macOS administrator elevation decisions for raw disk paths.
    @Test
    public void elevatesMacOsRawDisks() {
        assertTrue(DdFlasherElevation.shouldElevate(
                Path.of("/dev/disk2"),
                "Mac OS X",
                null,
                null,
                "alice",
                null));
        assertTrue(DdFlasherElevation.shouldElevate(
                Path.of("/dev/rdisk2"),
                "Darwin",
                null,
                null,
                "alice",
                null));
        assertFalse(DdFlasherElevation.shouldElevate(
                Path.of("/tmp/target.raw"),
                "Mac OS X",
                null,
                null,
                "alice",
                null));
        assertFalse(DdFlasherElevation.shouldElevate(
                Path.of("/dev/disk2"),
                "Mac OS X",
                null,
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

    /// Verifies macOS elevation uses osascript administrator privileges and shell quoting.
    @Test
    public void buildsMacOsAdministratorCommand() {
        List<String> command = DdFlasherElevation.macOsElevatedCommand(
                "/Applications/Ruyi Imager.app/Contents/tools/dd-flasher",
                List.of("write", "--source", "/Users/alice/o'clock.raw"));
        assertEquals("osascript", command.getFirst());
        assertEquals("-e", command.get(1));

        String script = command.get(2);
        assertTrue(script.startsWith("do shell script "));
        assertTrue(script.endsWith(" with administrator privileges"));
        assertTrue(script.contains("'/Applications/Ruyi Imager.app/Contents/tools/dd-flasher'"));
        assertTrue(script.contains("'/Users/alice/o'\\\\''clock.raw'"));
    }
}

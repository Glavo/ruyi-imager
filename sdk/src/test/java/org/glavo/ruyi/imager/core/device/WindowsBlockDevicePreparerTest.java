// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.device;

import org.glavo.ruyi.imager.core.PowerShellScripts;
import org.glavo.ruyi.imager.core.ProgressReporter;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for Windows block-device preparation command handling.
@NotNullByDefault
public final class WindowsBlockDevicePreparerTest {
    /// Progress reporter that ignores progress events.
    private static final ProgressReporter NO_PROGRESS = _ -> {
    };

    /// Verifies that mounted Windows disks are dismounted through PowerShell.
    ///
    /// @throws Exception when preparation fails unexpectedly.
    @Test
    public void preparesMountedWindowsDisk() throws Exception {
        ArrayList<List<String>> commands = new ArrayList<>();
        WindowsBlockDevicePreparer preparer = new WindowsBlockDevicePreparer((command, _) -> {
            commands.add(command);
            return new WindowsBlockDevicePreparer.CommandResult(0, "prepared", "", false);
        });
        BlockDevice target = device("windows-disk-2", Path.of("\\\\.\\PHYSICALDRIVE2"), true);

        assertTrue(preparer.canPrepareMounted(target));
        assertTrue(preparer.shouldPrepare(target));
        BlockDevice prepared = preparer.prepare(target, NO_PROGRESS);

        assertFalse(prepared.mounted());
        assertTrue(prepared.mountPoints().isEmpty());
        assertEquals(1, commands.size());
        @Unmodifiable List<String> command = commands.get(0);
        assertTrue(command.contains("powershell.exe"));
        assertFalse(command.contains("-EncodedCommand"));
        assertTrue(command.contains("-File"));
        assertTrue(command.contains("-PrepareScript"));
        assertTrue(command.contains("-DiskNumber"));
        assertTrue(command.contains("2"));

        String launcherScript = Files.readString(PowerShellScripts.path("prepare-windows-disk-launcher.ps1"));
        assertTrue(launcherScript.contains("Start-Process"));
        assertTrue(launcherScript.contains("-Verb RunAs"));
        assertTrue(launcherScript.contains("-EncodedCommand"));
        assertTrue(launcherScript.contains("Join-NullSeparated"));
        assertTrue(launcherScript.contains("[Console]::OpenStandardError()"));

        String prepareScript = Files.readString(PowerShellScripts.path("prepare-windows-disk.ps1"));
        assertFalse(prepareScript.contains("Dismount-Volume"));
        assertTrue(prepareScript.contains("Set-Disk"));
        assertTrue(prepareScript.contains("-IsOffline"));
        assertTrue(prepareScript.contains("mountvol.exe"));
        assertTrue(prepareScript.contains("{ '/p' } else { '/d' }"));
        assertTrue(prepareScript.contains("Volume{"));
    }

    /// Verifies that unmounted Windows disks are still prepared before writing.
    ///
    /// @throws Exception when preparation fails unexpectedly.
    @Test
    public void preparesUnmountedWindowsDisk() throws Exception {
        ArrayList<List<String>> commands = new ArrayList<>();
        WindowsBlockDevicePreparer preparer = new WindowsBlockDevicePreparer((command, _) -> {
            commands.add(command);
            return new WindowsBlockDevicePreparer.CommandResult(0, "prepared", "", false);
        });
        BlockDevice target = device("windows-disk-2", Path.of("\\\\.\\PHYSICALDRIVE2"), false);

        assertFalse(preparer.canPrepareMounted(target));
        assertTrue(preparer.shouldPrepare(target));
        BlockDevice prepared = preparer.prepare(target, NO_PROGRESS);

        assertFalse(prepared.mounted());
        assertTrue(prepared.mountPoints().isEmpty());
        assertEquals(1, commands.size());
        assertTrue(commands.getFirst().contains("2"));
    }

    /// Verifies that unrecognized mounted devices are left unchanged.
    ///
    /// @throws Exception when preparation fails unexpectedly.
    @Test
    public void returnsOriginalTargetForUnrecognizedDisk() throws Exception {
        WindowsBlockDevicePreparer preparer = new WindowsBlockDevicePreparer((_, _) -> {
            throw new AssertionError("Command should not run.");
        });
        BlockDevice target = device("linux-disk-sdb", Path.of("/dev/sdb"), true);

        assertFalse(preparer.canPrepareMounted(target));
        assertFalse(preparer.shouldPrepare(target));
        assertSame(target, preparer.prepare(target, NO_PROGRESS));
    }

    /// Verifies that mounted non-removable disks are left unchanged.
    ///
    /// @throws Exception when preparation fails unexpectedly.
    @Test
    public void returnsOriginalTargetForNonRemovableDisk() throws Exception {
        WindowsBlockDevicePreparer preparer = new WindowsBlockDevicePreparer((_, _) -> {
            throw new AssertionError("Command should not run.");
        });
        BlockDevice target = device("windows-disk-2", Path.of("\\\\.\\PHYSICALDRIVE2"), true, false);

        assertFalse(preparer.canPrepareMounted(target));
        assertFalse(preparer.shouldPrepare(target));
        assertSame(target, preparer.prepare(target, NO_PROGRESS));
    }

    /// Verifies that PowerShell failures are surfaced as IO errors.
    @Test
    public void reportsPowerShellFailure() {
        WindowsBlockDevicePreparer preparer = new WindowsBlockDevicePreparer((_, _) ->
                new WindowsBlockDevicePreparer.CommandResult(1, "", "boom", false));
        BlockDevice target = device("windows-disk-3", Path.of("\\\\.\\PHYSICALDRIVE3"), true);

        IOException exception = assertThrows(IOException.class, () -> preparer.prepare(target, NO_PROGRESS));
        assertTrue(exception.getMessage().contains("boom"));
    }

    /// Creates test block-device metadata.
    ///
    /// @param id target id.
    /// @param path target path.
    /// @param mounted whether the target is mounted.
    /// @return test block-device metadata.
    private static BlockDevice device(String id, Path path, boolean mounted) {
        return device(id, path, mounted, true);
    }

    /// Creates test block-device metadata.
    ///
    /// @param id target id.
    /// @param path target path.
    /// @param mounted whether the target is mounted.
    /// @param removable whether the target is removable.
    /// @return test block-device metadata.
    private static BlockDevice device(String id, Path path, boolean mounted, boolean removable) {
        return new BlockDevice(
                id,
                "Test Disk",
                path,
                1024L,
                removable,
                false,
                mounted,
                false,
                "Test",
                "USB",
                mounted ? List.of("E:\\") : List.of());
    }
}

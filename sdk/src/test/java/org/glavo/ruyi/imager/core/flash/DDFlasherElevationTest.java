// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.flash;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for dd-flasher privilege elevation decisions and launch commands.
@NotNullByDefault
public final class DDFlasherElevationTest {
    /// Verifies native termination and handle closure using an unprivileged child process.
    ///
    /// @throws Throwable when process creation or native calls fail.
    @Test
    @EnabledOnOs(OS.WINDOWS)
    public void terminatesProcessAndClosesNativeHandle() throws Throwable {
        Process child = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "-cp", System.getProperty("java.class.path"),
                SleepingHelper.class.getName()).start();
        try (Arena arena = Arena.ofConfined()) {
            SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", arena);
            var openProcess = Linker.nativeLinker().downcallHandle(
                    kernel32.find("OpenProcess").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
            // Request synchronization, limited query, and termination access to this test's child only.
            MemorySegment handle = (MemorySegment) openProcess.invokeExact(0x00101001, 0, (int) child.pid());
            assertFalse(handle.equals(MemorySegment.NULL));
            var process = new DDFlasherElevation.WindowsElevatedProcess(handle);
            try (process) {
                assertFalse(process.waitFor(100L));
                process.destroyForcibly();
                assertTrue(process.waitFor(5_000L));
                assertEquals(1, process.exitValue());
            }
            process.close();
            assertThrows(IOException.class, () -> process.waitFor(0L));
            assertThrows(IOException.class, process::exitValue);
            assertThrows(IOException.class, process::destroyForcibly);
        } finally {
            child.destroyForcibly();
            assertTrue(child.waitFor(5L, TimeUnit.SECONDS));
        }
    }

    /// Verifies that native API failures are propagated instead of swallowed.
    @Test
    @EnabledOnOs(OS.WINDOWS)
    public void reportsInvalidNativeHandle() {
        var process = new DDFlasherElevation.WindowsElevatedProcess(MemorySegment.NULL);
        assertThrows(IOException.class, process::destroyForcibly);
        assertThrows(IOException.class, process::close);
    }

    /// Child process that remains alive until the native termination test stops it.
    @NotNullByDefault
    public static final class SleepingHelper {
        /// Prevents construction.
        private SleepingHelper() {
        }

        /// Waits long enough for the parent to exercise native process termination.
        ///
        /// @param arguments unused arguments.
        /// @throws InterruptedException when sleep is interrupted.
        public static void main(String[] arguments) throws InterruptedException {
            Thread.sleep(60_000L);
        }
    }

    /// Verifies automatic Windows UAC decisions for raw device paths.
    @Test
    public void elevatesWindowsRawDevices() {
        assertTrue(DDFlasherElevation.shouldElevate(
                Path.of("\\\\.\\PHYSICALDRIVE2"),
                "Windows 11",
                null,
                null,
                "Alice",
                null));
        assertFalse(DDFlasherElevation.shouldElevate(
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
        assertTrue(DDFlasherElevation.shouldElevate(
                Path.of("/dev/sdb"),
                "Linux",
                ":0",
                null,
                "alice",
                null));
        assertFalse(DDFlasherElevation.shouldElevate(
                Path.of("/dev/sdb"),
                "Linux",
                null,
                null,
                "alice",
                null));
        assertFalse(DDFlasherElevation.shouldElevate(
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
        assertTrue(DDFlasherElevation.shouldElevate(
                Path.of("/dev/disk2"),
                "Mac OS X",
                null,
                null,
                "alice",
                null));
        assertTrue(DDFlasherElevation.shouldElevate(
                Path.of("/dev/rdisk2"),
                "Darwin",
                null,
                null,
                "alice",
                null));
        assertFalse(DDFlasherElevation.shouldElevate(
                Path.of("/tmp/target.raw"),
                "Mac OS X",
                null,
                null,
                "alice",
                null));
        assertFalse(DDFlasherElevation.shouldElevate(
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
        assertTrue(DDFlasherElevation.shouldElevate(
                Path.of("target.raw"),
                "Linux",
                null,
                null,
                "alice",
                "always"));
        assertFalse(DDFlasherElevation.shouldElevate(
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
                DDFlasherElevation.elevatedCommand(
                        "/opt/ruyi/dd-flasher",
                        List.of("write", "--source", "image.raw"),
                        "Linux"));
    }

    /// Verifies Windows elevation uses native launching instead of a command wrapper.
    ///
    /// @throws Exception when the command cannot be built.
    @Test
    public void rejectsWindowsWrapperCommand() throws Exception {
        assertTrue(DDFlasherElevation.usesNativeWindowsElevation("Windows 11"));
        assertEquals(
                "Windows elevation uses native ShellExecuteExW.",
                org.junit.jupiter.api.Assertions.assertThrows(
                                java.io.IOException.class,
                                () -> DDFlasherElevation.elevatedCommand(
                                        "C:\\Tools\\dd-flasher.exe",
                                        List.of("write"),
                                        "Windows 11"))
                        .getMessage());
    }

    /// Verifies Windows argv values are quoted for `ShellExecuteExW` parameters.
    @Test
    public void quotesWindowsCommandLineArguments() {
        assertEquals("simple", DDFlasherElevation.windowsCommandLineArgument("simple"));
        assertEquals("\"\"", DDFlasherElevation.windowsCommandLineArgument(""));
        assertEquals("\"two words\"", DDFlasherElevation.windowsCommandLineArgument("two words"));
        assertEquals("\"quote\\\"x\"", DDFlasherElevation.windowsCommandLineArgument("quote\"x"));
        assertEquals(
                "\"C:\\Path With Spaces\\\\\"",
                DDFlasherElevation.windowsCommandLineArgument("C:\\Path With Spaces\\"));
        assertEquals(
                "write --source \"C:\\Path With Spaces\\\\\"",
                DDFlasherElevation.windowsCommandLine(List.of(
                        "write",
                        "--source",
                        "C:\\Path With Spaces\\")));
    }

    /// Verifies macOS elevation uses osascript administrator privileges and shell quoting.
    @Test
    public void buildsMacOsAdministratorCommand() {
        List<String> command = DDFlasherElevation.macOsElevatedCommand(
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

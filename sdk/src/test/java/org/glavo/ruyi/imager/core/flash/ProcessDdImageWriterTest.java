// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.flash;

import org.glavo.ruyi.imager.core.ProgressEvent;
import org.glavo.ruyi.imager.core.ProgressReporter;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for the process-backed dd-flasher adapter.
@NotNullByDefault
public final class ProcessDdImageWriterTest {
    /// Progress reporter that ignores progress events.
    private static final ProgressReporter NO_PROGRESS = _ -> {
    };

    /// Verifies large helper stderr output is drained and returned as a failure diagnostic.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when fixture files cannot be written.
    @Test
    public void drainsLargeHelperStderr(@TempDir Path temporaryDirectory) throws Exception {
        Path source = temporaryDirectory.resolve("source.raw");
        Path target = temporaryDirectory.resolve("target.raw");
        Files.write(source, new byte[]{1, 2, 3, 4});
        Files.write(target, new byte[8]);

        ProcessDdImageWriter writer = new ProcessDdImageWriter(List.of(
                javaExecutable().toString(),
                "-cp",
                System.getProperty("java.class.path"),
                StderrFloodHelper.class.getName()));

        IOException exception = assertTimeoutPreemptively(
                Duration.ofSeconds(5L),
                () -> assertThrows(IOException.class, () -> writer.write(
                        source,
                        target,
                        "Test Target",
                        4L,
                        true,
                        "Writing test image.",
                        NO_PROGRESS)));
        assertTrue(exception.getMessage().contains("stderr-marker-0"), exception.getMessage());
    }

    /// Verifies Windows physical drive targets are passed without a trailing separator.
    @Test
    public void trimsWindowsPhysicalDriveTargetTrailingSeparator() {
        assertEquals(
                "\\\\.\\PHYSICALDRIVE3",
                ProcessDdImageWriter.helperTargetArgument(Path.of("\\\\.\\PHYSICALDRIVE3\\")));
        assertEquals(
                "\\\\.\\physicaldrive4",
                ProcessDdImageWriter.helperTargetArgument(Path.of("\\\\.\\physicaldrive4\\")));
        assertEquals(
                Path.of("target.raw").toString(),
                ProcessDdImageWriter.helperTargetArgument(Path.of("target.raw")));
    }

    /// Verifies Windows physical drive target recognition.
    @Test
    public void recognizesWindowsPhysicalDriveTargets() {
        assertTrue(ProcessDdImageWriter.windowsPhysicalDriveTarget(Path.of("\\\\.\\PHYSICALDRIVE3")));
        assertTrue(ProcessDdImageWriter.windowsPhysicalDriveTarget(Path.of("\\\\.\\physicaldrive4\\")));
        assertFalse(ProcessDdImageWriter.windowsPhysicalDriveTarget(Path.of("\\\\.\\PHYSICALDRIVE")));
        assertFalse(ProcessDdImageWriter.windowsPhysicalDriveTarget(Path.of("\\\\.\\PHYSICALDRIVE3\\foo")));
        assertFalse(ProcessDdImageWriter.windowsPhysicalDriveTarget(Path.of("target.raw")));
    }

    /// Verifies the helper process receives the selected target display name.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when fixture files cannot be written.
    @Test
    public void passesTargetDisplayNameToHelper(@TempDir Path temporaryDirectory) throws Exception {
        Path source = temporaryDirectory.resolve("source.raw");
        Path target = temporaryDirectory.resolve("target.raw");
        Files.write(source, new byte[]{1, 2, 3, 4});
        Files.write(target, new byte[8]);

        ProcessDdImageWriter writer = new ProcessDdImageWriter(List.of(
                javaExecutable().toString(),
                "-cp",
                System.getProperty("java.class.path"),
                ArgumentCaptureHelper.class.getName()));

        assertTrue(writer.verify(
                source,
                target,
                "Test USB Target",
                4L,
                true,
                "Verifying test image.",
                NO_PROGRESS));
    }

    /// Verifies combined write-verify helper progress is mapped to write and verification stages.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when fixture files cannot be written.
    @Test
    public void mapsWriteVerifyProgressEvents(@TempDir Path temporaryDirectory) throws Exception {
        Path source = temporaryDirectory.resolve("source.raw");
        Path target = temporaryDirectory.resolve("target.raw");
        Files.write(source, new byte[]{1, 2, 3, 4});
        Files.write(target, new byte[8]);
        ArrayList<ProgressEvent> events = new ArrayList<>();

        ProcessDdImageWriter writer = new ProcessDdImageWriter(List.of(
                javaExecutable().toString(),
                "-cp",
                System.getProperty("java.class.path"),
                ProgressSequenceHelper.class.getName()));

        assertTrue(writer.writeAndVerify(
                source,
                target,
                "Test Target",
                4L,
                true,
                "Writing test image.",
                "Verifying test image.",
                events::add));
        assertEquals(List.of("flash", "verify", "verify"), events.stream().map(ProgressEvent::stage).toList());
        assertEquals(List.of(4L, 0L, 4L), events.stream().map(ProgressEvent::currentBytes).toList());
    }

    /// Verifies POSIX elevated event logs can be appended by helper processes running under another user.
    ///
    /// @throws Exception when the temporary event log cannot be created or inspected.
    @Test
    public void createsPosixElevatedEventLogForCrossUserAppend() throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));

        Path eventLog = ProcessDdImageWriter.temporaryEventLog("Linux");
        try {
            assertEquals(Path.of("/tmp").toRealPath(), eventLog.getParent().toRealPath());
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(eventLog);
            assertTrue(permissions.contains(PosixFilePermission.OTHERS_READ));
            assertTrue(permissions.contains(PosixFilePermission.OTHERS_WRITE));
        } finally {
            Files.deleteIfExists(eventLog);
        }
    }

    /// Verifies Linux elevated helpers use stdout events instead of temporary event logs.
    @Test
    public void usesPipedEventsForLinuxElevation() {
        assertFalse(ProcessDdImageWriter.usesElevatedEventLog("Linux"));
        assertTrue(ProcessDdImageWriter.usesElevatedEventLog("Windows 11"));
        assertTrue(ProcessDdImageWriter.usesElevatedEventLog("macOS"));
        assertTrue(ProcessDdImageWriter.usesElevatedEventLog("Darwin"));
    }

    /// Returns the current Java executable path.
    ///
    /// @return Java executable path.
    private static Path javaExecutable() {
        String executableName = System.getProperty("os.name", "").toLowerCase().startsWith("windows")
                ? "java.exe"
                : "java";
        return Path.of(System.getProperty("java.home"), "bin", executableName);
    }

    /// Helper process that fills stderr before exiting with failure.
    @NotNullByDefault
    public static final class StderrFloodHelper {
        /// Prevents construction.
        private StderrFloodHelper() {
        }

        /// Writes enough stderr to fill a process pipe unless the parent drains it.
        ///
        /// @param args ignored command-line arguments.
        @SuppressWarnings("unused")
        static void main(String[] args) {
            for (int index = 0; index < 20_000; index++) {
                System.err.println("stderr-marker-" + index + " 0123456789abcdef0123456789abcdef");
            }
            System.exit(2);
        }
    }

    /// Helper process that emits one combined write-verify progress sequence.
    @NotNullByDefault
    public static final class ProgressSequenceHelper {
        /// Prevents construction.
        private ProgressSequenceHelper() {
        }

        /// Emits a combined write-verify event sequence.
        ///
        /// @param args ignored command-line arguments.
        @SuppressWarnings("unused")
        static void main(String[] args) {
            System.out.println("{\"type\":\"progress\",\"operation\":\"write\",\"currentBytes\":4,\"totalBytes\":4}");
            System.out.println("{\"type\":\"progress\",\"operation\":\"verify\",\"currentBytes\":0,\"totalBytes\":4}");
            System.out.println("{\"type\":\"progress\",\"operation\":\"verify\",\"currentBytes\":4,\"totalBytes\":4}");
            System.out.println("{\"type\":\"complete\",\"success\":true}");
        }
    }

    /// Helper process that verifies selected command-line arguments.
    @NotNullByDefault
    public static final class ArgumentCaptureHelper {
        /// Prevents construction.
        private ArgumentCaptureHelper() {
        }

        /// Validates dd-flasher wire arguments and emits a successful completion event.
        ///
        /// @param args command-line arguments.
        @SuppressWarnings("unused")
        public static void main(String[] args) {
            List<String> arguments = List.of(args);
            if (!"verify".equals(arguments.getFirst())
                    || !"Test USB Target".equals(optionValue(arguments, "--target-display-name"))
                    || !"true".equals(optionValue(arguments, "--removable"))) {
                System.out.println("{\"type\":\"error\",\"message\":\"missing expected arguments\"}");
                System.exit(2);
            }
            System.out.println("{\"type\":\"complete\",\"success\":true}");
        }

        /// Returns one option value from the command line.
        ///
        /// @param arguments command-line arguments.
        /// @param name option name.
        /// @return option value, or an empty string when absent.
        private static String optionValue(List<String> arguments, String name) {
            int index = arguments.indexOf(name);
            if (index < 0 || index + 1 >= arguments.size()) {
                return "";
            }
            return arguments.get(index + 1);
        }
    }
}

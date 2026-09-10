// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.flash;

import org.glavo.ruyi.imager.core.ProgressEvent;
import org.glavo.ruyi.imager.core.ProgressReporter;
import org.glavo.ruyi.imager.core.device.BlockDevice;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

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
        BlockDevice blockTarget = fileTarget(target, "Test Target");

        ProcessDdImageWriter writer = new ProcessDdImageWriter(List.of(
                javaExecutable().toString(),
                "-cp",
                System.getProperty("java.class.path"),
                StderrFloodHelper.class.getName()));

        IOException exception = assertTimeoutPreemptively(
                Duration.ofSeconds(5L),
                () -> assertThrows(IOException.class, () -> writer.write(
                        source,
                        blockTarget,
                        4L,
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
        BlockDevice blockTarget = fileTarget(target, "Test USB Target");

        ProcessDdImageWriter writer = new ProcessDdImageWriter(List.of(
                javaExecutable().toString(),
                "-cp",
                System.getProperty("java.class.path"),
                ArgumentCaptureHelper.class.getName()));

        assertTrue(writer.verify(
                source,
                blockTarget,
                4L,
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
        BlockDevice blockTarget = fileTarget(target, "Test Target");
        ArrayList<ProgressEvent> events = new ArrayList<>();

        ProcessDdImageWriter writer = new ProcessDdImageWriter(List.of(
                javaExecutable().toString(),
                "-cp",
                System.getProperty("java.class.path"),
                ProgressSequenceHelper.class.getName()));

        assertTrue(writer.writeAndVerify(
                source,
                blockTarget,
                4L,
                "Writing test image.",
                "Verifying test image.",
                events::add));
        assertEquals(List.of("flash", "verify", "verify"), events.stream().map(ProgressEvent::stage).toList());
        assertEquals(List.of(4L, 0L, 4L), events.stream().map(ProgressEvent::currentBytes).toList());
    }

    /// Verifies POSIX elevated event logs are private to the invoking user and the root helper.
    ///
    /// @throws Exception when the temporary event log cannot be created or inspected.
    @Test
    public void createsPrivatePosixElevatedEventLog() throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));

        Path eventLog = ProcessDdImageWriter.temporaryEventLog("Linux");
        try {
            assertEquals(Path.of("/tmp").toRealPath(), eventLog.getParent().toRealPath());
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(eventLog);
            assertEquals(Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE), permissions);
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

    /// Retains cancellation after event parsing or a progress callback fails, even after killing the launcher.
    @Test
    public void retainsCancellationAfterEventFailure(@TempDir Path directory) throws Exception {
        for (boolean callbackFailure : List.of(false, true)) {
            Path eventLog = directory.resolve("events.ndjson");
            Path cancel = directory.resolve("cancel");
            Files.writeString(eventLog, callbackFailure
                    ? "{\"type\":\"progress\",\"operation\":\"write\"}\n"
                    : "invalid-json\n");
            var process = new TestElevationLauncher(false);
            ProgressReporter reporter = _ -> { throw new IllegalStateException("Callback failed."); };
            Class<? extends Exception> expectedType = callbackFailure ? IllegalStateException.class : IOException.class;
            assertThrows(expectedType,
                    () -> ProcessDdImageWriter.runEventLogElevated("write",
                            Map.of("write", new ProcessDdImageWriter.ProgressSink("flash", "Writing")),
                            List.of("test-launcher"), process, eventLog, cancel, reporter));
            assertTrue(process.destroyed);
            assertTrue(Files.exists(cancel));
            assertFalse(Files.exists(eventLog));
            Files.delete(cancel);
        }
    }

    /// Retains cancellation and the interrupt flag when the launcher wait is interrupted.
    @Test
    public void retainsCancellationAfterInterruptedWait(@TempDir Path directory) throws Exception {
        Path eventLog = Files.createFile(directory.resolve("events.ndjson"));
        Path cancel = directory.resolve("cancel");
        var process = new TestElevationLauncher(false);
        try {
            Thread.currentThread().interrupt();
            IOException failure = assertThrows(IOException.class, () -> ProcessDdImageWriter.runEventLogElevated(
                    "write", Map.of(), List.of("test-launcher"), process, eventLog, cancel, NO_PROGRESS));
            assertTrue(failure.getCause() instanceof InterruptedException);
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(process.destroyed);
            assertTrue(Files.exists(cancel));
        } finally {
            Thread.interrupted();
        }
    }

    /// Removes temporary files after a normally completed helper operation.
    @Test
    public void cleansCancellationAfterNormalCompletion(@TempDir Path directory) throws Exception {
        Path eventLog = directory.resolve("events.ndjson");
        Path cancel = Files.createFile(directory.resolve("cancel"));
        Files.writeString(eventLog, "{\"type\":\"complete\",\"success\":true}\n");
        assertTrue(ProcessDdImageWriter.runEventLogElevated("write", Map.of(), List.of("test-launcher"),
                new TestElevationLauncher(true), eventLog, cancel, NO_PROGRESS));
        assertFalse(Files.exists(eventLog));
        assertFalse(Files.exists(cancel));
    }

    /// Deletes piped-helper cancellation only after exit is confirmed, preserving cleanup failures and interruption.
    ///
    /// @param directory temporary cancellation directory.
    /// @throws Exception when the helper adapter or temporary file operations fail.
    @Test
    public void retainsPipedCancellationUntilExit(@TempDir Path directory) throws Exception {
        for (boolean interrupted : List.of(false, true)) {
            for (boolean confirmsTermination : List.of(false, true)) {
                Path cancel = directory.resolve("cancel");
                var process = new TestElevationLauncher(false, confirmsTermination);
                try {
                    if (interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    Class<? extends Exception> expectedType = interrupted ? IOException.class : IllegalStateException.class;
                    Exception failure = assertThrows(expectedType,
                            () -> ProcessDdImageWriter.runPipedElevated("write", Map.of(),
                                    List.of("test-helper"), process, cancel, NO_PROGRESS));
                    Throwable cause = interrupted ? java.util.Objects.requireNonNull(failure.getCause()) : failure;
                    assertEquals(interrupted, Thread.currentThread().isInterrupted());
                    assertTrue(process.destroyed);
                    assertEquals(2, process.timedWaits);
                    assertEquals(!confirmsTermination, Files.exists(cancel));
                    assertEquals(confirmsTermination ? 0 : 1, cause.getSuppressed().length);
                } finally {
                    Thread.interrupted();
                    Files.deleteIfExists(cancel);
                }
            }
        }
    }

    /// Removes a piped-helper cancellation path after normal process completion.
    ///
    /// @param directory temporary cancellation directory.
    /// @throws Exception when the helper adapter or temporary file operations fail.
    @Test
    public void cleansPipedCancellationAfterCompletion(@TempDir Path directory) throws Exception {
        Path cancel = Files.createFile(directory.resolve("cancel"));
        assertTrue(ProcessDdImageWriter.runPipedElevated("write", Map.of(), List.of("test-helper"),
                new TestElevationLauncher(true), cancel, NO_PROGRESS));
        assertFalse(Files.exists(cancel));
    }

    /// Models a launcher whose termination gives no information about its elevated child.
    @NotNullByDefault
    private static final class TestElevationLauncher extends Process {
        /// Whether the launcher completed without intervention.
        private final boolean completed;

        /// Whether a termination request is followed by observed process exit.
        private final boolean confirmsTermination;

        /// Number of bounded waits requested by cleanup.
        private int timedWaits;

        /// Whether forceful termination was requested.
        private boolean destroyed;

        /// Creates a completed or unresponsive launcher.
        private TestElevationLauncher(boolean completed) {
            this(completed, true);
        }

        /// Creates a process whose termination may remain unconfirmed.
        private TestElevationLauncher(boolean completed, boolean confirmsTermination) {
            this.completed = completed;
            this.confirmsTermination = confirmsTermination;
        }

        /// Discards launcher input.
        @Override
        public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }

        /// Returns a completion event for a normally exited process.
        @Override
        public InputStream getInputStream() {
            return completed ? new java.io.ByteArrayInputStream(
                    "{\"type\":\"complete\",\"success\":true}\n".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    : InputStream.nullInputStream();
        }

        /// Returns empty launcher diagnostics.
        @Override
        public InputStream getErrorStream() { return InputStream.nullInputStream(); }

        /// Simulates a completed, interrupted, or failed helper wait.
        @Override
        public int waitFor() throws InterruptedException {
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
            if (completed) {
                return 0;
            }
            throw new IllegalStateException("Wait failed.");
        }

        /// Simulates interruption or an expired wait without delaying the test.
        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            timedWaits++;
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
            return completed || (destroyed && confirmsTermination);
        }

        /// Returns a successful launcher exit only when it has stopped.
        @Override
        public int exitValue() {
            if (!completed && !(destroyed && confirmsTermination)) { throw new IllegalThreadStateException(); }
            return 0;
        }

        /// Records termination without affecting any elevated child.
        @Override
        public void destroy() { destroyed = true; }

        /// Records forceful termination without affecting any elevated child.
        @Override
        public Process destroyForcibly() { destroy(); return this; }
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

    /// Creates a file-backed target used by process adapter tests.
    ///
    /// @param path target file path.
    /// @param displayName target display name.
    /// @return file-backed block device.
    /// @throws IOException when the target size cannot be read.
    private static BlockDevice fileTarget(Path path, String displayName) throws IOException {
        return new BlockDevice(
                path.toString(),
                displayName,
                path,
                Files.size(path),
                true,
                false,
                false,
                false,
                "Test File Target",
                "file",
                "fixture=test-target",
                List.of());
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
                    || !"8".equals(optionValue(arguments, "--target-size-bytes"))
                    || !"true".equals(optionValue(arguments, "--removable"))
                    || !"true".equals(optionValue(arguments, "--file-backed"))
                    || !"Test File Target".equals(optionValue(arguments, "--target-model"))
                    || !"file".equals(optionValue(arguments, "--target-bus-type"))
                    || !"fixture=test-target".equals(optionValue(arguments, "--target-hardware-id"))) {
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

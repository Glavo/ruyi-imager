// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.flash;

import org.glavo.ruyi.imager.core.ProgressReporter;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

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
                        4L,
                        "Writing test image.",
                        NO_PROGRESS)));
        assertTrue(exception.getMessage().contains("stderr-marker-0"), exception.getMessage());
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
}

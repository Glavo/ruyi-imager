// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.logging;

import org.glavo.ruyi.imager.core.AppDirectories;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests application logging configuration.
@NotNullByDefault
public final class RuyiLoggingTest {
    /// Verifies the default rotating log file path.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when the log file cannot be created.
    @Test
    public void configuresDefaultLogFile(@TempDir Path temporaryDirectory) throws Exception {
        AppDirectories directories = new AppDirectories(
                temporaryDirectory.resolve("config"),
                temporaryDirectory.resolve("cache"));
        Path expected = temporaryDirectory
                .resolve("cache")
                .resolve("logs")
                .resolve("ruyi-imager-0.log")
                .toAbsolutePath()
                .normalize();

        try {
            RuyiLogging.configure(directories, RuyiLogLevel.INFO, null);
            Logger.getLogger(RuyiLoggingTest.class.getName()).info("default log test");

            assertEquals(expected, RuyiLogging.currentLogFile());
            assertEquals(RuyiLogLevel.INFO, RuyiLogging.currentLevel());
        } finally {
            RuyiLogging.shutdown();
        }
        assertTrue(Files.isRegularFile(expected));
    }

    /// Verifies explicit log level and log file overrides.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when the log file cannot be created.
    @Test
    public void configuresExplicitLogFileAndLevel(@TempDir Path temporaryDirectory) throws Exception {
        AppDirectories directories = new AppDirectories(
                temporaryDirectory.resolve("config"),
                temporaryDirectory.resolve("cache"));
        Path logFile = temporaryDirectory.resolve("custom.log").toAbsolutePath().normalize();

        try {
            RuyiLogging.configure(directories, RuyiLogLevel.DEBUG, logFile);
            Logger.getLogger(RuyiLoggingTest.class.getName()).fine("debug log test");

            assertEquals(logFile, RuyiLogging.currentLogFile());
            assertEquals(RuyiLogLevel.DEBUG, RuyiLogging.currentLevel());
        } finally {
            RuyiLogging.shutdown();
        }
        assertTrue(Files.isRegularFile(logFile));
    }
}

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager;

import org.glavo.ruyi.imager.core.AppDirectories;
import org.glavo.ruyi.imager.logging.RuyiLogging;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.parallel.Resources.SYSTEM_ERR;
import static org.junit.jupiter.api.parallel.Resources.SYSTEM_OUT;

/// Tests process bootstrap diagnostic stream behavior.
@NotNullByDefault
public final class MainTest {
    /// Keeps successful version and help output free of bootstrap diagnostics on standard error.
    ///
    /// @param temporaryDirectory temporary log directory.
    /// @throws Exception when an application log cannot be read.
    @Test
    @ResourceLock(SYSTEM_OUT)
    @ResourceLock(SYSTEM_ERR)
    public void informationalOutputHasCleanStandardError(@TempDir Path temporaryDirectory) throws Exception {
        for (String argument : List.of("--version", "--help")) {
            Path logFile = temporaryDirectory.resolve(argument.substring(2) + ".log");
            BootstrapResult result = runMain(logFile, argument);

            assertEquals("", result.standardError(), argument);
            assertFalse(result.standardOutput().isBlank(), argument);
            assertEquals(
                    1,
                    countResolvedDirectoryLogs(Files.readString(logFile)),
                    argument);
        }
    }

    /// Keeps bootstrap-owned logging available after JavaFX stop so launch failures can be recorded.
    ///
    /// @param temporaryDirectory temporary log directory.
    /// @throws Exception when the application log cannot be read.
    @Test
    public void guiStopPreservesBootstrapLogging(@TempDir Path temporaryDirectory) throws Exception {
        Path logFile = temporaryDirectory.resolve("gui-startup.log");
        @Nullable String originalLogFile = System.getProperty(RuyiLogging.LOG_FILE_PROPERTY);
        @Nullable String originalLogLevel = System.getProperty(RuyiLogging.LOG_LEVEL_PROPERTY);
        try {
            System.setProperty(RuyiLogging.LOG_FILE_PROPERTY, logFile.toString());
            System.setProperty(RuyiLogging.LOG_LEVEL_PROPERTY, "info");
            RuyiLogging.configure(new AppDirectories(temporaryDirectory, temporaryDirectory));

            RuyiImager application = new RuyiImager();
            application.init();
            application.stop();

            assertTrue(RuyiLogging.isConfigured());
            java.util.logging.Logger.getLogger(MainTest.class.getName()).severe("GUI launch failure marker.");
            RuyiLogging.shutdown();
            assertTrue(Files.readString(logFile).contains("GUI launch failure marker."));
        } finally {
            restoreProperty(RuyiLogging.LOG_FILE_PROPERTY, originalLogFile);
            restoreProperty(RuyiLogging.LOG_LEVEL_PROPERTY, originalLogLevel);
            RuyiLogging.shutdown();
        }
    }

    /// Runs the application entry point with captured process streams and an explicit log file.
    ///
    /// A temporary JUL console handler makes this method detect records emitted before
    /// [RuyiLogging#configure(org.glavo.ruyi.imager.core.AppDirectories)] removes console output.
    ///
    /// @param logFile  application log file.
    /// @param argument informational CLI argument.
    /// @return captured application output.
    private static BootstrapResult runMain(Path logFile, String argument) {
        PrintStream originalOutput = System.out;
        PrintStream originalError = System.err;
        @Nullable String originalLogFile = System.getProperty(RuyiLogging.LOG_FILE_PROPERTY);
        @Nullable String originalLogLevel = System.getProperty(RuyiLogging.LOG_LEVEL_PROPERTY);
        java.util.logging.Logger rootLogger = LogManager.getLogManager().getLogger("");
        Handler[] originalHandlers = rootLogger.getHandlers();
        @Nullable Level originalRootLevel = rootLogger.getLevel();
        for (Handler handler : originalHandlers) {
            rootLogger.removeHandler(handler);
        }

        ByteArrayOutputStream standardOutput = new ByteArrayOutputStream();
        ByteArrayOutputStream standardError = new ByteArrayOutputStream();
        try (PrintStream capturedOutput = new PrintStream(standardOutput, true, StandardCharsets.UTF_8);
             PrintStream capturedError = new PrintStream(standardError, true, StandardCharsets.UTF_8)) {
            System.setOut(capturedOutput);
            System.setErr(capturedError);
            System.setProperty(RuyiLogging.LOG_FILE_PROPERTY, logFile.toString());
            System.setProperty(RuyiLogging.LOG_LEVEL_PROPERTY, "info");

            ConsoleHandler bootstrapHandler = new ConsoleHandler();
            bootstrapHandler.setLevel(Level.ALL);
            rootLogger.setLevel(Level.ALL);
            rootLogger.addHandler(bootstrapHandler);

            Main.main(new String[]{argument});
            return new BootstrapResult(
                    standardOutput.toString(StandardCharsets.UTF_8),
                    standardError.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOutput);
            System.setErr(originalError);
            restoreProperty(RuyiLogging.LOG_FILE_PROPERTY, originalLogFile);
            restoreProperty(RuyiLogging.LOG_LEVEL_PROPERTY, originalLogLevel);
            RuyiLogging.shutdown();
            for (Handler handler : originalHandlers) {
                rootLogger.addHandler(handler);
            }
            rootLogger.setLevel(originalRootLevel);
        }
    }

    /// Restores one nullable system property value.
    ///
    /// @param name  system property name.
    /// @param value previous value, or null when absent.
    private static void restoreProperty(String name, @Nullable String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }

    /// Counts application-directory resolution records.
    ///
    /// @param text searched log text.
    /// @return occurrence count.
    private static int countResolvedDirectoryLogs(String text) {
        String marker = "Resolved application directories.";
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(marker, offset)) >= 0) {
            count++;
            offset += marker.length();
        }
        return count;
    }

    /// Captures output from one application bootstrap invocation.
    ///
    /// @param standardOutput captured standard output.
    /// @param standardError  captured standard error.
    @NotNullByDefault
    private record BootstrapResult(String standardOutput, String standardError) {
    }
}

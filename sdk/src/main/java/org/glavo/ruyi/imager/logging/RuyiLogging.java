// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.logging;

import org.glavo.ruyi.imager.core.AppDirectories;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.logging.ErrorManager;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;

/// Configures application logging for CLI and GUI runs.
@NotNullByDefault
public final class RuyiLogging {
    /// System property used to configure the log level.
    public static final String LOG_LEVEL_PROPERTY = "ruyi.imager.log.level";

    /// Environment variable used to configure the log level.
    public static final String LOG_LEVEL_ENV = "RUYI_IMAGER_LOG_LEVEL";

    /// System property used to configure the log file.
    public static final String LOG_FILE_PROPERTY = "ruyi.imager.log.file";

    /// Environment variable used to configure the log file.
    public static final String LOG_FILE_ENV = "RUYI_IMAGER_LOG_FILE";

    /// Default rotating log file limit.
    private static final int ROTATION_LIMIT_BYTES = 5 * 1024 * 1024;

    /// Default rotating log file count.
    private static final int ROTATION_FILE_COUNT = 5;

    /// Root JUL logger.
    private static final java.util.logging.Logger ROOT_LOGGER = LogManager.getLogManager().getLogger("");

    /// SLF4J logger for logging subsystem events.
    private static final Logger LOGGER = LoggerFactory.getLogger(RuyiLogging.class);

    /// Active file handler installed by this class.
    private static @Nullable Handler activeHandler;

    /// Current log file shown to users.
    private static @Nullable Path currentLogFile;

    /// Current configured log level.
    private static RuyiLogLevel currentLevel = RuyiLogLevel.INFO;

    /// Whether logging configuration has been attempted for the current application run.
    private static boolean configured;

    /// Prevents construction of the logging utility.
    private RuyiLogging() {
    }

    /// Configures logging from system properties, environment variables, and default paths.
    ///
    /// @param directories application directories.
    public static synchronized void configure(AppDirectories directories) {
        configure(directories, null, null);
    }

    /// Configures logging with optional CLI overrides.
    ///
    /// @param directories application directories.
    /// @param levelOverride explicit log level, or null to read system configuration.
    /// @param fileOverride explicit log file, or null to read system configuration.
    public static synchronized void configure(
            AppDirectories directories,
            @Nullable RuyiLogLevel levelOverride,
            @Nullable Path fileOverride) {
        RuyiLogLevel level = levelOverride == null ? configuredLevel() : levelOverride;
        Path logFile = fileOverride == null ? configuredLogFile(directories) : fileOverride;
        configureFileHandler(logFile, level, fileOverride == null && configuredFileOverride() == null);
    }

    /// Resolves an effective CLI log level.
    ///
    /// @param configuredValue explicit CLI level value.
    /// @param verbose whether `--verbose` was requested.
    /// @return effective log level.
    public static RuyiLogLevel cliLevel(@Nullable String configuredValue, boolean verbose) {
        @Nullable RuyiLogLevel parsed = RuyiLogLevel.parse(configuredValue);
        if (parsed != null) {
            return parsed;
        }
        if (verbose && (configuredValue == null || configuredValue.isBlank())) {
            return RuyiLogLevel.DEBUG;
        }
        return configuredLevel();
    }

    /// Returns the current log file path.
    ///
    /// @return current log file path, or null when logging is disabled or unavailable.
    public static synchronized @Nullable Path currentLogFile() {
        return currentLogFile;
    }

    /// Returns the current log file path as display text.
    ///
    /// @return current log file text, or null when logging is disabled or unavailable.
    public static synchronized @Nullable String currentLogFileText() {
        return currentLogFile == null ? null : currentLogFile.toString();
    }

    /// Returns the current configured log level.
    ///
    /// @return current configured log level.
    public static synchronized RuyiLogLevel currentLevel() {
        return currentLevel;
    }

    /// Returns whether application logging has been configured.
    ///
    /// @return whether [#configure(AppDirectories)] or its explicit-override variant has run since
    /// the last [#shutdown()].
    public static synchronized boolean isConfigured() {
        return configured;
    }

    /// Closes active logging handlers.
    public static synchronized void shutdown() {
        closeActiveHandler();
        removeRootHandlers();
        configured = false;
    }

    /// Configures the root logger and file handler.
    ///
    /// @param logFile visible log file path.
    /// @param level effective log level.
    /// @param rotating whether to use the default rotating file pattern.
    private static void configureFileHandler(Path logFile, RuyiLogLevel level, boolean rotating) {
        closeActiveHandler();
        removeRootHandlers();
        configured = true;
        currentLevel = level;

        if (level == RuyiLogLevel.OFF) {
            ROOT_LOGGER.setLevel(Level.OFF);
            currentLogFile = null;
            return;
        }

        try {
            @Nullable Path parent = logFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            FileHandler handler = rotating
                    ? new FileHandler(defaultPattern(logFile), ROTATION_LIMIT_BYTES, ROTATION_FILE_COUNT, true)
                    : new FileHandler(logFile.toString(), true);
            handler.setEncoding(StandardCharsets.UTF_8.name());
            handler.setLevel(level.julLevel());
            handler.setFormatter(new RuyiLogFormatter());

            ROOT_LOGGER.addHandler(handler);
            ROOT_LOGGER.setLevel(level.julLevel());
            activeHandler = handler;
            currentLogFile = logFile.toAbsolutePath().normalize();
            LOGGER.info("Logging initialized. level={}, file={}", level, currentLogFile);
        } catch (IOException | RuntimeException exception) {
            currentLogFile = null;
            ROOT_LOGGER.setLevel(Level.OFF);
            ErrorManager errorManager = new ErrorManager();
            errorManager.error("Failed to initialize logging.", exception, ErrorManager.OPEN_FAILURE);
        }
    }

    /// Reads the configured log level from system properties or environment variables.
    ///
    /// @return configured log level.
    private static RuyiLogLevel configuredLevel() {
        @Nullable RuyiLogLevel propertyLevel = RuyiLogLevel.parse(System.getProperty(LOG_LEVEL_PROPERTY));
        if (propertyLevel != null) {
            return propertyLevel;
        }
        @Nullable RuyiLogLevel envLevel = RuyiLogLevel.parse(System.getenv(LOG_LEVEL_ENV));
        return envLevel == null ? RuyiLogLevel.INFO : envLevel;
    }

    /// Reads the configured log file from system properties or environment variables.
    ///
    /// @param directories application directories.
    /// @return configured or default log file.
    private static Path configuredLogFile(AppDirectories directories) {
        @Nullable Path configured = configuredFileOverride();
        return configured == null
                ? directories.cacheDirectory().resolve("logs").resolve("ruyi-imager-0.log")
                : configured;
    }

    /// Reads an explicit log-file override.
    ///
    /// @return configured log file, or null when absent.
    private static @Nullable Path configuredFileOverride() {
        @Nullable String property = nonBlank(System.getProperty(LOG_FILE_PROPERTY));
        if (property != null) {
            return Path.of(property);
        }
        @Nullable String env = nonBlank(System.getenv(LOG_FILE_ENV));
        return env == null ? null : Path.of(env);
    }

    /// Converts a default visible log file to a JUL rotating pattern.
    ///
    /// @param logFile visible log file.
    /// @return file handler pattern.
    private static String defaultPattern(Path logFile) {
        @Nullable Path parent = logFile.getParent();
        String fileName = logFile.getFileName().toString();
        String patternName = fileName.replace("-0.log", "-%g.log");
        Path pattern = parent == null ? Path.of(patternName) : parent.resolve(patternName);
        return pattern.toString();
    }

    /// Removes and closes handlers from the root logger.
    private static void removeRootHandlers() {
        for (Handler handler : ROOT_LOGGER.getHandlers()) {
            ROOT_LOGGER.removeHandler(handler);
            if (handler != activeHandler) {
                handler.close();
            }
        }
    }

    /// Closes the active file handler.
    private static void closeActiveHandler() {
        @Nullable Handler handler = activeHandler;
        if (handler != null) {
            ROOT_LOGGER.removeHandler(handler);
            handler.close();
            activeHandler = null;
        }
    }

    /// Returns a non-blank string.
    ///
    /// @param value candidate string.
    /// @return stripped value, or null when absent or blank.
    private static @Nullable String nonBlank(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /// Formatter used for the application log file.
    @NotNullByDefault
    private static final class RuyiLogFormatter extends Formatter {
        /// Timestamp formatter.
        private static final DateTimeFormatter TIMESTAMP_FORMAT =
                DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.systemDefault());

        /// Formats one log record.
        ///
        /// @param record log record.
        /// @return formatted log line and optional stack trace.
        @Override
        public String format(LogRecord record) {
            StringBuilder builder = new StringBuilder(256);
            builder.append(TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(record.getMillis())))
                    .append(' ')
                    .append(record.getLevel().getName())
                    .append(" [")
                    .append(record.getLongThreadID())
                    .append("] ")
                    .append(record.getLoggerName())
                    .append(" - ")
                    .append(formatMessage(record))
                    .append(System.lineSeparator());

            @Nullable Throwable thrown = record.getThrown();
            if (thrown != null) {
                StringWriter writer = new StringWriter();
                thrown.printStackTrace(new PrintWriter(writer));
                builder.append(writer);
            }
            return builder.toString();
        }
    }
}

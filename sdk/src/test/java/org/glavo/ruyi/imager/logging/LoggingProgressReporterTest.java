// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.logging;

import org.glavo.ruyi.imager.core.ProgressEvent;
import org.glavo.ruyi.imager.core.ProgressReporter;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests progress reporter logging.
@NotNullByDefault
public final class LoggingProgressReporterTest {
    /// Verifies that progress events are forwarded and diagnostic records are emitted.
    @Test
    public void forwardsAndLogsProgressEvents() {
        String loggerName = "progress-test-" + UUID.randomUUID();
        java.util.logging.Logger julLogger = java.util.logging.Logger.getLogger(loggerName);
        CapturingHandler handler = new CapturingHandler();
        handler.setLevel(Level.FINE);
        julLogger.setUseParentHandlers(false);
        julLogger.setLevel(Level.FINE);
        julLogger.addHandler(handler);

        ArrayList<ProgressEvent> events = new ArrayList<>();
        ProgressReporter reporter = new LoggingProgressReporter(events::add, LoggerFactory.getLogger(loggerName));
        reporter.report(new ProgressEvent("download", "Downloading.", 0L, 10L));
        reporter.report(new ProgressEvent("download", "Downloading.", 5L, 10L));
        reporter.report(new ProgressEvent("download", "Downloading.", 10L, 10L));

        julLogger.removeHandler(handler);
        assertEquals(3, events.size());
        assertTrue(handler.records().size() >= 2);
        assertEquals("download", events.getFirst().stage());
    }

    /// Captures log records emitted by a test logger.
    @NotNullByDefault
    private static final class CapturingHandler extends Handler {
        /// Captured records.
        private final ArrayList<LogRecord> records = new ArrayList<>();

        /// Publishes one record.
        ///
        /// @param record log record.
        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        /// Flushes the handler.
        @Override
        public void flush() {
        }

        /// Closes the handler.
        @Override
        public void close() {
        }

        /// Returns captured records.
        ///
        /// @return captured records.
        private @Unmodifiable List<LogRecord> records() {
            return List.copyOf(records);
        }
    }
}

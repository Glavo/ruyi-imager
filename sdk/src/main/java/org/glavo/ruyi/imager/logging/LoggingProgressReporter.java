// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.logging;

import org.glavo.ruyi.imager.core.ProgressEvent;
import org.glavo.ruyi.imager.core.ProgressReporter;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/// Progress reporter wrapper that mirrors throttled progress snapshots to logs.
@NotNullByDefault
public final class LoggingProgressReporter implements ProgressReporter {
    /// Minimum interval between progress log records for the same stage.
    private static final long MIN_LOG_INTERVAL_NANOS = Duration.ofSeconds(1).toNanos();

    /// Wrapped user-visible progress reporter.
    private final ProgressReporter delegate;

    /// Logger receiving progress records.
    private final Logger logger;

    /// Last stage logged by this reporter.
    private @Nullable String lastStage;

    /// Last current byte or step value logged by this reporter.
    private @Nullable Long lastCurrent;

    /// Last total byte or step value logged by this reporter.
    private @Nullable Long lastTotal;

    /// Time of the last progress log record.
    private long lastLogNanos;

    /// Creates a logging progress reporter.
    ///
    /// @param delegate wrapped reporter.
    /// @param logger logger receiving progress records.
    public LoggingProgressReporter(ProgressReporter delegate, Logger logger) {
        this.delegate = Objects.requireNonNull(delegate);
        this.logger = Objects.requireNonNull(logger);
    }

    /// Wraps a reporter with progress logging.
    ///
    /// @param delegate wrapped reporter.
    /// @param logger logger receiving progress records.
    /// @return logging progress reporter.
    public static ProgressReporter wrap(ProgressReporter delegate, Logger logger) {
        return new LoggingProgressReporter(delegate, logger);
    }

    /// Forwards a progress event and logs a throttled diagnostic snapshot.
    ///
    /// @param event progress event.
    @Override
    public void report(ProgressEvent event) {
        delegate.report(event);
        if (!logger.isLoggable(Level.FINE)) {
            return;
        }

        long now = System.nanoTime();
        boolean stageChanged = lastStage == null || !lastStage.equals(event.stage());
        boolean completed = event.currentBytes() != null
                && event.totalBytes() != null
                && event.totalBytes() > 0L
                && event.currentBytes().longValue() >= event.totalBytes().longValue();
        boolean valueChanged = !Objects.equals(lastCurrent, event.currentBytes())
                || !Objects.equals(lastTotal, event.totalBytes());

        if (stageChanged || completed || (valueChanged && now - lastLogNanos >= MIN_LOG_INTERVAL_NANOS)) {
            lastStage = event.stage();
            lastCurrent = event.currentBytes();
            lastTotal = event.totalBytes();
            lastLogNanos = now;
            logger.fine(() -> progressMessage(event));
        }
    }

    /// Formats one progress event for logs.
    ///
    /// @param event progress event.
    /// @return log message.
    private static String progressMessage(ProgressEvent event) {
        @Nullable Long current = event.currentBytes();
        @Nullable Long total = event.totalBytes();
        if (current != null && total != null) {
            return "Progress stage=" + event.stage()
                    + ", current=" + current
                    + ", total=" + total
                    + ", message=" + event.message();
        }
        return "Progress stage=" + event.stage() + ", message=" + event.message();
    }
}

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/// Runs short-lived external commands while concurrently draining stdout and stderr.
@NotNullByDefault
public final class ProcessOutputCapture {
    /// Prevents construction of the process utility.
    private ProcessOutputCapture() {
    }

    /// Runs one process with a timeout and captures both output streams.
    ///
    /// @param command command line.
    /// @param timeout process timeout.
    /// @return captured process result.
    /// @throws IOException when the process cannot be started or output cannot be read.
    /// @throws InterruptedException when waiting is interrupted.
    public static Result run(@Unmodifiable List<String> command, Duration timeout) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).start();
        StreamCollector stdout = StreamCollector.start(process.getInputStream(), "ruyi-imager-process-stdout");
        StreamCollector stderr = StreamCollector.start(process.getErrorStream(), "ruyi-imager-process-stderr");
        boolean completed;
        try {
            completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            process.destroyForcibly();
            stdout.awaitQuietly();
            stderr.awaitQuietly();
            throw exception;
        }

        if (!completed) {
            process.destroyForcibly();
            stdout.awaitQuietly();
            stderr.awaitQuietly();
            return new Result(-1, stdout.text(), stderr.text(), true);
        }

        stdout.await();
        stderr.await();
        return new Result(process.exitValue(), stdout.text(), stderr.text(), false);
    }

    /// Captured process result.
    ///
    /// @param exitCode process exit code.
    /// @param output standard output text.
    /// @param error standard error text.
    /// @param timedOut whether the process exceeded its timeout.
    @NotNullByDefault
    public record Result(int exitCode, String output, String error, boolean timedOut) {
    }

    /// Background stream reader.
    @NotNullByDefault
    private static final class StreamCollector {
        /// Guards captured stream bytes.
        private final Object lock = new Object();

        /// Captured stream bytes.
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();

        /// Source stream.
        private final InputStream input;

        /// Reader thread.
        private final Thread thread;

        /// Failure raised while reading.
        private @Nullable IOException failure;

        /// Starts one collector.
        ///
        /// @param input source stream.
        /// @param name reader thread name.
        /// @return started collector.
        private static StreamCollector start(InputStream input, String name) {
            StreamCollector collector = new StreamCollector(input, name);
            collector.thread.start();
            return collector;
        }

        /// Creates one collector.
        ///
        /// @param input source stream.
        /// @param name reader thread name.
        private StreamCollector(InputStream input, String name) {
            this.input = input;
            this.thread = new Thread(this::drain, name);
            this.thread.setDaemon(true);
        }

        /// Drains the stream until EOF.
        private void drain() {
            byte[] buffer = new byte[8192];
            try (InputStream stream = input) {
                int read;
                while ((read = stream.read(buffer)) >= 0) {
                    synchronized (lock) {
                        output.write(buffer, 0, read);
                    }
                }
            } catch (IOException exception) {
                failure = exception;
            }
        }

        /// Waits for the stream reader to finish.
        ///
        /// @throws IOException when waiting is interrupted or stream reading fails.
        private void await() throws IOException {
            try {
                thread.join();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while reading process output.", exception);
            }
            if (failure != null) {
                throw failure;
            }
        }

        /// Waits briefly for cleanup without surfacing failures.
        private void awaitQuietly() {
            try {
                thread.join(TimeUnit.SECONDS.toMillis(1L));
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }

        /// Returns the captured text.
        ///
        /// @return captured text decoded as UTF-8.
        private String text() {
            synchronized (lock) {
                return output.toString(StandardCharsets.UTF_8);
            }
        }
    }
}

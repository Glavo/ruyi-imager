// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.update;

import org.glavo.ruyi.imager.core.NetworkDefaults;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/// Transfers bounded update data over HTTPS without verifying package integrity.
/// Each invocation has an independent deadline covering requests, redirects, and body
/// consumption. Network waits are interruptible; interruption preserves the calling
/// thread's interrupt status. The client is borrowed and is never closed here.
/// Only HTTP 200 responses with identity encoding are accepted; at most five redirects
/// are followed, and the initial URI and every redirect must satisfy the HTTPS policy.
@NotNullByDefault
final class UpdateTransport {
    /// Default time budget for a complete transfer, including redirects.
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(30);

    /// Maximum budget for small metadata reads, including response body consumption.
    private static final long READ_TIMEOUT_NANOS = Duration.ofSeconds(60).toNanos();

    /// Maximum number of redirects followed for one invocation.
    private static final int MAX_REDIRECTS = 5;

    /// Maximum number of bytes passed to a file write at once.
    private static final int BUFFER_SIZE = 64 * 1024;

    /// Borrowed client, configured to leave redirect handling to this transport.
    private final HttpClient client;

    /// Positive operation time budget in nanoseconds.
    private final long timeoutNanos;

    /// Creates a transport with the default operation timeout.
    ///
    /// @param client borrowed client with automatic redirects disabled.
    /// @throws IllegalArgumentException if automatic redirects are enabled.
    UpdateTransport(HttpClient client) {
        this(client, DEFAULT_TIMEOUT);
    }

    /// Creates a transport with an independent time budget for each invocation.
    ///
    /// @param client borrowed client with automatic redirects disabled.
    /// @param overallTimeout positive timeout representable in nanoseconds.
    /// @throws IllegalArgumentException if redirects are enabled or the timeout is invalid.
    UpdateTransport(HttpClient client, Duration overallTimeout) {
        this.client = Objects.requireNonNull(client);
        Objects.requireNonNull(overallTimeout);
        if (client.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new IllegalArgumentException("Update transport requires automatic redirects to be disabled.");
        }
        try {
            timeoutNanos = overallTimeout.toNanos();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Update timeout is too large.", exception);
        }
        if (timeoutNanos <= 0) {
            throw new IllegalArgumentException("Update timeout must be positive.");
        }
    }

    /// Returns a transport using the shared client and application proxy defaults.
    static UpdateTransport systemDefault() {
        return Defaults.TRANSPORT;
    }

    /// Reads a complete response into a new caller-owned array.
    /// The deadline is the lesser of the configured operation timeout and 60 seconds.
    ///
    /// @param uri absolute HTTPS URI without credentials or a fragment.
    /// @param maxBytes maximum accepted body size, including zero for an empty body.
    /// @return response bytes; no partial result is returned on failure.
    /// @throws IllegalArgumentException if the byte bound is negative.
    /// @throws IOException if the URI, response, size, or transfer is invalid.
    /// @throws HttpTimeoutException if the overall deadline expires.
    /// @throws InterruptedIOException if the calling thread is interrupted.
    byte[] read(URI uri, int maxBytes) throws IOException {
        if (maxBytes < 0) {
            throw new IllegalArgumentException("Update read limit must not be negative.");
        }
        long deadline = System.nanoTime() + Math.min(timeoutNanos, READ_TIMEOUT_NANOS);
        validateUri(uri);
        var output = new ByteArrayOutputStream(Math.min(maxBytes, BUFFER_SIZE));
        transfer(uri, maxBytes, output, null, deadline);
        byte[] result = output.toByteArray();
        remaining(deadline);
        return result;
    }

    /// Streams a response into a caller-owned destination, creating or truncating it.
    /// The destination's parent directory must exist. No package digest is computed.
    /// On failure the file may contain a prefix of the body and is not deleted.
    /// The output is closed and the response subscription is cancelled before return.
    ///
    /// Progress starts at zero after response validation and advances after successful
    /// writes, with {@code maxBytes} as its total. A shorter body is permitted; callers
    /// must separately verify an exact expected package size. Callbacks execute on the
    /// calling thread and must return promptly: arbitrary callback code and filesystem
    /// operations cannot be forcibly stopped at the deadline. Their elapsed time counts
    /// toward the budget and is checked before subsequent work and before success.
    ///
    /// @param uri absolute HTTPS URI without credentials or a fragment.
    /// @param destination file to create or truncate, even if the request later fails.
    /// @param maxBytes positive maximum body size and progress total.
    /// @param progress callback receiving successfully written byte counts.
    /// @throws IllegalArgumentException if the byte bound is not positive.
    /// @throws IOException if the URI, response, size, transfer, or file operation fails.
    /// @throws HttpTimeoutException if the overall deadline expires.
    /// @throws InterruptedIOException if the calling thread is interrupted.
    /// @throws RuntimeException if the callback throws a runtime exception; it is propagated unchanged.
    void download(URI uri, Path destination, long maxBytes, Consumer<UpdateProgress> progress) throws IOException {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("Update download limit must be positive.");
        }
        Objects.requireNonNull(destination);
        Objects.requireNonNull(progress);
        long deadline = System.nanoTime() + timeoutNanos;
        validateUri(uri);
        remaining(deadline);
        try (OutputStream output = Files.newOutputStream(destination)) {
            transfer(uri, maxBytes, output, progress, deadline);
        }
        remaining(deadline);
    }

    /// Follows validated redirects and copies a single HTTP 200 identity response.
    /// The caller owns the output; each request and body subscription is cancelled on exit.
    private void transfer(URI uri, long maxBytes, OutputStream output,
                          @Nullable Consumer<UpdateProgress> progress, long deadline) throws IOException {
        for (int redirects = 0; ; redirects++) {
            validateUri(uri);
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofNanos(remaining(deadline)))
                    .header("Accept-Encoding", "identity")
                    .GET().build();
            try (var body = new ResponseBody()) {
                CompletableFuture<HttpResponse<ResponseBody>> future = client.sendAsync(request, ignored -> body);
                try {
                    HttpResponse<ResponseBody> response;
                    try {
                        response = future.get(remaining(deadline), TimeUnit.NANOSECONDS);
                    } catch (InterruptedException exception) {
                        throw interrupted(exception);
                    } catch (TimeoutException exception) {
                        throw new HttpTimeoutException("Update transfer timed out.");
                    } catch (ExecutionException exception) {
                        @Nullable Throwable cause = exception.getCause();
                        if (cause instanceof IOException ioException) {
                            throw ioException;
                        }
                        throw new IOException("Update request failed.", cause);
                    }
                    remaining(deadline);
                    validateUri(response.uri());
                    if (!uri.equals(response.uri())) {
                        throw new IOException("Update client followed an unexpected redirect.");
                    }
                    int status = response.statusCode();
                    if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                        if (redirects >= MAX_REDIRECTS) {
                            throw new IOException("Too many update redirects.");
                        }
                        List<String> locations = response.headers().allValues("Location");
                        if (locations.size() != 1 || locations.getFirst().isBlank()) {
                            throw new IOException("Update redirect requires one nonempty Location header.");
                        }
                        try {
                            uri = uri.resolve(locations.getFirst());
                        } catch (IllegalArgumentException exception) {
                            throw new IOException("Invalid update redirect URI.", exception);
                        }
                        continue;
                    }
                    if (status != 200) {
                        throw new IOException("Unexpected update HTTP status: " + status);
                    }
                    long contentLength = validateHeaders(response.headers(), maxBytes);
                    copy(body, output, maxBytes, contentLength, progress, deadline);
                    return;
                } finally {
                    future.cancel(true);
                }
            }
        }
    }

    /// Rejects non-HTTPS, opaque, hostless, credential-bearing, and fragment-bearing URIs.
    private static void validateUri(URI uri) throws IOException {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.isOpaque() || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                || uri.getPort() == 0 || uri.getPort() > 65535) {
            throw new IOException("Update URI must use HTTPS with a host and without credentials or fragments.");
        }
    }

    /// Checks identity encoding and a single valid bounded Content-Length, returning -1 if absent.
    private static long validateHeaders(HttpHeaders headers, long maxBytes) throws IOException {
        for (String value : headers.allValues("Content-Encoding")) {
            for (String encoding : value.split(",", -1)) {
                if (!encoding.strip().equalsIgnoreCase("identity")) {
                    throw new IOException("Unsupported update Content-Encoding.");
                }
            }
        }
        List<String> lengths = headers.allValues("Content-Length");
        if (lengths.isEmpty()) {
            return -1;
        }
        String value = lengths.getFirst();
        if (lengths.size() != 1 || value.isEmpty()
                || !value.chars().allMatch(character -> character >= '0' && character <= '9')) {
            throw new IOException("Invalid update Content-Length.");
        }
        long length;
        try {
            length = Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IOException("Invalid update Content-Length.", exception);
        }
        if (length > maxBytes) {
            throw new IOException("Update response exceeds its byte limit.");
        }
        return length;
    }

    /// Copies demanded body chunks, checking the bound and deadline before every write and callback.
    private static void copy(ResponseBody body, OutputStream output, long maxBytes, long contentLength,
                             @Nullable Consumer<UpdateProgress> progress, long deadline) throws IOException {
        long copied = 0;
        byte[] buffer = new byte[BUFFER_SIZE];
        remaining(deadline);
        if (progress != null) {
            progress.accept(new UpdateProgress(0, maxBytes));
        }
        while (true) {
            List<ByteBuffer> buffers = body.next(deadline);
            if (buffers.isEmpty()) {
                break;
            }
            for (ByteBuffer source : buffers) {
                while (source.hasRemaining()) {
                    remaining(deadline);
                    int count = Math.min(source.remaining(), buffer.length);
                    if (count > maxBytes - copied) {
                        throw new IOException("Update response exceeds its byte limit.");
                    }
                    source.get(buffer, 0, count);
                    output.write(buffer, 0, count);
                    copied += count;
                    remaining(deadline);
                    if (progress != null) {
                        progress.accept(new UpdateProgress(copied, maxBytes));
                    }
                }
            }
            body.requestNext();
        }
        remaining(deadline);
        if (contentLength >= 0 && copied != contentLength) {
            throw new IOException("Update response does not match its Content-Length.");
        }
    }

    /// Returns the remaining budget or fails without clearing an existing interrupt flag.
    private static long remaining(long deadline) throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Update transfer interrupted.");
        }
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            throw new HttpTimeoutException("Update transfer timed out.");
        }
        return remaining;
    }

    /// Restores interruption and creates the checked interruption failure.
    private static InterruptedIOException interrupted(InterruptedException cause) {
        Thread.currentThread().interrupt();
        var exception = new InterruptedIOException("Update transfer interrupted.");
        exception.initCause(cause);
        return exception;
    }

    /// Lazily creates the shared production transport without allocating clients for injected transports.
    @NotNullByDefault
    private static final class Defaults {
        /// Shared transport whose client lives for the application lifetime.
        private static final UpdateTransport TRANSPORT = new UpdateTransport(createClient());

        /// Prevents construction.
        private Defaults() {
        }

        /// Builds a TLS-validating client with application proxies and manual redirect handling.
        private static HttpClient createClient() {
            HttpClient.Builder builder = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .connectTimeout(Duration.ofSeconds(20));
            @Nullable ProxySelector proxy = NetworkDefaults.proxySelector();
            if (proxy != null) {
                builder.proxy(proxy);
            }
            return builder.build();
        }
    }

    /// Bridges one demanded HTTP body chunk at a time to interruptible, deadline-bounded polling.
    /// Closing also cancels subscriptions that arrive after cancellation, including before headers.
    @NotNullByDefault
    private static final class ResponseBody implements HttpResponse.BodySubscriber<ResponseBody>, AutoCloseable {
        /// Terminal marker, distinct from nonempty data lists.
        private static final @Unmodifiable List<ByteBuffer> END = List.of();

        /// Holds at most one demanded data chunk and one terminal marker.
        private final BlockingQueue<List<ByteBuffer>> chunks = new ArrayBlockingQueue<>(2);

        /// Subscription published by the HTTP client, initially absent.
        private final AtomicReference<Flow.@Nullable Subscription> subscription = new AtomicReference<>();

        /// Whether this response has been abandoned by its caller.
        private volatile boolean closed;

        /// Terminal body failure, published before the terminal marker.
        private volatile @Nullable Throwable failure;

        /// Creates an unsubscribed response body.
        private ResponseBody() {
        }

        /// Makes the response available as soon as headers arrive, before body completion.
        @Override
        public CompletionStage<ResponseBody> getBody() {
            return CompletableFuture.completedFuture(this);
        }

        /// Accepts one subscription and cancels duplicates or subscriptions arriving after closure.
        @Override
        public void onSubscribe(Flow.Subscription incoming) {
            if (!subscription.compareAndSet(null, incoming) || closed) {
                incoming.cancel();
            } else {
                incoming.request(1);
            }
        }

        /// Queues a demanded chunk without blocking an HTTP client thread.
        @Override
        public void onNext(List<ByteBuffer> item) {
            if (!closed) {
                if (item.isEmpty()) {
                    requestNext();
                } else if (!chunks.offer(item)) {
                    close();
                    onError(new IOException("Update body exceeded its requested demand."));
                }
            }
        }

        /// Publishes a terminal failure and wakes a waiting reader.
        @Override
        public void onError(Throwable throwable) {
            failure = throwable;
            chunks.offer(END);
        }

        /// Publishes normal completion after the final demanded chunk.
        @Override
        public void onComplete() {
            chunks.offer(END);
        }

        /// Waits for one chunk or completion, translating asynchronous failures to IOException.
        private List<ByteBuffer> next(long deadline) throws IOException {
            @Nullable List<ByteBuffer> item;
            try {
                item = chunks.poll(remaining(deadline), TimeUnit.NANOSECONDS);
            } catch (InterruptedException exception) {
                throw interrupted(exception);
            }
            remaining(deadline);
            @Nullable Throwable cause = failure;
            if (cause != null) {
                throw new IOException("Update response body failed.", cause);
            }
            if (item == null) {
                throw new HttpTimeoutException("Update transfer timed out.");
            }
            return item;
        }

        /// Requests another chunk only after the previous chunk has been consumed.
        private void requestNext() {
            @Nullable Flow.Subscription current = subscription.get();
            if (!closed && current != null) {
                current.request(1);
            }
        }

        /// Idempotently cancels the subscription and releases queued body buffers.
        @Override
        public void close() {
            closed = true;
            @Nullable Flow.Subscription current = subscription.get();
            if (current != null) {
                current.cancel();
            }
            chunks.clear();
        }
    }
}

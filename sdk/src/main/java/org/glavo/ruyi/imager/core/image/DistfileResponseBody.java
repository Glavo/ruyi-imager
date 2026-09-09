// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.image;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/// Delivers one demanded HTTP body chunk at a time with interruptible, bounded waits.
/// The download thread must close this subscriber on completion or failure to cancel outstanding demand.
@NotNullByDefault
final class DistfileResponseBody implements HttpResponse.BodySubscriber<DistfileResponseBody>, AutoCloseable {
    /// Marks completion without retaining response data.
    private static final @Unmodifiable List<ByteBuffer> END = List.of();

    /// Holds at most one demanded chunk and a terminal marker.
    private final BlockingQueue<List<ByteBuffer>> chunks = new ArrayBlockingQueue<>(2);

    /// Subscription, including one arriving after the caller has abandoned the request.
    private final AtomicReference<Flow.@Nullable Subscription> subscription = new AtomicReference<>();

    /// Whether cancellation has been requested.
    private volatile boolean closed;

    /// Terminal failure published before its marker.
    private volatile @Nullable Throwable failure;

    /// Creates an unsubscribed response body.
    DistfileResponseBody() {
    }

    /// Makes headers available without waiting for body completion.
    @Override
    public CompletionStage<DistfileResponseBody> getBody() {
        return CompletableFuture.completedFuture(this);
    }

    /// Requests one chunk, or cancels a duplicate or late subscription.
    @Override
    public void onSubscribe(Flow.Subscription incoming) {
        if (!subscription.compareAndSet(null, incoming) || closed) {
            incoming.cancel();
        } else {
            incoming.request(1);
        }
    }

    /// Queues one demanded chunk without blocking an HTTP client thread.
    @Override
    public void onNext(List<ByteBuffer> item) {
        if (!closed) {
            if (item.isEmpty()) {
                requestNext();
            } else if (!chunks.offer(item)) {
                close();
                onError(new IOException("Distfile body exceeded its requested demand."));
            }
        }
    }

    /// Publishes a failure and wakes the download thread.
    @Override
    public void onError(Throwable throwable) {
        failure = throwable;
        chunks.offer(END);
    }

    /// Publishes completion after the last chunk.
    @Override
    public void onComplete() {
        chunks.offer(END);
    }

    /// Waits up to the given duration for a chunk, returning an empty list at end of body.
    /// The caller may consume buffer positions and must request another chunk only after consuming this one.
    /// Must not be called again after completion, failure, timeout, or closure.
    ///
    /// @param timeout positive maximum wait for the next chunk.
    /// @return received buffers, or an empty list on completion.
    /// @throws IOException when the body fails or the wait times out.
    /// @throws InterruptedException when the download thread is interrupted.
    List<ByteBuffer> next(Duration timeout) throws IOException, InterruptedException {
        @Nullable List<ByteBuffer> item = chunks.poll(timeout.toNanos(), TimeUnit.NANOSECONDS);
        @Nullable Throwable cause = failure;
        if (cause != null) {
            throw new IOException("Distfile response body failed.", cause);
        }
        if (item == null) {
            throw new HttpTimeoutException("Distfile response body timed out.");
        }
        return item;
    }

    /// Requests another chunk after the download thread has consumed the previous one.
    void requestNext() {
        @Nullable Flow.Subscription current = subscription.get();
        if (!closed && current != null) {
            current.request(1);
        }
    }

    /// Idempotently cancels reception, including late subscriptions, and releases queued buffers.
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

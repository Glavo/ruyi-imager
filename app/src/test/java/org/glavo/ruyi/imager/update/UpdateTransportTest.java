// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.update;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Exercises real HTTPS transfers using a loopback server and a fixture-only trust store.
/// The test certificate is valid for localhost and 127.0.0.1 from 2020 through 2119.
/// Its public test private key and password are not suitable for production use.
@NotNullByDefault
@Timeout(20)
public final class UpdateTransportTest {
    /// Reads exact bounds and empty bodies, rejecting both declared and chunked excess.
    @Test
    public void boundsReads() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.server.createContext("/fixed", exchange -> respond(exchange, 200, "hello"));
            fixture.server.createContext("/empty", exchange -> respond(exchange, 200, ""));
            fixture.server.createContext("/chunked", exchange -> {
                try (exchange) {
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().write("hello".getBytes(StandardCharsets.UTF_8));
                }
            });
            UpdateTransport transport = fixture.transport(Duration.ofSeconds(5));
            assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), transport.read(fixture.uri("/fixed"), 5));
            assertArrayEquals(new byte[0], transport.read(fixture.uri("/empty"), 0));
            assertThrows(IOException.class, () -> transport.read(fixture.uri("/fixed"), 4));
            assertThrows(IOException.class, () -> transport.read(fixture.uri("/chunked"), 4));
            assertThrows(IOException.class, () -> transport.read(fixture.uri("/chunked"), 0));
        }
    }

    /// Streams bytes with accurate bounded progress and permits bodies shorter than the limit.
    @Test
    public void downloadsWithProgress(@TempDir Path directory) throws Exception {
        try (var fixture = new Fixture()) {
            fixture.server.createContext("/file", exchange -> respond(exchange, 200, "hello"));
            Path destination = directory.resolve("package.part");
            List<UpdateProgress> progress = new ArrayList<>();
            fixture.transport(Duration.ofSeconds(5)).download(fixture.uri("/file"), destination, 8, progress::add);
            assertEquals("hello", Files.readString(destination));
            assertEquals(new UpdateProgress(0, 8), progress.getFirst());
            assertEquals(new UpdateProgress(5, 8), progress.getLast());
            long previous = -1;
            for (UpdateProgress event : progress) {
                assertTrue(event.currentBytes() > previous);
                assertEquals(8, event.totalBytes());
                previous = event.currentBytes();
            }
            Files.delete(destination);
        }
    }

    /// Rejects an oversized chunk before writing it or reporting its bytes as progress.
    @Test
    public void checksDownloadBoundBeforeWriting(@TempDir Path directory) throws Exception {
        try (var fixture = new Fixture()) {
            var firstChunkCopied = new CountDownLatch(1);
            fixture.server.createContext("/file", exchange -> {
                try (exchange) {
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().write(new byte[]{1, 2, 3});
                    exchange.getResponseBody().flush();
                    awaitHandler(firstChunkCopied);
                    exchange.getResponseBody().write(new byte[]{4, 5, 6});
                }
            });
            Path destination = directory.resolve("package.part");
            List<UpdateProgress> progress = new ArrayList<>();
            assertThrows(IOException.class, () -> fixture.transport(Duration.ofSeconds(5))
                    .download(fixture.uri("/file"), destination, 5, event -> {
                        progress.add(event);
                        if (event.currentBytes() == 3) {
                            firstChunkCopied.countDown();
                        }
                    }));
            assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(destination));
            assertEquals(new UpdateProgress(3, 5), progress.getLast());
            Files.delete(destination);
        }
    }

    /// Applies the HTTPS URI policy before sending a request or changing a destination.
    @Test
    public void rejectsUnsafeUris(@TempDir Path directory) throws Exception {
        try (var fixture = new Fixture()) {
            var requests = new AtomicInteger();
            fixture.server.createContext("/", exchange -> {
                requests.incrementAndGet();
                respond(exchange, 200, "unexpected");
            });
            UpdateTransport transport = fixture.transport(Duration.ofSeconds(5));
            Path destination = directory.resolve("package.part");
            Files.writeString(destination, "keep");
            for (String uri : List.of("http://localhost/", "file:///tmp/update", "/relative",
                    "https://user:password@localhost/", "https://localhost/#fragment",
                    "https:opaque", "https://localhost:0/", "https://localhost:65536/")) {
                assertThrows(IOException.class, () -> transport.read(URI.create(uri), 100));
                assertThrows(IOException.class,
                        () -> transport.download(URI.create(uri), destination, 100, ignored -> { }));
            }
            assertEquals(0, requests.get());
            assertEquals("keep", Files.readString(destination));
        }
    }

    /// Follows relative HTTPS redirects and blocks downgrades, credentials, fragments, and loops.
    @Test
    public void validatesEveryRedirect() throws Exception {
        try (var fixture = new Fixture()) {
            var requests = new AtomicInteger();
            fixture.server.createContext("/ok", exchange -> redirect(exchange, 302, "/target"));
            fixture.server.createContext("/target", exchange -> respond(exchange, 200, "done"));
            fixture.server.createContext("/loop", exchange -> {
                requests.incrementAndGet();
                redirect(exchange, 307, "/loop");
            });
            fixture.server.createContext("/downgrade", exchange -> redirect(exchange, 301, "http://127.0.0.1:1/"));
            fixture.server.createContext("/credentials", exchange -> redirect(exchange, 303, "https://user@localhost/"));
            fixture.server.createContext("/fragment", exchange -> redirect(exchange, 308, "/target#fragment"));
            fixture.server.createContext("/missing", exchange -> respond(exchange, 302, ""));
            fixture.server.createContext("/malformed", exchange -> redirect(exchange, 302, "https://["));
            UpdateTransport transport = fixture.transport(Duration.ofSeconds(5));
            assertEquals("done", new String(transport.read(fixture.uri("/ok"), 4), StandardCharsets.UTF_8));
            for (String path : List.of("/downgrade", "/credentials", "/fragment", "/missing", "/malformed", "/loop")) {
                IOException failure = assertThrows(IOException.class, () -> transport.read(fixture.uri(path), 100));
                assertFalse(failure instanceof HttpTimeoutException);
                if (path.equals("/downgrade")) {
                    assertTrue(failure.getMessage().contains("HTTPS"));
                }
            }
            assertEquals(6, requests.get());
        }
    }

    /// Rejects unsuccessful, partial, and encoded responses without reporting or writing body bytes.
    @Test
    public void rejectsInvalidResponses(@TempDir Path directory) throws Exception {
        try (var fixture = new Fixture()) {
            fixture.server.createContext("/error", exchange -> respond(exchange, 503, "error"));
            fixture.server.createContext("/partial", exchange -> respond(exchange, 206, "partial"));
            fixture.server.createContext("/encoded", exchange -> {
                exchange.getResponseHeaders().set("Content-Encoding", "gzip");
                respond(exchange, 200, "encoded");
            });
            fixture.server.createContext("/large", exchange -> respond(exchange, 200, "too large"));
            UpdateTransport transport = fixture.transport(Duration.ofSeconds(5));
            for (String path : List.of("/error", "/partial", "/encoded", "/large")) {
                Path destination = directory.resolve("package.part");
                List<UpdateProgress> progress = new ArrayList<>();
                assertThrows(IOException.class, () -> transport.download(fixture.uri(path), destination, 4, progress::add));
                assertEquals(0, Files.size(destination));
                assertTrue(progress.isEmpty());
                Files.delete(destination);
            }
        }
    }

    /// Cancels a live body after a callback failure and closes the partially written destination.
    @Test
    public void cancelsAfterCallbackFailure(@TempDir Path directory) throws Exception {
        try (var fixture = new Fixture()) {
            var disconnected = new CountDownLatch(1);
            fixture.server.createContext("/stream", exchange -> streamUntilDisconnected(exchange, disconnected));
            var expected = new IllegalStateException("Callback failed.");
            Path destination = directory.resolve("package.part");
            RuntimeException actual = assertThrows(RuntimeException.class,
                    () -> fixture.transport(Duration.ofSeconds(5)).download(fixture.uri("/stream"),
                            destination, Long.MAX_VALUE, event -> {
                                if (event.currentBytes() > 0) {
                                    throw expected;
                                }
                            }));
            assertSame(expected, actual);
            assertTrue(Files.size(destination) > 0);
            Files.delete(destination);
            assertTrue(disconnected.await(5, TimeUnit.SECONDS));
        }
    }

    /// Detects a truncated fixed-length body and releases the file for caller-owned deletion.
    @Test
    public void closesAfterTruncatedTransfer(@TempDir Path directory) throws Exception {
        try (var fixture = new Fixture()) {
            fixture.server.createContext("/truncated", exchange -> {
                try (exchange) {
                    exchange.sendResponseHeaders(200, 100);
                    exchange.getResponseBody().write(new byte[]{1, 2, 3});
                    exchange.getResponseBody().flush();
                }
            });
            Path destination = directory.resolve("package.part");
            IOException failure = assertThrows(IOException.class,
                    () -> fixture.transport(Duration.ofSeconds(5)).download(fixture.uri("/truncated"),
                            destination, 100, ignored -> { }));
            assertFalse(failure instanceof HttpTimeoutException);
            assertTrue(Files.size(destination) <= 3);
            Files.delete(destination);
        }
    }

    /// Includes stalled headers and stalled body consumption in the operation deadline.
    @Test
    public void timesOutHeadersAndBodies(@TempDir Path directory) throws Exception {
        try (var fixture = new Fixture()) {
            fixture.warmUp();
            var release = new CountDownLatch(1);
            var disconnected = new CountDownLatch(1);
            fixture.server.createContext("/headers", exchange -> {
                try (exchange) {
                    awaitHandler(release);
                }
            });
            fixture.server.createContext("/body", exchange -> {
                try (exchange) {
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().write(1);
                    exchange.getResponseBody().flush();
                    awaitHandler(release);
                    writeUntilDisconnected(exchange, disconnected);
                }
            });
            UpdateTransport transport = fixture.transport(Duration.ofMillis(500));
            assertThrows(HttpTimeoutException.class, () -> transport.read(fixture.uri("/headers"), 100));
            Path destination = directory.resolve("package.part");
            List<UpdateProgress> progress = new ArrayList<>();
            try {
                assertThrows(HttpTimeoutException.class,
                        () -> transport.download(fixture.uri("/body"), destination, 100, progress::add));
                assertEquals(new UpdateProgress(1, 100), progress.getLast());
                Files.delete(destination);
            } finally {
                release.countDown();
            }
            assertTrue(disconnected.await(5, TimeUnit.SECONDS));
        }
    }

    /// Reuses one deadline across redirects instead of giving each response a new budget.
    @Test
    public void sharesDeadlineAcrossRedirects() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.warmUp();
            var requests = new AtomicInteger();
            fixture.server.createContext("/redirect", exchange -> {
                requests.incrementAndGet();
                try (exchange) {
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    redirect(exchange, 302, "/redirect");
                }
            });
            assertThrows(HttpTimeoutException.class,
                    () -> fixture.transport(Duration.ofMillis(800)).read(fixture.uri("/redirect"), 100));
            assertTrue(requests.get() >= 2);
            assertTrue(requests.get() < 6);
        }
    }

    /// Interrupts a waiting body reader promptly, preserving its flag and cancelling the server stream.
    @Test
    public void interruptsBodyTransfer(@TempDir Path directory) throws Exception {
        try (var fixture = new Fixture()) {
            var copied = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var disconnected = new CountDownLatch(1);
            fixture.server.createContext("/body", exchange -> {
                try (exchange) {
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().write(1);
                    exchange.getResponseBody().flush();
                    awaitHandler(release);
                    writeUntilDisconnected(exchange, disconnected);
                }
            });
            Path destination = directory.resolve("package.part");
            var failure = new AtomicReference<@Nullable Throwable>();
            var interrupted = new AtomicBoolean();
            Thread reader = Thread.ofVirtual().start(() -> {
                try {
                    fixture.transport(Duration.ofSeconds(10)).download(fixture.uri("/body"),
                            destination, 100, event -> {
                                if (event.currentBytes() > 0) {
                                    copied.countDown();
                                }
                            });
                } catch (Throwable exception) {
                    failure.set(exception);
                    interrupted.set(Thread.currentThread().isInterrupted());
                }
            });
            try {
                assertTrue(copied.await(5, TimeUnit.SECONDS));
                reader.interrupt();
                assertTrue(reader.join(Duration.ofSeconds(3)));
                assertInstanceOf(InterruptedIOException.class, failure.get());
                assertTrue(interrupted.get());
                assertEquals(1, Files.size(destination));
                Files.delete(destination);
            } finally {
                reader.interrupt();
                release.countDown();
                assertTrue(reader.join(Duration.ofSeconds(3)));
            }
            assertTrue(disconnected.await(5, TimeUnit.SECONDS));
        }
    }

    /// Rejects invalid configuration and observes preexisting interruption before opening a file.
    @Test
    public void validatesConfigurationAndPreexistingInterruption(@TempDir Path directory) throws Exception {
        try (var fixture = new Fixture();
             var redirecting = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build()) {
            assertThrows(IllegalArgumentException.class, () -> new UpdateTransport(redirecting));
            assertThrows(IllegalArgumentException.class, () -> fixture.transport(Duration.ZERO));
            assertThrows(IllegalArgumentException.class, () -> fixture.transport(Duration.ofSeconds(-1)));
            assertThrows(IllegalArgumentException.class, () -> fixture.transport(Duration.ofSeconds(Long.MAX_VALUE)));
            UpdateTransport transport = fixture.transport(Duration.ofSeconds(5));
            assertThrows(IllegalArgumentException.class, () -> transport.read(fixture.uri("/"), -1));
            Path destination = directory.resolve("package.part");
            assertThrows(IllegalArgumentException.class,
                    () -> transport.download(fixture.uri("/"), destination, 0, ignored -> { }));
            Thread.currentThread().interrupt();
            try {
                assertThrows(InterruptedIOException.class,
                        () -> transport.download(fixture.uri("/"), destination, 100, ignored -> { }));
                assertTrue(Thread.currentThread().isInterrupted());
                assertFalse(Files.exists(destination));
            } finally {
                Thread.interrupted();
            }
        }
    }

    /// Sends a UTF-8 fixed-length body, or a bodyless response when the text is empty.
    private static void respond(HttpExchange exchange, int status, String text) throws IOException {
        try (exchange) {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
        }
    }

    /// Sends a redirect whose body does not need to be drained by the client.
    private static void redirect(HttpExchange exchange, int status, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        respond(exchange, status, "redirect body");
    }

    /// Waits for a test-controlled release, converting handler interruption into an I/O failure.
    private static void awaitHandler(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IOException("Test handler release timed out.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Test handler interrupted.", exception);
        }
    }

    /// Starts a chunked stream and records when cancellation closes the client connection.
    private static void streamUntilDisconnected(HttpExchange exchange, CountDownLatch disconnected) throws IOException {
        try (exchange) {
            exchange.sendResponseHeaders(200, 0);
            writeUntilDisconnected(exchange, disconnected);
        }
    }

    /// Writes a bounded number of chunks until the peer closes, without retaining package-sized data.
    private static void writeUntilDisconnected(HttpExchange exchange, CountDownLatch disconnected) {
        byte[] chunk = new byte[8192];
        try {
            for (int index = 0; index < 2048 && !Thread.currentThread().isInterrupted(); index++) {
                exchange.getResponseBody().write(chunk);
                exchange.getResponseBody().flush();
            }
        } catch (IOException expected) {
            disconnected.countDown();
        }
    }

    /// Owns a loopback HTTPS server and a client trusting only the dedicated test certificate.
    @NotNullByDefault
    private static final class Fixture implements AutoCloseable {
        /// Server accepting requests only on the loopback interface.
        private final HttpsServer server;

        /// Interruptible request handlers that are stopped when the fixture closes.
        private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        /// Client with real hostname verification and fixture-only certificate trust.
        private final HttpClient client;

        /// Loads the public test key material and starts an isolated HTTPS listener.
        private Fixture() throws Exception {
            KeyStore store = KeyStore.getInstance("PKCS12");
            try (InputStream input = Objects.requireNonNull(UpdateTransportTest.class
                    .getResourceAsStream("update-transport-test.p12"))) {
                store.load(input, "changeit".toCharArray());
            }
            KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keys.init(store, "changeit".toCharArray());
            TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init(store);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keys.getKeyManagers(), trust.getTrustManagers(), null);
            server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setHttpsConfigurator(new HttpsConfigurator(context));
            server.setExecutor(executor);
            client = HttpClient.newBuilder()
                    .sslContext(context)
                    .proxy(new DirectProxySelector())
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
            server.start();
        }

        /// Resolves an absolute path against the loopback listener.
        private URI uri(String path) {
            return URI.create("https://127.0.0.1:" + server.getAddress().getPort() + path);
        }

        /// Creates a transport borrowing the fixture client for a bounded operation.
        private UpdateTransport transport(Duration timeout) {
            return new UpdateTransport(client, timeout);
        }

        /// Completes the initial TLS handshake before tests using deliberately short deadlines.
        private void warmUp() throws IOException {
            server.createContext("/warmup", exchange -> respond(exchange, 200, "ready"));
            assertEquals(5, transport(Duration.ofSeconds(5)).read(uri("/warmup"), 5).length);
        }

        /// Stops server handlers and the client without waiting indefinitely for an unfinished exchange.
        @Override
        public void close() throws InterruptedException {
            server.stop(0);
            executor.shutdownNow();
            client.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(client.awaitTermination(Duration.ofSeconds(5)));
        }
    }

    /// Keeps loopback fixtures independent of system or application proxy configuration.
    @NotNullByDefault
    private static final class DirectProxySelector extends ProxySelector {
        /// Creates a selector that never sends fixture traffic to a proxy.
        private DirectProxySelector() {
        }

        /// Returns a direct route for each fixture URI.
        @Override
        public @Unmodifiable List<Proxy> select(URI uri) {
            return List.of(Proxy.NO_PROXY);
        }

        /// Rejects unexpected proxy failure callbacks because no proxy was selected.
        @Override
        public void connectFailed(URI uri, SocketAddress address, IOException exception) {
            throw new AssertionError("Unexpected test proxy failure.", exception);
        }
    }
}

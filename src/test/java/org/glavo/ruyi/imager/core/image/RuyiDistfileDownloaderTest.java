// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.image;

import org.glavo.ruyi.imager.core.ProgressReporter;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/// Tests for Ruyi distfile downloading.
@NotNullByDefault
public final class RuyiDistfileDownloaderTest {
    /// Progress reporter that ignores progress events.
    private static final ProgressReporter NO_PROGRESS = _ -> {
    };

    /// Verifies that a distfile can be downloaded and verified.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when the fixture server or filesystem fails.
    @Test
    public void downloadsDistfileAndVerifiesChecksum(@TempDir Path temporaryDirectory) throws Exception {
        byte[] content = "hello ruyi\n".getBytes(StandardCharsets.UTF_8);
        try (TinyHttpServer server = new TinyHttpServer(content)) {
            RuyiDistfile distfile = new RuyiDistfile(
                    "image.raw",
                    List.of(server.uri("/image.raw")),
                    (long) content.length,
                    Map.of("sha256", sha256(content)),
                    false,
                    true,
                    0,
                    "raw");

            Path result = new RuyiDistfileDownloader().download(distfile, temporaryDirectory, NO_PROGRESS);

            assertEquals(temporaryDirectory.resolve("image.raw"), result);
            assertArrayEquals(content, Files.readAllBytes(result));
            assertEquals(List.of(), server.rangeRequests());
        }
    }

    /// Verifies that an existing partial file is resumed with a Range request.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when the fixture server or filesystem fails.
    @Test
    public void resumesPartialDownloadWithRangeRequest(@TempDir Path temporaryDirectory) throws Exception {
        byte[] content = new byte[1024];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) i;
        }

        Files.write(temporaryDirectory.resolve("image.raw.part"), Arrays.copyOf(content, 128));
        try (TinyHttpServer server = new TinyHttpServer(content)) {
            RuyiDistfile distfile = new RuyiDistfile(
                    "image.raw",
                    List.of(server.uri("/image.raw")),
                    (long) content.length,
                    Map.of("sha256", sha256(content)),
                    false,
                    true,
                    0,
                    "raw");

            Path result = new RuyiDistfileDownloader().download(distfile, temporaryDirectory, NO_PROGRESS);

            assertArrayEquals(content, Files.readAllBytes(result));
            assertEquals(List.of("bytes=128-"), server.rangeRequests());
        }
    }

    /// Computes a SHA-256 digest for bytes.
    ///
    /// @param content bytes to digest.
    /// @return lowercase hexadecimal digest.
    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    /// Minimal HTTP server that supports GET and byte ranges.
    @NotNullByDefault
    private static final class TinyHttpServer implements AutoCloseable {
        /// Bytes served by the fixture server.
        private final byte[] content;

        /// Server socket bound to loopback.
        private final ServerSocket serverSocket;

        /// Background accept loop.
        private final Thread thread;

        /// Range request headers received by the server.
        private final List<String> rangeRequests = java.util.Collections.synchronizedList(new ArrayList<>());

        /// Creates and starts the fixture server.
        ///
        /// @param content bytes to serve.
        /// @throws IOException when the server socket cannot be opened.
        private TinyHttpServer(byte[] content) throws IOException {
            this.content = content.clone();
            this.serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            this.thread = new Thread(this::run, "ruyi-imager-test-http");
            this.thread.setDaemon(true);
            this.thread.start();
        }

        /// Returns a URI on this server.
        ///
        /// @param path request path.
        /// @return fixture URI.
        private URI uri(String path) {
            return URI.create("http://" + serverSocket.getInetAddress().getHostAddress() + ":" + serverSocket.getLocalPort() + path);
        }

        /// Returns received Range request headers.
        ///
        /// @return immutable range header list.
        private List<String> rangeRequests() {
            synchronized (rangeRequests) {
                return List.copyOf(rangeRequests);
            }
        }

        /// Runs the server accept loop.
        private void run() {
            while (!serverSocket.isClosed()) {
                try {
                    handle(serverSocket.accept());
                } catch (IOException e) {
                    if (!serverSocket.isClosed()) {
                        throw new IllegalStateException(e);
                    }
                }
            }
        }

        /// Handles one HTTP connection.
        ///
        /// @param socket client socket.
        /// @throws IOException when the connection cannot be read or written.
        private void handle(Socket socket) throws IOException {
            try (Socket client = socket;
                 BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
                 OutputStream output = client.getOutputStream()) {
                client.setSoTimeout(5000);
                @Nullable String requestLine = reader.readLine();
                if (requestLine == null || !requestLine.startsWith("GET ")) {
                    writeResponse(output, 400, 0, 0);
                    return;
                }

                @Nullable String range = null;
                while (true) {
                    @Nullable String line = reader.readLine();
                    if (line == null || line.isEmpty()) {
                        break;
                    }
                    if (line.regionMatches(true, 0, "Range:", 0, "Range:".length())) {
                        range = line.substring("Range:".length()).strip();
                    }
                }

                if (range != null) {
                    rangeRequests.add(range);
                    long start = parseRangeStart(range);
                    if (start < 0L || start >= content.length) {
                        writeResponse(output, 416, 0, 0);
                        return;
                    }
                    writeResponse(output, 206, Math.toIntExact(start), content.length - Math.toIntExact(start));
                    return;
                }

                writeResponse(output, 200, 0, content.length);
            }
        }

        /// Writes one HTTP response.
        ///
        /// @param output output stream.
        /// @param status HTTP status.
        /// @param offset content offset.
        /// @param length content length.
        /// @throws IOException when the response cannot be written.
        private void writeResponse(OutputStream output, int status, int offset, int length) throws IOException {
            String reason = switch (status) {
                case 200 -> "OK";
                case 206 -> "Partial Content";
                case 416 -> "Range Not Satisfiable";
                default -> "Bad Request";
            };
            StringBuilder headers = new StringBuilder()
                    .append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
                    .append("Content-Length: ").append(length).append("\r\n")
                    .append("Connection: close\r\n");
            if (status == 206) {
                headers.append("Content-Range: bytes ")
                        .append(offset)
                        .append('-')
                        .append(offset + length - 1)
                        .append('/')
                        .append(content.length)
                        .append("\r\n");
            }
            headers.append("\r\n");
            output.write(headers.toString().getBytes(StandardCharsets.US_ASCII));
            if (length > 0) {
                output.write(content, offset, length);
            }
            output.flush();
        }

        /// Parses the starting byte of a Range header.
        ///
        /// @param range Range header value.
        /// @return start byte, or -1 when invalid.
        private static long parseRangeStart(String range) {
            if (!range.startsWith("bytes=")) {
                return -1L;
            }
            int end = range.indexOf('-', "bytes=".length());
            if (end < 0) {
                return -1L;
            }
            try {
                return Long.parseLong(range.substring("bytes=".length(), end));
            } catch (NumberFormatException e) {
                return -1L;
            }
        }

        /// Stops the fixture server.
        ///
        /// @throws IOException when the server socket cannot be closed.
        @Override
        public void close() throws IOException {
            serverSocket.close();
        }
    }
}

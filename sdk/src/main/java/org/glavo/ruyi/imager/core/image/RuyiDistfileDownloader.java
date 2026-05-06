// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.image;

import org.glavo.ruyi.imager.core.ProgressEvent;
import org.glavo.ruyi.imager.core.ProgressReporter;
import org.glavo.ruyi.imager.i18n.Messages;
import org.glavo.ruyi.imager.logging.LogRedactor;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/// Downloads and verifies Ruyi distfiles.
@NotNullByDefault
public final class RuyiDistfileDownloader {
    /// Logger for distfile download operations.
    private static final Logger LOGGER = Logger.getLogger(RuyiDistfileDownloader.class.getName());

    /// HTTP client used for distfile downloads.
    private final HttpClient httpClient;

    /// Creates a downloader with a default HTTP client.
    public RuyiDistfileDownloader() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    /// Creates a downloader with a provided HTTP client.
    ///
    /// @param httpClient HTTP client.
    public RuyiDistfileDownloader(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /// Downloads a distfile into the target directory.
    ///
    /// @param distfile distfile declaration.
    /// @param targetDirectory target directory.
    /// @param reporter progress reporter.
    /// @return verified target path.
    /// @throws IOException when the distfile cannot be downloaded or verified.
    public Path download(RuyiDistfile distfile, Path targetDirectory, ProgressReporter reporter) throws IOException {
        Files.createDirectories(targetDirectory);
        Path target = targetDirectory.resolve(distfile.name());
        LOGGER.info(() -> "Downloading distfile. name="
                + distfile.name()
                + ", target="
                + target
                + ", sources="
                + distfile.sourceUris().size());
        if (Files.isRegularFile(target) && verify(target, distfile)) {
            LOGGER.info(() -> "Using cached distfile. name=" + distfile.name() + ", path=" + target);
            reporter.report(new ProgressEvent(
                    "download",
                    Messages.get("core.download.cached", distfile.name()),
                    bytesOrNull(target),
                    distfile.sizeBytes()));
            return target;
        }

        if (distfile.fetchRestricted()) {
            LOGGER.warning(() -> "Distfile requires manual download. name=" + distfile.name() + ", target=" + target);
            throw new IOException(manualDownloadMessage(distfile, target));
        }

        List<URI> sourceUris = distfile.sourceUris();
        if (sourceUris.isEmpty()) {
            LOGGER.warning(() -> "Distfile has no source URLs. name=" + distfile.name());
            throw new IOException(Messages.get("core.download.noUrls", distfile.name()));
        }

        Path partial = targetDirectory.resolve(distfile.name() + ".part");
        IOException failure = null;
        for (URI sourceUri : sourceUris) {
            try {
                downloadFromUri(distfile, sourceUri, partial, reporter);
                moveVerifiedPartial(partial, target, distfile);
                LOGGER.info(() -> "Distfile download completed. name=" + distfile.name() + ", target=" + target);
                return target;
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Distfile source failed. name="
                        + distfile.name()
                        + ", uri="
                        + LogRedactor.redactUri(sourceUri), e);
                failure = e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOGGER.log(Level.WARNING, "Distfile download interrupted. name=" + distfile.name(), e);
                throw new IOException(Messages.get("core.download.interrupted", distfile.name()), e);
            }
        }

        throw new IOException(Messages.get("core.download.failed", distfile.name()), failure);
    }

    /// Downloads one distfile from one source URI.
    ///
    /// @param distfile distfile declaration.
    /// @param sourceUri source URI.
    /// @param partial partial output path.
    /// @param reporter progress reporter.
    /// @throws IOException when the download fails.
    /// @throws InterruptedException when the HTTP request is interrupted.
    private void downloadFromUri(
            RuyiDistfile distfile,
            URI sourceUri,
            Path partial,
            ProgressReporter reporter) throws IOException, InterruptedException {
        @Nullable String scheme = sourceUri.getScheme();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            LOGGER.warning(() -> "Unsupported distfile URI scheme. uri=" + LogRedactor.redactUri(sourceUri));
            throw new IOException(Messages.get("core.download.unsupportedScheme", sourceUri));
        }

        long existingBytes = Files.isRegularFile(partial) ? Files.size(partial) : 0L;
        Long expectedSize = distfile.sizeBytes();
        if (expectedSize != null && existingBytes > expectedSize) {
            long oversizedBytes = existingBytes;
            LOGGER.info(() -> "Discarding oversized partial download. name="
                    + distfile.name()
                    + ", partial="
                    + partial
                    + ", partialBytes="
                    + oversizedBytes
                    + ", expectedBytes="
                    + expectedSize);
            Files.deleteIfExists(partial);
            existingBytes = 0L;
        }

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(sourceUri)
                .timeout(Duration.ofMinutes(10))
                .GET();
        if (existingBytes > 0L) {
            requestBuilder.header("Range", "bytes=" + existingBytes + "-");
        }

        long resumeBytes = existingBytes;
        LOGGER.info(() -> "Requesting distfile source. name="
                + distfile.name()
                + ", uri="
                + LogRedactor.redactUri(sourceUri)
                + ", resumeBytes="
                + resumeBytes);
        reporter.report(new ProgressEvent("download", Messages.get("core.download.downloading", distfile.name()), existingBytes, expectedSize));
        HttpResponse<InputStream> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());
        int statusCode = response.statusCode();
        LOGGER.info(() -> "Distfile source responded. name="
                + distfile.name()
                + ", uri="
                + LogRedactor.redactUri(sourceUri)
                + ", status="
                + statusCode);
        boolean append = existingBytes > 0L && statusCode == 206;
        if (existingBytes > 0L && statusCode == 416 && verify(partial, distfile)) {
            response.body().close();
            return;
        }
        if (statusCode != 200 && statusCode != 206) {
            response.body().close();
            LOGGER.warning(() -> "Unexpected distfile source status. name="
                    + distfile.name()
                    + ", uri="
                    + LogRedactor.redactUri(sourceUri)
                    + ", status="
                    + statusCode);
            throw new IOException(Messages.get("core.download.unexpectedStatus", statusCode, sourceUri));
        }
        if (existingBytes > 0L && statusCode == 200) {
            LOGGER.info(() -> "Source ignored range request; restarting partial download. name=" + distfile.name());
            existingBytes = 0L;
        }

        writeResponse(response.body(), partial, distfile, reporter, existingBytes, append);
    }

    /// Writes an HTTP response body into the partial file.
    ///
    /// @param body response body.
    /// @param partial partial output path.
    /// @param distfile distfile declaration.
    /// @param reporter progress reporter.
    /// @param initialBytes bytes already present in the partial file.
    /// @param append whether the response should be appended.
    /// @throws IOException when the response cannot be written.
    private static void writeResponse(
            InputStream body,
            Path partial,
            RuyiDistfile distfile,
            ProgressReporter reporter,
            long initialBytes,
            boolean append) throws IOException {
        StandardOpenOption writeMode = append ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING;
        try (InputStream input = body;
             OutputStream output = Files.newOutputStream(partial, StandardOpenOption.CREATE, StandardOpenOption.WRITE, writeMode)) {
            byte[] buffer = new byte[256 * 1024];
            long currentBytes = initialBytes;
            while (true) {
                int read = input.read(buffer);
                if (read < 0) {
                    break;
                }
                output.write(buffer, 0, read);
                currentBytes += read;
                reporter.report(new ProgressEvent(
                        "download",
                        Messages.get("core.download.downloading", distfile.name()),
                        currentBytes,
                        distfile.sizeBytes()));
            }
        }
    }

    /// Moves a verified partial download into its final path.
    ///
    /// @param partial partial path.
    /// @param target final target path.
    /// @param distfile distfile declaration.
    /// @throws IOException when verification or move fails.
    private static void moveVerifiedPartial(Path partial, Path target, RuyiDistfile distfile) throws IOException {
        if (!verify(partial, distfile)) {
            LOGGER.warning(() -> "Downloaded distfile verification failed. name="
                    + distfile.name()
                    + ", partial="
                    + partial);
            throw new IOException(Messages.get("core.download.verifyFailed", distfile.name()));
        }

        try {
            Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /// Verifies a distfile against size and supported checksums.
    ///
    /// @param path file path.
    /// @param distfile distfile declaration.
    /// @return whether the file matches available integrity metadata.
    /// @throws IOException when the file cannot be read.
    private static boolean verify(Path path, RuyiDistfile distfile) throws IOException {
        if (!Files.isRegularFile(path)) {
            return false;
        }

        Long expectedSize = distfile.sizeBytes();
        if (expectedSize != null && Files.size(path) != expectedSize) {
            return false;
        }

        Map<String, String> checksums = distfile.checksums();
        @Nullable String sha256 = checksums.get("sha256");
        if (sha256 != null && !sha256.equalsIgnoreCase(computeDigest(path, "SHA-256"))) {
            return false;
        }

        @Nullable String sha512 = checksums.get("sha512");
        return sha512 == null || sha512.equalsIgnoreCase(computeDigest(path, "SHA-512"));
    }

    /// Creates a manual download error message.
    ///
    /// @param distfile distfile declaration.
    /// @param target expected target path.
    /// @return manual download message.
    private static String manualDownloadMessage(RuyiDistfile distfile, Path target) {
        @Nullable RuyiFetchRestriction restriction = distfile.fetchRestriction();
        if (restriction == null) {
            return Messages.get("core.download.manual", distfile.name());
        }

        String instructions = restriction.render(target, Messages.locale());
        if (instructions.isBlank()) {
            return Messages.get("core.download.manual", distfile.name());
        }
        return Messages.get("core.download.manualWithInstructions", distfile.name(), instructions);
    }

    /// Computes a message digest for a file.
    ///
    /// @param path file path.
    /// @param algorithm message digest algorithm.
    /// @return lowercase hexadecimal digest.
    /// @throws IOException when the file cannot be read.
    private static String computeDigest(Path path, String algorithm) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(Messages.get("core.download.missingDigest", algorithm), e);
        }

        try (InputStream input = new DigestInputStream(Files.newInputStream(path), digest)) {
            input.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /// Reads file size if the file exists.
    ///
    /// @param path file path.
    /// @return size in bytes, or null when the file cannot be read.
    private static @Nullable Long bytesOrNull(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return null;
        }
    }
}

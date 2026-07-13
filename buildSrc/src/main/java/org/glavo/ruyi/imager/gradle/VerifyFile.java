// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/// Verifies a file by size and SHA-256 digest.
@DisableCachingByDefault(because = "Verification tasks have no outputs.")
@NotNullByDefault
public abstract class VerifyFile extends DefaultTask {
    /// Returns the file to verify.
    ///
    /// @return file to verify.
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getInputFile();

    /// Returns the expected file size in bytes.
    ///
    /// @return expected file size in bytes.
    @Input
    public abstract Property<Long> getExpectedSizeBytes();

    /// Returns the expected SHA-256 digest as lowercase or uppercase hexadecimal.
    ///
    /// @return expected SHA-256 digest.
    @Input
    public abstract Property<String> getExpectedSha256();

    /// Verifies the input file.
    ///
    /// @throws IOException if the input file cannot be read.
    @TaskAction
    public void run() throws IOException {
        Path file = getInputFile().get().getAsFile().toPath();
        long expectedSizeBytes = getExpectedSizeBytes().get();
        long actualSizeBytes = Files.size(file);
        if (actualSizeBytes != expectedSizeBytes) {
            throw new GradleException(
                    "Expected " + file.getFileName() + " to be " + expectedSizeBytes
                            + " bytes, but was " + actualSizeBytes + " bytes");
        }

        String expectedSha256 = getExpectedSha256().get().toLowerCase(Locale.ROOT);
        String actualSha256 = sha256(file);
        if (!actualSha256.equals(expectedSha256)) {
            throw new GradleException(
                    "Expected " + file.getFileName() + " SHA-256 to be " + expectedSha256
                            + ", but was " + actualSha256);
        }
    }

    /// Computes the SHA-256 digest for a file.
    ///
    /// @param file file to read.
    /// @return lowercase hexadecimal SHA-256 digest.
    /// @throws IOException if the file cannot be read.
    private static String sha256(Path file) throws IOException {
        MessageDigest digest = newSha256Digest();
        byte[] buffer = new byte[1024 * 1024];
        try (InputStream input = Files.newInputStream(file)) {
            while (true) {
                int length = input.read(buffer);
                if (length < 0) {
                    break;
                }
                digest.update(buffer, 0, length);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /// Creates a SHA-256 message digest.
    ///
    /// @return SHA-256 message digest.
    private static MessageDigest newSha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest is not available", e);
        }
    }
}

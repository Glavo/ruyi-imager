// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.update;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests verified local update package preparation and platform handoff commands.
@NotNullByDefault
public final class UpdatePackageManagerTest {
    /// Copies a matching installer into the content-addressed update cache.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when fixture files cannot be prepared.
    @Test
    public void preparesVerifiedPackage(@TempDir Path temporaryDirectory) throws Exception {
        byte[] packageBytes = "verified setup package".getBytes();
        Path source = temporaryDirectory.resolve("packages").resolve("ruyi-imager-setup.exe");
        Files.createDirectories(source.getParent());
        Files.write(source, packageBytes);
        Path manifest = temporaryDirectory.resolve("update.json");
        Files.writeString(manifest, "{}");
        UpdateArtifact artifact = artifact("packages/ruyi-imager-setup.exe", packageBytes);
        UpdateRelease release = release(artifact);
        AtomicReference<UpdateProgress> lastProgress = new AtomicReference<>();

        PreparedUpdate prepared = new UpdatePackageManager(
                manifest,
                temporaryDirectory.resolve("cache"),
                UpdatePlatform.WINDOWS_X86_64).prepare(release, lastProgress::set);

        assertArrayEquals(packageBytes, Files.readAllBytes(prepared.packageFile()));
        assertEquals(packageBytes.length, lastProgress.get().currentBytes());
        assertEquals(artifact.sha256(), prepared.packageFile().getParent().getFileName().toString());
    }

    /// Rejects a package whose digest differs from the manifest.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when fixture files cannot be prepared.
    @Test
    public void rejectsDigestMismatch(@TempDir Path temporaryDirectory) throws Exception {
        byte[] packageBytes = "untrusted setup package".getBytes();
        Path source = temporaryDirectory.resolve("ruyi-imager-setup.exe");
        Files.write(source, packageBytes);
        Path manifest = temporaryDirectory.resolve("update.json");
        Files.writeString(manifest, "{}");
        UpdateArtifact artifact = new UpdateArtifact(
                UpdatePlatform.WINDOWS_X86_64,
                UpdatePackageType.SETUP_EXE,
                source.getFileName().toString(),
                packageBytes.length,
                "0".repeat(64));

        assertThrows(
                IOException.class,
                () -> new UpdatePackageManager(
                        manifest,
                        temporaryDirectory.resolve("cache"),
                        UpdatePlatform.WINDOWS_X86_64).prepare(release(artifact), _ -> {
                }));
    }

    /// Rejects lexical source traversal outside the manifest directory.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when fixture files cannot be prepared.
    @Test
    public void rejectsSourceTraversal(@TempDir Path temporaryDirectory) throws Exception {
        byte[] packageBytes = "outside setup package".getBytes();
        Path manifestDirectory = temporaryDirectory.resolve("manifest");
        Files.createDirectories(manifestDirectory);
        Path manifest = manifestDirectory.resolve("update.json");
        Files.writeString(manifest, "{}");
        Files.write(temporaryDirectory.resolve("outside.exe"), packageBytes);
        UpdateArtifact artifact = artifact("../outside.exe", packageBytes);

        assertThrows(
                IOException.class,
                () -> new UpdatePackageManager(
                        manifest,
                        temporaryDirectory.resolve("cache"),
                        UpdatePlatform.WINDOWS_X86_64).prepare(release(artifact), _ -> {
                }));
    }

    /// Builds fixed commands without accepting manifest-provided arguments.
    ///
    /// @param temporaryDirectory temporary test directory.
    @Test
    public void buildsPlatformInstallerCommands(@TempDir Path temporaryDirectory) {
        Path windowsPackage = temporaryDirectory.resolve("setup with spaces.exe");
        Path linuxPackage = temporaryDirectory.resolve("ruyi-imager.deb");
        Path macPackage = temporaryDirectory.resolve("ruyi-imager.pkg");

        assertEquals(
                List.of(windowsPackage.toAbsolutePath().toString()),
                UpdateInstaller.commandFor(
                        UpdatePlatform.WINDOWS_X86_64,
                        UpdatePackageType.SETUP_EXE,
                        windowsPackage));
        assertEquals(
                List.of("xdg-open", linuxPackage.toAbsolutePath().toString()),
                UpdateInstaller.commandFor(
                        UpdatePlatform.LINUX_X86_64,
                        UpdatePackageType.DEB,
                        linuxPackage));
        assertEquals(
                List.of("open", macPackage.toAbsolutePath().toString()),
                UpdateInstaller.commandFor(
                        UpdatePlatform.MACOS_AARCH64,
                        UpdatePackageType.PKG,
                        macPackage));
    }

    /// Detects common Java operating system and architecture aliases.
    @Test
    public void detectsUpdatePlatforms() {
        assertEquals(UpdatePlatform.WINDOWS_X86_64, UpdatePlatform.detect("Windows 11", "amd64"));
        assertEquals(UpdatePlatform.LINUX_AARCH64, UpdatePlatform.detect("Linux", "aarch64"));
        assertEquals(UpdatePlatform.MACOS_AARCH64, UpdatePlatform.detect("Mac OS X", "arm64"));
    }

    /// Creates one Windows artifact for package bytes.
    ///
    /// @param source relative package source.
    /// @param bytes  expected package bytes.
    /// @return update artifact.
    private static UpdateArtifact artifact(String source, byte[] bytes) {
        return new UpdateArtifact(
                UpdatePlatform.WINDOWS_X86_64,
                UpdatePackageType.SETUP_EXE,
                source,
                bytes.length,
                sha256(bytes));
    }

    /// Creates one stable release containing an artifact.
    ///
    /// @param artifact release artifact.
    /// @return update release.
    private static UpdateRelease release(UpdateArtifact artifact) {
        return new UpdateRelease(
                UpdateChannel.STABLE,
                "1.1.0",
                "Test release",
                List.of(artifact));
    }

    /// Computes a hexadecimal SHA-256 digest.
    ///
    /// @param bytes source bytes.
    /// @return hexadecimal digest.
    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}

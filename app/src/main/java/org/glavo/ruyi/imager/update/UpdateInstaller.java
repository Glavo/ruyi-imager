// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.update;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

/// Starts a verified installer with a fixed platform-specific handoff command.
@NotNullByDefault
public final class UpdateInstaller {
    /// Prevents construction.
    private UpdateInstaller() {
    }

    /// Starts the prepared installer without waiting for it to exit.
    ///
    /// @param prepared verified prepared update.
    /// @throws IOException when the installer cannot be started.
    public static void launch(PreparedUpdate prepared) throws IOException {
        Path packageFile = prepared.packageFile().toRealPath();
        if (!Files.isRegularFile(packageFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Prepared update package is not a regular file: " + packageFile);
        }

        UpdatePlatform platform = UpdatePlatform.current();
        UpdateArtifact artifact = prepared.artifact();
        if (artifact.platform() != platform || !platform.supports(artifact.packageType())) {
            throw new IOException("Prepared update package does not match the current platform: " + platform.id());
        }
        if (!artifact.packageType().matchesFileName(packageFile.getFileName().toString())) {
            throw new IOException("Prepared update package file name does not match its package type: " + packageFile);
        }
        if (!UpdatePackageManager.verifyFile(packageFile, artifact)) {
            throw new IOException("Prepared update package no longer matches the verified manifest: " + packageFile);
        }

        ProcessBuilder builder = new ProcessBuilder(commandFor(platform, artifact.packageType(), packageFile));
        builder.directory(packageFile.getParent().toFile());
        builder.redirectInput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.start();
    }

    /// Builds the fixed installer handoff command for one platform.
    ///
    /// @param platform    current platform.
    /// @param packageType package type.
    /// @param packageFile installer package path.
    /// @return command argument list.
    static @Unmodifiable List<String> commandFor(
            UpdatePlatform platform,
            UpdatePackageType packageType,
            Path packageFile) {
        if (!platform.supports(packageType)) {
            throw new IllegalArgumentException(
                    "Update package type " + packageType.token() + " is not valid for " + platform.id() + '.');
        }
        String path = packageFile.toAbsolutePath().normalize().toString();
        return switch (platform) {
            case WINDOWS_X86_64, WINDOWS_AARCH64 -> List.of(path);
            case LINUX_X86_64, LINUX_AARCH64 -> List.of("xdg-open", path);
            case MACOS_X86_64, MACOS_AARCH64 -> List.of("open", path);
        };
    }
}

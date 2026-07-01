// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/// Writes Debian package launcher wrappers and desktop metadata.
@CacheableTask
@NotNullByDefault
public abstract class WriteJlinkDebMetadata extends DefaultTask {
    /// Creates a Debian package metadata task.
    public WriteJlinkDebMetadata() {
        getInstallDirectory().convention("/opt/ruyi-imager");
        getExecutableName().convention("ruyi-imager");
        getCliExecutableName().convention("ruyi-imager-cli");
        getDesktopFileName().convention("ruyi-imager.desktop");
        getDesktopName().convention("Ruyi Imager");
        getDesktopComment().convention("Flash Ruyi SDK and local images to removable devices");
        getDesktopIcon().convention("ruyi-imager");
    }

    /// Returns the packaged application installation directory.
    ///
    /// @return package installation directory.
    @Input
    public abstract Property<String> getInstallDirectory();

    /// Returns the GUI executable name.
    ///
    /// @return GUI executable name.
    @Input
    public abstract Property<String> getExecutableName();

    /// Returns the CLI executable name.
    ///
    /// @return CLI executable name.
    @Input
    public abstract Property<String> getCliExecutableName();

    /// Returns the desktop entry file name.
    ///
    /// @return desktop entry file name.
    @Input
    public abstract Property<String> getDesktopFileName();

    /// Returns the desktop entry display name.
    ///
    /// @return desktop entry display name.
    @Input
    public abstract Property<String> getDesktopName();

    /// Returns the desktop entry comment.
    ///
    /// @return desktop entry comment.
    @Input
    public abstract Property<String> getDesktopComment();

    /// Returns the desktop entry icon name.
    ///
    /// @return desktop entry icon name.
    @Input
    public abstract Property<String> getDesktopIcon();

    /// Returns the output directory containing Debian package metadata files.
    ///
    /// @return metadata output directory.
    @OutputDirectory
    public abstract DirectoryProperty getOutputDirectory();

    /// Writes Debian package metadata files.
    ///
    /// @throws IOException if a metadata file cannot be written.
    @TaskAction
    public void run() throws IOException {
        Path outputDirectory = getOutputDirectory().get().getAsFile().toPath();
        GeneratedDirectories.recreateDirectory(outputDirectory);

        Path binDirectory = outputDirectory.resolve("usr/bin");
        Path applicationsDirectory = outputDirectory.resolve("usr/share/applications");
        Files.createDirectories(binDirectory);
        Files.createDirectories(applicationsDirectory);

        String installDirectory = getInstallDirectory().get();
        String executableName = getExecutableName().get();
        String cliExecutableName = getCliExecutableName().get();
        String desktopFileName = getDesktopFileName().get();
        validateInstallDirectory(installDirectory);
        validateFileName(executableName, "executable name");
        validateFileName(cliExecutableName, "CLI executable name");
        validateFileName(desktopFileName, "desktop file name");

        writeExecutable(
                binDirectory.resolve(executableName),
                """
                #!/bin/sh
                exec %s/bin/%s "$@"
                """.formatted(installDirectory, executableName));
        writeExecutable(
                binDirectory.resolve(cliExecutableName),
                """
                #!/bin/sh
                exec %s/bin/%s "$@"
                """.formatted(installDirectory, cliExecutableName));
        Files.writeString(
                applicationsDirectory.resolve(desktopFileName),
                """
                [Desktop Entry]
                Type=Application
                Name=%s
                Comment=%s
                Exec=%s
                Icon=%s
                Terminal=false
                StartupNotify=true
                Categories=Utility;System;
                Keywords=ruyi;imager;flash;sd-card;
                """.formatted(
                        getDesktopName().get(),
                        getDesktopComment().get(),
                        executableName,
                        getDesktopIcon().get()),
                StandardCharsets.UTF_8);
    }

    /// Validates the application installation directory used by wrapper scripts.
    ///
    /// @param value installation directory.
    private static void validateInstallDirectory(String value) {
        if (value.isBlank() || !value.startsWith("/") || value.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("Invalid installation directory: " + value);
        }
    }

    /// Validates a generated metadata file name.
    ///
    /// @param value file name.
    /// @param label value label for error messages.
    private static void validateFileName(String value, String label) {
        if (value.isBlank()
                || value.contains("/")
                || value.contains("\\")
                || value.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("Invalid " + label + ": " + value);
        }
    }

    /// Writes an executable text file.
    ///
    /// @param file destination file.
    /// @param content file content.
    /// @throws IOException if the file cannot be written.
    private static void writeExecutable(Path file, String content) throws IOException {
        Files.writeString(file, content, StandardCharsets.UTF_8);
        file.toFile().setExecutable(true, false);
    }
}

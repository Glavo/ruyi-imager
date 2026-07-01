// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/// Writes Debian `control` metadata for a staged data tree.
@CacheableTask
@NotNullByDefault
public abstract class WriteDebControl extends DefaultTask {
    /// Creates a Debian control metadata task.
    public WriteDebControl() {
    }

    /// Returns the staged Debian data directory.
    ///
    /// @return staged data directory.
    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getDataDirectory();

    /// Returns the package name.
    ///
    /// @return Debian package name.
    @Input
    public abstract Property<String> getPackageName();

    /// Returns the Debian package version.
    ///
    /// @return Debian version.
    @Input
    public abstract Property<String> getPackageVersion();

    /// Returns the Debian package section.
    ///
    /// @return Debian section.
    @Input
    public abstract Property<String> getSection();

    /// Returns the Debian package priority.
    ///
    /// @return Debian priority.
    @Input
    public abstract Property<String> getPriority();

    /// Returns the Debian architecture.
    ///
    /// @return Debian architecture.
    @Input
    public abstract Property<String> getArchitecture();

    /// Returns the Debian maintainer field.
    ///
    /// @return maintainer field.
    @Input
    public abstract Property<String> getMaintainer();

    /// Returns the package short description.
    ///
    /// @return short description.
    @Input
    public abstract Property<String> getPackageDescription();

    /// Returns the package long description.
    ///
    /// @return long description.
    @Input
    public abstract Property<String> getLongDescription();

    /// Returns the package homepage.
    ///
    /// @return homepage URL.
    @Input
    public abstract Property<String> getHomepage();

    /// Returns the output directory containing the generated `control` file.
    ///
    /// @return output directory.
    @OutputDirectory
    public abstract DirectoryProperty getOutputDirectory();

    /// Writes the `control` file.
    ///
    /// @throws IOException if the data directory cannot be scanned or the control file cannot be written.
    @TaskAction
    public void run() throws IOException {
        Path dataDirectory = getDataDirectory().get().getAsFile().toPath();
        Path outputDirectory = getOutputDirectory().get().getAsFile().toPath();
        Files.createDirectories(outputDirectory);

        long installedSize = installedSizeKilobytes(dataDirectory);
        String control = """
                Package: %s
                Version: %s
                Section: %s
                Priority: %s
                Architecture: %s
                Installed-Size: %d
                Maintainer: %s
                Description: %s
                 %s
                Homepage: %s
                """.formatted(
                getPackageName().get(),
                getPackageVersion().get(),
                getSection().get(),
                getPriority().get(),
                getArchitecture().get(),
                installedSize,
                getMaintainer().get(),
                getPackageDescription().get(),
                getLongDescription().get(),
                getHomepage().get());
        Files.writeString(outputDirectory.resolve("control"), control, StandardCharsets.UTF_8);
    }

    /// Computes the Debian `Installed-Size` field.
    ///
    /// @param directory staged data directory.
    /// @return installed size in KiB.
    /// @throws IOException if the directory cannot be scanned.
    private static long installedSizeKilobytes(Path directory) throws IOException {
        long bytes;
        try (Stream<Path> paths = Files.walk(directory)) {
            bytes = paths
                    .filter(Files::isRegularFile)
                    .mapToLong(WriteDebControl::fileSize)
                    .sum();
        }
        return Math.max((bytes + 1023L) / 1024L, 1L);
    }

    /// Returns one file size, wrapping checked exceptions for stream processing.
    ///
    /// @param path file path.
    /// @return file size.
    private static long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read file size: " + path, e);
        }
    }
}

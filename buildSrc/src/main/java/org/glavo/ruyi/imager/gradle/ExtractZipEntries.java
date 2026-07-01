// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/// Extracts selected ZIP entries into a generated output directory.
@CacheableTask
@NotNullByDefault
public abstract class ExtractZipEntries extends DefaultTask {
    /// Creates a selected ZIP entry extraction task.
    public ExtractZipEntries() {
        getExecutableFileNames().convention(List.of());
    }

    /// Returns the source ZIP archive.
    ///
    /// @return source ZIP archive.
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getArchiveFile();

    /// Returns the selected archive entries mapped to output file names.
    ///
    /// @return archive entry to output file name mapping.
    @Input
    public abstract MapProperty<String, String> getEntries();

    /// Returns output file names that should be marked executable.
    ///
    /// @return executable output file names.
    @Input
    public abstract ListProperty<String> getExecutableFileNames();

    /// Returns the generated output directory.
    ///
    /// @return output directory.
    @OutputDirectory
    public abstract DirectoryProperty getOutputDirectory();

    /// Extracts the configured entries.
    ///
    /// @throws IOException if the archive cannot be read or an output file cannot be written.
    @TaskAction
    public void run() throws IOException {
        Path archiveFile = getArchiveFile().get().getAsFile().toPath();
        Path outputDirectory = getOutputDirectory().get().getAsFile().toPath();
        Map<String, String> entries = getEntries().get();
        validateEntries(entries);

        GeneratedDirectories.recreateDirectory(outputDirectory);

        Set<String> extractedEntries = new HashSet<>();
        try (InputStream fileInput = Files.newInputStream(archiveFile);
             ZipInputStream zipInput = new ZipInputStream(fileInput)) {
            ZipEntry entry;
            while ((entry = zipInput.getNextEntry()) != null) {
                String outputFileName = entries.get(entry.getName());
                if (outputFileName != null && !entry.isDirectory()) {
                    extractEntry(zipInput, outputDirectory.resolve(outputFileName));
                    extractedEntries.add(entry.getName());
                }
                zipInput.closeEntry();
            }
        }

        if (!extractedEntries.containsAll(entries.keySet())) {
            Set<String> missingEntries = new HashSet<>(entries.keySet());
            missingEntries.removeAll(extractedEntries);
            throw new GradleException("Missing ZIP entries in " + archiveFile.getFileName() + ": " + missingEntries);
        }

        for (String fileName : getExecutableFileNames().get()) {
            outputDirectory.resolve(fileName).toFile().setExecutable(true, false);
        }
    }

    /// Validates configured archive and output paths.
    ///
    /// @param entries archive entry to output file name mapping.
    private static void validateEntries(Map<String, String> entries) {
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("At least one ZIP entry must be configured");
        }

        for (Map.Entry<String, String> entry : entries.entrySet()) {
            validateArchiveEntryName(entry.getKey());
            validateOutputFileName(entry.getValue());
        }
    }

    /// Validates an archive entry name.
    ///
    /// @param name archive entry name.
    private static void validateArchiveEntryName(String name) {
        if (name.isBlank() || name.startsWith("/") || name.startsWith("\\") || name.contains("..")) {
            throw new IllegalArgumentException("Invalid ZIP entry name: " + name);
        }
    }

    /// Validates an output file name.
    ///
    /// @param name output file name.
    private static void validateOutputFileName(String name) {
        if (name.isBlank() || name.contains("/") || name.contains("\\") || name.contains("..")) {
            throw new IllegalArgumentException("Invalid output file name: " + name);
        }
    }

    /// Extracts one ZIP entry.
    ///
    /// @param input ZIP input stream positioned at the selected entry content.
    /// @param outputFile output file.
    /// @throws IOException if the output file cannot be written.
    private static void extractEntry(InputStream input, Path outputFile) throws IOException {
        Files.createDirectories(outputFile.getParent());
        byte[] buffer = new byte[1024 * 1024];
        try (OutputStream output = Files.newOutputStream(outputFile)) {
            while (true) {
                int length = input.read(buffer);
                if (length < 0) {
                    break;
                }
                output.write(buffer, 0, length);
            }
        }
    }
}

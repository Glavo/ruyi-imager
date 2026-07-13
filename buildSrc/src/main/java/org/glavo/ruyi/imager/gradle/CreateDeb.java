// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/// Creates a Debian package from prebuilt `control.tar.gz` and `data.tar.gz` archives.
///
/// The task writes the outer Debian `ar` container directly so the build script
/// can keep using Gradle `Tar` tasks for archive content and permissions.
@CacheableTask
@NotNullByDefault
public abstract class CreateDeb extends DefaultTask {
    /// Returns the generated `control.tar.gz` member.
    ///
    /// @return control archive file.
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getControlArchive();

    /// Returns the generated `data.tar.gz` member.
    ///
    /// @return data archive file.
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getDataArchive();

    /// Returns the final `.deb` output file.
    ///
    /// @return Debian package output file.
    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    /// Writes the Debian package.
    ///
    /// @throws IOException if an input archive cannot be read or the output cannot be written.
    @TaskAction
    public void run() throws IOException {
        Path outputFile = getOutputFile().get().getAsFile().toPath();
        Path controlArchive = getControlArchive().get().getAsFile().toPath();
        Path dataArchive = getDataArchive().get().getAsFile().toPath();

        Path parent = outputFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        try (OutputStream output = Files.newOutputStream(outputFile)) {
            output.write("!<arch>\n".getBytes(StandardCharsets.US_ASCII));
            writeArEntry(output, "debian-binary", "2.0\n".getBytes(StandardCharsets.US_ASCII));
            writeArEntry(output, "control.tar.gz", Files.readAllBytes(controlArchive));
            writeArEntry(output, "data.tar.gz", Files.readAllBytes(dataArchive));
        }
    }

    /// Writes one Debian `ar` member.
    ///
    /// @param output destination stream.
    /// @param name member name.
    /// @param content member bytes.
    /// @throws IOException if the member cannot be written.
    private static void writeArEntry(OutputStream output, String name, byte[] content) throws IOException {
        String entryName = name + "/";
        if (entryName.length() > 16) {
            throw new IOException("Debian ar member name is too long: " + name);
        }

        String header = String.format(
                Locale.ROOT,
                "%-16s%-12s%-6s%-6s%-8s%-10s`\n",
                entryName,
                "0",
                "0",
                "0",
                "100644",
                Integer.toString(content.length));
        byte[] headerBytes = header.getBytes(StandardCharsets.US_ASCII);
        if (headerBytes.length != 60) {
            throw new IOException("Invalid Debian ar header for " + name);
        }

        output.write(headerBytes);
        output.write(content);
        if ((content.length & 1) != 0) {
            output.write('\n');
        }
    }
}

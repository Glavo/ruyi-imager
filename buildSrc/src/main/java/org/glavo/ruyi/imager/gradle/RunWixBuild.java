// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.process.ExecOperations;
import org.gradle.work.DisableCachingByDefault;
import org.jetbrains.annotations.NotNullByDefault;

import javax.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/// Builds a Windows Installer package with WiX.
@DisableCachingByDefault(because = "WiX output can depend on the external tool installation.")
@NotNullByDefault
public abstract class RunWixBuild extends DefaultTask {
    /// Executes the external `wix` process.
    private final ExecOperations execOperations;

    /// Creates a WiX build task.
    ///
    /// @param execOperations Gradle process execution service.
    @Inject
    public RunWixBuild(ExecOperations execOperations) {
        this.execOperations = execOperations;
    }

    /// Returns the WiX executable name or path.
    ///
    /// @return WiX executable.
    @Input
    public abstract Property<String> getWixExecutable();

    /// Returns the WiX target architecture.
    ///
    /// @return WiX architecture.
    @Input
    public abstract Property<String> getArchitecture();

    /// Returns the staged application image directory.
    ///
    /// @return staged application image directory.
    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getAppDirectory();

    /// Returns the generated WiX source file.
    ///
    /// @return WiX source file.
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getSourceFile();

    /// Returns the MSI output file.
    ///
    /// @return MSI output file.
    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    /// Runs `wix build`.
    ///
    /// @throws IOException if the output directory cannot be created.
    @TaskAction
    public void run() throws IOException {
        Path sourceFile = getSourceFile().get().getAsFile().toPath();
        Path outputFile = getOutputFile().get().getAsFile().toPath();
        Path parent = outputFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        execOperations.exec(spec -> {
            spec.setExecutable(getWixExecutable().get());
            spec.args(
                    "build",
                    sourceFile.toString(),
                    "-arch",
                    getArchitecture().get(),
                    "-pdbtype",
                    "none",
                    "-o",
                    outputFile.toString());
        });

        if (!Files.isRegularFile(outputFile)) {
            throw new GradleException("WiX did not create MSI output: " + outputFile);
        }
    }
}

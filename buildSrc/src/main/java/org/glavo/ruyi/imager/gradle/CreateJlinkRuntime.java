// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.process.ExecOperations;
import org.jetbrains.annotations.NotNullByDefault;

import javax.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/// Builds a custom Java runtime image with `jlink`.
@CacheableTask
@NotNullByDefault
public abstract class CreateJlinkRuntime extends DefaultTask {
    /// Executes the external `jlink` process.
    private final ExecOperations execOperations;

    /// Creates a jlink runtime task.
    ///
    /// @param execOperations Gradle process execution service.
    @Inject
    public CreateJlinkRuntime(ExecOperations execOperations) {
        this.execOperations = execOperations;
    }

    /// Returns the host `jlink` executable.
    ///
    /// @return host `jlink` executable.
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getExecutable();

    /// Returns the directory containing target JDK jmods.
    ///
    /// @return jmods directory.
    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getJmodsDirectory();

    /// Returns the comma-separated module list passed to `jlink --add-modules`.
    ///
    /// @return module list.
    @Input
    public abstract Property<String> getModules();

    /// Returns JavaFX modules that must have matching jmod files.
    ///
    /// @return required JavaFX module names.
    @Input
    public abstract ListProperty<String> getRequiredJavafxModules();

    /// Returns the generated runtime image output directory.
    ///
    /// @return runtime image output directory.
    @OutputDirectory
    public abstract DirectoryProperty getOutputDirectory();

    /// Runs `jlink` after validating required inputs.
    ///
    /// @throws IOException if the output directory cannot be cleaned.
    @TaskAction
    public void run() throws IOException {
        Path executable = getExecutable().get().getAsFile().toPath();
        Path jmodsDirectory = getJmodsDirectory().get().getAsFile().toPath();
        Path outputDirectory = getOutputDirectory().get().getAsFile().toPath();

        requireFile(executable, "Missing host jlink executable: ");
        requireFile(jmodsDirectory.resolve("java.base.jmod"), "Missing java.base.jmod in downloaded JDK: ");
        for (String moduleName : getRequiredJavafxModules().get()) {
            requireFile(
                    jmodsDirectory.resolve(moduleName + ".jmod"),
                    "Missing " + moduleName + ".jmod in downloaded Liberica Full JDK: ");
        }

        GeneratedDirectories.deleteExisting(outputDirectory);
        execOperations.exec(spec -> {
            spec.setExecutable(executable.toFile());
            spec.args(jlinkArguments(jmodsDirectory, outputDirectory));
        });
    }

    /// Creates the `jlink` command arguments.
    ///
    /// @param jmodsDirectory directory containing target JDK jmods.
    /// @param outputDirectory generated runtime image output directory.
    /// @return `jlink` arguments.
    private List<String> jlinkArguments(Path jmodsDirectory, Path outputDirectory) {
        List<String> arguments = new ArrayList<>();
        arguments.add("--strip-debug");
        arguments.add("--no-header-files");
        arguments.add("--no-man-pages");
        arguments.add("--module-path");
        arguments.add(jmodsDirectory.toString());
        arguments.add("--add-modules");
        arguments.add(getModules().get());
        arguments.add("--output");
        arguments.add(outputDirectory.toString());
        return arguments;
    }

    /// Requires an existing regular file.
    ///
    /// @param path file path.
    /// @param message error message prefix.
    private static void requireFile(Path path, String message) {
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException(message + path);
        }
    }
}

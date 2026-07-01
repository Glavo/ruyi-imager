// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.ListProperty;
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
import java.util.List;

/// Writes launch scripts for the jlink application image.
@CacheableTask
@NotNullByDefault
public abstract class WriteJlinkLaunchers extends DefaultTask {
    /// Creates a jlink launcher generation task.
    public WriteJlinkLaunchers() {
        getExecutableName().convention("ruyi-imager");
        getCliExecutableName().convention("ruyi-imager-cli");
        getJvmArgumentsFileName().convention("ruyi-imager.jvmargs");
    }

    /// Returns the Java main class used by generated launchers.
    ///
    /// @return Java main class.
    @Input
    public abstract Property<String> getMainClass();

    /// Returns JVM arguments written to the launcher argument file.
    ///
    /// @return JVM arguments.
    @Input
    public abstract ListProperty<String> getJvmArguments();

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

    /// Returns the JVM argument file name.
    ///
    /// @return JVM argument file name.
    @Input
    public abstract Property<String> getJvmArgumentsFileName();

    /// Returns the output directory containing launch scripts.
    ///
    /// @return launcher output directory.
    @OutputDirectory
    public abstract DirectoryProperty getOutputDirectory();

    /// Writes launch scripts and the JVM argument file.
    ///
    /// @throws IOException if a launcher file cannot be written.
    @TaskAction
    public void run() throws IOException {
        Path outputDirectory = getOutputDirectory().get().getAsFile().toPath();
        GeneratedDirectories.recreateDirectory(outputDirectory);

        String mainClass = getMainClass().get();
        String executableName = getExecutableName().get();
        String cliExecutableName = getCliExecutableName().get();
        String jvmArgumentsFileName = getJvmArgumentsFileName().get();
        List<String> jvmArguments = getJvmArguments().get();
        validatePlainToken(mainClass, "main class");
        validateFileName(executableName, "executable name");
        validateFileName(cliExecutableName, "CLI executable name");
        validateFileName(jvmArgumentsFileName, "JVM argument file name");
        validateJvmArguments(jvmArguments);

        String unixLauncher = """
                #!/bin/sh
                APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
                exec "$APP_HOME/runtime/bin/java" @"$APP_HOME/bin/%s" -cp "$APP_HOME/lib/*" %s "$@"
                """.formatted(jvmArgumentsFileName, mainClass);
        writeExecutable(outputDirectory.resolve(executableName), unixLauncher);
        writeExecutable(outputDirectory.resolve(cliExecutableName), unixLauncher);

        Files.writeString(
                outputDirectory.resolve(cliExecutableName + ".bat"),
                """
                @echo off
                set "APP_HOME=%%~dp0.."
                "%%APP_HOME%%\\runtime\\bin\\java.exe" @"%%APP_HOME%%\\bin\\%s" -cp "%%APP_HOME%%\\lib\\*" %s %%*
                """.formatted(jvmArgumentsFileName, mainClass),
                StandardCharsets.UTF_8);

        Files.writeString(
                outputDirectory.resolve(jvmArgumentsFileName),
                String.join(System.lineSeparator(), jvmArguments) + System.lineSeparator(),
                StandardCharsets.UTF_8);
    }

    /// Validates a token used directly in launch scripts.
    ///
    /// @param value token value.
    /// @param label value label for error messages.
    private static void validatePlainToken(String value, String label) {
        if (value.isBlank() || value.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("Invalid " + label + ": " + value);
        }
    }

    /// Validates a generated launcher file name.
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

    /// Validates JVM arguments before writing the Java launcher argument file.
    ///
    /// @param arguments JVM arguments.
    private static void validateJvmArguments(List<String> arguments) {
        for (String argument : arguments) {
            if (argument.isBlank() || argument.chars().anyMatch(Character::isWhitespace)) {
                throw new IllegalArgumentException("JVM argument cannot be blank or contain whitespace: " + argument);
            }
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

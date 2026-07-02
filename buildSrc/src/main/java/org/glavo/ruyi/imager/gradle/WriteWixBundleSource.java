// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/// Writes WiX source for a Burn setup executable that embeds the MSI package.
@CacheableTask
@NotNullByDefault
public abstract class WriteWixBundleSource extends DefaultTask {
    /// Identifies a per-user Windows Installer package.
    private static final String INSTALL_SCOPE_PER_USER = "perUser";

    /// Identifies a per-machine Windows Installer package.
    private static final String INSTALL_SCOPE_PER_MACHINE = "perMachine";

    /// Creates a WiX bundle source generation task.
    public WriteWixBundleSource() {
        getInstallScope().convention(INSTALL_SCOPE_PER_USER);
        getInstallDirectoryName().convention("Ruyi Imager");
    }

    /// Returns the MSI package embedded into the setup executable.
    ///
    /// @return embedded MSI package.
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getMsiPackageFile();

    /// Returns the icon file used by the setup executable and Add/Remove Programs.
    ///
    /// @return setup icon file.
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getIconFile();

    /// Returns the logo file shown by the bootstrapper UI.
    ///
    /// @return bootstrapper UI logo file.
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getLogoFile();

    /// Returns the bootstrapper UI localization directory.
    ///
    /// @return bootstrapper UI localization directory.
    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getLocalizationDirectory();

    /// Returns the bundle display name.
    ///
    /// @return bundle display name.
    @Input
    public abstract Property<String> getProductName();

    /// Returns the bundle manufacturer.
    ///
    /// @return bundle manufacturer.
    @Input
    public abstract Property<String> getManufacturer();

    /// Returns the bundle version.
    ///
    /// @return bundle version.
    @Input
    public abstract Property<String> getProductVersion();

    /// Returns the bundle upgrade code.
    ///
    /// @return bundle upgrade code.
    @Input
    public abstract Property<String> getUpgradeCode();

    /// Returns the Windows Installer installation scope.
    ///
    /// @return installation scope.
    @Input
    public abstract Property<String> getInstallScope();

    /// Returns the installation directory name.
    ///
    /// @return installation directory name.
    @Input
    public abstract Property<String> getInstallDirectoryName();

    /// Returns the generated WiX bundle source output file.
    ///
    /// @return generated WiX bundle source file.
    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    /// Writes the WiX bundle source file.
    ///
    /// @throws IOException if the output cannot be written.
    @TaskAction
    public void run() throws IOException {
        Path outputFile = getOutputFile().get().getAsFile().toPath();
        Path localizationDirectory = getLocalizationDirectory().get().getAsFile().toPath();
        Path parent = outputFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        StringBuilder output = new StringBuilder();
        output.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        output.append("<Wix xmlns=\"http://wixtoolset.org/schemas/v4/wxs\"");
        output.append(" xmlns:bal=\"http://wixtoolset.org/schemas/v4/wxs/bal\">\n");
        output.append("  <Bundle Name=\"").append(xml(getProductName().get())).append('"');
        output.append(" Manufacturer=\"").append(xml(getManufacturer().get())).append('"');
        output.append(" Version=\"").append(xml(getProductVersion().get())).append('"');
        output.append(" UpgradeCode=\"").append(xml(normalizedGuid(getUpgradeCode().get()))).append('"');
        output.append(" IconSourceFile=\"");
        output.append(xml(getIconFile().get().getAsFile().getAbsolutePath()));
        output.append("\">\n");
        output.append("    <Variable Name=\"InstallFolder\" Type=\"formatted\" Value=\"");
        output.append(xml(defaultInstallFolder()));
        output.append("\" />\n");
        output.append("    <BootstrapperApplication>\n");
        output.append("      <bal:WixStandardBootstrapperApplication Theme=\"hyperlinkLicense\"");
        output.append(" LicenseUrl=\"\"");
        output.append(" ShowVersion=\"yes\"");
        output.append(" LogoFile=\"");
        output.append(xml(getLogoFile().get().getAsFile().getAbsolutePath()));
        output.append('"');
        output.append(" LocalizationFile=\"");
        output.append(xml(defaultLocalizationFile(localizationDirectory).toString()));
        output.append('"');
        output.append(" />\n");
        appendLocalizedPayloads(output, localizationDirectory);
        output.append("    </BootstrapperApplication>\n");
        output.append("    <Chain>\n");
        output.append("      <MsiPackage SourceFile=\"");
        output.append(xml(getMsiPackageFile().get().getAsFile().getAbsolutePath()));
        output.append("\" Compressed=\"yes\" Visible=\"no\"");
        output.append(">\n");
        output.append("        <MsiProperty Name=\"INSTALLFOLDER\" Value=\"[InstallFolder]\" />\n");
        output.append("      </MsiPackage>\n");
        output.append("    </Chain>\n");
        output.append("  </Bundle>\n");
        output.append("</Wix>\n");
        Files.writeString(outputFile, output.toString(), StandardCharsets.UTF_8);
    }

    /// Returns the default bootstrapper UI localization file.
    ///
    /// @param localizationDirectory bootstrapper UI localization directory.
    /// @return default localization file.
    private static Path defaultLocalizationFile(Path localizationDirectory) {
        Path file = localizationDirectory.resolve("thm.wxl");
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("Missing default WiX bootstrapper localization file: " + file);
        }
        return file;
    }

    /// Appends culture-specific bootstrapper UI localization payloads.
    ///
    /// @param output WiX source output.
    /// @param localizationDirectory bootstrapper UI localization directory.
    /// @throws IOException if the localization directory cannot be scanned.
    private static void appendLocalizedPayloads(StringBuilder output, Path localizationDirectory) throws IOException {
        List<Path> files;
        try (var stream = Files.walk(localizationDirectory)) {
            files = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> !localizationDirectory.relativize(path).toString().equals("thm.wxl"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }

        for (Path file : files) {
            String relativePath = localizationDirectory.relativize(file).toString().replace('/', '\\');
            output.append("      <Payload Name=\"");
            output.append(xml(relativePath));
            output.append("\" Compressed=\"yes\" SourceFile=\"");
            output.append(xml(file.toString()));
            output.append("\" />\n");
        }
    }

    /// Returns the default install folder for the configured installation scope.
    ///
    /// @return default install folder.
    private String defaultInstallFolder() {
        String directoryName = getInstallDirectoryName().get();
        return switch (normalizedInstallScope(getInstallScope().get())) {
            case INSTALL_SCOPE_PER_USER -> "[LocalAppDataFolder]Programs\\" + directoryName;
            case INSTALL_SCOPE_PER_MACHINE -> "[ProgramFiles64Folder]" + directoryName;
            default -> throw new AssertionError("Unexpected install scope");
        };
    }

    /// Normalizes and validates an installation scope value.
    ///
    /// @param value raw installation scope.
    /// @return normalized installation scope.
    private static String normalizedInstallScope(String value) {
        String scope = value.trim();
        return switch (scope) {
            case INSTALL_SCOPE_PER_USER, INSTALL_SCOPE_PER_MACHINE -> scope;
            default -> throw new IllegalArgumentException("Unsupported MSI install scope: " + value);
        };
    }

    /// Normalizes a GUID string for WiX source.
    ///
    /// @param value GUID value.
    /// @return normalized GUID with braces.
    private static String normalizedGuid(String value) {
        String text = value.trim();
        if (text.startsWith("{") && text.endsWith("}")) {
            text = text.substring(1, text.length() - 1);
        }
        return "{" + UUID.fromString(text).toString().toUpperCase() + "}";
    }

    /// Escapes XML attribute content.
    ///
    /// @param value raw value.
    /// @return escaped value.
    private static String xml(String value) {
        return value
                .replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}

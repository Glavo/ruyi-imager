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
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/// Writes WiX source for a Burn setup executable that embeds the MSI package.
@CacheableTask
@NotNullByDefault
public abstract class WriteWixBundleSource extends DefaultTask {
    /// Identifies a per-user Windows Installer package.
    private static final String INSTALL_SCOPE_PER_USER = "perUser";

    /// Identifies a per-machine Windows Installer package.
    private static final String INSTALL_SCOPE_PER_MACHINE = "perMachine";

    /// Contains characters rejected in Windows directory segment names.
    private static final String WINDOWS_DIRECTORY_NAME_FORBIDDEN_CHARACTERS = "<>:\"/\\|?*";

    /// Creates a WiX bundle source generation task.
    public WriteWixBundleSource() {
        getInstallScope().convention(INSTALL_SCOPE_PER_USER);
        getInstallDirectoryName().convention("Ruyi Imager");
    }

    /// Returns the MSI package embedded into the setup executable.
    ///
    /// @return embedded MSI package.
    @InputFile
    @PathSensitive(PathSensitivity.ABSOLUTE)
    public abstract RegularFileProperty getMsiPackageFile();

    /// Returns the icon file used by the setup executable and Add/Remove Programs.
    ///
    /// @return setup icon file.
    @InputFile
    @PathSensitive(PathSensitivity.ABSOLUTE)
    public abstract RegularFileProperty getIconFile();

    /// Returns the logo file shown by the bootstrapper UI.
    ///
    /// @return bootstrapper UI logo file.
    @InputFile
    @PathSensitive(PathSensitivity.ABSOLUTE)
    public abstract RegularFileProperty getLogoFile();

    /// Returns the bootstrapper UI theme file.
    ///
    /// @return bootstrapper UI theme file.
    @InputFile
    @PathSensitive(PathSensitivity.ABSOLUTE)
    public abstract RegularFileProperty getThemeFile();

    /// Returns the bootstrapper UI localization directory.
    ///
    /// @return bootstrapper UI localization directory.
    @InputDirectory
    @PathSensitive(PathSensitivity.ABSOLUTE)
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

    /// Returns the human-readable bundle display version.
    ///
    /// @return bundle display version.
    @Input
    public abstract Property<String> getDisplayVersion();

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
        output.append("    <Variable Name=\"RuyiImagerDisplayVersion\" Type=\"string\" Value=\"");
        output.append(xml(getDisplayVersion().get()));
        output.append("\" />\n");
        output.append("    <BootstrapperApplication>\n");
        output.append("      <bal:WixStandardBootstrapperApplication Theme=\"hyperlinkLicense\"");
        output.append(" LicenseUrl=\"\"");
        output.append(" ThemeFile=\"");
        output.append(xml(getThemeFile().get().getAsFile().getAbsolutePath()));
        output.append('"');
        output.append(" ShowVersion=\"yes\"");
        output.append(" LogoFile=\"");
        output.append(xml(getLogoFile().get().getAsFile().getAbsolutePath()));
        output.append('"');
        output.append(" LocalizationFile=\"");
        output.append(xml(defaultLocalizationFile(localizationDirectory).toString()));
        output.append('"');
        output.append(" />\n");
        appendLocalizedPayloads(output, localizationDirectory);
        appendIconPayload(output);
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
        Map<String, Path> payloads = new TreeMap<>();
        try (var stream = Files.walk(localizationDirectory)) {
            stream
                    .filter(Files::isRegularFile)
                    .filter(path -> isCultureLocalizationFile(localizationDirectory, path))
                    .forEach(path -> addLocalizationPayload(payloads, localizationDirectory, path));
        }

        for (Map.Entry<String, Path> payload : payloads.entrySet()) {
            output.append("      <Payload Name=\"");
            output.append(xml(payload.getKey()));
            output.append("\" Compressed=\"yes\" SourceFile=\"");
            output.append(xml(payload.getValue().toString()));
            output.append("\" />\n");
        }
    }

    /// Appends the bootstrapper window icon payload.
    ///
    /// @param output WiX source output.
    private void appendIconPayload(StringBuilder output) {
        output.append("      <Payload Name=\"icon.ico\" Compressed=\"yes\" SourceFile=\"");
        output.append(xml(getIconFile().get().getAsFile().getAbsolutePath()));
        output.append("\" />\n");
    }

    /// Returns whether a file is a culture-specific bootstrapper UI localization file.
    ///
    /// @param localizationDirectory bootstrapper UI localization directory.
    /// @param file candidate localization file.
    /// @return true if the file is a culture-specific `thm.wxl` file.
    private static boolean isCultureLocalizationFile(Path localizationDirectory, Path file) {
        Path relativePath = localizationDirectory.relativize(file);
        return relativePath.getNameCount() == 2 && relativePath.getFileName().toString().equals("thm.wxl");
    }

    /// Adds a localization payload and any required lookup alias.
    ///
    /// @param payloads localization payloads by bundle path.
    /// @param localizationDirectory bootstrapper UI localization directory.
    /// @param file localization source file.
    private static void addLocalizationPayload(Map<String, Path> payloads, Path localizationDirectory, Path file) {
        payloads.put(localizationPayloadName(localizationDirectory, file), file);
    }

    /// Returns the bundle payload name used by the standard bootstrapper UI localization probe.
    ///
    /// @param localizationDirectory bootstrapper UI localization directory.
    /// @param file localization source file.
    /// @return payload name using a decimal Windows language identifier directory.
    private static String localizationPayloadName(Path localizationDirectory, Path file) {
        Path relativePath = localizationDirectory.relativize(file);
        String cultureName = relativePath.getName(0).toString();
        return windowsLanguageId(cultureName) + "\\thm.wxl";
    }

    /// Returns the decimal Windows language identifier for a supported localization culture.
    ///
    /// @param cultureName localization culture name.
    /// @return decimal Windows language identifier.
    private static String windowsLanguageId(String cultureName) {
        return switch (cultureName) {
            case "zh-CN" -> "2052";
            default -> throw new IllegalArgumentException("Unsupported WiX setup localization culture: " + cultureName);
        };
    }

    /// Returns the default install folder for the configured installation scope.
    ///
    /// @return default install folder.
    private String defaultInstallFolder() {
        String directoryName = normalizedInstallDirectoryName(getInstallDirectoryName().get());
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

    /// Normalizes and validates the install directory name.
    ///
    /// @param value raw install directory name.
    /// @return normalized install directory name.
    private static String normalizedInstallDirectoryName(String value) {
        String name = value.trim();
        if (!name.equals(value) || name.isEmpty()) {
            throw new IllegalArgumentException("MSI install directory name must be a non-empty Windows directory name");
        }
        if (name.endsWith(".") || name.endsWith(" ")) {
            throw new IllegalArgumentException("MSI install directory name must not end with a space or period: " + value);
        }
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (ch < 0x20 || WINDOWS_DIRECTORY_NAME_FORBIDDEN_CHARACTERS.indexOf(ch) >= 0) {
                throw new IllegalArgumentException("MSI install directory name contains an invalid character: " + value);
            }
        }

        String baseName = name;
        int extensionIndex = baseName.indexOf('.');
        if (extensionIndex >= 0) {
            baseName = baseName.substring(0, extensionIndex);
        }
        String upperBaseName = baseName.toUpperCase(Locale.ROOT);
        if (upperBaseName.equals("CON")
                || upperBaseName.equals("PRN")
                || upperBaseName.equals("AUX")
                || upperBaseName.equals("NUL")
                || upperBaseName.matches("COM[1-9]")
                || upperBaseName.matches("LPT[1-9]")) {
            throw new IllegalArgumentException("MSI install directory name is reserved on Windows: " + value);
        }
        return name;
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

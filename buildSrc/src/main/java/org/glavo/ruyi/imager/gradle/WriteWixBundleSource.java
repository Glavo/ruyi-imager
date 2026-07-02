// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/// Writes WiX source for a Burn setup executable that embeds the MSI package.
@CacheableTask
@NotNullByDefault
public abstract class WriteWixBundleSource extends DefaultTask {
    /// Creates a WiX bundle source generation task.
    public WriteWixBundleSource() {
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

    /// Returns the RTF license file shown by the bootstrapper UI.
    ///
    /// @return RTF license file.
    @Optional
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getLicenseFile();

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
        output.append("    <BootstrapperApplication>\n");
        output.append("      <bal:WixStandardBootstrapperApplication Theme=\"");
        output.append(getLicenseFile().isPresent() ? "rtfLicense" : "hyperlinkLicense");
        output.append('"');
        output.append(" ShowVersion=\"yes\"");
        if (getLicenseFile().isPresent()) {
            output.append(" LicenseFile=\"");
            output.append(xml(getLicenseFile().get().getAsFile().getAbsolutePath()));
            output.append('"');
        } else {
            output.append(" LicenseUrl=\"\"");
        }
        output.append(" />\n");
        output.append("    </BootstrapperApplication>\n");
        output.append("    <Chain>\n");
        output.append("      <MsiPackage SourceFile=\"");
        output.append(xml(getMsiPackageFile().get().getAsFile().getAbsolutePath()));
        output.append("\" Compressed=\"yes\" Visible=\"no\"");
        output.append(" bal:DisplayInternalUICondition=\"1\" />\n");
        output.append("    </Chain>\n");
        output.append("  </Bundle>\n");
        output.append("</Wix>\n");
        Files.writeString(outputFile, output.toString(), StandardCharsets.UTF_8);
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

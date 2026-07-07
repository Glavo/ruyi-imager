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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/// Writes WiX source for the staged jlink application image.
@CacheableTask
@NotNullByDefault
public abstract class WriteWixSource extends DefaultTask {
    /// Identifies a per-user Windows Installer package.
    private static final String INSTALL_SCOPE_PER_USER = "perUser";

    /// Identifies a per-machine Windows Installer package.
    private static final String INSTALL_SCOPE_PER_MACHINE = "perMachine";

    /// Creates a WiX source generation task.
    public WriteWixSource() {
        getInstallScope().convention(INSTALL_SCOPE_PER_USER);
        getInstallDirectoryName().convention("Ruyi Imager");
        getGuiExecutablePath().convention("bin/ruyi-imager.exe");
    }

    /// Returns the staged application image directory.
    ///
    /// @return staged application image directory.
    @InputDirectory
    @PathSensitive(PathSensitivity.ABSOLUTE)
    public abstract DirectoryProperty getAppDirectory();

    /// Returns the icon file used by Add/Remove Programs and shortcuts.
    ///
    /// @return product icon file.
    @InputFile
    @PathSensitive(PathSensitivity.ABSOLUTE)
    public abstract RegularFileProperty getIconFile();

    /// Returns the product display name.
    ///
    /// @return product display name.
    @Input
    public abstract Property<String> getProductName();

    /// Returns the product manufacturer.
    ///
    /// @return product manufacturer.
    @Input
    public abstract Property<String> getManufacturer();

    /// Returns the Windows Installer product version.
    ///
    /// @return Windows Installer product version.
    @Input
    public abstract Property<String> getProductVersion();

    /// Returns the Windows Installer upgrade code.
    ///
    /// @return upgrade code.
    @Input
    public abstract Property<String> getUpgradeCode();

    /// Returns the Windows Installer installation scope.
    ///
    /// @return installation scope.
    @Input
    public abstract Property<String> getInstallScope();

    /// Returns the WiX target architecture.
    ///
    /// @return WiX architecture.
    @Input
    public abstract Property<String> getArchitecture();

    /// Returns the installation directory name.
    ///
    /// @return installation directory name.
    @Input
    public abstract Property<String> getInstallDirectoryName();

    /// Returns the GUI executable path relative to the app image directory.
    ///
    /// @return GUI executable relative path.
    @Input
    public abstract Property<String> getGuiExecutablePath();

    /// Returns the generated WiX source output file.
    ///
    /// @return generated WiX source file.
    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    /// Writes the WiX source file.
    ///
    /// @throws IOException if the application directory cannot be scanned or the output cannot be written.
    @TaskAction
    public void run() throws IOException {
        Path appDirectory = getAppDirectory().get().getAsFile().toPath();
        Path outputFile = getOutputFile().get().getAsFile().toPath();
        List<WixFile> files = collectFiles(appDirectory);
        DirectoryNode rootDirectory = buildDirectoryTree(files);

        Path parent = outputFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        StringBuilder output = new StringBuilder();
        appendHeader(output, rootDirectory);
        appendComponents(output, files);
        appendFeature(output, files);
        output.append("  </Package>\n");
        output.append("</Wix>\n");
        Files.writeString(outputFile, output.toString(), StandardCharsets.UTF_8);
    }

    /// Appends package metadata and directory declarations.
    ///
    /// @param output WiX source output.
    /// @param rootDirectory installation root directory.
    private void appendHeader(StringBuilder output, DirectoryNode rootDirectory) {
        String installScope = normalizedInstallScope(getInstallScope().get());
        output.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        output.append("<Wix xmlns=\"http://wixtoolset.org/schemas/v4/wxs\"");
        output.append(" xmlns:ui=\"http://wixtoolset.org/schemas/v4/wxs/ui\">\n");
        output.append("  <Package Name=\"").append(xml(getProductName().get())).append('"');
        output.append(" Manufacturer=\"").append(xml(getManufacturer().get())).append('"');
        output.append(" Version=\"").append(xml(getProductVersion().get())).append('"');
        output.append(" UpgradeCode=\"").append(xml(normalizedGuid(getUpgradeCode().get()))).append('"');
        output.append(" Scope=\"").append(xml(installScope)).append("\">\n");
        output.append("    <MajorUpgrade DowngradeErrorMessage=\"A newer version of ");
        output.append(xml(getProductName().get()));
        output.append(" is already installed.\" />\n");
        output.append("    <MediaTemplate EmbedCab=\"yes\" />\n");
        output.append("    <Icon Id=\"ApplicationIcon.ico\" SourceFile=\"");
        output.append(xml(getIconFile().get().getAsFile().getAbsolutePath()));
        output.append("\" />\n");
        output.append("    <Property Id=\"ARPPRODUCTICON\" Value=\"ApplicationIcon.ico\" />\n");
        appendInstallDirUi(output, getArchitecture().get());
        appendInstallDirectories(output, rootDirectory, installScope);
        output.append("    <StandardDirectory Id=\"ProgramMenuFolder\">\n");
        output.append("      <Directory Id=\"ApplicationProgramsFolder\" Name=\"");
        output.append(xml(getProductName().get()));
        output.append("\" />\n");
        output.append("    </StandardDirectory>\n");
    }

    /// Appends a directory-selection UI sequence without a license agreement dialog.
    ///
    /// @param output WiX source output.
    /// @param architecture WiX target architecture.
    private static void appendInstallDirUi(StringBuilder output, String architecture) {
        String validatePathActionId = "WixUIValidatePath_" + wixUiArchitectureSuffix(architecture);

        output.append("    <Property Id=\"WIXUI_INSTALLDIR\" Value=\"INSTALLFOLDER\" />\n");
        output.append("    <UI>\n");
        output.append("      <TextStyle Id=\"WixUI_Font_Normal\" FaceName=\"Tahoma\" Size=\"8\" />\n");
        output.append("      <TextStyle Id=\"WixUI_Font_Bigger\" FaceName=\"Tahoma\" Size=\"12\" />\n");
        output.append("      <TextStyle Id=\"WixUI_Font_Title\" FaceName=\"Tahoma\" Size=\"9\" Bold=\"yes\" />\n");
        output.append("      <Property Id=\"DefaultUIFont\" Value=\"WixUI_Font_Normal\" />\n");
        output.append("      <DialogRef Id=\"BrowseDlg\" />\n");
        output.append("      <DialogRef Id=\"DiskCostDlg\" />\n");
        output.append("      <DialogRef Id=\"ErrorDlg\" />\n");
        output.append("      <DialogRef Id=\"FatalError\" />\n");
        output.append("      <DialogRef Id=\"FilesInUse\" />\n");
        output.append("      <DialogRef Id=\"MsiRMFilesInUse\" />\n");
        output.append("      <DialogRef Id=\"PrepareDlg\" />\n");
        output.append("      <DialogRef Id=\"ProgressDlg\" />\n");
        output.append("      <DialogRef Id=\"ResumeDlg\" />\n");
        output.append("      <DialogRef Id=\"UserExit\" />\n");
        output.append("      <Publish Dialog=\"ExitDialog\" Control=\"Finish\" Event=\"EndDialog\"");
        output.append(" Value=\"Return\" Order=\"999\" />\n");
        output.append("      <Publish Dialog=\"WelcomeDlg\" Control=\"Next\" Event=\"NewDialog\"");
        output.append(" Value=\"InstallDirDlg\" Condition=\"NOT Installed\" />\n");
        output.append("      <Publish Dialog=\"WelcomeDlg\" Control=\"Next\" Event=\"NewDialog\"");
        output.append(" Value=\"VerifyReadyDlg\" Condition=\"Installed AND PATCH\" />\n");
        output.append("      <Publish Dialog=\"InstallDirDlg\" Control=\"Back\" Event=\"NewDialog\"");
        output.append(" Value=\"WelcomeDlg\" />\n");
        output.append("      <Publish Dialog=\"InstallDirDlg\" Control=\"Next\" Event=\"SetTargetPath\"");
        output.append(" Value=\"[WIXUI_INSTALLDIR]\" Order=\"1\" />\n");
        output.append("      <Publish Dialog=\"InstallDirDlg\" Control=\"Next\" Event=\"DoAction\"");
        output.append(" Value=\"").append(validatePathActionId).append("\" Order=\"2\"");
        output.append(" Condition=\"NOT WIXUI_DONTVALIDATEPATH\" />\n");
        output.append("      <Publish Dialog=\"InstallDirDlg\" Control=\"Next\" Event=\"SpawnDialog\"");
        output.append(" Value=\"InvalidDirDlg\" Order=\"3\"");
        output.append(" Condition=\"NOT WIXUI_DONTVALIDATEPATH AND WIXUI_INSTALLDIR_VALID&lt;&gt;&quot;1&quot;\" />\n");
        output.append("      <Publish Dialog=\"InstallDirDlg\" Control=\"Next\" Event=\"NewDialog\"");
        output.append(" Value=\"VerifyReadyDlg\" Order=\"4\"");
        output.append(" Condition=\"WIXUI_DONTVALIDATEPATH OR WIXUI_INSTALLDIR_VALID=&quot;1&quot;\" />\n");
        output.append("      <Publish Dialog=\"InstallDirDlg\" Control=\"ChangeFolder\" Property=\"_BrowseProperty\"");
        output.append(" Value=\"[WIXUI_INSTALLDIR]\" Order=\"1\" />\n");
        output.append("      <Publish Dialog=\"InstallDirDlg\" Control=\"ChangeFolder\" Event=\"SpawnDialog\"");
        output.append(" Value=\"BrowseDlg\" Order=\"2\" />\n");
        output.append("      <Publish Dialog=\"BrowseDlg\" Control=\"OK\" Event=\"DoAction\"");
        output.append(" Value=\"").append(validatePathActionId).append("\" Order=\"1\"");
        output.append(" Condition=\"NOT WIXUI_DONTVALIDATEPATH\" />\n");
        output.append("      <Publish Dialog=\"BrowseDlg\" Control=\"OK\" Event=\"SpawnDialog\"");
        output.append(" Value=\"InvalidDirDlg\" Order=\"2\"");
        output.append(" Condition=\"NOT WIXUI_DONTVALIDATEPATH AND WIXUI_INSTALLDIR_VALID&lt;&gt;&quot;1&quot;\" />\n");
        output.append("      <Publish Dialog=\"BrowseDlg\" Control=\"OK\" Event=\"SetTargetPath\"");
        output.append(" Value=\"[_BrowseProperty]\" Order=\"3\"");
        output.append(" Condition=\"WIXUI_DONTVALIDATEPATH OR WIXUI_INSTALLDIR_VALID=&quot;1&quot;\" />\n");
        output.append("      <Publish Dialog=\"BrowseDlg\" Control=\"OK\" Event=\"EndDialog\"");
        output.append(" Value=\"Return\" Order=\"4\"");
        output.append(" Condition=\"WIXUI_DONTVALIDATEPATH OR WIXUI_INSTALLDIR_VALID=&quot;1&quot;\" />\n");
        output.append("      <Publish Dialog=\"VerifyReadyDlg\" Control=\"Back\" Event=\"NewDialog\"");
        output.append(" Value=\"InstallDirDlg\" Order=\"1\" Condition=\"NOT Installed\" />\n");
        output.append("      <Publish Dialog=\"VerifyReadyDlg\" Control=\"Back\" Event=\"NewDialog\"");
        output.append(" Value=\"MaintenanceTypeDlg\" Order=\"2\" Condition=\"Installed AND NOT PATCH\" />\n");
        output.append("      <Publish Dialog=\"VerifyReadyDlg\" Control=\"Back\" Event=\"NewDialog\"");
        output.append(" Value=\"WelcomeDlg\" Order=\"3\" Condition=\"Installed AND PATCH\" />\n");
        output.append("      <Publish Dialog=\"MaintenanceWelcomeDlg\" Control=\"Next\" Event=\"NewDialog\"");
        output.append(" Value=\"MaintenanceTypeDlg\" />\n");
        output.append("      <Publish Dialog=\"MaintenanceTypeDlg\" Control=\"RepairButton\" Event=\"NewDialog\"");
        output.append(" Value=\"VerifyReadyDlg\" />\n");
        output.append("      <Publish Dialog=\"MaintenanceTypeDlg\" Control=\"RemoveButton\" Event=\"NewDialog\"");
        output.append(" Value=\"VerifyReadyDlg\" />\n");
        output.append("      <Publish Dialog=\"MaintenanceTypeDlg\" Control=\"Back\" Event=\"NewDialog\"");
        output.append(" Value=\"MaintenanceWelcomeDlg\" />\n");
        output.append("    </UI>\n");
        output.append("    <CustomActionRef Id=\"").append(validatePathActionId).append("\" />\n");
        output.append("    <UIRef Id=\"WixUI_Common\" />\n");
    }

    /// Returns the WiX UI architecture suffix for architecture-specific UI custom actions.
    ///
    /// @param architecture WiX target architecture.
    /// @return WiX UI architecture suffix without the leading underscore.
    private static String wixUiArchitectureSuffix(String architecture) {
        return switch (architecture.trim().toLowerCase(Locale.ROOT)) {
            case "x86" -> "X86";
            case "x64" -> "X64";
            case "arm64" -> "A64";
            default -> throw new IllegalArgumentException("Unsupported WiX architecture: " + architecture);
        };
    }

    /// Appends installation directory declarations.
    ///
    /// @param output WiX source output.
    /// @param rootDirectory installation root directory.
    /// @param installScope Windows Installer installation scope.
    private static void appendInstallDirectories(StringBuilder output, DirectoryNode rootDirectory, String installScope) {
        if (installScope.equals(INSTALL_SCOPE_PER_USER)) {
            output.append("    <StandardDirectory Id=\"LocalAppDataFolder\">\n");
            output.append("      <Directory Id=\"LocalProgramsFolder\" Name=\"Programs\">\n");
            appendDirectory(output, rootDirectory, 4);
            output.append("      </Directory>\n");
            output.append("    </StandardDirectory>\n");
        } else {
            output.append("    <StandardDirectory Id=\"ProgramFiles64Folder\">\n");
            appendDirectory(output, rootDirectory, 3);
            output.append("    </StandardDirectory>\n");
        }
    }

    /// Appends all file components.
    ///
    /// @param output WiX source output.
    /// @param files files to install.
    private void appendComponents(StringBuilder output, List<WixFile> files) {
        String guiExecutablePath = normalizedRelativePath(getGuiExecutablePath().get());
        for (WixFile file : files) {
            output.append("    <DirectoryRef Id=\"").append(xml(file.directoryId())).append("\">\n");
            output.append("      <Component Id=\"").append(xml(file.componentId())).append("\" Guid=\"");
            output.append(xml(file.componentGuid())).append("\">\n");
            output.append("        <File Id=\"").append(xml(file.fileId())).append("\" Source=\"");
            output.append(xml(file.sourceFile().toString())).append("\" KeyPath=\"yes\"");
            if (file.relativePath().equals(guiExecutablePath)) {
                output.append(">\n");
                appendShortcut(output, "GuiShortcut", getProductName().get(), "ApplicationIcon.ico");
                output.append("        </File>\n");
            } else {
                output.append(" />\n");
            }
            output.append("      </Component>\n");
            output.append("    </DirectoryRef>\n");
        }
    }

    /// Appends one shortcut nested below a file.
    ///
    /// @param output WiX source output.
    /// @param id shortcut identifier.
    /// @param name shortcut display name.
    /// @param iconId shortcut icon identifier.
    private static void appendShortcut(StringBuilder output, String id, String name, String iconId) {
        output.append("          <Shortcut Id=\"").append(xml(id)).append("\" Directory=\"ApplicationProgramsFolder\"");
        output.append(" Name=\"").append(xml(name)).append("\" WorkingDirectory=\"INSTALLFOLDER\"");
        output.append(" Icon=\"").append(xml(iconId)).append("\" Advertise=\"no\" />\n");
    }

    /// Appends the main feature.
    ///
    /// @param output WiX source output.
    /// @param files files to include.
    private static void appendFeature(StringBuilder output, List<WixFile> files) {
        output.append("    <Feature Id=\"MainFeature\" Title=\"Ruyi Imager\" Level=\"1\">\n");
        for (WixFile file : files) {
            output.append("      <ComponentRef Id=\"").append(xml(file.componentId())).append("\" />\n");
        }
        output.append("    </Feature>\n");
    }

    /// Appends one directory tree.
    ///
    /// @param output WiX source output.
    /// @param directory directory node.
    /// @param indent indentation level.
    private static void appendDirectory(StringBuilder output, DirectoryNode directory, int indent) {
        indent(output, indent);
        output.append("<Directory Id=\"").append(xml(directory.id())).append("\" Name=\"");
        output.append(xml(directory.name())).append("\">\n");
        for (DirectoryNode child : directory.children().values()) {
            appendDirectory(output, child, indent + 1);
        }
        indent(output, indent);
        output.append("</Directory>\n");
    }

    /// Creates the directory tree needed by all files.
    ///
    /// @param files files to install.
    /// @return root directory node.
    private DirectoryNode buildDirectoryTree(List<WixFile> files) {
        DirectoryNode root = new DirectoryNode(getInstallDirectoryName().get(), "INSTALLFOLDER", new TreeMap<>());
        for (WixFile file : files) {
            String parent = parentPath(file.relativePath());
            if (!parent.isEmpty()) {
                addDirectory(root, parent);
            }
        }
        return root;
    }

    /// Adds one relative directory path to the directory tree.
    ///
    /// @param root root directory node.
    /// @param relativePath directory path relative to the app image root.
    private static void addDirectory(DirectoryNode root, String relativePath) {
        DirectoryNode current = root;
        StringBuilder path = new StringBuilder();
        for (String segment : relativePath.split("/")) {
            if (path.length() > 0) {
                path.append('/');
            }
            path.append(segment);
            String currentPath = path.toString();
            current = current.children().computeIfAbsent(
                    segment,
                    ignored -> new DirectoryNode(segment, idFor("dir", currentPath), new TreeMap<>()));
        }
    }

    /// Collects files from the staged app image.
    ///
    /// @param appDirectory staged app image directory.
    /// @return files to install.
    /// @throws IOException if the app image cannot be scanned.
    private static List<WixFile> collectFiles(Path appDirectory) throws IOException {
        List<Path> paths;
        try (var stream = Files.walk(appDirectory)) {
            paths = stream
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }

        List<WixFile> files = new ArrayList<>(paths.size());
        for (Path path : paths) {
            String relativePath = normalizedRelativePath(appDirectory.relativize(path).toString());
            String parentPath = parentPath(relativePath);
            String directoryId = parentPath.isEmpty() ? "INSTALLFOLDER" : idFor("dir", parentPath);
            files.add(new WixFile(
                    path.toAbsolutePath(),
                    relativePath,
                    directoryId,
                    idFor("cmp", relativePath),
                    idFor("fil", relativePath),
                    "{" + UUID.nameUUIDFromBytes(("ruyi-imager:" + relativePath).getBytes(StandardCharsets.UTF_8))
                            .toString().toUpperCase() + "}"));
        }
        return files;
    }

    /// Returns the parent path for a normalized relative path.
    ///
    /// @param relativePath normalized relative path.
    /// @return parent path, or an empty string for the root directory.
    private static String parentPath(String relativePath) {
        int index = relativePath.lastIndexOf('/');
        return index < 0 ? "" : relativePath.substring(0, index);
    }

    /// Normalizes a relative path for WiX comparisons.
    ///
    /// @param path relative path.
    /// @return normalized path.
    private static String normalizedRelativePath(String path) {
        return path.replace('\\', '/');
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

    /// Creates a stable WiX identifier.
    ///
    /// @param prefix identifier prefix.
    /// @param value source value.
    /// @return stable WiX identifier.
    private static String idFor(String prefix, String value) {
        UUID uuid = UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
        byte[] bytes = new byte[16];
        long most = uuid.getMostSignificantBits();
        long least = uuid.getLeastSignificantBits();
        for (int i = 0; i < 8; i++) {
            bytes[i] = (byte) (most >>> (56 - i * 8));
            bytes[i + 8] = (byte) (least >>> (56 - i * 8));
        }
        return prefix + "_" + HexFormat.of().formatHex(bytes);
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

    /// Appends indentation spaces.
    ///
    /// @param output WiX source output.
    /// @param indent indentation level.
    private static void indent(StringBuilder output, int indent) {
        output.append("  ".repeat(indent));
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

    /// A generated WiX directory tree node.
    ///
    /// @param name directory name.
    /// @param id WiX directory identifier.
    /// @param children child directories by name.
    private record DirectoryNode(String name, String id, Map<String, DirectoryNode> children) {
    }

    /// A staged file represented in WiX source.
    ///
    /// @param sourceFile source file path.
    /// @param relativePath normalized relative path inside the app image.
    /// @param directoryId containing WiX directory identifier.
    /// @param componentId WiX component identifier.
    /// @param fileId WiX file identifier.
    /// @param componentGuid stable component GUID.
    private record WixFile(
            Path sourceFile,
            String relativePath,
            String directoryId,
            String componentId,
            String fileId,
            String componentGuid) {
    }
}

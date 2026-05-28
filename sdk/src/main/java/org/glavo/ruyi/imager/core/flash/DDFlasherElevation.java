// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.flash;

import org.glavo.ruyi.imager.core.PowerShellScripts;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/// Builds platform-specific elevated launch commands for the dd-flasher helper.
@NotNullByDefault
final class DDFlasherElevation {
    /// System property used to configure dd-flasher elevation.
    static final String ELEVATION_PROPERTY = "ruyi.imager.ddFlasher.elevation";

    /// Environment variable used to configure dd-flasher elevation.
    static final String ELEVATION_ENV = "RUYI_IMAGER_DD_FLASHER_ELEVATION";

    /// Prevents construction.
    private DDFlasherElevation() {
    }

    /// Returns whether helper execution should be elevated for a target path.
    ///
    /// @param target target path.
    /// @return whether elevated execution should be used.
    static boolean shouldElevate(Path target) {
        return shouldElevate(
                target,
                System.getProperty("os.name", ""),
                System.getenv("DISPLAY"),
                System.getenv("WAYLAND_DISPLAY"),
                System.getProperty("user.name", ""),
                configuredMode());
    }

    /// Returns whether helper execution should be elevated for a target path and environment.
    ///
    /// @param target target path.
    /// @param osName operating system name.
    /// @param display X11 display environment value.
    /// @param waylandDisplay Wayland display environment value.
    /// @param userName current user name.
    /// @param configuredMode configured elevation mode.
    /// @return whether elevated execution should be used.
    static boolean shouldElevate(
            Path target,
            String osName,
            @Nullable String display,
            @Nullable String waylandDisplay,
            String userName,
            @Nullable String configuredMode) {
        ElevationMode mode = elevationMode(configuredMode);
        if (mode == ElevationMode.NEVER) {
            return false;
        }
        if (mode == ElevationMode.ALWAYS) {
            return true;
        }

        String normalizedOs = osName.toLowerCase(Locale.ROOT);
        if (normalizedOs.startsWith("windows")) {
            return windowsRawTarget(target);
        }
        if (normalizedOs.contains("linux")) {
            return linuxRawTarget(target)
                    && !"root".equals(userName)
                    && (nonBlank(display) != null || nonBlank(waylandDisplay) != null);
        }
        if (normalizedOs.contains("mac") || normalizedOs.contains("darwin")) {
            return macOsRawTarget(target) && !"root".equals(userName);
        }
        return false;
    }

    /// Builds a platform-specific elevated launcher command.
    ///
    /// @param executable helper executable.
    /// @param arguments helper arguments excluding executable.
    /// @param osName operating system name.
    /// @return launcher command.
    /// @throws IOException when the operating system is not supported.
    static List<String> elevatedCommand(String executable, List<String> arguments, String osName) throws IOException {
        String normalizedOs = osName.toLowerCase(Locale.ROOT);
        if (normalizedOs.startsWith("windows")) {
            return windowsElevatedCommand(executable, arguments);
        }
        if (normalizedOs.contains("linux")) {
            ArrayList<String> command = new ArrayList<>(arguments.size() + 2);
            command.add("pkexec");
            command.add(executable);
            command.addAll(arguments);
            return command;
        }
        if (normalizedOs.contains("mac") || normalizedOs.contains("darwin")) {
            return macOsElevatedCommand(executable, arguments);
        }
        throw new IOException("dd-flasher elevation is not supported on this operating system.");
    }

    /// Builds a Windows UAC launcher command.
    ///
    /// @param executable helper executable.
    /// @param arguments helper arguments excluding executable.
    /// @return PowerShell command that starts the helper through UAC.
    /// @throws IOException when the fixed launcher script cannot be loaded.
    static List<String> windowsElevatedCommand(String executable, List<String> arguments) throws IOException {
        return List.of(
                "powershell.exe",
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                PowerShellScripts.path("start-elevated-process.ps1").toString(),
                "-FilePath",
                executable,
                "-ArgumentsBase64",
                encodedArguments(arguments));
    }

    /// Builds a macOS administrator launcher command.
    ///
    /// @param executable helper executable.
    /// @param arguments helper arguments excluding executable.
    /// @return osascript command that starts the helper with administrator privileges.
    static List<String> macOsElevatedCommand(String executable, List<String> arguments) {
        return List.of("osascript", "-e", macOsElevationScript(executable, arguments));
    }

    /// Builds the AppleScript used for macOS administrator elevation.
    ///
    /// @param executable helper executable.
    /// @param arguments helper arguments excluding executable.
    /// @return AppleScript snippet.
    static String macOsElevationScript(String executable, List<String> arguments) {
        return "do shell script "
                + appleScriptString(shellCommand(executable, arguments))
                + " with administrator privileges";
    }

    /// Returns whether a path identifies a Windows raw device target.
    ///
    /// @param target target path.
    /// @return whether this is a Windows raw device path.
    static boolean windowsRawTarget(Path target) {
        return target.toString().startsWith("\\\\.\\");
    }

    /// Returns whether a path identifies a Linux raw device target.
    ///
    /// @param target target path.
    /// @return whether this is a Linux raw device path.
    static boolean linuxRawTarget(Path target) {
        return target.toString().replace('\\', '/').startsWith("/dev/");
    }

    /// Returns whether a path identifies a macOS raw disk target.
    ///
    /// @param target target path.
    /// @return whether this is a macOS raw disk path.
    static boolean macOsRawTarget(Path target) {
        String normalized = target.toString().replace('\\', '/');
        return normalized.startsWith("/dev/disk") || normalized.startsWith("/dev/rdisk");
    }

    /// Reads the configured elevation mode.
    ///
    /// @return configured mode, or null when no mode is configured.
    private static @Nullable String configuredMode() {
        @Nullable String property = nonBlank(System.getProperty(ELEVATION_PROPERTY));
        if (property != null) {
            return property;
        }
        return nonBlank(System.getenv(ELEVATION_ENV));
    }

    /// Parses one elevation mode.
    ///
    /// @param value configured mode text.
    /// @return parsed elevation mode.
    private static ElevationMode elevationMode(@Nullable String value) {
        @Nullable String normalized = nonBlank(value);
        if (normalized == null) {
            return ElevationMode.AUTO;
        }
        return switch (normalized.toLowerCase(Locale.ROOT)) {
            case "always" -> ElevationMode.ALWAYS;
            case "never", "off", "false" -> ElevationMode.NEVER;
            default -> ElevationMode.AUTO;
        };
    }

    /// Builds a shell command with safely quoted arguments.
    ///
    /// @param executable executable path.
    /// @param arguments command arguments.
    /// @return shell command text.
    private static String shellCommand(String executable, List<String> arguments) {
        StringBuilder builder = new StringBuilder(shellString(executable));
        for (String argument : arguments) {
            builder.append(' ').append(shellString(argument));
        }
        return builder.toString();
    }

    /// Quotes one shell argument.
    ///
    /// @param value raw value.
    /// @return quoted shell argument.
    private static String shellString(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /// Quotes one AppleScript string literal.
    ///
    /// @param value raw value.
    /// @return quoted AppleScript string literal.
    private static String appleScriptString(String value) {
        return "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                + "\"";
    }

    /// Returns trimmed non-blank text.
    ///
    /// @param value input value.
    /// @return trimmed text, or null when blank.
    private static @Nullable String nonBlank(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }

    /// Encodes helper process arguments for the fixed PowerShell launcher script.
    ///
    /// @param arguments helper arguments excluding executable.
    /// @return Base64-encoded UTF-8 argument payload.
    private static String encodedArguments(List<String> arguments) {
        return Base64.getEncoder().encodeToString(String.join("\0", arguments).getBytes(StandardCharsets.UTF_8));
    }

    /// dd-flasher elevation policy.
    private enum ElevationMode {
        /// Automatically elevate raw devices on supported platforms.
        AUTO,

        /// Always elevate helper execution.
        ALWAYS,

        /// Never elevate helper execution.
        NEVER
    }
}

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.flash;

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
final class DdFlasherElevation {
    /// System property used to configure dd-flasher elevation.
    static final String ELEVATION_PROPERTY = "ruyi.imager.ddFlasher.elevation";

    /// Environment variable used to configure dd-flasher elevation.
    static final String ELEVATION_ENV = "RUYI_IMAGER_DD_FLASHER_ELEVATION";

    /// Prevents construction.
    private DdFlasherElevation() {
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
        if (normalizedOs.contains("win")) {
            return windowsRawTarget(target);
        }
        if (normalizedOs.contains("linux")) {
            return linuxRawTarget(target)
                    && !"root".equals(userName)
                    && (nonBlank(display) != null || nonBlank(waylandDisplay) != null);
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
        if (normalizedOs.contains("win")) {
            return windowsElevatedCommand(executable, arguments);
        }
        if (normalizedOs.contains("linux")) {
            ArrayList<String> command = new ArrayList<>(arguments.size() + 2);
            command.add("pkexec");
            command.add(executable);
            command.addAll(arguments);
            return command;
        }
        throw new IOException("dd-flasher elevation is not supported on this operating system.");
    }

    /// Builds a Windows UAC launcher command.
    ///
    /// @param executable helper executable.
    /// @param arguments helper arguments excluding executable.
    /// @return PowerShell command that starts the helper through UAC.
    static List<String> windowsElevatedCommand(String executable, List<String> arguments) {
        String script = windowsElevationScript(executable, arguments);
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        return List.of(
                "powershell.exe",
                "-NoProfile",
                "-ExecutionPolicy",
                "Bypass",
                "-EncodedCommand",
                encoded);
    }

    /// Builds the PowerShell script used for Windows UAC elevation.
    ///
    /// @param executable helper executable.
    /// @param arguments helper arguments excluding executable.
    /// @return PowerShell script.
    static String windowsElevationScript(String executable, List<String> arguments) {
        StringBuilder builder = new StringBuilder();
        builder.append("$ErrorActionPreference = 'Stop'\n");
        builder.append("$process = Start-Process -FilePath ");
        builder.append(powerShellString(executable));
        builder.append(" -ArgumentList @(");
        for (int i = 0; i < arguments.size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(powerShellString(arguments.get(i)));
        }
        builder.append(") -Verb RunAs -Wait -PassThru -WindowStyle Hidden\n");
        builder.append("exit $process.ExitCode\n");
        return builder.toString();
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

    /// Quotes one PowerShell string literal.
    ///
    /// @param value raw value.
    /// @return quoted PowerShell string literal.
    private static String powerShellString(String value) {
        return "'" + value.replace("'", "''") + "'";
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

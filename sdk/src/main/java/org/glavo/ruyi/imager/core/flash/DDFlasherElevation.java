// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.flash;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
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

    /// Returns whether the operating system uses native Windows elevation.
    ///
    /// @param osName operating system name.
    /// @return whether native Windows elevation is used.
    static boolean usesNativeWindowsElevation(String osName) {
        return osName.toLowerCase(Locale.ROOT).startsWith("windows");
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
            throw new IOException("Windows elevation uses native ShellExecuteExW.");
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

    /// Starts one Windows elevated helper process.
    ///
    /// @param executable helper executable.
    /// @param arguments helper arguments excluding executable.
    /// @return elevated process wrapper.
    /// @throws IOException when UAC launch fails.
    static WindowsElevatedProcess startWindowsElevated(String executable, List<String> arguments) throws IOException {
        return WindowsNative.startElevated(executable, windowsCommandLine(arguments));
    }

    /// Builds a Windows command line from argv-style arguments.
    ///
    /// @param arguments command arguments excluding executable.
    /// @return Windows command line.
    static String windowsCommandLine(List<String> arguments) {
        StringBuilder builder = new StringBuilder();
        for (String argument : arguments) {
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(windowsCommandLineArgument(argument));
        }
        return builder.toString();
    }

    /// Quotes one Windows command-line argument using `CommandLineToArgvW` rules.
    ///
    /// @param argument raw argument.
    /// @return command-line argument text.
    static String windowsCommandLineArgument(String argument) {
        boolean quote = argument.isEmpty()
                || argument.indexOf(' ') >= 0
                || argument.indexOf('\t') >= 0
                || argument.indexOf('"') >= 0;
        if (!quote) {
            return argument;
        }

        StringBuilder builder = new StringBuilder(argument.length() + 2);
        builder.append('"');
        int backslashes = 0;
        for (int index = 0; index < argument.length(); index++) {
            char ch = argument.charAt(index);
            if (ch == '\\') {
                backslashes++;
            } else if (ch == '"') {
                builder.append("\\".repeat(backslashes * 2 + 1));
                builder.append('"');
                backslashes = 0;
            } else {
                builder.append("\\".repeat(backslashes));
                builder.append(ch);
                backslashes = 0;
            }
        }
        builder.append("\\".repeat(backslashes * 2));
        builder.append('"');
        return builder.toString();
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

    /// dd-flasher elevation policy.
    private enum ElevationMode {
        /// Automatically elevate raw devices on supported platforms.
        AUTO,

        /// Always elevate helper execution.
        ALWAYS,

        /// Never elevate helper execution.
        NEVER
    }

    /// Native Windows elevation support.
    @NotNullByDefault
    private static final class WindowsNative {
        /// `SEE_MASK_NOCLOSEPROCESS` keeps the process handle available to the caller.
        private static final int SEE_MASK_NOCLOSEPROCESS = 0x00000040;

        /// `SW_SHOWNORMAL` requests the default visible UAC behavior.
        private static final int SW_SHOWNORMAL = 1;

        /// `SHELLEXECUTEINFOW` size for the supported 64-bit JDK runtime.
        private static final int SHELLEXECUTEINFOW_SIZE = 112;

        /// Offset of `cbSize`.
        private static final long SHELLEXECUTEINFOW_CB_SIZE = 0L;

        /// Offset of `fMask`.
        private static final long SHELLEXECUTEINFOW_F_MASK = 4L;

        /// Offset of `hwnd`.
        private static final long SHELLEXECUTEINFOW_HWND = 8L;

        /// Offset of `lpVerb`.
        private static final long SHELLEXECUTEINFOW_LP_VERB = 16L;

        /// Offset of `lpFile`.
        private static final long SHELLEXECUTEINFOW_LP_FILE = 24L;

        /// Offset of `lpParameters`.
        private static final long SHELLEXECUTEINFOW_LP_PARAMETERS = 32L;

        /// Offset of `lpDirectory`.
        private static final long SHELLEXECUTEINFOW_LP_DIRECTORY = 40L;

        /// Offset of `nShow`.
        private static final long SHELLEXECUTEINFOW_N_SHOW = 48L;

        /// Offset of `hInstApp`.
        private static final long SHELLEXECUTEINFOW_H_INST_APP = 56L;

        /// Offset of `lpIDList`.
        private static final long SHELLEXECUTEINFOW_LP_ID_LIST = 64L;

        /// Offset of `lpClass`.
        private static final long SHELLEXECUTEINFOW_LP_CLASS = 72L;

        /// Offset of `hkeyClass`.
        private static final long SHELLEXECUTEINFOW_HKEY_CLASS = 80L;

        /// Offset of `dwHotKey`.
        private static final long SHELLEXECUTEINFOW_DW_HOT_KEY = 88L;

        /// Offset of `hIcon`/`hMonitor`.
        private static final long SHELLEXECUTEINFOW_H_ICON = 96L;

        /// Offset of `hProcess`.
        private static final long SHELLEXECUTEINFOW_H_PROCESS = 104L;

        /// Prevents construction.
        private WindowsNative() {
        }

        /// Starts one elevated process through `ShellExecuteExW`.
        ///
        /// @param executable helper executable.
        /// @param parameters command-line parameters.
        /// @return elevated process wrapper.
        /// @throws IOException when UAC launch fails.
        private static WindowsElevatedProcess startElevated(String executable, String parameters) throws IOException {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment info = arena.allocate(SHELLEXECUTEINFOW_SIZE);
                info.set(ValueLayout.JAVA_INT, SHELLEXECUTEINFOW_CB_SIZE, SHELLEXECUTEINFOW_SIZE);
                info.set(ValueLayout.JAVA_INT, SHELLEXECUTEINFOW_F_MASK, SEE_MASK_NOCLOSEPROCESS);
                info.set(ValueLayout.ADDRESS, SHELLEXECUTEINFOW_HWND, MemorySegment.NULL);
                info.set(ValueLayout.ADDRESS, SHELLEXECUTEINFOW_LP_VERB, utf16(arena, "runas"));
                info.set(ValueLayout.ADDRESS, SHELLEXECUTEINFOW_LP_FILE, utf16(arena, executable));
                info.set(ValueLayout.ADDRESS, SHELLEXECUTEINFOW_LP_PARAMETERS, utf16(arena, parameters));
                info.set(ValueLayout.ADDRESS, SHELLEXECUTEINFOW_LP_DIRECTORY, MemorySegment.NULL);
                info.set(ValueLayout.JAVA_INT, SHELLEXECUTEINFOW_N_SHOW, SW_SHOWNORMAL);
                info.set(ValueLayout.ADDRESS, SHELLEXECUTEINFOW_H_INST_APP, MemorySegment.NULL);
                info.set(ValueLayout.ADDRESS, SHELLEXECUTEINFOW_LP_ID_LIST, MemorySegment.NULL);
                info.set(ValueLayout.ADDRESS, SHELLEXECUTEINFOW_LP_CLASS, MemorySegment.NULL);
                info.set(ValueLayout.ADDRESS, SHELLEXECUTEINFOW_HKEY_CLASS, MemorySegment.NULL);
                info.set(ValueLayout.JAVA_INT, SHELLEXECUTEINFOW_DW_HOT_KEY, 0);
                info.set(ValueLayout.ADDRESS, SHELLEXECUTEINFOW_H_ICON, MemorySegment.NULL);
                info.set(ValueLayout.ADDRESS, SHELLEXECUTEINFOW_H_PROCESS, MemorySegment.NULL);

                int success = (int) Shell32.SHELL_EXECUTE_EX.invokeExact(info);
                if (success == 0) {
                    throw new IOException("ShellExecuteExW failed to start elevated process.");
                }

                MemorySegment handle = info.get(ValueLayout.ADDRESS, SHELLEXECUTEINFOW_H_PROCESS);
                if (handle.equals(MemorySegment.NULL)) {
                    throw new IOException("ShellExecuteExW did not return a process handle.");
                }
                return new WindowsElevatedProcess(handle);
            } catch (RuntimeException exception) {
                throw new IOException("ShellExecuteExW failed to start elevated process.", exception);
            } catch (Throwable exception) {
                throw new IOException("ShellExecuteExW failed to start elevated process.", exception);
            }
        }

        /// Allocates one null-terminated UTF-16LE string.
        ///
        /// @param arena memory arena.
        /// @param value string value.
        /// @return native string segment.
        private static MemorySegment utf16(Arena arena, String value) {
            byte[] bytes = (value + "\0").getBytes(StandardCharsets.UTF_16LE);
            MemorySegment segment = arena.allocate(bytes.length);
            for (int index = 0; index < bytes.length; index++) {
                segment.set(ValueLayout.JAVA_BYTE, index, bytes[index]);
            }
            return segment;
        }
    }

    /// Shell32 native calls.
    @NotNullByDefault
    private static final class Shell32 {
        /// `ShellExecuteExW` downcall handle.
        private static final MethodHandle SHELL_EXECUTE_EX = Linker.nativeLinker().downcallHandle(
                SymbolLookup.libraryLookup("shell32", Arena.global())
                        .find("ShellExecuteExW")
                        .orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

        /// Prevents construction.
        private Shell32() {
        }
    }

    /// Kernel32 native calls.
    @NotNullByDefault
    private static final class Kernel32 {
        /// `WaitForSingleObject` downcall handle.
        private static final MethodHandle WAIT_FOR_SINGLE_OBJECT = Linker.nativeLinker().downcallHandle(
                SymbolLookup.libraryLookup("kernel32", Arena.global())
                        .find("WaitForSingleObject")
                        .orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

        /// `GetExitCodeProcess` downcall handle.
        private static final MethodHandle GET_EXIT_CODE_PROCESS = Linker.nativeLinker().downcallHandle(
                SymbolLookup.libraryLookup("kernel32", Arena.global())
                        .find("GetExitCodeProcess")
                        .orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

        /// `TerminateProcess` downcall handle.
        private static final MethodHandle TERMINATE_PROCESS = Linker.nativeLinker().downcallHandle(
                SymbolLookup.libraryLookup("kernel32", Arena.global())
                        .find("TerminateProcess")
                        .orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

        /// `CloseHandle` downcall handle.
        private static final MethodHandle CLOSE_HANDLE = Linker.nativeLinker().downcallHandle(
                SymbolLookup.libraryLookup("kernel32", Arena.global())
                        .find("CloseHandle")
                        .orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

        /// Prevents construction.
        private Kernel32() {
        }
    }

    /// Windows elevated process handle.
    @NotNullByDefault
    static final class WindowsElevatedProcess implements AutoCloseable {
        /// `WAIT_OBJECT_0` wait result.
        private static final int WAIT_OBJECT_0 = 0;

        /// Native process handle.
        private final MemorySegment handle;

        /// Whether the handle has been closed.
        private boolean closed;

        /// Creates a Windows elevated process wrapper.
        ///
        /// @param handle native process handle.
        private WindowsElevatedProcess(MemorySegment handle) {
            this.handle = handle;
        }

        /// Waits for process exit up to the supplied timeout.
        ///
        /// @param timeoutMillis timeout in milliseconds.
        /// @return whether the process exited.
        /// @throws IOException when the native wait fails.
        boolean waitFor(long timeoutMillis) throws IOException {
            int timeout = timeoutMillis <= 0L
                    ? 0
                    : timeoutMillis >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) timeoutMillis;
            try {
                int result = (int) Kernel32.WAIT_FOR_SINGLE_OBJECT.invokeExact(handle, timeout);
                return result == WAIT_OBJECT_0;
            } catch (Throwable exception) {
                throw new IOException("WaitForSingleObject failed.", exception);
            }
        }

        /// Returns the process exit value.
        ///
        /// @return process exit value.
        /// @throws IOException when the exit code cannot be read.
        int exitValue() throws IOException {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment exitCode = arena.allocate(ValueLayout.JAVA_INT);
                int success = (int) Kernel32.GET_EXIT_CODE_PROCESS.invokeExact(handle, exitCode);
                if (success == 0) {
                    throw new IOException("GetExitCodeProcess failed.");
                }
                return exitCode.get(ValueLayout.JAVA_INT, 0L);
            } catch (IOException exception) {
                throw exception;
            } catch (Throwable exception) {
                throw new IOException("GetExitCodeProcess failed.", exception);
            }
        }

        /// Terminates the process.
        void destroyForcibly() {
            try {
                Kernel32.TERMINATE_PROCESS.invokeExact(handle, 1);
            } catch (Throwable _) {
            }
        }

        /// Closes the native process handle.
        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                Kernel32.CLOSE_HANDLE.invokeExact(handle);
            } catch (Throwable _) {
            }
        }
    }
}

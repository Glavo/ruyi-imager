// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/// Resolves fixed PowerShell script files usable by `powershell.exe -File`.
@NotNullByDefault
public final class PowerShellScripts {
    /// System property used to override the directory containing PowerShell scripts.
    public static final String SCRIPTS_DIRECTORY_PROPERTY = "ruyi.imager.powershell.scripts";

    /// Environment variable used to override the directory containing PowerShell scripts.
    public static final String SCRIPTS_DIRECTORY_ENV = "RUYI_IMAGER_POWERSHELL_SCRIPTS";

    /// System property used to override application home discovery.
    public static final String APP_HOME_PROPERTY = "ruyi.imager.appHome";

    /// Classpath directory that contains audited PowerShell scripts.
    private static final String RESOURCE_ROOT = "/org/glavo/ruyi/imager/core/powershell/";

    /// Audited PowerShell script resource names.
    private static final Set<String> SCRIPT_NAMES = Set.of("list-windows-block-devices.ps1");

    /// Distribution directory for audited PowerShell scripts.
    private static final String DISTRIBUTION_DIRECTORY = "tools/powershell";

    /// Resolved script paths keyed by resource file name.
    private static final Map<String, Path> RESOLVED = new HashMap<>();

    /// Prevents construction.
    private PowerShellScripts() {
    }

    /// Returns a filesystem path for a fixed PowerShell script resource.
    ///
    /// @param name script resource file name.
    /// @return script path.
    /// @throws IOException when the script resource is not available as a regular filesystem file.
    public static synchronized Path path(String name) throws IOException {
        if (!SCRIPT_NAMES.contains(name)) {
            throw new IOException("Invalid PowerShell script resource name: " + name);
        }

        @Nullable Path existing = RESOLVED.get(name);
        if (existing != null && Files.isRegularFile(existing)) {
            return existing;
        }

        @Nullable Path script = configuredScript(name);
        if (script == null) {
            script = bundledScript(name);
        }
        if (script == null) {
            script = classpathScript(name);
        }
        if (script == null) {
            throw new IOException("PowerShell script is not available as a filesystem file: " + name);
        }

        RESOLVED.put(name, script);
        return script;
    }

    /// Resolves a script from the configured scripts directory.
    ///
    /// @param name script file name.
    /// @return script path, or null when not configured or missing.
    private static @Nullable Path configuredScript(String name) {
        @Nullable String directory = nonBlank(System.getProperty(SCRIPTS_DIRECTORY_PROPERTY));
        if (directory == null) {
            directory = nonBlank(System.getenv(SCRIPTS_DIRECTORY_ENV));
        }
        return directory == null ? null : regularScript(Path.of(directory).resolve(name));
    }

    /// Resolves a script from an application distribution.
    ///
    /// @param name script file name.
    /// @return script path, or null when no application home layout is available.
    private static @Nullable Path bundledScript(String name) {
        @Nullable Path appHome = appHome();
        if (appHome == null) {
            return null;
        }
        return regularScript(appHome.resolve(DISTRIBUTION_DIRECTORY).resolve(name));
    }

    /// Resolves a script from an exploded classpath resource.
    ///
    /// @param name script file name.
    /// @return script path, or null when the resource is packaged inside an archive.
    private static @Nullable Path classpathScript(String name) {
        @Nullable URL resource = PowerShellScripts.class.getResource(RESOURCE_ROOT + name);
        if (resource == null || !"file".equalsIgnoreCase(resource.getProtocol())) {
            return null;
        }
        try {
            return regularScript(Path.of(resource.toURI()));
        } catch (IllegalArgumentException | SecurityException | URISyntaxException _) {
            return null;
        }
    }

    /// Finds the current application home directory.
    ///
    /// @return application home, or null when running from an unsupported layout.
    private static @Nullable Path appHome() {
        @Nullable String property = nonBlank(System.getProperty(APP_HOME_PROPERTY));
        if (property != null) {
            return Path.of(property);
        }

        try {
            Path codeSource = Path.of(PowerShellScripts.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI());
            @Nullable Path libDirectory = codeSource.getParent();
            if (libDirectory != null && "lib".equals(libDirectory.getFileName().toString())) {
                return libDirectory.getParent();
            }
        } catch (IllegalArgumentException | NullPointerException | SecurityException | URISyntaxException _) {
        }
        return null;
    }

    /// Returns a regular script path when it exists.
    ///
    /// @param path candidate path.
    /// @return regular script path, or null.
    private static @Nullable Path regularScript(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        return Files.isRegularFile(normalized) ? normalized : null;
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
}

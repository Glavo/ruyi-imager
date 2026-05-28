// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

/// Extracts fixed PowerShell script resources to filesystem paths usable by `powershell.exe -File`.
@NotNullByDefault
public final class PowerShellScripts {
    /// Classpath directory that contains audited PowerShell scripts.
    private static final String RESOURCE_ROOT = "/org/glavo/ruyi/imager/core/powershell/";

    /// Extracted script paths keyed by resource file name.
    private static final Map<String, Path> EXTRACTED = new HashMap<>();

    /// Prevents construction.
    private PowerShellScripts() {
    }

    /// Returns a filesystem path for a fixed PowerShell script resource.
    ///
    /// @param name script resource file name.
    /// @return extracted script path.
    /// @throws IOException when the script resource is missing or cannot be copied.
    public static synchronized Path path(String name) throws IOException {
        if (name.contains("/") || name.contains("\\") || !name.endsWith(".ps1")) {
            throw new IOException("Invalid PowerShell script resource name: " + name);
        }

        @Nullable Path existing = EXTRACTED.get(name);
        if (existing != null && Files.isRegularFile(existing)) {
            return existing;
        }

        String resourceName = RESOURCE_ROOT + name;
        try (InputStream input = PowerShellScripts.class.getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new IOException("PowerShell script resource is missing: " + resourceName);
            }

            Path script = Files.createTempFile("ruyi-imager-", "-" + name);
            Files.copy(input, script, StandardCopyOption.REPLACE_EXISTING);
            script.toFile().deleteOnExit();
            EXTRACTED.put(name, script);
            return script;
        }
    }
}

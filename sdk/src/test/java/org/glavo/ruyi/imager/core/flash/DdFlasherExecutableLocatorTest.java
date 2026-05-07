// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.flash;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/// Tests for bundled dd-flasher executable resolution.
@NotNullByDefault
public final class DdFlasherExecutableLocatorTest {
    /// Verifies supported platform mappings.
    @Test
    public void mapsSupportedPlatforms() {
        assertPlatform("windows-x86_64", "dd-flasher.exe", "Windows 11", "amd64");
        assertPlatform("macos-x86_64", "dd-flasher", "Mac OS X", "x86_64");
        assertPlatform("macos-aarch64", "dd-flasher", "Darwin", "arm64");
        assertPlatform("linux-x86_64", "dd-flasher", "Linux", "x64");
        assertPlatform("linux-aarch64", "dd-flasher", "Linux", "aarch64");
    }

    /// Verifies unsupported architectures do not map to bundled binaries.
    @Test
    public void ignoresUnsupportedArchitectures() {
        assertNull(DdFlasherExecutableLocator.platform("Linux", "riscv64"));
        assertNull(DdFlasherExecutableLocator.platform("Plan 9", "amd64"));
    }

    /// Verifies bundled executable lookup under an application home.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when fixture files cannot be written.
    @Test
    public void findsBundledExecutableInApplicationHome(@TempDir Path temporaryDirectory) throws Exception {
        Path executable = temporaryDirectory
                .resolve("tools")
                .resolve("dd-flasher")
                .resolve("linux-x86_64")
                .resolve("dd-flasher");
        Files.createDirectories(executable.getParent());
        Files.writeString(executable, "dd-flasher");

        assertEquals(executable, DdFlasherExecutableLocator.bundledExecutable(temporaryDirectory, "Linux", "amd64"));
    }

    /// Verifies missing bundled binaries fall back to PATH lookup.
    ///
    /// @param temporaryDirectory temporary test directory.
    @Test
    public void returnsNullWhenBundledExecutableIsMissing(@TempDir Path temporaryDirectory) {
        assertNull(DdFlasherExecutableLocator.bundledExecutable(temporaryDirectory, "Linux", "amd64"));
    }

    /// Asserts one platform mapping.
    ///
    /// @param expectedDirectory expected distribution directory.
    /// @param expectedExecutable expected executable name.
    /// @param osName operating system name.
    /// @param osArch operating system architecture.
    private static void assertPlatform(
            String expectedDirectory,
            String expectedExecutable,
            String osName,
            String osArch) {
        @Nullable DdFlasherExecutableLocator.DdFlasherPlatform platform =
                DdFlasherExecutableLocator.platform(osName, osArch);
        assertNotNull(platform);
        assertEquals(expectedDirectory, platform.directory());
        assertEquals(expectedExecutable, platform.executableName());
    }
}

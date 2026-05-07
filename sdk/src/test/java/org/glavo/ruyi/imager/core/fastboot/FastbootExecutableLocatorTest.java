// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.fastboot;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/// Tests for bundled fastboot executable resolution.
@NotNullByDefault
public final class FastbootExecutableLocatorTest {
    /// Verifies supported platform mappings.
    @Test
    public void mapsSupportedPlatforms() {
        assertPlatform("windows-x86_64", "fastboot.exe", "Windows 11", "amd64");
        assertPlatform("macos-x86_64", "fastboot", "Mac OS X", "x86_64");
        assertPlatform("macos-x86_64", "fastboot", "Darwin", "x86-64");
        assertPlatform("linux-x86_64", "fastboot", "Linux", "x64");
        assertPlatform("linux-riscv64", "fastboot", "Linux", "riscv64");
        assertPlatform("linux-riscv64", "fastboot", "Linux", "riscv64gc");
    }

    /// Verifies unsupported architectures do not map to bundled binaries.
    @Test
    public void ignoresUnsupportedArchitectures() {
        assertNull(FastbootExecutableLocator.platform("Linux", "aarch64"));
        assertNull(FastbootExecutableLocator.platform("Mac OS X", "arm64"));
    }

    /// Verifies bundled executable lookup under an application home.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when fixture files cannot be written.
    @Test
    public void findsBundledExecutableInApplicationHome(@TempDir Path temporaryDirectory) throws Exception {
        Path executable = temporaryDirectory
                .resolve("tools")
                .resolve("fastboot")
                .resolve("linux-x86_64")
                .resolve("fastboot");
        Files.createDirectories(executable.getParent());
        Files.writeString(executable, "fastboot");

        assertEquals(executable, FastbootExecutableLocator.bundledExecutable(temporaryDirectory, "Linux", "amd64"));
    }

    /// Verifies missing bundled binaries fall back to PATH lookup.
    ///
    /// @param temporaryDirectory temporary test directory.
    @Test
    public void returnsNullWhenBundledExecutableIsMissing(@TempDir Path temporaryDirectory) {
        assertNull(FastbootExecutableLocator.bundledExecutable(temporaryDirectory, "Linux", "amd64"));
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
        @Nullable FastbootExecutableLocator.FastbootPlatform platform =
                FastbootExecutableLocator.platform(osName, osArch);
        assertNotNull(platform);
        assertEquals(expectedDirectory, platform.directory());
        assertEquals(expectedExecutable, platform.executableName());
    }
}

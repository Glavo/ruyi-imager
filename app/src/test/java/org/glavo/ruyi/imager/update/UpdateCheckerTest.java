// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.update;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests local application update manifest checks.
@NotNullByDefault
public final class UpdateCheckerTest {
    /// Detects a newer semantic version.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when the manifest cannot be written or checked.
    @Test
    public void detectsNewerVersion(@TempDir Path temporaryDirectory) throws Exception {
        Path manifest = writeManifest(temporaryDirectory, "1.1.0", 1L);

        UpdateCheckResult result = new UpdateChecker(new BuildInfo("1.0.0", 10L), manifest).check();

        assertEquals(UpdateCheckResult.Status.UPDATE_AVAILABLE, result.status());
        assertEquals("1.1.0", result.available().version());
    }

    /// Detects a newer nightly build when SemVer build metadata differs.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when the manifest cannot be written or checked.
    @Test
    public void detectsNewerBuildNumber(@TempDir Path temporaryDirectory) throws Exception {
        Path manifest = writeManifest(temporaryDirectory, "1.0.0+nightly.new", 42L);

        UpdateCheckResult result = new UpdateChecker(
                new BuildInfo("1.0.0+nightly.old", 41L),
                manifest).check();

        assertEquals(UpdateCheckResult.Status.UPDATE_AVAILABLE, result.status());
    }

    /// Does not offer an older manifest build.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when the manifest cannot be written or checked.
    @Test
    public void rejectsOlderBuild(@TempDir Path temporaryDirectory) throws Exception {
        Path manifest = writeManifest(temporaryDirectory, "1.0.0", 40L);

        UpdateCheckResult result = new UpdateChecker(new BuildInfo("1.0.0", 41L), manifest).check();

        assertEquals(UpdateCheckResult.Status.UP_TO_DATE, result.status());
    }

    /// Orders prerelease and release versions according to Semantic Versioning.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when the manifest cannot be written or checked.
    @Test
    public void releaseSupersedesPrerelease(@TempDir Path temporaryDirectory) throws Exception {
        Path manifest = writeManifest(temporaryDirectory, "1.0.0", 0L);

        UpdateCheckResult result = new UpdateChecker(new BuildInfo("1.0-SNAPSHOT", 0L), manifest).check();

        assertEquals(UpdateCheckResult.Status.UPDATE_AVAILABLE, result.status());
    }

    /// Rejects unsupported manifest schema versions.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when the manifest cannot be written.
    @Test
    public void rejectsUnsupportedSchema(@TempDir Path temporaryDirectory) throws Exception {
        Path manifest = temporaryDirectory.resolve("update.json");
        Files.writeString(manifest, """
                {
                  "schemaVersion": 2,
                  "version": "1.1.0",
                  "buildNumber": 1
                }
                """);

        IOException exception = assertThrows(
                IOException.class,
                () -> new UpdateChecker(new BuildInfo("1.0.0", 0L), manifest).check());

        assertTrue(exception.getMessage().contains("update"));
    }

    /// Rejects unknown fields so incompatible manifests do not silently pass validation.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when the manifest cannot be written.
    @Test
    public void rejectsUnknownFields(@TempDir Path temporaryDirectory) throws Exception {
        Path manifest = temporaryDirectory.resolve("update.json");
        Files.writeString(manifest, """
                {
                  "schemaVersion": 1,
                  "version": "1.1.0",
                  "buildNumber": 1,
                  "unexpected": true
                }
                """);

        assertThrows(
                IOException.class,
                () -> new UpdateChecker(new BuildInfo("1.0.0", 0L), manifest).check());
    }

    /// Rejects a manifest without its required build number.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when the manifest cannot be written.
    @Test
    public void rejectsMissingBuildNumber(@TempDir Path temporaryDirectory) throws Exception {
        Path manifest = temporaryDirectory.resolve("update.json");
        Files.writeString(manifest, """
                {
                  "schemaVersion": 1,
                  "version": "1.1.0"
                }
                """);

        assertThrows(
                IOException.class,
                () -> new UpdateChecker(new BuildInfo("1.0.0", 0L), manifest).check());
    }

    /// Rejects invalid Semantic Versioning prerelease identifiers.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when the manifest cannot be written.
    @Test
    public void rejectsInvalidPrereleaseVersion(@TempDir Path temporaryDirectory) throws Exception {
        Path manifest = writeManifest(temporaryDirectory, "1.1.0-rc.01", 1L);

        assertThrows(
                IOException.class,
                () -> new UpdateChecker(new BuildInfo("1.0.0", 0L), manifest).check());
    }

    /// Reports a missing local manifest as an I/O failure.
    ///
    /// @param temporaryDirectory temporary test directory.
    @Test
    public void rejectsMissingManifest(@TempDir Path temporaryDirectory) {
        Path manifest = temporaryDirectory.resolve("missing.json");

        IOException exception = assertThrows(
                IOException.class,
                () -> new UpdateChecker(new BuildInfo("1.0.0", 0L), manifest).check());

        assertTrue(exception.getMessage().contains(manifest.toAbsolutePath().toString()));
    }

    /// Writes a valid test manifest.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @param version            available version.
    /// @param buildNumber        available build number.
    /// @return manifest path.
    /// @throws IOException when the manifest cannot be written.
    private static Path writeManifest(Path temporaryDirectory, String version, long buildNumber) throws IOException {
        Path manifest = temporaryDirectory.resolve("update.json");
        Files.writeString(manifest, """
                {
                  "schemaVersion": 1,
                  "version": "%s",
                  "buildNumber": %d,
                  "releaseNotes": "Test release"
                }
                """.formatted(version, buildNumber));
        return manifest;
    }
}

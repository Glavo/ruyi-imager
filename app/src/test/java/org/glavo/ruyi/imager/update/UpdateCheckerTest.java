// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.update;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests local application update manifest checks.
@NotNullByDefault
public final class UpdateCheckerTest {
    /// Detects a newer stable semantic version.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when the manifest cannot be written or checked.
    @Test
    public void detectsNewerVersion(@TempDir Path temporaryDirectory) throws Exception {
        Path manifest = writeManifest(temporaryDirectory, "stable", "1.1.0");

        UpdateCheckResult result = new UpdateChecker(new BuildInfo("1.0.0"), manifest).check();

        assertEquals(UpdateCheckResult.Status.UPDATE_AVAILABLE, result.status());
        assertEquals("1.1.0", result.available().version());
    }

    /// Selects releases only from the requested channel.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when the manifest cannot be written or checked.
    @Test
    public void selectsRequestedChannel(@TempDir Path temporaryDirectory) throws Exception {
        Path manifest = temporaryDirectory.resolve("update.json");
        Files.writeString(manifest, """
                {
                  "schemaVersion": 1,
                  "releases": [
                    {
                      "channel": "stable",
                      "version": "1.1.0",
                      "artifacts": []
                    },
                    {
                      "channel": "nightly",
                      "version": "1.2.0-nightly.20+test",
                      "artifacts": []
                    }
                  ]
                }
                """);

        UpdateCheckResult result = new UpdateChecker(new BuildInfo("1.0.0"), manifest)
                .check(UpdateChannel.NIGHTLY);

        assertEquals("1.2.0-nightly.20+test", result.available().version());
        assertEquals(UpdateChannel.NIGHTLY, result.available().channel());
    }

    /// Selects the newest release within one channel.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when the manifest cannot be written or checked.
    @Test
    public void selectsNewestRelease(@TempDir Path temporaryDirectory) throws Exception {
        Path manifest = temporaryDirectory.resolve("update.json");
        Files.writeString(manifest, """
                {
                  "schemaVersion": 1,
                  "releases": [
                    {
                      "channel": "stable",
                      "version": "1.1.0",
                      "artifacts": []
                    },
                    {
                      "channel": "stable",
                      "version": "1.2.0",
                      "artifacts": []
                    }
                  ]
                }
                """);

        UpdateCheckResult result = new UpdateChecker(new BuildInfo("1.0.0"), manifest).check();

        assertEquals("1.2.0", result.available().version());
    }

    /// Rejects same-channel releases with equal SemVer precedence.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when the manifest cannot be written.
    @Test
    public void rejectsAmbiguousReleases(@TempDir Path temporaryDirectory) throws Exception {
        Path manifest = temporaryDirectory.resolve("update.json");
        Files.writeString(manifest, """
                {
                  "schemaVersion": 1,
                  "releases": [
                    {
                      "channel": "stable",
                      "version": "1.1.0+first",
                      "artifacts": []
                    },
                    {
                      "channel": "stable",
                      "version": "1.1.0+second",
                      "artifacts": []
                    }
                  ]
                }
                """);

        assertThrows(
                IOException.class,
                () -> new UpdateChecker(new BuildInfo("1.0.0"), manifest).check());
    }

    /// Parses platform installer metadata from a release.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when the manifest cannot be written or checked.
    @Test
    public void parsesPlatformArtifact(@TempDir Path temporaryDirectory) throws Exception {
        Path manifest = temporaryDirectory.resolve("update.json");
        Files.writeString(manifest, """
                {
                  "schemaVersion": 1,
                  "releases": [
                    {
                      "channel": "stable",
                      "version": "1.1.0",
                      "artifacts": [
                        {
                          "platform": "windows-x86_64",
                          "packageType": "setup-exe",
                          "source": "packages/ruyi-imager-setup.exe",
                          "size": 123,
                          "sha256": "%s"
                        }
                      ]
                    }
                  ]
                }
                """.formatted("0".repeat(64)));

        UpdateArtifact artifact = Objects.requireNonNull(new UpdateChecker(new BuildInfo("1.0.0"), manifest)
                .check()
                .available()
                .artifactFor(UpdatePlatform.WINDOWS_X86_64));

        assertEquals(UpdatePackageType.SETUP_EXE, artifact.packageType());
        assertEquals(123L, artifact.size());
    }

    /// Detects a newer nightly build from its numeric prerelease identifier.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when the manifest cannot be written or checked.
    @Test
    public void detectsNewerNightlyVersion(@TempDir Path temporaryDirectory) throws Exception {
        Path manifest = writeManifest(temporaryDirectory, "nightly", "1.0.0-nightly.42+new");

        UpdateCheckResult result = new UpdateChecker(
                new BuildInfo("1.0.0-nightly.41+old"),
                manifest).check(UpdateChannel.NIGHTLY);

        assertEquals(UpdateCheckResult.Status.UPDATE_AVAILABLE, result.status());
    }

    /// Offers the stable build when leaving a same-version nightly channel.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when the manifest cannot be written or checked.
    @Test
    public void offersStableReleaseWhenLeavingNightly(@TempDir Path temporaryDirectory) throws Exception {
        Path manifest = writeManifest(temporaryDirectory, "stable", "1.0.0");

        UpdateCheckResult result = new UpdateChecker(
                new BuildInfo("1.0.0-nightly.41+old"),
                manifest).check(UpdateChannel.STABLE);

        assertEquals(UpdateCheckResult.Status.UPDATE_AVAILABLE, result.status());
    }

    /// Does not offer an older nightly version.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when the manifest cannot be written or checked.
    @Test
    public void rejectsOlderNightlyVersion(@TempDir Path temporaryDirectory) throws Exception {
        Path manifest = writeManifest(temporaryDirectory, "nightly", "1.0.0-nightly.40+old");

        UpdateCheckResult result = new UpdateChecker(
                new BuildInfo("1.0.0-nightly.41+new"),
                manifest).check(UpdateChannel.NIGHTLY);

        assertEquals(UpdateCheckResult.Status.UP_TO_DATE, result.status());
    }

    /// Orders prerelease and release versions according to Semantic Versioning.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when the manifest cannot be written or checked.
    @Test
    public void releaseSupersedesPrerelease(@TempDir Path temporaryDirectory) throws Exception {
        Path manifest = writeManifest(temporaryDirectory, "stable", "1.0.0");

        UpdateCheckResult result = new UpdateChecker(new BuildInfo("1.0-SNAPSHOT"), manifest).check();

        assertEquals(UpdateCheckResult.Status.UPDATE_AVAILABLE, result.status());
    }

    /// Rejects duplicate JSON fields.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when the manifest cannot be written.
    @Test
    public void rejectsDuplicateFields(@TempDir Path temporaryDirectory) throws Exception {
        Path manifest = temporaryDirectory.resolve("update.json");
        Files.writeString(manifest, """
                {
                  "schemaVersion": 1,
                  "schemaVersion": 1,
                  "releases": []
                }
                """);

        assertThrows(
                IOException.class,
                () -> new UpdateChecker(new BuildInfo("1.0.0"), manifest).check());
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
                  "releases": []
                }
                """);

        IOException exception = assertThrows(
                IOException.class,
                () -> new UpdateChecker(new BuildInfo("1.0.0"), manifest).check());

        assertTrue(exception.getMessage().contains("update"));
    }

    /// Rejects unknown nested fields so incompatible manifests cannot silently pass validation.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when the manifest cannot be written.
    @Test
    public void rejectsUnknownFields(@TempDir Path temporaryDirectory) throws Exception {
        Path manifest = temporaryDirectory.resolve("update.json");
        Files.writeString(manifest, """
                {
                  "schemaVersion": 1,
                  "releases": [
                    {
                      "channel": "stable",
                      "version": "1.1.0",
                      "artifacts": [],
                      "unexpected": true
                    }
                  ]
                }
                """);

        assertThrows(
                IOException.class,
                () -> new UpdateChecker(new BuildInfo("1.0.0"), manifest).check());
    }

    /// Rejects invalid Semantic Versioning prerelease identifiers.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when the manifest cannot be written.
    @Test
    public void rejectsInvalidPrereleaseVersion(@TempDir Path temporaryDirectory) throws Exception {
        Path manifest = writeManifest(temporaryDirectory, "stable", "1.1.0-rc.01");

        assertThrows(
                IOException.class,
                () -> new UpdateChecker(new BuildInfo("1.0.0"), manifest).check());
    }

    /// Reports a missing local manifest as an I/O failure.
    ///
    /// @param temporaryDirectory temporary test directory.
    @Test
    public void rejectsMissingManifest(@TempDir Path temporaryDirectory) {
        Path manifest = temporaryDirectory.resolve("missing.json");

        IOException exception = assertThrows(
                IOException.class,
                () -> new UpdateChecker(new BuildInfo("1.0.0"), manifest).check());

        assertTrue(exception.getMessage().contains(manifest.toAbsolutePath().toString()));
    }

    /// Writes a valid test manifest with one release and no artifacts.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @param channel            available channel token.
    /// @param version            available version.
    /// @return manifest path.
    /// @throws IOException when the manifest cannot be written.
    private static Path writeManifest(
            Path temporaryDirectory,
            String channel,
            String version) throws IOException {
        Path manifest = temporaryDirectory.resolve("update.json");
        Files.writeString(manifest, """
                {
                  "schemaVersion": 1,
                  "releases": [
                    {
                      "channel": "%s",
                      "version": "%s",
                      "releaseNotes": "Test release",
                      "artifacts": []
                    }
                  ]
                }
                """.formatted(channel, version));
        return manifest;
    }

}

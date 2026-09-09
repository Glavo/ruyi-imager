// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.update;

import org.glavo.ruyi.imager.core.AppDirectories;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.parallel.Resources.SYSTEM_PROPERTIES;

/// Tests local application update manifest checks.
@NotNullByDefault
public final class UpdateCheckerTest {
    /// Keeps every checker factory usable on unknown platforms while rejecting installer preparation.
    ///
    /// @param property runtime platform property to override.
    /// @param value unsupported OS or architecture.
    /// @param directory isolated application directory.
    /// @throws IOException when the manifest cannot be written or checked.
    @ParameterizedTest
    @CsvSource({"os.name, UnsupportedOS", "os.arch, unsupported-arch"})
    @ResourceLock(SYSTEM_PROPERTIES)
    public void degradesUnsupportedPlatforms(String property, String value, @TempDir Path directory) throws IOException {
        Path manifest = writeManifest(directory, "stable", "1.1.0");
        AppDirectories directories = new AppDirectories(directory, directory.resolve("cache"));
        UpdateSource source = UpdateSource.of(manifest);
        UpdateChecker local;
        UpdateChecker configured;
        UpdateChecker defaults;
        @Nullable String original = System.getProperty(property);
        System.setProperty(property, value);
        try {
            local = new UpdateChecker(new BuildInfo("1.0.0"), manifest);
            configured = UpdateChecker.createConfigured(source);
            defaults = UpdateChecker.createDefault(directories);
        } finally {
            if (original == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, original);
            }
        }
        for (UpdateChecker checker : List.of(local, configured, defaults)) {
            assertNull(checker.target());
            assertThrows(IllegalStateException.class, () -> UpdatePackageManager.createDefault(directories, checker));
        }
        UpdateCheckResult result = local.check();
        assertEquals(UpdateCheckResult.Status.NO_COMPATIBLE_UPDATE, result.status());
        assertEquals("1.1.0", Objects.requireNonNull(result.available()).version());
        assertNull(result.artifact());
        assertEquals(UpdateCheckResult.Status.UP_TO_DATE, local.check(UpdateChannel.NIGHTLY).status());
    }

    /// Detects a newer stable Burn-compatible version.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when the manifest cannot be written or checked.
    @Test
    public void detectsNewerVersion(@TempDir Path temporaryDirectory) throws Exception {
        Path manifest = writeManifest(temporaryDirectory, "stable", "1.1.0");

        UpdateCheckResult result = checker(new BuildInfo("1.0.0"), manifest).check();

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
                      "version": "1.2.0-nightly.20260716T143052Z.3921d84",
                      "artifacts": []
                    }
                  ]
                }
                """);

        UpdateCheckResult result = checker(new BuildInfo("1.0.0"), manifest)
                .check(UpdateChannel.NIGHTLY);

        assertEquals("1.2.0-nightly.20260716T143052Z.3921d84", result.available().version());
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

        UpdateCheckResult result = checker(new BuildInfo("1.0.0"), manifest).check();

        assertEquals("1.2.0", result.available().version());
    }

    /// Rejects duplicate releases with equal application version precedence.
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
                      "version": "1.1.0-rc.1+build.1",
                      "artifacts": []
                    },
                    {
                      "channel": "stable",
                      "version": "1.1.0-RC.01+build.2",
                      "artifacts": []
                    }
                  ]
                }
                """);

        assertThrows(
                IOException.class,
                () -> checker(new BuildInfo("1.0.0"), manifest).check());
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

        UpdateArtifact artifact = Objects.requireNonNull(checker(new BuildInfo("1.0.0"), manifest)
                .check().artifact());

        assertEquals(UpdatePackageType.SETUP_EXE, artifact.packageType());
        assertEquals(123L, artifact.size());
    }

    /// Ignores unsupported macOS archive types without failing checks on other platforms.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when the manifest cannot be written.
    @Test
    public void skipsUnsupportedMacOsArtifact(@TempDir Path temporaryDirectory) throws Exception {
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
                          "platform": "macos-aarch64",
                          "packageType": "tar-gz",
                          "source": "packages/ruyi-imager.tar.gz",
                          "size": 123,
                          "sha256": "%s"
                        }
                      ]
                    }
                  ]
                }
                """.formatted("0".repeat(64)));

        UpdateCheckResult result = checker(new BuildInfo("1.0.0"), manifest).check();
        assertEquals(UpdateCheckResult.Status.NO_COMPATIBLE_UPDATE, result.status());
        assertTrue(Objects.requireNonNull(result.available()).artifacts().isEmpty());
    }

    /// Detects a newer nightly build from ordered prerelease identifiers.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when the manifest cannot be written or checked.
    @Test
    public void detectsNewerNightlyVersion(@TempDir Path temporaryDirectory) throws Exception {
        Path manifest = writeManifest(
                temporaryDirectory,
                "nightly",
                "1.0.0-nightly.20260716T143052Z.2222222");

        UpdateCheckResult result = checker(
                new BuildInfo("1.0.0-nightly.20260716T143051Z.1111111"),
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

        UpdateCheckResult result = checker(
                new BuildInfo("1.0.0-nightly.20260716T143052Z.3921d84"),
                manifest).check(UpdateChannel.STABLE);

        assertEquals(UpdateCheckResult.Status.UPDATE_AVAILABLE, result.status());
    }

    /// Does not offer an older nightly version.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when the manifest cannot be written or checked.
    @Test
    public void rejectsOlderNightlyVersion(@TempDir Path temporaryDirectory) throws Exception {
        Path manifest = writeManifest(
                temporaryDirectory,
                "nightly",
                "1.0.0-nightly.20260716T143051Z.1111111");

        UpdateCheckResult result = checker(
                new BuildInfo("1.0.0-nightly.20260716T143052Z.2222222"),
                manifest).check(UpdateChannel.NIGHTLY);

        assertEquals(UpdateCheckResult.Status.UP_TO_DATE, result.status());
    }

    /// Orders development and stable versions according to application policy.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when the manifest cannot be written or checked.
    @Test
    public void releaseSupersedesPrerelease(@TempDir Path temporaryDirectory) throws Exception {
        Path manifest = writeManifest(temporaryDirectory, "stable", "1.0.0");

        UpdateCheckResult result = checker(new BuildInfo("1.0.0-dev"), manifest).check();

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
                () -> checker(new BuildInfo("1.0.0"), manifest).check());
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
                () -> checker(new BuildInfo("1.0.0"), manifest).check());

        assertTrue(exception.getMessage().contains("update"));
    }

    /// Ignores optional extension fields while preserving known release metadata.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when the manifest cannot be written.
    @Test
    public void acceptsOptionalExtensionFields(@TempDir Path temporaryDirectory) throws Exception {
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

        assertEquals("1.1.0", Objects.requireNonNull(
                checker(new BuildInfo("1.0.0"), manifest).check().available()).version());
    }

    /// Rejects versions outside the strict Burn-compatible ordering subset.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when the manifest cannot be written.
    @Test
    public void rejectsUnsupportedVersionFormat(@TempDir Path temporaryDirectory) throws Exception {
        Path manifest = writeManifest(temporaryDirectory, "stable", "1.1.0-preview/1");

        assertThrows(
                IOException.class,
                () -> checker(new BuildInfo("1.0.0"), manifest).check());
    }

    /// Reports a missing local manifest as an I/O failure.
    ///
    /// @param temporaryDirectory temporary test directory.
    @Test
    public void rejectsMissingManifest(@TempDir Path temporaryDirectory) {
        Path manifest = temporaryDirectory.resolve("missing.json");

        IOException exception = assertThrows(
                IOException.class,
                () -> checker(new BuildInfo("1.0.0"), manifest).check());

        assertTrue(exception.getMessage().contains(manifest.toAbsolutePath().toString()));
    }

    /// Selects an older compatible update when the newest release has unmet or unknown conditions.
    ///
    /// @param requirements ineligible conditions for the newest release.
    /// @param temporaryDirectory temporary test directory.
    /// @throws IOException when the feed cannot be written or checked.
    @ParameterizedTest
    @ValueSource(strings = {
            "{\"minimumSystemVersion\":\"10.0.30000\"}",
            "{\"minimumAppVersion\":\"1.1.0\"}",
            "{\"futureCondition\":true}"
    })
    public void selectsNewestCompatibleRelease(String requirements, @TempDir Path temporaryDirectory) throws IOException {
        Path manifest = temporaryDirectory.resolve("update.json");
        Files.writeString(manifest, """
                {"schemaVersion":1,"releases":[
                  {"channel":"stable","version":"2.0.0","requirements":%s,"artifacts":[%s]},
                  {"channel":"stable","version":"1.1.0","artifacts":[%s]},
                  {"channel":"stable","version":"1.0.1","artifacts":[%s]}
                ]}
                """.formatted(requirements, installerJson(), installerJson(), installerJson()));
        UpdateCheckResult result = checker(new BuildInfo("1.0.0"), manifest).check();
        assertEquals(UpdateCheckResult.Status.UPDATE_AVAILABLE, result.status());
        assertEquals("1.1.0", Objects.requireNonNull(result.available()).version());
        assertEquals(UpdatePackageType.SETUP_EXE, Objects.requireNonNull(result.artifact()).packageType());
    }

    /// Ignores unknown channels and installer variants without hiding a recognized installer.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws IOException when the feed cannot be written or checked.
    @Test
    public void acceptsFutureCandidates(@TempDir Path temporaryDirectory) throws IOException {
        Path manifest = temporaryDirectory.resolve("update.json");
        Files.writeString(manifest, """
                {"schemaVersion":1,"futureMetadata":{},"releases":[
                  {"channel":"future"},
                  {"channel":"stable","version":"1.1.0","artifacts":[
                    {"platform":"future","packageType":"future"},
                    {"platform":"windows-x86_64","packageType":"future"},
                    %s
                  ]}
                ]}
                """.formatted(installerJson()));
        UpdateCheckResult result = checker(new BuildInfo("1.0.0"), manifest).check();
        assertEquals(UpdateCheckResult.Status.UPDATE_AVAILABLE, result.status());
        assertEquals(1, Objects.requireNonNull(result.available()).artifacts().size());
    }

    /// Distinguishes an empty channel from a newer release with no compatible installer.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws IOException when the feed cannot be written or checked.
    @Test
    public void distinguishesNoCompatibleUpdate(@TempDir Path temporaryDirectory) throws IOException {
        Path manifest = temporaryDirectory.resolve("update.json");
        Files.writeString(manifest, """
                {"schemaVersion":1,"releases":[
                  {"channel":"stable","version":"1.1.0","artifacts":[]}
                ]}
                """);
        UpdateChecker checker = checker(new BuildInfo("1.0.0"), manifest);
        UpdateCheckResult stable = checker.check();
        assertEquals(UpdateCheckResult.Status.NO_COMPATIBLE_UPDATE, stable.status());
        assertEquals("1.1.0", Objects.requireNonNull(stable.available()).version());
        assertNull(stable.artifact());
        UpdateCheckResult nightly = checker.check(UpdateChannel.NIGHTLY);
        assertEquals(UpdateCheckResult.Status.UP_TO_DATE, nightly.status());
        assertNull(nightly.available());
        Files.writeString(manifest, "{\"schemaVersion\":1,\"releases\":[]}");
        assertEquals(UpdateCheckResult.Status.UP_TO_DATE, checker.check().status());
    }

    /// Reports unsupported integral schema values as checked manifest failures, including overflow values.
    ///
    /// @param schema unsupported schema number.
    /// @param temporaryDirectory temporary test directory.
    /// @throws IOException when the feed cannot be written.
    @ParameterizedTest
    @ValueSource(strings = {"2", "2147483648", "9223372036854775808"})
    public void rejectsInvalidSchemaNumbers(String schema, @TempDir Path temporaryDirectory) throws IOException {
        Path manifest = temporaryDirectory.resolve("update.json");
        Files.writeString(manifest, "{\"schemaVersion\":" + schema + ",\"releases\":[]}");
        assertThrows(IOException.class, () -> checker(new BuildInfo("1.0.0"), manifest).check());
    }

    /// Returns a recognized installer object with valid metadata but no physical package dependency.
    ///
    /// @return JSON installer object.
    private static String installerJson() {
        return """
                {"platform":"windows-x86_64","packageType":"setup-exe",
                 "source":"packages/setup.exe","size":123,"sha256":"%s"}
                """.formatted("0".repeat(64));
    }

    /// Creates a checker with deterministic Windows installation capabilities.
    ///
    /// @param current installed build.
    /// @param manifest local manifest.
    /// @return checker independent of the host operating system.
    private static UpdateChecker checker(BuildInfo current, Path manifest) {
        return new UpdateChecker(current, UpdateSource.of(manifest), new UpdateTarget(
                UpdatePlatform.WINDOWS_X86_64, "10.0.22631", List.of(UpdatePackageType.SETUP_EXE)));
    }

    /// Writes a valid test manifest with one release and a Windows installer.
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
                      "artifacts": [{
                        "platform": "windows-x86_64", "packageType": "setup-exe",
                        "source": "setup.exe", "size": 123, "sha256": "%s"
                      }]
                    }
                  ]
                }
                """.formatted(channel, version, "0".repeat(64)));
        return manifest;
    }

}

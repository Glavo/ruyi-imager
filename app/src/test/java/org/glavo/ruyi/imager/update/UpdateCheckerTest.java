// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.update;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
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
        Path manifest = writeManifest(temporaryDirectory, "stable", "1.1.0", 1L);

        UpdateCheckResult result = new UpdateChecker(new BuildInfo("1.0.0", 10L), manifest).check();

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
                      "buildNumber": 10,
                      "artifacts": []
                    },
                    {
                      "channel": "nightly",
                      "version": "1.2.0+nightly.test",
                      "buildNumber": 20,
                      "artifacts": []
                    }
                  ]
                }
                """);

        UpdateCheckResult result = new UpdateChecker(new BuildInfo("1.0.0", 0L), manifest)
                .check(UpdateChannel.NIGHTLY);

        assertEquals("1.2.0+nightly.test", result.available().version());
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
                      "buildNumber": 20,
                      "artifacts": []
                    },
                    {
                      "channel": "stable",
                      "version": "1.2.0",
                      "buildNumber": 10,
                      "artifacts": []
                    }
                  ]
                }
                """);

        UpdateCheckResult result = new UpdateChecker(new BuildInfo("1.0.0", 0L), manifest).check();

        assertEquals("1.2.0", result.available().version());
    }

    /// Rejects same-channel releases with equal SemVer precedence and build number.
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
                      "buildNumber": 10,
                      "artifacts": []
                    },
                    {
                      "channel": "stable",
                      "version": "1.1.0+second",
                      "buildNumber": 10,
                      "artifacts": []
                    }
                  ]
                }
                """);

        assertThrows(
                IOException.class,
                () -> new UpdateChecker(new BuildInfo("1.0.0", 0L), manifest).check());
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
                      "buildNumber": 1,
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

        UpdateArtifact artifact = Objects.requireNonNull(new UpdateChecker(new BuildInfo("1.0.0", 0L), manifest)
                .check()
                .available()
                .artifactFor(UpdatePlatform.WINDOWS_X86_64));

        assertEquals(UpdatePackageType.SETUP_EXE, artifact.packageType());
        assertEquals(123L, artifact.size());
    }

    /// Detects a newer nightly build when SemVer build metadata differs.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when the manifest cannot be written or checked.
    @Test
    public void detectsNewerBuildNumber(@TempDir Path temporaryDirectory) throws Exception {
        Path manifest = writeManifest(temporaryDirectory, "nightly", "1.0.0+nightly.new", 42L);

        UpdateCheckResult result = new UpdateChecker(
                new BuildInfo("1.0.0+nightly.old", 41L),
                manifest).check(UpdateChannel.NIGHTLY);

        assertEquals(UpdateCheckResult.Status.UPDATE_AVAILABLE, result.status());
    }

    /// Offers the stable build when leaving a same-version nightly channel.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when the manifest cannot be written or checked.
    @Test
    public void offersStableReleaseWhenLeavingNightly(@TempDir Path temporaryDirectory) throws Exception {
        Path manifest = writeManifest(temporaryDirectory, "stable", "1.0.0", 0L);

        UpdateCheckResult result = new UpdateChecker(
                new BuildInfo("1.0.0+nightly.old", 41L),
                manifest).check(UpdateChannel.STABLE);

        assertEquals(UpdateCheckResult.Status.UPDATE_AVAILABLE, result.status());
    }

    /// Does not offer an older manifest build.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when the manifest cannot be written or checked.
    @Test
    public void rejectsOlderBuild(@TempDir Path temporaryDirectory) throws Exception {
        Path manifest = writeManifest(temporaryDirectory, "stable", "1.0.0", 40L);

        UpdateCheckResult result = new UpdateChecker(new BuildInfo("1.0.0", 41L), manifest).check();

        assertEquals(UpdateCheckResult.Status.UP_TO_DATE, result.status());
    }

    /// Orders prerelease and release versions according to Semantic Versioning.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when the manifest cannot be written or checked.
    @Test
    public void releaseSupersedesPrerelease(@TempDir Path temporaryDirectory) throws Exception {
        Path manifest = writeManifest(temporaryDirectory, "stable", "1.0.0", 0L);

        UpdateCheckResult result = new UpdateChecker(new BuildInfo("1.0-SNAPSHOT", 0L), manifest).check();

        assertEquals(UpdateCheckResult.Status.UPDATE_AVAILABLE, result.status());
    }

    /// Accepts a valid detached Ed25519 signature.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when test keys or files cannot be created.
    @Test
    public void verifiesDetachedSignature(@TempDir Path temporaryDirectory) throws Exception {
        Path manifest = writeManifest(temporaryDirectory, "stable", "1.1.0", 1L);
        KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Path publicKey = temporaryDirectory.resolve("update-public-key.der");
        Files.write(publicKey, keyPair.getPublic().getEncoded());
        writeSignature(manifest, keyPair);

        UpdateCheckResult result = new UpdateChecker(
                new BuildInfo("1.0.0", 0L),
                manifest,
                publicKey,
                true).check();

        assertEquals(UpdateCheckResult.Status.UPDATE_AVAILABLE, result.status());
    }

    /// Rejects a manifest modified after it was signed.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when test keys or files cannot be created.
    @Test
    public void rejectsTamperedSignedManifest(@TempDir Path temporaryDirectory) throws Exception {
        Path manifest = writeManifest(temporaryDirectory, "stable", "1.1.0", 1L);
        KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Path publicKey = temporaryDirectory.resolve("update-public-key.der");
        Files.write(publicKey, keyPair.getPublic().getEncoded());
        writeSignature(manifest, keyPair);
        Files.writeString(manifest, Files.readString(manifest).replace("1.1.0", "1.2.0"));

        assertThrows(
                IOException.class,
                () -> new UpdateChecker(
                        new BuildInfo("1.0.0", 0L),
                        manifest,
                        publicKey,
                        true).check());
    }

    /// Rejects unsigned manifests when signature enforcement is enabled.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when the manifest cannot be written.
    @Test
    public void rejectsUnsignedManifestWhenRequired(@TempDir Path temporaryDirectory) throws Exception {
        Path manifest = writeManifest(temporaryDirectory, "stable", "1.1.0", 1L);

        assertThrows(
                IOException.class,
                () -> new UpdateChecker(new BuildInfo("1.0.0", 0L), manifest, null, true).check());
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
                () -> new UpdateChecker(new BuildInfo("1.0.0", 0L), manifest).check());
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
                () -> new UpdateChecker(new BuildInfo("1.0.0", 0L), manifest).check());

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
                      "buildNumber": 1,
                      "artifacts": [],
                      "unexpected": true
                    }
                  ]
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
        Path manifest = writeManifest(temporaryDirectory, "stable", "1.1.0-rc.01", 1L);

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

    /// Writes a valid test manifest with one release and no artifacts.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @param channel            available channel token.
    /// @param version            available version.
    /// @param buildNumber        available build number.
    /// @return manifest path.
    /// @throws IOException when the manifest cannot be written.
    private static Path writeManifest(
            Path temporaryDirectory,
            String channel,
            String version,
            long buildNumber) throws IOException {
        Path manifest = temporaryDirectory.resolve("update.json");
        Files.writeString(manifest, """
                {
                  "schemaVersion": 1,
                  "releases": [
                    {
                      "channel": "%s",
                  "version": "%s",
                  "buildNumber": %d,
                      "releaseNotes": "Test release",
                      "artifacts": []
                    }
                  ]
                }
                """.formatted(channel, version, buildNumber));
        return manifest;
    }

    /// Signs exact manifest bytes and writes the detached Base64 signature.
    ///
    /// @param manifest manifest path.
    /// @param keyPair  signing key pair.
    /// @throws Exception when signing or writing fails.
    private static void writeSignature(Path manifest, KeyPair keyPair) throws Exception {
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(keyPair.getPrivate());
        signature.update(Files.readAllBytes(manifest));
        Files.writeString(
                manifest.resolveSibling(manifest.getFileName() + ".sig"),
                Base64.getEncoder().encodeToString(signature.sign()));
    }
}

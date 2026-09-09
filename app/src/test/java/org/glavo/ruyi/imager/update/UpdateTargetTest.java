// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.update;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests requirement conjunction, fail-closed eligibility, version bounds, and installer preferences.
@NotNullByDefault
public final class UpdateTargetTest {
    /// Detects a real Windows build number and uses it when evaluating installer requirements.
    ///
    /// @throws IOException when native version detection fails.
    @Test
    @EnabledOnOs(OS.WINDOWS)
    public void detectsWindowsBuildNumber() throws IOException {
        String version = WindowsSystemVersion.read();
        assertTrue(version.matches("[0-9]+\\.[0-9]+\\.[1-9][0-9]*"));
        UpdateTarget target = UpdateTarget.current();
        assertEquals(version, target.systemVersion());
        assertTrue(new UpdateRequirements(null, version, true).matches(new BuildInfo("1.0.0"), target));
        String[] components = version.split("\\.");
        String futureBuild = components[0] + "." + components[1] + "." + (Long.parseLong(components[2]) + 1);
        assertFalse(new UpdateRequirements(null, futureBuild, true).matches(new BuildInfo("1.0.0"), target));
    }

    /// Requires both release-level application bounds and artifact-level OS bounds to match.
    @Test
    public void combinesReleaseAndArtifactRequirements() {
        UpdateArtifact artifact = artifact(UpdatePlatform.MACOS_AARCH64, UpdatePackageType.PKG,
                new UpdateRequirements(null, "13.2", true));
        UpdateRelease release = release(new UpdateRequirements("2.0", null, true), List.of(artifact));
        UpdateTarget compatible = macTarget("13.2", List.of(UpdatePackageType.PKG));

        assertSame(artifact, compatible.select(release, new BuildInfo("2.0")));
        assertNull(compatible.select(release, new BuildInfo("1.9")));
        assertNull(macTarget("13.1", List.of(UpdatePackageType.PKG)).select(release, new BuildInfo("2.0")));
        assertNull(macTarget("13.1", List.of(UpdatePackageType.PKG)).select(release, new BuildInfo("1.9")));
    }

    /// Prevents either requirement layer from weakening the other's bound on the same property.
    ///
    /// @param releaseApp release-level minimum application version.
    /// @param artifactApp artifact-level minimum application version.
    /// @param releaseOs release-level minimum OS version.
    /// @param artifactOs artifact-level minimum OS version.
    @ParameterizedTest
    @CsvSource({
            "2.0, 3.0, 13.0, 14.0",
            "3.0, 2.0, 14.0, 13.0",
            "2.0, 3.0, 14.0, 13.0",
            "3.0, 2.0, 13.0, 14.0"
    })
    public void retainsStricterBoundsFromBothLayers(String releaseApp, String artifactApp,
                                                   String releaseOs, String artifactOs) {
        UpdateArtifact artifact = artifact(UpdatePlatform.MACOS_AARCH64, UpdatePackageType.PKG,
                new UpdateRequirements(artifactApp, artifactOs, true));
        UpdateRelease release = release(new UpdateRequirements(releaseApp, releaseOs, true), List.of(artifact));

        assertNull(macTarget("14", List.of(UpdatePackageType.PKG)).select(release, new BuildInfo("2.9")));
        assertNull(macTarget("13.9", List.of(UpdatePackageType.PKG)).select(release, new BuildInfo("3")));
        assertSame(artifact, macTarget("14", List.of(UpdatePackageType.PKG)).select(release, new BuildInfo("3")));
    }

    /// Rejects unknown release requirements even when every known bound and artifact matches.
    @Test
    public void unknownReleaseRequirementsDisableAllArtifacts() {
        UpdateArtifact artifact = artifact(UpdatePlatform.MACOS_AARCH64, UpdatePackageType.PKG, UpdateRequirements.NONE);
        UpdateRelease release = release(new UpdateRequirements("1", "12", false), List.of(artifact));

        assertNull(macTarget("14", List.of(UpdatePackageType.PKG)).select(release, new BuildInfo("3")));
    }

    /// Skips an artifact with unknown requirements and uses a compatible lower-priority installer.
    @Test
    public void unknownArtifactRequirementsAllowOnlyKnownAlternatives() {
        UpdateArtifact unknown = artifact(UpdatePlatform.MACOS_AARCH64, UpdatePackageType.PKG,
                new UpdateRequirements(null, null, false));
        UpdateArtifact known = artifact(UpdatePlatform.MACOS_AARCH64, UpdatePackageType.DMG, UpdateRequirements.NONE);
        UpdateRelease release = release(UpdateRequirements.NONE, List.of(unknown, known));

        assertSame(known, macTarget("14", List.of(UpdatePackageType.PKG, UpdatePackageType.DMG))
                .select(release, new BuildInfo("3")));
        assertNull(macTarget("14", List.of(UpdatePackageType.PKG)).select(release, new BuildInfo("3")));
    }

    /// Makes unknown JSON requirement names fail closed at either the release or artifact layer.
    ///
    /// @param unknownAtRelease whether the unknown condition belongs to the release instead of the artifact.
    /// @throws IOException if the manifest cannot be parsed.
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void parsedUnknownRequirementsFailClosed(boolean unknownAtRelease) throws IOException {
        String known = "{\"minimumAppVersion\":\"1\",\"minimumSystemVersion\":\"12\"}";
        String unknown = "{\"minimumAppVersion\":\"1\",\"minimumSystemVersion\":\"12\",\"futureCondition\":false}";
        UpdateRelease release = parseRelease(unknownAtRelease ? unknown : known, unknownAtRelease ? known : unknown);

        assertFalse(unknownAtRelease ? release.requirements().understood()
                : release.artifacts().getFirst().requirements().understood());
        assertNull(macTarget("14", List.of(UpdatePackageType.PKG)).select(release, new BuildInfo("3")));
    }

    /// Applies both known JSON requirement layers after manifest parsing.
    ///
    /// @throws IOException if the manifest cannot be parsed.
    @Test
    public void parsedRequirementsRemainConjunctive() throws IOException {
        UpdateRelease release = parseRelease("{\"minimumAppVersion\":\"2\"}", "{\"minimumSystemVersion\":\"14\"}");

        assertNull(macTarget("14", List.of(UpdatePackageType.PKG)).select(release, new BuildInfo("1")));
        assertNull(macTarget("13", List.of(UpdatePackageType.PKG)).select(release, new BuildInfo("2")));
        assertSame(release.artifacts().getFirst(), macTarget("14", List.of(UpdatePackageType.PKG))
                .select(release, new BuildInfo("2")));
    }

    /// Compares OS components numerically, pads omitted components, and ignores distribution suffixes.
    ///
    /// @param actual installed OS version.
    /// @param minimum required OS version.
    /// @param expected whether the version permits selecting the artifact.
    @ParameterizedTest
    @CsvSource({
            "10.10, 10.9, true",
            "10.9, 10.10, false",
            "10.0.22000, 10.0.19045, true",
            "10.0.19045, 10.0.22000, false",
            "14, 14.0.0.0, true",
            "14.0.0.0, 14, true",
            "14, 14.0.0.1, false",
            "14.0.0.1, 14, true",
            "6.10.0-rc1, 6.9.12, true",
            "6.8.0-51-generic, 6.8.0, true",
            "6.8.0-51-generic, 6.8.1, false",
            "6.8.0+vendor, 6.8, true",
            "0014.002, 14.2.0, true",
            "9999999999, 2147483648, true",
            "2147483648, 9999999999, false"
    })
    public void comparesNumericSystemVersions(String actual, String minimum, boolean expected) {
        UpdateArtifact artifact = artifact(UpdatePlatform.MACOS_AARCH64, UpdatePackageType.PKG,
                new UpdateRequirements(null, minimum, true));
        UpdateRelease release = release(UpdateRequirements.NONE, List.of(artifact));

        assertEquals(expected, macTarget(actual, List.of(UpdatePackageType.PKG)).select(release, new BuildInfo("3")) != null);
    }

    /// Fails closed for unreadable required OS versions while allowing them when no OS minimum exists.
    ///
    /// @param actual unreadable installed OS version.
    @ParameterizedTest
    @ValueSource(strings = {"", "unknown", "v14.0", " 14.0", "14..0", "14.", "1.2.3.4.5", "10000000000", "-1"})
    public void unreadableSystemVersionsFailOnlyRequiredChecks(String actual) {
        UpdateArtifact restricted = artifact(UpdatePlatform.MACOS_AARCH64, UpdatePackageType.PKG,
                new UpdateRequirements(null, "1", true));
        UpdateArtifact unrestricted = artifact(UpdatePlatform.MACOS_AARCH64, UpdatePackageType.PKG, UpdateRequirements.NONE);
        UpdateTarget target = macTarget(actual, List.of(UpdatePackageType.PKG));

        assertNull(target.select(release(UpdateRequirements.NONE, List.of(restricted)), new BuildInfo("3")));
        assertSame(unrestricted, target.select(release(UpdateRequirements.NONE, List.of(unrestricted)), new BuildInfo("3")));
    }

    /// Validates malformed known minimum OS values even when unknown conditions are present.
    ///
    /// @param minimum malformed minimum OS version.
    @ParameterizedTest
    @ValueSource(strings = {"", "v14", "14-rc1", "14.", "1.2.3.4.5", "10000000000", "-1"})
    public void rejectsInvalidMinimumSystemVersions(String minimum) {
        assertThrows(IllegalArgumentException.class, () -> new UpdateRequirements(null, minimum, true));
        assertThrows(IllegalArgumentException.class, () -> new UpdateRequirements(null, minimum, false));
    }

    /// Validates known application minimums even when the containing requirements are not understood.
    ///
    /// @param minimum malformed minimum application version.
    @ParameterizedTest
    @ValueSource(strings = {"", "latest", "1..0", "4294967296"})
    public void rejectsInvalidMinimumApplicationVersions(String minimum) {
        assertThrows(IllegalArgumentException.class, () -> new UpdateRequirements(minimum, null, true));
        assertThrows(IllegalArgumentException.class, () -> new UpdateRequirements(minimum, null, false));
    }

    /// Applies application-version precedence rather than lexical order or release version identity.
    ///
    /// @param installed installed application version.
    /// @param minimum required installed application version.
    /// @param expected whether the version permits selecting the artifact.
    @ParameterizedTest
    @CsvSource({
            "2.10, 2.9, true",
            "2.9, 2.10, false",
            "3.0-rc.1, 3.0, false",
            "3.0, 3.0-rc.1, true",
            "3.0+installed, 3.0+required, true",
            "3.0-rc.10, 3.0-rc.2, true"
    })
    public void comparesInstalledApplicationVersions(String installed, String minimum, boolean expected) {
        UpdateArtifact artifact = artifact(UpdatePlatform.MACOS_AARCH64, UpdatePackageType.PKG, UpdateRequirements.NONE);
        UpdateRelease release = release(new UpdateRequirements(minimum, null, true), List.of(artifact));

        assertEquals(expected, macTarget("14", List.of(UpdatePackageType.PKG)).select(release, new BuildInfo(installed)) != null);
    }

    /// Chooses the target's preferred package type independently of the manifest's artifact ordering.
    @Test
    public void honorsPackagePreferencesInsteadOfManifestOrder() {
        UpdateArtifact pkg = artifact(UpdatePlatform.MACOS_AARCH64, UpdatePackageType.PKG, UpdateRequirements.NONE);
        UpdateArtifact dmg = artifact(UpdatePlatform.MACOS_AARCH64, UpdatePackageType.DMG, UpdateRequirements.NONE);
        UpdateTarget preferPkg = macTarget("14", List.of(UpdatePackageType.PKG, UpdatePackageType.DMG));
        UpdateTarget preferDmg = macTarget("14", List.of(UpdatePackageType.DMG, UpdatePackageType.PKG));
        BuildInfo current = new BuildInfo("3");

        for (List<UpdateArtifact> artifacts : List.of(List.of(pkg, dmg), List.of(dmg, pkg))) {
            UpdateRelease release = release(UpdateRequirements.NONE, artifacts);
            assertSame(pkg, preferPkg.select(release, current));
            assertSame(dmg, preferDmg.select(release, current));
        }
    }

    /// Falls back when a preferred installer fails its known requirements without enabling unlisted types.
    @Test
    public void fallsBackOnlyToListedCompatibleTypes() {
        UpdateArtifact pkg = artifact(UpdatePlatform.MACOS_AARCH64, UpdatePackageType.PKG,
                new UpdateRequirements(null, "15", true));
        UpdateArtifact dmg = artifact(UpdatePlatform.MACOS_AARCH64, UpdatePackageType.DMG, UpdateRequirements.NONE);
        UpdateRelease release = release(UpdateRequirements.NONE, List.of(pkg, dmg));
        BuildInfo current = new BuildInfo("3");

        assertSame(dmg, macTarget("14", List.of(UpdatePackageType.PKG, UpdatePackageType.DMG)).select(release, current));
        assertNull(macTarget("14", List.of(UpdatePackageType.PKG)).select(release, current));
        assertNull(macTarget("14", List.of()).select(release, current));
        assertSame(dmg, macTarget("14", List.of(UpdatePackageType.PKG, UpdatePackageType.DMG))
                .select(release(UpdateRequirements.NONE, List.of(dmg)), current));
    }

    /// Rejects artifacts for other architectures and operating systems before applying type preferences.
    @Test
    public void requiresExactPlatformMatch() {
        UpdateArtifact intel = artifact(UpdatePlatform.MACOS_X86_64, UpdatePackageType.PKG, UpdateRequirements.NONE);
        UpdateArtifact windows = artifact(UpdatePlatform.WINDOWS_AARCH64, UpdatePackageType.SETUP_EXE, UpdateRequirements.NONE);
        UpdateArtifact arm = artifact(UpdatePlatform.MACOS_AARCH64, UpdatePackageType.DMG, UpdateRequirements.NONE);
        UpdateTarget target = macTarget("14", List.of(UpdatePackageType.PKG, UpdatePackageType.DMG));
        BuildInfo current = new BuildInfo("3");

        assertSame(arm, target.select(release(UpdateRequirements.NONE, List.of(intel, windows, arm)), current));
        assertNull(target.select(release(UpdateRequirements.NONE, List.of(intel, windows)), current));
        assertNull(target.select(release(UpdateRequirements.NONE, List.of()), current));
    }

    /// Snapshots caller-owned preferences and rejects duplicate or platform-incompatible types.
    @Test
    public void validatesAndSnapshotsPackagePreferences() {
        List<UpdatePackageType> preferences = new ArrayList<>(List.of(UpdatePackageType.PKG, UpdatePackageType.DMG));
        UpdateTarget target = macTarget("14", preferences);
        preferences.clear();

        assertEquals(List.of(UpdatePackageType.PKG, UpdatePackageType.DMG), target.packageTypes());
        assertThrows(UnsupportedOperationException.class, () -> target.packageTypes().clear());
        assertThrows(IllegalArgumentException.class,
                () -> macTarget("14", List.of(UpdatePackageType.PKG, UpdatePackageType.PKG)));
        assertThrows(IllegalArgumentException.class, () -> macTarget("14", List.of(UpdatePackageType.DEB)));
        assertThrows(IllegalArgumentException.class, () -> new UpdateTarget(UpdatePlatform.WINDOWS_X86_64,
                "10", List.of(UpdatePackageType.PKG)));
    }

    /// Creates a macOS Arm target without consulting the host's operating system or package tools.
    ///
    /// @param version installed OS version.
    /// @param types installer types in preference order.
    /// @return deterministic installation target.
    private static UpdateTarget macTarget(String version, List<UpdatePackageType> types) {
        return new UpdateTarget(UpdatePlatform.MACOS_AARCH64, version, types);
    }

    /// Creates a structurally valid artifact without reading or verifying any package file.
    ///
    /// @param platform artifact runtime platform.
    /// @param type installer package type.
    /// @param requirements artifact-specific conditions.
    /// @return artifact with dummy size and digest metadata.
    private static UpdateArtifact artifact(UpdatePlatform platform, UpdatePackageType type, UpdateRequirements requirements) {
        return new UpdateArtifact(platform, type, "package" + type.fileSuffix(), 1, "0".repeat(64), requirements);
    }

    /// Creates a stable candidate with explicit release-level requirements.
    ///
    /// @param requirements conditions shared by all installers.
    /// @param artifacts available installer variants.
    /// @return candidate release.
    private static UpdateRelease release(UpdateRequirements requirements, List<UpdateArtifact> artifacts) {
        return new UpdateRelease(UpdateChannel.STABLE, "20.0", artifacts, requirements);
    }

    /// Parses one installer and both requirement layers through the production manifest parser.
    ///
    /// @param releaseRequirements JSON object containing release conditions.
    /// @param artifactRequirements JSON object containing artifact conditions.
    /// @return parsed candidate release.
    /// @throws IOException if the fixture JSON cannot be parsed.
    private static UpdateRelease parseRelease(String releaseRequirements, String artifactRequirements) throws IOException {
        String json = """
                {
                  "schemaVersion": 1,
                  "releases": [{
                    "channel": "stable",
                    "version": "20.0",
                    "requirements": %s,
                    "artifacts": [{
                      "platform": "macos-aarch64",
                      "packageType": "pkg",
                      "source": "package.pkg",
                      "size": 1,
                      "sha256": "%s",
                      "requirements": %s
                    }]
                  }]
                }
                """.formatted(releaseRequirements, "0".repeat(64), artifactRequirements);
        return UpdateChecker.readManifest(json.getBytes(StandardCharsets.UTF_8)).releases().getFirst();
    }
}

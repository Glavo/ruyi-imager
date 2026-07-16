// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.update;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the Ruyi Imager application version policy.
@NotNullByDefault
public final class ApplicationVersionTest {
    /// Parses every supported release stage.
    @Test
    public void parsesSupportedVersions() {
        ApplicationVersion stable = ApplicationVersion.parse("1.2.3");
        ApplicationVersion development = ApplicationVersion.parse("1.2.3-dev");
        ApplicationVersion nightly = ApplicationVersion.parse(
                "1.2.3-nightly.20260716T143052Z.3921d84");

        assertEquals(ApplicationVersion.Stage.STABLE, stable.stage());
        assertEquals(ApplicationVersion.Stage.DEVELOPMENT, development.stage());
        assertEquals(ApplicationVersion.Stage.NIGHTLY, nightly.stage());
        assertEquals(Instant.parse("2026-07-16T14:30:52Z"), nightly.builtAt());
        assertEquals("3921d84", nightly.commit());
    }

    /// Orders numeric versions before considering release stages.
    @Test
    public void ordersNumericVersions() {
        assertTrue(ApplicationVersion.parse("1.2.3").compareTo(ApplicationVersion.parse("1.2.4-dev")) < 0);
        assertTrue(ApplicationVersion.parse("1.2.9").compareTo(ApplicationVersion.parse("1.3.0-dev")) < 0);
        assertTrue(ApplicationVersion.parse("1.9.9").compareTo(ApplicationVersion.parse("2.0.0-dev")) < 0);
    }

    /// Orders development, nightly, and stable stages within one numeric version.
    @Test
    public void ordersReleaseStages() {
        ApplicationVersion development = ApplicationVersion.parse("1.2.3-dev");
        ApplicationVersion nightly = ApplicationVersion.parse(
                "1.2.3-nightly.20260716T143052Z.3921d84");
        ApplicationVersion stable = ApplicationVersion.parse("1.2.3");

        assertTrue(development.compareTo(nightly) < 0);
        assertTrue(nightly.compareTo(stable) < 0);
    }

    /// Orders nightly builds by UTC build time and then commit identity.
    @Test
    public void ordersNightlyBuilds() {
        ApplicationVersion earlier = ApplicationVersion.parse(
                "1.2.3-nightly.20260716T143051Z.fffffff");
        ApplicationVersion later = ApplicationVersion.parse(
                "1.2.3-nightly.20260716T143052Z.0000000");
        ApplicationVersion sameTimeLaterCommit = ApplicationVersion.parse(
                "1.2.3-nightly.20260716T143052Z.0000001");

        assertTrue(earlier.compareTo(later) < 0);
        assertTrue(later.compareTo(sameTimeLaterCommit) < 0);
    }

    /// Rejects syntax not defined by the application release policy.
    @Test
    public void rejectsUnsupportedSyntax() {
        assertThrows(IllegalArgumentException.class, () -> ApplicationVersion.parse("1.2"));
        assertThrows(IllegalArgumentException.class, () -> ApplicationVersion.parse("01.2.3"));
        assertThrows(IllegalArgumentException.class, () -> ApplicationVersion.parse(" 1.2.3"));
        assertThrows(IllegalArgumentException.class, () -> ApplicationVersion.parse("1.2.3-SNAPSHOT"));
        assertThrows(IllegalArgumentException.class, () -> ApplicationVersion.parse("1.2.3-alpha"));
        assertThrows(IllegalArgumentException.class, () -> ApplicationVersion.parse("1.2.3+git.3921d84"));
        assertThrows(
                IllegalArgumentException.class,
                () -> ApplicationVersion.parse("1.2.3-nightly.20260230T143052Z.3921d84"));
        assertThrows(
                IllegalArgumentException.class,
                () -> ApplicationVersion.parse("1.2.3-nightly.20260716T143052Z.3921D84"));
        assertThrows(
                IllegalArgumentException.class,
                () -> ApplicationVersion.parse("1.2.3-nightly.20260716T143052Z.3921d8"));
    }

    /// Rejects release versions assigned to a different update channel.
    @Test
    public void rejectsReleaseChannelMismatch() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new UpdateRelease(UpdateChannel.STABLE,
                        "1.2.3-nightly.20260716T143052Z.3921d84",
                        null,
                        java.util.List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new UpdateRelease(UpdateChannel.NIGHTLY, "1.2.3", null, java.util.List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new UpdateRelease(UpdateChannel.STABLE, "1.2.3-dev", null, java.util.List.of()));
    }
}

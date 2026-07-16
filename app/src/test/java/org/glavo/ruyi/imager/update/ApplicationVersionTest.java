// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.update;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the Ruyi Imager application version ordering envelope.
@NotNullByDefault
public final class ApplicationVersionTest {
    /// Parses unsuffixed and opaque suffixed versions.
    @Test
    public void parsesSupportedEnvelope() {
        ApplicationVersion stable = ApplicationVersion.parse("1.2.3");
        ApplicationVersion development = ApplicationVersion.parse("1.2.3-dev");
        ApplicationVersion nightly = ApplicationVersion.parse(
                "1.2.3-nightly.20260716T143052Z.3921d84");
        ApplicationVersion future = ApplicationVersion.parse("1.2.3-preview_2027+build.4");
        ApplicationVersion releaseCandidate = ApplicationVersion.parse("1.2.3-rc.01");

        assertNull(stable.suffix());
        assertEquals("dev", development.suffix());
        assertEquals("nightly.20260716T143052Z.3921d84", nightly.suffix());
        assertEquals("preview_2027+build.4", future.suffix());
        assertEquals("rc.01", releaseCandidate.suffix());
    }

    /// Orders numeric versions before considering suffixes.
    @Test
    public void ordersNumericVersions() {
        assertTrue(ApplicationVersion.parse("1.2.3").compareTo(ApplicationVersion.parse("1.2.4-dev")) < 0);
        assertTrue(ApplicationVersion.parse("1.2.9").compareTo(ApplicationVersion.parse("1.3.0-dev")) < 0);
        assertTrue(ApplicationVersion.parse("1.9.9").compareTo(ApplicationVersion.parse("2.0.0-dev")) < 0);
    }

    /// Orders opaque suffixes lexically and keeps unsuffixed versions last.
    @Test
    public void ordersOpaqueSuffixes() {
        ApplicationVersion development = ApplicationVersion.parse("1.2.3-dev");
        ApplicationVersion earlierNightly = ApplicationVersion.parse(
                "1.2.3-nightly.20260716T143051Z.fffffff");
        ApplicationVersion laterNightly = ApplicationVersion.parse(
                "1.2.3-nightly.20260716T143052Z.0000000");
        ApplicationVersion stable = ApplicationVersion.parse("1.2.3");

        assertTrue(development.compareTo(earlierNightly) < 0);
        assertTrue(earlierNightly.compareTo(laterNightly) < 0);
        assertTrue(laterNightly.compareTo(stable) < 0);
    }

    /// Rejects malformed numeric envelopes and unsafe suffix characters.
    @Test
    public void rejectsMalformedEnvelope() {
        assertThrows(IllegalArgumentException.class, () -> ApplicationVersion.parse("1.2"));
        assertThrows(IllegalArgumentException.class, () -> ApplicationVersion.parse("01.2.3"));
        assertThrows(IllegalArgumentException.class, () -> ApplicationVersion.parse(" 1.2.3"));
        assertThrows(IllegalArgumentException.class, () -> ApplicationVersion.parse("1.2.3-"));
        assertThrows(IllegalArgumentException.class, () -> ApplicationVersion.parse("1.2.3-preview/4"));
        assertThrows(IllegalArgumentException.class, () -> ApplicationVersion.parse("1.2.3-preview 4"));
    }

    /// Uses only the known nightly prefix when inferring a default channel.
    @Test
    public void infersKnownChannelConvention() {
        assertEquals(UpdateChannel.STABLE, ApplicationVersion.parse("1.2.3").inferredChannel());
        assertEquals(UpdateChannel.STABLE, ApplicationVersion.parse("1.2.3-dev").inferredChannel());
        assertEquals(UpdateChannel.STABLE, ApplicationVersion.parse("1.2.3-preview.4").inferredChannel());
        assertEquals(
                UpdateChannel.NIGHTLY,
                ApplicationVersion.parse("1.2.3-nightly.future-format").inferredChannel());
    }

    /// Keeps update channel metadata independent from version suffix conventions.
    @Test
    public void acceptsChannelIndependentSuffixes() {
        new UpdateRelease(UpdateChannel.STABLE, "1.2.3-preview.4", null, java.util.List.of());
        new UpdateRelease(UpdateChannel.NIGHTLY, "1.2.3-build.2027", null, java.util.List.of());
    }
}

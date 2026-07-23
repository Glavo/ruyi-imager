// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.update;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests WiX Burn-compatible application version parsing and ordering.
@NotNullByDefault
public final class ApplicationVersionTest {
    /// Parses one to four numeric components and optional version decorations.
    @Test
    public void parsesSupportedBurnVersions() {
        ApplicationVersion abbreviated = ApplicationVersion.parse("1");
        ApplicationVersion complete = ApplicationVersion.parse("v1.2.3.004-2.a.22+abcdef");
        ApplicationVersion maximum = ApplicationVersion.parse(
                "4294967295.4294967295.4294967295.4294967295");

        assertEquals(1L, abbreviated.major());
        assertEquals(0L, abbreviated.minor());
        assertEquals(0L, abbreviated.patch());
        assertEquals(0L, abbreviated.revision());
        assertNull(abbreviated.prerelease());
        assertNull(abbreviated.buildMetadata());

        assertEquals(1L, complete.major());
        assertEquals(2L, complete.minor());
        assertEquals(3L, complete.patch());
        assertEquals(4L, complete.revision());
        assertEquals("2.a.22", complete.prerelease());
        assertEquals("abcdef", complete.buildMetadata());
        assertEquals(0xffff_ffffL, maximum.revision());
    }

    /// Treats omitted components as zero and compares the optional revision component.
    @Test
    public void ordersNumericComponents() {
        assertEquals(0, ApplicationVersion.parse("1").compareTo(ApplicationVersion.parse("1.0.0.0")));
        assertEquals(0, ApplicationVersion.parse("0.02.3").compareTo(ApplicationVersion.parse("0.2.3.0")));
        assertTrue(ApplicationVersion.parse("1.2.3.4").compareTo(ApplicationVersion.parse("1.2.3")) > 0);
        assertTrue(ApplicationVersion.parse("1.2.3").compareTo(ApplicationVersion.parse("1.2.4-dev")) < 0);
        assertTrue(ApplicationVersion.parse("1.9.9").compareTo(ApplicationVersion.parse("2-dev")) < 0);
    }

    /// Applies Burn prerelease identifier precedence.
    @Test
    public void ordersPrereleaseIdentifiers() {
        assertTrue(ApplicationVersion.parse("1.0-rc.2").compareTo(ApplicationVersion.parse("1.0-rc.10")) < 0);
        assertTrue(ApplicationVersion.parse("1.0-2").compareTo(ApplicationVersion.parse("1.0-rc")) < 0);
        assertTrue(ApplicationVersion.parse("1.0-rc").compareTo(ApplicationVersion.parse("1.0-rc.1")) < 0);
        assertTrue(ApplicationVersion.parse("1.0-rc.10").compareTo(ApplicationVersion.parse("1.0")) < 0);
        assertTrue(ApplicationVersion.parse("1.0-2.0").compareTo(ApplicationVersion.parse("1.0-1.19")) > 0);
        assertTrue(ApplicationVersion.parse("1.0-2.0").compareTo(ApplicationVersion.parse("1.0-19")) < 0);
        assertEquals(0, ApplicationVersion.parse("1.0-RC.2").compareTo(ApplicationVersion.parse("1.0-rc.2")));
        assertEquals(0, ApplicationVersion.parse("1.0-rc.01").compareTo(ApplicationVersion.parse("1.0-rc.1")));
        assertEquals(
                0,
                ApplicationVersion.parse("1.0-0000000000000000000000001")
                        .compareTo(ApplicationVersion.parse("1.0-1")));
        assertEquals(
                0,
                ApplicationVersion.parse("0.1-a.b.0").compareTo(ApplicationVersion.parse("0.1.0-a.b.000")));
    }

    /// Treats digit-only prerelease identifiers above the unsigned 32-bit range as text like Burn.
    @Test
    public void ordersOverflowingPrereleaseIdentifiersAsText() {
        assertTrue(
                ApplicationVersion.parse("1-4294967295")
                        .compareTo(ApplicationVersion.parse("1-4294967296")) < 0);
        assertTrue(
                ApplicationVersion.parse("1-10000000000")
                        .compareTo(ApplicationVersion.parse("1-9999999999")) < 0);
    }

    /// Ignores build metadata when determining precedence.
    @Test
    public void ignoresBuildMetadataForOrdering() {
        ApplicationVersion first = ApplicationVersion.parse("1.2.3-rc.1+build.1");
        ApplicationVersion second = ApplicationVersion.parse("1.2.3-RC.01+build_2");

        assertEquals(
                0,
                ApplicationVersion.parse("1.2.3+abc").compareTo(ApplicationVersion.parse("1.2.3+xyz")));
        assertEquals(0, first.compareTo(second));
        assertNotEquals(first, second);
    }

    /// Rejects syntax outside the strict, reliably comparable Burn subset.
    @Test
    public void rejectsUnsupportedBurnVersions() {
        ApplicationVersion.parse("4294967295.4294967295.4294967295.4294967295");
        assertThrows(IllegalArgumentException.class, () -> ApplicationVersion.parse(""));
        assertThrows(IllegalArgumentException.class, () -> ApplicationVersion.parse("1."));
        assertThrows(IllegalArgumentException.class, () -> ApplicationVersion.parse("1.2.3.4.5"));
        assertThrows(IllegalArgumentException.class, () -> ApplicationVersion.parse("4294967296"));
        assertThrows(IllegalArgumentException.class, () -> ApplicationVersion.parse("1.2.3-"));
        assertThrows(IllegalArgumentException.class, () -> ApplicationVersion.parse("1.2.3-rc_1"));
        assertThrows(IllegalArgumentException.class, () -> ApplicationVersion.parse("1.2.3-rc."));
        assertThrows(IllegalArgumentException.class, () -> ApplicationVersion.parse("1.2.3+"));
        assertThrows(IllegalArgumentException.class, () -> ApplicationVersion.parse("1.2.3+build/1"));
        assertThrows(IllegalArgumentException.class, () -> ApplicationVersion.parse(" 1.2.3"));
    }

    /// Uses only the first prerelease identifier when inferring the default channel.
    @Test
    public void infersKnownChannelConvention() {
        assertEquals(UpdateChannel.STABLE, ApplicationVersion.parse("1.2.3").inferredChannel());
        assertEquals(UpdateChannel.STABLE, ApplicationVersion.parse("1.2.3-dev").inferredChannel());
        assertEquals(UpdateChannel.STABLE, ApplicationVersion.parse("1.2.3+nightly").inferredChannel());
        assertEquals(
                UpdateChannel.NIGHTLY,
                ApplicationVersion.parse("1.2.3-NIGHTLY.20260716T143052Z.3921d84").inferredChannel());
    }

    /// Keeps update channel metadata independent from prerelease conventions.
    @Test
    public void acceptsChannelIndependentPrereleases() {
        new UpdateRelease(UpdateChannel.STABLE, "1.2.3-preview.4", null, java.util.List.of());
        new UpdateRelease(UpdateChannel.NIGHTLY, "1.2.3-build.2027", null, java.util.List.of());
    }
}

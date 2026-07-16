// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.update;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/// Tests generated application build information.
@NotNullByDefault
public final class BuildInfoTest {
    /// Verifies that Gradle provides valid runtime build information.
    @Test
    public void loadsGeneratedBuildInfo() {
        BuildInfo buildInfo = BuildInfo.current();

        assertFalse(buildInfo.version().isBlank());
    }

    /// Infers stable and nightly channels from packaged version metadata.
    @Test
    public void infersBuildChannel() {
        assertEquals(UpdateChannel.STABLE, new BuildInfo("1.0.0").inferredChannel());
        assertEquals(UpdateChannel.STABLE, new BuildInfo("1.0.0-dev").inferredChannel());
        assertEquals(
                UpdateChannel.NIGHTLY,
                new BuildInfo("1.0.0-nightly.20260716T143052Z.3921d84").inferredChannel());
    }
}

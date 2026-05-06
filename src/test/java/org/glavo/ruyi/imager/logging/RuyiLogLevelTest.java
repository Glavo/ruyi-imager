// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.logging;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/// Tests stable log-level parsing.
@NotNullByDefault
public final class RuyiLogLevelTest {
    /// Parses supported log-level spellings.
    @Test
    public void parsesSupportedLevels() {
        assertEquals(RuyiLogLevel.OFF, RuyiLogLevel.parse("off"));
        assertEquals(RuyiLogLevel.ERROR, RuyiLogLevel.parse("ERROR"));
        assertEquals(RuyiLogLevel.WARN, RuyiLogLevel.parse("warn"));
        assertEquals(RuyiLogLevel.INFO, RuyiLogLevel.parse("info"));
        assertEquals(RuyiLogLevel.DEBUG, RuyiLogLevel.parse("debug"));
        assertEquals(RuyiLogLevel.TRACE, RuyiLogLevel.parse("trace"));
    }

    /// Rejects absent or unsupported log-level values.
    @Test
    public void rejectsUnsupportedLevels() {
        assertNull(RuyiLogLevel.parse(null));
        assertNull(RuyiLogLevel.parse(""));
        assertNull(RuyiLogLevel.parse("verbose"));
    }
}

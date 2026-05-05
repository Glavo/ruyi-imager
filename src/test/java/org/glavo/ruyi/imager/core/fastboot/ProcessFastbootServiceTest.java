// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.fastboot;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Tests for process-backed fastboot helpers.
@NotNullByDefault
public final class ProcessFastbootServiceTest {
    /// Parses standard fastboot device output.
    @Test
    public void parsesFastbootDevicesOutput() {
        List<FastbootDevice> devices = ProcessFastbootService.parseDevices("""
                abc123\tfastboot
                def456 fastbootd
                """);

        assertEquals(2, devices.size());
        assertEquals("abc123", devices.get(0).serial());
        assertEquals("fastboot", devices.get(0).state());
        assertEquals("def456", devices.get(1).serial());
        assertEquals("fastbootd", devices.get(1).state());
    }
}

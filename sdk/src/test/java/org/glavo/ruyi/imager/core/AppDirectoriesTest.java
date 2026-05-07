// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for application directory platform decisions.
@NotNullByDefault
public final class AppDirectoriesTest {
    /// Verifies Windows detection does not classify Darwin as Windows.
    @Test
    public void detectsWindowsByPrefixOnly() {
        assertTrue(AppDirectories.isWindows("Windows 11"));
        assertFalse(AppDirectories.isWindows("Darwin"));
        assertFalse(AppDirectories.isWindows("Mac OS X"));
        assertFalse(AppDirectories.isWindows("Linux"));
    }
}

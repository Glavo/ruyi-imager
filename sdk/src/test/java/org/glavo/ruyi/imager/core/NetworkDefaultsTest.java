// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Tests for application network defaults.
@NotNullByDefault
public final class NetworkDefaultsTest {
    /// Verifies system proxy discovery is enabled when the user did not configure it.
    @Test
    public void enablesSystemProxiesByDefault() {
        @Nullable String original = System.getProperty(NetworkDefaults.USE_SYSTEM_PROXIES_PROPERTY);
        try {
            System.clearProperty(NetworkDefaults.USE_SYSTEM_PROXIES_PROPERTY);
            NetworkDefaults.enableSystemProxiesByDefault();
            assertEquals("true", System.getProperty(NetworkDefaults.USE_SYSTEM_PROXIES_PROPERTY));
        } finally {
            restore(original);
        }
    }

    /// Verifies explicit user proxy configuration is preserved.
    @Test
    public void preservesExplicitSystemProxySetting() {
        @Nullable String original = System.getProperty(NetworkDefaults.USE_SYSTEM_PROXIES_PROPERTY);
        try {
            System.setProperty(NetworkDefaults.USE_SYSTEM_PROXIES_PROPERTY, "false");
            NetworkDefaults.enableSystemProxiesByDefault();
            assertEquals("false", System.getProperty(NetworkDefaults.USE_SYSTEM_PROXIES_PROPERTY));
        } finally {
            restore(original);
        }
    }

    /// Restores the original JVM property value.
    ///
    /// @param original original property value.
    private static void restore(@Nullable String original) {
        if (original == null) {
            System.clearProperty(NetworkDefaults.USE_SYSTEM_PROXIES_PROPERTY);
        } else {
            System.setProperty(NetworkDefaults.USE_SYSTEM_PROXIES_PROPERTY, original);
        }
    }
}

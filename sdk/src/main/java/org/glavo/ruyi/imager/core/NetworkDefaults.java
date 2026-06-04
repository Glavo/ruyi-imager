// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.net.ProxySelector;

/// Configures process-wide network defaults used by repository and distfile downloads.
@NotNullByDefault
public final class NetworkDefaults {
    /// JVM property that enables host operating system proxy discovery.
    public static final String USE_SYSTEM_PROXIES_PROPERTY = "java.net.useSystemProxies";

    /// Prevents construction.
    private NetworkDefaults() {
    }

    /// Enables system proxy discovery unless the user configured the JVM property explicitly.
    public static void enableSystemProxiesByDefault() {
        if (System.getProperty(USE_SYSTEM_PROXIES_PROPERTY) == null) {
            System.setProperty(USE_SYSTEM_PROXIES_PROPERTY, "true");
        }
    }

    /// Returns the default proxy selector after applying application network defaults.
    ///
    /// @return default proxy selector, or null when the JVM has none.
    public static @Nullable ProxySelector proxySelector() {
        enableSystemProxiesByDefault();
        return ProxySelector.getDefault();
    }
}

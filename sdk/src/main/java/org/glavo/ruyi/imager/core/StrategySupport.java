// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core;

import org.jetbrains.annotations.NotNullByDefault;

/// Describes whether an image provisioning strategy can be executed.
@NotNullByDefault
public enum StrategySupport {
    /// The strategy can be executed by the current application.
    SUPPORTED,

    /// The strategy is known but is not implemented yet.
    UNSUPPORTED,

    /// The strategy was not recognized.
    UNKNOWN
}

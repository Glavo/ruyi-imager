// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core;

import org.jetbrains.annotations.NotNullByDefault;

/// Receives progress events from long-running services.
@FunctionalInterface
@NotNullByDefault
public interface ProgressReporter {
    /// Reports one progress event.
    ///
    /// @param event progress event.
    void report(ProgressEvent event);
}

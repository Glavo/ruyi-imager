// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.dd;

import org.jetbrains.annotations.NotNullByDefault;

/// Receives progress snapshots from a dd-style raw image operation.
@FunctionalInterface
@NotNullByDefault
public interface DdProgressReporter {
    /// Returns a reporter that ignores every progress event.
    ///
    /// @return no-op reporter.
    static DdProgressReporter none() {
        return _ -> {
        };
    }

    /// Reports one progress event.
    ///
    /// @param event progress event.
    void report(DdProgressEvent event);
}

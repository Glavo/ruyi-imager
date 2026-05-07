// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.dd;

import org.jetbrains.annotations.NotNullByDefault;

/// Operation phase emitted by a dd-style raw image writer.
@NotNullByDefault
public enum DdOperation {
    /// Source bytes are being written to the target.
    WRITE,

    /// Target bytes are being compared against the source.
    VERIFY
}

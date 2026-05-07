// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.dd;

import org.jetbrains.annotations.NotNullByDefault;

/// Progress snapshot emitted by a dd-style raw image operation.
///
/// @param operation operation phase.
/// @param currentBytes bytes processed so far.
/// @param totalBytes total bytes expected for the operation.
@NotNullByDefault
public record DdProgressEvent(
        DdOperation operation,
        long currentBytes,
        long totalBytes) {
}

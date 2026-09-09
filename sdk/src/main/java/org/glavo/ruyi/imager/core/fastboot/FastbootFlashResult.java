// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.fastboot;

import org.glavo.ruyi.imager.core.OperationResult;
import org.jetbrains.annotations.NotNullByDefault;

/// Reports a flashing outcome and the last resolved target identity.
///
/// @param result operation outcome; a failed operation must not be followed by another component.
/// @param device target to use for the next component after success, including any handoff serial change.
@NotNullByDefault
public record FastbootFlashResult(OperationResult result, FastbootDevice device) {
}

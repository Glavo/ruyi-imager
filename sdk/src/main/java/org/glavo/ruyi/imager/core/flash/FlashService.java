// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.flash;

import org.glavo.ruyi.imager.core.OperationResult;
import org.glavo.ruyi.imager.core.ProgressReporter;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;

/// Writes images to target devices.
@NotNullByDefault
public interface FlashService {
    /// Executes a flash request.
    ///
    /// @param request flash request.
    /// @param reporter progress reporter.
    /// @return operation result.
    /// @throws IOException when the flash backend cannot access required files or devices.
    OperationResult flash(FlashRequest request, ProgressReporter reporter) throws IOException;
}

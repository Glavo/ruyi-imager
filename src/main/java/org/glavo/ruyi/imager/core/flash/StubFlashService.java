// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.flash;

import org.glavo.ruyi.imager.core.OperationResult;
import org.glavo.ruyi.imager.core.ProgressEvent;
import org.glavo.ruyi.imager.core.ProgressReporter;
import org.jetbrains.annotations.NotNullByDefault;

/// Placeholder flash service used before platform write backends are implemented.
@NotNullByDefault
public final class StubFlashService implements FlashService {
    /// Reports the missing backend and refuses to write.
    ///
    /// @param request flash request.
    /// @param reporter progress reporter.
    /// @return failed operation result.
    @Override
    public OperationResult flash(FlashRequest request, ProgressReporter reporter) {
        reporter.report(ProgressEvent.indeterminate("flash", "Platform flash backend is not implemented yet."));
        return OperationResult.failure("Platform flash backend is not implemented yet.");
    }
}

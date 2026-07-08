// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.repo;

import org.glavo.ruyi.imager.core.OperationResult;
import org.glavo.ruyi.imager.core.ProgressReporter;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;

/// Manages local Ruyi repository metadata.
@NotNullByDefault
public interface RepositoryService {
    /// Returns whether all active repositories have local metadata.
    ///
    /// @return true when local metadata is available for all active repositories.
    /// @throws IOException when repository configuration cannot be read.
    boolean hasLocalMetadata() throws IOException;

    /// Updates local repository metadata.
    ///
    /// @param reporter progress reporter.
    /// @return operation result.
    /// @throws IOException when local metadata cannot be prepared or updated.
    OperationResult update(ProgressReporter reporter) throws IOException;
}

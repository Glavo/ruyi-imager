// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.repo;

import org.glavo.ruyi.imager.core.OperationResult;
import org.glavo.ruyi.imager.core.ProgressReporter;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;

/// Repository service for Ruyi metadata.
@NotNullByDefault
public final class RuyiRepositoryService implements RepositoryService {
    /// Repository store used to synchronize metadata.
    private final RuyiRepositoryStore store;

    /// Creates the repository service.
    ///
    /// @param store repository store.
    public RuyiRepositoryService(RuyiRepositoryStore store) {
        this.store = store;
    }

    /// Updates local metadata repositories.
    ///
    /// @param reporter progress reporter.
    /// @return operation result.
    /// @throws IOException when repository metadata cannot be updated.
    @Override
    public OperationResult update(ProgressReporter reporter) throws IOException {
        return store.update(reporter);
    }
}

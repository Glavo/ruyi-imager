// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.repo;

import org.glavo.ruyi.imager.core.OperationResult;
import org.glavo.ruyi.imager.core.ProgressReporter;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.util.Objects;

/// Repository service for Ruyi metadata.
@NotNullByDefault
public final class RuyiRepositoryService implements RepositoryService {
    /// Repository store used to synchronize metadata.
    private final RuyiRepositoryStore store;

    /// Action used to invalidate metadata caches after each update attempt.
    private final Runnable cacheInvalidator;

    /// Creates the repository service.
    ///
    /// @param store repository store.
    public RuyiRepositoryService(RuyiRepositoryStore store) {
        this(store, () -> {
        });
    }

    /// Creates the repository service.
    ///
    /// @param store repository store.
    /// @param cacheInvalidator cache invalidation action.
    public RuyiRepositoryService(RuyiRepositoryStore store, Runnable cacheInvalidator) {
        this.store = store;
        this.cacheInvalidator = Objects.requireNonNull(cacheInvalidator);
    }

    /// Returns whether all active repositories have local metadata.
    ///
    /// @return true when local metadata is available for all active repositories.
    /// @throws IOException when repository configuration cannot be read.
    @Override
    public boolean hasLocalMetadata() throws IOException {
        return store.hasLocalMetadata();
    }

    /// Updates local metadata repositories.
    ///
    /// Invalidates dependent caches even when updating fails, since earlier repositories
    /// may already have changed. If invalidation also fails, its exception is suppressed
    /// on the update failure.
    ///
    /// @param reporter progress reporter.
    /// @return operation result.
    /// @throws IOException when repository metadata cannot be updated.
    @Override
    public OperationResult update(ProgressReporter reporter) throws IOException {
        OperationResult result;
        try {
            result = store.update(reporter);
        } catch (IOException | RuntimeException | Error failure) {
            try {
                cacheInvalidator.run();
            } catch (RuntimeException | Error invalidationFailure) {
                if (invalidationFailure != failure) {
                    failure.addSuppressed(invalidationFailure);
                }
            }
            throw failure;
        }
        cacheInvalidator.run();
        return result;
    }
}

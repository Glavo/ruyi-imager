// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.repo;

import org.glavo.ruyi.imager.core.AppDirectories;
import org.glavo.ruyi.imager.core.OperationResult;
import org.glavo.ruyi.imager.core.ProgressEvent;
import org.glavo.ruyi.imager.core.ProgressReporter;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.file.Files;

/// Repository service for Ruyi metadata.
@NotNullByDefault
public final class RuyiRepositoryService implements RepositoryService {
    /// Application directories used to store metadata.
    private final AppDirectories directories;

    /// Creates the repository service.
    ///
    /// @param directories application directories.
    public RuyiRepositoryService(AppDirectories directories) {
        this.directories = directories;
    }

    /// Prepares local metadata directories.
    ///
    /// @param reporter progress reporter.
    /// @return operation result.
    /// @throws IOException when directories cannot be created.
    @Override
    public OperationResult update(ProgressReporter reporter) throws IOException {
        reporter.report(ProgressEvent.indeterminate("repo", "Preparing local Ruyi metadata directories."));
        Files.createDirectories(directories.configDirectory());
        Files.createDirectories(directories.cacheDirectory().resolve("repos"));
        return OperationResult.success("Local Ruyi metadata directories are ready.");
    }
}

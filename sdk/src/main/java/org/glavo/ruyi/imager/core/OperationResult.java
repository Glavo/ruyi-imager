// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core;

import org.jetbrains.annotations.NotNullByDefault;

/// Result of a user-visible operation.
///
/// @param success whether the operation completed successfully.
/// @param message human-readable result message.
@NotNullByDefault
public record OperationResult(boolean success, String message) {
    /// Creates a successful result.
    ///
    /// @param message human-readable result message.
    /// @return successful result.
    public static OperationResult success(String message) {
        return new OperationResult(true, message);
    }

    /// Creates a failed result.
    ///
    /// @param message human-readable result message.
    /// @return failed result.
    public static OperationResult failure(String message) {
        return new OperationResult(false, message);
    }
}

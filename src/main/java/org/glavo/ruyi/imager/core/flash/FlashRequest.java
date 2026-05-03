// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.flash;

import org.glavo.ruyi.imager.core.device.BlockDevice;
import org.glavo.ruyi.imager.core.image.ImageEntry;
import org.glavo.ruyi.imager.i18n.Messages;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

/// Request to write an image to a target device.
///
/// @param image image selected from the Ruyi catalog.
/// @param localImage local image file selected by path.
/// @param target target block device.
/// @param verify whether post-write verification should run.
@NotNullByDefault
public record FlashRequest(
        @Nullable ImageEntry image,
        @Nullable Path localImage,
        BlockDevice target,
        boolean verify) {
    /// Validates the selected image source.
    public FlashRequest {
        if ((image == null) == (localImage == null)) {
            throw new IllegalArgumentException(Messages.get("core.flash.exactlyOneSource"));
        }
    }
}

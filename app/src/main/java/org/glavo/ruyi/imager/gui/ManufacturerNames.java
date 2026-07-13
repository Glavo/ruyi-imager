// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.gui;

import org.glavo.ruyi.imager.i18n.Messages;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Map;

/// Localizes manufacturer display names for the GUI.
@NotNullByDefault
final class ManufacturerNames {
    /// Known manufacturer display names to message keys.
    private static final @Unmodifiable Map<String, String> MESSAGE_KEYS = Map.ofEntries(
            Map.entry("Allwinner", "gui.manufacturer.allwinner"),
            Map.entry("Canaan", "gui.manufacturer.canaan"),
            Map.entry("Milk-V", "gui.manufacturer.milkv"),
            Map.entry("Pine64", "gui.manufacturer.pine64"),
            Map.entry("SiFive", "gui.manufacturer.sifive"),
            Map.entry("Sipeed", "gui.manufacturer.sipeed"),
            Map.entry("SpacemiT", "gui.manufacturer.spacemit"),
            Map.entry("StarFive", "gui.manufacturer.starfive"),
            Map.entry("WinChipHead", "gui.manufacturer.wch"));

    /// Prevents construction of the utility class.
    private ManufacturerNames() {
    }

    /// Returns the localized display name for one manufacturer.
    ///
    /// @param manufacturer metadata manufacturer name.
    /// @return localized display name, or the metadata name when no localization is known.
    static String displayName(String manufacturer) {
        @Nullable String key = MESSAGE_KEYS.get(manufacturer);
        return key == null ? manufacturer : Messages.get(key);
    }

}

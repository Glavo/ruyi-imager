// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.gui;

import org.glavo.ruyi.imager.i18n.Messages;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Tests for localized manufacturer display names.
@NotNullByDefault
public final class ManufacturerNamesTest {
    /// Verifies that known manufacturer names are localized for Simplified Chinese.
    @Test
    public void localizesKnownManufacturersForSimplifiedChinese() {
        Locale originalLocale = Messages.locale();
        try {
            Messages.setLocale(Locale.SIMPLIFIED_CHINESE);

            assertEquals("Allwinner (全志科技)", ManufacturerNames.displayName("Allwinner"));
            assertEquals("Sipeed (矽速科技)", ManufacturerNames.displayName("Sipeed"));
            assertEquals("WinChipHead (沁恒微电子)", ManufacturerNames.displayName("WinChipHead"));
        } finally {
            Messages.setLocale(originalLocale);
        }
    }

    /// Verifies that English keeps canonical metadata manufacturer names.
    @Test
    public void keepsCanonicalNamesForEnglish() {
        Locale originalLocale = Messages.locale();
        try {
            Messages.setLocale(Locale.ENGLISH);

            assertEquals("Allwinner", ManufacturerNames.displayName("Allwinner"));
            assertEquals("WinChipHead", ManufacturerNames.displayName("WinChipHead"));
        } finally {
            Messages.setLocale(originalLocale);
        }
    }

    /// Verifies that unknown manufacturer names fall back to metadata values.
    @Test
    public void keepsUnknownManufacturerNames() {
        Locale originalLocale = Messages.locale();
        try {
            Messages.setLocale(Locale.SIMPLIFIED_CHINESE);

            assertEquals("Example Vendor", ManufacturerNames.displayName("Example Vendor"));
        } finally {
            Messages.setLocale(originalLocale);
        }
    }
}

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.gui;

import org.glavo.ruyi.imager.i18n.Messages;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for localized manufacturer display names.
@NotNullByDefault
public final class ManufacturerNamesTest {
    /// Verifies that known manufacturer names are localized for Simplified Chinese.
    @Test
    public void localizesKnownManufacturersForSimplifiedChinese() {
        Locale originalLocale = Messages.locale();
        try {
            Messages.setLocale(Locale.SIMPLIFIED_CHINESE);

            assertEquals("全志科技 (Allwinner)", ManufacturerNames.displayName("Allwinner"));
            assertEquals("矽速科技 (Sipeed)", ManufacturerNames.displayName("Sipeed"));
            assertEquals("沁恒微电子 (WCH)", ManufacturerNames.displayName("WinChipHead"));
            assertTrue(ManufacturerNames.hasLocalizedName("Allwinner"));
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
            assertFalse(ManufacturerNames.hasLocalizedName("Allwinner"));
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
            assertFalse(ManufacturerNames.hasLocalizedName("Example Vendor"));
        } finally {
            Messages.setLocale(originalLocale);
        }
    }
}

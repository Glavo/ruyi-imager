// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.i18n;

import javafx.beans.binding.StringBinding;
import javafx.beans.property.SimpleStringProperty;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Tests for localized message lookup and bindings.
@NotNullByDefault
public final class MessagesTest {
    /// Verifies that a message binding is invalidated when the selected locale changes.
    @Test
    public void bindingUpdatesWhenLocaleChanges() {
        Locale originalLocale = Messages.locale();
        StringBinding binding = Messages.binding("gui.button.flash");
        try {
            Messages.setLocale(Locale.ENGLISH);
            assertEquals(Messages.get("gui.button.flash"), binding.get());

            Messages.setLocale(Locale.SIMPLIFIED_CHINESE);
            assertEquals(Messages.get("gui.button.flash"), binding.get());
        } finally {
            binding.dispose();
            Messages.setLocale(originalLocale);
        }
    }

    /// Verifies that formatted bindings observe observable format arguments.
    @Test
    public void formattedBindingUpdatesWhenArgumentChanges() {
        Locale originalLocale = Messages.locale();
        SimpleStringProperty fileName = new SimpleStringProperty("image.raw");
        StringBinding binding = Messages.binding("gui.value.local.selected", fileName);
        try {
            Messages.setLocale(Locale.ENGLISH);
            assertEquals(Messages.get("gui.value.local.selected", "image.raw"), binding.get());

            fileName.set("other.raw");
            assertEquals(Messages.get("gui.value.local.selected", "other.raw"), binding.get());

            Messages.setLocale(Locale.SIMPLIFIED_CHINESE);
            assertEquals(Messages.get("gui.value.local.selected", "other.raw"), binding.get());
        } finally {
            binding.dispose();
            Messages.setLocale(originalLocale);
        }
    }
}

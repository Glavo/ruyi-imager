// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.gui;

import org.glavo.ruyi.imager.core.AppDirectories;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for GUI preference persistence.
@NotNullByDefault
public final class GuiPreferencesTest {
    /// Verifies that missing preferences do not force a locale.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when the preferences file cannot be inspected.
    @Test
    public void returnsNullWhenLocalePreferenceIsMissing(@TempDir Path temporaryDirectory) throws Exception {
        GuiPreferences preferences = preferences(temporaryDirectory);

        assertNull(preferences.readLocale());
    }

    /// Verifies that missing preferences do not mark the startup warning as accepted.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when the preferences file cannot be inspected.
    @Test
    public void returnsFalseWhenStartupSafetyWarningPreferenceIsMissing(@TempDir Path temporaryDirectory) throws Exception {
        GuiPreferences preferences = preferences(temporaryDirectory);

        assertFalse(preferences.readStartupSafetyWarningAccepted());
    }

    /// Verifies that editable settings are persisted together.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when the preferences file cannot be written or read.
    @Test
    public void persistsEditableSettings(@TempDir Path temporaryDirectory) throws Exception {
        GuiPreferences preferences = preferences(temporaryDirectory);

        preferences.writeSettings(Locale.SIMPLIFIED_CHINESE, true);

        assertEquals(Locale.SIMPLIFIED_CHINESE, preferences.readLocale());
        assertTrue(preferences.readStartupSafetyWarningAccepted());
    }

    /// Verifies that settings can re-enable the startup safety warning.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when the preferences file cannot be written or read.
    @Test
    public void reEnablesStartupSafetyWarning(@TempDir Path temporaryDirectory) throws Exception {
        GuiPreferences preferences = preferences(temporaryDirectory);

        preferences.writeSettings(Locale.ENGLISH, true);
        preferences.writeSettings(Locale.ENGLISH, false);

        assertEquals(Locale.ENGLISH, preferences.readLocale());
        assertFalse(preferences.readStartupSafetyWarningAccepted());
    }

    /// Creates a test preferences store.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @return test preferences store.
    private static GuiPreferences preferences(Path temporaryDirectory) {
        return new GuiPreferences(new AppDirectories(
                temporaryDirectory.resolve("config"),
                temporaryDirectory.resolve("cache")));
    }
}

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.gui;

import org.glavo.ruyi.imager.core.AppDirectories;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/// Stores JavaFX front-end preferences outside the Ruyi repository configuration file.
@NotNullByDefault
final class GuiPreferences {
    /// GUI preferences file name.
    private static final String FILE_NAME = "gui.properties";

    /// Locale preference key.
    private static final String LOCALE_KEY = "locale";

    /// Preferences file path.
    private final Path path;

    /// Creates a preferences store.
    ///
    /// @param directories application directories.
    GuiPreferences(AppDirectories directories) {
        this.path = directories.configDirectory().resolve(FILE_NAME);
    }

    /// Reads the persisted locale preference.
    ///
    /// @return persisted locale, or null when no preference is stored.
    /// @throws IOException when the preferences file cannot be read.
    public @Nullable Locale readLocale() throws IOException {
        if (!Files.isRegularFile(path)) {
            return null;
        }

        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }

        @Nullable String languageTag = properties.getProperty(LOCALE_KEY);
        if (languageTag == null || languageTag.isBlank()) {
            return null;
        }

        Locale locale = Locale.forLanguageTag(languageTag.replace('_', '-'));
        return locale.getLanguage().isBlank() ? null : locale;
    }

    /// Writes the locale preference.
    ///
    /// @param locale locale to persist.
    /// @throws IOException when the preferences file cannot be written.
    public void writeLocale(Locale locale) throws IOException {
        @Nullable Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Properties properties = new Properties();
        properties.setProperty(LOCALE_KEY, locale.toLanguageTag());
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            properties.store(writer, "Ruyi Imager GUI preferences");
        }
    }
}

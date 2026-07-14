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
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Properties;

/// Stores JavaFX front-end preferences outside the Ruyi repository configuration file.
@NotNullByDefault
final class GuiPreferences {
    /// GUI preferences file name.
    private static final String FILE_NAME = "gui.properties";

    /// Locale preference key.
    private static final String LOCALE_KEY = "locale";

    /// Startup safety warning preference key.
    private static final String STARTUP_SAFETY_WARNING_ACCEPTED_KEY = "startupSafetyWarningAccepted";

    /// Last successful metadata update preference key.
    private static final String METADATA_UPDATED_AT_KEY = "metadataUpdatedAt";

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
        Properties properties = readProperties();
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
        Properties properties = readProperties();
        properties.setProperty(LOCALE_KEY, locale.toLanguageTag());
        writeProperties(properties);
    }

    /// Reads the last successful metadata update time.
    ///
    /// @return update time, or null when no valid time is stored.
    /// @throws IOException when the preferences file cannot be read.
    public @Nullable Instant readMetadataUpdatedAt() throws IOException {
        Properties properties = readProperties();
        @Nullable String value = properties.getProperty(METADATA_UPDATED_AT_KEY);
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    /// Writes the last successful metadata update time.
    ///
    /// @param updatedAt update time to persist.
    /// @throws IOException when the preferences file cannot be written.
    public void writeMetadataUpdatedAt(Instant updatedAt) throws IOException {
        Properties properties = readProperties();
        properties.setProperty(METADATA_UPDATED_AT_KEY, updatedAt.toString());
        writeProperties(properties);
    }

    /// Reads whether the startup safety warning has been accepted.
    ///
    /// @return whether the startup safety warning has been accepted.
    /// @throws IOException when the preferences file cannot be read.
    public boolean readStartupSafetyWarningAccepted() throws IOException {
        Properties properties = readProperties();
        return Boolean.parseBoolean(properties.getProperty(STARTUP_SAFETY_WARNING_ACCEPTED_KEY));
    }

    /// Marks the startup safety warning as accepted.
    ///
    /// @throws IOException when the preferences file cannot be written.
    public void writeStartupSafetyWarningAccepted() throws IOException {
        Properties properties = readProperties();
        properties.setProperty(STARTUP_SAFETY_WARNING_ACCEPTED_KEY, Boolean.TRUE.toString());
        writeProperties(properties);
    }

    /// Reads all stored GUI preferences.
    ///
    /// @return stored preferences, or an empty set when no file exists.
    /// @throws IOException when the preferences file cannot be read.
    private Properties readProperties() throws IOException {
        Properties properties = new Properties();
        if (!Files.isRegularFile(path)) {
            return properties;
        }

        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    /// Writes all GUI preferences.
    ///
    /// @param properties preferences to write.
    /// @throws IOException when the preferences file cannot be written.
    private void writeProperties(Properties properties) throws IOException {
        @Nullable Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            properties.store(writer, "Ruyi Imager GUI preferences");
        }
    }
}

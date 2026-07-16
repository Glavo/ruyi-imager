// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.gui;

import org.glavo.ruyi.imager.core.AppDirectories;
import org.glavo.ruyi.imager.update.UpdateChannel;
import org.glavo.ruyi.imager.update.UpdateRelease;
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

    /// Automatic application update check preference key.
    private static final String APPLICATION_UPDATE_AUTOMATIC_KEY = "applicationUpdate.automatic";

    /// Selected application update channel preference key.
    private static final String APPLICATION_UPDATE_CHANNEL_KEY = "applicationUpdate.channel";

    /// Last successful application update check preference key.
    private static final String APPLICATION_UPDATE_CHECKED_AT_KEY_PREFIX = "applicationUpdate.checkedAt.";

    /// Skipped application update channel preference key.
    private static final String APPLICATION_UPDATE_SKIPPED_CHANNEL_KEY = "applicationUpdate.skipped.channel";

    /// Skipped application update version preference key.
    private static final String APPLICATION_UPDATE_SKIPPED_VERSION_KEY = "applicationUpdate.skipped.version";

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

    /// Writes the user-editable general and application update settings together.
    ///
    /// @param locale                GUI locale.
    /// @param automaticUpdateChecks whether startup update checks are enabled.
    /// @param updateChannel         selected update channel.
    /// @throws IOException when preferences cannot be written.
    public void writeSettings(
            Locale locale,
            boolean automaticUpdateChecks,
            UpdateChannel updateChannel) throws IOException {
        Properties properties = readProperties();
        properties.setProperty(LOCALE_KEY, locale.toLanguageTag());
        properties.setProperty(APPLICATION_UPDATE_AUTOMATIC_KEY, Boolean.toString(automaticUpdateChecks));
        properties.setProperty(APPLICATION_UPDATE_CHANNEL_KEY, updateChannel.token());
        writeProperties(properties);
    }

    /// Reads whether startup application update checks are enabled.
    ///
    /// @return whether automatic checks are enabled; defaults to true.
    /// @throws IOException when preferences cannot be read.
    public boolean readAutomaticUpdateChecksEnabled() throws IOException {
        Properties properties = readProperties();
        @Nullable String value = properties.getProperty(APPLICATION_UPDATE_AUTOMATIC_KEY);
        return value == null || Boolean.parseBoolean(value);
    }

    /// Reads the selected application update channel.
    ///
    /// @return selected channel; defaults to stable.
    /// @throws IOException when preferences cannot be read.
    public UpdateChannel readUpdateChannel() throws IOException {
        return readUpdateChannel(UpdateChannel.STABLE);
    }

    /// Reads the selected application update channel with a caller-supplied default.
    ///
    /// @param defaultChannel channel used when no valid preference is stored.
    /// @return selected or default channel.
    /// @throws IOException when preferences cannot be read.
    public UpdateChannel readUpdateChannel(UpdateChannel defaultChannel) throws IOException {
        Properties properties = readProperties();
        @Nullable String value = properties.getProperty(APPLICATION_UPDATE_CHANNEL_KEY);
        if (value == null || value.isBlank()) {
            return defaultChannel;
        }
        try {
            return UpdateChannel.parse(value);
        } catch (IllegalArgumentException ignored) {
            return defaultChannel;
        }
    }

    /// Reads the last successful application update check time.
    ///
    /// @param channel update channel.
    /// @return update check time for the channel, or null when no valid time is stored.
    /// @throws IOException when preferences cannot be read.
    public @Nullable Instant readApplicationUpdateCheckedAt(UpdateChannel channel) throws IOException {
        return readInstant(readProperties(), APPLICATION_UPDATE_CHECKED_AT_KEY_PREFIX + channel.token());
    }

    /// Writes the last successful application update check time.
    ///
    /// @param channel   checked update channel.
    /// @param checkedAt successful check time.
    /// @throws IOException when preferences cannot be written.
    public void writeApplicationUpdateCheckedAt(UpdateChannel channel, Instant checkedAt) throws IOException {
        Properties properties = readProperties();
        properties.setProperty(APPLICATION_UPDATE_CHECKED_AT_KEY_PREFIX + channel.token(), checkedAt.toString());
        writeProperties(properties);
    }

    /// Returns whether a release was explicitly skipped.
    ///
    /// @param release available release.
    /// @return whether the release identity matches the skipped release.
    /// @throws IOException when preferences cannot be read.
    public boolean isUpdateSkipped(UpdateRelease release) throws IOException {
        Properties properties = readProperties();
        @Nullable String channel = properties.getProperty(APPLICATION_UPDATE_SKIPPED_CHANNEL_KEY);
        @Nullable String version = properties.getProperty(APPLICATION_UPDATE_SKIPPED_VERSION_KEY);
        if (channel == null || version == null) {
            return false;
        }
        try {
            return release.channel() == UpdateChannel.parse(channel)
                    && release.version().equals(version);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    /// Persists one skipped release identity.
    ///
    /// @param release release to skip.
    /// @throws IOException when preferences cannot be written.
    public void writeSkippedUpdate(UpdateRelease release) throws IOException {
        Properties properties = readProperties();
        properties.setProperty(APPLICATION_UPDATE_SKIPPED_CHANNEL_KEY, release.channel().token());
        properties.setProperty(APPLICATION_UPDATE_SKIPPED_VERSION_KEY, release.version());
        writeProperties(properties);
    }

    /// Reads the last successful metadata update time.
    ///
    /// @return update time, or null when no valid time is stored.
    /// @throws IOException when the preferences file cannot be read.
    public @Nullable Instant readMetadataUpdatedAt() throws IOException {
        return readInstant(readProperties(), METADATA_UPDATED_AT_KEY);
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

    /// Reads an ISO-8601 instant property.
    ///
    /// @param properties source preferences.
    /// @param key        property key.
    /// @return parsed instant, or null when absent or invalid.
    private static @Nullable Instant readInstant(Properties properties, String key) {
        @Nullable String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            return null;
        }
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

    /// Writes all stored GUI preferences.
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

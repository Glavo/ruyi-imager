// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.i18n;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.ResourceBundle;

/// Provides localized user-facing application messages.
@NotNullByDefault
public final class Messages {
    /// System property used to override locale selection.
    public static final String LOCALE_PROPERTY = "ruyi.imager.locale";

    /// Resource bundle base name.
    private static final String BUNDLE_NAME = "org.glavo.ruyi.imager.i18n.messages";

    /// Resource bundle control that does not fall back to the host default locale.
    private static final ResourceBundle.Control BUNDLE_CONTROL =
            ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_DEFAULT);

    /// Prevents construction of the message utility.
    private Messages() {
    }

    /// Returns the active resource bundle.
    ///
    /// @return active resource bundle.
    public static ResourceBundle bundle() {
        return ResourceBundle.getBundle(BUNDLE_NAME, selectedLocale(), BUNDLE_CONTROL);
    }

    /// Returns one localized message.
    ///
    /// @param key message key.
    /// @return localized message.
    public static String get(String key) {
        return bundle().getString(key);
    }

    /// Formats one localized message.
    ///
    /// @param key message key.
    /// @param arguments format arguments.
    /// @return localized message.
    public static String get(String key, Object @Unmodifiable ... arguments) {
        ResourceBundle bundle = bundle();
        return new MessageFormat(bundle.getString(key), bundle.getLocale()).format(arguments);
    }

    /// Selects the locale requested by configuration or the host system.
    ///
    /// @return selected locale.
    private static Locale selectedLocale() {
        String configuredLocale = System.getProperty(LOCALE_PROPERTY);
        if (configuredLocale != null && !configuredLocale.isBlank()) {
            return Locale.forLanguageTag(configuredLocale.replace('_', '-'));
        }
        return Locale.getDefault();
    }
}

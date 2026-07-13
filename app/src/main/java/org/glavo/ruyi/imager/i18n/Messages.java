// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.i18n;

import javafx.beans.binding.Bindings;
import javafx.beans.binding.StringBinding;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.value.ObservableValue;
import org.glavo.ruyi.imager.core.SdkMessages;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Objects;
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

    /// Currently selected locale.
    private static final ReadOnlyObjectWrapper<Locale> locale = new ReadOnlyObjectWrapper<>(selectedLocale());

    static {
        SdkMessages.setResolver(Messages::resolveSdkMessage);
    }

    /// Prevents construction of the message utility.
    private Messages() {
    }

    /// Returns the selected locale property.
    ///
    /// @return selected locale property.
    public static ReadOnlyObjectProperty<Locale> localeProperty() {
        return locale.getReadOnlyProperty();
    }

    /// Returns the selected locale.
    ///
    /// @return selected locale.
    public static Locale locale() {
        return locale.get();
    }

    /// Selects a locale and invalidates all message bindings.
    ///
    /// @param value selected locale.
    public static void setLocale(Locale value) {
        locale.set(Objects.requireNonNull(value));
    }

    /// Selects a locale from a BCP 47 language tag and invalidates all message bindings.
    ///
    /// @param languageTag selected locale tag.
    public static void setLocale(String languageTag) {
        setLocale(Locale.forLanguageTag(languageTag.replace('_', '-')));
    }

    /// Returns the active resource bundle.
    ///
    /// @return active resource bundle.
    public static ResourceBundle bundle() {
        return bundle(locale());
    }

    /// Returns a resource bundle for one locale.
    ///
    /// @param locale locale to load.
    /// @return resource bundle.
    public static ResourceBundle bundle(Locale locale) {
        return ResourceBundle.getBundle(BUNDLE_NAME, locale, BUNDLE_CONTROL);
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

    /// Resolves SDK messages from the active application resource bundle.
    ///
    /// @param key message key.
    /// @param arguments format arguments.
    /// @return localized SDK message, or null when the application bundle does not contain the key.
    private static @Nullable String resolveSdkMessage(String key, Object @Unmodifiable ... arguments) {
        ResourceBundle bundle = bundle();
        if (!bundle.containsKey(key)) {
            return null;
        }

        String pattern = bundle.getString(key);
        return arguments.length == 0 ? pattern : new MessageFormat(pattern, bundle.getLocale()).format(arguments);
    }

    /// Returns a binding for one localized message.
    ///
    /// @param key message key.
    /// @return localized message binding.
    public static StringBinding binding(String key) {
        return Bindings.createStringBinding(() -> get(key), locale);
    }

    /// Returns a binding for one formatted localized message.
    ///
    /// @param key message key.
    /// @param arguments format arguments.
    /// @return localized message binding.
    public static StringBinding binding(String key, Object @Unmodifiable ... arguments) {
        return Bindings.createStringBinding(() -> get(key, formatArguments(arguments)), dependencies(arguments));
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

    /// Extracts binding dependencies from format arguments.
    ///
    /// @param arguments format arguments.
    /// @return binding dependencies.
    private static ObservableValue<?> @Unmodifiable [] dependencies(Object @Unmodifiable [] arguments) {
        ArrayList<ObservableValue<?>> dependencies = new ArrayList<>();
        dependencies.add(locale);
        for (Object argument : arguments) {
            if (argument instanceof ObservableValue<?> observable) {
                dependencies.add(observable);
            }
        }
        return dependencies.toArray(ObservableValue<?>[]::new);
    }

    /// Resolves observable format arguments to their current values.
    ///
    /// @param arguments format arguments.
    /// @return resolved arguments.
    private static Object @Unmodifiable [] formatArguments(Object @Unmodifiable [] arguments) {
        Object[] result = new Object[arguments.length];
        for (int i = 0; i < arguments.length; i++) {
            Object argument = arguments[i];
            if (argument instanceof ObservableValue<?> observable) {
                @Nullable Object value = observable.getValue();
                result[i] = value;
            } else {
                result[i] = argument;
            }
        }
        return result;
    }
}

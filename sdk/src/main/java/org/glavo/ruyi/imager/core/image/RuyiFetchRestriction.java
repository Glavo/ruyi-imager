// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.image;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Manual fetch instructions attached to a fetch-restricted Ruyi distfile.
///
/// @param templates message templates keyed by repository language code.
/// @param params package-defined template parameters.
@NotNullByDefault
public record RuyiFetchRestriction(
        @Unmodifiable Map<String, String> templates,
        @Unmodifiable Map<String, String> params) {
    /// Jinja-style variable pattern supported by this renderer.
    private static final Pattern JINJA_VARIABLE = Pattern.compile("\\{\\{\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*}}");

    /// Copies collections into immutable instances.
    public RuyiFetchRestriction {
        templates = Map.copyOf(templates);
        params = Map.copyOf(params);
    }

    /// Renders the manual fetch instructions.
    ///
    /// @param destination expected distfile path.
    /// @param locale requested locale.
    /// @return rendered instructions, or an empty string when no template is available.
    public String render(Path destination, Locale locale) {
        @Nullable String template = template(locale);
        if (template == null) {
            return "";
        }

        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (!"dest_path".equals(entry.getKey())) {
                values.put(entry.getKey(), entry.getValue());
            }
        }
        values.put("dest_path", destination.toAbsolutePath().normalize().toString());
        return renderTemplate(template, values);
    }

    /// Selects the best template for a locale.
    ///
    /// @param locale requested locale.
    /// @return selected template, or null when there are no templates.
    private @Nullable String template(Locale locale) {
        if (templates.isEmpty()) {
            return null;
        }

        String languageTag = locale.toLanguageTag();
        @Nullable String template = templates.get(languageTag);
        if (template != null) {
            return template;
        }

        String underscoreTag = locale.toString();
        template = templates.get(underscoreTag);
        if (template != null) {
            return template;
        }

        template = templates.get(locale.getLanguage());
        if (template != null) {
            return template;
        }

        template = templates.get("en_US");
        if (template != null) {
            return template;
        }

        template = templates.get("en");
        if (template != null) {
            return template;
        }

        return templates.values().iterator().next();
    }

    /// Renders a small subset of Jinja variable interpolation.
    ///
    /// @param template message template.
    /// @param values template values.
    /// @return rendered text.
    private static String renderTemplate(String template, @Unmodifiable Map<String, String> values) {
        Matcher matcher = JINJA_VARIABLE.matcher(template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            @Nullable String value = values.get(matcher.group(1));
            matcher.appendReplacement(result, Matcher.quoteReplacement(value == null ? matcher.group() : value));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}

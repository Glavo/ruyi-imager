// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.logging;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Redacts sensitive values before they are written to logs.
@NotNullByDefault
public final class LogRedactor {
    /// Placeholder used for redacted secrets.
    private static final String REDACTED = "<redacted>";

    /// URL pattern used to redact query strings embedded in free-form text.
    private static final Pattern URL_PATTERN = Pattern.compile("(?i)\\bhttps?://[^\\s\"'<>]+");

    /// Assignment pattern for common secret-bearing keys.
    private static final Pattern SENSITIVE_ASSIGNMENT = Pattern.compile(
            "(?i)\\b(token|access[_-]?token|password|passwd|secret|signature|sig|api[_-]?key|credential)"
                    + "(\\s*[=:]\\s*)([^\\s&;]+)");

    /// Prevents construction of the redaction utility.
    private LogRedactor() {
    }

    /// Redacts one URI by removing user info, query, and fragment.
    ///
    /// @param uri URI to redact.
    /// @return redacted URI text.
    public static String redactUri(URI uri) {
        try {
            URI redacted = new URI(
                    uri.getScheme(),
                    null,
                    uri.getHost(),
                    uri.getPort(),
                    uri.getPath(),
                    null,
                    null);
            return redacted.toString();
        } catch (URISyntaxException | IllegalArgumentException _) {
            return redactText(uri.toString());
        }
    }

    /// Redacts sensitive assignments and URL queries in free-form text.
    ///
    /// @param text text to redact.
    /// @return redacted text.
    public static String redactText(String text) {
        Matcher urlMatcher = URL_PATTERN.matcher(text);
        StringBuilder builder = new StringBuilder(text.length());
        while (urlMatcher.find()) {
            String value = urlMatcher.group();
            String replacement;
            try {
                replacement = redactUri(URI.create(value));
            } catch (IllegalArgumentException _) {
                replacement = value;
            }
            urlMatcher.appendReplacement(builder, Matcher.quoteReplacement(replacement));
        }
        urlMatcher.appendTail(builder);

        Matcher sensitiveMatcher = SENSITIVE_ASSIGNMENT.matcher(builder.toString());
        return sensitiveMatcher.replaceAll("$1$2" + REDACTED);
    }

    /// Redacts and formats command arguments.
    ///
    /// @param command command arguments.
    /// @return redacted command text.
    public static String redactCommand(@Unmodifiable List<String> command) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < command.size(); i++) {
            if (i > 0) {
                builder.append(' ');
            }
            builder.append(redactText(command.get(i)));
        }
        return builder.toString();
    }

    /// Redacts and truncates external command output.
    ///
    /// @param output command output.
    /// @param maxChars maximum output characters after redaction.
    /// @return redacted and truncated output.
    public static String redactOutput(String output, int maxChars) {
        String redacted = redactText(output);
        if (redacted.length() <= maxChars) {
            return redacted;
        }
        return redacted.substring(0, Math.max(0, maxChars)) + "...";
    }
}

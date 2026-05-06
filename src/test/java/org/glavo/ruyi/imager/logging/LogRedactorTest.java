// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.logging;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests log redaction helpers.
@NotNullByDefault
public final class LogRedactorTest {
    /// Redacts URL credentials, query strings, and fragments.
    @Test
    public void redactsUriSecrets() {
        assertEquals(
                "https://example.com/path/file.img",
                LogRedactor.redactUri(URI.create("https://user:pass@example.com/path/file.img?token=secret#frag")));
    }

    /// Redacts sensitive assignments embedded in free-form text.
    @Test
    public void redactsSensitiveAssignments() {
        String redacted = LogRedactor.redactText("token=abc password: secret signature=xyz");

        assertTrue(redacted.contains("token=<redacted>"));
        assertTrue(redacted.contains("password: <redacted>"));
        assertTrue(redacted.contains("signature=<redacted>"));
        assertFalse(redacted.contains("abc"));
        assertFalse(redacted.contains("secret"));
        assertFalse(redacted.contains("xyz"));
    }

    /// Redacts command arguments and truncates output.
    @Test
    public void redactsCommandAndOutput() {
        String command = LogRedactor.redactCommand(List.of(
                "curl",
                "https://example.com/image.img?token=abc",
                "--password=secret"));
        String output = LogRedactor.redactOutput("first token=abc second", 12);

        assertEquals("curl https://example.com/image.img --password=<redacted>", command);
        assertEquals("first token=...", output);
    }
}

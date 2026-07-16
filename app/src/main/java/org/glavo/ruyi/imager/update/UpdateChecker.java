// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.update;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.glavo.ruyi.imager.core.AppDirectories;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Set;

/// Checks a local JSON manifest for a newer Ruyi Imager build.
///
/// @param current running application build.
/// @param source  local update manifest path.
@NotNullByDefault
public record UpdateChecker(BuildInfo current, Path source) {
    /// JVM property that overrides the local update manifest path.
    public static final String SOURCE_PROPERTY = "ruyi.imager.update.source";

    /// Default update manifest file name under the application configuration directory.
    private static final String DEFAULT_SOURCE_FILE_NAME = "update-manifest.json";

    /// Strict JSON reader for update manifests.
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /// Fields accepted by the current manifest schema.
    private static final @Unmodifiable Set<String> MANIFEST_FIELDS = Set.of(
            "schemaVersion",
            "version",
            "buildNumber",
            "releaseNotes");

    /// Normalizes the local update manifest path.
    public UpdateChecker {
        source = source.toAbsolutePath().normalize();
    }

    /// Creates a checker using the configured local manifest path.
    ///
    /// @param directories application directories.
    /// @return configured update checker.
    public static UpdateChecker createDefault(AppDirectories directories) {
        return new UpdateChecker(BuildInfo.current(), configuredSource(directories));
    }

    /// Resolves the configured local update manifest path.
    ///
    /// @param directories application directories.
    /// @return local update manifest path.
    public static Path configuredSource(AppDirectories directories) {
        @Nullable String configured = System.getProperty(SOURCE_PROPERTY);
        if (configured == null || configured.isBlank()) {
            return directories.configDirectory().resolve(DEFAULT_SOURCE_FILE_NAME).toAbsolutePath().normalize();
        }
        return Path.of(configured).toAbsolutePath().normalize();
    }

    /// Reads the manifest and compares it with the running build.
    ///
    /// @return update check result.
    /// @throws IOException when the manifest cannot be read or parsed.
    public UpdateCheckResult check() throws IOException {
        if (!Files.isRegularFile(source)) {
            throw new IOException("Update manifest does not exist or is not a regular file: " + source);
        }

        UpdateManifest manifest;
        try {
            manifest = readManifest(source);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid update manifest: " + source, exception);
        }

        SemanticVersion currentVersion;
        try {
            currentVersion = SemanticVersion.parse(current.version());
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid running application version: " + current.version(), exception);
        }
        SemanticVersion availableVersion = SemanticVersion.parse(manifest.version());
        int versionComparison = availableVersion.compareTo(currentVersion);
        boolean updateAvailable = versionComparison > 0
                || (versionComparison == 0 && manifest.buildNumber() > current.buildNumber());
        return new UpdateCheckResult(
                updateAvailable
                        ? UpdateCheckResult.Status.UPDATE_AVAILABLE
                        : UpdateCheckResult.Status.UP_TO_DATE,
                current,
                manifest);
    }

    /// Reads and validates one update manifest object.
    ///
    /// @param source manifest path.
    /// @return validated manifest.
    /// @throws IOException when JSON cannot be read.
    private static UpdateManifest readManifest(Path source) throws IOException {
        JsonNode root = MAPPER.readTree(source.toFile());
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("Update manifest must be a JSON object.");
        }

        Iterator<String> fieldNames = root.fieldNames();
        while (fieldNames.hasNext()) {
            String fieldName = fieldNames.next();
            if (!MANIFEST_FIELDS.contains(fieldName)) {
                throw new IllegalArgumentException("Unknown update manifest field: " + fieldName);
            }
        }

        JsonNode schemaVersion = requiredField(root, "schemaVersion");
        JsonNode version = requiredField(root, "version");
        JsonNode buildNumber = requiredField(root, "buildNumber");
        if (!schemaVersion.isIntegralNumber() || !schemaVersion.canConvertToInt()) {
            throw new IllegalArgumentException("Update manifest schemaVersion must be an integer.");
        }
        if (!version.isTextual() || version.textValue().isBlank()) {
            throw new IllegalArgumentException("Update manifest version must be a non-blank string.");
        }
        if (!buildNumber.isIntegralNumber() || !buildNumber.canConvertToLong()) {
            throw new IllegalArgumentException("Update manifest buildNumber must be an integer.");
        }

        @Nullable JsonNode releaseNotesNode = root.get("releaseNotes");
        @Nullable String releaseNotes = null;
        if (releaseNotesNode != null && !releaseNotesNode.isNull()) {
            if (!releaseNotesNode.isTextual()) {
                throw new IllegalArgumentException("Update manifest releaseNotes must be a string.");
            }
            releaseNotes = releaseNotesNode.textValue();
        }
        return new UpdateManifest(
                schemaVersion.intValue(),
                version.textValue(),
                buildNumber.longValue(),
                releaseNotes);
    }

    /// Returns one required manifest field.
    ///
    /// @param root      manifest object.
    /// @param fieldName required field name.
    /// @return field value.
    private static JsonNode requiredField(JsonNode root, String fieldName) {
        @Nullable JsonNode value = root.get(fieldName);
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("Missing update manifest field: " + fieldName);
        }
        return value;
    }
}

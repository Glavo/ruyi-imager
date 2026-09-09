// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.update;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.glavo.ruyi.imager.core.AppDirectories;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/// Reads an update feed and selects the newest installable release in a channel.
///
/// @param current installed application build.
/// @param source local or HTTPS update manifest.
/// @param target local installation capabilities, or null when the update platform is unsupported.
@NotNullByDefault
public record UpdateChecker(BuildInfo current, UpdateSource source, @Nullable UpdateTarget target) {
    /// JVM property overriding the default local manifest with a path or HTTPS URL.
    public static final String SOURCE_PROPERTY = "ruyi.imager.update.source";

    /// Strict JSON parser that rejects duplicate keys and trailing documents.
    private static final ObjectMapper MAPPER = new ObjectMapper(
            JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /// Requirement names whose semantics are implemented by this client.
    private static final @Unmodifiable Set<String> REQUIREMENT_FIELDS = Set.of(
            "minimumAppVersion", "minimumSystemVersion");

    /// Creates a local-file checker, retaining version checks on unsupported update platforms.
    ///
    /// @param current installed build.
    /// @param source local manifest file.
    public UpdateChecker(BuildInfo current, Path source) {
        this(current, UpdateSource.of(source), currentTarget());
    }

    /// Creates a checker using application directories and an optional source override.
    ///
    /// @param directories application directories.
    /// @return configured checker.
    public static UpdateChecker createDefault(AppDirectories directories) {
        return createConfigured(configuredSource(directories));
    }

    /// Creates a checker for the running application and an explicit source.
    /// Unsupported update platforms can report newer releases but cannot select installers.
    ///
    /// @param source manifest location.
    /// @return configured checker.
    public static UpdateChecker createConfigured(UpdateSource source) {
        return new UpdateChecker(BuildInfo.current(), source, currentTarget());
    }

    /// Detects installer capabilities without making them a prerequisite for version checks.
    ///
    /// @return current target, or null when the OS or architecture is unsupported for updates.
    private static @Nullable UpdateTarget currentTarget() {
        try {
            return UpdateTarget.current();
        } catch (IllegalStateException ignored) {
            return null;
        }
    }

    /// Resolves the configured update source, defaulting to a local test manifest.
    ///
    /// @param directories application directories.
    /// @return manifest source.
    public static UpdateSource configuredSource(AppDirectories directories) {
        @Nullable String configured = System.getProperty(SOURCE_PROPERTY);
        return configured == null || configured.isBlank()
                ? UpdateSource.of(directories.configDirectory().resolve("update-manifest.json"))
                : UpdateSource.parse(configured);
    }

    /// Checks the stable channel.
    ///
    /// @return comparison and installation eligibility result.
    /// @throws IOException when the source cannot be read or parsed.
    public UpdateCheckResult check() throws IOException {
        return check(UpdateChannel.STABLE);
    }

    /// Selects the newest compatible release newer than the installed build.
    /// When newer releases exist but none is installable, the newest is returned only
    /// for informational display. An empty or absent channel is considered up to date.
    ///
    /// @param channel requested channel.
    /// @return selected release and artifact, or a non-installable status.
    /// @throws IOException when the source cannot be read or violates the manifest contract.
    public UpdateCheckResult check(UpdateChannel channel) throws IOException {
        UpdateManifest manifest;
        try {
            manifest = readManifest(source.read());
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid update manifest: " + source, exception);
        }
        ApplicationVersion installedVersion = ApplicationVersion.parse(current.version());
        @Nullable UpdateRelease newest = null;
        @Nullable UpdateRelease compatible = null;
        @Nullable UpdateArtifact selected = null;
        for (UpdateRelease release : manifest.releases()) {
            if (release.channel() != channel
                    || ApplicationVersion.parse(release.version()).compareTo(installedVersion) <= 0) {
                continue;
            }
            if (newest == null || compare(release, newest) > 0) {
                newest = release;
            }
            @Nullable UpdateArtifact artifact = target == null ? null : target.select(release, current);
            if (artifact != null && (compatible == null || compare(release, compatible) > 0)) {
                compatible = release;
                selected = artifact;
            }
        }
        if (compatible != null) {
            return new UpdateCheckResult(UpdateCheckResult.Status.UPDATE_AVAILABLE, current, compatible, selected);
        }
        return new UpdateCheckResult(newest == null ? UpdateCheckResult.Status.UP_TO_DATE
                : UpdateCheckResult.Status.NO_COMPATIBLE_UPDATE, current, newest, null);
    }

    /// Compares release precedence without considering build metadata.
    ///
    /// @param left first release.
    /// @param right second release.
    /// @return version comparison result.
    private static int compare(UpdateRelease left, UpdateRelease right) {
        return ApplicationVersion.parse(left.version()).compareTo(ApplicationVersion.parse(right.version()));
    }

    /// Parses a manifest, ignoring non-critical extension fields and unknown candidate types.
    /// Unknown installation requirements make their containing candidate ineligible.
    ///
    /// @param bytes bounded JSON document.
    /// @return validated manifest containing recognized channels and installer types.
    /// @throws IOException when JSON syntax is invalid.
    /// @throws IllegalArgumentException when required fields or known values are invalid.
    static UpdateManifest readManifest(byte[] bytes) throws IOException {
        @Nullable JsonNode root = MAPPER.readTree(bytes);
        requireObject(root, "Update manifest");
        long schemaVersion = requiredLong(root, "schemaVersion");
        if (schemaVersion != UpdateManifest.CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported update manifest schema version: " + schemaVersion);
        }
        List<UpdateRelease> releases = new ArrayList<>();
        for (JsonNode node : requiredArray(root, "releases")) {
            requireObject(node, "Update release");
            UpdateChannel channel;
            String channelName = requiredText(node, "channel");
            try {
                channel = UpdateChannel.parse(channelName);
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            List<UpdateArtifact> artifacts = new ArrayList<>();
            for (JsonNode artifactNode : requiredArray(node, "artifacts")) {
                @Nullable UpdateArtifact artifact = readArtifact(artifactNode);
                if (artifact != null) {
                    artifacts.add(artifact);
                }
            }
            releases.add(new UpdateRelease(channel, requiredText(node, "version"),
                    optionalText(node, "releaseNotes"), artifacts, readRequirements(node)));
        }
        return new UpdateManifest(UpdateManifest.CURRENT_SCHEMA_VERSION, releases);
    }

    /// Parses an installer, skipping platform or package types this client cannot use.
    ///
    /// @param node artifact JSON object.
    /// @return artifact, or null for unsupported variants.
    private static @Nullable UpdateArtifact readArtifact(JsonNode node) {
        requireObject(node, "Update artifact");
        String platformName = requiredText(node, "platform");
        String typeName = requiredText(node, "packageType");
        UpdatePlatform platform;
        UpdatePackageType type;
        try {
            platform = UpdatePlatform.parse(platformName);
            type = UpdatePackageType.parse(typeName);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        if (!platform.supports(type)) {
            return null;
        }
        return new UpdateArtifact(platform, type, requiredText(node, "source"), requiredLong(node, "size"),
                requiredText(node, "sha256"), readRequirements(node));
    }

    /// Parses fail-closed installation conditions from a release or artifact.
    ///
    /// @param node containing JSON object.
    /// @return understood conditions, or an ineligible set containing unknown keys.
    private static UpdateRequirements readRequirements(JsonNode node) {
        @Nullable JsonNode requirements = node.get("requirements");
        if (requirements == null) {
            return UpdateRequirements.NONE;
        }
        requireObject(requirements, "Update requirements");
        boolean understood = true;
        var names = requirements.fieldNames();
        while (names.hasNext()) {
            if (!REQUIREMENT_FIELDS.contains(names.next())) {
                understood = false;
            }
        }
        return new UpdateRequirements(
                requirements.has("minimumAppVersion") ? requiredText(requirements, "minimumAppVersion") : null,
                requirements.has("minimumSystemVersion") ? requiredText(requirements, "minimumSystemVersion") : null,
                understood);
    }

    /// Requires a non-null JSON object.
    ///
    /// @param node candidate value.
    /// @param description diagnostic context.
    private static void requireObject(@Nullable JsonNode node, String description) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException(description + " must be a JSON object.");
        }
    }

    /// Returns a required JSON array.
    ///
    /// @param node containing object.
    /// @param name field name.
    /// @return validated array.
    private static JsonNode requiredArray(JsonNode node, String name) {
        @Nullable JsonNode value = node.get(name);
        if (value == null || !value.isArray()) {
            throw new IllegalArgumentException(name + " must be an array.");
        }
        return value;
    }

    /// Returns a required non-blank JSON string.
    ///
    /// @param node containing object.
    /// @param name field name.
    /// @return validated text.
    private static String requiredText(JsonNode node, String name) {
        @Nullable String value = optionalText(node, name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be a non-blank string.");
        }
        return value;
    }

    /// Returns an optional JSON string, permitting an explicit null value.
    ///
    /// @param node containing object.
    /// @param name field name.
    /// @return text, or null if absent.
    private static @Nullable String optionalText(JsonNode node, String name) {
        @Nullable JsonNode value = node.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new IllegalArgumentException(name + " must be a string.");
        }
        return value.textValue();
    }

    /// Returns a required signed 64-bit JSON integer.
    ///
    /// @param node containing object.
    /// @param name field name.
    /// @return integer value.
    private static long requiredLong(JsonNode node, String name) {
        @Nullable JsonNode value = node.get(name);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new IllegalArgumentException(name + " must be an integer.");
        }
        return value.longValue();
    }
}

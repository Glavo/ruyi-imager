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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/// Checks a local JSON manifest for a newer Ruyi Imager build.
///
/// @param current           running application build.
/// @param source            local update manifest path.
/// @param publicKey         trusted Ed25519 public key path, or null for an explicitly trusted unsigned local source.
/// @param signatureRequired whether a detached manifest signature is mandatory.
@NotNullByDefault
public record UpdateChecker(
        BuildInfo current,
        Path source,
        @Nullable Path publicKey,
        boolean signatureRequired) {
    /// JVM property that overrides the local update manifest path.
    public static final String SOURCE_PROPERTY = "ruyi.imager.update.source";

    /// JVM property that configures an X.509 PEM or DER Ed25519 public key.
    public static final String PUBLIC_KEY_PROPERTY = "ruyi.imager.update.publicKey";

    /// JVM property that explicitly trusts unsigned local manifests for development.
    public static final String ALLOW_UNSIGNED_LOCAL_PROPERTY = "ruyi.imager.update.allowUnsignedLocal";

    /// Default update manifest file name under the application configuration directory.
    private static final String DEFAULT_SOURCE_FILE_NAME = "update-manifest.json";

    /// Maximum accepted manifest size.
    private static final long MAX_MANIFEST_SIZE = 1024L * 1024L;

    /// Maximum accepted detached signature size.
    private static final long MAX_SIGNATURE_SIZE = 16L * 1024L;

    /// Maximum accepted public key file size.
    private static final long MAX_PUBLIC_KEY_SIZE = 16L * 1024L;

    /// Strict JSON reader for update manifests.
    private static final ObjectMapper MAPPER = new ObjectMapper(
            JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /// Fields accepted by the manifest root object.
    private static final @Unmodifiable Set<String> MANIFEST_FIELDS = Set.of("schemaVersion", "releases");

    /// Fields accepted by one release object.
    private static final @Unmodifiable Set<String> RELEASE_FIELDS = Set.of(
            "channel",
            "version",
            "buildNumber",
            "releaseNotes",
            "artifacts");

    /// Fields accepted by one artifact object.
    private static final @Unmodifiable Set<String> ARTIFACT_FIELDS = Set.of(
            "platform",
            "packageType",
            "source",
            "size",
            "sha256");

    /// Creates a checker for a trusted unsigned local manifest.
    ///
    /// @param current running application build.
    /// @param source  local update manifest path.
    public UpdateChecker(BuildInfo current, Path source) {
        this(current, source, null, false);
    }

    /// Normalizes local update paths and signature policy.
    public UpdateChecker {
        source = source.toAbsolutePath().normalize();
        if (publicKey != null) {
            publicKey = publicKey.toAbsolutePath().normalize();
            signatureRequired = true;
        }
    }

    /// Creates a checker using configured local manifest and signature paths.
    ///
    /// @param directories application directories.
    /// @return configured update checker.
    public static UpdateChecker createDefault(AppDirectories directories) {
        return createConfigured(configuredSource(directories));
    }

    /// Creates a checker using configured signature policy and an explicit local manifest.
    ///
    /// @param source explicit local manifest path.
    /// @return configured update checker.
    public static UpdateChecker createConfigured(Path source) {
        @Nullable Path key = configuredPublicKey();
        boolean allowUnsignedLocal = Boolean.parseBoolean(
                System.getProperty(ALLOW_UNSIGNED_LOCAL_PROPERTY, "false"));
        boolean requireSignature = key != null || !allowUnsignedLocal;
        return new UpdateChecker(BuildInfo.current(), source, key, requireSignature);
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

    /// Resolves the configured trusted Ed25519 public key path.
    ///
    /// @return public key path, or null when unsigned local manifests are explicitly allowed.
    private static @Nullable Path configuredPublicKey() {
        @Nullable String configured = System.getProperty(PUBLIC_KEY_PROPERTY);
        return configured == null || configured.isBlank()
                ? null
                : Path.of(configured).toAbsolutePath().normalize();
    }

    /// Reads the stable channel and compares it with the running build.
    ///
    /// @return update check result.
    /// @throws IOException when the manifest cannot be read, authenticated, or parsed.
    public UpdateCheckResult check() throws IOException {
        return check(UpdateChannel.STABLE);
    }

    /// Reads one update channel and compares its newest release with the running build.
    ///
    /// @param channel selected update channel.
    /// @return update check result.
    /// @throws IOException when the manifest cannot be read, authenticated, or parsed.
    public UpdateCheckResult check(UpdateChannel channel) throws IOException {
        byte[] manifestBytes = readBoundedFile(source, MAX_MANIFEST_SIZE, "Update manifest");
        verifySignature(manifestBytes);

        UpdateManifest manifest;
        try {
            manifest = readManifest(manifestBytes);
        } catch (IOException | IllegalArgumentException exception) {
            throw new IOException("Invalid update manifest: " + source, exception);
        }

        UpdateRelease available = newestRelease(manifest, channel);
        SemanticVersion currentVersion;
        try {
            currentVersion = SemanticVersion.parse(current.version());
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid running application version: " + current.version(), exception);
        }
        int versionComparison = SemanticVersion.parse(available.version()).compareTo(currentVersion);
        boolean updateAvailable = versionComparison > 0
                || (versionComparison == 0
                && (available.buildNumber() > current.buildNumber()
                || (channel == UpdateChannel.STABLE && current.inferredChannel() == UpdateChannel.NIGHTLY)));
        return new UpdateCheckResult(
                updateAvailable
                        ? UpdateCheckResult.Status.UPDATE_AVAILABLE
                        : UpdateCheckResult.Status.UP_TO_DATE,
                current,
                available);
    }

    /// Returns the detached signature path for this manifest.
    ///
    /// @return detached signature path.
    public Path signaturePath() {
        return source.resolveSibling(source.getFileName() + ".sig");
    }

    /// Selects the newest release in one channel.
    ///
    /// @param manifest parsed update manifest.
    /// @param channel  selected update channel.
    /// @return newest matching release.
    /// @throws IOException when the manifest has no release for the channel.
    private static UpdateRelease newestRelease(UpdateManifest manifest, UpdateChannel channel) throws IOException {
        @Nullable UpdateRelease newest = null;
        for (UpdateRelease release : manifest.releases()) {
            if (release.channel() != channel) {
                continue;
            }
            if (newest == null || compareReleases(release, newest) > 0) {
                newest = release;
            }
        }
        if (newest == null) {
            throw new IOException("Update manifest contains no releases for channel: " + channel.token());
        }
        return newest;
    }

    /// Compares two releases by SemVer precedence and build number.
    ///
    /// @param left  left release.
    /// @param right right release.
    /// @return comparison result.
    private static int compareReleases(UpdateRelease left, UpdateRelease right) {
        int result = SemanticVersion.parse(left.version()).compareTo(SemanticVersion.parse(right.version()));
        return result == 0 ? Long.compare(left.buildNumber(), right.buildNumber()) : result;
    }

    /// Verifies the detached Ed25519 signature when configured or required.
    ///
    /// @param manifestBytes exact manifest bytes covered by the signature.
    /// @throws IOException when signature policy or verification fails.
    private void verifySignature(byte[] manifestBytes) throws IOException {
        Path signature = signaturePath();
        boolean signatureExists = Files.isRegularFile(signature);
        if (publicKey == null) {
            if (signatureRequired) {
                throw new IOException("Update manifest signatures are required but no trusted public key is configured.");
            }
            if (signatureExists) {
                throw new IOException("Update manifest has a signature but no trusted public key is configured: " + signature);
            }
            return;
        }
        if (!signatureExists) {
            throw new IOException("Update manifest signature is missing: " + signature);
        }

        try {
            PublicKey key = readPublicKey(publicKey);
            String encodedSignature = new String(
                    readBoundedFile(signature, MAX_SIGNATURE_SIZE, "Update manifest signature"),
                    StandardCharsets.US_ASCII).strip();
            byte[] signatureBytes = Base64.getDecoder().decode(encodedSignature);
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(manifestBytes);
            if (!verifier.verify(signatureBytes)) {
                throw new IOException("Update manifest signature verification failed: " + source);
            }
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IOException("Failed to verify update manifest signature: " + source, exception);
        }
    }

    /// Reads an Ed25519 public key in X.509 PEM or DER form.
    ///
    /// @param path public key path.
    /// @return parsed public key.
    /// @throws IOException              when the key cannot be read.
    /// @throws GeneralSecurityException when the key cannot be parsed.
    private static PublicKey readPublicKey(Path path) throws IOException, GeneralSecurityException {
        byte[] keyBytes = readBoundedFile(path, MAX_PUBLIC_KEY_SIZE, "Update public key");
        String text = new String(keyBytes, StandardCharsets.US_ASCII).strip();
        byte[] encoded;
        if (text.startsWith("-----BEGIN PUBLIC KEY-----")) {
            String base64 = text
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s+", "");
            encoded = Base64.getDecoder().decode(base64);
        } else {
            encoded = keyBytes;
        }
        return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
    }

    /// Reads a bounded regular file.
    ///
    /// @param path        file path.
    /// @param maximumSize maximum accepted byte count.
    /// @param description file description used in failures.
    /// @return file bytes.
    /// @throws IOException when the file is missing, oversized, or unreadable.
    private static byte[] readBoundedFile(Path path, long maximumSize, String description) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IOException(description + " does not exist or is not a regular file: " + path);
        }
        if (Files.size(path) > maximumSize) {
            throw new IOException(description + " exceeds the maximum size of " + maximumSize + " bytes: " + path);
        }
        try (InputStream input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(Math.toIntExact(maximumSize + 1L));
            if (bytes.length > maximumSize) {
                throw new IOException(
                        description + " exceeds the maximum size of " + maximumSize + " bytes: " + path);
            }
            return bytes;
        }
    }

    /// Reads and validates one update manifest object.
    ///
    /// @param manifestBytes manifest bytes.
    /// @return validated manifest.
    /// @throws IOException when JSON cannot be read.
    private static UpdateManifest readManifest(byte[] manifestBytes) throws IOException {
        JsonNode root = MAPPER.readTree(manifestBytes);
        requireObject(root, "Update manifest");
        rejectUnknownFields(root, MANIFEST_FIELDS, "update manifest");
        int schemaVersion = requiredInt(root, "schemaVersion", "Update manifest");
        JsonNode releasesNode = requiredArray(root, "releases", "Update manifest");
        List<UpdateRelease> releases = new ArrayList<>();
        for (JsonNode releaseNode : releasesNode) {
            releases.add(readRelease(releaseNode));
        }
        return new UpdateManifest(schemaVersion, releases);
    }

    /// Reads one release object.
    ///
    /// @param node release JSON node.
    /// @return validated release.
    private static UpdateRelease readRelease(JsonNode node) {
        requireObject(node, "Update release");
        rejectUnknownFields(node, RELEASE_FIELDS, "update release");
        UpdateChannel channel = UpdateChannel.parse(requiredText(node, "channel", "Update release"));
        String version = requiredText(node, "version", "Update release");
        long buildNumber = requiredLong(node, "buildNumber", "Update release");
        @Nullable String releaseNotes = optionalText(node, "releaseNotes", "Update release");
        JsonNode artifactsNode = requiredArray(node, "artifacts", "Update release");
        List<UpdateArtifact> artifacts = new ArrayList<>();
        for (JsonNode artifactNode : artifactsNode) {
            artifacts.add(readArtifact(artifactNode));
        }
        return new UpdateRelease(channel, version, buildNumber, releaseNotes, artifacts);
    }

    /// Reads one installer artifact object.
    ///
    /// @param node artifact JSON node.
    /// @return validated artifact.
    private static UpdateArtifact readArtifact(JsonNode node) {
        requireObject(node, "Update artifact");
        rejectUnknownFields(node, ARTIFACT_FIELDS, "update artifact");
        return new UpdateArtifact(
                UpdatePlatform.parse(requiredText(node, "platform", "Update artifact")),
                UpdatePackageType.parse(requiredText(node, "packageType", "Update artifact")),
                requiredText(node, "source", "Update artifact"),
                requiredLong(node, "size", "Update artifact"),
                requiredText(node, "sha256", "Update artifact"));
    }

    /// Requires an object node.
    ///
    /// @param node        JSON node.
    /// @param description value description.
    private static void requireObject(@Nullable JsonNode node, String description) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException(description + " must be a JSON object.");
        }
    }

    /// Rejects fields outside an explicitly supported schema.
    ///
    /// @param node          object node.
    /// @param accepted      accepted field names.
    /// @param objectContext object description.
    private static void rejectUnknownFields(JsonNode node, Set<String> accepted, String objectContext) {
        Iterator<String> fieldNames = node.fieldNames();
        while (fieldNames.hasNext()) {
            String fieldName = fieldNames.next();
            if (!accepted.contains(fieldName)) {
                throw new IllegalArgumentException("Unknown " + objectContext + " field: " + fieldName);
            }
        }
    }

    /// Returns a required array field.
    ///
    /// @param node        object node.
    /// @param fieldName   field name.
    /// @param description object description.
    /// @return array node.
    private static JsonNode requiredArray(JsonNode node, String fieldName, String description) {
        @Nullable JsonNode value = node.get(fieldName);
        if (value == null || !value.isArray()) {
            throw new IllegalArgumentException(description + ' ' + fieldName + " must be an array.");
        }
        return value;
    }

    /// Returns a required non-blank text field.
    ///
    /// @param node        object node.
    /// @param fieldName   field name.
    /// @param description object description.
    /// @return text value.
    private static String requiredText(JsonNode node, String fieldName, String description) {
        @Nullable JsonNode value = node.get(fieldName);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException(description + ' ' + fieldName + " must be a non-blank string.");
        }
        return value.textValue();
    }

    /// Returns an optional text field.
    ///
    /// @param node        object node.
    /// @param fieldName   field name.
    /// @param description object description.
    /// @return text value, or null.
    private static @Nullable String optionalText(JsonNode node, String fieldName, String description) {
        @Nullable JsonNode value = node.get(fieldName);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new IllegalArgumentException(description + ' ' + fieldName + " must be a string.");
        }
        return value.textValue();
    }

    /// Returns a required integer field that fits in an int.
    ///
    /// @param node        object node.
    /// @param fieldName   field name.
    /// @param description object description.
    /// @return integer value.
    private static int requiredInt(JsonNode node, String fieldName, String description) {
        @Nullable JsonNode value = node.get(fieldName);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException(description + ' ' + fieldName + " must be an integer.");
        }
        return value.intValue();
    }

    /// Returns a required integer field that fits in a long.
    ///
    /// @param node        object node.
    /// @param fieldName   field name.
    /// @param description object description.
    /// @return integer value.
    private static long requiredLong(JsonNode node, String fieldName, String description) {
        @Nullable JsonNode value = node.get(fieldName);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new IllegalArgumentException(description + ' ' + fieldName + " must be an integer.");
        }
        return value.longValue();
    }
}

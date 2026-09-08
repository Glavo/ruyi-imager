// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.update;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.abort;

/// Tests update source parsing, bounded local reads, and artifact containment without network access.
@NotNullByDefault
public final class UpdateSourceTest {
    /// Normalizes an absolute HTTPS source while retaining its query and reporting remote availability.
    @Test
    public void parsesHttpsSource() {
        UpdateSource source = UpdateSource.parse("https://updates.example.test/feeds/../stable/manifest.json?channel=stable");

        assertEquals(URI.create("https://updates.example.test/stable/manifest.json?channel=stable"), source.uri());
        assertFalse(source.isLocal());
        assertTrue(source.isAvailable());
        assertEquals(source.uri().toString(), source.toString());
        assertFalse(UpdateSource.parse("HTTPS://updates.example.test/manifest.json").isLocal());
    }

    /// Rejects blank source text and unsupported or unsafe manifest URLs before any I/O is attempted.
    ///
    /// @param value invalid source text or manifest URL.
    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "   ",
            "\t",
            "http://updates.example.test/manifest.json",
            "ftp://updates.example.test/manifest.json",
            "https://user:secret@updates.example.test/manifest.json",
            "https://@updates.example.test/manifest.json",
            "https://updates.example.test/manifest.json#fragment",
            "https://updates.example.test/manifest.json#",
            "https:manifest.json",
            "https:///manifest.json",
            "https://updates.example.test:0/manifest.json",
            "https://updates.example.test:65536/manifest.json",
            "https://["
    })
    public void rejectsUnsafeManifestUrls(String value) {
        assertThrows(IllegalArgumentException.class, () -> UpdateSource.parse(value));
    }

    /// Treats relative source text as a local path, including spaces and literal number signs.
    @Test
    public void parsesRelativeLocalPath() {
        Path path = Path.of("update fixtures", "nested", "..", "manifest #1.json");
        UpdateSource source = UpdateSource.parse(path.toString());

        assertTrue(source.isLocal());
        assertEquals(path.toAbsolutePath().normalize().toUri(), source.uri());
        assertEquals(path.toAbsolutePath().normalize().toString(), source.toString());
    }

    /// Parses both a local absolute path and its escaped file URI without changing the file identity.
    ///
    /// @param directory isolated local fixture directory.
    /// @throws IOException if the manifest cannot be created or resolved.
    @Test
    public void parsesAbsolutePathsAndFileUris(@TempDir Path directory) throws IOException {
        Path manifest = Files.writeString(directory.resolve("manifest #1.json"), "{}");
        UpdateSource fromPath = UpdateSource.parse(manifest.toString());
        UpdateSource fromUri = UpdateSource.parse(manifest.toUri().toString());

        assertEquals(UpdateSource.of(manifest), fromPath);
        assertEquals(fromPath, fromUri);
        assertTrue(fromPath.isLocal());
        assertTrue(fromPath.isAvailable());
        assertEquals(manifest.toAbsolutePath().normalize(), Path.of(fromUri.uri()));
    }

    /// Recognizes Windows drive paths with either separator instead of treating the drive as a scheme.
    @Test
    @EnabledOnOs(OS.WINDOWS)
    public void parsesWindowsDrivePaths() {
        Path expected = Path.of("C:\\update fixtures\\manifest.json");

        assertEquals(UpdateSource.of(expected), UpdateSource.parse("C:\\update fixtures\\manifest.json"));
        assertEquals(UpdateSource.of(expected), UpdateSource.parse("C:/update fixtures/nested/../manifest.json"));
    }

    /// Resolves relative, root-relative, cross-host, and absolute HTTPS references against the manifest URI.
    ///
    /// @param reference artifact reference from a manifest.
    /// @param expected resolved HTTPS URL.
    /// @throws IOException if a valid reference is rejected.
    @ParameterizedTest
    @CsvSource({
            "package.pkg, https://updates.example.test/feeds/stable/package.pkg",
            "../packages/package.pkg, https://updates.example.test/feeds/packages/package.pkg",
            "/packages/package.pkg, https://updates.example.test/packages/package.pkg",
            "//cdn.example.test/package.pkg, https://cdn.example.test/package.pkg",
            "https://cdn.example.test/package.pkg?download=1, https://cdn.example.test/package.pkg?download=1",
            "package%20one.pkg, https://updates.example.test/feeds/stable/package%20one.pkg"
    })
    public void resolvesHttpsArtifacts(String reference, String expected) throws IOException {
        UpdateSource source = UpdateSource.parse("https://updates.example.test/feeds/stable/manifest.json?channel=stable");

        assertEquals(URI.create(expected), source.resolve(reference));
    }

    /// Rejects remote references that change scheme, introduce credentials or fragments, or are malformed.
    ///
    /// @param reference unsafe artifact reference.
    @ParameterizedTest
    @ValueSource(strings = {
            "file:///tmp/package.pkg",
            "FILE:///tmp/package.pkg",
            "http://downloads.example.test/package.pkg",
            "HTTP://downloads.example.test/package.pkg",
            "ftp://downloads.example.test/package.pkg",
            "https://user:secret@downloads.example.test/package.pkg",
            "//user@downloads.example.test/package.pkg",
            "//downloads.example.test/package.pkg#fragment",
            "https://downloads.example.test/package.pkg#fragment",
            "package.pkg#fragment",
            "package.pkg#",
            "https:package.pkg",
            "https:///package.pkg",
            "https://downloads.example.test:0/package.pkg",
            "https://downloads.example.test:65536/package.pkg",
            "https://[",
            "package%ZZ.pkg",
            "package with spaces.pkg"
    })
    public void rejectsUnsafeRemoteArtifacts(String reference) {
        UpdateSource source = UpdateSource.parse("https://updates.example.test/feeds/manifest.json");

        assertThrows(IOException.class, () -> source.resolve(reference));
    }

    /// Resolves contained local files with path normalization and literal filename punctuation.
    ///
    /// @param directory isolated local fixture directory.
    /// @throws IOException if the fixture cannot be created or resolved.
    @Test
    public void resolvesContainedLocalArtifacts(@TempDir Path directory) throws IOException {
        Path manifest = Files.writeString(directory.resolve("manifest.json"), "{}");
        Path packages = Files.createDirectory(directory.resolve("packages"));
        Path artifact = Files.writeString(packages.resolve("package #1.pkg"), "package");
        UpdateSource source = UpdateSource.of(manifest);

        assertEquals(artifact.toRealPath().toUri(), source.resolve("packages/package #1.pkg"));
        assertEquals(artifact.toRealPath().toUri(), source.resolve("packages/../packages/package #1.pkg"));
    }

    /// Rejects parent traversal, sibling-prefix escapes, absolute paths, missing files, and directories.
    ///
    /// @param directory parent of the isolated manifest and outside directories.
    /// @throws IOException if the local fixture cannot be created.
    @Test
    public void rejectsLocalEscapesAndNonFiles(@TempDir Path directory) throws IOException {
        Path root = Files.createDirectory(directory.resolve("feed"));
        Path sibling = Files.createDirectory(directory.resolve("feed-other"));
        Path manifest = Files.writeString(root.resolve("manifest.json"), "{}");
        Path inside = Files.writeString(root.resolve("inside.pkg"), "inside");
        Files.writeString(directory.resolve("outside.pkg"), "outside");
        Files.writeString(sibling.resolve("outside.pkg"), "outside");
        Files.createDirectory(root.resolve("packages"));
        UpdateSource source = UpdateSource.of(manifest);

        assertThrows(IOException.class, () -> source.resolve("../outside.pkg"));
        assertThrows(IOException.class, () -> source.resolve("../feed-other/outside.pkg"));
        assertThrows(IOException.class, () -> source.resolve(inside.toAbsolutePath().toString()));
        assertThrows(IOException.class, () -> source.resolve("packages"));
        assertThrows(IOException.class, () -> source.resolve("missing.pkg"));
        assertThrows(IOException.class, () -> source.resolve("invalid\0.pkg"));
    }

    /// Allows explicit HTTPS artifacts for a local source while applying the remote credential policy.
    ///
    /// @param directory isolated local fixture directory.
    /// @throws IOException if a valid remote reference is rejected.
    @Test
    public void allowsExplicitHttpsArtifactsFromLocalSources(@TempDir Path directory) throws IOException {
        UpdateSource source = UpdateSource.of(directory.resolve("manifest.json"));
        URI remote = URI.create("https://cdn.example.test/package.pkg");

        assertEquals(remote, source.resolve(remote.toString()));
        assertThrows(IOException.class, () -> source.resolve("https://user@cdn.example.test/package.pkg"));
        assertThrows(IOException.class, () -> source.resolve("https://cdn.example.test/package.pkg#fragment"));
    }

    /// Rejects an escaping file symlink while accepting a symlink to a file within the real root.
    ///
    /// @param directory parent of isolated manifest and outside files.
    /// @throws IOException if a fixture operation other than unsupported link creation fails.
    @Test
    public void checksFileSymlinkContainment(@TempDir Path directory) throws IOException {
        Path root = Files.createDirectory(directory.resolve("feed"));
        Path manifest = Files.writeString(root.resolve("manifest.json"), "{}");
        Path inside = Files.writeString(root.resolve("inside.pkg"), "inside");
        Path outside = Files.writeString(directory.resolve("outside.pkg"), "outside");
        createSymbolicLinkOrSkip(root.resolve("inside-link.pkg"), inside);
        createSymbolicLinkOrSkip(root.resolve("outside-link.pkg"), outside);
        UpdateSource source = UpdateSource.of(manifest);

        assertEquals(inside.toRealPath().toUri(), source.resolve("inside-link.pkg"));
        assertThrows(IOException.class, () -> source.resolve("outside-link.pkg"));
    }

    /// Rejects a relative artifact reached through an escaping directory symlink.
    ///
    /// @param directory parent of isolated manifest and outside directories.
    /// @throws IOException if a fixture operation other than unsupported link creation fails.
    @Test
    public void rejectsDirectorySymlinkEscape(@TempDir Path directory) throws IOException {
        Path root = Files.createDirectory(directory.resolve("feed"));
        Path outside = Files.createDirectory(directory.resolve("feed-other"));
        Path manifest = Files.writeString(root.resolve("manifest.json"), "{}");
        Files.writeString(outside.resolve("package.pkg"), "outside");
        createSymbolicLinkOrSkip(root.resolve("packages"), outside);

        assertThrows(IOException.class, () -> UpdateSource.of(manifest).resolve("packages/package.pkg"));
    }

    /// Resolves artifacts relative to a symlinked manifest's real parent, not the link's parent.
    ///
    /// @param directory parent of isolated real and linked manifest directories.
    /// @throws IOException if a fixture operation other than unsupported link creation fails.
    @Test
    public void usesRealManifestDirectory(@TempDir Path directory) throws IOException {
        Path realRoot = Files.createDirectory(directory.resolve("real"));
        Path linkRoot = Files.createDirectory(directory.resolve("links"));
        Path manifest = Files.writeString(realRoot.resolve("manifest.json"), "{}");
        Path artifact = Files.writeString(realRoot.resolve("package.pkg"), "real");
        Files.writeString(linkRoot.resolve("package.pkg"), "decoy");
        Path link = linkRoot.resolve("manifest.json");
        createSymbolicLinkOrSkip(link, manifest);

        assertEquals(artifact.toRealPath().toUri(), UpdateSource.of(link).resolve("package.pkg"));
    }

    /// Reads empty and maximum-sized local manifests and rejects oversized or non-regular sources.
    ///
    /// @param directory isolated local fixture directory.
    /// @throws IOException if the fixture cannot be created or read.
    @Test
    public void boundsLocalReads(@TempDir Path directory) throws IOException {
        Path manifest = Files.createFile(directory.resolve("manifest.json"));
        UpdateSource source = UpdateSource.of(manifest);
        assertArrayEquals(new byte[0], source.read());

        byte @Unmodifiable [] bytes = new byte[1024 * 1024];
        Files.write(manifest, bytes);
        assertArrayEquals(bytes, source.read());
        Files.write(manifest, new byte[1024 * 1024 + 1]);
        assertThrows(IOException.class, source::read);
        assertFalse(UpdateSource.of(directory).isAvailable());
        assertThrows(IOException.class, UpdateSource.of(directory)::read);
        Files.delete(manifest);
        assertFalse(source.isAvailable());
        assertThrows(IOException.class, source::read);
    }

    /// Preserves a preexisting interrupt flag when rejecting a local manifest read.
    ///
    /// @param directory isolated local fixture directory.
    /// @throws IOException if the manifest cannot be created.
    @Test
    public void preservesReadInterruption(@TempDir Path directory) throws IOException {
        UpdateSource source = UpdateSource.of(Files.writeString(directory.resolve("manifest.json"), "{}"));
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class, source::read);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    /// Creates a fixture symlink, aborting only when link creation is unavailable or disallowed.
    ///
    /// @param link new fixture link path.
    /// @param target existing fixture target.
    private static void createSymbolicLinkOrSkip(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target.toAbsolutePath());
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            abort("Symbolic link creation is unavailable for this fixture: " + exception);
        }
    }
}

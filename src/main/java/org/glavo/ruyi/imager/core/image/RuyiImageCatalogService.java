// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.image;

import org.glavo.ruyi.imager.core.AppDirectories;
import org.glavo.ruyi.imager.core.ProgressEvent;
import org.glavo.ruyi.imager.core.ProgressReporter;
import org.glavo.ruyi.imager.core.StrategySupport;
import org.glavo.ruyi.imager.core.repo.RuyiRepositoryEntry;
import org.glavo.ruyi.imager.core.repo.RuyiRepositoryMetadata;
import org.glavo.ruyi.imager.core.repo.RuyiRepositoryStore;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseError;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// Ruyi-backed image catalog service.
@NotNullByDefault
public final class RuyiImageCatalogService implements ImageCatalogService {
    /// Application directories used to find metadata and downloads.
    private final AppDirectories directories;

    /// Repository store used to locate Ruyi metadata.
    private final RuyiRepositoryStore repositoryStore;

    /// Downloader used for Ruyi distfiles.
    private final RuyiDistfileDownloader downloader;

    /// Creates the catalog service.
    ///
    /// @param directories application directories.
    /// @param repositoryStore repository store.
    public RuyiImageCatalogService(AppDirectories directories, RuyiRepositoryStore repositoryStore) {
        this(directories, repositoryStore, new RuyiDistfileDownloader());
    }

    /// Creates the catalog service.
    ///
    /// @param directories application directories.
    /// @param repositoryStore repository store.
    /// @param downloader distfile downloader.
    public RuyiImageCatalogService(
            AppDirectories directories,
            RuyiRepositoryStore repositoryStore,
            RuyiDistfileDownloader downloader) {
        this.directories = directories;
        this.repositoryStore = repositoryStore;
        this.downloader = downloader;
    }

    /// Lists images from the local metadata cache.
    ///
    /// @return currently known image catalog.
    /// @throws IOException when local metadata cannot be read.
    @Override
    public ImageCatalog listImages() throws IOException {
        ArrayList<ImageEntry> images = new ArrayList<>();
        HashSet<String> seenPackages = new HashSet<>();

        for (RuyiRepositoryEntry entry : repositoryStore.readActiveEntries()) {
            Path root = repositoryStore.resolveRoot(entry);
            if (!Files.isDirectory(root)) {
                continue;
            }

            RuyiRepositoryMetadata metadata = repositoryStore.readMetadata(entry);
            for (ImageEntry image : readRepositoryImages(metadata)) {
                String key = image.category() + "/" + image.name() + "(" + image.version() + ")";
                if (seenPackages.add(key)) {
                    images.add(image);
                }
            }
        }

        images.sort(Comparator.comparing(ImageEntry::displayName).thenComparing(ImageEntry::atom));
        return new ImageCatalog(images);
    }

    /// Downloads a selected image.
    ///
    /// @param image image to download.
    /// @param reporter progress reporter.
    /// @return local image path.
    /// @throws IOException when the download directory cannot be prepared.
    @Override
    public Path downloadImage(ImageEntry image, ProgressReporter reporter) throws IOException {
        if (image.distfiles().isEmpty()) {
            throw new IOException("Image has no distfiles: " + image.atom());
        }

        Path downloadDirectory = directories.cacheDirectory()
                .resolve("downloads")
                .resolve(image.repoId())
                .resolve(image.category())
                .resolve(image.name())
                .resolve(image.version());
        Files.createDirectories(downloadDirectory);

        Path result = downloadDirectory;
        for (RuyiDistfile distfile : image.distfiles()) {
            result = downloader.download(distfile, downloadDirectory, reporter);
        }

        reporter.report(ProgressEvent.indeterminate("download", "Downloaded " + image.atom() + "."));
        return image.distfiles().size() == 1 ? result : downloadDirectory;
    }

    /// Reads all provisionable image manifests from one repository.
    ///
    /// @param metadata repository metadata.
    /// @return immutable image entries.
    /// @throws IOException when package manifests cannot be read.
    private static @Unmodifiable List<ImageEntry> readRepositoryImages(RuyiRepositoryMetadata metadata) throws IOException {
        @Nullable Path packageRoot = resolvePackageRoot(metadata.root());
        if (packageRoot == null) {
            return List.of();
        }

        ArrayList<ImageEntry> result = new ArrayList<>();
        try (var categories = Files.newDirectoryStream(packageRoot, Files::isDirectory)) {
            for (Path categoryPath : categories) {
                readCategory(metadata, categoryPath, result);
            }
        }
        return List.copyOf(result);
    }

    /// Resolves the package metadata root.
    ///
    /// @param repositoryRoot repository root.
    /// @return package root, or null when no package root exists.
    private static @Nullable Path resolvePackageRoot(Path repositoryRoot) {
        Path packages = repositoryRoot.resolve("packages");
        if (Files.isDirectory(packages)) {
            return packages;
        }

        Path manifests = repositoryRoot.resolve("manifests");
        return Files.isDirectory(manifests) ? manifests : null;
    }

    /// Reads all package manifests under one category.
    ///
    /// @param metadata repository metadata.
    /// @param categoryPath category path.
    /// @param result mutable output list.
    /// @throws IOException when manifests cannot be read.
    private static void readCategory(
            RuyiRepositoryMetadata metadata,
            Path categoryPath,
            ArrayList<ImageEntry> result) throws IOException {
        String category = categoryPath.getFileName().toString();
        try (var packages = Files.newDirectoryStream(categoryPath, Files::isDirectory)) {
            for (Path packagePath : packages) {
                readPackage(metadata, category, packagePath, result);
            }
        }
    }

    /// Reads all versions of one package.
    ///
    /// @param metadata repository metadata.
    /// @param category package category.
    /// @param packagePath package path.
    /// @param result mutable output list.
    /// @throws IOException when manifests cannot be read.
    private static void readPackage(
            RuyiRepositoryMetadata metadata,
            String category,
            Path packagePath,
            ArrayList<ImageEntry> result) throws IOException {
        String name = packagePath.getFileName().toString();
        try (var versions = Files.newDirectoryStream(packagePath, "*.toml")) {
            for (Path manifestPath : versions) {
                String fileName = manifestPath.getFileName().toString();
                if (!startsWithDigit(fileName)) {
                    continue;
                }

                String version = fileName.substring(0, fileName.length() - ".toml".length());
                @Nullable ImageEntry image = readImageManifest(metadata, category, name, version, manifestPath);
                if (image != null) {
                    result.add(image);
                }
            }
        }
    }

    /// Reads one provisionable image manifest.
    ///
    /// @param metadata repository metadata.
    /// @param category package category.
    /// @param name package name.
    /// @param version package version.
    /// @param manifestPath manifest path.
    /// @return image entry, or null when the package is not provisionable.
    /// @throws IOException when the manifest cannot be parsed.
    private static @Nullable ImageEntry readImageManifest(
            RuyiRepositoryMetadata metadata,
            String category,
            String name,
            String version,
            Path manifestPath) throws IOException {
        TomlParseResult manifest = parseToml(manifestPath);
        @Nullable TomlTable provisionable = manifest.getTable("provisionable");
        if (provisionable == null || !hasKind(manifest, "provisionable")) {
            return null;
        }

        @Nullable String strategy = provisionable.getString("strategy");
        if (strategy == null || strategy.isBlank()) {
            return null;
        }

        @Nullable TomlTable partitionMapTable = provisionable.getTable("partition_map");
        if (partitionMapTable == null || partitionMapTable.isEmpty()) {
            return null;
        }

        @Unmodifiable Map<String, String> partitionMap = readStringMap(partitionMapTable);
        @Unmodifiable List<RuyiDistfile> distfiles = readDistfiles(metadata, manifest);
        String displayName = readDisplayName(manifest, category, name, version);
        String atom = category + "/" + name + "(" + version + ")";
        return new ImageEntry(
                metadata.id(),
                category,
                name,
                version,
                atom,
                displayName,
                deriveBoardName(category, name),
                deriveVariantName(name),
                strategy,
                partitionMap,
                distfiles,
                classifyStrategy(strategy));
    }

    /// Checks whether a manifest has a package kind.
    ///
    /// @param manifest package manifest.
    /// @param kind package kind.
    /// @return whether the manifest has the package kind.
    private static boolean hasKind(TomlTable manifest, String kind) {
        @Nullable TomlArray kinds = manifest.getArray("kind");
        if (kinds == null) {
            return manifest.getTable(kind) != null;
        }

        for (int i = 0; i < kinds.size(); i++) {
            Object value = kinds.get(i);
            if (kind.equals(value)) {
                return true;
            }
        }
        return manifest.getTable(kind) != null;
    }

    /// Reads distfile declarations.
    ///
    /// @param metadata repository metadata.
    /// @param manifest package manifest.
    /// @return immutable distfile list.
    private static @Unmodifiable List<RuyiDistfile> readDistfiles(
            RuyiRepositoryMetadata metadata,
            TomlTable manifest) {
        @Nullable TomlArray array = manifest.getArray("distfiles");
        if (array == null) {
            return List.of();
        }

        ArrayList<RuyiDistfile> result = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            Object value = array.get(i);
            if (!(value instanceof TomlTable table)) {
                continue;
            }

            @Nullable String name = table.getString("name");
            if (name == null || name.isBlank()) {
                continue;
            }

            @Unmodifiable Set<String> restricts = readRestricts(table);
            @Unmodifiable List<String> declaredUrls = readStringArray(table.getArray("urls"));
            @Nullable Long size = table.getLong("size");
            @Nullable TomlTable checksumsTable = table.getTable("checksums");
            @Unmodifiable Map<String, String> checksums = checksumsTable == null ? Map.of() : readStringMap(checksumsTable);
            @Nullable Long stripComponents = table.getLong("strip_components");
            result.add(new RuyiDistfile(
                    name,
                    metadata.resolveDistfileUrls(name, declaredUrls, restricts.contains("mirror")),
                    size,
                    checksums,
                    restricts.contains("fetch"),
                    restricts.contains("mirror"),
                    stripComponents == null ? 1 : Math.toIntExact(stripComponents),
                    table.getString("unpack")));
        }
        return List.copyOf(result);
    }

    /// Reads a restrict field that may be either a string or a string array.
    ///
    /// @param table distfile table.
    /// @return immutable restrict set.
    private static @Unmodifiable Set<String> readRestricts(TomlTable table) {
        Object value = table.get("restrict");
        if (value instanceof String text) {
            return Set.of(text);
        }

        if (value instanceof TomlArray array) {
            return Set.copyOf(readStringArray(array));
        }

        return Set.of();
    }

    /// Reads a string array.
    ///
    /// @param array TOML array.
    /// @return immutable string list.
    private static @Unmodifiable List<String> readStringArray(@Nullable TomlArray array) {
        if (array == null) {
            return List.of();
        }

        ArrayList<String> result = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            Object value = array.get(i);
            if (value instanceof String text) {
                result.add(text);
            }
        }
        return List.copyOf(result);
    }

    /// Reads a string map from a TOML table.
    ///
    /// @param table TOML table.
    /// @return immutable string map.
    private static @Unmodifiable Map<String, String> readStringMap(TomlTable table) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        for (String key : table.keySet()) {
            Object value = table.get(key);
            if (value instanceof String text) {
                result.put(key, text);
            }
        }
        return Map.copyOf(result);
    }

    /// Reads a display name from manifest metadata.
    ///
    /// @param manifest package manifest.
    /// @param category package category.
    /// @param name package name.
    /// @param version package version.
    /// @return human-readable display name.
    private static String readDisplayName(TomlTable manifest, String category, String name, String version) {
        @Nullable TomlTable metadata = manifest.getTable("metadata");
        if (metadata != null) {
            @Nullable String description = metadata.getString("desc");
            if (description != null && !description.isBlank()) {
                return description;
            }
        }

        return category + "/" + name + " " + version;
    }

    /// Derives a board label from Ruyi image naming conventions.
    ///
    /// @param category package category.
    /// @param name package name.
    /// @return board label.
    private static String deriveBoardName(String category, String name) {
        if (!"board-image".equals(category)) {
            return name;
        }

        String[] fragments = name.split("-");
        if (fragments.length >= 3) {
            return fragments[fragments.length - 2] + "-" + fragments[fragments.length - 1];
        }
        return name;
    }

    /// Derives a variant label from Ruyi image naming conventions.
    ///
    /// @param name package name.
    /// @return variant label.
    private static String deriveVariantName(String name) {
        String[] fragments = name.split("-");
        if (fragments.length >= 4 && fragments[fragments.length - 1].matches("[0-9]+g")) {
            return fragments[fragments.length - 1];
        }
        return "generic";
    }

    /// Classifies support for a provision strategy.
    ///
    /// @param strategy strategy name.
    /// @return support classification.
    private static StrategySupport classifyStrategy(String strategy) {
        return switch (strategy) {
            case "dd-v1", "fastboot-v1", "fastboot-v1(lpi4a-uboot)" -> StrategySupport.SUPPORTED;
            default -> StrategySupport.UNKNOWN;
        };
    }

    /// Checks whether a file name starts with a digit.
    ///
    /// @param fileName file name.
    /// @return whether the file name starts with a digit.
    private static boolean startsWithDigit(String fileName) {
        return !fileName.isEmpty() && Character.isDigit(fileName.charAt(0));
    }

    /// Parses a TOML file and reports syntax errors.
    ///
    /// @param path TOML file path.
    /// @return TOML parse result.
    /// @throws IOException when the file cannot be read or parsed.
    private static TomlParseResult parseToml(Path path) throws IOException {
        TomlParseResult result = Toml.parse(path);
        if (result.hasErrors()) {
            StringBuilder builder = new StringBuilder("Failed to parse TOML file ").append(path).append(':');
            for (TomlParseError error : result.errors()) {
                builder.append(System.lineSeparator()).append(error);
            }
            throw new IOException(builder.toString());
        }
        return result;
    }
}

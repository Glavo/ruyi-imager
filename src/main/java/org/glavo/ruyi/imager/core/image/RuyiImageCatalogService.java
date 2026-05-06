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
import org.glavo.ruyi.imager.i18n.Messages;
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
import java.util.logging.Logger;

/// Ruyi-backed image catalog service.
@NotNullByDefault
public final class RuyiImageCatalogService implements ImageCatalogService {
    /// Logger for image catalog operations.
    private static final Logger LOGGER = Logger.getLogger(RuyiImageCatalogService.class.getName());

    /// Known Ruyi device vendor id to display-name mapping.
    private static final @Unmodifiable Map<String, String> DEVICE_MANUFACTURERS = Map.ofEntries(
            Map.entry("awol", "Allwinner"),
            Map.entry("canaan", "Canaan"),
            Map.entry("milkv", "Milk-V"),
            Map.entry("pine64", "Pine64"),
            Map.entry("sifive", "SiFive"),
            Map.entry("sipeed", "Sipeed"),
            Map.entry("spacemit", "SpacemiT"),
            Map.entry("starfive", "StarFive"),
            Map.entry("wch", "WinChipHead"));

    /// Application directories used to find metadata and downloads.
    private final AppDirectories directories;

    /// Repository store used to locate Ruyi metadata.
    private final RuyiRepositoryStore repositoryStore;

    /// Downloader used for Ruyi distfiles.
    private final RuyiDistfileDownloader downloader;

    /// Materializer used to prepare flashable artifacts.
    private final RuyiImageMaterializer materializer;

    /// Cached catalog snapshot read from repository metadata.
    private @Nullable ImageCatalog cachedCatalog;

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
        this(directories, repositoryStore, downloader, new RuyiImageMaterializer());
    }

    /// Creates the catalog service.
    ///
    /// @param directories application directories.
    /// @param repositoryStore repository store.
    /// @param downloader distfile downloader.
    /// @param materializer image materializer.
    public RuyiImageCatalogService(
            AppDirectories directories,
            RuyiRepositoryStore repositoryStore,
            RuyiDistfileDownloader downloader,
            RuyiImageMaterializer materializer) {
        this.directories = directories;
        this.repositoryStore = repositoryStore;
        this.downloader = downloader;
        this.materializer = materializer;
    }

    /// Lists images from the local metadata cache.
    ///
    /// @return currently known image catalog.
    /// @throws IOException when local metadata cannot be read.
    @Override
    public synchronized ImageCatalog listImages() throws IOException {
        ImageCatalog catalog = cachedCatalog;
        if (catalog != null) {
            int imageCount = catalog.images().size();
            LOGGER.fine(() -> "Using cached image catalog. images=" + imageCount);
            return catalog;
        }

        catalog = loadImages();
        cachedCatalog = catalog;
        return catalog;
    }

    /// Invalidates the in-memory image catalog cache.
    @Override
    public synchronized void invalidateCache() {
        cachedCatalog = null;
        LOGGER.info("Image catalog cache invalidated.");
    }

    /// Loads images from repository metadata.
    ///
    /// @return loaded image catalog.
    /// @throws IOException when local metadata cannot be read.
    private ImageCatalog loadImages() throws IOException {
        LOGGER.info("Listing image catalog.");
        ArrayList<ImageEntry> images = new ArrayList<>();
        HashSet<String> seenPackages = new HashSet<>();

        for (RuyiRepositoryEntry entry : repositoryStore.readActiveEntries()) {
            Path root = repositoryStore.resolveRoot(entry);
            if (!Files.isDirectory(root)) {
                LOGGER.info(() -> "Skipping repository without local root. id=" + entry.id() + ", root=" + root);
                continue;
            }

            RuyiRepositoryMetadata metadata = repositoryStore.readMetadata(entry);
            int beforeCount = images.size();
            for (ImageEntry image : readRepositoryImages(metadata)) {
                String key = image.category() + "/" + image.name() + "(" + image.version() + ")";
                if (seenPackages.add(key)) {
                    images.add(image);
                }
            }
            int addedCount = images.size() - beforeCount;
            LOGGER.info(() -> "Repository images loaded. repo=" + entry.id() + ", added=" + addedCount);
        }

        images.sort(Comparator.comparing(ImageEntry::displayName).thenComparing(ImageEntry::atom));
        LOGGER.info(() -> "Image catalog listed. images=" + images.size());
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
            LOGGER.warning(() -> "Image has no distfiles. atom=" + image.atom());
            throw new IOException(Messages.get("core.download.imageNoDistfiles", image.atom()));
        }

        Path downloadDirectory = downloadDirectory(image);
        Files.createDirectories(downloadDirectory);
        LOGGER.info(() -> "Downloading image. atom="
                + image.atom()
                + ", distfiles="
                + image.distfiles().size()
                + ", downloadDirectory="
                + downloadDirectory);

        ArrayList<Path> downloadedDistfiles = new ArrayList<>();
        for (RuyiDistfile distfile : image.distfiles()) {
            downloadedDistfiles.add(downloader.download(distfile, downloadDirectory, reporter));
        }

        Path artifactDirectory = directories.cacheDirectory()
                .resolve("artifacts")
                .resolve(image.repoId())
                .resolve(image.category())
                .resolve(image.name())
                .resolve(image.version());
        Path result = materializer.materialize(image, List.copyOf(downloadedDistfiles), artifactDirectory, reporter);
        reporter.report(ProgressEvent.indeterminate("download", Messages.get("core.download.imageComplete", image.atom())));
        LOGGER.info(() -> "Image download completed. atom=" + image.atom() + ", artifact=" + result);
        return result;
    }

    /// Returns a lightweight cache status for one image.
    ///
    /// @param image image to inspect.
    /// @return image cache status.
    /// @throws IOException when the cache cannot be inspected.
    @Override
    public ImageCacheStatus cacheStatus(ImageEntry image) throws IOException {
        List<RuyiDistfile> distfiles = image.distfiles();
        if (distfiles.isEmpty()) {
            LOGGER.fine(() -> "Cache status unknown for image without distfiles. atom=" + image.atom());
            return ImageCacheStatus.unknown(0);
        }

        Path downloadDirectory = downloadDirectory(image);
        int cachedDistfiles = 0;
        long cachedBytes = 0L;
        long expectedBytes = 0L;
        boolean totalBytesKnown = true;
        boolean partialCachePresent = false;
        boolean manualDownloadRequired = false;

        for (RuyiDistfile distfile : distfiles) {
            @Nullable Long distfileSize = distfile.sizeBytes();
            if (distfileSize == null) {
                totalBytesKnown = false;
            } else {
                expectedBytes += distfileSize;
            }

            Path target = downloadDirectory.resolve(distfile.name());
            boolean cached = false;
            if (Files.isRegularFile(target)) {
                long size = Files.size(target);
                if (distfileSize == null || size == distfileSize) {
                    cached = true;
                    cachedDistfiles++;
                    cachedBytes += size;
                }
            }

            if (!cached) {
                if (distfile.fetchRestricted()) {
                    manualDownloadRequired = true;
                }

                Path partial = downloadDirectory.resolve(distfile.name() + ".part");
                if (Files.isRegularFile(partial)) {
                    long partialBytes = Files.size(partial);
                    if (distfileSize == null || partialBytes <= distfileSize) {
                        cachedBytes += partialBytes;
                        partialCachePresent = partialCachePresent || partialBytes > 0L;
                    }
                }
            }
        }

        ImageCacheStatus.State state;
        if (cachedDistfiles == distfiles.size()) {
            state = ImageCacheStatus.State.COMPLETE;
        } else if (manualDownloadRequired) {
            state = ImageCacheStatus.State.MANUAL_REQUIRED;
        } else if (cachedDistfiles > 0 || partialCachePresent) {
            state = ImageCacheStatus.State.PARTIAL;
        } else {
            state = ImageCacheStatus.State.EMPTY;
        }

        ImageCacheStatus status = new ImageCacheStatus(
                state,
                cachedDistfiles,
                distfiles.size(),
                cachedBytes,
                totalBytesKnown ? expectedBytes : null);
        LOGGER.fine(() -> "Image cache status. atom="
                + image.atom()
                + ", state="
                + status.state()
                + ", cachedDistfiles="
                + status.cachedDistfiles()
                + ", totalDistfiles="
                + status.totalDistfiles()
                + ", cachedBytes="
                + status.cachedBytes());
        return status;
    }

    /// Resolves the download cache directory for one image.
    ///
    /// @param image image entry.
    /// @return download cache directory.
    private Path downloadDirectory(ImageEntry image) {
        return directories.cacheDirectory()
                .resolve("downloads")
                .resolve(image.repoId())
                .resolve(image.category())
                .resolve(image.name())
                .resolve(image.version());
    }

    /// Reads all provisionable image manifests from one repository.
    ///
    /// @param metadata repository metadata.
    /// @return immutable image entries.
    /// @throws IOException when package manifests cannot be read.
    private static @Unmodifiable List<ImageEntry> readRepositoryImages(RuyiRepositoryMetadata metadata) throws IOException {
        @Nullable Path packageRoot = resolvePackageRoot(metadata.root());
        if (packageRoot == null) {
            LOGGER.info(() -> "Repository has no package metadata root. repo=" + metadata.id() + ", root=" + metadata.root());
            return List.of();
        }

        @Unmodifiable List<String> deviceIds = readDeviceIds(metadata.root());
        LOGGER.info(() -> "Reading repository images. repo="
                + metadata.id()
                + ", packageRoot="
                + packageRoot
                + ", deviceIds="
                + deviceIds.size());
        ArrayList<ImageEntry> result = new ArrayList<>();
        try (var categories = Files.newDirectoryStream(packageRoot, Files::isDirectory)) {
            for (Path categoryPath : categories) {
                readCategory(metadata, categoryPath, deviceIds, result);
            }
        }
        LOGGER.info(() -> "Repository image manifests read. repo=" + metadata.id() + ", images=" + result.size());
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

    /// Reads known Ruyi device ids from repository entity metadata.
    ///
    /// @param repositoryRoot repository root.
    /// @return immutable device ids sorted by longest match first.
    /// @throws IOException when device metadata cannot be read.
    private static @Unmodifiable List<String> readDeviceIds(Path repositoryRoot) throws IOException {
        Path deviceRoot = repositoryRoot.resolve("entities").resolve("device");
        if (!Files.isDirectory(deviceRoot)) {
            return List.of();
        }

        HashSet<String> seen = new HashSet<>();
        ArrayList<String> result = new ArrayList<>();
        try (var files = Files.newDirectoryStream(deviceRoot, "*.toml")) {
            for (Path file : files) {
                @Nullable String id = readDeviceId(file);
                if (id != null && seen.add(id)) {
                    result.add(id);
                }
            }
        }
        result.sort(Comparator
                .<String>comparingInt(RuyiImageCatalogService::fragmentCount)
                .thenComparingInt(String::length)
                .reversed());
        return List.copyOf(result);
    }

    /// Reads one device id from an entity file.
    ///
    /// @param path entity file path.
    /// @return device id, or null when absent.
    /// @throws IOException when the entity file cannot be parsed.
    private static @Nullable String readDeviceId(Path path) throws IOException {
        TomlParseResult entity = parseToml(path);
        @Nullable TomlTable device = entity.getTable("device");
        if (device == null) {
            return null;
        }

        @Nullable String id = device.getString("id");
        return id == null || id.isBlank() ? null : id;
    }

    /// Counts hyphen-separated fragments in an id.
    ///
    /// @param id id to inspect.
    /// @return fragment count.
    private static int fragmentCount(String id) {
        int count = 1;
        for (int i = 0; i < id.length(); i++) {
            if (id.charAt(i) == '-') {
                count++;
            }
        }
        return count;
    }

    /// Reads all package manifests under one category.
    ///
    /// @param metadata repository metadata.
    /// @param categoryPath category path.
    /// @param deviceIds known device ids.
    /// @param result mutable output list.
    /// @throws IOException when manifests cannot be read.
    private static void readCategory(
            RuyiRepositoryMetadata metadata,
            Path categoryPath,
            @Unmodifiable List<String> deviceIds,
            ArrayList<ImageEntry> result) throws IOException {
        String category = categoryPath.getFileName().toString();
        try (var packages = Files.newDirectoryStream(categoryPath, Files::isDirectory)) {
            for (Path packagePath : packages) {
                readPackage(metadata, category, packagePath, deviceIds, result);
            }
        }
    }

    /// Reads all versions of one package.
    ///
    /// @param metadata repository metadata.
    /// @param category package category.
    /// @param packagePath package path.
    /// @param deviceIds known device ids.
    /// @param result mutable output list.
    /// @throws IOException when manifests cannot be read.
    private static void readPackage(
            RuyiRepositoryMetadata metadata,
            String category,
            Path packagePath,
            @Unmodifiable List<String> deviceIds,
            ArrayList<ImageEntry> result) throws IOException {
        String name = packagePath.getFileName().toString();
        try (var versions = Files.newDirectoryStream(packagePath, "*.toml")) {
            for (Path manifestPath : versions) {
                String fileName = manifestPath.getFileName().toString();
                if (!startsWithDigit(fileName)) {
                    continue;
                }

                String version = fileName.substring(0, fileName.length() - ".toml".length());
                @Nullable ImageEntry image = readImageManifest(metadata, category, name, version, manifestPath, deviceIds);
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
    /// @param deviceIds known device ids.
    /// @return image entry, or null when the package is not provisionable.
    /// @throws IOException when the manifest cannot be parsed.
    private static @Nullable ImageEntry readImageManifest(
            RuyiRepositoryMetadata metadata,
            String category,
            String name,
            String version,
            Path manifestPath,
            @Unmodifiable List<String> deviceIds) throws IOException {
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
        @Nullable String slug = readSlug(manifest);
        String displayName = readDisplayName(manifest, category, name, version);
        @Nullable DeviceNameParts deviceName = parseDeviceName(category, name, deviceIds);
        String boardName = deriveBoardName(category, name, deviceName);
        String manufacturer = deriveManufacturer(category, name, manifest, metadata.id(), deviceName);
        String atom = category + "/" + name + "(" + version + ")";
        return new ImageEntry(
                metadata.id(),
                category,
                name,
                version,
                slug,
                atom,
                displayName,
                manufacturer,
                boardName,
                deriveVariantName(category, name, deviceName),
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

        @Unmodifiable Map<String, @Unmodifiable Map<String, String>> repoMessages =
                readRepoMessages(metadata.root().resolve("messages.toml"));
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
            @Unmodifiable List<String> prefixesToUnpack = readStringArray(table.getArray("prefixes_to_unpack"));
            @Nullable RuyiFetchRestriction fetchRestriction =
                    readFetchRestriction(table.getTable("fetch_restriction"), repoMessages);
            result.add(new RuyiDistfile(
                    name,
                    metadata.resolveDistfileUrls(name, declaredUrls, restricts.contains("mirror")),
                    size,
                    checksums,
                    restricts.contains("fetch"),
                    restricts.contains("mirror"),
                    fetchRestriction,
                    stripComponents == null ? 1 : Math.toIntExact(stripComponents),
                    prefixesToUnpack,
                    table.getString("unpack")));
        }
        return List.copyOf(result);
    }

    /// Reads a fetch restriction declaration.
    ///
    /// @param table fetch restriction table.
    /// @param repoMessages repository messages keyed by message id and language code.
    /// @return fetch restriction, or null when absent or incomplete.
    private static @Nullable RuyiFetchRestriction readFetchRestriction(
            @Nullable TomlTable table,
            @Unmodifiable Map<String, @Unmodifiable Map<String, String>> repoMessages) {
        if (table == null) {
            return null;
        }

        @Nullable String msgid = table.getString("msgid");
        if (msgid == null || msgid.isBlank()) {
            return null;
        }

        @Nullable Map<String, String> templates = repoMessages.get(msgid);
        if (templates == null || templates.isEmpty()) {
            return null;
        }

        @Nullable TomlTable paramsTable = table.getTable("params");
        @Unmodifiable Map<String, String> params = paramsTable == null ? Map.of() : readStringMap(paramsTable);
        return new RuyiFetchRestriction(templates, params);
    }

    /// Reads repository message templates.
    ///
    /// @param path messages.toml path.
    /// @return messages keyed by message id and language code.
    private static @Unmodifiable Map<String, @Unmodifiable Map<String, String>> readRepoMessages(Path path) {
        if (!Files.isRegularFile(path)) {
            return Map.of();
        }

        TomlParseResult messages;
        try {
            messages = Toml.parse(path);
        } catch (IOException e) {
            return Map.of();
        }
        if (messages.hasErrors() || !"v1".equals(messages.get(List.of("ruyi-repo-messages")))) {
            return Map.of();
        }

        LinkedHashMap<String, @Unmodifiable Map<String, String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : messages.entrySet()) {
            String key = entry.getKey();
            if ("ruyi-repo-messages".equals(key)) {
                continue;
            }
            if (entry.getValue() instanceof TomlTable table) {
                @Unmodifiable Map<String, String> values = readStringMap(table);
                if (!values.isEmpty()) {
                    result.put(key, values);
                }
            }
        }
        return Map.copyOf(result);
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

    /// Derives the target board manufacturer.
    ///
    /// @param category package category.
    /// @param name package name.
    /// @param manifest package manifest.
    /// @param fallback fallback manufacturer name.
    /// @param deviceName parsed device name from entity metadata.
    /// @return manufacturer name.
    private static String deriveManufacturer(
            String category,
            String name,
            TomlTable manifest,
            String fallback,
            @Nullable DeviceNameParts deviceName) {
        if (deviceName != null) {
            @Nullable String manufacturer = DEVICE_MANUFACTURERS.get(firstFragment(deviceName.deviceId()));
            if (manufacturer != null) {
                return manufacturer;
            }
        }

        if ("board-image".equals(category)) {
            String[] fragments = name.split("-");
            int vendorIndex = deviceVendorIndex(fragments);
            if (vendorIndex >= 0) {
                @Nullable String manufacturer = DEVICE_MANUFACTURERS.get(fragments[vendorIndex]);
                if (manufacturer != null) {
                    return manufacturer;
                }
            }
        }

        return readPackageVendor(manifest, fallback);
    }

    /// Reads the package vendor name from manifest metadata.
    ///
    /// @param manifest package manifest.
    /// @param fallback fallback vendor name.
    /// @return package vendor name.
    private static String readPackageVendor(TomlTable manifest, String fallback) {
        @Nullable TomlTable metadata = manifest.getTable("metadata");
        if (metadata != null) {
            @Nullable TomlTable vendor = metadata.getTable("vendor");
            if (vendor != null) {
                @Nullable String name = vendor.getString("name");
                if (name != null && !name.isBlank()) {
                    return name;
                }
            }
        }

        @Nullable TomlTable vendor = manifest.getTable("vendor");
        if (vendor != null) {
            @Nullable String name = vendor.getString("name");
            if (name != null && !name.isBlank()) {
                return name;
            }
        }

        return fallback;
    }

    /// Reads a deprecated package slug from manifest metadata.
    ///
    /// @param manifest package manifest.
    /// @return slug, or null when absent.
    private static @Nullable String readSlug(TomlTable manifest) {
        @Nullable TomlTable metadata = manifest.getTable("metadata");
        if (metadata != null) {
            @Nullable String slug = metadata.getString("slug");
            if (slug != null && !slug.isBlank()) {
                return slug;
            }
        }

        @Nullable String legacySlug = manifest.getString("slug");
        return legacySlug == null || legacySlug.isBlank() ? null : legacySlug;
    }

    /// Derives a board label from Ruyi image naming conventions.
    ///
    /// @param category package category.
    /// @param name package name.
    /// @param deviceName parsed device name from entity metadata.
    /// @return board label.
    private static String deriveBoardName(String category, String name, @Nullable DeviceNameParts deviceName) {
        if (!"board-image".equals(category)) {
            return name;
        }
        if (deviceName != null) {
            return deviceName.deviceId();
        }

        String[] fragments = name.split("-");
        int vendorIndex = deviceVendorIndex(fragments);
        if (vendorIndex >= 0) {
            return joinFragments(fragments, vendorIndex, deviceEndIndex(fragments, vendorIndex));
        }
        return name;
    }

    /// Derives a variant label from Ruyi image naming conventions.
    ///
    /// @param category package category.
    /// @param name package name.
    /// @param deviceName parsed device name from entity metadata.
    /// @return variant label.
    private static String deriveVariantName(String category, String name, @Nullable DeviceNameParts deviceName) {
        if (deviceName != null) {
            return deviceName.variant();
        }

        String[] fragments = name.split("-");
        int vendorIndex = "board-image".equals(category) ? deviceVendorIndex(fragments) : -1;
        if (vendorIndex >= 0
                && deviceEndIndex(fragments, vendorIndex) < fragments.length) {
            return fragments[fragments.length - 1];
        }
        if (fragments.length >= 4 && isVariantFragment(fragments[fragments.length - 1])) {
            return fragments[fragments.length - 1];
        }
        return "generic";
    }

    /// Parses the device id and variant from a board-image package name.
    ///
    /// @param category package category.
    /// @param name package name.
    /// @param deviceIds known device ids sorted by longest match first.
    /// @return parsed device name, or null when no known device id matches.
    private static @Nullable DeviceNameParts parseDeviceName(
            String category,
            String name,
            @Unmodifiable List<String> deviceIds) {
        if (!"board-image".equals(category)) {
            return null;
        }

        for (String deviceId : deviceIds) {
            @Nullable DeviceNameParts deviceName = matchDeviceId(name, deviceId);
            if (deviceName != null) {
                return deviceName;
            }
        }
        return null;
    }

    /// Matches one device id in a board-image package name.
    ///
    /// @param name package name.
    /// @param deviceId device id to match.
    /// @return parsed device name, or null when the device id is not present.
    private static @Nullable DeviceNameParts matchDeviceId(String name, String deviceId) {
        if (name.equals(deviceId)) {
            return new DeviceNameParts(deviceId, "generic");
        }

        String marker = "-" + deviceId;
        int fromIndex = 0;
        while (fromIndex < name.length()) {
            int markerIndex = name.indexOf(marker, fromIndex);
            if (markerIndex < 0) {
                return null;
            }

            int deviceStart = markerIndex + 1;
            int deviceEnd = deviceStart + deviceId.length();
            if (deviceEnd == name.length()) {
                return new DeviceNameParts(deviceId, "generic");
            }
            if (name.charAt(deviceEnd) == '-') {
                String variant = name.substring(deviceEnd + 1);
                if (!variant.isBlank()) {
                    return new DeviceNameParts(deviceId, variant);
                }
            }
            fromIndex = markerIndex + 1;
        }
        return null;
    }

    /// Returns the first hyphen-separated fragment.
    ///
    /// @param text text to inspect.
    /// @return first fragment.
    private static String firstFragment(String text) {
        int hyphen = text.indexOf('-');
        return hyphen < 0 ? text : text.substring(0, hyphen);
    }

    /// Finds the vendor id fragment in a board-image package name.
    ///
    /// @param fragments package name fragments.
    /// @return fragment index, or -1 when no known vendor id is present.
    private static int deviceVendorIndex(String @Unmodifiable [] fragments) {
        for (int i = 0; i < fragments.length; i++) {
            if (DEVICE_MANUFACTURERS.containsKey(fragments[i])) {
                return i;
            }
        }
        return -1;
    }

    /// Returns the exclusive device-id end index in package name fragments.
    ///
    /// @param fragments package name fragments.
    /// @param vendorIndex index of the device vendor fragment.
    /// @return exclusive device-id end index.
    private static int deviceEndIndex(String @Unmodifiable [] fragments, int vendorIndex) {
        if (fragments.length - vendorIndex >= 3 && isVariantFragment(fragments[fragments.length - 1])) {
            return fragments.length - 1;
        }
        return fragments.length;
    }

    /// Returns whether one package-name fragment is a known compact variant marker.
    ///
    /// @param fragment package-name fragment.
    /// @return whether the fragment is a variant marker.
    private static boolean isVariantFragment(String fragment) {
        return fragment.matches("[0-9]+g");
    }

    /// Joins package name fragments with hyphens.
    ///
    /// @param fragments package name fragments.
    /// @param start inclusive start index.
    /// @param end exclusive end index.
    /// @return joined fragment range.
    private static String joinFragments(String @Unmodifiable [] fragments, int start, int end) {
        StringBuilder builder = new StringBuilder();
        for (int i = start; i < end; i++) {
            if (builder.length() > 0) {
                builder.append('-');
            }
            builder.append(fragments[i]);
        }
        return builder.toString();
    }

    /// Device and variant parsed from a board-image package name.
    ///
    /// @param deviceId Ruyi device id.
    /// @param variant package variant, or `generic` when absent.
    @NotNullByDefault
    private record DeviceNameParts(String deviceId, String variant) {
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
            LOGGER.warning(() -> "TOML parse failed. path=" + path + ", errors=" + result.errors().size());
            StringBuilder builder = new StringBuilder(Messages.get("core.toml.parseFailed", path));
            for (TomlParseError error : result.errors()) {
                builder.append(System.lineSeparator()).append(error);
            }
            throw new IOException(builder.toString());
        }
        return result;
    }
}

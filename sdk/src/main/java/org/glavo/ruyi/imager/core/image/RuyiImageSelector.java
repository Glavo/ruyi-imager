// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.image;

import org.glavo.ruyi.imager.i18n.Messages;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Selects images using Ruyi atom syntax.
@NotNullByDefault
public final class RuyiImageSelector {
    /// Atom expression pattern.
    private static final Pattern ATOM_EXPR = Pattern.compile("^([^:(]+)\\((.+)\\)$");

    /// Plain atom name pattern.
    private static final Pattern ATOM_NAME = Pattern.compile("^[^:()]+$");

    /// Prevents construction of the selector utility.
    private RuyiImageSelector() {
    }

    /// Finds one image matching an atom.
    ///
    /// @param catalog image catalog.
    /// @param atom input atom.
    /// @return matching image, or null when not found.
    public static @Nullable ImageEntry find(ImageCatalog catalog, String atom) {
        for (ImageEntry image : catalog.images()) {
            if (image.atom().equals(atom)) {
                return image;
            }
        }

        return switch (ParsedAtom.parse(atom)) {
            case ParsedAtom.Slug slug -> findBySlug(catalog.images(), slug.slug());
            case ParsedAtom.Name name -> findByName(catalog.images(), name.name());
            case ParsedAtom.Expression expression -> findByExpression(catalog.images(), expression.name(), expression.expression());
        };
    }

    /// Finds an image by slug.
    ///
    /// @param images images to search.
    /// @param slug package slug.
    /// @return matching image, or null when not found.
    private static @Nullable ImageEntry findBySlug(@Unmodifiable List<ImageEntry> images, String slug) {
        for (ImageEntry image : images) {
            @Nullable String imageSlug = image.slug();
            if (slug.equals(imageSlug) && !isRuyiPrerelease(image)) {
                return image;
            }
        }
        return null;
    }

    /// Finds the latest non-prerelease image by name.
    ///
    /// @param images images to search.
    /// @param name atom package name.
    /// @return matching image, or null when not found.
    private static @Nullable ImageEntry findByName(@Unmodifiable List<ImageEntry> images, String name) {
        ArrayList<ImageEntry> matches = new ArrayList<>();
        for (ImageEntry image : images) {
            if (matchesName(image, name) && !isRuyiPrerelease(image)) {
                matches.add(image);
            }
        }
        return latest(matches);
    }

    /// Finds the latest non-prerelease image by name and version expression.
    ///
    /// @param images images to search.
    /// @param name atom package name.
    /// @param expression version expression.
    /// @return matching image, or null when not found.
    private static @Nullable ImageEntry findByExpression(
            @Unmodifiable List<ImageEntry> images,
            String name,
            String expression) {
        ArrayList<ImageEntry> matches = new ArrayList<>();
        for (ImageEntry image : images) {
            if (matchesName(image, name)
                    && !isRuyiPrerelease(image)
                    && RuyiSemVersion.matches(image.version(), expression)) {
                matches.add(image);
            }
        }
        return latest(matches);
    }

    /// Checks whether an image matches an atom name.
    ///
    /// @param image image entry.
    /// @param name atom name.
    /// @return whether the image matches.
    private static boolean matchesName(ImageEntry image, String name) {
        int separator = name.indexOf('/');
        if (separator >= 0) {
            return (image.category() + "/" + image.name()).equals(name);
        }
        return image.name().equals(name);
    }

    /// Returns the latest SemVer image from a list.
    ///
    /// @param images images to inspect.
    /// @return latest image, or null when no image has a valid SemVer version.
    private static @Nullable ImageEntry latest(List<ImageEntry> images) {
        @Nullable ImageEntry latestImage = null;
        @Nullable RuyiSemVersion latestVersion = null;
        for (ImageEntry image : images) {
            @Nullable RuyiSemVersion version = RuyiSemVersion.parseOrNull(image.version());
            if (version == null) {
                continue;
            }

            if (latestVersion == null || version.compareTo(latestVersion) > 0) {
                latestVersion = version;
                latestImage = image;
            }
        }
        return latestImage;
    }

    /// Checks whether an image version is treated as a Ruyi prerelease.
    ///
    /// @param image image entry.
    /// @return whether the image version is a prerelease.
    private static boolean isRuyiPrerelease(ImageEntry image) {
        @Nullable RuyiSemVersion version = RuyiSemVersion.parseOrNull(image.version());
        return version != null && version.isRuyiPrerelease();
    }

    /// Parsed Ruyi atom.
    @NotNullByDefault
    private sealed interface ParsedAtom permits ParsedAtom.Slug, ParsedAtom.Name, ParsedAtom.Expression {
        /// Parses one atom string.
        ///
        /// @param value atom string.
        /// @return parsed atom.
        static ParsedAtom parse(String value) {
            if (value.startsWith("slug:")) {
                return new Slug(value.substring("slug:".length()));
            }

            if (value.startsWith("name:")) {
                return parseName(value.substring("name:".length()));
            }

            Matcher expressionMatcher = ATOM_EXPR.matcher(value);
            if (expressionMatcher.matches()) {
                return new Expression(expressionMatcher.group(1), expressionMatcher.group(2));
            }

            return parseName(value);
        }

        /// Parses a plain atom name.
        ///
        /// @param value atom name.
        /// @return parsed name atom.
        private static Name parseName(String value) {
            if (!ATOM_NAME.matcher(value).matches()) {
                throw new IllegalArgumentException(Messages.get("core.image.invalidAtom", value));
            }
            return new Name(value);
        }

        /// Slug atom.
        ///
        /// @param slug package slug.
        @NotNullByDefault
        record Slug(String slug) implements ParsedAtom {
        }

        /// Name atom.
        ///
        /// @param name package name.
        @NotNullByDefault
        record Name(String name) implements ParsedAtom {
        }

        /// Version expression atom.
        ///
        /// @param name package name.
        /// @param expression version expression.
        @NotNullByDefault
        record Expression(String name, String expression) implements ParsedAtom {
        }
    }
}

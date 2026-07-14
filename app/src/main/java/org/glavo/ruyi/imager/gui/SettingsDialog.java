// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.gui;

import io.github.palexdev.materialfx.controls.MFXButton;
import io.github.palexdev.materialfx.controls.MFXComboBox;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;
import org.glavo.ruyi.imager.i18n.Messages;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.Locale;

/// Provides the editable controls shown in the GUI settings dialog.
@NotNullByDefault
final class SettingsDialog {
    /// Language choices available in the GUI.
    private static final @Unmodifiable List<LanguageOption> LANGUAGE_OPTIONS = List.of(
            new LanguageOption("gui.language.english", Locale.ENGLISH),
            new LanguageOption("gui.language.simplifiedChinese", Locale.SIMPLIFIED_CHINESE));

    /// Root settings content.
    private final VBox root;

    /// Language selector.
    private final MFXComboBox<LanguageOption> languageSelector;

    /// Manual metadata update button.
    private final Button metadataUpdateButton;

    /// Manual metadata update status.
    private final Label metadataUpdateStatus;

    /// Creates settings controls with the currently selected locale.
    ///
    /// @param locale selected GUI locale.
    SettingsDialog(Locale locale) {
        this.languageSelector = createLanguageSelector(locale);
        this.metadataUpdateButton = new MFXButton(Messages.get("gui.settings.updateMetadata"));
        this.metadataUpdateButton.getStyleClass().add("settings-action-button");
        this.metadataUpdateStatus = new Label();
        this.metadataUpdateStatus.setWrapText(true);
        this.metadataUpdateStatus.setVisible(false);
        this.metadataUpdateStatus.setManaged(false);
        this.metadataUpdateStatus.getStyleClass().add("settings-update-status");

        Label generalTitle = new Label(Messages.get("gui.settings.general"));
        generalTitle.getStyleClass().add("settings-section-title");

        Label languageLabel = new Label(Messages.get("gui.language"));
        languageLabel.getStyleClass().add("settings-label");

        GridPane generalGrid = new GridPane();
        generalGrid.setAlignment(Pos.CENTER_LEFT);
        generalGrid.getStyleClass().add("settings-grid");
        generalGrid.add(languageLabel, 0, 0);
        generalGrid.add(languageSelector, 1, 0);

        VBox generalSection = new VBox(generalTitle, generalGrid);
        generalSection.getStyleClass().add("settings-section");

        Label metadataTitle = new Label(Messages.get("gui.settings.metadata"));
        metadataTitle.getStyleClass().add("settings-section-title");

        VBox metadataSection = new VBox(metadataTitle, metadataUpdateButton, metadataUpdateStatus);
        metadataSection.getStyleClass().add("settings-section");

        this.root = new VBox(generalSection, metadataSection);
        this.root.getStyleClass().add("settings-content");
    }

    /// Returns the settings content node.
    ///
    /// @return settings content node.
    Node root() {
        return root;
    }

    /// Returns the selected GUI locale.
    ///
    /// @return selected locale.
    Locale selectedLocale() {
        return languageSelector.getValue().locale();
    }

    /// Returns the manual metadata update button.
    ///
    /// @return metadata update button.
    Button metadataUpdateButton() {
        return metadataUpdateButton;
    }

    /// Marks the manual metadata update as active.
    void metadataUpdateStarted() {
        metadataUpdateButton.setDisable(true);
        setMetadataUpdateStatus(Messages.get("gui.progress.updatingMetadata"), "settings-update-active");
    }

    /// Shows the result of a manual metadata update.
    ///
    /// @param successful whether the update succeeded.
    /// @param message    update result message.
    void metadataUpdateFinished(boolean successful, String message) {
        metadataUpdateButton.setDisable(false);
        setMetadataUpdateStatus(
                message,
                successful ? "settings-update-success" : "settings-update-error");
    }

    /// Updates the visible metadata status.
    ///
    /// @param message    status text.
    /// @param styleClass status style class.
    private void setMetadataUpdateStatus(String message, String styleClass) {
        metadataUpdateStatus.setText(message);
        metadataUpdateStatus.getStyleClass().removeAll(
                "settings-update-active",
                "settings-update-success",
                "settings-update-error");
        metadataUpdateStatus.getStyleClass().add(styleClass);
        metadataUpdateStatus.setVisible(true);
        metadataUpdateStatus.setManaged(true);
    }

    /// Creates the language selector.
    ///
    /// @param locale initially selected locale.
    /// @return language selector.
    private static MFXComboBox<LanguageOption> createLanguageSelector(Locale locale) {
        MFXComboBox<LanguageOption> selector = new MFXComboBox<>(
                FXCollections.observableArrayList(LANGUAGE_OPTIONS));
        selector.setAllowEdit(false);
        selector.setRowsCount(LANGUAGE_OPTIONS.size());
        selector.setPrefWidth(220);
        selector.setConverter(new StringConverter<>() {
            /// Converts a language option to localized display text.
            ///
            /// @param option language option.
            /// @return localized display text.
            @Override
            public String toString(@Nullable LanguageOption option) {
                return languageLabel(option);
            }

            /// Converts localized display text back to a language option.
            ///
            /// @param text localized display text.
            /// @return matching language option, or null when no option matches.
            @Override
            public @Nullable LanguageOption fromString(@Nullable String text) {
                if (text == null) {
                    return null;
                }
                for (LanguageOption option : LANGUAGE_OPTIONS) {
                    if (languageLabel(option).equals(text)) {
                        return option;
                    }
                }
                return null;
            }
        });
        selector.getStyleClass().add("settings-language-selector");

        LanguageOption selected = languageOption(locale);
        selector.selectItem(selected);
        selector.setText(languageLabel(selected));
        return selector;
    }

    /// Selects the supported language option for a locale.
    ///
    /// @param locale selected locale.
    /// @return matching language option.
    private static LanguageOption languageOption(Locale locale) {
        if (Locale.SIMPLIFIED_CHINESE.getLanguage().equals(locale.getLanguage())) {
            return LANGUAGE_OPTIONS.get(1);
        }
        return LANGUAGE_OPTIONS.getFirst();
    }

    /// Returns the localized display label for a language option.
    ///
    /// @param option language option.
    /// @return localized language label.
    private static String languageLabel(@Nullable LanguageOption option) {
        return option == null ? "" : Messages.get(option.labelKey());
    }

    /// Supported GUI language option.
    ///
    /// @param labelKey message key for the language label.
    /// @param locale   locale selected by this option.
    @NotNullByDefault
    private record LanguageOption(String labelKey, Locale locale) {
    }
}

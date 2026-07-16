// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.gui;

import io.github.palexdev.materialfx.controls.MFXButton;
import io.github.palexdev.materialfx.controls.MFXComboBox;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;
import org.glavo.ruyi.imager.i18n.Messages;
import org.glavo.ruyi.imager.update.BuildInfo;
import org.glavo.ruyi.imager.update.UpdateChannel;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.Path;
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

    /// Automatic application update check option.
    private final CheckBox automaticUpdateChecks;

    /// Application update channel selector.
    private final MFXComboBox<UpdateChannel> updateChannelSelector;

    /// Application update check button.
    private final Button applicationUpdateButton;

    /// Application update status.
    private final Label applicationUpdateStatus;

    /// Manual metadata update button.
    private final Button metadataUpdateButton;

    /// Manual metadata update status.
    private final Label metadataUpdateStatus;

    /// Creates settings controls with current values.
    ///
    /// @param locale       selected GUI locale.
    /// @param buildInfo    running application build.
    /// @param updateSource local update manifest path.
    /// @param automaticUpdateChecks whether startup update checks are enabled.
    /// @param updateChannel         selected update channel.
    SettingsDialog(
            Locale locale,
            BuildInfo buildInfo,
            Path updateSource,
            boolean automaticUpdateChecks,
            UpdateChannel updateChannel) {
        this.languageSelector = createLanguageSelector(locale);
        this.automaticUpdateChecks = new CheckBox(Messages.get("gui.settings.automaticUpdateChecks"));
        this.automaticUpdateChecks.setSelected(automaticUpdateChecks);
        this.automaticUpdateChecks.getStyleClass().add("settings-checkbox");
        this.updateChannelSelector = createUpdateChannelSelector(updateChannel);
        this.applicationUpdateButton = new MFXButton(Messages.get("gui.settings.checkForUpdates"));
        this.applicationUpdateButton.getStyleClass().add("settings-action-button");
        this.applicationUpdateStatus = createStatusLabel();
        this.metadataUpdateButton = new MFXButton(Messages.get("gui.settings.updateMetadata"));
        this.metadataUpdateButton.getStyleClass().add("settings-action-button");
        this.metadataUpdateStatus = createStatusLabel();

        Label generalTitle = new Label(Messages.get("gui.settings.general"));
        generalTitle.getStyleClass().add("settings-section-title");
        GridPane generalGrid = new GridPane();
        generalGrid.setAlignment(Pos.CENTER_LEFT);
        generalGrid.getStyleClass().add("settings-grid");
        generalGrid.add(settingsLabel("gui.language"), 0, 0);
        generalGrid.add(languageSelector, 1, 0);
        generalGrid.add(settingsLabel("gui.settings.version"), 0, 1);
        Label versionValue = new Label(buildInfo.version());
        versionValue.getStyleClass().add("settings-value");
        generalGrid.add(versionValue, 1, 1);
        VBox generalSection = new VBox(generalTitle, generalGrid);
        generalSection.getStyleClass().add("settings-section");

        Label applicationUpdateTitle = new Label(Messages.get("gui.settings.applicationUpdates"));
        applicationUpdateTitle.getStyleClass().add("settings-section-title");
        Label updateSourceLabel = new Label(Messages.get("gui.settings.updateSource", updateSource));
        updateSourceLabel.setWrapText(true);
        updateSourceLabel.getStyleClass().add("settings-source");
        GridPane updateGrid = new GridPane();
        updateGrid.setAlignment(Pos.CENTER_LEFT);
        updateGrid.getStyleClass().add("settings-grid");
        updateGrid.add(settingsLabel("gui.settings.updateChannel"), 0, 0);
        updateGrid.add(updateChannelSelector, 1, 0);
        VBox applicationUpdateSection = new VBox(
                applicationUpdateTitle,
                updateSourceLabel,
                this.automaticUpdateChecks,
                updateGrid,
                applicationUpdateButton,
                applicationUpdateStatus);
        applicationUpdateSection.getStyleClass().add("settings-section");

        Label metadataTitle = new Label(Messages.get("gui.settings.metadata"));
        metadataTitle.getStyleClass().add("settings-section-title");
        VBox metadataSection = new VBox(metadataTitle, metadataUpdateButton, metadataUpdateStatus);
        metadataSection.getStyleClass().add("settings-section");

        this.root = new VBox(generalSection, applicationUpdateSection, metadataSection);
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

    /// Returns whether startup application update checks are enabled.
    ///
    /// @return whether automatic update checks are enabled.
    boolean automaticUpdateChecksEnabled() {
        return automaticUpdateChecks.isSelected();
    }

    /// Returns the selected application update channel.
    ///
    /// @return selected update channel.
    UpdateChannel selectedUpdateChannel() {
        return updateChannelSelector.getValue();
    }

    /// Returns the manual metadata update button.
    ///
    /// @return metadata update button.
    Button metadataUpdateButton() {
        return metadataUpdateButton;
    }

    /// Returns the application update check button.
    ///
    /// @return application update check button.
    Button applicationUpdateButton() {
        return applicationUpdateButton;
    }

    /// Marks the application update check as active.
    void applicationUpdateStarted() {
        setOperationButtonsDisabled(true);
        setStatus(
                applicationUpdateStatus,
                Messages.get("gui.progress.checkingForUpdates"),
                "settings-update-active");
    }

    /// Shows the result of an application update check.
    ///
    /// @param updateAvailable whether a newer build is available.
    /// @param message         update check result message.
    void applicationUpdateFinished(boolean updateAvailable, String message) {
        setOperationButtonsDisabled(false);
        setStatus(
                applicationUpdateStatus,
                message,
                updateAvailable ? "settings-update-available" : "settings-update-success");
    }

    /// Shows an application update check failure.
    ///
    /// @param message failure message.
    void applicationUpdateFailed(String message) {
        setOperationButtonsDisabled(false);
        setStatus(applicationUpdateStatus, message, "settings-update-error");
    }

    /// Marks the manual metadata update as active.
    void metadataUpdateStarted() {
        setOperationButtonsDisabled(true);
        setStatus(metadataUpdateStatus, Messages.get("gui.progress.updatingMetadata"), "settings-update-active");
    }

    /// Shows the result of a manual metadata update.
    ///
    /// @param successful whether the update succeeded.
    /// @param message    update result message.
    void metadataUpdateFinished(boolean successful, String message) {
        setOperationButtonsDisabled(false);
        setStatus(
                metadataUpdateStatus,
                message,
                successful ? "settings-update-success" : "settings-update-error");
    }

    /// Creates a settings row label.
    ///
    /// @param messageKey localized message key.
    /// @return styled settings label.
    private static Label settingsLabel(String messageKey) {
        Label label = new Label(Messages.get(messageKey));
        label.getStyleClass().add("settings-label");
        return label;
    }

    /// Creates an initially hidden operation status label.
    ///
    /// @return operation status label.
    private static Label createStatusLabel() {
        Label status = new Label();
        status.setWrapText(true);
        status.setVisible(false);
        status.setManaged(false);
        status.getStyleClass().add("settings-update-status");
        return status;
    }

    /// Enables or disables settings operations together.
    ///
    /// @param disabled whether operations are disabled.
    private void setOperationButtonsDisabled(boolean disabled) {
        applicationUpdateButton.setDisable(disabled);
        metadataUpdateButton.setDisable(disabled);
        automaticUpdateChecks.setDisable(disabled);
        updateChannelSelector.setDisable(disabled);
    }

    /// Updates a visible operation status.
    ///
    /// @param status     status label.
    /// @param message    status text.
    /// @param styleClass status style class.
    private static void setStatus(Label status, String message, String styleClass) {
        status.setText(message);
        status.getStyleClass().removeAll(
                "settings-update-active",
                "settings-update-available",
                "settings-update-success",
                "settings-update-error");
        status.getStyleClass().add(styleClass);
        status.setVisible(true);
        status.setManaged(true);
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

    /// Creates the update channel selector.
    ///
    /// @param selected initially selected channel.
    /// @return channel selector.
    private static MFXComboBox<UpdateChannel> createUpdateChannelSelector(UpdateChannel selected) {
        MFXComboBox<UpdateChannel> selector = new MFXComboBox<>(
                FXCollections.observableArrayList(UpdateChannel.values()));
        selector.setAllowEdit(false);
        selector.setRowsCount(UpdateChannel.values().length);
        selector.setPrefWidth(220);
        selector.setConverter(new StringConverter<>() {
            /// Converts a channel to localized display text.
            ///
            /// @param channel update channel.
            /// @return localized display text.
            @Override
            public String toString(@Nullable UpdateChannel channel) {
                return channel == null ? "" : updateChannelLabel(channel);
            }

            /// Converts localized display text back to a channel.
            ///
            /// @param text localized display text.
            /// @return matching channel, or null.
            @Override
            public @Nullable UpdateChannel fromString(@Nullable String text) {
                if (text == null) {
                    return null;
                }
                for (UpdateChannel channel : UpdateChannel.values()) {
                    if (updateChannelLabel(channel).equals(text)) {
                        return channel;
                    }
                }
                return null;
            }
        });
        selector.getStyleClass().add("settings-language-selector");
        selector.selectItem(selected);
        selector.setText(updateChannelLabel(selected));
        return selector;
    }

    /// Returns the localized channel label.
    ///
    /// @param channel update channel.
    /// @return localized label.
    private static String updateChannelLabel(UpdateChannel channel) {
        return Messages.get("gui.settings.updateChannel." + channel.token());
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

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.gui;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.transformation.FilteredList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Separator;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Window;
import javafx.util.StringConverter;
import io.github.palexdev.materialfx.controls.MFXButton;
import io.github.palexdev.materialfx.controls.MFXComboBox;
import io.github.palexdev.materialfx.controls.MFXProgressBar;
import io.github.palexdev.materialfx.controls.MFXScrollPane;
import io.github.palexdev.materialfx.controls.MFXTextField;
import io.github.palexdev.materialfx.controls.legacy.MFXLegacyListCell;
import io.github.palexdev.materialfx.controls.legacy.MFXLegacyListView;
import io.github.palexdev.materialfx.dialogs.MFXGenericDialog;
import io.github.palexdev.materialfx.dialogs.MFXGenericDialogBuilder;
import io.github.palexdev.materialfx.dialogs.MFXStageDialog;
import io.github.palexdev.materialfx.dialogs.MFXStageDialogBuilder;
import org.glavo.ruyi.imager.core.AppServices;
import org.glavo.ruyi.imager.core.OperationResult;
import org.glavo.ruyi.imager.core.StrategySupport;
import org.glavo.ruyi.imager.core.device.BlockDevice;
import org.glavo.ruyi.imager.core.fastboot.FastbootDevice;
import org.glavo.ruyi.imager.core.flash.FlashRequest;
import org.glavo.ruyi.imager.core.flash.FlashTarget;
import org.glavo.ruyi.imager.core.image.ImageCacheStatus;
import org.glavo.ruyi.imager.core.image.ImageCatalog;
import org.glavo.ruyi.imager.core.image.ImageEntry;
import org.glavo.ruyi.imager.i18n.Messages;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

/// Main JavaFX window for the guided imager workflow.
@NotNullByDefault
public final class MainWindow {
    /// Language choices exposed in the GUI.
    private static final @Unmodifiable List<LanguageOption> LANGUAGE_OPTIONS = List.of(
            new LanguageOption("gui.language.english", Locale.ENGLISH),
            new LanguageOption("gui.language.simplifiedChinese", Locale.SIMPLIFIED_CHINESE));

    /// Binary size units used by storage device summaries.
    private static final @Unmodifiable List<String> SIZE_UNITS = List.of("B", "KiB", "MiB", "GiB", "TiB");

    /// Fixed width used by modal selection lists.
    private static final double SELECTION_LIST_WIDTH = 640.0;

    /// Fixed height used by modal selection lists so dialog actions never overlap them.
    private static final double SELECTION_LIST_HEIGHT = 320.0;

    /// Compact bottom inset between modal selection lists and dialog actions.
    private static final double SELECTION_CONTENT_BOTTOM_INSET = 14.0;

    /// Fixed width used by the independent local-image option.
    private static final double LOCAL_IMAGE_OPTION_WIDTH = 340.0;

    /// Core services shared with the CLI.
    private final AppServices services;

    /// GUI preferences store.
    private final GuiPreferences preferences;

    /// Root node for the window.
    private final BorderPane root;

    /// Current user selections.
    private WizardState state = new WizardState(null, null, null, null, null);

    /// Status text shown in the top bar.
    private final Label statusLabel = new Label();

    /// Progress bar shown for background work.
    private final MFXProgressBar progressBar = new MFXProgressBar(0);

    /// Manufacturer selection summary.
    private final Label manufacturerValue = new Label();

    /// Board selection summary.
    private final Label boardValue = new Label();

    /// Operating system selection summary.
    private final Label osValue = new Label();

    /// Local image selection summary.
    private final Label localImageValue = new Label();

    /// Storage selection summary.
    private final Label storageValue = new Label();

    /// Target step title.
    private final Label targetTitle = new Label();

    /// Manufacturer selection button.
    private final Button manufacturerButton = localizedButton("gui.button.chooseManufacturer");

    /// Board selection button.
    private final Button boardButton = localizedButton("gui.button.chooseBoard");

    /// Operating system selection button.
    private final Button osButton = localizedButton("gui.button.chooseOs");

    /// Local image selection button.
    private final Button localImageButton = localizedButton("gui.button.useLocalImage");

    /// Target selection button.
    private final Button storageButton = new MFXButton();

    /// Repository metadata update button.
    private final Button repoUpdateButton = localizedButton("gui.button.updateMetadata");

    /// Flash action button.
    private final Button flashButton = localizedButton("gui.button.flash");

    /// Whether a background operation is active.
    private boolean busy;

    /// Creates the main window.
    ///
    /// @param services shared application services.
    public MainWindow(AppServices services) {
        this.services = services;
        this.preferences = new GuiPreferences(services.directories());
        loadPreferredLocale();
        this.root = createRoot();
        Messages.localeProperty().addListener((_, _, _) -> {
            if (!busy && !statusLabel.textProperty().isBound()) {
                statusLabel.setText(Messages.get("gui.status.ready"));
            }
            refreshState();
        });
        statusLabel.setText(Messages.get("gui.status.ready"));
        refreshState();
    }

    /// Returns the root node for scene installation.
    ///
    /// @return root node.
    public Parent root() {
        return root;
    }

    /// Creates the main layout.
    ///
    /// @return root layout.
    private BorderPane createRoot() {
        BorderPane pane = new BorderPane();
        pane.getStyleClass().add("app-root");
        pane.setTop(createHeader());
        pane.setCenter(createWorkflowScrollPane());
        pane.setBottom(createFooter());
        return pane;
    }

    /// Creates the scrollable workflow surface.
    ///
    /// @return workflow scroll pane.
    private MFXScrollPane createWorkflowScrollPane() {
        MFXScrollPane scrollPane = new MFXScrollPane(createWorkflow());
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(true);
        scrollPane.getStyleClass().add("workflow-scroll-pane");
        return scrollPane;
    }

    /// Creates the window header.
    ///
    /// @return header node.
    private VBox createHeader() {
        Label title = localizedLabel("app.title");
        title.getStyleClass().add("app-title");

        Label subtitle = localizedLabel("gui.header.subtitle");
        subtitle.getStyleClass().add("app-subtitle");

        MFXComboBox<LanguageOption> languageSelector = createLanguageSelector();

        Region titleSpacer = new Region();
        HBox.setHgrow(titleSpacer, Priority.ALWAYS);

        HBox titleRow = new HBox(16, title, titleSpacer, languageSelector);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        repoUpdateButton.setOnAction(_ -> updateRepository());
        repoUpdateButton.getStyleClass().add("header-button");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox status = new HBox(12, statusLabel, progressBar, spacer, repoUpdateButton);
        status.setAlignment(Pos.CENTER_LEFT);
        progressBar.setPrefWidth(180);
        progressBar.setVisible(false);

        VBox header = new VBox(8, titleRow, subtitle, status);
        header.getStyleClass().add("app-header");
        return header;
    }

    /// Creates the runtime language selector.
    ///
    /// @return language selector.
    private MFXComboBox<LanguageOption> createLanguageSelector() {
        MFXComboBox<LanguageOption> selector = new MFXComboBox<>(
                FXCollections.observableArrayList(LANGUAGE_OPTIONS));
        selector.setAllowEdit(false);
        selector.setRowsCount(LANGUAGE_OPTIONS.size());
        selector.setPrefWidth(190);
        selector.floatingTextProperty().bind(Messages.binding("gui.language"));
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
        selector.getStyleClass().add("language-selector");
        updateLanguageSelectorValue(selector);
        selector.valueProperty().addListener((_, _, selected) -> {
            if (selected != null && !selected.locale().equals(languageOption(Messages.locale()).locale())) {
                Messages.setLocale(selected.locale());
                savePreferredLocale(selected.locale());
            }
        });
        Messages.localeProperty().addListener((_, _, _) -> updateLanguageSelectorValue(selector));
        return selector;
    }

    /// Creates the guided workflow controls.
    ///
    /// @return workflow node.
    private VBox createWorkflow() {
        manufacturerButton.setOnAction(_ -> chooseManufacturer());

        boardButton.setOnAction(_ -> chooseBoard());

        osButton.setOnAction(_ -> chooseOperatingSystem());

        localImageButton.setOnAction(_ -> chooseLocalImage());

        storageButton.setOnAction(_ -> chooseStorage());

        flashButton.setOnAction(_ -> flash());

        localImageButton.getStyleClass().add("secondary-action-button");
        flashButton.getStyleClass().add("primary-action-button");
        targetTitle.getStyleClass().add("step-title");

        HBox writeActions = new HBox(flashButton);
        writeActions.getStyleClass().add("write-actions");

        Label catalogTitle = localizedLabel("gui.choice.catalog");
        catalogTitle.getStyleClass().add("choice-title");

        VBox catalogFlow = new VBox(12,
                catalogTitle,
                createStep("1", "gui.step.manufacturer", manufacturerValue, manufacturerButton),
                createStep("2", "gui.step.board", boardValue, boardButton),
                createStep("3", "gui.step.os", osValue, osButton));
        catalogFlow.getStyleClass().add("catalog-choice");
        HBox.setHgrow(catalogFlow, Priority.ALWAYS);

        Label localTitle = localizedLabel("gui.choice.local");
        localTitle.getStyleClass().add("choice-title");

        VBox localFlow = new VBox(12, localTitle, createLocalImageOption());
        localFlow.setAlignment(Pos.CENTER);
        localFlow.setFillWidth(false);
        localFlow.getStyleClass().add("local-choice");
        HBox.setHgrow(localFlow, Priority.ALWAYS);

        Label separator = localizedLabel("gui.choice.or");
        separator.getStyleClass().add("choice-separator");

        HBox sourceChoices = new HBox(16, catalogFlow, separator, localFlow);
        sourceChoices.setAlignment(Pos.CENTER);
        sourceChoices.getStyleClass().add("source-choices");

        VBox workflow = new VBox(14,
                sourceChoices,
                createStep("4", targetTitle, storageValue, storageButton),
                writeActions);
        workflow.getStyleClass().add("workflow");
        return workflow;
    }

    /// Creates the independent local-image option outside the catalog selection flow.
    ///
    /// @return custom image option node.
    private HBox createLocalImageOption() {
        Label description = localizedLabel("gui.local.description");
        description.getStyleClass().add("option-value");
        description.setWrapText(true);

        localImageValue.getStyleClass().add("option-value");
        localImageValue.setWrapText(true);

        VBox text = new VBox(4, description, localImageValue);
        HBox.setHgrow(text, Priority.ALWAYS);

        HBox row = new HBox(16, text, localImageButton);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPrefWidth(LOCAL_IMAGE_OPTION_WIDTH);
        row.setMaxWidth(LOCAL_IMAGE_OPTION_WIDTH);
        row.getStyleClass().add("independent-option-row");

        return row;
    }

    /// Creates one workflow row.
    ///
    /// @param number step number.
    /// @param titleKey step title key.
    /// @param value current step value.
    /// @param action action button.
    /// @return workflow row.
    private HBox createStep(String number, String titleKey, Label value, Node action) {
        Label titleLabel = localizedLabel(titleKey);
        return createStep(number, titleLabel, value, action);
    }

    /// Creates one workflow row.
    ///
    /// @param number step number.
    /// @param titleLabel step title label.
    /// @param value current step value.
    /// @param action action button.
    /// @return workflow row.
    private HBox createStep(String number, Label titleLabel, Label value, Node action) {
        Label badge = new Label(number);
        badge.getStyleClass().add("step-badge");

        titleLabel.getStyleClass().add("step-title");
        value.getStyleClass().add("step-value");
        VBox text = new VBox(4, titleLabel, value);
        HBox.setHgrow(text, Priority.ALWAYS);

        if (action instanceof Button button) {
            button.getStyleClass().add("step-button");
        }
        HBox row = new HBox(16, badge, text, action);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("step-row");
        return row;
    }

    /// Creates a label bound to a localized message.
    ///
    /// @param key message key.
    /// @return localized label.
    private static Label localizedLabel(String key) {
        Label label = new Label();
        label.textProperty().bind(Messages.binding(key));
        return label;
    }

    /// Creates a button bound to a localized message.
    ///
    /// @param key message key.
    /// @return localized button.
    private static MFXButton localizedButton(String key) {
        MFXButton button = new MFXButton();
        button.setButtonType(io.github.palexdev.materialfx.enums.ButtonType.RAISED);
        button.textProperty().bind(Messages.binding(key));
        return button;
    }

    /// Creates a MaterialFX-styled selection list.
    ///
    /// @param <T> item type.
    /// @return selection list view.
    private static <T> MFXLegacyListView<T> selectionListView() {
        MFXLegacyListView<T> listView = new MFXLegacyListView<>();
        listView.getStyleClass().add("selection-list-view");
        listView.setMinSize(SELECTION_LIST_WIDTH, SELECTION_LIST_HEIGHT);
        listView.setPrefSize(SELECTION_LIST_WIDTH, SELECTION_LIST_HEIGHT);
        listView.setMaxSize(SELECTION_LIST_WIDTH, SELECTION_LIST_HEIGHT);
        return listView;
    }

    /// Wraps a selection list with a MaterialFX search field.
    ///
    /// @param listView selection list view.
    /// @param items source items.
    /// @param matcher item matcher receiving a normalized query.
    /// @param <T> item type.
    /// @return searchable selection content.
    private static <T> Node searchableSelectionContent(
            ListView<T> listView,
            List<T> items,
            BiPredicate<T, String> matcher) {
        MFXTextField searchField = new MFXTextField();
        searchField.floatingTextProperty().bind(Messages.binding("gui.search"));
        searchField.promptTextProperty().bind(Messages.binding("gui.search.placeholder"));
        searchField.getStyleClass().add("selection-search");

        FilteredList<T> filteredItems = new FilteredList<>(FXCollections.observableArrayList(items));
        listView.setItems(filteredItems);
        searchField.textProperty().addListener((_, _, value) -> {
            String query = normalizeSearchQuery(value);
            filteredItems.setPredicate(item -> query.isEmpty() || matcher.test(item, query));
            @Nullable T selected = listView.getSelectionModel().getSelectedItem();
            if (filteredItems.isEmpty()) {
                listView.getSelectionModel().clearSelection();
            } else if (selected == null || !filteredItems.contains(selected)) {
                listView.getSelectionModel().selectFirst();
            }
        });

        VBox content = new VBox(10, searchField, listView);
        content.setMinWidth(SELECTION_LIST_WIDTH);
        content.setPrefWidth(SELECTION_LIST_WIDTH);
        content.setMaxWidth(SELECTION_LIST_WIDTH);
        content.setPadding(new Insets(0.0, 0.0, SELECTION_CONTENT_BOTTOM_INSET, 0.0));
        content.getStyleClass().add("selection-content");
        VBox.setVgrow(listView, Priority.NEVER);
        return content;
    }

    /// Normalizes search text for case-insensitive matching.
    ///
    /// @param text raw search text.
    /// @return normalized query.
    private static String normalizeSearchQuery(@Nullable String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }

    /// Updates the language selector to match the selected locale.
    ///
    /// @param selector language selector.
    private static void updateLanguageSelectorValue(MFXComboBox<LanguageOption> selector) {
        LanguageOption option = languageOption(Messages.locale());
        if (!option.equals(selector.getValue())) {
            selector.selectItem(option);
        }
        selector.setText(languageLabel(option));
    }

    /// Selects the supported language option for one locale.
    ///
    /// @param locale selected locale.
    /// @return matching language option.
    private static LanguageOption languageOption(Locale locale) {
        if (Locale.SIMPLIFIED_CHINESE.getLanguage().equals(locale.getLanguage())) {
            return LANGUAGE_OPTIONS.get(1);
        }
        return LANGUAGE_OPTIONS.getFirst();
    }

    /// Returns the localized display label for one language option.
    ///
    /// @param option language option.
    /// @return localized language label.
    private static String languageLabel(@Nullable LanguageOption option) {
        return option == null ? "" : Messages.get(option.labelKey());
    }

    /// Loads the persisted GUI language preference unless a system property override is present.
    private void loadPreferredLocale() {
        if (hasLocaleSystemProperty()) {
            return;
        }

        try {
            @Nullable Locale locale = preferences.readLocale();
            if (locale != null) {
                Messages.setLocale(locale);
            }
        } catch (IOException _) {
            // The UI can still run with the default locale.
        }
    }

    /// Persists the selected GUI language.
    ///
    /// @param locale selected locale.
    private void savePreferredLocale(Locale locale) {
        try {
            preferences.writeLocale(locale);
        } catch (IOException exception) {
            showError(Messages.get("gui.dialog.preferencesWriteFailed"), exception.getMessage());
        }
    }

    /// Returns whether locale was explicitly configured with a JVM property.
    ///
    /// @return whether the locale system property is present.
    private static boolean hasLocaleSystemProperty() {
        @Nullable String configuredLocale = System.getProperty(Messages.LOCALE_PROPERTY);
        return configuredLocale != null && !configuredLocale.isBlank();
    }

    /// Starts repository metadata update.
    private void updateRepository() {
        Task<OperationResult> task = new Task<>() {
            /// Updates local repository metadata outside the JavaFX application thread.
            ///
            /// @return update result.
            @Override
            protected OperationResult call() throws Exception {
                updateMessage(Messages.get("gui.progress.updatingMetadata"));
                return services.repository().update(event -> {
                    updateMessage(event.message());
                    @Nullable Long currentBytes = event.currentBytes();
                    @Nullable Long totalBytes = event.totalBytes();
                    if (currentBytes != null && totalBytes != null && totalBytes > 0L) {
                        updateProgress(currentBytes, totalBytes);
                    }
                });
            }
        };

        startBackgroundTask(task, Messages.get("gui.dialog.metadataUpdateFailed"), result -> {
            if (result.success()) {
                state = new WizardState(null, null, null, null, state.target());
                refreshState();
                showInfo(Messages.get("gui.dialog.metadataUpdated"), result.message());
            } else {
                showError(Messages.get("gui.dialog.metadataUpdateFailed"), result.message());
            }
        });
    }

    /// Creates the footer node.
    ///
    /// @return footer node.
    private VBox createFooter() {
        Label safety = localizedLabel("gui.footer.safety");
        safety.getStyleClass().add("footer-text");
        VBox footer = new VBox(new Separator(), safety);
        footer.getStyleClass().add("app-footer");
        return footer;
    }

    /// Opens the manufacturer selection dialog.
    private void chooseManufacturer() {
        Task<ImageCatalog> task = new Task<>() {
            /// Loads the image catalog outside the JavaFX application thread.
            ///
            /// @return image catalog.
            @Override
            protected ImageCatalog call() throws Exception {
                updateMessage(Messages.get("gui.progress.loadingCatalog"));
                return services.images().listImages();
            }
        };

        startBackgroundTask(task, Messages.get("gui.dialog.imageError"), this::showManufacturerDialog);
    }

    /// Shows the manufacturer selection dialog.
    ///
    /// @param catalog image catalog.
    private void showManufacturerDialog(ImageCatalog catalog) {
        @Unmodifiable List<ManufacturerOption> manufacturers = manufacturerOptions(catalog.images());
        if (manufacturers.isEmpty()) {
            showInfo(Messages.get("gui.dialog.noManufacturers"), Messages.get("gui.dialog.noManufacturers.message"));
            return;
        }

        ListView<ManufacturerOption> listView = selectionListView();
        Node content = searchableSelectionContent(listView, manufacturers, MainWindow::manufacturerMatches);
        listView.setCellFactory(_ -> new MFXLegacyListCell<>() {
            /// Updates one manufacturer list cell.
            ///
            /// @param item manufacturer option.
            /// @param empty whether the cell is empty.
            @Override
            protected void updateItem(@Nullable ManufacturerOption item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null
                        : Messages.get("gui.list.manufacturer", item.name(), item.boardCount(), item.imageCount()));
            }
        });
        selectCurrentManufacturer(listView, state.manufacturerName());

        if (showSelectionDialog(
                Messages.get("gui.dialog.chooseManufacturer"),
                Messages.get("gui.dialog.chooseManufacturer.header"),
                content)) {
            ManufacturerOption selected = listView.getSelectionModel().getSelectedItem();
            if (selected != null) {
                state = new WizardState(selected.name(), null, null, null, state.target());
                refreshState();
            }
        }
    }

    /// Opens the board selection dialog.
    private void chooseBoard() {
        if (state.manufacturerName() == null) {
            showInfo(Messages.get("gui.dialog.incompleteSelection"), Messages.get("gui.dialog.selectManufacturerFirst"));
            return;
        }

        Task<ImageCatalog> task = new Task<>() {
            /// Loads the image catalog outside the JavaFX application thread.
            ///
            /// @return image catalog.
            @Override
            protected ImageCatalog call() throws Exception {
                updateMessage(Messages.get("gui.progress.loadingCatalog"));
                return services.images().listImages();
            }
        };

        startBackgroundTask(task, Messages.get("gui.dialog.imageError"), this::showBoardDialog);
    }

    /// Shows the board selection dialog.
    ///
    /// @param catalog image catalog.
    private void showBoardDialog(ImageCatalog catalog) {
        @Unmodifiable List<BoardOption> boards = boardOptions(catalog.images(), state.manufacturerName());
        if (boards.isEmpty()) {
            showInfo(
                    Messages.get("gui.dialog.noBoards"),
                    Messages.get("gui.dialog.noBoards.message", state.manufacturerName()));
            return;
        }

        ListView<BoardOption> listView = selectionListView();
        Node content = searchableSelectionContent(listView, boards, MainWindow::boardMatches);
        listView.setCellFactory(_ -> new MFXLegacyListCell<>() {
            /// Updates one board list cell.
            ///
            /// @param item board option.
            /// @param empty whether the cell is empty.
            @Override
            protected void updateItem(@Nullable BoardOption item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : Messages.get("gui.list.board", item.name(), item.imageCount()));
            }
        });
        selectCurrentBoard(listView, state.boardName());

        if (showSelectionDialog(
                Messages.get("gui.dialog.chooseBoard"),
                Messages.get("gui.dialog.chooseBoard.header", state.manufacturerName()),
                content)) {
            BoardOption selected = listView.getSelectionModel().getSelectedItem();
            if (selected != null) {
                state = new WizardState(state.manufacturerName(), selected.name(), null, null, state.target());
                refreshState();
            }
        }
    }

    /// Opens the operating system selection dialog.
    private void chooseOperatingSystem() {
        if (state.manufacturerName() == null || state.boardName() == null) {
            showInfo(
                    Messages.get("gui.dialog.incompleteSelection"),
                    Messages.get("gui.dialog.selectManufacturerAndBoardFirst"));
            return;
        }

        Task<ImageCatalog> task = new Task<>() {
            /// Loads the image catalog outside the JavaFX application thread.
            ///
            /// @return image catalog.
            @Override
            protected ImageCatalog call() throws Exception {
                updateMessage(Messages.get("gui.progress.loadingCatalog"));
                return services.images().listImages();
            }
        };

        startBackgroundTask(task, Messages.get("gui.dialog.imageError"), this::showOperatingSystemDialog);
    }

    /// Shows the operating system selection dialog.
    ///
    /// @param catalog image catalog.
    private void showOperatingSystemDialog(ImageCatalog catalog) {
        @Unmodifiable List<ImageEntry> images = filteredImages(catalog.images(), state.manufacturerName(), state.boardName());
        if (images.isEmpty()) {
            showInfo(Messages.get("gui.dialog.noOperatingSystems"), imageEmptyMessage());
            return;
        }

        ListView<ImageEntry> listView = selectionListView();
        @Unmodifiable Map<String, ImageCacheStatus> cacheStatuses = imageCacheStatuses(images);
        Node content = searchableSelectionContent(listView, images, MainWindow::imageMatches);
        listView.setCellFactory(_ -> new MFXLegacyListCell<>() {
            /// Updates one image list cell.
            ///
            /// @param item image item.
            /// @param empty whether the cell is empty.
            @Override
            protected void updateItem(@Nullable ImageEntry item, boolean empty) {
                super.updateItem(item, empty);
                clearSelectionCellStyles(this);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }

                setText(null);
                setGraphic(imageCellContent(item, cacheStatus(cacheStatuses, item)));
                getStyleClass().add(catalogImageFlashable(item) ? "flashable-image-cell" : "unsupported-image-cell");
            }
        });
        selectCurrentImage(listView, state.image());

        if (showSelectionDialog(
                Messages.get("gui.dialog.chooseOperatingSystem"),
                Messages.get("gui.dialog.chooseOperatingSystem.header", state.boardName()),
                content)) {
            ImageEntry selected = listView.getSelectionModel().getSelectedItem();
            if (selected != null) {
                state = new WizardState(
                        selected.manufacturer(),
                        selected.board(),
                        selected,
                        null,
                        compatibleTarget(state.target(), selected, null));
                refreshState();
            }
        }
    }

    /// Opens a local image file selection dialog.
    private void chooseLocalImage() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(Messages.get("gui.dialog.chooseLocalImage"));
        chooser.getExtensionFilters().setAll(
                new FileChooser.ExtensionFilter(Messages.get("gui.fileChooser.imageFiles"), "*.img", "*.raw", "*.bin", "*.iso"),
                new FileChooser.ExtensionFilter(Messages.get("gui.fileChooser.allFiles"), "*.*"));

        @Nullable Path currentLocalImage = state.localImage();
        if (currentLocalImage != null) {
            @Nullable Path parent = currentLocalImage.getParent();
            if (parent != null && Files.isDirectory(parent)) {
                chooser.setInitialDirectory(parent.toFile());
            }
        }

        @Nullable Window owner = ownerWindow();
        @Nullable File selected = chooser.showOpenDialog(owner);
        if (selected == null) {
            return;
        }

        Path selectedPath = selected.toPath();
        state = new WizardState(
                null,
                null,
                null,
                selectedPath,
                compatibleTarget(state.target(), null, selectedPath));
        refreshState();
    }

    /// Opens the storage selection dialog.
    private void chooseStorage() {
        if (requiresFastbootTarget()) {
            Task<List<FastbootDevice>> task = new Task<>() {
                /// Loads fastboot devices outside the JavaFX application thread.
                ///
                /// @return fastboot devices.
                @Override
                protected List<FastbootDevice> call() throws Exception {
                    updateMessage(Messages.get("gui.progress.detectingFastbootDevices"));
                    return services.fastboot().listDevices();
                }
            };

            startBackgroundTask(task, Messages.get("gui.dialog.deviceError"), this::showFastbootDialog);
            return;
        }

        Task<List<BlockDevice>> task = new Task<>() {
            /// Loads target devices outside the JavaFX application thread.
            ///
            /// @return target devices.
            @Override
            protected List<BlockDevice> call() throws Exception {
                updateMessage(Messages.get("gui.progress.detectingDevices"));
                return services.devices().listDevices();
            }
        };

        startBackgroundTask(task, Messages.get("gui.dialog.deviceError"), this::showStorageDialog);
    }

    /// Shows the fastboot target selection dialog.
    ///
    /// @param devices target devices.
    private void showFastbootDialog(List<FastbootDevice> devices) {
        if (devices.isEmpty()) {
            showInfo(Messages.get("gui.dialog.noFastbootDevices"), Messages.get("gui.dialog.noFastbootDevices.message"));
            return;
        }

        ListView<FastbootDevice> listView = selectionListView();
        Node content = searchableSelectionContent(listView, devices, MainWindow::fastbootTargetMatches);
        listView.setCellFactory(_ -> new MFXLegacyListCell<>() {
            /// Updates one fastboot target list cell.
            ///
            /// @param item target device.
            /// @param empty whether the cell is empty.
            @Override
            protected void updateItem(@Nullable FastbootDevice item, boolean empty) {
                super.updateItem(item, empty);
                clearSelectionCellStyles(this);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }

                setText(null);
                setGraphic(fastbootTargetCellContent(item));
                getStyleClass().add("safe-target-cell");
            }
        });
        selectCurrentFastbootTarget(listView, state.target());

        if (showSelectionDialog(
                Messages.get("gui.dialog.chooseFastbootDevice"),
                Messages.get("gui.dialog.chooseFastbootDevice.header"),
                content)) {
            FastbootDevice selected = listView.getSelectionModel().getSelectedItem();
            if (selected != null) {
                state = new WizardState(
                        state.manufacturerName(),
                        state.boardName(),
                        state.image(),
                        state.localImage(),
                        FlashTarget.fastbootDevice(selected));
                refreshState();
            }
        }
    }

    /// Shows the storage selection dialog.
    ///
    /// @param devices target devices.
    private void showStorageDialog(List<BlockDevice> devices) {
        if (devices.isEmpty()) {
            showInfo(Messages.get("gui.dialog.noStorageDevices"), Messages.get("gui.dialog.noStorageDevices.message"));
            return;
        }

        ListView<BlockDevice> listView = selectionListView();
        Node content = searchableSelectionContent(listView, devices, MainWindow::targetMatches);
        listView.setCellFactory(_ -> new MFXLegacyListCell<>() {
            /// Updates one target list cell.
            ///
            /// @param item target device.
            /// @param empty whether the cell is empty.
            @Override
            protected void updateItem(@Nullable BlockDevice item, boolean empty) {
                super.updateItem(item, empty);
                clearSelectionCellStyles(this);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }

                setText(null);
                setGraphic(targetCellContent(item));
                getStyleClass().add(targetWritable(item) ? "safe-target-cell" : "blocked-target-cell");
            }
        });
        selectCurrentBlockTarget(listView, state.target());

        if (showSelectionDialog(
                Messages.get("gui.dialog.chooseStorageDevice"),
                Messages.get("gui.dialog.chooseStorageDevice.header"),
                content)) {
            BlockDevice selected = listView.getSelectionModel().getSelectedItem();
            if (selected != null) {
                state = new WizardState(
                        state.manufacturerName(),
                        state.boardName(),
                        state.image(),
                        state.localImage(),
                        FlashTarget.blockDevice(selected));
                refreshState();
            }
        }
    }

    /// Starts flashing after final confirmation.
    private void flash() {
        if (!hasImageSource() || state.target() == null) {
            showInfo(Messages.get("gui.dialog.incompleteSelection"), Messages.get("gui.dialog.flashIncomplete"));
            return;
        }

        @Nullable ImageEntry selectedImage = state.image();
        @Nullable Path selectedLocalImage = state.localImage();
        @Nullable FlashTarget selectedTarget = state.target();
        if ((selectedImage == null) == (selectedLocalImage == null) || selectedTarget == null) {
            return;
        }

        if (selectedImage != null && !catalogImageFlashable(selectedImage)) {
            showInfo(
                    Messages.get("gui.dialog.unsupportedImage"),
                    Messages.get("gui.dialog.unsupportedImage.message"));
            return;
        }
        @Nullable BlockDevice blockDevice = selectedTarget.blockDevice();
        if (blockDevice != null && !targetWritable(blockDevice)) {
            showInfo(
                    Messages.get("gui.dialog.blockedStorage"),
                    Messages.get("gui.dialog.blockedStorage.message", targetSafetyLabel(blockDevice)));
            return;
        }

        Node confirmContent = flashConfirmationContent(selectedTarget);
        if (!showConfirmationDialog(
                Messages.get("gui.dialog.confirmFlash"),
                Messages.get("gui.dialog.confirmFlash.header"),
                confirmContent,
                "gui.button.flash",
                "material-confirm-dialog")) {
            return;
        }

        Task<OperationResult> task = new Task<>() {
            /// Runs the flash operation outside the JavaFX application thread.
            ///
            /// @return flash result.
            @Override
            protected OperationResult call() throws Exception {
                return services.flash().flash(
                        new FlashRequest(selectedImage, selectedLocalImage, selectedTarget, true),
                        event -> {
                            updateMessage(event.message());
                            @Nullable Long currentBytes = event.currentBytes();
                            @Nullable Long totalBytes = event.totalBytes();
                            if (currentBytes != null && totalBytes != null && totalBytes > 0L) {
                                updateProgress(currentBytes, totalBytes);
                            }
                        });
            }
        };

        startBackgroundTask(task, Messages.get("gui.dialog.flashFailed"), result -> {
            if (result.success()) {
                showInfo(Messages.get("gui.dialog.complete"), result.message());
            } else {
                showError(Messages.get("gui.dialog.flashFailed"), result.message());
            }
        });
    }

    /// Starts a background task and binds UI state.
    ///
    /// @param task task to run.
    /// @param failureTitle title used when the task fails.
    /// @param onSuccess action executed on the JavaFX application thread when the task succeeds.
    private <T> void startBackgroundTask(Task<T> task, String failureTitle, Consumer<T> onSuccess) {
        busy = true;
        refreshState();
        progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        progressBar.setVisible(true);
        progressBar.progressProperty().bind(task.progressProperty());
        statusLabel.textProperty().bind(task.messageProperty());

        task.setOnSucceeded(_ -> {
            finishBackgroundTask();
            @Nullable T result = task.getValue();
            if (result != null) {
                onSuccess.accept(result);
            } else {
                showError(failureTitle, Messages.get("gui.dialog.emptyResult"));
            }
        });
        task.setOnFailed(_ -> {
            finishBackgroundTask();
            Throwable failure = task.getException();
            showError(failureTitle, failure == null ? "Unknown failure." : failure.getMessage());
        });

        Thread thread = new Thread(task, "ruyi-imager-background");
        thread.setDaemon(true);
        thread.start();
    }

    /// Clears background task UI bindings.
    private void finishBackgroundTask() {
        statusLabel.textProperty().unbind();
        progressBar.progressProperty().unbind();
        statusLabel.setText(Messages.get("gui.status.ready"));
        progressBar.setProgress(0);
        progressBar.setVisible(false);
        busy = false;
        refreshState();
    }

    /// Refreshes labels and enabled states from the current selections.
    private void refreshState() {
        manufacturerValue.setText(manufacturerLabel());
        boardValue.setText(boardLabel());
        osValue.setText(osLabel());
        localImageValue.setText(localImageLabel());
        boolean fastbootTarget = requiresFastbootTarget();
        targetTitle.setText(Messages.get(fastbootTarget ? "gui.step.fastboot" : "gui.step.storage"));
        storageButton.setText(Messages.get(fastbootTarget ? "gui.button.chooseFastboot" : "gui.button.chooseStorage"));
        @Nullable FlashTarget target = state.target();
        storageValue.setText(target == null ? targetNoneLabel() : targetLabel(target));
        repoUpdateButton.setDisable(busy);
        manufacturerButton.setDisable(busy);
        boardButton.setDisable(busy || state.localImage() != null || state.manufacturerName() == null);
        osButton.setDisable(busy
                || state.localImage() != null
                || state.manufacturerName() == null
                || state.boardName() == null);
        localImageButton.setDisable(busy);
        storageButton.setDisable(busy || !hasImageSource());
        flashButton.setDisable(!canFlash());
    }

    /// Returns whether the current state can start a flash operation.
    ///
    /// @return whether flashing can start.
    private boolean canFlash() {
        if (busy || !hasImageSource()) {
            return false;
        }

        @Nullable FlashTarget target = state.target();
        if (target == null) {
            return false;
        }

        @Nullable ImageEntry image = state.image();
        if (image != null && !catalogImageFlashable(image)) {
            return false;
        }
        if (requiresFastbootTarget()) {
            return target.isFastbootDevice();
        }

        @Nullable BlockDevice blockDevice = target.blockDevice();
        return blockDevice != null && targetWritable(blockDevice);
    }

    /// Returns whether exactly one image source is selected.
    ///
    /// @return whether an image source is selected.
    private boolean hasImageSource() {
        return (state.image() == null) != (state.localImage() == null);
    }

    /// Returns whether the selected catalog image requires a fastboot target.
    ///
    /// @return whether target selection should use fastboot devices.
    private boolean requiresFastbootTarget() {
        @Nullable ImageEntry image = state.image();
        return image != null && fastbootStrategy(image.strategy());
    }

    /// Returns the localized empty target label for the current strategy.
    ///
    /// @return empty target label.
    private String targetNoneLabel() {
        return requiresFastbootTarget()
                ? Messages.get("gui.value.fastboot.none")
                : Messages.get("gui.value.storage.none");
    }

    /// Keeps a target only when it matches the selected image source.
    ///
    /// @param target current target.
    /// @param image selected catalog image.
    /// @param localImage selected local image.
    /// @return compatible target, or null when target type no longer matches.
    private static @Nullable FlashTarget compatibleTarget(
            @Nullable FlashTarget target,
            @Nullable ImageEntry image,
            @Nullable Path localImage) {
        if (target == null) {
            return null;
        }
        if (localImage != null) {
            return target.isBlockDevice() ? target : null;
        }
        if (image != null && fastbootStrategy(image.strategy())) {
            return target.isFastbootDevice() ? target : null;
        }
        return target.isBlockDevice() ? target : null;
    }

    /// Builds the final destructive-operation confirmation content.
    ///
    /// @param target selected target device.
    /// @return confirmation content.
    private Node flashConfirmationContent(FlashTarget target) {
        GridPane summary = new GridPane();
        summary.getStyleClass().add("confirmation-summary");
        summary.setHgap(18);
        summary.setVgap(8);
        addConfirmationRow(summary, 0, "gui.dialog.confirmFlash.manufacturer", manufacturerLabel());
        addConfirmationRow(summary, 1, "gui.dialog.confirmFlash.board", boardLabel());
        addConfirmationRow(summary, 2, "gui.dialog.confirmFlash.imageSource", imageSourceLabel());
        addConfirmationRow(summary, 3, "gui.dialog.confirmFlash.target", targetLabel(target));

        Label warning = new Label(Messages.get(target.isFastbootDevice()
                ? "gui.dialog.confirmFlash.fastbootWarning"
                : "gui.dialog.confirmFlash.storageWarning"));
        warning.setWrapText(true);
        warning.getStyleClass().add("confirmation-warning");

        VBox content = new VBox(12, summary, warning);
        content.getStyleClass().add("confirmation-content");
        return content;
    }

    /// Adds one row to a confirmation summary grid.
    ///
    /// @param summary target summary grid.
    /// @param row row index.
    /// @param keyLabelKey message key for the row label.
    /// @param valueText row value.
    private static void addConfirmationRow(GridPane summary, int row, String keyLabelKey, String valueText) {
        Label keyLabel = new Label(Messages.get(keyLabelKey));
        keyLabel.getStyleClass().add("confirmation-key");

        Label valueLabel = new Label(valueText);
        valueLabel.setWrapText(true);
        valueLabel.getStyleClass().add("confirmation-value");
        GridPane.setHgrow(valueLabel, Priority.ALWAYS);

        summary.add(keyLabel, 0, row);
        summary.add(valueLabel, 1, row);
    }

    /// Formats the manufacturer step label.
    ///
    /// @return manufacturer step label.
    private String manufacturerLabel() {
        if (state.localImage() != null) {
            return Messages.get("gui.value.skippedLocal");
        }
        return state.manufacturerName() == null ? Messages.get("gui.value.manufacturer.none") : state.manufacturerName();
    }

    /// Formats the board step label.
    ///
    /// @return board step label.
    private String boardLabel() {
        if (state.localImage() != null) {
            return Messages.get("gui.value.skippedLocal");
        }
        return state.boardName() == null ? Messages.get("gui.value.board.none") : state.boardName();
    }

    /// Formats the operating system catalog step label.
    ///
    /// @return operating system step label.
    private String osLabel() {
        if (state.localImage() != null) {
            return Messages.get("gui.value.skippedLocal");
        }

        @Nullable ImageEntry image = state.image();
        return image == null ? Messages.get("gui.value.os.none") : imageLabel(image, imageCacheStatus(image));
    }

    /// Formats the selected local image option.
    ///
    /// @return local image option label.
    private String localImageLabel() {
        @Nullable Path localImage = state.localImage();
        if (localImage == null) {
            return Messages.get("gui.value.local.none");
        }

        @Nullable Path fileName = localImage.getFileName();
        return Messages.get("gui.value.local.selected", fileName == null ? localImage : fileName);
    }

    /// Formats the selected image source.
    ///
    /// @return source label.
    private String imageSourceLabel() {
        @Nullable ImageEntry image = state.image();
        if (image != null) {
            return imageLabel(image, imageCacheStatus(image));
        }

        @Nullable Path localImage = state.localImage();
        if (localImage != null) {
            @Nullable Path fileName = localImage.getFileName();
            return Messages.get("gui.value.local.source", fileName == null ? localImage : fileName);
        }

        return Messages.get("gui.value.os.none");
    }

    /// Builds manufacturer choices from image metadata.
    ///
    /// @param images available images.
    /// @return manufacturer options sorted by manufacturer name.
    private static @Unmodifiable List<ManufacturerOption> manufacturerOptions(List<ImageEntry> images) {
        Map<String, Integer> imageCounts = new LinkedHashMap<>();
        Map<String, List<String>> boardNames = new LinkedHashMap<>();
        for (ImageEntry image : images) {
            String manufacturer = image.manufacturer();
            imageCounts.merge(manufacturer, 1, Integer::sum);
            List<String> boards = boardNames.computeIfAbsent(manufacturer, _ -> new ArrayList<>());
            if (!boards.contains(image.board())) {
                boards.add(image.board());
            }
        }

        ArrayList<ManufacturerOption> manufacturers = new ArrayList<>(imageCounts.size());
        for (Map.Entry<String, Integer> entry : imageCounts.entrySet()) {
            List<String> boards = boardNames.get(entry.getKey());
            manufacturers.add(new ManufacturerOption(
                    entry.getKey(),
                    boards == null ? 0 : boards.size(),
                    entry.getValue()));
        }
        manufacturers.sort(Comparator.comparing(ManufacturerOption::name));
        return List.copyOf(manufacturers);
    }

    /// Builds board choices from image metadata.
    ///
    /// @param images available images.
    /// @param manufacturerName selected manufacturer name.
    /// @return board options sorted by board name.
    private static @Unmodifiable List<BoardOption> boardOptions(
            List<ImageEntry> images,
            @Nullable String manufacturerName) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ImageEntry image : images) {
            if (manufacturerName == null || image.manufacturer().equals(manufacturerName)) {
                counts.merge(image.board(), 1, Integer::sum);
            }
        }

        ArrayList<BoardOption> boards = new ArrayList<>(counts.size());
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            boards.add(new BoardOption(entry.getKey(), entry.getValue()));
        }
        boards.sort(Comparator.comparing(BoardOption::name));
        return List.copyOf(boards);
    }

    /// Filters images by the selected board.
    ///
    /// @param images available images.
    /// @param manufacturerName selected manufacturer name.
    /// @param boardName selected board name.
    /// @return matching image list.
    private static @Unmodifiable List<ImageEntry> filteredImages(
            List<ImageEntry> images,
            @Nullable String manufacturerName,
            @Nullable String boardName) {
        if (manufacturerName == null && boardName == null) {
            return List.copyOf(images);
        }

        ArrayList<ImageEntry> filtered = new ArrayList<>();
        for (ImageEntry image : images) {
            if ((manufacturerName == null || image.manufacturer().equals(manufacturerName))
                    && (boardName == null || image.board().equals(boardName))) {
                filtered.add(image);
            }
        }
        return List.copyOf(filtered);
    }

    /// Selects the current manufacturer when the dialog opens.
    ///
    /// @param listView manufacturer list view.
    /// @param manufacturerName selected manufacturer name.
    private static void selectCurrentManufacturer(
            ListView<ManufacturerOption> listView,
            @Nullable String manufacturerName) {
        if (manufacturerName == null) {
            listView.getSelectionModel().selectFirst();
            return;
        }

        for (int i = 0; i < listView.getItems().size(); i++) {
            if (listView.getItems().get(i).name().equals(manufacturerName)) {
                listView.getSelectionModel().select(i);
                return;
            }
        }
        listView.getSelectionModel().selectFirst();
    }

    /// Selects the current board when the dialog opens.
    ///
    /// @param listView board list view.
    /// @param boardName selected board name.
    private static void selectCurrentBoard(ListView<BoardOption> listView, @Nullable String boardName) {
        if (boardName == null) {
            listView.getSelectionModel().selectFirst();
            return;
        }

        for (int i = 0; i < listView.getItems().size(); i++) {
            if (listView.getItems().get(i).name().equals(boardName)) {
                listView.getSelectionModel().select(i);
                return;
            }
        }
        listView.getSelectionModel().selectFirst();
    }

    /// Selects the current image when the dialog opens.
    ///
    /// @param listView image list view.
    /// @param image selected image.
    private static void selectCurrentImage(ListView<ImageEntry> listView, @Nullable ImageEntry image) {
        if (image == null) {
            listView.getSelectionModel().selectFirst();
            return;
        }

        for (int i = 0; i < listView.getItems().size(); i++) {
            if (listView.getItems().get(i).atom().equals(image.atom())) {
                listView.getSelectionModel().select(i);
                return;
            }
        }
        listView.getSelectionModel().selectFirst();
    }

    /// Selects the current block target when the dialog opens.
    ///
    /// @param listView target list view.
    /// @param target selected target.
    private static void selectCurrentBlockTarget(ListView<BlockDevice> listView, @Nullable FlashTarget target) {
        @Nullable BlockDevice blockDevice = target == null ? null : target.blockDevice();
        if (blockDevice == null) {
            listView.getSelectionModel().selectFirst();
            return;
        }

        for (int i = 0; i < listView.getItems().size(); i++) {
            if (listView.getItems().get(i).id().equals(blockDevice.id())) {
                listView.getSelectionModel().select(i);
                return;
            }
        }
        listView.getSelectionModel().selectFirst();
    }

    /// Selects the current fastboot target when the dialog opens.
    ///
    /// @param listView target list view.
    /// @param target selected target.
    private static void selectCurrentFastbootTarget(ListView<FastbootDevice> listView, @Nullable FlashTarget target) {
        @Nullable FastbootDevice fastbootDevice = target == null ? null : target.fastbootDevice();
        if (fastbootDevice == null) {
            listView.getSelectionModel().selectFirst();
            return;
        }

        for (int i = 0; i < listView.getItems().size(); i++) {
            if (listView.getItems().get(i).id().equals(fastbootDevice.id())) {
                listView.getSelectionModel().select(i);
                return;
            }
        }
        listView.getSelectionModel().selectFirst();
    }

    /// Returns whether a manufacturer option matches a search query.
    ///
    /// @param manufacturer manufacturer option.
    /// @param query normalized query.
    /// @return whether the option matches.
    private static boolean manufacturerMatches(ManufacturerOption manufacturer, String query) {
        return textMatches(manufacturer.name(), query)
                || textMatches(Messages.get(
                        "gui.list.manufacturer",
                        manufacturer.name(),
                        manufacturer.boardCount(),
                        manufacturer.imageCount()), query);
    }

    /// Returns whether a board option matches a search query.
    ///
    /// @param board board option.
    /// @param query normalized query.
    /// @return whether the option matches.
    private static boolean boardMatches(BoardOption board, String query) {
        return textMatches(board.name(), query)
                || textMatches(Messages.get("gui.list.board", board.name(), board.imageCount()), query);
    }

    /// Returns whether an image entry matches a search query.
    ///
    /// @param image image entry.
    /// @param query normalized query.
    /// @return whether the image matches.
    private static boolean imageMatches(ImageEntry image, String query) {
        return textMatches(image.displayName(), query)
                || textMatches(image.manufacturer(), query)
                || textMatches(image.board(), query)
                || textMatches(image.variant(), query)
                || textMatches(image.strategy(), query)
                || textMatches(image.atom(), query)
                || textMatches(imageSupportLabel(image), query);
    }

    /// Returns whether a target device matches a search query.
    ///
    /// @param target target device.
    /// @param query normalized query.
    /// @return whether the target matches.
    private static boolean targetMatches(BlockDevice target, String query) {
        return textMatches(target.displayName(), query)
                || textMatches(targetPathText(target), query)
                || textMatches(targetSizeLabel(target), query)
                || textMatches(targetSafetyLabel(target), query)
                || textMatches(target.model(), query)
                || textMatches(target.busType(), query);
    }

    /// Returns whether a fastboot target matches a search query.
    ///
    /// @param target target device.
    /// @param query normalized query.
    /// @return whether the target matches.
    private static boolean fastbootTargetMatches(FastbootDevice target, String query) {
        return textMatches(target.displayName(), query)
                || textMatches(target.serial(), query)
                || textMatches(target.state(), query);
    }

    /// Returns whether text contains a normalized search query.
    ///
    /// @param text text to search.
    /// @param query normalized query.
    /// @return whether the text matches.
    private static boolean textMatches(@Nullable String text, String query) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(query);
    }

    /// Reads cache statuses for a list of images.
    ///
    /// @param images image entries.
    /// @return cache status map keyed by image atom.
    private @Unmodifiable Map<String, ImageCacheStatus> imageCacheStatuses(List<ImageEntry> images) {
        LinkedHashMap<String, ImageCacheStatus> result = new LinkedHashMap<>();
        for (ImageEntry image : images) {
            result.put(image.atom(), imageCacheStatus(image));
        }
        return Map.copyOf(result);
    }

    /// Reads the cache status for one image.
    ///
    /// @param image image entry.
    /// @return cache status, or unknown when status inspection fails.
    private ImageCacheStatus imageCacheStatus(ImageEntry image) {
        try {
            return services.images().cacheStatus(image);
        } catch (IOException _) {
            return ImageCacheStatus.unknown(image.distfiles().size());
        }
    }

    /// Returns a cached status for one image.
    ///
    /// @param cacheStatuses cache status map keyed by image atom.
    /// @param image image entry.
    /// @return cache status.
    private static ImageCacheStatus cacheStatus(
            Map<String, ImageCacheStatus> cacheStatuses,
            ImageEntry image) {
        @Nullable ImageCacheStatus cacheStatus = cacheStatuses.get(image.atom());
        return cacheStatus == null ? ImageCacheStatus.unknown(image.distfiles().size()) : cacheStatus;
    }

    /// Clears state-dependent list cell styles.
    ///
    /// @param cell list cell to reset.
    private static void clearSelectionCellStyles(MFXLegacyListCell<?> cell) {
        cell.getStyleClass().removeAll(
                "flashable-image-cell",
                "unsupported-image-cell",
                "safe-target-cell",
                "blocked-target-cell");
    }

    /// Creates rich content for one image list cell.
    ///
    /// @param image image entry.
    /// @param cacheStatus image cache status.
    /// @return cell content.
    private static Node imageCellContent(ImageEntry image, ImageCacheStatus cacheStatus) {
        Label title = new Label(image.displayName());
        title.setWrapText(true);
        title.getStyleClass().add("selection-title");

        Label details = new Label(Messages.get(
                "gui.image.details",
                image.variant(),
                image.strategy(),
                imageCacheStatusLabel(cacheStatus)));
        details.setWrapText(true);
        details.getStyleClass().add("selection-detail");

        Label supportStatus = statusPill(
                imageSupportLabel(image),
                catalogImageFlashable(image) ? "status-supported" : "status-blocked");
        Label cacheStatusLabel = statusPill(imageCacheStatusLabel(cacheStatus), imageCacheStatusStyle(cacheStatus));
        HBox statusRow = new HBox(6, supportStatus, cacheStatusLabel);
        statusRow.getStyleClass().add("selection-pill-row");

        VBox content = new VBox(4, title, details, statusRow);
        content.getStyleClass().add("selection-cell-content");
        return content;
    }

    /// Creates rich content for one target list cell.
    ///
    /// @param target target device.
    /// @return cell content.
    private static Node targetCellContent(BlockDevice target) {
        Label title = new Label(target.displayName());
        title.setWrapText(true);
        title.getStyleClass().add("selection-title");

        Label details = new Label(Messages.get("gui.target.details", targetPathText(target), targetSizeLabel(target)));
        details.setWrapText(true);
        details.getStyleClass().add("selection-detail");

        boolean writable = targetWritable(target);
        String statusText = writable ? Messages.get("gui.target.ready") : targetSafetyLabel(target);
        Label status = statusPill(statusText, writable ? "status-supported" : "status-blocked");
        HBox statusRow = new HBox(status);
        statusRow.getStyleClass().add("selection-pill-row");

        VBox content = new VBox(4, title, details, statusRow);
        content.getStyleClass().add("selection-cell-content");
        return content;
    }

    /// Creates rich content for one fastboot target list cell.
    ///
    /// @param target target device.
    /// @return cell content.
    private static Node fastbootTargetCellContent(FastbootDevice target) {
        Label title = new Label(target.displayName());
        title.setWrapText(true);
        title.getStyleClass().add("selection-title");

        Label details = new Label(Messages.get("gui.fastboot.details", target.serial(), target.state()));
        details.setWrapText(true);
        details.getStyleClass().add("selection-detail");

        Label status = statusPill(Messages.get("gui.fastboot.ready"), "status-supported");
        HBox statusRow = new HBox(status);
        statusRow.getStyleClass().add("selection-pill-row");

        VBox content = new VBox(4, title, details, statusRow);
        content.getStyleClass().add("selection-cell-content");
        return content;
    }

    /// Creates a compact status label.
    ///
    /// @param text label text.
    /// @param styleClass state-specific style class.
    /// @return status label.
    private static Label statusPill(String text, String styleClass) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.getStyleClass().add("status-pill");
        label.getStyleClass().add(styleClass);
        return label;
    }

    /// Formats an image for list and summary display.
    ///
    /// @param image image entry.
    /// @param cacheStatus image cache status.
    /// @return display text.
    private static String imageLabel(ImageEntry image, ImageCacheStatus cacheStatus) {
        return Messages.get(
                "gui.image.summary",
                image.displayName(),
                image.variant(),
                image.strategy(),
                imageSupportLabel(image),
                imageCacheStatusLabel(cacheStatus));
    }

    /// Returns whether the current GUI writer can flash a catalog image.
    ///
    /// @param image image entry.
    /// @return whether the image is flashable through the current local writer.
    private static boolean catalogImageFlashable(ImageEntry image) {
        if (image.support() != StrategySupport.SUPPORTED) {
            return false;
        }
        if ("dd-v1".equals(image.strategy())) {
            return image.partitionMap().size() == 1;
        }
        return fastbootStrategy(image.strategy()) && !image.partitionMap().isEmpty();
    }

    /// Returns whether a strategy uses fastboot.
    ///
    /// @param strategy strategy name.
    /// @return whether this is a fastboot strategy.
    private static boolean fastbootStrategy(String strategy) {
        return "fastboot-v1".equals(strategy) || "fastboot-v1(lpi4a-uboot)".equals(strategy);
    }

    /// Formats the current flash support state for an image.
    ///
    /// @param image image entry.
    /// @return localized support label.
    private static String imageSupportLabel(ImageEntry image) {
        if (catalogImageFlashable(image)) {
            if (fastbootStrategy(image.strategy())) {
                return Messages.get("gui.image.support.fastbootFlashable");
            }
            return Messages.get("gui.image.support.flashable");
        }
        if (image.support() == StrategySupport.UNKNOWN) {
            return Messages.get("gui.image.support.unknown");
        }
        if (image.support() == StrategySupport.UNSUPPORTED) {
            return Messages.get("gui.image.support.unsupported");
        }
        if (fastbootStrategy(image.strategy())) {
            return Messages.get("gui.image.support.noPartitions");
        }
        if (!"dd-v1".equals(image.strategy())) {
            return Messages.get("gui.image.support.writerUnsupported");
        }
        return Messages.get("gui.image.support.multiTargetUnsupported");
    }

    /// Formats an image cache state.
    ///
    /// @param cacheStatus image cache status.
    /// @return localized cache label.
    private static String imageCacheStatusLabel(ImageCacheStatus cacheStatus) {
        return switch (cacheStatus.state()) {
            case COMPLETE -> Messages.get("gui.image.cache.cached");
            case PARTIAL -> Messages.get(
                    "gui.image.cache.partial",
                    cacheStatus.cachedDistfiles(),
                    cacheStatus.totalDistfiles());
            case EMPTY -> Messages.get("gui.image.cache.downloadRequired");
            case MANUAL_REQUIRED -> Messages.get("gui.image.cache.manualRequired");
            case UNKNOWN -> Messages.get("gui.image.cache.unknown");
        };
    }

    /// Chooses a style class for an image cache state.
    ///
    /// @param cacheStatus image cache status.
    /// @return style class.
    private static String imageCacheStatusStyle(ImageCacheStatus cacheStatus) {
        return switch (cacheStatus.state()) {
            case COMPLETE -> "status-supported";
            case PARTIAL -> "status-warning";
            case EMPTY, UNKNOWN -> "status-neutral";
            case MANUAL_REQUIRED -> "status-blocked";
        };
    }

    /// Formats a target for list and summary display.
    ///
    /// @param target target device.
    /// @return display text.
    private static String targetLabel(FlashTarget target) {
        @Nullable BlockDevice blockDevice = target.blockDevice();
        if (blockDevice != null) {
            return targetLabel(blockDevice);
        }

        @Nullable FastbootDevice fastbootDevice = target.fastbootDevice();
        if (fastbootDevice != null) {
            return fastbootTargetLabel(fastbootDevice);
        }

        return "";
    }

    /// Formats a block target for list and summary display.
    ///
    /// @param target target device.
    /// @return display text.
    private static String targetLabel(BlockDevice target) {
        String safety = targetSafetyLabel(target);
        return Messages.get(
                "gui.target.summary",
                target.displayName(),
                targetPathText(target),
                targetSizeLabel(target),
                safety.isEmpty() ? Messages.get("gui.target.ready") : safety);
    }

    /// Formats a fastboot target for list and summary display.
    ///
    /// @param target target device.
    /// @return display text.
    private static String fastbootTargetLabel(FastbootDevice target) {
        return Messages.get("gui.fastboot.summary", target.serial(), target.state());
    }

    /// Returns whether a target can be written by the GUI.
    ///
    /// @param target target device.
    /// @return whether the target is not blocked by safety flags.
    private static boolean targetWritable(BlockDevice target) {
        return !target.system() && !target.mounted() && !target.readOnly();
    }

    /// Formats a target size.
    ///
    /// @param target target device.
    /// @return human-readable target size.
    private static String targetSizeLabel(BlockDevice target) {
        long size = target.sizeBytes();
        if (size <= 0L) {
            return Messages.get("gui.target.sizeUnknown");
        }

        double value = size;
        int unitIndex = 0;
        while (value >= 1024.0 && unitIndex < SIZE_UNITS.size() - 1) {
            value /= 1024.0;
            unitIndex++;
        }
        if (unitIndex == 0) {
            return "%d %s".formatted(size, SIZE_UNITS.get(unitIndex));
        }
        return String.format(Locale.ROOT, "%.1f %s", value, SIZE_UNITS.get(unitIndex));
    }

    /// Converts a target path to display text.
    ///
    /// @param target target device.
    /// @return printable target path.
    private static String targetPathText(BlockDevice target) {
        String text = target.path().toString();
        if (text.startsWith("\\\\.\\PHYSICALDRIVE") && text.endsWith("\\")) {
            return text.substring(0, text.length() - 1);
        }
        return text;
    }

    /// Formats target safety flags.
    ///
    /// @param target target device.
    /// @return safety label, or empty string when no warning applies.
    private static String targetSafetyLabel(BlockDevice target) {
        ArrayList<String> flags = new ArrayList<>(3);
        if (target.system()) {
            flags.add(Messages.get("gui.target.system"));
        }
        if (target.mounted()) {
            flags.add(Messages.get("gui.target.mounted"));
        }
        if (target.readOnly()) {
            flags.add(Messages.get("gui.target.readOnly"));
        }
        if (flags.isEmpty()) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < flags.size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(flags.get(i));
        }
        return Messages.get("gui.target.blocked", builder);
    }

    /// Creates the image-empty message for the current state.
    ///
    /// @return message shown to the user.
    private String imageEmptyMessage() {
        @Nullable String boardName = state.boardName();
        if (boardName == null) {
            return Messages.get("gui.image.empty");
        }
        return Messages.get("gui.image.emptyForBoard", boardName);
    }

    /// Shows an informational dialog.
    ///
    /// @param title dialog title.
    /// @param message dialog message.
    private void showInfo(String title, String message) {
        showMessageDialog(title, message, "material-info-dialog");
    }

    /// Shows an error dialog.
    ///
    /// @param title dialog title.
    /// @param message dialog message.
    private void showError(String title, @Nullable String message) {
        showMessageDialog(
                title,
                message == null ? Messages.get("gui.dialog.unknownFailure") : message,
                "material-error-dialog");
    }

    /// Shows a MaterialFX message dialog on the JavaFX application thread.
    ///
    /// @param title dialog title.
    /// @param message dialog message.
    /// @param styleClass dialog style class.
    private void showMessageDialog(String title, String message, String styleClass) {
        Runnable action = () -> showMaterialDialog(
                title,
                title,
                messageContent(message),
                "gui.dialog.ok",
                styleClass,
                false);

        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }

    /// Shows a MaterialFX selection dialog.
    ///
    /// @param title dialog title.
    /// @param header dialog header.
    /// @param content dialog content.
    /// @return whether the user accepted the dialog.
    private boolean showSelectionDialog(String title, String header, Node content) {
        return showConfirmationDialog(title, header, content, "gui.dialog.select", "material-selection-dialog");
    }

    /// Shows a MaterialFX confirmation dialog with text content.
    ///
    /// @param title dialog title.
    /// @param header dialog header.
    /// @param message dialog message.
    /// @param confirmKey confirm button message key.
    /// @return whether the user accepted the dialog.
    private boolean showConfirmationDialog(String title, String header, String message, String confirmKey) {
        return showConfirmationDialog(title, header, messageContent(message), confirmKey, "material-confirm-dialog");
    }

    /// Shows a MaterialFX confirmation dialog with custom content.
    ///
    /// @param title dialog title.
    /// @param header dialog header.
    /// @param content dialog content.
    /// @param confirmKey confirm button message key.
    /// @param styleClass dialog style class.
    /// @return whether the user accepted the dialog.
    private boolean showConfirmationDialog(
            String title,
            String header,
            Node content,
            String confirmKey,
            String styleClass) {
        return showMaterialDialog(title, header, content, confirmKey, styleClass, true);
    }

    /// Shows a MaterialFX dialog.
    ///
    /// @param title dialog title.
    /// @param header dialog header.
    /// @param content dialog content.
    /// @param confirmKey confirm button message key.
    /// @param styleClass dialog style class.
    /// @param showCancel whether to show a cancel action.
    /// @return whether the user accepted the dialog.
    private boolean showMaterialDialog(
            String title,
            String header,
            Node content,
            String confirmKey,
            String styleClass,
            boolean showCancel) {
        AtomicBoolean accepted = new AtomicBoolean();
        @Nullable MFXButton cancelButton = showCancel
                ? dialogActionButton("gui.dialog.cancel", "dialog-secondary-button")
                : null;
        MFXButton confirmButton = dialogActionButton(confirmKey, "dialog-primary-button");
        MFXGenericDialogBuilder dialogBuilder = MFXGenericDialogBuilder.build()
                .setHeaderText(header)
                .setContent(content)
                .setShowClose(false)
                .setShowMinimize(false)
                .setShowAlwaysOnTop(false)
                .addStyleClasses("material-dialog", styleClass);
        @Nullable String stylesheet = applicationStylesheet();
        if (stylesheet != null) {
            dialogBuilder.addStylesheets(stylesheet);
        }
        if (cancelButton == null) {
            dialogBuilder.addActions(confirmButton);
        } else {
            dialogBuilder.addActions(cancelButton, confirmButton);
        }
        MFXGenericDialog dialogContent = dialogBuilder.get();
        MFXStageDialogBuilder builder = MFXStageDialogBuilder.build()
                .setContent(dialogContent)
                .setOwnerNode(root)
                .setCenterInOwnerNode(true)
                .setScrimOwner(true)
                .setScrimStrength(0.35)
                .setDraggable(true)
                .setOverlayClose(false)
                .initModality(Modality.WINDOW_MODAL)
                .setTitle(title);
        @Nullable Window owner = ownerWindow();
        if (owner != null) {
            builder.initOwner(owner);
        }

        MFXStageDialog dialog = builder.get();
        if (cancelButton != null) {
            cancelButton.setOnAction(_ -> dialog.close());
        }
        confirmButton.setOnAction(_ -> {
            accepted.set(true);
            dialog.close();
        });
        dialog.showAndWait();
        dialog.dispose();
        return accepted.get();
    }

    /// Creates a MaterialFX dialog button.
    ///
    /// @param key message key.
    /// @param styleClass button style class.
    /// @return dialog action button.
    private static MFXButton dialogActionButton(String key, String styleClass) {
        MFXButton button = localizedButton(key);
        button.getStyleClass().add("dialog-button");
        button.getStyleClass().add(styleClass);
        return button;
    }

    /// Creates wrapped message content for MaterialFX dialogs.
    ///
    /// @param message message text.
    /// @return message content label.
    private static Label messageContent(String message) {
        Label label = new Label(message);
        label.setWrapText(true);
        label.getStyleClass().add("dialog-message");
        return label;
    }

    /// Returns the current window owner.
    ///
    /// @return owner window, or null before the root is attached to a scene.
    private @Nullable Window ownerWindow() {
        return root.getScene() == null ? null : root.getScene().getWindow();
    }

    /// Returns the application stylesheet URL for independent dialog scenes.
    ///
    /// @return stylesheet URL, or null when the resource is unavailable.
    private static @Nullable String applicationStylesheet() {
        URL stylesheet = MainWindow.class.getResource("/org/glavo/ruyi/imager/gui/application.css");
        return stylesheet == null ? null : stylesheet.toExternalForm();
    }

    /// Supported GUI language option.
    ///
    /// @param labelKey message key for the language label.
    /// @param locale locale selected by this option.
    @NotNullByDefault
    private record LanguageOption(String labelKey, Locale locale) {
    }

    /// Holds the current guided workflow selections.
    ///
    /// @param manufacturerName selected manufacturer name.
    /// @param boardName selected board name.
    /// @param image selected operating system image.
    /// @param localImage selected local image file.
    /// @param target selected target device.
    @NotNullByDefault
    private record WizardState(
            @Nullable String manufacturerName,
            @Nullable String boardName,
            @Nullable ImageEntry image,
            @Nullable Path localImage,
            @Nullable FlashTarget target) {
    }

    /// Manufacturer option derived from image metadata.
    ///
    /// @param name manufacturer name.
    /// @param boardCount number of boards available for the manufacturer.
    /// @param imageCount number of images available for the manufacturer.
    @NotNullByDefault
    private record ManufacturerOption(String name, int boardCount, int imageCount) {
    }

    /// Board option derived from image metadata.
    ///
    /// @param name board name.
    /// @param imageCount number of images available for the board.
    @NotNullByDefault
    private record BoardOption(String name, int imageCount) {
    }
}

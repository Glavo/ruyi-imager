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
import javafx.scene.control.TextField;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
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
import io.github.palexdev.materialfx.controls.legacy.MFXLegacyListCell;
import io.github.palexdev.materialfx.controls.legacy.MFXLegacyListView;
import io.github.palexdev.materialfx.dialogs.MFXGenericDialog;
import io.github.palexdev.materialfx.dialogs.MFXGenericDialogBuilder;
import io.github.palexdev.materialfx.dialogs.MFXStageDialog;
import io.github.palexdev.materialfx.dialogs.MFXStageDialogBuilder;
import org.glavo.ruyi.imager.core.AppServices;
import org.glavo.ruyi.imager.core.OperationResult;
import org.glavo.ruyi.imager.core.ProvisionStrategies;
import org.glavo.ruyi.imager.core.ProgressEvent;
import org.glavo.ruyi.imager.core.StrategySupport;
import org.glavo.ruyi.imager.core.device.BlockDevice;
import org.glavo.ruyi.imager.core.fastboot.FastbootDevice;
import org.glavo.ruyi.imager.core.flash.FlashRequest;
import org.glavo.ruyi.imager.core.flash.FlashTarget;
import org.glavo.ruyi.imager.core.image.ImageCacheStatus;
import org.glavo.ruyi.imager.core.image.ImageCatalog;
import org.glavo.ruyi.imager.core.image.ImageEntry;
import org.glavo.ruyi.imager.i18n.Messages;
import org.glavo.ruyi.imager.logging.LoggingProgressReporter;
import org.glavo.ruyi.imager.logging.RuyiLogging;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.glavo.ruyi.imager.gui.GuiSelectionRules.catalogImageFlashable;
import static org.glavo.ruyi.imager.gui.GuiSelectionRules.compatibleTarget;
import static org.glavo.ruyi.imager.gui.GuiSelectionRules.partitionTargetKeysMatch;
import static org.glavo.ruyi.imager.gui.GuiSelectionRules.partitionTargetsReady;
import static org.glavo.ruyi.imager.gui.GuiSelectionRules.supportedTargets;
import static org.glavo.ruyi.imager.gui.GuiSelectionRules.targetPreparablyMounted;
import static org.glavo.ruyi.imager.gui.GuiSelectionRules.targetWritable;

/// Main JavaFX window for the guided imager workflow.
@NotNullByDefault
public final class MainWindow {
    /// Logger for GUI workflow events.
    private static final Logger LOGGER = LoggerFactory.getLogger(MainWindow.class);

    /// Language choices exposed in the GUI.
    private static final @Unmodifiable List<LanguageOption> LANGUAGE_OPTIONS = List.of(
            new LanguageOption("gui.language.english", Locale.ENGLISH),
            new LanguageOption("gui.language.simplifiedChinese", Locale.SIMPLIFIED_CHINESE));

    /// Binary size units used by storage device summaries.
    private static final @Unmodifiable List<String> SIZE_UNITS = List.of("B", "KiB", "MiB", "GiB", "TiB");

    /// Operating system category aliases recognized from Ruyi image metadata.
    private static final @Unmodifiable List<OperatingSystemCategoryAlias> OPERATING_SYSTEM_CATEGORY_ALIASES = List.of(
            new OperatingSystemCategoryAlias("revyos", "RevyOS"),
            new OperatingSystemCategoryAlias("ubuntu", "Ubuntu"),
            new OperatingSystemCategoryAlias("debian", "Debian"),
            new OperatingSystemCategoryAlias("openwrt", "OpenWrt"),
            new OperatingSystemCategoryAlias("openbsd", "OpenBSD"),
            new OperatingSystemCategoryAlias("freebsd", "FreeBSD"),
            new OperatingSystemCategoryAlias("openkylin", "openKylin"),
            new OperatingSystemCategoryAlias("fedora", "Fedora"),
            new OperatingSystemCategoryAlias("archlinux", "Arch Linux"),
            new OperatingSystemCategoryAlias("arch", "Arch Linux"),
            new OperatingSystemCategoryAlias("alpine", "Alpine Linux"),
            new OperatingSystemCategoryAlias("gentoo", "Gentoo"),
            new OperatingSystemCategoryAlias("deepin", "deepin"),
            new OperatingSystemCategoryAlias("android", "Android"),
            new OperatingSystemCategoryAlias("buildroot", "Buildroot"),
            new OperatingSystemCategoryAlias("rtthread", "RT-Thread"),
            new OperatingSystemCategoryAlias("zephyr", "Zephyr"),
            new OperatingSystemCategoryAlias("u-boot", "U-Boot"),
            new OperatingSystemCategoryAlias("uboot", "U-Boot"));

    /// Fixed width used by modal selection lists.
    private static final double SELECTION_LIST_WIDTH = 640.0;

    /// Fixed height used by modal selection lists so dialog actions never overlap them.
    private static final double SELECTION_LIST_HEIGHT = 320.0;

    /// Compact bottom inset between modal selection lists and dialog actions.
    private static final double SELECTION_CONTENT_BOTTOM_INSET = 14.0;

    /// Large logo resource shown beside the main title.
    private static final String HEADER_LOGO_RESOURCE = "/ruyi-logo-128.png";

    /// Rendered logo size used in the application header.
    private static final double HEADER_LOGO_SIZE = 58.0;

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

    /// Progress rows shown for each flash operation stage.
    private final VBox phaseProgressBox = new VBox(8);

    /// Progress rows keyed by stage identifier for the active flash operation.
    private final LinkedHashMap<String, PhaseProgressRow> phaseProgressRows = new LinkedHashMap<>();

    /// Selection controls shown before a flash operation starts.
    private final VBox selectionControlsBox = new VBox(14);

    /// Compact flash progress surface shown while a flash operation is running.
    private final VBox activeFlashBox = new VBox(16);

    /// Generation token used to ignore late progress events from a finished flash task.
    private long phaseProgressGeneration;

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

    /// Active flash manufacturer summary.
    private final Label activeManufacturerValue = new Label();

    /// Active flash board summary.
    private final Label activeBoardValue = new Label();

    /// Active flash image summary.
    private final Label activeImageValue = new Label();

    /// Active flash target summary.
    private final Label activeTargetValue = new Label();

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

    /// Cancel action shown while a flash operation is active.
    private final Button cancelFlashButton = localizedButton("gui.button.cancelFlash");

    /// Thread running the current background task, or null when idle.
    private @Nullable Thread currentBackgroundThread;

    /// Whether a background operation is active.
    private boolean busy;

    /// Whether the active background operation is a flash operation.
    private boolean flashInProgress;

    /// Whether cancellation has already been requested for the current flash operation.
    private boolean cancellationRequested;

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
        LOGGER.info("Main window initialized.");
    }

    /// Returns the root node for scene installation.
    ///
    /// @return root node.
    public Parent root() {
        return root;
    }

    /// Shows the first-run safety warning when it has not been accepted yet.
    public void showStartupSafetyWarningIfNeeded() {
        boolean accepted;
        try {
            accepted = preferences.readStartupSafetyWarningAccepted();
        } catch (IOException exception) {
            LOGGER.warn("Failed to read GUI startup safety warning preference.", exception);
            accepted = false;
        }

        if (accepted) {
            return;
        }

        LOGGER.info("Showing startup safety warning.");
        boolean confirmed = showMaterialDialog(
                Messages.get("gui.dialog.startupSafetyWarning"),
                Messages.get("gui.dialog.startupSafetyWarning"),
                messageContent(Messages.get("gui.dialog.startupSafetyWarning.message")),
                "gui.dialog.ok",
                "material-warning-dialog",
                false);
        if (!confirmed) {
            return;
        }

        try {
            preferences.writeStartupSafetyWarningAccepted();
            LOGGER.info("Saved GUI startup safety warning acknowledgement.");
        } catch (IOException exception) {
            LOGGER.warn("Failed to write GUI startup safety warning preference.", exception);
            showError(Messages.get("gui.dialog.preferencesWriteFailed"), exception.getMessage());
        }
    }

    /// Creates the main layout.
    ///
    /// @return root layout.
    private BorderPane createRoot() {
        BorderPane pane = new BorderPane();
        pane.getStyleClass().add("app-root");
        pane.setTop(createHeader());
        pane.setCenter(createWorkflowScrollPane());
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

        VBox titleText = new VBox(2, title, subtitle);
        titleText.getStyleClass().add("app-title-text");

        Region titleSpacer = new Region();
        HBox.setHgrow(titleSpacer, Priority.ALWAYS);

        HBox titleRow = new HBox(16, createHeaderLogo(), titleText, titleSpacer, languageSelector);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        repoUpdateButton.setOnAction(_ -> updateRepository());
        repoUpdateButton.getStyleClass().add("header-button");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox status = new HBox(12, statusLabel, progressBar, spacer, repoUpdateButton);
        status.setAlignment(Pos.CENTER_LEFT);
        progressBar.setPrefWidth(180);
        progressBar.setVisible(false);

        VBox header = new VBox(10, titleRow, status);
        header.getStyleClass().add("app-header");
        return header;
    }

    /// Creates the logo node shown beside the application title.
    ///
    /// @return header logo node.
    private static Node createHeaderLogo() {
        @Nullable URL logoResource = MainWindow.class.getResource(HEADER_LOGO_RESOURCE);
        if (logoResource == null) {
            LOGGER.warn("Header logo resource is missing: {}", HEADER_LOGO_RESOURCE);
            return new Region();
        }

        Image image = new Image(logoResource.toExternalForm());
        if (image.isError()) {
            @Nullable Throwable failure = image.getException();
            if (failure == null) {
                LOGGER.warn("Header logo could not be loaded: {}", HEADER_LOGO_RESOURCE);
            } else {
                LOGGER.warn("Header logo could not be loaded: " + HEADER_LOGO_RESOURCE, failure);
            }
            return new Region();
        }

        ImageView logo = new ImageView(image);
        logo.getStyleClass().add("app-logo");
        logo.setFitWidth(HEADER_LOGO_SIZE);
        logo.setFitHeight(HEADER_LOGO_SIZE);
        logo.setPreserveRatio(true);
        logo.setSmooth(true);
        return logo;
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
        cancelFlashButton.setOnAction(_ -> requestFlashCancellation());

        localImageButton.getStyleClass().add("secondary-action-button");
        flashButton.getStyleClass().add("primary-action-button");
        cancelFlashButton.getStyleClass().add("cancel-flash-button");
        targetTitle.getStyleClass().add("step-title");

        HBox writeActions = new HBox(flashButton);
        writeActions.getStyleClass().add("write-actions");

        phaseProgressBox.getStyleClass().add("phase-progress-box");
        phaseProgressBox.setVisible(false);
        phaseProgressBox.setManaged(false);
        phaseProgressBox.setFillWidth(true);
        phaseProgressBox.setMaxWidth(Double.MAX_VALUE);

        Label catalogTitle = localizedLabel("gui.choice.catalog");
        catalogTitle.getStyleClass().add("choice-title");
        catalogTitle.setAlignment(Pos.CENTER);
        catalogTitle.setMaxWidth(Double.MAX_VALUE);

        VBox catalogSteps = new VBox(12,
                createStep("1", "gui.step.manufacturer", manufacturerValue, manufacturerButton),
                createStep("2", "gui.step.board", boardValue, boardButton),
                createStep("3", "gui.step.os", osValue, osButton));

        VBox catalogFlow = new VBox(12,
                catalogTitle,
                catalogSteps);
        catalogFlow.getStyleClass().add("catalog-choice");
        catalogFlow.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(catalogFlow, Priority.ALWAYS);

        Label localTitle = localizedLabel("gui.choice.local");
        localTitle.getStyleClass().add("choice-title");
        localTitle.setAlignment(Pos.CENTER);
        localTitle.setMaxWidth(Double.MAX_VALUE);

        VBox localOption = createLocalImageOption();
        localOption.minHeightProperty().bind(catalogSteps.heightProperty());
        localOption.prefHeightProperty().bind(catalogSteps.heightProperty());

        VBox localFlow = new VBox(12, localTitle, localOption);
        localFlow.setAlignment(Pos.TOP_RIGHT);
        localFlow.setMinWidth(LOCAL_IMAGE_OPTION_WIDTH);
        localFlow.setPrefWidth(LOCAL_IMAGE_OPTION_WIDTH);
        localFlow.setMaxWidth(LOCAL_IMAGE_OPTION_WIDTH);
        localFlow.getStyleClass().add("local-choice");
        HBox.setHgrow(localFlow, Priority.NEVER);

        Label separator = localizedLabel("gui.choice.or");
        separator.getStyleClass().add("choice-separator");
        Region separatorTitleSpacer = new Region();
        separatorTitleSpacer.prefHeightProperty().bind(catalogTitle.heightProperty());
        VBox separatorBox = new VBox(separator);
        separatorBox.setAlignment(Pos.CENTER);
        separatorBox.prefHeightProperty().bind(catalogSteps.heightProperty());
        VBox separatorFlow = new VBox(12, separatorTitleSpacer, separatorBox);
        separatorFlow.setAlignment(Pos.TOP_CENTER);

        HBox sourceChoices = new HBox(16, catalogFlow, separatorFlow, localFlow);
        sourceChoices.setAlignment(Pos.TOP_CENTER);
        sourceChoices.setMaxWidth(Double.MAX_VALUE);
        sourceChoices.getStyleClass().add("source-choices");

        selectionControlsBox.getChildren().setAll(
                sourceChoices,
                createStep("4", targetTitle, storageValue, storageButton),
                writeActions);
        selectionControlsBox.getStyleClass().add("selection-controls-box");
        selectionControlsBox.setFillWidth(true);
        selectionControlsBox.setMaxWidth(Double.MAX_VALUE);

        VBox workflow = new VBox(14,
                selectionControlsBox,
                createActiveFlashBox());
        workflow.getStyleClass().add("workflow");
        return workflow;
    }

    /// Creates the flash-only surface used while a write operation is active.
    ///
    /// @return active flash surface.
    private VBox createActiveFlashBox() {
        activeFlashBox.getStyleClass().add("active-flash-box");
        activeFlashBox.setAlignment(Pos.TOP_CENTER);
        activeFlashBox.setFillWidth(true);
        activeFlashBox.setMaxWidth(Double.MAX_VALUE);
        activeFlashBox.setVisible(false);
        activeFlashBox.setManaged(false);

        GridPane summary = new GridPane();
        summary.getStyleClass().add("active-flash-summary");
        summary.setHgap(14);
        summary.setVgap(8);
        summary.setMaxWidth(Double.MAX_VALUE);
        addActiveFlashSummaryRow(summary, 0, "gui.dialog.confirmFlash.manufacturer", activeManufacturerValue);
        addActiveFlashSummaryRow(summary, 1, "gui.dialog.confirmFlash.board", activeBoardValue);
        addActiveFlashSummaryRow(summary, 2, "gui.dialog.confirmFlash.imageSource", activeImageValue);
        addActiveFlashSummaryRow(summary, 3, "gui.dialog.confirmFlash.target", activeTargetValue);

        HBox actions = new HBox(cancelFlashButton);
        actions.getStyleClass().add("active-flash-actions");
        actions.setAlignment(Pos.CENTER_RIGHT);
        actions.setMaxWidth(Double.MAX_VALUE);

        activeFlashBox.getChildren().setAll(summary, phaseProgressBox, actions);
        return activeFlashBox;
    }

    /// Adds one active-flash summary row.
    ///
    /// @param summary summary grid.
    /// @param row row index.
    /// @param titleKey localized title key.
    /// @param value summary value label.
    private static void addActiveFlashSummaryRow(GridPane summary, int row, String titleKey, Label value) {
        Label title = localizedLabel(titleKey);
        title.getStyleClass().add("active-flash-summary-title");

        value.getStyleClass().add("active-flash-summary-value");
        value.setMinWidth(0);
        value.setWrapText(true);
        value.setMaxWidth(Double.MAX_VALUE);

        summary.add(title, 0, row);
        summary.add(value, 1, row);
        GridPane.setHgrow(value, Priority.ALWAYS);
        GridPane.setFillWidth(value, true);
    }

    /// Creates the independent local-image option outside the catalog selection flow.
    ///
    /// @return custom image option node.
    private VBox createLocalImageOption() {
        Label description = localizedLabel("gui.local.description");
        description.getStyleClass().add("option-value");
        description.setAlignment(Pos.CENTER);
        description.setMaxWidth(Double.MAX_VALUE);
        description.setWrapText(true);

        localImageValue.getStyleClass().add("option-value");
        localImageValue.setAlignment(Pos.CENTER);
        localImageValue.setMaxWidth(Double.MAX_VALUE);
        localImageValue.setWrapText(true);

        VBox text = new VBox(4, description, localImageValue);
        text.setAlignment(Pos.CENTER);
        text.setMaxWidth(Double.MAX_VALUE);

        VBox row = new VBox(16, text, localImageButton);
        row.setAlignment(Pos.CENTER);
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
        listView.setEffect(null);
        listView.setMinSize(SELECTION_LIST_WIDTH, SELECTION_LIST_HEIGHT);
        listView.setPrefSize(SELECTION_LIST_WIDTH, SELECTION_LIST_HEIGHT);
        listView.setMaxSize(SELECTION_LIST_WIDTH, SELECTION_LIST_HEIGHT);
        return listView;
    }

    /// Creates a styled selection tree.
    ///
    /// @param <T> item type.
    /// @return selection tree view.
    static <T> TreeView<T> selectionTreeView() {
        TreeView<T> treeView = new TreeView<>();
        treeView.getStyleClass().add("selection-tree-view");
        treeView.setEffect(null);
        treeView.setShowRoot(false);
        treeView.setMinSize(SELECTION_LIST_WIDTH, SELECTION_LIST_HEIGHT);
        treeView.setPrefSize(SELECTION_LIST_WIDTH, SELECTION_LIST_HEIGHT);
        treeView.setMaxSize(SELECTION_LIST_WIDTH, SELECTION_LIST_HEIGHT);
        return treeView;
    }

    /// Wraps a selection list with a search field.
    ///
    /// @param header header text.
    /// @param listView selection list view.
    /// @param items source items.
    /// @param matcher item matcher receiving a normalized query.
    /// @param <T> item type.
    /// @return searchable selection content.
    private static <T> Node searchableSelectionContent(
            String header,
            ListView<T> listView,
            List<T> items,
            BiPredicate<T, String> matcher) {
        TextField searchField = selectionSearchField();

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

        VBox content = new VBox(10, selectionHeaderRow(header, searchField), listView);
        content.setMinWidth(SELECTION_LIST_WIDTH);
        content.setPrefWidth(SELECTION_LIST_WIDTH);
        content.setMaxWidth(SELECTION_LIST_WIDTH);
        content.setPadding(new Insets(0.0, 0.0, SELECTION_CONTENT_BOTTOM_INSET, 0.0));
        content.getStyleClass().add("selection-content");
        VBox.setVgrow(listView, Priority.NEVER);
        return content;
    }

    /// Creates the search field used by selection dialogs.
    ///
    /// @return selection search field.
    private static TextField selectionSearchField() {
        TextField searchField = new TextField();
        searchField.promptTextProperty().bind(Messages.binding("gui.search.placeholder"));
        searchField.setMinWidth(320.0);
        searchField.setPrefWidth(320.0);
        searchField.setMaxWidth(320.0);
        searchField.setMinHeight(36.0);
        searchField.setPrefHeight(36.0);
        searchField.setMaxHeight(36.0);
        searchField.getStyleClass().add("selection-search");
        return searchField;
    }

    /// Creates a header row with the title on the left and search field on the right.
    ///
    /// @param header header text.
    /// @param searchField search field.
    /// @return header row.
    private static HBox selectionHeaderRow(String header, TextField searchField) {
        Label title = new Label(header);
        title.getStyleClass().add("selection-dialog-title");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox row = new HBox(16, title, spacer, searchField);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setMinWidth(SELECTION_LIST_WIDTH);
        row.setPrefWidth(SELECTION_LIST_WIDTH);
        row.setMaxWidth(SELECTION_LIST_WIDTH);
        row.getStyleClass().add("selection-dialog-header-row");
        return row;
    }

    /// Wraps an operating system tree with search.
    ///
    /// @param header header text.
    /// @param treeView operating system tree view.
    /// @param images source image entries.
    /// @param currentImage currently selected image.
    /// @return operating system selection content.
    static Node operatingSystemSelectionContent(
            String header,
            TreeView<OperatingSystemTreeNode> treeView,
            List<ImageEntry> images,
            @Nullable ImageEntry currentImage) {
        TextField searchField = selectionSearchField();

        Runnable refreshTree = () -> {
            String query = normalizeSearchQuery(searchField.getText());
            @Nullable ImageEntry selected = selectedTreeImage(treeView);
            populateOperatingSystemTree(
                    treeView,
                    images,
                    query,
                    selected == null ? currentImage : selected);
        };

        searchField.textProperty().addListener((_, _, _) -> refreshTree.run());
        populateOperatingSystemTree(treeView, images, "", currentImage);

        VBox content = new VBox(10, selectionHeaderRow(header, searchField), treeView);
        content.setMinWidth(SELECTION_LIST_WIDTH);
        content.setPrefWidth(SELECTION_LIST_WIDTH);
        content.setMaxWidth(SELECTION_LIST_WIDTH);
        content.setPadding(new Insets(0.0, 0.0, SELECTION_CONTENT_BOTTOM_INSET, 0.0));
        content.getStyleClass().add("selection-content");
        VBox.setVgrow(treeView, Priority.NEVER);
        return content;
    }

    /// Populates the operating system tree with category and image nodes.
    ///
    /// @param treeView operating system tree view.
    /// @param images source image entries.
    /// @param query normalized search query.
    /// @param selectedImage image to select after rebuilding.
    private static void populateOperatingSystemTree(
            TreeView<OperatingSystemTreeNode> treeView,
            List<ImageEntry> images,
            String query,
            @Nullable ImageEntry selectedImage) {
        TreeItem<OperatingSystemTreeNode> root = new TreeItem<>(
                new OperatingSystemTreeNode("", null, 0));
        root.setExpanded(true);

        @Nullable TreeItem<OperatingSystemTreeNode> selectedItem = null;
        @Unmodifiable List<OperatingSystemCategoryOption> categories = operatingSystemCategoryOptions(images);
        for (OperatingSystemCategoryOption category : categories) {
            boolean categoryMatches = textMatches(category.name(), query)
                    || textMatches(Messages.get("gui.osCategory.item", category.name(), category.imageCount()), query);
            ArrayList<ImageEntry> matchingImages = new ArrayList<>();
            for (ImageEntry image : images) {
                if (operatingSystemCategoryId(image).equals(category.id())
                        && (query.isEmpty() || categoryMatches || imageMatches(image, query))) {
                    matchingImages.add(image);
                }
            }
            if (matchingImages.isEmpty()) {
                continue;
            }

            TreeItem<OperatingSystemTreeNode> categoryItem = new TreeItem<>(
                    new OperatingSystemTreeNode(category.name(), null, matchingImages.size()));
            categoryItem.setExpanded(true);
            for (ImageEntry image : matchingImages) {
                TreeItem<OperatingSystemTreeNode> imageItem = new TreeItem<>(
                        new OperatingSystemTreeNode("", image, 0));
                categoryItem.getChildren().add(imageItem);
                if (selectedImage != null && selectedImage.atom().equals(image.atom())) {
                    selectedItem = imageItem;
                }
            }
            root.getChildren().add(categoryItem);
        }

        treeView.setRoot(root);
        if (selectedItem == null) {
            selectedItem = firstImageTreeItem(root);
        }
        if (selectedItem == null) {
            treeView.getSelectionModel().clearSelection();
        } else {
            treeView.getSelectionModel().select(selectedItem);
        }
    }

    /// Returns the selected image from an operating system tree.
    ///
    /// @param treeView operating system tree view.
    /// @return selected image, first image under a selected category, or null when the tree is empty.
    static @Nullable ImageEntry selectedTreeImage(TreeView<OperatingSystemTreeNode> treeView) {
        @Nullable TreeItem<OperatingSystemTreeNode> selectedItem =
                treeView.getSelectionModel().getSelectedItem();
        if (selectedItem == null) {
            return null;
        }

        @Nullable OperatingSystemTreeNode node = selectedItem.getValue();
        if (node == null) {
            return null;
        }
        @Nullable ImageEntry image = node.image();
        if (image != null) {
            return image;
        }

        @Nullable TreeItem<OperatingSystemTreeNode> firstChild = firstImageTreeItem(selectedItem);
        if (firstChild == null) {
            return null;
        }
        @Nullable OperatingSystemTreeNode firstChildNode = firstChild.getValue();
        return firstChildNode == null ? null : firstChildNode.image();
    }

    /// Finds the first image leaf in an operating system tree.
    ///
    /// @param root root tree item or category tree item.
    /// @return first image tree item, or null when the tree is empty.
    private static @Nullable TreeItem<OperatingSystemTreeNode> firstImageTreeItem(
            TreeItem<OperatingSystemTreeNode> root) {
        @Nullable OperatingSystemTreeNode node = root.getValue();
        if (node != null && node.image() != null) {
            return root;
        }

        for (TreeItem<OperatingSystemTreeNode> child : root.getChildren()) {
            @Nullable TreeItem<OperatingSystemTreeNode> imageItem = firstImageTreeItem(child);
            if (imageItem != null) {
                return imageItem;
            }
        }
        return null;
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
        } catch (IOException exception) {
            LOGGER.debug("Failed to read GUI preferences.", exception);
            // The UI can still run with the default locale.
        }
    }

    /// Persists the selected GUI language.
    ///
    /// @param locale selected locale.
    private void savePreferredLocale(Locale locale) {
        try {
            preferences.writeLocale(locale);
            LOGGER.atInfo().log(() -> "Saved GUI locale preference. locale=" + locale);
        } catch (IOException exception) {
            LOGGER.warn("Failed to write GUI preferences.", exception);
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
                return services.repository().update(LoggingProgressReporter.wrap(event -> {
                    updateMessage(event.message());
                    @Nullable Long currentBytes = event.currentBytes();
                    @Nullable Long totalBytes = event.totalBytes();
                    if (currentBytes != null && totalBytes != null && totalBytes > 0L) {
                        updateProgress(currentBytes, totalBytes);
                    }
                }, LOGGER));
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
        String header = Messages.get("gui.dialog.chooseManufacturer.header");
        Node content = searchableSelectionContent(header, listView, manufacturers, MainWindow::manufacturerMatches);
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

        if (showSearchableSelectionDialog(Messages.get("gui.dialog.chooseManufacturer"), content)) {
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
        String header = Messages.get("gui.dialog.chooseBoard.header", state.manufacturerName());
        Node content = searchableSelectionContent(header, listView, boards, MainWindow::boardMatches);
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

        if (showSearchableSelectionDialog(Messages.get("gui.dialog.chooseBoard"), content)) {
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

        TreeView<OperatingSystemTreeNode> treeView = selectionTreeView();
        @Unmodifiable Map<String, ImageCacheStatus> cacheStatuses = imageCacheStatuses(images);
        String header = Messages.get("gui.dialog.chooseOperatingSystem.header", state.boardName());
        Node content = operatingSystemSelectionContent(header, treeView, images, state.image());
        treeView.setCellFactory(_ -> new TreeCell<>() {
            /// Updates one operating system tree cell.
            ///
            /// @param item tree node.
            /// @param empty whether the cell is empty.
            @Override
            protected void updateItem(@Nullable OperatingSystemTreeNode item, boolean empty) {
                super.updateItem(item, empty);
                clearSelectionTreeCellStyles(this);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }

                @Nullable ImageEntry image = item.image();
                if (image == null) {
                    setText(Messages.get("gui.osCategory.item", item.name(), item.imageCount()));
                    setGraphic(null);
                    getStyleClass().add("os-category-tree-cell");
                } else {
                    setText(null);
                    setGraphic(imageCellContent(image, cacheStatus(cacheStatuses, image)));
                    getStyleClass().add(catalogImageFlashable(image)
                            ? "flashable-image-cell"
                            : "unsupported-image-cell");
                }
            }
        });

        if (showSearchableSelectionDialog(Messages.get("gui.dialog.chooseOperatingSystem"), content)) {
            @Nullable ImageEntry selected = selectedTreeImage(treeView);
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
        String header = Messages.get("gui.dialog.chooseFastbootDevice.header");
        Node content = searchableSelectionContent(header, listView, devices, MainWindow::fastbootTargetMatches);
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

        if (showSearchableSelectionDialog(Messages.get("gui.dialog.chooseFastbootDevice"), content)) {
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

        @Unmodifiable List<BlockDevice> supportedDevices = supportedTargets(devices);
        if (supportedDevices.isEmpty()) {
            showInfo(
                    Messages.get("gui.dialog.noSupportedStorageDevices"),
                    Messages.get("gui.dialog.noSupportedStorageDevices.message"));
            return;
        }

        @Nullable ImageEntry image = state.image();
        if (GuiSelectionRules.requiresPartitionTargets(image)) {
            showPartitionStorageDialog(supportedDevices, image);
            return;
        }

        ListView<BlockDevice> listView = selectionListView();
        String header = Messages.get("gui.dialog.chooseStorageDevice.header");
        Node content = searchableSelectionContent(header, listView, supportedDevices, MainWindow::targetMatches);
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
                getStyleClass().add("safe-target-cell");
            }
        });
        selectCurrentBlockTarget(listView, state.target());

        if (showSearchableSelectionDialog(Messages.get("gui.dialog.chooseStorageDevice"), content)) {
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

    /// Shows the multi-partition storage selection dialog.
    ///
    /// @param devices target devices.
    /// @param image selected image.
    private void showPartitionStorageDialog(List<BlockDevice> devices, ImageEntry image) {
        LinkedHashMap<String, MFXComboBox<BlockDevice>> selectors = new LinkedHashMap<>();
        GridPane grid = new GridPane();
        grid.getStyleClass().add("partition-target-grid");
        grid.setHgap(14);
        grid.setVgap(10);

        @Nullable FlashTarget currentTarget = state.target();
        @Unmodifiable Map<String, BlockDevice> currentTargets =
                currentTarget == null ? Map.of() : currentTarget.blockDevices();

        int row = 0;
        for (String partition : image.partitionMap().keySet()) {
            Label partitionLabel = new Label(partition);
            partitionLabel.getStyleClass().add("partition-target-name");

            MFXComboBox<BlockDevice> selector = new MFXComboBox<>(FXCollections.observableArrayList(devices));
            selector.setAllowEdit(false);
            selector.setRowsCount(Math.min(8, devices.size()));
            selector.setPrefWidth(460);
            selector.getStyleClass().add("partition-target-selector");
            selector.setConverter(new StringConverter<>() {
                /// Converts a block device to selection text.
                ///
                /// @param target target device.
                /// @return display text.
                @Override
                public String toString(@Nullable BlockDevice target) {
                    return target == null ? "" : targetLabel(target);
                }

                /// Converts selection text back to a block device.
                ///
                /// @param text display text.
                /// @return matching device, or null when no device matches.
                @Override
                public @Nullable BlockDevice fromString(@Nullable String text) {
                    if (text == null) {
                        return null;
                    }
                    for (BlockDevice device : devices) {
                        if (targetLabel(device).equals(text)) {
                            return device;
                        }
                    }
                    return null;
                }
            });

            @Nullable BlockDevice current = currentTargets.get(partition);
            if (current != null) {
                @Nullable BlockDevice matching = blockDeviceById(devices, current.id());
                if (matching != null) {
                    selector.setValue(matching);
                }
            }

            selectors.put(partition, selector);
            grid.add(partitionLabel, 0, row);
            grid.add(selector, 1, row);
            row++;
        }

        Label message = new Label(Messages.get("gui.dialog.choosePartitionStorageDevice.message"));
        message.setWrapText(true);
        message.getStyleClass().add("dialog-message");

        VBox content = new VBox(14, message, grid);
        content.getStyleClass().add("partition-target-content");

        if (showSelectionDialog(
                Messages.get("gui.dialog.choosePartitionStorageDevice"),
                Messages.get("gui.dialog.choosePartitionStorageDevice.header"),
                content)) {
            @Nullable FlashTarget selectedTarget = partitionTargetSelection(selectors);
            if (selectedTarget != null) {
                state = new WizardState(
                        state.manufacturerName(),
                        state.boardName(),
                        state.image(),
                        state.localImage(),
                        selectedTarget);
                refreshState();
            }
        }
    }

    /// Builds a flash target from partition selector values.
    ///
    /// @param selectors partition selectors.
    /// @return selected partition flash target, or null when selection is invalid.
    @Nullable FlashTarget partitionTargetSelection(
            @Unmodifiable Map<String, MFXComboBox<BlockDevice>> selectors) {
        LinkedHashMap<String, BlockDevice> targets = new LinkedHashMap<>();
        Set<Path> targetPaths = new HashSet<>();
        for (Map.Entry<String, MFXComboBox<BlockDevice>> entry : selectors.entrySet()) {
            String partition = entry.getKey();
            @Nullable BlockDevice target = entry.getValue().getValue();
            if (target == null) {
                showInfo(
                        Messages.get("gui.dialog.incompleteSelection"),
                        Messages.get("gui.dialog.partitionStorageIncomplete"));
                return null;
            }
            if (!targetWritable(target)) {
                showInfo(
                        Messages.get("gui.dialog.blockedStorage"),
                        Messages.get("gui.dialog.blockedPartitionStorage.message", partition, targetSafetyLabel(target)));
                return null;
            }

            Path targetPath = target.path().toAbsolutePath().normalize();
            if (!targetPaths.add(targetPath)) {
                showInfo(
                        Messages.get("gui.dialog.duplicateStorage"),
                        Messages.get("gui.dialog.duplicateStorage.message", targetPathText(target)));
                return null;
            }
            targets.put(partition, target);
        }
        return FlashTarget.blockDevices(targets);
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
        @Nullable String targetError = targetWriteError(selectedTarget);
        if (targetError != null) {
            showInfo(
                    Messages.get("gui.dialog.blockedStorage"),
                    targetError);
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

        flashInProgress = true;
        long progressGeneration = beginPhaseProgress();
        Task<OperationResult> task = new Task<>() {
            /// Runs the flash operation outside the JavaFX application thread.
            ///
            /// @return flash result.
            @Override
            protected OperationResult call() throws Exception {
                LOGGER.info("Starting GUI flash operation.");
                return services.flash().flash(
                        new FlashRequest(selectedImage, selectedLocalImage, selectedTarget, true),
                        LoggingProgressReporter.wrap(event -> {
                            updateMessage(event.message());
                            Platform.runLater(() -> updatePhaseProgress(progressGeneration, event));
                            @Nullable Long currentBytes = event.currentBytes();
                            @Nullable Long totalBytes = event.totalBytes();
                            if (currentBytes != null && totalBytes != null && totalBytes > 0L) {
                                updateProgress(currentBytes, totalBytes);
                            } else {
                                updateProgress(ProgressBar.INDETERMINATE_PROGRESS, 1.0);
                            }
                        }, LOGGER));
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
        LOGGER.atDebug().log(() -> "Starting GUI background task. failureTitle=" + failureTitle);
        busy = true;
        cancellationRequested = false;
        refreshState();
        progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        progressBar.setVisible(!flashInProgress);
        progressBar.progressProperty().bind(task.progressProperty());
        statusLabel.textProperty().bind(task.messageProperty());

        task.setOnSucceeded(_ -> {
            boolean cancelledFlash = flashInProgress && cancellationRequested;
            finishBackgroundTask();
            if (cancelledFlash) {
                showFlashCancelled();
                return;
            }
            @Nullable T result = task.getValue();
            if (result != null) {
                LOGGER.debug("GUI background task succeeded.");
                onSuccess.accept(result);
            } else {
                LOGGER.warn("GUI background task returned null.");
                showError(failureTitle, Messages.get("gui.dialog.emptyResult"));
            }
        });
        task.setOnFailed(_ -> {
            boolean cancelledFlash = flashInProgress && cancellationRequested;
            finishBackgroundTask();
            Throwable failure = task.getException();
            if (cancelledFlash) {
                LOGGER.info("GUI background task stopped after cancellation request.", failure);
                showFlashCancelled();
                return;
            }
            if (failure == null) {
                LOGGER.error("GUI background task failed without an exception.");
            } else {
                LOGGER.error("GUI background task failed.", failure);
            }
            showError(failureTitle, failure == null ? null : failure.getMessage());
        });

        Thread thread = new Thread(task, "ruyi-imager-background");
        thread.setDaemon(true);
        currentBackgroundThread = thread;
        thread.start();
    }

    /// Requests cancellation for the active flash operation.
    private void requestFlashCancellation() {
        if (!flashInProgress || cancellationRequested) {
            return;
        }
        LOGGER.info("GUI flash cancellation requested.");
        cancellationRequested = true;
        cancelFlashButton.setDisable(true);
        if (statusLabel.textProperty().isBound()) {
            statusLabel.textProperty().unbind();
        }
        statusLabel.setText(Messages.get("gui.status.cancellingFlash"));

        @Nullable Thread thread = currentBackgroundThread;
        if (thread != null) {
            thread.interrupt();
        }
    }

    /// Shows the flash cancellation result dialog.
    private void showFlashCancelled() {
        showInfo(
                Messages.get("gui.dialog.flashCancelled"),
                Messages.get("gui.dialog.flashCancelled.message"));
    }

    /// Clears background task UI bindings.
    private void finishBackgroundTask() {
        statusLabel.textProperty().unbind();
        progressBar.progressProperty().unbind();
        statusLabel.setText(Messages.get("gui.status.ready"));
        progressBar.setProgress(0);
        progressBar.setVisible(false);
        resetPhaseProgress();
        flashInProgress = false;
        cancellationRequested = false;
        currentBackgroundThread = null;
        busy = false;
        refreshState();
    }

    /// Starts a fresh visible phase-progress generation for one flash task.
    ///
    /// @return generation token for the task.
    private long beginPhaseProgress() {
        phaseProgressGeneration++;
        clearPhaseProgressRows();
        return phaseProgressGeneration;
    }

    /// Hides and invalidates phase progress rows.
    private void resetPhaseProgress() {
        phaseProgressGeneration++;
        clearPhaseProgressRows();
    }

    /// Removes all phase progress rows without changing the generation token.
    private void clearPhaseProgressRows() {
        phaseProgressRows.clear();
        phaseProgressBox.getChildren().clear();
        phaseProgressBox.setVisible(false);
        phaseProgressBox.setManaged(false);
    }

    /// Updates one phase progress row from a backend progress event.
    ///
    /// @param generation generation token for the active flash task.
    /// @param event backend progress event.
    private void updatePhaseProgress(long generation, ProgressEvent event) {
        if (generation != phaseProgressGeneration) {
            return;
        }

        phaseProgressBox.setVisible(true);
        phaseProgressBox.setManaged(true);
        PhaseProgressRow row = phaseProgressRows.computeIfAbsent(event.stage(), this::createPhaseProgressRow);
        row.message().setText(event.message());

        @Nullable Long currentBytes = event.currentBytes();
        @Nullable Long totalBytes = event.totalBytes();
        if (currentBytes != null && totalBytes != null && totalBytes > 0L) {
            double progress = progressValue(currentBytes, totalBytes);
            row.progressBar().setProgress(progress);
            row.percent().setText(percentText(progress));
        } else if (row.progressBar().getProgress() <= 0.0) {
            row.progressBar().setProgress(ProgressBar.INDETERMINATE_PROGRESS);
            row.percent().setText("");
        }
    }

    /// Creates a visual row for one progress stage.
    ///
    /// @param stage stable stage identifier.
    /// @return progress row.
    private PhaseProgressRow createPhaseProgressRow(String stage) {
        Label title = new Label();
        @Nullable String titleKey = phaseTitleKey(stage);
        if (titleKey == null) {
            title.setText(stage);
        } else {
            title.textProperty().bind(Messages.binding(titleKey));
        }
        title.getStyleClass().add("phase-progress-title");

        Label message = new Label();
        message.getStyleClass().add("phase-progress-message");
        message.setMaxWidth(Double.MAX_VALUE);
        message.setWrapText(true);
        HBox.setHgrow(message, Priority.ALWAYS);

        Label percent = new Label();
        percent.getStyleClass().add("phase-progress-percent");

        HBox header = new HBox(10, title, message, percent);
        header.getStyleClass().add("phase-progress-header");
        header.setAlignment(Pos.CENTER_LEFT);
        header.setMaxWidth(Double.MAX_VALUE);

        MFXProgressBar rowProgressBar = new MFXProgressBar(ProgressBar.INDETERMINATE_PROGRESS);
        rowProgressBar.getStyleClass().add("phase-progress-bar");
        rowProgressBar.setMaxWidth(Double.MAX_VALUE);

        VBox container = new VBox(6, header, rowProgressBar);
        container.getStyleClass().add("phase-progress-row");
        container.setMaxWidth(Double.MAX_VALUE);
        phaseProgressBox.getChildren().add(container);
        return new PhaseProgressRow(message, percent, rowProgressBar);
    }

    /// Returns a bounded progress value for a byte counter pair.
    ///
    /// @param currentBytes current byte count.
    /// @param totalBytes total byte count.
    /// @return progress value in the JavaFX progress range.
    private static double progressValue(long currentBytes, long totalBytes) {
        return Math.max(0.0, Math.min(1.0, (double) currentBytes / (double) totalBytes));
    }

    /// Formats a compact percentage for a progress value.
    ///
    /// @param progress JavaFX progress value.
    /// @return percentage text.
    private static String percentText(double progress) {
        return String.format(Locale.ROOT, "%.0f%%", progress * 100.0);
    }

    /// Returns the localized title key for a progress stage.
    ///
    /// @param stage stable stage identifier.
    /// @return localized title key, or null for unknown stages.
    private static @Nullable String phaseTitleKey(String stage) {
        return switch (stage) {
            case "repo" -> "gui.progress.stage.repo";
            case "download" -> "gui.progress.stage.download";
            case "materialize" -> "gui.progress.stage.materialize";
            case "prepare" -> "gui.progress.stage.prepare";
            case "flash" -> "gui.progress.stage.flash";
            case "verify" -> "gui.progress.stage.verify";
            case "fastboot" -> "gui.progress.stage.fastboot";
            default -> null;
        };
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
        refreshActiveFlashSummary(target);
        boolean showActiveFlashBox = flashInProgress;
        selectionControlsBox.setVisible(!showActiveFlashBox);
        selectionControlsBox.setManaged(!showActiveFlashBox);
        activeFlashBox.setVisible(showActiveFlashBox);
        activeFlashBox.setManaged(showActiveFlashBox);
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
        cancelFlashButton.setDisable(!flashInProgress || cancellationRequested);
    }

    /// Refreshes the compact selection summary shown while flashing.
    ///
    /// @param target selected target, or null when incomplete.
    private void refreshActiveFlashSummary(@Nullable FlashTarget target) {
        activeManufacturerValue.setText(manufacturerLabel());
        activeBoardValue.setText(boardLabel());
        activeImageValue.setText(imageSourceLabel());
        activeTargetValue.setText(target == null ? targetNoneLabel() : targetLabel(target));
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

        if (GuiSelectionRules.requiresPartitionTargets(image)) {
            return partitionTargetsReady(image, target);
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
        return image != null && ProvisionStrategies.isFastboot(image.strategy());
    }

    /// Returns whether the selected catalog image requires partition-specific block targets.
    ///
    /// @return whether target selection should use multiple storage devices.
    private boolean requiresPartitionTargets() {
        return GuiSelectionRules.requiresPartitionTargets(state.image());
    }

    /// Returns the localized empty target label for the current strategy.
    ///
    /// @return empty target label.
    private String targetNoneLabel() {
        if (requiresFastbootTarget()) {
            return Messages.get("gui.value.fastboot.none");
        }
        if (requiresPartitionTargets()) {
            return Messages.get("gui.value.partitionStorage.none");
        }
        return Messages.get("gui.value.storage.none");
    }

    /// Returns a writable target error for a flash target.
    ///
    /// @param target selected target.
    /// @return localized error text, or null when the target is writable.
    private static @Nullable String targetWriteError(FlashTarget target) {
        @Nullable BlockDevice blockDevice = target.blockDevice();
        if (blockDevice != null && !targetWritable(blockDevice)) {
            return Messages.get("gui.dialog.blockedStorage.message", targetSafetyLabel(blockDevice));
        }

        for (Map.Entry<String, BlockDevice> entry : target.blockDevices().entrySet()) {
            if (!targetWritable(entry.getValue())) {
                return Messages.get(
                        "gui.dialog.blockedPartitionStorage.message",
                        entry.getKey(),
                        targetSafetyLabel(entry.getValue()));
            }
        }
        return null;
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

    /// Builds operating system categories from image metadata.
    ///
    /// @param images available images.
    /// @return operating system categories sorted by name.
    private static @Unmodifiable List<OperatingSystemCategoryOption> operatingSystemCategoryOptions(
            List<ImageEntry> images) {
        LinkedHashMap<String, String> names = new LinkedHashMap<>();
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
        for (ImageEntry image : images) {
            String name = operatingSystemCategoryName(image);
            String id = operatingSystemCategoryId(name);
            names.putIfAbsent(id, name);
            counts.merge(id, 1, Integer::sum);
        }

        ArrayList<OperatingSystemCategoryOption> categories = new ArrayList<>(names.size());
        for (Map.Entry<String, String> entry : names.entrySet()) {
            @Nullable Integer count = counts.get(entry.getKey());
            categories.add(new OperatingSystemCategoryOption(
                    entry.getKey(),
                    entry.getValue(),
                    count == null ? 0 : count));
        }
        categories.sort(Comparator.comparing(OperatingSystemCategoryOption::name));
        return List.copyOf(categories);
    }

    /// Derives an operating system category id for one image.
    ///
    /// @param image image entry.
    /// @return stable category id.
    private static String operatingSystemCategoryId(ImageEntry image) {
        return operatingSystemCategoryId(operatingSystemCategoryName(image));
    }

    /// Normalizes an operating system category name to a stable id.
    ///
    /// @param name category display name.
    /// @return category id.
    private static String operatingSystemCategoryId(String name) {
        return normalizeCategorySearchText(name).trim().replace(' ', '-');
    }

    /// Derives an operating system category name for one image.
    ///
    /// @param image image entry.
    /// @return category display name.
    private static String operatingSystemCategoryName(ImageEntry image) {
        String text = categorySourceText(image);
        for (OperatingSystemCategoryAlias alias : OPERATING_SYSTEM_CATEGORY_ALIASES) {
            if (text.contains(" " + normalizeCategorySearchText(alias.matchText()).trim() + " ")) {
                return alias.name();
            }
        }

        return fallbackOperatingSystemCategoryName(image.name());
    }

    /// Builds normalized text used by operating system category matching.
    ///
    /// @param image image entry.
    /// @return normalized source text with word boundaries.
    private static String categorySourceText(ImageEntry image) {
        StringBuilder builder = new StringBuilder();
        appendCategorySource(builder, image.name());
        appendCategorySource(builder, image.slug());
        appendCategorySource(builder, image.displayName());
        appendCategorySource(builder, image.atom());
        return normalizeCategorySearchText(builder.toString());
    }

    /// Appends one value to category matching source text.
    ///
    /// @param builder source text builder.
    /// @param value value to append.
    private static void appendCategorySource(StringBuilder builder, @Nullable String value) {
        if (value != null && !value.isBlank()) {
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(value);
        }
    }

    /// Normalizes arbitrary text for category matching.
    ///
    /// @param text raw text.
    /// @return normalized text padded with spaces.
    private static String normalizeCategorySearchText(String text) {
        StringBuilder builder = new StringBuilder(text.length() + 2);
        builder.append(' ');
        boolean previousSpace = true;
        for (int i = 0; i < text.length(); i++) {
            char ch = Character.toLowerCase(text.charAt(i));
            if (Character.isLetterOrDigit(ch)) {
                builder.append(ch);
                previousSpace = false;
            } else if (!previousSpace) {
                builder.append(' ');
                previousSpace = true;
            }
        }
        if (!previousSpace) {
            builder.append(' ');
        }
        return builder.toString();
    }

    /// Falls back to the first package-name segment as the category.
    ///
    /// @param name package name.
    /// @return category display name.
    private static String fallbackOperatingSystemCategoryName(String name) {
        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            return Messages.get("gui.osCategory.other");
        }

        int delimiterIndex = trimmed.indexOf('-');
        String token = delimiterIndex <= 0 ? trimmed : trimmed.substring(0, delimiterIndex);
        token = token.strip();
        if (token.isEmpty()) {
            return Messages.get("gui.osCategory.other");
        }
        return titleCaseCategoryToken(token);
    }

    /// Converts an unknown package-name token into a readable category label.
    ///
    /// @param token package-name token.
    /// @return display label.
    private static String titleCaseCategoryToken(String token) {
        String lower = token.toLowerCase(Locale.ROOT);
        if (lower.length() <= 1) {
            return lower.toUpperCase(Locale.ROOT);
        }
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
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

    /// Finds one block device by id in a device list.
    ///
    /// @param devices device list.
    /// @param id target device id.
    /// @return matching block device, or null when not found.
    private static @Nullable BlockDevice blockDeviceById(List<BlockDevice> devices, String id) {
        for (BlockDevice device : devices) {
            if (device.id().equals(id)) {
                return device;
            }
        }
        return null;
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
                || textMatches(operatingSystemCategoryName(image), query)
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

    /// Clears state-dependent tree cell styles.
    ///
    /// @param cell tree cell to reset.
    private static void clearSelectionTreeCellStyles(TreeCell<?> cell) {
        cell.getStyleClass().removeAll(
                "os-category-tree-cell",
                "flashable-image-cell",
                "unsupported-image-cell");
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
        content.setMouseTransparent(true);
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

        Label details = new Label(targetDetailsLabel(target));
        details.setWrapText(true);
        details.getStyleClass().add("selection-detail");

        boolean writable = targetWritable(target);
        String safetyText = targetSafetyLabel(target);
        String statusText = safetyText.isEmpty() ? Messages.get("gui.target.ready") : safetyText;
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

    /// Formats the current flash support state for an image.
    ///
    /// @param image image entry.
    /// @return localized support label.
    private static String imageSupportLabel(ImageEntry image) {
        if (catalogImageFlashable(image)) {
            if (ProvisionStrategies.isFastboot(image.strategy())) {
                return Messages.get("gui.image.support.fastbootFlashable");
            }
            if (image.partitionMap().size() > 1) {
                return Messages.get("gui.image.support.partitionFlashable");
            }
            return Messages.get("gui.image.support.flashable");
        }
        if (image.support() == StrategySupport.UNKNOWN) {
            return Messages.get("gui.image.support.unknown");
        }
        if (image.support() == StrategySupport.UNSUPPORTED) {
            return Messages.get("gui.image.support.unsupported");
        }
        if (ProvisionStrategies.isFastboot(image.strategy())) {
            return Messages.get("gui.image.support.noPartitions");
        }
        if (!ProvisionStrategies.isDD(image.strategy())) {
            return Messages.get("gui.image.support.writerUnsupported");
        }
        return Messages.get("gui.image.support.noPartitions");
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

        if (!target.blockDevices().isEmpty()) {
            return partitionTargetLabel(target.blockDevices());
        }

        @Nullable FastbootDevice fastbootDevice = target.fastbootDevice();
        if (fastbootDevice != null) {
            return fastbootTargetLabel(fastbootDevice);
        }

        return "";
    }

    /// Formats partition target mappings for summary display.
    ///
    /// @param targets partition targets.
    /// @return display text.
    private static String partitionTargetLabel(@Unmodifiable Map<String, BlockDevice> targets) {
        ArrayList<String> entries = new ArrayList<>(targets.size());
        for (Map.Entry<String, BlockDevice> entry : targets.entrySet()) {
            entries.add(Messages.get("gui.target.partitionEntry", entry.getKey(), targetLabel(entry.getValue())));
        }
        return String.join(System.lineSeparator(), entries);
    }

    /// Formats a block target for list and summary display.
    ///
    /// @param target target device.
    /// @return display text.
    private static String targetLabel(BlockDevice target) {
        String safety = targetSafetyLabel(target);
        String status = safety.isEmpty() ? Messages.get("gui.target.ready") : safety;
        if (target.mounted() && !target.mountPoints().isEmpty()) {
            return Messages.get(
                    "gui.target.summaryMounted",
                    target.displayName(),
                    targetPathText(target),
                    targetSizeLabel(target),
                    mountPointsLabel(target),
                    status);
        }

        return Messages.get(
                "gui.target.summary",
                target.displayName(),
                targetPathText(target),
                targetSizeLabel(target),
                status);
    }

    /// Formats a fastboot target for list and summary display.
    ///
    /// @param target target device.
    /// @return display text.
    private static String fastbootTargetLabel(FastbootDevice target) {
        return Messages.get("gui.fastboot.summary", target.serial(), target.state());
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

    /// Formats target details for list display.
    ///
    /// @param target target device.
    /// @return target details.
    private static String targetDetailsLabel(BlockDevice target) {
        if (target.mounted() && !target.mountPoints().isEmpty()) {
            return Messages.get(
                    "gui.target.detailsMounted",
                    targetPathText(target),
                    targetSizeLabel(target),
                    mountPointsLabel(target));
        }
        return Messages.get("gui.target.details", targetPathText(target), targetSizeLabel(target));
    }

    /// Formats known mount points for one target.
    ///
    /// @param target target device.
    /// @return mount point label.
    private static String mountPointsLabel(BlockDevice target) {
        return String.join(", ", target.mountPoints());
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
            if (target.mountPoints().isEmpty()) {
                flags.add(Messages.get(targetPreparablyMounted(target)
                        ? "gui.target.mountedWillDismount"
                        : "gui.target.mounted"));
            } else {
                flags.add(Messages.get(targetPreparablyMounted(target)
                        ? "gui.target.mountedWithPointsWillDismount"
                        : "gui.target.mountedWithPoints", mountPointsLabel(target)));
            }
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
        if (targetWritable(target)) {
            return builder.toString();
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
        String text = message == null || message.isBlank() ? Messages.get("gui.dialog.unknownFailure") : message;
        @Nullable String logFile = RuyiLogging.currentLogFileText();
        if (logFile != null) {
            text = text + System.lineSeparator() + System.lineSeparator() + Messages.get("gui.dialog.logFile", logFile);
        }
        showMessageDialog(
                title,
                text,
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

    /// Shows a MaterialFX selection dialog whose header is rendered inside the content area.
    ///
    /// @param title dialog title.
    /// @param content dialog content.
    /// @return whether the user accepted the dialog.
    private boolean showSearchableSelectionDialog(String title, Node content) {
        return showConfirmationDialog(title, "", content, "gui.dialog.select", "material-search-selection-dialog");
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

    /// Operating system category alias used for image metadata inference.
    ///
    /// @param matchText token recognized from image metadata.
    /// @param name category display name.
    @NotNullByDefault
    private record OperatingSystemCategoryAlias(String matchText, String name) {
    }

    /// Operating system category option shown in the image picker.
    ///
    /// @param id stable category id.
    /// @param name category display name.
    /// @param imageCount number of images in the category.
    @NotNullByDefault
    private record OperatingSystemCategoryOption(String id, String name, int imageCount) {
    }

    /// JavaFX controls for one flash phase progress row.
    ///
    /// @param message latest stage message label.
    /// @param percent compact percentage label.
    /// @param progressBar stage progress bar.
    @NotNullByDefault
    private record PhaseProgressRow(
            Label message,
            Label percent,
            MFXProgressBar progressBar) {
    }

    /// Operating system tree node shown in the image picker.
    ///
    /// @param name category display name for category nodes.
    /// @param image image entry for leaf nodes.
    /// @param imageCount visible image count for category nodes.
    @NotNullByDefault
    record OperatingSystemTreeNode(String name, @Nullable ImageEntry image, int imageCount) {
    }
}

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.gui;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Separator;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Window;
import io.github.palexdev.materialfx.controls.MFXButton;
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
import org.glavo.ruyi.imager.core.device.BlockDevice;
import org.glavo.ruyi.imager.core.flash.FlashRequest;
import org.glavo.ruyi.imager.core.image.ImageCatalog;
import org.glavo.ruyi.imager.core.image.ImageEntry;
import org.glavo.ruyi.imager.i18n.Messages;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/// Main JavaFX window for the guided imager workflow.
@NotNullByDefault
public final class MainWindow {
    /// Core services shared with the CLI.
    private final AppServices services;

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

    /// Manufacturer selection button.
    private final Button manufacturerButton = localizedButton("gui.button.chooseManufacturer");

    /// Board selection button.
    private final Button boardButton = localizedButton("gui.button.chooseBoard");

    /// Operating system selection button.
    private final Button osButton = localizedButton("gui.button.chooseOs");

    /// Local image selection button.
    private final Button localImageButton = localizedButton("gui.button.useLocalImage");

    /// Storage selection button.
    private final Button storageButton = localizedButton("gui.button.chooseStorage");

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

        repoUpdateButton.setOnAction(_ -> updateRepository());
        repoUpdateButton.getStyleClass().add("header-button");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox status = new HBox(12, statusLabel, progressBar, spacer, repoUpdateButton);
        status.setAlignment(Pos.CENTER_LEFT);
        progressBar.setPrefWidth(180);
        progressBar.setVisible(false);

        VBox header = new VBox(8, title, subtitle, status);
        header.getStyleClass().add("app-header");
        return header;
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

        osButton.getStyleClass().add("step-button");
        localImageButton.getStyleClass().add("secondary-action-button");
        flashButton.getStyleClass().add("primary-action-button");

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
        localFlow.getStyleClass().add("local-choice");
        HBox.setHgrow(localFlow, Priority.ALWAYS);

        Label separator = localizedLabel("gui.choice.or");
        separator.getStyleClass().add("choice-separator");

        HBox sourceChoices = new HBox(16, catalogFlow, separator, localFlow);
        sourceChoices.setAlignment(Pos.TOP_CENTER);
        sourceChoices.getStyleClass().add("source-choices");

        VBox workflow = new VBox(14,
                sourceChoices,
                createStep("4", "gui.step.storage", storageValue, storageButton),
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
        Label badge = new Label(number);
        badge.getStyleClass().add("step-badge");

        Label titleLabel = localizedLabel(titleKey);
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
        listView.setPrefSize(640, 360);
        listView.setMaxHeight(420);
        return listView;
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
        listView.getItems().setAll(manufacturers);
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
                listView)) {
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
        listView.getItems().setAll(boards);
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
                listView)) {
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
        listView.getItems().setAll(images);
        listView.setCellFactory(_ -> new MFXLegacyListCell<>() {
            /// Updates one image list cell.
            ///
            /// @param item image item.
            /// @param empty whether the cell is empty.
            @Override
            protected void updateItem(@Nullable ImageEntry item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : imageLabel(item));
            }
        });
        selectCurrentImage(listView, state.image());

        if (showSelectionDialog(
                Messages.get("gui.dialog.chooseOperatingSystem"),
                Messages.get("gui.dialog.chooseOperatingSystem.header", state.boardName()),
                listView)) {
            ImageEntry selected = listView.getSelectionModel().getSelectedItem();
            if (selected != null) {
                state = new WizardState(selected.manufacturer(), selected.board(), selected, null, state.target());
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

        state = new WizardState(null, null, null, selected.toPath(), state.target());
        refreshState();
    }

    /// Opens the storage selection dialog.
    private void chooseStorage() {
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

    /// Shows the storage selection dialog.
    ///
    /// @param devices target devices.
    private void showStorageDialog(List<BlockDevice> devices) {
        if (devices.isEmpty()) {
            showInfo(Messages.get("gui.dialog.noStorageDevices"), Messages.get("gui.dialog.noStorageDevices.message"));
            return;
        }

        ListView<BlockDevice> listView = selectionListView();
        listView.getItems().setAll(devices);
        listView.setCellFactory(_ -> new MFXLegacyListCell<>() {
            /// Updates one target list cell.
            ///
            /// @param item target device.
            /// @param empty whether the cell is empty.
            @Override
            protected void updateItem(@Nullable BlockDevice item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : targetLabel(item));
            }
        });
        selectCurrentTarget(listView, state.target());

        if (showSelectionDialog(
                Messages.get("gui.dialog.chooseStorageDevice"),
                Messages.get("gui.dialog.chooseStorageDevice.header"),
                listView)) {
            BlockDevice selected = listView.getSelectionModel().getSelectedItem();
            if (selected != null) {
                state = new WizardState(
                        state.manufacturerName(),
                        state.boardName(),
                        state.image(),
                        state.localImage(),
                        selected);
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
        BlockDevice selectedTarget = state.target();
        if ((selectedImage == null) == (selectedLocalImage == null) || selectedTarget == null) {
            return;
        }

        String confirmContent = Messages.get(
                "gui.dialog.confirmFlash.content",
                manufacturerLabel(),
                boardLabel(),
                imageSourceLabel(),
                targetLabel(selectedTarget));
        if (!showConfirmationDialog(
                Messages.get("gui.dialog.confirmFlash"),
                Messages.get("gui.dialog.confirmFlash.header"),
                confirmContent,
                "gui.button.flash")) {
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
        BlockDevice target = state.target();
        storageValue.setText(target == null ? Messages.get("gui.value.storage.none") : targetLabel(target));
        repoUpdateButton.setDisable(busy);
        manufacturerButton.setDisable(busy);
        boardButton.setDisable(busy || state.localImage() != null);
        osButton.setDisable(busy || state.localImage() != null);
        localImageButton.setDisable(busy);
        storageButton.setDisable(busy);
        flashButton.setDisable(busy || !hasImageSource() || state.target() == null);
    }

    /// Returns whether exactly one image source is selected.
    ///
    /// @return whether an image source is selected.
    private boolean hasImageSource() {
        return (state.image() == null) != (state.localImage() == null);
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
        return image == null ? Messages.get("gui.value.os.none") : imageLabel(image);
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
            return imageLabel(image);
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

    /// Selects the current target when the dialog opens.
    ///
    /// @param listView target list view.
    /// @param target selected target.
    private static void selectCurrentTarget(ListView<BlockDevice> listView, @Nullable BlockDevice target) {
        if (target == null) {
            listView.getSelectionModel().selectFirst();
            return;
        }

        for (int i = 0; i < listView.getItems().size(); i++) {
            if (listView.getItems().get(i).id().equals(target.id())) {
                listView.getSelectionModel().select(i);
                return;
            }
        }
        listView.getSelectionModel().selectFirst();
    }

    /// Formats an image for list and summary display.
    ///
    /// @param image image entry.
    /// @return display text.
    private static String imageLabel(ImageEntry image) {
        return image.displayName()
                + " - "
                + image.variant()
                + " - "
                + image.strategy()
                + " - "
                + image.support();
    }

    /// Formats a target for list and summary display.
    ///
    /// @param target target device.
    /// @return display text.
    private static String targetLabel(BlockDevice target) {
        String safety = targetSafetyLabel(target);
        return target.displayName() + " - " + targetPathText(target) + (safety.isEmpty() ? "" : " - " + safety);
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

    /// Holds the current guided workflow selections.
    ///
    /// @param manufacturerName selected manufacturer name.
    /// @param boardName selected board name.
    /// @param image selected operating system image.
    /// @param localImage selected local image file.
    /// @param target selected storage device.
    @NotNullByDefault
    private record WizardState(
            @Nullable String manufacturerName,
            @Nullable String boardName,
            @Nullable ImageEntry image,
            @Nullable Path localImage,
            @Nullable BlockDevice target) {
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

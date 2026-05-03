// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.gui;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Separator;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.glavo.ruyi.imager.core.AppServices;
import org.glavo.ruyi.imager.core.OperationResult;
import org.glavo.ruyi.imager.core.device.BlockDevice;
import org.glavo.ruyi.imager.core.flash.FlashRequest;
import org.glavo.ruyi.imager.core.image.ImageCatalog;
import org.glavo.ruyi.imager.core.image.ImageEntry;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final Label statusLabel = new Label("Ready");

    /// Progress bar shown for background work.
    private final ProgressBar progressBar = new ProgressBar(0);

    /// Manufacturer selection summary.
    private final Label manufacturerValue = new Label("No manufacturer selected");

    /// Board selection summary.
    private final Label boardValue = new Label("No board selected");

    /// Operating system selection summary.
    private final Label osValue = new Label("No operating system selected");

    /// Storage selection summary.
    private final Label storageValue = new Label("No storage device selected");

    /// Manufacturer selection button.
    private final Button manufacturerButton = new Button("Choose Manufacturer");

    /// Board selection button.
    private final Button boardButton = new Button("Choose Board");

    /// Operating system selection button.
    private final Button osButton = new Button("Choose OS");

    /// Local image selection button.
    private final Button localImageButton = new Button("Choose Local Image");

    /// Storage selection button.
    private final Button storageButton = new Button("Choose Storage");

    /// Repository metadata update button.
    private final Button repoUpdateButton = new Button("Update Metadata");

    /// Flash action button.
    private final Button flashButton = new Button("Flash");

    /// Whether a background operation is active.
    private boolean busy;

    /// Creates the main window.
    ///
    /// @param services shared application services.
    public MainWindow(AppServices services) {
        this.services = services;
        this.root = createRoot();
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
        pane.setCenter(createWorkflow());
        pane.setBottom(createFooter());
        return pane;
    }

    /// Creates the window header.
    ///
    /// @return header node.
    private VBox createHeader() {
        Label title = new Label("Ruyi Imager");
        title.getStyleClass().add("app-title");

        Label subtitle = new Label("Select a manufacturer, board, operating system, and storage device.");
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
        localImageButton.getStyleClass().add("step-button");
        flashButton.getStyleClass().add("primary-action-button");

        HBox osActions = new HBox(8, osButton, localImageButton);
        osActions.getStyleClass().add("step-actions");

        HBox writeActions = new HBox(flashButton);
        writeActions.getStyleClass().add("write-actions");

        VBox workflow = new VBox(14,
                createStep("1", "Manufacturer", manufacturerValue, manufacturerButton),
                createStep("2", "Board", boardValue, boardButton),
                createStep("3", "Operating System", osValue, osActions),
                createStep("4", "Storage Device", storageValue, storageButton),
                writeActions);
        workflow.getStyleClass().add("workflow");
        return workflow;
    }

    /// Creates one workflow row.
    ///
    /// @param number step number.
    /// @param title step title.
    /// @param value current step value.
    /// @param action action button.
    /// @return workflow row.
    private HBox createStep(String number, String title, Label value, Node action) {
        Label badge = new Label(number);
        badge.getStyleClass().add("step-badge");

        Label titleLabel = new Label(title);
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

    /// Starts repository metadata update.
    private void updateRepository() {
        Task<OperationResult> task = new Task<>() {
            /// Updates local repository metadata outside the JavaFX application thread.
            ///
            /// @return update result.
            @Override
            protected OperationResult call() throws Exception {
                updateMessage("Updating metadata.");
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

        startBackgroundTask(task, "Metadata Update Failed", result -> {
            if (result.success()) {
                state = new WizardState(null, null, null, null, state.target());
                refreshState();
                showInfo("Metadata Updated", result.message());
            } else {
                showError("Metadata Update Failed", result.message());
            }
        });
    }

    /// Creates the footer node.
    ///
    /// @return footer node.
    private VBox createFooter() {
        Label safety = new Label("System, mounted, and read-only targets will be rejected by the platform backend.");
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
                updateMessage("Loading image catalog.");
                return services.images().listImages();
            }
        };

        startBackgroundTask(task, "Image Error", this::showManufacturerDialog);
    }

    /// Shows the manufacturer selection dialog.
    ///
    /// @param catalog image catalog.
    private void showManufacturerDialog(ImageCatalog catalog) {
        @Unmodifiable List<ManufacturerOption> manufacturers = manufacturerOptions(catalog.images());
        if (manufacturers.isEmpty()) {
            showInfo("No Manufacturers", "No manufacturers are available in the local metadata cache.");
            return;
        }

        ListView<ManufacturerOption> listView = new ListView<>();
        listView.getItems().setAll(manufacturers);
        listView.setCellFactory(_ -> new ListCell<>() {
            /// Updates one manufacturer list cell.
            ///
            /// @param item manufacturer option.
            /// @param empty whether the cell is empty.
            @Override
            protected void updateItem(@Nullable ManufacturerOption item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null
                        : item.name() + " - " + item.boardCount() + " boards, " + item.imageCount() + " images");
            }
        });
        selectCurrentManufacturer(listView, state.manufacturerName());

        Alert dialog = new Alert(Alert.AlertType.CONFIRMATION);
        dialog.setTitle("Choose Manufacturer");
        dialog.setHeaderText("Select a manufacturer");
        dialog.getDialogPane().setContent(listView);
        dialog.showAndWait();
        if (dialog.getResult() == ButtonType.OK) {
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
            showInfo("Incomplete Selection", "Select a manufacturer first.");
            return;
        }

        Task<ImageCatalog> task = new Task<>() {
            /// Loads the image catalog outside the JavaFX application thread.
            ///
            /// @return image catalog.
            @Override
            protected ImageCatalog call() throws Exception {
                updateMessage("Loading image catalog.");
                return services.images().listImages();
            }
        };

        startBackgroundTask(task, "Image Error", this::showBoardDialog);
    }

    /// Shows the board selection dialog.
    ///
    /// @param catalog image catalog.
    private void showBoardDialog(ImageCatalog catalog) {
        @Unmodifiable List<BoardOption> boards = boardOptions(catalog.images(), state.manufacturerName());
        if (boards.isEmpty()) {
            showInfo("No Boards", "No boards are available for " + state.manufacturerName() + ".");
            return;
        }

        ListView<BoardOption> listView = new ListView<>();
        listView.getItems().setAll(boards);
        listView.setCellFactory(_ -> new ListCell<>() {
            /// Updates one board list cell.
            ///
            /// @param item board option.
            /// @param empty whether the cell is empty.
            @Override
            protected void updateItem(@Nullable BoardOption item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.name() + " - " + item.imageCount() + " images");
            }
        });
        selectCurrentBoard(listView, state.boardName());

        Alert dialog = new Alert(Alert.AlertType.CONFIRMATION);
        dialog.setTitle("Choose Board");
        dialog.setHeaderText("Select a board from " + state.manufacturerName());
        dialog.getDialogPane().setContent(listView);
        dialog.showAndWait();
        if (dialog.getResult() == ButtonType.OK) {
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
            showInfo("Incomplete Selection", "Select a manufacturer and board first.");
            return;
        }

        Task<ImageCatalog> task = new Task<>() {
            /// Loads the image catalog outside the JavaFX application thread.
            ///
            /// @return image catalog.
            @Override
            protected ImageCatalog call() throws Exception {
                updateMessage("Loading image catalog.");
                return services.images().listImages();
            }
        };

        startBackgroundTask(task, "Image Error", this::showOperatingSystemDialog);
    }

    /// Shows the operating system selection dialog.
    ///
    /// @param catalog image catalog.
    private void showOperatingSystemDialog(ImageCatalog catalog) {
        @Unmodifiable List<ImageEntry> images = filteredImages(catalog.images(), state.manufacturerName(), state.boardName());
        if (images.isEmpty()) {
            showInfo("No Operating Systems", imageEmptyMessage());
            return;
        }

        ListView<ImageEntry> listView = new ListView<>();
        listView.getItems().setAll(images);
        listView.setCellFactory(_ -> new ListCell<>() {
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

        Alert dialog = new Alert(Alert.AlertType.CONFIRMATION);
        dialog.setTitle("Choose Operating System");
        dialog.setHeaderText("Select an operating system for " + state.boardName());
        dialog.getDialogPane().setContent(listView);
        dialog.showAndWait();
        if (dialog.getResult() == ButtonType.OK) {
            ImageEntry selected = listView.getSelectionModel().getSelectedItem();
            if (selected != null) {
                state = new WizardState(selected.manufacturer(), selected.board(), selected, null, state.target());
                refreshState();
            }
        }
    }

    /// Opens a local image file selection dialog.
    private void chooseLocalImage() {
        if (state.manufacturerName() == null || state.boardName() == null) {
            showInfo("Incomplete Selection", "Select a manufacturer and board first.");
            return;
        }

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Choose Local Image");
        chooser.getExtensionFilters().setAll(
                new FileChooser.ExtensionFilter("Image files", "*.img", "*.raw", "*.bin", "*.iso"),
                new FileChooser.ExtensionFilter("All files", "*.*"));

        @Nullable Path currentLocalImage = state.localImage();
        if (currentLocalImage != null) {
            @Nullable Path parent = currentLocalImage.getParent();
            if (parent != null && Files.isDirectory(parent)) {
                chooser.setInitialDirectory(parent.toFile());
            }
        }

        @Nullable Window owner = root.getScene() == null ? null : root.getScene().getWindow();
        @Nullable File selected = chooser.showOpenDialog(owner);
        if (selected == null) {
            return;
        }

        state = new WizardState(state.manufacturerName(), state.boardName(), null, selected.toPath(), state.target());
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
                updateMessage("Detecting target devices.");
                return services.devices().listDevices();
            }
        };

        startBackgroundTask(task, "Device Error", this::showStorageDialog);
    }

    /// Shows the storage selection dialog.
    ///
    /// @param devices target devices.
    private void showStorageDialog(List<BlockDevice> devices) {
        if (devices.isEmpty()) {
            showInfo("No Storage Devices", "No storage devices were detected.");
            return;
        }

        ListView<BlockDevice> listView = new ListView<>();
        listView.getItems().setAll(devices);
        listView.setCellFactory(_ -> new ListCell<>() {
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

        Alert dialog = new Alert(Alert.AlertType.CONFIRMATION);
        dialog.setTitle("Choose Storage Device");
        dialog.setHeaderText("Select a storage device");
        dialog.getDialogPane().setContent(listView);
        dialog.showAndWait();
        if (dialog.getResult() == ButtonType.OK) {
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
            showInfo("Incomplete Selection", "Select an operating system and a storage device first.");
            return;
        }

        @Nullable ImageEntry selectedImage = state.image();
        @Nullable Path selectedLocalImage = state.localImage();
        BlockDevice selectedTarget = state.target();
        if ((selectedImage == null) == (selectedLocalImage == null) || selectedTarget == null) {
            return;
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Confirm Flash");
        confirm.setHeaderText("Write image to target device?");
        confirm.setContentText("Manufacturer: " + manufacturerLabel()
                + "\nBoard: " + boardLabel()
                + "\nOperating system: " + osSourceLabel()
                + "\nStorage: " + targetLabel(selectedTarget)
                + "\nThis will overwrite the selected storage device.");
        confirm.showAndWait();
        if (confirm.getResult() != ButtonType.OK) {
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

        startBackgroundTask(task, "Flash Failed", result -> {
            if (result.success()) {
                showInfo("Complete", result.message());
            } else {
                showError("Flash Failed", result.message());
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
                showError(failureTitle, "Operation completed without a result.");
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
        statusLabel.setText("Ready");
        progressBar.setProgress(0);
        progressBar.setVisible(false);
        busy = false;
        refreshState();
    }

    /// Refreshes labels and enabled states from the current selections.
    private void refreshState() {
        manufacturerValue.setText(manufacturerLabel());
        boardValue.setText(boardLabel());
        osValue.setText(hasImageSource() ? osSourceLabel() : "No operating system selected");
        BlockDevice target = state.target();
        storageValue.setText(target == null ? "No storage device selected" : targetLabel(target));
        repoUpdateButton.setDisable(busy);
        manufacturerButton.setDisable(busy);
        boardButton.setDisable(busy);
        osButton.setDisable(busy);
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
        return state.manufacturerName() == null ? "No manufacturer selected" : state.manufacturerName();
    }

    /// Formats the board step label.
    ///
    /// @return board step label.
    private String boardLabel() {
        return state.boardName() == null ? "No board selected" : state.boardName();
    }

    /// Formats the selected operating system source.
    ///
    /// @return source label.
    private String osSourceLabel() {
        @Nullable ImageEntry image = state.image();
        if (image != null) {
            return imageLabel(image);
        }

        @Nullable Path localImage = state.localImage();
        if (localImage != null) {
            @Nullable Path fileName = localImage.getFileName();
            return "Local image - " + (fileName == null ? localImage : fileName);
        }

        return "No operating system selected";
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
            flags.add("system");
        }
        if (target.mounted()) {
            flags.add("mounted");
        }
        if (target.readOnly()) {
            flags.add("read-only");
        }
        if (flags.isEmpty()) {
            return "";
        }

        StringBuilder builder = new StringBuilder("blocked: ");
        for (int i = 0; i < flags.size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(flags.get(i));
        }
        return builder.toString();
    }

    /// Creates the image-empty message for the current state.
    ///
    /// @return message shown to the user.
    private String imageEmptyMessage() {
        @Nullable String boardName = state.boardName();
        if (boardName == null) {
            return "No operating systems are available in the local metadata cache.";
        }
        return "No operating systems are available for " + boardName + ".";
    }

    /// Shows an informational dialog.
    ///
    /// @param title dialog title.
    /// @param message dialog message.
    private void showInfo(String title, String message) {
        showAlert(Alert.AlertType.INFORMATION, title, message);
    }

    /// Shows an error dialog.
    ///
    /// @param title dialog title.
    /// @param message dialog message.
    private void showError(String title, @Nullable String message) {
        showAlert(Alert.AlertType.ERROR, title, message == null ? "Unknown failure." : message);
    }

    /// Shows an alert on the JavaFX application thread.
    ///
    /// @param type alert type.
    /// @param title dialog title.
    /// @param message dialog message.
    private void showAlert(Alert.AlertType type, String title, String message) {
        Runnable action = () -> {
            Alert alert = new Alert(type);
            alert.setTitle(title);
            alert.setHeaderText(title);
            alert.setContentText(message);
            alert.showAndWait();
        };

        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
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

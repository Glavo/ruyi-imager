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
    private WizardState state = new WizardState(null, null, null, null);

    /// Status text shown in the top bar.
    private final Label statusLabel = new Label("Ready");

    /// Progress bar shown for background work.
    private final ProgressBar progressBar = new ProgressBar(0);

    /// Board selection summary.
    private final Label boardValue = new Label("No board selected");

    /// Image selection summary.
    private final Label imageValue = new Label("No image selected");

    /// Target selection summary.
    private final Label targetValue = new Label("No target selected");

    /// Board selection button.
    private final Button boardButton = new Button("Choose Board");

    /// Image selection button.
    private final Button imageButton = new Button("Choose Image");

    /// Local image selection button.
    private final Button localImageButton = new Button("Choose Local Image");

    /// Target selection button.
    private final Button targetButton = new Button("Choose Target");

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

        Label subtitle = new Label("Select a board image, choose a target device, then flash safely.");
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
        boardButton.setOnAction(_ -> chooseBoard());

        imageButton.setOnAction(_ -> chooseImage());

        localImageButton.setOnAction(_ -> chooseLocalImage());

        targetButton.setOnAction(_ -> chooseTarget());

        flashButton.setOnAction(_ -> flash());

        imageButton.getStyleClass().add("step-button");
        localImageButton.getStyleClass().add("step-button");

        HBox imageActions = new HBox(8, imageButton, localImageButton);
        imageActions.getStyleClass().add("step-actions");

        VBox workflow = new VBox(14,
                createStep("1", "Board", boardValue, boardButton),
                createStep("2", "Image", imageValue, imageActions),
                createStep("3", "Target", targetValue, targetButton),
                createStep("4", "Write", new Label("Review selections before writing"), flashButton));
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

    /// Opens the board selection dialog.
    private void chooseBoard() {
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
        @Unmodifiable List<BoardOption> boards = boardOptions(catalog.images());
        if (boards.isEmpty()) {
            showInfo("No Boards", "No boards are available in the local metadata cache.");
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
        dialog.setHeaderText("Select a target board");
        dialog.getDialogPane().setContent(listView);
        dialog.showAndWait();
        if (dialog.getResult() == ButtonType.OK) {
            BoardOption selected = listView.getSelectionModel().getSelectedItem();
            if (selected != null) {
                @Nullable ImageEntry currentImage = state.image();
                if (currentImage != null && !currentImage.board().equals(selected.name())) {
                    currentImage = null;
                }
                state = new WizardState(selected.name(), currentImage, null, state.target());
                refreshState();
            }
        }
    }

    /// Opens the image selection dialog.
    private void chooseImage() {
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

        startBackgroundTask(task, "Image Error", this::showImageDialog);
    }

    /// Shows the image selection dialog.
    ///
    /// @param catalog image catalog.
    private void showImageDialog(ImageCatalog catalog) {
        @Unmodifiable List<ImageEntry> images = filteredImages(catalog.images(), state.boardName());
        if (images.isEmpty()) {
            showInfo("No Images", imageEmptyMessage());
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
        dialog.setTitle("Choose Image");
        dialog.setHeaderText(state.boardName() == null ? "Select an image" : "Select an image for " + state.boardName());
        dialog.getDialogPane().setContent(listView);
        dialog.showAndWait();
        if (dialog.getResult() == ButtonType.OK) {
            ImageEntry selected = listView.getSelectionModel().getSelectedItem();
            if (selected != null) {
                state = new WizardState(selected.board(), selected, null, state.target());
                refreshState();
            }
        }
    }

    /// Opens a local image file selection dialog.
    private void chooseLocalImage() {
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

        state = new WizardState(null, null, selected.toPath(), state.target());
        refreshState();
    }

    /// Opens the target selection dialog.
    private void chooseTarget() {
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

        startBackgroundTask(task, "Device Error", this::showTargetDialog);
    }

    /// Shows the target selection dialog.
    ///
    /// @param devices target devices.
    private void showTargetDialog(List<BlockDevice> devices) {
        if (devices.isEmpty()) {
            showInfo("No Targets", "No target devices were detected.");
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
        dialog.setTitle("Choose Target");
        dialog.setHeaderText("Select a target device");
        dialog.getDialogPane().setContent(listView);
        dialog.showAndWait();
        if (dialog.getResult() == ButtonType.OK) {
            BlockDevice selected = listView.getSelectionModel().getSelectedItem();
            if (selected != null) {
                state = new WizardState(state.boardName(), state.image(), state.localImage(), selected);
                refreshState();
            }
        }
    }

    /// Starts flashing after final confirmation.
    private void flash() {
        if (!hasImageSource() || state.target() == null) {
            showInfo("Incomplete Selection", "Select an image and a target device first.");
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
        confirm.setContentText("Image: " + imageSourceLabel()
                + "\nTarget: " + targetLabel(selectedTarget)
                + "\nThis will overwrite the selected target device.");
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
        boardValue.setText(boardLabel());
        imageValue.setText(hasImageSource() ? imageSourceLabel() : "No image selected");
        BlockDevice target = state.target();
        targetValue.setText(target == null ? "No target selected" : targetLabel(target));
        repoUpdateButton.setDisable(busy);
        boardButton.setDisable(busy);
        imageButton.setDisable(busy);
        localImageButton.setDisable(busy);
        targetButton.setDisable(busy);
        flashButton.setDisable(busy || !hasImageSource() || state.target() == null);
    }

    /// Returns whether exactly one image source is selected.
    ///
    /// @return whether an image source is selected.
    private boolean hasImageSource() {
        return (state.image() == null) != (state.localImage() == null);
    }

    /// Formats the board step label.
    ///
    /// @return board step label.
    private String boardLabel() {
        if (state.localImage() != null) {
            return "Local image source";
        }
        return state.boardName() == null ? "No board selected" : state.boardName();
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
            return "Local image - " + (fileName == null ? localImage : fileName);
        }

        return "No image selected";
    }

    /// Builds board choices from image metadata.
    ///
    /// @param images available images.
    /// @return board options sorted by board name.
    private static @Unmodifiable List<BoardOption> boardOptions(List<ImageEntry> images) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ImageEntry image : images) {
            counts.merge(image.board(), 1, Integer::sum);
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
    /// @param boardName selected board name.
    /// @return matching image list.
    private static @Unmodifiable List<ImageEntry> filteredImages(List<ImageEntry> images, @Nullable String boardName) {
        if (boardName == null) {
            return List.copyOf(images);
        }

        ArrayList<ImageEntry> filtered = new ArrayList<>();
        for (ImageEntry image : images) {
            if (image.board().equals(boardName)) {
                filtered.add(image);
            }
        }
        return List.copyOf(filtered);
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
            return "No images are available in the local metadata cache.";
        }
        return "No images are available for " + boardName + ".";
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
    /// @param boardName selected board name.
    /// @param image selected image.
    /// @param target selected target device.
    @NotNullByDefault
    private record WizardState(
            @Nullable String boardName,
            @Nullable ImageEntry image,
            @Nullable Path localImage,
            @Nullable BlockDevice target) {
    }

    /// Board option derived from image metadata.
    ///
    /// @param name board name.
    /// @param imageCount number of images available for the board.
    @NotNullByDefault
    private record BoardOption(String name, int imageCount) {
    }
}

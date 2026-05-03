// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.gui;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Separator;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.glavo.ruyi.imager.core.AppServices;
import org.glavo.ruyi.imager.core.OperationResult;
import org.glavo.ruyi.imager.core.device.BlockDevice;
import org.glavo.ruyi.imager.core.flash.FlashRequest;
import org.glavo.ruyi.imager.core.image.ImageCatalog;
import org.glavo.ruyi.imager.core.image.ImageEntry;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.List;

/// Main JavaFX window for the guided imager workflow.
@NotNullByDefault
public final class MainWindow {
    /// Core services shared with the CLI.
    private final AppServices services;

    /// Root node for the window.
    private final BorderPane root;

    /// Current user selections.
    private WizardState state = new WizardState(null, null, null);

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

    /// Flash action button.
    private final Button flashButton = new Button("Flash");

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

        HBox status = new HBox(12, statusLabel, progressBar);
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
        Button boardButton = new Button("Choose Board");
        boardButton.setOnAction(_ -> chooseBoard());

        Button imageButton = new Button("Choose Image");
        imageButton.setOnAction(_ -> chooseImage());

        Button targetButton = new Button("Choose Target");
        targetButton.setOnAction(_ -> chooseTarget());

        flashButton.setOnAction(_ -> flash());

        VBox workflow = new VBox(14,
                createStep("1", "Board", boardValue, boardButton),
                createStep("2", "Image", imageValue, imageButton),
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
    private HBox createStep(String number, String title, Label value, Button action) {
        Label badge = new Label(number);
        badge.getStyleClass().add("step-badge");

        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("step-title");

        value.getStyleClass().add("step-value");
        VBox text = new VBox(4, titleLabel, value);
        HBox.setHgrow(text, Priority.ALWAYS);

        action.getStyleClass().add("step-button");
        HBox row = new HBox(16, badge, text, action);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("step-row");
        return row;
    }

    /// Creates the footer node.
    ///
    /// @return footer node.
    private VBox createFooter() {
        Label safety = new Label("System disks and read-only targets will be rejected by the platform backend.");
        safety.getStyleClass().add("footer-text");
        VBox footer = new VBox(new Separator(), safety);
        footer.getStyleClass().add("app-footer");
        return footer;
    }

    /// Opens the board selection placeholder.
    private void chooseBoard() {
        TextInputDialog dialog = new TextInputDialog(state.boardName());
        dialog.setTitle("Choose Board");
        dialog.setHeaderText("Enter a board name");
        dialog.setContentText("Board");
        dialog.showAndWait();
        @Nullable String board = dialog.getResult();
        if (board != null) {
            String normalized = board.strip();
            if (!normalized.isEmpty()) {
                state = new WizardState(normalized, state.image(), state.target());
                refreshState();
            }
        }
    }

    /// Opens the image selection dialog.
    private void chooseImage() {
        ImageCatalog catalog = services.images().listImages();
        List<ImageEntry> images = catalog.images();
        if (images.isEmpty()) {
            showInfo("No Images", "No images are available in the local metadata cache.");
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
                setText(empty || item == null ? null : item.displayName() + " - " + item.strategy());
            }
        });

        Alert dialog = new Alert(Alert.AlertType.CONFIRMATION);
        dialog.setTitle("Choose Image");
        dialog.setHeaderText("Select an image");
        dialog.getDialogPane().setContent(listView);
        dialog.showAndWait();
        if (dialog.getResult() == ButtonType.OK) {
            ImageEntry selected = listView.getSelectionModel().getSelectedItem();
            if (selected != null) {
                state = new WizardState(state.boardName(), selected, state.target());
                refreshState();
            }
        }
    }

    /// Opens the target selection dialog.
    private void chooseTarget() {
        List<BlockDevice> devices;
        try {
            devices = services.devices().listDevices();
        } catch (IOException e) {
            showError("Device Error", e.getMessage());
            return;
        }

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
                setText(empty || item == null ? null : item.displayName() + " - " + item.path());
            }
        });

        Alert dialog = new Alert(Alert.AlertType.CONFIRMATION);
        dialog.setTitle("Choose Target");
        dialog.setHeaderText("Select a target device");
        dialog.getDialogPane().setContent(listView);
        dialog.showAndWait();
        if (dialog.getResult() == ButtonType.OK) {
            BlockDevice selected = listView.getSelectionModel().getSelectedItem();
            if (selected != null) {
                state = new WizardState(state.boardName(), state.image(), selected);
                refreshState();
            }
        }
    }

    /// Starts flashing after final confirmation.
    private void flash() {
        if (state.image() == null || state.target() == null) {
            showInfo("Incomplete Selection", "Select an image and a target device first.");
            return;
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Confirm Flash");
        confirm.setHeaderText("Write image to target device?");
        confirm.setContentText("This will overwrite the selected target device.");
        confirm.showAndWait();
        if (confirm.getResult() != ButtonType.OK) {
            return;
        }

        ImageEntry selectedImage = state.image();
        BlockDevice selectedTarget = state.target();
        if (selectedImage == null || selectedTarget == null) {
            return;
        }

        Task<OperationResult> task = new Task<>() {
            /// Runs the flash operation outside the JavaFX application thread.
            ///
            /// @return flash result.
            @Override
            protected OperationResult call() throws Exception {
                return services.flash().flash(
                        new FlashRequest(selectedImage, null, selectedTarget, true),
                        event -> updateMessage(event.message()));
            }
        };

        startBackgroundTask(task);
    }

    /// Starts a background task and binds UI state.
    ///
    /// @param task task to run.
    private void startBackgroundTask(Task<OperationResult> task) {
        progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        progressBar.setVisible(true);
        flashButton.setDisable(true);
        statusLabel.textProperty().bind(task.messageProperty());

        task.setOnSucceeded(_ -> {
            finishBackgroundTask();
            OperationResult result = task.getValue();
            if (result.success()) {
                showInfo("Complete", result.message());
            } else {
                showError("Flash Failed", result.message());
            }
        });
        task.setOnFailed(_ -> {
            finishBackgroundTask();
            Throwable failure = task.getException();
            showError("Flash Failed", failure == null ? "Unknown failure." : failure.getMessage());
        });

        Thread thread = new Thread(task, "ruyi-imager-background");
        thread.setDaemon(true);
        thread.start();
    }

    /// Clears background task UI bindings.
    private void finishBackgroundTask() {
        statusLabel.textProperty().unbind();
        statusLabel.setText("Ready");
        progressBar.setVisible(false);
        refreshState();
    }

    /// Refreshes labels and enabled states from the current selections.
    private void refreshState() {
        boardValue.setText(state.boardName() == null ? "No board selected" : state.boardName());
        imageValue.setText(state.image() == null ? "No image selected" : state.image().displayName());
        BlockDevice target = state.target();
        targetValue.setText(target == null ? "No target selected" : target.displayName() + " - " + target.path());
        flashButton.setDisable(state.image() == null || state.target() == null);
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
            @Nullable BlockDevice target) {
    }
}

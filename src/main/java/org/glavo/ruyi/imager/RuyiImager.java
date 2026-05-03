// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.glavo.ruyi.imager.core.AppServices;
import org.glavo.ruyi.imager.gui.MainWindow;
import org.glavo.ruyi.imager.i18n.Messages;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.net.URL;

/// JavaFX application for the Ruyi Imager guided flashing workflow.
@NotNullByDefault
public final class RuyiImager extends Application {
    /// Core services shared by the GUI and CLI.
    private @Nullable AppServices services;

    /// Creates the default service graph before the UI is shown.
    @Override
    public void init() {
        services = AppServices.createDefault();
    }

    /// Shows the main JavaFX window.
    ///
    /// @param primaryStage primary application stage.
    @Override
    public void start(Stage primaryStage) {
        AppServices currentServices = services;
        if (currentServices == null) {
            currentServices = AppServices.createDefault();
        }

        MainWindow window = new MainWindow(currentServices);
        Scene scene = new Scene(window.root(), 980, 640);
        URL stylesheet = RuyiImager.class.getResource("/org/glavo/ruyi/imager/gui/application.css");
        if (stylesheet != null) {
            scene.getStylesheets().add(stylesheet.toExternalForm());
        }

        primaryStage.setTitle(Messages.get("app.title"));
        primaryStage.setMinWidth(840);
        primaryStage.setMinHeight(560);
        primaryStage.setScene(scene);
        primaryStage.show();
    }
}

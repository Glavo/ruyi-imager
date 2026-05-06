// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;
import io.github.palexdev.materialfx.theming.JavaFXThemes;
import io.github.palexdev.materialfx.theming.MaterialFXStylesheets;
import io.github.palexdev.materialfx.theming.UserAgentBuilder;
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
        installMaterialTheme();

        AppServices currentServices = services;
        if (currentServices == null) {
            currentServices = AppServices.createDefault();
        }

        MainWindow window = new MainWindow(currentServices);
        Scene scene = new Scene(window.root(), 1180, 760);
        URL stylesheet = RuyiImager.class.getResource("/org/glavo/ruyi/imager/gui/application.css");
        if (stylesheet != null) {
            scene.getStylesheets().add(stylesheet.toExternalForm());
        }

        primaryStage.titleProperty().bind(Messages.binding("app.title"));
        primaryStage.setMinWidth(840);
        primaryStage.setMinHeight(620);
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    /// Installs the MaterialFX theme before the scene graph is rendered.
    private static void installMaterialTheme() {
        UserAgentBuilder.builder()
                .themes(JavaFXThemes.MODENA)
                .themes(MaterialFXStylesheets.forAssemble(true))
                .setDeploy(true)
                .setResolveAssets(true)
                .build()
                .setGlobal();
    }
}

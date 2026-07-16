// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.text.Font;
import javafx.stage.Screen;
import javafx.stage.Stage;
import io.github.palexdev.materialfx.theming.JavaFXThemes;
import io.github.palexdev.materialfx.theming.MaterialFXStylesheets;
import io.github.palexdev.materialfx.theming.UserAgentBuilder;
import org.glavo.ruyi.imager.core.AppServices;
import org.glavo.ruyi.imager.gui.MainWindow;
import org.glavo.ruyi.imager.i18n.Messages;
import org.glavo.ruyi.imager.logging.RuyiLogging;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.net.URL;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// JavaFX application for the Ruyi Imager guided flashing workflow.
@NotNullByDefault
public final class RuyiImager extends Application {
    /// Logger for JavaFX application lifecycle events.
    private static final Logger LOGGER = LoggerFactory.getLogger(RuyiImager.class);

    /// Default GUI font bundled with the application.
    private static final String DEFAULT_FONT_RESOURCE =
            "/org/glavo/ruyi/imager/fonts/AlibabaPuHuiTi-3-65-Medium.ttf";

    /// Application icon resources installed on JavaFX stages.
    private static final String @Unmodifiable [] APPLICATION_ICON_RESOURCES = {
            "/ruyi-logo-16.png",
            "/ruyi-logo-24.png",
            "/ruyi-logo-32.png",
            "/ruyi-logo-64.png",
            "/ruyi-logo-128.png",
            "/ruyi-logo-256.png",
    };

    /// Core services shared by the GUI and CLI.
    private @Nullable AppServices services;

    /// Creates the default service graph before the UI is shown.
    @Override
    public void init() {
        services = AppServices.createDefault();
        RuyiLogging.configure(services.directories());
        LOGGER.info("JavaFX application initialized.");
    }

    /// Shows the main JavaFX window.
    ///
    /// @param primaryStage primary application stage.
    @Override
    public void start(Stage primaryStage) {
        loadDefaultFont();
        installMaterialTheme();
        LOGGER.info("Showing main window.");

        AppServices currentServices = services;
        if (currentServices == null) {
            currentServices = AppServices.createDefault();
            RuyiLogging.configure(currentServices.directories());
        }

        MainWindow window = new MainWindow(currentServices);
        Scene scene = new Scene(window.root(), 1180, 700);
        URL stylesheet = RuyiImager.class.getResource("/org/glavo/ruyi/imager/gui/application.css");
        if (stylesheet != null) {
            scene.getStylesheets().add(stylesheet.toExternalForm());
        }

        installWindowIcons(primaryStage);
        primaryStage.titleProperty().bind(Messages.binding("app.title"));
        primaryStage.setMinWidth(840);
        primaryStage.setMinHeight(560);
        primaryStage.setScene(scene);
        primaryStage.show();
        Platform.runLater(window::showStartupActions);
    }

    /// Stops the JavaFX application and closes logging resources.
    @Override
    public void stop() {
        LOGGER.info("JavaFX application stopped.");
        RuyiLogging.shutdown();
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

    /// Loads the bundled default GUI font.
    private static void loadDefaultFont() {
        if (System.getProperty("prism.lcdtext") == null
                && System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows")
                && Screen.getPrimary().getOutputScaleX() > 1) {
            System.getProperties().put("prism.lcdtext", "false");
        }

        @Nullable URL fontResource = RuyiImager.class.getResource(DEFAULT_FONT_RESOURCE);
        if (fontResource == null) {
            LOGGER.warn("Default GUI font resource is missing.");
            return;
        }

        @Nullable Font font = Font.loadFont(fontResource.toExternalForm(), 13.0);
        if (font == null) {
            LOGGER.warn("Default GUI font could not be loaded.");
        } else {
            LOGGER.atInfo().log(() -> "Loaded default GUI font. family=" + font.getFamily() + ", name=" + font.getName());
        }
    }

    /// Installs bundled application icons on a JavaFX stage.
    ///
    /// @param stage stage receiving the application icons.
    private static void installWindowIcons(Stage stage) {
        for (String resource : APPLICATION_ICON_RESOURCES) {
            @Nullable URL iconResource = RuyiImager.class.getResource(resource);
            if (iconResource == null) {
                LOGGER.warn("Application icon resource is missing: {}", resource);
                continue;
            }

            Image icon = new Image(iconResource.toExternalForm());
            if (icon.isError()) {
                @Nullable Throwable failure = icon.getException();
                if (failure == null) {
                    LOGGER.warn("Application icon could not be loaded: {}", resource);
                } else {
                    LOGGER.warn("Application icon could not be loaded: " + resource, failure);
                }
                continue;
            }

            stage.getIcons().add(icon);
        }

        if (stage.getIcons().isEmpty()) {
            LOGGER.warn("No application icon resources were loaded.");
        }
    }
}

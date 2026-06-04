// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager;

import org.glavo.ruyi.imager.core.AppDirectories;
import org.glavo.ruyi.imager.cli.CliApplication;
import org.glavo.ruyi.imager.core.AppServices;
import org.glavo.ruyi.imager.core.NetworkDefaults;
import org.glavo.ruyi.imager.logging.RuyiLogging;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Starts the Ruyi Imager desktop or command-line application.
@NotNullByDefault
public final class Main {
    /// Logger for process bootstrap events.
    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);

    /// Prevents construction of the bootstrap class.
    private Main() {
    }

    /// Runs the application.
    ///
    /// @param args command-line arguments.
    public static void main(String @Unmodifiable [] args) {
        NetworkDefaults.enableSystemProxiesByDefault();
        RuyiLogging.configure(AppDirectories.defaults());
        LOGGER.info("Starting Ruyi Imager.");
        if (shouldLaunchGui(args)) {
            LOGGER.info("Launching JavaFX GUI.");
            JavaFxLauncher.launch(args);
            return;
        }

        LOGGER.info("Launching CLI.");
        int exitCode = CliApplication.run(AppServices.createDefault(), args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    /// Checks whether the requested command should open the JavaFX interface.
    ///
    /// @param args command-line arguments.
    /// @return whether JavaFX should be launched.
    static boolean shouldLaunchGui(String @Unmodifiable [] args) {
        return args.length == 0 || (args.length == 1 && "gui".equals(args[0]));
    }
}

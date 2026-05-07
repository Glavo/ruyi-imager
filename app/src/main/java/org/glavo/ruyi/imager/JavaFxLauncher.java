// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/// Launches the JavaFX application without linking the CLI bootstrap to JavaFX classes.
@NotNullByDefault
final class JavaFxLauncher {
    /// Fully qualified JavaFX application base class name.
    private static final String JAVAFX_APPLICATION_CLASS = "javafx.application.Application";

    /// Fully qualified Ruyi Imager JavaFX application class name.
    private static final String RUYI_IMAGER_APPLICATION_CLASS = "org.glavo.ruyi.imager.RuyiImager";

    /// Prevents construction.
    private JavaFxLauncher() {
    }

    /// Launches the JavaFX GUI.
    ///
    /// @param args command-line arguments passed to JavaFX.
    static void launch(String @Unmodifiable [] args) {
        try {
            Class<?> applicationClass = Class.forName(JAVAFX_APPLICATION_CLASS);
            Class<?> ruyiImagerClass = Class.forName(RUYI_IMAGER_APPLICATION_CLASS);
            Method launch = applicationClass.getMethod("launch", Class.class, String[].class);
            launch.invoke(null, ruyiImagerClass, args);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                    "JavaFX runtime is not available. Install JavaFX for this platform or run a CLI command.",
                    e);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new IllegalStateException("JavaFX launcher is not available.", e);
        } catch (InvocationTargetException e) {
            throwUnchecked(e.getCause());
        }
    }

    /// Throws a reflected launcher failure without wrapping unchecked failures.
    ///
    /// @param cause reflected failure cause.
    private static void throwUnchecked(@Nullable Throwable cause) {
        if (cause == null) {
            throw new IllegalStateException("JavaFX launcher failed.");
        }
        if (cause instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("JavaFX launcher failed.", cause);
    }
}

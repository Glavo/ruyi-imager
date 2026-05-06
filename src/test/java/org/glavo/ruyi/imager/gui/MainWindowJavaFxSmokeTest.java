// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.gui;

import io.github.palexdev.materialfx.controls.MFXComboBox;
import io.github.palexdev.materialfx.controls.MFXTextField;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.VBox;
import org.glavo.ruyi.imager.core.AppDirectories;
import org.glavo.ruyi.imager.core.AppServices;
import org.glavo.ruyi.imager.core.OperationResult;
import org.glavo.ruyi.imager.core.ProgressReporter;
import org.glavo.ruyi.imager.core.StrategySupport;
import org.glavo.ruyi.imager.core.device.BlockDevice;
import org.glavo.ruyi.imager.core.device.BlockDeviceService;
import org.glavo.ruyi.imager.core.fastboot.FastbootDevice;
import org.glavo.ruyi.imager.core.fastboot.FastbootService;
import org.glavo.ruyi.imager.core.flash.FlashRequest;
import org.glavo.ruyi.imager.core.flash.FlashService;
import org.glavo.ruyi.imager.core.flash.FlashTarget;
import org.glavo.ruyi.imager.core.image.ImageCatalog;
import org.glavo.ruyi.imager.core.image.ImageCatalogService;
import org.glavo.ruyi.imager.core.image.ImageEntry;
import org.glavo.ruyi.imager.core.repo.RepositoryService;
import org.glavo.ruyi.imager.i18n.Messages;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.TestAbortedException;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/// Smoke tests for JavaFX controls used by the main window selection flows.
@NotNullByDefault
public final class MainWindowJavaFxSmokeTest {
    /// Maximum time to wait for one JavaFX action.
    private static final long FX_TIMEOUT_SECONDS = 10L;

    /// Starts the JavaFX toolkit for smoke tests.
    @BeforeAll
    public static void startJavaFx() {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "JavaFX smoke tests require a graphics environment.");
        try {
            Platform.startup(() -> {
            });
        } catch (IllegalStateException _) {
        } catch (RuntimeException e) {
            throw new TestAbortedException("JavaFX toolkit is not available.", e);
        }
    }

    /// Verifies that the operating-system tree can filter, select leaves, and resolve a selected category.
    ///
    /// @throws Exception when JavaFX execution fails.
    @Test
    public void filtersAndSelectsOperatingSystemTree() throws Exception {
        runOnJavaFxThread(() -> {
            Locale originalLocale = Messages.locale();
            Messages.setLocale(Locale.ENGLISH);
            try {
                ImageEntry revyos = image(
                        "revyos-test",
                        "RevyOS image for Test Board",
                        "generic",
                        "dd-v1",
                        Map.of("disk", "revyos.raw"));
                ImageEntry ubuntu = image(
                        "ubuntu-test",
                        "Ubuntu image for Test Board",
                        "generic",
                        "dd-v1",
                        Map.of("disk", "ubuntu.raw"));

                TreeView<MainWindow.OperatingSystemTreeNode> treeView = MainWindow.selectionTreeView();
                Node content = MainWindow.operatingSystemSelectionContent(treeView, List.of(revyos, ubuntu), revyos);
                VBox contentBox = assertInstanceOf(VBox.class, content);
                MFXTextField searchField = assertInstanceOf(MFXTextField.class, contentBox.getChildren().get(0));

                assertEquals(revyos, MainWindow.selectedTreeImage(treeView));

                searchField.setText("ubuntu");
                TreeItem<MainWindow.OperatingSystemTreeNode> root = treeView.getRoot();
                assertNotNull(root);
                assertEquals(1, root.getChildren().size());
                TreeItem<MainWindow.OperatingSystemTreeNode> category = root.getChildren().getFirst();
                assertEquals("Ubuntu", category.getValue().name());

                treeView.getSelectionModel().select(category);
                @Nullable ImageEntry selected = MainWindow.selectedTreeImage(treeView);
                assertNotNull(selected);
                assertEquals(ubuntu.atom(), selected.atom());
            } finally {
                Messages.setLocale(originalLocale);
            }
            return null;
        });
    }

    /// Verifies that multi-partition storage selectors produce a partition target map.
    ///
    /// @param temporaryDirectory temporary application directory.
    /// @throws Exception when JavaFX execution fails.
    @Test
    public void mapsPartitionStorageSelectors(@TempDir Path temporaryDirectory) throws Exception {
        runOnJavaFxThread(() -> {
            MainWindow window = new MainWindow(testServices(temporaryDirectory));
            BlockDevice boot = blockDevice("boot", temporaryDirectory.resolve("boot.raw"));
            BlockDevice root = blockDevice("root", temporaryDirectory.resolve("root.raw"));

            LinkedHashMap<String, MFXComboBox<BlockDevice>> selectors = new LinkedHashMap<>();
            selectors.put("boot", selector(List.of(boot, root), boot));
            selectors.put("root", selector(List.of(boot, root), root));

            @Nullable FlashTarget target = window.partitionTargetSelection(selectors);
            assertNotNull(target);
            assertFalse(target.blockDevices().isEmpty());
            assertEquals(boot, target.blockDevices().get("boot"));
            assertEquals(root, target.blockDevices().get("root"));
            return null;
        });
    }

    /// Runs an action on the JavaFX application thread.
    ///
    /// @param action action to run.
    /// @param <T> action result type.
    /// @return action result.
    /// @throws Exception when the action fails.
    private static <T> T runOnJavaFxThread(Callable<T> action) throws Exception {
        if (Platform.isFxApplicationThread()) {
            return action.call();
        }

        CompletableFuture<T> future = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                future.complete(action.call());
            } catch (Throwable e) {
                future.completeExceptionally(e);
            }
        });

        try {
            return future.get(FX_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new RuntimeException(cause);
        } catch (TimeoutException e) {
            throw new AssertionError("Timed out waiting for JavaFX action.", e);
        }
    }

    /// Creates a block-device selector with a selected value.
    ///
    /// @param devices available devices.
    /// @param selected selected device.
    /// @return configured selector.
    private static MFXComboBox<BlockDevice> selector(
            @Unmodifiable List<BlockDevice> devices,
            BlockDevice selected) {
        MFXComboBox<BlockDevice> selector = new MFXComboBox<>();
        selector.getItems().setAll(devices);
        selector.setValue(selected);
        return selector;
    }

    /// Creates an image entry for GUI smoke tests.
    ///
    /// @param name package name.
    /// @param displayName image display name.
    /// @param variant image variant.
    /// @param strategy provision strategy.
    /// @param partitionMap image partition map.
    /// @return image entry.
    private static ImageEntry image(
            String name,
            String displayName,
            String variant,
            String strategy,
            @Unmodifiable Map<String, String> partitionMap) {
        return new ImageEntry(
                "ruyisdk",
                name,
                "test-board",
                "1.0.0",
                null,
                name + "/test-board(1.0.0)",
                displayName,
                "Test Manufacturer",
                "test-board",
                variant,
                strategy,
                partitionMap,
                List.of(),
                StrategySupport.SUPPORTED);
    }

    /// Creates block-device metadata for GUI smoke tests.
    ///
    /// @param id device id.
    /// @param path device path.
    /// @return block-device metadata.
    private static BlockDevice blockDevice(String id, Path path) {
        return new BlockDevice(
                id,
                "Test " + id,
                path,
                1024L,
                true,
                false,
                false,
                false,
                "Test",
                "file");
    }

    /// Creates a minimal service graph for constructing the main window.
    ///
    /// @param directory temporary application directory.
    /// @return test service graph.
    private static AppServices testServices(Path directory) {
        AppDirectories directories = new AppDirectories(directory.resolve("config"), directory.resolve("cache"));
        return new AppServices(
                directories,
                new EmptyRepositoryService(),
                new EmptyImageCatalogService(),
                new EmptyBlockDeviceService(),
                new EmptyFastbootService(),
                new NoOpFlashService());
    }

    /// Repository service that reports a no-op success.
    @NotNullByDefault
    private static final class EmptyRepositoryService implements RepositoryService {
        /// Reports a successful no-op update.
        ///
        /// @param reporter progress reporter.
        /// @return successful operation result.
        @Override
        public OperationResult update(ProgressReporter reporter) {
            return OperationResult.success("No update.");
        }
    }

    /// Image catalog service with no images.
    @NotNullByDefault
    private static final class EmptyImageCatalogService implements ImageCatalogService {
        /// Lists no images.
        ///
        /// @return empty image catalog.
        @Override
        public ImageCatalog listImages() {
            return new ImageCatalog(List.of());
        }

        /// Refuses image downloads.
        ///
        /// @param image image to download.
        /// @param reporter progress reporter.
        /// @return never returns.
        /// @throws IOException always thrown.
        @Override
        public Path downloadImage(ImageEntry image, ProgressReporter reporter) throws IOException {
            throw new IOException("No images are available.");
        }
    }

    /// Block-device service with no devices.
    @NotNullByDefault
    private static final class EmptyBlockDeviceService implements BlockDeviceService {
        /// Lists no block devices.
        ///
        /// @return empty device list.
        @Override
        public @Unmodifiable List<BlockDevice> listDevices() {
            return List.of();
        }
    }

    /// Fastboot service with no devices.
    @NotNullByDefault
    private static final class EmptyFastbootService implements FastbootService {
        /// Lists no fastboot devices.
        ///
        /// @return empty fastboot device list.
        @Override
        public @Unmodifiable List<FastbootDevice> listDevices() {
            return List.of();
        }

        /// Refuses fastboot flashing.
        ///
        /// @param strategy Ruyi provision strategy.
        /// @param partitions materialized partition images keyed by target partition name.
        /// @param device target fastboot device.
        /// @param reporter progress reporter.
        /// @return never returns.
        /// @throws IOException always thrown.
        @Override
        public OperationResult flash(
                String strategy,
                @Unmodifiable Map<String, Path> partitions,
                FastbootDevice device,
                ProgressReporter reporter) throws IOException {
            throw new IOException("Fastboot is not available.");
        }
    }

    /// Flash service that does nothing.
    @NotNullByDefault
    private static final class NoOpFlashService implements FlashService {
        /// Reports a successful no-op flash.
        ///
        /// @param request flash request.
        /// @param reporter progress reporter.
        /// @return successful operation result.
        @Override
        public OperationResult flash(FlashRequest request, ProgressReporter reporter) {
            return OperationResult.success("No flash.");
        }
    }
}

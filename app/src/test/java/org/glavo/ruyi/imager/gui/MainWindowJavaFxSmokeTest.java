// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.gui;

import io.github.palexdev.materialfx.controls.MFXComboBox;
import javafx.scene.control.TextField;
import javafx.application.Platform;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.HBox;
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
import java.net.URL;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
                Node content = MainWindow.operatingSystemSelectionContent("Choose OS", treeView, List.of(revyos, ubuntu), revyos);
                VBox contentBox = assertInstanceOf(VBox.class, content);
                HBox headerRow = assertInstanceOf(HBox.class, contentBox.getChildren().get(0));
                TextField searchField = assertInstanceOf(
                        TextField.class,
                        headerRow.getChildren().get(headerRow.getChildren().size() - 1));

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

    /// Verifies that the operating-system tree keeps a visible vertical scrollbar.
    ///
    /// @throws Exception when JavaFX execution fails.
    @Test
    public void showsOperatingSystemTreeScrollbar() throws Exception {
        runOnJavaFxThread(() -> {
            TreeView<MainWindow.OperatingSystemTreeNode> treeView = MainWindow.selectionTreeView();
            Node content = MainWindow.operatingSystemSelectionContent("Choose OS", treeView, manyImages(), null);
            Parent parent = assertInstanceOf(Parent.class, content);
            Scene scene = new Scene(parent);
            @Nullable String stylesheet = applicationStylesheet();
            assertNotNull(stylesheet);
            scene.getStylesheets().add(stylesheet);

            parent.applyCss();
            parent.layout();
            treeView.applyCss();
            treeView.layout();

            @Nullable ScrollBar verticalScrollBar = verticalScrollBar(treeView);
            assertNotNull(verticalScrollBar);
            assertTrue(verticalScrollBar.prefWidth(-1.0) > 0.0);
            assertTrue(verticalScrollBar.isVisible());
            return null;
        });
    }

    /// Verifies the daily automatic metadata update boundary.
    @Test
    public void schedulesDailyMetadataUpdates() {
        Instant now = Instant.parse("2026-07-14T06:00:00Z");

        assertTrue(MainWindow.metadataUpdateDue(null, now));
        assertFalse(MainWindow.metadataUpdateDue(now.minus(Duration.ofHours(23)), now));
        assertTrue(MainWindow.metadataUpdateDue(now.minus(Duration.ofHours(24)), now));
        assertFalse(MainWindow.metadataUpdateDue(now.plus(Duration.ofHours(1)), now));
    }

    /// Verifies the daily automatic application update check boundary.
    @Test
    public void schedulesDailyApplicationUpdateChecks() {
        Instant now = Instant.parse("2026-07-16T06:00:00Z");

        assertTrue(MainWindow.applicationUpdateCheckDue(null, now));
        assertFalse(MainWindow.applicationUpdateCheckDue(now.minus(Duration.ofHours(23)), now));
        assertTrue(MainWindow.applicationUpdateCheckDue(now.minus(Duration.ofHours(24)), now));
        assertFalse(MainWindow.applicationUpdateCheckDue(now.plus(Duration.ofHours(1)), now));
    }

    /// Verifies flash progress rows are known before backend progress events arrive.
    @Test
    public void createsInitialFlashProgressStages() {
        ImageEntry ddImage = image(
                "dd-test",
                "DD image for Test Board",
                "generic",
                "dd-v1",
                Map.of("disk", "dd.raw"));
        ImageEntry fastbootImage = image(
                "fastboot-test",
                "Fastboot image for Test Board",
                "generic",
                "fastboot-v1",
                Map.of("root", "root.ext4"));
        BlockDevice block = blockDevice("target", Path.of("target.raw"));
        FastbootDevice fastboot = new FastbootDevice("serial", "serial", "fastboot");

        assertEquals(
                List.of("download", "materialize", "prepare", "flash", "verify"),
                MainWindow.initialPhaseProgressStages(ddImage, FlashTarget.blockDevice(block)));
        assertEquals(
                List.of("prepare", "flash", "verify"),
                MainWindow.initialPhaseProgressStages(null, FlashTarget.blockDevice(block)));
        assertEquals(
                List.of("download", "materialize", "fastboot"),
                MainWindow.initialPhaseProgressStages(fastbootImage, FlashTarget.fastbootDevice(fastboot)));
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

    /// Finds the vertical scrollbar inside a tree view.
    ///
    /// @param treeView tree view.
    /// @return vertical scrollbar, or null when no scrollbar exists.
    private static @Nullable ScrollBar verticalScrollBar(TreeView<?> treeView) {
        Set<Node> scrollBars = treeView.lookupAll(".scroll-bar");
        for (Node node : scrollBars) {
            if (node instanceof ScrollBar scrollBar && scrollBar.getOrientation() == Orientation.VERTICAL) {
                return scrollBar;
            }
        }
        return null;
    }

    /// Returns the application stylesheet for GUI smoke tests.
    ///
    /// @return stylesheet URL, or null when missing.
    private static @Nullable String applicationStylesheet() {
        @Nullable URL stylesheet = MainWindow.class.getResource("/org/glavo/ruyi/imager/gui/application.css");
        return stylesheet == null ? null : stylesheet.toExternalForm();
    }

    /// Creates enough image entries to require scrolling in the operating-system tree.
    ///
    /// @return image list.
    private static @Unmodifiable List<ImageEntry> manyImages() {
        ArrayList<ImageEntry> images = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            images.add(image(
                    "ubuntu-test-" + i,
                    "Ubuntu image " + i + " for Test Board",
                    "generic",
                    "dd-v1",
                    Map.of("disk", "ubuntu-" + i + ".raw")));
        }
        return List.copyOf(images);
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
        /// Reports that local metadata is already available.
        ///
        /// @return true.
        @Override
        public boolean hasLocalMetadata() {
            return true;
        }

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

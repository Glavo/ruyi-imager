// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.glavo.ruyi.imager.core.AppServices;
import org.glavo.ruyi.imager.core.OperationResult;
import org.glavo.ruyi.imager.core.ProgressEvent;
import org.glavo.ruyi.imager.core.ProgressReporter;
import org.glavo.ruyi.imager.core.device.BlockDevice;
import org.glavo.ruyi.imager.core.flash.FlashRequest;
import org.glavo.ruyi.imager.core.image.ImageCatalog;
import org.glavo.ruyi.imager.core.image.ImageEntry;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/// Picocli command tree for scriptable Ruyi Imager operations.
@Command(
        name = "ruyi-imager",
        mixinStandardHelpOptions = true,
        description = "Flash Ruyi images from a CLI or GUI.")
@NotNullByDefault
public final class CliApplication implements Runnable {
    /// Services used by every command.
    private final AppServices services;

    /// Picocli command specification injected at runtime.
    @Spec
    private @Nullable CommandSpec spec;

    /// Creates the command root.
    ///
    /// @param services shared application services.
    public CliApplication(AppServices services) {
        this.services = services;
    }

    /// Executes CLI arguments.
    ///
    /// @param services shared application services.
    /// @param args command-line arguments.
    /// @return process exit code.
    public static int run(AppServices services, String @Unmodifiable [] args) {
        CliApplication root = new CliApplication(services);
        CommandLine commandLine = new CommandLine(root);
        commandLine.addSubcommand("repo", repoCommand(services));
        commandLine.addSubcommand("image", imageCommand(services));
        commandLine.addSubcommand("device", deviceCommand(services));
        commandLine.addSubcommand("flash", new FlashCommand(services));
        return commandLine.execute(args);
    }

    /// Prints root usage when no CLI subcommand was selected.
    @Override
    public void run() {
        printUsage(spec);
    }

    /// Builds the repository command group.
    ///
    /// @param services shared application services.
    /// @return configured command line.
    private static CommandLine repoCommand(AppServices services) {
        CommandLine commandLine = new CommandLine(new RepoCommand());
        commandLine.addSubcommand("update", new RepoUpdateCommand(services));
        return commandLine;
    }

    /// Builds the image command group.
    ///
    /// @param services shared application services.
    /// @return configured command line.
    private static CommandLine imageCommand(AppServices services) {
        CommandLine commandLine = new CommandLine(new ImageCommand());
        commandLine.addSubcommand("list", new ImageListCommand(services));
        commandLine.addSubcommand("download", new ImageDownloadCommand(services));
        return commandLine;
    }

    /// Builds the device command group.
    ///
    /// @param services shared application services.
    /// @return configured command line.
    private static CommandLine deviceCommand(AppServices services) {
        CommandLine commandLine = new CommandLine(new DeviceCommand());
        commandLine.addSubcommand("list", new DeviceListCommand(services));
        return commandLine;
    }

    /// Prints usage for a command specification.
    ///
    /// @param spec command specification.
    private static void printUsage(@Nullable CommandSpec spec) {
        if (spec != null) {
            spec.commandLine().usage(System.out);
        }
    }

    /// Creates a progress reporter for the selected output mode.
    ///
    /// @param json whether progress should be emitted as JSON events.
    /// @return progress reporter.
    private static ProgressReporter progressReporter(boolean json) {
        if (json) {
            return event -> JsonOutput.print(eventMap("progress", event));
        }

        return event -> System.err.printf("%s: %s%n", event.stage(), event.message());
    }

    /// Creates a JSON event object.
    ///
    /// @param type event type.
    /// @param event progress event.
    /// @return JSON-ready event map.
    private static Map<String, Object> eventMap(String type, ProgressEvent event) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", type);
        map.put("stage", event.stage());
        map.put("message", event.message());
        map.put("currentBytes", event.currentBytes());
        map.put("totalBytes", event.totalBytes());
        return map;
    }

    /// Prints a command failure in the selected output mode.
    ///
    /// @param message error message.
    /// @param json whether JSON output is enabled.
    /// @return software error exit code.
    private static int fail(String message, boolean json) {
        if (json) {
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("type", "error");
            output.put("message", message);
            JsonOutput.print(output);
        } else {
            System.err.println("Error: " + message);
        }
        return CommandLine.ExitCode.SOFTWARE;
    }

    /// Prints an operation result in the selected output mode.
    ///
    /// @param result operation result.
    /// @param json whether JSON output is enabled.
    /// @return process exit code.
    private static int finish(OperationResult result, boolean json) {
        if (json) {
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("type", result.success() ? "complete" : "error");
            output.put("success", result.success());
            output.put("message", result.message());
            JsonOutput.print(output);
        } else {
            PrintStream stream = result.success() ? System.out : System.err;
            stream.println(result.message());
        }

        return result.success() ? CommandLine.ExitCode.OK : CommandLine.ExitCode.SOFTWARE;
    }

    /// Command group for repository operations.
    @Command(name = "repo", description = "Manage Ruyi metadata repositories.")
    @NotNullByDefault
    private static final class RepoCommand implements Runnable {
        /// Picocli command specification injected at runtime.
        @Spec
        private @Nullable CommandSpec spec;

        /// Prints repository command usage.
        @Override
        public void run() {
            printUsage(spec);
        }
    }

    /// Updates local Ruyi metadata repositories.
    @Command(name = "update", description = "Prepare or refresh local Ruyi metadata.")
    @NotNullByDefault
    private static final class RepoUpdateCommand implements Callable<Integer> {
        /// Services used by the command.
        private final AppServices services;

        /// Whether command output should be JSON.
        @Option(names = "--json", description = "Emit newline-delimited JSON events.")
        private boolean json;

        /// Creates the repository update command.
        ///
        /// @param services shared application services.
        private RepoUpdateCommand(AppServices services) {
            this.services = services;
        }

        /// Runs repository update.
        ///
        /// @return process exit code.
        @Override
        public Integer call() {
            try {
                OperationResult result = services.repository().update(progressReporter(json));
                return finish(result, json);
            } catch (IOException | RuntimeException e) {
                return fail(e.getMessage(), json);
            }
        }
    }

    /// Command group for image operations.
    @Command(name = "image", description = "List and download Ruyi images.")
    @NotNullByDefault
    private static final class ImageCommand implements Runnable {
        /// Picocli command specification injected at runtime.
        @Spec
        private @Nullable CommandSpec spec;

        /// Prints image command usage.
        @Override
        public void run() {
            printUsage(spec);
        }
    }

    /// Lists known Ruyi images.
    @Command(name = "list", description = "List images from the local Ruyi metadata cache.")
    @NotNullByDefault
    private static final class ImageListCommand implements Callable<Integer> {
        /// Services used by the command.
        private final AppServices services;

        /// Whether command output should be JSON.
        @Option(names = "--json", description = "Emit a JSON object.")
        private boolean json;

        /// Creates the image list command.
        ///
        /// @param services shared application services.
        private ImageListCommand(AppServices services) {
            this.services = services;
        }

        /// Lists images.
        ///
        /// @return process exit code.
        @Override
        public Integer call() {
            try {
                ImageCatalog catalog = services.images().listImages();
                if (json) {
                    Map<String, Object> output = new LinkedHashMap<>();
                    output.put("type", "image-list");
                    output.put("images", catalog.images());
                    JsonOutput.print(output);
                    return CommandLine.ExitCode.OK;
                }

                if (catalog.images().isEmpty()) {
                    System.out.println("No images are available in the local metadata cache.");
                    return CommandLine.ExitCode.OK;
                }

                for (ImageEntry image : catalog.images()) {
                    System.out.printf("%s\t%s\t%s%n", image.atom(), image.displayName(), image.strategy());
                }
                return CommandLine.ExitCode.OK;
            } catch (IOException | RuntimeException e) {
                return fail(e.getMessage(), json);
            }
        }
    }

    /// Downloads a Ruyi image.
    @Command(name = "download", description = "Download one image by atom name.")
    @NotNullByDefault
    private static final class ImageDownloadCommand implements Callable<Integer> {
        /// Services used by the command.
        private final AppServices services;

        /// Whether command output should be JSON.
        @Option(names = "--json", description = "Emit newline-delimited JSON events.")
        private boolean json;

        /// Image atom to download.
        @Parameters(index = "0", paramLabel = "ATOM", description = "Image atom name.")
        private @Nullable String atom;

        /// Creates the image download command.
        ///
        /// @param services shared application services.
        private ImageDownloadCommand(AppServices services) {
            this.services = services;
        }

        /// Downloads an image.
        ///
        /// @return process exit code.
        @Override
        public Integer call() {
            String requestedAtom = atom;
            if (requestedAtom == null) {
                return fail("Missing image atom.", json);
            }

            try {
                ImageEntry image = services.images().findImage(requestedAtom);
                if (image == null) {
                    return fail("Unknown image atom: " + requestedAtom, json);
                }

                Path imagePath = services.images().downloadImage(image, progressReporter(json));
                if (json) {
                    Map<String, Object> output = new LinkedHashMap<>();
                    output.put("type", "complete");
                    output.put("success", true);
                    output.put("path", imagePath.toString());
                    JsonOutput.print(output);
                } else {
                    System.out.println(imagePath);
                }
                return CommandLine.ExitCode.OK;
            } catch (IOException | RuntimeException e) {
                return fail(e.getMessage(), json);
            }
        }
    }

    /// Command group for block device operations.
    @Command(name = "device", description = "Inspect writable target devices.")
    @NotNullByDefault
    private static final class DeviceCommand implements Runnable {
        /// Picocli command specification injected at runtime.
        @Spec
        private @Nullable CommandSpec spec;

        /// Prints device command usage.
        @Override
        public void run() {
            printUsage(spec);
        }
    }

    /// Lists writable target devices.
    @Command(name = "list", description = "List candidate target devices.")
    @NotNullByDefault
    private static final class DeviceListCommand implements Callable<Integer> {
        /// Services used by the command.
        private final AppServices services;

        /// Whether command output should be JSON.
        @Option(names = "--json", description = "Emit a JSON object.")
        private boolean json;

        /// Creates the device list command.
        ///
        /// @param services shared application services.
        private DeviceListCommand(AppServices services) {
            this.services = services;
        }

        /// Lists block devices.
        ///
        /// @return process exit code.
        @Override
        public Integer call() {
            try {
                List<BlockDevice> devices = services.devices().listDevices();
                if (json) {
                    Map<String, Object> output = new LinkedHashMap<>();
                    output.put("type", "device-list");
                    output.put("devices", deviceOutput(devices));
                    JsonOutput.print(output);
                } else if (devices.isEmpty()) {
                    System.out.println("No target devices were detected.");
                } else {
                    for (BlockDevice device : devices) {
                        System.out.printf("%s\t%s\t%s%n", device.id(), device.displayName(), devicePathText(device));
                    }
                }
                return CommandLine.ExitCode.OK;
            } catch (IOException | RuntimeException e) {
                return fail(e.getMessage(), json);
            }
        }
    }

    /// Converts block devices to stable JSON output maps.
    ///
    /// @param devices devices to serialize.
    /// @return immutable JSON-ready device maps.
    private static @Unmodifiable List<@Unmodifiable Map<String, @Nullable Object>> deviceOutput(
            List<BlockDevice> devices) {
        ArrayList<@Unmodifiable Map<String, @Nullable Object>> output = new ArrayList<>(devices.size());
        for (BlockDevice device : devices) {
            Map<String, @Nullable Object> map = new LinkedHashMap<>();
            map.put("id", device.id());
            map.put("displayName", device.displayName());
            map.put("path", devicePathText(device));
            map.put("sizeBytes", device.sizeBytes());
            map.put("removable", device.removable());
            map.put("system", device.system());
            map.put("mounted", device.mounted());
            map.put("readOnly", device.readOnly());
            map.put("model", device.model());
            map.put("busType", device.busType());
            output.add(Collections.unmodifiableMap(map));
        }
        return List.copyOf(output);
    }

    /// Converts a device target path to CLI text.
    ///
    /// @param device block device.
    /// @return printable target path.
    private static String devicePathText(BlockDevice device) {
        String text = device.path().toString();
        if (text.startsWith("\\\\.\\PHYSICALDRIVE") && text.endsWith("\\")) {
            return text.substring(0, text.length() - 1);
        }
        return text;
    }

    /// Flashes an image to a target device.
    @Command(name = "flash", description = "Flash an image to a target device.")
    @NotNullByDefault
    private static final class FlashCommand implements Callable<Integer> {
        /// Services used by the command.
        private final AppServices services;

        /// Image atom selected from Ruyi metadata.
        @Option(names = "--atom", paramLabel = "ATOM", description = "Image atom name.")
        private @Nullable String atom;

        /// Local image path to flash.
        @Option(names = "--local-image", paramLabel = "PATH", description = "Local image path.")
        private @Nullable Path localImage;

        /// Target block device identifier.
        @Option(names = "--device", required = true, paramLabel = "ID", description = "Target device id.")
        private @Nullable String deviceId;

        /// Whether the destructive operation was explicitly confirmed.
        @Option(names = "--yes", description = "Confirm destructive writing.")
        private boolean yes;

        /// Whether write verification should be skipped.
        @Option(names = "--skip-verify", description = "Skip post-write verification.")
        private boolean skipVerify;

        /// Whether command output should be JSON.
        @Option(names = "--json", description = "Emit newline-delimited JSON events.")
        private boolean json;

        /// Creates the flash command.
        ///
        /// @param services shared application services.
        private FlashCommand(AppServices services) {
            this.services = services;
        }

        /// Runs the flash command.
        ///
        /// @return process exit code.
        @Override
        public Integer call() {
            if (!yes) {
                return fail("Refusing to write without --yes.", json);
            }

            boolean hasAtom = atom != null;
            boolean hasLocalImage = localImage != null;
            if (hasAtom == hasLocalImage) {
                return fail("Specify exactly one of --atom or --local-image.", json);
            }

            String requestedDeviceId = deviceId;
            if (requestedDeviceId == null) {
                return fail("Missing target device id.", json);
            }

            try {
                BlockDevice target = services.devices().findDevice(requestedDeviceId);
                if (target == null) {
                    return fail("Unknown target device: " + requestedDeviceId, json);
                }

                ImageEntry image = null;
                Path requestedLocalImage = localImage;
                if (atom != null) {
                    image = services.images().findImage(atom);
                    if (image == null) {
                        return fail("Unknown image atom: " + atom, json);
                    }
                } else if (requestedLocalImage != null && !Files.isRegularFile(requestedLocalImage)) {
                    return fail("Local image does not exist: " + requestedLocalImage, json);
                }

                OperationResult result = services.flash().flash(
                        new FlashRequest(image, requestedLocalImage, target, !skipVerify),
                        progressReporter(json));
                return finish(result, json);
            } catch (IOException | RuntimeException e) {
                return fail(e.getMessage(), json);
            }
        }
    }

    /// JSON writer used by command output paths.
    @NotNullByDefault
    private static final class JsonOutput {
        /// Shared JSON mapper.
        private static final ObjectMapper MAPPER = new ObjectMapper();

        /// Prevents construction of the JSON utility.
        private JsonOutput() {
        }

        /// Writes one JSON object followed by a newline.
        ///
        /// @param value value to write.
        private static void print(Object value) {
            try {
                MAPPER.writeValue(System.out, value);
                System.out.println();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}

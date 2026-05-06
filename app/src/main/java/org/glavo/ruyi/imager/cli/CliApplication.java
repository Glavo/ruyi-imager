// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.glavo.ruyi.imager.core.AppServices;
import org.glavo.ruyi.imager.core.OperationResult;
import org.glavo.ruyi.imager.core.ProgressEvent;
import org.glavo.ruyi.imager.core.ProgressReporter;
import org.glavo.ruyi.imager.core.device.BlockDevice;
import org.glavo.ruyi.imager.core.fastboot.FastbootDevice;
import org.glavo.ruyi.imager.core.flash.FlashRequest;
import org.glavo.ruyi.imager.core.flash.FlashTarget;
import org.glavo.ruyi.imager.core.image.ImageCatalog;
import org.glavo.ruyi.imager.core.image.ImageEntry;
import org.glavo.ruyi.imager.i18n.Messages;
import org.glavo.ruyi.imager.logging.LogRedactor;
import org.glavo.ruyi.imager.logging.LoggingProgressReporter;
import org.glavo.ruyi.imager.logging.RuyiLogLevel;
import org.glavo.ruyi.imager.logging.RuyiLogging;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ScopeType;
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
import java.util.logging.Level;
import java.util.logging.Logger;

/// Picocli command tree for scriptable Ruyi Imager operations.
@Command(
        name = "ruyi-imager",
        description = "Flash Ruyi images from a CLI or GUI.")
@NotNullByDefault
public final class CliApplication implements Runnable {
    /// Logger for CLI command execution.
    private static final Logger LOGGER = Logger.getLogger(CliApplication.class.getName());

    /// Services used by every command.
    private final AppServices services;

    /// Picocli command specification injected at runtime.
    @Spec
    private @Nullable CommandSpec spec;

    /// Whether usage help should be displayed.
    @Option(
            names = {"-h", "--help"},
            usageHelp = true,
            description = "Show this help message and exit.",
            descriptionKey = "cli.option.help")
    private boolean usageHelp;

    /// Whether version help should be displayed.
    @Option(
            names = {"-V", "--version"},
            versionHelp = true,
            description = "Print version information and exit.",
            descriptionKey = "cli.option.version")
    private boolean versionHelp;

    /// Configured log level inherited by subcommands.
    @Option(
            names = "--log-level",
            scope = ScopeType.INHERIT,
            paramLabel = "LEVEL",
            description = "Set log level.",
            descriptionKey = "cli.option.logLevel")
    private @Nullable String logLevel;

    /// Whether debug logging is enabled.
    @Option(
            names = "--verbose",
            scope = ScopeType.INHERIT,
            description = "Enable debug logging.",
            descriptionKey = "cli.option.verbose")
    private boolean verbose;

    /// Configured log file inherited by subcommands.
    @Option(
            names = "--log-file",
            scope = ScopeType.INHERIT,
            paramLabel = "PATH",
            description = "Write logs to this file.",
            descriptionKey = "cli.option.logFile")
    private @Nullable Path logFile;

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
        RuyiLogging.configure(
                services.directories(),
                RuyiLogging.cliLevel(optionValue(args, "--log-level"), hasFlag(args, "--verbose")),
                pathOptionValue(args, "--log-file"));
        LOGGER.info(() -> "CLI command started. args=" + LogRedactor.redactCommand(List.of(args)));
        try {
            CliApplication root = new CliApplication(services);
            CommandLine commandLine = new CommandLine(root);
            commandLine.addSubcommand("repo", repoCommand(services));
            commandLine.addSubcommand("image", imageCommand(services));
            commandLine.addSubcommand("device", deviceCommand(services));
            commandLine.addSubcommand("flash", new FlashCommand(services));
            localizeCommands(commandLine);
            int exitCode = commandLine.execute(args);
            LOGGER.info(() -> "CLI command finished. exitCode=" + exitCode);
            return exitCode;
        } finally {
            RuyiLogging.shutdown();
        }
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
        ProgressReporter reporter;
        if (json) {
            reporter = event -> JsonOutput.print(eventMap("progress", event));
        } else {
            reporter = event -> System.err.printf("%s: %s%n", event.stage(), event.message());
        }

        return LoggingProgressReporter.wrap(reporter, LOGGER);
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
        @Nullable String logFile = RuyiLogging.currentLogFileText();
        if (json) {
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("type", "error");
            output.put("message", message);
            if (logFile != null) {
                output.put("logFile", logFile);
            }
            JsonOutput.print(output);
        } else {
            System.err.println(Messages.get("cli.error.prefix", message));
            if (logFile != null) {
                System.err.println("Log file: " + logFile);
            }
        }
        return CommandLine.ExitCode.SOFTWARE;
    }

    /// Logs and prints a command failure.
    ///
    /// @param exception failure exception.
    /// @param json whether JSON output is enabled.
    /// @return software error exit code.
    private static int failException(Exception exception, boolean json) {
        LOGGER.log(Level.SEVERE, "CLI command failed.", exception);
        return fail(exceptionMessage(exception), json);
    }

    /// Returns a localized fallback for an exception without a message.
    ///
    /// @param exception failure exception.
    /// @return failure message.
    private static String exceptionMessage(Exception exception) {
        @Nullable String message = exception.getMessage();
        return message == null ? Messages.get("cli.error.unknownFailure") : message;
    }

    /// Localizes command descriptions after the command model is assembled.
    ///
    /// @param commandLine root command line.
    private static void localizeCommands(CommandLine commandLine) {
        commandLine.setResourceBundle(Messages.bundle());
        commandLine.setAdjustLineBreaksForWideCJKCharacters(true);
        setDescription(commandLine, "cli.root.description");
        CommandLine repo = commandLine.getSubcommands().get("repo");
        setDescription(repo, "cli.repo.description");
        setDescription(repo.getSubcommands().get("update"), "cli.repo.update.description");
        CommandLine image = commandLine.getSubcommands().get("image");
        setDescription(image, "cli.image.description");
        setDescription(image.getSubcommands().get("list"), "cli.image.list.description");
        setDescription(image.getSubcommands().get("download"), "cli.image.download.description");
        CommandLine device = commandLine.getSubcommands().get("device");
        setDescription(device, "cli.device.description");
        setDescription(device.getSubcommands().get("list"), "cli.device.list.description");
        setDescription(commandLine.getSubcommands().get("flash"), "cli.flash.description");
    }

    /// Sets one command description from the active message bundle.
    ///
    /// @param commandLine command line to update.
    /// @param key message key.
    private static void setDescription(CommandLine commandLine, String key) {
        commandLine.setResourceBundle(Messages.bundle());
        commandLine.setAdjustLineBreaksForWideCJKCharacters(true);
        commandLine.getCommandSpec().usageMessage().description(Messages.get(key));
    }

    /// Prints an operation result in the selected output mode.
    ///
    /// @param result operation result.
    /// @param json whether JSON output is enabled.
    /// @return process exit code.
    private static int finish(OperationResult result, boolean json) {
        @Nullable String logFile = RuyiLogging.currentLogFileText();
        if (!result.success()) {
            LOGGER.warning(() -> "CLI operation failed. message=" + result.message());
        }
        if (json) {
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("type", result.success() ? "complete" : "error");
            output.put("success", result.success());
            output.put("message", result.message());
            if (!result.success() && logFile != null) {
                output.put("logFile", logFile);
            }
            JsonOutput.print(output);
        } else {
            PrintStream stream = result.success() ? System.out : System.err;
            stream.println(result.message());
            if (!result.success() && logFile != null) {
                stream.println("Log file: " + logFile);
            }
        }

        return result.success() ? CommandLine.ExitCode.OK : CommandLine.ExitCode.SOFTWARE;
    }

    /// Returns whether an argument flag is present.
    ///
    /// @param args command-line arguments.
    /// @param name option name.
    /// @return whether the flag is present.
    private static boolean hasFlag(String @Unmodifiable [] args, String name) {
        for (String arg : args) {
            if (name.equals(arg)) {
                return true;
            }
        }
        return false;
    }

    /// Returns an option value from CLI arguments without consuming it.
    ///
    /// @param args command-line arguments.
    /// @param name option name.
    /// @return option value, or null when absent.
    private static @Nullable String optionValue(String @Unmodifiable [] args, String name) {
        String prefix = name + "=";
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith(prefix)) {
                return arg.substring(prefix.length());
            }
            if (name.equals(arg) && i + 1 < args.length && !args[i + 1].startsWith("--")) {
                return args[i + 1];
            }
        }
        return null;
    }

    /// Returns a path option value from CLI arguments without consuming it.
    ///
    /// @param args command-line arguments.
    /// @param name option name.
    /// @return option path, or null when absent.
    private static @Nullable Path pathOptionValue(String @Unmodifiable [] args, String name) {
        @Nullable String value = optionValue(args, name);
        return value == null || value.isBlank() ? null : Path.of(value);
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
        @Option(
                names = "--json",
                description = "Emit newline-delimited JSON events.",
                descriptionKey = "cli.option.json.events")
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
                return failException(e, json);
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
        @Option(
                names = "--json",
                description = "Emit a JSON object.",
                descriptionKey = "cli.option.json.object")
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
                    System.out.println(Messages.get("cli.image.none"));
                    return CommandLine.ExitCode.OK;
                }

                for (ImageEntry image : catalog.images()) {
                    System.out.printf("%s\t%s\t%s%n", image.atom(), image.displayName(), image.strategy());
                }
                return CommandLine.ExitCode.OK;
            } catch (IOException | RuntimeException e) {
                return failException(e, json);
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
        @Option(
                names = "--json",
                description = "Emit newline-delimited JSON events.",
                descriptionKey = "cli.option.json.events")
        private boolean json;

        /// Image atom to download.
        @Parameters(
                index = "0",
                paramLabel = "ATOM",
                description = "Image atom name.",
                descriptionKey = "cli.parameter.atom")
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
                return fail(Messages.get("cli.error.missingImageAtom"), json);
            }

            try {
                ImageEntry image = services.images().findImage(requestedAtom);
                if (image == null) {
                    return fail(Messages.get("cli.error.unknownImageAtom", requestedAtom), json);
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
                return failException(e, json);
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
        @Option(
                names = "--json",
                description = "Emit a JSON object.",
                descriptionKey = "cli.option.json.object")
        private boolean json;

        /// Whether to list fastboot devices instead of block devices.
        @Option(
                names = "--fastboot",
                description = "List fastboot devices.",
                descriptionKey = "cli.option.fastboot")
        private boolean fastbootDevices;

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
                if (fastbootDevices) {
                    return listFastbootDevices();
                }

                List<BlockDevice> devices = services.devices().listDevices();
                if (json) {
                    Map<String, Object> output = new LinkedHashMap<>();
                    output.put("type", "device-list");
                    output.put("devices", deviceOutput(devices));
                    JsonOutput.print(output);
                } else if (devices.isEmpty()) {
                    System.out.println(Messages.get("cli.device.none"));
                } else {
                    for (BlockDevice device : devices) {
                        String mountPoints = deviceMountPointsText(device);
                        if (mountPoints.isEmpty()) {
                            System.out.printf("%s\t%s\t%s%n", device.id(), device.displayName(), devicePathText(device));
                        } else {
                            System.out.printf(
                                    "%s\t%s\t%s\t%s%n",
                                    device.id(),
                                    device.displayName(),
                                    devicePathText(device),
                                    mountPoints);
                        }
                    }
                }
                return CommandLine.ExitCode.OK;
            } catch (IOException | RuntimeException e) {
                return failException(e, json);
            }
        }

        /// Lists fastboot devices.
        ///
        /// @return process exit code.
        private Integer listFastbootDevices() {
            try {
                List<FastbootDevice> devices = services.fastboot().listDevices();
                if (json) {
                    Map<String, Object> output = new LinkedHashMap<>();
                    output.put("type", "fastboot-device-list");
                    output.put("devices", fastbootDeviceOutput(devices));
                    JsonOutput.print(output);
                } else if (devices.isEmpty()) {
                    System.out.println(Messages.get("cli.device.none"));
                } else {
                    for (FastbootDevice device : devices) {
                        System.out.printf("%s\t%s\t%s%n", device.id(), device.serial(), device.state());
                    }
                }
                return CommandLine.ExitCode.OK;
            } catch (IOException | RuntimeException e) {
                return failException(e, json);
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
            map.put("mountPoints", device.mountPoints());
            map.put("readOnly", device.readOnly());
            map.put("model", device.model());
            map.put("busType", device.busType());
            output.add(Collections.unmodifiableMap(map));
        }
        return List.copyOf(output);
    }

    /// Converts fastboot devices to stable JSON output maps.
    ///
    /// @param devices devices to serialize.
    /// @return immutable JSON-ready device maps.
    private static @Unmodifiable List<@Unmodifiable Map<String, @Nullable Object>> fastbootDeviceOutput(
            List<FastbootDevice> devices) {
        ArrayList<@Unmodifiable Map<String, @Nullable Object>> output = new ArrayList<>(devices.size());
        for (FastbootDevice device : devices) {
            Map<String, @Nullable Object> map = new LinkedHashMap<>();
            map.put("id", device.id());
            map.put("displayName", device.displayName());
            map.put("serial", device.serial());
            map.put("state", device.state());
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

    /// Formats device mount points for CLI output.
    ///
    /// @param device block device.
    /// @return mount point text, or empty string when none are known.
    private static String deviceMountPointsText(BlockDevice device) {
        if (device.mountPoints().isEmpty()) {
            return "";
        }
        return String.join(", ", device.mountPoints());
    }

    /// Returns whether a strategy uses fastboot.
    ///
    /// @param strategy strategy name.
    /// @return whether this is a fastboot strategy.
    private static boolean fastbootStrategy(String strategy) {
        return "fastboot-v1".equals(strategy) || "fastboot-v1(lpi4a-uboot)".equals(strategy);
    }

    /// Flashes an image to a target device.
    @Command(name = "flash", description = "Flash an image to a target device.")
    @NotNullByDefault
    private static final class FlashCommand implements Callable<Integer> {
        /// Services used by the command.
        private final AppServices services;

        /// Image atom selected from Ruyi metadata.
        @Option(
                names = "--atom",
                paramLabel = "ATOM",
                description = "Image atom name.",
                descriptionKey = "cli.option.atom")
        private @Nullable String atom;

        /// Local image path to flash.
        @Option(
                names = "--local-image",
                paramLabel = "PATH",
                description = "Local image path.",
                descriptionKey = "cli.option.localImage")
        private @Nullable Path localImage;

        /// Target block device identifier.
        @Option(
                names = "--device",
                paramLabel = "ID",
                description = "Target device id.",
                descriptionKey = "cli.option.device")
        private @Nullable String deviceId;

        /// Partition-specific target block device mappings.
        @Option(
                names = "--partition-device",
                paramLabel = "PARTITION=ID",
                description = "Partition target mapping.",
                descriptionKey = "cli.option.partitionDevice")
        private List<String> partitionDeviceSpecs = new ArrayList<>();

        /// Whether the destructive operation was explicitly confirmed.
        @Option(
                names = "--yes",
                description = "Confirm destructive writing.",
                descriptionKey = "cli.option.yes")
        private boolean yes;

        /// Whether write verification should be skipped.
        @Option(
                names = "--skip-verify",
                description = "Skip post-write verification.",
                descriptionKey = "cli.option.skipVerify")
        private boolean skipVerify;

        /// Whether command output should be JSON.
        @Option(
                names = "--json",
                description = "Emit newline-delimited JSON events.",
                descriptionKey = "cli.option.json.events")
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
                return fail(Messages.get("cli.error.refusingWithoutYes"), json);
            }

            boolean hasAtom = atom != null;
            boolean hasLocalImage = localImage != null;
            if (hasAtom == hasLocalImage) {
                return fail(Messages.get("cli.error.chooseOneImageSource"), json);
            }

            String requestedDeviceId = deviceId;
            try {
                @Unmodifiable Map<String, String> requestedPartitionDevices =
                        parsePartitionDeviceSpecs(partitionDeviceSpecs);
                @Nullable ImageEntry image = null;
                @Nullable Path requestedLocalImage = localImage;
                if (atom != null) {
                    image = services.images().findImage(atom);
                    if (image == null) {
                        return fail(Messages.get("cli.error.unknownImageAtom", atom), json);
                    }
                } else if (requestedLocalImage != null && !Files.isRegularFile(requestedLocalImage)) {
                    return fail(Messages.get("cli.error.localImageMissing", requestedLocalImage), json);
                }

                FlashTarget target;
                if (!requestedPartitionDevices.isEmpty()) {
                    if (requestedDeviceId != null) {
                        return fail(Messages.get("cli.error.deviceConflictsWithPartitionDevice"), json);
                    }
                    if (image == null || !"dd-v1".equals(image.strategy())) {
                        return fail(Messages.get("cli.error.partitionDeviceRequiresAtom"), json);
                    }
                    target = FlashTarget.blockDevices(resolvePartitionDevices(requestedPartitionDevices));
                } else if (image != null && "dd-v1".equals(image.strategy()) && image.partitionMap().size() > 1) {
                    return fail(
                            Messages.get("cli.error.missingPartitionTargets", String.join(", ", image.partitionMap().keySet())),
                            json);
                } else if (image != null && fastbootStrategy(image.strategy())) {
                    if (requestedDeviceId == null) {
                        return fail(Messages.get("cli.error.missingTargetDevice"), json);
                    }
                    @Nullable FastbootDevice fastbootDevice = services.fastboot().findDevice(requestedDeviceId);
                    if (fastbootDevice == null) {
                        return fail(Messages.get("cli.error.unknownTargetDevice", requestedDeviceId), json);
                    }
                    target = FlashTarget.fastbootDevice(fastbootDevice);
                } else {
                    if (requestedDeviceId == null) {
                        return fail(Messages.get("cli.error.missingTargetDevice"), json);
                    }
                    @Nullable BlockDevice blockDevice = services.devices().findDevice(requestedDeviceId);
                    if (blockDevice == null) {
                        return fail(Messages.get("cli.error.unknownTargetDevice", requestedDeviceId), json);
                    }
                    target = FlashTarget.blockDevice(blockDevice);
                }

                OperationResult result = services.flash().flash(
                        new FlashRequest(image, requestedLocalImage, target, !skipVerify),
                        progressReporter(json));
                return finish(result, json);
            } catch (IOException | RuntimeException e) {
                return failException(e, json);
            }
        }

        /// Parses partition target mapping options.
        ///
        /// @param specs raw option values.
        /// @return immutable partition target id map.
        private static @Unmodifiable Map<String, String> parsePartitionDeviceSpecs(List<String> specs) {
            LinkedHashMap<String, String> result = new LinkedHashMap<>();
            for (String spec : specs) {
                int delimiterIndex = spec.indexOf('=');
                if (delimiterIndex <= 0 || delimiterIndex == spec.length() - 1) {
                    throw new IllegalArgumentException(Messages.get("cli.error.invalidPartitionDevice", spec));
                }

                String partition = spec.substring(0, delimiterIndex).strip();
                String device = spec.substring(delimiterIndex + 1).strip();
                if (partition.isEmpty() || device.isEmpty()) {
                    throw new IllegalArgumentException(Messages.get("cli.error.invalidPartitionDevice", spec));
                }
                if (result.put(partition, device) != null) {
                    throw new IllegalArgumentException(Messages.get("cli.error.duplicatePartitionDevice", partition));
                }
            }
            return Collections.unmodifiableMap(result);
        }

        /// Resolves partition target device ids to block devices.
        ///
        /// @param partitionDeviceIds partition target device ids.
        /// @return immutable partition target device map.
        /// @throws IOException when device enumeration fails.
        private @Unmodifiable Map<String, BlockDevice> resolvePartitionDevices(
                @Unmodifiable Map<String, String> partitionDeviceIds) throws IOException {
            @Unmodifiable List<BlockDevice> devices = services.devices().listDevices();
            LinkedHashMap<String, BlockDevice> result = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : partitionDeviceIds.entrySet()) {
                @Nullable BlockDevice device = findBlockDevice(devices, entry.getValue());
                if (device == null) {
                    throw new IllegalArgumentException(Messages.get("cli.error.unknownTargetDevice", entry.getValue()));
                }
                result.put(entry.getKey(), device);
            }
            return Collections.unmodifiableMap(result);
        }

        /// Finds a block device by id in an already enumerated device list.
        ///
        /// @param devices device list.
        /// @param id target device id.
        /// @return matching device, or null when not found.
        private static @Nullable BlockDevice findBlockDevice(@Unmodifiable List<BlockDevice> devices, String id) {
            for (BlockDevice device : devices) {
                if (device.id().equals(id)) {
                    return device;
                }
            }
            return null;
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
                System.out.println(MAPPER.writeValueAsString(value));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}

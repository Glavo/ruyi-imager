// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.text.MessageFormat;
import java.util.Map;
import java.util.MissingResourceException;

/// Formats stable SDK diagnostic messages with optional presentation-layer localization.
@NotNullByDefault
public final class SdkMessages {
    /// English message patterns keyed by SDK diagnostic id.
    private static final @Unmodifiable Map<String, String> MESSAGES = Map.ofEntries(
            Map.entry("core.repo.updated", "Updated {0} Ruyi metadata repositories."),
            Map.entry("core.repo.usingLocal", "Using local Ruyi repo {0}."),
            Map.entry("core.repo.localMissingConfig", "Local Ruyi repo is missing config.toml: {0}"),
            Map.entry("core.repo.usingUnmanagedLocal", "Using unmanaged local Ruyi repo {0}."),
            Map.entry("core.repo.cacheNotGit", "Ruyi repo cache path exists but is not a Git checkout: {0}"),
            Map.entry("core.repo.cloning", "Cloning Ruyi repo {0}."),
            Map.entry("core.repo.cloned", "Cloned Ruyi repo {0}."),
            Map.entry("core.repo.cloneFailed", "Failed to clone Ruyi repo {0}: {1}"),
            Map.entry("core.repo.updating", "Updating Ruyi repo {0}."),
            Map.entry("core.repo.pullFailed", "Git pull did not complete successfully for repo {0}."),
            Map.entry("core.repo.updatedOne", "Updated Ruyi repo {0}."),
            Map.entry("core.repo.updateFailed", "Failed to update Ruyi repo {0}: {1}"),
            Map.entry("core.toml.parseFailed", "Failed to parse TOML file {0}:"),
            Map.entry("core.flash.imageTooLarge", "Image is larger than target device."),
            Map.entry("core.flash.selfWrite", "Refusing to flash an image onto itself."),
            Map.entry("core.flash.writing", "Writing image to target."),
            Map.entry("core.flash.verifying", "Verifying written image."),
            Map.entry("core.flash.preparingTarget", "Preparing target device {0}."),
            Map.entry("core.flash.preparedTarget", "Target device prepared: {0}."),
            Map.entry("core.flash.writingPartition", "Writing {0} image to target."),
            Map.entry("core.flash.verifyingPartition", "Verifying {0} image on target."),
            Map.entry("core.flash.verifyFailed", "Written image failed verification."),
            Map.entry("core.flash.success", "Image flashed successfully."),
            Map.entry("core.flash.refuseSystem", "Refusing to write to a system disk."),
            Map.entry("core.flash.refuseNonRemovable", "Refusing to write to a non-removable device."),
            Map.entry("core.flash.refuseUnknownTargetSize", "Refusing to write to a real block device with unknown size."),
            Map.entry("core.flash.refuseMounted", "Refusing to write to a mounted device."),
            Map.entry("core.flash.refuseMountedWithPoints", "Refusing to write to a mounted device: {0}"),
            Map.entry("core.flash.refuseReadOnly", "Refusing to write to a read-only device."),
            Map.entry("core.flash.localImageMissing", "Local image does not exist: {0}"),
            Map.entry("core.flash.noSource", "No image source was selected."),
            Map.entry("core.flash.oneTarget", "dd-v1 image requires exactly one partition mapping in this flow."),
            Map.entry("core.flash.partitionTargetsRequired", "This dd-v1 image has multiple partition mappings; specify one block target per partition. Required partitions: {0}"),
            Map.entry("core.flash.missingPartitionTarget", "Missing target device for partition: {0}"),
            Map.entry("core.flash.unknownPartitionTarget", "Partition target was provided for an unknown partition: {0}"),
            Map.entry("core.flash.duplicatePartitionTarget", "Partition target is assigned more than once: {0}"),
            Map.entry("core.flash.partitionTargetInvalid", "Partition {0} target is not writable: {1}"),
            Map.entry("core.flash.backendMissing", "Platform flash backend is not implemented yet."),
            Map.entry("core.flash.exactlyOneSource", "Exactly one image source is required."),
            Map.entry("core.flash.exactlyOneTarget", "Exactly one flash target is required."),
            Map.entry("core.flash.blockTargetRequired", "A block device target is required for this image source."),
            Map.entry("core.flash.unsupportedStrategy", "Unsupported provision strategy: {0}"),
            Map.entry("core.dd.missingExecutable", "dd-flasher executable was not found: {0}"),
            Map.entry("core.dd.interrupted", "Interrupted while running dd-flasher command: {0}"),
            Map.entry("core.dd.commandFailed", "dd-flasher exited with code {0}: {1}"),
            Map.entry("core.dd.elevationFailed", "Failed to start elevated dd-flasher command: {0}"),
            Map.entry("core.dd.writeFailed", "dd-flasher did not complete the write operation."),
            Map.entry("core.dd.invalidOutput", "Invalid dd-flasher output: {0}"),
            Map.entry("core.dd.unexpectedEvent", "Unexpected dd-flasher event type: {0}"),
            Map.entry("core.dd.unknownFailure", "Unknown dd-flasher failure."),
            Map.entry("core.dd.missingResult", "dd-flasher exited without a completion event."),
            Map.entry("core.dd.noOutput", "no output"),
            Map.entry("core.fastboot.targetRequired", "A fastboot target is required for this image."),
            Map.entry("core.fastboot.missingExecutable", "fastboot executable was not found: {0}"),
            Map.entry("core.fastboot.interrupted", "Interrupted while running fastboot command: {0}"),
            Map.entry("core.fastboot.outputInterrupted", "Interrupted while reading fastboot output."),
            Map.entry("core.fastboot.timeout", "fastboot command timed out: {0}"),
            Map.entry("core.fastboot.commandFailed", "fastboot exited with code {0}: {1}\n{2}"),
            Map.entry("core.fastboot.commandSucceeded", "fastboot command completed: {0}"),
            Map.entry("core.fastboot.noOutput", "no output"),
            Map.entry("core.fastboot.noPartitions", "Image has no fastboot partitions."),
            Map.entry("core.fastboot.missingPartition", "Image is missing fastboot partition: {0}"),
            Map.entry("core.fastboot.unsupportedStrategy", "Unsupported fastboot strategy: {0}"),
            Map.entry("core.fastboot.flashingPartition", "Flashing fastboot partition {0}."),
            Map.entry("core.fastboot.sendingPartition", "Sending fastboot partition {0}."),
            Map.entry("core.fastboot.sendingSparsePartition", "Sending fastboot partition {0} chunk {1}/{2}."),
            Map.entry("core.fastboot.writingPartition", "Writing fastboot partition {0}."),
            Map.entry("core.fastboot.loadingLpi4aUboot", "Loading LPi4A U-Boot through fastboot."),
            Map.entry("core.fastboot.lpi4aRamTargetMissing", "The selected fastboot device does not accept the LPi4A RAM U-Boot handoff. Put the board into BootROM/download fastboot mode and select the U-Boot image that matches the board RAM size.\n{0}"),
            Map.entry("core.fastboot.rebooting", "Rebooting fastboot device."),
            Map.entry("core.fastboot.spacemit.stageFsbl", "Staging SpacemiT K1 FSBL through fastboot."),
            Map.entry("core.fastboot.spacemit.continueFsbl", "Continuing to SpacemiT K1 FSBL."),
            Map.entry("core.fastboot.spacemit.waitFsbl", "Waiting for SpacemiT K1 FSBL handoff."),
            Map.entry("core.fastboot.spacemit.stageUboot", "Staging SpacemiT K1 U-Boot through fastboot."),
            Map.entry("core.fastboot.spacemit.continueUboot", "Continuing to SpacemiT K1 U-Boot."),
            Map.entry("core.fastboot.spacemit.waitUboot", "Waiting for SpacemiT K1 U-Boot handoff."),
            Map.entry("core.fastboot.spacemit.handoffInterrupted", "Interrupted during SpacemiT K1 fastboot handoff."),
            Map.entry("core.fastboot.waitingReconnect", "Waiting for fastboot device {0} to reconnect."),
            Map.entry("core.fastboot.reconnected", "fastboot device reconnected: {0}"),
            Map.entry("core.fastboot.reconnectTimedOut", "Timed out waiting for fastboot device to reconnect: {0}"),
            Map.entry("core.fastboot.reconnectAmbiguous", "Multiple fastboot devices are visible after handoff ({0}); refusing to continue without a unique target."),
            Map.entry("core.fastboot.reconnectInterrupted", "Interrupted while waiting for fastboot reconnect."),
            Map.entry("core.fastboot.success", "Fastboot flashing completed successfully."),
            Map.entry("core.device.windowsInterrupted", "Interrupted while enumerating Windows disks."),
            Map.entry("core.device.windowsTimedOut", "Timed out while enumerating Windows disks."),
            Map.entry("core.device.powershellExit", "PowerShell exited with code {0}."),
            Map.entry("core.device.windowsEnumerationFailed", "Failed to enumerate Windows disks: {0}"),
            Map.entry("core.device.windowsPrepareInterrupted", "Interrupted while preparing Windows disk for writing."),
            Map.entry("core.device.windowsPrepareTimedOut", "Timed out while preparing Windows disk for writing."),
            Map.entry("core.device.windowsPrepareFailed", "Failed to prepare Windows disk {0} for writing: {1}"),
            Map.entry("core.device.enumerationInterrupted", "Interrupted while enumerating {0} disks."),
            Map.entry("core.device.enumerationTimedOut", "Timed out while enumerating {0} disks."),
            Map.entry("core.device.commandExit", "{0} exited with code {1}."),
            Map.entry("core.device.enumerationFailed", "Failed to enumerate {0} disks: {1}"),
            Map.entry("core.download.cached", "Using cached {0}"),
            Map.entry("core.download.manual", "Distfile requires manual download: {0}"),
            Map.entry("core.download.manualWithInstructions", "Distfile requires manual download: {0}\n{1}"),
            Map.entry("core.download.noUrls", "No source URLs are available for distfile: {0}"),
            Map.entry("core.download.interrupted", "Interrupted while downloading {0}."),
            Map.entry("core.download.failed", "Failed to download distfile {0}."),
            Map.entry("core.download.unsupportedScheme", "Unsupported distfile URL scheme: {0}"),
            Map.entry("core.download.downloading", "Downloading {0}"),
            Map.entry("core.download.unexpectedStatus", "Unexpected HTTP status {0} for {1}"),
            Map.entry("core.download.verifyFailed", "Downloaded distfile failed verification: {0}"),
            Map.entry("core.download.missingDigest", "Missing digest algorithm: {0}"),
            Map.entry("core.download.imageNoDistfiles", "Image has no distfiles: {0}"),
            Map.entry("core.download.imageComplete", "Downloaded {0}"),
            Map.entry("core.image.invalidAtom", "Invalid Ruyi image atom: {0}"),
            Map.entry("core.image.invalidSemVer", "Invalid SemVer version: {0}"),
            Map.entry("core.materialize.countMismatch", "Downloaded distfile count does not match image metadata."),
            Map.entry("core.materialize.materializing", "Materializing {0}"),
            Map.entry("core.materialize.noPartitionMap", "Image has no partition map: {0}"),
            Map.entry("core.materialize.partitionMissing", "Image partition artifact is missing: {0}"),
            Map.entry("core.materialize.interrupted", "Interrupted while materializing {0}."),
            Map.entry("core.materialize.unsupportedMethod", "Unsupported Ruyi unpack method {0} for distfile {1}. Supported methods: {2}. Downloaded file: {3}. Artifact directory: {4}. Extract the file manually into the artifact directory, or update package metadata to use a supported unpack method."),
            Map.entry("core.materialize.compressorFailed", "Failed to open {0} compressed stream for {1}. The file may be corrupt or the compressor provider may be unavailable."),
            Map.entry("core.materialize.archiveEscape", "{0} entry escapes artifact directory: {1}"),
            Map.entry("core.materialize.partitionEscape", "Partition path escapes artifact directory: {0}"),
            Map.entry("core.materialize.invalidDeb", "Invalid Debian package or missing data.tar member: {0}"),
            Map.entry("core.materialize.invalidPrefix", "Invalid archive prefix for distfile {0}: {1}")
    );

    /// Optional resolver supplied by an application layer with localized resources.
    private static volatile @Nullable MessageResolver resolver;

    /// Prevents construction.
    private SdkMessages() {
    }

    /// Sets the optional presentation-layer message resolver.
    ///
    /// @param value resolver to use, or null to use SDK English defaults.
    public static void setResolver(@Nullable MessageResolver value) {
        resolver = value;
    }

    /// Formats an SDK diagnostic message.
    ///
    /// @param key stable diagnostic key.
    /// @param arguments message arguments.
    /// @return formatted diagnostic message.
    public static String get(String key, Object @Unmodifiable ... arguments) {
        @Nullable MessageResolver currentResolver = resolver;
        if (currentResolver != null) {
            try {
                @Nullable String message = currentResolver.resolve(key, arguments);
                if (message != null) {
                    return message;
                }
            } catch (IllegalArgumentException | MissingResourceException _) {
                // Keep SDK diagnostics available even when application resources are incomplete.
            }
        }

        return formatDefault(key, arguments);
    }

    /// Formats a message with the SDK English default table.
    ///
    /// @param key stable diagnostic key.
    /// @param arguments message arguments.
    /// @return formatted default diagnostic message.
    private static String formatDefault(String key, Object @Unmodifiable ... arguments) {
        String pattern = MESSAGES.getOrDefault(key, key);
        return arguments.length == 0 ? pattern : MessageFormat.format(pattern, arguments);
    }

    /// Resolves SDK diagnostic messages for a presentation layer.
    @FunctionalInterface
    @NotNullByDefault
    public interface MessageResolver {
        /// Resolves an SDK diagnostic message.
        ///
        /// @param key stable diagnostic key.
        /// @param arguments message arguments.
        /// @return formatted localized message, or null to use the SDK default.
        @Nullable String resolve(String key, Object @Unmodifiable ... arguments);
    }
}

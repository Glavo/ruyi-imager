# Plans

## Scope

- Build a Java 25 CLI/JavaFX GUI for catalog and local-image flashing without invoking the external `ruyi` command.
- Share metadata, download, materialization, and flashing services between the CLI and GUI.
- Support `dd-v1`, `fastboot-v1`, `fastboot-v1(lpi4a-uboot)`, and SpacemiT K1 eMMC fastboot workflows.

## Current Status

- Modules: `:sdk`, `:app`, `:dd-flasher`, and `:launcher`.
- Catalog support includes Ruyi repository configuration, ordered remote fallback, branch switching, image combinations, and cached metadata. Repository update attempts invalidate cached metadata even after partial failure; mixed DD/Fastboot combinations are classified as unsupported.
- Downloads support system proxies, resumable transfers, bounded body waits, SHA-256/SHA-512 verification, and manually supplied fetch-restricted files. Materialization detects archive formats by their final filename suffix and handles supported archives and concatenated compressor members with path and resource limits.
- Raw writes use the Rust helper for target inspection, volume handling, progress, cancellation, and optional verification. The SDK refreshes target identity and safety flags before writing.
- Fastboot supports partition flashing, board-specific bootloader handoffs, sparse progress, duplicate-serial rejection, and propagation of the resolved device identity between components. All combo component strategies, partition paths, and strategy-required partitions are checked before the first flash command.
- The GUI provides catalog/local image and target selection, confirmation, progress, cancellation, metadata updates, language settings, and log access. Background operations share busy state.
- Packaging includes JLink distributions, bundled helpers, Windows setup executables, Linux Debian packages, macOS archives, and nightly/versioned release workflows. Linux ARM64 and RISC-V 64 require fastboot on `PATH`.
- Application updates are disabled for 1.0 through `ruyiApplicationUpdatesEnabled=false`. Local/HTTPS manifests, compatibility selection, verified installer caching, and installer handoff remain available in explicitly enabled builds; see [docs/updates.md](docs/updates.md).

## Remaining Work

- Run read-only Linux/macOS block-device enumeration smoke tests on real machines.
- Continue real Windows removable-device validation for raw writes, volume lock/dismount, cancellation, and verification.
- Validate non-host `dd-flasher` release builds with an appropriate linker or `cross` environment.
- After real-device validation, remove or downgrade diagnostics that are only useful for troubleshooting.
- Before enabling application updates, validate installation using real Windows setup, Debian, and macOS package artifacts.
- Before enabling application updates, publish immutable packages and manifests over HTTPS, automate manifest publication, and configure the public default endpoint.

## Known Limits

- Full Linux/macOS release packaging from Windows depends on a working non-host `dd-flasher` toolchain; the reviewed Windows environment lacked the required Linux linker/Docker setup.
- Automated tests and local fixtures do not replace the outstanding physical-device and installer validation.

# Ruyi Imager

Ruyi Imager is a desktop image writer for RISC-V boards supported by the [RuyiSDK ecosystem](https://github.com/ruyisdk/ruyi).

## Highlights

- Browse catalog images by vendor, board, and operating system, or use a local image.
- Download, verify, unpack, and flash catalog images without installing the `ruyi` CLI.
- Handle raw-device and Fastboot workflows, including LPi4A U-Boot handoff and SpacemiT K1 eMMC provisioning.
- Re-identify the selected device before destructive writes, reject known unsafe targets, and verify written data by default.
- Use the same flashing engine from an English or Simplified Chinese GUI and a scriptable CLI with NDJSON progress events.
- Run on Windows, Linux, and macOS with a bundled Java runtime and platform tools where available.

## Download

Builds are published on [GitHub Releases](https://github.com/Glavo/ruyi-imager/releases).

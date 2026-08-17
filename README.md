![Ruyi Imager](resources/ruyi-logo-128.png)

# Ruyi Imager

Ruyi Imager is a desktop and command-line image writer for boards supported by the [RuyiSDK ecosystem](https://github.com/ruyisdk/ruyi).

## Highlights

- Browse catalog images by vendor, board, and operating system, or use a local image.
- Download, verify, unpack, and flash catalog images without installing the `ruyi` CLI.
- Handle raw-device and Fastboot workflows, including LPi4A U-Boot handoff and SpacemiT K1 eMMC provisioning.
- Re-identify the selected device before destructive writes, reject known unsafe targets, and verify written data by default.
- Use the same flashing engine from an English or Simplified Chinese GUI and a scriptable CLI with NDJSON progress events.
- Run on Windows, Linux, and macOS with a bundled Java runtime and platform tools where available.

## Download

Builds are published on [GitHub Releases](https://github.com/Glavo/ruyi-imager/releases):

- Windows x86-64: setup `.exe` or portable `.zip`
- Linux x86-64 and AArch64: `.deb` or `.tar.gz`
- macOS x86-64 and Apple silicon: `.tar.gz`

## Safety

Writing an image destroys data on the selected target. Ruyi Imager checks device identity and blocks known system, non-removable, or read-only disks where the operating system exposes that information. Always confirm the displayed device name and capacity before continuing.

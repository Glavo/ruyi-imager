# Plans

## Java 25 Ruyi Imager CLI/GUI

### Goal

- Build a Java 25 CLI/JavaFX GUI image flashing application for catalog images and local images.
- Reuse Ruyi catalog semantics for metadata, downloads, verification, artifact materialization, flashing, logging, and packaging without invoking the external `ruyi` command.
- Support `dd-v1`, `fastboot-v1`, `fastboot-v1(lpi4a-uboot)`, and SpacemiT K1 eMMC fastboot flows with testable SDK, app, helper, and packaging modules.

### Current Status

- The project is split into `:sdk`, `:app`, `:dd-flasher`, and `:launcher`; CLI and GUI share the SDK service graph.
- Ruyi metadata, `image-combo`, distfile download/cache, checksum verification, artifact materialization, and system proxy discovery are implemented. In `Asia/Shanghai`, the default metadata remote uses the ISCAS mirror; user config can still override remote, branch, or local repository.
- Distfile and artifact extraction paths are hardened against unsafe names, cache corruption, path escape, conflicting declarations, and missing single-partition ZIP artifacts.
- `dd-flasher` handles destructive raw writes, declared byte limits, same-helper write/verify, target display-name validation, and NDJSON progress reporting.
- Before destructive `dd-v1` writes, SDK/helper paths re-enumerate and validate target id, path, hardware identity, size, model, bus, and removable status.
- Windows raw physical-drive writes lock/dismount related volumes inside `dd-flasher`; Windows UAC uses Java FFM `ShellExecuteExW` instead of a PowerShell launcher.
- Linux `pkexec` elevated `dd-flasher` reads NDJSON progress directly from stdout; Windows UAC and macOS administrator script paths still use shared temporary event logs.
- Windows/Linux/macOS block-device and fastboot enumeration are implemented with concurrent stdout/stderr draining.
- Linux and macOS mounted removable targets are automatically unmounted before writing, then re-enumerated before destructive access.
- Fastboot flows cover ordinary partition flashing, LPi4A/Meles U-Boot handoff, SpacemiT K1 stage/continue, sparse progress parsing, duplicate serial rejection, and post-handoff ambiguity checks.
- GUI supports catalog/local image selection, automatic metadata update before first manufacturer selection, target selection, safety confirmation, progress, cancellation, log path display, language switching, Chinese vendor display names, window icons, and short progress status text without trailing full stops.
- Packaging supports bundled fastboot, bundled `dd-flasher`, Windows Rust native launchers, JLink runtime images, Debian packages, WiX MSI packages, WiX Burn setup executables, and nightly release workflow. Windows JLink packages are `.zip`; Linux/macOS packages are `.tar.gz` with explicit Unix executable modes for launchers, JDK binaries, `jspawnhelper`, fastboot, and `dd-flasher`. Fastboot verification/extraction, JLink runtime and launcher generation, Debian package metadata/assembly, WiX MSI source/build orchestration, and WiX Burn bundle source/build orchestration are implemented as Java tasks/helpers in `buildSrc`; WiX MSI packages default to per-user installation under `LocalAppDataFolder` and include a directory selection UI. Windows setup executables use a single Burn package with a custom no-license bootstrapper theme, embedded bootstrapper UI localization payloads, a bootstrapper window titlebar icon payload, and a separate display version variable for full project versions that cannot be stored in WiX numeric version fields. Linux nightly builds publish `.deb` packages, Windows nightly builds publish setup `.exe` bundles, and commit-based nightly artifacts use a `1.0.0+nightly.<short-sha>` project version.

### Remaining

- Run read-only Linux/macOS block-device enumeration smoke tests on real machines.
- Continue real Windows removable-device validation for raw physical-drive write, volume lock/dismount, cancellation, and verification.
- Install and validate linker or `cross` support for non-host `dd-flasher` release targets; the current Windows environment lacks the Linux linker/Docker setup.
- After the next real-device pass, remove or downgrade any diagnostics that are still only useful for troubleshooting.

### Recent Verification

- `./gradlew -g .gradle-user-home :app:compileJava`
- `./gradlew -g .gradle-user-home :app:prepareBundledFastboot --rerun-tasks`
- `./gradlew -g .gradle-user-home "-Pjlink.jdk.platform=linux-x86_64" :app:jlinkRuntime --rerun-tasks`
- `./gradlew -g .gradle-user-home "-Pjlink.jdk.platform=linux-x86_64" :app:writeJlinkLaunchers :app:writeJlinkDebMetadata :app:jlinkDeb -x :app:installJlinkDist --rerun-tasks`
- `./gradlew -g .gradle-user-home "-Pjlink.jdk.platform=linux-x86_64" :app:jlinkArchive -x :app:installJlinkDist --rerun-tasks`
- `./gradlew -g .gradle-user-home "-Pjlink.jdk.platform=linux-x86_64" :app:help --task :app:jlinkArchive`
- `./gradlew -g .gradle-user-home "-Pjlink.jdk.platform=windows-x86_64" :app:help --task :app:jlinkArchive`
- Linux `.tar.gz` archive mode check using a minimal generated JLink image: executable entries are `0755`; regular files are `0644`.
- `./gradlew -g .gradle-user-home "-Pjlink.jdk.platform=linux-x86_64" :app:jlinkDeb -x :app:installJlinkDist --rerun-tasks`
- Debian package structure check: ar members are `debian-binary`, `control.tar.gz`, and `data.tar.gz`; control metadata and data archive modes are valid.
- `./gradlew -g .gradle-user-home "-Pjlink.jdk.platform=windows-x86_64" :app:writeJlinkWixSource --rerun-tasks`
- `./gradlew -g .gradle-user-home "-Pjlink.jdk.platform=windows-x86_64" :app:help --task :app:jlinkMsi`
- `./gradlew -g .gradle-user-home "-Pjlink.jdk.platform=windows-x86_64" :app:jlinkMsi --dry-run`
- `./gradlew -g .gradle-user-home "-Pjlink.jdk.platform=windows-x86_64" :app:help --task :app:jlinkSetupExe`
- `./gradlew -g .gradle-user-home "-Pjlink.jdk.platform=windows-x86_64" :app:jlinkSetupExe --dry-run`
- `./gradlew -g .gradle-user-home "-Pjlink.jdk.platform=windows-x86_64" :app:writeJlinkWixBundleSource -x :app:jlinkMsi --rerun-tasks`
- `./gradlew -g .gradle-user-home :buildSrc:compileJava --rerun-tasks`
- Generated WiX source XML smoke check: package metadata, main feature, application icon, GUI shortcut, per-user scope, custom no-license InstallDir UI, `WIXUI_INSTALLDIR`, architecture-specific path validation, Browse dialog OK events, and Exit dialog Finish event are present.
- Generated WiX Burn source XML smoke check: bundle metadata, WixStdBA with custom no-license theme, setup icon, bootstrapper UI logo, default install folder variable, default English localization and Simplified Chinese LCID localization payload, compressed embedded MSI package, hidden MSI ARP entry, and `INSTALLFOLDER` MSI property forwarding are present.
- WiX bootstrapper theme/localization XML check: theme, default localization, Simplified Chinese localization, generated MSI source, and generated setup source parse as XML; all `#(loc.*)` theme references exist in both localization files.
- `./gradlew -g .gradle-user-home -q properties`
- `./gradlew -g .gradle-user-home -q "-Pruyi.version=1.0.0+nightly.3921d84" properties`
- `./gradlew -g .gradle-user-home "-Pruyi.version=1.0.0+nightly.3921d84" "-Pjlink.jdk.platform=windows-x86_64" :app:jlinkArchive :app:jlinkMsi :app:jlinkSetupExe --dry-run`
- `./gradlew -g .gradle-user-home "-Pruyi.version=1.0.0+nightly.3921d84" "-Pjlink.jdk.platform=linux-x86_64" :app:jlinkArchive :app:jlinkDeb --dry-run`
- `./gradlew -g .gradle-user-home :buildSrc:compileJava`
- `./gradlew -g .gradle-user-home "-Pruyi.version=1.0.0+nightly.3921d84" "-Pjlink.jdk.platform=windows-x86_64" :app:writeJlinkWixBundleSource -x :app:jlinkMsi --rerun-tasks`
- WiX Burn display-version XML check: `Bundle/@Version` remains numeric, `RuyiImagerDisplayVersion` contains the full nightly version, and default and Simplified Chinese setup localization use the display version variable.
- `./gradlew -g .gradle-user-home "-Pruyi.version=1.0.0+nightly.3921d84" "-Pjlink.jdk.platform=windows-x86_64" :app:jlinkSetupExe --dry-run`
- `./gradlew -g .gradle-user-home "-Pjlink.jdk.platform=windows-x86_64" :app:writeJlinkWixBundleSource -x :app:jlinkMsi --rerun-tasks`
- WiX Burn window-icon XML check: the bootstrapper theme references `icon.ico`, and the generated setup source embeds `icon.ico` from `resources/ruyi-logo.ico` as a bootstrapper application payload.
- `./gradlew -g .gradle-user-home "-Pjlink.jdk.platform=windows-x86_64" :app:jlinkSetupExe --dry-run`
- `./gradlew -g .gradle-user-home :sdk:test --tests org.glavo.ruyi.imager.core.repo.RuyiRepositoryStoreTest --tests org.glavo.ruyi.imager.core.repo.RuyiRepositoryServiceTest`
- `./gradlew -g .gradle-user-home :app:compileJava :app:compileTestJava`
- `./gradlew -g .gradle-user-home :app:processResources`
- GUI short-status punctuation check: no `gui.progress.*` or `gui.status.*` localization values end with `.` or `。`.
- `git diff --check`

### Known Limits

- Windows CIM disk enumeration may be denied inside the Codex sandbox and needs out-of-sandbox read-only verification.
- Full Linux/macOS release packaging from Windows still depends on a working non-host `dd-flasher` toolchain.

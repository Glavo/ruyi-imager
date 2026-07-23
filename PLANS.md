# Plans

## Java 25 Ruyi Imager CLI/GUI

### Goal

- Build a Java 25 CLI/JavaFX GUI image flashing application for catalog images and local images.
- Reuse Ruyi catalog semantics for metadata, downloads, verification, artifact materialization, flashing, logging, and packaging without invoking the external `ruyi` command.
- Support `dd-v1`, `fastboot-v1`, `fastboot-v1(lpi4a-uboot)`, and SpacemiT K1 eMMC fastboot flows with testable SDK, app, helper, and packaging modules.

### Current Status

- The project is split into `:sdk`, `:app`, `:dd-flasher`, and `:launcher`; CLI and GUI share the SDK service graph.
- Ruyi metadata, `image-combo`, distfile download/cache, checksum verification, artifact materialization, and system proxy discovery are implemented. Automatic distfile downloads require SHA-256 or SHA-512 metadata; manually supplied fetch-restricted files remain supported. In `Asia/Shanghai`, the default metadata remote uses the ISCAS mirror; user config can still override remote, branch, or local repository.
- Distfile and artifact extraction paths are hardened against unsafe names, cache corruption, path escape, conflicting declarations, missing single-partition ZIP artifacts, excessive archive entry counts, oversized entries, aggregate expansion, filesystem exhaustion, unbounded TAR parser metadata, invalid TAR checksums, and unsupported sparse TAR extensions.
- `dd-flasher` handles destructive raw writes, declared byte limits, same-helper write/verify, target display-name validation, NDJSON progress reporting, and post-elevation target inspection.
- Before destructive `dd-v1` writes, the SDK refreshes and validates target id, path, hardware identity, size, model, bus, and removable status. Windows Storage Management boot/system flags and Linux `/`, `/boot`, `/boot/efi`, and `/efi` mounts protect system disks. The elevated helper independently rechecks whole-device type, capacity, removable/system state, available read-only/model/bus fields, and overlapping hardware identity before opening the target.
- Windows raw physical-drive writes lock/dismount related volumes inside `dd-flasher`; Windows UAC uses Java FFM `ShellExecuteExW` instead of a PowerShell launcher.
- Linux `pkexec` elevated `dd-flasher` reads NDJSON progress directly from stdout; Windows UAC and macOS administrator script paths still use shared temporary event logs.
- Windows/Linux/macOS block-device and fastboot enumeration are implemented with concurrent stdout/stderr draining.
- Linux and macOS mounted removable targets are automatically unmounted before writing, then re-enumerated before destructive access.
- Fastboot flows cover ordinary partition flashing, LPi4A/Meles U-Boot handoff, SpacemiT K1 stage/continue, sparse progress parsing, duplicate serial rejection, and post-handoff ambiguity checks.
- Logging is initialized before application-directory and service diagnostics. Informational CLI commands keep standard error clean, and GUI launch failures retain bootstrap logging until the failure has been recorded.
- GUI supports catalog/local image selection, automatic metadata updates when catalog data is missing or older than 24 hours, target selection, safety confirmation, progress, cancellation, log path display, a settings dialog for language, application update channel and automatic-check policy, and manual metadata updates, Chinese vendor display names, window icons, and short progress status text without trailing full stops. Local application update manifests support stable/nightly releases, WiX Burn-compatible precedence for one to four numeric components and prerelease identifiers, channel metadata independent from prerelease conventions, per-platform installer packages, exact skipped-release state, a 24-hour startup check interval, size and SHA-256 verification, content-addressed package caching, fixed Windows/Linux installer handoffs, and macOS package or disk-image handoffs.
- Packaging supports bundled fastboot, bundled `dd-flasher`, Windows Rust native launchers, JLink runtime images, Debian packages, WiX MSI packages, WiX Burn setup executables, nightly releases, and manually dispatched versioned GitHub Releases. Windows JLink packages are `.zip`; Linux/macOS packages are `.tar.gz` with explicit Unix executable modes for launchers, JDK binaries, `jspawnhelper`, fastboot, and `dd-flasher`. The universal Darwin fastboot is packaged for both macOS architectures, and both release workflows validate its x86_64 and arm64 slices using `lipo`'s input-file-first syntax; Linux ARM64 explicitly falls back to a user-provided fastboot on `PATH`. Fastboot and JLink JDK archives are verified by declared size and SHA-256 before extraction. Fastboot verification/extraction, JLink runtime and launcher generation, Debian package metadata/assembly, WiX MSI source/build orchestration, and WiX Burn bundle source/build orchestration are implemented as Java tasks/helpers in `buildSrc`; WiX MSI packages default to per-user installation under `LocalAppDataFolder`, include a directory selection UI, permit replacement when two builds share the same three-field MSI version, and require Burn for initial installation. The Burn bundle forwards the required installation marker so first-time MSI installation satisfies that policy. Windows setup executables use a single Burn package with the full project version for upgrade ordering, a custom no-license bootstrapper theme, embedded bootstrapper UI localization payloads, a bootstrapper window titlebar icon payload, and a matching display version variable. The tracked `gradle/project.properties` contains only the three-part release base; builds may supply prerelease qualifiers and build metadata independently, while the legacy full-version property remains available as an override. The formal release workflow accepts optional qualifier and metadata inputs, creates an immutable `v<version>` tag, marks qualified versions as prereleases, and refuses dispatches outside `main`. Linux releases publish `.deb` packages, Windows releases publish setup `.exe` bundles, and nightly artifacts use a `1.0.0-nightly.<UTC timestamp>.<short-sha>` project version whose fixed-width timestamp participates in project-defined ordering. Every released OS/architecture runs Gradle verification, Rust tests, formatting checks, and Clippy before artifacts can be published.

### Remaining

- Run read-only Linux/macOS block-device enumeration smoke tests on real machines.
- Continue real Windows removable-device validation for raw physical-drive write, volume lock/dismount, cancellation, and verification.
- Install and validate linker or `cross` support for non-host `dd-flasher` release targets; the current Windows environment lacks the Linux linker/Docker setup.
- After the next real-device pass, remove or downgrade any diagnostics that are still only useful for troubleshooting.
- Validate update installation with real Windows setup, Debian, and macOS package artifacts.
- Publish update manifests and packages over HTTPS and replace the local-file transport without changing the manifest or installation policy.

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
- Generated WiX source XML smoke check: package metadata, Burn-only initial-install condition, same-version major upgrades, main feature, application icon, GUI shortcut, per-user scope, custom no-license InstallDir UI, `WIXUI_INSTALLDIR`, architecture-specific path validation, Browse dialog OK events, and Exit dialog Finish event are present.
- Generated WiX Burn source XML smoke check: bundle metadata, WixStdBA with custom no-license theme, setup icon, bootstrapper UI logo, default install folder variable, default English localization and Simplified Chinese LCID localization payload, compressed embedded MSI package, hidden MSI ARP entry, and `INSTALLFOLDER` MSI property forwarding are present.
- WiX bootstrapper theme/localization XML check: theme, default localization, Simplified Chinese localization, generated MSI source, and generated setup source parse as XML; all `#(loc.*)` theme references exist in both localization files.
- `./gradlew -g .gradle-user-home -q properties`
- `./gradlew -g .gradle-user-home -q "-Pruyi.version=1.0.0-nightly.20260716T143052Z.3921d84" properties`
- `./gradlew -g .gradle-user-home "-Pruyi.version=1.0.0-nightly.20260716T143052Z.3921d84" "-Pjlink.jdk.platform=windows-x86_64" :app:jlinkArchive :app:jlinkMsi :app:jlinkSetupExe --dry-run`
- `./gradlew -g .gradle-user-home "-Pruyi.version=1.0.0-nightly.20260716T143052Z.3921d84" "-Pjlink.jdk.platform=linux-x86_64" :app:jlinkArchive :app:jlinkDeb --dry-run`
- `./gradlew -g .gradle-user-home :buildSrc:compileJava`
- WiX Burn version XML check: `Bundle/@Version` and `RuyiImagerDisplayVersion` contain the full nightly version, and default and Simplified Chinese setup localization use the display version variable.
- `./gradlew -g .gradle-user-home "-Pruyi.version=1.0.0-nightly.20260716T143052Z.3921d84" "-Pjlink.jdk.platform=windows-x86_64" :app:jlinkSetupExe --dry-run`
- `./gradlew -g .gradle-user-home "-Pjlink.jdk.platform=windows-x86_64" :app:writeJlinkWixBundleSource -x :app:jlinkMsi --rerun-tasks`
- WiX Burn window-icon XML check: the bootstrapper theme references `icon.ico`, and the generated setup source embeds `icon.ico` from `resources/ruyi-logo.ico` as a bootstrapper application payload.
- `./gradlew -g .gradle-user-home "-Pjlink.jdk.platform=windows-x86_64" :app:jlinkSetupExe --dry-run`
- `./gradlew -g .gradle-user-home :sdk:test --tests org.glavo.ruyi.imager.core.repo.RuyiRepositoryStoreTest --tests org.glavo.ruyi.imager.core.repo.RuyiRepositoryServiceTest`
- `./gradlew -g .gradle-user-home :app:compileJava :app:compileTestJava`
- `./gradlew -g .gradle-user-home :app:processResources`
- GUI short-status punctuation check: no `gui.progress.*` or `gui.status.*` localization values end with `.` or `。`.
- `cargo clippy --target x86_64-unknown-linux-gnu -- -D warnings`
- `cargo clippy --target x86_64-apple-darwin -- -D warnings`
- `./gradlew -g .gradle-user-home "-Pjlink.jdk.platform=windows-x86_64" :app:verifyJlinkJdk`
- `./gradlew -g .gradle-user-home "-Pjlink.jdk.platform=linux-x86_64" :app:verifyJlinkJdk`
- Custom `jlink.jdk.url` configuration without `jlink.jdk.sha256` fails during Gradle configuration.
- `./gradlew -g .gradle-user-home test`
- `./gradlew -g .gradle-user-home cleanTest test`
- `./gradlew -g .gradle-user-home :app:test --tests org.glavo.ruyi.imager.update.UpdateCheckerTest --tests org.glavo.ruyi.imager.update.BuildInfoTest`
- `./gradlew -g .gradle-user-home :app:run --args='check-update'`
- Local update manifest CLI check: `1.1.0` is reported as newer than `1.0.0-dev`.
- `./gradlew -g .gradle-user-home :app:test --tests org.glavo.ruyi.imager.update.* --tests org.glavo.ruyi.imager.gui.GuiPreferencesTest`
- `./gradlew -g .gradle-user-home :app:test --tests org.glavo.ruyi.imager.gui.MainWindowJavaFxSmokeTest`
- Local update manifest CLI channel check: `check-update --channel nightly` selects `1.1.0-nightly.20260716T143052Z.3921d84`.
- `./gradlew -g .gradle-user-home cleanTest check`
- `./gradlew -g .gradle-user-home :sdk:test --tests org.glavo.ruyi.imager.core.image.RuyiImageMaterializerTest`
- `./gradlew -g .gradle-user-home :app:test --tests org.glavo.ruyi.imager.MainTest --tests org.glavo.ruyi.imager.update.ApplicationVersionTest --tests org.glavo.ruyi.imager.update.UpdateCheckerTest`
- `cargo fmt --manifest-path dd-flasher/Cargo.toml --all -- --check`
- `cargo clippy --locked --manifest-path dd-flasher/Cargo.toml --all-targets -- -D warnings`
- `cargo fmt --manifest-path launcher/Cargo.toml --all -- --check`
- `cargo clippy --locked --manifest-path launcher/Cargo.toml --all-targets -- -D warnings`
- PowerShell parser check for `list-windows-block-devices.ps1` completed without syntax errors.
- Read-only Windows CIM disk enumeration completed successfully with Storage Management system flags.
- `./gradlew -g .gradle-user-home '-Pjlink.jdk.platform=windows-x86_64' :app:jlinkArchive`; packaged Windows fastboot contains `fastboot.exe`, `AdbWinApi.dll`, and `AdbWinUsbApi.dll`.
- Independent subagent reviews found no remaining device/materialization safety, Burn ordering, update packaging, or bootstrap logging issues.
- `./gradlew -g .gradle-user-home -q properties`; the tracked base resolves to `1.0.0`.
- `./gradlew -g .gradle-user-home -q "-PruyiVersionQualifier=alpha.1" "-PruyiVersionMetadata=build.42+sha" properties`; the composed version resolves to `1.0.0-alpha.1+build.42+sha`.
- `./gradlew -g .gradle-user-home -q "-Pruyi.version=2.0.0-rc.1" "-PruyiVersionQualifier=ignored" properties`; the legacy full-version override remains authoritative.
- `./gradlew -g .gradle-user-home "-PruyiVersionQualifier=alpha.1" "-Pjlink.jdk.platform=linux-x86_64" :app:writeJlinkDebControl -x :app:installJlinkDist --rerun-tasks`; generated Debian version is `1.0.0~alpha.1`.
- `./gradlew -g .gradle-user-home "-PruyiVersionQualifier=nightly.20260723T120000Z.41bb42e" "-Pjlink.jdk.platform=windows-x86_64" :app:jlinkArchive :app:jlinkMsi :app:jlinkSetupExe --dry-run`
- `./gradlew -g .gradle-user-home "-Pruyi.version=1.0.0+nightly.3921d84" "-Pjlink.jdk.platform=windows-x86_64" :app:writeJlinkWixBundleSource -x :app:jlinkMsi --rerun-tasks`
- Generated WiX Burn source XML check: the embedded `MsiPackage` forwards both `INSTALLFOLDER=[InstallFolder]` and `BURNMSIINSTALL=1`.
- GitHub Release workflow YAML, IntelliJ inspection, and ten embedded Bash script syntax checks completed without errors.
- Nightly release run `30024186791` inspection confirmed that both macOS jobs reached `jlinkArchive` and failed only because `lipo -verify_arch` parsed the trailing input path as an architecture.
- Nightly and formal release workflow YAML parse successfully after changing both macOS fastboot checks to `lipo <input_file> -verify_arch x86_64 arm64`; IntelliJ reports no workflow problems.
- `./gradlew -g .gradle-user-home -q "-PruyiVersionQualifier=alpha.1" properties`; the workflow input resolves to `1.0.0-alpha.1`.
- `./gradlew -g .gradle-user-home "-PruyiVersionQualifier=alpha.1" "-Pjlink.jdk.platform=windows-x86_64" :app:jlinkArchive :app:jlinkMsi :app:jlinkSetupExe --dry-run`
- `./gradlew -g .gradle-user-home "-PruyiVersionQualifier=alpha.1" "-Pjlink.jdk.platform=linux-x86_64" :app:jlinkArchive :app:jlinkDeb --dry-run`
- `./gradlew -g .gradle-user-home "-PruyiVersionQualifier=alpha.1" "-Pjlink.jdk.platform=macos-aarch64" :app:jlinkArchive --dry-run`
- `./gradlew -g .gradle-user-home cleanTest check`
- `git diff --check`

### Known Limits

- Full Linux/macOS release packaging from Windows still depends on a working non-host `dd-flasher` toolchain.

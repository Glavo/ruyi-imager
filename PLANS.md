# Plans

## Java 25 Ruyi Imager CLI/GUI

### Goal

- 构建 Java 25 的 CLI/JavaFX GUI 镜像刷写应用，目录镜像按 Manufacturer -> Board -> Operating System 选择，本地镜像作为并列入口。
- 从 Ruyi 语义移植镜像元数据、下载、校验、物化和刷写流程，不依赖外部 `ruyi` 命令。
- 支持 `dd-v1` 和 `fastboot-v1` 刷写，并把刷写、下载、设备枚举、日志等能力拆成可测试模块。

### Implemented

- 项目已拆分为 `:sdk`、`:app` 和 Rust `:dd-flasher`；CLI/GUI 共用 SDK service graph，i18n 和 JavaFX 资源保留在 app。
- Ruyi catalog/repo、下载缓存、校验、artifact 物化和内存 catalog cache 已接入，压缩包/ tar / Debian `data.tar*` 物化使用 `Glavo/kala-compress`。
- Distfile 下载和物化路径已加固：拒绝不安全 distfile 文件名，自动丢弃校验失败的完整 `.part`，并在干净 staging 目录物化成功后替换 artifact cache，避免路径逃逸和旧分区产物复用。
- `dd-v1`、`fastboot-v1` 和 `fastboot-v1(lpi4a-uboot)` 已接入；Rust `dd-flasher` helper 负责 raw 写入/校验并通过 NDJSON 回传进度。
- Rust `dd-flasher` 写入路径已限制最多写入声明的镜像字节数，并覆盖源文件变大时不越界写目标的回归测试。
- Java `ProcessDdImageWriter` 已并发消费 helper 诊断输出，避免异常 helper 写满 stdout/stderr 管道后卡住 CLI/GUI。
- 平台设备枚举和 Windows 目标准备命令已改为并发消费 stdout/stderr，避免外部命令输出填满管道导致误超时。
- Windows UAC、Linux `pkexec`、macOS `osascript` 提权路径已接入；已挂载目标会按平台能力进行准备或拒绝，并显示挂载点。
- Windows/Linux/macOS 块设备枚举和 fastboot 设备枚举已接入；默认隐藏当前策略不支持的目标设备。
- GUI 已完成 MaterialFX 主界面、目录/本地镜像二选一流程、渐进启用、树形 OS 分类、搜索弹窗、i18n、首次安全提醒和目标确认。
- SDK 刷写测试覆盖 fake `DdImageWriter` 编排路径，包括跳过校验、校验失败、多分区顺序和分区 target 拒绝条件，不依赖真实 helper 写目标内容。
- 日志已改用 SLF4J API，运行时接 JUL 文件后端；CLI/GUI 错误都会暴露日志路径，日志默认脱敏和截断敏感外部输出。
- 打包支持 bundled fastboot、bundled `dd-flasher`、JLink runtime 和 JLink zip；`dd-flasher` release 构建会追踪 Cargo manifest/lockfile/Rust 源码输入；`jlinkRuntime` 使用主机 JDK 25 的 `jlink` 链接目标平台 Liberica JDK `jmods`，非 RISC-V 默认内置 JavaFX modules。
- Android Platform Tools 下载已迁移到 `gradle-download-task`，支持本地缓存复用、临时文件落盘、重试和主 URL 覆盖，避免临时限流直接阻断 `jlinkZip`。
- `dd-flasher` 已提供每个发行平台的 Gradle 构建/打包任务，可通过 `ddFlasher.buildTool=cross` 切换到 `cross`；JLink 包会自动依赖与 `jlink.jdk.platform` 匹配的 helper，并只打入对应平台目录。

### Remaining

- 真实设备验证：
  - 在真实 Linux/macOS 机器上做只读块设备枚举 smoke test。
  - 在可擦写 Windows removable 设备上测试挂载目标准备流程，确认卸载卷和移除访问路径行为。
- 交叉发行包：
  - 为 `dd-flasher` 非本机目标安装并验证 linker 或 `cross` 运行环境；当前 Windows 沙箱不跳出沙箱，未配置外部交叉 linker/Docker。

### Verification

- 已通过：
  - `./gradlew -g .gradle-user-home test`
  - `./gradlew -g .gradle-user-home check`
  - `./gradlew -g .gradle-user-home :sdk:test --tests org.glavo.ruyi.imager.core.image.RuyiDistfileDownloaderTest --tests org.glavo.ruyi.imager.core.image.RuyiImageMaterializerTest`
  - `./gradlew -g .gradle-user-home :dd-flasher:test`
  - `./gradlew -g .gradle-user-home :dd-flasher:cargoBuild`
  - `./gradlew -g .gradle-user-home :sdk:test --tests org.glavo.ruyi.imager.core.flash.*`
  - `./gradlew -g .gradle-user-home :app:test --tests org.glavo.ruyi.imager.cli.CliApplicationTest.flashLocalImageWritesSimulatedTarget`
  - `cargo fmt --check` in `dd-flasher`
  - `./gradlew -g .gradle-user-home :app:jlinkRuntime --info`
  - `./gradlew -g .gradle-user-home "-Pjlink.jdk.platform=linux-x86_64" :app:jlinkRuntime --info`
  - `./gradlew -g .gradle-user-home :app:tasks --group distribution`
  - `./gradlew -g .gradle-user-home :app:jlinkZip --info`
  - `./gradlew -g .gradle-user-home :dd-flasher:tasks --group build`
  - `./gradlew -g .gradle-user-home :dd-flasher:tasks --group distribution`
  - `./gradlew -g .gradle-user-home :dd-flasher:printDdFlasherTargets`
  - `./gradlew -g .gradle-user-home "-Pjlink.jdk.platform=linux-x86_64" :app:jlinkZip --dry-run`
  - `app/build/jlink/windows-x86_64/runtime/bin/java --list-modules`
  - `app/build/jlink/linux-x86_64/runtime/release`
  - `jar tf app/build/distributions/app-1.0-SNAPSHOT-windows-x86_64-jlink.zip`
  - `git diff --check`
  - `cargo clippy --all-targets -- -D warnings` in `dd-flasher`
- 已知限制：
  - Windows CIM 磁盘枚举在 Codex 沙箱内可能被权限拒绝，需要沙箱外只读运行验证。

# Plans

## Java 25 Ruyi Imager CLI/GUI

### Goal

- 构建 Java 25 的 CLI/JavaFX GUI 镜像刷写应用，目录镜像按 Manufacturer -> Board -> Operating System 选择，本地镜像作为并列入口。
- 从 Ruyi 语义移植镜像元数据、下载、校验、物化和刷写流程，不依赖外部 `ruyi` 命令。
- 支持 `dd-v1` 和 `fastboot-v1` 刷写，并把刷写、下载、设备枚举、日志等能力拆成可测试模块。

### Implemented

- 项目已拆分为 `:sdk`、`:app` 和 Rust `:dd-flasher`；CLI/GUI 共用 SDK service graph，i18n 和 JavaFX 资源保留在 app。
- Ruyi catalog/repo、下载缓存、校验、artifact 物化和内存 catalog cache 已接入，压缩包/ tar / Debian `data.tar*` 物化使用 `Glavo/kala-compress`。
- `dd-v1`、`fastboot-v1` 和 `fastboot-v1(lpi4a-uboot)` 已接入；Rust `dd-flasher` helper 负责 raw 写入/校验并通过 NDJSON 回传进度。
- Rust `dd-flasher` 写入路径已限制最多写入声明的镜像字节数，并覆盖源文件变大时不越界写目标的回归测试。
- Java `ProcessDdImageWriter` 已并发消费 helper 诊断输出，避免异常 helper 写满 stdout/stderr 管道后卡住 CLI/GUI。
- Windows UAC、Linux `pkexec`、macOS `osascript` 提权路径已接入；已挂载目标会按平台能力进行准备或拒绝，并显示挂载点。
- Windows/Linux/macOS 块设备枚举和 fastboot 设备枚举已接入；默认隐藏当前策略不支持的目标设备。
- GUI 已完成 MaterialFX 主界面、目录/本地镜像二选一流程、渐进启用、树形 OS 分类、搜索弹窗、i18n、首次安全提醒和目标确认。
- 日志已改用 SLF4J API，运行时接 JUL 文件后端；CLI/GUI 错误都会暴露日志路径，日志默认脱敏和截断敏感外部输出。
- 打包支持 bundled fastboot、bundled `dd-flasher`、JLink runtime 和 JLink zip；`dd-flasher` release 构建会追踪 Cargo manifest/lockfile/Rust 源码输入；`jlinkRuntime` 使用主机 JDK 25 的 `jlink` 链接目标平台 Liberica JDK `jmods`，非 RISC-V 默认内置 JavaFX modules。

### Remaining

- 真实设备验证：
  - 在真实 Linux/macOS 机器上做只读块设备枚举 smoke test。
  - 在可擦写 Windows removable 设备上测试挂载目标准备流程，确认卸载卷和移除访问路径行为。
- 交叉发行包：
  - 为 `dd-flasher` 非本机目标配置 linker 或 `cross` 构建环境；当前 Windows 沙箱已安装 Rust targets，但 Windows ARM64 缺 `link.exe`，Linux RISC-V 缺 `cc`/交叉 linker。
  - 为 Android Platform Tools 下载增加 mirror/cache/retry 策略，避免 `jlinkZip` 因 Google 下载源 HTTP 429 中断。

### Verification

- 已通过：
  - `./gradlew -g .gradle-user-home test`
  - `./gradlew -g .gradle-user-home :dd-flasher:test`
  - `./gradlew -g .gradle-user-home :dd-flasher:cargoBuild`
  - `./gradlew -g .gradle-user-home :sdk:test --tests org.glavo.ruyi.imager.core.flash.*`
  - `./gradlew -g .gradle-user-home :app:test --tests org.glavo.ruyi.imager.cli.CliApplicationTest.flashLocalImageWritesSimulatedTarget`
  - `cargo fmt --check` in `dd-flasher`
  - `./gradlew -g .gradle-user-home :app:jlinkRuntime --info`
  - `./gradlew -g .gradle-user-home "-Pjlink.jdk.platform=linux-x86_64" :app:jlinkRuntime --info`
  - `app/build/jlink/windows-x86_64/runtime/bin/java --list-modules`
  - `app/build/jlink/linux-x86_64/runtime/release`
  - `git diff --check`
- 已知限制：
  - Windows CIM 磁盘枚举在 Codex 沙箱内可能被权限拒绝，需要沙箱外只读运行验证。
  - `:app:jlinkZip` 最近一次失败在 `downloadLinuxX8664Fastboot`，Google Platform Tools 返回 HTTP 429。

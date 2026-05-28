# Plans

## Java 25 Ruyi Imager CLI/GUI

### Goal

- 构建 Java 25 的 CLI/JavaFX GUI 镜像刷写应用，目录镜像按 Manufacturer -> Board -> Operating System 选择，本地镜像作为并列入口。
- 从 Ruyi 语义移植镜像元数据、下载、校验、准备镜像和刷写流程，不依赖外部 `ruyi` 命令。
- 支持 `dd-v1` 和 `fastboot-v1` 刷写，并把刷写、下载、设备枚举、日志等能力拆成可测试模块。

### Implemented

- 项目已拆分为 `:sdk`、`:app` 和 Rust `:dd-flasher`；CLI/GUI 共用 SDK service graph，i18n 和 JavaFX 资源保留在 app。
- Ruyi catalog/repo、下载缓存、校验、artifact 生成和内存 catalog cache 已接入，压缩包、tar、Debian `data.tar*` 的解包准备使用 `Glavo/kala-compress`。
- Ruyi `image-combo` 实体已接入，组合镜像会保留每个 package component 的独立刷写策略并按 Ruyi strategy priority 排序；LicheePi 4A/Meles 等带 U-Boot 组合会先执行 `fastboot-v1(lpi4a-uboot)`，再刷普通系统 fastboot 分区；同名 distfile 只允许完全相同声明去重，冲突时拒绝该 combo，避免单一 combo 下载目录错误复用文件。
- Distfile 下载和镜像准备路径已加固：拒绝不安全 distfile 文件名，自动丢弃校验失败的完整 `.part`，并在干净 staging 目录准备成功后替换 artifact cache，避免路径逃逸和旧分区产物复用。
- 刷写前会解析准备好的分区文件真实路径，并拒绝通过符号链接逃出 artifact 目录，避免被篡改 cache 或自定义 catalog 绕过词法路径检查。
- `dd-v1`、`fastboot-v1`、`fastboot-v1(lpi4a-uboot)` 和 Bianbu eMMC 使用的 `spacemit-k1-v1` 已接入；Rust `dd-flasher` helper 负责 raw 写入/校验并通过 NDJSON 回传进度，提权 helper 会接收 cancel-file 信号以支持取消时停止写入/校验；普通 fastboot 按 Ruyi 元数据 `partition_map` 顺序刷写，LicheePi 4A U-Boot handoff 在 reboot 后短暂等待并重新校验目标：serial 不变时继续限定原设备，serial 变化时会排除 handoff 前已经存在的非目标 fastboot 设备，只接受唯一新出现的 fastboot 设备，`flash ram` 缺少 RAM handoff 目标时会提示进入 BootROM/download fastboot 模式并确认 U-Boot RAM 容量匹配；fastboot 命令输出会被实时解析为发送、写入和 sparse chunk 子阶段进度，长耗时 root fastboot 刷写不会被 30 分钟超时提前杀掉，`fastboot flash` 在完整成功输出下误返回非零退出码时不会被误判失败；SpacemiT K1 fastboot 流程会先 stage/continue FSBL 和 U-Boot，再按 Ruyi 插件顺序刷写 gpt、bootinfo、fsbl、env、opensbi、uboot、bootfs、rootfs。
- Rust `dd-flasher` 写入路径已限制最多写入声明的镜像字节数，并覆盖源文件变大时不越界写目标的回归测试。
- `dd-v1` raw 写入已要求目标必须标记为 removable：GUI 默认过滤非可移动块设备，SDK 写前拒绝非 removable 目标，`dd-flasher` helper 也通过必传 wire 参数在打开目标前再次拒绝；容量未知的真实块设备会被 GUI 过滤并在 SDK 写前拒绝，文件型测试 target 仍可用于模拟写入。
- Java `ProcessDdImageWriter` 已并发消费 helper 诊断输出，避免异常 helper 写满 stdout/stderr 管道后卡住 CLI/GUI；传给 helper 的 Windows `\\.\PHYSICALDRIVE...` 目标会去掉 Java `Path` 追加的末尾分隔符，避免 raw device 打开失败。
- 平台设备枚举和 Windows 目标准备命令已改为并发消费 stdout/stderr，避免外部命令输出填满管道导致误超时。
- Windows UAC、Linux `pkexec`、macOS `osascript` 提权路径已接入；Windows UAC 启动失败会抑制 PowerShell progress CLIXML 并返回普通错误文本；已挂载目标会按平台能力进行准备或拒绝，并显示挂载点。
- Windows/Linux/macOS 块设备枚举和 fastboot 设备枚举已接入；默认隐藏当前策略不支持的目标设备。
- GUI 已完成 MaterialFX 主界面、目录/本地镜像二选一流程、渐进启用、树形 OS 分类、搜索弹窗、i18n、首次安全提醒、目标确认、窗口图标和页头 Logo；元数据更新成功后会重置镜像来源和目标设备选择；刷写期间会折叠选择流程，仅保留当前制造商、开发板、镜像和目标摘要栏，长文本摘要使用全宽行式布局避免逐字换行，并在开始刷写时预显示本次流程涉及的下载、准备镜像、准备目标、写入、校验和 fastboot 等阶段进度条，后续按后端 `ProgressEvent.stage` 更新；刷写中可从 GUI 取消，GUI 会中断后台任务、等待后台刷写流程实际退出后再恢复控件，若后台已成功完成则保留成功结果，只有中断导致的停止才显示取消提示；SDK 进度和诊断消息会在 app 层通过资源包本地化，下载相关短状态消息不带末尾句号，后台任务未知错误兜底和 fastboot 设备详情标签也会随 GUI locale 切换，CLI/GUI 不再混用英文 SDK 文本。
- SDK 刷写测试覆盖 fake `DdImageWriter` 编排路径，包括跳过校验、校验失败、多分区顺序和分区 target 拒绝条件，不依赖真实 helper 写目标内容。
- 日志已改用 SLF4J API，运行时接 JUL 文件后端；CLI/GUI 错误都会暴露日志路径，日志默认脱敏和截断敏感外部输出。
- 打包支持 bundled fastboot、bundled `dd-flasher`、Windows Rust native launcher、JLink runtime 和 JLink zip；Windows JLink 包同时提供 console subsystem 的 `ruyi-imager.exe` 作为 CLI 默认入口，以及 Windows subsystem 的 `ruyi-imager-gui.exe` 作为无黑框双击 GUI 入口，`dd-flasher`/launcher release 构建会追踪 Cargo manifest/lockfile/Rust 源码输入；`jlinkRuntime` 使用主机 JDK 25 的 `jlink` 链接目标平台 Liberica JDK `jmods`，非 RISC-V 默认内置 JavaFX modules。
- Java/Gradle 代码标识符统一使用 `DDFlasher` 作为 dd-flasher helper 的 acronym 命名；外部可执行文件名、目录名和配置属性保持 `dd-flasher`/`ddFlasher` 兼容。
- Android Platform Tools 下载已迁移到 `gradle-download-task`，fastboot 打包默认锁定到 Platform-Tools 37.0.0 的版本化归档并校验 SHA-256/大小，支持本地缓存复用、临时文件落盘、重试和主 URL/校验值覆盖，避免临时限流直接阻断 `jlinkZip`。
- Gradle `run` 会为当前平台解包 bundled fastboot、构建当前平台 `dd-flasher`，并通过 `ruyi.imager.fastboot.executable`、`ruyi.imager.ddFlasher.executable` 系统属性指向对应可执行文件，开发运行不再依赖 PATH 中预装 fastboot 或 `dd-flasher`。
- JLink 包只依赖和打入与 `jlink.jdk.platform` 匹配的 fastboot bundle；没有配置 fastboot bundle 的目标平台会跳过 bundled fastboot，普通发行包仍保留全部已配置平台的 fastboot。
- `dd-flasher` 已提供每个发行平台的 Gradle 构建/打包任务，可通过 `ddFlasher.buildTool=cross` 切换到 `cross`；JLink 包会自动依赖与 `jlink.jdk.platform` 匹配的 helper，并只打入对应平台目录。
- GitHub Actions 已接入 nightly release workflow：每天定时或手动构建 Windows x86_64、Linux x86_64/aarch64 和 macOS x86_64/aarch64 JLink zip，并更新固定 `nightly` prerelease 的说明、tag 和 assets；workflow 使用 Node 24-native 官方 action 主版本和明确 runner 标签，避免 Node 20 action/runtime 与 `*-latest` 漂移警告。

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
  - `./gradlew -g .gradle-user-home :sdk:test --tests org.glavo.ruyi.imager.core.flash.DDFlasherElevationTest --tests org.glavo.ruyi.imager.core.flash.DDFlasherExecutableLocatorTest`
  - `./gradlew -g .gradle-user-home :dd-flasher:tasks --group distribution :app:jlinkZip --dry-run`
  - `./gradlew -g .gradle-user-home :sdk:test --tests org.glavo.ruyi.imager.core.image.RuyiDistfileDownloaderTest --tests org.glavo.ruyi.imager.core.image.RuyiImageMaterializerTest`
  - `./gradlew -g .gradle-user-home :dd-flasher:test`
  - `./gradlew -g .gradle-user-home :dd-flasher:cargoBuild`
  - `./gradlew -g .gradle-user-home :sdk:test --tests org.glavo.ruyi.imager.core.flash.*`
  - `./gradlew -g .gradle-user-home :sdk:test --tests org.glavo.ruyi.imager.core.flash.LocalFlashServiceTest --tests org.glavo.ruyi.imager.core.device.WindowsBlockDevicePreparerTest`
  - `./gradlew -g .gradle-user-home :sdk:test --tests org.glavo.ruyi.imager.core.fastboot.ProcessFastbootServiceTest --tests org.glavo.ruyi.imager.core.image.RuyiImageCatalogServiceTest :app:test --tests org.glavo.ruyi.imager.gui.GuiSelectionRulesTest --tests org.glavo.ruyi.imager.cli.CliApplicationTest`
  - `./gradlew -g .gradle-user-home :sdk:test --tests org.glavo.ruyi.imager.core.flash.LocalFlashServiceTest :app:test --tests org.glavo.ruyi.imager.gui.GuiSelectionRulesTest`
  - `./gradlew -g .gradle-user-home :sdk:test --tests org.glavo.ruyi.imager.core.flash.* :app:test --tests org.glavo.ruyi.imager.gui.GuiSelectionRulesTest --tests org.glavo.ruyi.imager.cli.CliApplicationTest.flashLocalImageWritesSimulatedTarget`
  - `./gradlew -g .gradle-user-home :app:test --tests org.glavo.ruyi.imager.cli.CliApplicationTest.flashLocalImageWritesSimulatedTarget`
  - `cargo fmt --check` in `dd-flasher`
  - `cargo test` in `dd-flasher`
  - `./gradlew -g .gradle-user-home :app:jlinkRuntime --info`
  - `./gradlew -g .gradle-user-home "-Pjlink.jdk.platform=linux-x86_64" :app:jlinkRuntime --info`
  - `./gradlew -g .gradle-user-home :app:tasks --group distribution`
  - `./gradlew -g .gradle-user-home :app:prepareBundledFastboot`
  - `./gradlew -g .gradle-user-home :app:jlinkZip --info`
  - `./gradlew -g .gradle-user-home :app:jlinkZip --dry-run`
  - `./gradlew -g .gradle-user-home "-Pjlink.jdk.platform=windows-x86_64" :app:jlinkZip --dry-run`
  - `./gradlew -g .gradle-user-home :launcher:cargoTest :launcher:prepareBundledLauncherWindowsX8664`
  - `./gradlew -g .gradle-user-home "-Pjlink.jdk.platform=windows-x86_64" :app:jlinkZip`
  - `app/build/jlink/windows-x86_64/ruyi-imager/bin/ruyi-imager.exe --help` resolves to `.exe`, prints CLI help, and returns exit code 0
  - `app/build/jlink/windows-x86_64/ruyi-imager/bin/ruyi-imager.exe` PE subsystem check returns `Subsystem=3`; `ruyi-imager-gui.exe` returns `Subsystem=2`
  - `./gradlew -g .gradle-user-home "-Pjlink.jdk.platform=linux-aarch64" :app:jlinkZip --dry-run`
  - `./gradlew -g .gradle-user-home "-Pjlink.jdk.platform=macos-x86_64" :app:jlinkZip --dry-run`
  - `./gradlew -g .gradle-user-home "-Pjlink.jdk.platform=macos-aarch64" :app:jlinkZip --dry-run`
  - `./gradlew -g .gradle-user-home :app:distZip --dry-run`
  - `./gradlew -g .gradle-user-home :app:compileJava :app:processResources`
  - `./gradlew -g .gradle-user-home :app:compileJava :app:compileTestJava`
  - `./gradlew -g .gradle-user-home :app:jar`
  - `./gradlew -g .gradle-user-home :app:test --tests org.glavo.ruyi.imager.gui.MainWindowJavaFxSmokeTest`
  - `./gradlew -g .gradle-user-home :app:test --tests org.glavo.ruyi.imager.gui.MainWindowJavaFxSmokeTest --tests org.glavo.ruyi.imager.gui.GuiSelectionRulesTest`
  - `./gradlew -g .gradle-user-home :app:test --tests org.glavo.ruyi.imager.i18n.MessagesTest --tests org.glavo.ruyi.imager.cli.CliApplicationTest.flashLocalImageWritesSimulatedTarget :sdk:test --tests org.glavo.ruyi.imager.core.fastboot.ProcessFastbootServiceTest`
  - `./gradlew -g .gradle-user-home :app:test --tests org.glavo.ruyi.imager.cli.CliApplicationTest.flashErrorUsesConfiguredChineseLocale`
  - `./gradlew -g .gradle-user-home :sdk:test --tests org.glavo.ruyi.imager.core.fastboot.ProcessFastbootServiceTest`
  - `./gradlew -g .gradle-user-home :app:test --tests org.glavo.ruyi.imager.i18n.MessagesTest`
  - `./gradlew -g .gradle-user-home :app:compileJava :sdk:compileJava`
  - `./gradlew -g .gradle-user-home :app:test --tests org.glavo.ruyi.imager.i18n.MessagesTest --tests org.glavo.ruyi.imager.gui.MainWindowJavaFxSmokeTest :sdk:test --tests org.glavo.ruyi.imager.core.flash.ProcessDdImageWriterTest`
  - `./gradlew -g .gradle-user-home :dd-flasher:cargoTest`
  - `cargo fmt --check` in `dd-flasher`
  - `./gradlew -g .gradle-user-home :app:compileJava`
  - `./gradlew -g .gradle-user-home :dd-flasher:tasks --group build`
  - `./gradlew -g .gradle-user-home :dd-flasher:tasks --group distribution`
  - `./gradlew -g .gradle-user-home :dd-flasher:printDDFlasherTargets`
  - `./gradlew -g .gradle-user-home "-Pjlink.jdk.platform=linux-x86_64" :app:jlinkZip --dry-run`
  - `app/build/jlink/windows-x86_64/runtime/bin/java --list-modules`
  - `app/build/jlink/linux-x86_64/runtime/release`
  - `jar tf app/build/distributions/ruyi-imager-1.0-SNAPSHOT-windows-x86_64.zip`
  - `git diff --check`
  - `cargo clippy --all-targets -- -D warnings` in `dd-flasher`
- 已知限制：
  - Windows CIM 磁盘枚举在 Codex 沙箱内可能被权限拒绝，需要沙箱外只读运行验证。

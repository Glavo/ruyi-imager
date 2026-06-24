# Plans

## Java 25 Ruyi Imager CLI/GUI

### Goal

- 构建 Java 25 CLI/JavaFX GUI 镜像刷写应用，支持目录镜像和本地镜像。
- 复用 Ruyi catalog 语义完成镜像元数据读取、下载、校验、准备和刷写，不依赖外部 `ruyi` 命令。
- 支持 `dd-v1`、`fastboot-v1`、`fastboot-v1(lpi4a-uboot)` 和 SpacemiT K1 eMMC fastboot 流程，并把下载、设备枚举、刷写、日志和打包能力拆成可测试模块。

### Current Status

- 项目已拆分为 `:sdk`、`:app`、`:dd-flasher` 和 `:launcher`；CLI/GUI 共用 SDK service graph，GUI 资源和 i18n 保留在 app。
- Ruyi repo/catalog、`image-combo`、下载缓存、校验和 artifact materialization 已接入；组合镜像会保留 component 策略顺序，并拒绝冲突 distfile 声明。
- Distfile 和 artifact 准备路径已加固：拒绝不安全文件名，校验失败会丢弃 `.part`，使用 staging 目录原子替换 artifact cache，分区路径会做逃逸检查；单 distfile、单分区 ZIP 若默认剥路径组件后缺少目标产物，会按 `partition_map` 精确匹配根目录条目并补提取。
- 默认网络访问启用 JVM 系统代理发现；distfile 下载默认 `HttpClient` 使用默认 `ProxySelector`，仍可通过 JVM 属性覆盖。
- `dd-flasher` 负责 raw 写入/校验和 NDJSON 进度回传；写入路径限制最多写入声明镜像字节数，`write-verify` 在同一 helper 内连续完成写入和校验。
- Windows raw physical drive 写入由 `dd-flasher` 在 helper 进程内枚举相关 volume，持有 lock/dismount handle 并用 Win32 raw disk handle 写入，避免 SDK PowerShell preparer 释放锁后重新挂载。
- `dd-v1` 破坏性写入前会重新枚举并核对目标 id、path、硬件身份、容量和型号/总线信息；GUI/SDK/helper 均拒绝非 removable 真实块设备，容量未知设备会被过滤或拒绝。
- SDK 调用 `dd-flasher` 时传入目标显示名称，helper 会校验并在错误和日志中携带该名称。
- SDK elevated `dd-flasher` handoff 在 Linux `pkexec` 路径直接通过 stdout 管道读取 NDJSON 事件，避免提权 helper 打开调用方创建的 event log；Windows UAC 和 macOS administrator script 路径仍使用共享临时 event log 回传进度或错误。
- Windows UAC 已改为 Java FFM 调用 `ShellExecuteExW`，不再通过 PowerShell launcher；PowerShell 资源只保留 Windows 设备枚举脚本，JLink 包放入 `tools/powershell`，`gradlew run` 直接指向源码资源目录。
- Windows/Linux/macOS 块设备枚举和 fastboot 设备枚举已接入；平台枚举和外部命令会并发消费 stdout/stderr，避免管道阻塞。
- Linux 块设备枚举会读取 `SERIAL`、`WWN` 和 `HOTPLUG`；破坏性写入前的目标重枚举会使用 `hardwareId` 加强身份校验，`HOTPLUG` 仅在 `TRAN=usb` 时作为 `RM=0` USB 设备的 removable 补充信号。
- Linux mounted removable 目标会在写入前通过平台 preparer 自动卸载：优先使用 `udisksctl unmount -b`，失败时按挂载点回退到 `umount`，随后仍通过重新枚举确认目标状态。
- macOS 块设备枚举会从 `diskutil info -plist` 读取媒体 UUID、磁盘 UUID、设备树路径、IORegistry 名称和序列号作为 `hardwareId`，会排除 `VirtualOrPhysical=Virtual` 的虚拟盘，并把 APFS synthesized container 挂载点回填到物理盘；mounted removable 目标会在写入前通过 `diskutil unmountDisk` 自动卸载，随后仍通过重新枚举确认目标状态。
- Linux/macOS 目标准备阶段会在外部卸载命令运行期间上报不可确定进度，避免 GUI 把不可量化准备工作显示为卡在 0%。
- Fastboot 流程支持普通分区刷写、LPi4A/Meles U-Boot handoff、SpacemiT K1 stage/continue 和 sparse progress 解析；重复 serial 和 handoff 后目标歧义会被拒绝。
- GUI 已完成目录/本地镜像选择、目标选择、安全确认、刷写进度、取消、日志路径展示、语言切换、窗口图标和 JLink GUI/CLI 启动器命名；进度条旁短状态文本不使用末尾句号。
- 日志使用 SLF4J API 和 JUL 文件后端；CLI/GUI 错误会暴露日志路径，敏感外部输出默认脱敏和截断。
- 打包支持 bundled fastboot、bundled `dd-flasher`、Windows Rust native launcher、JLink runtime 和 nightly release workflow；Windows JLink 包使用 `.zip`，Linux/macOS JLink 包使用 `.tar.gz`；Windows 包提供 `ruyi-imager.exe` GUI 入口和 `ruyi-imager-cli.exe` CLI 入口，并静态链接 MSVC CRT 以避免缺少 `VCRUNTIME140.dll`。
- Linux/macOS JLink `.tar.gz` 包会显式设置 Unix 可执行权限，确保启动脚本、JDK launcher、`jspawnhelper`、bundled fastboot 和 `dd-flasher` 可执行。

### Remaining

- 在真实 Linux/macOS 机器上做只读块设备枚举 smoke test。
- 在可擦写 Windows removable 设备上继续验证 raw physical drive 写入、volume lock/dismount、取消和校验行为。
- 为 `dd-flasher` 非本机发行目标安装并验证 linker 或 `cross` 运行环境；当前 Windows 沙箱未配置外部交叉 linker/Docker。
- 在下一轮真实设备验证后，清理仍只用于排查的日志或诊断输出，避免发行版日志过噪。

### Recent Verification

- `./gradlew -g .gradle-user-home "-Pjlink.jdk.platform=linux-x86_64" :app:jlinkArchive --dry-run`
- `./gradlew -g .gradle-user-home "-Pjlink.jdk.platform=macos-aarch64" :app:jlinkArchive --dry-run`
- `./gradlew -g .gradle-user-home "-Pjlink.jdk.platform=windows-x86_64" :app:jlinkArchive --dry-run`
- `./gradlew -g .gradle-user-home "-Pjlink.jdk.platform=linux-x86_64" :app:help --task :app:jlinkArchive`
- `./gradlew -g .gradle-user-home "-Pjlink.jdk.platform=windows-x86_64" :app:help --task :app:jlinkArchive`
- `./gradlew -g .gradle-user-home :sdk:test --tests org.glavo.ruyi.imager.core.image.RuyiImageMaterializerTest`
- `./gradlew -g .gradle-user-home :sdk:test --tests org.glavo.ruyi.imager.core.device.MacOSBlockDeviceServiceTest --tests org.glavo.ruyi.imager.core.flash.MacOSBlockDevicePreparerTest --tests org.glavo.ruyi.imager.core.flash.LinuxBlockDevicePreparerTest :app:test --tests org.glavo.ruyi.imager.gui.GuiSelectionRulesTest`
- `./gradlew -g .gradle-user-home :sdk:test --tests org.glavo.ruyi.imager.core.device.MacOSBlockDeviceServiceTest --tests org.glavo.ruyi.imager.core.flash.MacOSBlockDevicePreparerTest --tests org.glavo.ruyi.imager.core.flash.LocalFlashServiceTest :app:test --tests org.glavo.ruyi.imager.gui.GuiSelectionRulesTest`
- `./gradlew -g .gradle-user-home :sdk:test --tests org.glavo.ruyi.imager.core.flash.LinuxBlockDevicePreparerTest --tests org.glavo.ruyi.imager.core.flash.LocalFlashServiceTest --tests org.glavo.ruyi.imager.core.device.LinuxBlockDeviceServiceTest :app:test --tests org.glavo.ruyi.imager.gui.GuiSelectionRulesTest --tests org.glavo.ruyi.imager.i18n.MessagesTest`
- `./gradlew -g .gradle-user-home :sdk:test --tests org.glavo.ruyi.imager.core.device.LinuxBlockDeviceServiceTest --tests org.glavo.ruyi.imager.core.flash.DDFlasherElevationTest --tests org.glavo.ruyi.imager.core.flash.ProcessDdImageWriterTest`
- `./gradlew -g .gradle-user-home :sdk:compileJava :sdk:compileTestJava`
- `./gradlew -g .gradle-user-home :sdk:test --tests org.glavo.ruyi.imager.core.flash.DDFlasherElevationTest --tests org.glavo.ruyi.imager.core.flash.ProcessDdImageWriterTest --tests org.glavo.ruyi.imager.core.flash.LocalFlashServiceTest --tests org.glavo.ruyi.imager.core.device.WindowsBlockDeviceServiceTest`
- `./gradlew -g .gradle-user-home :app:compileJava :app:compileTestJava :app:test --tests org.glavo.ruyi.imager.i18n.MessagesTest`
- PowerShell parser check for `sdk/src/main/resources/org/glavo/ruyi/imager/core/powershell/*.ps1`
- `git diff --check`

### Known Limits

- Windows CIM 磁盘枚举在 Codex 沙箱内可能被权限拒绝，需要在沙箱外只读验证。
- 当前验证记录只保留最近一组代表性命令；完整历史可从 git 和 CI 记录追溯。

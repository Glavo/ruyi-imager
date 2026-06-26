# Plans

## Java 25 Ruyi Imager CLI/GUI

### Goal

- 构建 Java 25 CLI/JavaFX GUI 镜像刷写应用，支持目录镜像和本地镜像。
- 复用 Ruyi catalog 语义完成元数据、下载、校验、artifact materialization、刷写、日志和打包，不依赖外部 `ruyi` 命令。
- 支持 `dd-v1`、`fastboot-v1`、`fastboot-v1(lpi4a-uboot)` 和 SpacemiT K1 eMMC fastboot 流程，并保持 SDK、app、helper 和 packaging 模块可测试。

### Current Status

- 项目已拆分为 `:sdk`、`:app`、`:dd-flasher` 和 `:launcher`；CLI/GUI 共用 SDK service graph。
- Ruyi metadata、`image-combo`、distfile 下载/缓存、checksum 校验、artifact materialization 和系统代理发现已接入。系统时区为 `Asia/Shanghai` 时默认 metadata remote 使用 ISCAS 镜像；用户配置仍可覆盖 remote、branch 或 local repository。
- Distfile 和 artifact 提取路径已加固：拒绝不安全文件名、防止缓存污染和路径逃逸、拒绝冲突声明，并处理单分区 ZIP 缺少默认目标产物的情况。
- `dd-flasher` 负责破坏性 raw 写入、声明字节数限制、同 helper 内 write/verify、目标显示名称校验和 NDJSON 进度回传。
- `dd-v1` 破坏性写入前会重新枚举并核对目标 id、path、硬件身份、容量、型号、总线和 removable 状态。
- Windows raw physical drive 写入会在 `dd-flasher` 内锁定/卸载相关 volume；Windows UAC 使用 Java FFM `ShellExecuteExW`，不再通过 PowerShell launcher。
- Linux `pkexec` elevated `dd-flasher` 直接从 stdout 读取 NDJSON 进度；Windows UAC 和 macOS administrator script 路径仍使用共享临时 event log。
- Windows/Linux/macOS 块设备枚举和 fastboot 设备枚举已接入，并发消费 stdout/stderr，避免管道阻塞。
- Linux/macOS mounted removable 目标会在写入前自动卸载，并在破坏性访问前重新枚举确认。
- Fastboot 流程覆盖普通分区刷写、LPi4A/Meles U-Boot handoff、SpacemiT K1 stage/continue、sparse progress 解析、重复 serial 拒绝和 handoff 后目标歧义检查。
- GUI 支持目录/本地镜像选择、目标选择、安全确认、进度、取消、日志路径展示、语言切换、中文厂商显示名、窗口图标和短状态文本无句号。
- 打包支持 bundled fastboot、bundled `dd-flasher`、Windows Rust native launcher、JLink runtime 和 nightly release workflow。Windows JLink 包为 `.zip`；Linux/macOS JLink 包为 `.tar.gz`，并显式设置启动脚本、JDK binaries、`jspawnhelper`、fastboot 和 `dd-flasher` 的 Unix 可执行权限。

### Remaining

- 在真实 Linux/macOS 机器上做只读块设备枚举 smoke test。
- 在可擦写 Windows removable 设备上继续验证 raw physical drive 写入、volume lock/dismount、取消和校验行为。
- 为 `dd-flasher` 非本机发行目标安装并验证 linker 或 `cross` 运行环境；当前 Windows 环境缺少 Linux linker/Docker 设置。
- 下一轮真实设备验证后，移除或降级仍只用于排查的诊断输出。

### Recent Verification

- `./gradlew -g .gradle-user-home :app:compileJava`
- `./gradlew -g .gradle-user-home "-Pjlink.jdk.platform=linux-x86_64" :app:help --task :app:jlinkArchive`
- `./gradlew -g .gradle-user-home "-Pjlink.jdk.platform=windows-x86_64" :app:help --task :app:jlinkArchive`
- 使用最小生成 JLink image 检查 Linux `.tar.gz` archive mode：可执行条目为 `0755`，普通文件为 `0644`。
- `git diff --check`

### Known Limits

- Windows CIM 磁盘枚举在 Codex 沙箱内可能被权限拒绝，需要在沙箱外只读验证。
- 从 Windows 完整构建 Linux/macOS release 包仍依赖可用的非本机 `dd-flasher` toolchain。

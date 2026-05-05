# Plans

## Java 25 Ruyi Imager CLI/GUI

### Goal

- 开发一个 Java 25 应用，通过共享 core service 同时支持 CLI 和 JavaFX GUI 刷写镜像。
- GUI 采用类似 Armbian Imager 的信息架构：目录镜像按 Manufacturer -> Board -> Operating System 选择，本地镜像作为并列二选一入口，然后选择 Storage Device。
- 镜像元数据、下载、校验和物化逻辑从 Ruyi 语义移植到 Java；不嵌入 Starlark，不依赖外部 `ruyi` 命令。
- 保留破坏性写入安全门；默认只执行当前应用明确支持的刷写策略。

### Current State

- 工程已是 Java 25 Gradle `application`，主类为 `org.glavo.ruyi.imager.Main`；依赖包含 JavaFX 25、MaterialFX、Picocli、Jackson、Tomlj、JGit 和 JetBrains annotations。
- 入口已统一：无参数或 `gui` 启动 JavaFX，其他参数进入 CLI。
- CLI 已支持 `repo update`、`image list/download`、`device list`、`device list --fastboot`，以及通过 `--atom` 或 `--local-image` 刷写目标设备；JSON/NDJSON 输出已接入主要命令。
- Core service 已建立：`RepositoryService`、`ImageCatalogService`、`BlockDeviceService`、`FlashService`，由 `AppServices` 组装供 CLI/GUI 共用。
- Ruyi repo/store 已支持默认 repo、用户配置覆盖、overlay repo、本地 repo、JGit clone/pull、mirror/dist URL 解析。
- Image catalog 已支持扫描 `packages/` 或旧 `manifests/`，解析 provisionable manifest、strategy、partition map、distfiles、checksums、mirror URL、slug，并优先按 Ruyi device entity 匹配开发板/variant、按 device id 推导开发板制造商，支持 atom/版本/slug/SemVer 选择。
- Distfile 下载器已支持 HTTP/HTTPS、`.part` 续传、原子落盘、大小和 SHA-256/SHA-512 校验、缓存复用，以及 `restrict = ["fetch"]` 的手动下载提示。
- Image catalog service 已提供轻量 cache status，可报告目录镜像 distfile 是否已缓存、部分缓存、需要下载或需要手动下载。
- Artifact 物化已支持 raw、gzip、zip、tar、tar.gz；tar 读取已使用 `Glavo/kala-compress`，`tar.xz`、`tar.zst`、`tar.bz2`、`tar.lz4`、`xz`、`zst`、`bz2`、`lz4`、`deb` 仍显式 unsupported。
- 本地 `dd-v1` 刷写已接入默认服务图，支持本地镜像和已物化 Ruyi 镜像写入 `BlockDevice.path()`，包含系统盘、已挂载、只读、容量、自写入、flush 和写后 verify 检查。
- Strategy support 目前将 `dd-v1`、`fastboot-v1` 和 `fastboot-v1(lpi4a-uboot)` 标为 supported；dd 使用本地块设备写入，fastboot 使用受控外部 `fastboot` 命令执行。
- Windows 只读块设备枚举已接入，并会保留已挂载卷的挂载点用于 CLI/GUI 展示；非 Windows 平台目前仍使用占位枚举服务。
- GUI 已实现 MaterialFX 风格主窗口、更宽默认窗口、无阴影步骤控件、运行时中英文切换、语言偏好持久化、目录镜像/本地镜像二选一流程、右侧本地镜像入口居中、渐进式步骤启用、搜索式选择弹窗、dd/fastboot 目标切换、策略/缓存/目标风险状态标记、结构化最终确认弹窗。
- i18n 基础设施已接入 `ResourceBundle`；`Messages` 提供 locale property 和 `StringBinding` helper，当前资源包含 English 和简体中文。

### Next Work

- 设备后端：
  - 实现 Linux 和 macOS 只读块设备枚举。
  - 完善 Windows 写盘前的挂载/卷占用处理策略；当前只拒绝已挂载目标，不做自动卸载。
  - 评估是否需要原生/FFM 后端替代直接 `FileChannel` 写物理设备。
- 刷写策略：
  - 扩展 `dd-v1` 多分区 target mapping。
  - 增加 fastboot 自动重连等待、取消、失败清理和更细粒度进度统计。
- 镜像物化：
  - 增加 tar.xz、tar.zst、tar.bz2 等 Ruyi 常见压缩 tar archive 支持。
  - 改进 unsupported archive 的用户提示和 fallback 路径。
- GUI：
  - 为多目标 dd strategy 增加专门流程。
- 测试：
  - 增加 CLI fixture repo 集成测试，覆盖 `repo update`、`image list/download --json` 和 unsupported strategy。
  - 增加 GUI ViewModel/service 层测试或 JavaFX smoke test。
  - 增加真实平台枚举 parser fixture，避免依赖本机硬件。

### Verification

- 常规验证命令：
  - `./gradlew -g .gradle-user-home test`
  - `./gradlew -g .gradle-user-home run --args='image list --json'`
  - `./gradlew -g .gradle-user-home run --args='device list --json'`
  - `git diff --check`
- Gradle 本地环境要求：
  - `GRADLE_USER_HOME=.gradle-user-home`
  - `GRADLE_OPTS=-Dhttps.proxyHost=p.g -Dhttps.proxyPort=7890 --sun-misc-unsafe-memory-access=allow --enable-native-access=ALL-UNNAMED`
  - `ZIG_EXECUTABLE=D:\Application\zig-x86_64-windows-0.16.0\zig.exe`
- 注意：Windows CIM 磁盘枚举在 Codex 沙箱内可能被权限拒绝；需要沙箱外只读运行验证。

### Constraints

- Java 代码遵守仓库 `AGENTS.md`：`@NotNullByDefault`、nullable 标注、record 优先、immutable collection 标注、Markdown Javadoc。
- GUI “类似 Armbian Imager”指交互结构和信息架构相似，不复制代码或视觉资产。
- CLI 必须保留 `--yes` 破坏性操作安全门；GUI 必须保留最终确认弹窗。
- 默认拒绝系统盘、已挂载目标和只读目标。

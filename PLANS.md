# Plans

## Java 25 Ruyi Imager CLI/GUI

### Goal

- 开发一个 Java 25 应用，通过同一套 core service 同时支持 CLI 和 JavaFX 25 GUI 刷写镜像。
- GUI 采用类似 armbian-imager 的目录选择流程：Manufacturer、Board、Operating System、Storage Device，并将本地镜像与前三步目录选择并列为二选一入口。
- 镜像获取、解析、下载、校验和物化逻辑从 `external/ruyi` 的语义移植，不嵌入 Starlark，不依赖外部 `ruyi` 命令。
- 默认只执行已知安全策略；未知或第三方 provision strategy 只展示为 unsupported。

### Current State

- Gradle 已是 Java 25 `application` 工程，主类为 `org.glavo.ruyi.imager.Main`，依赖包含 JavaFX 25、MaterialFX、Picocli、Jackson、Tomlj、JGit 和 JetBrains annotations。
- 入口已统一：无参数或 `gui` 启动 JavaFX，其他参数进入 CLI。
- CLI 已实现：
  - `repo update`
  - `image list [--json]`
  - `image download <atom> [--json]`
  - `device list [--json]`
  - `flash --atom <atom> --device <id> [--yes] [--skip-verify] [--json]`
  - `flash --local-image <path> --device <id> [--yes] [--skip-verify] [--json]`
- Core service 边界已建立：`RepositoryService`、`ImageCatalogService`、`BlockDeviceService`、`FlashService`，由 `AppServices` 组装供 CLI/GUI 共用。
- Ruyi repo/store 已支持默认 `ruyisdk`、用户 `[repo]` 覆盖、`[[repos]]` overlay、本地 repo、JGit clone/pull，以及 repo `config.toml` mirror/dist 解析。
- Image catalog 已支持扫描 `packages/` 或旧 `manifests/`，解析 `provisionable` manifest、strategy、partition map、distfiles、checksums、mirror URL、slug，并生成 `ImageEntry`。
- Image catalog 已从 Ruyi `metadata.vendor.name` 读取 manufacturer，用于 GUI 的制造商分组。
- Image selection 已支持 atom/版本解析：精确 `category/name(version)`、短名、`category/name`、`name:`、`slug:` 和 SemVer 范围；默认选择最新非 prerelease。
- Distfile 下载器已支持 HTTP/HTTPS、`.part` 临时文件、Range 续传、原子落盘、大小校验、SHA-256/SHA-512 校验、缓存复用，以及 `restrict = ["fetch"]` 拒绝自动下载。
- Artifact 物化已支持 raw copy、bare gzip 解压、zip 安全解包、tar 解包、tar.gz 解包，以及 zip/tar 的 `stripComponents`；`tar.xz`、`tar.zst`、`tar.bz2`、`tar.lz4`、`xz`、`zst`、`bz2`、`lz4`、`deb` 仍显式 unsupported。
- 本地 `dd-v1` 刷写核心已接入默认服务图，支持本地镜像和 Ruyi 镜像物化路径写入 `BlockDevice.path()`，包含系统盘、已挂载、只读、容量、自写入安全检查、flush 和写后 verify。
- Windows 只读块设备枚举已接入，输出 `\\.\PHYSICALDRIVE*`、容量、型号、bus type、removable/system/mounted/read-only。非 Windows 平台目前仍使用占位枚举服务。
- CLI JSON/NDJSON 输出已修正为稳定字符串路径和完整 progress/final event 输出。
- JavaFX GUI 已实现程序化主窗口和 CSS；目录镜像选择流程已改为类似 Armbian Imager 的 Manufacturer -> Board -> Operating System -> Storage Device 四步级联，并显示 storage 安全阻断状态。
- GUI 已支持后台触发 repo metadata update；本地镜像选择已从目录四步流程中拆出，与 Manufacturer -> Board -> Operating System 目录选择并列显示为二选一入口，并在本地镜像模式下跳过目录选择步骤。
- GUI 已接入 MaterialFX 主题，主流程按钮、进度条、滚动容器和选择列表已切换为 MaterialFX 控件或 legacy MaterialFX 控件。
- i18n 基础设施已接入 `ResourceBundle`，当前提供英文和简体中文资源；GUI 文本、CLI 运行时错误、CLI root help、核心下载/仓库/刷写进度与结果消息已走同一套消息资源，可通过系统 locale 或 `ruyi.imager.locale` 覆盖。`Messages` 已暴露 locale property 和 `StringBinding` helper，GUI 固定文本可随 locale 切换更新。

### Remaining Work

- 设备后端：
  - 实现 Linux 和 macOS 只读块设备枚举。
  - Windows 写盘前需要更完整的挂载/卷占用处理策略；当前只拒绝已挂载目标，不做自动卸载。
  - 评估是否需要原生/FFM 后端替代直接 `FileChannel` 写入物理设备。
- 刷写策略：
  - 扩展 `dd-v1` 多分区 target mapping。
  - 实现 `fastboot-v1` 和 `fastboot-v1(lpi4a-uboot)` 的受控外部工具执行、设备检测和日志输出。
  - 增加取消、失败清理和更细粒度进度统计。
- 镜像物化：
  - 增加 tar.xz、tar.zst、tar.bz2 等 Ruyi 常见压缩 tar archive 支持。
  - 明确 unsupported archive 的用户提示和 fallback 路径。
- GUI：
  - 增加 GUI 内语言切换入口，并决定是否需要持久化用户语言偏好。
  - 增加 image cache 状态提示。
  - 增加列表搜索、策略支持状态标记、target 风险视觉状态、final confirmation 的更完整摘要。
  - 为 fastboot 和多目标 strategy 增加专门流程。
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
- GUI “类似 armbian-imager”指交互结构和信息架构相似，不复制代码或视觉资产。
- CLI 必须保留 `--yes` 破坏性操作安全门；GUI 必须保留最终确认弹窗。
- 默认拒绝系统盘、已挂载目标和只读目标。

# Plans

## Java 25 Ruyi Imager CLI/GUI

### Goal

- 构建一个 Java 25 应用，通过共享 core service 同时支持 CLI 和 JavaFX GUI 刷写镜像。
- GUI 采用类似 Armbian Imager 的流程：目录镜像按 Manufacturer -> Board -> Operating System 选择，本地镜像作为并列二选一入口，然后选择 storage 或 fastboot 目标。
- 镜像元数据、下载、校验和物化逻辑从 Ruyi 语义移植到 Java；不嵌入 Starlark，不依赖外部 `ruyi` 命令。
- 默认只执行当前应用明确支持的刷写策略。
- 将刷写、下载、设备枚举、日志和共享 i18n 作为内部 SDK 模块沉淀，方便后续独立测试和替换写入后端。

### Implemented

- 工程入口：Java 25 Gradle `application`，无参数或 `gui` 启动 JavaFX，其他参数进入 CLI。
- 模块化：Gradle 已拆分为 `:sdk` 和 `:app`；`:sdk` 使用 `java-library` 承载 core、i18n、logging 和对应单测，`:app` 承载 CLI、JavaFX GUI、发行包和应用层测试。
- Core service：`RepositoryService`、`ImageCatalogService`、`BlockDeviceService`、`FastbootService`、`FlashService` 已由 `AppServices` 统一组装，供 CLI/GUI 共享。
- Ruyi metadata：支持默认 repo、用户配置覆盖、overlay repo、本地 repo、JGit clone/pull、mirror/dist URL 解析；catalog 支持 `packages/` 和旧 `manifests/`，并解析 provisionable manifest、strategy、partition map、distfiles、checksums、slug、device entity、SemVer/atom 选择。
- Catalog cache：目录镜像元数据首次读取后缓存在 `ImageCatalogService` 内存快照中，GUI 的 manufacturer/board/OS 多步选择复用同一份 catalog；`repo update` 成功后自动失效缓存。
- 下载和物化：支持 HTTP/HTTPS、`.part` 续传、缓存复用、大小和 SHA-256/SHA-512 校验、`restrict = ["fetch"]` 和 `fetch_restriction` 手动下载提示；artifact 物化支持 raw、gzip、bzip2、lz4、xz、zstd、zip、tar、常见 tar 压缩组合和 Debian package 的 `data.tar*`，tar 物化支持 `strip_components` 和 `prefixes_to_unpack`，tar 和压缩流使用 `Glavo/kala-compress`。
- 刷写：支持 `dd-v1`、`fastboot-v1`、`fastboot-v1(lpi4a-uboot)`；dd 支持单目标整盘和多分区 target mapping，写入前预校验目标；Windows removable 挂载目标可在写入前通过 PowerShell 卸载卷和访问路径，non-removable 挂载目标保持阻止；raw block 写入已封装为可替换 writer，默认使用 `FileChannel`；fastboot 支持分区级进度、LPi4A reboot 后重连等待、超时/中断清理。
- Bundled fastboot：发行包会下载并携带 Android SDK Platform Tools 中的 Windows/macOS/Linux x86-64 fastboot；运行时优先使用发行目录内的 bundled fastboot，缺失或不支持的平台退回 PATH；平台识别覆盖 Windows、macOS、Darwin、Linux 的 x86-64 别名。
- 设备枚举：Windows、Linux、macOS 只读块设备枚举已接入，保留挂载点用于 CLI/GUI 展示；三套平台 parser fixture 已覆盖。
- CLI：支持 `repo update`、`image list/download`、`device list`、`device list --fastboot`、`flash --atom`、`flash --local-image`、多分区 `--partition-device`，主要命令支持 JSON/NDJSON；本地 Ruyi repo fixture 集成测试覆盖 repo update、image list/download 和 unsupported strategy。
- GUI：MaterialFX 主窗口、首次启动安全提醒、运行时中英文切换、语言偏好持久化、调整后的默认窗口尺寸、目录镜像/本地镜像二选一、渐进式步骤启用、树形操作系统分类选择及 MaterialFX 风格滚动条、可搜索选择弹窗标题与搜索框同一行布局、storage/fastboot 目标切换、多分区 `dd-v1` 目标映射、策略/缓存/目标风险标记、最终确认弹窗；选择规则已拆出为可单测逻辑；JavaFX smoke test 覆盖 OS 树选择和多分区存储选择控件。
- 日志：基于 `java.util.logging` 写入默认轮转日志文件，支持 CLI `--log-level`、`--verbose`、`--log-file` 以及 JVM/env 配置；CLI JSON 错误和 GUI 错误弹窗会提示日志路径；日志脱敏 URL query/fragment、常见 secret 字段和截断外部命令输出；repo、catalog、download、materialize、device、fastboot、flash 和 GUI 后台任务边界已记录关键诊断信息。

### Remaining

- 设备后端：
  - 在真实 Linux/macOS 设备上做只读枚举 smoke test。
  - 在可擦写 Windows removable 设备上做挂载目标写入准备 smoke test，确认卸载卷和移除访问路径行为。

### Verification

- 常规验证：
  - `./gradlew -g .gradle-user-home test`
  - `./gradlew -g .gradle-user-home run --args='image list --json'`
  - `./gradlew -g .gradle-user-home run --args='device list --json'`
  - `./gradlew -g .gradle-user-home run --args='--verbose --log-file build/tmp/ruyi-imager.log image list --json'`
  - `git diff --check`
- Gradle 本地环境要求：
  - `GRADLE_USER_HOME=.gradle-user-home`
  - `GRADLE_OPTS=-Dhttps.proxyHost=p.g -Dhttps.proxyPort=7890 --sun-misc-unsafe-memory-access=allow --enable-native-access=ALL-UNNAMED`
  - `ZIG_EXECUTABLE=D:\Application\zig-x86_64-windows-0.16.0\zig.exe`
- 注意：Windows CIM 磁盘枚举在 Codex 沙箱内可能被权限拒绝；需要沙箱外只读运行验证。

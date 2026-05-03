# Plans

## Java 25 Ruyi Imager CLI/GUI

### Summary

- 将当前空骨架扩展为 Java 25 应用：同一套核心服务同时供 CLI 和 JavaFX GUI 使用。
- GUI 使用 JavaFX 25，做成类似 armbian-imager 的设备向导：设备、变体、镜像、存储目标、刷写进度。
- 从 `external/ruyi` 移植 Ruyi repo 元数据、镜像包解析、下载、校验与官方 provision 策略语义；不嵌入 Starlark，不调用外部 `ruyi`。
- 支持三平台架构；块设备写入走 Java + FFM 平台实现，`fastboot` 等特殊策略作为受控外部工具调用。

### Progress

- 2026-05-03：已将 Gradle 改为 Java 25 `application` 工程，加入 JavaFX `25.0.2`、Picocli、Jackson、Tomlj、JGit 依赖，并配置应用主类 `org.glavo.ruyi.imager.Main`。
- 2026-05-03：已实现 GUI/CLI 共享入口；无参数或 `gui` 启动 JavaFX，其他参数进入 Picocli CLI。
- 2026-05-03：已实现 CLI 命令骨架：`repo update`、`image list`、`image download`、`device list`、`flash`，包含 human/JSON 输出路径和 `--yes` 安全门。
- 2026-05-03：已建立核心服务边界与模型：`AppServices`、`RepositoryService`、`ImageCatalogService`、`BlockDeviceService`、`FlashService` 及对应 record 模型；真实 Ruyi 下载和平台刷写后端仍为安全占位实现。
- 2026-05-03：已实现程序化 JavaFX 主窗口和 CSS，提供 Board/Image/Target/Write 四步向导骨架；当前镜像目录和设备枚举仍返回空结果。
- 2026-05-03：已验证 `./gradlew -g .gradle-user-home compileJava`、`./gradlew -g .gradle-user-home run --args='image list --json'` 和 `./gradlew -g .gradle-user-home test`；当前测试任务无测试源。
- 2026-05-03：已实现 Ruyi repo 配置读取与同步骨架：支持默认 `ruyisdk` repo、用户 `[repo]` 覆盖、`[[repos]]` overlay、本地 repo、JGit clone/pull、repo `config.toml` 镜像源解析。
- 2026-05-03：已实现本地 Ruyi package catalog 扫描：从 `packages/` 或旧 `manifests/` 中读取 `provisionable` manifest，解析 strategy、partition map、distfiles、checksums、mirror URL，并生成 `ImageEntry`。
- 2026-05-03：已新增 repo/config 与 image catalog 单元测试；Gradle `test` 的 `java.io.tmpdir` 固定到工作区 `build/tmp/test-tmp`，避免本机沙箱限制系统临时目录。
- 2026-05-03：已验证 `./gradlew -g .gradle-user-home test`、`./gradlew -g .gradle-user-home run --args='image list --json'` 和 `git diff --check`。
- 2026-05-03：已实现 Ruyi distfile 下载器：支持 HTTP/HTTPS 下载、临时 `.part` 文件、Range 断点续传、原子落盘、文件大小校验、SHA-256/SHA-512 校验、缓存命中复用和 `restrict = ["fetch"]` 拒绝自动下载。
- 2026-05-03：`image download <atom>` 已接入真实 distfile 下载；单 distfile 返回文件路径，多 distfile 返回下载目录。已新增下载器单元测试覆盖正常下载和 Range 续传。
- 2026-05-03：已实现 Ruyi image atom 解析与版本选择：支持精确 `category/name(version)`、`name`、`category/name`、`name:` 前缀、`slug:` 前缀，以及逗号分隔 SemVer 比较表达式；默认选择最新非 prerelease 版本。
- 2026-05-03：`ImageEntry` 已补充 manifest slug；已新增 atom/版本选择测试，覆盖最新稳定版本、精确版本、范围表达式、短 name 和 slug 查询。
- 2026-05-03：已实现下载后 image artifact 物化：`downloadImage` 现在下载 distfile 后写入 `cache/artifacts/<repo>/<category>/<name>/<version>/`，并校验 `provisionable.partition_map` 指向的文件存在；单分区返回具体 artifact 路径，多分区返回 artifact 目录。
- 2026-05-03：已实现 raw 复制、bare gzip 解压和 zip 安全解包；`tar*`、`xz`、`zst`、`bz2`、`lz4`、`deb` 暂时显式报 unsupported。已新增物化测试覆盖 raw、gzip、zip 和 unsupported archive。
- 2026-05-03：已实现平台无关的 `dd-v1` 本地刷写核心：支持本地镜像和 Ruyi 镜像物化路径写入 `BlockDevice.path()`，执行系统盘/只读/容量/自写入安全检查、flush 和按镜像长度进行写后 verify。
- 2026-05-03：默认服务图已接入 `LocalFlashService`。已新增临时文件模拟块设备测试，覆盖本地镜像、Ruyi `dd-v1` 镜像、安全拒绝和容量不足路径。
- 2026-05-03：已实现 Windows 只读块设备枚举后端：默认服务图按平台选择 Windows CIM 枚举或占位服务，`device list` 可输出真实 `\\.\PHYSICALDRIVE*` 目标、容量、型号、bus type、removable/system/read-only 状态；已新增 PowerShell JSON 解析测试。
- 2026-05-03：已修正 CLI JSON 路径输出：`image download --json` 与 `device list --json` 使用明确字符串路径，避免 Jackson 将 `Path` 序列化为 `file:` URI。
- 2026-05-03：块设备模型已新增 `mounted` 安全标志；Windows 枚举会根据磁盘关联的 logical disk 判断挂载状态，CLI JSON 输出该字段，`LocalFlashService` 默认拒绝写入已挂载目标并新增对应测试。

### Key Changes

- Gradle 改为应用工程：Java toolchain 25，JavaFX `25.0.2`，`applicationDefaultJvmArgs += "--enable-native-access=ALL-UNNAMED"`；保留 JetBrains annotations 规则。
- 增加 CLI 入口 `org.glavo.ruyi.imager.Main`：无参数或 `gui` 启动 JavaFX；其他参数进入 Picocli CLI。
- CLI 命令固定为：
  - `repo update`
  - `image list [--json]`
  - `image download <atom> [--json]`
  - `device list [--json]`
  - `flash --atom <atom> --device <id> [--yes] [--skip-verify] [--json]`
  - `flash --local-image <path> --device <id> [--yes] [--skip-verify] [--json]`
- JSON 输出采用单对象结果；长任务输出 newline-delimited progress events，最终输出 completion/error event。

### Implementation Changes

- Ruyi 移植层：
  - 实现 repo clone/pull、mirror URL 解析、package manifest、entity graph、image combo、provisionable metadata、distfile 下载、断点续传、sha256/sha512 校验。
  - 默认 repo 为 RuyiSDK official packages-index；支持配置 overlay repo，但未知第三方 strategy plugin 只显示 unsupported，不执行。
  - 支持官方标准策略：`dd-v1`、`fastboot-v1`、`fastboot-v1(lpi4a-uboot)`；策略实现为 Java service。
- 刷写服务：
  - `BlockDeviceService` 枚举设备，返回路径、容量、型号、bus type、removable/system/read-only 状态。
  - `FlashService` 统一处理本地镜像、下载镜像、解压镜像、写入、flush、写后 verify、取消和失败清理。
  - 默认拒绝系统盘和已挂载目标；CLI 必须传 `--yes` 才实际写盘；GUI 必须显示最终确认弹窗。
- GUI：
  - 程序化 JavaFX + CSS，不使用 FXML。
  - 首页为 4 步选择按钮和顶部状态条；列表弹窗带搜索、支持等级/策略状态标记。
  - 复杂 strategy 需要多目标路径时显示高级目标映射对话框；fastboot 策略显示设备检测和命令日志。
  - 后台任务全部通过 core service progress callback 驱动 UI，不在 UI 层实现业务逻辑。

### Test Plan

- 单元测试：Ruyi manifest/entity 解析、SemVer 排序、mirror URL 展开、distfile 校验、strategy 选择。
- 下载测试：临时 HTTP server 覆盖正常下载、Range 续传、checksum mismatch、大小不符、取消清理。
- CLI 测试：临时 app dirs + fixture repo，验证 `--json` 输出、错误码、未知策略 unsupported、`--yes` 安全门。
- 刷写测试：使用临时文件模拟块设备，验证写入、verify、skip verify、取消和只读/系统盘拒绝。
- GUI 测试：ViewModel/service 集成测试；JavaFX smoke test 验证主窗口可启动并显示向导初始状态。

### Assumptions

- 依赖默认使用：JavaFX `25.0.2`、Picocli `4.7.7`、Tomlj `1.1.1`、Jackson `2.21.2`、JGit `7.6.0.202603022253-r`。
- Java 代码遵守仓库 `AGENTS.md`：`@NotNullByDefault`、nullable 标注、record 优先、Markdown Javadoc。
- 未知或第三方 Ruyi provision strategy 不执行，只在 CLI/GUI 中标注为 unsupported。
- GUI “类似 armbian-imager”指交互结构和信息架构相似，不复制其代码或视觉资产。

[EN / English](CHANGELOG.md) · [ZH / 简体中文](CHANGELOG_zh.md) · [JP / 日本語](CHANGELOG_ja.md) · [KR / 한국어](CHANGELOG_ko.md)

# 更新日志

Turboism 的所有重要变更都记录在本文件中。

格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循语义化版本规范。

## [Unreleased]

## [0.67.4] - 2026-10-24

### 新增

- 文件夹一键纹理排版支持 Bitmask 和 Dalsoo 多边形算法，跨文件夹复用页面空余区域，尽可能提高纹理利用率。
- 插件管理显示已安装及市场插件数量；共用进度窗口居中显示于编辑器。
- SDK 新增源图像轮廓、多边形输出页面及可复用进度窗口。保留现有纹理排版构造方法签名；记录类型的反射、相等性和字符串表示包含新增组件，原规范构造方法不再提供参数名元数据。

### 变更

- `atlas-folder-layout`、`atlas-bitmask-nesting`、`atlas-dalsoo-polygon` 和 `clipmask-viewer` 使用了新增 SDK，最低 API 要求改为 `0.67.4`；其他官方插件保持原有下限。

### 修复

- 新导入的 PSD 尚无纹理集时，一键排版会自动创建并立即生成纹理，无需原生初始放置，也无需另开纹理集窗口确认。
- 裁剪源图像轮廓，并精确放置单个多边形，避免 Bitmask 在接近 4096 像素页面上限时误报超尺寸。排版失败会区分图像过大和无法放置，并提供相关尺寸。
- 避免纹理排版弹窗重叠，保留启动模型时的文件参数。
- Windows 安装器采用 Turboism 品牌样式，并隐藏提权配置控制台。
- 简化文件夹排版与剪贴蒙版窗口，统一 PSD 导入进度显示。

## [0.67.2] - 2026-10-23

### 新增

- 新增插件作者指南，涵盖生命周期、线程、权限、设置、存储、事件和测试，并明确稳定版发布前的 `@Incubating` 策略。
- `SettingsControl` 新增 `FilePath` 变体；插件重新编译时，针对 `SettingsControl` 的穷尽式 switch 必须添加对应分支。
- 纹理图集 SDK 新增逐页尺寸、打包失败诊断与规划器协商支持。

### 修复

- 插件管理明确标示 SDK API 兼容范围，并分别显示 Cubism 可用、完整功能及部分功能的精确版本；缺失或无效声明显示为未声明。
- README 同步不再随框架补丁发版自动抬高所有插件的 API 门槛；每个官方插件的最低 API 只反映其实际使用的 SDK。
- 完整功能版本的生成计算纳入 SDK 与端点限制；插件 README 明确审核范围及完整功能范围，并移除过期的未发布安装说明。
- 任务在 Swing 事件线程上启动期间收到的中断，现在与执行期间的中断一样触发放弃补偿。

### 变更

- `external-psd-edit`、`atlas-bitmask-nesting`、`atlas-folder-layout` 和 `atlas-maxrects-bssf` 使用了上述 SDK 新增内容，最低 API 要求改为 `0.67.2`；其他官方插件保持原有下限。

## [0.67.1] - 2026-10-20

### 修复

- 下载 API 监控改为校验标准 EXE 与便携 ZIP 两个文件，避免旧四下载格式检查引起误报，同时继续拒绝缺失、重复或旧格式文件。

### 变更

- Windows 工作区验证在同一就绪截止时间内重试暂时不可用的状态，在布局未变化时重试调整，并在更新或重置默认布局前重新选择自定义工作区。此项提升验证可靠性，不代表重新完成了 Windows 宿主版本矩阵验证。

## [0.67.0] - 2026-10-19

### 新增

- 支持全部 20 个已审核 Cubism Editor 版本（5.0.00–5.3.04）的多版本适配：143 项能力中有 129 项在所有版本上准入；覆盖率 5.3.x 为 100%、5.2.x 为 97.9%、5.1.x 最高 95.1%、5.0.x 为 90.2%。补丁线能力单调递增，每个排除项都在 `compatibility/cubism/version-boundaries.json` 中有证据（例如 5.0.x 没有自动备份与受保护导出，编辑协议从 5.1.02 起，动画时间线与选择笔刷从 5.2.00 起）。能力与插件兼容矩阵以四种语言发布在 `compatibility/cubism/`。
- 所有第一方插件 JAR 均包含由审核能力依赖派生的 `META-INF/turboism/cubism-versions.json` 描述文件，声明其支持的 Editor 版本。确立 SDK `@CubismEditor` 注解契约：不带参数的裸注解表示在全部 20 个审核版本上可用，显式属性用于限定特定版本子集。
- 第一方插件适配范围扩大：`parameter`、`recent-preview`、`warp-deformer-alt-symmetry` 在全部 20 个版本上功能完整；`mesh-edit-mirror-axis-enhance`、`selection-brush` 与 `mcp`（含纹理写入与事务）在全部 20 个版本上可用，部分功能限制已在文档中注明；`command-palette`、`protected-export` 与 WebDAV 自动备份需要 5.1.00 及以上。
- 实机验证自动处理 Cubism 启动对话框（选择并确认 PRO 试用、更新提示、Home 新闻、低版本打开新版工程的警告、5.0.x 的 JavaFX 公告）。56 个验证任务的版本由兼容矩阵派生，探针覆盖全部 20 个已审核版本。
- 3D 模型导入插件（`dev.turboism.plugin.model-import-3d`）：原生 3D 模型导入，支持静态不透明 GLB、glTF、OBJ/MTL 及模型 ZIP 压缩包，支持 PNG/JPEG 纹理贴图、XYZ 全范围 360 度旋转及有界几何优化，并以单个可撤销的编辑事务提交创建。
- Turboism ACP 插件（`dev.turboism.plugin.acp`）：提供 ACP v1 Agent 与设置窗口，将用户安装的 ACP 兼容 AI 代理（Claude Agent ACP、Codex ACP、Pi、Gemini CLI、OpenCode、Antigravity、Devin CLI 或自定义可执行程序）连接至经过身份验证的 Turboism MCP 服务器，支持 PATH 可执行程序探测、持久化会话以及免凭据的 stdio-to-HTTP MCP 挂载桥接。
- 动画工作区 SDK 与运行时支持：插件可枚举动画文档、工程时间线、轨道、属性与关键帧；切换与重命名场景；控制播放寻道；应用批量关键帧编辑与曲线类型；并记录求值结果。提供纯 SDK 的 `Motion3Validator` 用于检测 motion3 数据的结构问题。动画工作区支持 Cubism 5.2.00–5.3.04。
- 历史面板插件（`dev.turboism.plugin.historypanel`）：Photoshop 风格的历史停靠面板，投射当前活动文档的原生撤销历史，支持基于快照的点击跳转撤销/重做导航。
- 最近项目预览插件（`dev.turboism.plugin.recent-preview`）：捕获最近打开工程文件的视口缩略图，通过内存有界缓存将其接入宿主“最近使用的文件”悬浮桥接。
- 纹理图集排列插件：MaxRects-BSSF 自动布局插件（`dev.turboism.plugin.texture-atlas`）、Dalsoo 多边形紧密装箱插件（`dev.turboism.plugin.texture-atlas-dalsoo`）及纹理图集统计；运行时支持瓦片包围盒处理与输出缓存复用，大幅降低工程打开与导出耗时。
- 选区笔刷插件（`dev.turboism.plugin.selection-brush`）：建模曲面选区笔刷工具，支持带权重顶点选区。
- 曲面变形器 Alt 对称插件（`dev.turboism.plugin.warp-deformer-alt-symmetry`）：在全部已审核版本上按住 Alt 垂直对称、Alt+Shift 水平对称拖动曲面变形器控制点。
- 快捷键窗口与运行时调度器：Turboism 菜单新增“快捷键”管理窗口，拦截宿主按键事件以触发插件操作并重绑定原生快捷键，具备冲突检测功能，持久化存储于 `state/runtime/keybindings.properties`。
- 独立 SDK 分发：每次发布均随附 `turboism-sdk-<version>.jar` 及其 SHA-256 校验文件作为独立开发者资产，支持通过 `publishToMavenLocal` 发布至本地 Maven，并提供 `templates/plugin-template` 独立工程模板。
- 通过 `PluginContext.services()` 与 `PluginServiceDirectory` 进行类型化的插件服务检索。
- 通过 `TurboismWindowFactory.installWindowIcon` 实现进程级窗口图标定制。
- 启动设置页提供 Cubism JVM ZGC 启动控制与编辑期间挂起自动备份开关。
- `devCheck` 新增版本适配门禁：补丁线单调性、字面版本集登记、SDK `@CubismEditor` 注解基线与实机验证任务版本。规则见 `CONTRIBUTING.md`。
- 测试资产：`test-assets/model-import-3d/` 收录自建小型 GLB 模型；新增版本化测试语料（`test-assets/corpus.json`、`scripts/test_corpus.py fetch|verify`），CC0-1.0 许可的纹理图集 CMO3/PSD 大型测试集以 GitHub Release 资产发布。远端卫生检查拒绝提交 `.cmo3`、`.can3`、`.psd` 与 `.moc3` 文件。
- 每个发布都会在元数据中声明支持的 Cubism Editor 版本；下载站按通道显示，并链接到新的兼容性页面（https://turboism.dev/docs/use/compatibility）。
- 插件开发者可以从独立模板仓库 `turboism/turboism-plugin-template` 开始：setup 脚本会拉取并校验 SDK 和新增的发布文件 `turboism-event-processor-<version>.jar`（`@SubscribeEvent` 需要它），重命名脚手架并写入 Cubism 版本声明。
- Cubism 内插件市场（Turboism → 插件市场…）：从已签名的插件目录浏览、安装、更新和卸载插件。声明 `lifecycle.hotReload` 的插件立即生效，其余在重启 Cubism 后生效。第一方插件会在后台自动更新，可在设置中关闭。网页插件市场 https://turboism.dev/plugins 显示每个插件的 README 并支持筛选。
- Cubism 菜单更名为“Turboism”，并新增独立的插件设置窗口。
- 发行包仅区分 Windows EXE 安装包（`TurboismInstaller-<v>.exe`）与便携 ZIP（`turboism-<v>.zip`），均默认包含全部随包第一方插件和完整离线 GraalJS 引擎；GraalVM 运行时单独安装。既有安装可通过安装器升级。不再发布 Java 安装器。

### 变更

- SDK 契约收敛，exact 基线升级为 v21（保留 v2–v20 历史审计），受影响的插件需要迁移：
  - 移除已弃用的按服务 `PluginContext` getter，改用 `ctx.services().find(...)` 或 `require(...)`。
  - 参数查询读取面由 `ParameterAppearance` 与 `cubism().model().active().parameters().all()` 取代。
  - 旧命令队列与 `TransactionManager` 由创作事务和 `Parameter.setValue` 取代，`StrictJson` 由 `dev.turboism.sdk.json.Json` 取代，自动备份路径由 `BackupArtifactHandle` 取代。
  - `PermissionIds` 成为权限标识的唯一来源。
  - 公开的 `dev.turboism.sdk.cubism` API 带有 `@CubismEditor` 版本声明，art-mesh、渲染、MOC 与插值 API 纳入审核接口。
- 内置核心插件折叠入运行时框架外壳（`dev.turboism.shell`），取消独立插件 JAR，同时保留配置、任务与日志归属。
- 托管启动默认启用 Cubism JVM ZGC（`-XX:+UseZGC`），消除 G1 秒级停顿并降低常态内存占用。
- CSV 参数批量导入合并至单一编辑事务中执行，使结构重建减少 89%，中位写入延迟降低 75%。
- 启动抑制功能（跳过更新检查、启动闪屏与通知对话框）默认开启。
- 宿主钩子改为在 `META-INF/turboism/hooks` 中声明并通过 `HookContributor` 扫描，取代手动硬编码并保持 fail-closed 准入。
- 语义事件类型包路径迁移至 `dev.turboism.sdk.cubism.event`。
- 插件的 Turboism API 版本现在跟随框架基础版本（含 beta/nightly 构建）。插件 SDK API 范围从 `0.67.0` 起、到 `1.0.0` 之前；旧的 `[0.1.0,0.2.0)` 插件会被拒绝，并提示运行时实际的 API 版本。运行时元数据记录实现版本与 API 版本；已审核的 SDK JAR 不变。
- Windows 安装器现在默认勾选官方 Cubism BAT 集成，与 legacy 的自动启动行为一致：默认
  备份并修改所选 `CubismEditor5.bat` 启动器，使现有 Cubism 快捷方式、文件关联和其他
  入口无需经过托管启动器即可加载 Turboism。该选项仍可在启动选项页取消勾选，保持哈希
  保护与幂等，并在卸载时恢复。静默安装同样应用；仅集成步骤通过 UAC 提权。

### 移除

- 完全移除托管 fx 运行时：不包含内嵌 agent 二进制、凭据存储或 OpenAI 网关适配器；退役开发期 `turboism-with-fx` 插件。
- 从 SDK 中移除 `ParameterQueryService`、`ParameterSummary`、`ParameterBounds` 及 `PluginContext.parameterQuery()`。
- 移除已废弃的单服务 `PluginContext` 快捷访问方法、`availableServices()` 以及历史 `cubism.transaction` 队列类型。

### 修复

- ACP 挂载代理可通过预编译的 stdio 桥接使用 Turboism MCP 写入工具，无需宿主 JRE 具备编译器，且在 MCP 端点变化时自动重连。
- 当引导类未定义 ClassLoader 时，`ClassPinTable.load` 回退使用系统类加载器。
- 强化插件生命周期与权限校验：在动画、剪贴蒙版、PSD、纹理图集与物理服务关闭后拒绝后续无效访问。
- 动画时间缩放对贝塞尔控制手柄应用一致的仿射变换。
- 内存增长边界化：浮层按钮表采用 FIFO 淘汰，编辑器绑定标识弱引用缓存，最近预览与调色板遍历清理失效条目。
- 纹理图集哈希缓存无法计算输入摘要时正确回退至重建路径。
- `Motion3Validator` 在收窄段标识符前校验数值范围，避免溢出。
- 托管 Graal 安装程序正确协调因中断遗留的孤立激活标记。
- 文档切换时立即发布新绑定的模型，避免求值读取引用并固定旧模型。
- 画布上下文菜单不再接收多余的工作区级插件条目注入。

### 安全

- MCP 回环服务器针对引起变更的 `tools/call` 请求与会话生命周期强制要求 `Authorization: Bearer` 令牌认证，由仅所有者可读的本地持久化令牌与 stdio 桥接保障安全。
- 强化作用域销毁保证与钩子原子化注销，防止跨会话资源泄漏与销毁后的未授权操作。

## [0.44.0] - 2026-09-11

### 新增

- Turboism 现在会针对已部署的发布 API（`api.turboism.dev/v1/releases/stable.json`）检查稳定版更新。比较使用安装包内嵌的权威构建号，因此更低的构建号永远不会被当作更新提供，构建号相同但版本不同的情况会被视为身份冲突而不是更新。早于构建号机制的安装仍然只按版本比较，并且永远不会被赋予臆造的编号。
- 可用更新现在以绘图区上的原生 Cubism 提示呈现，与宿主自身右下角消息使用同一层界面，而不再是 Turboism 停靠面板中的条目。提示以 key 标识，因此更新的构建会替换旧文案；一旦更新不再被提供，提示会自动消失。点击提示会打开固定的官方下载页；不会打开或安装来自发布源的任何 URL，也不会自动下载或执行安装程序。
- 更新检查不阻塞界面，最多每 24 小时运行一次，并始终提供手动检查。自动检查在“启动”设置页有独立的持久开关，且不受 Cubism 自身更新抑制设置的影响。
- 插件现在可以通过 `UiHostCapabilityService.notifyCanvasHint`、`notifyDismissibleCanvasHint` 和 `showCanvasHintWhile`，以及 `CanvasHintNotification`、`CanvasHintHandle`、`CanvasHintPosition` 和 `ConditionalCanvasHint`，在绘图区显示原生 Cubism 提示（需要新增的 `turboism.ui.canvas.hint` 权限）。该能力按版本路由到已校验的 5.2.03、5.3.02 和 5.3.03 宿主路径；在无法解析该路径的宿主上会报告为不可用，而不是以近似方式实现。详见 [SDK v10 评审](sdk/api-contracts/sdk-api-v10-review.md)：本次修订为纯增量，插件无需迁移。
- 发布说明现在在简体中文与日文之外还提供经过审阅的韩文：发布文档通过 `notesByLanguage` 暴露，缺少译文时网站回退显示英文；Nightly 的标题与警告提供四种语言，而原始提交标题保持原文并明确标注。

### 改进

- 已评审的 SDK exact 基线升级为 v10，锚定到 canvas hint 提交。本次修订新增 42 条 API 记录，未删除或修改任何记录；v9 与 v8 保留为历史 exact 审计，每次发布仍会执行。
- WebDAV 备份插件由 `backup` 更名为 `webdav-backup`：Gradle 模块与 Java 包为 `webdav-backup`/`dev.turboism.plugin.webdavbackup`，安装包内文件为 `plugins/webdav-backup.jar`（原 `plugins/backup.jar`），插件 id 为 `dev.turboism.plugin.webdav`（原 `dev.turboism.plugin.backup`），菜单项也已本地化。`backup/webdav.cfg` 中已保存的端点设置不受影响。
- `release-notes/<version>.json` 中若出现受审阅矩阵之外的语言，现在会让发布直接失败，而不是静默丢弃，因此拼写错误不会发布出缺少译文的版本。

### 修复

- 当 GitHub 不可达时，发布 API 会继续提供最后一次校验过的发布快照，而不是对所有通道都回答“unavailable”。快照在最长 24 小时内保持可用，单个通道的临时故障不再丢弃该通道此前已校验的数据，刷新失败也会保留上一份快照。发布仍会立即通知 API，并新增定时监控以确认去重后的故障通告。
- Windows 安装器在预置托管 Graal 运行时不再要求预装 Java：它直接下载并校验压缩包，为独立安装初始化完整的运行时配置，其 Graal 页面也不再声明已过时的 Java 前提条件。该预置流程已在 Windows PowerShell 5.1 与 7 上验证。
- 升级既有安装不再残留两个 WebDAV 插件条目。两个安装器都会在受管升级时按内嵌插件 id 从 `plugins/` 删除更名前的旧 JAR（因此任何文件名都被覆盖），旧 id `dev.turboism.plugin.backup` 也加入了已退役/被取代边界：运行时拒绝加载它，插件管理不再列出它，`config.json` 的 `disabledPlugins` 也不再保留它。更名前的 WebDAV 设置对话框现在也会本地化每一个标签、按钮、提示与状态消息，而不再始终显示中文。

## [0.43.11] - 2026-09-11

### 新增

- 安装器新增显式语言选择，不再只依赖宿主语言，并在英文、简体中文、日文之外加入韩文。NSIS 向导在欢迎页之前显示标准语言对话框，且无论宿主语言为何都列出全部语言；IzPack 安装器附带 `kor` 语言包、其许可资源以及模态语言包选择器。安装器所选语言仅作用于安装器，不会写入 `config.json`。
- `GET /v1/downloads/<version>.json` 提供每个版本的下载请求统计：官方镜像的请求起始次数会与 GitHub 的二进制 `download_count` 相加，响应为每个二进制文件返回一行 `assets`，包含名称、key、SHA-256、official、GitHub 与 total，且逐文件数值与版本总计一致。校验文件、HEAD/304、失败请求、非零断点续传区间以及带验证标记的流量都不计入；未知来源保持为 `null`，不会伪造为 0。
- Stable、Beta 与 Nightly 版本现在携带经过审阅的简体中文与日文说明（`notesByLanguage`），英文作为回退，网站本地切换语言。摘要与英文段落不一致的译文会被拒绝，而不会被复用。Nightly 在准备候选时冻结已发布的祖先基线与真实提交标题，因此之后落入的提交不会改变已构建候选的内容。
- 经过审阅的译文也会在不改写历史版本的前提下增强其展示：0.43.10 与 0.43.10-0.nightly.3 获得与其 release ID、源码修订和原始可见正文精确绑定的展示补充，其公开 Release 正文、标签、回执、文件与构建号均不变。
- 框架消息目录现在与官方插件接受同一套语言矩阵校验（`verifyFrameworkCatalogs`）。插件在目录不完整时本就会明确失败，而框架通过 `ResourceBundle` 解析自身界面，缺失目录只会静默回退到英文。新的校验还会拒绝在受验证根目录之外提供目录的框架模块。

### 改进

- Beta 与 Nightly 候选记录其冻结的说明上下文（`schemaVersion: 2`），晋升时将 Stable 说明绑定到精确的 `CHANGELOG.md` 段落以及经过审阅的译文摘要。若候选在检出后改动过 `CHANGELOG.md`、`release-notes/` 或说明模块，现在会直接失败，而不是发布未经审阅的说明。
- Java 卸载程序默认保留 `config.json`，与 NSIS 卸载程序保持一致；未传入该属性的无界面或控制台运行也会保留。
- 已评审的 SDK v9 exact 锚点移至宿主语言修复，使 SDK 契约保留宿主实际应用的语言，而不是启动器的 DISPLAY 区域。规范 API 转储不变，只有 `UiHostCapabilityService.hostLocale()` 默认方法体的字节发生变动；v2–v8 历史锚点仍保持已审计状态。

### 修复

- 插件界面语言现在跟随 Cubism Editor 的“文件 → 环境设置 → 常规 → 语言”中所选语言。启动器的 `-Duser.language` 只用于选择构建的语言版本，运行时不会变化，因此不再被视为宿主语言。由于 Cubism 会在本运行时挂载之后把保存的设置应用到进程默认区域，有效区域会在确认宿主处于 ACTIVE 后重新解析一次；显式的 `-Dturboism.locale` 或 `config.json` 区域设置仍然优先于宿主。
- 框架自身的 `ResourceBundle` 目录现在覆盖完整的 zh-Hans/zh-Hant/en/ja/ko 矩阵。`dev.turboism.ui.panel` 缺少 `messages_en.properties` 与 `messages_zh_Hans.properties`，导致简体中文宿主静默回退到旧的无脚本 `messages_zh.properties`。该目录保留为可选兼容别名，但不再能替代带脚本后缀的目录。
- 工具栏图标改为通过插件提供的显示缩放变体（125/150/175/200%）加载，并解析为单个多分辨率图标，因此安装器入口在高 DPI 显示器上不再由单个未缩放位图绘制。
- Java 卸载程序确认对话框的韩文分支已本地化，不再回退为英文文本；四份 README 模板现在描述卸载程序默认勾选的“保留 `config.json`”，而不再是默认删除。

## [0.43.10] - 2026-09-09

### 新增

- 当前页纹理图集排布，明确缩放契约，并为原生排布集成加入保护检查
- 可捕获的语义历史时间线、稳定导航，以及可配置的核心工具栏图标
- 独立更新 API、GitHub Release 同步、经过校验的流式镜像，以及全局分配的产品构建身份

### 改进

- 采用经评审的 SDK v9 精确发布基线，并将 v8 降级为历史审计。该锚点新增一个展示字段：`CubismOperationEvent` 获得可选的 `label`，使观察到的原生 Cubism Editor 编辑可以携带可本地化的原生编辑名称。该组件追加在 `subjectId` 之后，且明确不作为身份标识。**插件 API 可能需要迁移：** 直接构造 `CubismOperationEvent` 的插件必须传入新增的第五个组件；参见 [SDK v9 评审](sdk/api-contracts/sdk-api-v9-review.md)。
- 采用经评审的 SDK v8 精确发布基线，并将 v7 降级为历史审计。该锚点捕获了 v7 门禁未曾记录的已捕获语义时间线与 UI 接口，以及原生编辑器编辑的四个新增 `CubismOperation` 标识（`SET_HIERARCHY_PARENT`、`DETACH_HIERARCHY_PARENT`、`MOVE_DRAWABLE`、`SET_DRAWABLE_COLOR`，均为追加因此不移动既有常量序数）。**插件 API 可能需要迁移：** `HistoryEntry`、`RuntimeSettings` 与 `PanelView.Toggle` 的构造函数已变更；参见 [SDK v8 评审](sdk/api-contracts/sdk-api-v8-review.md)。
- 将产品发行拆分为只读候选构建和明确授权的受保护 GitHub 发布流程。失败的候选尝试沿用预期版本；只有发布阶段才创建正式附注标签，并直接发布已验证文件，不重新构建
- 采用已审阅的 SDK v8 当前页纹理排布契约
- 新增四语言项目与安装文档，以及限制在任务范围内的本地宿主验证监督

### 修复

- 加固多版本 Scene 面板桥接，加入 Cubism 5.3.03 精确路由，并保留统一的面板清理逻辑
- 将核心工具栏图片调整为与宿主主页图标一致的尺寸
- 加固宿主验证的环境处理，并拒绝格式错误的结果

## [0.43.9] - 2026-09-06

本发布取代未发布的 0.43.4–0.43.8 候选。

### 新增

- 同步编写事务、分组 Glue 写入、稳定的历史条目/事务身份、扩展的类型化 MCP 读写操作、
  从 JAR 文件直接安装插件，以及恢复的包围盒覆盖控制。

### 变更

- 采用经评审的 SDK v7 精确发布基线，同时保留 v2–v6 历史基线。**插件 API 可能需要
  迁移：** `McpHttpConnection` 现在只接受 endpoint/protocol，不再暴露 `authorization()`；
  `HistoryEntry` 携带额外的身份字段；异常枚举序数已变更。见
  [SDK v7 评审](sdk/api-contracts/sdk-api-v7-review.md)。
- 统一调色板工具栏/过滤器贡献，减少重复的模型与历史扫描。
- 本地回环 MCP 服务器不再需要 bearer 认证即可工作。
- 发布包排除托管 fx 运行时字节与仅开发用途的 Turboism with fx 插件。

### 修复

- 稳定快照竞争与异步插件禁用回归测试，修正包围盒草稿元数据，并将原生 Java 安装器
  Full 载荷测试与 Windows 策略覆盖分离，且不削弱生产验证。
- 包含载荷变更前的安装器配置校验、参数批量传输行布局修复、SDK 接口代理覆盖、
  Cubism 5.3.03 纹理图集自动布局钩子选择，以及 MCP/运行时事务与生命周期修复。

## [0.43.8] - 2026-09-06

本发布取代未发布的 0.43.4–0.43.7 候选。

### 新增

- 同步编写事务、分组 Glue 写入、扩展的类型化 MCP 读写操作、从 JAR 文件直接安装插件，
  以及恢复的包围盒覆盖控制。

### 变更

- 统一调色板工具栏/过滤器贡献，减少重复的模型与历史扫描。
- 本地回环 MCP 服务器不再需要 bearer 认证即可工作。
- 发布包排除托管 fx 运行时字节与仅开发用途的 Turboism with fx 插件。

### 修复

- 修正发布验证：确定性的快照竞争与异步插件禁用测试、符合 schema 的包围盒草稿元数据，
  以及带独立 Windows 策略覆盖的原生 OS Java 安装器 Full 载荷测试。这些验证修复未改变
  任何生产验证、生命周期行为或宿主选择器。
- 包含载荷变更前的安装器配置校验、参数批量传输行布局修复、SDK 接口代理覆盖、
  Cubism 5.3.03 纹理图集自动布局钩子选择，以及 MCP/运行时事务与生命周期修复。

## [0.43.7] - 2026-09-06

本发布取代未发布的 0.43.4–0.43.6 候选。

### 新增

- 同步编写事务、分组 Glue 写入、扩展的类型化 MCP 读写操作、从 JAR 文件直接安装插件，
  以及恢复的包围盒覆盖控制。

### 变更

- 统一调色板工具栏/过滤器贡献，减少重复的模型与历史扫描。
- 本地回环 MCP 服务器不再需要 bearer 认证即可工作。
- 发布包排除托管 fx 运行时字节与仅开发用途的 Turboism with fx 插件。

### 修复

- 修正 Cubism 5.3.03 包围盒草稿映射元数据以符合既有 schema；选择器与验证记录不变，
  该草稿仍未验证。
- 使快照竞争与异步插件禁用回归测试确定性化，不改变生产验证或生命周期行为。
- 包含载荷变更前的安装器配置校验、参数批量传输行布局修复、SDK 接口代理覆盖、
  Cubism 5.3.03 纹理图集自动布局钩子选择，以及 MCP/运行时事务与生命周期修复。

## [0.43.6] - 2026-09-06

本发布取代未发布的 0.43.4 与 0.43.5 候选。

### 新增

- 同步编写事务作用域、分组 Glue 写入、扩展的类型化 MCP 读写操作、从 JAR 文件直接安装
  插件，以及恢复的包围盒覆盖控制。

### 变更

- 统一调色板工具栏与过滤器贡献，带生产宿主生命周期处理，并减少重复的模型与历史扫描。
- 本地回环 MCP 服务器不再需要 bearer 认证即可工作。
- 发布包不再包含托管 fx 运行时字节或仅开发用途的 Turboism with fx 插件。

### 修复

- 稳定发布回归覆盖：快照复制竞争显式覆盖已变更与保留的时间戳，重复插件禁用测试等待
  生命周期终态完成而非回调进入。这些测试修复不改变生产验证与生命周期行为。
- 包含载荷变更前的安装器配置校验、参数批量传输行布局修复、SDK 接口代理覆盖、
  Cubism 5.3.03 纹理图集自动布局钩子选择，以及 MCP/运行时事务与生命周期修复。

## [0.43.5] - 2026-09-06

本发布取代未发布的 0.43.4 候选，并包含其下述变更。

### 新增

- 新增同步编写事务作用域、分组 Glue 写入、扩展的类型化 MCP 读写操作、从 JAR 文件直接
  安装插件，以及恢复的包围盒覆盖控制。

### 变更

- 统一调色板工具栏与过滤器贡献，带生产宿主生命周期处理，并减少重复的模型与历史扫描。
- 本地回环 MCP 服务器改为不再需要 bearer 认证即可工作。
- 从所有发布包中移除托管 fx 运行时字节与仅开发用途的 Turboism with fx 插件。

### 修复

- 使快照复制 ABA 回归测试独立于文件系统时间戳精度，覆盖可观察的文件变更与时间戳未变
  的损坏快照，且不削弱生产验证。
- 包含载荷变更前的安装器配置校验、参数批量传输行布局修复、SDK 接口代理覆盖、
  Cubism 5.3.03 纹理图集自动布局钩子选择，以及 MCP/运行时事务与生命周期修复。

## [0.43.4] - 2026-09-06

### 新增

- 新增同步编写事务作用域（包括分组 Glue 写入）与扩展的类型化 MCP 读写操作。
- 新增从 JAR 文件直接安装插件，并恢复包围盒覆盖控制。

### 变更

- 统一调色板工具栏与过滤器贡献，带生产宿主生命周期处理。
- 本地回环 MCP 服务器改为不再需要 bearer 认证即可工作。
- 减少运行时热路径中重复的模型与历史扫描。

### 修复

- 使 Windows 与 Java 安装器在载荷变更前校验或迁移 `config.json`，要求整数 schema 令牌与
  运行时合法的 v1 值，并在应用升级插件选择时不覆盖无关设置。
- 从每个发布渠道移除托管 fx 运行时载荷，并文档化仅开发用途的 Turboism with fx 插件不
  随任何发布包发布；使可选的 Windows fx 解析器测试在其私有 fixture 路径缺失时安全跳过。
- 修复参数批量传输绑定行重叠、SDK 接口代理覆盖与 Cubism 5.3.03 纹理图集自动布局钩子
  选择。
- 处理围绕事务结果与生命周期处理的 MCP 与运行时评审发现。

## [0.43.3] - 2026-09-03

### 新增

- 为 Full 安装器与归档新增托管 Windows x64 fx v0.0.5 产品载荷，启动前按精确大小与
  SHA-256 验证；Windows 产品修复或重装会恢复它，因为上游不提供 Windows 修复归档。
- 在 Core About 窗口的 Turboism 标题下方新增本地化的“For you, a bouquet”献词。
- 在仓库根目录与公开安装器中新增以简体中文为准的完整 Turboism 最终用户运行声明 v2.0，
  包含项目身份、合法 Cubism 授权、用户内容备份与按现状运行四项独立必选确认。
- 新增配置、服务、宿主适配器、插件加载与最终报告的简洁启动阶段耗时诊断。
- 新增 Turboism MCP Connection 窗口，显示当前本地 MCP 地址、其 bearer 令牌、显式复制
  动作，以及有界的进程内连接与请求历史——绝不记录 bearer 值或 MCP 会话标识符。
- 新增稳定的默认 MCP 端口 `43123`，`turboism.mcp.port=0` 仍选择临时端口，并提供已验证
  的 Claude Code、Visual Studio Code 与 Codex CLI 配置示例。
- 新增直接的 **Turboism → fx Settings** 菜单项，使运行时路径、fx 自有 shell 与提供方
  设置在任何 MCP 或 ACP 连接建立之前即可到达。
- 新增已保存的 fx 提供方档案，带模态的新增、编辑、移除与选择对话框：fx 自有的 Vercel、
  Codex 与 Grok 内置项启动其精确的 fx 登录命令，自定义 OpenAI 兼容或自托管端点由
  Turboism 自有的回环适配器服务。
- 为自定义提供方档案新增尽力而为的 `/v1/models` 发现，外加模态的手动模型 ID 对话框。
- 新增自定义提供方 API 密钥的持久存储，密钥只需输入一次：在可用时使用 Windows DPAPI
  为当前用户保护，否则写入插件自身配置目录下的 `auth.json`。
- 新增确定性的 Windows 安装器载荷处理：按 SHA-256 跳过未变化的 JAR 与 fx 产品载荷，
  不再显示空白的完成页。
- 新增 PSD Clip Mask Import 进度报告，并在导入运行期间防止重入。
- 新增持久的安装器启动器与托管子进程诊断，并使 MCP 参数与模型写入结果、显式绑定
  存在性与已提交创建可重试且关联进运行时诊断。

### 变更

- 使 Core About 窗口显示由权威 Gradle 发布版本生成的框架版本。
- 修正发布插件白名单，发布 History Panel 与 PSD Clip Mask Import 业务插件，同时让
  开发 shell、演示与旧占位符留在公开安装器与归档之外。
- 扩大 Windows 安装器并使手动配置器可调整大小、可最大化；安装现在发现精确受支持的
  Cubism Editor 安装（5.2.03、5.3.02 与 5.3.03），选择发现的每个兼容安装，并无头地
  应用所选的 Turboism 快捷方式与哈希守护的官方 BAT 控制项，而不打开配置器。
- 使可选的托管 GraalVM 安装失败或取消可见并记录日志，不中止其余 Turboism 安装。
- 将普通底部状态通知定义为最新消息槽，并通过调用插件的作用域 Turboism 日志器记录
  每次状态调用；紧凑常驻指标保留独立的带键槽位。
- 在每个官方 UI Theme locale 中澄清：应用主题后应重启 Cubism Editor 以确保正确渲染。
- 文档化 Windows fx 候选仅接纳 Turboism 的精确认证数字回环 HTTP MCP 服务器，不宣称
  与官方 Linux/macOS fx 资产在持久会话、原生工具、通用网络、进程或持久化上的对等。
- 使 Clip Mask Viewer 立即显示本地化的加载状态，并将分离的关系索引、计数、分析与图
  投影移出 Cubism 宿主线程，带取消与过期结果守卫。
- 文档化 fx v0.0.5 没有 Claude 订阅登录，Claude Pro/Max 或 Claude Code 订阅不是
  Anthropic API 凭据，因此不提供此类提供方档案；自定义适配器只实现 OpenAI Chat
  Completions。
- 文档化 fx 的 Gateway reasoning 级别被有意不转发到 OpenAI 兼容端点，而不是被翻译成
  猜测的 `reasoning_effort`。
- 使 fx Agent 窗口无需提供方、模型或兼容性选择即可自动连接；缺少提供方/模型设置现在
  只在用户发送提示词时才报告。
- 使自定义提供方默认模型可选，并使 **Use** 立即重连所选档案。

### 修复

- 修复 MCP 工具写入与宿主模型操作竞争的问题：使模型对象创建与 Cubism 运行时保持一致，
  并将运行时诊断资源与连接状态分离。
- 修复 MCP 写入无法存活于重试、参数绑定与已提交创建被误报，以及非法运行时请求被不安全
  记录的问题；写入警告现在与运行时诊断关联，应用的 schema 与绑定运行时对齐。
- 修复 PSD Clip Mask Import 跨命名空间比较模型 id 的问题（不同场景中的相等 id 可能
  误触发）；id 比较现在保持在单一命名空间内，导入报告进度且不再重入。
- 修复载荷 SHA-256 工作后 Windows 安装器完成页空白的问题，并使安装器启动器诊断可靠
  地排空并发带标签的 stdout/stderr。
- 修复 Windows 卸载器配置保留复选框挂在外层向导窗口上的问题——这导致无法可靠交互且
  可能使确认页卡顿。
- 将卸载选项改为明确无歧义、默认启用的“保留 config.json”行为；仅在用户清除该选项时
  才删除配置。
- 在保持精确版本 SDK 准入的同时，恢复 Cubism Editor 5.2.03 上的 History 快照可用性。
- 在 Java 暴露可用 ACL 视图或既有每用户 Windows 路径与重解析点检查时恢复 Windows MCP
  启动，且不记录 bearer 值、端点或私有连接文件路径。
- 新增持久的配置器与托管 GraalVM 子进程诊断，包括并发带标签 stdout 与 stderr 排空，
  使失败的 BAT 集成与可选运行时设置可定位。
- 阻止插件激活创建空的每插件 config、data 与 cache 目录；存储现在只创建首个真实操作
  所需的目录，插件日志使用共享运行时日志，不再产生已过时的 `palette-filter-attach.tsv`
  诊断。
- 移除不受支持的反射式画布外重绘尝试；主题变更现在使用已在 Cubism Editor 5.2.03 与
  5.3.02 上确认的可靠的重启生效行为。
- 修复 fx Settings 窗口要求先建立连接才能打开 fx shell 的问题——这使全新安装上的
  提供方与模型设置无法到达。
- 修复自定义端点适配器拒绝 fx 每次请求都发送的 reasoning 字段、忽略
  `ai-language-model-id` 请求头，以及对无认证自托管端点要求 API 密钥的问题。
- 修复自定义档案从用户常规 fx home 继承无关 Codex 或 Grok 选择的问题：适配的连接以
  插件自有的仅 Gateway fx home 启动，并可选传入 `--model` 参数。
- 修复提示词文本在 Turboism 能报告没有可用提供方或模型之前就被清空的问题。
- 用可独立复现的构建替换此前受限的 Windows fx 载荷（它会拒绝 ACP MCP 服务器），新构建
  仅允许 Turboism 的精确认证数字回环 HTTP MCP 服务器。
- 修复托管 Windows 启动将带路径的 Turboism JVM 选项重解析为独立 `cmd.exe` 命令的问题；
  选项现在作为带引号的参数插入临时 Cubism BAT，继承的 Java 选项变量与过期 Turboism
  集成块被排除在子进程之外。

## [0.43.2] - 2026-08-29

### 修复

- 修复当 Cubism 自带 JVM 不暴露可选 `java.net.http` 模块时，Turboism 运行时启动在 Core
  插件 UI 注册之前中止的问题；托管 GraalVM 控制项现在失败关闭，而不会禁用菜单、工具栏
  条目或面板。
- 将托管 GraalVM 整包下载期限从 20 分钟延长到 4 小时，使缓慢但持续进展的 Windows 下载
  不会被过早终止。

## [0.43.1] - 2026-08-29

### 变更

- 澄清 Windows 配置器的独立快捷方式与快捷方式接管模式，两种模式都保持官方 Cubism BAT
  文件不被修改，生成的托管 `.lnk` 文件名不含空格，并新增安装完成时打开 Turboism 目录
  的选项。
- 在 `logs/installer/managed-graal-install.log` 新增持久的托管 GraalVM 安装进度与诊断。

### 修复

- 修复 Windows PowerShell 启动与托管 GraalVM 辅助脚本因其大小写不敏感的 `$home` 变量与
  PowerShell 只读 `$HOME` 自动变量冲突而失败的问题。
- 修复托管 GraalVM 安装拒绝普通 Windows 文件与目录的问题——OpenJDK 在 Windows 上对
  `BasicFileAttributes.fileKey()` 返回 null；Windows 现在重新校验文件类型、大小与
  重解析点状态，不要求该不可用的键。

## [0.43.0] - 2026-08-28

### 新增

- 面向 Cubism Editor 5.3.03 的精确版本运行时、SDK 可用性、编写、历史、生命周期与
  纹理图集支持。
- Turboism with fx，包括 ACP 集成、持久会话恢复，以及面向受支持的 Linux 与 macOS
  Java 安装器包的经评审托管 fx 运行时载荷。

### 变更

- 扩展 GitHub README，加入受支持的 Windows 宿主平台、精确 Cubism Editor 版本、安装
  选项、当前能力与验证指引。
- 新增回归检查：每个 GitHub Release 必须取自 `CHANGELOG.md` 中匹配版本的段落。
- 将可复用测试支持与跨模块集成测试归入 `testing/`，并将经评审的 SDK API 契约移到
  `sdk/` 域下。
- 将公开 Cubism 兼容性契约与被忽略的本地 Cubism 引用及宿主证据分离，生成的参考报告
  移到 `build/reports/` 下。
- 强化仓库卫生，拒绝强制添加本地引用、研究、AI 评审证据、生成报告与验证输出路径。
- 拆分 Windows 安全与 Java 安装器载荷暂存，使托管原生 fx 运行时限于经评审的 Linux 与
  macOS Java 包。
- 新增确定性发布计划、精确候选与载荷验证、不可变的八资产框架契约，以及协调的
  Plugin Directory 与 Updates 发布顺序。

### 修复

- 保留旧版 SDK 历史实现，同时拒绝过期文档绑定并在不泄漏宿主对象图的前提下保留原生
  绑定。
- 使参数组与绑定批量访问线性扩展，保留每个原生纹理图集条目，并将图集解析器与视图
  绑定到单一宿主世代。
- 加固托管运行时生命周期、ACP 持久会话重放、安装器平台策略与本地 JSON 解析，包括
  纯 ASCII JSON 数字与 Unicode 转义。
- 使 MCP bearer 发布在无法建立仅所有者权限或受保护 Windows DACL 证明时失败关闭。
- 发布前验证精确的插件名册、Windows/Java 载荷分离以及每个发布验证器调用点。

### 已知限制

- Windows 包不包含托管原生 fx 运行时。在原生受保护 DACL 与重解析安全的 bearer 文件
  发布机制可用之前，基于 MCP 的 Turboism with fx 在 Windows 上仍不可用。
- 托管子进程清理在实现原生 Unix 进程组与 Windows Job Object 约束之前仍是尽力而为。
- 已发布二进制未代码签名或公证；下载后请验证随附的 SHA-256 旁挂文件。

## [0.42.0] - 2026-08-25

### 新增

- 面向 Live2D Cubism Editor 的 Java 17 agent 运行时与公开插件 SDK。
- 面向 Cubism Editor 5.2.03 与 5.3.02 的精确版本运行时适配器。
- Windows NSIS 安装器、Lite 与 Full ZIP 发行版，以及跨平台 IzPack 安装器。
- 官方第一方插件包，含插件生命周期、权限、配置、本地化、任务、事件、动作、菜单、
  工具栏、工作区与 Cubism 集成服务。
- 插件包检查与 Plugin Directory 集成，带确定性发布元数据。
- 每个已发布安装器与归档的 SHA-256 旁挂文件。

### 变更

- 将公开 SDK 治理整合为单一发布 API 层级，带精确 Cubism Editor 可用性注解。
- 统一运行时事件投递，加固插件激活、替换、拆除与失败隔离。
- 使发布打包使用单一共享暂存载荷与单一经评审插件白名单。

### 修复

- 稳定实机钩子、公共事件验证、插件关闭、备份继续围栏与最近预览悬停缩略图。
- 加固包检查、配置合并、路径处理、报告脱敏与供应链验证。

### 已知限制

- Windows 是主要的 Cubism 宿主平台。
- Java 安装器可用于 macOS 与 Linux，但不宣称 macOS Cubism 宿主就绪，Linux Cubism
  托管不受支持。
- 本发布中已发布二进制未代码签名或公证；安装前请验证随附的 SHA-256 旁挂文件。

[Unreleased]: https://github.com/Turboism/Turboism/compare/v0.44.0...HEAD
[0.44.0]: https://github.com/Turboism/Turboism/releases/tag/v0.44.0
[0.43.11]: https://github.com/Turboism/Turboism/releases/tag/v0.43.11
[0.43.10]: https://github.com/Turboism/Turboism/releases/tag/v0.43.10
[0.43.9]: https://github.com/Turboism/Turboism/releases/tag/v0.43.9
[0.43.8]: https://github.com/Turboism/Turboism/releases/tag/v0.43.8
[0.43.7]: https://github.com/Turboism/Turboism/releases/tag/v0.43.7
[0.43.6]: https://github.com/Turboism/Turboism/releases/tag/v0.43.6
[0.43.5]: https://github.com/Turboism/Turboism/releases/tag/v0.43.5
[0.43.4]: https://github.com/Turboism/Turboism/releases/tag/v0.43.4
[0.43.3]: https://github.com/Turboism/Turboism/releases/tag/v0.43.3
[0.43.2]: https://github.com/Turboism/Turboism/releases/tag/v0.43.2
[0.43.1]: https://github.com/Turboism/Turboism/releases/tag/v0.43.1
[0.43.0]: https://github.com/Turboism/Turboism/releases/tag/v0.43.0
[0.42.0]: https://github.com/Turboism/Turboism/releases/tag/v0.42.0

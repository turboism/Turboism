[EN / English](CHANGELOG.md) · [ZH / 简体中文](CHANGELOG_zh.md) · [JP / 日本語](CHANGELOG_ja.md) · [KR / 한국어](CHANGELOG_ko.md)

# 更新日志

Turboism 的所有重要变更都记录在本文件中。

格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循语义化版本规范。

## [Unreleased]

### 新增

- Turboism 自身设置窗口的“启动”页新增官方 BAT 启动集成开关（仅 Windows、精确支持版本）：勾选框
  反映安装器的托管状态，开启/关闭委托给经哈希守护的配置脚本（提权、有备份、可恢复）；更改在
  编辑器重启后生效。结合下述安装器默认值变更，从现有 Cubism 快捷方式启动 Turboism 的 legacy
  式体验现已开箱即用，并在设置中保持一键可开关。

### 新增

- SDK 与运行时新增动画工作区支持：插件可以枚举动画文档、工程时间线、轨道、属性与关键帧，
  激活和重命名场景、定位播放进度、应用批量关键帧编辑与曲线类型，以及录制/烘焙求值结果。
  纯 SDK 的 `Motion3Validator` 可报告 motion3 数据中的结构问题。该对象模型已通过
  `animation-timeline-host-probe` 在 Cubism 5.2.03、5.3.02 和 5.3.03 上完成实机验证。
- `PluginContext.availableServices()` 与 `PluginService` 枚举报告运行时实际安装了哪些可选
  上下文服务，插件不再需要探测 getter 或猜测 `unavailable()` 哨兵；默认实现以空集合
  失败关闭（fail closed）。
- `TurboismWindowFactory.installWindowIcon` 安装进程级窗口图标覆盖，使每个插件拥有的窗口
  都使用用户为主工具栏按钮选择的产品图标（文本模式或安装器模式），而不是内置默认图标。
- 性能设置页新增两个启动/编辑控制项：Cubism JVM ZGC 开关（`launcher.zgc`，其默认值见
  “变更”一节）和“禁用自动备份（会削弱崩溃恢复）”开关——后者在编辑期间暂停宿主的周期性
  自动备份计时器，同时保留已配置的间隔与上限。首次覆盖前，观察到的宿主设置会捕获到
  插件状态基线，并在开关关闭时恢复，因此开关开启期间发生崩溃也不会使备份停留在禁用
  状态。手动 `backupNow`/`backupAfterSave` 不受影响。
- 纹理图集（texture-atlas）的 tile 包围盒处理与输出缓存复用，两者均默认开启。临时绘制与
  合成被限制在变换后的 tile 包围盒内，而非整张图集页面；`CTextureAtlas.updateTexture` 上的
  内容签名守卫在相同签名再次出现时提供已保留记录输出的副本。在经评审的真实宿主 fixture 上，
  编辑器打开耗时从约 100 秒降至 15–18 秒，导出 EDT 工作从约 127 秒降至约 15 秒，且页面
  摘要逐位一致；经评审的目标锁定精确受支持的归档，其余一律失败关闭。
- 受守护的、选择性开启的性能实验：原生纹理准备、PNG 归档复用和 Cubism 图像诊断。全部默认
  关闭；实机差分探针对其进行测量，但不宣称 GPU/FPS 或加载时间上的提速。
- SDK 现在直接面向插件开发者发布：每个框架版本都携带 `turboism-sdk-<version>.jar` 及其
  SHA-256 旁挂文件作为仅限 GitHub 的开发者资产，`./gradlew publishToMavenLocal` 产生干净的
  `dev.turboism` 坐标，`templates/plugin-template` 是可针对已发布 SDK 构建的独立工程。
- Warp Deformer Alt Symmetry 插件将包围盒 Alt 语义扩展到 Warp Deformer 控制点：Alt 拖动
  沿垂直网格轴镜像，Alt+Shift 沿水平轴镜像。画布顶部控制条上的贡献开关用于武装镜像轴，
  原生画布提示显示武装状态；镜像后的位置通过模型写入路径提交，使对称移动并入原生
  Undo/Redo。该插件请求 `turboism.cubism.model.read`、`turboism.cubism.model.write`、
  `turboism.ui.toolbar.contribute` 和 `turboism.ui.canvas.hint` 权限，其原生镜像仅在
  已验证的宿主钩子安装后绑定。已验证的 drag-tick 钩子仅接纳经评审的 Cubism 5.2.03、
  5.3.02 和 5.3.03 精确产物——在原生 drag tick 内提供实时镜像预览与单个撤销条目——
  未经评审的宿主则保留发布时的 AWT 回退路径，结果相同。
- `Action.of` 与 `MenuContribution.of` 将单点贡献注册构建为普通的
  `SimpleAction`/`SimpleMenuContribution` 值，插件不再需要为每个动作或菜单项编写匿名类。

### 变更

- Windows 安装器与 Windows 配置工具现在默认勾选 Cubism 官方 BAT 启动集成（此前为需明确勾选）：
  全新 NSIS 安装与配置工具的 Cubism 页会预选经哈希守护的 BAT 修改，使现有 Cubism 快捷方式
  加载 Turboism；用户仍可取消勾选，取消后保持既有的保存时恢复行为。欢迎页、选项标签与
  配置工具文案已在四种界面语言中同步更新。
- 常规 CI 现在对每次 pull request 和推送到 `main` 都同时运行 `devCheck` 与完整的
  `checkCompletedCommit` 套件，显示相关测试使用 Xvfb。覆盖率守卫拒绝跳过、过滤或
  软性失败的门禁；渠道检查现在跟随 `main` 并纳入根构建输入。
- Javadoc 存在性检查改用 JDK 17 编译器树 API 替代基于行的匹配，覆盖接口的隐式 public
  方法与公开可达的嵌套类型。解析或工具失败会使检查失败，而不是静默回退到不完整的
  结果。
- SDK、运行时与官方插件的公共 API 文档现在描述了此前未文档化的声明，并澄清了生命周期、
  所有权、失败与结果语义。
- 内置核心插件并入运行时成为框架 shell（`dev.turboism.shell`）：不再以插件 JAR 形式发布，
  保留的 `turboism.core` 身份仍用于归属配置、任务与日志，插件管理仍将其列为不可移除的
  核心行，插件加载报告不再列出它。共享顶部菜单根仍以 “Turboism” 作为路由键，但显示
  框架本地化的名称。
- 托管启动现在默认让 Cubism 运行在 ZGC（`-XX:+UseZGC`）上：实机 A/B 显示它消除了 G1 的
  秒级停顿（加载约 1.4 秒 → 亚毫秒，写入约 0.6 秒 → 亚毫秒），并将稳态 RSS 降低约
  三分之一。Performance → Cubism JVM 开关在下一次启动时生效，且只存储显式 opt-out。
- CSV 参数批量导入现在在单个编写事务（authoring transaction）内运行，而不是每次写入都
  提交。在重模型实机 A/B 上，Update Parameter Structure 重建次数从 37 降到 4（-89%），
  参数写入中位耗时从 4.71 ms 降到 1.19 ms（-75%），35 次写入突发期间的 EDT 分配
  降低约 99.8%。
- 运行时读取通过 SDK 形态的观察接缝，对每次带版本读取或作用域捕获只观察宿主一次，不再
  对每个访问器重复投影；合成基准测得读路径时间减少约 29%，每次调用分配减少约 41–43%。
- Cubism 启动抑制（跳过更新检查、启动画面与信息对话框）现在默认开启，包括全新安装以及
  Turboism home 尚无 `config.json` 的运行。将任一 `hooks.startup.skip*` 标志设为 `false`
  或启用安全模式可退出；schema 非法的配置仍然失败关闭。Premain 诊断现在缓冲到运行时
  日志槽安装为止，使 `STARTUP_SUPPRESSION_*` 准入代码能够进入会话日志。
- 发布门禁现在将本地化要求提升到完整的 Cubism 语言矩阵。市场插件条目必须在每个声明的
  locale（en、ja、ko、zh-Hans、zh-Hant）中携带 `plugin.name`/`plugin.description`，且
  除非经评审的 zh、ja、ko 发布说明译文存在，否则 release candidate 将被拒绝。框架消息
  目录加入官方插件完整性门禁，该门禁现在也作为 `checkIntegration` 的一部分运行。
- 每个可选的 `PluginContext` 服务访问器现在返回该服务的 `unavailable()` 哨兵，不再从
  getter 抛出 `UnsupportedOperationException`，且每个服务暴露 `isAvailable()` 用于探测。
  哨兵仍然失败关闭：在领域提供结构化失败的地方报告结构化失败（任务提交、宿主读取、
  存储、用户文件、网格编辑、宿主对话框、脚本运行），在没有的地方使用时抛出稳定的
  `UnsupportedOperationException`。`ScriptService#available` 更名为 `isAvailable`，使
  探测在各处使用同一个名字。**插件 API 可能需要迁移：** 捕获访问器
  `UnsupportedOperationException` 的插件应改为调用 `isAvailable()`。
- 十八个语义事件类型从 `dev.turboism.sdk.event.cubism` 迁移到
  `dev.turboism.sdk.cubism.event`；已退役的包会被边界与包布局检查拒绝，使废弃形态
  无法回归。**插件 API 可能需要迁移：** 更新事件 import。
- 安装期宿主钩子在 `META-INF/turboism/hooks` 中声明并由 agent 扫描，不再手工接线：每个
  `HookContributor` 检查自身准入并通过 `HookRegistry` 转发 install/bind/uninstall，因此
  新增钩子只需要一行清单加一个 contributor 类，而不必修改 agent。已验证/失败关闭准入、
  原子记录提取、mesh-mirror premain/bind 生命周期以及 `installation=`/`cleanup=` 报告行
  均保持不变。

### 修复

- 核心外壳菜单（设置、插件管理、日志、关于与检查更新）重新合并到唯一的共享本地化顶层“插件”
  菜单下。此前一次改动把它们路由到本地化显示词而非保留共享根令牌，导致在插件的共享菜单旁
  又生成一个同名顶层菜单。

- 动画文档、场景、轨道与属性现在在整个对象图中强制插件权限、作用域存活性和文档世代。
  关键帧复制拒绝过期或外来来源，同时保留同一插件拥有的活动视图之间的有效复制。
- 动画时间缩放现在对关键帧及其贝塞尔控制柄时间应用相同的仿射变换，而不是仅平移控制柄。
  分数控制柄时间与围绕大原点的恒等缩放保持精度；平移与复制行为不变。
- 保留的 `cubismRead()` 与 Clip Mask 收集服务现在在其所属插件作用域关闭后拒绝访问，
  包括直接的 PSD、clip-mask、texture-atlas、render、workspace 与 theme 读取路径，
  且不影响其他插件。
- 物理编辑器贡献在其插件作用域关闭时自动释放。已关闭的服务不能注册新贡献，重复关闭
  也无法移除更晚注册的贡献。
- 图集缓存复用现在在无法计算输入摘要时回退到原始重建路径，包括超出既有像素预算的
  图像。未知摘要不再被判定相等而返回过期像素；普通未变更输入仍符合复用条件。
- `Motion3Validator` 在收窄 `Version` 与段类型标识符之前检查精确数值，拒绝分数值与
  溢出回绕值，且不会对这些字段中的极端指数抛出异常。数学上等价的合法表示仍然被接受。
- 托管 Graal 安装在开始另一次下载之前，会先调和首次安装中断遗留的、已验证且当前版本的
  孤儿激活标记，避免一次完整下载与探测后收到 `GRAAL_RUNTIME_RECOVERY_REQUIRED`。未知
  标记与既有回滚保护仍然受保护。
- 每个 `DisposableScope` 注册现在拥有由注册句柄与作用域清理共享的单一释放动作，包括
  重复或并发关闭。相等资源的不同注册不再互相移除对方的清理项。
- 接受宿主适配器访问的三到六参数 `CorePluginContext` 便捷构造函数现在通过默认路径组装
  省略的服务，而不是以空指针失败。显式服务与严格构造函数重载保持既有契约。
- 运行时测试 fixture 现在将临时宿主类与同名的 classpath fixture 隔离，在独立的无头 JVM
  中执行无头对话框检查，并关闭自己的设置窗口作用域，消除了相关的 linkage 失败、模态
  挂起与跨测试窗口泄漏。
- Cubism 5.3.03 编辑器模型 DRAFT 映射元数据现在与其引用的验证记录一致。回归检查验证
  精确计数、能力 ID 与记录摘要，不接受有损数值转换；这不启用新的运行时映射。
- 在两个已打开文档之间切换时现在立即发布新绑定的模型；此前旧模型会保持已发布状态，
  使求值读取可能在新绑定身份下追踪——并钉住——错误的模型。
- 编辑器绑定身份缓存不再在整个会话期间保留已关闭的文档图（其缓存身份现在以弱引用
  持有），借用的 Core 模型在其绑定消失后立即释放。
- 若干无界增长路径现在已有界：overlay 按钮侧表按 FIFO 驱逐，recent-preview 内存映射与
  轮询器的发送去重标记被修剪到存活条目，未显示的子树从场景调色板轮询与调色板过滤器
  组件遍历中剔除。
- 反射密集的宿主路径缓存已验证的成员、构造函数与所有者类以及永久未命中；mesh-mirror
  扫描每次派发只索引一次；工作区 `safeSegment` 清洗模式预编译；调色板工具栏在无变化时
  跳过重新布局。
- 日志脱敏将每个模式按其严格需要的字节数进行门控，每条观察到的日志减少约 7 次
  matcher 分配；脱敏输出不变。
- 框架 shell 获得了覆盖 agent jar 的正式 `URLClassLoader`，并带系统类加载器回退；所有
  剩余的窗口构造都经由 `TurboismWindowFactory`。
- 宿主验证与预览打包修复：参数插件 JAR 通过 glob 定位而非过期的带版本文件名，验证探针
  携带其声明的 i18n 目录，工作区包从规范 agent jar 打包，runner 支持本地宿主传输与
  Windows 结果行，close 阶段产物计入自动化结果，插件管理重启钩子被接纳进经评审的
  队列清单，Cubism 5.3.03 精确宿主加入验证队列。
- 钩子注册表在进程退出关闭与迟到登记竞争时不再损坏或重复关闭句柄：关闭遍历快照并对
  每个条目原子认领，遍历中途登记的句柄保持已登记状态而不是被半关闭。
- agent 在引导类路径上读取钩子清单，因此打包在 agent JAR 内的清单无论进程工作目录如何
  都能解析。

## [0.44.0] - 2026-09-11

### 新增

- Turboism 现在针对已部署的发布 API（`api.turboism.dev/v1/releases/stable.json`）检查稳定版
  更新。比较使用安装包内嵌的权威构建号，因此更低的构建永远不会被作为更新提供，构建号
  相同而版本不同会被视为身份冲突而非更新。早于构建号的安装仍只按版本比较，且永远不会
  被赋予虚构的号码。
- 可用更新以 Cubism 原生提示的形式呈现在绘图区域上方——与宿主自身右下角消息使用同一
  表面——而不是 Turboism 停靠面板中的条目。该提示带键，更新的构建会替换先前文本，并在
  更新不再可用时自动消失。点击它打开固定的第一方下载页面；不会打开或安装来自发布源的
  任何 URL，也不会自动下载或执行安装器。
- 更新检查器是非阻塞的，每 24 小时最多运行一次，手动检查始终可用。自动检查在启动设置
  页有独立的持久开关，与 Cubism 自身的更新抑制相互独立。
- 插件可以通过 `UiHostCapabilityService.notifyCanvasHint`、`notifyDismissibleCanvasHint`
  和 `showCanvasHintWhile` 在绘图区域上方显示 Cubism 原生提示，配套类型包括
  `CanvasHintNotification`、`CanvasHintHandle`、`CanvasHintPosition` 与
  `ConditionalCanvasHint`。插件需要新的 `turboism.ui.canvas.hint` 权限才能显示。该能力
  通过已验证的 5.2.03、5.3.02 与 5.3.03 宿主路由按版本路由，在路由无法解析的宿主上
  报告不可用，而不是近似模拟。见 [SDK v10 评审](sdk/api-contracts/sdk-api-v10-review.md)；
  本次修订纯增量，不需要插件迁移。
- 已发布的发布说明现在除简体中文和日文外还携带经评审的韩文文本。发布文档以
  `notesByLanguage` 暴露这些文本，英文仍是网站在译文缺失时显示的回退语言；Nightly 的
  标题与警告以全部四种语言翻译，而原始提交主题保留原语言并显式标注。

### 变更

- 经评审的 SDK 精确基线现在为 v10，钉在 canvas-hint 提交上。该修订新增 42 条 API 记录，
  未移除或修改任何记录；v9 与 v8 仍是每个发布都会运行的历史精确审计。
- WebDAV 备份插件从 `backup` 更名为 `webdav-backup`：其 Gradle 模块与 Java 包为
  `webdav-backup`/`dev.turboism.plugin.webdavbackup`，安装器产物为
  `plugins/webdav-backup.jar`（此前为 `plugins/backup.jar`），插件 id 为
  `dev.turboism.plugin.webdav`（此前为 `dev.turboism.plugin.backup`），其菜单项现在已
  本地化。`backup/webdav.cfg` 中存储的端点设置不受影响。
- 命名了经评审矩阵之外语言的 `release-notes/<version>.json` 文件现在会使发布失败，而不
  再被静默丢弃，因此拼写错误无法带着缺失译文发布。

### 修复

- 发布 API 在 GitHub 不可达时继续提供最近一次已验证的发布快照，而不是对每个渠道回答
  “不可用”。快照最长可用 24 小时；单个渠道的瞬时失败不再丢弃该渠道此前已验证的数据，
  刷新失败保留先前快照。发布仍会立即通知 API，新增的定时监视器报告已确认、去重的
  事故。
- Windows 安装器现在无需预装 Java 即可供应托管 Graal 运行时：直接下载并校验归档，为
  独立安装初始化完整运行时配置，其 Graal 页面不再声称过时的 Java 前置要求。该供应
  路径已在 Windows PowerShell 5.1 与 7 上验证。
- 升级既有安装不再遗留两个 WebDAV 插件条目。两个安装器在托管升级期间按内嵌插件 id
  移除 `plugins/` 中改名前的旧 JAR（因此覆盖任意文件名），旧 id
  `dev.turboism.plugin.backup` 加入已退役/被取代边界：运行时拒绝加载它，插件管理不
  列出它，`config.json` 的 `disabledPlugins` 不再保留它。改名前的 WebDAV 设置对话框
  现在本地化每个标签、按钮、工具提示与状态消息，不再总是显示中文。

## [0.43.11] - 2026-09-11

### 新增

- 安装器现在提供显式语言选择，不再只依赖宿主 locale，韩语加入英语、简体中文与日语
  行列。NSIS 向导在欢迎页之前显示标准语言对话框，并无论宿主语言都列出全部 locale；
  IzPack 安装器附带 `kor` 语言包、其许可资源与模态语言包选择器。所选安装器语言仅
  在安装器范围内生效，绝不写入 `config.json`。
- `GET /v1/downloads/<version>.json` 报告每个发布的下载请求开始次数。官方镜像开始次数
  计入 GitHub 的二进制 `download_count`，响应为每个二进制携带一行 `assets`，包含名称、
  键、SHA-256、official、GitHub 与总计值，与发布总计对账一致。校验和旁挂文件、
  HEAD/304、失败请求、非零续传区间与 verification 前缀流量均被排除，未知来源保持
  `null` 而不是输出虚构的零。
- Stable、Beta 与 Nightly 发布现在携带经评审的简体中文与日文说明（`notesByLanguage`），
  英文作为回退，网站在本地选择语言。摘要不再匹配精确英文段落的译文将被拒绝而不是
  复用。Nightly 在候选准备期间冻结其已发布的祖先基线与真实提交主题，使之后落入的
  提交无法改变已构建候选的内容。
- 经评审的译文还可以充实历史发布而不改写它们：0.43.10 与 0.43.10-0.nightly.3 获得绑定到
  其精确发布 ID、源修订与原始可见正文的显示补充，其公开 Release 正文、标签、回执、
  文件与构建号均不受影响。
- 框架消息目录现在由 `verifyFrameworkCatalogs` 按与官方插件相同的 locale 矩阵要求。
  插件目录不完整时已会响亮失败；框架通过 `ResourceBundle` 解析其界面资源，缺失目录
  此前会静默降级为英文。新门禁同时拒绝框架模块携带已验证根目录之外的目录。

### 变更

- Beta 与 Nightly 候选记录其冻结的说明上下文（`schemaVersion: 2`），晋级时将 Stable 说明
  绑定到精确的 `CHANGELOG.md` 段落加上经评审的译文摘要。检出后 `CHANGELOG.md`、
  `release-notes/` 或说明模块发生变化的候选现在失败关闭，而不是发布从未评审过的说明。
- Java 卸载器默认保留 `config.json`，与 NSIS 卸载器一致；未带该属性的无头或控制台运行
  同样保留。
- 经评审的 SDK v9 精确锚点移动到宿主 locale 修复，使 SDK 契约保留应用的语言而非启动器
  的 DISPLAY locale。规范 API dump 不变；只有
  `UiHostCapabilityService.hostLocale()` 默认方法体的字节发生了移动，v2–v8 历史锚点
  保持已审计状态。

### 修复

- 插件 UI 语言现在跟随 Cubism Editor 的 File → Environment Settings → General →
  Language 中选择的语言。启动器的 `-Duser.language` 只选择构建的语言版本且在运行时
  从不改变，因此不再被视为宿主语言。由于 Cubism 在此运行时附加之后才把保存的设置
  应用到进程默认 locale，有效 locale 在已验证宿主进入 ACTIVE 后重新解析；显式的
  `-Dturboism.locale` 或 `config.json` locale 仍优先于宿主。
- 框架自身的 `ResourceBundle` 目录现在携带完整的 zh-Hans/zh-Hant/en/ja/ko 矩阵。
  `dev.turboism.ui.panel` 此前缺少 `messages_en.properties` 与
  `messages_zh_Hans.properties`，导致简体中文宿主静默回退到旧的、无书写系统后缀的
  `messages_zh.properties`。该目录保留为可选的兼容别名，但不能再顶替带书写系统后缀的
  目录。
- 工具栏图标通过插件携带的显示缩放变体（125/150/175/200%）加载并解析为单个多分辨率
  图标，因此安装器条目在高 DPI 显示器上不再从单个未缩放位图绘制。
- Java 卸载器确认对话框的韩语分支现在已本地化，不再回退到英文文本；四个 README 模板
  现在描述卸载器默认保留 `config.json` 的复选框，而不是默认删除的描述。

## [0.43.10] - 2026-09-09

### 新增

- 当前页纹理图集打包，带显式缩放契约与受守护的原生打包集成。
- 捕获的语义历史时间线，带稳定导航与可配置的核心工具栏图标。
- 独立的发布 API、GitHub 发布同步、已验证的流媒体镜像，以及全局分配的产品构建身份。

### 变更

- 采用经评审的 SDK v9 精确发布基线，v8 降为历史审计。该锚点新增一个展示字段：
  `CubismOperationEvent` 获得可选的 `label`，使观察到的 Cubism Editor 原生编辑可以携带
  可本地化的原生编辑名称。该组件追加在 `subjectId` 之后，且明确不是身份。**插件 API
  可能需要迁移：** 直接构造 `CubismOperationEvent` 的插件必须传入新的第五个组件；见
  [SDK v9 评审](sdk/api-contracts/sdk-api-v9-review.md)。
- 采用经评审的 SDK v8 精确发布基线，v7 降为历史审计。该锚点捕获了 v7 门禁从未记录的
  已捕获语义时间线与 UI 表面，以及四个新的原生编辑器编辑 `CubismOperation` 身份
  （`SET_HIERARCHY_PARENT`、`DETACH_HIERARCHY_PARENT`、`MOVE_DRAWABLE`、
  `SET_DRAWABLE_COLOR`，追加方式使既有常量序数不移动）。**插件 API 可能需要迁移：**
  `HistoryEntry`、`RuntimeSettings` 与 `PanelView.Toggle` 构造函数已变更；见
  [SDK v8 评审](sdk/api-contracts/sdk-api-v8-review.md)。
- 产品发布拆分为只读候选构建与显式受保护的 GitHub 晋级。失败的候选尝试复用预期版本号；
  只有晋级才创建官方注解标签并在不重建的情况下发布已验证字节。
- 采用经评审的 SDK v8 当前页纹理布局契约。
- 新增四语言项目与安装文档，以及任务内闭环的本地宿主验证监督。

### 修复

- 加固多版本 Scene 调色板桥接，新增 Cubism 5.3.03 精确路由，保留统一调色板清理。
- 使核心工具栏图像与宿主主页图标尺寸匹配。
- 加固宿主验证环境处理与对畸形结果的拒绝。

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

# 当前插件与 Legacy 能力对照

核对日期：2026-10-17。Dalsoo 后续更正：用户指出遗漏后，核对到三宿主真实排版记录，已将该插件补入正式包与市场发布配置。当前工作树 `a7b4912a9`；只读参考 `/opt/dev/projects/turboism-legacy`（`2411b35`）和 `/opt/dev/projects/turboism-legacy-windows`（`5555101b`）。

本报告以源码、插件入口、发行/市场清单为依据；参数锁定的逐行/组锁与持久化实机结果及限制见 [验收记录](validation/parameter-lock/ROW-UI-ACCEPTANCE.md)。“已有”表示找到实现和入口，不表示所有支持版本都已通过实机验证；版本与既有实机证据见 [插件兼容表](compatibility/cubism/plugins_zh.md) 和 [能力兼容表](compatibility/cubism/capabilities_zh.md)。未找到实现的结论限定于这次核对的当前工作树。

## 结论

- 当前有 **30 个插件模块，26 个列入发行清单，26 个列入市场发布配置**；后二者集合一致。市场配置不等于线上市场已经发布成功。
- 另有 **4 个未随包发布的模块**：`parameter-lock`、`demo`、`parameter`、`project-inspector`。
- Legacy 主仓库的内置目录有 **16 项（含 Core）**；Windows 快照有 **13 项（含 Core）**。旧仓库还有 demo 源码，不能把内置目录数与新版插件模块数直接相减。
- 新版并未完整覆盖 Legacy。主要缺口是默认参数模板、完整参数批量工作流、PSD 重导绑定修复与画布扩展、Warp 贴合 ArtMesh、便签/Todo。
- 参数锁定已新增独立 `parameter-lock` 插件，复用原生部件 Tab 16px 锁图标，按模型 GUID 持久化并在模型加载后恢复；目前是开发交付，尚未随包分发；逐参数行已在 5.2.03/5.3.02 实机通过，5.3.02 已通过真实单层组混合状态、全部子参数锁定/恢复及独立会话持久化；深层嵌套组实机仍未覆盖。
- 旧插件消失不一定代表能力消失：日志过滤、浮窗停靠、保存目录分离、性能优化、更新检查和主界面贡献已有运行时承接。

Dalsoo 是多版本插件：多边形排版能力的兼容范围是 **5.2.00–5.3.04**，已有实机记录覆盖 **5.2.03、5.3.02、5.3.03**。插件元数据的加载声明为 5.0.00–5.3.04 共 20 个版本，不能把可加载范围当作多边形排版能力范围，也不能当作 20 个版本均已实机通过。正式清单已补入该插件，线上发布仍需独立执行。

用户已批准删除 `bounding-box`、`context-menu`、`project-panel`、`psd-import` 四个占位模块；对应运行时扩展能力保留，删除不代表 Legacy 缺口已补齐。

## 当前全部插件

下表插件 ID 省略共同前缀 `dev.turboism.plugin.`；“发行”以 [release-plugins.txt](packaging/release-plugins.txt) 为准。

| 模块 | 当前能力 | 交付情况 / 关键边界 |
|---|---|---|
| `acp` | 编辑器内 Agent 对话、ACP 会话、MCP stdio 接入 | 发行；需用户安装兼容 Agent |
| `mcp` | 模型、参数、绑定、历史、命令工具及资源/提示词 | 发行；受权限与宿主能力约束 |
| `command-palette` | Ctrl+K 搜索并执行 Editor 命令和插件操作 | 发行；主要是无参命令 |
| `history-panel` | 原生历史展示、语义说明、撤销/重做游标跳转 | 发行；按已验证历史类型解码 |
| `perf-stats` | CPU、FPS、JVM 内存等性能面板/窗口 | 发行；采样诊断 |
| `webdav-backup` | 备份同步到 WebDAV | 发行；旧插件 ID `backup` 已退休，备份基础服务在运行时 |
| `protected-export` | 受保护导出和导出设置流程 | 发行；需对应宿主服务 |
| `ui-theme` | 主题应用、导入导出、编辑、删除、恢复原生外观 | 发行 |
| `cubism-tab-filter` | 参数、变形器、Scene、Log 筛选框 | 发行；Log 级别过滤由运行时承接 |
| `scene-palette-enhancer` | Scene 排序、拖拽手动顺序和持久化 | 发行 |
| `palette-label-style` | 面板标签颜色、自定义选色、按项目恢复 | 发行；原生颜色与临时外观覆盖分开处理 |
| `recent-preview` | 工程打开/保存后的缩略图和 Recent Files 悬停预览 | 发行 |
| `clipmask-viewer` | 剪贴蒙版关系图、表格、筛选、选择高亮 | 发行；只读 |
| `psd-clip-mask-import` | 从 PSD 导入剪切关系 | 发行；不能替代绑定修复和画布扩展 |
| `external-psd-edit` | ArtMesh 原图导出 PSD、外部编辑、监听保存后原生替换 | 发行；不等于旧版错配绑定修复 |
| `frame-animation-import` | 精灵表/图片序列导入、部件与网格生成、参数绑定 | 发行 |
| `model-import-3d` | 3D 模型查看与原生可编辑模型导入工作流 | 发行；任意角度视觉等价未建立，专项映射仍有进行中内容 |
| `mesh-edit-mirror-axis-enhance` | 网格检查、镜像轴角度控件 | 发行；网格镜像 Hook 在运行时，不是四等分保护插件 |
| `selection-brush` | 建模选区笔刷 | 发行；有宿主版本边界 |
| `warp-deformer-alt-symmetry` | Warp 控制点 Alt 对称拖拽、选区权重镜像和轴切换 | 发行 |
| `boundingbox-warp-mirror` | 包围框入口、四方向 Warp 整体镜像、子物体形状补偿 | 发行；不支持 ArtMesh/旋转变形器作为镜像目标，不支持控制点子集 |
| `parameter-batch-transfer` | 从一个选中对象向多个目标转移参数绑定，可反转 | 发行；不覆盖完整参数工具集，也不承诺单一合并撤销组 |
| `physics-editor` | Physics Settings 组全选和重开后的选择保留 | 发行；不是完整物理模拟器 |
| `atlas-maxrects-bssf` | 原生图集自动排版接入、MaxRects-BSSF、倍率/旋转策略 | 发行；当前页原生入口与完整图集 SDK 请求有不同边界 |
| `texture-atlas-stats` | 图集编辑器图像数量统计 | 发行；只读 |
| `atlas-dalsoo-polygon` | 凹轮廓图集排版、旋转、固定项与回滚 | 发行；已有三宿主真实排版与优化复测记录，详见 HOST-PERF-MATRIX.md |
| `parameter-lock` | 参数/文件夹锁定、写入拦截、模型 GUID 持久化与加载恢复 | 未发行；已有离线回归及 5.2.03/5.3.02 实机锁定矩阵、5.3.02 跨会话恢复证据 |
| `parameter` | 参数值 CSV、绑定反转与转移 | 未发行；CSV 默认读文件链路及导出交付不完整 |
| `project-inspector` | 项目/工作区快照诊断窗口 | 未发行；只读开发预览，不能代替旧项目面板 |
| `demo` | SDK 菜单、工具栏、事件等注册示例 | 未发行；开发示例 |

各模块详情见对应 `plugins/<模块>/README.md` / `README_zh.md`；模块集合见 [settings.gradle.kts](settings.gradle.kts)。

## Legacy 插件逐项对应

旧 ID 省略 `turboism.` 前缀。Legacy 内置清单来自两个仓库各自的 `tool/plugin/builtin/BuiltinPluginCatalog.java`。

| Legacy 插件 | 新版承接位置 | 判断 |
|---|---|---|
| `core` | bootstrap、runtime、SDK、Core/Editor 适配 | 已有，框架重新组织 |
| `parameter` | `parameter`、`parameter-batch-transfer`、`parameter-lock`、参数对象/绑定 API | 部分恢复；锁定已有开发交付，模板、完整批量编辑及 CSV 结构往返仍有缺口 |
| `psd-import` | `psd-clip-mask-import`；旧同名占位模块已删除 | 只恢复剪切关系导入；绑定修复、扩画布仍缺失 |
| `project-panel` | runtime 嵌入面板/浮窗/工作区 + `recent-preview` | 部分恢复；旧专用面板、便签/Todo 未完整恢复，同名占位已删除 |
| `bounding-box` | overlay 服务 + `boundingbox-warp-mirror` | 部分恢复；旧 Shrink/贴合 ArtMesh 未恢复，同名占位已删除 |
| `context-menu` | runtime ContextMenuRegistry + 各功能插件 | 扩展机制和若干操作已有；同名占位已删除，旧菜单全集未覆盖 |
| `render-opt` | runtime 的渲染/GL/图像/模型更新优化 | 已有替代实现；旧插件不再独立发行，不能推导全部旧优化行为一一等价 |
| `perf-opt` | runtime 性能/JVM 配置 + `perf-stats` | 已有；统计和优化拆开 |
| `mesh-edit` | runtime mesh 镜像 + 镜像轴插件、选区笔刷等 | 部分恢复；四等分阈值保护未找到对应实现 |
| `clip-mask` | `clipmask-viewer`、剪贴蒙版 API | 已有查看能力；修改由其他工作流承担 |
| `log-filter` | `cubism-tab-filter` + runtime `PaletteLogFilter` | 已有；主 Legacy 有该独立内置项，Windows 内置目录没有 |
| `texture-atlas` | runtime 图集服务 + MaxRects + stats + polygon | 已有多种承接实现；不等于逐项确认旧优化等价 |
| `ui-theme` | `ui-theme` + runtime appearance | 已有；全局替换原生选色器需单独核对 |
| `main-toolbar` | runtime shell、主工具栏贡献服务 | 基础机制/主界面入口已有；旧按钮布局不是一一复制 |
| `toolbar` | runtime 菜单、横/纵工具栏、面板等贡献服务 | 已有框架能力 |
| `update` | runtime 更新服务和插件自动更新 | 已有；主 Legacy 有该独立内置项，Windows 内置目录没有 |

两个 Legacy 快照共同能力主要集中于参数、PSD、镜像、面板、主题与性能。Windows 快照缺少 `log-filter`、`clip-mask`、`update` 三个内置目录项；但它仍有 `turboism.clip-mask` 外部源码插件。这里区分“内置目录项”和“源码/外部插件”，避免误判该快照完全没有蒙版插件。

## 具体能力缺口

“未找到”指没有找到能替代旧行为的当前实现/用户入口；基础 SDK 能修改类似对象，不自动算该工作流已迁移。

| 旧能力 | 当前情况 | 缺口性质 |
|---|---|---|
| 默认参数列表：选择、编辑、分组/文件夹/颜色、从模型或文件导入、手动应用、新建时替换 | 有参数结构 API 和原生命令 `SET_DEFAULT_PARAMETERS`，未找到 Turboism 模板管理器及相应操作链 | 工作流未恢复；原生命令名称不等于旧模板编辑器 |
| 参数锁定 | 新增 `parameter-lock`：参数/文件夹菜单、写入 Hook 与模型 GUID 持久化；已接入逐行锁及参数组递归锁 | 已有开发交付；尚未随包分发；5.2.03/5.3.02 锁定与 5.3.02 跨会话恢复实机通过；保留同一 GUID 的副本共享锁记录 |
| 参数批量调整、复制、Q 菜单控制点调整/变更 | 已有绑定转移、反转等局部能力 | 缺少旧版完整批量工具集，不能用 batch-transfer 名称视为全部迁移 |
| 参数结构 CSV 中 Morph/Combine 等标记导入导出 | 当前 `parameter` CSV 明确为 `id,value` | 仅参数值交换；旧结构/标记往返未恢复 |
| 参数 CSV 正常文件导入导出 | 默认构造注入 `CsvContentProvider.unavailable()`；导出只保留 `lastExportCsv` | 已实现部分逻辑但用户链路不完整，且未发行 |
| PSD 重导后绑定修复：名称+位置优先、可忽略图层 ID | 旧 `psd-import` 占位模块已删除 | 工作流仍缺失；`external-psd-edit` 的显式原图替换不能替代错配修复 |
| 根据 PSD 图层 AABB 扩展画布 | 旧 `psd-import` 占位模块已删除 | 工作流仍缺失；能通过 SDK 改画布尺寸也不等于按 PSD 范围自动扩展 |
| Warp Shrink / 贴合 ArtMesh 轮廓 | 旧版有 `WarpShrinkwrapGeometry` 和红按钮路由；新版未找到对应实现 | 未恢复；原生 `FIT_ART_MESH_CONTOUR` 命令不能直接认作旧 Warp shrinkwrap |
| 四等分阈值保护与用户阈值设置 | 旧文档/UI 有阈值入口；新版未找到相应保护链 | 未找到对应功能；三角化加速、网格内存预算是不同能力 |
| 参数便签、Todo | 旧文档及面板入口存在；新版未找到专用面板实现 | 未恢复用户工具；旧 `createStickyParameterPanel` 实际创建 `TodoPanel`，旧便签完整语义本身也需核验 |
| 变形器应用到子物体 | 当前有 `Deformers.applyToChildren` 及 Editor 原生实现 | SDK 能力已恢复；旧专用右键入口需另核，不应标成底层缺失 |
| 原生 Tab 拆分、浮窗并回 | runtime 原生 tab floating bridge 和 host operations 已有 | 已承接；旧 project-panel 占位不代表浮窗能力缺失 |
| 原生 Tab 复制/多实例 | 旧文档明确复制按钮暂时关闭；新版未找到复制工作流 | 不列为“旧版稳定能力丢失”，单独作为待建设能力 |
| 全局替换 Cubism 原生选色器 | 当前有 SDK 选色器，标签配色插件使用该服务 | 部分相关能力已有；未找到与旧全局替换策略等价的 Hook |
| 最近工程预览图管理 | 捕获与悬停预览已有；旧文档的打开缓存目录、清选中/全部缓存入口未逐项确认 | 核心已恢复，管理入口待核 |

参数 CSV 的 README 有过期描述：其声称写入非事务化，但当前 `ParameterCsvService` 已调用 `cubism.authoringTransactions().execute(...)`，并为不可用情况保留降级路径。不能把该 README 原话直接当成当前“完全无事务”的结论；默认文件读取不可用和无导出文件交付则由入口与服务源码直接证实。

## 当前框架能力盘点

| 能力域 | 当前实现范围 | 对 Legacy 对照的意义 |
|---|---|---|
| 模型对象 | Canvas、Parameter、Part、Drawable/ArtMesh、Warp/Rotation Deformer、Glue、颜色、几何、层级、绑定、关键形 | 为新旧建模插件提供统一对象 API；不等于旧工具 UI 已恢复 |
| 编辑与历史 | Editor 写入、事务、Undo/Redo、结构化/语义历史、事件与 Hook | 有运行时实现；不同成员仍有版本边界 |
| 项目与工作区 | 活动项目/文档、选择、工作区读取/切换/默认布局、Dock 布局快照、浮窗 | 项目面板占位之外已有大量底层能力 |
| UI 扩展 | 菜单、右键、工具栏、面板、Overlay、画布提示、状态通知、对话框、选色器、设置页 | 多数旧独立 UI 插件的基础机制并入 runtime |
| 文件与存储 | 用户文件授权、插件配置/存储、文件选择、工程/导出目录分离、最近文件与预览 | 有基础服务，缺失的文件链路仍需实际消费方接通 |
| 纹理与 PSD | 原图/模型图像/图集关系、PSD 导出替换、剪切关系、图集布局/统计 | PSD 修复与按图层扩画布仍有明确缺口 |
| 性能与稳定性 | 模型更新跳过/增量更新、uniform 缓存、上传和冗余状态消除、输入路径优化、矩阵/投影暂存、图像归档复用、纹理上传准备、浮点解析缓存、插值/三角化优化 | 旧 render/perf 插件不独立存在，不代表性能能力消失；各优化准入/开关不同 |
| 启动与部署 | 精确版本适配、启动抑制、JVM/GraalVM 路径、内存 profile、ZGC、Full/Thin 包、更新、插件管理 | 与旧配置器/内置插件组织方式不同 |
| 开发与自动化 | SDK、事件处理器、任务调度、诊断、Graal 脚本服务、MCP、ACP | 已有能力范围扩大；脚本服务不是单独发行插件 |

新扩展明显包括 MCP/ACP、命令面板、历史面板、选区笔刷、外部 PSD 编辑、序列帧导入、3D 工作流与受保护导出。其余如 Scene 排序、主题、标签色、预览图和物理组辅助在 Legacy 内部也能找到线索，不宜简单按新插件名称统称“全新能力”。

## 建议补齐顺序

1. **先补参数日常链路**：接通 CSV 文件读写、确认结构 CSV 的 Morph/Combine 兼容范围，再恢复模板管理与批量控制点工具；参数锁定已完成逐行及单层组实机验收，继续补深层嵌套与物理输入覆盖；不要仅把当前 `parameter` 加进发行清单就宣称完成迁移。
2. **恢复 PSD 两项独立工作流**：重导后绑定修复、按 PSD 图层扩画布；明确各自验证条件与 Undo/保存重开行为。
3. **恢复建模保护及贴合**：四等分阈值保护、Warp 贴合 ArtMesh，补充旧右键菜单与 apply-to-children 的用户入口核验。
4. **补面板辅助**：Todo、经确认的参数便签语义、预览缓存管理；原生 Tab 复制作为独立需求评估。
5. **清理状态表达**：四个占位模块保留明确 development-only 标记；同步参数 README 与源码事务实现；polygon 已补入发行清单并纠正过期验收说明。

## 关键证据入口

- 当前模块：[settings.gradle.kts](settings.gradle.kts)；发行：[release-plugins.txt](packaging/release-plugins.txt)；市场配置：[market-plugins.json](packaging/market-plugins.json)；退休 ID：[retired-plugins.txt](packaging/retired-plugins.txt)。
- 参数锁定：[插件说明](plugins/parameter-lock/README_zh.md)、[原生资源与持久化身份证据](validation/parameter-lock/NATIVE-RESOURCE-IDENTITY.md)。
- 参数缺口：[ParameterPlugin](plugins/parameter/src/main/java/dev/turboism/plugin/parameter/ParameterPlugin.java)、[ParameterCsvService](plugins/parameter/src/main/java/dev/turboism/plugin/parameter/service/ParameterCsvService.java)、[参数插件说明](plugins/parameter/README.md)。
- 占位模块：[PSD](plugins/psd-import/README.md)、[右键菜单](plugins/context-menu/README.md)、[项目面板](plugins/project-panel/README.md)、[包围框](plugins/bounding-box/README.md)；均核对了各自入口源码。
- 运行时承接：[日志过滤](runtime/src/main/java/dev/turboism/ui/filter/PaletteLogFilter.java)、[浮窗](runtime/src/main/java/dev/turboism/ui/panel/VerifiedEmbeddedPanelHostOperations.java)、[保存目录](runtime/src/main/java/dev/turboism/shell/SaveDirectoryHistoryProvider.java)、[JVM 设置](runtime/src/main/java/dev/turboism/config/CubismJvmSettingsFileService.java)、[应用到子物体 API](sdk/src/main/java/dev/turboism/sdk/cubism/model/Deformers.java)。
- Legacy 主仓库：`docs/guide/features.md`、`parameter-workflow.md`、`panel-workflow.md`、`psd-workflow.md`、`mirror-mesh-workflow.md`、`configuration.md`；并核对参数 lock、CSV importer、PSD workflow、WarpShrinkwrapGeometry 和红按钮路由源码。
- Legacy Windows：`README.md`、`tool/plugin/builtin/BuiltinPluginCatalog.java` 及外部插件源码目录。

本次尝试读取用户指定的 `/root/.codex/RTK.md`，普通与提权读取均返回文件系统 `Permission denied`，未能应用该文件中的未知指令；不影响本报告的只读代码核对。

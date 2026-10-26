# ACP 自动建模能力与接口完整性审计

日期：2026-10-24。原审计基准：`a7486647d66bbfb5e0c415523c38d5f0fcd1feac`，含原审计会话的工作区修改。

性质：需求矩阵、源码核对、接口设计建议；不是功能已交付声明，也不是全部宿主版本的验收报告。审计整理阶段只新增文档，不修改生产代码、不启动或修改模型、不连接 Cubism。后续实现与测试另记于 [实施验证记录](verification.md)，不追溯改写本审计的历史状态。

当前工作区已有后续实现和实机证据；最新统一入口见[当前建模检查点](current-readiness.md)，逐轮记录至验证第65节。本文13工具与矩阵状态仅表示原审计基线。

配套文档：[实施清单与验收门槛](implementation-plan.md)、[Paseo harness 通信参考与验收表](harness-reference.md)、[已有实现切片验证记录](verification.md)。

## 1. 证据边界与工作区差异

本文根据用户提供的审计记录整理。原审计会话的未提交修改不属于基准提交本身，不能仅凭该提交复现。整理时工作区 HEAD 为 `63993968018d88e8158303dd6f363bd4411f2721`，初始工作区干净；原基准提交对象存在，但本次未进行完整跨提交复核。

| 内容 | 原审计记录 | 本次整理的证据边界 |
| --- | --- | --- |
| Cubism 实时目录 | 未发现带窗口的进程；stdio initialize 返回 `-32000`，没有成功目录 | 本次未连接宿主；不将这段历史描述为本次连接结果 |
| Pi 资源桥接 | 原会话已有 list/templates/read 工作区补丁 | 整理前的 `pi-mcp-extension.mjs` 仅有 tools 目录桥接，未发现上述资源桥接；应重新实现或找回补丁 |
| 权限摘要 | 原会话修复 message 字符串导致 `<unavailable>` | 属历史工作区记录，本次未复验，也不宣称已包含在当前 HEAD |
| 离线采集器 | 原会话有 `collect_live_catalog.py` 和 2 项测试 | 整理前本目录及脚本均不存在；本次不补造脚本、成功目录或测试结果 |
| 历史 ACP 测试 | 200 项：189 通过、4 失败、7 跳过；19 项 native transport 与 Pi 专项通过 | 用户提供的历史记录，不是本次运行或新增接口验收 |
| 本地证据与链接 | Windows `D:\Turboism-maintenance\acp-evidence`；原文 48 个本地链接检查通过 | 不是仓库通用 CI 证据；原文链接检查结果不适用于本文 |

整理前抽查确认：SDK 有 `CubismModel.renderPixels` / `exportMoc` 声明；缩略图请求上限为 150×150；`NativeAgentProvider.tool` 对非字符串结果 JSON 化后作为 text 输出；Pi 桥接存在 15 秒请求超时和 8MiB 消息限制；MCP 中文说明顶部写 11 个工具，正文列 13 个。其余矩阵保留原审计判断，仍需逐项源码及宿主准入复核。

## 2. 结论与纠正

主要问题是 **SDK → MCP → Agent → ACP 界面的能力链没有完整接通**。

原审计已定位的 SDK/Runtime 能力包括：网格与逐关键形几何、Warp/Rotation 存储关键形编辑、模型原生渲染、图像注册与绑定、图集布局应用、部分动画编辑和原生 MOC 导出。存在底层原语，不代表已有可靠自动建模算法或当前生产 MCP 入口。

仍需设计或补验收：文件授权远程会话桥接、长任务/取消与结果核对、跨对象安全事务、几何/视觉质量评估、物理创作写接口、场景生命周期与完整交付流程。

两处必须纠正的过宽结论：

- **有原生渲染能力。** `CubismModel.renderPixels(width,height)` 已有 SDK/Runtime 路线。`ScreenshotCaptureService` 是另一种 recent-file 预览，最大 150×150，不能代替眼嘴细节验收。两者均未由本审计所述生产 MCP 暴露给 Agent。
- **不能说 MCP 完全不能保存。** `EditorCommand.SAVE` 的 ID 为 `save`，direct 分派没有统一排除它；仅当实时 `availableDirectCommands` 声明且权限/宿主允许时，才可使用 `turboism.editor_commands.execute({kind:"direct",commandId:"save"})`。带授权句柄的 OPEN/SAVE_AS 工作流未开放；枚举存在不能代替当前会话发现或保存完成验证。

## 3. 范围与状态

### 三种目标

| 层级 | 目标 | 必需闭环 |
| --- | --- | --- |
| L1 | 现成 CMO3 增量建模 | 读取拓扑与关键形 → 头部/眼嘴/身体编辑 → 渲染检查 → Undo → 保存重开 |
| L2 | 已拆分 PSD/图层从零绑定 | 导入 → 语义映射 → 图像绑定/网格 → 变形器/参数/关键形 → 修正 → 图集/导出 |
| L3 | 单张立绘到完整交付模型 | 另含语义分层、遮挡补画、风格一致性、物理/表情/动作及应用适配 |

完整清单覆盖三种目标的工程链路，不表示覆盖 Cubism 全部工具、版本、风格或参数组合。L3 不能承诺仅靠开放 Cubism API 自动解决。

| 标记 | 含义 |
| --- | --- |
| E | 已有生产 MCP 入口；仍按宿主、权限、对象和操作动态准入 |
| P | 部分公开，或只可发起 UI 命令，缺带数据与验证的完整工作流 |
| B | 原审计查到 SDK/Runtime 实现，缺 MCP 封装；不是实机通过标记 |
| S | 有 SDK 声明或局部能力，目标场景完整 Runtime 支持待核验 |
| N | 当前已审计接口不足，需要新增类型化契约、实现或证据 |
| A | 主要是 Agent、算法、质量与编排工作，不是单个 setter |

优先级：P0 = L1 真实头部编辑闭环；P1 = L2 素材到新模型；P2 = 完整角色交付扩展。下表共 **59 项**，均为原审计状态，非本次新增接口的通过证明。

## 4. 原审计时的生产 MCP 表面

生产装配器/Domain 与原审计记录列出 13 个工具，统一前缀 `turboism.`：

`model_objects.apply`、`parameters.apply`、`parameter_bindings.apply`、`glues.read`、`glues.write`、`textures.read`、`textures.write`、`history.read`、`history.undo`、`history.redo`、`transaction.execute`、`capabilities.read`、`editor_commands.execute`。

目录记录为 15 个资源、2 个模板、8 个提示词。中文说明顶部仍写 11 个工具，应另行修正文档；本次保持生产插件文档不变，仅记录差异。

参数资源可读值和绑定点，不能提供网格坐标、Warp 控制网格或 Rotation 各 form。对象 create 可带初始化几何，不等于已有对象逐关键形读写。direct 命令依赖 UI 模式、选中对象和参数状态，不能以枚举存在替代无对话框、参数化、可验收接口。

覆盖账本仅跟踪明确列出的十三个 SDK owner，不能证明整个 SDK 已覆盖，也不能因 Drawable/Warp/Rotation 未列入就认定能力不存在。

## 5. 端到端能力矩阵

### 5.1 环境、文件和素材

| ID | 必需能力 | 现有接口/事实 | 状态与缺口 | 优先级 |
| --- | --- | --- | --- | --- |
| ENV-01 | 版本、权限、服务准入 | `CubismFacade.runtime()`、服务目录、capabilities | P：缺逐几何/每类 form/default/Morph/render/export 动态准入 | P0 |
| DOC-01 | 文档列表、明确目标 | activeProject/document/model；快照 documentId | P：写入未统一携带预期 document/model/generation | P0 |
| DOC-02 | 新建模型 | `EditorCommand.NEW_MODEL` | P：缺文档身份、就绪、画布配置和成功核对 | P1 |
| FILE-01 | 文件授权与会话句柄 | `UserFileAccessService.request/readBytesBounded/writeBytesAtomic`、`UserFileHandle` | B/N：缺 MCP 会话 opaque grant 桥接；附件路径不是授权 | P1 |
| FILE-02 | 打开 CMO3、导入 PSD | 文件命令 OPEN、`EditorFileCommandRequest` | P：句柄命令未公开；PSD 打开不等于无交互生成/合并模型 | P1 |
| FILE-03 | 保存、另存副本 | SAVE、SAVE_AS | P：SAVE 须实时发现；另存、覆盖批准、完成及重开验证不完整 | P0/P1 |
| FILE-04 | 备份、检查点、恢复 | `EditorAutoBackupService`、历史、文件句柄 | P/N：Undo 不是磁盘备份；需重启后定位、恢复和副本编排 | P0 |
| ASSET-01 | 图层、图像、PSD 关联 | `psdDocuments()`、`ModelTextures.relations()/sources(query)` | P/B：完整来源关联、像素和绑定未全部公开 | P1 |
| ASSET-02 | 五官/前后发/左右/遮挡语义 | 名称、层级、像素 | A：需语义映射和人工确认；不能根据 Eye 或对象 ID 猜左右 | P0/P1 |
| ASSET-03 | 单图拆分、遮挡补画 | 无完整类型化美术生成契约 | A/N：需外部图像能力、模型、许可和质量验收 | P2 |
| ASSET-04 | PSD 外部修改与替换 | `exportRawImagePsd/replaceRawImagePsd`、`PsdFileRevision` | B：缺 MCP；保留修订冲突保护，不按路径任意覆盖 | P1 |

### 5.2 结构、网格、图像和外观

| ID | 必需能力 | 现有接口/事实 | 状态与缺口 | 优先级 |
| --- | --- | --- | --- | --- |
| OBJ-01 | 对象创建、命名、层级、删除 | `ModelObjectService`、对象集合、model_objects.apply | E/P：跨调用建模步骤不原子 | P0 |
| OBJ-02 | 身份、选择、编辑模式 | Drawables/MeshEditor select、selection、editLevel/defaultKeyformLocked | P/B：缺通用写契约；typed geometry 尽量不依赖选择 | P0 |
| GEO-01 | 源位置、UV、三角拓扑 | `geometry()`、`authoringSnapshot().sourceGeometry()` | B：缺几何读取、分页、坐标系；对象列表不能代替几何 | P0 |
| GEO-02 | 初始化拓扑、剖分与校验 | `previewSourceTriangulation`、source geometry create/createMany | P/B：create 有基本三角数据，缺预览及 source/default 分离创建 | P1 |
| GEO-03 | 已有拓扑、UV、源几何修改 | `replaceGeometry`、MeshEditService 点/边操作 | B/S：缺 typed MCP；编辑模式、选择和宿主支持独立验收 | P1 |
| GEO-04 | 已有网格逐关键形顶点 | authoringSnapshot、ordinaryKeyform、stored batch、`setPositions` | B：需完整参数 GUID tuple、formGuid 和 expected snapshot | P0 |
| GEO-05 | 逐关键形绘制顺序/联合编辑 | `setOrdinaryKeyformDrawOrders/setOrdinaryKeyforms` | B：缺 MCP；遮挡修正不只依赖顶点 | P0 |
| GEO-06 | 求值几何与新鲜度 | evaluationSnapshot、vertexPositions、ModelInstance、update | B：缺 typed 求值投影；写入不等于当前帧已更新 | P0 |
| TEX-01 | 像素注册、图像绑定 | `registerImageAndBind`、bindModelImage、textureInput | B：缺 MCP；只有几何可能不可见或空白 | P1 |
| LOOK-01 | 可见/锁定/透明度/颜色/混合/剔除 | Drawable/Deformer/Part typed 属性 | B/P：大多未公开；UI toggle 不是可读幂等属性写入 | P0/P1 |
| MASK-01 | 剪切与反向蒙版 | replaceArtMeshClipMasks、setClippingMaskIds/setInvertedMask | P/B：资源只读，缺写入口；影响眼嘴表现 | P0 |
| GLUE-01 | 胶水连接与强度 | Glues/Glue、glues.read/write | P：字段可改，创建/删除标为 RUNTIME_UNAVAILABLE | P2 |

### 5.3 变形器、参数、关键形与算法

| ID | 必需能力 | 现有接口/事实 | 状态与缺口 | 优先级 |
| --- | --- | --- | --- | --- |
| DEF-01 | Warp 当前与逐关键形网格 | grid/replaceGrid、authoringSnapshot、stored positions | B：create 初始化已有，存储 form 读写未公开 | P0 |
| DEF-02 | Rotation 基准、轴心、角度、缩放、反射 | baseAngle、form/replaceForm、snapshot、stored forms | B：baseAngle 不能代替某个 Z 关键形 angle | P0 |
| DEF-03 | 镜像与子对象补偿 | WarpMirrorService、mesh mirror | B/S：未公开；当前形镜像不等于全关键形/全 Morph | P1 |
| PAR-01 | 普通参数定义、值、复制、批量 | Parameters、updateDefinition/setValue | E：copy 不保证复制绑定/form；历史实测为空绑定 | P0 |
| PAR-02 | 文件夹、排序、Combined | ParameterGroups、combineWith/uncombine、edit 结构服务 | B：当前 apply 不含全部结构操作 | P1 |
| KEY-01 | 普通关键点与绑定/迁移 | ParameterBindingOperations/BatchOperations、parameter_bindings.apply | E/P：transfer 不是无损复制 rig；使用回读 canonical ID | P0 |
| KEY-02 | 多参数组合、共享/默认 form | 三类 AuthoringSnapshot axes/cells/forms | B：绑定点列表不足，需精确 tuple→form 映射 | P0 |
| KEY-03 | 默认形、源几何、编辑级别策略 | defaultKeyformLocked、editLevel、source 创建、writer 保护 | S/P：stored ordinary batch 拒绝默认/共享等；不得自动解锁绕过 | P0 |
| MORPH-01 | Morph 几何/opacity | Drawable/Warp morph API、ArtMeshMorphKeyform | B/P：限定域；MCP 拒绝单项 blend-shape CRUD，仅有受支持原子 transfer 等 | P1 |
| RIG-01 | 头 XYZ/XY 九宫格/眼嘴眉发联动 | geometry、bindings、render 原语 | A+B：仍需模板、轴心、透视/体积约束、对称和角点修正 | P0/P1 |
| RIG-02 | 复制/迁移 rig 并保留关键形 | 快照、转移原语；参数 copy 仅定义级 | A/N：需映射、碰撞策略和高层复制计划 | P1 |
| RIG-03 | 身体、四肢、服装协调 | 基础对象/参数/几何 | A：专项模板、关联约束和 QA；头部通过不代表全身完成 | P2 |

### 5.4 观察、验证、物理、动画和交付

| ID | 必需能力 | 现有接口/事实 | 状态与缺口 | 优先级 |
| --- | --- | --- | --- | --- |
| VIS-01 | 模型原生渲染、Agent 图像 | renderPixels、CubismRenderedPixels、EditorRenderPixelsAccess | B：缺 MCP 图像/资源包装、revision/pose 对应和尺寸预算 | P0 |
| VIS-02 | 近期文件缩略图 | ScreenshotCaptureService，150×150 上限 | B：独立预览，不代替细节渲染 | P2 |
| VIS-03 | 局部裁剪、多 pose 比较 | renderPixels 与参数服务组合 | A/N：需参数保存/恢复、pose→revision→image、预算和失败恢复 | P0 |
| QA-01 | 几何质量检查 | source/stored/evaluated、拓扑、统计 | A：需退化、翻面、越界、交叉、裂缝、密度/伸缩、连续性分析 | P0 |
| QA-02 | 视觉质量检查 | 原生渲染、语义、参考图 | A：闭眼漏白、口腔穿帮、遮挡、体积/五官漂移；APPLIED 不是质量通过 | P0 |
| PHY-01 | 物理设置与计算状态读取 | PhysicsSettings、AutoYure | B：无 typed MCP 资源 | P2 |
| PHY-02 | 物理创作与回放 | PhysicsEditorService.contribute 是 UI 扩展；PhysicsSettings 只读 | N/S：需 writer 与模拟闭环；插件存在不能证明可自动配置 | P2 |
| ANI-01 | 已有时间线/轨道/关键帧/曲线 | AnimationDocument/Scene/Track/Attribute | B/S：有 SDK/Runtime，缺 MCP；逐版本准入 scalar/point/curve | P2 |
| ANI-02 | 新建/删除场景、全新动作工程 | animationDocuments 注释声明场景生命周期无 reviewed Undo、fail closed | N/S：已有轨道 setter 不能代表场景创建 | P2 |
| EXP-01 | expression/motion/pose 应用资产 | 参数/动画数据、motion3 校验、部分文件命令 | A/N：缺格式、参数引用、blink/lipsync/pose 分组和应用适配流程 | P2 |
| ATLAS-01 | 图集元数据、页增删 | ModelTextures、textures.read/write | E/P：不能代替像素上传、布局或 UV 烘焙 | P1 |
| ATLAS-02 | 完整布局/多边形打包/追加页 | textureAtlasLayouts/current/apply、polygonLayouts、appendTextureAtlasPages | B：有实现和独立探针，缺 MCP；保留 target、revision 和布局约束 | P1 |
| OUT-01 | 原生 MOC/图集导出 | exportMoc(settings)、EditorMocExportAccess | B：内存导出已有；缺 MCP、有效图集/设置/预算/授权落盘闭环 | P1/P2 |
| OUT-02 | CMO3/模型文件集交付 | SAVE/文件命令、资源、原生导出 | P/A：需重开、相对引用、texture/physics/motion/manifest 核对 | P1/P2 |

### 5.5 事务、传输与 Agent 编排

| ID | 必需能力 | 已有基础 | 状态与缺口 | 优先级 |
| --- | --- | --- | --- | --- |
| SAFE-01 | 读→计划→写一致性 | expected authoring snapshots、历史 generation/revision、事务 | P：统一 document/model/sourceGuid/formGuid 与 CAS；历史 revision 不能替代几何比较 | P0 |
| SAFE-02 | 跨对象单步 Undo、异常回滚 | AuthoringTransactionService、transaction.execute | P：三个 legacy apply 不可入事务；纹理独立 Undo；不能直接拼成原子建模事务 | P0 |
| SAFE-03 | 幂等、未知结果、取消 | APPLIED/READBACK_WARNING/OUTCOME_UNKNOWN 等语义 | P/N：需 operationId/核对；超时/Abort 不是宿主取消，禁止盲重放 | P0 |
| ACP-01 | Agent 发现、模型、连接、权限、恢复 | Pi/Codex/Claude native 和部分 ACP-v1 | E/P：Pi native 历史成功不代表其他路线通过 | P0 |
| ACP-02 | MCP 资源进入模型上下文 | 原会话 Pi list/templates/read 补丁 | P：当前整理工作区未含该补丁；需找回/实现、发布及逐 Agent 验收 | P0 |
| ACP-03 | 图像模型可见、窗口可见 | Pi 可转发 image；ACP 有图像组件 | P：NativeAgentProvider.tool JSON→text 可能丢 typed image，输出大段 base64 | P0 |
| ACP-04 | 大几何、图片、分页、上下文 | HTTP 1MiB、Pi 8MiB、事务每步结果 256KiB | P/N：需不可变分页、稀疏补丁/受控 blob、摘要及内存预算 | P0 |
| ACP-05 | 长任务、进度、断线/取消/重连 | Pi 请求 15 秒；MCP 无 Tasks/进度通知/订阅 | P/N：需可核对结果的异步 job 或已验收的超时/取消协议 | P0/P1 |
| ACP-06 | 计划、预算、人工检查点、完成定义 | 对话、权限 UI、建模参考上下文 | A：需任务状态、范围、vision 能力、成本/质量门槛与复盘 | P0 |
| ACP-07 | 授权边界 | loopback、Bearer、stdio、插件权限 | E/N：不得开放 generic invoke、host.unsafe、任意 shell/路径或绕过批准 | P0 |

## 6. 建议 MCP 目录（全部是设计草案）

统一前缀 `turboism.`。读操作不移动参数、不修改选择；写操作绑定对象身份与前置状态，不依赖 UI 隐式状态。可调整命名/合并 Domain，但不得牺牲闭合输入、版本准入、结果类型和回滚边界；无需一次完成全部 P2。

| 建议接口 | 输入重点 | 返回重点与实现边界 |
| --- | --- | --- |
| authoring.capabilities.read | document/model、对象类型/集合 | 每操作版本/权限/default/shared/morph/预算；扩展现有 capabilities |
| authoring.objects.read | documentId、IDs、field mask、cursor | canvas/profile、层级、source GUID、绑定、锁、分页/计数 |
| authoring.meshes.read | meshId、source/stored/evaluated、form filter、分页 | positions/UV/indices、axes/cells/forms、identity/revision/坐标系；分清三种几何 |
| authoring.warps.read | deformerId、form filter | rows/columns/quadTransform、存储控制点、axes/cells |
| authoring.rotations.read | deformerId、form filter | baseAngle、各 form angle/origin/scale/reflection、axes/cells |
| authoring.keyforms.write | expectedState、kind、objectId、完整 GUID tuple、formGuid、edits | 三类 stored batch 闭合 union；changed IDs、前后摘要、Undo/事务收据、回读警告 |
| authoring.geometry.replace | source/current geometry、expected、显式影响范围 | topology/UV/source 变更与重读；独立高风险入口，不混普通关键形写入 |
| authoring.properties.write | 枚举属性、对象、值、expected | 可见/opacity/颜色/draw order/蒙版真实回读，保留版本域 |
| parameters.structure.apply | folder/Combined/排序闭合操作 | 完整结构和引用影响，不滥用 bind/transfer |
| authoring.morphs.read/write | 参数 GUID、非 basic key、目标域、expected | morph 身份/geometry/opacity；仅开放已验证域 |
| render.capture | expected doc/model/revision、尺寸、选项 | MCP image/受控链接，pose、revision、时间/坐标元数据；复用 renderPixels |
| render.pose_grid | pose 列表、对象、图像预算、restorePolicy | 各 pose 图像/状态及恢复结果；不是仅最终 pose 图 |
| files.request_grant | READ/WRITE、用途、过滤器 | 用户批准流程、会话 grantId、失效/撤销；映射 UserFileHandle，不收任意路径 |
| documents.open/save_as/checkpoint | grantId、预期文档、覆盖策略 | 文档身份、完成/版本/验证；普通 SAVE 可复用现有命令 |
| assets.read/register/bind/replace_psd | authorized image/blob/grant、mesh、expected revision | 来源/修订/像素/绑定；复用 ModelTextures，补授权二进制传输 |
| atlas.read/plan/apply/append | issued target、完整 plan、expected、预算 | 页/UV/碰撞、Undo、绑定变化；plan 算法与 apply 分离 |
| validation.model/geometry/poses | scope、阈值、poses、参考要求 | 对象/参数/form 定位、severity、证据与已实施检查范围；空 findings 不能代表全检查 |
| physics.read/plan/apply/simulate | 物理文档、输入输出、expected | 参数引用、模拟质量、真实写入；完整 writer 待补证据 |
| animation.read/keyframes.apply | scene/track/attribute、typed edits | keyframes/curves、Undo、版本限制；场景创建独立准入 |
| export.plan/execute/validate | 类型、设置、授权目标、expected | 字节/文件 manifest、引用/兼容性/哈希；不默认支持所有格式 |
| jobs.start/status/cancel/result | allowlisted operation、operationId、预算 | queued/running/committed/failed/outcome_unknown、进度/结果；非任意 tool/代码执行 |

### 普通关键形写入示例

以下是草案；数字只演示格式，不能用于真实模型。`snapshotId` 是建议的服务器缓存机制，不是现有 SDK 字段。

```json
{
  "expectedState": {
    "documentId": "<discovered-document>",
    "modelId": "<discovered-model>",
    "snapshotId": "<server-issued-immutable-snapshot>"
  },
  "operationId": "<client-unique-operation>",
  "edits": [{
    "kind": "rotation_deformer",
    "objectId": "<discovered-deformer>",
    "formGuid": "<discovered-stored-form-guid>",
    "coordinates": [{"parameterGuid": "<native-axis-guid>", "value": 30}],
    "form": {
      "angle": 12, "originX": 0, "originY": 0,
      "scale": 1, "reflectedX": false, "reflectedY": false
    }
  }]
}
```

契约约束：

1. 坐标覆盖对象全部普通绑定轴，引用实际存在的 cell/form；GUID 不等于显示 ID，显示 ID 可重复或变化。
2. 服务器保存并比较完整 expected authoring state，不能凭 snapshotId 跳过 CAS。先建关键点后重新读取，不沿用旧快照。
3. ordinary writer 仅编辑已有、独立、非默认、受支持域内的 form；默认/共享/Morph/拓扑分别独立准入，不自动解锁或用 replaceGeometry 绕过保护。
4. ArtMesh positions、Warp controlPoints、Rotation 完整 form 使用独立闭合 schema；拒绝跨类型字段、NaN/Infinity、越界及点数不符。
5. 明示坐标空间、单位和父变形器关系；截图像素、Core 求值顶点与 authoring/source 坐标不能互换。

## 7. 安全、事务与传输规范

- **身份绑定：** 每次写入核对 document/model；切换文档立即拒绝。source/form、generation 与完整 expected state 在全部目标写入前一次性核对。
- **授权：** 区分只读、可撤销写、破坏性修改、文件、外部副作用。附件/路径不是 handle；保存不承诺 Undo。
- **事务：** 复用 AuthoringTransactionService；新工具满足同步、UI affinity、exact schema 等条件后才声明 transactionEligible。legacy apply 不得只改标记入事务。
- **结果：** 明确 NOT_APPLIED、APPLIED、APPLIED_WITH_READBACK_WARNING、ROLLED_BACK、OUTCOME_UNKNOWN；回读失败不代表可安全重试。
- **重试与取消：** operationId/缓存要对齐真实提交边界；超时先核对，不盲重放。Agent 停止、桥接不再等待、宿主取消、事务回滚是四种状态，断线不能证明无改动。
- **预算：** 分别限定几何/图像/表格的页、字节、顶点、像素、时间。HTTP 1MiB、Pi 8MiB、事务每步 256KiB 等限制不得未经测试统一提高。
- **一致性分页：** 同一不可变快照生成全部页；拒绝混合写前/写后页面。稀疏补丁先在服务端重建完整 DTO，再校验身份/拓扑并调用 SDK。
- **失败闭合：** 版本不准入、权限不足、UI 不适配或能力缺失时返回 unavailable/reason；不反射、不执行 shell、不伪造成功。

## 8. ACP/Agent 必需闭环

### 8.1 发现、权限与图像

Agent 应完整发现 tools/resources/templates，Pi 原会话补丁需找回或重新实现、纳入包并回归恢复会话后的目录更新。其他 native/ACP harness 分别验收。prompts/list/get 缺口可后续优化，不是执行建模的硬依赖。

必须分别验证 MCP image → 模型上下文 → native/ACP tool result → Swing 图像展示。模型需具备 vision；ACP 能附图不证明当前模型能看图。重点修正 JSON→text 路线，避免 base64 代替图像。

图像需绑定 document/model/revision、pose、分辨率、裁剪坐标，不能拿旧图、缓存或文件缩略图代表修改后结果。权限窗口展示目标、影响、参数摘要、危险属性和 Undo 能力；大几何使用可展开摘要。

### 8.2 可恢复任务状态

建议状态机：

```text
DISCOVER → INSPECT → PLAN → APPROVE → SNAPSHOT → APPLY
→ VERIFY_GEOMETRY → VERIFY_RENDER → ACCEPT / REPAIR / ROLLBACK
→ SAVE → REOPEN_VERIFY → REPORT
```

各阶段记录 taskId、operationId、对象身份、pose、快照/事务收据、图像和 findings。对话恢复不是模型恢复；重连重新核对文档与 generation，不继续执行旧计划。

头部算法还需图层语义与左右映射、头颈轴心、体积/透视模板、眼眉鼻嘴/轮廓联动、前后发遮挡、对称与不对称策略、XY 角点、Z 细节、眨眼/口型与 XYZ 组合检查。允许人工确认结构/分层、变形器计划、关键形视觉、导出覆盖，不能承诺任意立绘无人监督产出专业成品。

## 9. 里程碑与验收门槛

| 里程碑 | 范围 | 通过门槛 |
| --- | --- | --- |
| A / P0 | 三类快照、坐标/动态准入、stored typed batch + CAS、draw order/蒙版/外观、render/pose 恢复、typed 图像、分页/预算/结果/取消、动态 SAVE | 官方 Hiyori 副本中分别真实编辑 ArtMesh 顶点、Warp stored form、Rotation stored form；数值与图像证据；单步 Undo/Redo 精确还原；故障回滚；保存重开 form/绑定不变；原件哈希不变 |
| B / P1 | grant/open/save-as、图层/像素、剖分/source/default、图像绑定、参数结构/Morph 支持域、图集/导出 | 明确许可的分层素材，新建可见非空眼嘴/头发网格、头 XY/Z 与眼嘴关键形；几何/视觉、保存重开、原生导出验证 |
| C / P2 | 身体/四肢/服装、物理 writer/模拟、动画/表情/pose、完整 manifest 与目标播放器 | 逐专项验收；只将通过部分纳入“完整自动建模”声明 |

A 的三类原语可分独立案例，不为凑编辑种类破坏同一个头部。不能只增加点/调滑块，也不能以连接 Agent 成功代替自动建模闭环。详细拆分见[实施清单](implementation-plan.md)。

## 10. 最小验收案例集合

| 类别 | 必测案例 |
| --- | --- |
| 准入 | 无文档、非模型文档、不支持精确版本、缺权限、空资源、明确 unavailable |
| 身份 | 标签页切换、删除重建、参数重命名/重复 ID、旧 generation/snapshot、拓扑变化 |
| form | 不完整 tuple、未声明键、共享/默认形、NaN/Infinity、点数不符、部分失败、域外 Morph |
| 几何 | 顶点/控制点/Rotation 精确回读；UV/indices、未选 form、原素材和其他文档不变 |
| 历史 | 单/多对象单次 Undo、Redo、异常回滚、取消边界、宿主成功但响应丢失的核对 |
| 渲染 | 官方 renderer 真图、新鲜度、pose/revision、裁剪坐标、参数恢复、模型与 ACP 均可见 |
| 文件 | READ 不可写、失效/撤销/跨会话拒绝、覆盖批准、失败不报成功、重开持久化 |
| 传输 | 分页一致、超限/超时/断线/重连、重复 operationId、不漏 token、只读 HTTP 不冒充可写 |
| 算法 | XY 九宫格、XYZ 组合、闭眼/张嘴/遮挡、眨眼口型组合、镜像与不对称素材 |
| 交付 | 图集无重叠/越界、绑定有效、MOC/纹理/引用齐全、官方/目标播放器真实加载 |

## 11. 源码证据索引

以下链接用于定位原审计论点，文件存在不等于本次完整语义复核或宿主通过。SDK 路径以 `sdk/src/main/java/dev/turboism/sdk/` 为根，具体包路径见链接。

- [Drawable.java](../../sdk/src/main/java/dev/turboism/sdk/cubism/model/Drawable.java)
- [WarpDeformer.java](../../sdk/src/main/java/dev/turboism/sdk/cubism/model/WarpDeformer.java)
- [RotationDeformer.java](../../sdk/src/main/java/dev/turboism/sdk/cubism/model/RotationDeformer.java)
- [ArtMeshAuthoringSnapshot.java](../../sdk/src/main/java/dev/turboism/sdk/cubism/model/ArtMeshAuthoringSnapshot.java)
- [WarpAuthoringSnapshot.java](../../sdk/src/main/java/dev/turboism/sdk/cubism/model/WarpAuthoringSnapshot.java)
- [RotationAuthoringSnapshot.java](../../sdk/src/main/java/dev/turboism/sdk/cubism/model/RotationAuthoringSnapshot.java)
- [EditorObjectReadAccess.java](../../runtime/src/main/java/dev/turboism/adapter/cubism/editor/EditorObjectReadAccess.java)
- [EditorArtMeshKeyformAccess.java](../../runtime/src/main/java/dev/turboism/adapter/cubism/editor/EditorArtMeshKeyformAccess.java)
- [EditorWarpKeyformAccess.java](../../runtime/src/main/java/dev/turboism/adapter/cubism/editor/EditorWarpKeyformAccess.java)
- [EditorRotationKeyformAccess.java](../../runtime/src/main/java/dev/turboism/adapter/cubism/editor/EditorRotationKeyformAccess.java)
- [PermissionCheckedDrawable.java](../../runtime/src/main/java/dev/turboism/adapter/cubism/PermissionCheckedDrawable.java)
- [PermissionCheckedDeformer.java](../../runtime/src/main/java/dev/turboism/adapter/cubism/PermissionCheckedDeformer.java)
- [CubismFacade.java](../../sdk/src/main/java/dev/turboism/sdk/cubism/CubismFacade.java)
- [CubismModel.java](../../sdk/src/main/java/dev/turboism/sdk/cubism/model/CubismModel.java)
- [EditorRenderPixelsAccess.java](../../runtime/src/main/java/dev/turboism/adapter/cubism/editor/EditorRenderPixelsAccess.java)
- [EditorMocExportAccess.java](../../runtime/src/main/java/dev/turboism/adapter/cubism/editor/EditorMocExportAccess.java)
- [ModelTextures.java](../../sdk/src/main/java/dev/turboism/sdk/cubism/model/ModelTextures.java)
- [Drawables.java](../../sdk/src/main/java/dev/turboism/sdk/cubism/model/Drawables.java)
- [EditorTextureRegistrationBindingAccess.java](../../runtime/src/main/java/dev/turboism/adapter/cubism/editor/EditorTextureRegistrationBindingAccess.java)
- [EditorTextureAtlasAppendAccess.java](../../runtime/src/main/java/dev/turboism/adapter/cubism/editor/EditorTextureAtlasAppendAccess.java)
- [TextureAtlasLayoutService.java](../../sdk/src/main/java/dev/turboism/sdk/cubism/textureatlas/TextureAtlasLayoutService.java)
- [WarpMirrorService.java](../../sdk/src/main/java/dev/turboism/sdk/cubism/mirror/WarpMirrorService.java)
- [UserFileAccessService.java](../../sdk/src/main/java/dev/turboism/sdk/ui/UserFileAccessService.java)
- [EditorFileCommandRequest.java](../../sdk/src/main/java/dev/turboism/sdk/cubism/command/EditorFileCommandRequest.java)
- [EditorFileCommand.java](../../sdk/src/main/java/dev/turboism/sdk/cubism/command/EditorFileCommand.java)
- [EditorCommand.java](../../sdk/src/main/java/dev/turboism/sdk/cubism/command/EditorCommand.java)
- [ScreenshotCaptureRequest.java](../../sdk/src/main/java/dev/turboism/sdk/cubism/screenshot/ScreenshotCaptureRequest.java)
- [PhysicsSettings.java](../../sdk/src/main/java/dev/turboism/sdk/cubism/model/PhysicsSettings.java)
- [PhysicsEditorService.java](../../sdk/src/main/java/dev/turboism/sdk/cubism/physics/PhysicsEditorService.java)
- [AnimationAttribute.java](../../sdk/src/main/java/dev/turboism/sdk/cubism/model/AnimationAttribute.java)
- [AnimationScene.java](../../sdk/src/main/java/dev/turboism/sdk/cubism/model/AnimationScene.java)
- [EditSession.java](../../sdk/src/main/java/dev/turboism/sdk/cubism/edit/EditSession.java)
- [McpHttpServer.java](../../plugins/mcp/src/main/java/dev/turboism/plugin/mcp/McpHttpServer.java)
- [McpProductionDomainCatalog.java](../../plugins/mcp/src/main/java/dev/turboism/plugin/mcp/McpProductionDomainCatalog.java)
- [McpParameterDomain.java](../../plugins/mcp/src/main/java/dev/turboism/plugin/mcp/McpParameterDomain.java)
- [McpHistoryCommandDomain.java](../../plugins/mcp/src/main/java/dev/turboism/plugin/mcp/McpHistoryCommandDomain.java)
- [McpTextureDomain.java](../../plugins/mcp/src/main/java/dev/turboism/plugin/mcp/McpTextureDomain.java)
- [McpGlueDomain.java](../../plugins/mcp/src/main/java/dev/turboism/plugin/mcp/McpGlueDomain.java)
- [McpTransactionDomain.java](../../plugins/mcp/src/main/java/dev/turboism/plugin/mcp/McpTransactionDomain.java)
- [McpRegisteredTool.java](../../plugins/mcp/src/main/java/dev/turboism/plugin/mcp/McpRegisteredTool.java)
- [mcp-sdk-coverage.json](../../plugins/mcp/src/main/resources/META-INF/turboism/mcp-sdk-coverage.json)
- [pi-mcp-extension.mjs](../../plugins/acp/src/main/resources/native/pi-mcp-extension.mjs)
- [NativeAgentProvider.java](../../plugins/acp/src/main/java/dev/turboism/plugin/acp/NativeAgentProvider.java)
- [NativePiProvider.java](../../plugins/acp/src/main/java/dev/turboism/plugin/acp/NativePiProvider.java)
- [MCP 中文说明](../../plugins/mcp/README_zh.md)
- [原生图集验证说明](../model-import-3d-auto-atlas/verification.md)
- [MCP host probe 说明](../mcp-host-probe/README.md)

历史图集验证说明记录独立 5.3.04 原生图集/内存导出探针，未包含跨 JVM 保存重开或全部 GPU 测试，不能扩张为完整自动建模验收。host probe 目录存在也不代表当前机器实测通过。

## 12. 复核方法与当前可执行性

原审计称两项离线测试通过，但原脚本与测试未随整理工作区提供，不能复现该历史结果。2026-10-25 已重新实现 [只读采集器](collect_live_catalog.py) 和 [离线测试](test_collect_live_catalog.py)，当前复核命令可执行：

```sh
python3 -m unittest discover -s validation/acp-auto-modeling-capability-audit -p 'test_*.py' -v
```

当前 14 项离线测试通过，包括真实子进程 stdio 假桥接、分页/预算、初始化 -32000 不生成成功、目录内容最小化和错误脱敏。假桥接测试不连接 Cubism，不属于宿主目录成功证据。

用户启动测试 Cubism 后，复制其 MCP 连接窗口的**可信 stdio 启动描述符**到本机文件，再执行：

```sh
python3 validation/acp-auto-modeling-capability-audit/collect_live_catalog.py --config <本机描述符文件>
```

支持直接 `{command,args,env?,type?:"stdio"}` 或 `{mcpServers:{turboism:{...}}}`；服务器名可用 --server 指定。也可通过 TURBOISM_MCP_CONFIG 指定描述符后执行无 --config 的命令。没有配置返回 NO_STDIO_CONFIG，不扫描用户目录或自动拼装 token；不接受 HTTP 配置，不读取原始 mcp.token/mcp-connection.json。启动描述符会作为 argv 执行，必须来自可信本机桥接，不能执行外来配置；不通过 shell。描述符及 env 只在内存中使用，不复制到报告或日志。

采集方法闭合为 initialize、initialized 通知、tools/list、resources/list、resources/templates/list、prompts/list，以及唯一资源 turboism://host/editor-commands。不会执行 tools/call、prompts/get、save、建模或其他资源读取，也不操作参数/模型。每个目录读完全部 pages，重复 cursor/身份、预算超限、协议错误、初始化失败均无成功报告。

仅完整成功后原子写入 live-mcp-catalog.json（可用 --output 指定）。保留 UTC 时间、协议、公开工具/资源/模板/提示词身份、数量、工具 input/output schema 哈希、availableDirectCommands 和 typedContracts 哈希；不导出原始 schema/description、初始化响应、配置、环境、Bearer、session header 或 stdio transcript。报告不是跨目录原子快照，也不证明目录中工具运行可用。默认请求超时 15 秒（--timeout 最大 60 秒），单行 8MiB、累计 32MiB、单目录 64 页/5000 项、报告 4MiB；错误只显示常量码，不打印原生响应。退出只清理此次启动的桥接子进程，不退出 Cubism。失败保留已有历史报告不变，应核对 capturedAt；不能把旧文件存在当成本次成功。禁止输出覆盖输入配置。

本轮未提供或连接真实 stdio 描述符，没有生成本次实时目录。当前状态与新增证据见 [验证记录](verification.md)，离线采集器测试不证明建模、渲染、文件或 harness 验收。


## 22. 后续实现：ArtMesh 存储 form 透明度读取

SDK 的 ArtMeshAuthoringSnapshot.Form 现保留可选原生存储 opacity；原三参数构造器保留并表示未知。Runtime 独立准入该读取 selector，MCP meshes.read 的 mesh_form 行以有限数或 null 返回，禁止使用 evaluated/current opacity 代填。Morph 原值不钳制、不推断其绝对透明度。字段随不可变快照分页、参与完整状态 CAS，现有 ArtMesh 顶点/绘制顺序与 Morph 几何/顺序写入的预期快照均保留它。同 revision 的透明度变化也会使旧 MCP 几何写计划失效。

工具目录仍为 44，资源/模板/提示词仍 15/2/8，有限覆盖账本仍 14 owner。本轮不开放 opacity writer；逐形颜色、Warp/Rotation 透明度、完整外观事务和实际宿主/模型/保存重开验收仍待继续。测试与证据范围见 [verification.md](verification.md) 最新记录，不能把读取与 CAS 保护当作美术透明度自动建模已交付。


## 23. 后续实现：普通 ArtMesh 透明度联合写入

在上节读取基础上，新增闭合 SDK ArtMeshKeyformValues / Drawable.setOrdinaryKeyformValues，生产 keyforms.write 的 ArtMesh edits 支持 positions、drawOrder、opacity 的七种非空组合。目标普通 form 的存储 opacity 必须已知，输入有限 0..1，省略字段保留 expected。Runtime 独立准入现有已审 opacity 读写 selector，完整 CAS 和所有目标预检后，通过实际 stored form 在一个 keyform contribution 中修改；原几何写入的准入不因缺少 opacity selector 而失效。

跨对象事务、完整回读、操作结果与重放继续沿用既有协议。默认/共享/Morph 保护和预算保持，opacity 批次包含保留的全部目标顶点；不调用当前 pose setter、不移动参数、不保存模型。目录仍 44 工具、15 资源、2 模板、8 提示词，有限覆盖账本 14 owner。验证范围见 [verification.md](verification.md) 最新记录；颜色、Warp/Rotation 外观、自动美术与真实模型 Undo/Redo/保存重开仍未完成。


## 24. 后续实现：逐操作发现与会话类型链修复

ordinary_write_support 新增不可变、闭合的 operationSelectors，分别报告 ArtMesh 顶点/顺序/联合值/含透明度联合值、Warp 控制点和 Rotation form 的 selector 观察。缺失项为 UNKNOWN，旧 SDK 构造器保留并给空 map；权限装饰保留操作信息。透明度独立检查，不从几何 writerSelectors 推断它已准入。

修复动态会话漏转发能力查询，并在通用 deformers.find/all 中保留实际 Warp/Rotation 子类型；此前普通 wrapper 会遮掉 MCP 所需的 typed form API。保留代际保护、plain fallback、host 顺序、对象 unwrap 和通用集合外观身份路由。MCP schema、三语说明与有限覆盖行同步，工具数仍为 44。完整 admission、实际模型/宿主与保存重开验收仍未完成，具体测试范围见 [verification.md](verification.md) 最新条目。


## 25. 多轴 Rotation 存储角度规划（2026-10-25）

扩展现有 turboism.rig.rotation.plan 的只读规划域。旧单轴 parameterGuid/nativeAnglePerParameterUnit 仍可用；新增互斥 axes 输入，1..3 个显式 GUID/gain 必须覆盖全部普通轴，不按显示 ID、对象名称或输入顺序推断。每轴至少两个唯一范围内 key，并含精确默认；组合网格须完整独立，最多 65 cells/64 非默认 edits。对每个非默认 cell，目标 angle = 全轴默认存储 angle + Σ((cellKey-axisDefault)×gain)，按 native 快照轴顺序以 double 求和后一次转换 float。保留每目标轴心、缩放、反射、baseAngle、默认和无关 forms、axes/cells。

这让 Agent 可为已有多轴 Rotation 绑定生成闭合普通写请求，而非受旧单轴模板限制。属于角度标量叠加，并非多个变形器旋转复合、XYZ 语义推断或父子补偿。仍执行普通 writer 纯预检与前后完整状态/父级/文档/历史验证，128KiB 输出预算和单独写入准入/CAS；规划不调用 setter、移动 pose/选择、渲染或事务。不新增工具、SDK selector 或原生写域。

专项 14 项、完整 MCP 518 项零失败/错误（1 项 headless UI 跳过）；格式和打包通过。来源见实施验证记录。真实多轴插值、头部体积/眼嘴协调、视觉和持久化仍未验收；未启动/连接 Cubism 或修改模型，不能据此勾选 Milestone A 或自动建模完成。


## 26. ArtMesh/Warp 三轴锚点与组合残差（2026-10-25）

现有 rig.mesh.plan 与 rig.warp.plan 扩展为显式一至三普通轴。每轴提供负/正端点锚点，GUID 必须覆盖全部实际绑定；默认形为参考，各轴位移按默认到声明端点的比例叠加。完整独立 Cartesian 网格、精确默认与双侧 key、最多 65 cells/64 edits/4096 计划点/240KiB 保持。未按名称猜测头部 XYZ，不新增关键点或改变绑定/拓扑。

cornerCorrections 可指定二或三个不同已绑定 GUID 及方向，残差权重只乘声明轴的同侧归一化比例。二轴残差沿未声明第三轴各 key 保持；三轴残差在任一默认面为零。三轴最多 12 个二轴象限加 8 个三轴卦限，即 20 唯一方向映射；不允许重复映射、单轴残差或未知 GUID。输入旧二轴语义保留。默认与无关 forms、源 positions/UV/indices、存储绘制顺序/已观察透明度、Warp grid/quadTransform 等保持。

计划复用现有 ordinary writer 纯预检，并可原样传给 validation.geometry/validation.warp 的 proposedEdits，在内存诊断后再单独提交 keyforms.write。前后原生/父级/文档/历史验证、事务/完整 CAS/写入准入边界不变；规划与诊断不调用 setter、移动 pose、渲染或事务。工具仍 44，有限 owner 14。

专项 56 项零失败，完整 MCP 526 项零失败/错误（1 项 headless UI 跳过），格式与打包通过。三轴存储模板不证明真实插值、透视/体积、眼嘴/遮挡协调或视觉质量；没有启动/连接宿主、修改真实模型或新增实机验收，Milestone A 与持续目标仍未完成。


## 27. 已保留 pose PNG 的局部颜色与差异检查（2026-10-25）

新增只读 turboism.validation.raster，目录现为 45 工具、15 资源、2 模板、8 提示词，有限 owner 仍 14。按本 MCP 会话的终态且未释放 pose-grid operationId，检查 1..32 个显式 PNG 局部区域。color_fraction 按 inclusive RGBA 字节范围统计颜色占比；frame_difference 按预乘 sRGB RGB 与 alpha 的最大通道差严格超过 tolerance 统计变化像素。两者均由调用方显式指定 fraction 上下限与 ROI，不猜测脸/眼/嘴位置或背景。

ROI 使用裁剪后 PNG 左上像素坐标；差异比较要求完整原生 raster/crop 元数据一致，不对齐、不重采样。计数与十进制阈值精确比较，结果保留实际配置、计数、比例、逐项 passed 和 checksPassed。checksPassed 只覆盖显式像素断言，ok 只表示检查执行成功，不代表模型、美术或全部视觉检查通过。报告关联捕获时刻的 captureId/hash、pose/state/crop，imageContentIndex 为 null，不复制图片或 base64。部分扫描可以检查实际保留帧，但 scanOutcome/parametersRestored/allRequestedPosesCaptured 单独报告，不将部分采集或恢复失败隐藏成成功。

执行不访问宿主、移动参数、重新渲染或写入。会话隔离、终态/释放/帧身份保护、PNG 长度/SHA-256/实际尺寸核对及 4194304 ROI samples/1048576 解码像素/3MiB 原 PNG/240KiB 报告预算生效。已开始的只读分析持有终态副本，之后 release 不使其转为新捕获或丢失对应关系；工具不另外长期缓存像素。历史图片不证明当前模型新鲜度。

新增 8 项回归，含账本的专项 31 项通过；最终 MCP 534 项零失败/错误、1 项 headless UI 跳过，格式与打包通过。真实 renderer/视觉模型/Swing 与官方副本的闭眼漏白/口腔穿帮等仍待验收，没有启动/连接 Cubism 或修改实际模型。该切片提供局部可复核视觉证据，不替代语义、美术算法与 Milestone A 的完整门槛。


## 28. 显式头部深度场的 Warp 透视模板（2026-10-25）

rig.warp.plan 新增 PROJECT_HEAD_VOLUME_FROM_DEFAULT policy，使用 headVolume 的中心/半径/深度/相机距离及实际普通轴 GUID→YAW/PITCH/ROLL 的显式度数增益；与原锚点/cornerCorrections 模式互斥。所有轴必须覆盖且 component 唯一，不推断名称、左右、轴心或坐标空间。模板沿默认存储控制点的椭圆定义正面深度场，将图像点沿相机射线提升后依次 yaw(+Y)/pitch(+X)/roll(+Z)，再透视投影；数学公式、轴符号和限制见 MCP 三语说明与实施清单。

这是图像定义的近似深度场，便于 Agent 生成可审阅的头转动候选，不是三维模型重建或任意立绘自动 rig。默认图像由射线提升保持，零角度直接复制原控制点；非默认目标从默认形重建，替换既有目标几何，不累积位移。保留默认、无关 forms、网格/quadTransform/绑定。复用完整独立网格与普通 writer 纯预检、既有预算和前后 native/parent/history 验证，返回独立 keyforms.write 请求，并可直接使用 validation.warp 的 proposedEdits 先诊断。

构造时验证 profile 有限、正半径/非负 depth、cameraDistance>depth 且剩余距离至少 1%；每 cell yaw/pitch 限 ±85°、roll 限 ±180°，拒绝旋转后 near-plane/非有限分母、float 溢出、缺轴/重复 component/共享或不完整网格。规划不移动 pose、写入、渲染或事务。调用方仍须确认头 Warp、父坐标、profile、轴符号和 children 适配，检查实际渲染/遮挡/眼嘴/体积。

专项 34 项通过；完整 MCP 540 项零失败/错误、1 项 headless UI 跳过，格式与打包通过。工具数保持 45，有限 owner 14。无 SDK/Runtime/ACP 变更，无 Cubism 启动/连接/模型改动或新增实机证据，Milestone A 和持续自动建模目标保持未完成。

## 29. 普通关键形跨对象写前预览（2026-10-25）

已实现只读 turboism.authoring.keyforms.preview，目录为 **46 工具、15 资源、2 模板、8 提示词**，有限 owner 14。接收与 write 完全相同的 typed 请求；共享完整 authoring/父级/历史 CAS、普通 form 支持域与预算，计算后再次核对状态。返回 requestHash、对象/form 变更字段、点数/原生空间最大位移、drawOrder/opacity/角度/轴心/缩放/反射差异，以及 before/expected-after 哈希。不回传完整坐标，不跨对象合成距离。

applied=false、readbackVerified=false、writeAdmission=NOT_CHECKED。预览不解析事务服务、不写模型、不移动参数、不渲染、不预留操作 ID 或失效快照。已 admitted ID 必须查 result；预览成功后提交同一请求仍重新执行实时 CAS/写入准入。纯 drawOrder 预算点为零，opacity/联合批次保留顶点沿用实际 writer 预算。最多 8 对象/64 forms/16384 预算点/768KiB 输入/240KiB 预览。不能用它替代几何/视觉 QA、写授权或宿主验收。

新增 5 项 Domain 测试；最终 MCP **545 项，0 失败/错误，1 headless UI 跳过**，格式检查和 JAR 通过。包含三类对象预览后实际模拟写入哈希一致、重复预览无 ID 预留、零 setter/事务查询、完整状态变化与跨会话/默认/共享/Morph/过期快照拒绝；既有写入与回滚全量回归保持。没有启动或连接 Cubism，没有实际模型改动、实机 Undo/Redo/保存重开证据。A 和持续自动建模目标仍未完成。

## 30. 现有模型图像绑定 API（2026-10-25）

新增 turboism.assets.binding.read/bind/result，生产目录 **49/15/2/8**，有限 SDK owner 14。依据真实 meshes.read authoringSnapshotId，观察当前图像/图集输入或真实未绑定状态，读取最多 1024 个唯一模型图像候选元数据并签发不可变会话 token。缓存保持 5 分钟/16 项/16MiB，并计入保留 native authoring 状态；读响应最多 240KiB。候选名称和尺寸不是语义分层或像素可用证明。

bind 仅对已发现的现有 modelImageId 与无父变形器且未锁定的 ArtMesh，完整核对 native geometry/forms/source GUID、父级、锁、history、原输入含 modelGeneration 和候选元数据；同图像 NO_CHANGE 不调用 native bind，不同图像/图集拒绝。独立 SDK transaction 内再次 CAS，并核对返回 ModelImageBinding 与实际 textureInput 的 model/image/mesh/generation，几何/UV/forms/父级/候选必须保持。事务内失败由 SDK 回滚或明确 recovery failed/unknown；提交后回读失败保留 APPLIED_WITH_READBACK_WARNING，不伪报 NOT_APPLIED。原生权限/精确宿主/祖先锁/扩展准入仍失败闭合，无自动解锁、重绑定或绕过。

结果为会话历史证据，最多 256 admitted 操作，无淘汰；重放不查宿主、不再次绑定，冲突 ID 拒绝，超时先查 result。哈希覆盖 document/model/mesh/source GUID 和输入资源身份，不含 generation/几何，后两者另有精确 CAS。成功后该 binding token 失效。HTTP 写入须 Bearer，结果查询为只读。现有 textureInput 与 bindModelImage 账本行已修正为实际入口；PNG 授权注册后续在第 31 节实现；PSD 仍未开放，不能把现有图像绑定接口称为从零素材链路全部完成。

新增 12 项回归，最终 MCP **557 项，零失败/错误，1 headless UI 跳过**，格式/打包/diff 检查通过。无 SDK/Runtime/ACP 新改动，无本轮宿主连接、真实图像/GPU/Undo/Redo/保存重开证据，自动建模目标与 Milestone A 继续未完成。

## 31. 授权 PNG 注册、绑定与任务结果（2026-10-25）

新增 assets.images.register/result/cancel，当前工作区目录 **52 工具、15 资源、2 模板、8 提示词**、有限 owner 14。基于同会话一次性 READ png grant，异步读取最多 16MiB 完整 PNG，按显式 maxPixels（最多 16777216，单维 <=8192）验证 header；可用 expectedSha256 锁定实际不可变 AuthorizedImageContent 的字节。fileEvidence 返回 hash/尺寸/字节数，fileStability=UNPROVEN，无路径/文件名自动泄漏/base64。原生 Runtime 负责真实 decode 与权限/Undo/扩展/祖先锁/精确宿主准入。

模型预检在 grant 消耗之前，读取后在原生事务前重新核对 snapshot TTL 与完整 native authoring/parent/lock/input/catalog/history CAS。独立 SDK 事务内必须新增恰好一个 raw image/图像组/model image，明确 name/尺寸和绑定 model/mesh/generation 与实际 typed observation 一致；旧 metadata/UV/topology/forms 保持，偏差触发恢复。binding.read 增加 groupCount，避免空组变化漏检。结果 writeResult 保留 rawImageId、实际 textureInput 与历史收据，metadata 哈希不覆盖像素或磁盘稳定性，几何另行完整 CAS。

任务立即返回并可查询，最多 128 operationId 不淘汰，全局单个 I/O/worker/host 槽直到真实完成。取消在原生事务最终准入前生效；hostAdmitted 后继续报告真实 commit/rollback/unknown，不把 Agent Abort/断线/关闭会话或服务称为宿主取消。pending READ 的 lease 等实际读完成才关闭；会话关闭移除结果访问，缺失结果不是未改模型证明。提交后回读失败警告、丢失响应 unknown 均不可盲目重试。结果不保留 PNG bytes，不参与 transaction.execute。

新增 14 项离线 Domain 案例；最终 MCP **571 项，0 失败/错误，1 headless UI 跳过**，格式/JAR/diff 通过。生产目录首跨 50 项，HTTP 集成真实遍历 nextCursor；Pi 模拟 52 工具分页与第二页查询/取消执行、原有资源/图片/权限/HTTP SSE/协商验证通过。未修改 SDK/Runtime/ACP Java，未启宿主、没有真实素材导入、像素/GPU、Undo/Redo 或保存重开证据。该链路是 PNG 工具基础，PSD、拓扑/atlas/交付以及 A/B 实机门槛仍待完成，持续自动建模目标保持 active。


## 32. Claude 标准观察预检（2026-10-26）

测试专用 Claude native 路线增加运行时固定、工具隔离与随机图片 canary；受限 CLI 握手、标准包与离线自检通过。修复后任务已 prepare，但实机提交被自动审批拒绝，等待明确授权将私有测试渲染和元数据发送给现有 Claude CLI 配置的外部服务；没有第二轮队列执行或真实视觉通过声明。详情见[第63节](verification.md#63-claude-标准观察入口与提交授权边界)及[证据摘要](claude-observer-preflight-20261026.json)。总目标与 Milestone A 仍未完成。


## 33. 写响应丢失的实机核对（2026-10-26）

标准head-edit任务在Hiyori测试副本中丢弃一次关键形写HTTP响应，以同operationId两次查询生产result取得稳定收据；三类精确回读、Undo/Redo、最终恢复及队列文件/身份/golden/安全清理通过。客户端与隔离队列182项通过。见[第64节](verification.md#64-真实关键形写响应丢失与同会话结果核对)和[摘要](live-host-20261026/head-response-loss.json)。这是同会话接收端故障证据，不是宿主崩溃/跨会话恢复、真实Agent视觉或完整建模通过；外部模型授权仍待回复。


## 34. ACP rawOutput 与真实窗口（2026-10-26）

修复仅返回rawOutput的ACP工具结果丢失，保留收据文字和typed图片，优先已有content以避免图片混合。Xvfb ACP252项（249通过、3 opt-in跳过）及脚本代理自检通过；标准实机任务验证原生PNG到Swing全ARGB一致、来源标签完整、无base64文本，原件/身份/golden/退出/清理通过。[第65节](verification.md#65-acp-rawoutput-结果回退与真实窗口验证)、[摘要](acp-raw-output-20261026.json)。不含真实外部模型消费或持久任务恢复；目标继续未完成。

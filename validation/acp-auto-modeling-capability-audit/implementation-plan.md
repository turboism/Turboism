# ACP 自动建模实施清单

依据：[2026-10-24 能力审计](README.md)。本文保留原实施计划和分阶段门槛；未勾选项表示整项尚未验收，不表示没有局部实现。用户后续已授权生产改动、API和实机；当前代码、实机证据与剩余工作见[当前检查点](current-readiness.md)及[实施验证记录](verification.md)。原整理阶段只交付文档，其边界仅作为历史保留。

## 1. 实施基线与边界

- 原审计基准为 `a7486647d66bbfb5e0c415523c38d5f0fcd1feac` 加原会话工作区修改；整理前 HEAD 为 `63993968018d88e8158303dd6f363bd4411f2721`，工作区干净。
- 原会话 Pi resources 补丁、权限摘要修复及采集器需确认来源；不推定已在当前代码或发行版中。
- 先完成 A，再进入 B；C 按专项需求拆分。API 名称均为草案，编码前以闭合 schema 和实际 SDK 限制定稿。
- 每个交付项记录审计 ID、精确版本、测试证据和限制。源码/单测/实机/发布状态分别记录，不能相互替代。
- 后续改动应保留授权、CAS、UI affinity、默认/共享形保护；不得引入 generic SDK invoke、反射、任意 shell 或原始文件路径后门。

## 2. Milestone A：真实头部编辑闭环

| 项目 | 前置依赖 | 交付物与主要位置 | 验收条件 | 审计 ID |
| --- | --- | --- | --- | --- |
| A0 基线与证据工具 | 无 | 找回/重建只读采集器及离线测试；核对 Pi/权限补丁；登记 Hiyori 来源、版本、许可与原件哈希 | 初始化失败不输出成功目录；分页完整；不导出凭据；无 tools/call；无 live endpoint 时明确失败 | ENV-01、ACP-02、ACP-07 |
| A1 身份、快照、准入 | A0 的基线结论 | 扩展现有 capabilities；定义 document/model/source/form 身份、generation、immutable snapshot 生命周期；Geometry/Deformer Domain 读 schema | 无文档/错类型/不支持版本/缺权限/删后重建/切页/旧快照均明确拒绝；不靠历史 revision 替代完整 expected state | ENV-01、DOC-01、OBJ-02、SAFE-01、KEY-03 |
| A2 三类 authoring 读取 | A1 | meshes/warps/rotations 读操作；source/stored/evaluated 分离；axes/cells/forms、GUID tuple、父关系/坐标单位；受控分页 | 一致不可变分页；不改 pose/选择；共享/默认标注；完整 tuple 定位；超限拒绝；求值新鲜度可核对 | GEO-01、GEO-06、DEF-01/02、KEY-02、ACP-04 |
| A3 typed 普通关键形写入 | A1、A2 | ArtMesh/Warp/Rotation 闭合 union；复用三个 stored batch writer；全目标 CAS、operationId 和回读收据 | 完整校验后写；精确数值回读；不完整 tuple/跨类型字段/NaN/Infinity/点数错误/默认共享/域外 Morph 全部拒绝 | GEO-04、DEF-01/02、KEY-03、SAFE-01/03 |
| A4 跨对象事务与结果核对 | A3 | 满足条件的新工具接入 AuthoringTransactionService；记录提交边界/结果；取消与未知结果协议 | 单/多对象一次 Undo；Redo 精确还原；中途故障回滚；响应丢失先核对；重复 operationId 不重复写；取消不能虚报回滚 | SAFE-02/03、ACP-05 |
| A5 绘制顺序/蒙版/必要外观 | A1、A2、A4 | 枚举属性读取/写入及 expected；版本与权限矩阵；必要时独立收据 | 属性幂等写和真实回读；遮挡/蒙版变化可见；拒绝域外值；未目标对象不变；确认 SDK Undo/事务边界 | GEO-05、LOOK-01、MASK-01 |
| A6 原生 render.capture | A1、A2 | Render Domain；复用 renderPixels；图像/受控资源结果，document/model/revision/pose/尺寸/坐标元数据 | 官方 renderer 真图；新鲜度对应；尺寸/像素/字节预算；错误不返回旧图；不使用 recent-file 缩略图替代 | VIS-01、ACP-04 |
| A7 pose 扫描与恢复 | A6、A4 的结果/取消协议 | pose_grid、参数备份/恢复、逐 pose 结果；超时/断线策略；必要时 allowlisted jobs | 成功/失败/取消均核对恢复；逐 pose 图像与参数一致；未恢复明确报告；不只返回最终 pose | VIS-03、SAFE-03、ACP-05 |
| A8 ACP 发现与 typed 图像 | A0；图像端到端验收依赖 A6 | Pi resources/list/templates/read 补丁与恢复回归；NativeAgentProvider.tool 到 UI 保留 typed image/resource；各 harness 验收表及 H1–H6 通信任务（见 [Paseo 参考](harness-reference.md)） | 模型上下文和 Swing 分别可见；非视觉模型明确提示；不输出大段 base64；恢复后目录更新；权限摘要可读 | ACP-01/02/03/06 |
| A9 几何/视觉 QA | A2、A5、A7、A8 | validation findings，含已实施检查范围、阈值、对象/form/pose 定位与证据；头部模板/修正计划 | 检出已知坏样本；退化/翻面/裂缝等几何检查；闭眼漏白/口腔穿帮/遮挡/体积漂移视觉检查；不以空结果冒充全检查 | QA-01/02、ASSET-02、RIG-01 |
| A10 保存与重开 | A4、A9；明确副本路径与用户文件操作授权 | 动态 SAVE 准入/完成核对；安全测试副本准备、检查点、保存与重开流程 | 当前能力发现可用后保存；失败不报成功；重开 form/绑定一致、原件哈希不变；“保存成功”与“重开一致”分别记录 | FILE-03/04、OUT-02 |
| A11 集成证据与发布门槛 | A0–A10 | Hiyori 副本可重复探针、证据 manifest、覆盖账本 owner/每操作准入、文档目录数字修正 | 满足以下 A 门槛；逐版本/逐 harness 声明范围；不将历史测试挪作新接口证据 | 全部 P0 |

实施进度：

- [ ] A0 基线与只读证据工具
- [ ] A1 身份、快照、动态准入
- [ ] A2 三类读取与分页
- [ ] A3 typed 普通关键形写入
- [ ] A4 事务、Undo/Redo、回滚与未知结果核对
- [ ] A5 绘制顺序、蒙版、必要外观
- [ ] A6 原生模型渲染
- [ ] A7 pose 扫描与参数恢复
- [ ] A8 ACP 资源发现与 typed 图像
- [ ] A9 几何/视觉 QA 与头部编排
- [ ] A10 保存、重开与副本保护
- [ ] A11 集成验收与发布范围记录

A10 的测试副本与重开可以使用明确授权的人工宿主流程；若要由 Agent 自动完成，必须提前实现最小 file grant/open/save-as 能力，不能等 B 才处理这一依赖。SAVE 能发起不等于保存完成，更不等于已重开验证。

A4/A7 的提交状态/取消协议须在 A3/A6 最终 schema 定稿前约定；长操作无法在现有传输时限内可靠返回时，引入仅允许已列举操作的 job。状态、取消、结果核对不可留作事后补丁。

### A 的硬性通过条件

- [ ] 在官方 Hiyori 副本，读取真实 form 后，分别执行至少一个 ArtMesh 顶点批次、Warp stored form、Rotation stored form 编辑；可使用独立副本/案例。
- [ ] 记录各项 source/form 前后哈希、精确数值回读及对应 pose 原生图像；UV/indices、非目标 form/对象/文档保持不变。
- [ ] 单对象和跨对象各验证单次 Undo/Redo 精确恢复；异常故障注入完整回滚；结果丢失与取消状态如实核对。
- [ ] 运行 XY 九宫格及所声明 XYZ、眨眼、口型组合检查；findings 与已实施检查项目对应；需要人工判断的结果明确记录。
- [ ] MCP 图像被视觉模型消费且 ACP 窗口显示；非视觉模型得到明确能力提示。
- [ ] 保存完成后重开，form/绑定保持；原件哈希不变；不将手工步骤伪称 Agent 自动完成。
- [ ] 实测精确版本、harness、权限与支持域写入验收记录；未测版本/路径明确待验收。

A 通过后只声明已验收的增量头部编辑闭环，不声明任意立绘到完整角色交付。

## 3. 每个新 Domain 的共同完成标准

- [ ] 输入/输出 schema 闭合、字段类型/数量/范围严格校验；三种几何坐标、GUID/display ID 与 default/shared/morph 范围有明确说明。
- [ ] 权限、effect、版本、UI affinity、transactionEligible、预算通过生产注册元数据可发现；覆盖账本增加实际 owner 与操作，不能泛化成 SDK 全覆盖。
- [ ] 无文档/不适配模式/版本/权限明确 unavailable；目标切换、旧 generation/snapshot、拓扑变化拒绝。
- [ ] 全目标写前校验、真实 SDK 事务边界、故障注入、回读警告/未知结果和重复 operationId 行为有针对性测试。
- [ ] 大数据分页属于不可变快照；写补丁在服务器还原完整 DTO；超限/超时/断线/重连不泄漏 token、不误报结果。
- [ ] typed image/resource 保留到模型和 UI；元数据能定位 document/model/revision/pose；不写入原始凭据。
- [ ] 文档和样例只使用已发现身份及占位符；草案接口不会被描述为现有可调用工具。

## 4. Milestone B：已拆分素材到新模型

前置：A 集成门槛通过；A10 若提前引入最小文件流程，B 扩展其完整范围。

| 项目 | 交付与验收 | 审计 ID |
| --- | --- | --- |
| B1 文件/文档/恢复 | 会话 grant、open/save-as/checkpoint；READ 不可写，失效/撤销/跨会话拒绝；覆盖批准；新建/打开身份与就绪核对；PSD 打开和生成/合并分开验收 | DOC-02、FILE-01–04 |
| B2 素材来源与绑定 | 图层/PSD/像素关联、注册/绑定、修订保护替换；明确许可与语义映射确认；可见非空网格 | ASSET-01/02/04、TEX-01 |
| B3 网格与参数结构 | 剖分预览、source/default 分离初始化、拓扑/UV 修改、参数组/排序/Combined、受支持 Morph；保护默认形不绕过 | GEO-02/03、PAR-02、KEY-03、MORPH-01 |
| B4 rig 算法 | 头 XY/Z、眼嘴/眉/发联动、镜像/补偿、复制映射/碰撞策略；保留已有 form，检查不对称素材 | DEF-03、RIG-01/02 |
| B5 图集与导出 | issued target、plan/apply/append、越界/重叠/缩放检查、有效绑定、原生 MOC/纹理/引用 manifest、授权落盘 | ATLAS-01/02、OUT-01/02 |
| B6 素材集成验收 | 许可分层素材 → 新文档 → 眼嘴头发网格/关键形 → QA → 保存重开 → 原生导出；实际加载目标资产 | L2 全链路 |

- [ ] B1 文件与文档
- [ ] B2 素材与绑定
- [ ] B3 网格/参数结构
- [ ] B4 rig 算法
- [ ] B5 图集与导出
- [ ] B6 L2 集成验收

## 5. Milestone C：完整角色交付扩展

- [ ] 单图分层/遮挡补画的外部能力、许可、风格与人工确认机制（ASSET-03）。
- [ ] 身体/四肢/服装模板与协调 QA；Glue 创建/删除须独立补齐支持域（RIG-03、GLUE-01）。
- [ ] 物理 typed read、安全 writer 与模拟回放；UI contribution 不冒充 writer（PHY-01/02）。
- [ ] 已有时间线 keyframe/curve 编辑逐版本准入；场景 add/delete 单独处理 reviewed Undo 和 capability（ANI-01/02）。
- [ ] expression/motion/pose、blink/lipsync 分组、完整交付 manifest 和目标播放器加载验证（EXP-01、OUT-02）。
- [ ] 专项验收通过后更新声明，未通过部分保持待交付。

## 6. 实机证据包要求

建议每轮记录一个不含凭据的 manifest，关联精确 commit/补丁摘要、宿主版本、SDK/插件版本、harness/模型及 vision 能力、授权范围、taskId/operationId、document/model/source/form 身份、pose、快照/事务收据、哈希、图像、findings、Undo/Redo/回滚/保存重开结果和已知限制。

分别标注源码检查、离线自动化、宿主只读、宿主可撤销写、文件持久化、播放器加载的证据层级。不要收录 descriptor 原文、Bearer、session header、无关本地路径。成功目录必须来自成功采集，不得根据源码拼接成 live 结果。

## 7. 本次整理状态

- [x] 审计文档落库，保留 59 项矩阵、状态、接口草案、安全约束和三阶段门槛。
- [x] 历史会话记录与当前工作区差异单独标注；缺失采集器命令只作存档。
- [x] A/B/C 实施清单与依赖、共同完成标准、证据要求写明。
- [ ] 生产能力完整交付与实机验收；局部实现及插件目录数字更新见第 8 节。
- [x] 重建只读采集器与离线测试；见第 20 节。
- [ ] 任意宿主实时目录、官方副本与完整验收。

文档数量、链接和空白检查由本次整理验证；不会因此将任何功能项标为已实现。

## 8. 工作区实现状态与下一步（早期切片快照）

本节表格/计数保留当时状态，后续实现与实机已推进至第65节；当前统一入口为[当前检查点](current-readiness.md)，勿用本表33工具或“未连接宿主”判断现状。

以下区分已有代码、已有离线测试记录和完整里程碑验收。此前逐切片的过程与命令保存在 [验证记录](verification.md)；本次文档整理未重跑生产测试，也未连接宿主。

| 范围 | 工作区状态 | 尚缺证据或交付 |
| --- | --- | --- |
| A1/A2 身份与读取 | 三类 source/stored 快照及原生 calculated observation 已接入会话缓存与一致分页；MCP 358 项回归通过 | calculated observation 的 freshness 明确为 UNPROVEN；成功求值证据、完整逐对象动态准入、实机身份/新鲜度验收仍缺 |
| A3/A4 关键形与事务 | `authoring.keyforms.write/result` 已接入；完整 expected snapshot CAS、SDK 自有事务、回读及会话内 operationId 核对已有模拟测试 | 真实宿主单/多对象 Undo/Redo、故障回滚、响应丢失核对；跨会话/重启恢复 |
| A5 属性与遮挡 | ArtMesh stored drawOrder、蒙版和 properties.read/write/result 已接入；后者支持三类对象源级 visible/locked 及 ArtMesh culling，含完整 native/CAS/事务/回读；MCP 374 项回归通过 | 空蒙版替换受 SDK 限制；opacity/颜色/混合模式、Part 身份、准入细分及实机 Undo/Redo/遮挡视觉核对仍缺 |
| A6/A7 渲染与姿态 | `render.state/capture` 和 pose_grid/status/cancel/result/release 已有实现及离线测试记录；该切片记录为 25 工具、MCP 303 项通过；最新全量记录为 33 工具、MCP 374 项通过 | 实机 GPU、局部裁剪、视觉模型消费、参数/历史恢复及持久化恢复；重复参数显示 ID 当前失败闭合 |
| A8 harness | Pi 资源桥接、native typed 图片、vision 检查及部分事件边界已有离线测试记录 | 逐 harness 握手/恢复、审批往返、取消竞态、模型消费与 Swing 展示实机验收；见参考矩阵 |
| A9 几何/视觉 QA | `validation.geometry` 已接入；12 项算法、7 项 Domain 测试及 MCP 322 项回归通过，目录增至 26 工具 | 交叉、裂缝、连续性及美术检查仍需实现与验收；没有实机质量证据 |
| A0/A10/A11 证据与持久化 | 审计、分阶段清单与证据格式已整理 | 成功实时目录采集、官方 Hiyori 副本、SAVE 动态验证、保存重开、原件保护与完整集成证据 |

所有 A 项保持未勾选，表示整项门槛尚未通过，不表示其中没有代码。后续实现切片已完成各自记录的编译、目录测试与打包；最新全量记录为 MCP 374 项通过、33 工具，见验证记录。此为已有记录，本轮文档整理未重跑。

建议后续按以下可独立评审的切片推进，实施须另行处于相应授权范围内：

1. 在已有几何诊断基础上补充交叉、裂缝、形变连续性与视觉检查，以及头部语义映射/rig 编排；保留 implemented/notImplemented，截断或参考退化不得返回完整通过结论。
2. 补齐 A1/A2 动态准入与 evaluated 新鲜度、A5 属性/蒙版；逐项记录 SDK 支持域及 Undo 边界。
3. 按 [harness 验收矩阵](harness-reference.md)分别验证模型与 UI 图像、权限、恢复和取消；保留 harness turn 与宿主 operation 两套状态。
4. 准备经授权的官方模型测试副本与文件流程，完成 A 的硬性通过条件；再决定 B 的素材导入与从零绑定实现范围。

原文档整理阶段仅修改文档。后续目标续行已实现并验证几何诊断切片；仍未连接或修改真实模型，未满足 A 的实机验收条件。

绘制顺序切片扩展现有 `authoring.keyforms.write`，不增加工具数量（仍 26）。支持纯 drawOrder、纯 positions、同 form 联合和跨 form 混合；通过 SDK 专用 draw-order 或 combined writer 写入，不移动当前 pose。源拓扑/UV、非目标 form 和省略字段全部保留。详见验证记录最新一节；A5 和 Milestone A 仍未完整通过。

蒙版切片新增 `authoring.masks.read/write/result`，生产目录共 29 工具。最多 64 个依赖对象、8 个写目标、128 KiB 写请求；快照 64 KiB、TTL 5 分钟、16 份；最多保留 256 个会话隔离的操作结果且不淘汰重放。空替换列表仍不受 SDK 条件批量接口支持，未以绕过保护的单独 setter 拼接实现。详见验证记录。

求值观察切片新增 `authoring.meshes.evaluated.read`，生产目录共 30 工具。引用同会话 authoringSnapshotId，逐页校验完整存储状态/父级/历史和完整原生样本；不触发求值，不混用坐标，缺失计算结果不回退存储形状。GUID 参数支持重复显示 ID，但不改变 render/pose 工具对重复 ID 的既有限制。此切片补观察数据，未补成功求值的新鲜度证明，因此 A2/GEO-06 保持未完整验收。

源级属性切片新增 `authoring.properties.read/write/result`，生产目录共 33 工具。明确区分对象源标志与实例/current-form 属性；不开放后者的隐式 setter。property snapshot 保留完整原生存储形状、父级、历史及全部已支持的源标志，写入使用显式布尔 patch、单次 SDK 事务与结果保留。后续需要专门补 opacity/颜色的精确 form 读写契约和混合模式原生读取，不能将当前实例 setter 改名后宣称满足 KEY/LOOK 全部场景。


## 9. 文件授权桥接与另存切片（待宿主验收）

工作区新增 `turboism.files.request_grant/grant_status/revoke_grant`，对应 FILE-01 与 A10/B1 的前置授权。请求异步返回，按会话和 operationId 去重；只返回 opaque grantId，不返回原生 handle、文件路径或内容。请求/授权 5 分钟过期，最多 128 个保留请求身份，最多一个原生待决选择窗口。撤销、删除会话、停止服务器和迟到授权均有释放逻辑；撤销不声称关闭了原生窗口。

授权桥接后已增加 documents.save_as/file_result，使用单次 WRITE grant 与原生命令结果核对；OPEN、覆盖批准、文件内容哈希、普通 SAVE 完成核对和重开仍未交付；不得据此勾选 A10/B1 或宣称可交付模型。SDK 覆盖账本扩展至 14 个 owner，仅新增 UserFileAccessService；未开放的内容读写逐项标为排除，不宣称全 SDK 覆盖。

后续用户已明确允许格式化并继续实现文件工作流，原自动审批边界已由用户澄清；格式化和格式检查现已通过。功能测试与限制详见 [验证记录](verification.md)。


`documents.save_as` 使用 authoring 读取返回的六字段 expectedState，前后核对 document/model/history，不将其称为完整几何 CAS。目标只接受同会话、仅 cmo3 扩展名的 WRITE grant；在宿主准入处单次消费，状态变为 CONSUMED；原生调用期间的句柄由 lease 持有，过期/会话关闭不冒充取消已启动调用，最终始终尝试释放。最多保留 128 个操作身份，重复/冲突/未知结果分别核对；file_result 无需 UI 等待。只开放 REJECT_EXISTING，防止没有独立覆盖批准便覆盖原件。

Runtime 核对修正下一步依赖：VerifiedTypedEditorCommandOperations 仅派发 SAVE_AS；OPEN 明确因首次打开行为未完整验证而 unavailable。后续须单独补 OPEN 的原生实现/精确版本验证，再完成保存重开。另存 EXECUTED 只记录原生成功报告（可含 no-op），persistenceVerified/reopenVerified 始终 false。该切片和 38 工具目录不能替代 A10/A11 实机证据。


## 10. 文件观察切片（待宿主与文件关联验收）

`files.inspect/inspect_result` 使用单次同会话 READ grant，异步读取限定字节并在专用 worker 上计算 SHA-256；仅非截断读取返回完整读取哈希。截断不返回前缀哈希，不输出路径/文件名/内容。最多 128MiB/请求，SDK 大文件 fallback 仍失败闭合；全局单个 I/O/hash 在途、128 个操作身份不淘汰重放。会话/服务器结束后丢弃结果，但原生 I/O 完成前不释放正在使用的句柄或假称已取消。

这为 FILE-03/04 的原件/副本哈希核对提供可授权观察原语。SDK 没有不可变文件快照，也没有将另存 WRITE grant 与新 READ grant 映射到同一文件的公开身份契约，因此 fileStability=UNPROVEN、documentAssociation=UNVERIFIED。它不能单独证明原件未变、另存落盘完整、CMO3 格式正确或重开一致，不自动升级 documents.save_as 的验证标记。OPEN 和 A10/A11 的实机证据仍缺。

最新目录/完整回归记录以 [验证记录](verification.md) 的文件观察切片为准；前述 33/38 工具数字均为各历史切片时状态。


## 11. 存储几何内部重叠检查切片

扩展现有 validation.geometry，不新增工具。对 source 与各显式 ordinary stored form 独立检测三角形内部正面积重叠，包含包含/重合/相邻折叠；只有边界接触不报内部重叠。输出 triangleIndex/relatedTriangleIndex 与 formGuid，可定位到实际拓扑。包围盒扫描、分离轴与精确二进制坐标 fallback 共享原 2M 工作预算；预算耗尽时明确 INCOMPLETE 和 intersectionCheckComplete/analysisComplete/comparisonComplete=false，不把部分扫描当全量通过。

这推进 QA-01/A9，不完成裂缝、跨对象交叉、边界/T 接触、求值/插值连续性或视觉 QA。重叠是待审几何 WARNING，不自动认定美术错误或执行修复。头部语义/rig 编排、官方副本实机、Undo/Redo、保存重开及 A 的完整门槛仍需继续。最新测试与算法交叉验证见 [验证记录](verification.md) 的内部重叠切片。


## 12. 单轴 Rotation rig 规划切片（待宿主与美术验收）

新增只读 `turboism.rig.rotation.plan`，目录为 41 工具；RIG-01/A 的规划原语继续推进，不勾选完整门槛。输入真实 rotations.read 会话快照、单个对象与唯一普通参数轴 GUID、拟用写入 operationId、显式 nativeAnglePerParameterUnit，以及 REPLACE_ANGLE_FROM_DEFAULT 策略。只支持 2..65 key 的完整、独立网格，最多 64 个非默认 form。不按名称猜测头部/Z 轴，不增加关键点、不推断轴心、不转换原生角度单位。

每个非默认角度替换为默认存储角度 + (key-defaultValue)×gain，最终一次转为 native float；不是向既有角度反复叠加。保留各 form 轴心、缩放、反射，保留默认 form、baseAngle、axes/cells 与未引用 forms。调用已有普通 writer 纯预检，拒绝默认/共享/重复 tuple/缺失默认和不完整网格；多普通轴不在当前规划域。规划前后验证完整 native authoring snapshot、父级与文档/历史身份。结果 128KiB 上限，返回闭合 keyforms.write 请求及限制说明；不调用 setter、移动 pose、渲染或事务。

预览不保留或预占 operationId，不授予写权限，也不保证之后仍新鲜；提交已有写接口时重新动态准入、CAS、事务和回读。现有目标角度会被替换，必须结合实际原始快照和渲染审阅。该算法仅为显式映射的线性角度模板，不完成头部 XY 透视、眼嘴联动、语义映射、父子多对象协调、视觉修正或实机 Undo/保存重开验收。


## 13. 逐 form 普通 writer 资格发现切片

三类存储读取的分页新增 ordinary_write_eligibility 行，放在几何数据之前，沿用同一会话不可变 snapshot 和分页/字节/TTL 预算，不增加工具（41）。每个存储 form 返回 snapshotEligible、明确 reason 与 hostWriteAdmission=UNPROVEN。普通 writer 与报告共用 Grid/默认形/引用规则，区别默认、共享、Morph 引用、无普通 cell 与全局异常网格。异常网格仍可读，不能因已读到 snapshot 就推断存在可写 form。

这是 ENV-01/A1/KEY-03 的前置规则发现，不是完整动态写准入。SDK 当前没有独立的普通 form writer availability 查询；读取成功不证明 write selector、权限、UI 编辑模式、事务、payload 预算已准入。分页资格属于签发状态而非每页 live 验证，写接口仍必须执行完整 CAS 与实际原生准入。后续需补 SDK/Runtime 只读动态写能力查询、精确宿主验证及细分属性/Morph/render/export 能力，不能把 snapshotEligible 当作 AVAILABLE 或完成 A1。


## 14. SDK/Runtime 普通 writer 前置条件观察

新增 SDK OrdinaryKeyformWriteSupport 及 Drawable/Deformer 的默认只读 ordinaryKeyformWriteSupport；旧实现返回 UNKNOWN。Runtime 三类对象视图核对当前对象身份后查询现有 selector 授权元数据，分别返回实际 hostVersion、reviewedHost、writerSelectors、historySelectors、transactionCoordinator；Rotation 额外包含其 source writer guard 的 selector 要求。权限封装要求 model.read，独立核对 model.write 并报告 PRESENT/ABSENT，不授予权限、不执行 setter、事务、刷新或 no-op 写入。

三类存储读取新增 ordinary_write_support 行，随同 snapshot 签发并分页；保留旧实现 UnsupportedOperationException→UNKNOWN 兼容路径，不将缺少查询误作几何读取不可用。字段均为 UNKNOWN/PRESENT/ABSENT 的观察；事务协调器只报告存在，不表示已进入事务或 binding 一定可提交。宿主 selector、权限和模式可能随后变化，字段非跨时刻原子准入票据；逐 form 仍使用既有规则，hostWriteAdmission=UNPROVEN。默认锁/编辑模式、完整 CAS、载荷与真实事务仍在实际写边界验证。

此切片补 ENV-01/A1 的可发现证据，替代上一节“完全没有只读基础设施查询”的现状，但未完成所有对象、渲染/导出/Morph 细分准入，也未完成精确宿主实际写入/Undo/保存重开。生产目录仍 41 工具，SDK 覆盖账本仍有限 14 owner，不因 SDK 新增方法扩大为全 SDK 覆盖声明。


## 15. Warp 锚点位移场规划

新增只读 rig.warp.plan（生产目录 42 工具），针对实际 warps.read 会话快照的一/二个完整普通轴生成已有非默认关键形计划。显式 GUID、双侧端点锚点（各 1..16）、原生存储坐标位移与 REPLACE_CONTROLS_FROM_DEFAULT；不按名称或像素猜测五官/左右/轴心。按逆距离平方归一化权重扩展锚点位移场，各 key 相对默认/已声明端点缩放；XY 两场相加。精确锚点不插值漂移，中间计算保持 double 到最终存储位置再转换 float。

保留默认形、网格尺寸、quadTransform、axes/cells 和无引用 form；已有目标 controls 被替换，重复规划不累积。最多 65 cells/64 edits/4096 计划控制点/240KiB 结果；默认 key 必须精确存在，双侧已声明 key 在参数范围内；不完整/共享/重复 cell/重合锚点/重复索引/错误 GUID/数值溢出均拒绝。前后完整 native/父级/文档历史核对，复用 ordinary writer 纯预检；返回独立 keyforms.write 请求，不执行 setter、pose、render 或事务。

此切片让 RIG-01 的半自动模板具备锚点传播与 XY 基础组合，不等于头 XY 透视/体积/轮廓/五官联动算法已通过。尚无 XY 角点专项修正、Warp 折叠/插值连续性诊断、美术语义确认或视觉验收；目前锚点由调用者显式规划，任意素材自动建模仍需后续算法和实机门槛。A 与持续目标保持未完成。


## 16. Warp XY 显式角点残差修正（2026-10-25）

扩展既有 rig.warp.plan，不新增工具。可选 cornerCorrections 为零至四个象限分别提供两个原生参数 GUID 的 NEGATIVE/POSITIVE 方向和 1..16 个残差位移锚点；方向必须精确覆盖两轴，不允许重复象限，单轴计划拒绝角点字段。省略或空列表保留原有加性计划行为。

残差采用同一默认控制点上的逆距离平方插值，并按两轴相对默认/本侧声明端点的归一化比例之积叠加。任一轴为默认值时为零，端点角为全残差，中间 key 按比例缩放；仅影响指定象限。输入是额外位移，不是目标绝对坐标。输出 cornerCorrectionCount，闭合 schema 与全部既有 CAS/普通形资格/预算保持。默认形、单轴形和未指定象限不会因残差改变；从默认重建保证重复规划不累积。

此项支持人工或后续算法明确提供 XY 角点调整，替代上一切片不支持角点的限制。不推断脸部体积、透视或五官关系，不声称 Cubism 求值插值等于该权重公式；Warp 折叠/连续性、视觉 QA、实际写入/Undo/保存重开仍需专项验收。A 与持续目标保持未完成。


## 17. Warp 存储/拟议控制网格质量诊断（2026-10-25）

新增只读 validation.warp，生产目录 43 工具。使用 warps.read 会话快照、显式普通参考 form 和 1..32 个目标 form；可选 proposedEdits 复用普通 writer 完整 tuple/资格/点数预检，在内存中构建拟议快照，不调用 setter 或事务。提议只能替换已选目标，不能覆盖参考。默认/共享普通 form 可只读检查，未引用 form 拒绝。

按 row-major 相邻四控制点建立双线性代理单元，精确二进制 float 行列式符号检测四角 Jacobian 混合符号（局部折叠），显式角点 abs(det)/单元包围盒对角线平方阈值检测退化；规则目标与规则参考比较方向。参考可以为一致镜像，不假定正向即正确。findings 返回 formGuid、row/column、四点索引和最小角点归一化行列式；参考/目标不规则时跳过方向比较并 comparisonComplete=false。最多含参考 8192 单元样本、200 保留 findings、240KiB 结果，预算在计算前拒绝或在编码后失败闭合，不返回部分 verdict。截断保留总 finding 数。完整原生/父级/文档历史前后核对，不移动参数、选择或渲染。

这为 Agent 的 PLAN→几何检查→审阅→独立写入增加真实风险定位，不代表 Cubism 原生 Warp 曲面/quadTransform 插值或求值几何已通过。全局单元重叠、父级变换、pose 连续性和美术/视觉质量明确列为未检查；需要渲染与实机证据继续完成 QA-01/02 和 A，而非将无 findings 作为模型验收。


## 18. ArtMesh 锚点规划与拟议几何诊断（2026-10-25）

新增只读 rig.mesh.plan，生产目录 44 工具。meshes.read 实际存储快照、一/二轴 GUID、双侧端点顶点锚点及可选 XY 象限残差；只对完整独立普通网格、精确默认与双侧已声明 key 规划。REPLACE_POSITIONS_FROM_DEFAULT 替换非默认目标，保留 source/UV/indices、所有绘制顺序、默认形、axes/cells 与无关 form/Morph 记录。不创建关键点、不改变拓扑或贴图。Morph 引用、共享/默认不安全形通过实际 ArtMesh ordinary writer 预检拒绝；实际提交仍重新验证宿主准入和完整 CAS。

抽取 McpLandmarkRigPlan 共用原生轴/cell/点列表上的逆距离平方传播和象限权重；Warp 与 ArtMesh 各以 typed adapter 提供实际元数据，未伪造 Warp 网格、转换坐标空间或共用错误的 SDK writer。保留 Warp 全部端点/角点/数值/预算回归。每个计划仍最多 65 cells/64 edits/4096 计划点/240KiB，前后 native/父级/文档/历史核对，无 setter/pose/render/transaction。

扩展 validation.geometry 的可选 positions-only proposedEdits，与 Warp 诊断一样在内存中构造拟议快照。原样接入规划目标 edits，formGuids 来自 edits，显式参考不得改变；拒绝未选目标、非法 tuple/资格/点数/字段。保留既有完整/不完整与 findings 截断语义。此链路让 Agent 可执行 PLAN→检查拟议网格→审阅→独立 APPLY，尚不执行自动美术修复，不保证默认 source 与 stored 坐标一致，更不完成头部透视/体积/眼嘴/遮挡语义、视觉与真实 Undo/保存重开验收。A 和持续目标仍未完成。


## 19. 原生渲染局部裁剪与多 pose 来源映射（2026-10-25）

扩展 render.capture/pose_grid 的可选原生像素 crop，不新增工具（44）。完整 width/height 仍传给原生 renderPixels；PNG 只编码所选矩形，输出尺寸与 renderWidth/renderHeight/crop、左上原点坐标关联。省略时记录全幅，modelToPixelTransform 仍未知；不是模型空间 ROI、原生相机缩放或插值放大。固定矩形用于一轮 pose 扫描的所有帧，并保留在部分失败/恢复后的图像元数据中。

crop 闭合字段/有限整数/正尺寸/完整边界验证在渲染前执行，扫描在任何参数写入与事务前拒绝不合法范围。operationId 签名包含 crop，重放不重新渲染。原生像素、累计像素和时间预算不因小裁剪提高；PNG 字节预算计算实际裁剪结果。不增加文件访问、写入或权限。眼嘴细节定位可直接基于原生 raster 像素人工/算法选择区域，但不得将像素偏移当作 authoring 坐标变换或视觉验收。

此切片推进 VIS-03/A6–A9 的局部观察；SDK 尚无独立存储 opacity/color form 契约，未用当前 pose setter 冒充逐形写入。原生放大/模型到像素矩阵、语义 ROI、视觉缺陷算法、实际 GPU/harness 图像与保存重开仍待继续。A 与持续目标保持未完成。


## 20. 只读 stdio 目录证据采集器重建（2026-10-25）

重建 collect_live_catalog.py 和 unittest 套件，补 A0 缺失的可执行证据工具；不是恢复原会话未提供的脚本，也不挪用历史两项测试。仅可信显式 stdio 描述符或 TURBOISM_MCP_CONFIG，subprocess argv 启动桥接，不使用 shell/HTTP/token 原文。方法闭合，只 discover 目录与 editor-commands 资源，不允许 tools/call/其他资源/prompts/get；绝不保存、打开文档或操作模型。

完整初始化/协议协商与全部分页成功后才原子输出目录身份/数量/schema 哈希和 direct 命令 IDs，原始描述/响应/配置/凭据均不写入。初始化 -32000、协议/分页/超限/断线/超时返回常量错误码，失败不覆盖历史报告，配置不能被输出覆盖。子进程 stdio 假桥接验证实际 wire 方法顺序和清理逻辑；不连接真实宿主。报告记录 capturedAt，但不是跨目录原子快照或工具可调用性验收。

本轮核对 OPEN：VerifiedTypedEditorCommandOperations 只准入 SAVE_AS，OPEN 因首次打开行为未完整验证而明确 unavailable；未找到可复用的已审 OPEN selector 序列，没有据枚举或 direct 命令存在便开放它。下一步仍须精确宿主行为/授权与新旧文档身份设计后补实现/验收。A0 仍缺成功实时目录及 Hiyori 来源/许可/原件哈希，A10/A11 仍缺保存重开；没有勾选整体门槛。


## 21. ACP 工具图片来源标签与 Pi 协议协商（2026-10-25）

ACP 的 typed 图片已能到达 image listener，但旧附件名称统一为 image。本轮在 AcpClient 工具结果事件中，从同一 update 的首个有界 JSON 元数据匹配图片：单 capture 必须唯一图片且位于 content index 1；pose-grid 按明确、唯一的 imageContentIndex 关联 frame，允许帧元数据重排，拒绝错误/重复/非图像索引。标签显示稳定工具调用 key，以及工具报告的 capture、pose label、文档/模型/revision、尺寸和合法 crop；缺信息或跨 update 时仅工具归属，不缓存上一 pose 猜测。每字段有界、先脱敏再截断并标省略，沿用附件显示/回放/Swing 预览路径，不改变图片数据或 listener 契约，不将工具报告当独立新鲜度证明。

另核对 Pi bridge：旧实现 initialize 和 HTTP 版本头固定 2024-11-05，与当前 MCP HTTP 支持集不符。本轮优先提示 2025-11-25，初始化不预发版本头；对四个已知版本的协商响应准入，后续 HTTP 请求/通知使用所返回版本，覆盖配置中的大小写混合旧头。未知版本在 initialized 通知、目录读取或工具注册前拒绝。无任意 native 调用、模型操作或新增 MCP surface。

native Codex/Claude/Pi 假进程、ACP-v1 假传输和 Swing 组件测试覆盖来源映射；Pi 的严格版本 HTTP/SSE 和 stdio 假服务验证协商。完整外部 CLI、视觉模型消费、真实 Swing 窗口/宿主渲染、恢复会话仍需专项实机验收。A8/ACP-01/02/03 因这些局部证据推进，但 Milestone A 和持续目标保持未完成。


## 22. ArtMesh 逐存储 form 透明度观察与 CAS（2026-10-25）

扩展 SDK ArtMeshAuthoringSnapshot.Form，新增 Optional<Float> opacity；保留三参数构造器并返回未知，不将历史实现的未知值替换为 1。字段保存有限的原生存储值，不钳制 Morph 值或将其解释为普通形绝对透明度。Runtime 在已审宿主、原存储读取准入之外独立核对 drawable-form.opacity 读取 selector；缺少 selector 时继续返回完整几何与未知 opacity，准入后数值不合法则整次读取失败，禁止静默退回未知。读取目标始终是逐个 native stored form，不是 current/evaluated form 或移动参数后的对象 opacity。

MCP meshes.read 的每个 mesh_form 行返回 opacity 数值或 null，沿同一不可变 snapshot 分页与预算；不增加工具（44）。已观察值纳入完整快照相等性和原有 CAS：同 history revision 的 opacity 变化或未知→已知变化，也使旧写计划失效。ArtMesh 普通 positions/drawOrder/combined 和 Morph positions/drawOrder 的预期快照构造保留该字段；mesh 锚点计划和拟议诊断复用同一纯预检，不生成透明度写入。

这是 LOOK-01/GEO-04/SAFE-01 的观察与状态保护前置切片，替代此前 ArtMesh 完全无逐形透明度观察的限制；尚不提供逐形 opacity setter、颜色、Warp/Rotation 透明度或跨对象外观算法。不能用当前 pose setter 冒充 writer；后续需独立的闭合 typed edits、默认/共享/Morph 保护、完整 expected appearance、Undo/回滚/回读准入。未据此勾选 A 或真实模型验收。


## 23. 普通 ArtMesh 透明度与联合存储值写入（2026-10-25）

新增 SDK ArtMeshKeyformValues，闭合为完整 coordinates/formGuid 以及可选 positions/drawOrder/opacity，至少显式提供一项。positions 与坐标列表防御性复制，drawOrder 限 0..1000 整数，普通 opacity 限有限 0..1。Drawable.setOrdinaryKeyformValues 为新增 default 方法，旧 provider fail closed；权限封装复用已存在 SET_DRAWABLE_KEYFORMS_BATCH 语义事件，动态会话 wrapper 保留代际 guard 并转发不可变输入。

Runtime 将稀疏 edits 与完整 expected authoring state 合成一次 keyform contribution。opacity 写入额外要求已审读取/写入 selector 与实际已知、受支持范围内的存储 opacity；不扩大原几何 writer 的必需 selector 集合。先做完整 source/geometry/axes/cells/forms/Morph 状态 CAS、完整 tuple 与实际 form 身份、默认/共享/Morph/重复/点数检查，再执行任何 setter。省略字段从 expected 保留，整批仍限 4096 form/2000000 target vertices（包括保留位置）。使用逐 native stored form 的 set-opacity，不调用当前 pose setter、不移动参数或选择。

MCP 既有 keyforms.write 的 ArtMesh edit schema 扩展为七种非空 positions/drawOrder/opacity 组合，不增加工具（44）。有 opacity 的目标批次调用新 typed SDK 方法；无 opacity 的调用保留原位置/顺序路由。完整 before/after 及实际回读包含存储 opacity，结果 hash、operationId/replay、跨对象事务/回滚沿用既有边界。有 opacity 批次的所有保留顶点计入 16384 点预算；默认/共享/Morph、未知透明度与支持域外存储值均拒绝。

恢复只在已准入的 ordinary opacity writer 中修复已知、独立、非默认且无 Morph 引用的普通存储透明度。原几何恢复路径不因此获得 opacity setter。多 contribution 的原生 abort/补偿回滚按中间完整快照逆序恢复；无法证明完整恢复仍沿用 RECOVERY_FAILED/OUTCOME_UNKNOWN，不能伪报回滚成功。该切片补 LOOK-01/A4 的普通 ArtMesh 透明度原语，未实现颜色、Warp/Rotation 透明度、Morph opacity writer、眼嘴自动渐隐、美术算法或精确宿主实机验收。A 与持续目标仍未完成。


## 24. 每操作 selector 发现与动态会话类型保留（2026-10-25）

扩展 OrdinaryKeyformWriteSupport，新增闭合 Operation→Check 的不可变 operationSelectors；保留原六参数构造器，旧 provider 返回空 map，缺失条目通过 selectorsFor 明确为 UNKNOWN。withWritePermission 只装饰权限，不丢失每操作观察。ArtMesh 分 positions、drawOrders、values（顶点/顺序）及 valuesWithOpacity；Warp 为 controlPoints，Rotation 为 forms。Runtime 按各 writer 的实际 alias 集合查询元数据；opacity 独立使用已存在扩展集合，Rotation 保留 source-writer 额外 guard。查询不调用 setter、事务或 no-op 写入。

动态 SessionDrawable/SessionDeformer/SessionWarpDeformer/SessionRotationDeformer 之前漏转发该 default 查询，导致底层已知状态被 UNKNOWN 遮住且未走代际 guard；补齐带 guard 的精确转发。同链路发现 SessionDeformers.find/all 总是返回普通 SessionDeformer，抹去实际 Warp/Rotation 类型，阻断 MCP 用通用集合读取/写入 typed form 的路径。现按真实 delegate 类型选对应 session wrapper，保留 plain fallback、host 顺序、不可变集合、unwrap/remove/applyToChildren 和代际保护；通用集合所得 typed wrapper 也保留原 modelId/modelGeneration 的 appearance 路由，既有专用集合构造路径的 unavailable 外观语义不变。

MCP 三类存储读的 ordinary_write_support 行新增 operationSelectors 与闭合 schema，空 map/缺失项不推断 ABSENT。字段随同不可变 snapshot 分页、TTL/预算缓存，不能当作当前 admission；PRESENT 与 reviewedHost、history、coordinator、permission、逐 form eligibility 均独立。写入仍实时做原生准入、完整 CAS/模式/默认形/共享/Morph/预算及事务检查。目录保持 44 工具、15/2/8，有限账本 14 owner。

该切片修复 SDK→会话→权限→MCP 中的实际信息/类型丢失，补 ENV-01/SAFE-01 的可发现性，但不是全 SDK 逐操作能力准入的完成声明。真实宿主、官方副本、渲染/视觉、Undo/Redo/保存重开门槛仍需证据；A 与持续建模目标保持未完成。


## 25. 多轴 Rotation 组合规划（已实现，待实机验收，2026-10-25）

目标：解除只读 Rotation 规划器的单轴限制，让 Agent 对已有一至三普通轴的独立完整存储网格生成角度组合计划。范围仅 MCP 的 planner、schema、测试与说明；不新增原生 selector、不扩大 SDK writer 域、不连接宿主。旧单轴 parameterGuid/nativeAnglePerParameterUnit 输入保留；新增互斥 axes 输入，必须逐 GUID 覆盖全部实际普通轴。

角度策略仍为 REPLACE_ANGLE_FROM_DEFAULT：目标角度 = 全轴默认存储角度 + Σ((cellKey-axisDefault)×显式 gain)，按快照轴顺序求和，最后转换 native float。保留每目标 origin/scale/reflection、baseAngle、默认形、axes/cells 与无关 form。要求精确默认、唯一键与 tuple、独立 form、完整 Cartesian grid、最多 65 cells/64 edits、128KiB 输出；完整 ordinary writer 纯预检及前后 snapshot/父级/文档/历史检查保持。

离线验收：旧输入与新单轴结果一致；乱序 GUID/cell 坐标正确匹配；二轴和三轴组合值准确；重复规划不累加；默认和非角度字段不变；缺轴/多轴/重复轴/共享/不完整网格/重复 tuple/非有限/float 溢出/预算拒绝；闭合互斥 schema；严格只读代理和 stale/权限测试。之后运行 MCP 完整测试、格式及打包检查。真实插值、XYZ 美术效果、Undo/Redo 与保存重开不由此切片证明。

实现结果：保留旧输入与 single_axis_rotation_plan 元数据，另增加 multi_axis_rotation_plan 准入说明和有限语义覆盖行；生产工具目录仍为 44、owner 仍为 14。新 schema 在根对象公开全部具名字段，以闭合 oneOf 拒绝混合旧/新输入。实际实现按完整 GUID tuple 与 native cell 身份匹配，在 double 中按快照轴顺序累加，最终转换 float；无默认/共享写或部分轴隐式选择。新增 7 项回归（纯规划 6、Domain 1）；Rotation 专项 14 项通过，完整 MCP 518 项零失败/错误，1 项 headless UI 跳过，格式和打包通过。未改 SDK/Runtime/ACP，本轮未重跑这些模块测试。无宿主操作和新增实机证据，A 与持续目标仍未完成。


## 26. ArtMesh/Warp 三轴组合与残差规划（已实现，待实机验收，2026-10-25）

目标：现有 ArtMesh/Warp 锚点模板从一/二轴扩展到显式一至三普通轴，使已有 XYZ 绑定可使用同一 PLAN→拟议几何检查→单独 APPLY 链路。改动仅 MCP 共享纯规划器、两类 adapter/Domain/schema、测试与文档；不新增原生 selector、写域或工具，不连接宿主。

每轴 GUID 必须覆盖实际绑定且独立提供正负端点锚点。完整独立 Cartesian 网格、精确默认与双侧 key、65 cells/64 edits/4096 计划点/240KiB 预算保持。目标点仍从默认形叠加各轴归一化位移，旧一/二轴结果保持。cornerCorrections 扩展为显式二轴象限或三轴卦限残差：directions 为 2..3 个不同的已绑定 GUID；权重只乘所声明轴的同侧归一化幅值，未声明轴不参与。二轴残差跨第三轴保持，三轴残差在任一声明轴默认面为零。三轴最多 12 二轴象限与 8 三轴卦限，共 20 唯一残差。

验收：完整三轴点位与 pair/triple 权重、非零默认/中间 key、GUID/坐标乱序、default/无关 form/source/UV/拓扑/order/opacity/网格信息保留、稳定重复规划、超限/错轴/重复残差/共享/不完整网格拒绝；两类 ordinary writer 纯预检与拟议几何诊断接收结果；旧 Domain stale/只读/模式和预算回归保留。运行针对性与 MCP 最终完整验证；不将这些结果声明为插值、美术或实机验收。

实现结果：共享位移引擎与两类 Domain/schema 已扩展，仍为闭合显式输入。旧一/二轴与四象限测试保留；第三轴没有放宽默认/共享/Morph 或 CAS 保护。新增 8 项回归（共享三轴纯规划 4、两个 Domain 各 1、两个诊断 Domain 各 1）；专项 56 项通过，最终 MCP 526 项零失败/错误、1 项 headless UI 跳过，格式与打包通过。三语说明和有限语义覆盖行同步，工具仍 44、owner 仍 14。无 SDK/Runtime/ACP 改动或新增实机证据，A 与持续目标仍未完成。


## 27. 已保留 pose 图片的局部像素 QA（已实现，待实机验收，2026-10-25）

目标：补齐 QA-02/VIS-03 的可重复局部视觉证据原语。新增只读 validation.raster，输入会话内 pose-grid operationId 与 1..32 个显式 ROI 检查，不接收原始路径/URL/PNG/base64。只访问终态且未释放的已有帧；不调用宿主、移动参数、渲染或写入。历史报告明确来源为 retained pose，不推断当前模型新鲜度，保留 scanOutcome/恢复状态/完整采集状态。

闭合两种检查：color_fraction 按明示 RGBA 8-bit min/max 统计命中像素；frame_difference 在同一原生 raster/crop 坐标下比较两帧，按 premultiplied RGB 与 alpha 的最大通道差超过 tolerance 统计变化像素。每项显式 fraction 上下限、ROI、frameIndex 和唯一 checkId；差异项显式 referenceFrameIndex。返回逐项计数/比例/是否达标、全部显式检查是否达标、帧 captureId/hash/pose/state/crop 等来源。无美术/语义/全局质量通过结论。

预算：最多 32 checks、4194304 ROI samples、1048576 decoded pixels/3MiB 原 PNG/240KiB report。解码前核对可用帧/ROI/完整坐标匹配及预算，核对 PNG 字节长度/hash、实际尺寸和服务端捕获元数据；同帧只解码一次，报告不回传图片或 base64。跨会话/运行中/已释放/缺帧/坐标不一致/超预算明确拒绝，不补渲染、不输出旧图当当前。终态部分扫描可检查实际存在帧，但全量采集和恢复状态单独报告。

离线验收：合成 PNG 的颜色/alpha/边界/透明隐藏 RGB/差异 tolerance、显式坏样本、ROI 与 fraction 边界、来源完整、身份/释放/状态/预算/哈希损坏拒绝、严格 no-host 查询、生产 schema/目录/账本/协议回归。真实视觉模型、Swing 与宿主渲染效果仍待实机验收。

实现结果：新增 turboism.validation.raster，生产目录 45 工具（15 资源/2 模板/8 提示词不变，有限 owner 14）。通过原 pose Job 锁取得终态元数据与图片字符串的不可变副本，再执行有界内存检查；释放不回收已开始分析的局部副本，不引入额外长期像素缓存或重放写入。输入闭合，配置和计数完整回传，原生输出 crop 与局部 ROI 坐标分别记录。新增 8 项回归；含覆盖账本的专项 31 项通过，最终 MCP 534 项零失败/错误、1 项 headless 界面跳过，格式与打包通过。尚未实现语义 ROI/美术判定或实际 vision/harness/宿主验收，不将 checksPassed 当作 Milestone A 通过。


## 28. 显式头部深度场的 Warp 透视规划（已实现，待实机验收，2026-10-25）

目标：补 RIG-01/A9 的可复用头转动模板，降低逐点编写位移场的需求。扩展现有 rig.warp.plan，新增互斥 policy=PROJECT_HEAD_VOLUME_FROM_DEFAULT，保持原锚点模式。显式 headVolume={centerX,centerY,radiusX,radiusY,depth,cameraDistance} 与 axes=[{parameterGuid,component:YAW/PITCH/ROLL,degreesPerParameterUnit}]；全部绑定轴需映射且 component 唯一，不按对象/参数名称推断头、左右、轴心或单位。

参考为完整存储网格的全轴默认 controls，使用原生存储坐标。默认图像平面的椭圆正面深度场 z=depth*sqrt(max(0,1-u²-v²))；椭圆外 z=0。通过 X=(x-centerX)*(d-z)/d、Y=(y-centerY)*(d-z)/d 把默认图像点提升到相应相机射线，绕 (centerX,centerY,z=0) 依次 YAW(+Y)、PITCH(+X)、ROLL(+Z) 后投影 x'=centerX+d*X'/(d-Z')、y' 类似。这是图像坐标定义的近似深度场，不是重建完整 3D 网格。角度=参数相对默认值×显式 degrees gain；默认姿态精确保留，零角度直接返回原点。

复用普通独立完整网格/默认/共享/Morph 支持域与预算检查，至少 3 key/轴、双侧默认、一至三轴、65 cells/64 edits/4096 计划点/240KiB。拒绝非有限、非法半径/深度/相机、YAW/PITCH 超 ±85°、ROLL 超 ±180°、投影距离不足相机距离 1%、float 溢出与缺轴/重复 component；默认/网格/quadTransform/绑定/无关 form 保留。只生成可交给 validation.warp 的拟议 edits；不创建对象、重建层级/遮挡、不写入/渲染。

验收：解析中心点 YAW/PITCH 与平面 ROLL、零角度/默认不变、非零默认/多轴顺序与组合、回写预检/重复规划稳定、source/无关 forms 不变、所有拒绝路径、旧模式 schema 互斥/边界保留、严格只读 Domain stale/权限及拟议诊断链。专项与 MCP 全量回归后记录边界；头体积美术效果、children 实际插值、眼嘴/遮挡与实机仍待验收。

实现结果：新 policy 已接入原 rig.warp.plan 与闭合互斥 schema，旧锚点/残差模式继续通过回归。profile 在构造时拒绝 cameraDistance-depth 小于相机距离 1%，零 gain 也不绕过该配置限制；逐点还检查旋转后有限分母及 near plane。新增 head_volume_warp_plan 有限语义行，版本复用既有 ReviewedVersions，无原生 selector/SDK 变更。新增 6 项回归；专项 34 项通过，最终 MCP 540 项零失败/错误、1 项 headless UI 跳过，格式/打包通过。工具仍 45、资源/模板/提示词 15/2/8、有限 owner 14。该近似模板不证明头部美术、children 插值/遮挡或实机，A 与持续目标仍未完成。


## 29. 跨对象普通关键形请求的只读预览（离线实现完成，2026-10-25）

新增 authoring.keyforms.preview，输入与 write 完全相同的闭合 typed 请求。提取写入既有解析/缓存身份/完整 CAS/父级/普通 form/聚合预算/摘要预算为共享只读预检；write 的幂等结果优先级、原生事务、结果保留与失败语义不变。preview 前后核对全目标状态与历史，拒绝已实际 admitted 的 operationId，不预留新 ID、不失效快照、不解析 authoringTransactions、不调用 setter/pose/render/selection。

返回 applied=false、精确 requestHash、discovered state、对象/form/预算点数量、原有 before/after 哈希与逐对象 diff 摘要（变更 form/点数、原生坐标最大点/轴心位移、角度/缩放增量、drawOrder/opacity/反射变化及 formGuid→changedFields）。统计严格基于完整 before/expected after，不回传全部坐标；最多 240KiB preview，既有 8 objects/64 forms/16384 budgeted points/768KiB input 与 96KiB 原始摘要上限保持。多对象不同父空间的位移只逐对象报告，不合成模型空间距离。

预览检查的是结构、快照一致性和支持域，不检查 live writer 准入/写权限/原生 Undo、几何或美术质量，不授予写入权且不能代替提交时 CAS。旧 ID 先查 result，不将预览当结果核对。验收覆盖三种对象及 opacity/order、no-op/差异统计、两次读的 stale/父级/跨会话/失效、非法/default/shared/Morph/预算、零写入/事务/ID 预留、预览后同 ID 真正写入与结果哈希一致，并运行 MCP 全量回归。

实现结果：已新增 turboism.authoring.keyforms.preview，生产目录 46 工具、15 资源、2 模板、8 提示词，有限 SDK owner 仍 14。ordinary_stored_preview 为 READ/NOT_APPLICABLE；写入仍保持既有幂等结果优先级与事务/结果语义。预算值完全复用 write：纯 drawOrder 为零点；positions 或 opacity/联合批次保留的顶点计入预算，不因预览另改写入范围。

新增 5 项 Domain 回归：三类型精确差异/哈希与后续同 ID 写入、重复预览/未保留结果、no-op/order/opacity、历史/父级/文档/模型/native/第二次核验变化、跨会话/default/闭合字段/未知 opacity，以及 shared/Morph/TTL 过期拒绝。夹具统计事务服务查询次数，证明预览连 authoringTransactions 都不解析；既有共享 preflight 的预算/非法数值/失败回滚回归继续全量执行。专项 65 项通过（新增过期等测试前），最终 MCP **545 项，0 失败/错误，1 headless UI 跳过，56 套件**；格式检查与 JAR 通过。三语 README 和覆盖账本已同步。无 SDK/Runtime/ACP 改动或新实机证据；Milestone A 与持续目标保持未完成。

## 30. 已注册模型图像的可核对绑定（离线实现完成，2026-10-25）

补齐 TEX-01 的已注册图像复用链：assets.binding.read / bind / result。读取依赖真实 ArtMesh authoring snapshot，观察 textureInput 与有界 modelImageGroups 候选；签发会话内不可变依赖快照，保留完整 native authoring/父级/锁/历史/绑定/候选列表 CAS。写入只接受已发现的 modelImageId，明确拒绝锁定、父变形器、已有不同图像或图集绑定，不提供覆盖或自动解锁。自己管理 SDK 事务，内部重验状态、校验 ModelImageBinding 与精确 textureInput，异常读回回滚；结果保留/replay/outcome_unknown 与既有工具对齐。native bind 在已有事务内贡献，不能把 legacy texture.write 直接标为 transactionEligible。

受控读写不承诺像素/GPU 可见或完整 L2；PNG 授权注册和 PSD 导入继续后续独立实现。测试覆盖绑定前后、同图像 no-op、几何/父级/候选/绑定/历史 stale、跨会话/TTL、Runtime unavailable/permission、返回身份不符/失败回滚/回读失败/丢失响应/重放、预算/闭合 schema。只做离线，不连接 Cubism。

实现结果：已接入生产 assets.binding.read/bind/result，目录 **49 工具、15 资源、2 模板、8 提示词**。read 复用 meshes.read native state 与原缓存；新 token 的字节预算包含保留 authoring 状态与依赖元数据，父 token 不延长 TTL。最多 1024 组/图像、240KiB 输出，拒绝重复 modelImageId 与超限；binding 同时覆盖 MODEL_IMAGE/TEXTURE_ATLAS_REGION/null。已有 textureInput/bindModelImage 的两条有限 SDK owner 账本行从 excluded 改为实际 READ/TRANSACTION_CONTROL，仍只跟踪 14 owner；registerImageAndBind 保持 excluded。manifest 说明补齐绑定读写目的，无新增权限。

新增 12 项 Domain 测试，含 model/image/mesh/generation 不匹配和事务前/内/post-commit 的故障边界，完全保留已有几何/候选；HTTP Bearer/只读结果核对和 ledger 约束同步。最终 MCP **557 项，0 失败/错误，1 headless UI 跳过，57 套件**；spotlessJavaCheck 与 JAR 通过，git diff --check 通过。未改 SDK/Runtime/ACP，未运行真实 Cubism/模型/保存重开，仍不能将身份绑定等同于实际像素采样或视觉合格。后续优先沿用该状态/事务契约实现授权 PNG 注册，再推进源拓扑/图集工作流；P0 实机验收与完整 L2/L3 仍待完成。

## 31. 授权 PNG 异步注册与绑定（离线实现完成，2026-10-25）

新增 assets.images.register/result/cancel，从同会话 READ grant（过滤器严格仅 png）读取有界完整内容，生成 AuthorizedImageContent 并调用原生 registerImageAndBind。先在 UI 对 binding snapshot 做完整 authoring/父级/锁/输入/候选/history 预检，再消耗 grant；读取与 header/hash 校验不占用 ACP 请求等待。原生提交前重新查快照 TTL/CAS，事务内核对新增 raw/group/model image/绑定及已有 metadata、几何/UV/forms 不变。预算 16MiB PNG、最多 16M pixels、8192 单维，显式 maxPngBytes/maxPixels、可选 expectedSha256。实际 native decode 与权限/Undo/extension 准入继续由 Runtime 判定。

一个全局 I/O/worker/host 槽，128 不淘汰 operationId；显式 cancel 只在原生事务准入前中止，之后保留实际提交/回读/rollback/unknown 结果。read stage 不取消，lease 直到实际读完成才关闭。会话清理/服务停止取消未准入工作并清理 late completion，不把断线称为宿主取消。结果只保留尺寸/hash/收据/绑定身份，不保留 bytes 或路径。补齐现有绑定 read 的 groupCount 元数据，以精确核验新增组；有限 SDK 账本 registerImageAndBind 从 excluded 改为 authorized-job，不混淆外部读 grant 与原生 Undo。离线测试完成后记录真实计数；不连接 Cubism。

实现结果：assets.images.register/result/cancel 已接入生产，共 **52 工具、15 资源、2 模板、8 提示词**，有限 SDK owner 仍 14。registerImageAndBind 账本改为 MCP_AUTHORIZED_JOB/EXTERNAL_SIDE_EFFECT，说明 grant 读取消耗不可 Undo、模型变更使用独立 SDK 事务；manifest 读写权限目的同步，不新增权限。绑定观察增加 groupCount；注册前/内/后复核新增 raw/group/model image 的准确身份、name/尺寸及既有 metadata/UV/geometry/forms 不变。header 仅为预检，实际 native decode 不冒充已实测。

新增 14 项注册 Domain 回归，涵盖 pending I/O lease、显式取消最终准入、会话关闭/停止在准入前后语义、坏 grant/内容/sha/预算、CAS/TTL、部分失败回滚/错误 SDK handle/响应丢失/回读失败、full 128 job/replay 等。目录超过 50 项后修正 HTTP 集成为实际 nextCursor 分页（首 50 + 后 2），未提高 page size。Pi stdio 模拟新增 52 项分页发现和第二页 result/cancel 调用，原 HTTP SSE/版本协商组继续通过。最终 MCP **571 项，0 失败/错误，1 headless UI 跳过，58 套件**；格式与打包、Node 桥接专项和 git diff --check 通过。未改 SDK/Runtime/ACP Java，无 Cubism 启动/连接/实际素材或模型改动。P1 API 基础可独立推进，不表示 Milestone B 或 A 的实机门槛已通过。后续仍须补源拓扑/参数结构/图集/导出及实机验收。

## 32. 完整矩形图集快照、规划与原生 Undo 任务

当前工作区新增 `turboism.atlas.read/plan/apply/result/cancel`，目录更新为 **57 工具 / 15 资源 / 2 模板 / 8 提示词**。ATLAS-02 的完整矩形子域已有离线实现；polygon、增量 append、GPU/绑定/实机 Undo 与保存重开验收仍待完成，不把本节视为 Milestone B 通过。

read 将 SDK 签发 opaque target 留在服务器，五分钟 TTL、最多16快照，绑定 MCP session、文档/模型/history 和全部原生 constraints/items/outlines/currentPlan。读取不改 UI/参数，不导出 target 或路径。每次 current 可能签发不同 target 包装，因此比较完整元数据而非包装 identity，apply 仍使用最初签发 target。只允许 complete rectangle target，拒绝临时 native invocation 和 polygon layout。

plan 为确定性的 height/width descending、ID tie-break shelf，不调用 setter/原生 apply、不占用 operationId，不宣称最优或通过写准入。返回闭合 applyRequest。apply 验证每个源图像恰好一次、原尺寸、无旋转/缩放、页名/独立页尺寸、边界/边距/间隔、页使用与像素预算；随后重新检查完整 CAS。最多512图像、32页、单边8192、总轮廓8192顶点、投影/请求240KiB、显式总页像素不超过67108864。轮廓参与状态比较但不用于打包。

apply 使用独立异步 worker，全局一个进行中任务，最多256保留 operation identities，不淘汰以免重放。result/replay 零宿主读取，冲突 ID 拒绝。cancel/dropSession/close 只在最终原生准入前阻止写入，已准入工作等实际结果；无 hostCancellationConfirmed 承诺。SDK atlas apply 自有原生图集 Undo，不能包进普通 AuthoringTransactionService，不提供 SDK transaction receipt/自动回滚，也不声明 transactionEligible。provider 失败/拒绝或响应丢失保守为 OUTCOME_UNKNOWN；原生成功而回读不一致为 APPLIED_WITH_READBACK_WARNING。布局哈希按 textureId 规范化 placement 顺序、扩展隐式 uniform pageSizes，适配 Runtime 实际投影；page 名称/尺寸/坐标仍精确比较。结果明确 textureBindingsVerified/pixelSamplingVerified/nativeUndoReceiptAvailable=false。

覆盖账本新增 TextureAtlasLayoutService（current/isAvailable/apply 三项 public instance 方法）为第15个有限 owner，增加 MCP_NATIVE_UNDO_JOB 分类约束；static unavailable sentinel 不在既有 instance method 覆盖域。新增只读规划 semantic row。未因此声称整个 CubismFacade、全部 atlas API 或所有宿主版本可用。三语目录/说明和既有 model read/write permission reason 同步，无新增权限。

离线 MCP **593 tests / 0 failures / 0 errors / 1 skipped / 60 suites**，格式检查与 JAR 构建通过；Pi 三组通过，覆盖57工具分页及第二页 atlas result/cancel。21项图集测试、1项 nullable enum 校验器回归新增；具体方法/日志见 [验证记录第32节](verification.md#32-完整矩形图集快照规划和原生-undo-任务2026-10-25)。仍未启动/连接 Cubism，没有实际图集/模型变更或 live catalog 证据。后续继续补拓扑/参数结构、polygon/append/export 与受控宿主验收。

补充 Runtime 对齐：原生 atlasId 由页名用控制分隔符拼接，会随合法改名/增页改变。只向 Agent 输出 atlasIdHash，写前仍比较完整原始身份；写后按请求精确比较页名/布局，允许预期身份变化。新增多页读回与改名回归，不能以旧 atlasId 不变作为成功条件。

## 33. 原生 MOC 包、Core 校验与授权有界原子写入

当前工作区新增 `turboism.export.moc.plan/execute/result/cancel`，目录为 **61 工具 / 15 资源 / 2 模板 / 8 提示词**。OUT-01 的原生 MOC5.3/PNG 基础导出与安全文件落盘已有离线实现；OUT-02 的完整角色资产、真实播放器及保存重开仍未验收。

plan 读取有界图集 id/name/order/dimensions/version/image-count 和完整 document/model/history binding，归一化显式 originX/originY/pixelsPerUnit 及5项原生 inclusion/edge 布尔值，检查 Core inspection 可用性。没有调用 exporter、检查 MOC bytes、消费 grant 或预留 operationId，writeAdmission=NOT_CHECKED。最多16个同会话计划，5分钟TTL，返回必须原样提交的 executeRequest。源核对范围明确为 HISTORY_AND_ATLAS_METADATA，不假装完整不可变几何快照。

execute 是单全局异步任务，128 operation 身份保留且不淘汰。重查 scope/history/图集元数据后调用 SDK 官方 exportMoc，要求返回 settings 一致、纹理数量/索引尺寸符合来源图集；实际 MOC bytes 交给获准 Core MocInspector，必须 V5_3/CONSISTENT，未知/不一致/不可用均不写文件。PNG 保留 top-left ARGB 与原生 MOC 纹理索引；生成固定路径 model.moc3、textures/texture_NN.png、仅相对 Moc/Textures 引用的静态 model.model3.json，以及 export-manifest.json。manifest 记录源状态/设置、逐 payload 大小与 SHA；自身由 archive SHA 覆盖，不作循环自哈希。JSON 键和 ZIP 时间固定，源纹理顺序保留。不接受任意包内路径/inline bytes，也不猜测 physics/motion/expression/pose/display/group 资产；fullCharacterDelivery/playerLoadVerified=false。

预算显式为 MOC64..67108864 bytes、ZIP1..134217728 bytes、总纹理1..33554432 pixels；每页沿用 SDK8192单边/16777216像素和32页限制。更紧的 MCP 预算在 SDK 返回后检查，不能阻止 SDK 既有原生分配；编码还会有临时像素/PNG/ZIP副本。最终写入前再次检查计划TTL/source/history，才消费同会话一次性 WRITE grant（严格 zip 过滤器）。writePolicy=REPLACE_AUTHORIZED_FILE 明确批准原子替换该用户选择的目标；不接受路径，不要求多文件写入的伪事务。

为此新增 SDK `UserFileAccessService.writeBytesAtomicBounded(handle,content,maxBytes)`：Runtime 显式最多128MiB、复制后异步使用既有授权与原子替换；原 writer 的8MiB限制不变。默认实现只在预算≤8MiB时转发，否则失败闭合，保持已有 provider 源兼容；旧 SDK 整体缺方法时 MCP 单独返回 BOUNDED_WRITE_API_UNAVAILABLE。SDK false 结果保证目标未变；异常/响应丢失为未知，不自动覆盖重试。

取消只阻止文件写入准入前的工作，不打断已开始 exporter 或 SDK file stage。pending write 持有 lease/全局槽到实际完成，cancel/session drop/close 都不提前释放；实际 written=true 后仍如实返回 WRITTEN。无 Undo/自动回滚、文件重读或播放器验收承诺。结果/replay 零宿主读取，只保留 metadata/hash，不保留 ZIP/native对象/像素。SDK有限账本仍15 owner，新增有界写入行和 export/Core/plan semantic rows；不扩张为整个 CubismModel/Core API 覆盖。

最终离线证据：SDK **467 tests，0失败/错误，1跳过，119 suites**；Runtime **6121 tests，0失败/错误，43跳过，696 suites**；MCP **610 tests，0失败/错误，1跳过，62 suites**。三模块格式检查、MCP/Runtime打包、ACP编译和 SDK current表面核对通过；Pi三组通过，61工具分页及第二页导出 result/execute 权限已验。当前开发API快照同步了累计 ordinary/opacity/support 与既有 ProgressDialog 的公开表面，并保留旧构造器；历史冻结基线未改，未声明新版本已发布。详见 [验证记录第33节](verification.md)。未启动/连接Cubism，未生成真实模型导出或播放器加载证据；拓扑、参数结构、完整角色资产与受控宿主验收仍需继续。


## 34. 参数混合顺序与按位置移动的 SDK 基础（2026-10-25）

PAR-02 下一切片先补真实顺序契约：新增 `ParameterGroupChild(Kind,id)`、`ParameterGroup.orderedChildren()` 和 `ParameterGroups.moveParameter(parameterId,targetGroupId,insertIndex)`。返回直接文件夹与参数的不可变混合列表；不从两个分离列表拼出伪顺序。索引按移除被移动参数后的目标混合列表计数，闭区间上界表示追加；同位置不产生 Undo，负值/超范围写前拒绝。原追加入口同时修正同组移动的索引，避免使用移除前 size。新 default 失败闭合，不丢弃 index 退化为 append。

Runtime 接通 Editor、PermissionChecked 与 Session 三层；读取沿用已验证 children/类型/ID 成员，未知子项明确失败，不静默遗漏。两类 ID 不是 GUID，仍须在未来 MCP 准入时拒绝重复显示 ID；本 API 不宣称解决此身份歧义。native ModelHandler 移除后插入的语义通过本地 5.3.02 参考 JAR 的只读 javap 核对，没有执行 Cubism。没有新反射入口或未经审查 selector；文件夹自身移动/排序仍未开放。

同时修复 Combined 伙伴在主参数之前时使用旧 sourceIndex 的错误：先移除伙伴，再按其对 source 位置的影响修正插入点。回归先失败再通过，核对实际顺序、双向 combinedWith 和 Undo/Redo。索引移动专项覆盖混合顺序、跨文件夹、旧 append、no-op/非法索引、不可变读结果、未知子项、权限撤销、会话失效；原生事务准入使用显式 group-undo 证据，检查无嵌套 edit、故障回滚与 Undo/Redo。

本节只完成 SDK/Runtime 基础与离线验证，生产 MCP 目录仍61工具，有限覆盖owner仍15；尚未新增 `parameters.structure.*`，不可把 PAR-02 标为已全部交付。后续 Domain 必须补完整结构快照/CAS、重复 ID 拒绝、创建后重新发现身份、明确级联删除影响、Combined 状态与混合顺序回读、原生事务准入和可核对结果；不把现有 legacy apply 直接标为 transactionEligible。全量测试与 API 快照结果见 [验证记录第34节](verification.md)。仍不启动/连接宿主或操作实际模型，A/B/C 和总体目标继续进行。


第34节最终全量通过：SDK469/0失败/1跳过，Runtime6127/0失败/43跳过，MCP610/0失败/1跳过；格式/打包/ACP编译/current API检查通过。API新增18行无删除；仅更新当前开发快照，保留历史冻结契约。具体错误数/suite计数与证据边界见验证记录。本轮新增2项SDK、6项Runtime测试；不借用这些测试宣称 MCP 参数结构或实机建模已完成。


## 35. 参数文件夹/排序/Combined 的 MCP 结构工作流（2026-10-25）

第34节 API 基础现接入生产：`turboism.parameters.structure.read/plan/apply/result`，目录 **65工具、15资源、2模板、8提示词**；有限 SDK 覆盖 owner 从15扩到17（加入 ParameterGroup、ParameterGroups），并将 Parameter Combined 读写从 excluded 改为实际路径。不会因这两个 owner 的加入而声称整个 SDK 已覆盖。

read 获取完整但有界的 actual mixed children、参数定义、可空 Combined 标记与双向伙伴，不读取当前值、binding 或 geometry、不改变pose/选择。先检查重复显示 ID、树/父引用、完整参数覆盖、索引与配对邻接；身份为唯一显示 ID + 完整已声明结构字段 + 六项文档/模型/历史绑定，**尚非 native parameter/folder GUID CAS**。上限128文件夹、512参数、192KiB；16个会话快照，5分钟TTL。

plan 纯模拟最多16个顺序编辑，支持根下 create_group、rename_group、显式完整子树 remove_group、mixed-index move_parameter、普通 NORMAL 同组 combine/uncombine。新建 ref 可供本批后续编辑引用，预览临时ID不冒充真实ID。删除必须显式给出含自身的前序 expectedGroupIds 和模型顺序 expectedParameterIds；调用者应审核其中参数/rig数据也会被删除。moving paired/splitting pair拒绝，先uncombine；不隐式改其他配对。未知 Combined 状态不能用于移动/配对；未知不推断为false。新建后立即删除自身的无效循环、文件夹自身移动/重排与任意Morph组合未开放。

apply 用 planId+operationId，在独立SDK authoring事务中重查来源和逐步预测状态。新建文件夹回读实际ID并映射后续 ref；每步完整结构回读，成功后另做最终回读；失败由原生事务恢复并复核结构，恢复不一致/错误文档返回未知。与geometry不同，本域不验证被删除binding/form内容的恢复，不能把结构一致扩张为完整rig恢复证据。旧SDK缺混合读取方法转UNAVAILABLE，缺索引writer在事务内转可回滚失败，不留永久RUNNING。actualAfterHash基于原生ID，expectedAfterHash基于临时ID；二者差异不代表变更失配。

result/同请求重放零宿主读取，保留256个operationId、不淘汰；ID冲突、跨会话、过期与缓存满明确拒绝。没有跨重启恢复，timeout先查询，不盲目生成新计划重放。桥接取消仅阻止准入，不能取消已进行的native事务。工具非transactionEligible，不嵌套transaction.execute、不改legacy三个apply标记。计划只做读取和模拟，writeAdmission=NOT_CHECKED；native exact selectors、权限和group-undo仍实时决定准入。

新增21个Domain测试，HTTP实际65工具分页/新工具名和unknown result信封验证，Pi65工具/68注册及第二页结构plan/apply权限测试通过。最终MCP全量 **631 tests、0失败/错误、1跳过、63 suites**；格式/打包通过。详见 [验证记录第35节](verification.md)。没有SDK/Runtime/ACP Java新增改动，不挪用此前计数作为本轮运行结果。未启动或连接Cubism，仍需验证实机参数结构/Undo及整个建模/渲染/保存重开闭环；PAR-02仍有文件夹自身重排与GUID身份等限制，完整半自动/全自动建模目标未完成。


## 36. 源几何/默认形分离的网格创建工作流（2026-10-25）

新增生产 `turboism.authoring.meshes.triangulate/create_plan/create/create_result`，目录 **69工具、15资源、2模板、8提示词**。有限覆盖账本加入 Drawables，现18 owner，8个公共实例方法逐一分类；仍非全SDK覆盖。复用已有SDK/Runtime，本轮没有新增SDK API或Runtime改动。

triangulate为不修改模型的native源平面剖分；输出可能合并/重排点，返回实际positions/indices，不推断UV映射。创建计划显式指定已有parentPartId、CANVAS源坐标/归一化UV/三角形、独立defaultPositions，可选现有modelImageId。null表示先创建未绑定网格，后接既有授权PNG注册绑定工作流；不声称创建即已可见。源/default点数和顺序一致；非空面，拒绝重复/退化/越界/非有限坐标。不是已有对象拓扑替换，也不绕过默认/共享form保护。

完整声明CAS范围为：全部已有ArtMesh原生source/stored快照与GUID/父级/lock/图像输入、Part的ID/name/parent/index/visible/locked、模型图像分组/name/memo/顺序/metadata、完整文档/模型/history绑定。Part自身没有native GUID CAS，不覆盖物理/动画等全模型字段。Runtime的Part.parentId对根部件返回empty，未猜测虚拟root ID；父级链缺失/循环/隐藏/锁定均失败。拒绝重复mesh ID/GUID。

上限1..8新网格，每网格4096点/24576索引、合计16384/65536、768KiB请求；512已有与新增网格总数、512部件、128图像组/1024图像、262144 source+stored点、4MiB编码source状态；快照native分配先于后段预算检查。16计划/5分钟TTL，最多约64MiB编码状态加原生DTO开销。旧reader/batch方法缺失明确失败，不反射fallback。

create以planId+operationId在独立SDK事务内先重查状态，再一次批量创建，可选逐个绑定已注册图像。事务内验证实际ID/GUID、唯一空轴cell/default form、source/default/UV/indices、父部件/无deformer、image binding和旧范围不变。回读返回实际defaultDrawOrder/nullable defaultOpacity，不推测渲染可见。事务回滚后复核声明状态；commit后回读失败保留warning，结果丢失或恢复无法核对保留unknown。createdMeshes每行均为历史观察；verified只表示该时刻回读通过，回滚后不能用作现存句柄。expectedAfterHash是请求payload hash。

result与相同请求重放零host读取，256操作身份不淘汰，不跨重启恢复。超时先核对原操作，不能换ID盲目创建。取消仅bridge admission，不等同native取消；不能嵌套transaction.execute，不改legacy apply的资格。

16项新增Domain测试通过；HTTP实际69目录（50+19）及missing mesh result错误信封，Pi69 mock工具/72注册及第二页创建批准回归通过。最终MCP **647 tests、0失败/错误、1跳过、64 suites**，格式/JAR通过，详情见[验证记录第36节](verification.md)。三语README与既有对象写权限说明同步。未启动/连接Cubism、打开/修改实际模型、部署JAR或产生实时目录成功证据。GEO-02/TEX-01工程路径扩展不代表原生可见网格、Undo/Redo、保存重开或完整自动建模验收完成；OPEN/new-document/grant导入、完整rig/资产与实机门槛仍待继续。


## 37. 文档目录与受控 NEW_MODEL 身份就绪（2026-10-25）

新增 `turboism.documents.read/new_model/new_result`，生产目录 **72工具、15资源、2模板、8提示词**。有限SDK owner仍18，账本增加组合语义行；未增加SDK API/Runtime写接口，没有新增exact-host验收声明。OPEN仍由Runtime明确fail closed，first-open行为未验证，本轮不解除限制、不用反射/chooser注入绕过。

read支持空项目/无文档：输出无路径的project/document/content ID、用户可见名称、类型、content→document关联、活动文档及可空authoring/history状态。目录两次核对；活动doc/model必须与project视图一致，重复doc/model/content ID拒绝。模型authoring/history不可用仍可读目录，但binding=null，不据此声称就绪。身份为声明metadata与活动binding，非native document GUID或全模型geometry CAS；资源/文件路径/像素/原生handle不输出，content投影不是完整素材清单。上限128文档/128contents/256关联/192KiB，16 session快照/5分钟TTL；native facade快照分配先于后段budget。

new_model需要snapshotId+operationId，两次核对metadata/binding与TTL并重新发现commands.available中NEW_MODEL；只分派既有typed命令一次。已满目录/contents/关联容量预拒绝；只有活动文档却没有project清单也不分派。native默认画布/UI适用，不接受任意命令、路径、name或canvas参数，不关闭/打开/保存文件。副作用独立于Undo/authoring事务，取消仅阻止bridge准入，不取消已分派native命令。

READY要求实际观察到恰好一个新增active MODEL、其model ID不与旧doc重用、authoring/history绑定可用、旧文档metadata/order和旧contents metadata保持。它仅证明authoring身份准入，不证明旧模型全部几何/画布默认值/像素/渲染/持久化。命令EXECUTED的no-op、IMAGE、新项目替换或无法确认的新target返回EXECUTED_NOT_READY/isError=true；重复身份/不一致/提交后读取失败、FAILED/异常返回unknown。UI仍可能需要人工动作；完成后documents.read重新发现，不能重复new_model作为readiness探测。

new_result和同请求重放零host读取，不自动轮询后来UI状态；128操作身份不淘汰/不跨重启恢复。QUEUED/RUNNING可直接查询，未知结果先核对不重建。权限理由、三语README和Pi mock目录同步；不改legacy apply事务资格。

新增19项Domain测试；HTTP实际72目录（50+22）及missing结果信封、Pi72 mock工具/75注册和第二页read无需批准/new_model需批准通过。最终MCP **666 tests、0失败/错误、1跳过、65 suites**，格式/打包通过；见[验证记录第37节](verification.md)。未启动/连接Cubism或任何实际模型、未部署JAR；DOC-01仍缺统一所有legacy写请求/目标tab激活，DOC-02画布配置与真实new-model/UI验收仍待完成。整个半自动/全自动建模目标、实机Undo/保存重开/视觉门槛保持未完成。


## 38. 参数原生 GUID 读取与结构计划身份强化（2026-10-25）

新增SDK `Parameter.guid()`，读取Editor-native authoring parameter GUID；与可修改的显示ID、名称、Core index不同，供ordinary snapshot轴GUID对照。默认实现明确unavailable，Core-only/未带身份的detached/第三方实现不生成替代GUID。GUID必须与document/model/generation、完整expected state一起使用，不能单独授权写入或复活stale handle。显示ID重命名不重定义source身份，但旧ID绑定句柄仍可能失效，应重新发现。

Runtime沿用现存verified `parameter-source.guid` 与 `guid.value` getter，无新增反射/路径/宿主命令。EditorParameter先核对同一authoring source与活动模型：求值对象重建可重新绑定同source，删除后同显示ID新source不能接管旧句柄。PermissionCheckedParameter每次检查model.read；SessionParameter每次检查session generation。没有参数setter、pose/选择变更或Undo。缺selector/空GUID拒绝，未扩大任何宿主写准入或宣称全版本实机通过。

MCP参数结构read每行加入 `guid`，计划/逐步预测/写前与事务内CAS/回滚结构核对均保留该GUID；同revision、同显示ID/定义/Combined字段但不同GUID的重建拒绝。重复GUID与旧SDK缺getter fail closed，无显示ID或index fallback。identityScope现为PARAMETER_GUIDS_GROUP_IDS_AND_HISTORY_BINDING。文件夹仍只有ID/字段/history，不冒充folder native GUID CAS；仍未检查删除binding/form完整内容恢复。结构工具数不变，生产72工具、15资源、2模板、8提示词；有限owner仍18，Parameter.guid账本归入真实read路径。

SDK current API本轮仅增加1个default getter、0删除；历史冻结契约和版本号未改变。三语README与SDK作者指南同步身份语义，不改legacy apply事务资格、长任务重试或默认/共享form保护。本轮是KEY-02/SAFE-01的身份基础，不是绑定初始化/rig复制算法。下一步仍须将显式parameter GUID/keys、目标完整原生状态、默认form保留、三类完整cells/forms回读组合为受控的普通绑定网格初始化，并独立检验事务恢复；不能只改legacy bind工具的事务标记。


本节验证：SDK **470 tests/0失败/0错误/1跳过/121 suites**；Runtime **6131/0/0/43跳过/696 suites**；MCP **668/0/0/1跳过/65 suites**。新增SDK1项、Runtime4项、MCP2项，并扩展既有Session metadata测试验证同model ID/GUID重连也不能复活旧generation。三模块格式、Runtime/MCP JAR、ACP Java兼容编译、SDK current API核对通过；详情见[验证记录第38节](verification.md)。未启动/连接Cubism、打开/修改实际模型或部署JAR，无实机目录/渲染/Undo/保存重开证据。Pi没有改动或重跑，不挪用其第37节结果作本轮验收。完整自动建模目标保持未完成。


## 39. 按数值创建普通参数绑定的 SDK 基础（2026-10-25）

新增default `ParameterBindingOperations.bindValues(target, List<Float>)`：客户端只提供关键值，不虚构原生point ID。Editor实现复制/排序、拒绝空值/非有限值/容差内重复，并与legacy bind共用既有exact-host准入、保护状态核对、native Undo和事务回滚；legacy bind不改变宿主写路径。PermissionCheckedModel沿用BIND_PARAMETER语义和model.write检查，SessionModel保留generation检查。无支持的第三方/Core实现明确unavailable，不生成替代ID、不调用UI命令或新增反射入口。

此API只是单轴绑定原语，不保证原零轴form GUID最终属于default key，不证明完整独立的多参数Cartesian网格或视觉正确性。现有native替身只足以证明轴更新与事务恢复，不能用它冒充完整cells/forms生成证据。完整初始化仍须独立核验parameter GUID/definition/value、目标source/default/stored状态、所有新cells/forms与默认内容保留，之后才加入受控MCP plan/apply/result。没有将legacy apply改为transactionEligible，也没有新增或宣传MCP网格初始化入口；目录仍72工具/15资源/2模板/8提示词，有限owner18。新方法账本明确EXCLUDED_WITH_REASON。

SDK current快照相对本轮开始只增加1个default方法、0删除，未改历史冻结契约/版本号。新增SDK1项fail-closed测试、Runtime9项（三类成功/Undo/Redo/abort、三类部分原生失败回滚、非法输入、权限与session）；源码/fixture中保留既有语义，不放宽生产准入。验证结果见[验证记录第39节](verification.md)。未启动/连接Cubism、操作实际模型或部署JAR；完整自动建模目标保持未完成。

本轮全量离线回归：SDK **471**、Runtime **6140**、MCP **668** 项，全部0失败/0错误，分别1/43/1项跳过；格式、API current、Runtime/MCP构建和ACP Java编译通过。详情见第39节验证记录。


## 40. 普通绑定完整网格的 MCP 规划与初始化（2026-10-25）

新增 `authoring.bindings.initialize_plan/initialize/initialize_result`，将第39节bindValues接入生产装配。一个目标ArtMesh/Warp/Rotation必须是零轴、一个cell/form且无受支持域外Morph；1..3条NORMAL、非repeat/Combined轴，实际值必须在default，每轴3..9个有限互异关键值含精确default与范围内两侧值。参数显示ID/GUID双身份、完整参数定义/值/顺序、source/stored原生DTO、父关系、source锁、default锁/editLevel及六字段历史绑定共同CAS；未知属性/重复参数身份拒绝。计划只读，不探测写准入或移动pose/选择。不能把静态reviewed版本元数据当成真实宿主可写证据，plan明确hostWriteAdmission=UNPROVEN。

initialize要求原样echo计划的target摘要（对象kind/ID/source GUID、参数显示ID/GUID、范围/default/keys），使权限窗口呈现实际范围；换摘要拒绝。一个独立SDK事务内按轴bindValues，每次先核对完整声明状态，写后验证实际axes/cells/forms：完整Cartesian基数、完整且互异tuple、每tuple独立form、无孤立或额外form；所有form内容与原始零轴form完全相同。ArtMesh保留positions/drawOrder/可空opacity/source UV与拓扑，Warp保留控制点/grid/quadTransform，Rotation保留完整angle/origin/scale/reflection与baseAngle。default按全tuple识别，不假设原form GUID属于default key。任何部分失败、共享形、缺角点、参数移动、内容变化或预算失败均在事务内抛错回滚；不解锁、不依赖选中对象、不改legacy apply事务资格。

最大27cells、4096顶点/控制点、32768存储采样、512参数与240KiB参数元数据；初始原生投影和元数据4MiB、16计划/总16MiB/5分钟TTL，源快照原TTL独立约束。每轴后也限制实际原生投影4MiB；native分配先于MCP回读预算。结果仅最终回读通过时返回实际form GUID/full GUID tuple与defaultCell标记；后续必须重新读authoring snapshot及binding资源，结果tuple不能直接充当CAS token。hash覆盖document/model/object/source身份和存储投影；父关系/参数另作CAS，不宣称全模型或像素哈希。

operationId最多128条已准入结果，无淘汰/重启恢复；同请求replay/initialize_result零宿主读取，冲突拒绝。未知/警告不能盲重试；取消仅桥接准入前有效，不取消已开始native事务。ROLLED_BACK后复核声明目标/参数状态，失败提升OUTCOME_UNKNOWN/ROLLBACK_READBACK_FAILED；rollbackStateVerified只描述这项有限核对。APPLIED_WITH_READBACK_WARNING不给未核验cells，renderVerified始终false。不是transactionEligible，不能用它嵌套transaction.execute。

新增17项Domain案例（含三类参数化案例），完整HTTP目录/写鉴权/result只读回退和有限账本对应核对通过。MCP全量 **685 tests/0失败/0错误/0跳过/66 suites**；格式、JAR、ACP Java兼容编译、SDK current API检查通过。Pi mock目录75工具/78注册，验证第二页新工具、计划/结果无需批准、写批准及目标摘要可见，另HTTP SSE/协议协商两组通过。三语README与SDK作者指南同步。目录现75工具/15资源/2模板/8提示词；有限owner仍18，本轮无新增SDK/Runtime API或版本改动。详情见[验证记录第40节](verification.md)。

未启动/连接Cubism、打开/修改实际模型或部署JAR。mock显式构造完整cells/forms只验证MCP准入与验收判定，不证明native真实生成完整组合网格；第39节native成员替身也不能补作此证据。仍须真实宿主逐版本/目标验证新轴/form生成、default内容、Undo/Redo/回滚、渲染和保存重开。普通网格初始化的离线路径是L2建模基础，不等于头部算法、美术质量、OPEN或完整模型交付通过；整体目标保持未完成。


## 41. 素材来源、图层关系与 Agent 建模参考（2026-10-25）

新增纯读生产工具 `turboism.assets.sources.read` 与 `turboism.assets.relations.read`，复用 `ModelTextures.sources(TextureSourceQuery)` / `relations()`；生产目录现 **77工具/15资源/2模板/8提示词**，有限owner仍18。账本将这两项从明确排除改为真实MCP_READ路径；没有SDK/Runtime/API或版本变化，没有新增exact-host实机验收声明。

sources仅按显式ArtMesh/model-image anchors查询（各64个唯一ID），空query只读binding；relations提供受限完整原始图像/图层树/模型图像/组/ArtMesh输入关系。currentRawImageId与linkedRawImageIds候选不混用；当前元数据缺失保留真实ID及未解析标记，不fallback。缺失anchor显式unresolved_anchor。rawLayerId在rawImageId内作用域，numeric PSD layer ID不能当作rawLayerId或按名称猜join。共享图像usingArtMeshIds可能包含非当前输入；空inputs/nullable currentInputIndex不能证明未绑定。UNKNOWN来源和transform/clipping availability保持原语义，不虚构实际矩阵/剪切值或组GUID；拒绝非法UTF-16等可能破坏身份的文本。工具不调用psdDocuments、文件、像素、导出或写接口，不解决左右/五官/遮挡语义。

两次UI-affinity typed读取须投影数据、native binding/generation相同，observation revision可增长不可倒退；同时核对六字段history绑定。native revision仅观察序号，不是Undo revision/CAS。分页使用同session/tool/query（集合语义digest）/snapshotId及当前history binding；缓存是历史不可变metadata，后续页不重读native graph，不证明当前新鲜或全模型原子性，更不是authoring写token。limit默认128、1..256，页240KiB，必要时缩小行数并给nextOffset。单行192KiB、每entry完整编码4MiB、16entry/总16MiB/不续期5分钟TTL；1024 raw images、2048 model images/meshes、256 groups、8192 layers/depth64、16384 listed links、32768 rows。native DTO分配先于MCP预算检查；初页成功后才保留cache。

ACP固定建模reference增加实际目录条件下的流程：来源与语义确认→三类快照→必要时普通绑定初始化→新快照→受限rig计划/preview/write→数值及图像检查→恢复/结果核对→文件授权与交付阶段。沿用用户已授权范围，不重复询问；未知结果不重放、非视觉模型明确限制、基础API不冒充专业成品。没有变更harness协议或借用Paseo实现声明兼容性。

新增Domain14项与HTTP只读鉴权1项；MCP全量 **700 tests/0失败/0错误/0跳过/67 suites**，格式、JAR、ACP Java兼容编译与SDK current API检查通过。Pi **77 mock tools/80 registrations** 的目录、第二页读取无需批准及既有图像/资源/取消/协议三组通过；ACP Controller **62 tests/0失败/0错误/0跳过** 验证增加reference后的提示词预算/边界回归。三语README同步。详见[验证记录第41节](verification.md)。

未启动/连接Cubism、打开/修改实际模型或部署JAR；metadata fixtures不是native真实图层验收，不证明GPU、Undo/Redo、保存重开、PSD导入或完整建模算法。Milestone A实机门槛尚未通过；本节为ASSET-01/02与ACP-06的离线工程切片，整体半自动/全自动建模目标保持未完成。


## 42. 三类普通关键形的相邻区间斜率诊断（2026-10-25）

新增生产只读工具 `turboism.validation.keyform_continuity`，复用实际ArtMesh/Warp/Rotation authoring快照，无SDK/Runtime/API/版本改变。生产目录 **78工具/15资源/2模板/8提示词**，有限owner仍18；不扩充账本owner或伪称全SDK覆盖。

完整Cartesian普通网格按parameter GUID、实际排序key和完整tuple定位，1..3轴/每轴3..9 key，最大27cells/forms、4096点/form、32768存储采样。缺失/重复tuple、孤立form、超预算或不支持网格失败，不给空findings通过。读取允许default/shared普通form，ArtMesh含Morph拒绝；Warp/Rotation authoring快照不描述Morph组合。已有initializer的纯grid投影被复用，但不要求每tuple独立form，不将initializer写限制错误套到读取。

沿每条轴线保持其他坐标固定，全部相邻三点比较左右secant：delta stored value / (delta key / full axis range)，非均匀key使用真实间距。Mesh顶点/Warp控制点/Rotation轴心比较向量斜率差范数，Rotation原始angle度数不作最短路径折返，scale比较原标量，X/Y反射变化始终警告。显式thresholds上限1e12、下限0，不提供美术默认值；有意曲率和反射也可警告。报告含真实轴范围/keys、form GUID三元组、完整中心tuple、axisValues、pointIndex或Rotation字段、observed/threshold和全部比较统计；非适用旋转最大值null，不冒充测得零。

可选proposedEdits使用三种闭合schema与既有ordinary writer preflight，完整GUID坐标、非default/shared等保护保持，不提交。前后核对完整native快照、对象source/ID、父关系和六字段history；缓存会话/TTL边界保持，不移动pose/选择，不渲染或写入。拒绝会破坏身份的文本；请求768KiB、结果240KiB、最多200保留findings，统计继续完整计算，findingsTruncated明确。native快照分配先于算法预算。analysisComplete仅表示这些存储secant检查全部完成，不证明native插值C0/C1、关键点间不翻折、混合导数、drawOrder/opacity、父变形或美术质量。

新增纯算法10项及Domain7项，覆盖非均匀key匀速/真实间距、三轴完整线/角点定位与重复显示ID、共享形读取、raw angle/反射/轴心/scale、截断仍完整计数、缺失/重复/孤立grid、Morph/预算、float极值；Domain三类型、拟议修改、默认/共享保护、前后native/父/history CAS、过期/跨session/权限与错误脱敏、严格schema、输出预算。HTTP增加只读无Bearer回归与78目录断言。MCP全量 **718 tests/0失败/0错误/0跳过/69 suites**、格式/JAR/ACP Java编译/SDK current API核对通过。Pi **78 mock tools/81 registrations** 三组通过，ACP Controller **62/0/0/0**；建模参考与三语README同步。见[验证记录第42节](verification.md)。

本轮推进QA-01/ACP-06的读取→规划→诊断→修正路径；不替代native interpolation/视觉QA。未启动/连接Cubism、操作模型或部署JAR，没有新增live目录、GPU/Undo/Redo/保存重开证据；Milestone A及整体半自动/全自动建模目标保持未完成。


## 43. 已有 rig 几何的显式跨对象迁移规划（2026-10-25）

生产新增 `turboism.rig.transfer_geometry.plan`，三类同kind、不同对象/源身份的现有ordinary存储快照生成target-only typed writeRequest。目录 **79工具/15资源/2模板/8提示词**，有限owner18不变；没有SDK/Runtime/API/版本、反射或宿主写准入变化。

必须显式映射双方全部parameter GUID和每个实际key，一一完整、default精确对应；支持反向key映射，但不推断左右/显示ID/nearest key，不创建绑定或form。双方必须已有完整1..3轴Cartesian网格，每轴2..65 keys、最多65 cells/forms，无孤立form；ArtMesh含Morph拒绝，Warp/Rotation快照不描述Morph组合。源共享ordinary form可读，目标default/shared保护复用既有ordinary writer preflight。sourcePointIndices为完整targetIndex→sourceIndex排列，同点数；targetDefault+显式可逆2x2 matrix*(sourceForm-sourceDefault)迁移Mesh/Warp位移，保留目标源/拓扑/UV/绑定/default/drawOrder/opacity。矩阵系数±1e6，不自动证明父坐标或拓扑语义对应。

Rotation采用显式angleGain（±1000）及RELATIVE_XOR：targetDefault angle+gain×原始source angle差、matrix×轴心差、targetDefault scale×sourceForm/sourceDefault scale、三方reflection XOR。保留target baseAngle/default，分量迁移不冒充旋转/矩阵复合，不从matrix推断角度gain。所有mapped nondefault目标值按策略替换，不能称无损合并已有rig；没有drawOrder/opacity/绑定复制。

规划前后各自核对完整native snapshot/source/对象ID/父关系/history，输出state/default身份。sourceReadScope=VERIFIED_AT_PLAN_ONLY：源只在计划阶段核对，后续生成值是历史明确计划；普通写请求只CAS目标，不持续核对源。独立source/target快照原session/TTL保持，计划不预留operation ID、不消费快照、不接触事务或任何setter/pose/选择/render。共享完整snapshot核对器从第42节提取复用，旧continuity回归通过。grid view增加显式内部预算参数；旧initializer与continuity默认仍27cells/32768采样，没有扩大既有接口上限。

最多4096点/form、双方合计262144存储采样、4096计划采样（Rotation每form/轴心计1），请求768KiB/结果240KiB；native分配先于MCP预算，非有限/原生float溢出、奇异变换、错误tuple/索引均拒绝。返回请求仍需普通preview/实时写准入/授权与几何及渲染验收，不能称完整rig复制或完整自动建模。

新增纯算法10项及Domain7项、HTTP只读1项；全量MCP **736 tests/0失败/0错误/0跳过/71 suites**，格式/JAR/ACP Java编译/SDK current核对通过；Pi **79 mock工具/82注册**三组通过；ACP Controller回归和完整记录见[验证记录第43节](verification.md)。三语README与固定Agent参考同步。未启动/连接Cubism、修改实际模型或部署JAR；RIG-02几何复用子路径推进，不代表原生Undo/Redo、GPU、保存重开、绑定复制或真实美术结果已验收。整体目标保持未完成。


## 44. 进入Turboism受管实机验收

2026-10-25用户允许实机。已完成5.3.03色块/Glue测试副本的生产目录、既有可逆矩阵与native renderPixels typed PNG实测，修复Render/Stored Domain把workspace opaque model ID与native source GUID错误等同的问题。MCP737项全量回归通过；具体任务、成功/失败记录及原生图片见[验证记录第44节](verification.md)。实机不绕过host_validation.py队列，原素材/安装hash不变，两个成功任务正常退出且安全清理。

首个Warp读取返回INVALID_ARGUMENT的真实原因是版本准入与生命周期代理把Warp/Rotation擦除为Deformer。已修复两层代理的闭合子类型保留，Runtime全量6142项（4跳过）与MCP737项通过；复测三类stored快照、不可变分页、渲染及恢复矩阵全部成功，宿主正常退出且安全清理。所选三对象仅有默认form，严格保持普通writer拒绝默认形。尚不声明几何写入/保存重开或Milestone A通过；后续优先选择已有独立非默认form完成三类ordinary写入闭环、官方Hiyori副本与ACP视觉通路，避免把已有纹理保存重开挪作几何持久化证据。


## 45. 三类ordinary写入实机原语通过

第44节读取之后，已用生产typed initializer临时准备Rotation普通轴，再由单个keyforms.write事务修改三类独立非默认form，真实MCP全量回读、一个native Undo/Redo/最终Undo与准备步骤撤销全部通过。原文件/安装hash不变，队列安全成功；细节、两轮未通过的探针问题、float32位值精确核对与本轮23项客户端测试见[验证记录第45节](verification.md)。没有直接写默认形、绕过保护或发布完整自动建模声明。

下一优先：三对象目标pose写前/后原生render与参数恢复，故障注入真正回滚，geometry/binding磁盘保存重开，官方Hiyori与ACP端到端图像。随后把rig算法计划→preview→write→QA→repair接入可恢复ACP任务流程。仍保留L2素材构建与P2交付能力清单，未将目标缩减为几何原语测试。


## 46. 三类写入的目标pose原生图像证据

5.3.03受管实机队列已通过三类ordinary写入前后512×512 pose扫描，全部PNG长度/哈希/typed image与document/model/generation/参数关联核对通过；三个pose均有真实像素差异。扫描始终恢复参数，独立Undo条目按receipt守卫撤销，再完成几何事务的Undo/Redo和临时绑定最终恢复。客户端25项测试与打包通过，无本轮生产Java/SDK/Runtime修改。详见[验证记录第46节](verification.md)及[图像对比](live-host-20261025/ordinary-pose-comparison.png)。

色块素材三原语的写入→读回→目标pose渲染→Undo/Redo→恢复链已取得证据；这不是官方Hiyori头部美术验收，也不证明ACP模型与窗口消费图片。下一步仍为宿主注入异常后的几何回滚、几何/绑定保存重开、官方模型和ACP图像端到端，再进入可恢复建模任务编排与质量修正。Milestone A与整体持续目标未完成。


## 47. 三类几何真实宿主异常回滚通过

仅在验证包增加typed SDK故障探针，生产MCP没有增加故障开关或任意代码入口。以实际发现的三类object/source/form身份，在一个SDK事务中分别确认完成第1/2/3个setter后抛异常；三次原生ROLLED_BACK、完整stored快照恢复、历史不变及外部MCP回读均实机通过。随后原有普通写入/pose图像/Undo/Redo/最终恢复矩阵继续通过，队列安全成功。4项Java探针测试、26项客户端测试和打包通过；过程中的两个观察器域假定错误保留为失败证据。详见[验证记录第47节](verification.md)和[原生回滚证据](live-host-20261025/geometry-rollback-native.json)。

本切片验证实际SDK事务与三类setter，不把它扩大为MCP响应丢失、宿主崩溃、取消或所有版本回滚验收。下一项优先几何/绑定保存重开；官方Hiyori头部、ACP视觉模型/Swing展示和可恢复建模编排仍欠，Milestone A与持续目标保持未完成。


## 48. 三类几何与绑定原生保存重开通过

第47节之后，在5.3.03色块副本完成三类MCP普通写入、完整回读、目标pose原生渲染、真实故障回滚和Undo/Redo，再恢复修改状态保存到独立CMO3，关闭并重新反序列化新文档，三类完整authoring records及全部SDK可见参数绑定精确一致。随后重开原任务副本，外部MCP三对象完整rows恢复原始基线；队列安全成功、原素材/安装hash不变。验证客户端明确不在重开后使用旧文档/history收据，未知文件结果停止写入。详见[验证记录第48节](verification.md)、[native持久化报告](live-host-20261025/geometry-persistence-native.properties)。

本轮复用测试专用exact-hash native文件工作流，没有新增生产MCP/SDK/Runtime接口；不能挪作生产grant/save_as/open端到端验收。下一步仍须补生产受控文件打开/重开闭环、官方Hiyori头部模型与ACP视觉模型/Swing实际图片链路，再把rig计划→写入→质量修正接入可恢复任务。L2素材创作、P2交付清单仍保留，Milestone A和持续目标尚未完成。


## 49. 受控 CMO3 OPEN 生产入口

新增授权CMO3 OPEN，精确5.3.03准入、同会话READ grant、不可变文档目录快照、双重状态/TTL检查与不确定结果查询；生产目录80工具。标准实机已验证真实chooser授权→一次消费→native dispatch→file_result→相同请求不重放，已有任务标签页前后完整状态一致。首次打开与生产save_as/关闭/重开内容一致性仍须独立验收，不能用本轮focus成功替代。版本manifest修正后的最终回归见[验证记录第49节](verification.md)。

下一项仍为首次加载和生产持久化闭环，再补官方Hiyori头部、ACP视觉模型与Swing图片实际消费及可恢复任务编排；Milestone A和持续目标未完成。

最终按版本manifest修复后的构建已通过第二轮标准实机复测（任务56c5b734-f3a6-4f07-907a-191b08933323），清理安全、原素材/安装不变；Runtime无桌面套件6145项中6102通过/43条件跳过，MCP741通过。Wayland/Xvfb窗口截图专项曾超时，保留失败边界；不宣称全部桌面测试通过。见[最终生产OPEN证据](live-host-20261025/authorized-open-final.json)。


## 50. 无活动文档后的生产授权加载

新增验证包专用关闭/读取探针与生产MCP客户端验收：精确任务文档关闭→公开空目录→真实READ授权→生产OPEN→目录就绪→三类完整rows/SDK records及全部bindings核对。首轮实机定位OPEN将hostObserved=false误当命令服务不可用；已修复，保留exact版本/命令服务/权限/快照CAS与授权边界。失败及后续复测结果见[验证记录第50节](verification.md)。不把关闭探针开放为生产API，不把dispatch返回当已加载或磁盘持久化保证。

修复后标准实机任务20fdcfdc-bcd0-41be-b4d1-4b2339987d68已通过：空目录→生产READ grant/OPEN→新native文档→三类全量rows/SDK records及全部绑定一致，fixture/安装不变、正常退出和安全清理。完整MCP742项通过，探针7项、客户端30项与collector14项通过。见[首次加载证据](live-host-20261025/fresh-open-verified.json)。下一项是生产SAVE_AS新目标与修改后几何/绑定持久化链，Milestone A及完整目标仍未完成。


## 51. 生产 SAVE_AS→OPEN 修改持久化通过

标准实机任务`a9402ce6-82ae-4a91-b192-942853301514`验证真实WRITE grant→生产SAVE_AS新CMO3→关闭精确任务文档→READ grant→生产OPEN输出→恢复原fixture。三类普通form均实际修改，Rotation绑定实际新增；重开后全部MCP rows、完整SDK authoring records及全部SDK可见bindings等值，三个pose PNG逐张hash一致。最后原始records/bindings恢复、原件和保存输出hash保持，队列正常退出/身份核对/安全清理通过。测试probe仅核对与关闭固定任务文档，不代替生产save/open。

[验证记录第51节](verification.md)与[完整持久化证据](live-host-20261026/production-save-open-verified.json)记录范围和9张PNG。离线Java探针9项、Python客户端33项通过；本轮未重跑完整MCP/Runtime套件。目录保持80/15/2/8。A10/A11获得5.3.03色块副本的生产持久化链证据，整体门槛仍未勾选：官方Hiyori许可副本与实际头部美术、ACP vision/Swing消费、可恢复任务编排及L2/P2仍待继续，不把文件原语通过当完整自动建模交付。


## 52. 原生图像到实际 ACP 窗口通过

标准acp:5303队列在脚本ACP-v1代理下验证生产render.capture→stdio bridge→typed ACP事件→生产AcpClient/Controller/ChatWindow→真实Swing图标。256×256全部ARGB像素、完整稳定工具键、capture/document/model/revision/尺寸标签及正文无base64通过；会话隔离、rename/Undo/SDK原名恢复、原件hash、宿主正常退出及安全清理通过。首轮探针依赖过期、测试误用原始tool ID、窗口焦点导致关闭拒绝的失败均保留，随后修复测试依赖/预期与关闭前精确焦点。没有放宽像素或对话框身份断言。

见[第52节验证记录](verification.md)、[实际ACP窗口](live-host-20261026/acp-agent-window.png)、[宿主结果](live-host-20261026/acp-native-render-verified.properties)。离线脚本正常/三类损坏拒绝、Swing matcher7项、打包依赖4项与关闭边界31项通过。A8窗口通路取得此窄范围实机证据；真实视觉模型消费与各native/ACP-v1 harness仍单独验收，整体门槛不勾选。

## 53. 真实 Pi/Codex/Claude native 隔离握手通过

本机Pi1.2.0、Codex0.159.2、Claude2.1.284经生产NativeAgentTransport、临时配置目录实际initialize/session-new：3项通过、无跳过。没有凭据或模型prompt、无Cubism连接；不代表模型看图、工具调用、恢复、权限或取消已通过。见[范围和原始JUnit证据](live-host-20261026/native-real-cli-smoke.json)。下一步是将真实视觉模型消费与受控宿主图像/建模任务接通，并继续官方Hiyori等专项；完整目标保持未完成。


## 54. 官方 Hiyori 三类只读快照及原生渲染通过

从Live2D官方来源取得PRO t11，在标准`mcp:5303@inspect`中经既有worker验证三类完整不可变分页、原生render.capture及history/pose不变。终态成功、正常退出、原件/黄金环境hash与身份核对通过，cleanup=safe。新增inspect仅允许闭合只读操作，客户端在transport前拒绝写工具，宿主停用全部可选写探针，队列拒绝混入写入工作量；未扩大生产宿主准入。

离线客户端36项、静态准入34项、隔离假队列122项、runner脚本与标准打包通过；本轮未改生产主源码或重跑全量MCP/Runtime。见[第54节验证记录](verification.md)、[官方来源及许可](fixtures/hiyori-pro-t11-source.json)、[独立Hiyori任务索引](live-host-20261026/hiyori-run.json)。归档仅metadata/hash，不分发原始素材/完整几何/PNG。候选对象尚无头部语义确认，256×256全身图不证明眼嘴质量；写入、Undo/Redo、保存重开及ACP真实视觉模型消费仍需独立验证，A保持未完成。


54节后续只读库存实机也通过：272层级节点、70参数/70绑定组的重复读取与state/history不变，正常退出/原件和golden保护/安全清理通过。见[库存任务与证据hash](live-host-20261026/hiyori-inventory-run.json)。核对发现先前Warp1/Rotation2分别绑定身体Z/X，不能作为头XYZ验收；下一步定向读取Warp13/Rotation1及轮廓候选的实际完整forms，再确认保持角色设计的rig编辑计划。候选仅来自层级/绑定关联，非已批准编辑目标，A仍不勾选。


## 55. 官方头部候选完整 forms 的只读实机通过

标准inspect任务实际读取Rotation1（头Z，3forms/2eligible）、Warp13和轮廓ArtMesh51（头XY，各9forms/8eligible），核对名称/kind唯一、父级、普通绑定、native GUID tuple、全部cell/form/eligibility，以及前后state和重复库存一致。队列成功、正常退出、原件和golden保护及cleanup=safe。见[第55节验证记录](verification.md)与[候选摘要](live-host-20261026/hiyori-head-candidates.json)。客户端41项通过，标准打包通过；未改变生产工具目录或扩大写能力。

候选仅是Hiyori专用测试profile，不是通用语义分类或已批准写入；hostWriteAdmission仍UNPROVEN。下一步在新任务中重新发现全部身份，按实际父空间与form尺度制定小幅rig测试，先预览/CAS再真实写入，核对非目标不变、Undo/Redo、异常回滚和图像；不能复用色块固定4.0位移。保存重开与真实ACP视觉模型消费仍独立验收，整体A门槛不勾选。


## 56. 官方 Hiyori 真实头部三类联合编辑通过

新增exact5.3.03的标准head-edit窄测试通路，按实际局部尺度对头X=+30/Y=0的ArtMesh51/Warp13与头Z=+30的Rotation1做小幅普通form修改。生产preview/write/CAS与精确回读通过，source/UV/indices及三目标的非目标form/其余rows保持；一个Undo entry、Undo/Redo/最终Undo精确恢复。实际确认1/2/3个setter后注入异常的native回滚及完整历史恢复均通过。两种不同pose的三张前后PNG均改变，已实际查看，未作美术或细节通过声明。

标准任务`0b91006c-fd63-452e-af78-9d6cfc6073ae`成功，正常退出/官方原件hash/golden/身份与安全清理通过。见[第56节验证记录](verification.md)、[source/form hash与收据](live-host-20261026/hiyori-head-edit.json)、[回滚证据](live-host-20261026/hiyori-head-edit-rollback.json)。客户端43项、隔离假队列123项、native回滚Java5项、runner与标准打包通过；未改生产主源码，工具目录保持80/15/2/8。

本轮没有保存/重开、XY九宫格/眨眼口型组合或真实视觉模型消费。head-edit禁止文件/结构/纹理/绑定操作，官方素材和全部几何/PNG仅留私有任务目录。下一步将这组实际头部目标纳入生产grant→SAVE_AS→OPEN核对，并继续ACP真实工具编排；A整体仍未完成。


## 57. 官方 Hiyori 生产 SAVE_AS→OPEN 持久化通过

在exact5.3.03标准head-save任务中，按第56节小幅计划实际修改三类头部forms，生产WRITE grant/SAVE_AS新CMO3、READ grant/OPEN后，三目标全部MCP rows、完整native authoring records和全部SDK可见绑定等值；三张pose PNG逐张hash一致。保留原有绑定，不新增参数/绑定。最终生产OPEN恢复原fixture、原始records/绑定和文件hash一致，官方原件不变，正常退出与安全清理通过。

见[第57节验证记录](verification.md)、[完整文件核对摘要](live-host-20261026/hiyori-head-save-open.json)、[队列与私有图像hash索引](live-host-20261026/hiyori-head-save-run.json)。客户端45项、隔离假队列124项、native OPEN Java6项、静态34项、runner和标准打包通过；未改生产主源码或扩大版本/权限范围。

这是同JVM新native文档、外部受控测试客户端的文件验收；chooser批准及关闭由测试助手完成，不伪称ACP Agent自主授权/保存。完整几何/CMO3/PNG保持私有。跨JVM、头部全组合QA、真实视觉模型消费及ACP建模编排仍需验收，A整体不勾选。下一步转向受控XY/XYZ/眨眼口型组合证据和真实ACP工具执行链。


## 58. 官方头部28pose修改前后扫描与有限视觉观察通过

新增标准exact5.3.03 head-qa测试通路，9个XY、8个XYZ角点、中心及两XYZ角点的眼嘴组合、左右眨眼共28不同pose，修改前后逐一原生1024渲染/512×384头部ROI、恢复参数、按收据清理扫描历史并release，共56job，不提高像素/图片/任务预算。三类实际小幅write/完整回读、Undo/Redo/最终Undo及native故障回滚均通过。8个预期pose改变，20个不受本次端点form影响的pose PNG字节不变。

首轮因标准wrapper已有600 requests/minute预算收到429，最终Undo未验证，保留FAIL与cleanup=safe；仅增加QA客户端请求前0.2秒节流，不扩大预算或重放写。新标准任务`43949ed4-ce6b-4e87-93ee-97bfb2807c7d`完整通过、正常退出/原件/golden/身份与安全清理通过。见[第58节验证记录](verification.md)、[扫描与离散差异证据](live-host-20261026/hiyori-head-qa.json)、[有限视觉观察](live-host-20261026/hiyori-head-qa-visual-review.json)。客户端48项、隔离假队列125项、native Java5项、静态34项、runner/标准打包通过；未改生产主源码。

28帧后图及8组改变pose前后已实际查看，闭眼/开嘴/左右眨眼可见，未见明显大片泄漏/穿帮；artQualityAccepted=false，不替代亚像素、所有组合/中间连续性、参考美术与专业成品验收。素材/几何/PNG保持私有。本轮尚非ACP模型消费；下一项优先真实ACP harness工具与图像建模闭环，A整体仍未完成。

## 59. 真实原生 Agent 观察通路

新增exact5.3.03标准native-observe任务，使用生产AcpClient/NativeAgentTransport/Pi provider及生产MCP扩展，固定Java/Node/Pi运行时。模型仅能发现两个资源和调用原生render.state/capture，内置文件/shell工具禁用；没有宿主写接口。分别核对原生PNG、typed ACP图片回调、模型描述与历史未变，不以图片到达代替视觉验收。见[探针契约](../acp-native-host-probe/README.md)与[第59节验证记录](verification.md)。

首轮默认文本模型、第二轮运行时声明可用的glm-4.6v均在模型工具调用前遇到同供应商HTTP429，两个标准任务已失败并安全清理；完整保留失败证据，未谎报视觉或最终runner文件核对通过。已补运行时视觉准入与测试清单的显式供应商排除，以新任务验证其他实际可用视觉模型。真实观察成功后再开放受控geometry preview/write，仍需真实模型读取form、提出计划、审批、写入、回读/视觉/Undo/保存等完整闭环，A整体不勾选。

第三轮排除429供应商，使用另一实际配置供应商的视觉模型，得到401凭据过期，仍0模型工具调用/图片；标准任务已失败且cleanup=safe，保留独立证据。已请求在原CLI恢复服务或提供可用视觉模型ID，不索取凭据，不重复打失败服务。等待期间，生产Pi桥接补充非视觉/未知能力图片提示，保留typed图片及原宿主结果语义；模型切换、资源图片、错误保留的Node专项通过，尚无新提示实机验收。真实观察及后续ACP几何编排继续未完成。


## 60. Codex 原生协议身份隔离与取消回归

H1/H3/H4新增离线实现：按本次turn/start ACK绑定权威turn ID，丢弃旧/无身份输出，拒绝其他thread/turn审批；ACK前缓存有界，提前取消等待准确ID，重复取消只发一次interrupt。迟到ALLOW和interrupt失败不影响新turn；缺ID/超限/当前请求失败要求重连和宿主结果核对，不重放。

三项原实现失败已复现并修复，新增事件序列与失败闭合回归、ACP全量/格式/JAR验证见[第60节](verification.md#60-codex-原生-turn-身份取消与审批隔离)及[离线摘要](codex-turn-isolation-20261026.json)。只支持已核对的Codex0.159.2带ID响应契约；未增加真实模型/宿主/窗口验收。H1/H3/H4其他provider、恢复和adapter仍待逐项验证；不因本项勾选A整体。


## 61. Pi/Claude 本轮回调归属与后续实机入口

修复旧审批ALLOW、Pi handled ACK和取消失败回调跨轮生效；按捕获future核对归属，空闲/重复cancel不发命令，当前请求结果不确定则要求重连和宿主结果核对。三项原实现失败已复现；ACP全量239项（236通过、3跳过）、本机三种真实CLI隔离握手3项、格式/JAR通过。见[第61节](verification.md#61-piclaude-本轮回调隔离与三种真实-cli-握手)及[摘要](native-callback-isolation-20261026.json)。

未完成：Pi/Claude无ID旧事件的原生归属、审批窗口/槽位在终态主动清理、真实模型审批/取消/恢复、其他native harness标准只读观察入口。下一步不能继续重复失败模型服务；其他harness入口必须先验证内置工具限制和精确视觉准入，再通过标准队列验证真实图像/资源消费，不把CLI握手当模型编排通过。


## 62. 本地审批窗口与槽位主动回收

新增session/prompt审批scope与本地lifetime signal，取消/终态/恢复/关闭立即终结待决审批、移除排队UI并关闭对应Swing窗口；native槽位按原future回收且只拒绝一次。保留9/8/64待决审批/native槽位/session预算，不把本地取消承诺为宿主取消或回滚。

两例原接线失败后修复；Xvfb全量248项（245通过、3 opt-in跳过），包括实际审批dialog打开/销毁、9个旧任务清队列与12轮无客户端答复的槽位复用。细节和源码身份见[第62节](verification.md#62-审批生命周期native槽位与真实swing窗口回收)、[摘要](permission-lifetime-20261026.json)。本地审批回收已补齐；真实模型往返、无ID旧事件归属及标准ACP建模闭环继续待验收，不扩大为所有harness恢复/取消通过。


2026-10-26追加：测试专用 Claude2.1.284 标准观察入口已构建，固定运行时、限制内置工具，并在宿主资源/渲染前要求随机图片 canary 与工具集合验证。受限真实 CLI 握手通过但未发 prompt；首轮启动参数失败已保留并修复。修复后任务已 prepare，提交因向 Claude 外部服务发送私有测试图像/元数据的授权范围被自动审批拒绝，尚未入队，不重放或绕过。见[第63节](verification.md#63-claude-标准观察入口与提交授权边界)与[摘要](claude-observer-preflight-20261026.json)。真实视觉消费和 ACP 建模仍未完成；Paseo参考及生产API范围未因此改变。


## 64. A4 同会话写响应丢失的真实核对

标准 head-edit 测试新增一次客户端HTTP响应接收后、RPC解码前丢失故障。Hiyori任务三类写入后，仅以原operationId查询两次生产result，稳定收据后精确回读、单步Undo/Redo及最终恢复；标准队列成功、原件与golden不变。182项隔离回归通过。见[第64节](verification.md#64-真实关键形写响应丢失与同会话结果核对)、[证据](live-host-20261026/head-response-loss.json)。未测跨会话恢复、宿主崩溃/取消或真实Agent处理未知结果，A4/A整体不据此全部勾选。外部Claude观察授权仍待回复，不重试被拒绝任务。


2026-10-26追加：AcpClient原先忽略rawOutput-only工具结果，三例失败已复现并修复。空/缺失content时复用typed转换，非空content优先，不混入raw图片或改变Agent工具状态；4新回归、Xvfb ACP252项（249通过/3 opt-in跳过）、代理坏图拒绝测试通过。标准acp:5303任务验证RAW_OUTPUT_ONLY原生PNG到真实Swing全ARGB/来源标签/无base64，队列正常退出、原件/身份/golden/清理通过。见[第65节](verification.md#65-acp-rawoutput-结果回退与真实窗口验证)、[摘要](acp-raw-output-20261026.json)。仅补结果接收/显示，不冒称外部模型视觉、持久任务恢复或ACP建模编排完成。Paseo0.11.2新增acp-agent.js参考hash已登记，未引入运行时依赖。

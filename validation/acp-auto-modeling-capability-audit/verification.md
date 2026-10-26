# 实施验证记录

日期：2026-10-24。工作树：`mighty-seal`；修改前 HEAD：`63993968018d88e8158303dd6f363bd4411f2721`。本页记录后续实现验证，原审计记录仍以 [README](README.md) 的历史基线为准。

## ACP 通路与原生渲染切片

已实现：

- Pi 按 MCP capability 提供 resources/list、resources/templates/list、resources/read 的只读模型工具，保留 cursor，每次访问实时服务器；资源图片转为 image，非图片二进制不转成 base64 聊天文本。
- native 工具结果保留 text/image/resource/resource_link；兼容 Claude base64 image source；图片通过 AcpClient 图片回调进入已有 UI 路径。
- Pi 拒绝将图片 prompt 发给未确认支持 image input 的模型；权限字符串摘要保留文本并继续脱敏；维持 settled/取消权威结束边界。
- 生产 MCP 新增 `turboism.render.state` / `turboism.render.capture`，复用 SDK `renderPixels`，校验文档/模型/历史/实际参数姿态，返回 typed PNG 与哈希元数据。
- 工具目录输出校验保留首个 JSON 文本与 structuredContent 一致性，允许有数量和字节上限的 PNG 附件，拒绝不合法附件。

执行并通过：

```text
node plugins/acp/src/test/js/pi-mcp-extension.test.mjs
./gradlew :plugins:mcp:test :plugins:acp:test :plugins:mcp:spotlessJavaCheck :plugins:acp:spotlessJavaCheck --no-daemon
```

Gradle XML 统计：MCP 245 项，0 失败/错误/跳过；ACP 208 项，0 失败/错误、3 跳过。报告位于构建目录 `build/worktree/mighty-seal/{mcp,acp}/test-results/test/`，未将构建报告复制到源码。

Pi JS 测试覆盖工具权限、图片转发、资源发现/分页/读取、非图片二进制抑制、输入校验、预取消和分块 HTTP SSE。Java 测试覆盖三个 native harness 的图片到图片回调及可解码预览、非视觉模型拒绝、settled 边界、权限摘要；MCP 测试覆盖 JSON 传输数值类型、原生像素到 PNG、状态/pose 过期、渲染中切文档、准入/权限、尺寸/字节预算和生产目录装配。

测试使用模拟 transport、SDK 或本地测试 HTTP 服务；没有运行真实 harness CLI/视觉模型，也没有连接 Cubism 或 GPU。图片回调/解码通过不等于 Swing 窗口实机可见或模型已经消费图片。状态读取不探测 GPU 可用性。原生渲染尚未证明真实宿主通过；模型到像素矩阵在 SDK 中缺失，返回 null，不伪造变换。

[参考 Paseo 实现](harness-reference.md)只涉及协议与事件边界，不证明自动建模完成。三类 authoring 读取见下一节；关键形写入、跨对象事务、质量算法、pose grid、文件授权和保存重开仍需实施及专项验收。

## 三类 stored authoring 读取切片

生产目录新增 `turboism.authoring.meshes.read`、`turboism.authoring.warps.read`、`turboism.authoring.rotations.read`，连同此前渲染工具共 18 个工具。显式 SDK DTO 投影保留 source/UV/topology、普通 axes/cells/forms、Warp 存储控制点、Rotation baseAngle 与逐 form 的完整值，以及 ArtMesh Morph 身份/原生 null。只读，不移动参数或选择，不替换为求值几何。

快照缓存按 MCP 会话隔离，保留完整原生 expected DTO；UI 队列单独传递会话所有权，不将中途取消误当成已回滚。缓存有 TTL、条目/总字节上限和深层不可变页面；分页使用同一快照，历史或目标切换后拒绝继续读取。

执行并通过：

```text
./gradlew :plugins:mcp:spotlessJavaApply :plugins:mcp:test :plugins:mcp:spotlessJavaCheck --no-daemon
```

最终 MCP XML 统计：256 项，0 失败/错误/跳过。新增测试覆盖三类 SDK 快照、整页遍历不重新读宿主、会话/历史隔离、TTL/条目/字节预算、深层不可变性、UI 会话恢复清理、重复显示参数 ID、多轴完整元组、共享 form、Morph null 和实际超限快照。ACP 仍为此前完整验证的 208 项，0 失败/错误、3 跳过；Pi JS 测试通过。`git diff --check` 通过；审计 59 项 ID 唯一，57 处本地链接有效。

覆盖账本增加四个 scoped semantic capability（原生渲染、三类读取）；tracked owner 仍是原有十三个，尚未完成整个 Drawable/Warp/Rotation owner 的逐方法覆盖扩展。读取接口尚不包含 evaluated 几何、完整逐对象写准入，也不证明关键形 writer、CAS/事务/Undo 或实机闭环可用。接下来复用缓存的完整 DTO 实施 typed writer，再进行质量、保存重开和官方模型副本的实机验证。

## 文档整理补充

补充 Paseo harness 通信契约、逐通路验收表，并将已有读取的离线测试状态与待实现写入区分。本次补充只修改审计目录内 Markdown；未运行真实 harness、Cubism 或模型操作，也未重跑生产测试。前文自动化结果属于已有实现切片的记录。

文档检查通过：59 项审计 ID 唯一、60 处本地 Markdown 链接存在、5 个 Paseo 参考文件 SHA-256 匹配；`git diff --check` 通过。此检查不证明新增建模接口或 harness 实机通路可用。

## 普通关键形写入预检切片

新增 `McpKeyformWritePlan`，为 ArtMesh/Warp/Rotation 分别构建不可变 SDK batch DTO 与完整预期写后 snapshot。此层为纯数据预检，没有 SDK setter 调用，也尚未接入生产 MCP 写工具；生产目录仍为 18 个工具。A3/A4 保持未完成。

预检要求完整 GUID tuple 与实际 cell/form 匹配；拒绝默认形、共享 form、Morph 引用的 form、重复 form、重复/未知轴、不完整 tuple、缺少精确默认 cell、重复 cell/keys、跨类型字段、非有限数和点数不符。原生 float 轴键必须通过文本精确往返，防止不同 JSON 坐标舍入后误命中另一 cell；几何值采用有限 native float 转换。每批最多 64 个 form、合计 16,384 个点，后续 MCP 封装还需约束总请求字节与跨对象总预算。保持 source/UV/indices、非目标 form、draw order、baseAngle/grid 和 Morph 身份等未编辑字段。

执行：

```text
./gradlew :plugins:mcp:spotlessJavaApply :plugins:mcp:test :plugins:mcp:spotlessJavaCheck --no-daemon
```

XML 统计为 MCP 267 项，0 失败/错误/跳过；其中新预检测试 11 项。覆盖三类重建、默认/共享/Morph 拒绝、重复显示 ID 的完整双轴元组、数值和闭合字段、单 form 与多 form 合计预算、输入深层隔离。格式及 `git diff --check` 通过。首次测试编译因使用 Java 21 的 List.getFirst 而失败，改为兼容当前编译目标的 get(0) 后上述全量测试通过。

下一步把预检接入共享快照缓存，实施完整状态 CAS、全部目标写前验证、真实 AuthoringTransactionService 单步 Undo/回滚、operationId 去重和结果核对，再暴露闭合 MCP 写 schema。当前测试不证明宿主写入、Undo/Redo、取消、视觉质量或持久化通过；本轮没有连接或修改 Cubism 模型。

## 普通关键形生产写入与结果核对切片

生产装配新增两个工具（共 20 个）：

- `turboism.authoring.keyforms.write`：ArtMesh positions、Warp controlPoints、Rotation 完整 form 的闭合 union。使用共享会话快照缓存中的完整原生 SDK DTO，而非只比对 snapshotId 或历史 revision。比较 document/model、history generation/revision/manager、source/form、完整存储内容和父级身份；全部目标先预检，在 SDK 事务内部再次预检后才调用对应 typed batch setter。写后比较完整预期 snapshot，包括未编辑 form/source/UV/indices/draw order 等字段。
- `turboism.authoring.keyforms.result`：独立 DIRECT 只读结果查询，不进入 UI 队列或读取模型。记录 operationId、历史事务收据、SDK diagnosticId、包含对象/source 身份与全部投影字段的 SHA-256、回读标记和明确结果。相同会话的相同已准入请求复用结果，冲突复用拒绝。

写工具自身拥有 AuthoringTransactionService 事务，因此 metadata 为 TRANSACTION_CONTROL、transactionEligible=false，禁止嵌套在 transaction.execute；没有改变 legacy apply 的资格。跨对象写共用一个 SDK 事务，无法准入则不回退单独 setter。事务内部验证失败交由 SDK 回滚；事务返回 RECOVERY_FAILED 或抛异常时明确 OUTCOME_UNKNOWN；提交后的回读问题保留收据并返回 APPLIED_WITH_READBACK_WARNING。宿主开始前可取消；开始后等待实际结果，不将 Agent/桥接取消当成宿主回滚。

预算：8 个对象、64 form、合计 16,384 点、768 KiB 请求、96 KiB 预计算摘要。身份字段有长度限制。结果全服务器最多保留 256 个已准入操作，保留到进程结束，不静默淘汰以允许旧请求重放；满额拒绝新写。此机制不提供跨会话或跨进程持久化恢复；查不到结果始终是未知，不表示宿主未变。

中英日插件说明、model.write 权限说明、生产目录断言和 scoped semantic 覆盖行同步更新；账本为自有事务增加 MCP_TRANSACTION_CONTROL 分类及其约束，trackedOwners 仍为十三个，不代表完整 SDK owner 审计完成。

本次专项测试使用模拟 SDK、事务 backend 和 HTTP 服务：验证三类 setter/预期快照、全部目标预检、旧几何与父级/身份/历史拒绝、跨会话隔离、一次事务调用、第二对象故障回滚、意外字段变化与回读失败、恢复失败、提交后结果丢失、operationId 冲突/去重、RUNNING 查询、无变化与缓存满额、准入前后取消，以及生产 HTTP 写鉴权/只读结果查询。测试夹具的 Undo/Redo 是模拟事务状态恢复，用于核对调用分组；不构成真实宿主历史、GPU、视觉模型或保存重开证据。

最终执行：

```text
./gradlew :plugins:mcp:spotlessJavaApply :plugins:mcp:test :plugins:mcp:spotlessJavaCheck :plugins:mcp:jar --no-daemon
```

MCP XML 统计为 284 项，0 失败/错误/跳过；其中写入 Domain 16 项，HTTP 新增鉴权/结果查询案例通过。JAR 检查确认打包 McpKeyformWriteDomain、McpKeyformWriteSchemas 与覆盖账本。60 处本地文档链接及空白检查通过。本轮没有修改 ACP 源码，也未重跑 ACP 测试；此前 ACP 结果只作历史记录。

仍未连接或修改真实 Cubism 模型；A3/A4 完整验收和 Milestone A 均未完成。接下来推进属性/遮挡、pose 扫描与参数恢复、能力细分、几何/视觉 QA、官方模型副本实机和保存重开证据。

## 多姿态扫描、恢复与结果控制切片

生产装配新增 `render.pose_grid` 及其 status/cancel/result/release 四个控制工具，共 25 个工具。扫描显式写参数，使用独立 SDK 事务并强制 restorePolicy=always；每个 pose 相对原始基线，返回每张 PNG 与实际参数/poseHash/history 状态、captureId、SHA-256、MCP content 索引。所有原始参数值都参与恢复核对；未验证恢复则明确 parametersRestored=false，不把 SDK 回滚结果替代数值证明。恢复值不代表历史未变化：最多产生一个 Undo 条目，收据保留真实 history 信息，后续须重新读取 render/authoring 状态。

控制工具不进入 UI 队列。请求取消 token 在 UI 调度前捕获，仅在扫描安全边界观察；当前 GPU/setter 调用与 finally 恢复不被强制中断。30 秒 timeBudgetMs 是协作预算，无法界定单个原生调用时长。响应丢失可查询当前会话进程内结果；不存在的结果仍是未知。最多保留两组图片和 64 个操作身份，释放终态图片后可继续扫描；元数据与防重放身份保留，已释放结果无 PNG、imageContentIndex=null。没有实现跨会话/服务器重启持久恢复或通用 MCP Tasks 协议。

数据上限为 9 pose、6 目标轴、合计 1,048,576 像素、3 MiB PNG、128 KiB 规划/基线、240 KiB 结果元数据（为终态收据预留空间）。图片附件上限由 8 调为 9，仅支持 XY 九宫格；PNG 总量和既有 HTTP/Pi/事务结果字节边界未提高。标准 render.state/capture 参数元数据也加 128 KiB 限制。

源码核对发现 Runtime Parameters.all() 以 firstById 将重复显示 ID 的句柄映射至第一来源，不能据 ordinal 假装读取全部实际值。因此 render.state/capture 明确返回 AMBIGUOUS_PARAMETER_IDS，pose 扫描也失败闭合；尚未提供参数值 native GUID 定位，不升级为“任意参数模型均可扫描”。生产 SDK/Runtime 没有因本切片而更改重复 ID 行为。

执行通过：

```text
./gradlew :plugins:mcp:spotlessJavaApply :plugins:mcp:test :plugins:mcp:spotlessJavaCheck :plugins:mcp:jar --no-daemon
```

最终 XML：MCP 303 项，0 失败/错误/跳过。其中 pose 17 项；另验证九附件允许、十附件拒绝、PNG 总预算、参数元数据字节预算和 HTTP 扫描/取消/释放鉴权。JAR 含 McpPoseGridDomain 与 McpPoseGridSchemas。专项用模拟 SDK 栅格解码实际 PNG 并核对九张图的 attachment/参数映射；覆盖相对基线、部分渲染失败、直接取消与请求取消、协作超时、文档切换保护、恢复 setter/SDK 回滚失败、未知结果、会话/缓存隔离、释放防重放、未改值时 NO_CHANGE。fixture 的历史与恢复为模拟，不能作为真实 Cubism Undo/GPU/视觉模型或保存重开证据。本轮未连接或修改真实模型，未改 ACP 或重跑 ACP 测试。

A7 已有实现切片，但局部裁剪、跨会话恢复、实机副本验收以及几何/视觉质量算法仍缺。Milestone A 及自动建模总体目标未完成。

## 本次文档整理复核（2026-10-24）

本次只修改审计目录 Markdown。将历史 13 工具基线、已有 25 工具/303 项离线验证记录，以及新增未验证几何诊断改动分别标注；合并实施清单中相互过时的进度描述。Paseo 参考保留固定文件摘要与逐 harness 验收矩阵，不把参考实现作为 Turboism 实机证据。

本次检查：59 项审计 ID 唯一、61 处本地 Markdown 文件链接存在、`git diff --check` 通过。未重跑生产编译/测试，未连接 Cubism、启动模型或执行模型写入；未修改已有生产代码。上述测试数量仅引用此前记录，不验证当前全部工作区改动。

## 范围明确的几何诊断切片

目标续行新增 `turboism.validation.geometry`，生产目录共 26 工具。纯算法读取 ArtMesh source/stored 快照，仅使用显式同对象普通存储参考 form 作相对比较，不混用 source 坐标。覆盖空/未用/重复/非流形拓扑、绕序一致性、退化、三角形长宽比、参考翻面与边长伸缩。允许只读诊断默认/共享普通 form，拒绝 Morph 引用/非普通 form。每条 finding 定位 scope/form/triangle/indices，保留阈值与观测值。

接口在分析前后核对完整原生 snapshot、document/model/history 和父级；revision 相同但几何不同也拒绝。MCP 会话快照 TTL/隔离继续生效。预算为 32 目标 form、200 保留 findings、2M 预估工作量、160 KiB findings 和 240 KiB 总结果。截断或未定义比较返回 INCOMPLETE；ok 只表示诊断运行成功。交叉、裂缝、UV/图集、求值/插值连续性、画布、蒙版/遮挡和美术质量明确未实施。无 setter、选择、调参或渲染调用。

执行通过：

```text
./gradlew :plugins:mcp:spotlessJavaApply :plugins:mcp:test :plugins:mcp:spotlessJavaCheck :plugins:mcp:jar --no-daemon
```

XML 统计 MCP 322 项，0 失败/错误/跳过。新增算法 12 项、Domain 7 项，覆盖坐标空间、精确定位、阈值、拓扑坏样本、退化参考、截断、预算、有限 float 极值、不可变结果，以及前后 CAS、父级/文档/历史、会话/过期、闭合 schema、异常脱敏。生产 HTTP 目录断言 26 工具通过；JAR 包含算法、Domain 和 schema。三语工具目录与 scoped semantic 覆盖行已同步，trackedOwners 仍为原十三个，不代表整个 SDK 覆盖。`git diff --check` 通过。

这些测试使用模拟 SDK 与测试 HTTP 服务。本轮没有连接真实 Cubism、GPU、视觉模型或执行模型/文件操作；没有实机 Undo/Redo、质量或保存重开证据。A9 完整质量闭环、Milestone A 和自动建模目标均未完成。后续仍需属性/遮挡、细分准入、视觉与 rig 算法及官方模型副本实机验收。

## 普通关键形绘制顺序与联合写入切片

按 GEO-05/A5 扩展 `turboism.authoring.keyforms.write` 的 ArtMesh 闭合 schema：每条 edit 指定 positions、drawOrder 或两者；drawOrder 为 0..1000 的精确整数，拒绝分数、溢出、非有限数、字符串和跨类型字段。完整 GUID tuple、默认/共享/Morph 保护、完整 snapshot CAS 和结果核对沿用原契约。工具数量仍为 26。

纯顺序使用 `Drawable.setOrdinaryKeyformDrawOrders`；纯顶点使用原 positions writer；联合或混合 edit 使用 `setOrdinaryKeyforms`，省略字段仅从不可变 expected snapshot 补齐，使两个 SDK 列表引用相同 form 集合。补齐的顶点也计入 16,384 点上限；纯顺序不人为上传顶点。跨对象事务内先完整核对，再写入，随后完整回读；未目标 form/source/UV/topology 均受 expected-after 比较保护。相同前后状态在已验证事务内跳过 setter，仍由真实 SDK 事务结果确定 NO_CHANGE。

新增 8 项测试（plan 5、Domain 3），覆盖顺序边界、联合/混合计划、补齐预算、默认/共享/Morph 拒绝、精确 SDK writer 选择、字段保留、模拟单事务 Undo/Redo、重复请求、跨对象异常回滚、提交后非目标字段变化警告、无变化及 schema 拒绝。首次专项运行发现旧测试仍把 drawOrder 当未知字段，另两处新测试对事务阶段/无变化 setter 的预期不匹配；已校正测试并在生产执行器跳过相同前后状态的 setter，随后全量通过。

执行：

```text
./gradlew :plugins:mcp:spotlessJavaApply :plugins:mcp:test :plugins:mcp:spotlessJavaCheck :plugins:mcp:jar --no-daemon
```

XML 为 MCP 330 项，0 失败/错误/跳过；writer plan 16 项、Domain 19 项。JAR 包含 writer Domain/Plan/schema；三语文档、工具 schema 描述与 scoped semantic 覆盖行同步。trackedOwners 仍十三个。本轮未修改 SDK/Runtime/ACP，未重跑 ACP 测试，也未连接或操作 Cubism。上述 Undo/Redo 与回滚使用模拟事务 backend，不能作为真实宿主历史或视觉遮挡证据。

A5 仍缺蒙版及必要外观读写和实机验收；完整自动建模尚需语义/rig 算法、视觉 QA、保存重开和官方模型副本集成。

## 蒙版依赖快照与条件批量写入切片

新增 `turboism.authoring.masks.read/write/result` 并接入生产装配，目录由 26 增至 29 工具。读取显式 objectIds 并展开已有蒙版的传递依赖，记录每个 ArtMesh 的原生 GUID、父级、完整蒙版列表与反向标志，以及 document/model/history 身份。读取不调用几何、选择、参数或渲染接口。会话签发快照 TTL 5 分钟、16 份、每份 64 KiB，最多 64 对象；超限明确失败，不签发成功快照。

写请求必须引用该会话快照，所有目标及拟使用的蒙版源均在快照范围内。事务前与事务内核对整个依赖范围，包括 GUID（删后重建）、父级、蒙版、反向标志及历史；同 revision 的实际蒙版变化也拒绝。使用 SDK `replaceArtMeshClipMasks` 条件批量接口同时修改列表/反向标志，置于自己的 AuthoringTransactionService 事务，不加入 `transaction.execute`。事务内外完整回读范围内状态，保护非目标对象。最多 8 目标、128 KiB 请求。空替换列表是 SDK 契约明确拒绝的范围，因此工具不能清空蒙版；自引用、重复来源、快照外来源和新引入循环拒绝。既有循环仍可读取，未引入新边的反向标志编辑不会误报新循环。

操作结果使用会话隔离、进程内 operationId 去重/冲突保护和最多 256 操作的无淘汰结果保留。DIRECT result 查询不等待 UI；满额拒绝新写。相同请求复用既有结果，响应丢失/恢复失败返回 OUTCOME_UNKNOWN，不自动重放。事务提交后的回读问题返回带原生收据的 APPLIED_WITH_READBACK_WARNING；事务内不一致触发 SDK 回滚。取消前后边界分别测试；Agent 取消后不假称宿主回滚。无跨会话/重启的持久恢复。

执行通过：

```text
./gradlew :plugins:mcp:spotlessJavaApply :plugins:mcp:test :plugins:mcp:spotlessJavaCheck :plugins:mcp:jar --no-daemon
```

XML 为 MCP 346 项，0 失败/错误/跳过；新增 mask Domain 16 项。覆盖闭合 schema、依赖展开、预算/TTL/淘汰、GUID/父级/历史/同 revision 变化、事务内竞态、非目标异常变化、部分批次失败回滚、恢复失败、提交后丢响应/回读失败、无变化、会话隔离、结果满额/冲突/去重、循环与无效来源、权限脱敏和取消边界。HTTP 集成验证 29 工具目录、蒙版写鉴权及无 UI 等待的只读结果查询。JAR 包含 McpMaskDomain/McpMaskSchemas；三语目录各 29 行，权限说明与 scoped semantic ledger 同步，trackedOwners 仍十三个。

初次编译因 Json.stringify 仅接受 Map/List 而失败，已修正类型边界后完成上述完整回归。未修改 SDK/Runtime/ACP，未重跑 ACP 测试。SDK/事务及 HTTP 测试均为模拟；没有连接或修改真实 Cubism 模型，没有实机 GPU、Undo/Redo、视觉遮挡、保存重开证据。A5 和 Milestone A 保持未完成。

## 原生最近计算结果观察切片

新增 `turboism.authoring.meshes.evaluated.read` 并接入生产装配，共 30 工具。使用同会话 meshes.read 签发的 authoringSnapshotId，核对完整存储 authoring 状态、父级与 document/model/history，再观察 SDK evaluationSnapshot。返回 calculated positions、drawOrder、原生 coordinateType、interpolatedIdentity、实时/已消费的 GUID 参数及独立有符号 updateVersion、原生 passEntryTimeMillis；保留重复显示 ID 的独立 GUID/值。无 model.update、渲染、调参、选择或 Core/current-form 代用。

SDK 契约明确：passEntryTimeMillis 写于原生计算开始，不证明各网格成功计算；计数器独立，不可跨 set 比较；interpolated 是原生对象身份相等，不证明插值成功。输出 freshness 始终 UNPROVEN，liveConsumedValuesMatch 仅比较 GUID/值映射；两组值相等也不升级为成功求值。calculatedForm 缺失时只返回观察元数据，没有顶点行。坐标标签不冒充 Core/source/stored/像素坐标或单位。

分页保留同一不可变样本，每页重新核对完整 native sample 与 authoring/父级/历史；姿态或计算结果变化拒绝。预算：65,536 顶点、live/consumed 各 2,048 参数、样本 4 MiB、每页 512 行/240 KiB；共享原 authoring 缓存 TTL/条目/总字节/会话限制。共享缓存增加求值 DTO 类型及依赖插入保护：不延长 authoring 父快照 TTL，插入求值样本时先保护父快照不被本次淘汰，后续过期/淘汰仍返回明确 NOT_FOUND。

执行通过：

```text
./gradlew :plugins:mcp:spotlessJavaApply :plugins:mcp:test :plugins:mcp:spotlessJavaCheck :plugins:mcp:jar --no-daemon
```

XML：MCP 358 项，0 失败/错误/跳过；新增求值 Domain 12 项，原缓存测试同时通过。测试覆盖 GUID 重复显示 ID、独立/负数计数器、顺序不同但值相等、缺失 calculated form、未证明新鲜度、完整分页、pose/样本/源/拓扑/父级/历史/身份变化、采样期间变化、会话/TTL/错误 token、满缓存依赖保护、参数数量/字节预算、页字节自适应、闭合 schema、异常脱敏。最初专项因缓存尚未允许 evaluation DTO 而失败，补齐类型及 import 后完成专项与全量回归。

生产 HTTP 目录断言 30 工具通过；JAR 含 McpEvaluatedMeshDomain/McpEvaluatedMeshSchemas；三语工具表各 30 行，scoped semantic ledger 同步，trackedOwners 仍十三个。未修改 SDK/Runtime/ACP，未重跑 ACP 测试。上述均为模拟 SDK/HTTP 证据；本轮未连接真实 Cubism 或修改模型，没有真实求值成功、GPU、视觉消费、Undo/Redo 或保存重开证据。A2、A5、A9 及 Milestone A 的剩余门槛均保持未完成。

## 三类对象源级属性切片

新增 `turboism.authoring.properties.read/write/result`，生产目录共 33 工具。read 使用同会话对应 authoringSnapshotId，支持 ArtMesh/Warp/Rotation 自身的 visible/locked，ArtMesh 另有 culling，返回 scope=source_object 和签发的 property snapshotId。保留完整原生 authoring DTO、父级、历史及已支持属性；共享 authoring 缓存预算/TTL/会话隔离，保守计入原 native 投影字节与属性元数据。快照插入保护原 authoring 父条目且不延长 TTL。

write 的闭合 union 只接受各类型显式非空 Boolean patch，最多 8 对象/128 KiB；拒绝跨类型 culling、空 patch、opacity 等未提供字段和非 Boolean 值。事务前及事务内全目标校验完整存储形状、GUID/父级/历史及属性，再调用 typed source setter，写后完整核对。源属性修改不改变默认/其他存储 form。省略属性和相同值不调用 setter；不自动解锁、改祖先或移动参数/选择。请求显式 locked:false 才改目标自身标志。

每个 Domain 最多保留 256 个会话/进程内已准入操作结果，不淘汰允许重放；DIRECT result 不等待 UI。结果含原生事务收据、前后身份/属性哈希及回读标记。冲突 ID、未知结果、回读警告、回滚和恢复失败保留原语义。将蒙版既有结果编码/schema/hash提取为共用 McpWriteResults，蒙版专项及完整回归继续通过，未改变其输入/输出形状或预算。

执行通过：

```text
./gradlew :plugins:mcp:spotlessJavaApply :plugins:mcp:test :plugins:mcp:spotlessJavaCheck :plugins:mcp:jar --no-daemon
```

XML：MCP 374 项，0 失败/错误/跳过；新增 source property Domain 16 项。覆盖三类读写、单事务多对象、模拟 Undo/Redo/回滚、全部目标预检、同 revision 属性/几何/父级变化、事务内竞态、未请求属性/几何意外变化、无变化、显式解锁、提交后丢响应/回读失败、恢复失败、不可用服务、会话/TTL/错误 token、闭合 schema、重复目标、预算与结果满额、operationId 冲突/去重、异常脱敏、读竞态、取消前后与 RUNNING 查询。HTTP 目录断言 33 工具、源属性写 Bearer 鉴权和只读结果查询通过；JAR 含 Domain/schema/共用结果类，三语表各 33 行，权限和 scoped semantic ledger 同步，trackedOwners 仍十三个。

SDK 核对确认 opacity/颜色 setter 使用当前实例形状，不等同于普通存储 form；blendMode 是 Core 评估简化枚举，不足以读回精确 ColorComposition。因此本次不冒充提供上述工作流，也不以缺少稳定 GUID 的 Part ID 代替删后重建保护。源级属性是 A5 的一个切片，剩余 opacity/颜色/混合模式/Part 身份与实机视觉、Undo/Redo、保存重开仍未完成。未修改 SDK/Runtime/ACP，未重跑 ACP 测试；没有连接真实 Cubism 或执行模型操作，所有新增验证为模拟 SDK/事务/HTTP。

## 会话文件授权桥接切片

新增 `turboism.files.request_grant/grant_status/revoke_grant` 并加入生产目录，共 36 工具。复用 PluginContext.userFiles()/UserFileAccessService 的异步用户选择流程，MCP 不暴露路径、原生 handle.id/displayName 或文件内容。mode 为 READ/WRITE、用途最多 200 个无控制字符、扩展名过滤器 1–8 项；SDK 再校验扩展名语法。用户批准后仅返回会话/进程内 opaque grantId，不代表已打开、保存或导出。

状态查询为 DIRECT READ，发起/撤销为 DIRECT EXTERNAL_SIDE_EFFECT、不可嵌套 authoring transaction、需要 Bearer。SDK 自己安排原生 chooser，不在 MCP UI 队列阻塞等待授权结果。operationId 按会话去重并检查完整输入指纹；保留最多 128 个请求身份，满额拒绝，不淘汰后允许旧请求重放。请求准入后 5 分钟过期，不续期。服务器维护线程定期释放过期授权，查询同时核对原生 handle 状态。

全服务器最多一个原生待决 chooser。撤销/过期/会话删除后，本地授权失效，但在原生 completion 之前仍占待决名额；响应明确区分 nativeChooserPending 与 hostCancellationConfirmed=false。未取消原生 stage，不把本地撤销当作窗口关闭；迟到授权一律 revoke/close。会话删除、空闲淘汰和服务器关闭均连接桥接清理，停止时关闭维护线程。native cleanup 失败仍尝试独立 close，桥接身份不会重新有效；不将原生异常中的路径或凭据写入结果。

专项测试 15 项通过。首次全量为 389 项、8 项失败：原 PluginContext proxy 不识别新增 userFiles，通用结果查询断言也不适用授权的 NOT_FOUND。补齐测试夹具和断言后全量通过，再增加 HTTP 会话删除/服务器关闭释放案例。最终执行：

```text
./gradlew :plugins:mcp:test :plugins:mcp:jar --no-daemon
```

最终 XML：MCP **390 项，0 失败/错误/跳过**。JAR 包含 McpFileGrantDomain/McpFileGrantSchemas。测试覆盖异步/同步完成、exception 脱敏、方向/生命期/状态不匹配、会话隔离、TTL/原生撤销、迟到授权、重复/冲突/并发请求、quota、pending 撤销不释放 chooser 名额、缺服务、非法路径字段/过滤器、空会话、cleanup 部分失败，以及 HTTP Bearer 准入和会话生命周期。全部为模拟 SDK/HTTP，不是原生 chooser 或真实文件系统持久化验收。SDK 覆盖账本增加 UserFileAccessService，共 14 owner，未公开内容读写逐项列排除，guard 通过。

`git diff --check` 通过。只读 `:plugins:mcp:spotlessJavaCheck` **未通过**，报告新增 Domain/schema/测试和 HTTP 装配格式差异。此前含 `spotlessJavaApply` 的命令被自动审批拒绝，理由是可能违反用户曾指定的“只整理文档、不修改生产代码”边界；没有绕过拒绝执行格式化。已向用户询问是否允许格式化并继续后续生产实现，待回复。格式检查与功能测试分别记录，不称全部检查通过。

没有启动或连接 Cubism/harness，没有真实文件授权、模型写入、GPU、Undo/Redo、保存重开或视觉模型验收。本轮未修改 SDK/Runtime/ACP，也未重跑 ACP 测试。授权消费/open/save-as 及完成核对仍需后续独立契约，本切片不足以满足 A10/B1，更未满足自动建模目标。

## 授权消费与模型另存切片

用户已明确回复“允许格式化并继续实现”，本轮已执行此前因文档边界被拒绝的格式化并通过只读格式检查；原审批问题已解决，不再作为实施阻碍。

新增 `turboism.documents.save_as/file_result`，目录共 38 工具。SAVE_AS 为 UI_THREAD EXTERNAL_SIDE_EFFECT，不可 Undo，不嵌套 authoring transaction；file_result 为 DIRECT READ。使用同会话且扩展名范围仅 cmo3 的 WRITE grantId、六字段 authoring expectedState 和明确 REJECT_EXISTING。写前核对活动 document/model/history，消费授权后在原生分派前再核对；这不是完整几何快照 CAS。只接受新目标副本；现有 SwingUserFileGrantSource 使用普通 showSaveDialog，没有独立覆盖批准契约，因此排除 REPLACE_EXISTING。

grant 消费是原子单次转移，状态 CONSUMED；Lease 持有原生 handle 至命令结束，并在所有结束路径尝试 revoke/close。过期、撤销、会话删除或服务器关闭不伪装成已取消已准入的 native 调用，也不提前释放正在使用的 handle；调用结束负责释放。consume 验证会话、状态、方向及允许扩展名，方向/扩展不符不消费。chooser 准入与本地撤销/close 在同一锁下排序，补上创建请求至原生 request 之间的生命周期竞态。

操作按 session+operationId 保留，最多 128 个身份不淘汰重放；输入 expectedState 防御性复制。QUEUED/RUNNING 可以无 UI 等待查询，完全相同请求复用结果，冲突拒绝。UI 队列准入由既有 cancellation 边界管理；原生开始后 await 实际结果。响应丢失后应查询 file_result，不能盲目重放。命令不符/原生 FAILED/调用异常为 OUTCOME_UNKNOWN；明确原生拒绝为 NOT_APPLIED；EXECUTED 仅表示原生成功报告（可含 no-op）。状态回读变化/失败记 STATE_READBACK_WARNING；persistenceVerified/reopenVerified/retrySafe 始终 false。

Runtime 源码核对：VerifiedTypedEditorCommandOperations.execute(ResolvedEditorFileCommand) 仅派发 SAVE_AS，OPEN 因首次打开行为未完整验证明确 unavailable；native saveAs 以原生保存布尔结果映射 EXECUTED/FAILED。MCP 未开放 OPEN，也未将枚举 supports 或 availableDirectCommands 当作文件命令完整准入。覆盖账本 EditorCommandService 的文件 overload 更新为 SAVE_AS 有限域；trackedOwners 保持 14，未宣称所有文件命令已开放。

执行：

```text
./gradlew :plugins:mcp:spotlessJavaApply :plugins:mcp:test :plugins:mcp:spotlessJavaCheck :plugins:mcp:jar --no-daemon
```

最终 XML：**MCP 407 项，0 失败/错误/跳过**；DocumentFileDomain 15 项、FileGrantDomain 17 项。新增案例覆盖单次授权消费/释放、READ/跨会话/过期/错误扩展、六字段 stale 和准入期间竞态、原生失败/异常/错误 commandId、已成功但读回失败或变化、重复/冲突/quota、QUEUED/RUNNING 直接查询、准入前取消、不可用服务、防御性复制、schema 拒绝路径/generic command/覆盖政策、consume 后生命周期及 Lease 幂等释放。HTTP 集成验证 38 工具目录、另存 Bearer 保护及直接只读结果查询。JAR 包含 DocumentFileDomain/schema 与 FileGrant Lease，`git diff --check` 通过，三语工具与权限说明已同步。

未修改 SDK/Runtime/ACP，未重跑 ACP 测试，未启动或连接 Cubism/agent harness。上述全部为模拟 SDK/命令/HTTP 证据，没有真实保存、源文件保护、文件哈希或保存重开证据。OPEN 原生实现/精确版本验证、覆盖批准、磁盘验证及官方模型重开验收仍需后续工作；A10/A11 和自动建模目标保持未完成。

## 授权文件完整读取与 SHA-256 观察切片

新增 `turboism.files.inspect/inspect_result`，生产目录 40 工具。inspect 为 DIRECT EXTERNAL_SIDE_EFFECT（消费授权，不修改文件）、需要 Bearer、不可嵌套 authoring transaction；inspect_result 为 DIRECT READ。输入 operationId、同会话 READ grantId、显式 maxBytes=1..134217728。SDK readBytesBounded 负责异步 I/O；MCP 立即返回 RUNNING，不使用会超时取消 native future 的 execution.stage。专用单 worker 在注册表锁和 native completion 线程之外计算 SHA-256，结果只留小型元数据，不返回路径、原生标识、文件名或文件内容。

非截断读取返回 COMPLETE、byteLength、sha256；截断返回 TRUNCATED 和观察到的前缀字节数，sha256 为 null，绝不把前缀摘要冒充完整文件哈希。SDK 超限返回值、结构化错误、异常/空 stage、worker 拒绝分别失败闭合并释放 Lease。空文件使用真实 SHA-256。maxBytes 是读取上限；Runtime readBytesNow 会临时分配 ByteArrayOutputStream/复制缓冲，不能声称进程内存严格等于这个数值。全服务器最多一个 I/O/hash 在途，避免多份大读叠加；native 或 hash 真正结束前不放开该名额。

最多保留 128 个按会话隔离的操作身份，不淘汰后允许旧请求重放，完全相同请求复用结果、冲突拒绝。session 删除/空闲过期连接 inspect 清理；server close 停止 worker 并让已排队 completion 执行清理，不使用 shutdownNow 丢掉 Lease。原生读取仍待决时不会假称取消：会话关闭后丢弃迟到结果，实际 completion 才释放句柄/在途名额。没有 native I/O 取消或跨重启恢复声明。

证据界限：fileStability=UNPROVEN，SDK 没有提供对并发外部修改隔离的文件快照；documentAssociation=UNVERIFIED，另选 READ grant 不证明是原 SAVE_AS 目标、原件或当前模型。COMPLETE 仅表示 SDK 报告的一次非截断读取，不证明格式、fsync、保存前后几何一致或成功重开。documents.save_as 的 persistenceVerified/reopenVerified 保持 false。

执行通过：

```text
./gradlew :plugins:mcp:spotlessJavaApply :plugins:mcp:test :plugins:mcp:spotlessJavaCheck :plugins:mcp:jar --no-daemon
```

权限说明同步后又执行不改写源码的 test/spotlessJavaCheck/jar，最终 XML：**MCP 422 项，0 失败/错误/跳过**。FileInspectDomain 14 项（含 SDK 默认 <=16MiB 委托与 >16MiB RUNTIME_UNAVAILABLE），另增加 HTTP 原生读待决时删除会话并释放迟到授权的案例。覆盖 abc/空文件真实摘要、非截断/截断、SDK 超预算、单在途、重复/冲突/quota、方向/会话/过期/已消费、exception 脱敏、缺 stage、queue/worker 关闭和拒绝、session/server 结束及 schema 预算/路径拒绝。HTTP 验证目录 40、inspect Bearer 准入和无 UI/I/O 等待的结果查询。覆盖账本 readBytesBounded 更新为 inspect 的有限观察域，owner 数仍 14，不提供原始内容传输。JAR 含 McpFileInspectDomain/schema；三语文档与 file.read 权限说明同步。

没有修改 SDK/Runtime/ACP，未重跑 ACP 测试，未连接或启动 Cubism/harness，没有实际磁盘/宿主保存/重开/官方模型保护证据。上述为模拟 SDK/HTTP、内存字节和 SDK 默认方法证据。OPEN、覆盖批准、文件身份关联/格式校验、官方副本保存重开及几何/视觉质量仍待交付验收；不能因此勾选 A10/A11 或完成自动建模目标。

## source/stored 三角形内部重叠切片

扩展现有 `turboism.validation.geometry`，生产目录仍 40 工具，不新增宿主 setter。新增 McpTriangleOverlap：按 minX/index 确定性排序的 AABB 扫描，正面积非退化三角形使用双向边分离轴测试；只有共享边/顶点或边界接触不作为内部重叠。包含、重合、交叉及同拓扑边的相邻折叠可以报告 WARNING，不能用“相邻”统一排除。零面积三角形无内部，由已有面积诊断单独报告；source/每个选中 stored form 独立检查，绝不混坐标或跨对象猜测。

方向谓词先使用保守 double roundoff bound；有限 float 输入的差/乘积不会溢出或下溢 double。误差区间内转为 `new BigDecimal((double)float)`，保留实际二进制值而非 valueOf 的短十进制近似，精确判断符号。精确 fallback 明确计入较高工作成本。算法输出的是严格正面积重叠，不使用模型像素/截图或面积阈值作为模糊接触判据；细小重叠仍是待审几何证据，不自动成为美术错误。

共用原 2M 预估预算：基础检查先估算，剩余用于排序、面片方向、扫描访问、narrow tests 与精确 fallback。预算耗尽保留已得到的 findings，但 intersectionCheckComplete/analysisComplete/comparisonComplete=false 且 verdict=INCOMPLETE；不再声称所有已实施检查均完成。statistics 新增 intersectionPairsTested（完成窄相测试的对数）、intersectionSweepVisits（含 AABB 排除/过期条目访问）、estimatedWork。计数/findings 仅覆盖已检查范围，不能在部分结果下外推全部坏面数量。findings 新增 relatedTriangleIndex；triangleIndex/relatedTriangleIndex 定位两片，indices 为第一片的三顶点，第二片通过签发拓扑定位。既有 finding 新字段为 null；闭合 output schema 同步。

implementedChecks 新增 TRIANGLE_INTERIOR_OVERLAP；未实施范围将原笼统 TRIANGLE_INTERSECTIONS 拆为 TRIANGLE_BOUNDARY_CONTACTS/CROSS_OBJECT_INTERSECTIONS。SEAM_CRACKS、UV/atlas、求值/插值、画布、蒙版/遮挡、美术质量继续省略；不能将此切片升级为全部几何或模型验收。

执行通过：

```text
./gradlew :plugins:mcp:spotlessJavaApply :plugins:mcp:test :plugins:mcp:spotlessJavaCheck :plugins:mcp:jar --no-daemon
```

最终 XML：**MCP 436 项，0 失败/错误/跳过**。TriangleOverlap 10 项、GeometryQuality 14 项、GeometryValidationDomain 9 项；本轮新增共 14 项。案例覆盖 crossing/containment/coincidence、两种 winding、相邻折叠、共享边/顶点/T 接触不误报、退化、Float.MAX_VALUE/次正规坐标、双精度消去丢失的细长三角形精确 fallback、稀疏扫描与密集候选预算、跨 source/stored 定位、部分结果闭合 schema。另用独立 Sutherland–Hodgman 裁剪+面积 oracle 交叉验证 2,000 组确定性随机整数三角形，并验证平移/缩放/顶点排列不变性。

JAR 含 McpTriangleOverlap 及现有验证 Domain/schema；三语说明、scoped semantic ledger 与实施清单同步，owner 仍 14。没有修改 SDK/Runtime/ACP，没有重跑 ACP 测试，没有连接或修改真实模型；全部新增证据为内存几何和模拟 SDK/Domain。真实头部语义/rig 算法、连续性/视觉 QA、宿主 Undo/Redo/回滚和保存重开仍缺，A9/A10/A11 及自动建模目标保持未完成。


## 单轴 Rotation rig 只读规划切片

新增 `turboism.rig.rotation.plan`（生产目录 41 工具，资源/模板/提示词保持 15/2/8）。单对象、单普通参数 GUID、显式 REPLACE_ANGLE_FROM_DEFAULT 与 nativeAnglePerParameterUnit，生成闭合 keyforms.write 请求；不执行写入，不消费或预占 operationId。仅完整独立的 2..65 key 网格、最多 64 非默认 form；复用 McpKeyformWritePlan.prepare 进行普通 writer 全部预检。默认角度作为参考，各目标角度一次计算为 float；保留各 form 的轴心、scale、reflection，以及 baseAngle/default/axes/cells/非目标 forms。原目标 angle 会被替换，必须审阅；重跑规划不重复叠加。

两次完整原生 authoring state/父级/文档历史核对；旧/跨会话/过期 snapshot 拒绝，native 变化即使 revision 未变也拒绝。READ/UI_THREAD，不声明 transactionEligible，不调用 setter/选中/pose/render。预览 128KiB，返回 write request 再由既有写工具重新动态准入、CAS、权限、事务和回读。语义映射、角度单位转换、XY、眼嘴、父子多对象协调、美术 QA 不在此域；不将单轴线性映射宣称为完整头部自动建模。

执行通过：

```text
./gradlew :plugins:mcp:spotlessJavaApply :plugins:mcp:test :plugins:mcp:spotlessJavaCheck :plugins:mcp:jar --no-daemon
```

最终 XML：**MCP 443 项，0 失败/错误/跳过**；新增 RotationRigDomain 7 项。覆盖预览闭合 schema、复用 writer 预检、默认 key 非首项、数值角度、非角度字段/默认/baseAngle/axes/cells 保留、重复规划稳定、最大 64 edits、超限、溢出/NaN、共享/重复 tuple/缺 key/缺默认/multi-axis、GUID 与显示 ID 区分、过期/跨会话、同 revision 几何变化、后置核对变化、父级/历史/文档变化和异常脱敏。只读代理对任何未声明调用直接失败，未借助 setter 生成计划。HTTP 目录数量 41 与有限覆盖账本新增 semantic row 同步；owner 数保持 14。格式检查及 JAR 打包通过。

首次局部编译发现 List.getFirst 不兼容本项目 Java 17，改为 get(0) 后专项及上述全量回归通过。未修改 SDK/Runtime/ACP，未重新运行 ACP 测试；未连接/启动 Cubism 或 harness，未进行真实模型变形、渲染、Undo/Redo/回滚、磁盘保存重开或官方素材保护验收。上述均为离线模拟证据，Milestone A 与持续目标尚未完成。

## 三类存储 form 的普通 writer 资格报告

三类 authoring meshes/warps/rotations.read 的不可变分页新增 `ordinary_write_eligibility` 行，位于大量几何行之前。每个 form 返回 formGuid、snapshotEligible、reason 与固定 hostWriteAdmission=UNPROVEN。拒绝原因区分 DEFAULT_FORM、SHARED_FORM、MORPH_REFERENCED_FORM、NO_ORDINARY_CELL，以及全局 MISSING_DEFAULT_KEY_OR_AMBIGUOUS_AXIS、DUPLICATE_CELL_TUPLE、MISSING_EXACT_DEFAULT_CELL、SHARED_OR_MORPH_DEFAULT_FORM。全局网格异常时全部 form 标拒绝，但原始 snapshot 仍能读取，不以读失败隐藏可供诊断的真实数据。

抽取并复用 McpKeyformWritePlan 的 Grid 构造/校验与单 form rejection，写接口仍保留完整 tuple、目标存在、重复 edits、点数/数值/字节预算和实际写入前 CAS。报告不进行 no-op setter、额外宿主探测、事务或求值。资格属于签发时的快照；沿用原分页规则，不每页重新读取原生几何。当前 SDK 没有独立普通 writer availability 查询，所以 snapshotEligible 只指网格身份规则，不证明 write selector/权限/模式/历史新鲜度/事务/payload 准入。没有将它映射为 host AVAILABLE，也未完成 ENV-01/A1 的全部动态准入要求。

执行通过：

```text
./gradlew :plugins:mcp:spotlessJavaApply :plugins:mcp:test :plugins:mcp:spotlessJavaCheck :plugins:mcp:jar --no-daemon
```

最终 XML：**MCP 449 项，0 失败/错误/跳过**。新增 OrdinaryEligibility 6 项；三类 StoredAuthoring 分页测试补充资格行断言。覆盖默认/独立/无普通 cell、共享目标/共享默认、缺默认 key/cell、重复 tuple、ArtMesh Morph/默认 Morph 引用、Warp 分类/闭合 schema、只读结果不可修改。遍历 27 种 Rotation 三 cell→三 form 的引用布局，对每个 cell 对照报告资格与普通 writer unchanged-form 预检；全部一致。原写入/规划/几何/HTTP 测试保持通过。工具总数仍 41，账本 owner 仍 14；仅更新三种存储读取的有限语义说明。格式检查、JAR 打包与 git diff --check 通过。

未修改 SDK/Runtime/ACP，未重跑 ACP，未启动/连接 Cubism 或 harness，未执行真实模型写入、渲染、Undo/Redo/回滚或保存重开；这些测试不替代实机验收。后续仍需 SDK/Runtime 的只读写准入投影、其他属性/Morph/render/export 的细分发现、建模算法和全部 A 门槛。

## SDK/Runtime 普通关键形 writer 基础设施观察 API

新增 `OrdinaryKeyformWriteSupport` DTO 与 Drawable/Deformer 的默认 `ordinaryKeyformWriteSupport()`；默认六字段保持 UNKNOWN/空版本，兼容未实现查询的旧对象。EditorObjectReadAccess 的 ArtMesh/Warp/Rotation 视图先核对当前对象，再只读查询既有 resolver 元数据，返回真实声明 hostVersion、reviewedHost、writerSelectors、historySelectors、transactionCoordinator。Rotation 的 writerSelectors 同时核对 stored writer 与其 source write guard。没有执行 setter、反射调用宿主成员、开启 Undo/事务、求值或刷新来探测可用性。

PermissionCheckedDrawable/Deformer 要求 model.read，独立查询 model.write 并投影 PRESENT/ABSENT；read 被拒绝时不调用 delegate，插件停用后的 retained handle 仍拒绝。写权限不会因为查询被授予。底层未知能力不因写权限存在而升级为已支持。

三类存储读取签发 ordinary_write_support 行，随不可变 snapshot 分页。对旧适配器显式 UnsupportedOperationException 返回未知观察，保留原几何读取；其他异常仍失败闭合。hostVersion 允许空串表示未知，其余字段仅 UNKNOWN/PRESENT/ABSENT。两行 parent/support 计入 100,000 行及 4MiB/分页预算。已知 ABSENT 不会被 JSON 化成 UNKNOWN；观察不授予写权，不替代各 form/default/Morph 规则、编辑模式、CAS、payload、实际事务 binding 和写入准入。后续分页不重新探测，逐 form hostWriteAdmission 仍 UNPROVEN。

用户进一步明确“允许增加API能力”；后续可以按目标增补 SDK/Runtime 类型化契约，不把原 docs-only 边界再次作为阻塞。真实模型操作仍未执行。

最终验证命令：

```text
./gradlew :sdk:spotlessJavaApply :runtime:spotlessJavaApply :plugins:mcp:spotlessJavaApply :sdk:test :runtime:test --tests '*EditorOrdinaryKeyformWriteSupportTest' --tests '*PermissionCheckedModelForwardingTest' --tests '*EditorObjectReadAccessTest' :plugins:mcp:test :sdk:spotlessJavaCheck :runtime:spotlessJavaCheck :plugins:mcp:spotlessJavaCheck :plugins:mcp:jar --no-daemon
```

最终 XML：**SDK 461 项（0 失败/错误，1 跳过）；Runtime 专项 86 项（0 失败/错误/跳过）；MCP 450 项（0 失败/错误/跳过）**。Runtime 非全量，仅包含列出的三个测试类及其参数化案例。新增 helper 测试逐一删除三类必要 writer aliases，验证缺 selector 拒绝；使用无法加载的 host 类作为 selector owner，证明查询不加载或调用这些成员。覆盖精确/未审版本/兼容绑定、独立历史/协调器信息、读/写权限撤销/插件停用，以及三类实际 Runtime 对象视图接线且无 Undo/dirty/update 副作用。SDK 精确 API 方法集与返回类型契约已同步。MCP 新增三类型 known-ABSENT/legacy-UNKNOWN 输出验证，并保留全部已有写入/CAS/分页回归。

实现期间先暴露旧 mock 不支持新查询、SDK 精确 API 方法集需同步，以及 Runtime fixture 本来已有事务协调器的预期差异；修正兼容处理和契约/fixture 预期后，上述最终命令全部通过。一次重跑的自动审批超时未执行；后续正常执行，未将超时误记为功能测试失败或增加批准要求。git diff --check 通过。

此为离线 SDK/mock Runtime/HTTP 证据，无真实 Cubism/harness/GPU、官方模型、实际几何写入、Undo/Redo/回滚、保存重开验收。完整 A1、Milestone A 与持续建模目标仍未完成；当前 API 只观察普通 writer 基础设施，其他细分能力与建模算法继续实施。

追加打包 `./gradlew :sdk:jar :runtime:jar :plugins:mcp:jar --no-daemon` 通过，并检查三份 JAR 分别含新 SDK DTO、Runtime 查询实现和 MCP 存储读取类。仅生成构建产物，未安装插件或替换运行中的宿主。

## Warp 锚点位移场与普通 XY 组合规划

新增只读 `turboism.rig.warp.plan`，生产目录为 **42 工具**，资源/模板/提示词保持 15/2/8。使用 warps.read 签发的会话快照，一/二个普通轴 GUID 必须完整显式覆盖已有轴；轴需有精确 default key 和双侧声明 key，grid 必须完整、独立、无重复 tuple/共享 form。端点取已声明 key 极值，不用参数范围端点或首 key 冒充。参数范围只用于校验 key 合法。支持同显示 ID 的不同 GUID 轴。

每轴提供负/正端点各 1..16 个锚点（controlPointIndex、dx、dy，索引沿 SDK row-major 顺序，位移是 native stored 坐标）。对非锚点以距离平方倒数归一化插值；精确坐标锚点直接使用指定位移，拒绝同侧重复索引/重合锚点位置，避免独立约束歧义。归一化使用最近距离平方/目标距离平方，避免极小距离倒数溢出；有限 float 坐标差平方保持在 double 范围。位移场和轴组合计算保持 double 到最终 native float，包含抵消场景，不先把位移舍入为 float。关键点相对 default 到本侧声明端点的比例缩放；XY 两场相加，未实现角点交互补偿。

REPLACE_CONTROLS_FROM_DEFAULT 从真实默认存储控制点重建非默认目标，替换已有目标 form 控制点，不反复累加；保留默认形、rows/columns/quadTransform、axes/cells 和无引用 form。最多 65 cells/64 edits、4096 计划控制点、240KiB 返回结果；生成 edits 后复用 ordinary writer 纯预检，预算超限不返回部分可执行请求。超长 form GUID 导致输出超字节预算时返回 BUDGET_EXCEEDED。实际提交仍是独立 keyforms.write，再检查写权限、动态能力、完整 CAS、事务与回读。

规划前后核对完整 native snapshot/source 身份、父级与 document/model/history。跨会话、过期、同 revision 的 native 变化、规划后变化和父级/历史/目标切换均失败闭合。READ/UI_THREAD、transactionEligible=false；不调用 setter、pose/selection/render 或事务，也不预占写 operationId。

最终执行通过：

```text
./gradlew :plugins:mcp:spotlessJavaApply :plugins:mcp:test :plugins:mcp:spotlessJavaCheck :plugins:mcp:jar --no-daemon
```

最终 XML：**MCP 463 项，0 失败/错误/跳过**。本轮新增 WarpRigPlan 8 项、WarpRigDomain 5 项。覆盖精确端点/逆距离权重、中间 key 比例、非零默认/非首 key、XY 九宫格加性组合、重复规划稳定、输入轴重排、默认/grid/quad/axes/cells/未引用 form 保留、重复显示 ID 的 GUID 区分、稀疏/共享/重复 tuple、锚点索引/非有限/重合/错误 GUID、计划点预算、最终 float 溢出、极端 float 距离、double 抵消保精度、闭合输入输出 schema、身份/历史/父级/过期/会话与前后原生核对、异常脱敏及超长 GUID 字节预算。只读测试代理对未声明调用直接失败。HTTP 目录断言、三语文档与 semantic 覆盖行同步为 42 工具；SDK owner 仍有限 14。格式检查、MCP JAR 与 git diff --check 通过。

未修改 SDK/Runtime/ACP，未重跑其测试（前轮 SDK 461、Runtime 专项 86 为历史记录）。未启动/连接 Cubism、harness 或 GPU，没有真实变形、Undo/Redo/回滚、保存重开或官方模型原件保护证据。本算法为显式锚点传播和双轴加性基础，尚不包含头部透视/体积/五官/遮挡语义、XY 角点修正、Warp 折叠/求值连续性或美术/视觉验收。A 与持续建模目标保持未完成。


## Warp XY 显式角点残差修正（2026-10-25）

扩展原 rig.warp.plan 的可选 cornerCorrections，不增加工具。每象限用完整两轴 GUID 和 NEGATIVE/POSITIVE 指定方向，提供额外位移锚点；最多四个不同象限。与单轴场使用同一默认控制点上的逆距离平方传播，再乘两轴本侧归一化比例之积；默认轴上为零，端点角为一，中间存储 key 按比例缩放。该权重只定义计划中的存储 key，不声明 Cubism 求值插值行为。输出增加 cornerCorrectionCount，省略/空列表保留原行为。复用完整 CAS、闭合输入输出、预算和普通 writer 预检，不执行实际写入。

执行通过：

```text
./gradlew :plugins:mcp:spotlessJavaApply :plugins:mcp:test :plugins:mcp:spotlessJavaCheck :plugins:mcp:jar --no-daemon
```

最终 XML：**MCP 467 项，0 失败/错误/跳过，48 个测试套件**。WarpRigPlan 11 项、WarpRigDomain 6 项；相对上一完整回归新增 4 项测试，覆盖四象限独立、默认/单轴/未选象限保持、重复规划稳定、方向/轴重排、中间 key 的双比例乘积、重复象限/单轴/缺失或错误 GUID 拒绝、闭合 schema 和结果计数。HTTP 目录仍 42 工具，覆盖账本仍有限 14 owner。三语文档、语义账本和实施清单已同步；MCP JAR 打包、格式检查及 git diff --check 通过。

未修改或重跑 SDK/Runtime/ACP；其历史测试数不属于本轮新增证据。未启动/连接 Cubism、harness 或 GPU，未操作实际模型，也未验证真实 Undo/Redo/回滚、保存重开或原件哈希保护。该能力依赖调用者明确设计残差，不进行自动透视/体积/五官/遮挡推断；Warp 折叠、求值连续性与视觉质量仍未验收。Milestone A 与持续建模目标保持未完成。


## Warp 存储/拟议控制单元质量诊断（2026-10-25）

新增只读 `turboism.validation.warp`，生产目录 **43 工具**，资源/模板/提示词保持 15/2/8，SDK 覆盖账本仍有限 14 owner。使用 warps.read 签发的会话快照，明确普通参考 form 与 1..32 个目标 form，允许只读观察默认/共享普通形，拒绝未引用形。可选 proposedEdits 复用普通 writer 的完整 tuple、默认/共享资格和点数预检，只在内存中生成拟议快照；禁止修改参考或未选目标。无 setter、参数移动、选择、渲染或事务。

相邻 row-major 控制点组成双线性代理单元，四角 Jacobian 行列式通过精确二进制 float→BigDecimal 运算取得符号，避免 double 乘积抵消误判。正负混合标记局部折叠；角点 abs(det)/单元包围盒对角线平方不超过显式阈值标记退化；规则目标与规则参考比较方向，规则镜像参考本身合法。findings 定位 form/row/column/四点索引并返回最小角点归一化比值。参考或目标不规则时跳过方向比较，comparisonComplete=false。最多含参考 8192 单元样本、200 保留 findings、240KiB 输出；预算超限不返回部分 verdict，截断仍计数。分析前后完整 native/source/父级/document/model/history 一致性校验。

最终命令通过：

```text
./gradlew :plugins:mcp:spotlessJavaApply :plugins:mcp:test :plugins:mcp:spotlessJavaCheck :plugins:mcp:jar --no-daemon
```

最终 XML：**MCP 485 项，0 失败/错误/跳过，50 个测试套件**。相对前轮新增 WarpQuality 9 项、WarpValidationDomain 9 项。覆盖平移/尺度/镜像参考、bow-tie/凹单元、角退化/薄单元阈值/零尺寸、最小 subnormal/最大有限 float 与精确行列式、规则单元对照 200 组随机网格的 11×11 双线性 Jacobian 采样、row-major 多单元索引、默认/共享/未引用形、参考不规则与 findings 截断计数、计算/输出预算、闭合 schema、native 同 revision 变化/后读变化/父级/历史/模型切换、跨会话/过期和异常脱敏。实际 Warp 锚点+角点规划产生的八个 edits 可直接传入诊断，校验完整结果且原快照不变；只读代理拒绝任何意外 SDK 调用。

集成测试最初误将 snapshot 中保留的未引用 form 也选入 formGuids，按设计返回 UNAVAILABLE；测试改为从规划 edits 提取已选 form GUID 后完整回归通过。三语使用说明补充这一衔接规则，没有放宽接口边界。生产 HTTP 目录断言、语义账本、三语文档和实施清单同步；格式检查、JAR 打包/类目核对、git diff --check 均通过。

本轮未修改或重跑 SDK/Runtime/ACP，没有启动/连接 Cubism、harness 或 GPU，也没有真实模型写入/Undo/Redo/回滚/保存重开证据。双线性控制单元检查不等于 Cubism 原生曲面/quadTransform 求值；原生插值、全局重叠、父级变换、pose 连续性与美术/视觉质量明确未实现。无 findings 只覆盖已声明检查，不能作为模型验收。Milestone A 与完整半自动/全自动建模目标仍未完成。


## ArtMesh 原生顶点锚点规划与拟议几何诊断（2026-10-25）

新增只读 `turboism.rig.mesh.plan`，生产目录 **44 工具**，资源/模板/提示词保持 15/2/8，覆盖账本仍有限 14 owner。基于 meshes.read 签发的真实存储快照、一/二个普通轴 GUID、双侧顶点位移锚点和可选 XY 角点残差生成现有非默认形的 typed positions edits。顶点按存储顺序/坐标解释，不从源几何、图像或当前求值位置猜测。完整独立网格、精确 default key、双侧声明 key、65 cells/64 edits/4096 计划顶点/240KiB 结果等限制与既有 Warp 规划一致。

抽取共享 McpLandmarkRigPlan 处理精确锚点、逆距离平方归一化、默认到端点缩放、象限残差和数值/预算规则；两种 typed adapter 提供真实 axes/cells/forms 并分别调用原 ArtMesh/Warp ordinary writer 纯预检，不伪造 Warp 网格维度、不转换坐标或执行通用 SDK invoke。ArtMesh 保留 source positions/UV/triangleIndices、所有 drawOrder、默认形、axes/cells、无关 forms/Morph 记录；实际默认/共享/Morph 引用目标通过 ordinary writer 校验拒绝。重复规划从默认重建，不累积位移。前后核对完整 native/source/父级/document/model/history，无 setter、pose/selection、render 或 transaction。

validation.geometry 增加可选 positions-only proposedEdits 与输出 proposedEditCount。在内存构造拟议快照后执行既有几何检查，禁止覆盖显式参考或未选目标，拒绝非法 tuple/字段/点数/资格；原 source/UV/indices/drawOrder 保留，宿主原状态前后核对。可直接消费 mesh planner 的 edits，formGuids 从实际 edits 提取。既有几何预算、截断/INCOMPLETE、交叠检查和未实施的求值/视觉检查语义保持。

最终执行通过：

```text
./gradlew :plugins:mcp:spotlessJavaApply :plugins:mcp:test :plugins:mcp:spotlessJavaCheck :plugins:mcp:jar --no-daemon
```

最终 XML：**MCP 498 项，0 失败/错误/跳过，52 个测试套件**。新增 MeshRigPlan 4 项、MeshRigDomain 6 项；GeometryValidationDomain 新增 3 项（现 12 项）。覆盖实际默认位置与独立 source 坐标、source/UV/indices/绘制顺序/default/未引用 form 保留、XY 角点精确值/轴重排/重复规划、default/target Morph 引用拒绝与无关 Morph 保留、稀疏/共享/锚点索引/点数预算、typed/闭合 schema、native/父级/历史/会话/过期/前后状态与异常脱敏、超长 GUID 输出预算。拟议顶点退化被诊断且 live 快照不变；参考/未选目标/错误 tuple/非 positions 字段拒绝；实际八形 mesh 规划输出可直接诊断，所有 SDK 意外写调用由只读代理拒绝。原 Warp 端点/角点/数值/预算及 Warp 拟议诊断回归全部通过。

HTTP 目录、语义账本、三语说明和实施清单同步为 44 工具；格式检查、MCP JAR 打包/共享与 mesh planner 类目核对、git diff --check 通过。未修改或重跑 SDK/Runtime/ACP，没有启动/连接 Cubism、harness 或 GPU，没有真实模型写入/渲染、Undo/Redo/回滚或保存重开证据。该算法为显式顶点锚点传播，不处理语义分层/头部透视/体积/眼嘴/遮挡、不证明原生插值连续性或视觉质量。实际提交仍需 fresh CAS、权限与宿主准入。Milestone A 和持续建模目标保持未完成。


## 原生像素局部裁剪与 pose 来源坐标（2026-10-25）

扩展 render.capture 与 render.pose_grid 的可选 crop={x,y,width,height}，不增加工具（仍 44），资源/模板/提示词 15/2/8，覆盖账本仍有限 14 owner。输入 width/height 是完整原生 renderPixels raster；只编码 crop 矩形的精确像素，不重采样、不放大、不改变宿主渲染。输出 PNG/帧 width/height 是裁剪尺寸，新增 renderWidth/renderHeight/crop，来源像素=结果像素+crop 原点；无 crop 时记录完整画布。保留左上原点/x 右/y 下说明和 modelToPixelTransform=null，不伪造 model/source/stored→pixel 变换。

闭合 crop schema、有限整数、正尺寸与完整边界在调用 renderer 前验证；pose 扫描在参数写入/事务之前验证，整轮使用同一区域。每帧结果保留来源坐标与当前 pose/state，包括部分失败证据、恢复后保留结果和 release 后元数据。operationId 请求签名包含 crop，重放/result 读取既有图像；不同 crop 拒绝冲突。原有单帧/累计 native 像素和时间上限保持，不能以小裁剪申请超预算完整 raster；PNG 字节预算按实际结果计算。

最终执行通过：

```text
./gradlew :plugins:mcp:spotlessJavaApply :plugins:mcp:test :plugins:mcp:spotlessJavaCheck :plugins:mcp:jar --no-daemon
```

最终 XML：**MCP 504 项，0 失败/错误/跳过，52 个测试套件**。RenderDomain 新增 3 项（现 10）、PoseGrid 新增 3 项（现 20）。使用非均匀随机 ARGB 原生 raster 验证非零偏移、跨行 stride 和裁剪 PNG 每像素精确匹配，核对实际 PNG SHA-256/尺寸/字节数、未知 model transform 和无裁剪全幅映射。覆盖负数/小数/空尺寸/额外字段/越界、裁剪前不渲染、stale pose 丢弃图像、full native 维数/总像素预算不绕过、九姿态裁剪帧附件映射与 baseline 恢复、result/replay 不重复渲染、crop 修改冲突、渲染失败保留首帧局部证据并回滚/核对恢复。既有取消/恢复/图像预算/几何/写入/HTTP 回归全部通过。

三语说明、有限语义覆盖行和实施清单同步；格式检查、MCP JAR 打包/内含 Crop DTO 核对、git diff --check 均通过。本轮未修改或重跑 SDK/Runtime/ACP，没有启动/连接 Cubism、harness 或 GPU，没有实际模型操作、视觉模型消费/ACP 图片显示、Undo/保存重开新增实机证据。裁剪提高局部观察便利性而非渲染分辨率，不实现原生 zoom、语义 ROI 或视觉质量算法。逐存储 form opacity/color API 仍缺独立契约，未用当前 pose setter 冒充；Milestone A 和持续建模目标保持未完成。


## 只读目录采集器重建与 OPEN 依赖核对（2026-10-25）

原整理工作区没有原会话 collect_live_catalog.py/test 文件；本轮重新实现脚本和离线测试，不将其称为历史脚本恢复。补齐 A0 的可执行发现工具：可信本机 stdio 描述符（直接 command/args 或 mcpServers 包装）显式选择，argv 启动桥接，不使用 shell、不接受 HTTP 配置或导出凭据。没有配置返回 NO_STDIO_CONFIG，不自动扫描用户目录或读取 mcp.token/原始 connection JSON。

方法闭合为 initialize、initialized 通知、tools/list、resources/list、resources/templates/list、prompts/list、唯一 editor-commands 资源读取。传输在发送前拒绝 tools/call/prompts/get/任意其他资源，收到 server 请求不执行/批准/回应。目录完整分页，检测重复 cursor/条目、条目/页/字节预算和协议/关联 ID 错误；完整成功前不写输出。报告保留时间/协议/公开身份/数量/schema 哈希/direct 命令 IDs/typedContracts 哈希，不保留配置、环境、描述、初始化原文、原始 schema、stdout/stderr transcript、Bearer 或 session header。单行 8MiB、累计 32MiB、单目录 64 页/5000 项、报告 4MiB；失败不覆盖已有历史报告，输出不能覆盖配置；默认请求超时 15 秒，最多 60 秒。只清理本次启动的桥接子进程，未退出或启动 Cubism。

执行通过：

```text
python3 -W error::ResourceWarning -m unittest discover -s validation/acp-auto-modeling-capability-audit -p 'test_*.py' -v
```

**14 项离线测试全部通过**。包含独立 Python 子进程 stdio 假桥接，核对实际发送的完整方法顺序、唯一资源 URI、成功文件内容最小化、秘密 sentinel 不出现在报告/CLI 输出中；初始化 -32000 只发 initialize、无新成功文件/旧报告保持；断流/无效 JSON/超行、超时子进程终止、恶意 server 请求拒绝、分页重复/预算、配置/输出冲突，以及含敏感 argv 的 subprocess 异常脱敏。测试没有 Cubism 或网络连接。67 个本地文档链接核对存在，git diff --check 通过；本目录不存在 live-mcp-catalog.json，未编造成功实时目录。

同时核对 Runtime VerifiedTypedEditorCommandOperations：execute(ResolvedEditorFileCommand) 只派发 SAVE_AS，OPEN 因首次打开行为未经完整验证而显式 unavailable。当前已审 selector 未提供可直接复用的 OPEN 顺序，不能仅凭枚举范围宣称可打开文档；本轮没有用菜单/任意路径/反射绕过准入，没有新增 OPEN 的功能交付声明。后续仍需精确宿主行为、READ grant 和新旧文档身份/就绪契约，才能完成真实保存重开。

本轮只修改验证脚本/测试/审计文档，未改生产 SDK/Runtime/MCP/ACP，也未重跑它们的测试；前轮 MCP 504 为历史记录。本轮没有真实 stdio 描述符、宿主连接、模型打开/保存/修改、GPU/harness 或官方模型副本验收。A0 仍缺真实目录和 Hiyori 来源/许可/原件哈希；Milestone A 与持续建模目标未完成。


## ACP 工具图片来源标注与 Pi 协议协商（2026-10-25）

ACP 工具图片沿既有 listener/controller/ImageEvent/Swing 附件通路增加有界来源标签，保留原 MIME、像素字节和 byteSize。只解析同次工具更新中首个 typed text block 的有界 JSON；单张 capture 必须唯一对应 image，pose_grid 必须通过无重复且有效的 imageContentIndex 对应图片/内嵌图片资源。缺失、跨更新、歧义或超预算元数据退回工具标签，不推测旧 pose。标签展示工具报告的 capture/document/model/historyRevision、pose、尺寸和有效 crop 来源；标量先按现有 ACP 规则脱敏，再清除控制字符并截断。标签不是独立新鲜度或视觉质量证明，不改变普通 Agent 消息图片。

JS 回归发现 Pi 固定使用 2024-11-05，而生产 MCP 接受的版本为 2025-11-25、2025-06-18、2025-03-26。桥接现优先提出 2025-11-25，保留已实现的旧版本协商；验证 initialize 返回版本并在 HTTP 后续请求/通知使用协商结果。初始化不提前发送版本头，配置中的不同大小写旧版本头不能覆盖协商结果；未知版本在目录发现/工具注册前明确拒绝。此问题由源码和严格假服务回归确认，没有作为本轮实际 Cubism 握手证据。

执行通过：

```text
./gradlew :plugins:acp:spotlessJavaApply :plugins:acp:test :plugins:acp:spotlessJavaCheck :plugins:acp:jar --no-daemon
node plugins/acp/src/test/js/pi-mcp-extension.test.mjs
```

最终 XML：**ACP 215 项，0 失败/错误，3 跳过，19 个测试套件**。本轮开始时同工作区 ACP 基线为 208 项、0 失败/错误、3 跳过；新增 6 项标签单测、1 项 ACP client 集成测试，并增强原有 CODEX/CLAUDE/PI 图片 transport 案例。覆盖重排 pose 图片关联、歧义索引/跨更新/缺失元数据、内嵌资源、整数/crop/大小预算、控制字符/截断和 Bearer 脱敏、原 PNG/预览字节不变及 Swing 文档可见标签，避免将 base64 作为聊天正文。跳过的是 opencode、devin CLI ACP 握手及 native real CLI smoke，不能称为这些真实 Agent 已验收。

Node 脚本断言通过：stdio 旧版本协商、HTTP 严格协商版本头（含大小写冲突配置）、分片 UTF-8 SSE、未知协商版本在发现前拒绝，以及既有权限/图片转发/资源发现分页读取/二进制抑制/验证/取消回归。沙箱内 Node 子进程未实际执行脚本且以 0 退出，最小独立子进程复现后以获准的沙箱外执行完成假服务验证；该环境现象不归为产品失败。Gradle 一次自动审批超时未执行，重试后最终构建成功。JAR 核对包含 AcpToolImageLabel.class 和更新后的 native/pi-mcp-extension.mjs；三语 ACP 说明、审计 README、实施清单与 harness-reference 已同步。

本轮生产代码仅涉及 ACP，没有新增 SDK/Runtime/MCP 变更或重跑其套件；MCP 504 与目录采集器 14 项仍为前轮记录。未启动/连接 Cubism、真实 harness、GPU 或可见窗口，没有模型编辑/Undo/Redo/回滚/保存重开、真实模型 vision 消费或窗口显示新增证据。工具来源标签的 Swing 文档测试不等同于实际 ACP 窗口验收；协议假服务通过也不代表全部宿主版本通过。Milestone A 与持续半自动/全自动建模目标仍未完成。


## ArtMesh 存储 form 透明度观察与 CAS（2026-10-25）

新增 SDK ArtMeshAuthoringSnapshot.Form.opacity（Optional<Float>），原三参数构造器保留且表示未知。Runtime 在原存储读准入后独立核对已有 drawable-form.opacity selector；只读实际存储 form，缺少 selector 时为未知，已准入但非有限数时整次失败。原始 Morph opacity 不钳制、不转换为普通绝对值。MCP meshes.read 的 mesh_form 行新增必返 opacity 数值/null，沿同一不可变分页快照；工具仍 44，资源/模板/提示词 15/2/8，有限覆盖账本仍 14 owner。

完整 snapshot 相等性包含已观察 opacity，旧状态与未知→已知变化也能使 MCP CAS 失效。ArtMesh ordinary positions/drawOrder/combined、Morph positions/drawOrder 的预期快照重建保留新字段，MCP landmark plan/拟议几何诊断复用该预检。未新增 opacity setter，也未使用当前 pose 的 getOpacity/setOpacity。SDK 单 form handle 原有语义是每次重读；本轮不将它改成完整 expected-snapshot CAS。批次 writer/MCP 的 expected-snapshot 比较仍在实际写边界执行。

先完成针对性验证：

```text
./gradlew :sdk:spotlessJavaApply :runtime:spotlessJavaApply :plugins:mcp:spotlessJavaApply :sdk:test --tests '*ArtMeshAuthoringSnapshotTest' :runtime:test --tests '*EditorArtMesh*' :plugins:mcp:test --no-daemon
```

通过结果：SDK snapshot 4 项，Runtime ArtMesh 224 项（6 套件），MCP 507 项（52 套件），均 0 失败/错误/跳过。本轮新增 SDK 1、Runtime 3、MCP 3 项；覆盖未知不等于 1、有限性/null 验证、原始负 Morph 值、不可变分离值、selector 缺失、准入后非法值不回退、旧三参数构造器、positions/order 及普通/Morph 联合事务保留、同 history revision 的 opacity-only 与未知→已知 CAS 拒绝，拒绝前没有事务或 setter。旧 transaction 假 resolver 曾将 opacity alias 接到另一种假 native form；修正测试夹具接线，未放宽生产类型检查。另将最初误要求单 handle 拒绝值变化的断言改为批次 expected-snapshot CAS 断言，遵守原 API 语义。

随后全量 SDK/Runtime 回归在已有 ScreenshotCaptureHostOperationsTest 的 capturesCurrentProjectWithDebounceAndPopupSuppression 卡住。只读线程栈确认 Test worker 在 finally 的 Swing dispose 等待，而 EDT 在 sun.awt.screencast.ScreencastHelper.getRGBPixelsImpl → Robot.createScreenCapture 的本机原生截屏调用阻塞；不是新透明度调用栈。仅终止本次已识别的测试 worker，原全量运行未通过，不挪用其旧 XML 作成功证据。该案例创建的是测试窗口，不是 Cubism。未修改截图生产代码或测试跳过条件。

用临时 init 脚本固定离线 headless 环境后完整执行：

```groovy
// /tmp/turboism-opacity-headless.gradle（临时验证配置，不是仓库构建设置）
allprojects {
    tasks.withType(Test).configureEach {
        systemProperty 'java.awt.headless', 'true'
    }
}
```

```text
./gradlew -I /tmp/turboism-opacity-headless.gradle :sdk:test :runtime:test :plugins:mcp:test :sdk:spotlessJavaCheck :runtime:spotlessJavaCheck :plugins:mcp:spotlessJavaCheck :plugins:acp:compileJava :plugins:mcp:jar :runtime:jar --no-daemon
```

**BUILD SUCCESSFUL，3m34s**。最终 XML：SDK **462 项，0 失败/错误，1 跳过，117 套件**；Runtime **6108 项，0 失败/错误，43 跳过，694 套件**；MCP **507 项，0 失败/错误，1 跳过，52 套件**。跳过范围为 SDK TurboismProgressDialogTest；Runtime SwingUserFileGrantSourceDisplay/CoreWindows/EdtDispatch/SwingEditSessionDialogPrimitives/ScreenshotCaptureHostOperations/VerifiedRecentPreviewPopupHostOperations/DeclarativeSettingsRenderer/RuntimeHostDialogAutomationService 的显示相关案例；MCP McpConnectionWindowTest。Runtime test 任务自身另有已存在的 legacy/artifact 专用测试排除配置，不能将本结果扩张为这些专用验收。前面的非 headless MCP 507 项则没有跳过。

SDK JAR 的 javap 确认三参数/四参数构造器同时存在以及 Optional opacity accessor；Runtime/MCP JAR 核对包含新观察/投影。三语 MCP 说明、有限语义账本、审计 README 和实施清单同步；git diff --check 通过。本轮仅对 ACP 做编译兼容性验证，未重跑其测试；前轮 ACP 215/3 跳过和 Node 桥接仍为历史记录，采集器 14 项也未重跑。

未启动或连接 Cubism、打开/编辑真实模型、调用原生模型 renderer/GPU、保存或重开文件，没有真实 harness/vision/ACP 窗口验收。新增观察与 CAS 保护不等于逐 form 透明度创作能力；默认/共享/Morph 外观 writer、stored colors、Warp/Rotation opacity、完整建模算法和官方模型副本实机门槛仍未完成。Milestone A 与持续半自动/全自动建模目标保持未完成。


## 普通 ArtMesh 存储透明度与稀疏联合值写入（2026-10-25）

新增 SDK ArtMeshKeyformValues（coordinates/formGuid + Optional positions/drawOrder/opacity，至少一项）与 Drawable.setOrdinaryKeyformValues。旧 provider 的 default 方法拒绝，不通过当前 pose setter 模拟。权限封装复用 SET_DRAWABLE_KEYFORMS_BATCH 事件并要求 model.write；SessionDrawable 转发不可变输入并执行原 generation guard。Runtime 扩展原 ArtMesh batch 内部预检和预期快照，在同一个原生 KEYFORM_EDIT contribution 写入所有指定 fields。

新 OPACITY_REQUIRED_ALIASES 是原几何 writer aliases 加已有 opacity 读写 selector；原 REQUIRED_ALIASES 不变。未知/支持域外普通 opacity、默认/共享/Morph/重复/错 tuple/form/旧 expected 状态在 setter 前拒绝。opacity 输入限有限 0..1，drawOrder 限 0..1000 整数。省略字段保留 expected，所有目标顶点（含保留位置）计入 SDK 4096-form/2000000-vertex 与 MCP 64-form/16384-point 预算；source/UV/indices/未选 form 保持。opacity 额外准入在实际提交检查；已有 ordinary_write_support.writerSelectors 仍是几何基础设施观察，不能当作 opacity 已准入。

MCP 既有 keyforms.write schema 扩展为七种非空 positions/drawOrder/opacity 组合；有 opacity 的批次走新 typed SDK writer，无 opacity 路由保留。结果 hash、完整回读、跨对象事务和 operationId/result 保留/重放沿用既有协议。恢复只在新 opacity writer 已准入时恢复受支持的独立非默认普通 opacity；原几何恢复不会获得透明度 setter。默认/共享/Morph/未知或支持域外 opacity 不作为补偿修复目标，无法证明完整恢复不伪报成功。

针对性命令：

```text
./gradlew -I /tmp/turboism-opacity-headless.gradle :sdk:spotlessJavaApply :runtime:spotlessJavaApply :plugins:mcp:spotlessJavaApply :sdk:test :runtime:test --tests '*EditorArtMesh*' --tests '*PermissionCheckedModelForwardingTest' --tests '*DynamicCubismModelAccessTest' :plugins:mcp:test --no-daemon
```

通过结果：SDK **464 项，0 失败/错误，1 跳过**；Runtime 目标套件 **280 项，0 失败/错误/跳过，8 套件**；MCP **510 项，0 失败/错误，1 跳过，52 套件**。新增 SDK DTO/default-provider 2 项、Runtime 原生事务 4 项、MCP plan/domain 3 项，并增强既有权限/动态会话测试。覆盖防御复制、空 edit/非法范围/null、旧 provider 无 delegate 调用、版本注解；七种字段组合、未知 opacity、闭合字段、完整 default/source 保留、同 operationId 重放不重复 setter、跨对象第二写失败恢复 opacity；原生 contribution 单次快照、等值无新 Undo、opacity setter 部分成功后抛异常、nativeAbort 开/关两种回滚、多 contribution 中间快照逆序恢复、缺 opacity selector 仍可写几何、默认/共享/Morph/重复/错 GUID/旧快照在 Undo contribution 前拒绝；权限撤销和会话失效拒绝。NativeUndo 测试替身补齐 opacity 捕获/恢复以匹配原生 KEYFORM_EDIT 已有属性范围，没有新增生产 Undo 格式。

首轮失败是旧测试仍将 opacity 当作未知字段，以及新 domain 测试误期待异常外抛（实际协议返回 UNAVAILABLE envelope）；将闭合字段案例改为仍未开放的 color，核对实际 envelope 后通过。没有放宽 form 保护或改变原结果语义。

全量命令使用上节记录的临时 headless init 脚本（不修改仓库 Gradle 设置）：

```text
./gradlew -I /tmp/turboism-opacity-headless.gradle :sdk:test :runtime:test :plugins:mcp:test :sdk:spotlessJavaCheck :runtime:spotlessJavaCheck :plugins:mcp:spotlessJavaCheck :plugins:acp:compileJava :plugins:mcp:jar :runtime:jar --no-daemon
```

**BUILD SUCCESSFUL，3m32s**。最终全量 XML：SDK **464 项，0 失败/错误，1 跳过，118 套件**；Runtime **6112 项，0 失败/错误，43 跳过，694 套件**；MCP **510 项，0 失败/错误，1 跳过，52 套件**。headless 跳过范围及 Runtime test 既有专用 artifact/legacy 排除边界与上节相同，不能称为桌面/全部专用验收通过。SDK/Runtime/MCP 格式检查、ACP 编译兼容、MCP/Runtime JAR 打包通过；SDK JAR javap 确认新 default 方法和既有方法同时存在，SDK DTO 与 Runtime writer 类存在。

最后复核修正 MCP 原始数值范围检查：先以 BigDecimal 核对 0..1，再转换 SDK float，避免 -1e-999 或 1.000000000000000000001 被 double 舍入成边界合法值；NaN/无限/字符串仍拒绝。变更仅影响 MCP 预检，随后重新执行受影响全量套件和打包：

```text
./gradlew -I /tmp/turboism-opacity-headless.gradle :plugins:mcp:spotlessJavaApply :plugins:mcp:test :plugins:mcp:spotlessJavaCheck :plugins:mcp:jar --no-daemon
```

**BUILD SUCCESSFUL，33s**；最终 MCP 仍 **510 项，0 失败/错误，1 headless 显示测试跳过**。一次自动审批超时未启动命令，按工具指示重试一次后成功。三语说明、有限语义覆盖账本、实施清单和审计 README 已同步；git diff --check 通过。仍为 44 工具、15 资源、2 模板、8 提示词、有限 14 owner。本轮未重跑 ACP 或目录采集器测试；前轮记录不作为本轮新增 writer 的验收证据。

未启动/连接 Cubism、打开/修改真实模型、调用真实模型 renderer/GPU、保存重开或接入真实外部 harness。上述原生事务测试只替换 native members/history 为内存对象，不证明真实宿主 Undo/Redo 或图像效果。尚缺精确宿主官方模型副本上的实际写入、Undo/Redo/回滚、渲染、保存重开；颜色、Warp/Rotation opacity、Morph opacity writer、眼嘴/头部美术算法和完整交付仍未完成。Milestone A 与持续半自动/全自动建模目标保持未完成。


## 每操作 selector 观察与动态会话类型保留（2026-10-25）

OrdinaryKeyformWriteSupport 新增不可变 Map<Operation,Check> operationSelectors、selectorsFor 查询和闭合六种操作身份。原六参数构造器仍保留并返回空 map；缺失项为 UNKNOWN，不根据 aggregate writerSelectors 猜测 PRESENT/ABSENT。withWritePermission 保留原操作 map。Runtime 按现有 writer 实际 alias 集合查询纯元数据，ArtMesh positions/drawOrders/values 与 valuesWithOpacity 分开；Warp/Rotation 各报告其 typed writer，Rotation 继续检查 source-writer guard。查询不解析或调用 native 成员，不执行 setter/事务/no-op probe。

核对发现 SessionDrawable 和三类 SessionDeformer view 漏转发 ordinaryKeyformWriteSupport，底层已知观察会被 SDK default UNKNOWN 遮掉，并且这条默认方法没有代际保护。本轮补齐四种视图的 guarded 转发。同时确认 SessionDeformers.find/all 原先总是构造普通 SessionDeformer，擦除 delegate 的实际 Warp/Rotation 类型；当前 MCP 三类存储读、keyform verify/apply 与 rig/validation 等通用集合路径依赖这些 instanceof/cast，因而存在真实生产调用链缺口。现保留实际 typed session wrapper，并保留 plain fallback、宿主顺序、不可变集合、unwrap/remove/applyToChildren 和代际 guard。通用集合的 typed wrapper 携带原 modelId/modelGeneration，继续使用原 appearance 路由；原专用集合/创建路径缺少这些身份时保持既有 unavailable 外观语义，不开放任意原生访问。

MCP 存储读 ordinary_write_support 行新增闭合 operationSelectors schema。查询结果随同会话不可变 snapshot 分页缓存，前后状态不是同一时间的实时 admission。透明度 selector 缺失可与几何 PRESENT 并存；缺失条目/旧 provider 空 map 为 UNKNOWN。selector PRESENT 仍与 reviewedHost、history、coordinator、permission、逐 form 资格、CAS/编辑模式/预算/实际事务分开；没有新增可写 capability 或绕过 setter 保护。工具仍 44，资源/模板/提示词 15/2/8，有限 SDK owner 14。

针对性执行通过：

```text
./gradlew -I /tmp/turboism-opacity-headless.gradle :sdk:spotlessJavaApply :runtime:spotlessJavaApply :plugins:mcp:spotlessJavaApply :sdk:test :runtime:test --tests '*EditorOrdinaryKeyformWriteSupportTest' --tests '*SessionOrdinaryKeyformWriteSupportTest' --tests '*SessionDeformerTypePreservationTest' --tests '*DynamicCubismModelAccessTest' --tests '*PermissionCheckedModelForwardingTest' --tests '*EditorObjectReadAccessTest' :plugins:mcp:test --no-daemon
```

SDK **466 项，0 失败/错误，1 跳过**；Runtime 目标套件 **115 项，0 失败/错误/跳过，6 套件**；MCP **511 项，0 失败/错误，1 跳过**。新增 SDK 2、Runtime 6、MCP 1 项。SDK 覆盖旧构造器、缺失项未知、防御性复制、只读 map、null 拒绝及权限装饰不丢失信息；Runtime selector 假 resolver 使用不可加载的 host 类名，证明观察只访问授权元数据，覆盖各类型操作集合、透明度读/写 selector 单独缺失、缺 geometry alias、reviewed host 分离。四种 session view 的严格代理只准 ordinary 查询，核对精确结果转发、断开/替换后零 delegate 调用。通用 Deformers 的 find/all 真实包装测试核对 Warp/Rotation 类型、存储快照精确转发、批次参数转发、顺序/不可变结果、plain fallback、raw delegate unwrap、失效后拒绝读取/写入/UI/remove。该批次写测试使用空 edits 与记录型 delegate，只证明包装转发，不冒称 native 模型写入/Undo。MCP 验证类型化操作观察、selector PRESENT 不授予权限、旧 provider 空 map、已签发 snapshot 不受随后查询结果变化影响及分页不重读宿主。

由于通用对象集合的类型修复影响多个既有调用方，又执行完整 headless 回归（临时 init 脚本定义见第 22 轮验证记录，不修改仓库 Gradle 设置）：

```text
./gradlew -I /tmp/turboism-opacity-headless.gradle :sdk:test :runtime:test :plugins:mcp:test :sdk:spotlessJavaCheck :runtime:spotlessJavaCheck :plugins:mcp:spotlessJavaCheck :plugins:acp:compileJava :plugins:mcp:jar :runtime:jar --no-daemon
```

**BUILD SUCCESSFUL，3m33s**。最终 XML：SDK **466 项，0 失败/错误，1 跳过，119 套件**；Runtime **6118 项，0 失败/错误，43 跳过，696 套件**；MCP **511 项，0 失败/错误，1 跳过，52 套件**。跳过仍为 SDK ProgressDialog、Runtime 显示/桌面自动化相关案例、MCP ConnectionWindow；Runtime test 已存在的 legacy/artifact 专用排除边界同前，不能称为这些专用验收通过。SDK/Runtime/MCP 格式检查、Runtime/MCP 打包及 ACP 编译兼容通过。SDK JAR javap 核对六/七参数构造器同时存在及新 accessor；Runtime JAR 包含 typed wrapper 分派，MCP JAR 包含 operationSelectors 投影。三语文档、有限覆盖行、实施清单与审计 README 同步，git diff --check 通过。

未启动/连接 Cubism、打开/编辑真实模型、调用实际模型 renderer/GPU、保存/重开或接入真实 Agent；没有新增实机目录、窗口/vision、Undo/Redo 或持久化证据。完整 MCP→权限→动态会话→原生宿主链还需实际官方模型副本验收；这里只对生产会话包装与独立 MCP 协议套件分别取证。本轮只验证 ACP 编译，未重跑 ACP/Node/目录采集器；不挪用前轮记录作为本轮实机证据。ENV-01、Milestone A 与持续半自动/全自动建模目标仍未完成。


## 多轴 Rotation 存储角度规划（2026-10-25）

扩展 McpRotationRigPlan、Domain 与 schema。新 axes 输入覆盖全部一至三普通轴，旧单轴输入保持且与新输入互斥。根 schema 保留 object 类型及全部 properties，分支闭合拒绝旧/新字段混用。完整笛卡尔网格由逐轴唯一/范围内键、精确默认、有效完整 tuple、唯一 tuple 和总基数共同验证；共享 form 一律拒绝。角度按快照轴顺序从全默认角度加权，最后转换 native float；省略无关 form，保留所有目标非角度字段。全部结果再经现有 ordinary writer 纯预检，Domain 前后原生/父级/历史核对未削弱。

保留 single_axis_rotation_plan 元数据和覆盖行，新增 multi_axis_rotation_plan 的只读语义行；版本从 ReviewedVersions 派生，没有新增硬编码版本表或原生 selector。工具 44、资源/模板/提示词 15/2/8、有限 owner 14 不变。三语 README 与审计/清单已同步。

新增 McpRotationRigPlanTest 6 项，现有 Domain 增加 1 项。纯规划测试使用一至三轴、重复显示 ID、倒序 cell/坐标和显式乱序轴；核对非零默认下每个组合 angle、每 form 的 origin/scale/reflection、baseAngle、axes/cells、默认与无关 form 不变，及重复规划不累加。拒绝缺轴/多轴/重复或未知 GUID、四轴、网格缺口/重复 tuple/共享默认与非默认 form、非法键、非有限 gain、native float 溢出和超过 65 cells。64 个 cells 的三轴组合与既有单轴 65 cells 预算边界通过。Domain 严格代理只允许读取快照/父级，验证新输入可生成普通 writer 接受的完整 tuple 请求，拒绝混合输入/缺轴/字符串 gain；保留旧单轴 stale/权限/会话/到期回归。

最初新增测试编译因 Prepared.after() 为 Object 缺少显式 Rotation 快照转换而失败，已修正；没有将该失败当作通过。随后针对性运行：

```text
./gradlew -I /tmp/turboism-opacity-headless.gradle :plugins:mcp:spotlessJavaApply :plugins:mcp:test --tests '*McpRotationRig*Test' --no-daemon
```

**BUILD SUCCESSFUL，19s**；Rotation Domain 8 项、纯规划 6 项，共 **14 项，0 失败/错误/跳过**。之后运行最终受影响模块全量验证：

```text
./gradlew -I /tmp/turboism-opacity-headless.gradle :plugins:mcp:test :plugins:mcp:spotlessJavaCheck :plugins:mcp:jar --no-daemon
```

**BUILD SUCCESSFUL，24s**；最终 XML **518 项，0 失败/错误，1 跳过，53 套件**。跳过为 McpConnectionWindowTest 的 headless UI 用例。包含生产目录/有限覆盖账本/协议集成回归、格式检查和 JAR 打包；git diff --check 通过。日志 /tmp/turboism-multi-axis-rotation-targeted.log 与 /tmp/turboism-multi-axis-rotation-full.log。本轮没有 SDK/Runtime/ACP 生产改动，未重跑这些模块或 Node/采集器测试。

未启动/连接 Cubism、读取/修改真实模型、渲染或保存重开，未接入真实 harness。假代理与纯规划不证明原生插值、XYZ 美术效果、Undo/Redo、回滚或保存持久化。真实官方副本、语义/视觉算法、完整准入及 Milestone A 验收仍缺，持续目标保持未完成。


## ArtMesh/Warp 三轴锚点与 pair/triple 残差（2026-10-25）

McpLandmarkRigPlan 接受一至三普通轴，仍要求输入覆盖全部实际 GUID。cornerCorrections 的 directions 从精确两轴扩展为 2..3 个不同已绑定轴；唯一 side-map 限 20，权重只乘所声明轴的本侧归一化比例。二轴 residual 跨未声明的第三轴保持，三轴 residual 在任一默认面为零。默认与单轴 forms 不受 residual 影响。两个 Domain 的 parser/schema 同步 axes≤3、directions 2..3、corrections≤20；输出 count 也闭合为 0..20。共享引擎的一/二轴算法、budget、前后状态验证和两类 ordinary writer 纯预检保留。无 selector/SDK/原生写域变更。

新增 8 项测试。McpLandmarkThreeAxisPlanTest 4 项包含三轴 64 cells（63 edits）、非零/不同默认、内侧 key、倒序 cell/坐标与乱序轴，逐 cell/逐点独立核算轴位移与二/三轴 residual 权重，测试 pair 在第三轴两侧与默认 key 均保持、triple 在默认面归零。ArtMesh/Warp 同源存储样本分别经实际 typed adapter 和 write-plan 纯预检，核对 source geometry/UV/indices、draw order/已知 opacity、Morph 记录、default/unused forms 与 Warp grid/quadTransform 保留，以及重复规划稳定。20 唯一残差一起输入时，每个端点恰好激活其三对轴象限和一个三轴卦限；单轴/双轴/三轴组合分别为 0/1/4 个活跃 residual。拒绝单轴/未知轴/四轴残差、重复 residual、缺轴、稀疏/共享网格、超过 65 cells、四普通轴，保留旧默认/Morph/预算与非法锚点测试。

两个 rig Domain 各增加 1 项，严格只读代理核对 axes 三项和全部 20 residuals 的 schema/解析/输出，26 edits 可经现有 typed ordinary write-plan 预检；第 21 个 residual 在 schema 前置拒绝。两个 validation Domain 各增加 1 项，接收三轴 planner 的全部 proposedEdits 并完成拟议诊断，原始 live snapshot 不变；只读代理仍只允许实际读取，不接受 setter/pose/render/transaction。ArtMesh source 样本是合成且独立的占位源，诊断 includeSource=false；这不是原素材质量或真实宿主视觉证据。

针对性最终运行：

```text
./gradlew -I /tmp/turboism-opacity-headless.gradle :plugins:mcp:spotlessJavaApply :plugins:mcp:test --tests '*McpLandmarkThreeAxisPlanTest' --tests '*McpMeshRig*Test' --tests '*McpWarpRig*Test' --tests '*McpGeometryValidationDomainTest' --tests '*McpWarpValidationDomainTest' --no-daemon
```

**BUILD SUCCESSFUL，18s；56 项，0 失败/错误/跳过，7 套件**。此前不含两个新增诊断案例的首轮专项也通过。最终受影响模块完整回归：

```text
./gradlew -I /tmp/turboism-opacity-headless.gradle :plugins:mcp:test :plugins:mcp:spotlessJavaCheck :plugins:mcp:jar --no-daemon
```

**BUILD SUCCESSFUL，27s；526 项，0 失败/错误，1 跳过，54 套件**。跳过为 McpConnectionWindowTest 的 headless UI 案例。生产目录/有限账本/协议集成回归、格式及 JAR 打包通过。三语 README、有限语义覆盖、审计与实施清单已同步；git diff --check 通过。日志 /tmp/turboism-three-axis-landmark-targeted.log 与 /tmp/turboism-three-axis-landmark-full.log。工具/资源/模板/提示词仍为 44/15/2/8、有限 owner 14。本轮未改 SDK/Runtime/ACP，也未重跑其测试、Node 或目录采集器。

未启动/连接 Cubism、读取/修改实际模型、执行真实渲染/Undo/Redo/保存重开或连接外部 Agent。存储模板与离线诊断不能证明实际求值插值、头 XYZ 的体积/透视、眼嘴/遮挡协调、美术质量、持久化或任意素材全自动建模。A 与持续半自动/全自动建模目标保持未完成。


## 保留 pose PNG 的局部像素 QA（2026-10-25）

新增 McpRasterValidation、McpRasterValidationSchemas，工具名 turboism.validation.raster，由现有 McpPoseGridDomain 注册为 READ/DIRECT/transactionEligible=false。闭合 color_fraction 与 frame_difference 两种显式检查：RGBA 字节闭区间，或预乘 RGB/alpha 最大通道差严格超过 tolerance；每项唯一 checkId、frameIndex、PNG 局部 ROI、fraction 范围，差异项引用明确参考帧。不接受图片内容、路径、URL、任意代码或 SDK 调用。

工具仅从 session-owned Job 取得终态、未释放元数据与图片的局部不可变副本，不读当前模型，不调用 render/context/参数/事务或 UI queue。并发 release 在已开始分析之后清理持久保留，不改变已取得的对应副本。检查前验证帧存在、坐标匹配、ROI 边界/样本与 PNG/像素预算；每个所用帧只解码一次，内存 PNG reader 不创建 ImageIO 磁盘缓存，先核对字节/hash与 header 尺寸。最大 32 checks/4194304 ROI samples/1048576 decoded pixels/3MiB PNG/240KiB report；finally 释放局部 raster，无新长期图像缓存。

报告包含显式 RGBA/tolerance/fraction 配置、exact counts、比例和逐项 passed，原始 BigDecimal 阈值与 count×分母精确比较后才决定 passed；返回比例的 double 舍入不改变判定。checksPassed 只覆盖显式检查。scanOutcome、parametersRestored、allRequestedPosesCaptured 保留独立语义，部分失败 scan 不冒充完整成功。来源标为 RETAINED_POSE_GRID，相关 frame 原 capture/pose/state/hash/crop 保留，imageContentIndex=null，不附图片/base64。不会声称历史帧是当前模型状态、自动检测语义或通过全部美术验收。

新增 McpRasterValidationTest 6 项：RGBA/alpha 闭区间与 ROI 计数、透明隐藏 RGB 不影响差异、预乘通道与 alpha 的严格 tolerance 边界、同帧差异零、精确十进制上下限（含 -1e-999、超过 1 及小于 1 的近边界）、部分扫描/恢复字段、重复 checkId/未知字段/缺帧/错坐标/非法颜色/越界 ROI、样本与解码像素超限在 decoder 前拒绝、PNG 长度/hash/实际尺寸/编码损坏拒绝。合成 PNG 来自本地内存，不是实机 renderer 证据。

现有 pose-grid Domain 新增 2 项，走实际工具注册/input/output schema/纯解码链。九帧合成原生渲染代理之后 forbidHost=true，任何 CubismFacade 查询立即 AssertionError；执行检查和跨会话/释放查询仍成功或按状态拒绝，参数写入、渲染、事务计数不变。集成输入实际 crop={x:1,y:1,width:1,height:1}，检查 ROI={x:0,y:0,width:1,height:1}，证明使用裁剪后 PNG 坐标并保留完整 crop 来源。验证 running/missing/released/cross-session/部分失败状态及拒绝 path 字段。生产 HTTP 目录更新到 45 并显式断言工具可发现；增加 retained_raster_checks 只读有限语义行，版本从既有 ReviewedVersions/render 来源派生，无新增 native selector/SDK owner。

最终专项运行：

```text
./gradlew -I /tmp/turboism-opacity-headless.gradle :plugins:mcp:spotlessJavaApply :plugins:mcp:test --tests '*McpRasterValidationTest' --tests '*McpPoseGridDomainTest' --tests '*McpSdkCoverageGuardTest' --no-daemon
```

**BUILD SUCCESSFUL，22s；31 项，0 失败/错误/跳过，3 套件**。随后完整 MCP 回归 534 项通过；补强裁剪后 ROI 集成断言后再执行最终批次：

```text
./gradlew -I /tmp/turboism-opacity-headless.gradle :plugins:mcp:spotlessJavaApply :plugins:mcp:test :plugins:mcp:spotlessJavaCheck :plugins:mcp:jar --no-daemon
```

**BUILD SUCCESSFUL，27s；534 项，0 失败/错误，1 跳过，55 套件**。跳过为 McpConnectionWindowTest headless 显示案例；编译仍有既有 PluginPaths.logsDir() deprecated/removal 提示。格式、生产目录/版本/覆盖账本/HTTP 协议集成回归与 JAR 打包通过。三语 README、审计/清单同步，并修正早先渲染段落仍称 crop/pose-grid 未实现的过时说明；git diff --check 通过。日志 /tmp/turboism-raster-validation-targeted.log、/tmp/turboism-raster-validation-full.log。目录 45/15/2/8，有限 owner 14。本轮未改 SDK/Runtime/ACP，未重跑其测试、Node 或目录采集器，不挪用旧记录作为新接口验收。

未启动/连接 Cubism，未读取/修改真实模型，未执行真实 GPU/Undo/Redo/保存重开，未接入外部视觉模型/真实 Swing 窗口。需要由调用方提供有效 ROI、颜色/差异阈值及期望关系；不能凭单个空白、白色或差异统计推断闭眼/口腔/头体积的语义正确性。自动语义定位、综合美术 QA、精确宿主和持久化验收仍缺，A 与持续目标保持未完成。


## Warp 显式头深度场与透视模板（2026-10-25）

新增纯 McpWarpHeadVolumePlan；McpWarpRigDomain/Schemas 扩展互斥 PROJECT_HEAD_VOLUME_FROM_DEFAULT 模式。Profile 是原生存储父空间中的图像定义椭圆深度场，不是世界坐标下真正三维椭球/完整 3D 重建；参数 GUID→角分量/增益全由调用方明确提供。先通过真实 Warp snapshot 和全零位移场调用既有 grid/ordinary writer 纯预检，复用完整默认/共享/独立/点数预算检查，仅取其实际 target 身份/完整 tuple；没有造假 Warp snapshot 或原生调用。真正投影参考从实际 stored default form 读取，零角度直接保留原 Point2。

每 cell 参数 delta 转 degree，YAW/PITCH/ROLL 唯一对应且按 +Y/+X/+Z 顺序投影；每目标从默认重建，最终一次转 native float。profile 构造拒绝非有限、非正半径、负 depth、相机不在深度前方、初始空间小于 d 的 1% 或比例下溢；零 gain 也不能绕过 profile 准入。逐点拒绝旋转后距离非有限或小于 d 的 1%、非有限/float 溢出，先除相机距离比避免 d*x 的不必要溢出。所有普通网格/默认/共享/Morph 观察限制、65 cells/64 edits/4096 points/240KiB 以及前后完整 native/parent/history 核对保持。

新模式不接受 cornerCorrections/landmark 字段，原模式不接受 headVolume/分量轴字段，root object 暴露具名 union properties 但使用闭合 oneOf 分支校验；结果 policy 闭合扩展，volume 的 cornerCorrectionCount=0。增加 head_volume_warp_plan 的 READ/NOT_APPLICABLE 有限语义行，版本仍从既有 ReviewedVersions 派生；工具数 45、资源/模板/提示词 15/2/8、有限 SDK owner 14 不变，不新增 selector/SDK/原生写域。三语 README 增补完整公式、坐标/符号、替换行为与 caller 验证要求。

新增 McpWarpHeadVolumePlanTest 4 项：中心点 yaw±30 与 pitch±30 的解析透视值；零深度平面 roll90 及 yaw→roll 组合；三轴 yaw30/pitch30/roll90 从 (0,0,2) 到 (sqrt(3)/2,1,1.5)，以相机距离 10、分母 8.5 独立核对投影；含非零默认与内侧 key 的 64 cells/63 edits，原 grid/quad/axes/cells/source/default/unused 不变，输入轴乱序和重复规划稳定，零 gain 目标从默认精确复制。拒绝半径/depth/camera/NaN/无限 gain、超角度、近相机 profile（含零 gain）、旋转后穿过 near plane、native float 溢出、缺轴/重复 component、稀疏/共享与超 cells。

Warp rig Domain 新增 1 项：严格只读代理，三轴 profile 返回 26 个 writer 接受的 edits，snapshot 读取前后各一次，拒绝模式字段混用，并保留 parent stale 拒绝。Warp validation Domain 新增 1 项：真实 planner 输出全部 proposedEdits 进入现有几何诊断，analysisComplete=true 且 live snapshot 不变；不以没有 findings 认定美术接受。

首轮针对性命令：

```text
./gradlew -I /tmp/turboism-opacity-headless.gradle :plugins:mcp:spotlessJavaApply :plugins:mcp:test --tests '*McpWarpHeadVolumePlanTest' --tests '*McpWarpRig*Test' --tests '*McpWarpValidationDomainTest' --no-daemon
```

**BUILD SUCCESSFUL，22s；34 项，0 失败/错误/跳过，4 套件**。随后完整 MCP 回归通过；复核补强 profile 近相机的构造期拒绝、有限分母及三轴顺序解析断言后，最终执行：

```text
./gradlew -I /tmp/turboism-opacity-headless.gradle :plugins:mcp:spotlessJavaApply :plugins:mcp:test :plugins:mcp:spotlessJavaCheck :plugins:mcp:jar --no-daemon
```

**BUILD SUCCESSFUL，30s；540 项，0 失败/错误，1 跳过，56 套件**。跳过为 McpConnectionWindowTest 的 headless UI 案例。格式、生产工具/版本/有限账本/HTTP 协议回归和 JAR 打包通过；git diff --check 通过。日志 /tmp/turboism-head-volume-targeted.log、/tmp/turboism-head-volume-full.log。本轮未改 SDK/Runtime/ACP，未重跑其测试、Node 或采集器。

没有启动/连接 Cubism、读取/修改实际模型、执行真实 renderer/GPU、Undo/Redo、保存重开或外部 Agent 验收。该模板不推断语义、重建遮挡或证明子对象真实 Warp 插值；椭圆外按零深度平面投影，并非保持外侧点不动，形变与边缘连续性必须结合拟议几何/渲染审阅。还需要官方副本上的体积/轮廓、眼嘴/遮挡协调、视觉模型与持久化证据。A 与持续目标仍未完成。

## 29. 跨对象普通关键形只读预览（2026-10-25）

范围：MCP keyforms.preview Domain/schema/纯差异汇总、生产目录、有限语义账本及三语说明。提取 write 的解析/快照/完整 CAS/聚合预算预检为共享 prepare；write 保持结果重放先于快照解析、独立 SDK 事务、回读和结果保留语义。preview 在差异计算后重验全部状态，拒绝已 admitted ID，不查询事务服务、不保留结果或占用 ID。六字段 authoring state schema 与 context.binding 对齐，不复用带 poseHash 的 render schema。

5 项新增 Domain 案例覆盖：三对象精确 counts/位移/角度/轴心/缩放/反射/changedFields、预测哈希与随后同 ID 写入一致；稳定重复预览及 result NOT_FOUND；no-op、order-only 零点预算、opacity 保留点预算；history/parent/doc/model/native/第二次核验变化及跨会话；默认、空 tuple、跨类型字段、重复对象、未知 opacity；shared/Morph/五分钟 TTL 过期。闭合 schema 非法输入在分派前抛错，其余失败返回明确 error。fixture 单独统计 authoringTransactions 查询（不仅 execute），所有预览路径为零；无 setter。既有共享 planner 的大数据、非法数值、form 支持域及写入事务/重放/回滚回归全量运行。

验证命令（全部离线）：

```sh
./gradlew -I /tmp/turboism-opacity-headless.gradle :plugins:mcp:spotlessJavaApply :plugins:mcp:test --tests '*McpKeyformWriteDomainTest' --tests '*McpHttpServerIntegrationTest' --tests '*McpSdkCoverageGuardTest'
./gradlew -I /tmp/turboism-opacity-headless.gradle :plugins:mcp:spotlessJavaApply :plugins:mcp:test :plugins:mcp:spotlessJavaCheck :plugins:mcp:jar
git diff --check
```

专项 65 项通过；其后新增 shared/Morph/TTL 案例，完整 XML **545 项，0 failures/errors，1 skipped，56 suites**；BUILD SUCCESSFUL，13s。跳过为既有 headless UI 案例。格式和打包通过，保留既有 deprecated logsDir 提示；测试夹具存在 unchecked 编译提示。首次专项暴露测试目录断言编辑导致的 Map.of 参数错误及两处测试预期（order-only 预算、schema 在分派前拒绝），修正后重跑通过，未放宽生产 schema。日志 /tmp/turboism-keyform-preview-targeted.log、/tmp/turboism-keyform-preview-full.log。

生产目录 46/15/2/8、有限 owner 14，新增 ordinary_stored_preview READ/NOT_APPLICABLE 语义行。未改 SDK/Runtime/ACP，未重跑其测试或借用历史结果；未启动/连接 Cubism、未改实际模型、未采集新 live catalog。预览不验证 live writer/权限/Undo，不构成几何/美术质量或 Milestone A 验收。

## 30. 模型图像绑定与原生输入观察（2026-10-25）

新增 McpImageBindingDomain / McpImageBindingSchemas / McpImageBindingDomainTest。复用 ModelTextures.textureInput / modelImageGroups / bindModelImage 和共享 authoring snapshot cache，不新增 native selector/SDK API。闭合 read/bind/result schema，无 path/base64/grant/overwrite/unlock 等输入。完整 CAS 同时包含 native authoring DTO、source GUID、父级、自身 lock、文档/历史绑定、既有 textureInput/modelGeneration 与全部有界候选元数据。验证前后均重读 native state，避免在候选/输入观察阶段发生无历史 revision 的几何变化。hash 仅为资源目标身份摘要，native geometry 与 generation 另有完整比较，未混用历史 revision 取代实际比较。

read 的依赖 token 复用原 cache 的 TTL/会话/项数/总字节边界，保留 native DTO 的预算也计入新项，不延长 parent 的 TTL；不返回任意文件路径或像素。空 textureInput 必须由 typed SDK 真正观察为 empty，其他资源/未知/外来引用失败闭合。写入为单目标独立 SDK transaction，same-image no-op 跳过 setter；不同 image/atlas、source locked、parent deformer 拒绝。Runtime 仍独立检查祖先锁/extension/权限/精确版本/Undo 和 model member ownership。回调内检查 handle model/image/mesh 与 observed generation，再做 full state readback；错误结果/意外几何/候选变更或 native 抛错触发恢复。MCP 不为 legacy texture.write 声明 transactionEligible。

12 项新增 Domain 案例覆盖：初始读取与精确绑定收据/回读、无宿主重放和 ID 冲突；same-image NO_CHANGE；其他图像/图集/锁/父级拒绝；source 删后重建、几何/父部件/父变形器/锁/输入/native generation/候选成员或尺寸/历史 generation/revision/文档/model/manager 的 stale；事务入口竞态零 setter；native 部分修改抛错和错误 image/model/mesh/generation handle、错误实际输入、意外几何/候选、recovery failure；提交后读取失权/响应丢失的保留语义；unavailable transaction 和 native 权限失败；读取期间历史和第二次 input 观察期间几何变化、外来 mesh 观察及拒绝时脱敏；跨会话/过期/错误 kind token；未发现图像/闭合字段/重复 ID/1025 候选/240KiB 超限；满 256 结果不淘汰或重写；RUNNING direct 查询与取消前后边界。fixture forbidHost 证明终态重放与 result 不查 facade/model/texture/context。

生产 HTTP 目录显式断言 49 工具含三项新入口；Bearer 写权限、无需 Bearer 的只读 retained result 测试同步。SDK 覆盖账本已有 textureInput 与 bindModelImage 行改为实际 READ/TRANSACTION_CONTROL，保留 Runtime authority 与非实机证据说明；registerImageAndBind 仍 EXCLUDED，owner 仍 14。metadata model read/write permission reasons 更新，不增加权限。三语 README 同步。

命令与结果：

```sh
./gradlew -I /tmp/turboism-opacity-headless.gradle :plugins:mcp:spotlessJavaApply :plugins:mcp:test --tests '*McpImageBindingDomainTest' --tests '*McpHttpServerIntegrationTest' --tests '*McpSdkCoverageGuardTest'
./gradlew -I /tmp/turboism-opacity-headless.gradle :plugins:mcp:spotlessJavaApply :plugins:mcp:test :plugins:mcp:spotlessJavaCheck :plugins:mcp:jar
git diff --check
```

专项通过；最终 BUILD SUCCESSFUL，14s。XML **557 tests / 0 failures / 0 errors / 1 skipped，57 suites**。跳过为已有 headless UI 测试，编译保留既有 logsDir deprecated/removal 提示。首次专项在测试 Registration 包名错误处编译失败，修正到 sdk.plugin.Registration 后通过，未放宽 API/测试。日志 /tmp/turboism-image-binding-targeted.log、/tmp/turboism-image-binding-full.log。SDK/Runtime/ACP 未改动和重测，没有连接或启动 Cubism、没有真实模型改动或目录采集；此证据不证明像素/GPU 显示、真实 Undo/Redo/保存重开、完整素材导入或 Milestone A。

## 31. 授权 PNG 的异步原生注册与绑定（2026-10-25）

新增 McpImageRegistrationDomain / McpImageRegistrationSchemas / McpImageRegistrationDomainTest；在现有 Binding Domain 复用 authoring/input/parent/history/catalog CAS，新增 registration preflight/transaction helpers 与 groupCount。异步 worker 先 UI preflight 后 consume 同会话 READ grant（过滤器严格等于 png），读完成后关闭 lease，再检查完整读取/header/显式 bytes/pixels/可选 SHA。AuthorizedImageContent.ofDimensionsBounded 保留不可变副本，SHA 从其 copyBytes 计算，确保不是 mutable SDK 原数组的后续状态。最大 16MiB PNG、16777216 pixels、每边 8192；实际 decoder 仅由 Runtime 调用，不使用 MCP ImageIO 冒充 native 验收。

提交重新 get 快照以核对 TTL/会话，旧 native authoring/父级/lock/input/候选 metadata/history、raw image metadata 做前/内/后完整比较。原生 registerImageAndBind 在 owned SDK transaction 中贡献；根据 SDK handle 建立精确预计新增 raw/group/model image 及输入，name/尺寸/资源/model/mesh/generation 与 typed readback 一致，旧 UV/geometry/forms/metadata 保持。偏差回滚，recovery failed 为未知。事务回执和 metadata before/expected-after hash 在结果保留；post-commit 读取失权为 APPLIED_WITH_READBACK_WARNING，响应丢失为 OUTCOME_UNKNOWN，禁止自动重试。

单全局 read/worker/host 槽，最多 128 session operation identities，不淘汰。queued/pending/read/validation/host 状态可 direct 查询。任务不传播一次 RPC Abort 为宿主取消；显式 cancel 在最终事务准入前检查，之后记录意图并等待实际原生结果。pending native file read 不 cancel，直到真正完成才释放授权 handle。dropSession/close 阻止未准入写入并清理 late completion；准入后仍完成真实核对，关闭会话导致 result missing 不能证明无改动。worker.shutdown 采用 drain，不 interrupt admitted UI/native work。结果不持有 image bytes 或 host handles。服务 lifecycle 已接入清理，读写权限 reason 更新，无新增 permission。

14 项新增 Domain 测试：2×2 真 PNG（仅离线 ImageIO 生成）通过真实注册 schema/cache/grant/任务/helper 及代理 SDK 事务，核对三种新资源与绑定、哈希/收据/geometry 保持、零宿主 replay；锁/父级/已绑定/过期预检零 READ；读后 history/source/document/model/TTL stale 零 register；前缀/空/坏签名/像素/单维/bytes/hash 预算拒绝与正确 pinned SHA；WRITE/跨会话/过期/撤销/混合扩展/缺失 grant；queued/read/validation/final-admission cancel 与准入后 cancel；部分原生失败、错误 image/model/mesh/generation handle 或实际输入、意外候选/raw metadata 变动回滚、recovery failure；post-commit permission failure 与响应丢失；会话关闭/服务停止在准入前后语义；worker/native-read 准入/异常/null/SDK error 与清理；busy/replay/跨会话/128 保留；闭合 path/base64/预算/hash/空白 name/session 输入。对事务/资源创建实际代理计数，而非只看成功标记。

registerImageAndBind 有限 owner 账本行从 excluded 改为新 MCP_AUTHORIZED_JOB（EXTERNAL_SIDE_EFFECT、RUNTIME_VERIFIED、transactionEligible=false），明确 grant READ 不可 Undo；不把 SDK owner 全部判为已覆盖。owner 14。生产目录 52/15/2/8，首次跨 tools/list 每页 50：HTTP 真实断言首 50 + nextCursor 后 2 并检查所有新入口；Bearer start/cancel、只读 result 测试同步。未修改 page limit。Pi 原桥接已实现工具分页，本次仅扩展 JS 测试为 52 项分页发现与第二页 result 只读执行/cancel 需批准，保留资源分页、typed image、binary suppression、HTTP fragmented SSE 与 protocol negotiation 组。三语 README 同步。

验证：

```sh
./gradlew -I /tmp/turboism-opacity-headless.gradle :plugins:mcp:spotlessJavaApply :plugins:mcp:test --tests '*McpImageRegistrationDomainTest' --tests '*McpImageBindingDomainTest' --tests '*McpHttpServerIntegrationTest' --tests '*McpSdkCoverageGuardTest'
node plugins/acp/src/test/js/pi-mcp-extension.test.mjs
./gradlew -I /tmp/turboism-opacity-headless.gradle :plugins:mcp:spotlessJavaApply :plugins:mcp:test :plugins:mcp:spotlessJavaCheck :plugins:mcp:jar
git diff --check
```

专项通过；追加 final-admission/session-close 案例后完整 **571 tests、0 failures/errors、1 skipped、58 suites，BUILD SUCCESSFUL，13s**。跳过为既有 headless UI；既有 logsDir deprecated 编译提示。Node 三组执行通过（stdio 分页/资源/图像/权限等、HTTP SSE/协商、拒绝不支持协议），不是真实 Pi CLI 或视觉模型测试。首次编译发现 catch 参数重名，修正后专项揭示测试只统计首 50 页和 guard 分类枚举未同步；实际增加 nextCursor 遍历并扩展 authorized-job 分类约束后通过，未放宽 schema/CAS/分页预算。日志 /tmp/turboism-image-registration-targeted.log、/tmp/turboism-image-registration-full.log、/tmp/turboism-image-registration-pi.log。

没有 SDK/Runtime/ACP Java 新改动；未重跑其 Java 套件，未挪用历史计数。未启动/连接 Cubism，没有真实 PNG/PSD/模型写入、GPU 采样/实机 Undo/Redo/保存重开或 live catalog 新证据。完整自动建模、A/B/C 验收仍未达成，后续拓扑/结构/atlas/export 及受控宿主探针继续待做。

## 32. 完整矩形图集快照、规划和原生 Undo 任务（2026-10-25）

新增 McpAtlasDomain、McpAtlasLayout、McpAtlasSchemas，接入生产装配、session DELETE/idle expiry 和 server close；新增21项图集测试。scope/history/native 完整 CAS、原始 issued target 留存、五分钟/16快照和字节/图像/页/像素/轮廓预算，闭合 schema。纯 shelf plan 无写入/operation reservation，完整矩形 apply 是独立 native Undo job，不能加入 SDK authoring transaction，不伪造回执/回滚或 binding/GPU 验收。语义布局投影按 textureId 排序并展开隐式 uniform pageSizes，仍保留精确坐标/页名/尺寸与原始 native constraints/items/outlines。部分失败/失联以结果核对为准，不盲目重试。

离线案例包括：确定性 shelf/边距/间隔/多页/空模型/边界恰好适配；放不下、页/像素/outline预算；漏图/未知图/旋转/错尺寸/间隔不足/边缘越界/空页/缺页名/隐式写入尺寸；闭合字段/小数/NaN/Infinity；outline真实 hash 与临时 invocation 拒绝；不同 native target wrapper 下保留原始 target；read/plan零原生写；完整应用/readback/NO_CHANGE；history/generation/标签页/items/constraints/TTL/session stale；缺省与规范化页尺寸/placement顺序；所有原生 failure enum 的 NOT_APPLIED/OUTCOME_UNKNOWN 分类与异常信息不泄漏；响应丢失/回读失败/错布局/意外 metadata；queued/final-boundary/admitted cancellation；session drop/close前后；busy/worker rejection/256identity/16snapshot eviction；无宿主 result/replay/id冲突；原生outline/currentPlan变化即使history不变也拒绝。测试使用离线 SDK代理，不是 Runtime 或 GPU 实测。

输出校验器遇到 oneOf(nullable enum) 的 null 时原先 List.of.contains(null) 会抛异常；改为 Objects.equals 逐项比较，新增独立回归同时验证 null/有效 enum/非法 enum。没有放宽 enum/schema。首轮另发现 scale JSON 使用 double1.0 而 const1 严格比较不匹配，投影改为规范整数1；SDK scale仍精确拒绝非1。覆盖账本新增 semantic row 身份修正后，HTTP真实分页、Bearer apply/cancel 与无需Bearer的 result 全部通过。有限owner增至15，未覆盖static sentinel或整个SDK。

命令：

```sh
./gradlew -I /tmp/turboism-opacity-headless.gradle :plugins:mcp:spotlessJavaApply :plugins:mcp:test --tests '*McpAtlas*' --tests '*McpSdkCoverageGuardTest' --tests '*McpHttpServerIntegrationTest' --tests '*McpToolRegistryTest'
node plugins/acp/src/test/js/pi-mcp-extension.test.mjs
./gradlew -I /tmp/turboism-opacity-headless.gradle :plugins:mcp:spotlessJavaApply :plugins:mcp:test :plugins:mcp:spotlessJavaCheck :plugins:mcp:jar
git diff --check
```

专项通过；最终完整 **593 tests / 0 failures / 0 errors / 1 skipped / 60 suites，BUILD SUCCESSFUL，15s**。跳过为既有 headless UI；保留既有 logsDir removal/unchecked 编译提示。Pi 三组通过（57项工具分页、第二页 atlas result无需批准/cancel需批准、typed image/资源、HTTP SSE/protocol协商等）。日志 `/tmp/turboism-atlas-targeted.log`、`/tmp/turboism-atlas-full.log`、`/tmp/turboism-atlas-pi.log`。三语 README 同步57工具及安全/预算/结果域；SDK owner账本15，原生Undo作业单独分类并强制非transactionEligible。

本节没有SDK/Runtime/ACP Java新改动，未重跑或挪用其历史Java测试计数。未连接或启动Cubism，没有真实图集写入、ArtMesh采样、Undo/Redo、保存重开或目录采集成功证据。ATLAS-02仍有polygon/append等未实现域；完整自动建模和A/B/C验收仍未通过。

最终 Runtime 复核确认原生 atlasId 用页名与控制分隔符拼接，并非跨布局不变的GUID。wire 改为 atlasIdHash；提交前完整 native identity CAS 不变；成功后允许请求中的页名/页数改变所产生的预期 identity 变化，仍严格比较请求布局与源图像等。新增多页应用/页名改变/多页再读取测试，最终上述593项为含此修复的全套结果。

## 33. 原生 MOC 包与有界授权文件写入（2026-10-25）

新增 McpMocExportDomain/McpMocExportSchemas/McpMocPackage 及17项MCP测试；SDK新增1项兼容default/接口反射测试，Runtime新增3项实际临时文件/授权测试。生产工具目录61/15/2/8；有限SDK覆盖owner仍15。新增MCP_AUTHORIZED_EXPORT_JOB分类，强制EXTERNAL_SIDE_EFFECT、NOT_APPLICABLE Undo、非transactionEligible与5.3版本域；UserFileAccessService新default方法明确逐项分类，exportMoc/Core inspection为scope有限semantic row。

plan无原生导出/字节检查/文件I/O或grant消费，归一化全部原生float设置，保留会话/source/history/预算5分钟、最多16计划。execute排队后UI重新核对历史和图集metadata/order，官方MOC返回必须settings/纹理索引尺寸一致；Core inspection要求V5_3/CONSISTENT。离线SDK代理中的64-byte MOC3仅用于结构与调用/拒绝路径测试，Core代理不证明真实MOC可加载。纯打包测试真实解ZIP并验证所有固定路径、相对model3引用、PNG逐像素ARGB/native texture order、大小/哈希和重复输入包；不使用人工base64结果冒充原生导出。manifest自身通过最终ZIP SHA覆盖，artifact列表是其他payload。

有界输出覆盖MOC/总纹理像素/ZIP预算；源核对仅完整history/atlas metadata，不声称全模型几何不可变。SDK本身的较大原生分配仍早于MCP post-return限制。字节、像素、native handle、用户路径不进入结果/重放缓存；只保留源/设置/文件摘要。14项Domain测试覆盖：pure plan与导出/Core/文件调用精确计数；WRITE ZIP grant消费/实际pending lease；state/document/model/generation/manager/atlas/TTL/session stale；空图集/Core不可用/检查不可用/像素超限；错settings/纹理数量和尺寸/MOC budget/版本/一致性/未知/响应异常/导出后状态改变；READ/缺失/混合扩展/过期/撤销授权拒绝；queued/export/final-write-boundary cancel；编码失败/编码后history或TTL改变；session drop/close在准入前后；pending stage不cancel、不提前释放槽/lease；SDKwritten=false与null/异常/提交响应丢失分开；零宿主result/replay/ID冲突；worker rejection/计划淘汰/128identity上限；closed schema/路径/inline bytes/预算/覆盖策略；旧SDK NoSuchMethodError失败闭合，不挂在WRITING。

Runtime新writer调用既有authorize与writeBytesNow，不改变旧8MiB writer。实际临时文件测试用9MiB内容越过旧限制、核对提交后调用者修改数组不改变已提交快照、错误budget不改变目标、128MiB声明预算可写小文件、超过128MiB拒绝；READ/foreign/ONE_OPERATION/撤销句柄失败闭合；failure sink operationId正确、无路径/文件名泄漏。新增SDK default只在≤8MiB预算委托，超预算或大预算旧backend明确拒绝，无任意路径入口。真实原子替换针对测试临时文件，未涉及真实Cubism模型/用户模型文件。

验证命令：

```sh
./gradlew -I /tmp/turboism-opacity-headless.gradle :sdk:spotlessJavaApply :runtime:spotlessJavaApply :sdk:test --tests '*UserFileAccessContractTest' :runtime:test --tests '*RuntimeUserFileAccessServiceTest'
./gradlew -I /tmp/turboism-opacity-headless.gradle :plugins:mcp:spotlessJavaApply :plugins:mcp:test --tests '*McpMoc*' --tests '*McpSdkCoverageGuardTest' --tests '*McpHttpServerIntegrationTest'
node plugins/acp/src/test/js/pi-mcp-extension.test.mjs
./gradlew -I /tmp/turboism-opacity-headless.gradle :sdk:spotlessJavaApply :runtime:spotlessJavaApply :plugins:mcp:spotlessJavaApply :sdk:test :runtime:test :plugins:mcp:test :sdk:spotlessJavaCheck :runtime:spotlessJavaCheck :plugins:mcp:spotlessJavaCheck :plugins:acp:compileJava :plugins:mcp:jar :runtime:jar
./gradlew -I /tmp/turboism-opacity-headless.gradle :plugins:mcp:spotlessJavaApply :plugins:mcp:test :plugins:mcp:spotlessJavaCheck :plugins:mcp:jar generateSdkApiReport
./gradlew updateSdkApiCurrent checkSdkApiCurrent
git diff --check
```

SDK/Runtime专项与MCP专项通过。首次full编译发现新Runtime测试没有静态导入assertNull，改用既有assertEquals(null,...)后全量通过，**BUILD SUCCESSFUL，3m22s**；之后追加旧SDK缺方法回归并完成最终MCP全量，**BUILD SUCCESSFUL，15s**。最终XML：SDK467/0失败/0错误/1跳过/119 suites；Runtime6121/0失败/0错误/43跳过/696 suites；MCP610/0失败/0错误/1跳过/62 suites。跳过沿用既有显示/桌面环境边界；既有legacy/artifact专用排除仍不在本次runtime:test范围。三模块spotless、Runtime/MCP JAR、ACP compile通过。Pi三组通过，mock目录61工具（首50+第二页11）、61+3资源辅助=64 registrations；第二页MOC result不提示批准、execute需批准，保留typed image/资源/SSE/协商回归。没有重跑ACP Java套件，不挪用过去计数。

SDK public current快照检查通过：69行新增、1行旧构造器描述被带generic签名的新描述替换；旧三参构造器descriptor仍存在，并非删除旧入口。除新bounded writer外，报告包括此前ordinary opacity/支持域类型与既有ProgressDialog公开表面，已按源码核对后更新当前开发快照。没有修改历史exact baselines、版本锚或发布声明。SDK author guide与三语MCP说明同步新API/预算/覆盖语义/原生分配边界。

日志：/tmp/turboism-bounded-write-targeted.log、/tmp/turboism-moc-targeted.log、/tmp/turboism-moc-full.log、/tmp/turboism-moc-final.log、/tmp/turboism-moc-api.log、/tmp/turboism-moc-pi.log。git diff --check通过。未启动/连接Cubism、未导出真实MOC/纹理或启动播放器，没有新的GPU、Undo/Redo、保存重开、真实harness视觉/权限窗口或live catalog成功证据。静态model3及manifest不构成完整physics/motion/expression/pose角色交付；A/B/C与总体半自动/全自动建模目标仍未完成。

产物复核：实际带 worktree 后缀的 runtime-0.67.2-SNAPSHOT-mighty-seal.jar 经 javap 包含 writeBytesAtomicBounded；mcp-0.67.2-SNAPSHOT-mighty-seal.jar 的 jar tf 包含 McpMocExportDomain/Schemas/Package。未安装到运行宿主。

## 34. 参数文件夹混合顺序、索引移动与 Combined 修正（2026-10-25）

本节新增 SDK `ParameterGroupChild`（GROUP/PARAMETER + 原样 ID）、`ParameterGroup.orderedChildren()` 与三参 `ParameterGroups.moveParameter`；两项 default 对旧 provider 失败闭合，不伪造顺序或忽略索引。Editor 使用现存 verified children/类型/ID 成员返回不可变混合列表，未知项拒绝；indexed 与原 append 都按移除后的目标列表计数，提前拒绝非法索引，same-slot 不生成 Undo。PermissionChecked/Session 全部对应入口已接线；native 模型和 exact host 准入规则沿用，未新增通用调用或反射接口。生产 MCP 仍61工具/15资源/2模板/8提示词，有限 owner 仍15；本轮未声称 MCP PAR-02 完成。

通过本地 5.3.02 参考 JAR 的只读 javap 核对 `ModelHandler` 移除后插入与 group.add 语义；未加载/执行 Cubism。索引移动测试的旧 fake 仅追加且不移除原父列表，现改为按索引移除/插入并带精确前父/前位置 Undo；相应原 smoke 的根计数改为实际移动后的计数。测试检查混合顺序、不可变快照、same-slot、边界/负索引、跨组、legacy append、未知 entry、权限撤销和 session invalidation。原生事务专项使用显式 edit-begin/group-undo 测试证据，检查单 root/no nested edit、Undo/Redo 与注入异常后恢复。初次 fixture 没有 group-undo 准入，生产路径正确拒绝并回滚；仅修正 fixture，使它显式实现/授权真实回滚成员，没有放宽生产 guard。该 fake 不代表实际宿主 Undo 验收。

新增 Combined 回归在修复前失败：ParamB.combineWith(ParamA) 的旧实现把 A 放在 C 后，使用了移除 A 前的 B 索引。现按 partnerIndex 对插入点修正，双向 partner/flag、顺序和 Undo/Redo 均通过。没有扩大任意 Combined/Morph 支持域，也未开放文件夹自身重排。

验证命令：

```sh
./gradlew -I /tmp/turboism-opacity-headless.gradle :sdk:spotlessJavaApply :runtime:spotlessJavaApply :sdk:test --tests '*ParameterGroupOrderContractTest' --tests '*CompleteCubismModelReadApiContractTest' :runtime:test --tests '*EditorParameterStructureAccessTest' --tests '*EditorBackedCubismCombinedWriteTest' --tests '*PermissionCheckedModelForwardingTest' --tests '*DynamicCubismModelAccessTest'
./gradlew -I /tmp/turboism-opacity-headless.gradle :sdk:test :runtime:test :plugins:mcp:test :sdk:spotlessJavaCheck :runtime:spotlessJavaCheck :plugins:mcp:spotlessJavaCheck :runtime:jar :plugins:mcp:jar :plugins:acp:compileJava generateSdkApiReport
./gradlew updateSdkApiCurrent checkSdkApiCurrent
git diff --check
```

定向 Runtime 78 tests 通过，SDK 契约通过。最终全量 **BUILD SUCCESSFUL，3m24s**：SDK **469 tests/0失败/0错误/1跳过/120 suites**，Runtime **6127/0失败/0错误/43跳过/696 suites**，MCP **610/0失败/0错误/1跳过/62 suites**。跳过仍为既有无显示/桌面环境边界，沿用既有 legacy/artifact 专用排除，不扩张为整个仓库验收。三个模块 spotless、Runtime/MCP JAR、ACP Java 编译通过；没有 ACP Java/Node 新改动，未重跑其测试。生成报告相对第33节当前开发快照新增18行、删除0行；update/check current通过，没有改历史 exact baseline/版本锚或发布声明。Runtime JAR 经 javap 包含 orderedChildren；没有安装到宿主。git diff --check通过。日志 `/tmp/turboism-parameter-order-targeted.log`、`/tmp/turboism-combined-order-red.log`、`/tmp/turboism-parameter-order-green.log`、`/tmp/turboism-parameter-order-full.log`、`/tmp/turboism-parameter-order-api.log`。没有启动/连接 Cubism、修改实际模型或新增 live catalog 证据；没有真实 harness/视觉、原生保存重开、GPU、原模型哈希或播放器验收。SDK API 存在不能替代半自动/全自动建模交付。


## 35. MCP 参数结构计划与原生事务调用（2026-10-25）

新增 `McpParameterStructure`（只读投影/纯模拟）、`McpParameterStructureDomain`、`McpParameterStructureSchemas` 与21项Domain测试，装配4个参数结构工具。目录65/15/2/8；覆盖账本新增ParameterGroup/ParameterGroups两个owner，共17，并逐一分类其15个public instance方法：真实混合读取/结构写入明确对应入口，UI appearance/不带index的append显式excluded；Parameter Combined四个方法改为实际读/事务控制路径。新writer继续非transactionEligible，严格守卫闭合schema、版本/Undo分类与owner集合，不扩大legacy事务资格。

完整结构投影含混合children、名称/父关系、模型参数顺序/定义/nullable Combined双向关系。read两次核对实际字段与完整history；plan使用独立JSON副本顺序模拟，支持新建ref后移动/重命名/配对，预览精确删除清单。当前仅唯一显示ID和文档/模型/历史+结构CAS，没有新增参数/文件夹GUID读取；不声称能识别所有无history变动的同ID同字段重建。group自身reparent/reorder仍缺verified入口，未提供generic invoke或原生路径。

写入前和事务内重查全部来源，逐步调用typed SDK并核对预测结构/新建实际ID，原生exact-host与group-undo仍准入。NO_CHANGE跳过setter，错误回读在事务内要求回滚；post-commit读取失败为APPLIED_WITH_READBACK_WARNING。ROLLED_BACK后额外结构核对失败/标签切换为OUTCOME_UNKNOWN，不伪造恢复；该核对未读取被删除binding/form，所以不证明完整rig恢复。错误结果中的createdGroups仅为观察，不能当作现存对象句柄。结果缓存有界不淘汰，不重读宿主、不持有native对象；旧SDK缺新方法转换为UNAVAILABLE/事务内UnsupportedOperationException，防永久RUNNING。取消只对应bridge admission边界，不中断实际写入。

21项专项涵盖：混合树/参数覆盖、只读不调用values/bindings/setters；单事务批内create/ref/move/rename/combine；反向伙伴和先uncombine后move、配对分断拒绝；删除完整子树含嵌套/配对及前序/模型顺序披露；no-op无setter/历史；全部六history字段和同revision结构变化；跨会话、原TTL不续期、16计划淘汰；duplicate ID、unknown metadata/损坏树/配对；未定义ref、索引/类型/occupied pair及closed schema；partial native failure/状态竞争/readback corruption回滚；recoveryFailed/丢响应/commit后readbackwarning；unavailable transaction、256结果上限/零宿主result和重放；cancel准入前后；元数据字节/参数/文件夹预算和计划副本不可变；事务内过期/切文档停止后续写；旧SDK新增方法缺失转明确结果。Fixture使用真实独立列表变更与rollback快照验证MCP编排，不是实际Cubism native Undo测试。

验证：

```sh
./gradlew :plugins:mcp:spotlessJavaApply :plugins:mcp:test --tests '*McpParameterStructureDomainTest' --tests '*McpSdkCoverageGuardTest' --tests '*McpHttpServerIntegrationTest'
node plugins/acp/src/test/js/pi-mcp-extension.test.mjs
./gradlew -I /tmp/turboism-opacity-headless.gradle :plugins:mcp:spotlessJavaApply :plugins:mcp:test :plugins:mcp:spotlessJavaCheck :plugins:mcp:jar
git diff --check
```

最终headless **BUILD SUCCESSFUL，16s**；XML **631 tests/0失败/0错误/1跳过/63 suites**（610+21），跳过为既有headless UI。初版编译修正Json.stringify仅支持map/list的类型及测试错误异常类名；source重复ID先于index一致性检查，使错误明确为AMBIGUOUS_DISPLAY_ID。追加HTTP missing-result断言最初误用要求isError=false的成功helper，正确改为验证isError=true+OUTCOME_UNKNOWN/NOT_FOUND，没有改变生产语义或放宽schema。期间非headless全量629项（未含最后旧SDK/ID碰撞回归）也通过，但最终证据以上述headless631项为准。

HTTP实测进程内loopback服务的tools/list首50+第二页15、四个新名字与缺失结构结果的typed错误信封；不是真实Cubism目录。Pi三组通过（65个mock工具+3资源辅助=68注册）：第二页structure.plan无批准、structure.apply需批准；原MOC/atlas/image/resource/SSE/协商回归保留。未启动真实Pi CLI/视觉模型。三语README、manifest既有权限说明同步，无新权限，无SDK/API快照/Runtime/ACP Java生产改动，未重跑或挪用其先前Java测试。

日志 `/tmp/turboism-parameter-structure-compile.log`、`/tmp/turboism-parameter-structure-targeted.log`、`/tmp/turboism-parameter-structure-integration.log`、`/tmp/turboism-parameter-structure-full.log`、`/tmp/turboism-parameter-structure-final.log`、`/tmp/turboism-parameter-structure-pi.log`。git diff --check通过。没有启动/连接Cubism、打开/修改实际模型或新增live catalog成功证据；真实authoring/Undo/Redo/故障恢复、harness vision/Swing、保存重开与原件哈希、完整角色资产/播放器仍未验收。新增四个接口不等于完整半自动/全自动建模交付。

第35节最终追加核对：128文件夹与512参数精确上界可读，超一项拒绝；已占用Combined配对不能被替换。仍为上述631项、0失败/错误、1跳过。MCP JAR含全部McpParameterStructure/Domain/Schemas类，未部署宿主。

第35节ID碰撞复核：真实文件夹ID即使形似 `~created-group:` 也可正常读取/重命名/删除；只拒绝与本批临时引用实际冲突的create ref，而非保留整个原生ID前缀命名空间。新增第21项回归后最终631项通过，16s；此前630项是追加此案例前的中间结果。


## 36. 源几何/默认形网格创建与剖分 MCP（2026-10-25）

新增McpMeshCreationDomain/Schemas及16项独立可变模型夹具测试，接入生产McpHttpServer。工具69/资源15/模板2/提示词8；覆盖owner18（新增Drawables的8方法分类）。SDK/Runtime未变，本节没有重跑或借用其先前测试计数。权限沿用既有对象写/纹理域权限，native实际准入仍决定可用性。

专项涵盖：source/default真实分离批量创建+可选图像binding同事务、未绑定路线、原有对象保持、actual native GUID/form发现；native剖分重排不猜UV/无setter，空剖分失败；六history绑定中的revision/generation/document及无revision变化的GUID/form/父级字段/lock/image变动拒绝、事务内再CAS；部分创建/绑定失败整批回滚、错误source/default/旧对象变化/返回数量不符回滚；commit后readbackwarning、response丢失、recoveryFailed unknown；TTL/session/16计划缓存隔离、result和exact replay零host读取；旧batch SDK缺失不留RUNNING；schema/transaction元数据、未知字段、非有限/越界/不匹配UV/重复面、隐藏或锁父级、模型数量预算、重复native GUID。夹具用独立mesh map与transaction前快照恢复验证编排，不是Cubism原生Undo证据。

最初专项7项失败源于测试将record HistorySnapshot误作interface proxy，改用真实DTO后，仅剩stale测试写回了与原值相同的位置；改成真实变动后51项专项/HTTP/coverage集成通过，未放宽CAS或生产结果语义。追加预算/身份/缓存等4项后，共16项Domain，最终全量通过。

```sh
./gradlew -I /tmp/turboism-opacity-headless.gradle :plugins:mcp:spotlessJavaApply :plugins:mcp:test --tests '*McpMeshCreationDomainTest' --tests '*McpSdkCoverageGuardTest' --tests '*McpHttpServerIntegrationTest'
node plugins/acp/src/test/js/pi-mcp-extension.test.mjs
./gradlew -I /tmp/turboism-opacity-headless.gradle :plugins:mcp:spotlessJavaApply :plugins:mcp:test :plugins:mcp:spotlessJavaCheck :plugins:mcp:jar
git diff --check
```

MCP最终XML **647 tests / 0 failures / 0 errors / 1 skipped / 64 suites**（631+16）；跳过为既有headless UI。HTTP进程内loopback目录50+19、四个新名字，missing create_result返回isError=true/OUTCOME_UNKNOWN/NOT_FOUND/空观察/renderVerified=false。Pi三组通过：69 mock工具+3资源辅助=72注册，新triangulate/create_plan无需批准、create需要批准，已有图片/资源/SSE/协议回归保持。不是真实harness或视觉模型。

日志：`/tmp/turboism-mesh-creation-compile.log`、`/tmp/turboism-mesh-creation-targeted.log`（保留初始失败）、`/tmp/turboism-mesh-creation-integration.log`、`/tmp/turboism-mesh-creation-full.log`、`/tmp/turboism-mesh-creation-final.log`、`/tmp/turboism-mesh-creation-pi.log`。JAR为 `build/worktree/mighty-seal/mcp/libs/mcp-0.67.2-SNAPSHOT-mighty-seal.jar`，没有部署。未连接Cubism、未打开或修改实际模型，未证明GPU图片、真实Undo/Redo/故障回滚/保存重开、原件哈希与播放器加载。整体自动建模目标仍未完成。


## 37. 文档身份目录与 NEW_MODEL 就绪工作流（2026-10-25）

新增McpDocumentLifecycleDomain/Schemas与19项独立项目/文档夹具测试，生产接线72工具/15资源/2模板/8提示词；SDK有限owner仍18，增加组合语义账本行，supportedVersions为空以明确不作新增exact-host认证。复用已有typed NEW_MODEL；不改变Runtime OPEN的first-open fail-closed限制。无SDK/Runtime/ACP Java生产改动，未重跑或借用这些模块的此前测试计数。

测试覆盖空project/无文档、混合MODEL/IMAGE、路径/原生handle不输出；确实新active doc/model/history身份、旧文档/内容metadata保持；native no-op/非MODEL/两个新增文档/替换旧doc/project/重复model ID均不READY；authoring unavailable仍可读但不就绪；目录/活动doc/历史/名称/contents无revision变化和available检查间状态竞争；native unavailable/rejected/FAILED/异常/结果commandId不符/提交后read failure与不一致；session/TTL/16快照淘汰、128保留结果不淘汰/零host result与replay；QUEUED/RUNNING读取不等待/不重派；取消前后区别；closed schema拒path/command/name/canvas字段；目录/encoded budget、精确128文档上界可读但预拒绝新增，以及不完整project清单拒绝分派。

最初15项专项有2失败：fixture强制覆盖外层会话；另将重复native model ID导致的严格unknown误期望为普通not-ready。修正fixture会话转发与预期，生产重复身份拒绝保留。追加3项取消/结果容量/旧contents案例后18项；随后增加第19项精确目录容量前置检查，最终如下。没有将失败改成宽松成功，也未连接宿主。

```sh
./gradlew -I /tmp/turboism-opacity-headless.gradle :plugins:mcp:spotlessJavaApply :plugins:mcp:test --tests '*McpDocumentLifecycleDomainTest' --tests '*McpHttpServerIntegrationTest' --tests '*McpSdkCoverageGuardTest'
node plugins/acp/src/test/js/pi-mcp-extension.test.mjs
./gradlew -I /tmp/turboism-opacity-headless.gradle :plugins:mcp:spotlessJavaApply :plugins:mcp:test :plugins:mcp:spotlessJavaCheck :plugins:mcp:jar
git diff --check
```

最终headless BUILD SUCCESSFUL（15s），XML **666 tests / 0 failures / 0 errors / 1 skipped / 65 suites**（647+19）；此前18项版本665为中间结果，skip为既有headless UI。HTTP进程内loopback实际目录50+22、三个新名字、missing new_result isError=true/OUTCOME_UNKNOWN/NOT_FOUND/authoringReady=false。Pi三组通过：72 mock工具+3资源helpers=75注册，新documents.read无批准/new_model需批准，原mesh/structure/image/resource/SSE/协议回归保持。未启动真实Pi或视觉模型。

日志 `/tmp/turboism-document-lifecycle-compile.log`、`/tmp/turboism-document-lifecycle-targeted.log`（初轮失败保留）、`/tmp/turboism-document-lifecycle-integration.log`、`/tmp/turboism-document-lifecycle-full.log`、`/tmp/turboism-document-lifecycle-final.log`、`/tmp/turboism-document-lifecycle-pi.log`。JAR含新Domain/Schemas，路径仍 `build/worktree/mighty-seal/mcp/libs/mcp-0.67.2-SNAPSHOT-mighty-seal.jar`；最终仅更新既有权限理由后另打包，不重复借用测试作宿主证据。git diff --check通过。

未启动/连接Cubism、打开/新建/修改实际模型或部署JAR，没有新增实时目录成功证据。READY检查仅metadata/authoring入口身份，existingModelContentsVerified/persistenceVerified始终false；不验证原模型完整内容、默认画布、GPU图像、native Undo/Redo/回滚、保存重开或播放器加载。DOC-01统一legacy目标绑定/标签激活、DOC-02画布/真实UI、FILE-02 OPEN以及完整自动建模算法/交付门槛仍未完成。


## 38. Parameter 原生 GUID 与结构 CAS（2026-10-25）

新增SDK default `Parameter.guid()`，Editor-native source GUID经现有verified `parameter-source.guid`/`guid.value` getter读取，PermissionCheckedParameter转发model.read检查，SessionParameter转发generation检查。默认/Core-only/detached无证据实现明确unavailable，不从显示ID、name、Core index等生成身份。SDK作者指南明确ID重命名与旧ID绑定句柄失效边界。无setter/pose/selection/Undo变化，没有新增宿主mapping或放宽exact selector准入。

MCP参数结构每行加入guid，Param record/纯模拟/规范化/写前/事务内/逐步回读/回滚核对保留GUID；唯一显示ID约束仍保留（写接口仍以显示ID查找），重复native GUID拒绝，缺getter的旧SDK转UNAVAILABLE。identityScope为PARAMETER_GUIDS_GROUP_IDS_AND_HISTORY_BINDING。文件夹GUID仍未开放；结构核对不读取删除的binding/forms，不能宣称完整rig恢复。目录仍72/15/2/8，有限owner18，Parameter.guid新增账本真实READ行，不声称全SDK覆盖或新增宿主实机验收。

新增SDK1项legacy backend不伪造GUID；Runtime4项native source GUID/求值对象重建/同ID新source旧句柄拒绝、缺selector、空GUID、权限撤销/active scope和不需要write权限；既有Session metadata测试加入GUID转发、deactivate与同model ID/GUID重连后旧generation继续拒绝。MCP新增2项：同display ID/定义/Combined字段且无history变化的新GUID使计划stale；重复GUID/旧SDK缺reader fail closed。fixture的GUID为独立opaque身份，不由名称推导；复制回滚fixture保留原GUID。

最初MCP专项失败来自新增getter未接入测试fixture的switch，补齐GUID分支后专项通过。随后全量仅空GUID测试的异常类型预期错误：原生损坏GUID一直被生产text校验以IllegalStateException拒绝，而测试写成UnsupportedOperationException；修正预期，未改变生产拒绝。首轮Runtime6131项1失败日志保留，重跑全量后以下结果全部通过。

```sh
./gradlew -I /tmp/turboism-opacity-headless.gradle :sdk:spotlessJavaApply :runtime:spotlessJavaApply :plugins:mcp:spotlessJavaApply :runtime:test --tests '*EditorParameterStructureAccessTest' --tests '*PermissionCheckedModelForwardingTest' --tests '*DynamicCubismModelAccessTest' :plugins:mcp:test --tests '*McpParameterStructureDomainTest' --tests '*McpSdkCoverageGuardTest'
./gradlew :sdk:spotlessJavaApply :runtime:spotlessJavaApply :plugins:mcp:spotlessJavaApply updateSdkApiCurrent checkSdkApiCurrent
./gradlew -I /tmp/turboism-opacity-headless.gradle :runtime:spotlessJavaApply :sdk:test :runtime:test :plugins:mcp:test :sdk:spotlessJavaCheck :runtime:spotlessJavaCheck :plugins:mcp:spotlessJavaCheck :runtime:jar :plugins:mcp:jar :plugins:acp:compileJava checkSdkApiCurrent
git diff --check
```

最终BUILD SUCCESSFUL（3m17s）。权威XML：SDK **470 tests/0 failures/0 errors/1 skipped/121 suites**；Runtime **6131/0/0/43 skipped/696 suites**；MCP **668/0/0/1 skipped/65 suites**。既有headless/平台条件skip仍不代表宿主测试通过。SDK与MCP已在首轮完成同版本全量、最终命令UP-TO-DATE复用其成功结果；Runtime最终实际重跑全部6131项，0失败。格式检查、Runtime/MCP JAR及ACP Java兼容编译成功（后者无源码变更，最终UP-TO-DATE）。current API与本轮开始前 `/tmp/turboism-parameter-guid-api-before.txt` 比较仅新增1个default getter、0删除；未改历史冻结契约或version。

日志 `/tmp/turboism-parameter-guid-compile.log`（初版fixture失败）、`/tmp/turboism-parameter-guid-targeted.log`、`/tmp/turboism-parameter-guid-api.log`、`/tmp/turboism-parameter-guid-full.log`（空GUID测试预期失败）、`/tmp/turboism-parameter-guid-final.log`（全量通过）。Pi/ACP transport未变，未重跑或借用之前的Pi计数作为本轮验收。git diff --check通过。

没有启动/连接Cubism、打开/修改实际模型、部署JAR或新增live catalog。未验收真实宿主参数GUID与snapshot轴匹配、跨JVM持久化、native binding/Undo/Redo/渲染/保存重开。参数GUID读取与CAS增强是绑定初始化基础；普通完整参数网格初始化、新模型rig/美术算法、OPEN和完整交付仍未实现或未验收，整体目标不标完成。


## 39. 按数值创建参数绑定的 SDK 原语（2026-10-25）

新增default `ParameterBindingOperations.bindValues(target, List<Float>)`，不需要虚构point ID；默认后端明确unavailable。Editor实现验证/复制/排序数值后进入现存bind的exact selector、保护状态、Undo和事务路径；legacy bind复用同实现。PermissionCheckedModel每次检查model.write与BIND_PARAMETER语义，SessionModel每次检查generation。没有新增mapping、UI命令、MCP工具或放宽write准入。SDK当前开发API只增加1方法/0删除（与`/tmp/turboism-bind-values-api-before.txt`比较），未修改历史冻结契约/版本号。

新增SDK1项无支持backend不伪造ID测试；Runtime9项执行真实adapter/decoder/coordinator配合native成员替身：ArtMesh/Warp/Rotation分别测试排序写入、重复绑定拒绝、完整snapshot Undo/Redo、事务中止恢复与部分native插入异常恢复；另测空/null/NaN/Infinity/容差重复输入在native修改前拒绝、权限撤销/active scope、同模型重连与deactivate后的旧session拒绝。原有测试继续覆盖共用写路径。它们不证明真实宿主生成完整Cartesian cells/forms或默认form GUID分配。

首轮专项2项失败因fixture缺semantic binding snapshot投影、新API委托；补齐fixture后87项Runtime专项及SDK契约通过，未放宽生产检查。fixture绑定投影采用显式开关，保留其他案例原来的unavailable行为。

```sh
./gradlew -I /tmp/turboism-opacity-headless.gradle :runtime:spotlessJavaApply :sdk:test --tests '*ParameterBindingOperationsContractTest' :runtime:test --tests '*EditorParameterBindingTransactionTest' --tests '*PermissionCheckedModelForwardingTest' --tests '*DynamicCubismModelAccessTest' updateSdkApiCurrent
./gradlew -I /tmp/turboism-opacity-headless.gradle :sdk:test :runtime:test :plugins:mcp:test :sdk:spotlessJavaCheck :runtime:spotlessJavaCheck :plugins:mcp:spotlessJavaCheck :runtime:jar :plugins:mcp:jar :plugins:acp:compileJava checkSdkApiCurrent
git diff --check
```

最终BUILD SUCCESSFUL（3m21s）。权威XML：SDK **471 tests/0 failures/0 errors/1 skipped/121 suites**；Runtime **6140/0/0/43 skipped/696 suites**；MCP **668/0/0/1 skipped/65 suites**。三模块格式检查、Runtime/MCP JAR、ACP Java兼容编译、SDK current API核对通过。headless/平台条件skip不代表宿主通过。git diff --check通过。

日志：`/tmp/turboism-bind-values-targeted.log`（最终专项），`/tmp/turboism-bind-values-full.log`（全量）。SDK作者指南同步数值绑定与回读要求，MCP有限owner18账本为新方法明确EXCLUDED_WITH_REASON；生产目录仍72工具/15资源/2模板/8提示词，未将legacy apply提升事务资格。Pi/ACP transport未改或重跑，不借用旧测试作为本轮harness验收。

未启动/连接Cubism、操作实际模型或部署JAR，无真实native binding、GPU/图像、Undo/Redo/回滚、保存重开与原件哈希验收。完整多参数网格初始化及MCP plan/apply/result、默认内容保留、视觉/几何验收仍待独立实现验证；整个自动建模目标保持未完成。


## 40. 普通绑定网格初始化 Domain（2026-10-25）

生产新增McpBindingInitializationDomain/Schemas与纯McpBindingGridSnapshot检查器，接入HttpServer；闭合plan/apply/result协议、六字段history/source/父/锁/editLevel/参数GUID及完整定义/值CAS、逐轴完整Cartesian/独立form/默认内容核对、单SDK事务与有限回滚后复核，完整契约见实施清单第40节和MCP三语README。当前75工具/15资源/2模板/8提示词，有限账本18owner；bindValues从EXCLUDED变为真实MCP_TRANSACTION_CONTROL调用路径，严格账本测试增加endpoint/operation配对，未放宽legacy apply。

新增Domain测试17项：三类型分别覆盖1/2/3轴完整网格、实际DTO中的form GUID/full tuple/defaultCell、内容不变、一次事务/逐轴bind、保留结果/冲突/零host replay；三类型分别注入缺cell/共享form/重复tuple/内容/source/轴定义/pose变化及原生部分失败验证回滚、停止后续轴；非法关键值、default/范围/近重复、repeat/Combined/Morph/参数身份/重复ID和GUID；源/存储form/父/锁/default锁/editLevel/参数GUID/值/名称/范围/顺序/history/document等stale；事务前二次核对竞态、恢复失败、响应丢失、提交后警告；源与计划TTL/会话隔离/计划淘汰/128结果容量；Morph/空/共享初始form、点数/采样/513参数超限；取消前后、RUNNING可查；权限target摘要篡改拒绝与JSON数值往返。fixture只模拟SDK事务及显式生成detached grids，不宣称真实native分配/Undo/GPU验收。

开发中先修正typed deformer lookup/Rotation form accessor与测试Map泛型；首轮专项两个时序预期错误（源snapshot签发时刻、取消后再次嵌套结果读取）经校正通过。首轮集成仅严格覆盖测试缺新endpoint/operation配对，补对应断言后通过。未因此放宽生产准入、取消检查或账本规则。

```sh
./gradlew :plugins:mcp:spotlessJavaApply :plugins:mcp:test --tests '*McpBindingInitializationDomainTest' --tests '*McpHttpServerIntegrationTest' --tests '*McpSdkCoverageGuardTest'
./gradlew :plugins:mcp:spotlessJavaApply :plugins:mcp:test :plugins:mcp:spotlessJavaCheck :plugins:mcp:jar :plugins:acp:compileJava checkSdkApiCurrent
node plugins/acp/src/test/js/pi-mcp-extension.test.mjs
git diff --check
```

全量BUILD SUCCESSFUL（17s），权威XML MCP **685 tests/0 failures/0 errors/0 skipped/66 suites**；格式、MCP JAR、ACP Java兼容编译和SDK current API检查通过。本轮没有SDK/Runtime源码变化，不重跑或挪用之前的471/6140项计数。MCP全量未使用headless init，当前环境本轮0skip按XML如实记录，不把之前的条件skip当宿主验证。

Pi三组通过：75个mock MCP工具（加3资源桥接共78注册）跨50/25两页发现/执行，初始化plan/result不弹批准、initialize写批准且JSON摘要包含真实目标/参数/keys；资源/typed image/二进制抑制/验证/取消既有回归；协议协商header与分片HTTP SSE；不支持协议拒绝。不是实际native harness或模型视觉验收。

另外重跑原审计复核命令 `python -m unittest discover -s validation/acp-auto-modeling-capability-audit -p 'test_*.py' -v`，当前14项全部通过（只使用fake bridge；不连接Cubism）。git diff --check通过；这些采集器测试不证明建模接口或native宿主可用。

日志：`/tmp/turboism-binding-initialize-compile.log`、`/tmp/turboism-binding-initialize-targeted.log`、`/tmp/turboism-binding-initialize-integration.log`、`/tmp/turboism-binding-initialize-full.log`、`/tmp/turboism-binding-initialize-pi.log`。本轮SDK current快照无增删，未改冻结契约/版本号；产物仍为工作树MCP JAR，未部署。

未启动/连接Cubism或打开/修改任何实际模型；无live catalog/GPU/native组合生成/Undo/Redo/故障回滚/保存重开证据。真实host是否生成完整独立forms需独立实机验证；当前服务会对不满足的返回状态失败回滚，不把partial/no-op当成功。整个半自动/全自动建模目标仍未完成。


## 41. 素材来源与图层关系纯读 Domain（2026-10-25）

新增McpAssetSourceDomain/Projection/Schemas并接入生产HttpServer：sources.read为显式anchors scoped读取，relations.read为完整受限metadata投影。复用ModelTextures.sources/relations及既有texture.read精确版本metadata，权限/session generation路径保持；没有SDK/Runtime/API版本变化。当前目录77/15/2/8，有限owner18，coverage ledger相应两行改MCP_READ。详细身份/观察序号/历史分页/预算/未解析关系边界见实施清单第41节与三语README。

新增14项Domain测试覆盖：当前/候选/UNKNOWN、raw-layer作用域与独立PSD数值ID、共享图像使用者、nullable当前输入和未知/ATLAS输入；missing anchors/raw/layer、available-empty与unavailable、scoped不fallback/不泄漏全图；两次数据/epoch/history竞态、immutable页不重读native、session/tool/query/TTL隔离；重复身份/owner/尺寸/metadata错配/非法UTF16；depth/count/links/单行与总编码预算、缩页完整性、16entry与16MiB缓存淘汰；权限撤销、错误脱敏、目标绑定和原始path严格拒绝。另新增HTTP测试确认两个closed READ请求在无Bearer时不被误当写入；有效参数下仍按实际model/permission/runtime返回typed失败。替身仅提供SDK metadata，不构成真实PSD/native来源证据。

```sh
./gradlew :plugins:mcp:spotlessJavaApply :plugins:mcp:test --tests '*McpAssetSourceDomainTest' --tests '*McpHttpServerIntegrationTest' --tests '*McpSdkCoverageGuardTest'
./gradlew :plugins:mcp:spotlessJavaCheck :plugins:mcp:test :plugins:mcp:jar :plugins:acp:compileJava checkSdkApiCurrent
./gradlew -I /tmp/turboism-opacity-headless.gradle :plugins:acp:test --tests '*AcpChatControllerTest'
node plugins/acp/src/test/js/pi-mcp-extension.test.mjs
```

MCP全量BUILD SUCCESSFUL（17s），XML **700 tests/0 failures/0 errors/0 skipped/67 suites**；格式/JAR/ACP Java编译/SDK current API核对通过。本轮SDK/Runtime未改，不重跑或挪用此前471/6140项作为本轮验收。ACP Controller BUILD SUCCESSFUL（9s），XML **62/0/0/0/1 suite**；固定reference增加半自动建模工具链，既有prompt预算/边界及controller回归通过。编译仅有既有logsDir弃用警告。

Pi三组通过：77个mock工具/80注册的分页发现、第二页sources/relations执行不弹批准，以及既有工具权限/图像转发/资源分页/二进制抑制/取消；协议header与分片HTTP SSE；不支持协议拒绝。这不是native/ACP-v1全harness或视觉端到端验收。未变更任何harness协议。

开发中修复Map泛型/Json.stringify编译用法；首轮专项1失败是测试误将schema边界抛出的IllegalArgumentException当作Domain错误信封，修正测试预期，未放宽生产schema。之后Domain14项及集成/账本专项通过，全量如上。日志保存在 `/tmp/turboism-asset-source-compile.log`、`/tmp/turboism-asset-source-targeted.log`、`/tmp/turboism-asset-source-integration.log`、`/tmp/turboism-asset-source-full.log`、`/tmp/turboism-asset-source-acp.log`、`/tmp/turboism-asset-source-pi.log`。

未启动/连接Cubism、操作实际模型或部署JAR；无新增live catalog、native图层来源/GPU/Undo/Redo/回滚/保存重开/原件哈希验收。不以metadata关系自动推断素材语义或美术质量；整体自动建模目标仍未完成。

本轮另执行原审计采集器离线测试（fake bridge）14项全部通过；`git diff --check`通过。采集器测试不连接Cubism，也不证明新增建模接口可用。


## 42. 普通关键形相邻区间斜率诊断（2026-10-25）

新增McpKeyformContinuity纯算法、McpContinuityDomain/Schemas和生产装配 `turboism.validation.keyform_continuity`。有限Cartesian普通网格的所有相邻三点沿GUID轴检查左右secant斜率差，覆盖Mesh/Warp存储点以及Rotation轴心/原始angle/scale/reflection；可分析闭合proposedEdits但不提交。复用现有authoring会话快照、普通writer preflight和三类完整native投影；没有SDK/Runtime/API/版本修改、宿主准入扩张或新反射/写入路径。详细范围/指标/预算/遗漏见实施清单第42节和三语MCP README。目录78/15/2/8，有限owner18保持。

新增算法10项：非均匀key匀速无警告与真实间距/全范围单位；三轴完整27条线、重复显示ID与native collection乱序；只修改一角的单点可定位到三条受影响轴线及完整tuple；共享普通form可读；raw angle未折返、origin/scale/双反射；maxFindings仅截断行不截断分析计数；缺失/重复tuple、孤立form、少key轴、Morph与点数预算拒绝；float极值仍有限及显式阈值非法输入。新增Domain7项：三类型closed输出、原生读取前后各一次且无任何写调用、拟议修改仅纯投影、default/shared写保护、同history的form/parent变化与document/history stale、过期/跨session、权限/unavailable/内部错误脱敏、未知path/NaN schema拒绝、完整统计与超大报告失败不输出verdict。HTTP新增1项无Bearer只读调用，以有效closed参数检验返回typed身份/准入失败而不是写鉴权拒绝；实际目录断言78工具。

```sh
./gradlew :plugins:mcp:spotlessJavaApply :plugins:mcp:compileJava
./gradlew :plugins:mcp:spotlessJavaApply :plugins:mcp:test --tests '*McpKeyformContinuityTest' --tests '*McpContinuityDomainTest'
./gradlew :plugins:mcp:spotlessJavaApply :plugins:mcp:test --tests '*McpKeyformContinuityTest' --tests '*McpContinuityDomainTest' --tests '*McpHttpServerIntegrationTest' --tests '*McpSdkCoverageGuardTest'
./gradlew :plugins:mcp:spotlessJavaApply :plugins:mcp:test :plugins:mcp:spotlessJavaCheck :plugins:mcp:jar :plugins:acp:compileJava checkSdkApiCurrent
./gradlew -I /tmp/turboism-opacity-headless.gradle :plugins:acp:test --tests '*AcpChatControllerTest'
node plugins/acp/src/test/js/pi-mcp-extension.test.mjs
git diff --check
```

首次test编译发现新fixture用了当前Java基线没有的List.reversed，以及条件Class表达式的泛型推导错误；改为现存乱序fixture、显式Class<?>后通过，未改生产拒绝策略。三轴fixture包含的独立unused form被移出该合法完整grid案例；孤立form仍有专门拒绝测试，不放宽算法。专项与HTTP/账本集成成功，随后完整MCP BUILD SUCCESSFUL（18s）；XML **718 tests/0 failures/0 errors/0 skipped/69 suites**。格式、MCP JAR、ACP Java兼容编译和SDK current API检查通过。本轮SDK/Runtime未改，不重跑或挪用其之前的471/6140项作为新证据。

ACP Controller BUILD SUCCESSFUL（8s），XML **62 tests/0 failures/0 errors/0 skipped/1 suite**：固定modeling-reference增加斜率诊断的使用条件、意图曲率/反射与原生插值证据边界，既有提示词预算/边界回归通过。Pi三组通过：78个mock MCP工具/81注册，跨两页发现与新只读诊断执行无需写批准；既有权限/资源/typed image/二进制抑制/取消回归；协商header与分片HTTP SSE；不支持协议拒绝。它们不证明实际native/ACP-v1全harness、模型视觉消费或Swing实机显示。

日志：`/tmp/turboism-continuity-compile.log`、`/tmp/turboism-continuity-targeted.log`（最终专项）、`/tmp/turboism-continuity-integration.log`、`/tmp/turboism-continuity-full.log`、`/tmp/turboism-continuity-acp.log`、`/tmp/turboism-continuity-pi.log`。git diff --check通过；既有logsDir弃用警告未修改。本轮三语README当前目录计数与契约同步，未部署产物。

没有启动/连接Cubism、打开/修改实际模型或新增live目录证据。全部形状和宿主服务来自detached SDK fixtures/proxy；原始native C0/C1插值、关键点之间的真实形变、GPU、Undo/Redo/故障回滚、保存重开和原件哈希仍无本轮证据。这个工具的NO_FINDINGS仅在声明的stored secant检查范围有效，不能转成自动建模或美术质量验收。整个半自动/全自动建模目标保持未完成。


## 43. 显式普通 rig 几何迁移规划（2026-10-25）

新增McpRigTransferPlan/Domain/Schemas，生产装配 `turboism.rig.transfer_geometry.plan`。三类同kind、不同对象与source GUID的显式双快照，完整parameter GUID/key对应及点排列/矩阵或Rotation分量策略，生成目标普通writer请求；无setter、绑定创建、事务或operation reservation。完整契约与sourceReadScope历史边界见实施清单第43节和三语README。目录79工具/15资源/2模板/8提示词，有限owner18；没有SDK/Runtime/API/版本变化、权限放宽或新native mapping。

从第42节提取McpStoredSnapshotVerification复用完整native/对象source ID/直接父关系/history核对，既有continuity测试继续通过。McpBindingGridSnapshot增加显式maxCells/maxSamples内部overload，原default调用上限27cells/32768采样未改变；新迁移独立限65cells/form、262144双方采样，最大4096计划采样。并非提升SDK/MCP全局预算。

新增10项纯算法案例：Warp相对位移/目标default/grid/quadTransform保留；Mesh点排列方向及source/UV/indices/drawOrder/opacity保留；Rotation原始角度/轴心/scale ratio/RELATIVE_XOR及target baseAngle；显式key反向对应；完整三轴组合tuple；65cells/64edits边界及旧initializer budget仍拒绝65；共享source可读但共享target拒绝；全部key/default/axis与点排列的完整唯一对应；不完整/孤立grid、Morph、原生float溢出；planned-point预算与跨kind拒绝。没有根据名称猜左右或通过对象copy复制rig。

新增7项Domain案例：三类型完整closed output和仅target writeRequest（default未编辑）；历史计划结果在后续source变化后不变；source/target分别二次native读取及父/history/document stale；别名对象、错误kind、过期/跨session；近似JSON数值不得round到实际key、非完整permutation、原始path/不支持reflectionPolicy/奇异矩阵拒绝；权限/unavailable/内部错误脱敏且不给partial request；重复plan结果一致、不保留operation身份/不消费快照。Proxy对全部未预期getter/setter/事务/文件/选择等调用直接抛AssertionError。新增HTTP1项无Bearer READ及79工具实际目录断言；有效closed请求返回typed准入/身份失败，未误作写鉴权。

```sh
./gradlew :plugins:mcp:spotlessJavaApply :plugins:mcp:compileJava
./gradlew :plugins:mcp:spotlessJavaApply :plugins:mcp:test --tests '*McpRigTransferPlanTest' --tests '*McpRigTransferDomainTest' --tests '*McpContinuityDomainTest' --tests '*McpBindingInitializationDomainTest'
./gradlew :plugins:mcp:spotlessJavaApply :plugins:mcp:test --tests '*McpRigTransferPlanTest' --tests '*McpRigTransferDomainTest' --tests '*McpHttpServerIntegrationTest' --tests '*McpSdkCoverageGuardTest'
./gradlew :plugins:mcp:spotlessJavaApply :plugins:mcp:test :plugins:mcp:spotlessJavaCheck :plugins:mcp:jar :plugins:acp:compileJava checkSdkApiCurrent
./gradlew -I /tmp/turboism-opacity-headless.gradle :plugins:acp:test --tests '*AcpChatControllerTest'
node plugins/acp/src/test/js/pi-mcp-extension.test.mjs
git diff --check
```

编译、专项和HTTP/覆盖账本集成均成功，最终MCP BUILD SUCCESSFUL（19s），权威XML **736 tests/0 failures/0 errors/0 skipped/71 suites**。格式、MCP JAR、ACP Java兼容编译与SDK current API检查通过。没有SDK/Runtime变化，未挪用此前471/6140项作为本轮证据。既有logsDir弃用warning保留，未部署JAR。

ACP Controller BUILD SUCCESSFUL（8s），XML **62 tests/0 failures/0 errors/0 skipped/1 suite**；固定Agent建模reference加入真实迁移工具名、先有完整绑定/显式映射、target-only CAS与源历史计划界限，既有prompt预算/边界回归通过。Pi三组通过：79个mock工具/82注册跨页发现及新plan读取无需批准，既有权限/图像/资源分页/验证/取消；协议header与分片HTTP SSE；不支持协议拒绝。这些不是native/ACP-v1逐harness或真实vision/UI验收。

日志：`/tmp/turboism-rig-transfer-compile.log`、`/tmp/turboism-rig-transfer-targeted.log`、`/tmp/turboism-rig-transfer-integration.log`、`/tmp/turboism-rig-transfer-full.log`、`/tmp/turboism-rig-transfer-acp.log`、`/tmp/turboism-rig-transfer-pi.log`。三语README与实施清单同步，git diff --check通过。

未启动/连接Cubism、打开/修改实际模型，没有新增live目录、GPU/原生rig迁移、Undo/Redo/故障回滚、保存重开或原件哈希证据。native fixtures仅验证typed planning/拒绝/目标写DTO，不能冒充已验收真实迁移或完整rig复制。目标已有ordinary绑定仍需实际完整grid；源在后续write时不会自动重读或CAS，目标default不被计划编辑，keyforms.write仍有自身实时准入与事务结果语义。Milestone A及整个半自动/全自动建模目标保持未完成。


## 44. 受管实机基线、原生渲染与模型身份修复（2026-10-25）

用户已明确允许格式化、生产/API扩展与实机，并要求使用Turboism实机流程；原审计“不连接/修改实际模型”的历史边界不再约束后续已授权的隔离测试。使用 `host_validation.py prepare/submit/status`，复用既有全局worker，不安装服务、不绕过队列、不直接启动Java/Proton/BAT、不删除锁或终止其他任务。只运行任务副本，原素材与安装前后hash由队列核对。

实测版本为 **Cubism 5.3.03 / Linux Proton**。选用既有Glue/纹理色块测试素材（SHA-256 `2980dc8c695b13eeb54e37c02832edf80607979f2cc721ea9ca7b671f8bbac10`），不是官方Hiyori，不作角色头部、美术或全部宿主版本验收。完整本机路径和冻结输入在私有队列；仓库仅保留脱敏[任务摘要](live-host-20261025/runs.json)与逐轮properties。

- [基线](live-host-20261025/baseline.properties)：真实发现79工具/15资源/2模板/8提示词，166 coverage entries/3 temporary exceptions；Glue写入/Undo/Redo/事务回滚，参数/绑定恢复，五类纹理操作及native层像素/保存重开通过。队列最终succeeded、normalExit/fixtureUnchanged/identityVerified=true、cleanup=safe。
- [首次渲染失败](live-host-20261025/render-initial-failure.properties)：`render.state`返回STALE_STATE，模型未修改，队列安全清理。原因是MCP把workspace session opaque model ID与typed model native source GUID直接比较；同样错误存在stored authoring context。
- 修复仅更正上述两个Domain的跨命名空间比较。保留document.model与activeModel快照一致性检查、typed model.id()当前原生绑定验证、history/pose CAS和native stored完整快照核对。测试fixture改用不同native GUID与workspace ID，新增真实document/model冲突拒绝案例。未改变SDK、Runtime、selector授权或事务资格。
- [修复后渲染](live-host-20261025/render-fixed.properties)：native renderPixels返回256×256 typed PNG，JSON/图像类型、尺寸、长度、SHA-256、pose/history对应、stale拒绝与状态保持通过；后续可逆矩阵同样通过，队列最终安全成功。[原生PNG](live-host-20261025/mcp-native-render.png)与[状态元数据](live-host-20261025/mcp-native-render.json)只来自本轮真实返回。图像为色块测试素材，不能当眼嘴细节质量证据；尚无ACP Swing/真实vision模型验收。
- [首次stored探针](live-host-20261025/stored-initial-failure.properties)：ArtMesh读取后，首个Warp返回INVALID_ARGUMENT，本轮失败且modelMutation=NONE、cleanup=safe。不能将失败或仅目录发现计为三类几何通过。[有界候选复测](live-host-20261025/stored-candidates-failure.properties)仍无可读取Warp，本轮失败且安全清理；未将未支持对象当作跳过后通过。后续仅validation probe增加typed SDK只读异常类/调用栈诊断，不输出异常原文、凭据或原始宿主对象。

本轮离线MCP全量 **737 tests / 71 suites，0失败/错误/跳过**；受审查Python客户端 **20 tests通过**，标准SDK JS语法检查、探针JAR编译打包与git diff --check通过。实机包构建修复共享WindowsProbeHarness及其WindowsHistoryManagerValidationProbe编译依赖；这些只属于validation probe。Python与Node标准客户端的精确目录同步79工具，资源/模板/提示词不变；发现目录不代表79工具均已执行。

后续[typed SDK诊断](live-host-20261025/stored-diagnostic.json)证实12个deformer均丢失Warp/Rotation子类型（warp=false/rotation=false），并非native Warp网格不支持。该历史诊断的status=READ仅表示读到了base interface身份，不表示调用过authoringSnapshot；测试探针已将此状态更正为BASE_INTERFACE_ONLY，真实快照成功另标SNAPSHOT_READ。[拒绝明细](live-host-20261025/stored-rejections.json)保留了8个Warp目标的INVALID_ARGUMENT，不掩盖失败。

修复 `CubismEditorApiAvailabilityInterceptor` 与 `PluginGenerationGuard`：仅对闭合的Deformer→Warp/Rotation SDK子类型，在创建代理前选择对应接口；不自动公开实现类其它接口。两层代理的缓存、版本/能力检查、异常转发与生命周期fence继续生效。各新增1项集合all/find保型与守卫案例；相关Runtime43项通过，随后 **Runtime全量6142 tests / 696 suites，0失败/错误、4跳过**，MCP737项通过。跳过项不计实机通过；这是本轮新执行结果，非前次6140项记录。

[代理修复后实机](live-host-20261025/stored-proxy-fixed.properties)最终succeeded、normalExit/fixtureUnchanged/identityVerified=true、cleanup=safe。本次每类首个对象均成功、没有拒绝候选：ArtMesh7为19 rows，Warp3为42 rows，Rotation4为6 rows；[完整stored rows及hash](live-host-20261025/stored-authoring.json)和[对应render状态](live-host-20261025/stored-native-render.json)已保存。逐页sourceGuid/snapshotId/state/offset/total与重复首屏核对通过，参数/history不变；相同原生PNG哈希，既有可逆矩阵也全部通过。三者目前均仅有默认form，snapshotEligible=false/DEFAULT_FORM；不借此进行普通writer写入，不绕过默认形保护。

Milestone A仍未通过：尚欠选择已有独立非默认form并完成三类真实写入、几何/图像前后、精确Undo/Redo、故障回滚、几何保存重开、官方Hiyori原件保护与ACP图像端到端证据。整体自动建模目标保持未完成。


## 45. 三类ordinary存储form写入、单步Undo/Redo与完整恢复（2026-10-25）

继续使用Turboism受管队列及第44节5.3.03色块/Glue素材副本。新客户端优先寻找`ordinary_write_eligibility.snapshotEligible=true`的独立非默认form，逐类型至多32个候选；完成扫描后重新取得选中对象快照，避免候选扫描引起16-entry缓存淘汰后继续使用旧snapshotId。仍完整核对分页、sourceGuid、document/model/history与重复读取，默认/共享/Morph引用目标不进入ordinary写入。

[发现任务](live-host-20261025/ordinary-discovery.properties)找到ArtMesh4和Warp2的可编辑ordinary forms，三类writer/Undo/transaction/权限前置观察均PRESENT。Rotation对象均无普通轴；通过生产 `authoring.bindings.initialize_plan/initialize`，使用实际发现的ParamBodyAngleX参数GUID和真实min/default/max（-10/0/10），在Rotation4副本临时建立三点ordinary网格。准备步骤独立创建一个native Undo条目；按initializer真实cells重新读取formGuid及完整tuple，未猜GUID、未解锁或直接写默认form。它是专门的素材准备，不能称原素材原有Rotation rig已被发现。

[成功任务](live-host-20261025/ordinary-three-kinds.properties)及[请求、完整before/observedAfter/expectedAfter、哈希、收据和恢复结果](live-host-20261025/ordinary-three-kinds.json)来自`queue-261daa5b360e4965bbd4491cd0bbad75`：

- 一个`authoring.keyforms.write`请求含三个闭合target；ArtMesh4一个已存在非默认form的首顶点x增加0.125，Warp2对应控制点x增加0.125，Rotation4一个非默认form angle增加1。示例改动仅用于色块原语验收，不作头部建模算法或美术方案。
- 三个writer均返回APPLIED与readbackVerified=true，native历史位置只增加1。重新通过MCP读取全部rows，逐项核对source位置/UV/indices、未选form、opacity/drawOrder、轴/cell/default/parent等原有数据及明确修改值。该检查限定所选三对象；不扩张为全模型全部对象未变的逐行证明。
- 守卫Undo恢复全部准备后stored rows，守卫Redo恢复完整改后rows，最终守卫Undo再恢复；historyEntryId/transactionId及实时generation/revision都参与历史命令准入。
- 随后撤销Rotation绑定准备，三对象完整rows再次等于准备前快照。最终writeRestored=true/preparationRestored=true/status=PASS；其它已有Glue/参数/绑定/纹理矩阵仍通过。队列succeeded、normalExit/fixtureUnchanged/identityVerified=true、cleanup=safe，原素材文件和原安装保持不变。

没有把中途失败抹成成功：

1. [收据假定错误](live-host-20261025/ordinary-receipt-failure.properties)：三类writer已APPLIED，但客户端错误假定initializer receipt.historyAfter含position，清理抛KeyError并遮蔽主错误；本轮FAIL、safe，不能宣称准备步骤已恢复。修复为使用准备前后实际history位置和收据entry/transaction身份，保留主错误及独立cleanupError，不重放未知写入。
2. [精度比较失败](live-host-20261025/ordinary-float-comparison-failure.properties)及[完整差异](live-host-20261025/ordinary-float-comparison-failure.json)：实测仅Warp一坐标的JSON `356.53055`与Python预期`356.5305480957031`不同，两者是同一IEEE float32；三对象写入和临时准备均完整撤销，任务仍FAIL。现浮点字段逐float32位值比较，身份、索引、点数/拓扑和其它非浮点字段继续精确核对，拒绝不同原生位值、改变索引/身份及正负零差异，不使用容差掩盖变化。原始行和JSON哈希保留。

Python客户端 **23 tests通过**：新增非默认form计划与默认/source不变、成功Undo/Redo/恢复、读回失败守卫清理、OUTCOME_UNKNOWN不重放/无收据不盲Undo、真实float32序列化案例、整数/身份/符号零严格比较及有默认候选时选择可写对象。原只读collector14项通过、语法检查与git diff --check通过；本轮未修改生产setter/SDK/Runtime逻辑，不将第44节全量Java结果描述为本轮重新执行。

尚欠：写后实际目标pose的图像前后及视觉判断、宿主故障注入回滚、几何/绑定保存重开、官方Hiyori副本与原件hash、ACP真实模型看图/Swing展示。纹理native保存重开不是几何保存重开；三类原语通过不是头XYZ算法或Milestone A全部通过。整体目标仍active。


## 46. 三类ordinary目标pose写前/后渲染与扫描历史恢复（2026-10-25）

继续遵循Turboism `prepare/submit/status`队列，复用既有全局worker；第一次沙箱status因进程视图隔离误报worker离线，`serve`被worker.lock正确拒绝，没有新建第二worker、删除锁或停止现有进程。此后在真实宿主命名空间查询。任务 `94b86fd7-07a6-4bbe-b6f7-73463e473729` / `queue-c9d3a0769b7c4f9d895672e58dfe1e04` 最终succeeded，normalExit/fixtureUnchanged/identityVerified=true、cleanup=safe，版本与色块素材hash同第44节。

客户端从实际stored axes/cell完整GUID tuple选择可写且位于当前参数min/max内的form，再映射为无歧义parameterId的pose；不使用旧的±30存储key去驱动当前±10范围参数。仍按第45节typed initializer准备Rotation，初始化后重新读取form身份。仅测试副本中的Mesh/Warp首点x增加4个authoring单位、Rotation angle增加5；不是面部算法或坐标建议。

写前后各调用一次 `render.pose_grid`，3个显式pose、512×512、restorePolicy=always、30秒协作预算。核对initialState/全部initialParameters、frame index/label/captureId/typed image index、每pose相对原始baseline的参数、document/model/historyGeneration、PNG头/尺寸/长度/哈希。传输超时只查result，未知结果不重放扫描。扫描完成后验证参数恢复；如留下一个native Undo条目，必须以receipt.entryId/transactionId和实时history守卫撤销，再核对位置和全部参数，最后release图像缓存。清理失败单独记cleanupError并使报告FAIL，不能以扫描COMPLETED替代客户端验收。写前扫描后重新读取普通form快照，避免旧history revision用于CAS。

实机结果：

- [完整几何请求/前后数据及收据](live-host-20261025/ordinary-pose-three-kinds.json)、[目录与可逆矩阵结果](live-host-20261025/ordinary-pose-three-kinds.properties)。三类型APPLIED、完整float32位值回读、单步Undo/Redo/最终Undo以及Rotation绑定准备撤销均PASS，最终RESTORED。
- [写前扫描](live-host-20261025/ordinary-poses-before.json)与[写后扫描](live-host-20261025/ordinary-poses-after.json)均PASS，scanHistoryRestored=true。六张真实PNG已按类型before/after归档；[三行对照图](live-host-20261025/ordinary-pose-comparison.png)列为写前/写后/逐通道绝对差，不对原始PNG做增强。
- [像素计数](live-host-20261025/ordinary-pose-pixel-differences.json)：ArtMesh pose改变100像素，Warp pose269像素，Rotation pose416像素（RGBA任一通道不同）；差异界分别为[72,120,100,147)、[210,43,416,95)、[367,296,423,353)。图像可见色块边缘/控制网格及旋转变化，差异不是仅靠PNG元数据判断。三个编辑在同一事务中提交，pose分别激活完整目标tuple；不将这当作严格隔离其他编辑的逐原语像素因果实验。

执行 `python3 -m unittest scripts/test/test_mcp_host_validation_client.py -q`：**25 tests通过**。新增完整tuple/范围拒绝和typed image/pose/hash/receipt错配、超时result查询、unknown不盲Undo、release失败报告FAIL测试；原成功/失败/未知写结果与恢复状态机回归保留。`package-windows-mcp-validation.sh`成功（含66项打包准备测试），`git diff --check`通过。本轮未修改生产Java/SDK/Runtime、不冒称重跑第44节737/6142项Java测试。Pillow仅用于离线查看和对比制图，不成为客户端运行依赖。

仍欠三类几何真实宿主故障注入回滚、geometry/binding保存重开、官方Hiyori副本与头部质量、ACP真实视觉模型和Swing图片端到端。既有Glue回滚/纹理持久化不挪作上述证据，整体自动建模目标保持active。


## 47. 三类ordinary setter宿主故障注入与跨对象回滚（2026-10-25）

沿用第44–46节Turboism队列、5.3.03/Linux Proton及色块素材副本。新增测试专用[探针](../../testing/integration-tests/src/test/java/dev/turboism/tests/plugin/McpGeometryRollbackProbe.java)和[离线回归](../../testing/integration-tests/src/test/java/dev/turboism/tests/plugin/McpGeometryRollbackProbeTest.java)，由MCP lifecycle验证插件加载；只进入host-validation-probe.jar，未加入生产MCP、Runtime或SDK。生产工具仍79项，未增加generic invoke、反射或故障注入API。

客户端在Rotation普通绑定准备完成后，向本任务目录原子发布闭合、有界的run/document/model及三个实际object/source/form身份。探针校验run、task home/fixture归属和非symlink后在EDT执行，自己从typed SDK重读完整快照并构造固定测试增量（Mesh/Warp首点x+1，Rotation angle+1）。每个独立事务分别写完第1、第2、第3个对象后抛InjectedFailure；抛出前每个setter必须已经返回、完整native快照精确等于预期，否则该案例失败。没有把preflight拒绝当作写后回滚。SDK仍负责默认/shared/form范围、CAS与Undo贡献准入。

[成功原生报告](live-host-20261025/geometry-rollback-native.json)来自job `77c17958-ba61-4ad3-b7d0-823106af693b` / run `queue-f9f054de04f246a2aa9184483c2f7838`：

- 三个边界verifiedSetterCount分别为1/2/3（共六次实际setter），injected=true，返回ROLLED_BACK；三份收据分别authoring-5/6/7，diagnostic明确InjectedFailure，无committed entry。
- 每次全部三对象完整SDK record精确equals原始记录，包含source/UV/topology、axes/cells/forms、drawOrder/opacity及Rotation基准等各快照已有字段。before、transient和restored哈希均保留，三个修改后的哈希与各自原值不同，三次恢复哈希全部等于baseline。哈希编码为SDK record toString UTF-8 SHA256，仅是本探针内完整record等值核对的索引，不冒充通用CMO3文件格式指纹。
- 两套历史观察器各自的完整before/after相等。公开历史为generation=1/revision=16、binding ID后缀2，事务宿主为generation=3/revision=6、binding ID后缀1；各自position=1/entryCount=1，entries哈希相同。scope额外核对实际document/model，两个观察器位置和条目数一致。不得把这两套generation/revision/opaque ID混为同一命名空间。
- 客户端独立通过MCP重新读取三对象全部rows，按float32位值和精确身份再次验证恢复，render.state的document/model/historyGeneration/poseHash前后相同。随后重新取得快照，原有三类普通write、目标pose前后图像、Undo/Redo、最终Undo及Rotation准备撤销全部通过，最终RESTORED。见[完整MCP/几何证据](live-host-20261025/geometry-rollback-three-kinds.json)及[整轮结果](live-host-20261025/geometry-rollback-three-kinds.properties)。
- 队列succeeded，normalExit/fixtureUnchanged/identityVerified=true、cleanup=safe，原素材及安装保持不变；[队列摘要](live-host-20261025/runs.json)保留冻结preparedId。

保留两轮失败，不伪报成功：

1. [观察器完全相等假定失败](live-host-20261025/geometry-rollback-observer-failure.json)、[native失败](live-host-20261025/geometry-rollback-observer-failure-native.json)与[整轮FAIL](live-host-20261025/geometry-rollback-observer-failure.properties)：探针直接将收据历史与公开history.snapshot比较，忽略两套EditorHistorySnapshotProvider的observer-local revision/binding ID。客户端停止后续写入，未声称Rotation准备已撤销。
2. [跨域generation假定失败](live-host-20261025/geometry-rollback-generation-failure.json)、[native失败](live-host-20261025/geometry-rollback-generation-failure-native.json)与[整轮FAIL](live-host-20261025/geometry-rollback-generation-failure.properties)：修正revision/ID比较后仍错误要求两套generation相同。源码HostSession.history使用Editor UI lifecycle generation，EditorBackedCubismModelAccess.authoringHost使用native model binding generation；临时开关文档后两者不同。最终按各自完整baseline独立核对，不放宽生产CAS、SDK恢复检查或原生writer准入。两个失败任务均cleanup=safe，失败任务未完成的normalExit/fixtureUnchanged等字段不当作成功哈希证明。

离线执行：

```sh
./gradlew :testing:integration-tests:spotlessJavaApply :testing:integration-tests:test --tests '*McpGeometryRollbackProbeTest'
python3 -m unittest scripts/test/test_mcp_host_validation_client.py -q
python3 -m unittest discover -s validation/acp-auto-modeling-capability-audit -p 'test_*.py' -q
bash scripts/preview/package-windows-mcp-validation.sh
git diff --check
```

Java XML **4 tests/0失败/错误/跳过**，包括任务/symlink/闭合请求和完整三边界恢复、未执行回调、假setter、未选字段污染、recovery-failed、文档切换拒绝；fixture明确使用不同generation/revision/binding命名空间。客户端 **26 tests通过**，新增各边界、实际变化哈希、完整恢复历史证据、receipt/foreign/incomplete拒绝及native恢复未知则停止后续MCP写入。collector14项、打包准备66项及构建通过。实机冻结后又收紧客户端报告历史字段完整性与失败时清理分支；当前更严checker已在临时目录对本次原始成功报告离线重新验证PASS，没有重放任何宿主操作，也不宣称这两项最后调整重新上机。没有本轮全量生产Java/SDK/Runtime重跑声明。

日志：`/tmp/turboism-geometry-rollback-tests.log`、`/tmp/turboism-geometry-rollback-package.log`，私有路径及原始日志保留在本机任务目录，仓库证据无Bearer/会话头/原始连接配置。

范围：本次故障是测试SDK事务callback在真实setter之后抛异常，复用生产普通writer原语；不是生产MCP keyforms.write内部故障注入、HTTP响应丢失、进程崩溃或取消验收。尚欠geometry/binding磁盘保存重开、官方Hiyori头部与ACP模型看图/Swing展示，不能宣称Milestone A或整体自动建模已完成。


## 48. 三类ordinary几何及绑定磁盘保存/关闭/重开（2026-10-25）

继续使用Turboism prepare/submit/status队列、既有worker、第44节5.3.03/Linux Proton及色块素材副本。本轮新增几何持久化验证入口，复用测试专用McpTexturePersistenceProbe已有的exact host JAR hash校验、任务路径所有权、新建输出检查、原生save/close/open以及不确定超时停止策略；没有向生产MCP暴露反射或文件路径。几何输出使用独立geometry-persistence目录，避免与先执行的纹理持久化产物冲突，不覆盖原素材或旧证据。

任务 `d548a4b8-3626-4381-b3b9-499b72bfb8ce` / run `queue-fee910336e924090bd4d5b6024907a45` 一次实机通过。按[整轮报告](live-host-20261025/geometry-persistence-three-kinds.properties)、[三类数据/请求/收据/扫描及恢复证据](live-host-20261025/geometry-persistence-three-kinds.json)和[native文件验证报告](live-host-20261025/geometry-persistence-native.properties)：

1. 生产MCP完成此前三类型ordinary写入、完整float32位值回读、目标pose渲染、真实SDK异常回滚；守卫Undo、Redo、最终Undo后再次Redo恢复已验证的修改状态。仍是ArtMesh4、Warp2及通过typed initializer临时准备的Rotation4，未猜测或直接写default/shared form。
2. 外部客户端将实际document/model/object/source/form身份写入任务内闭合有界请求。测试探针核对归属和活动文档，重新通过typed SDK读取三对象完整authoring records及全部可见参数绑定，保存前再次确认精确一致。只有测试探针调用已审阅的原生saveDocument输出全新任务内CMO3；成功返回后等待实际字节/hash稳定，不把原生命令返回值当作完成证明。
3. 保存CMO3 SHA-256为 `5b412bf6de0fb4ad0c475c60cad19b82ce1ffc05023e3e51b26ffe6fbc4e6c47`。关闭保存文档、重开该输出，断言新的native document对象不同于已关闭实例；完整SDK record逐字段equals保存前状态，覆盖各快照包含的source坐标/UV/topology、全部axes/cells/forms、未选form、绘制顺序/opacity、Warp grid及Rotation baseAngle等字段。三个哈希在报告中保存前/重开后逐一相同；不是仅验证三个修改坐标。
4. 全部SDK可见ParameterBinding记录同样精确equals，保存前/重开后指纹均 `8d9b271373452d28329dc4a292fbf7a826a57f148c8df712f3886f735f0a4384`。本例包括本轮初始化的Rotation绑定；不推广为任意Morph/所有宿主版本持久化通过。指纹编码仍为SDK record toString的UTF-8 SHA256，本探针同时执行完整record等值核对，不把格式化字符串哈希当通用CMO3内容规范。
5. 关闭输出副本、重开原任务副本，原素材文件hash不变，纹理原始投影恢复。外部MCP重新发现document/model/history状态并读取三对象全部rows，与本轮任何准备/写入之前的原始基线逐项一致；最终status=PASS、phase=RESTORED、nativeRollbackVerified/geometryPersistenceVerified/writeRestored/preparationRestored=true。几何渲染六张before/after PNG已按geometry-persistence-art_mesh/warp_deformer/rotation_deformer命名归档。
6. 队列succeeded，normalExit/fixtureUnchanged/identityVerified=true、cleanup=safe；原素材与安装前后hash由受管队列核对，[脱敏摘要](live-host-20261025/runs.json)保留preparedId。保存的CMO3留在私有任务证据目录，未把模型二进制加入仓库。

文档重开会建立新文档/model/history绑定。因此保存流程一旦dispatch，客户端不再用旧几何或准备步骤的历史收据清理；失败/超时/结果未知时停止后续写入、不重放文件请求。只有native报告全部通过、外部MCP原始rows再次核对通过后，才报告恢复成功。此前数值Undo/Redo检查仍独立执行；没有用“重开后画面相似”替代数值历史验证。

离线执行：

```sh
./gradlew :testing:integration-tests:spotlessJavaApply :testing:integration-tests:test --tests '*McpTexturePersistenceProbeTest' --tests '*McpGeometryRollbackProbeTest'
python3 -m unittest scripts/test/test_mcp_host_validation_client.py -q
python3 -m unittest discover -s validation/acp-auto-modeling-capability-audit -p 'test_*.py' -q
bash scripts/preview/package-windows-mcp-validation.sh
git diff --check
```

Java两套XML共 **15 tests/0失败/错误/跳过**（纹理/文件探针11、几何回滚4）；新增geometry输出与既有texture产物隔离、拒绝已存在目标、原fixture不变案例，原路径/symlink保护与回滚拒绝回归保留。客户端 **27 tests通过**：成功/foreign/非fresh native document/几何hash不匹配/绑定不匹配/无文件hash/缺一种对象/timeout拒绝，矩阵中新增文件结果未知时不执行旧Undo、不重放write的案例。collector14项、打包准备66项及构建通过。日志 `/tmp/turboism-geometry-persistence-tests.log`、`/tmp/turboism-geometry-persistence-package.log`；未声称重跑生产MCP/Runtime全量Java测试。

本轮没有新增生产API或修改SDK/Runtime；生产目录仍79工具/15资源/2模板/8提示词。生产documents.save_as的文件grant与原生命令只是另一条待实机验收路线，OPEN仍未由当前MCP开放且Runtime需核验/补实现；本探针不证明Agent已经可以独立完成文件重开。尚欠官方Hiyori头部与原件保护、ACP真实视觉模型/Swing图片链路和可恢复建模任务编排，Milestone A及整体自动建模目标保持active。


## 49. 生产授权 CMO3 OPEN 分派与已有标签页实机验证

新增 `turboism.documents.open({operationId,grantId,documentSnapshotId})`，生产目录由79增为80工具，资源/模板/提示词仍15/2/8，有限账本18 owners/166 entries不变。文件授权必须同会话READ且扩展名仅cmo3，使用documents.read签发的不可变目录/authoring快照；消费前与分派前完整比对，二次校验TTL，目录满额拒绝。OPEN/SAVE_AS共享128个不淘汰operation结果，同ID跨命令冲突；结果查询与相同请求重放不读宿主、不再次调用。取消只约束原生准入前，原生开始后异常/不确定响应保守OUTCOME_UNKNOWN。

Runtime新增CMO3 OPEN typed实现，仅exact 5.3.03准入，复用已审阅CEAppCtrl.command_open(File,boolean)与existing/regular/canonical/use-point保护。要求MODEL_READ+MODEL_WRITE+FILE_READ，权限不足不解析授权句柄。不开放PSD、raw path、任意命令或关闭文档。selector/DRAFT/template与两层版本准入例外均显式声明，不扩大为5.3.04或所有版本支持。TopMenu manifest的新增必需别名只属于5.3.03；其他版本保持原集合。

EXECUTED / NATIVE_OPEN_DISPATCHED_REDISCOVER_DOCUMENTS仅表示native调用返回，可能聚焦既有标签页；afterState只作best-effort观察。不得用其声称新文件已完成加载、文件身份已核对、磁盘保存或重开内容已验证；persistenceVerified/reopenVerified/retrySafe始终false。后续必须documents.read重新发现，再读取实际几何。

### 第一轮实机证据

任务 `cf24c20d-5a95-442f-924d-5c449a6cef8a` / run `queue-7838088db79a43e9ac6667b642b7c344`，仍使用第44节5.3.03/Linux Proton与色块副本，遵循标准prepare/submit/status、复用既有worker。真实生产files.request_grant触发Swing chooser；测试专用响应器仅批准一次精确READ用途、cmo3过滤器、当前任务已打开的fixture，校验任务目录所有权、canonical/regular路径，不向生产注入grant，不传路径给MCP。测试批准不冒称人工点击。

生产授权消费、OPEN调用、file_result结果核对、相同请求重放、CONSUMED状态均通过。documents.read前后完整目录与authoring状态一致，实测范围为ALREADY_OPEN_TASK_COPY。队列succeeded，normalExit/fixtureUnchanged/identityVerified=true、cleanup=safe。见[授权打开证据](live-host-20261025/authorized-open-existing.json)、[整轮报告](live-host-20261025/authorized-open-existing.properties)、[队列摘要](live-host-20261025/runs.json)。首次未打开CMO3加载、无文档首次打开、PSD导入、生产SAVE_AS→关闭→OPEN的持久化链仍未验收。

全量Runtime检查曾发现新增alias被误列为所有版本通用必需项及旧5.3.03记录hash断言未更新，已修正为按版本manifest和精确新hash；不删除准入检查、不补造其他版本selector。最终回归与最终产物复测结果在下段补记。

### 离线检查与中途诊断

生产MCP全量741项通过（含文件Domain19项）；Runtime定向56项通过，Python客户端28项、collector14项通过，Pi桥接（含OPEN拒绝/批准、80工具分页）专项通过，打包准备66项通过。第49节新增链接后135个本地文档链接存在，git diff --check通过。日志位于本机/tmp/turboism-authorized-open-*，不属于仓库CI证据。

第二次全量Runtime在修正manifest后仍使用编译前旧hash断言，且一个已有截图测试随后阻塞：线程栈显示AWT事件线程在sun.awt.screencast.ScreencastHelper.getRGBPixelsImpl，Test worker等待ScreenshotCaptureHostOperationsTest的finally窗口dispose。已核对本轮Gradle Test Executor身份后仅终止该测试JVM，未操作Cubism或无关进程。旧hash断言已按新增selector的materialized record hash更新，并新增5.3.02/03/04 alias隔离测试；后续改用隔离Xvfb重跑，不能将中断运行列为通过。

隔离Xvfb全量最终6145项/1失败/4跳过：新增manifest/权限/OPEN回归均通过，唯一失败为既有ScreenshotCaptureHostOperationsTest窗口截图超时，测试进程仍继承Wayland环境；该轮随后自行结束，第二次身份核对时已无需要终止的executor。没有改动截图生产代码或降低断言。随后清除DISPLAY/WAYLAND_DISPLAY运行无桌面离线套件，图形测试按原有条件跳过；不能把离线通过称为桌面截图验收。

无桌面环境最终全量Runtime：**6145 tests / 0失败 / 0错误 / 43跳过**，6102项实际通过；新增static manifest隔离测试在内。43项为现有显示/宿主条件跳过，不声称桌面截图通过。生产MCP全量741项无失败/错误/跳过；Runtime/MCP spotlessJavaCheck通过。最终客户端28项与Pi专项通过。无桌面命令及日志：

```sh
env -u DISPLAY -u WAYLAND_DISPLAY XDG_SESSION_TYPE=x11 ./gradlew :runtime:test
```

`/tmp/turboism-authorized-open-full-runtime-headless.log`；Xvfb失败日志另留`/tmp/turboism-authorized-open-full-runtime-xvfb.log`。

### 最终产物复测

修正版本manifest、精确hash断言并限定实机客户端版本后重新打包，准备检查66项通过。最终任务 `56c5b734-f3a6-4f07-907a-191b08933323` / run `queue-5cd0ff0e079f437bb5ae55b640855369`，preparedId `0eaa82369104e6e2f5dad3cd57ef8e2c05e101474f9c0bc099afba589b076076`。队列succeeded，normalExit/fixtureUnchanged/identityVerified=true、cleanup=safe。生产80工具目录与本轮全套三类几何读写/原生渲染/回滚/保存重开矩阵再次通过，最后真实READ chooser授权、OPEN分派、结果核对/重放及前后目录一致性通过。见[最终OPEN证据](live-host-20261025/authorized-open-final.json)、[最终整轮报告](live-host-20261025/authorized-open-final.properties)及runs.json；仍仅ALREADY_OPEN_TASK_COPY，不扩大first-open/persistence结论。最终源码构建对应本次prepared快照；原素材和安装保持不变。

本切片完成受控OPEN入口及已有标签页生产链路验证；下一切片须验证未打开CMO3的真实加载、无文档启动与生产SAVE_AS→重新加载→几何/绑定核对。官方Hiyori头部、ACP实际视觉模型/窗口消费、可恢复建模任务、L2/P2仍未完成，持续目标保持active。


## 50. 无活动文档后的生产 CMO3 加载验证

第49节只有已有标签页focus证据，本轮新增仅验证包使用的McpAuthorizedOpenProbe，不向生产MCP暴露close、反射、路径或测试控制开关。probe先校验exact5.3.03 native JAR hash、任务fixture/home归属、请求run/document/model/source/form身份，读取三类完整SDK authoring records及全部SDK可见ParameterBindings。仅关闭该任务文档一次，以有界只读等待确认native currentDoc与SDK activeDocument/activeModel均为空；超时/异常保留FAIL，不自动重试关闭、打开或用旧Undo收据恢复。

外部客户端随后由documents.read确认空目录，真实files.request_grant READ/cmo3-only授权，使用该空状态snapshot调用生产documents.open一次。等待公开目录出现MODEL与authoring state，重新发现文档/模型身份并分页读取三对象全部rows；比较source GUID及float32位值精确rows。第二阶段probe只读核对native文档引用不同于关闭前、实际文件仍为任务fixture、完整三类SDK records与全部bindings等值，核对SDK_RECORD_UTF8_SHA256和fixture hash。该结果是测试验收证据，不把dispatch响应的persistenceVerified/reopenVerified改成true。

测试chooser响应器对已有标签页和无文档加载两个精确READ用途各批准一次，只选canonical/regular的任务fixture；不向MCP返回路径、不注入grant、不代替真实人工批准记录。新probe随validation/mcp-host-probe/build.sh编入测试包，不能进入发行插件。

离线：Java首次加载探针3项、既有请求/任务身份回滚4项共7项通过；Python客户端30项通过，包含目录未清空、UNKNOWN不重放、加载未就绪、rows变化、非新native文档、部分hash及foreign/FAIL native证据拒绝。打包准备66项通过。SDK/Runtime未改；根据首轮实机结果修复MCP空目录准入，见下文。不挪用上一轮741/6145测试数量作为本轮重跑结果。日志为/tmp/turboism-fresh-open-*。实机已提交标准prepare/submit队列，后续按真实status和证据补记结果。

首轮任务 `f401d39e-ffd6-4c33-b1d1-e9efc10e2f41` / run `queue-b580d78685f745acbd4a27d6bb0cc688` 未通过：probe已确认关闭任务文档且SDK/native均为空，公开documents.read返回hostObserved=false、project/activeDocument/authoringState均null；生产OPEN在消费grant前返回NOT_APPLIED/UNAVAILABLE。源码HostSessionSnapshotSource.isHostPresent实际上仅表示当前project/document存在，并不代表命令服务脱离宿主。McpDocumentFileDomain误把它当OPEN准入，导致不能从空Editor加载。

修复仅移除这个错误前提，继续由commands.isAvailable、Runtime exact selector/权限/句柄校验决定准入；目录快照/CAS/TTL/容量和READ授权校验全部保留。单测改为真正project=null且activeDocument=null的成功用例，并新增空目录但命令服务不可用时不消费grant的拒绝用例。不改变快照hostObserved语义、不伪造有活动文档。首轮结果保留为FAIL，queue cleanup=safe，其余聚合证明标记未通过，不宣称正常退出或完整验收。见[失败客户端证据](live-host-20261025/fresh-open-empty-admission-failure.json)、[关闭确认](live-host-20261025/fresh-open-empty-admission-closed.json)、[失败整轮报告](live-host-20261025/fresh-open-empty-admission-failure.properties)。没有重放本轮OPEN或执行旧Undo。

### 修复后实机通过

复测任务 `20fdcfdc-bcd0-41be-b4d1-4b2339987d68` / run `queue-4b6f0b28061b41629768b25312702c52`，preparedId `678cafa6519893f5b8e80b7dc8fae0724c1f724fbbd56e6090ed0506fc559ae7`。仍为5.3.03/Linux Proton、第44节色块副本及既有标准worker。

- 先完成上一轮普通三类几何写入、pose渲染、真实故障回滚、测试专用原生保存重开和最终恢复矩阵；已有标签页的生产授权OPEN回归继续通过。
- 精确任务文档关闭后，native currentDoc与SDK活动文档/模型为空，公开documents.read确认hostObserved=false、project/activeDocument/authoringState=null。
- 真实READ chooser第二个精确用途批准、一次性grant消费、空目录snapshot准入、生产documents.open分派及file_result核对通过；生产响应仍不自行宣称就绪或重开验证。
- 新目录实际出现MODEL和authoring state；三类对象的source GUID和全部MCP rows精确一致，按新文档/model/history身份重新读取，不使用旧快照/Undo收据。
- 原生文档引用确实不同于关闭前；文件身份仍为任务fixture。三类完整SDK authoring records及全部SDK可见ParameterBindings record equals通过，before/after SDK_RECORD_UTF8_SHA256分别一致，fixture hash保持`2980dc8c695b13eeb54e37c02832edf80607979f2cc721ea9ca7b671f8bbac10`。
- 结果为READY_VERIFIED / CLOSED_TASK_COPY_FRESH_LOAD。队列succeeded，normalExit/fixtureUnchanged/identityVerified=true、cleanup=safe；原素材与安装保持不变。

证据：[完整客户端请求/目录/三类rows核对](live-host-20261025/fresh-open-verified.json)、[关闭确认](live-host-20261025/fresh-open-closed.json)、[native身份/几何/绑定核对](live-host-20261025/fresh-open-native.json)、[整轮报告](live-host-20261025/fresh-open-verified.properties)、[队列摘要](live-host-20261025/runs.json)。首次加载这里精确定义为关闭任务副本后从无活动文档状态重新加载，native文档是新对象；不声称测试了从未打开过的其他素材、全部宿主或PSD。

修复后完整MCP **742项通过/0失败/错误/跳过**，Java探针7项、Python客户端30项、collector14项与打包准备66项通过，git diff --check通过。没有重跑Runtime全量，也未修复或重验第49节Wayland窗口截图问题。生产目录保持80/15/2/8。

下一项：真实生产WRITE grant→SAVE_AS新目标→关闭任务副本→READ grant→OPEN输出文件→确认已修改几何和绑定持久化→恢复原任务文档；不能以本轮重新加载原始fixture替代该生产持久化链。官方Hiyori、ACP视觉模型与Swing图像实际消费、可恢复建模编排及L2/P2仍未完成，持续目标保持active。


## 51. 生产授权 SAVE_AS→OPEN 的修改持久化验收

本轮目标是生产MCP文件工作流，不以此前测试专用native.saveDocument/open流程或原始fixture重开代替。复用并扩展仅验证包的McpAuthorizedOpenProbe，添加原始全量SDK records/全部bindings基线、保存输出关闭/读取验证、关闭输出后原fixture恢复校验三个阶段；所有native路径/方法仍为exact5.3.03、任务所有权固定、无任意参数反射。probe不执行save/open，这两步必须走真实生产grant和documents.save_as/open。

客户端先用生产initializer为原本无普通轴的Rotation创建真实绑定，读取真实form再在一个typed事务修改Mesh/Warp/Rotation普通非默认form。preview/完整rows回读、修改前后pose原生图像必须有差异。WRITE grant只能选择任务state/authorized-save中的新CMO3，明确REJECT_EXISTING；chooser扩展为几个精确用途各一次，不接受路径输入或覆盖。授权后刷新六字段expectedState并调用生产SAVE_AS，响应丢失只读file_result核对，OUTCOME_UNKNOWN立即停止后续文件/恢复写入。

保存后probe校验实际文件与doc/model/source/form身份，记录完整修改后SDK records及全部bindings，且两者必须相对原始基线有变化；只关闭该任务文档。READ grant/生产OPEN输出文件后重新发现身份，全量rows、SDK records、bindings及保存字节hash一致，三个修改后pose与重开后PNG hash一致。随后probe关闭精确已验证输出文档，生产READ grant/OPEN原fixture，最后原始三类records/全部bindings/fixture hash及输出hash一致。文件重开后不执行任何旧Undo收据。

离线：Java探针5项+回滚/身份4项共9通过；Python客户端33项通过，增加新文件/任务归属/symlink拒绝、响应丢失只读核对、非空目录/未就绪拒绝、SAVE_AS未知后不OPEN/不旧Undo/不触发native关闭的案例。本轮随后完成标准实机队列，结果与证据见下文。未重跑完整MCP/Runtime套件，不能挪用第49–50节742/6145作为本轮重跑数量。


### 2026-10-26 标准实机结果：通过

任务 `a9402ce6-82ae-4a91-b192-942853301514` / run `queue-02530bdade5e4c0a952978c3e33b3764`，preparedId `2e69dd6cf570ef59684c1e95248510404d8e8493d19fce7a2623874c4d1d9e20`。仍走Turboism标准prepare/submit/status与既有worker，exact5.3.03/Linux Proton、第44节色块/Glue任务副本。UTC日志为2026-10-25T16:37，本地Asia/Shanghai日期为2026-10-26。

- 生产initializer实际新增Rotation普通绑定，三类typed ordinary writer各修改一个真实非默认form；所有三类完整MCP rows精确回读。完整SDK records与全部SDK可见bindings相对原始基线均改变。
- 真实WRITE chooser授权任务内全新CMO3，生产`documents.save_as`使用REJECT_EXISTING与刷新后的六字段expectedState；授权消费、分派和file_result核对通过。测试probe没有调用save/open。
- probe仅关闭精确任务文档，SDK/native/公开目录确认空；真实READ授权与生产`documents.open`加载输出，新native文档引用、实际输出文件身份与就绪状态均验证。
- 重开后的全部三类MCP rows、完整SDK records和全部SDK可见bindings等值。保存输出SHA256为`3be38eb8a62a6e45fbe7ec6505f3dd1aa5ebd485f63e8d0bae66c1cd45e7fd68`，重开和恢复原文档后保持不变。
- 三个修改后pose PNG与重开后逐张SHA256一致，且修改前后均有图像差异；每组pose参数恢复与图像释放均通过。图像是512×512原生模型renderPixels输出，非桌面截图或recent-file缩略图。
- 最后只关闭已验证的输出文档，通过生产READ grant/OPEN恢复原任务fixture；原始三类完整SDK records、全部bindings与原始hash恢复/保持。原件SHA256仍为`2980dc8c695b13eeb54e37c02832edf80607979f2cc721ea9ca7b671f8bbac10`，没有对重开文件执行旧Undo。
- 标准队列succeeded、validationStatus=PASS、normalExit/fixtureUnchanged/identityVerified/goldenUnchanged=true、cleanup=safe。本轮整轮报告包含生产保存重开断言及此前几何Undo/Redo/故障回滚/授权OPEN回归；不扩张为全部版本或桌面截图测试通过。

归档：[完整客户端数据/操作收据/姿态元数据](live-host-20261026/production-save-open-verified.json)、[原始基线](live-host-20261026/production-save-baseline.json)、[保存后关闭确认](live-host-20261026/production-save-closed.json)、[native持久化核对](live-host-20261026/production-save-native.json)、[输出文档关闭](live-host-20261026/production-save-restore-closed.json)、[原件恢复](live-host-20261026/production-save-restored.json)、[整轮报告](live-host-20261026/production-save-open-verified.properties)、[队列与9张PNG索引](live-host-20261026/runs.json)。保存的CMO3二进制留在私有任务目录，不加入仓库；未归档连接描述符或原始凭据。

| pose | 修改前 | 修改后 | 重开后 |
| --- | --- | --- | --- |
| ArtMesh | [PNG](live-host-20261026/production-save-before-0.png) | [PNG](live-host-20261026/production-save-modified-0.png) | [PNG](live-host-20261026/production-save-reloaded-0.png) |
| Warp | [PNG](live-host-20261026/production-save-before-1.png) | [PNG](live-host-20261026/production-save-modified-1.png) | [PNG](live-host-20261026/production-save-reloaded-1.png) |
| Rotation | [PNG](live-host-20261026/production-save-before-2.png) | [PNG](live-host-20261026/production-save-modified-2.png) | [PNG](live-host-20261026/production-save-reloaded-2.png) |

本轮仅增加测试包/客户端与证据，不新增生产工具；目录保持80工具/15资源/2模板/8提示词。生产SAVE_AS/OPEN响应仍不自行承诺persistenceVerified或reopenVerified；这些事实由本轮外部核对证明。该结果补齐本色块副本的生产文件持久化链，不代表官方Hiyori头部美术、ACP视觉模型和Swing显示、可恢复建模编排、L2/P2或全部宿主已验收，Milestone A与持续目标仍未完成。

归档后复核：本审计目录167个本地Markdown链接全部存在，9张PNG逐张SHA256与原始render元数据/索引一致；归档文本未包含Bearer、连接endpoint或MCP session header，git diff --check通过。


## 52. 原生渲染图经 ACP-v1 到实际 Swing transcript

第51节完成生产保存重开后，本轮补A8/H5的窗口通路。现有acp标准任务此前仅通过脚本代理执行rename/Undo；新增仅验证包流程：5303任务向脚本代理传递显式render验收开关，其余版本保持关闭。代理通过生产stdio bridge有界分页发现工具，读取实际render.state，调用生产render.capture(256×256)，核对typed PNG/metadata、原生renderer身份、完整state/parameters、PNG尺寸/字节/hash及捕获前后状态不变，然后发送真实ACP tool_call/tool_call_update，原样保留text/image类型。session身份响应先发送，再启动脚本场景，避免通知先于session/new响应。

宿主probe读取独立原生PNG/metadata，在EDT检查实际Agent窗口的StyledDocument：仅一个256×256图标、全部ARGB像素一致、工具/capture/document/model/revision/尺寸标签一致、正文无对应base64，并等待生产预览SwingWorker完成；不是给UI注入图标，也不反射插件私有字段。通过后输出实际窗口与图标像素截图。此处使用脚本ACP-v1代理，不代表真实视觉模型消费，也不代替三种native harness验收。

离线：脚本代理正常流通过，损坏hash/state/type三种结果在转发/输出PNG前全部拒绝；独立Swing matcher六项断言通过（正常、无图、错误像素、过期capture、base64文本、重复图标）。本轮尚未取得新增窗口链的实机结果，后文按标准prepare/submit/status结果补记。日志为/tmp/turboism-acp-render-*；未新增生产工具/API或修改SDK/Runtime。

首轮任务`e9ccd324-b53f-4e2f-b856-fc7e6ed83617` / `queue-a353fe2483a44a0a884d28457fc09b76`在探针依赖解析阶段失败，未进入图片链：当前构建MCP/ACP版本均为0.67.2，测试探针仍限制[0.1.0,0.2.0)，Runtime明确DEPENDENCY_FAILED。已经标准队列取消请求结束，终态failed、cleanup=safe；不把该轮启动或原生OpenCL日志当图像验收。见[失败记录](live-host-20261026/acp-native-render-dependency-failure.json)。修复仅更新测试探针依赖到生产ACP当前使用的[0.1.0,0.68.0)窗口，新增打包时按实际stamped JAR检查三插件身份与required范围；4项离线案例通过（当前版本、旧范围拒绝、错误身份、未知范围失败闭合）。未放宽生产依赖或版本准入。ACP JAR构建与spotlessJavaCheck通过；后续按新prepared任务重新验收。

依赖修复后任务`2a09f9fe-261a-4e7e-8843-ba48b9a3fe9d` / `queue-af0a07c3a2034719857d757b0104b262`中，脚本代理的原生render/typed转发和rename/Undo均通过，但窗口断言失败；保留[宿主报告](live-host-20261026/acp-native-render-ui-failure.properties)、[代理报告](live-host-20261026/acp-native-render-ui-failure-agent.properties)、[原生metadata](live-host-20261026/acp-native-render-ui-failure-render.json)。进一步添加逐字段诊断与失败窗口，而非降低标准。任务`a3951b6a-a243-4d53-9f28-156da35949f1` / `queue-2686c04806d6494eb715d8b5c7f9bbef`证明一个256×256图标全部ARGB精确一致，capture/document/model/revision/尺寸与无base64正文全部通过，只有测试的toolLabel预期错误。生产AcpClient.toolCallKey始终SHA-256化原始tool ID；测试却要求原始字符串。见[字段诊断](live-host-20261026/acp-native-render-tool-key-failure.properties)和[实际窗口](live-host-20261026/acp-native-render-tool-key-failure.png)。两轮仍保留FAIL、cleanup=safe，不抹去失败。宿主结束阶段出现未批准的modal时，测试关闭helper失败闭合并由标准队列清理，不点击未知对话框。

修复只使独立测试匹配固定原始ID的完整SHA-256稳定键及边界，不改变生产标识或UI，也不放宽其他像素/来源检查；Swing matcher新增错误tool键拒绝，共7项断言通过。等待新标准队列结果。

工具键修正后任务`c2c1fad6-ede3-4bf9-a7aa-3df23f359c3f` / `queue-f771672fd07d4a49b0dbb3679396ad04`全部图像与UI/会话断言PASS，但宿主关闭确认框owner落在ACP frame4，而非已选定的任务Cubism窗口；安全关闭helper拒绝点击。标准队列取消请求后终态failed、cleanup=safe，不将局部PASS报为整轮正常退出。见[窗口通过/退出失败报告](live-host-20261026/acp-native-render-close-failure.properties)。

源码显示仅Robot路线会先聚焦精确宿主，WINDOW_CLOSING路线直接发事件，可能让保存确认框继承前台插件窗口。测试专用WindowsHistoryNativeUiHostClose现在所有路线均先聚焦并证明活动窗口为精确任务目标，再触发关闭；不扩大对话框owner归属、不点未知modal、不改生产代码。既有31项身份/路线/语义discard/失败闭合回归全部通过；新实机将核验正常退出。该变更使“必须是精确宿主焦点”的约束更严格，不放松副本/版本/文件名/对话框验证。


### 窗口链完整复测通过

任务`fc419ae2-960e-4b1a-82fc-dac4edf9ab7d` / run `queue-dc403097f8c245a38aae4190a37b0742`，preparedId `2cb8a8c6f5a583cfb90bafe8112feb870e0642460ce4f58782a5c2460bc00c4b`。标准prepare/submit/status、既有worker、exact5.3.03/Linux Proton和同一色块任务副本；队列succeeded、validationStatus=PASS、normalExit/fixtureUnchanged/identityVerified/goldenUnchanged=true、cleanup=safe。关闭前精确宿主焦点证明后正常退出通过，没有再批准或点击外部窗口的modal。

- 生产render.capture的typed PNG、state/pose/parameters/renderer、SHA256与捕获后无状态改变通过；PNG hash为`46eed740b85502158891dfd4d23d29626737499f071a691ae85f9ca1aff7d623`。
- 真实ACP-v1事件到生产AcpClient/Controller/ChatWindow，窗口实际StyledDocument只有一个256×256图标，全部65536个ARGB像素与原生PNG精确一致。
- 固定tool ID的完整SHA-256键，以及capture/document/model/revision/尺寸标签全部匹配；聊天正文无图片base64。生产预览worker已完成后才截取实际窗口。
- credential-free stdio发布/接入、分页工具发现、任务对象rename/回读/guarded Undo/历史平衡/SDK原名恢复、会话绑定和仅Turboism-owned会话显示等回归均通过。原件SHA256保持`2980dc8c695b13eeb54e37c02832edf80607979f2cc721ea9ca7b671f8bbac10`。

证据：[宿主报告](live-host-20261026/acp-native-render-verified.properties)、[脚本代理报告](live-host-20261026/acp-native-render-agent.properties)、[原生metadata](live-host-20261026/acp-native-render.json)、[原生PNG](live-host-20261026/acp-native-render.png)、[实际图标像素PNG](live-host-20261026/acp-transcript-render.png)、[实际ACP窗口](live-host-20261026/acp-agent-window.png)、[含全部失败/复测的队列摘要](live-host-20261026/runs.json)。本轮未新增生产工具或改SDK/Runtime/ACP主源码，目录保持80/15/2/8；关闭助手仅位于测试包。没有以局部PASS覆盖前四轮失败/非正常退出记录。

本轮推进A8/H5的真实宿主窗口显示，仍仅是脚本ACP-v1通路；真实Pi/Codex/Claude视觉模型消费、恢复/取消/权限专项、官方Hiyori头部美术和L2/P2尚未通过。Milestone A与持续目标不标完成。

## 53. 本机真实 native CLI 的隔离握手

确认本机安装Pi 1.2.0、Codex CLI 0.159.2、Claude Code 2.1.284后，运行既有opt-in NativeRealCliSmokeTest：`TURBOISM_NATIVE_SMOKE=1 ./gradlew :plugins:acp:test --tests dev.turboism.plugin.acp.NativeRealCliSmokeTest`。3项通过、0失败/错误/跳过，逐项确实执行CODEX/CLAUDE/PI，而非条件跳过。首次工具执行审批因自动审核超时未启动，依提示仅重试一次后运行；没有放宽审批或读取真实凭据。

每种provider使用独立临时HOME/配置目录，实际启动已安装CLI，经生产NativeAgentTransport执行initialize与session/new，并核对非空sessionId和适用的configOptions。没有发送模型prompt，不使用用户凭据，不连接Cubism，也未验证恢复会话、工具调用、视觉输入、真实权限或取消。该证据和第52节脚本ACP-v1窗口实机分别记录，不拼成“真实native视觉建模通过”。

证据：[版本/范围/测试源hash与结果](live-host-20261026/native-real-cli-smoke.json)、[原始JUnit结果](live-host-20261026/native-real-cli-smoke.xml)。本轮未重跑ACP完整套件、MCP或Runtime全量。下一项仍是实际视觉模型经受控宿主图片完成消费与建模编排，以及官方许可模型专项验收。

归档复核：190个本地Markdown链接均存在，原生ACP PNG的SHA256与metadata一致，当日归档文本凭据扫描通过，git diff --check通过。截图已实际查看，确认图片和来源文字在真实ACP窗口中可见；不把图片显示视为模型消费。


## 54. 官方 Hiyori PRO t11 来源及标准只读检查通路

为推进原清单的官方模型门槛，本轮从Live2D官方Hiyori页及其官方download.js核对英文素材地址，下载hiyori_en.zip到临时目录，仅提取PRO t11与其ReadMe为本机只读原件。模型发布记录为2023-03-08修改关键形；archive SHA256为`1e4254d561f2a151562aa67036d78e17e4ffee08869b8ce10ab6052a05e2b3a4`，CMO3为`cd435579a8f12363493049233b97db47a7ac91e6aeb5993c7c5f93dc6374070f`。详细URL/大小/ReadMe及条款hash见[来源manifest](fixtures/hiyori-pro-t11-source.json)。仓库不收录CMO3/PSD/ZIP或完整几何。

官方将Hiyori归为Live2D original character。Free Material License Agreement 2.1.3/2.1.3.2允许适用范围内的内部使用，但角色专项条款明确“No changes of any kind to the design of this character are permitted.”，因此本轮仅做内部只读结构与渲染检查，不据通用alter许可推断可以改变角色设计，也不发布素材或角色设计改版。

This content uses sample data owned and copyrighted by Live2D Inc. The sample data are utilized in accordance with terms and conditions set by Live2D Inc. This content itself is created at the author’s sole discretion.

新增标准`mcp:5303@inspect`变体：同一固定客户端/生产MCP和既有worker，JVM显式标记同时由runner转为闭合`--read-only-inspection`参数；不靠文件缺失或run-label猜测模式。客户端在构造HTTP前拒绝所有非白名单操作，仅允许目录、特定只读资源/提示词、capabilities/history/render.state/capture和三类authoring read；保存、文件授权、参数/几何写、Undo/Redo及editor commands均拒绝。宿主probe在该模式停用chooser批准和全部可选纹理/几何/文件写入探针。读取实际目录和有界候选，三类完整不可变快照分页和原生PNG，比较history与render前后pose/state不变；成功摘要仅IDs/计数/hash，不含几何坐标或原始凭据。

离线客户端36项通过（新增所有非只读工具在transport前拒绝、history变更不写成功摘要、未知模式不连接），静态准入34项通过。构建发现上一轮ACP wrapper的5.3.03窄场景literal尚未登记，已依据第52节真实任务补证据型exception，不扩大版本集合；66项release自检及标准包重建通过。本轮标准队列实机成功结果见下文。不是三类编辑、官方头部质量或模型视觉消费验收，持续目标仍未完成。

准备阶段还核对了队列的精确客户端依赖准入：原审查只列standard/free-fixture，新增inspect必须逐项审查而不能借用direct-runner。现在仅同一固定MCP脚本/解释器/结果协议/原件hash保护可用，且唯一readOnlyInspection=true、拒绝同属性重影和其他validation写工作量。新增回归通过，完整队列122项通过；首次沙箱运行因临时Unix socket被禁止失败，获准在隔离假队列环境重跑后通过，未启动真实worker或模型进程。


### Hiyori 只读实机通过

标准`mcp:5303@inspect`任务`5e3065a5-2d89-4097-ad3f-d4c39a12763b`，run `queue-82cf1783e2334b799d6a74656eeba723`，preparedId `20ba092155346fec7a3970c7b2485f655b62b66c49e2bd00936e61b98d3d38ab`。exact5.3.03/Linux Proton、既有全局worker；终态succeeded，validationStatus=PASS，normalExit/fixtureUnchanged/identityVerified/goldenUnchanged=true，cleanup=safe。原件SHA256与来源manifest一致；无模型写入，历史及渲染前后pose/state均不变。

| 实际读取对象 | 完整分页行数 | rows SHA256 |
| --- | ---: | --- |
| ArtMesh123 | 347 | `2c93788b818b8829d92b1131142c523341f75ed2574002bf954d472f647e68c3` |
| Warp1 | 121 | `d9d84c314d4246249f263c9b2e5f02b9fdf7ceb3327d9688fb9d14c6ea394467` |
| Rotation2 | 13 | `6a706a91063c31ad00fb23d8cc89d0538742212bf723ce9ce3f4b32cb46f9294` |

生产目录仍为80工具/15资源/2模板/8提示词，有限覆盖账本18 owners/166 entries。三类实际存储快照均可完整读取，但候选仅按有界结构发现，尚未完成头部语义映射；ordinary eligibility元数据不等于本模型的写入准入实测。256×256原生PNG已实际查看，确为Hiyori默认pose全身图，SHA256为`78640fee6440a225ab96b06d172129aba632b5bd1493fb3b083bcb714bd77fe4`；不作为眼嘴细节或美术质量验收。

证据：[队列结果与归档hash](live-host-20261026/hiyori-run.json)、[只读摘要](live-host-20261026/hiyori-read-only-inspection.json)、[宿主报告](live-host-20261026/hiyori-read-only-verified.properties)、[原生渲染metadata](live-host-20261026/hiyori-native-render.json)。完整几何、模型二进制和PNG留在私有任务目录，不随审计归档分发；来源与许可见上文。该轮是外部只读MCP客户端，未经过ACP视觉模型，不验证写入、Undo/Redo、保存重开或头部设计质量。下一步先建立实际头部层级/参数/form的语义映射，再安排保持角色设计的受控rig测试；Milestone A仍未完成。


本节归档复核：198个本地Markdown链接存在，Hiyori独立索引中的三份证据SHA256逐项一致，归档文本未包含Bearer、连接endpoint或MCP session header；git diff --check通过。

### 头部语义映射输入：只读库存采集

为避免按第一个可读对象推断头部，本轮进一步扩展inspect测试客户端，在原生render后读取层级、参数定义/当前值及parameter-bindings资源。三份资源分别重复读取，前后render.state精确一致，限制4MiB/100000层级节点/10000参数或绑定组；过期state、资源变化或超限均在输出成功文件前拒绝。完整资源仅输出私有任务文件`mcp-read-only-inventory-private.json`；可归档摘要仅含状态、计数、字节及canonical资源hash，不含名称、坐标或完整绑定。显示ID仍不是native GUID，不做未经核对的头部/左右语义结论。

新增重复读取变化拒绝及过期state在资源读取前拒绝回归，客户端共38项通过；标准测试包重建通过。这项改动仅为测试侧收集输入，不新增生产工具或扩大可写白名单。


库存采集任务`eff90898-7abc-4215-bdfd-03cee6e46843` / run `queue-5755c0c8a70441f7850debcfc458ee63` / preparedId `df4e0d4918ae2f1abe888c06bd5a37ff65d25e0812dd68a1b5371811b5c1b202`已succeeded。validationStatus=PASS，正常退出、原件/任务副本hash、身份及goldenUnchanged均通过，cleanup=safe，无模型写入。实际272节点（1 MODEL、27 PART、140 ART_MESH、50 WARP_DEFORMER、54 ROTATION_DEFORMER），70参数、70绑定组，三份canonical资源共122616字节，SHA256为`004101a50effe185df62ab63c2cb65617c4a177282ac021f2bc56a0a8b939292`。两次资源读取及前后state一致，最终history不变。

证据：[任务索引](live-host-20261026/hiyori-inventory-run.json)、[无完整资源的库存摘要](live-host-20261026/hiyori-inventory.json)、[三类快照与不变性摘要](live-host-20261026/hiyori-inventory-inspection.json)、[宿主报告](live-host-20261026/hiyori-inventory-verified.properties)。完整资源保持私有，不把重复读取检查称作服务器不可变资源快照。

内部核对得到候选线索：先前Warp1实际名为Body Warp Z，绑定ParamBodyAngleZ；Rotation2为Neck Position，绑定ParamBodyAngleX。因此先前三类只读通过不构成头XYZ目标覆盖。Warp13为Face Warp，绑定ParamAngleX/ParamAngleY；Rotation1为Face Rotation，绑定ParamAngleZ；ArtMesh51为Contour，父Warp13。这些名称/层级/绑定可支持下一次定向检查，但不是最终语义或可写form批准，左右归属也不由L/R名称单独推断。后续任务必须重新发现document/model与source/form GUID，读取完整axes/cells/forms并重新校验CAS及许可范围；不得复用本轮会话身份或默认选择第一个对象。

本轮最终复核：203个本地Markdown链接存在，两轮Hiyori归档hash及凭据扫描通过；客户端38项和独立目录采集器14项通过，git diff --check通过。未重跑生产全量套件，也未将本轮只读证据扩张为建模验收。


## 55. Hiyori 头部候选的定向完整 form 检查

上一轮第54节取得官方Hiyori完整层级/绑定库存，本轮不再把有界扫描的第一个对象当作头部。只在inspect测试客户端增加Hiyori PRO t11的候选profile：Face Rotation、Face Warp、Contour，要求对象kind/名称唯一、Warp父为Rotation、Contour父为Warp，精确匹配ParamAngleZ或ParamAngleX/Y的普通keyform_grid绑定及[-30,0,30]键。显示参数ID重复、重名、错误父级、身体轴或额外绑定均拒绝。无匹配profile时明确UNAVAILABLE，不影响其他模型的普通只读检查；该profile是测试候选规则，不是通用语义算法或写入批准。

对三类候选重新获取生产不可变存储快照与全部分页，逐项核对native axis GUID、完整cell tuple、组合网格、已存在form及每form资格。读取前后render.state必须一致，层级/参数/绑定库存须再次等值。完整rows只留私有任务文件，可归档摘要仅身份/轴键/计数/rows hash；hostWriteAdmission固定UNPROVEN，semanticMapping为CANDIDATE_ONLY，不因snapshotEligible便宣称宿主已写入。

新增profile拒绝边界、native tuple/form/eligibility完整性、状态改变不发布成功三项回归；客户端共41项通过。标准MCP测试包重建通过（含66项release检查），未改SDK/Runtime/MCP/ACP生产主源码或写入白名单。标准实机成功结果见下文。


标准队列任务`1a8a0596-aee7-4eb5-805a-fca3bf23165e`，run `queue-33b26f842ab94b68b09b145fd2586cf2`，preparedId `cf5c52d6dc115f05609ebe5a3a7d56035353d75ef530e391c7010886fb6d9917`已succeeded。exact5.3.03/Linux Proton、既有worker；validationStatus=PASS，normalExit/fixtureUnchanged/identityVerified/goldenUnchanged=true，cleanup=safe，无模型写入，最终history与读取前一致。

| 候选 | 参数/完整cell数 | form数 / snapshotEligible数 | rows SHA256 |
| --- | --- | --- | --- |
| Rotation1 / Face Rotation | ParamAngleZ / 3 | 3 / 2 | `e5f031abbe400d62c2ecebf4714c6ae8fa5fd47c8dbf19031d09d7397a33a29a` |
| Warp13 / Face Warp | ParamAngleX,Y / 9 | 9 / 8 | `6859c10139bfde6e30a8370eabf4b0a85a6d0ca1cdba8997a2d362e37c8c6c70` |
| ArtMesh51 / Contour | ParamAngleX,Y / 9 | 9 / 8 | `1eab3f6d6fa75f62a1b415096b67e77d75bbd986fd50215597963ef8b66d27c3` |

证据：[队列与证据hash](live-host-20261026/hiyori-head-run.json)、[身份/轴键/组合与资格摘要](live-host-20261026/hiyori-head-candidates.json)、[最终只读摘要](live-host-20261026/hiyori-head-inspection.json)、[库存摘要](live-host-20261026/hiyori-head-inventory.json)、[宿主报告](live-host-20261026/hiyori-head-verified.properties)、[渲染metadata](live-host-20261026/hiyori-head-native-render.json)。完整rows和PNG保持私有，不分发样例素材。

本轮进一步确认不能复用色块探针的固定几何位移量：Hiyori轮廓网格在父Warp局部空间，Face Warp则受父Rotation影响，实际坐标量级不同。后续编辑计划须明确父空间，以目标form自身尺度约束扰动，保留UV/indices、非目标form和原始素材，重新读取并使用CAS；不得把固定4.0当成所有对象的安全位移。以上只证明候选完整读取和约束核对，不证明头部写入、专业美术、保存重开或ACP视觉模型消费。Milestone A及完整目标继续保持未完成。

归档复核：211个本地Markdown链接存在，Hiyori各轮归档hash与凭据扫描通过，git diff --check通过。未重跑生产全量测试，不挪用旧测试总数。


## 56. Hiyori 受控头部三类编辑验证通路

第55节取得真实候选完整forms后，本轮增加标准`mcp:5303@head-edit`测试变体。队列仅准入exact5.3.03、唯一headEditing=true与固定MCP客户端/结果协议，拒绝覆盖模式、其他版本及混入只读/纹理/FREE等工作量；沿用原件hash保护与既有全局worker，不增加direct-runner绕过。客户端使用独立工具白名单，仅目录/只读资源、三类authoring读、keyforms preview/write/result、pose_grid/result/release及history Undo/Redo；文件、参数结构、图像、纹理、蒙版、绑定CRUD和editor commands均在transport前拒绝。宿主测试probe不批准文件chooser、不运行纹理和保存/打开探针；仅为本轮启用受控native故障回滚助手。

候选必须再次经完整头部profile检查。选择头X=+30/Y=0的轮廓/Face Warp，以及头Z=+30的Face Rotation；仅独立非默认ordinary form。网格/Warp在自身stored父空间沿x平移form局部宽度的1/256，float32回读要求每点非零且不超过宽度1/128；Rotation只增加0.5度、保持轴心/缩放/反射。拓扑、UV、source、其他form和其余字段完整保留。不是专业建模算法或角色设计改版；不照搬色块固定4.0偏移。native故障探针在head-edit模式同样使用尺度约束，原标准lane行为保持。

复用既有生产preview/CAS→三类联合write→完整存储回读→pose前后原生PNG→单步Undo/Redo/最终Undo；在实际确认1/2/3个setter后注入异常，要求完整native快照及public/transaction历史各自回到基线。head-edit本轮不进入保存重开，明确persistence未测试，不借色块持久化结果证明Hiyori通过。未知结果不重放写入，仅按已确认receipt清理。

离线客户端43项通过（新增局部尺度、保持非目标form和transport写域拒绝，既有矩阵补无持久化路径不调用文件探针）；隔离假队列123项通过；native回滚Java专项5项通过，0失败/跳过，格式化和标准打包通过。未改生产MCP/ACP/SDK/Runtime主源码，目录保持80/15/2/8。标准实机成功结果见下文。


### 官方 Hiyori 三类头部真实编辑通过

任务`0b91006c-fd63-452e-af78-9d6cfc6073ae` / run `queue-0e268041ab58451688f82045987d8f34` / preparedId `8d7aed7f587f50c4e8d5c4dba8b33999d36d7083159d6b60a9d2c8c0e831673b`，标准prepare/submit/status、既有worker、exact5.3.03/Linux Proton。队列succeeded，validationStatus=PASS，normalExit/fixtureUnchanged/identityVerified/goldenUnchanged=true，cleanup=safe。官方只读原件SHA256保持`cd435579a8f12363493049233b97db47a7ac91e6aeb5993c7c5f93dc6374070f`。

生产MCP对ArtMesh51、Warp13、Rotation1的实际目标forms执行preview→联合write，结果APPLIED，三个目标readbackVerified=true，一个native Undo entry；float32 bit及完整身份回读与预期一致。目标form hash改变，source（含网格源坐标/UV/indices或变形器源配置）及非目标form/其余rows hash保持一致。单步Undo精确回到基线，Redo精确恢复修改，最终Undo再次还原；本轮没有创建参数/绑定，没有触及默认form或解锁。此核对覆盖三目标完整快照，不扩张为所有非目标对象/文档均已逐一hash检查。

native故障注入三轮分别在确认实际1/2/3个setter后抛异常，均返回ROLLED_BACK；目标完整native authoring snapshots回到基线，public history及transaction history分别完整恢复其各自基线，MCP重读也一致。该助手是测试专用SDK异常注入，不伪称生产MCP可提供任意故障执行器。

三张512×512前后pose PNG均hash不同；轮廓与Warp的X=+30/Y=0是同一pose，故只有两种不同参数姿态，不声称三种独立视觉贡献。前后六张PNG的实际字节hash均与metadata一致，已查看全图及头部裁剪对比；此次小幅rig变化可见，但512全身图和放大裁剪不等于高分辨率细节或美术质量验收。未运行XY九宫格、眨眼/口型组合，也未验证真实视觉模型消费或ACP自动编排。

归档：[队列结果/hash索引](live-host-20261026/hiyori-head-edit-run.json)、[三目标source/form/非目标hash与写入收据](live-host-20261026/hiyori-head-edit.json)、[故障注入回滚证据](live-host-20261026/hiyori-head-edit-rollback.json)、[候选准入摘要](live-host-20261026/hiyori-head-edit-candidates.json)、[宿主报告](live-host-20261026/hiyori-head-edit-verified.properties)、[修改前pose metadata](live-host-20261026/hiyori-head-edit-render-before.json)、[修改后pose metadata](live-host-20261026/hiyori-head-edit-render-after.json)。完整几何、原件、PNG保持私有，不分发官方素材；许可/版权声明沿用第54节。

该结果推进官方Hiyori的真实头部编辑/Undo/Redo/故障回滚原语门槛。未保存任务模型，本轮明确geometryPersistenceVerified=false；不能用第51节色块的生产SAVE_AS/OPEN证明Hiyori持久化。后续需Hiyori生产文件闭环、完整组合QA和真实ACP视觉/工具编排，Milestone A与持续目标仍不标完成。

本轮最终归档复核：221个本地Markdown链接存在；各轮Hiyori索引hash、六张私有pose PNG hash及本轮文本凭据/原始几何扫描通过，git diff --check通过。生产全量MCP/Runtime/ACP未重跑，不挪用旧总数。


## 57. Hiyori 现有头部绑定的生产 SAVE_AS→OPEN 通路

第56节证明Hiyori三类头部真实可撤销写入。本轮增加标准`mcp:5303@head-save`，只准入exact5.3.03、唯一headPersistence=true与固定MCP测试客户端/结果协议；拒绝混入head-edit/只读/FREE等工作量。复用头部profile、全量分页与按自身父空间尺度的小幅计划，不新增参数或绑定。客户端在head-edit工具白名单上仅补files.request_grant/grant_status与documents.read/save_as/open/file_result，结构/纹理/绑定初始化继续拒绝。任务副本及官方原件hash保护、既有全局worker不变。

复用生产文件闭环：读取原始完整native authoring records及全部SDK可见绑定基线→三类生产preview/write与回读、前后pose图→严格用途/CMO3过滤的WRITE grant→生产SAVE_AS（REJECT_EXISTING）→测试助手确认已保存后关闭精确任务文档→READ grant→生产OPEN保存输出→新native文档/完整records及绑定/pose像素核对→关闭保存输出→生产READ grant/OPEN恢复原fixture→原始records/绑定和文件hash核对。选择器批准由测试专用助手完成，不能称为无人工授权的产品流程；助手不代替生产save/open。

此前色块用例要求新增绑定并验证持久化，本轮Hiyori必须保留现有绑定。客户端与native探针明确分开两种策略：两者都要求真实geometry改变；原lane仍要求bindings改变，head-save则要求bindings与原基线相同。重开后完整records/绑定仍各自严格等值，不因策略分开而放松。文件结果未知只查询file_result，不重放SAVE_AS/OPEN，不按旧文档receipt执行Undo。

离线客户端45项通过（含预绑定完整成功、意外绑定改变停止、不调用初始化，以及封闭transport域）；隔离假队列124项通过；原生OPEN探针专项6项通过，无失败/跳过，测试Java格式化完成。标准实机成功结果见下文；未改生产主源码或扩大生产宿主准入。


### 官方 Hiyori 生产文件持久化通过

标准任务`70ed7ee6-50ad-49cc-be93-ee1700f4ff45` / run `queue-f0dec7d50c8a41519c84bc8dad094302` / preparedId `30f6b4b2a5fccf0c1650d845dfcfc92aa44564cfe017093298cc2bd83fa2dcaa`，既有worker、exact5.3.03/Linux Proton；终态succeeded，validationStatus=PASS，normalExit/fixtureUnchanged/identityVerified/goldenUnchanged=true，cleanup=safe。

生产preview/write实际修改ArtMesh51、Warp13、Rotation1的指定独立头部forms，全部readbackVerified。WRITE grant→生产SAVE_AS新文件→关闭精确已保存文档→READ grant/生产OPEN完成；重开为同JVM内新的native文档对象。三目标全部MCP存储rows与保存前等值，完整native authoring records hashes逐项相同，全部SDK可见绑定hash在原基线/保存前/重开后相同。初始化字段为NOT_NEEDED_EXISTING_BINDINGS_PRESERVED；未新增参数/绑定。三目标source/拓扑/UV及非目标form/其余rows hash保持，目标form实际改变并在重开后保留。不是只按工具EXECUTED返回判定成功，也未改变生产响应persistenceVerified/reopenVerified=false的分派语义。

三张512×512保存后pose与重开后的PNG逐张SHA256完全一致，前后九张PNG实际字节hash全部与metadata一致；轮廓和Warp同用X=+30/Y=0，Rotation用Z=+30，共两种不同姿态。已实际查看重开后的Hiyori图像。未据此声明高分辨率眼嘴质量、XY九宫格、眨眼口型组合或全部非目标对象/文档均已逐一检查。

保存输出SHA256为`5d262f4c6f64867ac223f12db40225b341f18de4f9e44e5cc8f3e8e72035f5e7`；官方原件保持`cd435579a8f12363493049233b97db47a7ac91e6aeb5993c7c5f93dc6374070f`。最后关闭保存输出，经生产READ grant/OPEN恢复原任务fixture，三目标原始完整records及全部SDK可见绑定hash回到基线，保存输出hash仍一致；未按旧文档receipt做Undo。原件与保存输出均保留在私有目录，不随审计分发。

证据：[队列/hash与九张私有PNG索引](live-host-20261026/hiyori-head-save-run.json)、[source/form/非目标hash及生产收据摘要](live-host-20261026/hiyori-head-save-open.json)、[原始native基线](live-host-20261026/hiyori-head-save-baseline.json)、[已保存文档关闭证明](live-host-20261026/hiyori-head-save-closed.json)、[重开完整records/绑定核对](live-host-20261026/hiyori-head-save-native.json)、[恢复前关闭证明](live-host-20261026/hiyori-head-save-restore-closed.json)、[原始fixture恢复核对](live-host-20261026/hiyori-head-save-restored.json)、[宿主报告](live-host-20261026/hiyori-head-save-verified.properties)、[修改前pose metadata](live-host-20261026/hiyori-head-save-render-before.json)、[修改后pose metadata](live-host-20261026/hiyori-head-save-render-modified.json)、[重开pose metadata](live-host-20261026/hiyori-head-save-render-reloaded.json)。没有归档完整几何、模型二进制或PNG，许可与版权声明沿用第54节。

本轮补齐Hiyori的生产文件原语闭环；不等于跨JVM持久化、所有宿主或真实Agent自动保存已通过。测试专用chooser批准与精确文档关闭须继续与产品授权/编排区分。客户端45项、隔离队列124项、native OPEN Java6项、静态准入34项、runner及标准打包通过；未重跑全量生产MCP/Runtime/ACP。下一步继续完整头部组合QA及真实ACP视觉/工具编排，Milestone A及持续目标仍未完成。

本轮最终复核：235个本地Markdown链接存在；各轮Hiyori归档hash、本轮九张私有PNG hash、文本凭据/grant/绝对路径/完整几何扫描通过，官方原件磁盘SHA256独立复算一致，git diff --check通过。


## 58. 头部 XY/XYZ 与眼嘴组合的有界原生扫描

第57节取得官方头部文件持久化证据后，本轮新增exact5.3.03标准`mcp:5303@head-qa`。仅唯一headPoseQA=true、固定MCP客户端/结果协议及任务副本hash保护；拒绝混入其他工作量。工具白名单沿用head-edit，不开放文件/结构/纹理或绑定写入，测试宿主仍禁用文件chooser、纹理/保存探针，故障回滚保持局部尺度约束。生产工具目录与预算均不增加。

固定28个不同pose：9个XY九宫格（Z=0，双眼开/嘴闭）、8个XYZ角点、中心与(-30,-30,-30)/(30,30,30)三锚点上的双眼开闭×嘴开闭组合（去重9项增量）、左右单独闭眼2项。使用实际发现且唯一的ParamAngleX/Y/Z、ParamEyeLOpen/ROpen、ParamMouthOpenY，逐项检查范围；每pose显式指定全部六轴，MouthForm等其他参数保持实际基线，不声称穷尽全部眼嘴/左右/参数组合。

每pose独立生产pose_grid，1024×1024原生render，裁剪固定像素ROI x=256/y=0/w=512/h=384；一帧1048576原生像素，不提高现有总像素/9pose/6axis/64job/2image-job限制。每次核对typed PNG、全部六轴实际值、document/model/generation、crop/原生尺寸/坐标方向、capture身份和hash，随后恢复全部参数、按收据清理扫描Undo并release。修改前后各28次共56job；客户端聚合明确标记MULTIPLE_INDEPENDENT_SCANS，不伪装成一次生产多图响应，不把全部图片塞入Agent上下文。

通过头部profile重新读取三目标，先原生故障回滚，再扫描修改前28pose，生产preview/CAS/三类联合write、完整回读、扫描修改后28pose，最后精确Undo/Redo/最终Undo；沿用既有非默认/独立ordinary form与局部尺度约束，不保存。扫描状态与视觉质量分开，artQuality始终NOT_AUTOMATICALLY_ACCEPTED；本轮先取得可复核输入，实际视觉观察与未实施检查另列。

初版客户端47项通过（新增28pose覆盖/重复与越界拒绝、独立扫描状态改变/未知失败不重放、裁剪来源匹配与错误来源拒绝）；隔离假队列125项、native回滚Java5项通过。首轮失败、节流修正及标准队列复测结果见下文；未改生产主源码或提高预算。


首轮任务`f0c0be00-9f08-4b17-aae8-582d4491b046` / run `queue-833e3a6694bf4fb5b7ad7e40bd3c6f6b`在RENDER_AFTER第27个pose前收到HTTP 429：修改前28帧和修改后26帧完成，最终Undo的历史读取也被拒绝，writeRestored=false。队列failed、validationStatus=FAIL、normalExit=false、cleanup=safe；没有重放几何写入。队列未完成最终fixture/identity/golden核对，其false/null字段不能解释为已证明文件或黄金环境改变，也不能冒认这些检查通过。官方原件另行从磁盘复算仍为已登记SHA256。见[保留的失败摘要](live-host-20261026/hiyori-head-qa-rate-limit-failure.json)，全部几何/PNG保持私有。

源码核对表明MCP标准wrapper显式配置600 requests/minute，服务器按全部HTTP请求计数并在429返回Retry-After=60；本轮大量逐pose验证累计触发该既有预算。修复仅在QA测试客户端增加每次POST/DELETE前至少0.2秒间距，约300/minute，保留正常server预算，不提高上限或自动重试429。新增假时钟回归确认间距、慢请求不额外等待，以及写入被429拒绝后绝不自动重放；客户端48项通过。按新的prepared任务从原始副本完整复测，而非续用失败会话或旧Undo收据。


### 节流后完整复测通过

新任务`43949ed4-ce6b-4e87-93ee-97bfb2807c7d` / run `queue-34929a1946d4482fad94b66f253b830a` / preparedId `410434ee15bf47bb0991c7e978d6a5fc30af1a771538ce8d3a8226db8ac0ed9c`，标准prepare/submit/status、既有worker、exact5.3.03/Linux Proton。队列succeeded，validationStatus=PASS，normalExit/fixtureUnchanged/identityVerified/goldenUnchanged=true，cleanup=safe，官方原件SHA256保持已登记值。未再触发429，未提高600/minute或其他生产预算；首轮失败仍独立归档。

修改前后各28个1024原生渲染的512×384头部ROI，56张实际PNG逐张hash与metadata一致，每次capture/pose/doc/model/generation及crop来源匹配，全部参数恢复且扫描历史position清理并release。两组均有28个不同PNG；独立扫描聚合没有伪称一次生产响应。实际三类联合写入/完整回读、native1/2/3 setter故障回滚、单步Undo/Redo与最终Undo也通过；最终三目标完整rows精确回到基线，无保存。

离散pose比较：恰好8个pose改变（indices 5、13–16、23–25），其余20张前后PNG字节相同。改变集合与本轮指定form恰好一致：X=+30/Y=0的Contour/Face Warp，或Z=+30的Face Rotation。该检查只针对本轮已声明的端点pose和改动，不扩张为中间值连续性或任意插值影响证明。

已实际查看修改后全部28帧的带pose标签私有图组，并查看8组改变pose前后配对；另外20组前图与已查看后图byte-identical。中心/两XYZ角点上的闭眼与张嘴可见，在检查尺度下未见明显大片眼白泄漏、嘴部破裂或脸发大片穿帮。左右眨眼提供实际像素线索：本fixture的ParamEyeLOpen=0关闭画面右眼，ParamEyeROpen=0关闭画面左眼；不是只凭L/R名称推断，也不泛化其他素材。图组有缩放，且没有参考美术标准，故仅为有限视觉观察，artQualityAccepted=false。亚像素蒙版/漏白、几何翻面/Jacobian、所有中间参数和风格/体积标准未在本轮验收。

证据：[成功任务及证据hash](live-host-20261026/hiyori-head-qa-run.json)、[28pose比较与56次恢复核对](live-host-20261026/hiyori-head-qa.json)、[修改前全部frame metadata](live-host-20261026/hiyori-head-qa-render-before.json)、[修改后全部frame metadata](live-host-20261026/hiyori-head-qa-render-after.json)、[有限视觉观察及未评估项目](live-host-20261026/hiyori-head-qa-visual-review.json)、[native故障回滚](live-host-20261026/hiyori-head-qa-rollback.json)、[候选摘要](live-host-20261026/hiyori-head-qa-candidates.json)、[宿主报告](live-host-20261026/hiyori-head-qa-verified.properties)。所有原始素材、完整几何、56张PNG及派生图组仍私有，不随审计分发；版权与用途沿用第54节。

本轮取得有限头部组合扫描/恢复及实际图像观察证据，仍是外部MCP测试客户端和ACP之外的Codex图像检查，不证明真实ACP Agent视觉消费/工具建模编排。下一项优先打通真实ACP harness的工具与图像闭环；Milestone A与持续目标仍未完成。客户端48项、隔离假队列125项、native回滚Java5项、静态34项、runner和标准打包通过，未重跑全量生产MCP/Runtime/ACP。

最终归档复核：247个本地Markdown链接存在，Hiyori各轮索引中的34项证据SHA256逐项一致；本轮8份JSON（含首轮失败）通过绝对路径、凭据/grant和完整几何字段扫描。官方原件磁盘SHA256独立复算保持登记值，git diff --check通过。此复核不启动宿主，不增加模型或harness的运行验收范围。

## 59. 真实 Pi 原生 ACP 观察通路与模型服务准入

本轮新增标准 `mcp:5303@native-observe`，复用既有worker、任务副本及原件hash保护；不直接启动宿主。Linux sidecar 使用本工作区生产 AcpClient→NativeAgentTransport→NativePiProvider→Pi MCP扩展，连接同一标准任务的生产MCP。不是脚本ACP假代理，也不把外部Python自行调用render算作模型调用。该sidecar不含Swing窗口验收，仍需与第52节的脚本代理窗口证据分开。测试范围与固定依赖见[探针说明](../acp-native-host-probe/README.md)。生产工具目录、权限与宿主版本准入未扩大。

Pi禁用内置工具、自动扩展、技能和提示词模板，仅显式加载生产MCP扩展。测试专用stdio转发器只公开render.state/capture和两个只读资源；在transport前拒绝文件/几何/参数/历史/pose写入、任意URI、额外字段与超限尺寸；32请求、2张512×512图像预算。令牌由任务转发器读取，不放入Agent的session参数或证据。真实模型/认证沿用用户现有CLI，由CLI自行读取；不复制配置或凭据。队列固定辅助包、运行时清单、Java/Node可执行文件及完整Pi1.2.0 release tree；代码改变则拒绝执行。

模型必须实际发现资源、读取活动文档、调用生产render.state/capture；分别核对原生PNG、ACP typed图片回调及真实模型文字描述，并检查状态/历史未变。辅助包仅在图片到达且turn完成后返回CAPTURE_DELIVERED_REVIEW_REQUIRED；modelVisionAccepted默认false，不能把模型声明支持image、成功握手、单张图片回调或工具APPLIED当作视觉质量通过。全部PNG、回答和CLI会话保留在私有任务目录，公共归档仅留必要metadata/hash/收据。超时只关闭测试创建的sidecar进程组，不声称宿主操作被取消。

首轮任务`4bf4be61-2618-42b7-8096-6a50f4228a6a` / run `queue-55f18504cdff4a93b87c7584f0ef23c0`：真实Pi完成MCP initialize、initialized和tools/list，但默认模型zai-coding-cn/glm-5.3在第一个模型工具调用前收到上游HTTP429；0模型工具调用、0图片。Pi自身执行上游重试，测试客户端未重放MCP写入。队列failed、validationStatus=FAIL、normalExit=false、cleanup=safe；runner最终身份/文件/golden检查未完成，其false/null字段不表示已证明文件改变，也不能当作这些检查通过。[首轮失败摘要](live-host-20261026/native-observe-rate-limit-failure.json)。

随后增加透明的原生stdout观察，只读取get_available_models的provider/id/input和错误状态，不改变协议字节、不反射宿主。保留支持image的当前模型；否则通过生产session配置切换至CLI声明可用的视觉模型；没有候选则在prompt前拒绝，不猜测视觉能力。第二轮任务`cef664c2-e3ff-4596-a2a5-2e786b7083e9` / run `queue-6564ca5105c54d6cb282393d4a392d01`，运行时声明25个可用视觉模型，已从默认文本模型切换到zai-coding-cn/glm-4.6v，仍在第一个工具调用前收到同一上游HTTP429。队列failed、cleanup=safe；最终runner检查仍未完成，不声称视觉消费或原件/golden最终检查通过。[第二轮失败摘要](live-host-20261026/native-observe-vision-rate-limit-failure.json)。

两次失败不是MCP600/minute限流，不能用提高MCP预算修复。下一任务在冻结的测试运行时清单中显式排除该供应商，再从其余真实运行时声明可用的视觉模型选择；不修改用户全局默认模型、不续用失败会话或在同一turn盲目切换/重放。分片UTF-8原生协议透传、视觉选择/未知拒绝/供应商排除和429分类的Java自检通过；客户端52项、隔离假队列126项、静态34项、runner、版本门禁66项与标准打包通过，ACP NativeAgentTransport23项和AcpClient32项通过，无失败/跳过。没有重跑全量MCP/Runtime。真实模型视觉/工具观察及后续几何编排仍未验收，目标保持未完成。

第三轮任务`eafc9bb5-5e54-4b86-afe8-2768af8c3701` / run `queue-e631f9b066644c40b9fd8ee7810cff5e` / preparedId `6b5a3e484dee1c3c01fa8e841ef452f71a15ea15a6851d2d82f949ad8cd0f672`，冻结清单排除zai-coding-cn，真实session配置选择opencode-go/claude-haiku-5-5；该供应商返回HTTP401、凭据过期，同样0模型工具调用、0图片。运行时25个视觉模型分属opencode-go23个和zai-coding-cn2个，不能把目录声明当作账号可用证明。队列failed、cleanup=safe，最终runner文件/身份/golden检查未完成；另从磁盘复算原始fixture源SHA256保持`2980dc8c695b13eeb54e37c02832edf80607979f2cc721ea9ca7b671f8bbac10`，该独立检查不替代runner全部核对。[第三轮失败摘要](live-host-20261026/native-observe-expired-credentials-failure.json)。已请求用户在原CLI恢复服务或指定实际可用视觉模型，不索取凭据；停止对这两个失败服务重复发起模型请求。

本轮另修复生产Pi MCP扩展的能力提示：工具或资源返回typed image时，以当前execute context的model.input判断，非视觉模型与未知/畸形元数据分别追加明确文字提示，模型切换后重新判断。图片仍保持typed以供ACP显示，宿主structuredContent、收据与isError原样保留；不因模型不能看图把已成功写入改成可重试错误。Node专项覆盖非视觉/未知/畸形能力、资源图片、模型切换及原错误状态保留，stdio/HTTP分页与协议回归通过。此生产提示只有离线证据，不冒充上述三轮实机已验收新提示；真实模型消费与native窗口通路仍未通过。

本轮源码/三次失败证据hash及离线结果汇总见[观察任务索引](live-host-20261026/native-observe-runs.json)，[Pi桥接专项输出](live-host-20261026/native-observe-pi-bridge-tests.txt)。新增timeout回归验证只终止本次创建的sidecar进程组，未向宿主发送取消/写操作；客户端最终52项通过。

最终复核：255个本地Markdown链接存在；Hiyori与本轮索引合计38项证据hash一致，本轮9份源码hash一致；新增JSON通过凭据/绝对路径/完整几何扫描，git diff --check通过。第三轮终态后，原始fixture源磁盘hash独立复算仍一致；不将此检查替代失败runner未完成的身份/golden核对。当前标准包构建及原生协议自检通过；没有遗留活跃验证任务。


## 60. Codex 原生 turn 身份、取消与审批隔离

本轮沿第59节等待模型服务恢复期间，继续核对真实 harness 的协议边界，未连接 Cubism 或发送模型 prompt。通过本机 Codex 0.159.2 的 `app-server generate-json-schema` 离线生成协议声明：输出、推理、工具和审批带 threadId/turnId；turn started/completed 使用 threadId/turn.id；turn/start 响应含 turn.id。仅 schema 生成不证明真实模型事件时序。重新读取 Paseo 本机 Codex provider，SHA256保持harness参考表登记值；参考其 interrupt/turn 身份管理，未修改或升级 Paseo。

先用原生产代码复现三项失败：旧turn完成/输出污染新turn、start ACK前取消不能绑定本次准确turn、其他thread审批进入权限UI。随后修改生产 NativeCodexProvider：每次prompt拥有独立状态，以该次turn/start响应的turn.id绑定身份；迟到turn/started不能覆盖。事件必须同时匹配thread与turn；无身份/旧身份输出丢弃，审批按请求类型默认拒绝。ACK前最多缓存64条、合计1MiB UTF-8事件，确定身份后按序回放；缺ID或超限要求重连并核对宿主结果。

取消在ACK前只标记本次请求，得到准确ID后仅发送一次interrupt；interrupt ACK不是终态。迟到interrupt失败回调不能结束下一turn；当前start/interrupt失败或未知terminal status会关闭此provider并要求核对，不自动重放。权限回调捕获原turn，用户迟到ALLOW在原turn结束、被替换或取消后均降为拒绝。文本去重状态按turn隔离。现有工具图片测试的Codex假事件补齐真实协议要求的身份字段，未放松生产过滤。

最终测试计数与源码/schema hash见[离线证据摘要](codex-turn-isolation-20261026.json)。覆盖ACK前合法输出与终态回放、旧输出/错误/工具结果和缺身份事件、重试错误、其他thread审批、迟到允许、提前/重复取消、旧interrupt失败、缺ID、两类缓存预算、start/interrupt失败不重放以及未知terminal拒绝。Java格式检查和JAR构建通过；ACP全量226项中223项通过、0失败/错误、3项opt-in smoke跳过（19套件），其中NativeAgentTransport34项和AcpClient32项均通过无跳过。没有重跑全量MCP/Runtime，也没有新增实机通过声明。

支持边界：本实现要求turn/start响应带权威turn.id；未实现Paseo对旧形态无ID ACK等待通知的兼容路线。迟到权限回复会拒绝，但未增加主动关闭既有Swing审批窗口；不保证所有宿主版本、恢复会话或ACP-v1 adapter具有相同边界。模型能力、真实Codex审批/取消、原生Swing图片和ACP建模编排仍需分别实机验收。Agent停止、transport关闭、宿主取消和模型事务回滚继续保持不同状态；此次修改不增加宿主取消/回滚承诺。总目标及Milestone A保持未完成。


本节最终归档复核：标准native-observe验证包重建及探针自检通过，既有版本门禁66项通过；没有提交队列任务。260个本地Markdown链接存在，本节两份源码hash与已索引失败证据hash一致，git diff --check通过。JAR hash仅记录本次构建身份，不扩大到真实harness或宿主验收。


## 61. Pi/Claude 本轮回调隔离与三种真实 CLI 握手

本轮继续等待第59节失败模型服务恢复，没有重试429/401供应商、连接Cubism或发送模型prompt。检查当前生产Pi/Claude provider发现：审批回调未绑定本轮future，原审批窗口的迟到ALLOW可能在本轮取消/结束后仍被发送；Pi的旧handled ACK通过全局complete结束当前prompt。先在原实现运行三例回归，三例均失败，再实施修复。红/绿案例、当前源码与Pi runtime参考hash见[证据摘要](native-callback-isolation-20261026.json)。

生产NativeAgentProvider增加当前运行future身份及不确定请求后的重连门槛。Pi/Claude prompt、event和cancel在同一provider锁下核对归属，初始化去重状态改在beginPrompt成功后，拒绝重叠prompt不会清空正在流式输出的状态。审批捕获原future，只有它仍为当前运行请求且未cancel才允许；取消后新审批和空闲期审批默认拒绝。空闲期文本/工具事件不再写入对话；空闲cancel不发命令，重复cancel每轮只发一次。

Pi prompt ACK只完成捕获的future；旧ACK不能完成下一轮。两种provider的取消失败同样核对捕获future，旧回调不能失败下一轮；当前prompt/取消请求失败要求重连并核对宿主结果，关闭此连接且不自动重放。interrupt/abort ACK仍不作为模型turn终态；Pi继续等待agent_settled，保留旧版本agent_end无willRetry的既有兼容域。正常当前审批允许、当前handled/agentInvoked=false、错误与typed图片通路均保留。

源码参考使用本机Pi1.2.0的rpc-mode.js与agent-session.js：prompt preflight响应含请求ID，abort等待session.abort后返回响应，agent_settled由订阅转发。参考hash单列，不把源代码可见当作实机时序验收。ACP全量239项中236通过、0失败/错误、3项opt-in smoke跳过，19套件；NativeAgentTransport47项和AcpClient32项全部通过。Java格式检查和JAR构建通过。随后单独打开隔离real-CLI开关，本机Codex0.159.2、Claude2.1.284、Pi1.2.0各initialize/new-session一例，3项全通过无跳过；临时CLI配置隔离，不读取/复制用户凭据，未发送模型prompt。未重跑全量MCP/Runtime。

重要限制：这是本地future/回调归属修复。Pi输出没有原生turn ID，Claude当前适配也未建立逐事件原生turn身份；不能识别“新prompt已开始后才第一次抵达”的旧无ID输出/终态/审批请求。因此不把本项算作H1全部通过或真实模型取消通过。既有Swing审批窗口和transport审批槽位未在terminal时主动关闭/清理；迟到回复会被拒绝，该生命周期工作仍待完成。三种真实握手均未覆盖恢复、模型视觉、实际工具调用、审批/取消或建模编排。

下一实机路径仍走标准prepare/submit/status和既有worker。已核对Codex0.159.2的model/list schema具有inputModalities，但其缺失时的schema默认值不能冒充本次运行时声明；当前native-observe仍只接Pi，其他harness需独立固定运行时、验证MCP配置与内置shell/文件/网络工具关闭，再接入同一受控只读代理和图像证据核对。不能只换可执行程序便继承Pi的测试安全范围或视觉准入。总目标保持active，A整体不勾选。


本节最终复核：标准native-observe包已重建，既有release门禁与探针自检通过，没有提交队列任务。265个本地Markdown链接存在，本节4份当前源码hash与隐私检查通过，git diff --check通过。此前第60节的共享测试文件hash表示当时源码快照，本节记录追加测试后的新身份，不追溯改写历史证据。


## 62. 审批生命周期、native槽位与真实Swing窗口回收

本轮接续第60–61节，不重试已失败模型服务，不发送模型prompt、连接或启动Cubism。新增本地审批lifetime signal，不是新增远程授权或宿主取消接口。先复现“cancel仍等待UI返回”和“prompt终态不关闭审批生命周期”两例，原接线均失败；红/绿案例及12份当前源码hash见[证据摘要](permission-lifetime-20261026.json)。

AcpClient现在将审批绑定本轮session scope：prompt完成/失败、显式cancel、有效new/load/resume/close以及client关闭/失败都会结束对应审批。scope核对使用捕获对象，旧prompt完成不取消新scope；无效恢复参数先拒绝，不关闭当前审批。每个审批原子终结一次，取消立即回复ACP cancelled，不等待UI线程；旧UI随后ALLOW不会再回复。排队但尚未显示的审批移出worker队列，已取消scope的迟到审批直接拒绝。保留最多9个client待决审批（1工作+8排队）、8个native槽位、64个已知permission session；session scope保留至重连以拒绝迟到审批，超限失败闭合，不提高原工具/图片/传输预算。

NativeAgentTransport的审批槽位绑定原prompt future，在terminal、取消或provider替换时默认拒绝并移除，不依赖客户端最终答复；三个native provider均接此scope。Swing请求观察本地lifetime：取消后在EDT dispose精确对应的dialog；已取消但还在EDT队列中的请求不再显示。UI等待超时/线程中断也结束该审批lifetime，不把超时说成宿主已停止。请求正常回复时退订scope并释放回调，保留5参数PermissionRequest构造兼容现有本地调用。

回归覆盖：取消立即回复与迟到ALLOW只答一次、prompt终态、有效resume/client close清理、无效resume无副作用、9个旧审批清队列后新prompt恢复容量、64个session上限拒绝新身份但允许已知身份复用、12轮无客户端答复的native槽位复用、并发取消只通知一次、退订和一个cleanup异常不阻碍其他owner。新增Xvfb测试实际打开生产Swing审批窗口，再从工作线程结束lifetime，验证返回CANCELLED且dialog不再displayable；重复使用已退役请求不会再弹窗。这是本地真实Swing窗口测试，不是Cubism窗口/模型审批实机。

旧fixture中“发审批后立即宣告prompt完成，仍期待稍后ALLOW/metadata”的时序已修正：合法审批、UI异常及敏感摘要测试先完成相应回调再终态；专门的终态抢先测试继续要求cancelled，未放松默认拒绝或隐私断言。最终Xvfb ACP全量248项中245通过、0失败/错误、3项opt-in smoke跳过，20套件；AcpClient37项、NativeAgentTransport48项、lifetime并发2项、Swing rendering15项全部通过无跳过。Java格式检查/JAR通过；未重跑全量MCP/Runtime或真实CLI模型请求。

本节补齐第61节所列本地审批窗口/槽位主动回收；未补齐无ID旧事件归属、真实harness审批/取消/恢复、宿主取消/回滚、模型视觉消费或ACP建模编排。不合作的自定义permission listener仍可能阻塞它的worker，生产Swing实现已观察signal；不强杀线程或声称任意listener已取消。总目标保持active，Milestone A仍未完成。


本节最终复核：标准native-observe验证包和自检通过，包内SHA256清单逐项一致，没有提交队列任务。270个本地Markdown链接、12份当前源码hash、证据隐私检查及git diff --check通过；旧章节的共享源码hash是当时快照，不追溯改写。


## 63. Claude 标准观察入口与提交授权边界

本轮只扩展测试专用 observer、标准包和隔离验证，没有修改生产 Java。新增 Claude native 路线，通过生产 AcpClient→NativeAgentTransport→NativeClaudeProvider 接同一受控只读 MCP proxy；仍走 Turboism prepare/submit/status，不直接启动宿主。固定 Claude2.1.284 完整安装树与 Java，manifest v2 为闭合 union；Pi v1 保持兼容。工具、文件、图片和请求预算不扩大。

Claude 缺少已核对的运行时视觉声明，因此先执行随机六位数字/四色位置图片 canary。回答全匹配、无工具调用且 system/init 工具集合安全后，才写私有 gate 开放宿主资源与 render；gate 拒绝符号链接、超限和非闭合字段。模型/工具变化拒绝。restricted、strict MCP、空 setting-sources、关闭 hooks/plugins/browser/slash commands/persistence，并只列出两个资源 builtin 与受控 MCP；权限回调默认拒绝。canary 是特定图片输入消费检查，modelVisionAccepted 仍 false，不能替代真实宿主 PNG 和回答人工核对或艺术质量验收。[探针说明](../acp-native-host-probe/README.md)。

首轮 job `9ba096e5-3b59-43a9-b79b-63ead39aa90e`、run `queue-8a3cd1fd5824428888f0815eea05f10f`，宿主已由标准任务启动，但 AgentLaunchSpec 在创建 native transport 前拒绝空 setting-sources argv，Claude 未启动、无模型 prompt/工具/图片。队列 failed、cleanup=safe、normalExit=false；最终 runner 身份/文件/golden 核对未完成，false/null 不能当作原件被改或检查通过。[失败摘要](live-host-20261026/claude-observe-launch-failure.json)。随后修正为单个 `--setting-sources=`，新增完整 launch 参数自检；真实受限参数 initialize/new-session 握手通过，未发模型 prompt、未连接 Cubism。

修复后的 preparedId `7b0078d379306ce54d97b2c4624c92517660d5de4ed815e227fcc95de9fe7484` 已生成。提交请求 `acp-claude-observe-20261026-02` 被自动审批拒绝，未进入队列，没有第二轮 job/run 或模型请求。拒绝原因：真实观察可能向现有 Claude CLI 配置的外部服务发送私有模型渲染与文档内容，“允许实机”未明确覆盖该载荷及目的地。没有绕过拒绝；等待明确授权前不重提。只读 proxy 限制宿主修改，不阻止允许的内容进入模型服务，两者不能混同。

离线验证包括随机 canary 匹配/错误/无法看图、工具名单未知拒绝、完整 launch factory、manifest 固定和安装代码变化拒绝、proxy gate 未就绪/通过/symlink 拒绝。标准包、Java 探针自检通过；隔离客户端/假队列180项全部通过。首次受限沙箱运行因本地测试socket bind被拒绝出现11失败/8错误，允许本地socket的隔离复测通过，不涉及真实队列或外部服务。当前源码身份、测试结果与授权阻碍见[本轮摘要](claude-observer-preflight-20261026.json)。未获得真实模型 canary/资源/渲染成功证据，未验收 native Swing 显示或 ACP 几何编排。Milestone A 与总目标保持未完成；历史源码 hash 不追溯改写。


## 64. 真实关键形写响应丢失与同会话结果核对

第63节外部模型内容授权仍待回复，本轮没有重试其提交或向任何外部模型发送内容。继续推进 SAFE-03/A4 的本地实机证据：测试专用 `ResponseLossHeadEditingClient` 仅在 exact5.3.03 标准 head-edit 案例中，完整读取第一次 keyforms.write 的 HTTP 响应后、JSON-RPC 解码前丢弃它，并向调用流程抛出专门异常。流程没有取得写入响应内容/状态，不缓存该响应作为证明；只使用相同 operationId 两次调用生产 keyforms.result，要求 APPLIED、匹配身份和稳定收据，再继续原有精确回读/历史验证。没有重发写入、新建 operationId、重连或凭当前几何猜测提交结果。

没有注入、结果 unknown/NOT_FOUND、身份不符、无收据、readback warning、两次结果变化或查询异常时，该案例失败且不凭未取得的receipt执行Undo。原有真实错误不被改为专门注入异常；没有扩大生产工具、权限、预算或结果语义。head-save/head-qa/standard 保留既有行为。见[标准流程说明](../../scripts/preview/README-mcp-validation.md#head-edit-response-loss-reconciliation)。

离线客户端55项、隔离假队列127项，共182项通过。新增测试覆盖解码前丢弃、每client只注入一次、结果查询不注入、原transport超时不冒充成功注入；同operation稳定查询、unknown/身份/receipt/warning/变化/查询失败均停止；完整三类几何流程在丢响应后成功恢复，未知结果不清理且写只提交一次。标准MCP包构建/探针自检、py_compile及diff检查通过；本轮不修改生产Java，不挪用旧MCP/Runtime/ACP全量计数。

标准任务 `904374cf-8382-4243-b04a-d5f9fa8136e9` / run `queue-763676eb53984a77bf4c5424c31f2534` / prepared `27c4f74baf5d2e7813330328f0f782235824b60a8662d27ca3148ccc89b39cc0` 已 succeeded、validationStatus=PASS、normalExit=true、cleanup=safe，fixtureUnchanged/identityVerified/goldenUnchanged均true。使用官方Hiyori任务副本，保留既有绑定，对实际ArtMesh/Warp/Rotation存储form做原有小幅内部可逆测试；响应注入一次、写尝试一次、结果只读查询两次且一致。全量stored rows精确回读、三类单步Undo/Redo、最终Undo和native故障回滚通过；修改前后三个pose共六张原生PNG hash与metadata逐一一致，三组图像hash均变化，参数扫描恢复通过。所有完整几何/原始PNG/模型继续私有，公共只归档metadata/hash。[本轮证据摘要](live-host-20261026/head-response-loss.json)。

官方原件独立磁盘SHA256仍为 `cd435579a8f12363493049233b97db47a7ac91e6aeb5993c7c5f93dc6374070f`。本轮没有保存/重开或专业视觉评审；六张图像hash核对不等于模型看图或美术质量通过。故障边界是客户端接收完整HTTP响应后丢失、同一仍有效MCP会话；不覆盖宿主崩溃、提交前断线、跨会话/跨JVM恢复、取消或真实Agent处理未知结果。Milestone A与完整目标保持未完成。

第63节preflight摘要中的共享Python源码hash现在是历史快照，本节记录新身份，不追溯改写。外部模型授权到达后须按当前代码重新构建/prepare独立观察任务，不复用此前准备身份来冒认当前实现。


## 65. ACP rawOutput 结果回退与真实窗口验证

继续检查恢复/结果接收的前置通路时，发现 AcpClient 的 tool_call/tool_call_update 只处理 content，完全忽略 rawOutput；适配器只提供原始工具输出时，收据和typed图片均不可见。重新读取本机Paseo0.11.2 acp-agent.js的 mergeToolSnapshot/mapToolSnapshotToTimeline：保留rawOutput，并在缺少投影文本时使用原始结果。本轮参考文件SHA256 `990bc04929092ea0b831d9cdd41783debc4236b377f9296c2fcdd0e0c57b9b7a`，已补[参考索引](harness-reference.md)，没有引入Paseo依赖或派发Agent。

原实现先运行三项新增回归，三项均失败：原始收据/未知结果、MCP图片及来源、unsupported binary/长文本。修复生产AcpClient：content为空或缺失、rawOutput非null时，通过既有NativeToolContent转换成typed内容；两类工具事件共用同一投影作文字/图片显示，继续沿用脱敏、UI文本/图片解码预算。原content非空则优先，不把raw图片与现有图片混合，不改变Agent报告status、不按图片metadata推断宿主成功，也不读取远程URL。此处只修复接收显示，不建立持久operation journal，不将对话恢复等同模型状态恢复。

四项新回归覆盖两类ACP事件、完整operationId/OUTCOME_UNKNOWN/不可重试信息、凭据脱敏；rawOutput MCP envelope图片字节及capture标签/解码；canonical content优先不显示raw图片；音频摘要和256KiB文本预算。完整Xvfb ACP 252项中249通过、0失败/错误、3项opt-in CLI smoke跳过，20套件；AcpClient41项、NativeToolContent3项、Swing rendering15项均通过。格式/JAR通过。没有重跑生产MCP/Runtime全量或真实CLI模型请求。

测试专用脚本ACP代理将verified原生MCP render结果改为rawOutput-only，明确不发送ACP content；agent记录nativeRenderAcpFormat=RAW_OUTPUT_ONLY。代理在模块受限真实子进程下的本地stub MCP selftest通过，并分别拒绝hash/state/type损坏，不转发坏图。[标准任务说明](../../scripts/preview/README-acp-validation.md#raw-output-only-render-forwarding)。真实Cubism/Swing任务结果和当前源码身份归档于[本轮摘要](acp-raw-output-20261026.json)。第63节外部模型内容授权仍待回复，本轮没有重试被拒绝的模型任务。


本节标准任务 `72f3fb03-68e8-4058-a10b-efdeb2a7465e` / run `queue-a7fb9e546a284a0c9e9979a2a22d5a99` / prepared `f798917e95f868b64b8681f8696a015fda8894378b939e6f309d7d178d5e5a41` 已 succeeded、validationStatus=PASS、normalExit=true、cleanup=safe，fixtureUnchanged/identityVerified/goldenUnchanged均true。脚本代理记录RAW_OUTPUT_ONLY；生产Swing只显示一个256×256图片组件，全部ARGB像素与宿主原生PNG相等，capture/document/model/revision/dimensions/tool标签均通过，无base64聊天文字。源PNG SHA256 `46eed740b85502158891dfd4d23d29626737499f071a691ae85f9ca1aff7d623` 与Agent/窗口报告一致。窗口截图、原生PNG、转录图片及报告仅保留私有任务目录，公开索引只含hash和必要布尔值。

原测试fixture源独立磁盘SHA256仍为 `2980dc8c695b13eeb54e37c02832edf80607979f2cc721ea9ca7b671f8bbac10`。首次prepare因未提供FIXTURE_5303路径在启动前拒绝，随后显式指定既有固定fixture及hash准备上述唯一任务；没有重放已启动任务或启动第二份宿主。此为同一具体脚本ACP-v1的rawOutput到真实窗口证据，不含外部模型看图、所有adapter、新的模型写入算法、恢复会话或持久任务journal；A整体继续未完成。共享AcpClient/Test源码hash更新记录在本节，旧章节仍为历史快照。

本节最终复核：当前标准ACP包依赖检查及SHA256清单通过；294个本地Markdown链接存在，6份当前源码hash及公共摘要隐私检查通过，git diff --check通过。没有遗留活跃的本轮实机任务。


## 66. 当前门槛与会话恢复边界复核

本轮先对照当前源码和实机索引复核下一步骤，没有修改生产代码或启动宿主。McpKeyformWriteDomain.result按MCP session ID+operationId查缓存；McpEvaluatedMeshDomain和authoring store同样按当前session取快照，evaluated始终明确freshness=UNPROVEN。NOT_FOUND/旧receipt不能授权重新写入或Undo。ACP对话恢复与宿主状态恢复之间仍缺完整持久任务契约，未用新增聊天日志冒充已解决。

已重写实施清单顶部/第8节的现状入口，避免早期“33工具、尚未连接宿主”被误当成当前结论；原历史表和全部A/B/C门槛保留。[当前建模检查点](current-readiness.md)逐项关联七个A硬门槛、三类原语/pose/窗口/文件证据及未覆盖范围；[机器可读摘要](readiness-20261026.json)记录本轮权威status与hash复核。两份最新标准任务重新status均succeeded、normalExit=true、cleanup=safe；Hiyori保存和pose QA索引的17份证据hash、最近9份当前源码hash全部一致。预期工具集合源码核对为80，未伪造新实时目录。

当前下一集成门槛是受控真实Agent视觉观察，Claude外部内容授权仍未回复；此前自动审批拒绝没有被重试或绕过。Pi失败服务没有重复请求。真实Agent观察、后续实际form编排/质量修正/任务恢复以及P1/P2范围均保留待完成；未宣布A或持续目标完成。本轮为证据与工作入口纠正，不将现有脚本窗口测试改称真实模型视觉通过。

## 67. Codex 6.1 Sol 只读观察通道

用户明确选择 Codex 6.1 Sol，并在首次提交被自动审批拒绝后，进一步明确授权将随机
验证码图、固定合成色块模型副本最多两张512×512渲染及受控文档/渲染身份元数据
发送到现有CLI配置的Codex模型服务。未发送Hiyori、用户创作素材或凭据。
此前被拒绝的提交没有执行；新的授权后采用新构建和新prepared身份。

[官方app-server文档](https://developers.openai.com/codex/app-server)及
[配置参考](https://developers.openai.com/codex/config-reference)已实际读取。
本机Codex 0.159.2的model/list明确列出`gpt-6.1-sol`、text/image，nextCursor为空；
没有用模型名称推断视觉能力，没有替换为其他模型。仅查询目录时不发送prompt。

测试专用observer/runtime manifest增加schemaVersion 3，固定模型、CLI版本、Node、
Java、entrypoint及完整安装树哈希。相同argv/cwd的无prompt config/read预检要求
只读sandbox、on-request审批、清空继承MCP并关闭shell、apply_patch、view_image、
web、code-mode、插件、子代理、记忆和宿主技能发现等。权限监听器默认拒绝。
这是配置生效证据，不是独立完整工具目录证明；不以systemToolsVerified冒充后者。
生产ACP注入只读代理，随机图像canary通过后才签发精确harness/model私有gate。
model/rerouted出现则拒绝，运行时未明确声明image则拒绝。

首次已授权任务`6614e3ab-93e9-4220-9f00-6cc5e9ad27aa`，run
`queue-00a19f17eb694e2ebcc199c7b2c37e4b`，prepared
`b3f5498555d313a9d65f2c15374626fcbc198366d502ab8ff0151574581fb079`：
配置预检和随机验证码/四格颜色canary全部匹配，HOST_OBSERVATION失败；0工具事件、
0宿主图片、代理1次拒绝。标准status为failed/FAIL、normalExit=false、cleanup=safe。
最终runner身份/fixture核对未完成，不能把false字段解释成已改变文件，也不能宣称实机通过。

随后纯本地假MCP、无模型prompt复现：Codex目录请求附带`_meta.progressToken`。
只读代理此前要求空目录参数，因此拒绝。用户随后要求陌生参数不能打断正常流程。最终修复按方法投影已定义的协议字段，丢弃
_meta及其他无关扩展，不转发宿主、不记录扩展值；目录接受空/缺失cursor及空params。
非空cursor、未知工具/资源、业务arguments错误、超限请求仍明确拒绝。render.state
缺失arguments规范化为空对象；capture仍要求完整尺寸/expectedState。回归覆盖未知
meta键/类型、未来扩展和多次后续请求正常完成，验证只有规范化的已知字段到达宿主。
没有放宽建模/文件权限；此修复限测试代理，不能冒称所有生产协议已做全面容错审计。

容错版任务`27a490ef-057b-477f-8735-3d36b081aca9` / run
`queue-17656936fbb344a5a7da43033399c5dd` / prepared
`3604cd045f72fbeb3828961d2bc7ea9a4de52ac430d7399c3f9de0c45bcc42ef`：
canary通过，tools/list附加字段被丢弃、代理0拒绝；仍无宿主工具/图像。
模型明确报告内部code-mode host被关闭，未推测图像或捏造身份。
标准status仍failed/FAIL、normalExit=false、cleanup=safe，最终runner核对未完成。
因此恢复Codex内部`features.code_mode_host=true`，保留模型`code_mode=false`及
shell/文件编辑等限制；内部host是MCP执行基础设施，不能为隔离而关闭它后声称工具可用。

最终标准任务`cfc03a1a-37ac-4062-8d23-750c85b14a20` / run
`queue-a74dd675468640d4992451a33f272c77` / prepared
`7599470e53f68e2b5dc01732a111eabd796aeb71ac9370d3ac7dea30e184d59d`：
**succeeded / PASS**，normalExit、fixtureUnchanged、identityVerified及details.goldenUnchanged
均true，cleanup=safe。目录/资源/工具的6项附加协议字段均被忽略，0拒绝；实际完成
resources/list、活动document和overview读取、render.state及render.capture，10项工具事件，
一个512×512原生PNG。PNG、代理与生产ACP图片回调SHA256均为
`74409151fb74ff7c91157570ec78f8e181e60536dd02674c1fa3ab2c603aed71`。

独立查看实际PNG后，模型对黑背景、青/蓝方块、左侧两块多色图、左下斜蓝形和底部
洋红方块的描述一致；回答中的capture/document/model身份也与原始结果精确匹配。
observer私有报告仍保留CAPTURE_DELIVERED_REVIEW_REQUIRED/modelVisionAccepted=false，
表示脚本不自我接受；随后人工查看PNG/回答的独立复核和受限modelVisionAccepted=true
记录于[公开哈希/状态摘要](live-host-20261026/native-observe-codex.json)。没有复制私有PNG、
canary答案、原生会话、配置或凭据进入仓库。

185项客户端/隔离队列离线测试通过，Java观察器自检和最终标准包7项SHA256清单通过。
原fixture独立SHA256仍为`2980dc8c695b13eeb54e37c02832edf80607979f2cc721ea9ca7b671f8bbac10`。
本轮没有新增生产接口或扩大权限，容错修改位于测试专用代理。此为exact5.3.03+Codex
0.159.2/gpt-6.1-sol+合成fixture的只读模型视觉证据；没有同链路Swing窗口、实际form
规划/写入、Hiyori外发或美术验收。A、B、C和完整自动建模目标继续未完成。

最终归档复核：审计采集器14项离线测试通过，7份当前源码hash匹配；本节相关文档的
165个本地链接存在，公开摘要无凭据/绝对路径/内嵌图片，git diff --check通过。


## 68. Codex 受控单顶点写入

用户在上一轮只读授权之外明确允许：固定合成模型副本中一个网格的完整关键形坐标、
绑定轴及必要身份外发给现有 Codex 6.1 Sol，并进行一次小幅单顶点受控写入，随后
回读、渲染、Undo/Redo及最终恢复。不包含Hiyori、用户素材或凭据，不保存、不改原件。
此前未获坐标授权时的提交被自动审批拒绝、未执行；收到授权后使用新构建和新快照。

独立标准任务 `mcp:5303@native-edit`，job
`8c279fff-f06a-4543-8345-aecf9615c43e` / run
`queue-ba267c70150847f1b8c83c3960d83735` / prepared
`ce6cec2deff08c983af6f15da76e7ed63a66728a77158fbf53ab5d13c8487e99`
已 **succeeded / PASS**，normalExit、fixtureUnchanged、identityVerified和
details.goldenUnchanged均true，cleanup=safe。[公开摘要](live-host-20261026/native-edit-codex.json)
仅保留状态、统计与hash；完整坐标、请求、图片、回答和收据留在私有任务目录。

测试专用manifest v4固定 `workflow: keyform-edit`、Codex0.159.2、gpt-6.1-sol及运行时
安装树，队列拒绝与观察variant混用。沿用生产AcpClient→NativeAgentTransport→
NativeCodexProvider和实际MCP Domains；没有新增生产写入后门。模型通过随机图片
canary和配置隔离预检，shell/文件编辑/子代理等保持关闭。内部code_mode_host保留，
协议未知扩展字段按方法丢弃；本次忽略13个无关字段、0拒绝，业务arguments继续闭合验证。

本地仅选择实际独立非默认form和完整GUID tuple；没有把预制positions交给模型。
模型自行发现资源、读取ArtMesh4完整218行快照，生成vertex0的x+1、y不变修改，
成功preview后发送完全相同write一次，结果APPLIED/COMMITTED，再读取完整快照。
独立比较确认只有一个form_point行的x改变；目标网格其余form/source/UV/indices及
样本Warp2/Rotation4的完整存储rows、kind/object/source身份保持。范围不扩张为全模型
所有对象或其他文档。代理最多允许一个顶点x正向移动min(4,x跨度/32)，拒绝其他变更。

模型经生产ACP收到两张512×512当前pose图片，PNG/代理/ACP回调hash逐一吻合；
当前EyeROpen=1，而被编辑form在EyeROpen=0。模型正确报告当前pose两图hash相同，
没有伪称已看见目标form变化。独立验证器另将参数移动到完整目标tuple并恢复，目标
pose修改前后PNG SHA256分别为
`74409151fb74ff7c91157570ec78f8e181e60536dd02674c1fa3ab2c603aed71`和
`aa07551d2eed0080968c808f4f0e9e68efd34e4eef346c847eece1b154ed031a`；
解码比较53个像素变化，差异框为(97,120,99,147)。实际PNG及模型回答已独立复核。
这证明小幅修改影响了目标pose，不是美术质量验收。

随后guarded Undo精确恢复baseline、Redo精确恢复expected，finally再次Undo；
最终所有上述sample存储rows、参数、document/model/historyGeneration和历史位置恢复。
没有保存文件，原fixture独立SHA256仍为
`2980dc8c695b13eeb54e37c02832edf80607979f2cc721ea9ca7b671f8bbac10`。
异常路径只在收据与历史entry/transaction身份匹配时恢复；尝试写入但无确切收据
记录OUTCOME_UNKNOWN，不盲目Undo或重放。失败也写验证报告。离线回归覆盖有收据
断线恢复、无收据不撤销；这些是本地故障测试，不冒充本次宿主断线实测。

189项客户端/隔离队列测试、14项审计采集测试及Java helper自检通过，标准包构建成功。
[探针说明](../acp-native-host-probe/README.md#codex-受控关键形写入)记录使用方式和限制。
私有observer仍保留CAPTURE_DELIVERED_REVIEW_REQUIRED/modelVisionAccepted=false；
独立验证接受的是这一个受控写入案例，不自动接受美术质量。没有同链路Swing、真实
Agent的Warp/Rotation写入、头部rig编排、文件保存重开或跨会话恢复证据；A/B/C继续未完成。

最终归档复核：11份当前源码hash一致、171个本地文档链接存在，公开摘要隐私检查与原fixture独立磁盘hash通过；标准包7项SHA256清单、shell语法检查及git diff --check通过。本轮任务已终态，无需再次提交或重放。

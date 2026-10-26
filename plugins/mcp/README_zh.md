---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.mcp
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: integration
tags: mcp, automation, external-tools
turboismApi: "[0.67.0,1.0.0)"
requiresCubism: true
interface: swing
---

# Turboism MCP 服务器

> **Turboism 官方插件** · **状态：预览**

在本地回环接口上运行令牌门控的 MCP Streamable HTTP 服务器；stdio 桥接器会自动携带令牌。

| 详情 | 值 |
|---|---|
| 版本 | `0.1.0` |
| 插件 ID | `dev.turboism.plugin.mcp` |
| 公开目录 | 80 个工具 · 15 个资源 · 2 个模板 · 8 个提示词 |
| 需要 Cubism | 是 |
| 接口 | `swing` |

## 功能概述

- 公开类型化、领域级的模型对象、参数、绑定、历史记录和 Editor 命令工具。
- 将活动文档、模型、工作区、Cubism Core 和经净化的运行时诊断公开为 JSON 资源。
- 为检查、诊断、编辑、恢复和有界的 Editor 自动化提供工作流提示词。
- 仅服务回环客户端，并强制实施来源、正文大小、协议、会话和速率限制。
- 通过运行时连接边界发布免凭据 stdio 启动描述符（当前 JVM 启动器、插件 classpath、桥接主类、状态目录），使 ACP 等捆绑消费方无需接触令牌即可将 Agent 挂接到预编译桥接。

## 要求与兼容性

- **SDK API 范围：** `[0.67.0,1.0.0)`。
- **Cubism：** Turboism 当前仅接受经过审查的精确 Editor 构件 `5.0.00` 至 `5.3.04`。面向宿主的资源在其公开 SDK 能力不可用时会以关闭状态失败；它绝不会伪造空的成功结果。
- **传输：** 使用协议 `2025-11-25` 的 MCP Streamable HTTP，并为受支持的较早协议版本进行兼容性协商。
- **接口模式：** `swing`，提供本地 MCP 连接信息窗口。
- **插件依赖项：** 未声明。

<!-- compatibility:begin -->
**Cubism Editor 版本支持（自动生成）：** 全部审核版本（5.0.00–5.0.06, 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04，共 20 个精确版本）.
完整功能（审核版本）：5.3.00–5.3.04。
- 外部编辑会话 API（edit()） 不适用于 5.0.00–5.1.01（[Cubism 5.1.00 新增外部应用联动（WebSocket API）](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history5-1/)）
- 顶部菜单集成 不适用于 5.0.00–5.0.06（更早版本 Cubism 本身无此功能）
- MCP 编辑器命令执行 不适用于 5.0.00–5.0.06（更早版本 Cubism 本身无此功能）
- MCP 编辑器命令清单 不适用于 5.0.00–5.0.06（更早版本 Cubism 本身无此功能）
完整矩阵: [compatibility matrix](../../compatibility/cubism/plugins_zh.md)
<!-- compatibility:end -->

## 安装与启用

1. 通过 Turboism 的官方发布包和**插件管理**安装并启用插件。
2. 打开 **Turboism → MCP 连接**。该窗口集中显示当前本地地址以及有界的进程内连接/请求历史。
3. 将 **stdio 客户端配置** 行复制到本地 MCP 客户端（推荐），或将 HTTP **地址** 复制到直连 HTTP 客户端。默认地址为 `http://127.0.0.1:43123/mcp`。stdio 客户端无需感知令牌；直连 HTTP 客户端进行写操作工具调用或关闭会话时，必须携带 `Authorization: Bearer <令牌>`（即插件状态目录中 `mcp.token` 的内容）。
4. 排查连接时可保持窗口打开，并点击**刷新**读取最新历史记录。
5. 程序化本地使用者仍可读取每用户插件状态目录中受所有者权限保护的 `mcp-connection.json`。
6. 完成 `initialize`，保留 `MCP-Session-Id`，发送 `notifications/initialized`，并在后续请求中包含协商后的协议版本。

服务器只绑定回环接口，连接文件仍使用所有者权限保护。可见历史不会包含 MCP 会话 ID，并会在进程退出时丢弃。

## 使用方法

使用 **Turboism → MCP 连接** 中显示的配置连接本地客户端；经 stdio 桥接时无需额外认证配置。

### 常见编码智能体

**Claude Code**

```bash
claude mcp add --transport http turboism \
  http://127.0.0.1:43123/mcp
```

等价的 Claude Code JSON 配置：

```json
{
  "mcpServers": {
    "turboism": {
      "type": "http",
      "url": "http://127.0.0.1:43123/mcp"
    }
  }
}
```

**Visual Studio Code**

```json
{
  "servers": {
    "turboism": {
      "type": "http",
      "url": "http://127.0.0.1:43123/mcp"
    }
  }
}
```

**Codex CLI**

添加：

```toml
[mcp_servers.turboism]
url = "http://127.0.0.1:43123/mcp"
```

### 公开 MCP 目录

#### 工具

| 工具 | 用途 |
|---|---|
| `turboism.model_objects.apply` | 应用有序的创建、重命名、重新设定父级和删除操作。 |
| `turboism.parameters.apply` | 应用类型化的参数值和定义操作。 |
| `turboism.parameters.structure.read` | 读取有界参数文件夹混合顺序、定义和显式 Combined 状态。 |
| `turboism.parameters.structure.plan` | 预览顺序结构编辑、批内新建引用和完整删除影响。 |
| `turboism.parameters.structure.apply` | 按会话计划核对状态，在独立原生 authoring 事务中应用。 |
| `turboism.parameters.structure.result` | 无宿主读取地查询保留结果。 |
| `turboism.authoring.meshes.triangulate` | 预览原生剖分，返回实际点序/三角形，不推测 UV 映射。 |
| `turboism.authoring.meshes.create_plan` | 计划新建源几何/默认形分离网格，显式父部件、UV 和可选图像。 |
| `turboism.authoring.meshes.create` | 在独立事务中批量创建并可选绑定图像，核对完整声明状态及回读。 |
| `turboism.authoring.meshes.create_result` | 零宿主读取查询创建结果。 |
| `turboism.documents.read` | 读取有界项目文档身份，支持空会话，不返回路径。 |
| `turboism.documents.new_model` | 条件分派一次 NEW_MODEL，核对新增活动模型与 authoring 身份。 |
| `turboism.documents.new_result` | 零宿主读取查询保留的新建结果，不重复分派。 |
| `turboism.parameter_bindings.apply` | 应用类型化的参数绑定操作和原生原子传输。 |
| `turboism.glues.read` | 列出或读取精确版本路由的 Glue 创作状态。 |
| `turboism.glues.write` | 应用一次带原生撤销能力的 Glue 修改。 |
| `turboism.textures.read` | 读取有数量上限的纹理库元数据和当前状态前置条件。 |
| `turboism.textures.write` | 执行一次受状态校验保护、支持独立原生撤销的纹理库操作。 |
| `turboism.assets.binding.read` | 依据 authoring 快照读取 ArtMesh 当前图像/图集绑定和有界模型图像候选。 |
| `turboism.assets.binding.bind` | 完整 CAS 下绑定一张已发现的现有模型图像，独立 SDK 事务与精确回读。 |
| `turboism.assets.binding.result` | 读取会话内保留的历史绑定结果，不查询宿主。 |
| `turboism.assets.images.register` | 通过一次性 READ grant 启动有界 PNG 注册及绑定，返回异步任务。 |
| `turboism.assets.images.result` | 不查宿主，核对注册阶段、实际内容哈希和历史原生事务/绑定结果。 |
| `turboism.assets.images.cancel` | 请求在原生事务准入前取消，已经准入的宿主工作继续核对真实结果。 |
| `turboism.atlas.read` | 读取有界、会话绑定的完整图集快照。 |
| `turboism.atlas.plan` | 无写入生成可审阅的确定性 shelf 布局。 |
| `turboism.atlas.apply` | 启动独立原生 Undo 的完整矩形布局任务。 |
| `turboism.atlas.result` | 不访问宿主读取保留任务结果。 |
| `turboism.atlas.cancel` | 请求在原生准入前取消。 |
| `turboism.export.moc.plan` | 读取导出设置和源元数据，签发会话内不可变计划；不导出、不消费授权。 |
| `turboism.export.moc.execute` | 原生 MOC/Core 校验、PNG/静态 model3/清单 ZIP 编码及授权原子落盘。 |
| `turboism.export.moc.result` | 零宿主访问核对导出/文件结果与资产哈希。 |
| `turboism.export.moc.cancel` | 请求在文件写入准入前取消。 |
| `turboism.history.read` | 读取不可变的原生撤销历史快照。 |
| `turboism.history.undo` | 使用世代、修订版本及可选条目标识执行受保护的撤销。 |
| `turboism.history.redo` | 使用世代、修订版本及可选条目标识执行受保护的重做。 |
| `turboism.transaction.execute` | 将符合条件的读取和可撤销写入组合为一个创作事务。 |
| `turboism.authoring.meshes.read` | 分页读取不可变 ArtMesh 源几何、GUID axes/cells/forms 与 Morph 身份。 |
| `turboism.authoring.meshes.evaluated.read` | 观察原生最近计算顶点及 GUID 参数消费状态，不宣称新鲜度。 |
| `turboism.authoring.warps.read` | 读取 Warp 存储控制网格及完整 GUID axes/cells/forms。 |
| `turboism.authoring.rotations.read` | 读取 Rotation 基准角、每个存储 form 及完整 GUID axes/cells。 |
| `turboism.authoring.keyforms.preview` | 对实际写请求执行完整 CAS 预检，返回原生空间差异和预计哈希。 |
| `turboism.authoring.keyforms.write` | 以完整快照 CAS 写普通 ArtMesh 顶点/绘制顺序/透明度、Warp 控制点及 Rotation form。 |
| `turboism.authoring.keyforms.result` | 按 operationId 查询会话内保留的历史结果，不读取或修改模型。 |
| `turboism.render.state` | 读取活动文档、历史、实际参数姿态和渲染预算，不调用渲染器。 |
| `turboism.render.capture` | 按预期状态调用模型原生渲染，返回 typed MCP PNG 图片。 |
| `turboism.render.pose_grid` | 显式调参扫描最多九姿态，逐图返回状态与索引，并恢复原参数值。 |
| `turboism.render.pose_grid.status` | 不排队等待 UI，读取扫描进度与真实终态。 |
| `turboism.render.pose_grid.cancel` | 请求在安全边界取消；当前宿主调用与恢复仍须完成。 |
| `turboism.render.pose_grid.result` | 查询保留的逐姿态图片、状态、恢复标记与事务收据。 |
| `turboism.render.pose_grid.release` | 消费证据后释放终态扫描像素，保留元数据和防重放身份。 |
| `turboism.validation.geometry` | 只读检查明确范围内的 ArtMesh 拓扑、面积、长宽比、翻面和边长比例，不自动修复。 |
| `turboism.validation.warp` | 只读诊断存储或拟议 Warp 控制单元的双线性折叠、退化和参考方向。 |
| `turboism.validation.raster` | 只读检查会话保留 pose PNG 的显式局部颜色占比或帧间差异，不访问宿主、不认定美术质量。 |
| `turboism.rig.rotation.plan` | 从核对后的 Rotation 存储快照只读生成一至三普通轴角度替换计划，返回另行执行的 CAS 写请求。 |
| `turboism.rig.warp.plan` | 对已有一至三轴 Warp 网格预览原生坐标锚点位移场，不写入、不推断美术语义。 |
| `turboism.rig.mesh.plan` | 预览原生存储顶点锚点位移场与显式 XY 残差，返回独立 typed 写入请求。 |
| `turboism.authoring.masks.read` | 读取含源 GUID 的有界 ArtMesh 蒙版依赖快照。 |
| `turboism.authoring.masks.write` | 在一个 SDK 事务中条件替换非空蒙版列表及反向标志。 |
| `turboism.authoring.masks.result` | 不等待 UI，查询当前会话保留的蒙版操作结果。 |
| `turboism.authoring.properties.read` | 基于 authoring 快照读取对象自身可见性/锁定及 ArtMesh 剔除属性。 |
| `turboism.authoring.properties.write` | 在完整存储状态 CAS 和一个 SDK 事务内写入显式源级布尔属性。 |
| `turboism.authoring.properties.result` | 不进入 UI 队列，查询保留的源级属性写入结果。 |
| `turboism.files.request_grant` | 异步请求用户选择文件，签发会话内授权标识。 |
| `turboism.files.grant_status` | 查询授权状态，不等待原生文件选择窗口。 |
| `turboism.files.revoke_grant` | 撤销授权并释放迟到的原生授权句柄。 |
| `turboism.documents.open` | 在精确 5.3.03 分派授权 CMO3 OPEN；就绪状态另行发现。 |
| `turboism.documents.save_as` | 使用同会话 WRITE 授权，将预期活动模型另存到新目标。 |
| `turboism.documents.file_result` | 不等待 UI，核对保留的原生文件命令结果。 |
| `turboism.files.inspect` | 通过单次 READ 授权，异步读取文件字节数与完整读取的 SHA-256。 |
| `turboism.files.inspect_result` | 不等待 I/O，查询保留的文件观察结果。 |
| `turboism.capabilities.read` | 读取操作效果、版本支持、事务资格和 SDK 覆盖账本。 |
| `turboism.editor_commands.execute` | 执行可发现的直接 Editor 命令和类型化的非文件 Editor 命令。 |

整个批次会在首次写入前完成输入 Schema 检查。后面的条目存在格式错误时，前面的条目也不会执行。格式正确的批次仍可能因运行时失败而部分成功；已完成结果会保留，`stopOnError` 决定是否继续执行。单个原生子操作的原子性不代表整个 MCP 批次具有事务性。

##### 写入结果与反转操作迁移

已确认的参数写入会保留 `outcome`、`retryable: false` 和对象身份，不会被后续回读失败覆盖。详情回读失败返回 `APPLIED_WITH_READBACK_WARNING`；可选的最终参数快照失败时，`parameters` 为 `null`，并附带 `parameterSnapshotWarning`，已完成的 `results` 不会丢失。不要因为回读不可用而重试已确认的写入。

含义不明确的参数绑定操作 `invert` 已被禁止，且会在写入前拒绝。显式反转目标对象的**全部普通关键形绑定**应使用：

```json
{
  "operations": [{
    "operation": "invert_all_bindings",
    "scope": "all_target_bindings",
    "targets": [{"type": "art_mesh", "id": "ArtMesh1"}]
  }]
}
```

该操作不是单参数反转，也不反转混合形状绑定。结果包含操作范围和受影响的参数 ID；回读成功时还包含全部受影响的普通绑定。不接受 `parameterId` 字段。单参数反转需要独立的、已验证的 Provider 契约。

既有 `model_objects.apply`、`parameters.apply` 和 `parameter_bindings.apply` 三个工具仍为 `transactionEligible: false`，`turboism.transaction.execute` 会在开启事务前拒绝它们。这不表示单个原生操作没有 Undo。`turboism.capabilities.read` 会公开这些临时例外。覆盖账本现在跟踪明确列出的十八个 SDK 类型，包括模型对象、参数、绑定和纹理接口，但不声称覆盖整个 SDK。`MCP_LEGACY_WRITE` 表示已公开的旧式写入，不代表已具备分组事务能力；新增账本行中空的 `supportedVersions` 表示账本未作精确版本声明，实际运行仍受 Provider 准入控制。

#### 纹理库创作

先调用 `turboism.textures.read`，参数为 `{"operation":"list"}`，再把返回的 **`state` 对象** 原样放入写入请求的 `expectedState`。另一个 `stateToken` 字符串仅用于关联本次读取，不是写入前置条件或授权令牌。写入或撤销／重做后应重新读取状态；原生历史不可用时拒绝写入。

适配范围为精确版本 `5.2.03 / 5.3.00–5.3.04`，操作包括 `add_model_image_group`（`name`）、`remove_model_image`（`id`）、`add_texture_atlas`（`name`、`widthPixels`、`heightPixels`）、`remove_texture_atlas`（`id`）和 `remove_raw_image`（`id`）。所有删除都必须显式传入 `confirmDelete: true`。模型图像组使用名称，不伪造 GUID；重名创建会在修改前拒绝。

名称和 ID 上限为 256 个 Unicode 码点，图集每边尺寸为 1–16384 像素。读取时，每个顶层集合最多 1024 项，嵌套模型图像总数最多 1024；超限返回错误而非静默截断。成功写入后的回读失败保留结果标识，返回 `APPLIED_WITH_READBACK_WARNING` 和 `postState: null`。无法证明结果的原生异常返回 `OUTCOME_UNKNOWN`，不会误称 `NOT_APPLIED`，也不会建议自动重试或泄露宿主异常中的路径。

这些操作有独立原生 Undo，但 `transactionEligible` 仍为 `false`；覆盖账本使用 `MCP_WRITE_STANDALONE_UNDO` 区分它们与可加入分组事务的操作。删除原始图像仅处理指定原始图像及其输入连接，保留模型图像、ArtMesh 和图集；5.2 的实现组合原生 Undo 操作，不调用删除对话框。

这是纹理库增删，不是像素上传、任意文件导入导出或通过 MCP 应用图集布局。版本元数据说明运行时适配与测试范围；实机验收单独记录，不能由接口注解或单元测试推断已通过实机验证。

#### 资源

| 资源 URI | 用途 |
|---|---|
| `turboism://active/document` | 活动项目、文档、模型、选择状态、工作区和主题快照。 |
| `turboism://active/model/overview` | 紧凑的活动模型和选择状态概览。 |
| `turboism://active/model/hierarchy` | 活动模型对象层级结构。 |
| `turboism://active/model/clip-masks` | 活动模型 ArtMesh 剪切蒙版记录。 |
| `turboism://active/model/parameters` | 实际的活动模型参数状态。 |
| `turboism://active/model/statistics` | 结构、几何体、纹理、蒙版和可选离屏计数。 |
| `turboism://active/model/textures` | 不含路径或字节的原始图像、模型图像组和纹理图集元数据。 |
| `turboism://active/model/parameter-bindings` | 活动模型的聚合参数绑定状态。 |
| `turboism://active/document/history` | 原生撤销可用性、条目、世代、修订版本和位置。 |
| `turboism://environment/cubism-core` | 已接受的 Cubism Core 版本和公开能力标志。 |
| `turboism://environment/workspace` | 当前和可用工作区及其类型化可用性。 |
| `turboism://environment/workspace/layout` | 具有类型化可用性的有序只读停靠布局树。 |
| `turboism://environment/diagnostics` | 有界且路径已脱敏的 Turboism 启动诊断问题。 |
| `turboism://environment/runtime-diagnostics` | 最近的、有界且已净化的 MCP 运行时结果。 |
| `turboism://host/editor-commands` | 当前可用的受支持 Editor 命令和类型化请求架构。 |

资源是某一时刻的快照。服务器当前不声明订阅或资源更新通知。

工作区和布局资源可能成功返回 `availability: "UNAVAILABLE"`，并附带诊断代码。这是一种类型化的宿主状态，不同于针对权限拒绝（`-32001`）、资源不存在（`-32002`）、不受支持的能力（`-32003`）、超时（`-32004`）或取消（`-32800`）的 JSON-RPC 错误。

#### 资源模板

- `turboism://active/model/parameters/{parameterId}`
- `turboism://active/model/parameters/{parameterId}/bindings`

#### 提示词

- `inspect_active_document`
- `edit_model_structure`
- `normalize_parameters`
- `repair_parameter_bindings`
- `recover_document_history`
- `run_editor_command`
- `diagnose_environment`
- `inspect_model_diagnostics`

提示词不接受参数。两个诊断提示词明确禁止变更操作。

## 功能能力

### 声明的能力

| 能力 | 对用户的作用 |
|---|---|
| `mcp.streamable-http` | 在数字回环接口上提供令牌门控的 MCP Streamable HTTP 服务（写操作需 Bearer）。 |
| `mcp.tools` | 发布 80 个类型化工具工作流。 |
| `mcp.resources` | 发布静态和模板化 JSON 资源。 |
| `mcp.prompts` | 发布由用户控制的工作流提示词。 |
| `cubism.workspace.read` | 读取类型化工作区状态和停靠布局快照。 |
| `cubism.model.objects.read` | 检查受支持的模型对象。 |
| `cubism.model.objects.write` | 创建、重命名、重新设定父级和删除受支持的模型对象。 |
| `cubism.parameters.read` | 读取活动模型参数。 |
| `cubism.parameters.write` | 应用类型化的参数值和定义操作。 |
| `cubism.parameter-bindings.read` | 读取参数绑定。 |
| `cubism.parameter-bindings.write` | 应用类型化的绑定操作和原生原子传输。 |
| `cubism.history.read` | 读取原生撤销历史。 |
| `cubism.history.write` | 使用世代和修订版本防护移动原生撤销历史。 |
| `cubism.editor-commands.execute` | 执行有界的受支持 Editor 命令界面。 |

## 权限

| 权限 | 作用域 | 请求原因 |
|---|---|---|
| `turboism.cubism.model.read` | `application` | 读取活动模型对象、Core 元数据、统计信息和纹理元数据。 |
| `turboism.cubism.parameter.read` | `application` | 为工具面声明参数读取意图；实际参数读取在运行时由上方的模型读取权限强制执行。 |
| `turboism.cubism.project.read` | `application` | 读取活动项目、工作区、布局和主题状态。 |
| `turboism.cubism.model.write` | `application` | 应用类型化的模型、参数、绑定、历史记录和模型设置写入。 |
| `turboism.ui.file-chooser.request` | `application` | 在签发会话内文件授权前，通过文件选择器请求用户批准。 |
| `turboism.file.read` | `application` | 在发布 loopback 端点前校验 MCP 连接文件元数据（存在性、所有者、符号链接检查）。 |
| `turboism.file.write` | `application` | 允许直接 Editor `SAVE` 命令，并向 MCP 状态目录写入仅属主可读的 loopback 连接文件。 |
| `turboism.network.fetch` | `application` | 允许类型化的外部应用程序设置命令，并绑定受 Origin 防护的 loopback HTTP 传输。 |
| `turboism.process.run` | `application` | 允许类型化的外部应用程序设置命令。 |
| `turboism.mcp.connection.publish` | `application` | 通过进程内运行时交换，将当前回环端点发布给已获权限批准的自动化插件。 |
| `turboism.action.register` | `application` | 注册打开本地 MCP 连接窗口的操作。 |
| `turboism.ui.menu.contribute` | `application` | 在 Turboism 菜单中添加 **MCP 连接**。 |

诊断扩展不会新增 `host.unsafe`、性能、文件读取、配置、事件或 UI 变更权限。

## 隐私与数据

### 网络

服务器仅监听 `127.0.0.1`。请求的 `Host` 头必须是回环地址（`127.0.0.1`、`localhost` 或 `[::1]`，可带 `:port`），并满足回环来源、MCP 协议与会话、1 MiB 正文上限和速率限制；写操作工具调用与会话关闭还要求 `Authorization: Bearer <令牌>`（即 `mcp.token` 的内容），只读方法无需凭据。设置系统属性 `turboism.mcp.requireAuthForRead=true` 可将 Bearer 要求扩展到所有 `tools/call`（包括读操作工具）。它并非为远程访问而设计。

### 本地数据

插件仅将连接元数据、每用户 Bearer 令牌和 stdio 桥接源文件写入插件状态存储，均以仅所有者权限发布。在 POSIX 系统上，它会尝试设置仅所有者权限。诊断和模型资源不会公开原始文件系统路径、原生宿主对象或图像字节。

`turboism://environment/diagnostics` 会省略 `DiagnosticReport.Problem.path()`，限制问题列表，将消息转换为单行，限制消息长度，并脱敏 Unix 路径、Windows 路径和 `file:` URI。

### 遥测

本插件不会发送遥测数据。插件生命周期和失败记录可能会出现在 Turboism 的会话日志和 Cubism 的宿主日志中，并附带插件 ID。

## 状态与限制

- **状态：** 预览。
- 稳定默认端口为 `43123`。仅在显式设置 `turboism.mcp.port=0` 时选择临时端口。`turboism.mcp.requestsPerMinute` 是高级速率限制系统属性。`turboism.mcp.requireAuthForRead`（`true`/`false`，默认 `false`）使读操作 `tools/call` 同样需要 Bearer 令牌。
- 未实现 GET SSE、资源订阅、列表变更通知、进度通知和 MCP Tasks。
- 工作区切换和默认布局变更被有意设为不可用，因为运行时当前以 `turboism.host.unsafe` 对它们进行门控。
- 在 MCP 会话无需接受原始路径即可获得真实 `UserFileHandle` 授权之前，`EditorFileCommandRequest`、导入/导出、另存为、备份和其他基于句柄的文件工作流始终不可用。
- 不公开通用 SDK 调用、反射、任意原生成员、shell 执行、原始路径、对话框自动化和生命周期注册 API。
- 性能采样、画布/配置文件、物理/动画、纹理像素导入及通过 MCP 应用图集布局、屏幕截图和二进制资源仍是独立的未来能力，具有各自的权限和精确宿主证据要求。
- 删除操作具有破坏性。默认会拒绝被引用的对象；必须显式请求级联。

## 故障排除

| 症状 | 检查事项 |
|---|---|
| MCP 客户端无法连接 | 打开 **Turboism → MCP 连接**，确认进程正在运行，并使用窗口显示的精确回环地址。如果端口 `43123` 被占用，请停止冲突进程或设置显式高级端口覆盖项。 |
| 请求在分派前被拒绝 | 检查方法、来源、会话、MCP 协议版本、正文大小、内容类型和速率限制。 |
| 资源返回 `UNAVAILABLE` | 检查活动文档/模型状态和精确宿主能力准入；不要将其视为空的成功值。 |
| 资源返回权限被拒绝 | 检查插件描述符授权以及上文命名的特定运行时权限。 |

## 更新日志

### 0.1.0

- 初始版本发布。

## 支持与许可证

- **项目网站：** [https://turboism.dev](https://turboism.dev)
- **发布者：** Turboism Contributors
- **许可证：** Project License
- **插件 ID：** `dev.turboism.plugin.mcp`

## 模型原生渲染

先调用 `turboism.render.state({})`，再将返回的完整 `state` 作为 `expectedState`，连同显式 `width`、`height` 传给 `turboism.render.capture`。读取当前姿态，不移动参数或选择。Runtime 在调用时校验精确宿主准入和 GPU 就绪；失败不返回缓存图或文件缩略图。状态读取成功不证明渲染器可用。

上限为单边 1024、1,048,576 像素、3MiB PNG、2048 条参数元数据。图片可能超过事务单步结果预算，因此两个工具均不参与 authoring transaction。文档、历史或姿态在渲染前后改变时丢弃图片。元数据包含 captureId、时间、pose、history、尺寸和 SHA-256；图片坐标原点在左上角。SDK 未提供模型到图片的矩阵，`modelToPixelTransform` 明确为 null。裁剪、pose grid 与限定检查见下文；真实宿主验收仍未完成。

## 存储关键形读取

`turboism.authoring.meshes.read`、`warps.read`、`rotations.read` 必须传入已发现的 `documentId`、`modelId`、`objectId`。首次省略 `snapshotId`，从 offset 0 开始；后续用同一 `snapshotId` 和 `nextOffset` 翻页。每页是同一个不可变 SDK 快照的 typed rows，不移动参数、选择或触发求值。这里提供 source/stored 数据，尚不提供 evaluated 几何。

ArtMesh 的 `mesh_form` 行还返回 `opacity`：已准入的原生存储有限数值；selector 不可用或旧 SDK 实现未观察时为 `null`，未知不能当作 1。Morph 原始值不钳制，也不解释为普通形绝对透明度。该观察参与不可变快照相等性/CAS，顶点/绘制顺序写入与锚点计划保留原值；读取不写入透明度、不移动参数；普通透明度 writer 由 keyforms.write 另行准入。Warp/Rotation 透明度和存储颜色仍须独立契约。

`ordinary_write_support` 行现包含闭合的 `operationSelectors`：按对象类型返回 ART_MESH_POSITIONS、ART_MESH_DRAW_ORDERS、ART_MESH_VALUES（顶点/顺序）、ART_MESH_VALUES_WITH_OPACITY、WARP_CONTROL_POINTS 或 ROTATION_FORMS。透明度单独核对原生 selector 扩展，几何 PRESENT 可以与透明度 ABSENT 并存。缺失条目表示 UNKNOWN；旧 provider 返回空 map。这是快照时的 selector 观察，与 reviewedHost、historySelectors、transactionCoordinator、writePermission 和逐 form 资格分开；PRESENT 不授予写权限，也不绕过默认/共享/Morph/编辑状态/CAS/预算/事务检查。状态改变后重新发现，writer 仍实时核对。动态会话视图保留这些观察，并在通用 deformers.find/all 中保留实际 Warp/Rotation 类型和代际保护。

rows 保留 source/form/parameter GUID、完整 axes/cells、共享引用、轴默认值及父级身份。不得根据第一个 key 推断默认形，不得把显示参数 ID 当成 GUID。坐标保留原生 SDK 值，不伪造父级变换或画布/像素转换。快照绑定 MCP 会话，5 分钟过期；最多 16 份，每份序列化 rows 4MiB、总计 16MiB、100,000 行，每页最多 512 行/240KiB。文档或历史改变后重新读取。缓存保留完整 SDK expected state 供后续 CAS，token 不授予写权限。存储几何写入使用独立 keyforms.write 契约；真实宿主验收仍待完成。

### 普通关键形批量写入

先通过三类 authoring read 获取真实快照，再调用 `turboism.authoring.keyforms.write`，显式传入 `documentId`、`modelId`、唯一 `operationId` 和 `targets`。每个 target 包含 `kind`、`objectId`、已颁发的 `snapshotId` 和 `edits`；每个 edit 包含 `formGuid`、覆盖所有普通绑定轴的 `coordinates`（parameterGuid/value），以及 ArtMesh 的 `positions`/`drawOrder`/`opacity`（显式提供的 positions 必须完整）、Warp 的完整 `controlPoints` 或 Rotation 的完整 `form`。所有字段以实时 inputSchema 为准，禁止使用显示参数 ID 猜测 GUID。

写前比较完整原生 snapshot 和父级身份，并在一个 SDK 事务内再次检查全部目标；只开放独立、非默认、已存在且在 Runtime 支持域内的普通 form。默认形、共享形、Morph 引用、拓扑变化和跨类型字段均拒绝。上限为 8 个对象、64 个 form、合计 16,384 点及 768 KiB 请求；工具自身拥有事务，不能嵌入 `transaction.execute`。成功或无变化后重新读取快照再发起下一次写入。

结果含事务收据、完整快照前后哈希及回读验证标记。响应丢失后调用 `turboism.authoring.keyforms.result({operationId: ...})`；相同 operationId 与相同请求只返回已保留结果，冲突复用拒绝。RUNNING、ROLLED_BACK、OUTCOME_UNKNOWN 和 APPLIED_WITH_READBACK_WARNING 必须分别处理；取消 Agent 或停止等待不能冒充宿主回滚。未找到结果表示未知，不表示未写入。结果按 MCP 会话隔离，仅在当前服务器进程存活期间保留；全服务器最多保留 256 个已准入操作，不淘汰后重放，满额则拒绝新写入。跨会话/服务器重启后的恢复与实机 Undo/Redo、保存重开仍需独立验收。

### 多姿态扫描与恢复

先读取 `render.state`，再将完整 state 作为 `render.pose_grid` 的 expectedState，明确指定 operationId、width/height、poses、timeBudgetMs 和 restorePolicy:"always"。每个 pose 包含唯一 label 与 values:[{parameterId,value}]。每个 pose 都相对原始基线求值，未指定的目标轴恢复为原始值，不继承上一姿态。此工具临时写参数，需要写权限与 Bearer；它自身拥有 SDK 事务，不可嵌套在 transaction.execute。即使值已恢复，也可能留下一个 Undo 条目，实际以返回收据为准。扫描后应重新发现 render/authoring 状态；参数值恢复不表示历史 revision 或旧几何快照仍有效。

上限：9 pose、6 个不同目标轴、合计 1,048,576 像素、3 MiB PNG、240 KiB 结果元数据；规划/基线元数据最多 128 KiB。最多 60 次参数值写入/恢复，低于 SDK 事务的 64 项限制。标准读取/单次 capture 的参数元数据也限制为 128 KiB。SDK 当前参数列表会把重复显示 ID 指向第一个参数；因此渲染状态和 pose 扫描在重复 ID 时失败闭合，不能拿 ordinal 冒充 native GUID。

`frames` 的每项有实际 pose state、参数值、captureId、PNG 哈希及 imageContentIndex，索引指向同一次 MCP content 的 typed image；九宫格返回九张图片，不只保留最后一姿态。全局图片附件数量从 8 调为 9，以支持 XY 九宫格，累计 PNG 3 MiB、HTTP 请求 1 MiB、Pi 桥接 8 MiB 和事务单步结果 256 KiB 均未提高。

长扫描响应丢失后，使用 status/result 核对 operationId。cancel 是协作式请求；timeBudgetMs（最多 30 秒）在宿主调用之间检查，不能强制中断 GPU、setter 或恢复。失败/取消也尝试恢复，并核对全部原始参数；文档/模型/代际变化时拒绝向新目标恢复。parametersRestored=true 仅表示返回时核实了原作用域全部参数值；false 表示未证明恢复。FAILED、CANCELLED、TIME_BUDGET_EXCEEDED、RESTORE_UNVERIFIED、OUTCOME_UNKNOWN 要分别处理，不能把连接中断当成宿主无改动。

结果按会话隔离、进程内保留，最多同时保留 2 组图片及 64 个操作身份。证据消费后显式 release 终态图片，才能继续扫描；release 不删除元数据或允许旧请求重放。释放后的 result 不含图片，frames 的 imageContentIndex 为 null，imagesReleased 为 true。不存在的结果表示未知；不具备跨会话或服务器重启后的持久化恢复。几何/视觉 QA、真实宿主/GPU、模型看图、保存重开仍须独立验收。

## 范围明确的几何诊断

`turboism.validation.geometry` 使用 `authoring.meshes.read` 签发的快照。必须传入 documentId/modelId/objectId/snapshotId、includeSource、referenceFormGuid、formGuids、thresholds、maxFindings。存储形状比较要求显式指定同对象的普通参考 form；只查 source 时 formGuids=[]、referenceFormGuid=null。默认/共享普通 form 可只读诊断；Morph 引用或非普通网格 form 拒绝。分析前后均核对完整原生快照、父级和历史身份，历史 revision 未变的几何变化也拒绝。

已实施空/未用/重复/非流形拓扑、绕序一致性、三角形面积/长宽比、相对参考翻面/边长比例，以及每个目标几何内部的三角形正面积重叠。面积使用绝对有向双倍面积相对包围盒对角平方；长宽比为边长平方和/(2*sqrt(3)*绝对双倍面积)。阈值须显式且有限。最多 32 目标 form、200 条保留 findings、2M 预估工作量、240 KiB 结果。截断或比较未定义返回 INCOMPLETE；ok 只说明诊断执行成功，不表示模型验收。边界接触、跨对象交叉、裂缝、求值/插值连续性、UV/图集、画布、蒙版/遮挡、美术质量明确尚未实施。不移动参数或选择，不渲染或写入。

内部重叠使用按 X 排序的包围盒扫描和分离轴判定，以实际二进制 float 坐标为准；双精度误差界无法确认方向时，使用精确保留二进制值的 BigDecimal 谓词。包含、重合及相邻面片折叠均可能报告 WARNING；只有共享边/顶点或边界接触不会报重叠。finding 的 triangleIndex/relatedTriangleIndex 定位两面片，indices 仅列第一片顶点，第二片由已签发拓扑查回。source/每个 stored form 独立检查，不跨对象、不自动修复。

原有 2M 预估工作预算同时约束基础诊断、排序、扫描和精确谓词。耗尽时 intersectionCheckComplete/analysisComplete/comparisonComplete=false，verdict=INCOMPLETE；统计 intersectionPairsTested、intersectionSweepVisits（含包围盒排除的访问）和 estimatedWork。此时 findings/counts 仅代表已检查部分，可缩小目标 form 范围后重查。finding 截断和参考比较未定义仍独立导致 INCOMPLETE。严格正面积重叠是几何证据，不直接等于美术缺陷或全模型验收。

`authoring.keyforms.write` 的 ArtMesh edit 可携带 positions、drawOrder（0..1000 整数）或两者；批次可混合这些类型。身份仍使用实际 formGuid 和完整 coordinates。纯顺序写入调用专用 SDK writer，保留全部顶点；联合/混合写入使用一个 combined SDK contribution，省略字段从不可变 expected 快照补齐，使两个 SDK 列表对应相同 form。补齐保留的顶点也计入 16,384 点预算。默认/共享/Morph 保护、跨对象事务、完整回读、operationId 及结果语义继续生效。预期前后状态相同时，在已校验事务内跳过 setter；实机 Undo/Redo 和遮挡效果仍须验收。

ArtMesh 普通 edit 现可携带 `opacity`（有限数 0..1），单独或与 positions/drawOrder 任意组合。目标的存储透明度须已知且处于受支持普通范围；混合批次使用闭合 SDK `ArtMeshKeyformValues`，省略项从完整 expected 快照保留，所有目标顶点（含保留位置）计入原有 16,384 点预算。Runtime 独立准入存储 opacity 读写 selector，在任何 setter 前验证全部 tuple/form 与默认/共享/Morph 规则，整对象一个原生 keyform contribution。沿用完整回读、operationId/result/回滚语义。原 ordinary_write_support.writerSelectors 只报告几何基础设施，不代表 opacity selector 已准入。这是存储普通形透明度，不是当前 pose setter、Morph 透明度或眼嘴自动渐隐算法；精确宿主 Undo/Redo、渲染与持久化仍须实机验收。

## ArtMesh 剪切蒙版

调用 `authoring.masks.read`，传 documentId/modelId 和 objectIds，包含拟修改目标及拟使用的蒙版源。读取自动展开当前蒙版传递依赖，最多 64 对象，返回 GUID/父级/蒙版/反向标志/历史状态及会话 snapshotId；TTL 5 分钟，最多保留 16 快照，每份 64 KiB。不改变选择或参数。

`authoring.masks.write` 输入 documentId/modelId/snapshotId、唯一 operationId 及 replacements:[{objectId,maskIds,inverted}]；最多 8 目标、128 KiB。目标和拟用源都必须在快照范围内；事务前及事务内核对全部依赖的 GUID/父级/蒙版/反向状态和历史，单次条件原生批量写后完整核对范围内状态，包括非目标对象。SDK 批量接口禁止空替换列表，因此此工具不支持清空蒙版；拒绝自引用、重复源和新引入的循环。不修改几何，也不自动宣称视觉通过。

响应丢失后用 `authoring.masks.result({operationId})` 核对，区分 RUNNING、APPLIED、NO_CHANGE、ROLLED_BACK、OUTCOME_UNKNOWN、APPLIED_WITH_READBACK_WARNING。相同请求重复提交返回保留结果，operationId 冲突拒绝。结果按会话隔离、限当前进程，最多 256 个准入操作且不淘汰重放，满额拒绝新写；查不到表示未知。宿主准入后的 Agent 取消不表示回滚。实机 Undo/Redo、遮挡效果和持久化仍待验收。

## ArtMesh 最近计算结果观察

`authoring.meshes.evaluated.read` 传 documentId/modelId/objectId 及同会话 meshes.read 的 authoringSnapshotId；可选 snapshotId/offset/limit 对同一不可变观察分页。每页核对完整存储几何、父级/历史和完整原生样本；pose 或计算结果改变即拒绝，不混用页面。不调用 model.update、渲染、调参或选择，不用存储/Core 几何代替。原生参数 GUID 保留重复显示 ID 的独立值。

freshness 始终为 UNPROVEN：passEntryTimeMillis 仅表示原生计算开始，不表示网格成功计算；liveConsumedValuesMatch 只比较 GUID/值映射，live/consumed updateVersion 是独立有符号计数器，interpolatedIdentity 仅表示原生对象身份相等，均不证明插值/求值成功。缺失 calculated form 时没有顶点行；coordinateType 保留原生标签，不臆测单位或源/Core/像素转换。

最多 65,536 顶点、live/consumed 各 2,048 参数、4 MiB 样本，每页 512 行/240 KiB。沿用 authoring 缓存/会话/TTL 预算；插入依赖样本时保护其 authoring 父快照且不延长 TTL。这是供后续诊断的明确观察，不是“当前帧已更新”或视觉通过证明。

## 对象源级属性

`authoring.properties.read` 输入 documentId/modelId/kind/objectId/authoringSnapshotId，kind 为 art_mesh、warp_deformer、rotation_deformer；token 必须来自同会话对应 authoring 读取。返回 scope=source_object：对象自身 visible/locked，ArtMesh 另有 culling。它们不是祖先叠加的有效标志，也不是当前形状的 opacity/颜色。属性快照沿用 authoring TTL/条目/字节预算，保留完整不可变原生存储形状与父级身份。

`authoring.properties.write` 输入 documentId/modelId/operationId，最多 8 个 targets:[{kind,objectId,snapshotId,properties}]。非空 patch 仅接受支持的显式布尔字段。事务前、事务内与写后核对完整存储几何/forms、源身份、父级、历史和全部支持的自身属性；未请求属性及所有存储 form 保持不变，相同值跳过 setter。显式 locked:false 只改该对象源标志，不自动解锁或修改祖先。请求上限 128 KiB。

响应丢失后查询 `authoring.properties.result`。会话/进程内保留最多 256 个准入结果且不淘汰重放；operationId 冲突拒绝，查不到表示未知，Agent 取消不等于回滚。属性与蒙版共用结果/收据编码。本接口不提供 Part 标志、当前形状 opacity/颜色、混合模式或 generic SDK setter，后者仍需各自身份/回读与 form 契约。实机视觉、Undo 和持久化仍待验收。

## 会话文件授权

`files.request_grant({operationId,mode,purpose,allowedExtensions})` 为需要 Bearer 的外部副作用请求。mode 仅 READ/WRITE，purpose 为最多 200 字符的可见用途，扩展名过滤器为 1–8 项。SDK 负责原生文件选择和权限校验；不接收或返回路径、原生句柄或文件内容。通过 `files.grant_status({operationId})` 轮询，GRANTED 返回仅当前会话/进程有效的 opaque grantId；已提供 documents.save_as 授权消费；CMO3 OPEN 使用 READ 授权；通用文件内容传输尚未开放。

请求和授权自准入起 5 分钟过期，不续期。`files.revoke_grant({operationId})`、删除会话和停止服务器会撤销桥接能力、释放句柄；迟到的授权也会释放。撤销待决请求不证明原生窗口已关闭，nativeChooserPending 与 hostCancellationConfirmed 分别说明状态。全服务器最多一个仍待原生完成的选择窗口，撤销/过期不会腾空该名额；保留最多 128 个请求身份，不淘汰后允许重放，满额拒绝新请求。创建授权不具备 Undo，也不打开或保存模型。宿主选择窗口、文件持久化仍待实机验收。

## 授权另存模型

`documents.save_as` 输入 operationId、同会话且 allowedExtensions=["cmo3"] 的 WRITE grantId、expectedState（三类 authoring 读取返回的六字段 state）和 overwritePolicy=REJECT_EXISTING。消费授权前及原生调用前再次核对 document/model/history。桥接授权只消费一次，状态为 CONSUMED；原生句柄在调用完成前保留，由调用方最终释放。READ、外会话、过期和已消费授权均拒绝。文件副作用不可 Undo，也不可嵌套 transaction.execute。

响应丢失时查询 `documents.file_result({operationId})`。最多保留 128 个按会话隔离的操作身份，不淘汰后允许重放；相同请求只返回既有结果。取消只约束 UI 队列准入，原生开始后等待实际结果。EXECUTED 仅表示原生 SAVE_AS 报告成功（可含 no-op），FAILED/异常为 OUTCOME_UNKNOWN；状态回读失败/变化给出 STATE_READBACK_WARNING。persistenceVerified/reopenVerified 始终 false，不证明字节、哈希或重开一致。CMO3 OPEN 仅准入精确 5.3.03，PSD 导入仍未开放；覆盖已有文件在有独立用户可见批准契约前排除，不能以普通文件选择窗口代替覆盖批准。

## 授权文件观察

`files.inspect({operationId,grantId,maxBytes})` 单次消费同会话 READ grant 并立即返回，通过 `files.inspect_result({operationId})` 轮询。maxBytes 显式为 1..134217728，旧 SDK 对超过 16 MiB 的请求可能返回 RUNTIME_UNAVAILABLE。全服务器只允许一个原生读取/哈希任务；SDK 读取期间可能临时分配多份缓冲。结果只保留小型元数据，不返回路径、文件名或字节。最多保留 128 个操作身份且不淘汰后允许重放；相同请求不重复读。虽然不写文件，消费授权仍需要 Bearer 且不可加入 authoring transaction。

只有非截断读取才返回 COMPLETE、byteLength 和 SHA-256；TRUNCATED 不返回前缀哈希，RUNNING/FAILED 分开报告。专用 worker 在注册表锁外计算哈希；删除会话/停止服务器丢弃迟到结果，待实际完成释放句柄，不声称取消原生 I/O。fileStability=UNPROVEN：SDK 不保证读取期间文件未被其他进程修改。documentAssociation=UNVERIFIED：重新选择的 READ grant 不能证明是另存目标或对应模型几何。哈希比较仅作为观察证据，文件身份/格式及重开模型仍须独立验证，不修改 documents.save_as 的 persistenceVerified/reopenVerified。

`turboism.rig.rotation.plan` 是半自动 rig 的只读构件。输入 rotations.read 签发快照、拟用写入 operationId 和 policy=REPLACE_ANGLE_FROM_DEFAULT。二选一：旧单轴 parameterGuid/nativeAnglePerParameterUnit，或 axes 的 1..3 个显式 {parameterGuid,nativeAnglePerParameterUnit}，逐 GUID 覆盖全部普通轴且不重复。每轴至少两个范围内唯一 key，且含精确默认；要求完整独立 Cartesian 网格，最多 65 cells/64 非默认 edits。目标 angle = 全轴默认存储 angle + Σ((cellKey-axisDefault)×gain)，按快照轴顺序求和，最终一次转换为 float。替换目标角度，保留各 form 轴心/缩放/反射、默认 form、baseAngle、axes/cells 与无关 form。轴与 cell 坐标按 GUID 匹配，不使用显示 ID 或输入顺序。此处为存储角度叠加，不是旋转复合或 XYZ/轴心语义推断，不转换原生单位。预览限 128KiB，规划前后核对完整原生状态、父级和历史。返回 keyforms.write 请求仍须单独写入准入和新鲜 CAS；规划不调用 setter、不移动 pose、不渲染、不启动事务。求值插值、头部体积/眼嘴协调与视觉验收仍未完成。

ArtMesh/Warp/Rotation 存储分页在几何行之前提供 `ordinary_write_eligibility` 行：formGuid、snapshotEligible、reason 和 hostWriteAdmission=UNPROVEN。原因区分默认形、共享、Morph 引用、无普通 cell、缺默认 key/cell、重复 cell 及共享默认形。分类与普通 writer 共用网格规则；不检查宿主写 selector、权限、编辑模式、payload 预算或事务准入。异常网格会阻止全部目标，但仍可读取。该元数据属于签发的不可变快照，分页不重读原生几何；实际写入仍须 live CAS 和原生准入。本次不增加工具。

存储分页另含 `ordinary_write_support`：hostVersion，以及 reviewedHost、writerSelectors、historySelectors、transactionCoordinator、writePermission 的 UNKNOWN/PRESENT/ABSENT 观察。新 Runtime 对象视图复用 selector 授权元数据查询，不调用 setter、不启动 Undo；权限封装要求 model.read，另行报告 model.write。SDK 默认值和旧适配器不支持查询时保留 UNKNOWN。观察随 snapshot 签发，后续分页不刷新；不证明编辑模式、默认/form 域、载荷/CAS 或事务已准入。即便全部 PRESENT 也不表示写入成功或实机验收；逐 form 的 hostWriteAdmission 仍为 UNPROVEN。

`turboism.rig.warp.plan` 规划已有一至三个显式参数 GUID 的完整独立 Warp 普通网格。轴需有精确默认 key 及双侧已声明 key，最多 65 cells。每轴提供负/正端点各 1..16 个锚点（controlPointIndex、dx、dy，原生存储坐标）；按距离平方倒数归一化权重插值，锚点位置精确复现所给位移。各关键点按到默认值相对已声明极值 key 的距离缩放位移场，各轴位移场相加。可选 `cornerCorrections` 指定二或三个不同的已绑定 GUID/NEGATIVE 或 POSITIVE 方向与 1..16 个残差锚点；三轴最多 20 个唯一方向映射（12 个二轴象限 + 8 个三轴卦限）。残差只乘所声明轴的本侧归一化比例，任一声明轴默认或反侧时为零，匹配端点角时为一。二轴修正跨第三轴各 key 保持；三轴修正在任一默认面为零。这是显式存储 key 修正，不推断透视或保证求值插值。REPLACE_CONTROLS_FROM_DEFAULT 会替换已有目标控制点，保留默认形、网格维数、quadTransform、axes/cells 和无引用 form。最多 64 edits/4096 计划点/240KiB 结果；规划前后核对完整 native/父级/历史并复用 ordinary writer 预检，返回另行执行的 keyforms.write，仍需写权限/事务/live CAS。不写入、不证明透视/美术/折叠/连续性或视觉质量；重复规划不累积位移。

`turboism.validation.warp` 接收 warps.read 签发快照、显式普通 referenceFormGuid、1..32 个 formGuids、minimumAbsJacobianRatio 和 maxFindings。可选 proposedEdits 复用 ordinary writer 预检但不提交，只能替换已选目标，不能修改参考形。对 row-major 控制单元计算四角精确二进制 float 的双线性 Jacobian 行列式：正负混合报告局部折叠；角点 abs(det)/单元包围盒对角线平方不超过阈值报告退化；规则单元与参考形比较方向，规则镜像参考本身合法。findings 定位 row/column 和四个控制点索引。参考或目标不规则时跳过方向比较，comparisonComplete=false。默认/共享普通 form 可读诊断，未引用 form 拒绝。最多 8192 个单元样本（含参考）、200 条保留 findings、240KiB 输出；超预算不返回验收结论，截断仍保留总数。即便 quadTransform=true，也仅为双线性控制网格代理：不检查原生插值、全局重叠、求值几何、父级变换、pose 连续性或美术质量。分析前后核对完整原生状态/父级/文档/历史，不写入、不渲染、不启事务。审阅后另行提交 keyforms.write，重新执行 CAS。

衔接 rig.warp.plan 时，将目标的 edits 传为 proposedEdits，formGuids 从这些 edits 提取，不要直接选快照全部 forms（可能包含未引用 form）。参考形必须从实际快照选取且保持不变。诊断结果不会授权后续写入。

`turboism.rig.mesh.plan` 使用 meshes.read 签发快照及一至三个显式普通轴 GUID，要求完整独立网格、精确默认 key 和双侧声明 key（最多 65 cells）。每轴提供各 1..16 个 negativeAnchors/positiveAnchors（vertexIndex/dx/dy），按原生存储顶点顺序和坐标，不混用 source、图片或求值坐标。可选 cornerCorrections 与 Warp 一样，提供二/三轴 GUID 的 NEGATIVE/POSITIVE 方向及最多 20 个唯一二轴/三轴残差。共用逆距离平方引擎，各轴位移按默认至声明端点 key 比例缩放，残差只乘声明轴本侧比例。二轴修正跨第三轴保持；三轴修正在任一默认面为零。REPLACE_POSITIONS_FROM_DEFAULT 替换已有非默认目标顶点，重复规划不累积；保留源 positions/UV/triangleIndices、存储绘制顺序、默认形、axes/cells 和无关 forms/Morph 记录。网格 ordinary writer 预检拒绝默认/共享/Morph 引用的目标或参考，不承诺实际宿主写准入。最多 64 edits/4096 计划顶点/240KiB 输出；前后完整 native/父级/历史核对。不改拓扑、不推断美术/透视、不渲染、不移动 pose、不启事务。返回 keyforms.write 请求，提交时独立检查权限与 fresh CAS。

validation.geometry 同时增加可选、仅 positions 的 proposedEdits。将网格规划目标 edits 原样传入，formGuids 从这些 edits 提取，选择保持不变的显式普通参考形。纯预检构造拟议快照，拒绝修改参考或未选目标，保留源几何/UV/indices/draw order。结果返回 proposedEditCount；分析前后核对原快照，不写入宿主。既有几何检查、预算、截断/INCOMPLETE 语义及未实施的视觉/求值检查保持；成功读取或空 findings 都不等于模型验收。

render.capture 与 render.pose_grid 支持可选 `crop:{x,y,width,height}`，坐标为原生渲染像素（左上原点、x 向右、y 向下）。输入 width/height 仍是完整原生画布尺寸，各不超过 1024；裁剪不改变渲染过程或提高分辨率。crop 必须为完全位于画布内的正尺寸矩形，越界在渲染前拒绝，pose 扫描则在参数写入前拒绝。PNG/帧 width/height 表示裁剪结果尺寸；renderWidth/renderHeight 表示来源画布，crop 记录区域，来源像素=结果像素+crop 原点。不传 crop 时也显式记录全幅区域。modelToPixelTransform 仍为 null，不能用该像素偏移推断存储几何坐标。

扫描所有 pose 使用同一 crop；各 typed 图像保留来源映射，包括部分失败及恢复后的保留结果。相同 operationId 改变 crop 返回冲突，重放/result 读取既有像素，不重新渲染。原生单帧/累计像素和时间预算仍按全画布计算，PNG 字节预算按实际裁剪结果计算。这为眼嘴局部比较提供观察通路，不表示原生放大、视觉质量或实机验收通过。

`turboism.validation.raster` 检查本 MCP 会话中终态且未释放的 pose-grid PNG；不读取当前模型或补渲染。输入 operationId 与 1..32 个唯一检查：frameIndex、region（裁剪后 PNG 的左上原点 x/y/width/height）、闭区间 minimumFraction/maximumFraction。color_fraction 还需 minimumRgba/maximumRgba，按 R,G,B,A 顺序给出含边界的字节范围；显式选择 alpha 以排除透明或背景颜色。frame_difference 需 referenceFrameIndex 和 tolerance：任一预乘 sRGB RGB 通道或 alpha 的字节差严格大于容差时，该像素计为命中。两帧完整原生 raster/crop 元数据必须相同，不做对齐或重采样。分母为全部 ROI 像素，计数与阈值精确比较。最多 4194304 ROI samples、1048576 解码像素、3MiB 原 PNG、240KiB 报告；使用前核对 PNG 字节长度、SHA-256 和实际尺寸。返回配置、计数、比例、逐项达标标记，以及相关帧的 captureId/hash/pose/state/crop；imageContentIndex 为 null，不返回图片或 base64。checksPassed 仅表示显式阈值达标；ok 表示分析执行成功，检查不达标时仍可为 true。终态部分扫描可检查已有帧，但 scanOutcome、parametersRestored、allRequestedPosesCaptured 分别报告。历史图片不能证明当前模型新鲜度。运行中、已释放或缺帧明确拒绝。调用方提供有意义的 ROI/阈值后，可据此获得闭眼漏白等颜色面积或差异证据；接口不推断眼嘴语义、不认定美术质量通过。

同一 `rig.warp.plan` 可选择 `policy:"PROJECT_HEAD_VOLUME_FROM_DEFAULT"`，配合互斥的 `headVolume:{centerX,centerY,radiusX,radiusY,depth,cameraDistance}` 与 `axes:[{parameterGuid,component,degreesPerParameterUnit}]`。全部实际普通轴 GUID 必须各出现一次，component 为唯一的 YAW/PITCH/ROLL；此模式不接受锚点或 cornerCorrections。profile 使用原生存储父空间，正深度朝向虚拟相机。对默认控制点取 u=(x-centerX)/radiusX、v=(y-centerY)/radiusY、z=depth*sqrt(max(0,1-u²-v²))，椭圆外 z=0；相机距离 d 将默认图像点沿射线提升为 X=(x-centerX)*(d-z)/d，Y 类同。按 (cellKey-axisDefault)×gain 度，绕 center/z=0 依次执行数学右手 +Y yaw、+X pitch、+Z roll，再投影 centerX+d*X'/(d-Z')，Y 类同。射线提升保持默认视图，零角度直接复制原点。这是图像定义的近似深度场，不是完整三维椭球重建或美术语义推断。半径须正、depth 非负、cameraDistance>depth 且 cameraDistance-depth 不小于相机距离的 1%；逐 cell yaw/pitch 限 ±85°、roll 限 ±180°，初始/旋转后相机分母小于 d 的 1% 或非有限/float 溢出均拒绝。普通网格/默认/共享保护与 65 cells/64 edits/4096 点/240KiB 预算保持。目标从默认形重新投影，替换已有目标几何；保留 default/grid/quadTransform/axes/cells/无关 forms，cornerCorrectionCount=0。规划及 validation.warp 的 proposedEdits 诊断均只读，实际提交仍独立准入/CAS/授权。调用方必须确认头部 Warp、坐标空间、profile、轴符号、父子适配和渲染姿态；模板不证明 children 插值、遮挡、眼嘴协调或美术效果通过。

`turboism.authoring.keyforms.preview` 接收与 `keyforms.write` 完全相同的闭合 typed 请求，复用独立普通 form 限制、完整 native/父级/历史 CAS 和聚合预算，计算后再次核对状态。逐对象/form 返回变更字段、点/绘制顺序/透明度/反射变化数、原生空间最大点/轴心位移、角度绝对差（不包角或换算单位）、缩放差与 before/expected-after 哈希；no-op 仍返回空 changedFields。不同对象位移不能当作同一模型空间距离。上限 8 对象、64 forms、16384 预算点（包含 opacity/联合批次保留顶点；纯 order 计零点）、768KiB 输入、240KiB 预览。applied=false、readbackVerified=false、writeAdmission=NOT_CHECKED；不解析事务、不调用 setter/pose/render、不预留 operationId 或失效快照。状态不变可重复预览，已 admitted 的 ID 必须查 result。审阅后另行提交，write 重新核对 CAS 和实际准入；预览不授予写入权限，也不代表几何/美术验收。

`turboism.assets.binding.read` 输入 documentId/modelId/objectId 和真实 meshes.read 的 authoringSnapshotId。返回自身 locked、真实 textureInput（确实未绑定才为 null），以及最多 1024 个唯一模型图像的 id/name/尺寸/groupIndex。图集区域作为独立 kind 返回，不猜测为 model image。这是作者绑定身份和元数据，不是像素、语义图层映射或 GPU 可见证明。祖先锁/扩展/精确宿主准入由 Runtime 最终检查。

`turboism.assets.binding.bind` 携带返回的 binding snapshotId、未使用 operationId 和已发现 modelImageId。写前及同一独立 SDK 事务内完整核对 native geometry/forms/source GUID、父级、锁、既有输入（含原生 modelGeneration）、候选元数据和历史。只允许无父变形器且未锁定的未绑定 ArtMesh；同图像为真实 NO_CHANGE，跳过 native bind；其他图像/图集明确拒绝，不覆盖/解锁/改父级。SDK 返回 handle 的 model/image/mesh/generation 必须与精确 textureInput 一致，UV/拓扑/存储 forms/父级/候选保持，事务内失败回滚。精确宿主权限/Undo/未知扩展限制仍由 Runtime 失败闭合。缓存复用 authoring 的 5 分钟/16 项/16MiB 限额并计入保留 native 状态，读响应最多 240KiB。这里不接收路径、grant、上传字节；PNG 注册使用单独 assets.images.register 的受控 grant 任务。

`assets.binding.result` 不查宿主，结果是历史证据。服务器最多保留 256 个 admitted 操作，不淘汰；相同请求重放结果，不重新绑定，ID 冲突拒绝。结果缺失不证明模型未变。提交后回读失败为 APPLIED_WITH_READBACK_WARNING，响应丢失/恢复失败为 OUTCOME_UNKNOWN，两者都不能盲目重试。成功回传实际 textureInput 和事务收据；哈希仅覆盖 document/model/mesh/source GUID 和 input kind/资源 ID，不含 generation 或几何（另有完整 CAS）。成功绑定后使该 binding token 失效，需要重新读。工具自己拥有事务，不加入 transaction.execute。渲染/视觉检查和实机 Undo/Redo 仍须分别验收。

`turboism.assets.images.register` 打通授权 PNG 字节→raw image/新图像组/model image→ArtMesh 绑定。先 meshes.read 与 assets.binding.read 发现目标，再请求 allowedExtensions=["png"] 的同会话 READ grant。输入 documentId/modelId/objectId、binding snapshotId、grantId、未使用 operationId、name（1..128 非空白/无控制字符）、maxPngBytes（1..16MiB）、maxPixels（1..16777216），可选小写 expectedSha256。拒绝路径/附件路径/base64/覆盖/自动解锁；原生素材名使用显式 name，不读取或回传所选文件名。

启动立即返回任务，通过 assets.images.result 读取 QUEUED/PREFLIGHT/READING/VALIDATING/HOST_PENDING/HOST_RUNNING/TERMINAL。模型预检先于 grant 消耗；完整非 truncated 读取须通过 PNG header/像素预算，单维不超过 8192。哈希从不可变 AuthorizedImageContent 内容计算，Runtime 独立验证实际 decode/尺寸。读取失败、前缀、hash 不符不触发注册。fileStability=UNPROVEN，不能证明磁盘快照。原生提交前再次核对 token TTL 与完整 geometry/forms/source/父级/锁/旧绑定/候选/history CAS。独立 SDK 事务内严格核对恰好新增一个 raw image/图像组/model image，其 name/尺寸符合输入，绑定 model/mesh/generation 正确，旧图像元数据与全部 UV/几何/forms 保持。错误回滚或报告 recovery failed；binding.read 新增 groupCount，空组变化也不能漏过。祖先锁/extension/精确宿主/权限/Undo 仍由 Runtime 失败闭合。

全局只允许一个 I/O/worker/host 任务，直到真实完成才释放槽；最多保留 128 个 operationId，无淘汰重放。相同请求查历史结果，ID 冲突拒绝。pending 原生读取不取消，lease 等实际完成才关闭。assets.images.cancel 在最终原生事务准入前阻止提交；hostAdmitted 后只记录取消意图并继续核对实际提交/回滚/unknown。Agent Abort、断线、服务器停止不证明宿主取消。会话关闭阻止未准入写入并移除结果访问，缺失结果不证明模型未改。成功 writeResult 包含 rawImageId/实际 textureInput/收据/完整 metadata 哈希；fileEvidence 为实际不可变授权内容的 SHA-256/字节数/尺寸，不保留 PNG 字节。metadata 哈希不覆盖像素/磁盘稳定性或几何；几何另有完整 CAS。提交后回读失败为 APPLIED_WITH_READBACK_WARNING，丢失原生响应为 OUTCOME_UNKNOWN，不能盲目重试。不加入 transaction.execute，也不证明 GPU 显示、实机 Undo/Redo、保存重开或完整 PSD 导入。

当前工具目录超过 tools/list 每页 50 项，必须跟随 nextCursor；Pi 桥接回归已覆盖 65 项工具及第二页 result/cancel 的发现与执行。

## 完整矩形图集任务

`turboism.atlas.read` 接收发现的 documentId/modelId 和显式 maxTotalPixels（1..67108864），将原生 opaque target 留在服务器五分钟（最多16快照），绑定会话和完整文档/模型/历史/原生规划状态；不接受路径或客户端 target。临时原生打包调用与 polygon 布局不在本路支持域。

`turboism.atlas.plan` 使用 snapshotId、operationId 和 policy=SHELF_HEIGHT_DESCENDING，返回可审阅后提交 `turboism.atlas.apply` 的 applyRequest；不写入、不预留 operationId，不保证最优打包。也可显式提交完整矩形布局：全部输入图像恰好一次、原尺寸、无旋转缩放、显式唯一页名/尺寸、边距/间隔和页/像素预算。上限512图像、32页、单边8192、总轮廓8192顶点、投影/请求240KiB；轮廓参与 CAS 但不用于矩形打包。

apply 为异步任务，通过 `turboism.atlas.result` 核对；全局一个活动任务、保留256个 operation 身份，重复请求不重放。`turboism.atlas.cancel` 仅阻止最终原生准入前的写入；断线/关闭/取消不表示已准入宿主被取消。原生图集有独立 Undo，不能并入普通 SDK transaction，无 SDK transaction receipt，不承诺 MCP 回滚。实际 Runtime 准入仍是权威；provider 失败/拒绝或响应丢失保守为 OUTCOME_UNKNOWN，原生成功而回读失败为 APPLIED_WITH_READBACK_WARNING。规范化布局哈希/回读只证明元数据，尚未证明 ArtMesh 绑定、像素采样、实机 Undo/Redo 或保存重开。

## 授权 MOC 导出包

`turboism.export.moc.plan` 接收 documentId/modelId、operationId、同会话 WRITE grantId（过滤器严格 ["zip"]）、显式 writePolicy=REPLACE_AUTHORIZED_FILE、全部原生 settings（originX/originY/pixelsPerUnit 及5项 inclusion/edge 布尔值）和 budgets：maxMocBytes64..67108864、maxArchiveBytes1..134217728、maxTotalPixels1..33554432。归一化 native float，检查已有图集元数据/Core inspection 可用性，返回原样提交的 executeRequest。不导出、不检查 MOC bytes、不消费授权，writeAdmission=NOT_CHECKED。计划5分钟TTL、最多16份；源核对为完整 history 与图集 metadata/order，不等于完整不可变几何快照。

`turboism.export.moc.execute` 异步重新核对 source/history，调用官方 MOC5.3 exporter，验证实际 settings/纹理数量与索引尺寸，再要求获准 Core inspection 返回 V5_3/CONSISTENT。按原生纹理索引将 top-left ARGB 编为 PNG，生成 model.moc3、textures/texture_NN.png、仅含相对 Moc/Textures 引用的静态 model.model3.json、带设置/源/大小/SHA 的 export-manifest.json。不接受任意包内路径或 inline bytes。明确不包含 physics/motions/expressions/pose/displayInfo/blink/lipsyncGroups；fullCharacterDelivery/playerLoadVerified=false。

编码后再次核对 TTL/history/图集 metadata，才消费一次性 ZIP WRITE grant 并原子替换该用户选择的目标；REPLACE_AUTHORIZED_FILE 是显式覆盖语义。新增 SDK writeBytesAtomicBounded 在 Runtime 支持显式最高128MiB，旧 writer 仍为8MiB；兼容 default 仅在8MiB内转发，更大失败闭合。原生 SDK 的 MOC/纹理分配发生在 MCP 更小的回传预算检查之前，编码还需要临时像素/PNG/ZIP副本。全局1任务、保留128身份；重复 operation 不重导出/重写。通过 result 核对，cancel 只阻止文件写入准入前的工作；pending write 不取消其 stage，lease/槽保留到实际完成，即使断线/关闭。WRITTEN 仅为 SDK 原子替换确认，false 为 NOT_WRITTEN，异常/响应丢失为 OUTCOME_UNKNOWN；无 Undo/盲重试/文件回读/播放器验收保证。结果只有哈希/元数据，无 ZIP/base64/原始路径/handle。

### 参数结构计划

`parameters.structure.read` 要求显式 documentId/modelId，读取完整但有界的文件夹树、文件夹/参数混合子项顺序、参数定义和 Combined 标记/双向伙伴。上限128文件夹、512参数、192KiB结构数据；不读取参数当前值、绑定或几何，不改变姿态/选中。重复显示ID、损坏的树/配对拒绝；未知 Combined 标记保留为 null，不能据此移动或配对该参数。每个参数新增原生 `guid` 字段；身份范围现包含参数 GUID、唯一显示ID、完整结构字段与六项历史绑定。文件夹仍按ID/结构校验，**尚非原生文件夹 GUID CAS**。同显示ID但不同参数GUID的重建会使旧计划失效，重复GUID或GUID reader不可用均拒绝，不用显示ID/index兜底。

将 snapshotId 和最多16个 edits 交给 `parameters.structure.plan`。操作闭合为 create_group（仅根下创建）、rename_group、remove_group、move_parameter、combine、uncombine。新建的 ref 可由本批后续操作的 `{ "createdRef": "eyes" }` 引用；已有文件夹用 `{ "id": "实际文件夹ID" }`。预览的 `~created-group:` ID 仅是临时标记，不能作为原生ID提交。新建后立即删除的无效循环不支持，文件夹自身移动/重排也未开放。

移动必须显式提供按移除后混合列表计数的 insertIndex；拒绝移动已配对参数或插入另一配对中间，应先 uncombine。combine 只支持同组、未配对的普通 NORMAL 参数；已有同一对是 no-op，不覆盖其他对。remove_group **删除整个子树及其参数/rig数据**，必须提供与预览来源逐项一致的 expectedGroupIds（含自身、前序）和 expectedParameterIds（模型顺序）；不是仅移除文件夹或保留 rig 的操作。

plan 不执行写入或试探写权限，返回 writeAdmission=NOT_CHECKED、预测结构、逐步删除清单及哈希。snapshot/plan共用来源的5分钟期限，各最多保留16项。使用 documentId/modelId/planId/operationId 调用 apply：一次 SDK 原生 authoring 事务内逐步核对预测结果，失败回滚后核对结构；精确宿主 selector、权限和 group-undo 准入仍是必要条件。它不是可嵌套于 transaction.execute 的工具，也不把 legacy apply 变成原子事务。

apply/result 返回原生收据、readbackVerified、rollbackStateVerified、实际 createdGroups 以及 actualAfterHash。expectedAfterHash 使用预览临时ID，actualAfterHash 使用发现的真实ID；含新建文件夹时两者通常不同。回滚仅核验已声明结构字段，没有额外核验被删除绑定/form的恢复。失败/未知结果中的新建ID只是观察记录，不保证仍存在。最多256个 operationId 保留、不淘汰；result与完全相同请求的重放不读取宿主。超时先查结果，禁止盲目重新规划并写入；取消桥接等待不等于取消原生事务。没有跨进程恢复或实机验收声明。

### 新网格源几何与默认形

`authoring.meshes.triangulate` 接收 documentId/modelId、`coordinateSpace:"CANVAS"` 和显式 positions/uvs/triangleIndices。原生剖分可能合并/重排点；只返回真实 positions/indices，不返回或猜测新 UV 对应，不修改模型或选择。采用结果前必须自行明确纹理映射。

`authoring.meshes.create_plan` 接收 1..8 个 meshes：name、已有 parentPartId、可空 modelImageId、sourceGeometry 和独立 defaultPositions。source/default 顶点顺序与数量必须一致，UV 在[0,1]，拒绝空/重复/退化面。每网格4096顶点/24576索引，全批16384/65536、768KiB；父部件及祖先须可见且未锁。null 图像表示明确新建未绑定网格，可接后续授权 PNG 注册绑定，不代表已可见。

计划保存全部已有 ArtMesh 的原生源/存储快照、GUID、父关系、锁与图像绑定，部件 ID/名称/父关系/index/可见性/锁，图像分组/元数据和历史。部件身份并非 native GUID CAS，也不覆盖整个模型。最多512网格/512部件/128图像组/1024图像、262144源与存储点、4MiB编码状态；原生快照分配先于预算检查。最多16计划、5分钟TTL，约64MiB编码状态加原生对象开销。规划不做写准入承诺。

`authoring.meshes.create({documentId,modelId,planId,operationId})` 在一个独立 SDK 事务内批量创建和可选绑定，核对完整声明状态、实际ID/GUID/空轴默认form、source/default/UV/拓扑及旧对象保持。返回实际默认绘制顺序和可空 opacity；不推断渲染可见。`create_result` 和同请求重放零宿主读取；最多256操作身份，不淘汰或跨重启恢复。全部 createdMeshes 是历史观察，verified=true 只表示当时回读通过，回滚后不保证仍存在；以整体 outcome/readbackVerified/rollbackStateVerified 判断。expectedAfterHash 是请求内容哈希。提交后回读失败/结果未知不能盲目重试。取消只阻止桥接准入，不取消native提交；不能嵌套 transaction.execute。尚无真实宿主渲染、Undo/Redo或保存重开验收。

### 文档目录与条件新建

`documents.read({})` 返回无路径的项目/文档/内容身份和活动文档，空项目/无文档也可读取。模型文档的 authoringState 仅在实际模型/历史入口可用时返回，否则为 null。投影含用户可见名称、文档类型、content/model ID、内容到文档的关联；不返回路径、资源像素或原生句柄，也不是完整资产清单。两次比对声明元数据，最多128文档/128内容/256关联/192KiB，native快照分配先于预算。16个会话快照/5分钟TTL。身份范围不是原生文档GUID或全模型几何CAS。

`documents.new_model({snapshotId,operationId})` 两次核对当前目录/活动authoring绑定，且实时 available 必须含NEW_MODEL，才分派一次typed命令。使用原生默认配置/UI，不接收任意命令、路径、名称或画布参数。仅在观察到恰好一个新增、唯一模型ID的活动MODEL文档及可用authoring/history状态，原文档元数据/顺序和内容元数据保持时返回READY。READY仅是身份准入就绪；不证明已有模型几何保持、默认画布、图像、渲染或持久化。

命令EXECUTED但没有确认新目标时返回EXECUTED_NOT_READY/isError=true；FAILED、异常或提交后无法可靠读取返回OUTCOME_UNKNOWN。原生UI可能需要人工交互，完成后用documents.read重新发现，不能重复新建来探测是否就绪。`documents.new_result`/同请求重放零host读取，不轮询后续UI变化；128操作身份不淘汰，不跨重启恢复。取消只在桥接准入前阻止分派；无native取消/Undo承诺，不能嵌套authoring事务。OPEN 使用下述有限分派契约，首次加载就绪仍须重新发现。新入口尚未经过真实宿主新建验收。

### 普通参数网格初始化

`authoring.bindings.initialize_plan` 接收 documentId/modelId、kind（art_mesh/warp_deformer/rotation_deformer）、objectId、已签发 authoringSnapshotId，以及1..3条显式 parameterId/parameterGuid/keys 轴。只接受零轴、一个cell/form的源。参数须为普通、非repeat/Combined且当前在default；每轴3..9个有限、互异关键值，包含精确default及范围内两侧关键值。拒绝重复显示ID/GUID。最多27个Cartesian cells、4096顶点/控制点、32768存储采样、512参数定义/值及240KiB元数据；初始编码状态4MiB、16计划/总16MiB/5分钟TTL，源快照不续期。计划不改pose/选择，不证明写准入。

`authoring.bindings.initialize({documentId,modelId,planId,operationId,target})` 必须原样回传 `plan.target`，让权限窗口显示实际对象/源/参数/关键值范围。独立SDK事务在写前和各轴间核对完整源、父关系、锁/editLevel、history和全部参数GUID/定义/值，调用bindValues，不虚构point ID。每轴回读须为完整Cartesian tuples、独立form GUID，全部form内容与原零轴form一致（网格positions/drawOrder/可空opacity、Warp控制点、完整Rotation值），包括精确default tuple；源几何/grid/baseAngle保持。共享、缺失、多余cell或内容变化触发回滚。不假设原form GUID落在哪个key；Runtime精确宿主、层级/扩展/权限/Undo准入仍具最终约束。native分配可能先于MCP回读预算，尚无真实宿主验收。

仅最终回读通过才返回实际form GUID tuples；后续rig编辑须重新读authoring快照和binding资源，tuple不是可写snapshot token。哈希覆盖document/model/object/source身份与存储投影，不覆盖全模型或像素。`initialize_result({operationId})`/同请求重放零宿主读取；最多128已准入操作，不淘汰/不跨重启恢复。缺失、unknown/warning不能盲重试。取消只阻止桥接准入，不等于native取消；不能嵌套transaction.execute。渲染、几何质量、真实Undo/Redo及保存重开仍须独立验收。


### 素材来源与图层关系元数据

`turboism.assets.sources.read` 接收 documentId/modelId 和 `query:{artMeshIds:[],modelImageIds:[]}`，每类最多64个唯一ID；空查询只读取绑定元数据，不返回全图。`turboism.assets.relations.read` 读取完整受限关系投影，不接受query。两者只调用typed纹理来源/关系读取，不打开PSD、访问像素、文件路径或修改模型。未找到的显式anchor返回unresolved_anchor。currentRawImageId与linkedRawImageIds候选来源严格区分，当前来源元数据缺失时保留真实ID并标记未解析，不选择候选替代。rawLayerId以rawImageId为作用域，数值psdLayerId是独立可空字段；usingArtMeshIds可含非当前输入，nullable currentInputIndex和空输入不能证明未绑定。来源UNKNOWN、transform/clipping可用性标志均保留，不猜测矩阵、剪切值、左右或五官语义。

首读不传snapshotId且offset=0；后续页沿用同session、tool、query和snapshotId，limit默认128、1..256，每页240KiB并可自动缩小行数。两次typed读取须有相同投影数据/native binding/generation，observation revision允许增长且不得倒退；它是观察序号，不是历史revision或写入CAS。缓存页是历史不可变元数据，后续只核对活动document/model/history绑定，不重读native关系，不证明当前新鲜或全模型原子状态。5分钟TTL不续期，16份/总16MiB、每份完整编码4MiB；1024原始图像、2048模型图像/网格、256组、8192图层/深64、16384链接、32768行，单行192KiB。native DTO分配先于MCP预算检查。这些快照不能用于写入、文件授权或视觉验收。


### 普通关键形相邻区间斜率诊断

`turboism.validation.keyform_continuity` 使用同会话 authoring snapshotId、documentId/modelId、显式kind/objectId和thresholds（maximumPositionSlopeChange/maximumAngleSlopeChange/maximumScaleSlopeChange，均为0..1e12有限数）及maxFindings（1..200）。仅接受1..3轴、每轴3..9个互异范围内key的完整Cartesian网格，最多27 cells/forms、每form4096点、32768存储采样。按参数GUID匹配并排序实际key，保持其他轴完整tuple不变，检查每一条轴线的全部相邻三点；重复/缺失tuple、孤立form、超限或不支持的网格明确失败，不产生通过结论。普通默认/共享form可读取；ArtMesh Morph快照拒绝，Warp/Rotation快照不描述Morph组合。

每个区间斜率为“存储值变化 ÷（key间距 ÷ 参数全范围）”，比较右区间减左区间斜率。Mesh顶点、Warp控制点及Rotation轴心使用欧氏范数；Rotation angle使用原始原生度数，不折返到最短角度，scale使用原始标量；反射变化始终警告。阈值由调用者明确给出，不是美术推荐默认值。报告包含实际axes/ranges/keys、三个form GUID/完整中心tuple/key值、pointIndex或Rotation字段、观测值与阈值；不适用的angle/scale最大值为null。全部比较完成后才返回analysisComplete=true，最多保留maxFindings条但统计不截断；findingsTruncated明确标注。

可选proposedEdits使用闭合positions/controlPoints/完整Rotation form及完整坐标，复用普通writer的只读preflight，不能编辑默认/共享form；不提交。分析前后核对完整原生快照、父关系和history，不移动pose/选择、不渲染或写入。请求768KiB、结果240KiB，native读取分配先于算法预算检查。曲率或反射可能是有意设计，警告供后续渲染/修正判断；无findings只表示这些已实施的存储斜率检查未超过阈值。没有实现原生插值C0/C1证明、关键点间几何安全、混合导数、drawOrder/opacity、父变形或视觉验收。


### 已有 rig 几何的显式迁移规划

`turboism.rig.transfer_geometry.plan` 为两个同类、不同对象的实际source/target authoring快照生成目标的 `keyforms.write` 请求。输入documentId/modelId/operationId、kind、policy=REPLACE_FROM_SOURCE_DEFAULT_DELTAS、source/target各自objectId/snapshotId，以及axes：每条显式sourceParameterGuid→targetParameterGuid，keys完整列出sourceValue→targetValue。两边全部普通轴/key必须一一覆盖，精确default映射到default，不使用显示ID、nearest key、重采样或名称推断。只接受已有完整1..3轴Cartesian网格，每轴2..65 key，每对象最多65 cells/forms，无孤立form。ArtMesh Morph快照拒绝，Warp/Rotation快照不描述Morph组合；source共享普通form可读，target必须通过ordinary writer的default/shared保护。

Mesh/Warp要求相同点数和完整唯一排列 `sourcePointIndices[targetIndex]=sourceIndex`，并显式提供可逆deltaTransform={xx,xy,yx,yy}（有限系数绝对值≤1e6）。结果是targetDefault+matrix*(sourceForm-sourceDefault)，不复制源默认形/源几何/UV/拓扑/绑定，也不迁移drawOrder/opacity。坐标对应由调用者明确提供，不自动转换父坐标或证明网格语义/拓扑对应。Rotation不接受点排列，而要求angleGain（±1000）和reflectionPolicy=RELATIVE_XOR：原始angle差乘gain后加targetDefault angle；轴心差用matrix；scale用targetDefault scale×sourceForm/sourceDefault scale；反射为targetDefault XOR sourceDefault XOR sourceForm。这是分量迁移，不是矩阵/旋转复合，angleGain也不从matrix行列式推断。

全部目标非default form内容按策略替换，保留其默认形/grid/baseAngle/源数据；不是无损合并既有目标rig，也不创建绑定。规划前后核对双方完整native快照、source/对象身份、父关系与history，返回state和default form身份。sourceReadScope=VERIFIED_AT_PLAN_ONLY：源只在规划时核对，之后生成值是历史计划；目标写请求只CAS目标，不持续保护源。最多4096点/form、双方合计262144存储采样、4096计划采样（Rotation每form/轴心计1）、请求768KiB/输出240KiB，native分配先于预算检查。只读、不保留operationId、不失效快照、不访问事务/写入/pose/选择/渲染。须另行preview、权限与实时写准入，再检查几何和渲染；该工具不是整套rig复制或美术验收。

### 受控 CMO3 OPEN

`documents.open({operationId,grantId,documentSnapshotId})` 消费同会话、扩展名仅 `["cmo3"]` 的 READ 授权和 `documents.read` 签发的有效快照。授权消费前与原生分派前核对完整文档目录、authoring 状态及快照 TTL，并检查目录容量。Runtime 要求模型读写及文件读取权限，仅准入精确 5.3.03。不接收路径、原生句柄、其他格式或关闭/覆盖操作。

`EXECUTED` / `NATIVE_OPEN_DISPATCHED_REDISCOVER_DOCUMENTS` 只表示原生调用返回，可能仅聚焦已有标签页。随后调用 `documents.read`；不证明首次加载就绪、文件身份、持久化或重开内容。未知结果查询 `documents.file_result`，不能盲目重放。OPEN 与 SAVE_AS 共享会话内操作身份，同请求返回缓存结果；无 Undo 或原生取消保证。

`documents.read.state.hostObserved=false` 可能仅表示没有观察到项目/文档，Editor 命令服务仍可用；不能据此拒绝授权 OPEN。仍须校验当前快照、命令服务、精确 Runtime 版本/权限及 grant。

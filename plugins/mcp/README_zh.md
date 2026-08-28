---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.mcp
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: integration
tags: mcp, automation, external-tools
turboismApi: "[0.1.0,0.2.0)"
requiresCubism: true
interface: none
---

# Turboism MCP 服务器

> **Turboism 官方插件** · **状态：预览**

在本地回环接口上运行受 Bearer 令牌保护的 MCP Streamable HTTP 服务器。

| 详情 | 值 |
|---|---|
| 版本 | `0.1.0` |
| 插件 ID | `dev.turboism.plugin.mcp` |
| 公开目录 | 5 个工具 · 13 个资源 · 2 个模板 · 8 个提示词 |
| Turboism API | `[0.1.0,0.2.0)` |
| 需要 Cubism | 是 |
| 接口 | `none` |

## 功能概述

- 公开类型化、领域级的模型对象、参数、绑定、历史记录和 Editor 命令工具。
- 将活动文档、模型、工作区、Cubism Core 和经净化的运行时诊断公开为 JSON 资源。
- 为检查、诊断、编辑、恢复和有界的 Editor 自动化提供工作流提示词。
- 仅服务经身份验证的回环客户端，并强制实施来源、正文大小、协议、会话和速率限制。

## 要求与兼容性

- **Turboism API：** `[0.1.0,0.2.0)`。
- **Cubism：** Turboism 当前仅接受经过审查的精确 Editor 构件 `5.2.03` 和 `5.3.02`。面向宿主的资源在其公开 SDK 能力不可用时会以关闭状态失败；它绝不会伪造空的成功结果。
- **传输：** 使用协议 `2025-11-25` 的 MCP Streamable HTTP，并为受支持的较早协议版本进行兼容性协商。
- **接口模式：** `none`。
- **插件依赖项：** 未声明。

## 安装与启用

1. 通过 Turboism 的官方发布包和**插件管理**安装并启用插件。
2. 在能够强制仅所有者权限的 POSIX 文件系统上，从插件状态目录读取生成的 `mcp-connection.json`。Windows 当前会在启动时关闭失败，因为 Java 17 无法证明受保护的 DACL，也无法以句柄相对且防重解析点的方式发布 Bearer 文件。
3. 使用精确的数字回环端点和 Bearer 授权值配置本地 MCP 客户端。
4. 完成 `initialize`，保留 `MCP-Session-Id`，发送 `notifications/initialized`，并在后续请求中包含协商后的协议版本。

连接文件包含本地密钥。请勿记录它、将其复制到验证证据中，或将其暴露给不受信任的进程。Windows 支持需要由运行时拥有的原生私有状态层级与发布服务：从一开始就创建并保留受保护的目录／文件句柄，应用受保护 DACL，验证重解析点和文件标识，通过句柄写入并刷新，然后原子发布。`Files.createDirectories` 以继承 ACL 创建目录后再收紧权限，无法撤销已通过打开句柄保留的访问，因此插件本地的一次性脚本或事后 ACL 重写不足以提供该边界。

## 使用方法

### 公开 MCP 目录

#### 工具

| 工具 | 用途 |
|---|---|
| `turboism.model_objects.apply` | 应用有序的创建、重命名、重新设定父级和删除操作。 |
| `turboism.parameters.apply` | 应用类型化的参数值和定义操作。 |
| `turboism.parameter_bindings.apply` | 应用类型化的参数绑定操作和原生原子传输。 |
| `turboism.history.move` | 使用世代/修订版本防护移动原生撤销历史记录。 |
| `turboism.editor_commands.execute` | 执行可发现的直接 Editor 命令和类型化的非文件 Editor 命令。 |

写入会在运行时权限和参数检查后执行。混合批次可能部分成功并报告每个操作的结果；除非底层 SDK 批次是原子的，否则它们不会被表述为事务。

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
| `turboism://active/document/history` | 原生撤销可用性、条目、世代、修订版本和位置。 |
| `turboism://environment/cubism-core` | 已接受的 Cubism Core 版本和公开能力标志。 |
| `turboism://environment/workspace` | 当前和可用工作区及其类型化可用性。 |
| `turboism://environment/workspace/layout` | 具有类型化可用性的有序只读停靠布局树。 |
| `turboism://environment/diagnostics` | 有界且路径已脱敏的 Turboism 诊断问题。 |
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
| `mcp.streamable-http` | 在数字回环接口上提供经身份验证的 MCP Streamable HTTP 服务。 |
| `mcp.tools` | 发布五个类型化工具工作流。 |
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
| `turboism.cubism.parameter.read` | `application` | 读取活动 Cubism 模型参数。 |
| `turboism.cubism.project.read` | `application` | 读取活动项目、工作区、布局和主题状态。 |
| `turboism.cubism.model.write` | `application` | 应用类型化的模型、参数、绑定、历史记录和模型设置写入。 |
| `turboism.file.write` | `application` | 允许直接 Editor `SAVE` 命令。 |
| `turboism.network.fetch` | `application` | 允许类型化的外部应用程序设置命令。 |
| `turboism.process.run` | `application` | 允许类型化的外部应用程序设置命令。 |
| `turboism.mcp.connection.publish` | `application` | 通过进程内运行时交换，将当前经过身份验证的回环端点发布给已获权限批准的自动化插件。 |

诊断扩展不会新增 `host.unsafe`、性能、文件读取、配置、事件或 UI 变更权限。

## 隐私与数据

### 网络

服务器仅监听 `127.0.0.1`。每个请求都需要生成或配置的 Bearer 令牌、被接受的回环来源、不超过 1 MiB 的正文以及已配置的速率限制。它并非为远程访问而设计。

### 本地数据

插件仅将其连接元数据写入插件状态存储。在 POSIX 系统上，它会尝试设置仅所有者权限。诊断和模型资源不会公开原始文件系统路径、原生宿主对象、图像字节或 Bearer 令牌。

`turboism://environment/diagnostics` 会省略 `DiagnosticReport.Problem.path()`，限制问题列表，将消息转换为单行，限制消息长度，并脱敏 Unix 路径、Windows 路径和 `file:` URI。

### 遥测

本插件不会发送遥测数据。插件生命周期和失败记录可能会出现在 Turboism 的会话日志和 Cubism 的宿主日志中，并附带插件 ID。

## 状态与限制

- **状态：** 预览。
- 默认端口 `0` 会选择一个临时端口。`turboism.mcp.port`、`turboism.mcp.token` 和 `turboism.mcp.requestsPerMinute` 是高级系统属性覆盖项。
- 未实现 GET SSE、资源订阅、列表变更通知、进度通知和 MCP Tasks。
- 工作区切换和默认布局变更被有意设为不可用，因为运行时当前以 `turboism.host.unsafe` 对它们进行门控。
- 在 MCP 会话无需接受原始路径即可获得真实 `UserFileHandle` 授权之前，`EditorFileCommandRequest`、导入/导出、另存为、备份和其他基于句柄的文件工作流始终不可用。
- 不公开通用 SDK 调用、反射、任意原生成员、shell 执行、原始路径、对话框自动化和生命周期注册 API。
- 性能采样、画布/配置文件、物理/动画、纹理图集创作、屏幕截图和二进制资源仍是独立的未来能力，具有各自的权限和精确宿主证据要求。
- 删除操作具有破坏性。默认会拒绝被引用的对象；必须显式请求级联。

## 故障排除

| 症状 | 检查事项 |
|---|---|
| MCP 客户端无法连接 | 读取当前连接文件，确认进程正在运行，并使用其中完全一致的回环端点。 |
| 请求未经授权 | 使用当前会话连接文件中的 Bearer 授权值。 |
| 请求在分派前被拒绝 | 检查方法、来源、会话、MCP 协议版本、正文大小、内容类型和速率限制。 |
| 资源返回 `UNAVAILABLE` | 检查活动文档/模型状态和精确宿主能力准入；不要将其视为空的成功值。 |
| 资源返回权限被拒绝 | 检查插件描述符授权以及上文命名的特定运行时权限。 |

## 支持与许可证

- **项目网站：** [https://turboism.dev](https://turboism.dev)
- **发布者：** Turboism Contributors
- **许可证：** Project License
- **插件 ID：** `dev.turboism.plugin.mcp`

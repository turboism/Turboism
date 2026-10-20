---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.acp
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: integration
tags: automation, acp, agent
turboismApi: "[0.67.1,1.0.0)"
requiresCubism: true
interface: swing
---

# Turboism ACP

> **Turboism 官方插件** · **状态：预览版**

打开 Agent 对话窗口与 Agent 设置窗口，通过 Agent Client Protocol (ACP) v1 驱动用户安装的 ACP 兼容 Agent，并通过免凭据 stdio 桥接接入本地 Turboism MCP 服务器。

## 功能概述

- 打开两个 Swing 窗口：**Agent**（对话）与 **Agent 设置**。
- 通过 stdin/stdout JSON-RPC 启动用户安装的 ACP Agent：Claude Agent ACP、Codex ACP、Antigravity、Gemini CLI、OpenCode、Pi、Devin CLI 或自定义命令。
- 按 ACP v1 契约驱动 `initialize`、`authenticate`、`session/new`、`session/load`、`session/resume`、`session/prompt`、`session/cancel`、`session/close`、`session/list`、`session/update`、`session/request_permission`、`session/set_config_option` 与 `logout`。
- 将 Turboism MCP 服务器作为 ACP stdio MCP 服务器接入，使 Agent 可以使用 Cubism 工具。HTTP 端点保留为只读回退，用于仅声明 HTTP MCP 支持的 Agent。
- 在 PATH 与常见安装目录中检测 Agent 可执行文件，未配置时自动选择首个可用 ACP Agent；持久化插件自有状态（已选 Agent、自定义命令、持久会话 ID、初始指令）并保留用户自定义配置。
- 区分普通 CLI 与支持 ACP 握手的代理：仅将声明支持 ACP 的工具（如 OpenCode、Devin CLI、Claude Agent ACP 等）纳入检测与连接，避免普通原生 CLI 冒充 ACP。
- 支持安全命令启动探测（验证 OS 级可执行性并兼容 Windows `.cmd`/`.bat` shim），并在窗口打开及连接失败时提供明确的不可用原因与日志输出。

## 要求与兼容性

- SDK API 范围 `[0.67.1,1.0.0)`。
- 用户安装的 ACP v1 兼容 Agent 可执行文件，可从 PATH 访问或在 **管理 Agent…** 中配置为自定义命令。
- 需要 MCP 工具访问时，由捆绑的 `dev.turboism.plugin.mcp` 插件提供 Turboism MCP 服务器。
- 需要已安装的 Cubism Editor 宿主；插件不在独立预览中运行。

<!-- compatibility:begin -->
**Cubism Editor 版本支持（自动生成）：** 全部审核版本.
完整矩阵: [compatibility matrix](../../compatibility/cubism/plugins_zh.md)
<!-- compatibility:end -->

## 安装与启用

- 插件随 Turboism 捆绑，默认启用。
- 在输入框中选择 Agent；自定义命令在 **管理 Agent…** 中配置。

## 使用方法

1. 点击主工具栏上的对话／终端图标，或从 Turboism 菜单打开 Agent 窗口。
2. 在输入框中选择 Agent，选择后自动连接。下拉菜单中的 **管理 Agent…** 集中提供可执行文件检测、登录／退出、自定义命令与重新连接。
3. 在输入框中选择模型、思考程度、权限和 Agent 支持的模式。
4. 点击 **＋** 选择文件或图片，也可用 Ctrl/Cmd+V 粘贴剪贴板图片与文件。普通文字复制、剪切与粘贴保持原有行为。附件先显示为可移除的草稿标签，也支持不输入文字、直接发送附件。
5. 齿轮按钮打开精简设置页，仅包含初始提示词和保存按钮。

### 图片与文件

- ACP 图片内容块、图片资源和工具返回的图片会显示缩略图。独占一行的 Markdown 本地图片引用也支持预览：绝对路径、相对于 ACP 会话目录的路径，以及 `file:` URI。远程 URL 不会自动下载；无法读取或无法解码的引用保留为文字。
- 每次最多 8 个附件，总大小不超过 4 MiB。图片预览限制源图不超过 2000 万像素，并缩放至 560×320 范围。文件读取与图片解码在后台完成。
- 发送格式遵循 Agent 的 ACP `promptCapabilities`：支持图片时使用图片内容块，支持 `embeddedContext` 时使用嵌入资源，否则磁盘文件使用本地资源链接。剪贴板图片需要图片或嵌入资源能力；无法发送时保留草稿，便于更换 Agent 或附件。
- 所选内容会交给本地启动的 Agent；该 Agent 可能按自身配置发送至远程模型服务。附件不写入插件设置。

### MCP 接入方式

- 当 MCP 服务器发布 stdio 启动描述符时，会话以 `{name:"turboism", command, args, env:[]}` 接入，桥接进程自行携带持久化的 bearer token。Agent 可以使用读写工具。
- 当只有 HTTP 端点可用且 Agent 声明 HTTP MCP 支持时，会话以只读形式接入端点；写操作仍受 bearer token 限制，该 token 从不离开 MCP 状态目录。
- 当两种形式都不可用时，会话不带 MCP 工具运行。

## 功能能力

| 能力 | 用户效果 |
|---|---|
| `automation.agent.acp` | 通过 Agent Client Protocol v1 连接用户安装的 ACP 兼容 Agent。 |
| `mcp.client` | 通过免凭据 stdio 桥接将本地 Turboism MCP 服务器接入 Agent 会话，HTTP 端点作为只读回退。 |
| `ui.window` | 打开 Agent 对话窗口与 Agent 设置窗口。 |

## 权限

| 权限 | 作用域 | 请求原因 |
|---|---|---|
| `turboism.action.register` | `application` | 注册打开 Agent 窗口的动作。 |
| `turboism.ui.menu.contribute` | `application` | 在 Turboism 菜单中添加 Agent 设置入口。 |
| `turboism.ui.toolbar.main.contribute` | `application` | 在主工具栏 Turboism Home 旁添加 Agent 按钮。 |
| `turboism.config.plugin.read` | `application` | 恢复已选 Agent ID、自定义命令、持久会话 ID 与初始指令。 |
| `turboism.config.plugin.write` | `application` | 持久化上述插件自有状态；不存储 Agent 凭据与 MCP 授权。 |
| `turboism.file.read` | `application` | 在 PATH 与常见安装目录检测用户安装的 Agent 可执行文件。 |
| `turboism.process.run` | `application` | 启动并管理所选 Agent 可执行文件。 |
| `turboism.mcp.connection.read` | `application` | 读取当前 MCP 连接快照（含免凭据 stdio 启动描述符）并订阅端点变化，使已接入会话重连。 |

## 隐私与数据

- 插件仅在自身作用域存储插件自有配置：已选 Agent、自定义命令、持久会话 ID 与初始指令。
- MCP bearer token 从不进入 ACP 负载、命令行参数、环境变量、界面文本或日志；stdio 桥接在 MCP 插件状态目录内读取它。
- 提示词与转录内容只发送到本地启动的 Agent 进程。

## 状态与限制

- 预览版插件：接口与持久化状态格式可能在版本间演进。
- Agent 端功能（持久会话、模型选择器、模式）取决于各 Agent 声明的能力。

## 故障排除

- **连接失败提示“找不到可执行文件”**：在 **管理 Agent…** 中设置自定义命令，或安装任一可检测的 Agent 使其位于 PATH。
- **“Agent 需要登录”**：点击 **登录…** 完成 Agent 的认证方式后重新连接。
- **会话中 Cubism 工具不可用**：Agent 转录会报告 MCP 以可写（stdio 桥接）、只读（HTTP）或未接入的形式挂载。检查 `dev.turboism.plugin.mcp` 插件是否已启用并启动。
- **无法加载会话**：保存的持久会话可能已失效；插件会自动回退到新会话。

## 支持与许可证

- 通过 Turboism 支持渠道报告问题。
- 与 Turboism 采用相同许可分发。

## 输入框控制项

- Agent 选择器使用所选 Agent 自身的图标；自定义 Agent 使用终端图标。
- 模型、思考程度和权限使用纯文字选择器，可选值来自 ACP `configOptions`。
- 快速模式（闪电）和计划模式（清单）仅显示图标，悬停提示和无障碍名称保留当前值。只显示当前 Agent 实际提供的控制项；支持情况可能随 Agent、适配器版本、模型或会话变化。未显示不代表安装异常。其他会话模式仍使用文字选择器。
- 思考和连续工具／命令调用使用纯文字折叠行，不添加装饰图标。

## UI 参考与致谢

本插件的部分聊天 UI 参考了 [Paseo](https://github.com/getpaseo/paseo)，包括紧凑输入框、Agent 身份图标、按能力显示的模式控制项，以及思考／工具活动的折叠展示。Swing 实现由 Turboism 维护。

图标来源、上游版本、修改说明及许可证见[Agent 图标署名说明](src/main/resources/META-INF/licenses/agent-icons.md)。插件随附 Paseo 的许可证及第三方声明，以及 Lobe Icons 的 MIT 许可证。Agent 名称和标识归各自权利人所有；本插件与 Paseo 或相关 Agent 提供方不存在隶属或背书关系。

### 初始提示词与参考资料

初始提示词输入框显示角色说明作为占位文字，不会把占位文字保存成用户编辑。每次请求都会注入随插件提供的[建模参考上下文](src/main/resources/context/modeling-reference.md)：简要建模流程，以及已核实的 Live2D 官方教程／编辑器手册和 Turboism 文档入口。这里是离线摘要与索引，不是网站全文快照；用户编写的初始提示词追加在固定上下文之后。

功能图标统一采用 Lucide v0.468.0，Agent 图标保持各自品牌。草稿中的图片显示裁切缩略图；文件卡片显示中间省略的文件名、文件类型，移除按钮的悬停提示保留完整文件名。

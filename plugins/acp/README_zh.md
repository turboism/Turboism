---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.acp
version: 0.1.0
kind: feature
status: development
delivery: development-only
category: integration
tags: automation, acp, agent
turboismApi: "[0.1.0,0.2.0)"
requiresCubism: true
interface: swing
---

# Turboism ACP

> **Turboism 官方插件** · **状态：开发中 — 未发布**

打开 Agent 对话窗口与 Agent 设置窗口，通过 Agent Client Protocol (ACP) v1 驱动用户安装的 ACP 兼容 Agent，并通过免凭据 stdio 桥接接入本地 Turboism MCP 服务器。

## 这个插件做什么

- 打开两个 Swing 窗口：**Agent**（对话）与 **Agent 设置**。
- 通过 stdin/stdout JSON-RPC 启动用户安装的 ACP Agent：Claude Agent ACP、Codex ACP、Antigravity、Gemini CLI、OpenCode、Pi、Devin CLI 或自定义命令。
- 按 ACP v1 契约驱动 `initialize`、`authenticate`、`session/new`、`session/load`、`session/resume`、`session/prompt`、`session/cancel`、`session/close`、`session/list`、`session/update`、`session/request_permission`、`session/set_config_option` 与 `logout`。
- 将 Turboism MCP 服务器作为 ACP stdio MCP 服务器接入，使 Agent 可以使用 Cubism 工具。HTTP 端点保留为只读回退，用于仅声明 HTTP MCP 支持的 Agent。
- 在 PATH 与常见安装目录中检测 Agent 可执行文件，并持久化少量插件自有状态（已选 Agent、自定义命令、持久会话 ID、初始指令）。

## 需求与兼容性

- Turboism SDK API 范围 `[0.1.0,0.2.0)`。
- 用户安装的 ACP v1 兼容 Agent 可执行文件，可从 PATH 访问或在 **Agent 设置** 中配置为自定义命令。
- 需要 MCP 工具访问时，由捆绑的 `dev.turboism.plugin.mcp` 插件提供 Turboism MCP 服务器。
- 需要已安装的 Cubism Editor 宿主；插件不在独立预览中运行。

## 安装与启用

- 插件随 Turboism 捆绑，默认启用。
- 打开 **Agent 设置** 选择 Agent 或输入自定义命令。

## 使用方法

1. 点击主工具栏上的 ACP Agent 图标，或从 Turboism 菜单打开 Agent 窗口。
2. 在 **Agent 设置** 中选择 Agent，或输入自定义命令并保存。
3. 点击 **连接**。当 Agent 要求认证时，点击 **登录…** 完成其声明的认证方式。
4. 在对话输入框中输入提示。Agent 可以调用 Turboism MCP 工具；权限请求以对话框形式出现在窗口内。
5. 当 Agent 声明配置选项时，使用会话选项页切换 provider、model 或 mode。

### MCP 接入方式

- 当 MCP 服务器发布 stdio 启动描述符时，会话以 `{name:"turboism", command, args, env:[]}` 接入，桥接进程自行携带持久化的 bearer token。Agent 可以使用读写工具。
- 当只有 HTTP 端点可用且 Agent 声明 HTTP MCP 支持时，会话以只读形式接入端点；写操作仍受 bearer token 限制，该 token 从不离开 MCP 状态目录。
- 当两种形式都不可用时，会话不带 MCP 工具运行。

## 能力

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

## 限制与状态

- 开发中插件：接口与持久化状态格式可能在版本间变化。
- Agent 端功能（持久会话、模型选择器、模式）取决于各 Agent 声明的能力。

## 故障排查

- **连接失败提示“找不到可执行文件”**：在 **Agent 设置** 中设置自定义命令，或安装任一可检测的 Agent 使其位于 PATH。
- **“Agent 需要登录”**：点击 **登录…** 完成 Agent 的认证方式后重新连接。
- **会话中 Cubism 工具不可用**：Agent 转录会报告 MCP 以可写（stdio 桥接）、只读（HTTP）或未接入的形式挂载。检查 `dev.turboism.plugin.mcp` 插件是否已启用并启动。
- **无法加载会话**：保存的持久会话可能已失效；插件会自动回退到新会话。

## 支持与许可

- 通过 Turboism 支持渠道报告问题。
- 与 Turboism 采用相同许可分发。

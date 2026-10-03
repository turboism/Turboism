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

打开 Agent 与 Settings 窗口，通过 Agent Client Protocol (ACP) v1 将用户安装的 ACP 兼容 agent 连接到已认证的 Turboism MCP 服务器。

| 项目 | 值 |
|---|---|
| 插件 ID | `dev.turboism.plugin.acp` |
| 显示名 | Turboism ACP |
| 分类 | 集成 |
| 标签 | automation, acp, agent |
| 分发 | 仅开发构建 — 不随任何发布包分发 |

## 功能

- 从 `PATH` 或自定义命令检测并启动用户安装的 agent 可执行文件，不经 shell，通过受监管的 stdio 使用 ACP v1 通信。
- 不分发任何 agent 运行时：Turboism 不下载、不捆绑、不校验、不管理第三方 agent 二进制。
- 对声明支持 HTTP MCP 的 agent 注入当前已认证的 Turboism MCP 端点，使 agent 可以调用类型化 Cubism 自动化工具。
- 提供独立的 Agent 会话窗口：持久会话侧栏、紧凑的彩色有界实时转写、IME 感知提交、取消、权限审查，以及由 ACP 驱动的认证。
- 当所选 agent 声明相应会话能力时，支持持久会话的列出、新建、加载、恢复与关闭。

## Agent 选择

Settings → Agent 提供内置目录和自定义命令：

| Agent | 可执行文件 | ACP 启动方式 |
|---|---|---|
| Claude Agent (ACP) | `claude-agent-acp` | 直接启动 |
| Codex (ACP) | `codex-acp` | 直接启动 |
| Google Antigravity | `agy-acp` | 直接启动 |
| Gemini CLI | `gemini` | `--experimental-acp` |
| OpenCode | `opencode` | `acp` 子命令 |
| Pi (ACP) | `pi-acp` | 直接启动 |
| Devin CLI | `devin` | `acp` 子命令 |
| 自定义命令 | 用户 argv | 按输入执行 |

**检测**会搜索 `PATH` 和常见用户级安装目录。agent 的认证、provider 和模型均由 agent 自身管理：声明 ACP `authMethods` 的 agent 可在 Settings 页登录；需要终端登录的 agent 可通过 **打开登录终端** 启动。Turboism 不存储任何 agent 凭据。

## 运行时与安全模型

- **进程边界：** 所选 agent 作为 Turboism 监管的子进程运行，退出时整棵进程树一并终止。
- **MCP 接入：** 仅当 agent 声明 HTTP MCP 支持时，会话才会收到已认证的 loopback MCP 端点。端点 URL 不含令牌；信任边界是用户安装并选择的 agent 二进制。
- **权限：** agent 的每次工具调用都先经 ACP `session/request_permission` 对话框确认。
- **常驻指令：** 固定边界提示要求 agent 只使用 Turboism MCP 工具。这是建议而非沙箱——带原生文件/终端工具的 agent 由其自身配置约束。

## 开始使用

1. 安装任一受支持的 agent；如需终端登录，先用其自身 CLI 登录。
2. 在插件管理中启用 **Turboism MCP Server** 和 **Turboism ACP**。
3. 打开 **Turboism → ACP Settings**，选择 agent（或输入自定义命令），执行 **检测** 或登录。
4. 点击主工具栏上的 **ACP** 图标打开 Agent 窗口并开始会话。

## 已授予权限

| 权限 | 范围 | 用途 |
|---|---|---|
| `turboism.action.register` | application | 注册 Agent 窗口与 Settings 动作。 |
| `turboism.ui.menu.contribute` | application | 在 Turboism 菜单中加入 **ACP Settings** 入口。 |
| `turboism.ui.toolbar.main.contribute` | application | 在主工具栏 Turboism Home 旁加入 ACP Agent 图标。 |
| `turboism.config.plugin.read` | application | 恢复所选 agent ID、自定义命令、持久会话 ID 与初始指令。 |
| `turboism.config.plugin.write` | application | 保存上述 Turboism 自有状态；绝不存储 agent 凭据或转写数据。 |
| `turboism.file.read` | application | 检测 `PATH` 与常见用户级安装目录中的 agent 可执行文件。 |
| `turboism.process.run` | application | 启动并监管所选 agent 可执行文件。 |
| `turboism.mcp.connection.read` | application | 读取当前已认证 MCP 端点并订阅其变更，用于接入 ACP 会话。 |

## 已知限制

- 仅支持 ACP v1；协议 v2（`auth/login`、`session/resume` 等价方法）尚未实现协商。
- 会话进行中若 MCP 服务器被禁用或重启，插件会检测到端点变化并自动重连以绑定新端点——持久会话通过 agent 的 load/resume 能力恢复（ACP 无法在既有会话上重新绑定 mcpServers）。
- 针对真实 agent 二进制的宿主级验证暂为手动，直到脚本化 ACP agent 夹具替代已退役的 fx 探针。

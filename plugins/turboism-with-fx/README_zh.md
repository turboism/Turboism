---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.turboism-with-fx
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: integration
tags: automation, fx, agent
turboismApi: "[0.1.0,0.2.0)"
requiresCubism: true
interface: swing
---

# Turboism with fx

> **Turboism 官方插件** · **状态：预览**

Opens separate Agent and Settings windows and connects fx v0.0.5 through Agent Client Protocol (ACP) v1 and authenticated loopback MCP. Full products carry managed runtimes outside the plugin JAR under `runtimes/fx/0.0.5/<platform>/`: official upstream payloads on Linux/macOS and a Turboism product payload on Windows x64.

| 详情 | 值 |
|---|---|
| Version | `0.1.0` |
| Plugin ID | `dev.turboism.plugin.turboism-with-fx` |
| Category | `integration` |
| Tags | automation, fx, agent |
| Turboism API | `[0.1.0,0.2.0)` |
| Requires Cubism | 是 |
| Interface | `swing` |
| License | Project License |

## 功能概述

- Resolves the current operating system and CPU architecture, selects the matching offline managed fx payload, and verifies its exact executable size and SHA-256 before starting `fx acp` without a shell.
- Offers an explicit optional Thin-package install/repair action that downloads one pinned fx v0.0.5 release asset, accepts only its reviewed GitHub release-asset redirect path, verifies archive, executable, license, and notice identities, and atomically activates the platform directory.
- Treats a saved custom executable as an advanced override rather than requiring a separate fx installation for normal use.
- Passes fx the current authenticated Turboism MCP endpoint so the agent can call typed Cubism automation tools.
- Provides a dedicated Agent conversation window with an fx-owned durable-session sidebar, compact color-coded bounded live transcript, IME-aware prompt submission, cancellation, and permission review.
- 提供独立的分页设置窗口，用于管理运行时路径、已保存的提供商及模型、当前 Provider/Model、内置登录操作、连接诊断和用户初始指令。
- Agent 窗口打开后会自动连接 ACP；连接时可以不选择提供商和模型，只有实际发送对话时才会提示补充配置。
- 通过 fx ACP 列出、创建和加载会话；自定义 OpenAI 兼容配置和凭据由 Turboism 插件设置及本地凭据存储管理。

## 要求与兼容性

- **Turboism API:** `[0.1.0,0.2.0)`.
- **Cubism:** Required. Host-facing MCP tools remain subject to Turboism's exact reviewed Cubism support and available typed services.
- **Interface mode:** `swing`.
- **Plugin dependency:** Turboism MCP Server `dev.turboism.plugin.mcp` in `[0.1.0,0.2.0)`.
- **Managed runtime:** Full products stage fx v0.0.5 (`df7e6245e1992758d4060c97477ceafa27770551`) under `runtimes/fx/0.0.5/<platform>/`: pinned official assets on Linux/macOS and an exact-size, exact-SHA Turboism build on Windows x64. Other ACP agent identities, versions, platform paths, sizes, or executable hashes fail closed.
- **认证：** 设置页可以为内置配置打开 fx v0.0.5 的交互操作（`fx`、`fx login vercel|codex|grok`、`fx setup` 及对应退出命令）。自定义 OpenAI 兼容 API Key 在 Windows 上优先使用 DPAPI，否则降级写入插件本地 `auth.json`。
- **Platform payloads:** Official upstream Linux x86_64, Linux ARM64, macOS x86_64, and macOS ARM64 release assets are pinned for offline product distribution. Full products also carry the Turboism-built Windows x86-64 payload. Java Full retains only the matching payload; unsupported targets fail before config or payload mutation. Thin carries plugins without native runtime bytes, and Lite remains plugin-free and runtime-free.
- **Windows 分发：** Windows NSIS Full、Full ZIP 和 Java Full 包含 `runtimes/fx/0.0.5/windows-x86_64/fx.exe`，精确身份为 `11,144,192` bytes / SHA-256 `a36b0b209d933e4757d7e1a961d259d39a8d370b68cbde8e9cba227603ac63c2`。这是基于上游 v0.0.5 的 Turboism 构建，不是 Vercel 官方 Windows 资产。Windows 候选只接收 Turboism 提供的精确认证数字回环 HTTP MCP Server，并保持 ACP 会话为临时状态；不声明与 Linux/macOS 官方运行时具有持久会话、原生工具、通用网络、进程或持久化能力的一致性。Windows 没有在线修复归档，请通过修复或重新安装 Turboism Full 恢复。

The reviewed fx release exposes only `--model` and `--log-file` for `fx acp`. Its runner defaults `allow_native_tools` to true, and no supported CLI flag, environment variable, settings field, or ACP option disables those tools.

## 安装与启用

This official plugin is a **store candidate**, not yet a published store listing. Until marketplace publication:

1. Use Full to install the matching fx runtime and notices under `runtimes/fx/0.0.5/<platform>/`. Windows x64 Full uses the bundled Turboism product payload; Thin has no managed runtime bytes and Lite does not install this plugin.
2. 在插件管理中启用 **Turboism with fx**，然后在任何 ACP 连接之前打开 **Turboism → fx 设置**。在 Runtime 页面选择 Vercel AI Gateway、Codex、Grok 或 Gateway API 密钥设置对应的 fx shell 操作。Turboism 会在独立终端中启动经验证的托管/自定义可执行文件；凭据仍由 fx 所有，绝不会写入运行时载荷或 Turboism 配置。
3. 在连接 Agent 前启用 **Turboism MCP Server**。ACP Agent 会话需要 MCP，但打开设置和 fx shell 不需要 MCP。
4. 准备连接时，选择 Cubism 主工具栏中紧靠 **Turboism Home** 左侧的 **fx** 图标。

Settings can override the managed executable with an explicit custom path. On Full installations, including Windows x64, normal use leaves that field blank. A custom override must be an existing absolute regular-file path; Turboism does not search `PATH` as an implicit fallback. A Thin installation on Linux/macOS may use **Install or repair online** while disconnected and with the custom override blank. Windows x64 uses product repair or reinstall instead of online runtime repair.

## 使用方法

1. 点击主工具栏中的 **fx** 图标打开 Agent 窗口。Turboism 会立即解析托管运行时并启动 ACP 连接；自定义可执行文件、提供商、模型和兼容模式都不是连接前置条件。
2. 点击 Agent 状态栏中的 **设置** 图标。Runtime、Provider and Model、Security and Instructions 页面分别管理启动、提供商配置和固定指令边界。正常使用时将高级自定义可执行文件留空。
3. **连接** 或 **重新连接** 仍可用于手动重试。连接过程会以简洁的 System 消息显示在对话记录中。
4. Provider and Model 页包含“未选择提供商”以及 Vercel AI Gateway、Codex、Grok 三个内置配置。**添加提供商** 可创建 OpenAI 兼容或自托管端点，默认模型可以留空；**发现模型** 会尝试读取 `/v1/models`，也可以手动添加模型 ID。点击 **使用** 后会保存并自动重新连接。
5. 自定义配置通过本地 Gateway-to-OpenAI 适配器运行。Turboism 为该 fx 子进程使用仅包含 `{"provider":"gateway"}` 的插件自有隔离 HOME，避免普通 fx 配置中的 Codex/Grok 选择干扰启动。非空默认模型会作为直接参数 `fx acp --model <id>` 传入。
6. Use the sidebar to refresh, create, or load fx-owned durable sessions.
7. Enter a Cubism automation request and press **Enter** or choose **Send**. Use **Shift+Enter** or **Ctrl+Enter** for a newline. Enter is left to an active platform input method while it is composing text, so Chinese and other IME candidates can be committed normally. Turboism's fixed security boundary is prepended first, followed by the saved initial instructions and then the current request.
7. Agent, System, Tool, User, and Thinking entries use distinct foreground colors without separate sender header lines or large message gaps. Thinking entries are omitted by default and can be revealed from the status strip.
8. Review the operation title, kind, and bounded redacted JSON arguments in the explicitly sized plugin-owned permission dialog before approving a request. Closing the dialog cancels the request, and a host Swing rendering failure also fails closed without terminating ACP.
9. Use **Stop** to send ACP `session/cancel` for the active prompt.

Compatibility mode enables fx's native file, terminal, search, and fetch tools in addition to Turboism MCP. The plugin asks fx to use only Turboism MCP, but that prompt is not a security boundary.

## 功能能力

| Declared capability | User effect |
|---|---|
| `automation.agent.acp` | Runs an ACP v1 automation session through the verified managed fx harness. |
| `mcp.client` | Gives the fx session Turboism's current authenticated loopback MCP endpoint. |
| `ui.window` | Provides separate modeless Agent and Settings Swing windows. |

## 权限

| Permission | Scope | Why it is requested |
|---|---|---|
| `turboism.action.register` | `application` | 注册打开 Agent 窗口和连接前设置窗口的操作。 |
| `turboism.ui.menu.contribute` | `application` | 向 Turboism 菜单添加直接的 **fx 设置** 入口。 |
| `turboism.ui.toolbar.main.contribute` | `application` | 在 Cubism 主工具栏的 Turboism Home 旁添加 fx Agent 图标。 |
| `turboism.config.plugin.read` | `application` | Restores the executable path, active opaque fx session ID, compatibility acknowledgement, user-authored initial instructions, and non-secret custom provider-profile metadata. |
| `turboism.config.plugin.write` | `application` | Persists that Turboism-owned state, including non-secret custom provider-profile metadata; fx-owned provider/model catalogs, transcript data, and MCP authorization are never stored. |
| `turboism.file.read` | `application` | Reads saved custom-provider API keys from this plugin's own configuration directory. |
| `turboism.file.write` | `application` | Writes saved custom-provider API keys into this plugin's own configuration directory, protected by Windows DPAPI when available and otherwise stored in `auth.json`. |
| `turboism.process.run` | `application` | Starts and supervises verified `fx acp` processes and explicit fx-owned interactive shell/login commands, or an advanced custom override. |
| `turboism.network.fetch` | `application` | On explicit user request, downloads one exact reviewed fx v0.0.5 asset for optional Thin-package install or repair with no credentials attached, and reaches a user-configured OpenAI-compatible provider endpoint when a custom provider profile is selected. |
| `turboism.mcp.connection.read` | `application` | Reads the current authenticated loopback MCP endpoint from the runtime's non-persistent exchange. |

## 隐私与数据

### Network

For built-in profiles the plugin does not implement a model-provider client: fx connects to its configured provider using fx-owned authentication and behavior, and Turboism supplies only its authenticated loopback MCP endpoint to the child process. For a custom OpenAI-compatible profile the plugin does act as a client, but only to the base URL you configured: a loopback adapter bound to `127.0.0.1` forwards `POST /v1/chat/completions` and `GET /v1/models` with your key, never follows redirects, and never forwards fx's adapter-local dummy bearer upstream. fx's Gateway reasoning level has no portable Chat Completions equivalent, so the adapter deliberately drops it rather than guessing a `reasoning_effort` that would break non-reasoning models. Separately, the explicit Thin-package repair action can contact the fixed fx v0.0.5 GitHub release URL and its reviewed release-asset redirect without Authorization, cookies, MCP bearer material, or provider credentials.

### Local data

Turboism with fx persists the optional advanced custom executable override, active opaque durable session ID, compatibility-mode acknowledgement, user-authored initial instructions, and non-secret custom provider-profile metadata (display name, base URL, API-key environment-variable name, default model, manually added model IDs) in plugin config. Custom-provider API keys are stored separately so they survive a restart: protected with Windows DPAPI for the current user where that succeeds, and otherwise written to `auth.json` in the plugin's config directory. Removing a profile or clearing its key deletes the stored credential. Keys are never written to ordinary settings, logs, diagnostics, release artifacts, or the transcript. The plugin does not persist the fx session list, transcript, fx-owned provider/model catalog, or MCP bearer authorization. Its copy of the bearer exists only in process memory and is redacted from forwarded stderr, RPC errors, permission details, diagnostics, and object text. The separate Turboism MCP Server still writes its private `mcp-connection.json` for other local clients and removes that file when the server stops.

fx owns its durable session list and event log, credentials, and provider/model session preferences. The sidebar displays only fx ACP's opaque session rows (`sessionId` and `updatedAt`) under local ordinal labels; fx v0.0.5 does not expose titles, previews, deletion, or provider authentication through native ACP.

### Telemetry

No telemetry is added by this plugin. Provider traffic and any fx telemetry remain properties of the managed fx runtime and selected provider.

Plugin lifecycle and failure records can appear in Turboism's session log and Cubism's host log with the plugin ID attached; bearer material is not intentionally logged.

## 状态与限制

- **Status:** Preview.
- Strict MCP-only mode fails closed because fx v0.0.5 has no supported native-tool disable control.
- Compatibility mode is not strict confinement: fx native file, terminal, search, and fetch tools remain available.
- Read-oriented native tools may not request approval in every case. Do not enable compatibility mode where fx must be confined to MCP.
- Provider/model catalogs and durable-session rows are unavailable until fx initializes successfully.
- Native fx ACP v0.0.5 supports session list/new/load/close but not durable-session deletion. The Agent sidebar therefore does not offer a misleading Remove action.
- Native fx ACP v0.0.5 has no provider-authentication flow and no provider/model object create/delete methods. Built-in profiles therefore open exact fx-owned CLI actions in a separate terminal instead of collecting credentials. Provider/model CRUD exists only for Turboism-owned custom OpenAI-compatible profiles, which are served by a local adapter rather than by fx-native provider support.
- The custom adapter speaks OpenAI Chat Completions only. Anthropic Messages, Gemini, and other non-OpenAI protocols are not implemented and are not offered.
- The current integration admits only the reviewed fx v0.0.5 identity and version.
- A future fx release with a supported native-tool disable control can enable the normal MCP-only path after review.

## 故障排除

| Symptom | What to check |
|---|---|
| MCP-only mode refuses to connect | This is intentional for stock fx v0.0.5. Review the warning and explicitly choose compatibility mode only when its native-tool exposure is acceptable. |
| Connection fails | Open Settings and follow its specific runtime status. Confirm Turboism MCP Server is enabled and the managed fx payload passes integrity verification. For missing authentication, use Runtime → fx shell to run the matching login or setup action, complete it in the separate terminal, then reconnect. A Thin installation can use **Install or repair online** while disconnected. |
| Windows managed fx is missing or invalid | Repair or reinstall Turboism Full. Windows x64 uses the bundled exact-identity product payload and has no online repair archive. Thin intentionally contains no managed runtime bytes. |
| fx version is unsupported | Install the reviewed v0.0.5 release. New versions require an ACP and native-tool behavior review before admission. |
| Provider or Model is empty | Confirm fx completed ACP initialization and returned provider/model `configOptions`. Turboism has no fallback catalog for built-in profiles. |
| Model discovery fails for a custom profile | The endpoint may not expose `/v1/models`, or the key may be missing. Add the exact model ID with **Add model** instead; discovery is best-effort and never blocks a configured model. |
| A custom profile has no effect | Custom profiles are applied when the adapter starts. Choose **Use**, save settings, then reconnect. |
| An API key must be re-entered after restart | The stored credential was removed or unreadable. Re-enter it once; it is saved again under DPAPI protection when available, otherwise in `auth.json`. |
| A saved session cannot load | The plugin logs the load failure and creates a new fx session, replacing the opaque saved session ID. |
| Permission details show `<redacted>` | Sensitive MCP bearer material was removed before display. Review the remaining command, path, and arguments. |

## 支持与许可证

- **Project website:** [https://turboism.dev](https://turboism.dev)
- **Publisher:** Turboism Contributors
- **Plugin license:** Project License (MIT repository code)
- **Plugin ID:** `dev.turboism.plugin.turboism-with-fx`
- **fx:** An independent third-party program licensed under Apache License 2.0 and redistributed as a reviewed platform payload with its required notices.

Apache License 2.0 permits redistribution when recipients receive the complete Apache license, upstream attribution, and full third-party notices. Turboism's managed payload includes upstream `LICENSE`, upstream `THIRD_PARTY_NOTICES.md` (including the bundled sound/UI attribution and Unicode License v3 notice), a pinned manifest, and a Turboism distribution notice. Official Linux/macOS executable and archive hashes and the Turboism-built Windows executable identity are fixed in source and verified during release staging. The Windows binary is labeled as a Turboism build of upstream v0.0.5, not an official Vercel Windows asset, and its narrower candidate limitations are stated explicitly. Vercel does not sponsor or endorse this integration.

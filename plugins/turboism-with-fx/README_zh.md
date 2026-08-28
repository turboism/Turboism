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

Opens separate Agent and Settings windows and connects fx v0.0.5 through Agent Client Protocol (ACP) v1 and authenticated loopback MCP. The Java Full installer carries reviewed Linux/macOS managed runtimes outside the plugin JAR under `runtimes/fx/0.0.5/<platform>/`. Windows ZIP/NSIS Full carries the plugin but no managed fx executable, so Windows requires an explicit custom executable.

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
- Provides a separate paged Settings window for the runtime path, compatibility acknowledgement, fx-owned Provider and Model controls, connection diagnostics, and user-authored initial instructions.
- Lists, creates, and loads durable sessions through fx ACP while leaving their event log, provider/model preferences, and credentials under fx ownership.

## 要求与兼容性

- **Turboism API:** `[0.1.0,0.2.0)`.
- **Cubism:** Required. Host-facing MCP tools remain subject to Turboism's exact reviewed Cubism support and available typed services.
- **Interface mode:** `swing`.
- **Plugin dependency:** Turboism MCP Server `dev.turboism.plugin.mcp` in `[0.1.0,0.2.0)`.
- **Managed runtime:** The Java Full installer stages the reviewed fx v0.0.5 release (`df7e6245e1992758d4060c97477ceafa27770551`) under `runtimes/fx/0.0.5/<platform>/` on reviewed Linux/macOS pairs. Windows product assets contain no managed fx executable. Other ACP agent identities, versions, platform paths, sizes, or executable hashes fail closed until reviewed.
- **Authentication:** Authenticate fx with one of fx's supported credential mechanisms. fx ACP v0.0.5 exposes no provider-login protocol to the plugin, so Turboism does not collect or persist provider credentials.
- **Platform payloads:** Official upstream Linux x86_64, Linux ARM64, macOS x86_64, and macOS ARM64 release assets are pinned for offline product distribution. A Java Full installation is available only on those reviewed OS/CPU pairs and retains only the matching payload; unsupported targets fail before config or payload mutation. A Thin product variant may ship the plugin without the native payload and use the explicit verified repair action. Existing Lite mode remains plugin-free and contains no fx runtime.
- **Windows distribution:** fx v0.0.5 has no reviewed Windows executable. Windows ZIP/NSIS Full nevertheless contains the exact full plugin roster, including `turboism-with-fx.jar`, but contains no `runtimes/fx/**`; using the plugin on Windows requires an explicit user-owned custom executable. Java Full rejects Windows before config or payload mutation. Java Thin remains available with the complete plugin roster and no native fx bytes, and Lite remains plugin-free. A phase-1 compile-only v0.0.5 backport and other investigated upstream Windows ports remain validation research, not product runtimes, until their security, durability, ACP, exact-host, and process-tree gates are satisfied.

The reviewed fx release exposes only `--model` and `--log-file` for `fx acp`. Its runner defaults `allow_native_tools` to true, and no supported CLI flag, environment variable, settings field, or ACP option disables those tools.

## 安装与启用

This official plugin is a **store candidate**, not yet a published store listing. Until marketplace publication:

1. On reviewed Linux/macOS pairs, use Java Full to install the matching fx runtime and notices under `runtimes/fx/0.0.5/<platform>/`. On Windows, ZIP/NSIS Full includes the plugin but no managed fx executable; configure an explicit custom executable instead. Java Thin installs the plugin roster without native fx bytes, and Lite does not install this plugin.
2. Authenticate fx using its supported provider setup or login flow. Credentials and durable provider configuration remain fx-owned and are never included in the runtime payload.
3. Enable **Turboism MCP Server** and **Turboism with fx** in Plugin Management.
4. Choose the **fx** icon immediately to the left of **Turboism Home** on Cubism's main toolbar.

Settings can override the managed executable with an explicit custom path. On reviewed Linux/macOS Java Full installs, normal use leaves that field blank. On Windows the custom path is required because product assets contain no managed fx executable. A custom override must be an existing absolute regular-file path; Turboism does not search `PATH` as an implicit fallback. A Thin installation on a supported Linux/macOS platform may use **Install or repair online** while disconnected and with the custom override blank.

## 使用方法

1. Choose the **fx** main-toolbar icon to open the Agent window. If compatibility mode was acknowledged, first open automatically resolves and connects the managed runtime; a custom executable is not required.
2. Choose the icon-only **Settings** control in the Agent status strip. Its Runtime, Provider and Model, and Security and Instructions pages separate launch, fx-owned configuration, and the fixed security boundary. Leave the advanced custom executable override blank for the managed runtime, add optional initial instructions, and review and explicitly select compatibility mode. Stock fx cannot be started in strict MCP-only mode.
3. Choose **Connect** or **Reconnect**. Turboism resolves the platform payload and verifies its pinned bytes before process launch. Provider and Model choices then come directly from fx. Connection and ACP initialization transitions also appear as System entries in the transcript; the status strip and Settings diagnostics distinguish unsupported platforms, missing or corrupt managed payloads, unavailable MCP, and missing fx authentication.
4. Select a provider/model value returned by fx, or enter an exact fx-owned opaque ID. Changes apply immediately to the active fx session; fx validates the value and returns its refreshed catalog, owns authentication and durable configuration, and Turboism restores the last confirmed selection if an update fails. Turboism does not provide a provider-login or credential form because fx v0.0.5 exposes no such ACP operation.
5. Use the sidebar to refresh, create, or load fx-owned durable sessions.
6. Enter a Cubism automation request and press **Enter** or choose **Send**. Use **Shift+Enter** or **Ctrl+Enter** for a newline. Enter is left to an active platform input method while it is composing text, so Chinese and other IME candidates can be committed normally. Turboism's fixed security boundary is prepended first, followed by the saved initial instructions and then the current request.
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
| `turboism.action.register` | `application` | Registers the action that opens the Agent window. |
| `turboism.ui.toolbar.main.contribute` | `application` | Adds the fx Agent icon beside Turboism Home on Cubism's main toolbar. |
| `turboism.config.plugin.read` | `application` | Restores the executable path, active opaque fx session ID, compatibility acknowledgement, and user-authored initial instructions. |
| `turboism.config.plugin.write` | `application` | Persists that Turboism-owned state; provider/model catalogs, credentials, transcript data, and MCP authorization are never stored. |
| `turboism.process.run` | `application` | Starts and supervises the verified managed `fx acp` process or an explicit advanced custom override. |
| `turboism.network.fetch` | `application` | On explicit user request, downloads one exact reviewed fx v0.0.5 asset for optional Thin-package install or repair; no provider or MCP credentials are attached. |
| `turboism.mcp.connection.read` | `application` | Reads the current authenticated loopback MCP endpoint from the runtime's non-persistent exchange. |

## 隐私与数据

### Network

The plugin does not implement a model-provider client. fx connects to its configured provider using fx-owned authentication and behavior. Turboism supplies only its authenticated loopback MCP endpoint to the child process. Separately, the explicit Thin-package repair action can contact the fixed fx v0.0.5 GitHub release URL and its reviewed release-asset redirect without Authorization, cookies, MCP bearer material, or provider credentials.

### Local data

Turboism with fx persists only the optional advanced custom executable override, active opaque durable session ID, compatibility-mode acknowledgement, and user-authored initial instructions in plugin config. It does not persist the fx session list, transcript, provider/model catalog, API keys, or MCP bearer authorization. Its copy of the bearer exists only in process memory and is redacted from forwarded stderr, RPC errors, permission details, diagnostics, and object text. The separate Turboism MCP Server still writes its private `mcp-connection.json` for other local clients and removes that file when the server stops.

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
- Native fx ACP v0.0.5 has no provider-authentication flow; Settings explains that authentication remains fx-owned but cannot securely collect provider credentials.
- The current integration admits only the reviewed fx v0.0.5 identity and version.
- A future fx release with a supported native-tool disable control can enable the normal MCP-only path after review.

## 故障排除

| Symptom | What to check |
|---|---|
| MCP-only mode refuses to connect | This is intentional for stock fx v0.0.5. Review the warning and explicitly choose compatibility mode only when its native-tool exposure is acceptable. |
| Connection fails | Open Settings and follow its specific runtime status. Confirm Turboism MCP Server is enabled, the matching managed fx payload is installed and passes integrity verification, and fx authentication succeeds. A Thin installation can use **Install or repair online** while disconnected. |
| Windows has no fx executable | This is expected: Windows ZIP/NSIS Full includes the plugin but no managed fx runtime. Configure an explicit absolute custom executable. Java Full rejects Windows; Java Thin remains available without native fx bytes. Turboism does not repackage Linux/macOS binaries or present validation bridges as product runtimes. |
| fx version is unsupported | Install the reviewed v0.0.5 release. New versions require an ACP and native-tool behavior review before admission. |
| Provider or Model is empty | Confirm fx completed ACP initialization and returned provider/model `configOptions`. Turboism has no fallback catalog. |
| A saved session cannot load | The plugin logs the load failure and creates a new fx session, replacing the opaque saved session ID. |
| Permission details show `<redacted>` | Sensitive MCP bearer material was removed before display. Review the remaining command, path, and arguments. |

## 支持与许可证

- **Project website:** [https://turboism.dev](https://turboism.dev)
- **Publisher:** Turboism Contributors
- **Plugin license:** Project License (MIT repository code)
- **Plugin ID:** `dev.turboism.plugin.turboism-with-fx`
- **fx:** An independent third-party program licensed under Apache License 2.0 and redistributed as a reviewed platform payload with its required notices.

Apache License 2.0 permits redistribution of the reviewed unmodified fx v0.0.5 assets when recipients receive the complete Apache license, upstream attribution, and full third-party notices. Turboism's managed payload includes upstream `LICENSE`, upstream `THIRD_PARTY_NOTICES.md` (including the bundled sound/UI attribution and Unicode License v3 notice), a pinned manifest, and a Turboism distribution notice. Official Linux/macOS executable and archive hashes are fixed in the source and release staging verifies them before packaging. Windows remains unavailable until a reviewed Turboism build from the exact source commit is reproducible, every modified upstream file is marked, corresponding source/build instructions are supplied, and both ACP plus exact Cubism host validation pass. Such a binary must be labeled as a Turboism build, not an official Vercel Windows asset. Vercel does not sponsor or endorse this integration.

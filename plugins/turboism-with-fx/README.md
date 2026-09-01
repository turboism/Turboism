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

> **Official Turboism plugin** · **Status: Preview**

Opens separate Agent and Settings windows and connects fx v0.0.5 through Agent Client Protocol (ACP) v1 and authenticated loopback MCP. Full products carry managed runtimes outside the plugin JAR under `runtimes/fx/0.0.5/<platform>/`: official upstream payloads on Linux/macOS and a Turboism product payload on Windows x64.

| Detail | Value |
|---|---|
| Version | `0.1.0` |
| Plugin ID | `dev.turboism.plugin.turboism-with-fx` |
| Category | `integration` |
| Tags | automation, fx, agent |
| Turboism API | `[0.1.0,0.2.0)` |
| Requires Cubism | Yes |
| Interface | `swing` |
| License | Project License |

## What it does

- Resolves the current operating system and CPU architecture, selects the matching offline managed fx payload, and verifies its exact executable size and SHA-256 before starting `fx acp` without a shell.
- Offers an explicit optional Thin-package install/repair action that downloads one pinned fx v0.0.5 release asset, accepts only its reviewed GitHub release-asset redirect path, verifies archive, executable, license, and notice identities, and atomically activates the platform directory.
- Treats a saved custom executable as an advanced override rather than requiring a separate fx installation for normal use.
- Passes fx the current authenticated Turboism MCP endpoint so the agent can call typed Cubism automation tools.
- Provides a dedicated Agent conversation window with an fx-owned durable-session sidebar, compact color-coded bounded live transcript, IME-aware prompt submission, cancellation, and permission review.
- Provides a separate paged Settings window for the runtime path, saved provider profiles with their model lists, active Provider and Model controls, built-in sign-in actions, connection diagnostics, and user-authored initial instructions.
- Connects ACP automatically when the Agent opens. A provider and model are optional at connection time and are requested in the conversation only when the user tries to send without a usable selection.
- Lists, creates, and loads durable sessions through fx ACP. Custom OpenAI-compatible profiles and credentials use Turboism's plugin settings and local credential store.

## Requirements and compatibility

- **Turboism API:** `[0.1.0,0.2.0)`.
- **Cubism:** Required. Host-facing MCP tools remain subject to Turboism's exact reviewed Cubism support and available typed services.
- **Interface mode:** `swing`.
- **Plugin dependency:** Turboism MCP Server `dev.turboism.plugin.mcp` in `[0.1.0,0.2.0)`.
- **Managed runtime:** Full products stage fx v0.0.5 (`df7e6245e1992758d4060c97477ceafa27770551`) under `runtimes/fx/0.0.5/<platform>/`: pinned official assets on Linux/macOS and an exact-size, exact-SHA Turboism build on Windows x64. Other ACP agent identities, versions, platform paths, sizes, or executable hashes fail closed.
- **Authentication:** Settings can open fx v0.0.5 interactive actions (`fx`, `fx login vercel|codex|grok`, `fx setup`, and matching logout commands) for built-in profiles. Custom OpenAI-compatible API keys are stored by Turboism using DPAPI on Windows when available, with the documented plugin-local `auth.json` fallback.
- **Platform payloads:** Official upstream Linux x86_64, Linux ARM64, macOS x86_64, and macOS ARM64 release assets are pinned for offline product distribution. Full products also carry the Turboism-built Windows x86-64 payload. Java Full retains only the matching payload; unsupported targets fail before config or payload mutation. Thin carries plugins without native runtime bytes, and Lite remains plugin-free and runtime-free.
- **Windows distribution:** Windows NSIS Full, Full ZIP, and Java Full contain `runtimes/fx/0.0.5/windows-x86_64/fx.exe` with exact identity `11,174,912` bytes / SHA-256 `04eca2ccb0037d4080724ad644cb42a2605f610632e0e95148f077e1550c4541`. This is a Turboism build of upstream v0.0.5, not an official Vercel asset. It has no online repair archive; repair or reinstall Turboism Full to restore it. The current Windows candidate does not claim durable-session, ACP MCP-server, native-tool, networking, process, or persistence parity with the official Linux/macOS runtimes.

The reviewed fx release exposes only `--model` and `--log-file` for `fx acp`. Its runner defaults `allow_native_tools` to true, and no supported CLI flag, environment variable, settings field, or ACP option disables those tools.

## Install and enable

This official plugin is a **store candidate**, not yet a published store listing. Until marketplace publication:

1. Use Full to install the matching fx runtime and notices under `runtimes/fx/0.0.5/<platform>/`. Windows x64 Full uses the bundled Turboism product payload; Thin has no managed runtime bytes and Lite does not install this plugin.
2. Enable **Turboism with fx** in Plugin Management, then open **Turboism → fx Settings** before any ACP connection. On Runtime, choose an fx shell action for Vercel AI Gateway, Codex, Grok, or Gateway API-key setup. Turboism launches the verified managed/custom executable in a separate terminal; credentials remain fx-owned and are never included in the runtime payload or Turboism config.
3. Enable **Turboism MCP Server** before connecting the Agent. MCP is required for an ACP Agent session, but it is not required to open Settings or the fx shell.
4. Choose the **fx** icon immediately to the left of **Turboism Home** on Cubism's main toolbar when ready to connect.

Settings can override the managed executable with an explicit custom path. On Full installations, including Windows x64, normal use leaves that field blank. A custom override must be an existing absolute regular-file path; Turboism does not search `PATH` as an implicit fallback. A Thin installation on Linux/macOS may use **Install or repair online** while disconnected and with the custom override blank. Windows x64 uses product repair or reinstall instead of online runtime repair.

## How to use

1. Choose the **fx** main-toolbar icon to open the Agent window. Turboism immediately resolves the managed runtime and starts the ACP connection; a custom executable, provider, model, and compatibility selection are not connection prerequisites.
2. Choose the icon-only **Settings** control in the Agent status strip. Its Runtime, Provider and Model, and Security and Instructions pages separate launch, provider setup, and the fixed instruction boundary. Leave the advanced custom executable override blank for the managed runtime and add optional initial instructions as needed.
3. **Connect** or **Reconnect** remains available for manual retries. Turboism resolves the platform payload and verifies its pinned bytes before process launch. Connection transitions also appear as concise System entries in the transcript.
4. Manage provider profiles on the Provider and Model page. The saved list contains an unconfigured choice plus the three fx built-ins (Vercel AI Gateway, Codex, Grok). **Sign in** launches the matching fx login command for a built-in profile. **Add provider** opens a modal dialog for an OpenAI-compatible or self-hosted endpoint; its default model is optional. **Discover models** reads `/v1/models` best-effort, and **Add model** accepts an exact model ID when discovery is unavailable. **Use** saves the active profile and reconnects automatically.
5. Custom profiles run through a local Gateway-to-OpenAI adapter. Turboism gives that fx child a plugin-owned isolated home containing only `{"provider":"gateway"}`, so an unrelated Codex or Grok choice in the user's normal fx settings cannot redirect custom-profile startup. Credentials remain in the existing credential store, not that isolated fx settings file. A non-empty default model is passed as the direct `fx acp --model <id>` argument.
6. Use the sidebar to refresh, create, or load fx-owned durable sessions.
7. Enter a Cubism automation request and press **Enter** or choose **Send**. Use **Shift+Enter** or **Ctrl+Enter** for a newline. Enter is left to an active platform input method while it is composing text, so Chinese and other IME candidates can be committed normally. Turboism's fixed security boundary is prepended first, followed by the saved initial instructions and then the current request.
7. Agent, System, Tool, User, and Thinking entries use distinct foreground colors without separate sender header lines or large message gaps. Thinking entries are omitted by default and can be revealed from the status strip.
8. Review the operation title, kind, and bounded redacted JSON arguments in the explicitly sized plugin-owned permission dialog before approving a request. Closing the dialog cancels the request, and a host Swing rendering failure also fails closed without terminating ACP.
9. Use **Stop** to send ACP `session/cancel` for the active prompt.

Compatibility mode enables fx's native file, terminal, search, and fetch tools in addition to Turboism MCP. The plugin asks fx to use only Turboism MCP, but that prompt is not a security boundary.

## Capabilities

| Declared capability | User effect |
|---|---|
| `automation.agent.acp` | Runs an ACP v1 automation session through the verified managed fx harness. |
| `mcp.client` | Gives the fx session Turboism's current authenticated loopback MCP endpoint. |
| `ui.window` | Provides separate modeless Agent and Settings Swing windows. |

## Permissions

| Permission | Scope | Why it is requested |
|---|---|---|
| `turboism.action.register` | `application` | Registers the actions that open the Agent and pre-connection Settings windows. |
| `turboism.ui.menu.contribute` | `application` | Adds the direct **fx Settings** entry to the Turboism menu. |
| `turboism.ui.toolbar.main.contribute` | `application` | Adds the fx Agent icon beside Turboism Home on Cubism's main toolbar. |
| `turboism.config.plugin.read` | `application` | Restores the executable path, active opaque fx session ID, compatibility acknowledgement, user-authored initial instructions, and non-secret custom provider-profile metadata. |
| `turboism.config.plugin.write` | `application` | Persists that Turboism-owned state, including non-secret custom provider-profile metadata; fx-owned provider/model catalogs, transcript data, and MCP authorization are never stored. |
| `turboism.file.read` | `application` | Reads saved custom-provider API keys from this plugin's own configuration directory. |
| `turboism.file.write` | `application` | Writes saved custom-provider API keys into this plugin's own configuration directory, protected by Windows DPAPI when available and otherwise stored in `auth.json`. |
| `turboism.process.run` | `application` | Starts and supervises verified `fx acp` processes and explicit fx-owned interactive shell/login commands, or an advanced custom override. |
| `turboism.network.fetch` | `application` | On explicit user request, downloads one exact reviewed fx v0.0.5 asset for optional Thin-package install or repair with no credentials attached, and reaches a user-configured OpenAI-compatible provider endpoint when a custom provider profile is selected. |
| `turboism.mcp.connection.read` | `application` | Reads the current authenticated loopback MCP endpoint from the runtime's non-persistent exchange. |

## Privacy and data

### Network

For built-in profiles the plugin does not implement a model-provider client: fx connects to its configured provider using fx-owned authentication and behavior, and Turboism supplies only its authenticated loopback MCP endpoint to the child process. For a custom OpenAI-compatible profile the plugin does act as a client, but only to the base URL you configured: a loopback adapter bound to `127.0.0.1` forwards `POST /v1/chat/completions` and `GET /v1/models` with your key, never follows redirects, and never forwards fx's adapter-local dummy bearer upstream. fx's Gateway reasoning level has no portable Chat Completions equivalent, so the adapter deliberately drops it rather than guessing a `reasoning_effort` that would break non-reasoning models. Separately, the explicit Thin-package repair action can contact the fixed fx v0.0.5 GitHub release URL and its reviewed release-asset redirect without Authorization, cookies, MCP bearer material, or provider credentials.

### Local data

Turboism with fx persists the optional advanced custom executable override, active opaque durable session ID, compatibility-mode acknowledgement, user-authored initial instructions, and non-secret custom provider-profile metadata (display name, base URL, API-key environment-variable name, default model, manually added model IDs) in plugin config. Custom-provider API keys are stored separately so they survive a restart: protected with Windows DPAPI for the current user where that succeeds, and otherwise written to `auth.json` in the plugin's config directory. Removing a profile or clearing its key deletes the stored credential. Keys are never written to ordinary settings, logs, diagnostics, release artifacts, or the transcript. The plugin does not persist the fx session list, transcript, fx-owned provider/model catalog, or MCP bearer authorization. Its copy of the bearer exists only in process memory and is redacted from forwarded stderr, RPC errors, permission details, diagnostics, and object text. The separate Turboism MCP Server still writes its private `mcp-connection.json` for other local clients and removes that file when the server stops.

fx owns its durable session list and event log, credentials, and provider/model session preferences. The sidebar displays only fx ACP's opaque session rows (`sessionId` and `updatedAt`) under local ordinal labels; fx v0.0.5 does not expose titles, previews, deletion, or provider authentication through native ACP.

### Telemetry

No telemetry is added by this plugin. Provider traffic and any fx telemetry remain properties of the managed fx runtime and selected provider.

Plugin lifecycle and failure records can appear in Turboism's session log and Cubism's host log with the plugin ID attached; bearer material is not intentionally logged.

## Status and limitations

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

## Troubleshooting

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

## Support and license

- **Project website:** [https://turboism.dev](https://turboism.dev)
- **Publisher:** Turboism Contributors
- **Plugin license:** Project License (MIT repository code)
- **Plugin ID:** `dev.turboism.plugin.turboism-with-fx`
- **fx:** An independent third-party program licensed under Apache License 2.0 and redistributed as a reviewed platform payload with its required notices.

Apache License 2.0 permits redistribution when recipients receive the complete Apache license, upstream attribution, and full third-party notices. Turboism's managed payload includes upstream `LICENSE`, upstream `THIRD_PARTY_NOTICES.md` (including the bundled sound/UI attribution and Unicode License v3 notice), a pinned manifest, and a Turboism distribution notice. Official Linux/macOS executable and archive hashes and the Turboism-built Windows executable identity are fixed in source and verified during release staging. The Windows binary is labeled as a Turboism build of upstream v0.0.5, not an official Vercel Windows asset, and its narrower candidate limitations are stated explicitly. Vercel does not sponsor or endorse this integration.

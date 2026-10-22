---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.acp
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: integration
tags: automation, acp, agent
turboismApi: "[0.67.0,1.0.0)"
requiresCubism: true
interface: swing
---

# Turboism ACP

> **Turboism official plugin** · **Status: Preview**

Opens an Agent conversation window and a Settings window. Drives a user-installed, ACP v1 compatible agent through the Agent Client Protocol and attaches the local Turboism MCP server through a credential-free stdio bridge.

### Native CLIs and local sessions

- Following [Paseo's provider design](https://paseo.sh/docs/providers.md), bundled adapters connect directly to original `claude`, `codex`, and `pi` CLIs. No additional ACP package or Node SDK is required. Pi's Turboism MCP extension is bundled. Gemini CLI uses its built-in ACP mode; Antigravity uses `agy-acp`. Existing ACP adapters remain supported.
- Harness selectors show available connections. Unsupported options, including permission modes, are hidden; enabled controls use the normal foreground color.
- A local `session-index/` records sessions created here, partitioned by harness, launch command, protocol, and working directory. CLI sessions created elsewhere are not imported.
- Native sessions resume model context but currently do not replay earlier conversation text. Pi files live under the plugin state directory's `native-sessions/pi/`; Codex and Claude Code use their native stores.
- Custom commands still use ACP. Select original CLIs through their corresponding built-in harness entries.


## What it does

- Opens two Swing windows: **Agent** (chat conversation) and **Agent Settings**.
- Launches original Claude Code, Codex, and Pi CLIs using bundled native adapters; Gemini CLI, OpenCode, Devin CLI, Antigravity (`agy-acp`), and custom commands use ACP.
- Drives `initialize`, `authenticate`, `session/new`, `session/load`, `session/resume`, `session/prompt`, `session/cancel`, `session/close`, `session/list`, `session/update`, `session/request_permission`, `session/set_config_option`, and `logout` per the ACP v1 contract.
- Attaches the Turboism MCP server as an ACP stdio MCP server so the agent can use the Cubism tools. The HTTP endpoint remains a read-only fallback for agents that only advertise HTTP MCP support.
- Detects the agent executable on PATH and in common install locations, and persists a small amount of plugin-owned state (selected agent, custom command, durable session id, initial instructions).

## Requirements and compatibility

- Turboism SDK API range `[0.67.0,1.0.0)`.
- A user-installed ACP v1 compatible agent executable, reachable from PATH or configured as a custom command in **Manage agents…**.
- The bundled `dev.turboism.plugin.mcp` plugin provides the Turboism MCP server when MCP tool access is wanted.
- Requires an installed Cubism Editor host; the plugin does not run inside the standalone preview.

<!-- compatibility:begin -->
**Cubism Editor version support (generated):** all reviewed versions (5.0.00–5.0.06, 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04; 20 exact versions).
Full feature set (reviewed versions): 5.0.00–5.0.06, 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04.
Full matrix: [compatibility matrix](../../compatibility/cubism/plugins.md)
<!-- compatibility:end -->

## Install and enable

- The plugin is bundled with Turboism and is enabled by default.
- Pick an Agent in the composer; use **Manage agents…** for custom commands.

## How to use

1. Open Agent from the main toolbar's conversation/terminal icon or the Turboism menu.
2. Choose an Agent in the composer; choosing one connects it. Use **Manage agents…** in that menu for executable detection, login/logout, custom commands, or reconnecting.
3. Use the composer selectors for the model, reasoning effort, permissions, and supported modes.
4. Use **+** to select files or images, or paste clipboard images/files with Ctrl/Cmd+V. Plain text paste and copy/cut keep their normal behavior. Review or remove attachment chips before sending; attachment-only prompts are supported.
5. The gear opens a compact Settings window containing only the initial instruction prompt and its Save button.

### Images and files

- ACP image blocks, image resources, and tool-returned images render as thumbnails. Standalone Markdown image references to local paths (absolute or relative to the ACP session directory) and `file:` URIs also render as previews. Remote URLs are not fetched automatically; unreadable/unsupported image references remain text.
- Up to 8 attachments and 4 MiB total per prompt. Image previews are limited to 20 million source pixels and scaled to fit 560×320. File reads and image decoding run in background workers.
- Sending follows the Agent's ACP `promptCapabilities`: images use image content blocks when supported; embedded files use resource blocks when `embeddedContext` is supported. Otherwise selected disk files use local resource links. Clipboard images require image or embedded-resource support; rejection keeps the draft so the user can change the Agent or attachments.
- Selected content is sent to the locally launched Agent process. Its provider may process attachments remotely under that Agent's configuration. Attachments are not persisted in plugin settings.

### MCP attachment

- When the MCP server publishes a stdio launch descriptor, the session attaches `{name:"turboism", command, args, env:[]}` and the bridge adds the persisted bearer token itself. The agent can use read and write tools.
- On HTTP 401, the stdio bridge re-reads the token and retries once only if it changed. If authentication still fails, it reports an authentication error without downgrading to read-only access; users do not need to give the token to the agent.
- When only the HTTP endpoint is usable and the agent advertises HTTP MCP support, the session attaches the endpoint in read-only form; mutating calls stay gated by the bearer token that never leaves the MCP state directory.
- When neither form is usable, the session runs without MCP tools.

### Connected but no reply

“Connected” confirms the ACP handshake. If the agent returns `end_turn` without text, thought, image, or tool events for the current prompt, the plugin reports an empty response instead of “Completed.” Check the selected model, provider credentials, and agent logs before retrying.

OpenCode 1.15.13 can hide a provider authentication failure behind `end_turn` and zero token usage. A local mock-provider comparison confirmed that 1.18.4 returns the authentication error. Update OpenCode and reconnect, then use the reported error to check the model configuration.

## Capabilities

| Capability | User effect |
|---|---|
| `automation.agent.acp` | Connects user-installed ACP-compatible agents over Agent Client Protocol v1. |
| `mcp.client` | Attaches the local Turboism MCP server to agent sessions over a credential-free stdio bridge, with the HTTP endpoint as a read-only fallback. |
| `ui.window` | Opens the Agent conversation window and the Agent Settings window. |

## Permissions

| Permission | Scope | Why it is requested |
|---|---|---|
| `turboism.action.register` | `application` | Registers the action that opens the Agent window. |
| `turboism.ui.menu.contribute` | `application` | Adds the Agent Settings entry to the Turboism menu. |
| `turboism.ui.toolbar.main.contribute` | `application` | Adds the Agent button beside Turboism Home on the main toolbar. |
| `turboism.config.plugin.read` | `application` | Restores the selected agent id, custom command, durable session id, and initial instructions. |
| `turboism.config.plugin.write` | `application` | Persists that plugin-owned state; agent credentials and MCP authorization are never stored. |
| `turboism.file.read` | `application` | Detects user-installed agent executables on PATH and in common install directories. |
| `turboism.file.write` | `application` | Stores local session indexes and native Pi sessions, and creates temporary MCP configuration and extension files. |
| `turboism.process.run` | `application` | Launches and supervises the selected agent executable. |
| `turboism.mcp.connection.read` | `application` | Reads the current MCP connection snapshot, including the credential-free stdio launch descriptor, and subscribes to endpoint changes so attached sessions reconnect. |

## Privacy and data

- The plugin stores only plugin-owned configuration under its own scope: selected agent, custom command, durable session id, and initial instructions.
- The MCP bearer token never enters an ACP payload, a command-line argument, an environment variable, UI text, or a log message; the stdio bridge reads it inside the MCP plugin state directory.
- Prompts and transcript content go only to the locally launched agent process.

## Status and limitations

- Preview plugin: the surface and persisted state format may evolve between releases.
- Agent-side features (durable sessions, model selectors, modes) depend on the capabilities each agent advertises.

## Troubleshooting

- **Connect fails with "Executable not found"**: set a custom command in **Manage agents…**, or install one of the detected agents so it is on PATH.
- **"The agent requires sign-in"**: press **Sign in…** and complete the agent's authentication method, then reconnect.
- **Cubism tools unavailable in the session**: the Agent transcript reports whether MCP attached writable (stdio bridge), read-only (HTTP), or not at all. Check that the `dev.turboism.plugin.mcp` plugin is enabled and started.
- **Session could not be loaded**: the stored durable session may be stale; the plugin automatically falls back to a new session.

## Support and license

- Report issues through the Turboism support channels.
- Distributed under the same license as Turboism.

## Composer controls

- The Agent selector uses the selected agent's own icon; custom agents use a terminal fallback.
- Model, reasoning effort, and permissions use text selectors without icons. Their available values come from ACP `configOptions`.
- Fast mode (lightning) and plan mode (checklist) use icon-only controls, with the current value in the tooltip and accessible label. Only controls advertised by the connected agent are shown; support can differ by agent, adapter version, model, or session. Missing controls do not imply an installation error. Other session modes retain text selectors.
- Thinking and consecutive tool/command activity use text disclosure rows without decorative icons.

## UI inspiration and credits

Parts of this plugin's chat UI are inspired by [Paseo](https://github.com/getpaseo/paseo): the compact composer, Agent identity icons, capability-dependent mode controls, and collapsible thinking/tool activity. The Swing implementation is maintained by Turboism.

Agent icon sources, upstream revisions, modifications, and licenses are documented in [Agent icon acknowledgements](src/main/resources/META-INF/licenses/agent-icons.md). Paseo's license and third-party notice and Lobe Icons' MIT license are bundled with the plugin. Agent names and logos belong to their respective owners; this plugin is not affiliated with or endorsed by Paseo or those providers.

### Initial instructions and references

The initial prompt field uses a role description as placeholder text; it is not saved as a user edit. Every request includes the bundled [modeling reference context](src/main/resources/context/modeling-reference.md): a short modeling workflow summary and verified Live2D official tutorial/manual and Turboism documentation links. This is an offline summary and index, not a full website snapshot. User-authored initial instructions are appended after the fixed context.

Functional icons use Lucide v0.468.0 consistently; provider marks retain their own branding. Draft images use cropped thumbnail cards. File cards show a middle-truncated filename, a file-type label, and the complete filename in the removal tooltip.

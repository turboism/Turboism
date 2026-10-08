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

> **Turboism official plugin** · **Status: Development — not released**

Opens an Agent conversation window and a Settings window. Drives a user-installed, ACP v1 compatible agent through the Agent Client Protocol and attaches the local Turboism MCP server through a credential-free stdio bridge.

## What it does

- Opens two Swing windows: **Agent** (chat conversation) and **Agent Settings**.
- Launches a user-installed ACP agent over stdin/stdout JSON-RPC: Claude Agent ACP, Codex ACP, Antigravity, Gemini CLI, OpenCode, Pi, Devin CLI, or a custom command.
- Drives `initialize`, `authenticate`, `session/new`, `session/load`, `session/resume`, `session/prompt`, `session/cancel`, `session/close`, `session/list`, `session/update`, `session/request_permission`, `session/set_config_option`, and `logout` per the ACP v1 contract.
- Attaches the Turboism MCP server as an ACP stdio MCP server so the agent can use the Cubism tools. The HTTP endpoint remains a read-only fallback for agents that only advertise HTTP MCP support.
- Detects the agent executable on PATH and in common install locations, and persists a small amount of plugin-owned state (selected agent, custom command, durable session id, initial instructions).

## Requirements and compatibility

- Turboism SDK API range `[0.1.0,0.2.0)`.
- A user-installed ACP v1 compatible agent executable, reachable from PATH or configured as a custom command in **Agent Settings**.
- The bundled `dev.turboism.plugin.mcp` plugin provides the Turboism MCP server when MCP tool access is wanted.
- Requires an installed Cubism Editor host; the plugin does not run inside the standalone preview.

## Install and enable

- The plugin is bundled with Turboism and is enabled by default.
- Open **Agent Settings** to pick an agent or enter a custom command.

## How to use

1. Click the ACP Agent icon on the main toolbar, or open the Agent window from the Turboism menu.
2. Pick an agent in **Agent Settings**, or type a custom command and save.
3. Press **Connect**. When the agent requires authentication, press **Sign in…** and complete the advertised method.
4. Type prompts in the conversation field. The agent may call Turboism MCP tools; permission prompts appear as dialogs inside the window.
5. Use the session options tab to switch provider, model, or mode when the agent advertises config options.

### MCP attachment

- When the MCP server publishes a stdio launch descriptor, the session attaches `{name:"turboism", command, args, env:[]}` and the bridge adds the persisted bearer token itself. The agent can use read and write tools.
- When only the HTTP endpoint is usable and the agent advertises HTTP MCP support, the session attaches the endpoint in read-only form; mutating calls stay gated by the bearer token that never leaves the MCP state directory.
- When neither form is usable, the session runs without MCP tools.

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
| `turboism.process.run` | `application` | Launches and supervises the selected agent executable. |
| `turboism.mcp.connection.read` | `application` | Reads the current MCP connection snapshot, including the credential-free stdio launch descriptor, and subscribes to endpoint changes so attached sessions reconnect. |

## Privacy and data

- The plugin stores only plugin-owned configuration under its own scope: selected agent, custom command, durable session id, and initial instructions.
- The MCP bearer token never enters an ACP payload, a command-line argument, an environment variable, UI text, or a log message; the stdio bridge reads it inside the MCP plugin state directory.
- Prompts and transcript content go only to the locally launched agent process.

## Status and limitations

- Development plugin: the surface and persisted state format may change between releases.
- Agent-side features (durable sessions, model selectors, modes) depend on the capabilities each agent advertises.

## Troubleshooting

- **Connect fails with "Executable not found"**: set a custom command in **Agent Settings**, or install one of the detected agents so it is on PATH.
- **"The agent requires sign-in"**: press **Sign in…** and complete the agent's authentication method, then reconnect.
- **Cubism tools unavailable in the session**: the Agent transcript reports whether MCP attached writable (stdio bridge), read-only (HTTP), or not at all. Check that the `dev.turboism.plugin.mcp` plugin is enabled and started.
- **Session could not be loaded**: the stored durable session may be stale; the plugin automatically falls back to a new session.

## Support and license

- Report issues through the Turboism support channels.
- Distributed under the same license as Turboism.

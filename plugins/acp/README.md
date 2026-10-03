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

> **Official Turboism plugin** · **Status: development — unreleased**

Opens Agent and Settings windows and connects a user-installed ACP-compatible agent to the authenticated Turboism MCP server through Agent Client Protocol (ACP) v1.

| Field | Value |
|---|---|
| Plugin ID | `dev.turboism.plugin.acp` |
| Display name | Turboism ACP |
| Category | Integration |
| Tags | automation, acp, agent |
| Delivery | Development builds only — the plugin ships in no release package |

## What it does

- Detects and launches a user-installed agent executable — from `PATH` or a custom command — and speaks ACP v1 over supervised stdio without a shell.
- Ships no agent runtime: Turboism never downloads, bundles, verifies, or manages a third-party agent binary.
- Passes the current authenticated Turboism MCP endpoint to agents that advertise HTTP MCP support, so the agent can call typed Cubism automation tools.
- Provides a dedicated Agent conversation window with a durable-session sidebar, compact color-coded bounded live transcript, IME-aware prompt submission, cancellation, permission review, and ACP-driven authentication.
- Lists, creates, loads, resumes, and closes durable sessions when the selected agent advertises the matching session capabilities.

## Agent selection

Settings → Agent offers the built-in catalog plus a custom command:

| Agent | Executable | ACP launch |
|---|---|---|
| Claude Agent (ACP) | `claude-agent-acp` | direct |
| Codex (ACP) | `codex-acp` | direct |
| Google Antigravity | `agy-acp` | direct |
| Gemini CLI | `gemini` | `--experimental-acp` |
| OpenCode | `opencode` | `acp` subcommand |
| Pi (ACP) | `pi-acp` | direct |
| Devin CLI | `devin` | `acp` subcommand |
| Custom command | user argv | as entered |

**Detect** searches `PATH` and common per-user install directories. Agent authentication, provider, and model stay with the agent: agents that expose ACP `authMethods` are signed in from the Settings page, and agents with a documented terminal login open it through **Open login terminal**. Turboism stores no agent credential.

## Runtime and security model

- **Process boundary:** the selected agent runs as a child process supervised by Turboism; teardown kills the whole process tree.
- **MCP attachment:** the session receives the authenticated loopback MCP endpoint only when the agent advertises HTTP MCP support. The endpoint carries no token in the URL; the trust boundary is the agent binary the user installed and selected.
- **Permissions:** every agent tool call is confirmed through the ACP `session/request_permission` dialog before it runs.
- **Standing instructions:** the fixed boundary prompt asks the agent to use only Turboism MCP tools. It is advisory, not a sandbox — agents with native file/terminal tools are governed by their own configuration.

## Getting started

1. Install one supported agent and sign it in with its own CLI if it requires terminal login.
2. Enable **Turboism MCP Server** and **Turboism ACP** in Plugin Management.
3. Open **Turboism → ACP Settings**, pick the agent (or enter a custom command), and run **Detect** or sign in.
4. Choose the **ACP** main-toolbar icon to open the Agent window and start a session.

## Granted permissions

| Permission | Scope | Purpose |
|---|---|---|
| `turboism.action.register` | application | Registers the Agent window and Settings actions. |
| `turboism.ui.menu.contribute` | application | Adds the **ACP Settings** entry to the Turboism menu. |
| `turboism.ui.toolbar.main.contribute` | application | Adds the ACP Agent icon beside Turboism Home on the main toolbar. |
| `turboism.config.plugin.read` | application | Restores the selected agent id, custom command, durable session id, and initial instructions. |
| `turboism.config.plugin.write` | application | Persists that Turboism-owned state; agent credentials and transcript data are never stored. |
| `turboism.file.read` | application | Detects user-installed agent executables on PATH and common install directories. |
| `turboism.process.run` | application | Launches and supervises the selected agent executable. |
| `turboism.mcp.connection.read` | application | Reads the current authenticated MCP endpoint to attach it to ACP sessions. |

## Known limitations

- ACP v1 only; protocol v2 (`auth/login`, `session/resume` equivalents) is not negotiated yet.
- If the MCP server is disabled or restarted while a session is open, the attached endpoint can go stale — reconnect to rebind the current endpoint.
- Host-level validation against real agent binaries is manual until a scripted ACP agent fixture replaces the retired fx probe.

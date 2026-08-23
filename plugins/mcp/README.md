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

# Turboism MCP Server

> **Official Turboism plugin** · **Status: Preview**

Runs a bearer-token-protected MCP Streamable HTTP server on the local loopback interface.

| Detail | Value |
|---|---|
| Version | `0.1.0` |
| Plugin ID | `dev.turboism.plugin.mcp` |
| Category | `integration` |
| Tags | mcp, automation, external-tools |
| Turboism API | `[0.1.0,0.2.0)` |
| Requires Cubism | Yes |
| Interface | `none` |
| License | Project License |

## What it does

- Exposes typed tools for model objects, parameters, hierarchy, selection, model snapshots, and clip masks.
- Provides explicit rename, reparent, create, and delete operations through Turboism's authoring APIs.
- Writes connection information for local MCP clients and enforces origin, body-size, and request-rate limits.

## Requirements and compatibility

- **Turboism API:** `[0.1.0,0.2.0)`.
- **Cubism:** Requires Cubism. Turboism currently admits exact reviewed Editor artifacts `5.2.03` and `5.3.02`; this plugin exposes each host-facing feature only when its declared services and capabilities are available.
- **Interface mode:** `none`.
- **Plugin dependencies:** None declared.

## Install and enable

This official plugin is a **store candidate**, not yet a published store listing. Until marketplace publication, install it through Turboism's official release packaging, then enable it in **Plugin Management**. Disable or uninstall it from the same window when the workflow is not needed.

## How to use

1. Enable the plugin and read the generated `mcp-connection.json` from the plugin state directory.
2. Configure the local MCP client with the loopback endpoint and bearer authorization value from that file.
3. Call read tools freely; review mutation arguments carefully, especially delete requests and explicit cascade policy.

## Capabilities

| Declared capability | User effect |
|---|---|
| `mcp.streamable-http` | Serves MCP Streamable HTTP on authenticated loopback. |
| `cubism.model.objects.read` | Lists and resolves typed model-object information. |
| `cubism.model.objects.write` | Renames, reparents, creates, and deletes supported model objects. |

## Permissions

| Permission | Scope | Why it is requested |
|---|---|---|
| `turboism.cubism.model.read` | `application` | Lists and resolves active Cubism model objects requested through MCP. |
| `turboism.cubism.parameter.read` | `application` | Lists and resolves active Cubism model parameters requested through MCP. |
| `turboism.cubism.project.read` | `application` | Reads the active project, workspace, and theme snapshots requested through MCP. |
| `turboism.cubism.model.write` | `application` | Renames, creates, and deletes active Cubism model objects requested through MCP. |

## Privacy and data

### Network

Listens only on `127.0.0.1` using HTTP. Every request requires the generated or configured bearer token, accepted loopback origin, a body no larger than 1 MiB, and the configured rate limit. It is not designed for remote access.

### Local data

Writes `mcp-connection.json` in plugin state storage with the endpoint, bearer authorization value, process ID, and start time. On POSIX systems it attempts owner-only permissions. The token is sensitive local connection material.

### Telemetry

No telemetry is sent by this plugin.

Plugin lifecycle and failure records can appear in Turboism's session log and Cubism's host log with the plugin ID attached.

## Status and limitations

- **Status:** Preview.
- Requires all typed Cubism services needed by the advertised tools; startup fails closed when they are unavailable.
- Default port `0` chooses an ephemeral port. `turboism.mcp.port`, `turboism.mcp.token`, and `turboism.mcp.requestsPerMinute` are advanced system-property overrides.
- Delete is destructive. The default rejects referenced objects; cascade must be requested explicitly.

## Troubleshooting

| Symptom | What to check |
|---|---|
| MCP client cannot connect | Read the current connection file, confirm the process is running, and use its exact loopback endpoint. |
| Request is unauthorized | Use the bearer authorization value from the current session's connection file. |
| Requests are rejected | Check method, origin, MCP protocol version, body size, and the configured per-minute rate limit. |

## Support and license

- **Project website:** [https://turboism.dev](https://turboism.dev)
- **Publisher:** Turboism Contributors
- **License:** Project License
- **Plugin ID:** `dev.turboism.plugin.mcp`

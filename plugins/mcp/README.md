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
| Public catalog | 5 tools · 13 resources · 2 templates · 8 prompts |
| Turboism API | `[0.1.0,0.2.0)` |
| Requires Cubism | Yes |
| Interface | `none` |

## What it does

- Exposes typed, domain-level model-object, parameter, binding, history, and Editor-command tools.
- Exposes active-document, model, workspace, Cubism Core, and sanitized runtime diagnostics as JSON resources.
- Provides workflow prompts for inspection, diagnostics, editing, recovery, and bounded Editor automation.
- Serves only authenticated loopback clients and enforces origin, body-size, protocol, session, and rate limits.

## Requirements and compatibility

- **Turboism API:** `[0.1.0,0.2.0)`.
- **Cubism:** Turboism currently admits exact reviewed Editor artifacts `5.2.03`, `5.3.02`, and `5.3.03`. A host-facing resource fails closed when its public SDK capability is unavailable; it never fabricates an empty success.
- **Transport:** MCP Streamable HTTP using protocol `2025-11-25`, with compatibility negotiation for the supported earlier protocol versions.
- **Interface mode:** `none`.
- **Plugin dependencies:** None declared.

## Install and enable

1. Install and enable the plugin through Turboism's official release packaging and **Plugin Management**.
2. On a POSIX filesystem with enforceable owner-only permissions, read the generated `mcp-connection.json` from the plugin state directory. Windows startup currently fails closed because Java 17 cannot prove a protected DACL or provide handle-relative reparse-safe publication for the bearer file.
3. Configure the local MCP client with the exact numeric-loopback endpoint and bearer authorization value.
4. Complete `initialize`, retain `MCP-Session-Id`, send `notifications/initialized`, and include the negotiated protocol version on later requests.

The connection file contains a local secret. Do not log it, copy it into validation evidence, or expose it to an untrusted process. Windows support requires a runtime-owned native private state-hierarchy and publication service that creates and retains protected directory/file handles from the start, applies a protected DACL, verifies reparse points and file identity, writes and flushes through the handle, and publishes atomically. Tightening a directory after `Files.createDirectories` inherited its ACL cannot revoke access retained through an already-open handle, so a plugin-local one-shot script or later ACL rewrite is insufficient.

## How to use

Connect a trusted local MCP client with the endpoint and bearer value from `mcp-connection.json`, initialize the session, then use the catalog below. Start with read resources and prompts; invoke write tools only after reviewing the requested operations and their permission scope.

## Public MCP catalog

### Tools

| Tool | Purpose |
|---|---|
| `turboism.model_objects.apply` | Applies ordered create, rename, reparent, and delete operations. |
| `turboism.parameters.apply` | Applies typed parameter value and definition operations. |
| `turboism.parameter_bindings.apply` | Applies typed parameter-binding operations and native atomic transfers. |
| `turboism.history.move` | Moves native Undo history with generation/revision guards. |
| `turboism.editor_commands.execute` | Executes discoverable direct and typed non-file Editor commands. |

Writes run after runtime permission and argument checks. Mixed batches can partially succeed and report per-operation results; they are not presented as transactions unless the underlying SDK batch is atomic.

### Resources

| Resource URI | Purpose |
|---|---|
| `turboism://active/document` | Active project, document, model, selection, workspace, and theme snapshot. |
| `turboism://active/model/overview` | Compact active model and selection overview. |
| `turboism://active/model/hierarchy` | Active model object hierarchy. |
| `turboism://active/model/clip-masks` | Active model ArtMesh clip-mask records. |
| `turboism://active/model/parameters` | Actual active-model parameter state. |
| `turboism://active/model/statistics` | Structural, geometry, texture, mask, and optional offscreen counts. |
| `turboism://active/model/textures` | Raw-image, model-image-group, and texture-atlas metadata without paths or bytes. |
| `turboism://active/document/history` | Native Undo availability, entries, generation, revision, and position. |
| `turboism://environment/cubism-core` | Admitted Cubism Core version and public capability flags. |
| `turboism://environment/workspace` | Current and available workspaces with typed availability. |
| `turboism://environment/workspace/layout` | Ordered read-only dock-layout tree with typed availability. |
| `turboism://environment/diagnostics` | Bounded, path-redacted Turboism diagnostic problems. |
| `turboism://host/editor-commands` | Currently available supported Editor commands and typed request schemas. |

Resources are point-in-time snapshots. The server does not currently declare subscriptions or resource-update notifications.

Workspace and layout resources may successfully return `availability: "UNAVAILABLE"` with a diagnostic code. That is a typed host state, distinct from JSON-RPC errors for permission denial (`-32001`), resource absence (`-32002`), unsupported capability (`-32003`), timeout (`-32004`), or cancellation (`-32800`).

### Resource templates

- `turboism://active/model/parameters/{parameterId}`
- `turboism://active/model/parameters/{parameterId}/bindings`

### Prompts

- `inspect_active_document`
- `edit_model_structure`
- `normalize_parameters`
- `repair_parameter_bindings`
- `recover_document_history`
- `run_editor_command`
- `diagnose_environment`
- `inspect_model_diagnostics`

Prompts accept no arguments. The two diagnostic prompts explicitly prohibit mutations.

## Capabilities

| Capability | User effect |
|---|---|
| `mcp.streamable-http` | Serves authenticated MCP Streamable HTTP on numeric loopback. |
| `mcp.tools` | Publishes the five typed tool workflows. |
| `mcp.resources` | Publishes static and templated JSON resources. |
| `mcp.prompts` | Publishes user-controlled workflow prompts. |
| `cubism.workspace.read` | Reads typed workspace status and dock-layout snapshots. |
| `cubism.model.objects.read` | Inspects supported model objects. |
| `cubism.model.objects.write` | Creates, renames, reparents, and deletes supported model objects. |
| `cubism.parameters.read` | Reads active-model parameters. |
| `cubism.parameters.write` | Applies typed parameter value and definition operations. |
| `cubism.parameter-bindings.read` | Reads parameter bindings. |
| `cubism.parameter-bindings.write` | Applies typed binding operations and native atomic transfers. |
| `cubism.history.read` | Reads native Undo history. |
| `cubism.history.write` | Moves native Undo history with generation and revision guards. |
| `cubism.editor-commands.execute` | Executes the bounded supported Editor-command surface. |

## Permissions

| Permission | Scope | Why it is requested |
|---|---|---|
| `turboism.cubism.model.read` | `application` | Reads active model objects, Core metadata, statistics, and texture metadata. |
| `turboism.cubism.parameter.read` | `application` | Reads active Cubism model parameters. |
| `turboism.cubism.project.read` | `application` | Reads active project, workspace, layout, and theme state. |
| `turboism.cubism.model.write` | `application` | Applies typed model, parameter, binding, history, and model-setting writes. |
| `turboism.file.write` | `application` | Allows the direct Editor `SAVE` command. |
| `turboism.network.fetch` | `application` | Allows the typed external-application settings command. |
| `turboism.process.run` | `application` | Allows the typed external-application settings command. |
| `turboism.mcp.connection.publish` | `application` | Publishes the active authenticated loopback endpoint to permission-approved automation plugins through the process-local runtime exchange. |

The diagnostic expansion adds no `host.unsafe`, performance, file-read, config, event, or UI-mutation permission.

## Privacy and data minimization

### Network

The server listens only on `127.0.0.1`. Every request requires the generated or configured bearer token, an accepted loopback origin, a body no larger than 1 MiB, and the configured rate limit. It is not designed for remote access.

### Local data

The plugin writes only its connection metadata in plugin state storage. On POSIX systems it attempts owner-only permissions. Diagnostic and model resources do not expose raw filesystem paths, native host objects, image bytes, or the bearer token.

`turboism://environment/diagnostics` omits `DiagnosticReport.Problem.path()`, bounds the problem list, converts messages to one line, caps message length, and redacts Unix paths, Windows paths, and `file:` URIs.

### Telemetry

No telemetry is sent by this plugin. Plugin lifecycle and failure records can appear in Turboism's session log and Cubism's host log with the plugin ID attached.

## Status and limitations

- **Status:** Preview.
- Default port `0` chooses an ephemeral port. `turboism.mcp.port`, `turboism.mcp.token`, and `turboism.mcp.requestsPerMinute` are advanced system-property overrides.
- GET SSE, resource subscriptions, list-changed notifications, progress notifications, and MCP Tasks are not implemented.
- Workspace switching and default-layout mutation are intentionally unavailable because the runtime currently gates them with `turboism.host.unsafe`.
- `EditorFileCommandRequest`, import/export, save-as, backup, and other handle-based file workflows remain unavailable until an MCP session can receive a real `UserFileHandle` authorization without accepting raw paths.
- Generic SDK invocation, reflection, arbitrary native members, shell execution, raw paths, dialog automation, and lifecycle-registration APIs are not exposed.
- Performance sampling, canvas/profile, physics/animation, texture-atlas authoring, screenshots, and binary resources remain separate future capabilities with their own permission and exact-host evidence requirements.
- Delete is destructive. The default rejects referenced objects; cascade must be requested explicitly.

## Troubleshooting

| Symptom | What to check |
|---|---|
| MCP client cannot connect | Read the current connection file, confirm the process is running, and use its exact loopback endpoint. |
| Request is unauthorized | Use the bearer authorization value from the current session's connection file. |
| Request is rejected before dispatch | Check method, origin, session, MCP protocol version, body size, content type, and rate limit. |
| Resource returns `UNAVAILABLE` | Check active document/model state and exact-host capability admission; do not treat it as an empty successful value. |
| Resource returns permission denied | Check the plugin descriptor grant and the specific runtime permission named above. |

## Support and license

- **Project website:** [https://turboism.dev](https://turboism.dev)
- **Publisher:** Turboism Contributors
- **License:** Project License
- **Plugin ID:** `dev.turboism.plugin.mcp`

---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.mcp
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: integration
tags: mcp, automation, external-tools
turboismApi: "[0.67.0,1.0.0)"
requiresCubism: true
interface: swing
---

# Turboism MCP Server

> **Official Turboism plugin** · **Status: Preview**

Runs a token-gated MCP Streamable HTTP server on the local loopback interface, plus a stdio bridge so MCP clients need no credential handling.

| Detail | Value |
|---|---|
| Version | `0.1.0` |
| Plugin ID | `dev.turboism.plugin.mcp` |
| Public catalog | 80 tools · 15 resources · 2 templates · 8 prompts |
| Requires Cubism | Yes |
| Interface | `swing` |

## What it does

- Exposes typed, domain-level model-object, parameter, binding, history, and Editor-command tools.
- Exposes active-document, model, workspace, Cubism Core, and sanitized runtime diagnostics as JSON resources.
- Provides workflow prompts for inspection, diagnostics, editing, recovery, and bounded Editor automation.
- Serves only loopback clients and enforces origin, body-size, protocol, session, and rate limits.
- Gates mutating tool calls and session close behind a per-user bearer token that the stdio bridge supplies automatically.
- Publishes a credential-free stdio launch descriptor (the running JVM's launcher, the plugin classpath, the bridge main class, and the state directory) through the runtime connection boundary, so bundled consumers such as the ACP plugin attach agents to a precompiled bridge without handling the token.

## Requirements and compatibility

- **SDK API range:** `[0.67.0,1.0.0)`.
- **Cubism:** Turboism currently admits exact reviewed Editor artifacts `5.0.00` through `5.3.04`. A host-facing resource fails closed when its public SDK capability is unavailable; it never fabricates an empty success.
- **Transport:** MCP Streamable HTTP using protocol `2025-11-25`, with compatibility negotiation for the supported earlier protocol versions.
- **Interface mode:** `swing`, with a local connection-details window.
- **Plugin dependencies:** None declared.

<!-- compatibility:begin -->
**Cubism Editor version support (generated):** all reviewed versions (5.0.00–5.0.06, 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04; 20 exact versions).
Full feature set (reviewed versions): 5.3.00–5.3.04.
- External edit-session API (edit()) not on 5.0.00–5.1.01（[Cubism 5.1.00 added External application integration (WebSocket API)](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history5-1/)）
- Top menu integration not on 5.0.00–5.0.06（earlier Cubism versions lack the feature entirely）
- MCP editor command execution not on 5.0.00–5.0.06（earlier Cubism versions lack the feature entirely）
- MCP editor-command listing not on 5.0.00–5.0.06（earlier Cubism versions lack the feature entirely）
Full matrix: [compatibility matrix](../../compatibility/cubism/plugins.md)
<!-- compatibility:end -->

## Install and enable

1. Install and enable the plugin through Turboism's official release packaging and **Plugin Management**.
2. Open **Turboism → MCP Connection**. This window shows the current local address and bounded process-local connection/request history.
3. Copy the **Stdio client config** line into a local MCP client (recommended), or the HTTP **Address** into a direct-HTTP client. The default is `http://127.0.0.1:43123/mcp`. Stdio clients never see the token; direct-HTTP clients must send `Authorization: Bearer <token>` (contents of `mcp.token` in the plugin state directory) for mutating tool calls and session close.
4. Keep the window open when diagnosing a client connection; use **Refresh** to load the newest history entries.
5. Programmatic local consumers may still read the owner-protected `mcp-connection.json` from the per-user plugin state directory.
6. Complete `initialize`, retain `MCP-Session-Id`, send `notifications/initialized`, and include the negotiated protocol version on later requests.

Turboism binds the transport to loopback, rejects unsafe connection-file paths, verifies owner and file type, avoids following symbolic links, and uses owner-only permissions where the platform exposes them. The visible history never includes MCP session IDs and is discarded when the process exits.

## How to use

Connect a local MCP client with the configuration shown in **Turboism → MCP Connection**, initialize the session, then use the catalog below. Start with read resources and prompts; invoke write tools only after reviewing the requested operations and their permission scope.

### Common coding agents

**stdio (recommended)** — the plugin publishes `TurboismMcpBridge.java` next to `mcp.token` in its state directory; the bridge relays stdio frames to the loopback endpoint with the bearer token attached. Bundled in-process consumers such as the ACP plugin instead launch the precompiled bridge class shipped inside this plugin JAR with the state directory as an argument, which also works on JREs without `jdk.compiler`. The connection window shows the exact, copyable line:

```json
{
  "mcpServers": {
    "turboism": {
      "command": "java",
      "args": ["<plugin-state-dir>/TurboismMcpBridge.java"]
    }
  }
}
```

**Claude Code (HTTP)**

```bash
claude mcp add --transport http turboism \
  http://127.0.0.1:43123/mcp
```

Direct-HTTP clients keep working for read-only methods; mutating tool calls and session close also need the header `Authorization: Bearer <token>`, where `<token>` is the contents of `mcp.token` in the plugin state directory.

Equivalent Claude Code JSON configuration:

```json
{
  "mcpServers": {
    "turboism": {
      "type": "http",
      "url": "http://127.0.0.1:43123/mcp"
    }
  }
}
```

**Visual Studio Code**

```json
{
  "servers": {
    "turboism": {
      "type": "http",
      "url": "http://127.0.0.1:43123/mcp"
    }
  }
}
```

**Codex CLI**

Add:

```toml
[mcp_servers.turboism]
url = "http://127.0.0.1:43123/mcp"
```

## Public MCP catalog

### Tools

| Tool | Purpose |
|---|---|
| `turboism.model_objects.apply` | Applies ordered create, rename, reparent, and delete operations. |
| `turboism.parameters.apply` | Applies typed parameter value and definition operations. |
| `turboism.parameters.structure.read` | Read bounded parameter folder mixed order, definitions and explicit Combined metadata. |
| `turboism.parameters.structure.plan` | Preview sequential folder/parameter organization edits, created references and exact deletion effects. |
| `turboism.parameters.structure.apply` | Conditionally apply a session plan inside one owned native authoring transaction. |
| `turboism.parameters.structure.result` | Read retained operation results without host reads. |
| `turboism.authoring.meshes.triangulate` | Preview detached native triangulation; return actual reordered positions/indices without inferred UV mapping. |
| `turboism.authoring.meshes.create_plan` | Plan new source/default meshes with explicit parent, normalized UVs and optional image binding. |
| `turboism.authoring.meshes.create` | Create the batch and optionally bind images in one owned transaction with scoped CAS/readback. |
| `turboism.authoring.meshes.create_result` | Read retained creation outcome without host reads. |
| `turboism.documents.read` | Read bounded project document identities, including empty sessions, without paths. |
| `turboism.documents.new_model` | Invoke typed NEW_MODEL once and inspect actual new active authoring identity. |
| `turboism.documents.new_result` | Read retained new-model outcome without host reads or redispatch. |
| `turboism.parameter_bindings.apply` | Applies typed parameter-binding operations and native atomic transfers. |
| `turboism.glues.read` | Lists or reads exact-version Glue authoring state. |
| `turboism.glues.write` | Applies one Undo-aware Glue mutation. |
| `turboism.textures.read` | Reads bounded texture-library metadata and a fresh state token. |
| `turboism.textures.write` | Applies one guarded texture-library operation with standalone native Undo. |
| `turboism.assets.binding.read` | Read an ArtMesh authored image/atlas input and bounded registered image candidates against an authoring snapshot. |
| `turboism.assets.binding.bind` | Bind one discovered existing model image with full CAS, exact native readback and an owned SDK transaction. |
| `turboism.assets.binding.result` | Read retained historical binding results without querying the host. |
| `turboism.assets.images.register` | Start bounded PNG registration and binding from a one-use authorized READ grant; returns a retained asynchronous job. |
| `turboism.assets.images.result` | Read registration phase, content hash and historical native transaction/binding result without host reads. |
| `turboism.assets.images.cancel` | Request cooperative cancellation before native transaction admission; never interrupt admitted host work. |
| `turboism.atlas.read` | Read a bounded session-retained complete atlas snapshot. |
| `turboism.atlas.plan` | Build a deterministic inspectable shelf plan without writes. |
| `turboism.atlas.apply` | Start a complete rectangle apply job with standalone native Undo. |
| `turboism.atlas.result` | Read retained atlas job outcome without host access. |
| `turboism.atlas.cancel` | Request cancellation before native admission. |
| `turboism.export.moc.plan` | Inspect export settings/source metadata and issue an immutable session plan without exporting or consuming a grant. |
| `turboism.export.moc.execute` | Start official MOC/Core validation, PNG/static model3/manifest ZIP encoding and an authorized atomic file write. |
| `turboism.export.moc.result` | Read retained export/file outcome and artifact hashes without host reads. |
| `turboism.export.moc.cancel` | Request cancellation before file-write admission. |
| `turboism.history.read` | Reads the immutable native Undo-history snapshot. |
| `turboism.history.undo` | Performs guarded Undo with generation, revision, and optional entry identities. |
| `turboism.history.redo` | Performs guarded Redo with generation, revision, and optional entry identities. |
| `turboism.transaction.execute` | Groups eligible reads and Undo-aware writes into one authoring transaction. |
| `turboism.authoring.meshes.read` | Read paged immutable ArtMesh source geometry, GUID axes/cells/forms and morph identities. |
| `turboism.authoring.meshes.evaluated.read` | Observe native last-calculated positions and GUID parameter consumption without asserting freshness. |
| `turboism.authoring.warps.read` | Read stored Warp grids, complete GUID axes/cells/forms and control points. |
| `turboism.authoring.rotations.read` | Read Rotation base angle and each stored form, with complete GUID axes/cells. |
| `turboism.authoring.keyforms.preview` | Preview the exact typed write request with full CAS, compact native-space diffs and predicted hashes. |
| `turboism.authoring.keyforms.write` | Write ordinary ArtMesh positions/draw order/opacity, Warp controls and Rotation forms with full snapshot CAS. |
| `turboism.authoring.keyforms.result` | Query a retained session-owned historical operation result without model access. |
| `turboism.render.state` | Read the active document/history/pose binding and render budgets without invoking the renderer. |
| `turboism.render.capture` | Capture a state-bound native model PNG as typed MCP image content. |
| `turboism.render.pose_grid` | Render up to nine explicit poses and verify mandatory parameter restoration. |
| `turboism.render.pose_grid.status` | Read progress and terminal state without entering the UI queue. |
| `turboism.render.pose_grid.cancel` | Request cooperative cancellation; do not interrupt a native call or restoration. |
| `turboism.render.pose_grid.result` | Read retained per-pose typed images, restoration evidence and transaction receipt. |
| `turboism.render.pose_grid.release` | Release consumed terminal images while retaining metadata and replay protection. |
| `turboism.validation.geometry` | Read-only scoped ArtMesh topology, area, aspect, winding and edge-ratio findings; no automatic repair. |
| `turboism.validation.warp` | Read-only stored/proposed Warp bilinear control-cell fold, degeneracy and reference-orientation diagnostics. |
| `turboism.validation.raster` | Inspect explicit color fractions/frame differences in session-retained pose PNG regions; no host access or artistic acceptance. |
| `turboism.rig.rotation.plan` | Read-only Rotation angle replacement on explicit one to three ordinary axes from a live-verified stored snapshot; returns a separate CAS write request. |
| `turboism.rig.warp.plan` | Preview native-coordinate landmark displacement fields for an existing one to three axis Warp grid; no write or anatomical inference. |
| `turboism.rig.mesh.plan` | Preview native stored ArtMesh vertex landmark fields and explicit XY residuals; returns a separate typed write request. |
| `turboism.authoring.masks.read` | Read a bounded ArtMesh mask dependency snapshot with source GUIDs. |
| `turboism.authoring.masks.write` | Conditionally replace nonempty mask lists/inversion in one SDK transaction. |
| `turboism.authoring.masks.result` | Query retained session-local mask operation results without waiting for UI. |
| `turboism.authoring.properties.read` | Read own source visibility/lock and ArtMesh culling against an authoring snapshot. |
| `turboism.authoring.properties.write` | Apply explicit source Boolean patches under full stored-state CAS and one SDK transaction. |
| `turboism.authoring.properties.result` | Read retained source property results without entering the UI queue. |
| `turboism.files.request_grant` | Request a user-chosen session file grant asynchronously. |
| `turboism.files.grant_status` | Poll authorization without waiting for the native chooser. |
| `turboism.files.revoke_grant` | Withdraw a grant and discard any late native authorization. |
| `turboism.documents.open` | Dispatch authorized CMO3 OPEN on reviewed exact 5.3.03; rediscover readiness. |
| `turboism.documents.save_as` | Save the expected active model to a user-authorized new destination. |
| `turboism.documents.file_result` | Read a retained native file-command outcome without waiting for UI. |
| `turboism.files.inspect` | Asynchronously observe byte count and SHA-256 through a one-use READ grant. |
| `turboism.files.inspect_result` | Read the retained bounded file observation without waiting for I/O. |
| `turboism.capabilities.read` | Reads operation effects, version support, transaction eligibility, and SDK coverage. |
| `turboism.editor_commands.execute` | Executes discoverable direct and typed non-file Editor commands. |

The complete batch is checked against its input schema before the first write. A malformed later entry rejects the request without applying earlier entries. Valid-shaped batches can still partially succeed when a runtime operation fails; completed results are preserved and `stopOnError` controls subsequent execution. A native atomic sub-operation does not make the whole MCP batch a transaction.

##### Write outcomes and inversion migration

Confirmed parameter writes retain `outcome`, `retryable: false`, and their identity even when subsequent reads fail. A detail read failure returns `APPLIED_WITH_READBACK_WARNING`. If the optional final parameter snapshot fails, `parameters` is `null` and `parameterSnapshotWarning` explains why; completed `results` remain intact. Do not retry a confirmed write just because its readback is unavailable.

The ambiguous parameter-binding operation `invert` is rejected before dispatch. To explicitly reverse **every normal keyform binding** on selected targets, use:

```json
{
  "operations": [{
    "operation": "invert_all_bindings",
    "scope": "all_target_bindings",
    "targets": [{"type": "art_mesh", "id": "ArtMesh1"}]
  }]
}
```

This is not single-parameter inversion and does not invert blend-shape bindings. The receipt includes the scope and affected parameter IDs; successful readback includes all affected normal bindings. A `parameterId` field is not accepted for this operation. Single-parameter inversion requires a separate verified provider contract.

The three legacy `model_objects.apply`, `parameters.apply` and `parameter_bindings.apply` tools remain `transactionEligible: false`; `turboism.transaction.execute` rejects them before opening a transaction. This does not imply that their individual native operations lack Undo. `turboism.capabilities.read` exposes these temporary exceptions. Its coverage ledger now tracks eighteen explicitly listed SDK owners, including model-object, parameter, binding and texture APIs; it is not whole-SDK coverage. `MCP_LEGACY_WRITE` marks exposed legacy writes without claiming grouped transaction readiness. Empty `supportedVersions` on these added ledger rows means no exact-version claim is made by the ledger; actual provider admission still applies.

### Texture-library authoring

Call `turboism.textures.read` with `operation: "list"`, then pass its returned **`state` object**
unchanged as `expectedState` to `turboism.textures.write`. The separate `stateToken` string is a
read-correlation ID, not the write precondition or authorization. Re-read `state` after any write or history movement.

The five operations cover exact **5.2.03 and 5.3.00–5.3.04**: `add_model_image_group` (`name`),
`remove_model_image` (`id`), `add_texture_atlas` (`name`, `widthPixels`, `heightPixels`),
`remove_texture_atlas` (`id`), and `remove_raw_image` (`id`). Every removal requires
`confirmDelete: true`. Invalid arguments, duplicate group names and stale state are rejected before
mutation. Groups have names, not invented GUIDs. Names and IDs are limited to 256 Unicode code
points; atlas dimensions are 1–16384 pixels. Reads reject projections exceeding 1024 items per
library collection or 1024 total nested model images instead of silently truncating them.
Confirmed write receipts remain non-retryable even if observational readback fails: an unavailable
`postState` is `null`, never a fabricated pre-write snapshot. An untyped native write exception
returns `OUTCOME_UNKNOWN`, not a false `NOT_APPLIED`. Host exception messages are not exposed.
Native history must be available before a guarded write is admitted.

These writes have individual native Undo but remain `transactionEligible: false`.
`MCP_WRITE_STANDALONE_UNDO` in the coverage ledger distinguishes them from grouped transaction children.
Raw-image removal clears only the selected raw image and its input connections, retaining model
images, ArtMeshes and atlases. Exact5.2 composes native Undo factories without opening a dialog.
This is library CRUD, not arbitrary pixel upload, file import/export or MCP layout-plan application.
Version metadata identifies runtime adapters and tests; exact-host acceptance is tracked separately
and must not be inferred from a capability row or from synthetic tests alone.

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
| `turboism://active/model/parameter-bindings` | Aggregated parameter-binding state for the active model. |
| `turboism://active/document/history` | Native Undo availability, entries, generation, revision, and position. |
| `turboism://environment/cubism-core` | Admitted Cubism Core version and public capability flags. |
| `turboism://environment/workspace` | Current and available workspaces with typed availability. |
| `turboism://environment/workspace/layout` | Ordered read-only dock-layout tree with typed availability. |
| `turboism://environment/diagnostics` | Bounded, path-redacted Turboism startup diagnostic problems. |
| `turboism://environment/runtime-diagnostics` | Recent bounded and sanitized MCP runtime outcomes. |
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
| `mcp.streamable-http` | Serves token-gated MCP Streamable HTTP on numeric loopback (bearer required for mutating operations). |
| `mcp.tools` | Publishes 80 typed tool workflows. |
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
| `turboism.cubism.parameter.read` | `application` | Declares the parameter-read intent for tooling surfaces; parameter reads themselves are enforced at runtime by the model-read permission above. |
| `turboism.cubism.project.read` | `application` | Reads active project, workspace, layout, and theme state. |
| `turboism.cubism.model.write` | `application` | Applies typed model, parameter, binding, history, and model-setting writes. |
| `turboism.ui.file-chooser.request` | `application` | Requests user approval through the file chooser before issuing session-scoped file grants. |
| `turboism.file.read` | `application` | Verifies MCP connection-file metadata (existence, ownership, symlink checks) before publishing the loopback endpoint. |
| `turboism.file.write` | `application` | Allows the direct Editor `SAVE` command and writes the owner-only loopback connection file under the MCP state directory. |
| `turboism.network.fetch` | `application` | Allows the typed external-application settings command and binds the Origin-guarded loopback HTTP transport. |
| `turboism.process.run` | `application` | Allows the typed external-application settings command. |
| `turboism.mcp.connection.publish` | `application` | Publishes the active token-gated loopback endpoint to permission-approved automation plugins through the process-local runtime exchange. |
| `turboism.action.register` | `application` | Registers the local MCP Connection window action. |
| `turboism.ui.menu.contribute` | `application` | Adds **MCP Connection** to the Turboism menu. |

The diagnostic expansion adds no `host.unsafe`, performance, file-read, config, event, or UI-mutation permission.

## Privacy and data minimization

### Network

The server listens only on `127.0.0.1`. Requests require a loopback `Host` header (`127.0.0.1`, `localhost`, or `[::1]`, with an optional `:port`), an accepted loopback origin, a body no larger than 1 MiB, the negotiated MCP session/protocol headers, and the configured rate limit. Mutating tool calls and session close additionally require `Authorization: Bearer <token>` (contents of `mcp.token`); read-only methods need no credential. Set the system property `turboism.mcp.requireAuthForRead=true` to extend the bearer requirement to every `tools/call`, including read-effect tools. It is not designed for remote access.

### Local data

The plugin writes only its connection metadata, a per-user bearer token, and the stdio bridge source in plugin state storage, each with owner-only permissions. The bounded connection/request history is process-local and is not persisted. On POSIX systems it attempts owner-only permissions. Diagnostic and model resources do not expose raw filesystem paths, native host objects, or image bytes.

`turboism://environment/diagnostics` omits `DiagnosticReport.Problem.path()`, bounds the problem list, converts messages to one line, caps message length, and redacts Unix paths, Windows paths, and `file:` URIs.

### Telemetry

No telemetry is sent by this plugin. Plugin lifecycle and failure records can appear in Turboism's session log and Cubism's host log with the plugin ID attached.

## Status and limitations

- **Status:** Preview.
- The stable default port is `43123`. Set `turboism.mcp.port=0` explicitly for an ephemeral port. `turboism.mcp.requestsPerMinute` is the advanced rate-limit system-property override. `turboism.mcp.requireAuthForRead` (`true`/`false`, default `false`) gates read-effect `tools/call` methods behind the bearer token as well.
- GET SSE, resource subscriptions, list-changed notifications, progress notifications, and MCP Tasks are not implemented.
- Workspace switching and default-layout mutation are intentionally unavailable because the runtime currently gates them with `turboism.host.unsafe`.
- `EditorFileCommandRequest`, import/export, save-as, backup, and other handle-based file workflows remain unavailable until an MCP session can receive a real `UserFileHandle` authorization without accepting raw paths.
- Generic SDK invocation, reflection, arbitrary native members, shell execution, raw paths, dialog automation, and lifecycle-registration APIs are not exposed.
- Performance sampling, canvas/profile, physics/animation, texture pixel ingestion and atlas-layout authoring through MCP, screenshots, and binary resources remain separate future capabilities. Texture-library CRUD is provided by the typed texture tools above.
- Delete is destructive. The default rejects referenced objects; cascade must be requested explicitly.

## Troubleshooting

| Symptom | What to check |
|---|---|
| MCP client cannot connect | Open **Turboism → MCP Connection**, confirm the process is running, and use the exact displayed loopback address. If port `43123` is occupied, stop the conflicting process or set an explicit advanced port override. |
| Request is rejected before dispatch | Check method, origin, session, MCP protocol version, body size, content type, and rate limit. |
| Resource returns `UNAVAILABLE` | Check active document/model state and exact-host capability admission; do not treat it as an empty successful value. |
| Resource returns permission denied | Check the plugin descriptor grant and the specific runtime permission named above. |

## Changelog

### 0.1.0

- Initial release.

## Support and license

- **Project website:** [https://turboism.dev](https://turboism.dev)
- **Publisher:** Turboism Contributors
- **License:** Project License
- **Plugin ID:** `dev.turboism.plugin.mcp`

## Native model rendering

Call `turboism.render.state({})`, then pass its complete `state` as `expectedState` with explicit `width` and `height` to `turboism.render.capture`. Rendering reads the current pose without moving parameters or selection. Runtime validates exact host authorization and GPU readiness; failures return no cached or thumbnail fallback. State discovery alone does not establish renderer availability.

Limits: 1024 per dimension, 1,048,576 pixels, 3 MiB PNG, 2048 parameter metadata entries. These tools are excluded from authoring transactions because image results can exceed the per-step result budget. A changed document/history/pose discards the image. Returned metadata includes capture identity, timestamp, pose, history, dimensions and SHA-256. Pixel coordinates start at the top left; the SDK does not expose a model-to-pixel matrix, so `modelToPixelTransform` is null. Crop, pose-grid and scoped diagnostics are described below; real-host acceptance remains outstanding.

## Stored authoring geometry

`turboism.authoring.meshes.read`, `warps.read`, and `rotations.read` require discovered `documentId`, `modelId`, and `objectId`. The initial call omits `snapshotId` and starts at offset zero. Follow `nextOffset` with the same `snapshotId`; each page contains typed rows from one immutable SDK snapshot. No parameter, selection, or evaluation changes are performed. Source geometry, stored forms, and evaluated geometry remain distinct; these endpoints expose source/stored data only.

ArtMesh `mesh_form` rows also expose `opacity`: a finite native stored value, or `null` when that selector is not admitted or a legacy SDK implementation has no observation. Unknown never means `1`. Stored Morph values are returned without clamping or treating them as absolute ordinary opacity. These observations are included in immutable snapshot equality/CAS, and position/draw-order writers and landmark plans preserve them. This read does not mutate opacity or parameter pose; ordinary opacity writing is separately admitted through keyforms.write; Warp/Rotation opacity and stored colors still require separate contracts.

The `ordinary_write_support` row now includes a closed `operationSelectors` map: ART_MESH_POSITIONS, ART_MESH_DRAW_ORDERS, ART_MESH_VALUES (positions/order), ART_MESH_VALUES_WITH_OPACITY, WARP_CONTROL_POINTS, or ROTATION_FORMS for the observed object kind. The opacity entry independently checks its native selector extension, so geometry PRESENT can coexist with opacity ABSENT. Missing entries mean UNKNOWN; legacy providers return an empty map. These are snapshot-time selector observations, separate from reviewedHost, historySelectors, transactionCoordinator, writePermission and per-form eligibility. PRESENT does not grant a write or bypass default/shared/Morph/edit-state/CAS/budget/transaction checks. Rediscover after state changes; the writer still validates live. Dynamic session views retain these observations and the actual Warp/Rotation subtype through generic deformers.find/all, with the same generation guards.

Rows preserve native source/form/parameter GUIDs, complete axes and cells, shared references, default axis values, and parent identities. Never infer a default form from the first key or treat a display parameter ID as a GUID. Coordinates remain native SDK values; parent transforms and canvas/pixel conversion are not synthesized. Snapshot tokens belong to the requesting MCP session and expire after five minutes. Limits: 16 snapshots, 4MiB serialized rows per snapshot, 16MiB total serialized rows, 100,000 projected rows, 512 rows/240KiB per page. History/document changes require a new snapshot. Tokens retain the full SDK expected state for future CAS writers; they do not authorize writes. Stored writes use the separate keyforms.write contract; real-host acceptance remains pending.

### Ordinary stored keyform writes

Read issued authoring snapshots first. `turboism.authoring.keyforms.write` requires explicit `documentId`, `modelId`, unique `operationId` and `targets`. Each target supplies `kind`, `objectId`, `snapshotId`, and `edits`. Edits use a real `formGuid`, complete parameter-GUID `coordinates`, and full mesh `positions`, warp `controlPoints`, or rotation `form`, according to the live closed input schema. Complete snapshots and parent identities are checked for all targets before any setter and again inside one SDK transaction. Only independent, existing non-default ordinary forms are admitted; default/shared/Morph/topology edits are excluded. Limits: 8 objects, 64 forms, 16,384 points total and 768 KiB. This tool owns its transaction and cannot nest in `transaction.execute`. Rediscover snapshots after success/no-change.

Results include transaction receipts, full snapshot hashes and readback verification. After a lost response, call `turboism.authoring.keyforms.result` with `operationId`. Exact request reuse returns the retained result; conflicting reuse rejects. Treat RUNNING, ROLLED_BACK, OUTCOME_UNKNOWN and APPLIED_WITH_READBACK_WARNING distinctly. A missing result is unknown, not proof of no mutation. Retention is session-isolated, process-local and capped at 256 admitted operations across the server; entries are never evicted to allow replay, and new writes reject when full. Agent cancellation does not prove host rollback. Cross-session/restart recovery, real-host Undo/Redo and save/reopen remain separately unverified.

### Pose scans with mandatory restoration

Pass the complete discovered render state as expectedState to `render.pose_grid`, plus operationId, width/height, poses, timeBudgetMs and restorePolicy:"always". Each pose has a unique label and values:[{parameterId,value}]. Partial poses are relative to the original baseline, never the previous pose. This is a mutating tool requiring model write permission and Bearer, and owns its SDK transaction. It cannot nest in transaction.execute. Even restored values can produce one Undo entry; consult the actual receipt. Rediscover render/authoring state after a scan: restored parameter values do not preserve the old history revision or snapshot validity.

Limits: 9 poses, 6 target parameters, 1,048,576 total pixels, 3 MiB total PNG, 240 KiB result metadata and 128 KiB planning/baseline metadata. At most 60 value writes/restores fit the SDK's 64-item transaction bound. Render state/capture parameter metadata is also bounded to 128 KiB. Duplicate parameter display IDs fail closed because the current SDK list aliases those handles; ordinal is not native GUID identity.

Each frame includes its actual pose state, parameter values, capture ID, PNG hash and imageContentIndex into this response's typed image blocks. Nine poses return nine images. The attachment count increases from eight to nine for XY grids; the 3 MiB PNG, 1 MiB HTTP request, 8 MiB Pi bridge and 256 KiB transaction step-result budgets do not increase.

After a transport timeout use status/result with operationId. Cancel and the maximum 30-second time budget are cooperative between host calls; they cannot forcibly interrupt GPU, setters or restoration. Failures/cancellation still restore and verify the full baseline; document/model/generation drift prevents writes to a new scope. parametersRestored=true means all baseline values were verified in the original scope; false means restoration is unproven. Distinguish FAILED, CANCELLED, TIME_BUDGET_EXCEEDED, RESTORE_UNVERIFIED and OUTCOME_UNKNOWN.

Retention is session-isolated and process-local: two image-bearing scans and 64 operation identities. Explicitly release terminal images after consuming evidence to permit further scans; metadata and replay protection remain. Released results contain no images, have null frame imageContentIndex and imagesReleased=true. Missing results are unknown, and cross-session/restart recovery is not provided. Real-host/GPU, visual-model consumption, quality and save/reopen acceptance remain separate.

## Scoped geometry diagnostics

`turboism.validation.geometry` consumes an issued `authoring.meshes.read` snapshot. Supply documentId/modelId/objectId/snapshotId, includeSource, referenceFormGuid, formGuids, thresholds and maxFindings. Stored comparisons require an explicit ordinary reference form of the same object; source-only uses an empty form list and null reference. Default/shared ordinary forms may be diagnosed; Morph-referenced or non-grid forms reject. Full native snapshot, parent and history identities are checked before and after analysis, including geometry changes without a revision change.

Implemented checks cover empty/unused/duplicate/non-manifold topology, winding consistency, triangle area/aspect, stored-reference winding/edge ratios and positive-area triangle interior overlaps within each selected geometry. Area uses absolute double-area relative to bounding-box diagonal squared; aspect is sum(edge length squared)/(2*sqrt(3)*absolute double-area). Set explicit finite thresholds. Limits: 32 target forms, 200 retained findings, 2M estimated work, 240 KiB result. Truncation or undefined comparisons produce INCOMPLETE; ok means diagnostics ran, never model acceptance. Boundary contacts, cross-object intersections, cracks, evaluated/interpolated geometry, UV/atlas, canvas, masks/occlusion and art quality remain explicitly unimplemented. This tool does not move parameters, selection, render or write.

Interior overlap uses an X-sorted bounding-box sweep and a separating-axis test on exact represented float coordinates. A conservative double roundoff bound selects exact binary-float BigDecimal predicates when needed. Containment, coincident triangles and adjacent fold-over can be warnings; shared edges/vertices or boundary contact alone are excluded. Findings locate triangleIndex and relatedTriangleIndex; indices describe the first triangle, while the issued topology locates the second. Source and each stored form are checked separately, with no cross-object comparison or automatic repair.

The existing 2M estimated-work budget is shared across base diagnostics, sorting, sweep visits and robust predicates. Exhaustion returns intersectionCheckComplete=false, analysisComplete=false, comparisonComplete=false and INCOMPLETE, rather than an all-clear. statistics reports completed intersectionPairsTested, intersectionSweepVisits (including pruned pairs) and estimatedWork. Findings/counts then describe only the inspected portion; reduce the selected form scope before another check. Finding retention truncation and undefined reference comparisons independently remain INCOMPLETE. Exact positive overlap is geometric evidence, not proof of an art defect or whole-model acceptance.

ArtMesh edits in `authoring.keyforms.write` may supply `positions`, `drawOrder` (integer 0..1000), or both with the same discovered formGuid and complete coordinates. A batch can mix these forms of edit. Order-only calls use the typed draw-order SDK writer and preserve vertices; combined/mixed calls use one combined SDK contribution. Omitted fields are filled from the immutable expected snapshot so both SDK lists identify the same forms. Retained positions also count against the 16,384-point budget. Default/shared/Morph protections, cross-object transaction, full readback, operationId and result semantics remain in force. Equal expected/after state skips setters inside the verified transaction; real-host Undo/Redo and visual occlusion still require acceptance.

ArtMesh ordinary edits also accept `opacity` (finite 0..1), alone or with positions/drawOrder. The corresponding stored opacity must be known and within the supported ordinary range. Mixed edits use the closed SDK `ArtMeshKeyformValues` batch, with omitted values retained from the complete expected snapshot; all target vertices, including retained positions, count toward the existing 16,384-point budget. Runtime independently admits the stored opacity read/write selectors, preflights every tuple/form/default/shared/Morph rule before mutation and uses one native keyform contribution. Full readback and existing operationId/result/rollback semantics apply. Existing ordinary_write_support.writerSelectors reports geometry infrastructure, not opacity-selector admission. This is stored ordinary opacity, not a current-pose setter, Morph opacity or automatic eye/mouth fading. Exact-host Undo/Redo, rendering and persistence still require acceptance.

## ArtMesh clipping masks

Call `authoring.masks.read` with documentId/modelId and objectIds including intended targets and proposed mask sources. Current mask dependencies expand transitively (64 objects maximum). The result contains GUID/parent/mask/inversion/history state and a session-issued snapshotId (5-minute TTL; at most 16 snapshots, 64 KiB each). Read does not alter selection or parameters.

Pass documentId/modelId/snapshotId, a unique operationId and replacements:[{objectId,maskIds,inverted}] to `authoring.masks.write`. Up to 8 targets and 128 KiB per request. Every target and proposed source must be in the issued snapshot; all scoped GUIDs/parents/masks/inversion and history are compared before and inside the SDK transaction. A single conditional native mask batch writes both fields; full scoped readback checks non-target objects too. The SDK batch forbids empty replacement lists, so clearing masks is unavailable here; new cycles and self/duplicate sources reject. No geometry edits or automatic visual acceptance.

After response loss, use `authoring.masks.result({operationId})`. RUNNING, APPLIED, NO_CHANGE, ROLLED_BACK, OUTCOME_UNKNOWN and APPLIED_WITH_READBACK_WARNING remain distinct. Exact request replay returns the retained result; conflicting operationId rejects. Results are session-isolated/process-local, capped at 256 admitted operations without eviction; full cache rejects new writes. Missing result is unknown, not proof of unchanged state. Agent cancellation after host admission does not imply host rollback. Real-host Undo/Redo, visual occlusion and persistence remain unverified.

## Last-calculated ArtMesh observation

`authoring.meshes.evaluated.read` takes documentId/modelId/objectId and a same-session meshes.read authoringSnapshotId; optional snapshotId/offset/limit page through one immutable observation. It verifies full stored authoring state, parent/history and the complete native sample on every page; pose/calculated changes reject rather than mixing pages. It never calls model.update, renders, moves parameters or substitutes stored/Core geometry. Native GUID parameters preserve duplicate display IDs.

Freshness is always UNPROVEN: native passEntryTimeMillis marks entry, not successful completion. liveConsumedValuesMatch compares GUID/value maps only; live/consumed updateVersion values are independent signed counters, and interpolatedIdentity is native identity equality. None proves interpolation/evaluation succeeded. A missing calculated form produces no positions. CoordinateType remains a native label, with no asserted units or source/Core/pixel conversion.

Limits: 65,536 positions, 2,048 live and consumed parameters each, 4 MiB immutable sample; 512 rows/240 KiB per page. Shared authoring cache/session/TTL budgets apply; insertion retains the authoring parent without extending its TTL. This enables explicit observations for further diagnostics, not a fresh-frame or visual acceptance claim.

## Source-level object properties

`authoring.properties.read` takes documentId/modelId/kind/objectId/authoringSnapshotId. Kinds are art_mesh, warp_deformer and rotation_deformer. The token must come from the corresponding same-session authoring read. It returns source_object properties: own visible/locked, plus culling for ArtMesh. These are not effective ancestor flags or evaluated/current-form opacity/colors. The issued property snapshot uses the shared authoring TTL/count/byte budgets and retains full immutable native authoring state and parent identities.

`authoring.properties.write` takes documentId/modelId/operationId and up to 8 targets:[{kind,objectId,snapshotId,properties}]. Each nonempty patch accepts only explicitly supported Boolean fields. Full stored geometry/forms, source identity, parents, history and all supported own properties are checked before and inside one SDK transaction and after writing. Unrequested properties and every stored form remain unchanged; equal values skip setters. Explicit locked:false edits only that object's source flag; there is no automatic unlocking or ancestor modification. Requests are capped at 128 KiB.

Use `authoring.properties.result` after a lost response. Results are session/process-local, 256 admitted operations without eviction; conflicting IDs reject and missing means unknown. Agent cancellation is not rollback. Source properties share the exact result/receipt encoding used by mask operations. These tools do not expose Part flags, current-form opacity/colors, blend composition or generic SDK setters. Those need separate identity/readback and form contracts. Real-host visual/Undo/persistence acceptance remains outstanding.

## Session file grants

`files.request_grant({operationId,mode,purpose,allowedExtensions})` requires an authenticated mutation request. Mode is READ or WRITE, purpose is printable text up to 200 characters, and 1–8 extension filters are required. The SDK owns the native chooser and permission checks. No path, native handle or file content is accepted or returned. Poll `files.grant_status({operationId})`; a granted result contains a session/process-only opaque grantId. The typed documents.save_as consumer is available; CMO3 OPEN uses a READ grant; general file content transfer is not exposed.

Requests and grants expire five minutes after admission, without renewal. `files.revoke_grant({operationId})`, session deletion and server shutdown invalidate the bridge capability and release native handles. Late grants are disposed. Revoking a pending request does not prove that the native chooser closed: nativeChooserPending and hostCancellationConfirmed distinguish these facts. Only one native chooser may remain pending, including after revocation/expiry; up to 128 request identities are retained without replay-enabling eviction. Full storage rejects new requests. Status not found does not prove a file operation's outcome. Grant creation has no Undo and does not open/save a model. Real-host chooser and persistence acceptance remain pending.

## Authorized model Save As

`documents.save_as` takes operationId, a same-session WRITE grantId restricted to allowedExtensions=["cmo3"], expectedState (the six-field state from stored authoring reads), and overwritePolicy=REJECT_EXISTING. Document/model/history are checked before grant consumption and again immediately before native dispatch. One-use bridge consumption reports CONSUMED; the native handle remains pinned through the invocation and is released on every completion path. READ, foreign, expired and spent grants fail closed. The operation is an external filesystem effect with no Undo; it cannot run inside transaction.execute.

Use `documents.file_result({operationId})` after response loss. Up to 128 operation identities are retained without eviction, isolated by session; identical replay never dispatches again. UI queue admission respects MCP cancellation, while an admitted native call is awaited to its actual result. EXECUTED records native SAVE_AS success, including possible no-op; FAILED/exception after dispatch yields OUTCOME_UNKNOWN. Readback changes produce STATE_READBACK_WARNING. persistenceVerified and reopenVerified remain false: no byte/hash or reopen validation is implied. CMO3 OPEN is limited to exact 5.3.03; PSD import remains unavailable. Replacement is excluded until a user-visible overwrite-approval contract exists; the ordinary file chooser alone does not establish it.

## Authorized file observation

`files.inspect({operationId,grantId,maxBytes})` consumes a same-session READ grant and returns immediately; poll `files.inspect_result({operationId})`. maxBytes is explicit, 1..134217728. The SDK may report RUNTIME_UNAVAILABLE above 16 MiB on older implementations. The single global I/O/hash slot remains occupied until native read/hash completion; the SDK can transiently allocate multiple buffers. Results retain only small metadata, never paths, filenames or file bytes. Up to 128 operation identities are retained without replay-enabling eviction; identical replay does not read again. Grant consumption requires Bearer and is not transaction-eligible, even though it performs no filesystem write.

COMPLETE returns byteLength and SHA-256 only after a nontruncated read. TRUNCATED returns no hash. RUNNING/FAILED remain distinct. Hashing uses a dedicated worker outside registry locks; session deletion/server close discard late results and release handles after actual completion, without pretending native cancellation. fileStability=UNPROVEN: the SDK does not guarantee an immutable view while another process changes the file. documentAssociation=UNVERIFIED: a newly selected READ grant is not proof that the file is the SAVE_AS destination or matches model geometry. Compare observed hashes for evidence, then separately validate file identity, format and reopened model; this tool does not set documents.save_as persistenceVerified/reopenVerified.

`turboism.rig.rotation.plan` is a read-only building block for semi-automatic rigging. Supply an issued rotations.read snapshot, proposed write operationId and policy=REPLACE_ANGLE_FROM_DEFAULT. Choose either the legacy single-axis parameterGuid/nativeAnglePerParameterUnit pair, or axes with 1..3 explicit {parameterGuid,nativeAnglePerParameterUnit} entries covering every ordinary axis exactly once. Each axis requires at least two unique in-range keys and an exact default; the complete independent Cartesian grid is limited to 65 cells/64 non-default edits. Target angle = all-default stored angle + sum((cellKey - axisDefault) * gain), summed in snapshot axis order and rounded once to float. This replaces existing target angles and preserves each form's pivot/scale/reflections, default form, baseAngle, axes/cells and unrelated forms. Axis and cell coordinate identity uses GUIDs, not display IDs or input ordering. This is additive stored-angle planning, not composition of rotations or semantic XYZ/pivot inference; no native unit conversion. Preview is bounded to 128KiB. Complete native state, parent and history are checked before and after planning. Returned keyforms.write requires separate write admission and fresh CAS; no setter, pose movement, render or transaction occurs during planning. Evaluated interpolation, head volume/eye/mouth coordination and visual acceptance remain unverified.

Stored ArtMesh/Warp/Rotation pages now include `ordinary_write_eligibility` rows before geometry rows. Each has formGuid, snapshotEligible, reason and hostWriteAdmission=UNPROVEN. Reasons distinguish default/shared/Morph-referenced/unreferenced forms, missing default keys/cells, duplicate cells and shared defaults. The classification shares ordinary writer grid logic; it does not test host write selectors, permissions, edit mode, payload budgets or transaction admission. A malformed grid blocks all targets but remains readable. Metadata belongs to the immutable issued snapshot; pages do not reread native geometry. The write tool must still perform live CAS and native admission. No new tool is added.

Stored pages also include `ordinary_write_support`: hostVersion and UNKNOWN/PRESENT/ABSENT observations of reviewedHost, writerSelectors, historySelectors, transactionCoordinator and writePermission. New Runtime object views query existing selector authorization metadata without invoking setters or starting Undo; permission wrappers require model.read and report model.write independently. SDK defaults and unsupported legacy queries preserve UNKNOWN. These observations are captured with the issued snapshot, not renewed on later pages, and do not prove edit mode, default/form scope, payload/CAS or transaction admission. Even all PRESENT must not be interpreted as a successful write or exact-host acceptance. Per-form hostWriteAdmission stays UNPROVEN.

`turboism.rig.warp.plan` plans an existing complete, independent Warp ordinary grid with one to three explicit axis GUIDs. Each axis must have an exact default and declared keys on both sides; at most 65 cells. Supply 1..16 negative/positive endpoint anchors per axis (`controlPointIndex`, `dx`, `dy`, native stored coordinates). Controls use normalized inverse-square-distance interpolation; exact landmarks reproduce the requested displacement. Each key scales the field by its distance from default relative to the declared extreme key. Axis fields add. Optional `cornerCorrections` specify two or three distinct bound GUID/NEGATIVE-or-POSITIVE directions and 1..16 residual displacement anchors. Up to 20 unique side maps (12 pair quadrants + 8 triple octants for three axes) are allowed. Residual weights multiply only the declared axes' normalized side fractions, zero on any declared default/opposite side and one at the matching endpoint corner. Pair residuals extend across all third-axis keys; triple residuals vanish on any default plane. These are explicit stored-key corrections, not inferred perspective or evaluated interpolation. `REPLACE_CONTROLS_FROM_DEFAULT` replaces existing target controls, preserving default, dimensions, quadTransform, axes/cells and unreferenced forms. Max 64 edits/4096 planned points/240KiB output. Full native/parent/history checks run before and after planning; ordinary writer preflight is reused. Returns keyforms.write for separate permission/transaction/live CAS. It neither writes nor proves anatomical, perspective, foldover, continuity or visual quality. Replanning is stable, not cumulative.

`turboism.validation.warp` takes an issued warps.read snapshot, explicit ordinary referenceFormGuid, 1..32 formGuids, minimumAbsJacobianRatio and maxFindings. Optional proposedEdits use the existing ordinary writer preflight without committing; only selected targets may change, never the reference. Compare stored or proposed row-major control cells using four exact binary-float bilinear corner Jacobian determinants. Mixed signs flag local folding; corner abs(det)/cell bounding-box diagonal squared at or below the supplied threshold flags degeneracy. Regular cells compare orientation against the explicit reference; a mirrored regular reference is valid. Findings locate row/column and all four control indices. Irregular reference/target cells skip orientation comparison and comparisonComplete=false. Default/shared ordinary forms may be observed; unreferenced forms are rejected. Budget: 8192 cell samples including reference, 200 retained findings, 240KiB result; over budget returns no verdict. Truncation retains total counts. This is a bilinear control-grid proxy even with quadTransform=true: native interpolation, global overlaps, evaluated geometry, parent transforms, pose continuity and visual quality are omitted. Native state/parent/document/history are checked before and after; no write, render or transaction. Submit separately through keyforms.write with fresh CAS after review.

For a rig.warp.plan result, pass the selected target’s edits as proposedEdits and derive formGuids from those edits, rather than every form in the snapshot (which can include unreferenced forms). Choose the actual unchanged ordinary reference from the snapshot. Diagnostics do not authorize the subsequent write.

`turboism.rig.mesh.plan` uses an issued meshes.read snapshot and one to three explicit ordinary axis GUIDs on a complete independent grid, with an exact default and declared keys on both sides (max 65 cells). Each axis supplies 1..16 negativeAnchors/positiveAnchors (`vertexIndex`, `dx`, `dy`), using native stored positions and vertex order, never source/image/evaluated coordinates. Optional cornerCorrections use the same two/three-GUID NEGATIVE/POSITIVE directions and up to 20 unique pair/triple residuals as Warp. The shared inverse-square landmark engine scales each axis field from default to declared endpoints; residuals multiply only declared side fractions. Pair corrections extend across the third axis, while triple corrections vanish on any default plane. `REPLACE_POSITIONS_FROM_DEFAULT` replaces existing non-default target vertices without accumulating on replanning. Preserves source positions/UV/triangleIndices, stored draw orders, default, axes/cells and unrelated forms/Morph records. The mesh ordinary writer preflight rejects shared/default/Morph-referenced targets or reference; planning does not promise host write admission. Max 64 edits/4096 planned vertices/240KiB output; full native/parent/history checks before and after. No topology edit, inferred anatomy/perspective, rendering, pose movement or transaction. The returned keyforms.write request still needs separate admission and fresh CAS.

`validation.geometry` also accepts optional positions-only proposedEdits. Pass the mesh planner target edits directly and derive formGuids from those edits; select an unchanged explicit ordinary reference. Preflight constructs a detached proposed snapshot, rejects reference or unselected edits, and preserves source/UV/indices/draw order. The output reports proposedEditCount; original snapshot state is checked before and after, never written. Existing geometric checks, budgets, truncation/INCOMPLETE semantics and omitted visual/evaluated checks remain. A successful read or empty findings is not modeling acceptance.

`render.capture` and `render.pose_grid` accept optional `crop:{x,y,width,height}` in native raster pixels (top-left origin, x right, y down). Input width/height remain the full native raster dimensions, at most 1024 each; cropping does not change rendering or increase resolution. Crop must be a positive rectangle entirely inside that raster; invalid crops are rejected before rendering, or before pose writes in a scan. PNG/frame width/height describe the cropped output. renderWidth/renderHeight describe the source raster, crop records its rectangle; source pixel = output pixel + crop origin. Without crop, this metadata explicitly describes the full raster. modelToPixelTransform remains null; no inferred conversion to stored geometry is provided.

Pose scans use the same crop for every pose and retain source mapping with each typed image, including partial-failure evidence and restored results. Changing crop under an existing operationId is a conflict; replay/result returns retained pixels without a new render. Native per-frame/total pixel and time budgets still count the full raster; PNG byte budgets count returned crop bytes. This supports local eye/mouth comparison without claiming visual acceptance, native zoom or real-host verification.

`turboism.validation.raster` inspects PNGs retained by a terminal, unreleased pose-grid operation in the same MCP session. It never reads the live model or recaptures an image. Supply operationId and 1..32 unique checks with frameIndex, an explicit region (x/y/width/height in the cropped PNG, top-left origin), and inclusive minimumFraction/maximumFraction. color_fraction additionally requires minimumRgba/maximumRgba arrays in R,G,B,A order, with inclusive byte bounds; select alpha explicitly to exclude transparency/background. frame_difference requires referenceFrameIndex and byte tolerance: a pixel matches when any premultiplied-sRGB RGB or alpha channel difference strictly exceeds tolerance. Coordinates must have identical full raster/crop metadata; no registration or resampling occurs. Fractions use all ROI pixels as the denominator, with exact count-to-threshold comparisons. At most 4,194,304 ROI samples, 1,048,576 decoded pixels, 3MiB original PNG and 240KiB report. PNG byte length/SHA-256/actual dimensions are verified before use. Reports contain check configuration/counts/fractions/pass flags and checked frames' captureId/hash/pose/state/crop, with null imageContentIndex and no image/base64. checksPassed only covers the explicit thresholds; ok means the analysis ran, even when checks fail. Partial terminal scans may be inspected, while scanOutcome/parametersRestored/allRequestedPosesCaptured remain separate. Retained images do not prove current model freshness. Running/released/missing frames fail closed. This supports explicit eye-white/color-area/difference evidence where a caller has supplied meaningful ROIs and thresholds; it does not infer eye/mouth semantics or certify artistic quality.

The same `rig.warp.plan` also accepts `policy: "PROJECT_HEAD_VOLUME_FROM_DEFAULT"` with mutually exclusive `headVolume:{centerX,centerY,radiusX,radiusY,depth,cameraDistance}` and `axes:[{parameterGuid,component,degreesPerParameterUnit}]`. Cover all actual ordinary GUIDs exactly once with unique YAW/PITCH/ROLL components; no landmark/cornerCorrections fields are allowed in this mode. The profile uses native stored parent-space coordinates, with positive depth toward the virtual camera. For each stored default control, let u=(x-centerX)/radiusX, v=(y-centerY)/radiusY and z=depth*sqrt(max(0,1-u²-v²)); outside the ellipse z=0. Camera distance d lifts default 2D points along their rays: X=(x-centerX)*(d-z)/d, Y likewise. Apply mathematical right-handed yaw about +Y, then pitch +X, then roll +Z, around center/z=0, using (cellKey-axisDefault)*gain degrees; project centerX+d*X'/(d-Z') and similarly Y. This ray lift preserves the default view; zero-angle points are copied exactly. This is an image-defined depth approximation, not a reconstructed 3D ellipsoid or inferred anatomy. Radii must be positive, depth nonnegative, cameraDistance>depth with cameraDistance-depth at least 1% of cameraDistance. Per-cell yaw/pitch are limited to ±85°, roll to ±180°; reject initial/projected camera denominators below 1% of d and nonfinite/native-float overflow. Existing ordinary grid/default/shared and 65 cells/64 edits/4096 points/240KiB limits remain. The mode replaces each target from default rather than adding to existing rig geometry, preserves default/grid/quadTransform/axes/cells/unrelated forms and returns cornerCorrectionCount=0. Preview and `validation.warp` proposedEdits are read-only; actual writes require separate fresh CAS/authorization. The caller must confirm the head Warp, coordinate space, profile, axis signs, parent/child suitability and rendered poses; child interpolation, occlusion, eye/mouth coordination and artistic acceptance are not established by this template.

`turboism.authoring.keyforms.preview` accepts the exact `keyforms.write` request. It shares closed typed preflight, independent ordinary form restrictions, full native/parent/history CAS and aggregate budgets; it rechecks state after computing compact per-object/form differences and before/expected-after hashes. Reports requested/changed forms and points, draw order/opacity/reflection changes, maximum native point/origin displacement, absolute native angle delta (no wrapping/unit conversion) and scale delta. No-op forms retain empty changedFields. Distances belong to each object’s native stored space. Limits: 8 targets, 64 forms, 16,384 budgeted points (including retained positions in opacity/combined batches; order-only counts zero points), 768KiB input, 240KiB preview. `applied=false`, `readbackVerified=false` and `writeAdmission=NOT_CHECKED`: no transaction lookup, setter, pose/render, operation reservation or snapshot invalidation. Repeated preview is stable while state is unchanged; an already admitted operationId must use result lookup. Submit the reviewed request separately: write performs fresh CAS and live admission. Preview is neither write authorization nor geometry/visual acceptance.

`turboism.assets.binding.read` requires documentId/modelId/objectId and an issued meshes.read authoringSnapshotId. It reports source locked state, the exact authored textureInput (null only for truly unbound), and up to 1024 unique registered model images with id/name/dimensions/groupIndex. Atlas-region input is a separate kind, never a model-image guess. This is metadata/identity, not image pixels, source-layer semantics or proof of GPU sampling. Native hierarchy-lock/extension/host admission is authoritative.

Use the returned binding snapshotId with `turboism.assets.binding.bind`, an unused operationId and a discovered modelImageId. Full native geometry/forms/source, parents, lock, exact previous input (including native model generation), candidate metadata and history are compared before and inside an owned SDK transaction. Unbound unlocked ArtMesh with no parent deformer only; same-image input is a verified NO_CHANGE without calling native bind. Different images/atlas inputs are rejected; no overwrite/unlock/reparent. The SDK binding handle and exact textureInput must agree on model/image/mesh/generation, with geometry/UV/forms/parents/catalog preserved. In-transaction failures roll back. Native per-host permissions/Undo/extension limits still apply. Snapshot validity uses the shared 5-minute/16-entry/16MiB authoring cache, including retained native state; read output max 240KiB. No file grant, path, upload or pixel bytes are accepted here; authorized PNG registration uses separate assets.images.register jobs.

`assets.binding.result` returns retained historical evidence without host reads. Max 256 admitted operations per server, no eviction; identical requests replay without a new bind, conflicts reject. A missing result does not prove unchanged host state. Post-commit readback failure is APPLIED_WITH_READBACK_WARNING; lost response/recovery failure is OUTCOME_UNKNOWN. Neither is safe to retry blindly. Successful responses include native textureInput and a transaction receipt. Hashes cover document/model/mesh/source GUID and input kind/resource IDs, excluding generation/geometry (separately CAS checked). The successful binding token is invalidated; reread for another operation. This tool owns its transaction and is not eligible for transaction.execute. Rendering, visual acceptance and actual host Undo/Redo remain independent gates.

`turboism.assets.images.register` completes the PNG route from user-authorized bytes to a registered raw image/group/model image and ArtMesh binding. First discover the target with meshes.read and assets.binding.read; request a same-session READ grant with allowedExtensions=["png"]. Submit documentId/modelId/objectId, the binding snapshotId, grantId, unused operationId, name (1..128 nonblank/control-free characters), maxPngBytes (1..16MiB), maxPixels (1..16777216) and optionally lowercase expectedSha256. No filesystem path, attachment path, inline base64, overwrite or automatic unlock is accepted. Native source/group/image names use the explicit name, never the chosen filename.

Start returns immediately with a job; poll assets.images.result through QUEUED/PREFLIGHT/READING/VALIDATING/HOST_PENDING/HOST_RUNNING/TERMINAL. Model preflight occurs before the grant is consumed. Actual nontruncated bytes must satisfy header/pixel budgets (each dimension <=8192); the immutable authorized content is hashed, and Runtime validates native decode/dimensions. Failed/truncated/hash-mismatched reads never call registration. File evidence is not an immutable disk snapshot: fileStability=UNPROVEN. Native admission rechecks token TTL and complete geometry/forms/source/parents/lock/input/catalog/history CAS. The single owned SDK transaction must append exactly one raw image/group/model image with the declared name/dimensions and bind the intended model/mesh/generation, preserving old metadata and all native geometry/UV/forms. Failures roll back or report recovery failure. Read now exposes groupCount so new empty groups cannot escape the metadata checks; native extension/hierarchy/host/permissions/Undo still fail closed.

One global job holds the I/O/worker/host slot until actual completion. Max 128 operation IDs, no replay-enabling eviction; exact requests replay, conflicts reject. Pending reads are not cancelled; their lease closes only at actual read completion. Explicit assets.images.cancel prevents native admission, including the final transaction boundary; after hostAdmitted it records intent while retaining the actual commit/rollback/unknown result. Agent Abort/disconnect/server shutdown do not prove host cancellation. Session close blocks pre-admission work and removes result access; a missing result means unknown model outcome. Successful writeResult carries the actual rawImageId/textureInput, native receipt and full metadata hashes; fileEvidence is SHA-256/byte count/dimensions of exactly the immutable authorized content. Neither geometry/pixels nor disk stability are covered by metadata hashes; geometry has separate full CAS. Post-commit readback failure is APPLIED_WITH_READBACK_WARNING; a lost native response is OUTCOME_UNKNOWN, never a blind retry. Jobs retain no PNG bytes in result storage, cannot join transaction.execute, and do not prove GPU rendering, real-host Undo/Redo, save/reopen or complete PSD import.

The production catalog now spans multiple tools/list pages (50 per page). Follow nextCursor; Pi discovery tests include 80 tools and execute the second-page result/cancel tools.

## Complete rectangle atlas jobs

Use `turboism.atlas.read` with discovered documentId/modelId and explicit maxTotalPixels (1..67108864). The server retains the opaque native target for five minutes, up to 16 snapshots per server, bound to the MCP session and full document/model/history/native planning state. No target or filesystem path is returned or accepted. Temporary native packing invocations and polygon layouts are unavailable in this route.

`turboism.atlas.plan` accepts snapshotId, operationId and policy `SHELF_HEIGHT_DESCENDING`. Its applyRequest can be inspected and sent to `turboism.atlas.apply`; planning performs no writes or operation reservation and claims no optimal packing. Apply also accepts a complete explicit rectangle plan: every issued image once, original dimensions, no rotation/scaling, explicit unique page names/sizes, exact margins/padding and page/pixel budgets. Limits: 512 images, 32 pages, dimension8192, 8192 total outline vertices, 240KiB projection/request. Outlines participate in CAS but are not used for packing.

Apply is asynchronous; poll `turboism.atlas.result`. One global active job and 256 retained operation identities prevent blind replay. `turboism.atlas.cancel` prevents native admission only; cancellation/disconnect/stop cannot claim admitted host work was cancelled. Native apply owns standalone atlas Undo; this is not transactionEligible, has no SDK transaction receipt and promises no MCP rollback. Exact Runtime admission remains authoritative. Provider failure/rejection or response loss is OUTCOME_UNKNOWN; a successful apply with failed readback is APPLIED_WITH_READBACK_WARNING. Normalized layout hashes/readback verify metadata only: ArtMesh texture bindings, pixel sampling, real Undo/Redo and save/reopen still require host acceptance.

## Authorized MOC packages

`turboism.export.moc.plan` accepts documentId/modelId, operationId, a same-session WRITE grantId with exactly ["zip"] filter, explicit writePolicy=REPLACE_AUTHORIZED_FILE, all native export settings (originX/originY/pixelsPerUnit and the five inclusion/edge booleans), and budgets (maxMocBytes64..67108864, maxArchiveBytes1..134217728, maxTotalPixels1..33554432). It normalizes native float settings, checks populated atlas metadata/Core inspection availability, and returns an exact executeRequest. Planning neither invokes export/Core byte inspection nor consumes the grant; writeAdmission remains NOT_CHECKED. Plans last5minutes, maximum16 retained. Source identity covers history and atlas metadata/order, not a full immutable geometry snapshot.

Send the issued executeRequest unchanged to `turboism.export.moc.execute`; poll result, and use cancel only to prevent file-write admission. The asynchronous job rechecks source/history, calls official MOC5.3 export, requires exact settings and native texture order/dimensions, and requires admitted Core inspection to report V5_3/CONSISTENT. It encodes top-left ARGB to PNG, preserving MOC texture indices, and creates model.moc3, textures/texture_NN.png, model.model3.json (static relative Moc/Textures references) and export-manifest.json (settings/source/hash/size metadata). No arbitrary archive paths or inline bytes. Physics, motions, expressions, pose, display info and blink/lipsync groups are explicitly omitted; fullCharacterDelivery/playerLoadVerified=false. This is an export foundation, not full character acceptance.

Before writing, recheck TTL/history/atlas metadata and consume the one-use ZIP WRITE grant. REPLACE_AUTHORIZED_FILE explicitly permits atomic replacement of that user-selected target. New SDK writeBytesAtomicBounded supports explicit budgets up to128MiB in Runtime; the original writer retains its8MiB limit. The compatibility default delegates at most8MiB and fails closed above it. SDK native MOC/texture allocations occur before tighter MCP post-return budgets; encoding also requires temporary pixel/PNG/ZIP copies. One global job,128retained identities; exact replay never exports/writes again. Pending writes retain their lease/global slot until real completion, including cancel/disconnect/stop. WRITTEN means SDK atomic replacement reported success; false means NOT_WRITTEN, exceptional/lost completion OUTCOME_UNKNOWN. There is no Undo/automatic retry, file reread or player acceptance guarantee. Results contain only hashes/metadata, no ZIP/base64/paths/handles.

### Parameter organization plans

`parameters.structure.read` requires documentId/modelId and reads whole bounded folders, actual mixed folder/parameter child order, definitions and nullable Combined flags/reciprocal partners. Limits:128 folders,512 parameters,192KiB structural data; no current values, bindings, geometry, pose or selection changes. Duplicate display IDs and inconsistent trees/pairs fail closed. Each parameter row now includes its opaque native `guid`; identity scope is parameter GUIDs plus unique display IDs, full structural fields and six history bindings. Folder identity still uses display IDs/fields, **not native folder GUID CAS**. Same-display-ID parameter recreation with a different GUID rejects stale plans. Duplicate native parameter GUIDs and unavailable GUID readers fail closed; no ID/index fallback. Unknown Combined metadata remains null; it is not permission to move or pair that parameter.

`parameters.structure.plan` takes snapshotId and up to16 sequential closed edits: create_group (under root only), rename_group, remove_group, move_parameter, combine, uncombine. Create supplies ref; later edits may use `{"createdRef":"eyes"}` instead of existing-folder `{"id":"discovered-id"}`. The returned `~created-group:` IDs are provisional, never native inputs. Creating then immediately deleting that folder is unsupported, as are group reparent/reorder operations. Move uses explicit post-removal mixed-list insertIndex; uncombine before moving paired parameters or splitting a pair. Combine supports unpaired NORMAL parameters in one folder; the same existing pair is a no-op.

remove_group **deletes all descendant folders and parameters/rig data**. expectedGroupIds must exactly disclose the pre-order subtree including itself; expectedParameterIds must exactly match its parameters in model order. This is not a rig-preserving folder removal. Preview writes nothing and returns writeAdmission=NOT_CHECKED, expectedStructure, effects and hashes. Snapshots/plans share the source5min expiry,16 retained each.

Apply documentId/modelId/planId/operationId inside one owned SDK authoring transaction, with source/history checks and per-step structural readback; exact native folder/move/Combined/group-undo admission remains required. This tool cannot nest in transaction.execute and does not change legacy apply eligibility. Results include receipt, readbackVerified, rollbackStateVerified, observed createdGroups and actualAfterHash. Expected hashes use provisional IDs, actual hashes use discovered IDs. Rollback verification covers structural fields, not restored binding/form contents; created IDs in failed/unknown results may no longer exist.256 operation IDs retained without eviction; result and exact replay read no host. Query after timeout, never blindly replan/rewrite; stopping bridge waiting does not cancel a native commit. No restart recovery or live-host acceptance claim.

### New source/default meshes

`authoring.meshes.triangulate` previews detached native triangulation with explicit document/model, CANVAS geometry and normalized UVs. Returned positions may be merged/reordered; no UV correspondence is inferred or returned, and no model/selection is changed.

`authoring.meshes.create_plan` takes 1..8 meshes with name, existing parentPartId, nullable modelImageId, sourceGeometry and separate defaultPositions. Source/default share vertex count/order; UVs are [0,1]; empty, duplicate and degenerate faces are rejected. Limits:4096 vertices/24576 indices per mesh,16384/65536 total,768KiB request. Parent ancestry must be visible/unlocked. Null image deliberately creates an unbound mesh for later authorized PNG registration; it is no visibility claim. The plan captures all existing native mesh source/stored snapshots/GUIDs/parents/locks/image bindings, declared Part fields and image metadata/history. Part identity uses IDs/fields/history, not native GUID CAS. Max512 meshes/512 Parts/128 image groups/1024 images/262144 source+stored points/4MiB encoded state. Native allocation precedes post-return checks. Max16 plans/5min TTL, roughly64MiB encoded state plus native DTO overhead. Write admission is not checked by planning.

`authoring.meshes.create` uses planId+operationId inside one owned SDK transaction for batch creation and optional image binding. It checks full declared state, actual IDs/GUIDs/default forms, source/default/UV/topology and preservation of existing scoped objects. Returned default draw order/nullable opacity are observations, not rendered visibility. `create_result` and exact replay perform zero host reads;256 operation identities without eviction or restart recovery. All createdMeshes rows are historical observations: verified=true means readback succeeded then, not that the ID survives rollback. Use overall outcome/readbackVerified/rollbackStateVerified. expectedAfterHash is the request payload hash. Unknown outcome or committed readback warning is not retry-safe. Bridge cancellation only prevents admission; no native cancel or transaction.execute nesting. Real-host render/Undo/Redo/save/reopen acceptance remains pending.

### Document identity and guarded creation

`documents.read({})` works with empty/no-document sessions. It returns bounded path-free project/document/content identity metadata and active document; active model authoring/history binding is nullable when unavailable. Names are user-visible; no paths, resource pixels or native handles. It checks declared fields twice, not full model geometry or native document GUID identity. Max128 documents/128 contents/256 associations/192KiB,16 session snapshots/5min TTL; native snapshot allocation precedes budget checks. The content projection is not a full asset inventory.

`documents.new_model({snapshotId,operationId})` checks captured metadata/active binding twice and live NEW_MODEL command availability before one typed dispatch. Native defaults/UI apply; no arbitrary command, path, name or canvas arguments. READY requires exactly one newly observed active MODEL document with a unique new model ID and usable authoring/history binding, with prior document metadata/order and content metadata preserved. It proves identity admission readiness only, not existing full model contents, canvas configuration, pixels, rendering or persistence. EXECUTED without a confirmed new target becomes EXECUTED_NOT_READY/isError=true; FAILED, lost response or unreadable post-state is OUTCOME_UNKNOWN. Native dialogs may need user interaction followed by fresh documents.read, never another create as a readiness probe.

`documents.new_result` and exact replay read no host state and never dispatch again;128 retained identities without eviction/restart recovery. Results are not live polls of later UI completion. Bridge cancellation prevents admission only; no native cancel/Undo/transaction nesting. OPEN dispatch has its own limited contract below; first-open readiness must be rediscovered. Real-host document creation acceptance remains pending.

### Initialize an ordinary parameter grid

`authoring.bindings.initialize_plan` takes documentId/modelId, kind (`art_mesh`, `warp_deformer`, `rotation_deformer`), objectId, an issued authoringSnapshotId, and 1..3 axes with explicit parameterId/parameterGuid/keys. Only a zero-axis source with one cell/form is admitted. Axes must be ordinary, nonrepeat, noncombined, currently at default; 3..9 distinct finite keys must include the exact default and values on both sides within range. Duplicate parameter display IDs/GUIDs are rejected. Limits: 27 Cartesian cells, 4096 source/control points, 32768 total stored samples; 512 parameter definitions/values and 240KiB metadata; 4MiB initial encoded state, 16 plans/16MiB total/5min TTL. The source snapshot's original TTL still applies. Planning neither changes pose/selection nor proves write admission.

`authoring.bindings.initialize({documentId,modelId,planId,operationId,target})` must echo the returned `plan.target` unchanged, making actual object/source/parameter/key scope visible in permission UI. It owns one SDK transaction, checks full native source/parent/lock/edit-level/history and all parameter GUID/definitions/values before and during writes, and calls value-based binding without invented point IDs. After each axis, readback must contain the exact complete Cartesian tuples, independent form GUIDs and unchanged original form content (mesh positions/draw order/nullable opacity, Warp controls, or complete Rotation value), including the default tuple. Source geometry/grid/baseAngle remain identical. Shared/missing/extra cells or changed content cause rollback; original form GUID placement is not assumed. Runtime exact-host/hierarchy/extension/permission/Undo admission is authoritative. Native allocation can precede MCP readback budgets; no real-host acceptance is claimed.

Only successfully verified final results return actual form GUID tuples. Re-read authoring snapshots and binding resources before rig edits; tuples are not writable snapshot tokens. Hashes cover document/model/object/source identity and stored projection, not whole-model state or rendered pixels. `initialize_result({operationId})` and exact replay read retained results without host reads; max128 admitted operations, no eviction or restart recovery. Missing/unknown/warning is not safe blind retry. Cancellation only prevents bridge admission; this is not transactionEligible or a native cancellation API. Rendering, geometry quality, Undo/Redo and save/reopen on a real model remain separate acceptance gates.


### Asset sources and layer relationship metadata

`turboism.assets.sources.read` takes documentId/modelId and `query:{artMeshIds:[],modelImageIds:[]}` (at most64 unique IDs per kind). An empty query reads binding metadata only. `turboism.assets.relations.read` reads the bounded full relationship projection and accepts no query. Both use typed texture metadata reads only: no PSD opening, pixels, file paths or mutations. Missing explicit anchors emit unresolved_anchor. currentRawImageId is distinct from linkedRawImageIds candidates; missing current metadata remains unresolved without candidate fallback. rawLayerId is scoped by rawImageId; nullable numeric psdLayerId is a separate identity. usingArtMeshIds can include inactive inputs; nullable currentInputIndex or empty inputs do not prove an unbound mesh. UNKNOWN source kind and transform/clipping availability are preserved without invented matrices, clipping values, left/right or facial semantics.

First read omits snapshotId with offset=0; later pages repeat the same session/tool/query/snapshotId. limit defaults128, range1..256; pages are capped240KiB and may contain fewer rows. Two typed reads must agree on projected data/native binding/generation; native observation revision may advance but never decrease. It is an observation sequence, not a history revision or write CAS. Cached pages are immutable historical metadata: subsequent pages check active document/model/history binding without rereading native relations or proving current freshness/whole-model atomicity. Non-renewing5min TTL,16 entries/16MiB total,4MiB complete encoding per entry;1024 raw images,2048 model images/meshes,256 groups,8192 layers/depth64,16384 links,32768 rows,192KiB per row. Native DTO allocation precedes MCP budget checks. These snapshots are not write tokens, file grants or visual acceptance.


### Ordinary keyform secant-slope diagnostics

`turboism.validation.keyform_continuity` takes a same-session authoring snapshotId, documentId/modelId, explicit kind/objectId, thresholds (maximumPositionSlopeChange/maximumAngleSlopeChange/maximumScaleSlopeChange, finite0..1e12) and maxFindings1..200. Requires a complete Cartesian grid of1..3 axes,3..9 distinct in-range keys each, at most27 cells/forms,4096 points/form and32768 stored samples. Actual keys are sorted and matched by parameter GUID; all adjacent triples on every axis line are checked with the remaining full tuple fixed. Missing/duplicate tuples, orphan forms, unsupported/over-budget grids fail without a verdict. Default/shared ordinary forms may be read; ArtMesh Morph snapshots are rejected, Warp/Rotation snapshots do not describe Morph combinations.

Each secant is delta stored value divided by (delta key / full parameter range); compare right minus left secant. Mesh vertices, Warp controls and Rotation origins use the Euclidean norm; Rotation angles use raw native degrees without shortest-angle wrapping, scale uses the raw scalar, and reflection changes always warn. Caller thresholds are explicit, not aesthetic defaults. Reports include actual axes/ranges/keys, three form GUIDs, full center tuple/key values, pointIndex or Rotation component, observed value and threshold. Inapplicable angle/scale maxima are null. analysisComplete=true requires all scoped comparisons; maxFindings only caps retained rows and findingsTruncated remains explicit.

Optional closed proposedEdits accept positions/controlPoints/full Rotation form and full GUID coordinates, reuse ordinary writer preflight, and cannot edit default/shared forms; nothing commits. Native full snapshot/parents/history are checked before and after analysis, without pose/selection/render/write. Request768KiB/result240KiB; native read allocation precedes algorithm budgets. Intentional curvature/reflection may warn; no findings only means these implemented stored-slope checks did not exceed thresholds. Native C0/C1 interpolation, between-key geometry safety, mixed derivatives, drawOrder/opacity, parent deformation and visual acceptance remain omitted.


### Explicit transfer of existing rig geometry

`turboism.rig.transfer_geometry.plan` generates a target-only keyforms.write request from two distinct objects of the same kind. Supply documentId/modelId/operationId, kind, policy=REPLACE_FROM_SOURCE_DEFAULT_DELTAS, source/target objectId/snapshotId, and axes mapping every sourceParameterGuid to a unique targetParameterGuid with complete sourceValue→targetValue key pairs. Exact defaults must map to defaults. No display-ID/nearest-key/name inference, resampling or binding creation. Both snapshots require complete1..3-axis Cartesian grids,2..65 keys per axis, at most65 cells/forms each and no orphan forms. ArtMesh Morph snapshots are rejected; Warp/Rotation snapshots do not describe Morph combinations. Source shared ordinary forms may be read; targets retain ordinary writer default/shared protection.

Mesh/Warp require equal point counts and an explicit full permutation sourcePointIndices[targetIndex]=sourceIndex. With an explicit invertible deltaTransform={xx,xy,yx,yy}, finite coefficients within +/-1e6, target points become targetDefault+matrix*(sourceForm-sourceDefault). Target default/source geometry/UV/topology/bindings/drawOrder/opacity are preserved; point correspondence and parent coordinate compatibility are caller decisions. Rotation instead requires angleGain within +/-1000 and reflectionPolicy=RELATIVE_XOR: add scaled raw angle delta to targetDefault, transform origin delta, multiply targetDefault scale by sourceForm/sourceDefault scale, and XOR the three reflection bits. Component transfer is not affine/rotation composition; angleGain is independent of matrix determinant.

All nondefault target forms are replaced by this policy, not merged with existing rig values. Both full native snapshots/identities/parents/history are checked before and after planning. Output state/default identities document that observation. sourceReadScope=VERIFIED_AT_PLAN_ONLY: generated values are historical; subsequent write CAS guards only target, not later source changes. Max4096 points/form,262144 combined stored samples,4096 planned samples (one form/origin sample per Rotation),768KiB request/240KiB output; native allocation precedes checks. Read-only, no operation reservation, snapshot invalidation, transactions, writes, pose/selection/render. Preview, separate write authorization/admission, geometry and rendered-pose checks remain required. This is not complete rig-copy or visual acceptance.

### Authorized CMO3 OPEN

`documents.open({operationId,grantId,documentSnapshotId})` consumes a same-session READ grant restricted to `["cmo3"]` and a current `documents.read` snapshot. Full document inventory and authoring state are checked before consumption and native dispatch, with TTL and capacity limits. Runtime requires model read/write and file read permissions; only exact 5.3.03 is admitted. No paths, handles, arbitrary formats, close operations or overwrite are accepted.

`EXECUTED` / `NATIVE_OPEN_DISPATCHED_REDISCOVER_DOCUMENTS` means only native dispatch returned, including focus of an existing tab. Rediscover with `documents.read`; it proves neither first-open readiness nor file identity, persistence or reopened content. Query `documents.file_result` after uncertainty. OPEN and SAVE_AS share session-bound operation identities; replay does not redispatch. No Undo or native cancellation is promised.

`documents.read.state.hostObserved=false` can mean no observed project/document while the Editor command service remains available. It does not itself reject authorized OPEN. The current snapshot, command service, exact Runtime version/permissions and grant checks still apply.

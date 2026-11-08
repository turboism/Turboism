---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.turbo-mesh
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: modeling
tags: mesh, artmesh, editing
turboismApi: "[0.1.0,0.2.0)"
requiresCubism: true
interface: embedded
---

# Turbo Mesh

> **Official Turboism plugin** · **Status: Preview**

Custom ArtMesh point placement: curvature-aware boundary resampling, autonomous region subdivision and constrained Delaunay triangulation, committed as one undoable edit.

| Detail | Value |
|---|---|
| Version | `0.1.0` |
| Plugin ID | `dev.turboism.plugin.turbo-mesh` |
| Category | `modeling` |
| Tags | mesh, artmesh, editing |
| Turboism API | `[0.1.0,0.2.0)` |
| Requires Cubism | Yes |
| Interface | `embedded` |
| License | Project License |

## What it does

- Adds a **Turbo Mesh: Re-place Points** entry to ArtMesh context menus.
- Extracts the mesh's boundary rings and resamples them with curvature-adaptive spacing: sharp tips get anchor points and tight spacing, smooth curves get chord-error-bounded samples.
- Segments the shape into autonomous regions (4-connected components, recursively split at narrow waists) and distributes the interior point budget by region area.
- Places interior samples on a seeded jittered grid that keeps a protection margin from region boundaries.
- Triangulates everything with constrained Delaunay so region separators become hard edges, then commits the result through the editor's undo envelope — Undo/Redo work normally.

## Requirements and compatibility

- **Turboism API:** `[0.1.0,0.2.0)`.
- **Cubism:** Requires Cubism. Turboism currently admits exact reviewed Editor artifacts `5.2.03`, `5.3.02`, and `5.3.03`; this plugin exposes each host-facing feature only when its declared services and capabilities are available.
- **Interface mode:** `embedded`.
- **Plugin dependencies:** None declared.

<!-- compatibility:begin -->
**Cubism Editor version support (generated):** 5.2.00–5.2.03, 5.3.00–5.3.04（earlier Cubism versions lack the feature entirely）.
Full feature set (reviewed versions): 5.2.00–5.2.03, 5.3.00–5.3.04.
- Mesh geometry read only on 5.2.00–5.3.04（earlier Cubism versions lack the feature entirely）
Full matrix: [compatibility matrix](../../compatibility/cubism/plugins.md)
<!-- compatibility:end -->

## Install and enable

This official plugin is a **store candidate**, not yet a published store listing. Until marketplace publication, install it through Turboism's official release packaging, then enable it in **Plugin Management**. Disable or uninstall it from the same window when the workflow is not needed.

## How to use

1. Select an ArtMesh in the deformer, part, or workspace object panel.
2. Right-click and pick **Turbo Mesh: re-placement**.
3. Choose a placement mode in the mode dialog (auto blend / soft strip / soft sheet / rigid geometry).
4. Compare source and new mesh statistics in the confirm dialog; confirming writes back, cancelling changes nothing.
5. The mesh is re-placed as a single undoable edit; Undo/Redo work as usual.

ArtMeshes bound to parameter keyforms get a warning in the confirm dialog; when the host cannot
show dialogs, keyform-bearing ArtMeshes are blocked from re-placement entirely.

Tuning currently happens through JVM system properties (a settings dialog is planned):

| Property | Default | Meaning |
|---|---|---|
| `turboMesh.targetPoints` | 320 | Soft target for total point count |
| `turboMesh.features` | `false` | Enable the 0.2.0 feature-aware placement path |
| `turboMesh.features.preset` | `AUTO_BLEND` | Preset: `AUTO_BLEND` / `SOFT_STRIP` / `SOFT_SHEET` / `RIGID_GEOMETRY` |
| `turboMesh.features.weightTexture` | per preset | Texture-edge density weight (soft signal) |
| `turboMesh.features.directionInfluence` | per preset | Direction-field influence on sampling (0 = off) |
| `turboMesh.confirm` | `dialog` | `never` skips confirmation dialogs (automation/validation) |
| `turboMesh.alphaThreshold` | `8` | Texture alpha domain threshold |
## Capabilities

| Declared capability | User effect |
|---|---|
| `cubism.mesh.read` | Reads the selected ArtMesh geometry for placement planning. |
| `cubism.mesh.write` | Writes the generated mesh geometry back to the ArtMesh. |
| `ui.context-menu.contribute` | Adds the placement entry to ArtMesh context menus. |
| `ui.status.notify` | Reports placement results and failures through host status notifications. |

## Permissions

| Permission | Scope | Why it is requested |
|---|---|---|
| `turboism.cubism.model.read` | `application` | Read the selected ArtMesh geometry for placement planning. |
| `turboism.cubism.model.write` | `application` | Commit the generated mesh geometry back to the ArtMesh. |
| `turboism.action.register` | `application` | Register the custom placement action. |
| `turboism.ui.context-menu.contribute` | `application` | Contribute the placement entry to ArtMesh context menus. |
| `turboism.ui.status.notify` | `application` | Report placement results and failures. |

## Privacy and data

### Network

Makes no network connections.

### Local data

Does not persist model data or plugin settings. Placement results are written only into the open model document as ordinary edits.

### Telemetry

No telemetry is sent by this plugin.

Plugin lifecycle and failure records can appear in Turboism's session log and Cubism's host log with the plugin ID attached.

## Status and limitations

- **Status:** Preview.
- The geometry write targets the **current keyform** only. Re-placing a mesh that is already deformed on other keyforms changes its vertex count and those keyforms do not re-map automatically — place points before deforming, or verify on a copy first.
- The placement domain is derived from the mesh's existing UV footprint (hole-aware, topology-preserving). Texture alpha-aware placement is a planned extension once a reviewed pixel-read surface exists.

## Troubleshooting

| Symptom | What to check |
|---|---|
| Menu entry is missing | Confirm the plugin is enabled and the selection contains an ArtMesh. |
| Placement reports a failure | Check the status notification and session log for the planner diagnostic. |
| Nothing changes on a mesh | Confirm the mesh has a valid triangle topology and is not locked. |

## Support and license

- **Project website:** [https://turboism.dev](https://turboism.dev)
- **Publisher:** Turboism Contributors
- **License:** Project License
- **Plugin ID:** `dev.turboism.plugin.turbo-mesh`

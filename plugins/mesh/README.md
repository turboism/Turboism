---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.mesh
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

# Mesh Inspector and Mirror-Axis Tools

> **Official Turboism plugin** · **Status: Preview**

Inspects mesh and deformer state and adds a bounded mirror-axis angle control to supported mesh-edit UI.

| Detail | Value |
|---|---|
| Version | `0.1.0` |
| Plugin ID | `dev.turboism.plugin.mesh` |
| Category | `modeling` |
| Tags | mesh, artmesh, editing |
| Turboism API | `[0.1.0,0.2.0)` |
| Requires Cubism | Yes |
| Interface | `embedded` |
| License | Project License |

## What it does

- Registers an Inspect Meshes action that reports mesh, deformer, and context counts.
- Contributes a mirror-axis angle control from -180° to 180° with 0.1° steps and reset support.
- Uses verified typed services for both inspection and angle changes.

## Requirements and compatibility

- **Turboism API:** `[0.1.0,0.2.0)`.
- **Cubism:** Requires Cubism. Turboism currently admits exact reviewed Editor artifacts `5.2.03` and `5.3.02`; this plugin exposes each host-facing feature only when its declared services and capabilities are available.
- **Interface mode:** `embedded`.
- **Plugin dependencies:** None declared.

## Install and enable

This official plugin is a **store candidate**, not yet a published store listing. Until marketplace publication, install it through Turboism's official release packaging, then enable it in **Plugin Management**. Disable or uninstall it from the same window when the workflow is not needed.

## How to use

1. Run **Inspect Meshes** from its contributed action surface to report the current mesh/deformer context.
2. Enter Cubism's supported mesh-edit mode and locate the mirror-axis angle control.
3. Adjust the angle or use reset; the control changes the current mesh-edit tool angle, not stored plugin settings.

## Capabilities

| Declared capability | User effect |
|---|---|
| `cubism.mesh.read` | Reads mesh snapshots for inspection. |
| `cubism.deformer.read` | Reads deformer snapshots for inspection. |
| `cubism.mesh.mirror-axis-angle` | Reads and writes the current mirror-axis tool angle. |
| `ui.mesh-edit.mirror-axis-angle` | Contributes the bounded control to supported mesh-edit UI. |
| `ui.context-source.read` | Reads the typed action context used by inspection. |
| `ui.status.notify` | Reports inspection and fallback outcomes. |

## Permissions

| Permission | Scope | Why it is requested |
|---|---|---|
| `turboism.cubism.model.read` | `application` | Read mesh and deformer snapshots for the inspector. |
| `turboism.ui.context-source.read` | `application` | Read typed context source for inspect context. |
| `turboism.ui.status.notify` | `application` | Notify inspect results and empty fallback. |
| `turboism.action.register` | `application` | Register the mesh inspect action. |
| `turboism.cubism.model.write` | `application` | Changes the current mesh mirror-axis tool angle. |
| `turboism.ui.panel.contribute` | `application` | Contributes the mirror-axis angle control to Cubism's mesh editor. |

## Privacy and data

### Network

Makes no network connections.

### Local data

Does not persist plugin data. Inspection reads model/context snapshots; the angle control writes only the active mesh-edit tool angle.

### Telemetry

No telemetry is sent by this plugin.

Plugin lifecycle and failure records can appear in Turboism's session log and Cubism's host log with the plugin ID attached.

## Status and limitations

- **Status:** Preview.
- Requires reviewed mesh, deformer, context-source, mirror-axis, and mesh-edit UI services.
- Inspection is read-only. The mirror-axis control is omitted when its exact host integration is unavailable.

## Troubleshooting

| Symptom | What to check |
|---|---|
| Mirror control is missing | Confirm the plugin is enabled, mesh-edit mode is active, and the host exposes the reviewed mirror-axis UI service. |
| Inspection reports no objects | Open a model containing meshes or deformers and ensure the relevant editor context is active. |
| Angle change is rejected | Check the supported -180° to 180° range and inspect the plugin log for host-service availability. |

## Support and license

- **Project website:** [https://turboism.dev](https://turboism.dev)
- **Publisher:** Turboism Contributors
- **License:** Project License
- **Plugin ID:** `dev.turboism.plugin.mesh`

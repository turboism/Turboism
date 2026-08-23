---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.clipmask-viewer
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: analysis
tags: clip-mask, viewer, graph
turboismApi: "[0.1.0,0.2.0)"
requiresCubism: true
interface: swing
---

# Clip Mask Viewer

> **Official Turboism plugin** · **Status: Preview**

Inspects clip-mask relationships, duplicates, order conflicts, and related ArtMeshes without modifying the model.

| Detail | Value |
|---|---|
| Version | `0.1.0` |
| Plugin ID | `dev.turboism.plugin.clipmask-viewer` |
| Category | `analysis` |
| Tags | clip-mask, viewer, graph |
| Turboism API | `[0.1.0,0.2.0)` |
| Requires Cubism | Yes |
| Interface | `swing` |
| License | Project License |

## What it does

- Adds a section to the Turboism panel and a Turboism menu action for opening the viewer.
- Presents graph, mask-primary table, and user-primary table views with filtering, zoom, and refresh.
- Highlights the editor selection and can copy selected GUIDs to the system clipboard.

## Requirements and compatibility

- **Turboism API:** `[0.1.0,0.2.0)`.
- **Cubism:** Requires Cubism. Turboism currently admits exact reviewed Editor artifacts `5.2.03` and `5.3.02`; this plugin exposes each host-facing feature only when its declared services and capabilities are available.
- **Interface mode:** `swing`.
- **Plugin dependencies:** None declared.

## Install and enable

This official plugin is a **store candidate**, not yet a published store listing. Until marketplace publication, install it through Turboism's official release packaging, then enable it in **Plugin Management**. Disable or uninstall it from the same window when the workflow is not needed.

## How to use

1. Open the Clip Mask Viewer from the Turboism panel section or Turboism menu.
2. Choose a graph or table view, then use filtering and the unrelated-node toggle to narrow the result.
3. Select a row or node to inspect its relationships; use the copy action when a GUID is needed.

## Capabilities

| Declared capability | User effect |
|---|---|
| `cubism.clipmask.read` | Reads clip-mask and ArtMesh relationship snapshots. |
| `ui.embedded-panel.contribute` | Adds the launcher section to the Turboism panel. |
| `ui.menu.contribute` | Adds the viewer command to the Turboism menu. |
| `ui.status.notify` | Reports clipboard and viewer outcomes through host status notifications. |

## Permissions

| Permission | Scope | Why it is requested |
|---|---|---|
| `turboism.cubism.model.read` | `application` | Read clip-mask and ArtMesh snapshots plus the editor selection for the duplicate checker and viewer. |
| `turboism.ui.panel.contribute` | `application` | Inject the Clip Mask Viewer collapsible section into the Turboism panel. |
| `turboism.action.register` | `application` | Register the clipmask-viewer.open.viewer action behind the Turboism tab button and menu item. |
| `turboism.ui.menu.contribute` | `application` | Expose the clip-mask duplicate checker through the Turboism menu. |
| `turboism.ui.status.notify` | `application` | Notify GUID copy results. |
| `turboism.event.subscribe` | `application` | Registers the generated selection observation subscriber. |
| `turboism.cubism.selection.observe` | `application` | Synchronizes an open viewer with pull-detected selection changes. |

## Privacy and data

### Network

Makes no network connections.

### Local data

Does not persist model data or plugin settings. A user-requested copy operation writes the selected GUID text to the system clipboard.

### Telemetry

No telemetry is sent by this plugin.

Plugin lifecycle and failure records can appear in Turboism's session log and Cubism's host log with the plugin ID attached.

## Status and limitations

- **Status:** Preview.
- Read-only: it does not change clip-mask assignments or model objects.
- Requires reviewed clip-mask and editor-selection read services; the Swing viewer is unavailable in a headless JVM.

## Troubleshooting

| Symptom | What to check |
|---|---|
| Viewer action is missing | Confirm the plugin is enabled and panel/menu contribution services are available. |
| Viewer is empty | Open a model with ArtMeshes and clip-mask relationships, then refresh. |
| Window does not open | Check for a headless environment or an unavailable clip-mask read capability in the log. |

## Support and license

- **Project website:** [https://turboism.dev](https://turboism.dev)
- **Publisher:** Turboism Contributors
- **License:** Project License
- **Plugin ID:** `dev.turboism.plugin.clipmask-viewer`

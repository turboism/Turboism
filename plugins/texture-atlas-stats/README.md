---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.texture-atlas-stats
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: analysis
tags: texture-atlas, metrics, inspection
turboismApi: "[0.1.0,0.2.0)"
requiresCubism: true
interface: swing
---

# Texture Atlas Statistics

> **Official Turboism plugin** · **Status: Preview**

Shows total and current-texture model-image counts in Cubism's native texture-atlas editor.

| Detail | Value |
|---|---|
| Version | `0.1.0` |
| Plugin ID | `dev.turboism.plugin.texture-atlas-stats` |
| Category | `analysis` |
| Tags | texture-atlas, metrics, inspection |
| Turboism API | `[0.1.0,0.2.0)` |
| Requires Cubism | Yes |
| Interface | `swing` |
| License | Project License |

## What it does

- Attaches one localized statistics line to the active texture-atlas editor UI.
- Refreshes total model-image and selected-texture image counts once per second.
- Displays an unavailable state instead of propagating read failures to the host UI.

## Requirements and compatibility

- **Turboism API:** `[0.1.0,0.2.0)`.
- **Cubism:** Requires Cubism. Turboism currently admits exact reviewed Editor artifacts `5.2.03`, `5.3.02`, and `5.3.03`; this plugin exposes each host-facing feature only when its declared services and capabilities are available.
- **Interface mode:** `swing`.
- **Plugin dependencies:** None declared.

## Install and enable

This official plugin is a **store candidate**, not yet a published store listing. Until marketplace publication, install it through Turboism's official release packaging, then enable it in **Plugin Management**. Disable or uninstall it from the same window when the workflow is not needed.

## How to use

1. Open a model and launch Cubism's texture-atlas editor.
2. Locate the statistics line contributed to the native editor window.
3. Switch the selected texture to update the current-texture count; totals refresh once per second.

## Capabilities

| Declared capability | User effect |
|---|---|
| `cubism.texture-atlas.statistics` | Reads model-image counts from the active texture-atlas editor session. |

## Permissions

| Permission | Scope | Why it is requested |
|---|---|---|
| `turboism.cubism.model.read` | `application` | Read model-image counts from the active texture-atlas editor session. |

## Privacy and data

### Network

Makes no network connections.

### Local data

Does not persist data. It reads only the active texture-atlas session summary and updates an in-memory UI label.

### Telemetry

No telemetry is sent by this plugin.

Plugin lifecycle and failure records can appear in Turboism's session log and Cubism's host log with the plugin ID attached.

## Status and limitations

- **Status:** Preview.
- Requires reviewed texture-atlas session and editor UI services.
- The plugin is read-only. When the editor or service is unavailable, the line is absent or displays the localized unavailable state.

## Troubleshooting

| Symptom | What to check |
|---|---|
| Statistics line is missing | Open the native texture-atlas editor and confirm the plugin is enabled on a reviewed host. |
| Counts remain zero | Confirm a model and texture are active, then wait for the next one-second refresh. |
| Unavailable text appears | Check the Turboism log for texture-atlas session read failure. |

## Support and license

- **Project website:** [https://turboism.dev](https://turboism.dev)
- **Publisher:** Turboism Contributors
- **License:** Project License
- **Plugin ID:** `dev.turboism.plugin.texture-atlas-stats`

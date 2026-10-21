---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.texture-atlas-stats
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: analysis
tags: texture-atlas, metrics, inspection
turboismApi: "[0.67.0,1.0.0)"
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
| Requires Cubism | Yes |
| Interface | `swing` |
| License | Project License |

## What it does

- Attaches one localized statistics line to the active texture-atlas editor UI.
- Refreshes total model-image and selected-texture image counts once per second.
- Displays an unavailable state instead of propagating read failures to the host UI.

## Requirements and compatibility

- **SDK API range:** `[0.67.0,1.0.0)`.
- **Cubism:** Requires Cubism. Turboism currently admits exact reviewed Editor artifacts `5.0.00` through `5.3.04`; this plugin exposes each host-facing feature only when its declared services and capabilities are available.
- **Interface mode:** `swing`.
- **Plugin dependencies:** None declared.

<!-- compatibility:begin -->
**Cubism Editor version support (generated):** all reviewed versions (5.0.00–5.0.06, 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04; 20 exact versions).
Full feature set (reviewed versions): 5.2.00–5.2.03, 5.3.00–5.3.04.
- Atlas editor panel only on 5.2.00–5.3.04（earlier Cubism versions lack the feature entirely）
- Native atlas editor session access only on 5.2.00–5.3.04（earlier Cubism versions lack the feature entirely）
- Native atlas editor UI contribution only on 5.2.00–5.3.04（earlier Cubism versions lack the feature entirely）
Full matrix: [compatibility matrix](../../compatibility/cubism/plugins.md)
<!-- compatibility:end -->

## Install and enable

This official plugin is bundled with Turboism and is available through **Plugin Market**. Enable, disable or uninstall the installed plugin in **Plugin Management**.

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

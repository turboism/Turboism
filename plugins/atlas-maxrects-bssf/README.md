---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.texture-atlas
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: modeling
tags: texture-atlas, packing, auto-layout
turboismApi: "[0.1.0,0.2.0)"
requiresCubism: true
interface: none
---

# MaxRects-BSSF Layout Algorithm

> **Official Turboism plugin** · **Status: Preview**

Adds a MaxRects-BSSF automatic layout algorithm to Cubism's texture-atlas workflow.

| Detail | Value |
|---|---|
| Version | `0.1.0` |
| Plugin ID | `dev.turboism.plugin.texture-atlas` |
| Category | `modeling` |
| Tags | texture-atlas, packing, auto-layout |
| Turboism API | `[0.1.0,0.2.0)` |
| Requires Cubism | Yes |
| Interface | `none` |
| License | Project License |

## What it does

- Registers MaxRects-BSSF and native layout choices with the texture-atlas editor.
- Plans bounded atlas layouts with optional parallel search, then applies a validated complete plan through the Editor authoring API.
- Persists the selected layout mode, algorithm, and parallel-search preference.

## Requirements and compatibility

- **Turboism API:** `[0.1.0,0.2.0)`.
- **Cubism:** Requires Cubism. Turboism currently admits exact reviewed Editor artifacts `5.2.03`, `5.3.02`, and `5.3.03`; this plugin exposes each host-facing feature only when its declared services and capabilities are available.
- **Interface mode:** `none`.
- **Plugin dependencies:** None declared.

## Install and enable

This official plugin is a **store candidate**, not yet a published store listing. Until marketplace publication, install it through Turboism's official release packaging, then enable it in **Plugin Management**. Disable or uninstall it from the same window when the workflow is not needed.

## How to use

1. Open Cubism's texture-atlas editor and choose the automatic layout workflow.
2. Select MaxRects-BSSF or the native algorithm and choose whether parallel search is enabled.
3. Run automatic layout; the plugin validates the complete plan before applying it.

## Capabilities

| Declared capability | User effect |
|---|---|
| `cubism.texture-atlas.layout` | Registers and applies automatic texture-atlas layout algorithms when the reviewed editor service is available. |

## Permissions

| Permission | Scope | Why it is requested |
|---|---|---|
| `turboism.cubism.model.read` | `application` | Read the complete active texture-atlas authoring snapshot. |
| `turboism.cubism.model.write` | `application` | Apply a validated complete texture-atlas layout plan through Editor authoring state. |
| `turboism.config.plugin.read` | `application` | Restore the selected automatic texture-atlas layout mode. |
| `turboism.config.plugin.write` | `application` | Persist the selected automatic texture-atlas layout mode. |

## Privacy and data

### Network

Makes no network connections.

### Local data

Stores layout settings in plugin configuration at `texture-atlas/layout.cfg`. It reads and writes the active texture-atlas authoring state only when the workflow runs.

### Telemetry

No telemetry is sent by this plugin.

Plugin lifecycle and failure records can appear in Turboism's session log and Cubism's host log with the plugin ID attached.

## Status and limitations

- **Status:** Preview.
- Requires an active texture-atlas editor session and reviewed model read/write services.
- Planning or application failure is reported without applying a partial layout; the native algorithm remains available as a fallback.

## Troubleshooting

| Symptom | What to check |
|---|---|
| Automatic layout choice is missing | Confirm the plugin is enabled and the current host exposes the texture-atlas layout capability. |
| Layout is not applied | Check the Turboism log for packing or validation failure and verify that a texture-atlas session is active. |

## Support and license

- **Project website:** [https://turboism.dev](https://turboism.dev)
- **Publisher:** Turboism Contributors
- **License:** Project License
- **Plugin ID:** `dev.turboism.plugin.texture-atlas`

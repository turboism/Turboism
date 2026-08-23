---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.scene-palette-enhancer
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: appearance
tags: scene, palette, enhancement
turboismApi: "[0.1.0,0.2.0)"
requiresCubism: true
interface: none
---

# Scene Palette Enhancer

> **Official Turboism plugin** · **Status: Preview**

Adds natural sorting and persistent manual row ordering to Cubism's Scene palette.

| Detail | Value |
|---|---|
| Version | `0.1.0` |
| Plugin ID | `dev.turboism.plugin.scene-palette-enhancer` |
| Category | `appearance` |
| Tags | scene, palette, enhancement |
| Turboism API | `[0.1.0,0.2.0)` |
| Requires Cubism | Yes |
| Interface | `none` |
| License | Project License |

## What it does

- Cycles Scene palette sorting through ascending, descending, and manual order.
- Supports manual row dragging and displays header sort markers.
- Persists manual order per opaque scene scope and merges it with newly discovered live rows.

## Requirements and compatibility

- **Turboism API:** `[0.1.0,0.2.0)`.
- **Cubism:** Requires Cubism. Turboism currently admits exact reviewed Editor artifacts `5.2.03` and `5.3.02`; this plugin exposes each host-facing feature only when its declared services and capabilities are available.
- **Interface mode:** `none`.
- **Plugin dependencies:** None declared.

## Install and enable

This official plugin is a **store candidate**, not yet a published store listing. Until marketplace publication, install it through Turboism's official release packaging, then enable it in **Plugin Management**. Disable or uninstall it from the same window when the workflow is not needed.

## How to use

1. Open Cubism's Scene palette and use the column header to cycle sort modes.
2. Switch to manual order and drag rows into the desired sequence.
3. Return to the same scene scope to restore the persisted manual order.

## Capabilities

No capabilities are declared in the plugin manifest.

## Permissions

| Permission | Scope | Why it is requested |
|---|---|---|
| `turboism.file.read` | `application` | Restores per-project Scene palette manual order. |
| `turboism.file.write` | `application` | Persists per-project Scene palette manual order. |

## Privacy and data

### Network

Makes no network connections.

### Local data

Atomically stores newline-separated item IDs in plugin state files named `manual-order-<scopeId>.txt`. The scope ID must be an opaque 64-character lowercase hexadecimal value; project paths are not stored by this plugin.

### Telemetry

No telemetry is sent by this plugin.

Plugin lifecycle and failure records can appear in Turboism's session log and Cubism's host log with the plugin ID attached.

## Status and limitations

- **Status:** Preview.
- Requires a supported Scene table service. If storage is unavailable, sorting can continue without persistent manual order.
- Stored IDs are reconciled with current rows: missing rows drop out and new rows are appended.

## Troubleshooting

| Symptom | What to check |
|---|---|
| Sorting controls are absent | Confirm the plugin is enabled and the current host exposes the Scene table service. |
| Manual order is not restored | Confirm the same scene scope is active and inspect state-storage diagnostics. |
| New rows appear at the end | This is expected when a stored manual order is merged with newly created scene items. |

## Support and license

- **Project website:** [https://turboism.dev](https://turboism.dev)
- **Publisher:** Turboism Contributors
- **License:** Project License
- **Plugin ID:** `dev.turboism.plugin.scene-palette-enhancer`

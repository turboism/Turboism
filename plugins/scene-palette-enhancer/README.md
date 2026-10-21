---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.scene-palette-enhancer
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: appearance
tags: scene, palette, enhancement
turboismApi: "[0.67.0,1.0.0)"
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
| Requires Cubism | Yes |
| Interface | `none` |
| License | Project License |

## What it does

- Cycles Scene palette sorting through ascending, descending, and manual order.
- Supports manual row dragging and displays header sort markers.
- Persists manual order per opaque scene scope and merges it with newly discovered live rows.

## Requirements and compatibility

- **SDK API range:** `[0.67.0,1.0.0)`.
- **Cubism:** Requires Cubism. The Scene palette bridge is admitted for the exact reviewed Editor artifacts `5.0.00` through `5.3.04`; any other artifact fails closed. All 23 Scene-specific private UI selectors were verified structurally against every exact reviewed artifact (member names, descriptors and access flags matching on each jar), and binding re-verifies them against the loaded host at attach time.

- **Interface mode:** `none`.
- **Plugin dependencies:** None declared.

<!-- compatibility:begin -->
**Cubism Editor version support (generated):** all reviewed versions (5.0.00–5.0.06, 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04; 20 exact versions).
Full feature set (reviewed versions): 5.0.00–5.0.06, 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04.
Full matrix: [compatibility matrix](../../compatibility/cubism/plugins.md)
<!-- compatibility:end -->

## Install and enable

This official plugin is bundled with Turboism and is available through **Plugin Market**. Enable, disable or uninstall the installed plugin in **Plugin Management**.

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
| `turboism.event.subscribe` | `application` | Registers generated annotation subscribers. |
| `turboism.ui.scene-table.observe` | `application` | Observes Runtime-owned Scene palette snapshots, header clicks, and reorder changes. |

## Privacy and data

### Network

Makes no network connections.

### Local data

Atomically stores a versioned `# turboism.scene.manual-order v1` header followed by newline-separated item IDs in plugin state files named `manual-order-<scopeId>.txt`. Legacy headerless files remain readable. Truncated or unknown future formats fail closed. The scope ID must be an opaque 64-character lowercase hexadecimal value; project paths are not stored by this plugin.

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

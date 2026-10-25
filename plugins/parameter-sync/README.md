---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.parameter-sync
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: modeling
tags: parameter, sync, link, workflow
turboismApi: "[0.67.0,1.0.0)"
requiresCubism: true
interface: swing
---

# Parameter Sync Plugin

> **Official Turboism plugin** · **Status: Preview**

Synchronizes Cubism parameter values in real time with normalized range mapping across checked parameters in a unified window.

| Detail | Value |
|---|---|
| Version | `0.1.0` |
| Plugin ID | `dev.turboism.plugin.parameter-sync` |
| Category | `modeling` |
| Tags | parameter, sync, link, workflow |
| Requires Cubism | Yes |
| Interface | `swing` |
| License | Project License |

## What it does

- Propagates parameter value changes to all other synchronized parameters instantly during slider drag, input, or external driving.
- Employs normalized range percentage mapping: dragging any checked parameter synchronizes all other checked parameters proportionally across their respective ranges.
- Unified single dialog: global switch and synchronized parameter count at the top, and independent checkboxes for each parameter in the middle table.
- Event-driven architecture with zero latency and programmatic write suppression to eliminate oscillation feedback loops.
- Cross-session persistence keyed by native model GUID.

## Requirements and compatibility

- **SDK API range:** `[0.67.0,1.0.0)`.
- **Cubism:** Requires Cubism. Turboism currently admits exact reviewed Editor artifacts `5.0.00` through `5.3.04`; this plugin exposes each host-facing feature only when its declared services and capabilities are available.
- **Interface mode:** `swing`.
- **Plugin dependencies:** None declared.

<!-- compatibility:begin -->
**Cubism Editor version support (generated):** all reviewed versions (5.0.00–5.0.06, 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04; 20 exact versions).
Full feature set (reviewed versions): 5.0.00–5.0.06, 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04.
Full matrix: [compatibility matrix](../../compatibility/cubism/plugins.md)
<!-- compatibility:end -->

## Install and enable

This official plugin is bundled with Turboism and is available through **Plugin Market**. Enable, disable or uninstall the installed plugin in **Plugin Management**.

## How to use

1. Right-click any parameter in the Parameters palette.
2. Select **Parameter Synchronization...** to open the unified configuration dialog.
3. Check the parameters you want to link together and click **Save & Close**.
4. Dragging any checked parameter in the palette or canvas will now sync all other checked parameters in real time.

## Capabilities

| Declared capability | User effect |
|---|---|
| `cubism.parameter.read` | Reads parameter values, ranges, and names from the active model. |
| `cubism.parameter.write` | Writes synchronized values to target parameters. |
| `cubism.model.observe` | Observes model open and close lifecycle events to restore synchronization rules. |
| `event.subscribe` | Subscribes to real-time parameter value change events. |
| `ui.context-menu.contribute` | Contributes parameter synchronization actions to the parameter palette context menu. |
| `ui.status.notify` | Notifies synchronization status and toggle notifications. |
| `file.read` | Reads saved parameter synchronization rules from plugin storage. |
| `file.write` | Persists parameter synchronization rules to plugin storage. |

## Permissions

| Permission | Scope | Why it is requested |
|---|---|---|
| `turboism.action.register` | `application` | Register parameter sync manager and toggle actions. |
| `turboism.ui.context-menu.contribute` | `application` | Contribute parameter sync actions to the parameter palette context menu. |
| `turboism.cubism.model.read` | `application` | Read parameter values, bounds, names, and active model GUID for synchronization. |
| `turboism.cubism.model.write` | `application` | Write synchronized values to target parameters. |
| `turboism.cubism.model.observe` | `application` | Observe parameter value changes and model lifecycle events. |
| `turboism.event.subscribe` | `application` | Subscribe to parameter value change events. |
| `turboism.file.read` | `application` | Read persisted parameter synchronization rules from plugin storage. |
| `turboism.file.write` | `application` | Persist parameter synchronization rules to plugin storage. |
| `turboism.ui.status.notify` | `application` | Notify sync rule changes, cycle warnings, and toggle status. |

## Privacy and data

### Network

Makes no network connections.

### Local data

Persists parameter synchronization configuration in sandboxed plugin storage keyed by model GUID. Contains only model GUID and parameter ID strings.

### Telemetry

No telemetry is sent by this plugin.

Plugin lifecycle and failure records can appear in Turboism's session log and Cubism's host log with the plugin ID attached.

## Status and limitations

- **Status:** Preview.
- Synchronizes checked parameters in the active model using normalized percentage mapping.
- Requires an active model document.
- Synchronization is disabled when fewer than two parameters are checked.

## Troubleshooting

| Symptom | What to check |
|---|---|
| Parameter does not sync | Check that Parameter Synchronization is enabled and that at least two parameters are checked. |
| Context menu entry is missing | Ensure an active model is loaded in the Editor. |
| Synchronization paused | Use Toggle Parameter Sync from the context menu or check the global switch in the dialog. |

## Changelog

### 0.1.0

- Initial release.

## Support and license

- **Project website:** [https://turboism.dev](https://turboism.dev)
- **Publisher:** Turboism Contributors
- **License:** Project License
- **Plugin ID:** `dev.turboism.plugin.parameter-sync`

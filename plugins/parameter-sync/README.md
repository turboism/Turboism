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

# Parameter Sync

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
- Unified single dialog: global switch and synchronized parameter count at the top, independent checkboxes for each parameter in the middle, and real-time test preview slider at the bottom.
- Event-driven architecture with zero latency and programmatic write suppression to eliminate oscillation feedback loops.
- Cross-session persistence keyed by native model GUID.

## Requirements and compatibility

- **SDK API range:** `[0.67.0,1.0.0)`.
- **Cubism:** Requires Cubism. Turboism currently admits exact reviewed Editor artifacts `5.0.00` through `5.3.04`.
- **Interface mode:** `swing`.
- **Plugin dependencies:** None declared.

<!-- compatibility:begin -->
**Cubism Editor version support (generated):** all reviewed versions (5.0.00–5.0.06, 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04; 20 exact versions).
Full feature set (reviewed versions): 5.0.00–5.0.06, 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04.
<!-- compatibility:end -->

## How to use

1. Right-click any parameter in the Parameters palette.
2. Select **Parameter Synchronization...** to open the unified dialog.
3. Check the parameters you want to link together, preview with the test slider, and click **Save & Close**.
4. Dragging any checked parameter in the palette or canvas will now sync all other checked parameters in real time.

## Permissions

| Permission | Scope | Reason |
|---|---|---|
| `turboism.action.register` | `application` | Registers parameter sync manager and toggle actions. |
| `turboism.ui.context-menu.contribute` | `application` | Contributes parameter sync actions to the parameter palette context menu. |
| `turboism.cubism.model.read` | `application` | Reads parameter values, bounds, names, and active model GUID. |
| `turboism.cubism.model.write` | `application` | Writes synchronized values to target parameters. |
| `turboism.cubism.model.observe` | `application` | Observes parameter value changes and model lifecycle events. |
| `turboism.event.subscribe` | `application` | Subscribes to parameter value change events. |
| `turboism.file.read` | `application` | Reads persisted parameter synchronization configuration from plugin storage. |
| `turboism.file.write` | `application` | Persists parameter synchronization configuration to plugin storage. |
| `turboism.ui.status.notify` | `application` | Notifies sync state changes and toggle status. |

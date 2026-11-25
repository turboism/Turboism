---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.warp-row-column-deform
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: modeling
tags: deformer, warp, editing, temporary-deform
turboismApi: "[0.70.3,)"
requiresCubism: true
interface: none
---

# Warp Row/Column Deform

> **Official Turboism plugin** · **Status: Preview**

Adds a custom Temporary Deform Tool that lets you move an entire row or an entire column
of Warp Deformer control points from outward edge handles or direct grid picks.

| Detail | Value |
|---|---|
| Version | `0.1.0` |
| Plugin ID | `dev.turboism.plugin.warp-row-column-deform` |
| Category | `modeling` |
| Tags | deformer, warp, editing, temporary-deform |
| Requires Cubism | Yes |
| Interface | `none` |
| License | Project License |

## What it does

- Registers a custom temporary deform tool accessible from **Modeling -> Temporary Deform Tool** and the **Tool Details** Deform Method dropdown.
- Renders outward edge handles on the left/right edges for rows and top/bottom edges for columns, while also supporting direct grid-line picks controlled by the **Axis Mode** option (`Auto`, `Row Only`, `Column Only`).
- Automatically targets Bezier anchor handles at Edit Level 2 and conversion division points at Edit Level 3, with manual override via the **Control Grid** option (`Auto`, `Bezier`, `Conversion`).
- Records every drag stroke inside Cubism's native Undo/Redo stack and highlights the hovered or dragged row/column on canvas.

## Requirements and compatibility

- **SDK API range:** `[0.70.3,)`.
- Requires a running Cubism Editor 5.x session launched through the Turboism premain agent.

<!-- compatibility:begin -->
**Cubism Editor version support (generated):** all reviewed versions (5.0.00–5.0.06, 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.05; 21 exact versions).
Full feature set (reviewed versions): 5.0.00–5.0.06, 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.05.
Full matrix: [compatibility matrix](../../compatibility/cubism/plugins.md)
<!-- compatibility:end -->

## Install and enable

Turboism bundles the plugin starting with 0.70.3-beta.4.
If a compatible installation lacks the plugin, install it from the Plugin Market.
Upgrade earlier Turboism builds to get the required temporary deform SDK.
Enable the plugin to show it in the Modeling menu and Tool Details dropdown.

## How to use

1. Select one or more Warp Deformers in the Modeling view.
2. Enter **Temporary Row / Column Deform** via **Modeling -> Temporary Deform Tool** or the **Tool Details -> Deform Method** dropdown.
3. Drag an outward row/column handle (or switch **Axis Mode** in Tool Details to `Row Only` / `Column Only` and drag directly on grid points or segments). Hold `Shift` to constrain motion orthogonally.
4. Click **Unbind** (or pick another tool) to exit cleanly.

## Capabilities

No capabilities are declared in the plugin manifest.

## Permissions

- `turboism.cubism.model.read`
- `turboism.cubism.model.write`
- `turboism.ui.menu.contribute`

## Privacy and data

Reads selected Warp Deformer grids on the local canvas only; stores nothing and sends nothing anywhere.

## Status and limitations

- Preview. The tool targets selected Warp Deformers.

## Troubleshooting

- Tool entry does not activate: confirm at least one editable Warp Deformer is selected and the Turboism premain agent attached cleanly.
- Bezier grid unavailable: switch **Control Grid** in Tool Details to `Conversion Division` or edit at Level 2.

## Changelog

### 0.1.0

- Initial release implementing full-row and full-column Warp Deformer temporary editing.

## Support and license

Project License. Report issues through the standard Turboism support channel.

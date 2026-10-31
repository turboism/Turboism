---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.texture-atlas-folders
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: modeling
tags: texture-atlas, packing, auto-layout
turboismApi: "[0.67.4,1.0.0)"
requiresCubism: true
interface: swing
---

# Folder Atlas Layout

## What it does

Creates texture pages by top-level Part folders, recursively splitting overflowing folders. Packing algorithms are discovered from the live registry; no specific algorithm plugin is required.

## Requirements and compatibility

Requires Java 17+, Turboism API [0.67.4,1.0.0), an active editable Cubism model. Enable at least one algorithm that supports complete-atlas layout.

<!-- compatibility:begin -->
**Cubism Editor version support (generated):** all reviewed versions (5.0.00–5.0.06, 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04; 20 exact versions).
Full feature set (reviewed versions): 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04.
- Top menu integration not on 5.0.00–5.0.06（earlier Cubism versions lack the feature entirely）
Full matrix: [compatibility matrix](../../compatibility/cubism/plugins.md)
<!-- compatibility:end -->

## Install and enable

Install the plugin JAR through Turboism plugin management and enable Folder Atlas Layout. Enable a complete-atlas algorithm plugin, such as MaxRects-BSSF, Bitmask Nesting or Dalsoo.

Bitmask Nesting and Dalsoo use material-local mesh contours when available, with an explicit full-image rectangle fallback for unsupported sources. Packing preserves source size without rotation. Folder order guides grouping; spare space is reused across folders and overflowing leaves are filled greedily before new pages are created.

## How to use

Open **Turboism → Arrange Atlas by Part Folders**. Choose the maximum texture edge (1024, 2048, 4096 or 8192), algorithm, margin, padding and automatic page sizing. The operation replaces the complete atlas as one undoable edit; page order follows the Parts tree.


For a newly imported PSD without an atlas, the operation creates the output pages directly without native initial placement. It generates the texture images and commits the result in one undoable edit; opening the atlas editor to confirm is unnecessary.

## Capabilities

- `cubism.texture-atlas.layout`
- `cubism.model.read`
- `ui.menu.contribute`
- `ui.status.notify`

## Permissions

- `turboism.action.register`
- `turboism.ui.menu.contribute`
- `turboism.ui.status.notify`
- `turboism.cubism.model.read`
- `turboism.cubism.model.write`

## Privacy and data

Reads the active model and writes its atlas layout in the Editor. This plugin does not upload model data or make network requests.

## Status and limitations

Preview. Current-page-only algorithms are listed but disabled for complete-atlas layout. The menu remains available independently of MaxRects; an enabled complete-atlas algorithm is still required.

## Troubleshooting

If no algorithm is available, enable a compatible algorithm plugin. If no atlas is open, create or open one for the active model. Use Undo to restore the previous layout.

## Changelog

### 0.1.0

- Initial release.

## Support and license

Report reproducible issues at https://github.com/turboism/Turboism/issues with the Editor version and selected algorithm. Covered by the repository Project License.

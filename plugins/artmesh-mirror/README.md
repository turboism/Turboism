---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.artmesh-mirror
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: modeling
tags: mesh, artmesh, editing, symmetry
turboismApi: "[0.67.0,1.0.0)"
requiresCubism: true
interface: none
---

# ArtMesh Mirror

## What it does

Independent mirror editing for ArtMesh vertices in ordinary modeling mode. The axis resets to the selected meshes’ center when the selection changes.

## Requirements and compatibility

Turboism API: `[0.67.0,1.0.0)`. Cubism Editor: 5.0.00–5.3.04.

<!-- compatibility:begin -->
**Cubism Editor version support (generated):** all reviewed versions (5.0.00–5.0.06, 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04; 20 exact versions).
Full feature set (reviewed versions): 5.0.00–5.0.06, 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04.
Full matrix: [compatibility matrix](../../compatibility/cubism/plugins.md)
<!-- compatibility:end -->

## Install and enable

Bundled with the official Turboism distribution. Enable or disable it in Plugin Management.

## How to use

Use the dedicated canvas button to toggle mirroring. Drag the hollow diamond or axis line to move the axis; drag the hollow square to rotate it. Double-click an axis control to reset it. The larger angle label has an outline and no background. Handle positions follow canvas zoom while their screen size stays easy to click. Warp Deformer mirroring has its own independent button. No keyboard shortcuts are registered.

## Capabilities

| Capability | Effect |
|---|---|
| `cubism.artmesh.mirror` | Controls mirror vertex edits and the canvas axis. |

## Permissions

| Permission | Scope | Effect |
|---|---|---|
| `turboism.cubism.model.write` | `application` | Controls mirror vertex edits and the canvas axis. |
| `turboism.ui.toolbar.contribute` | `application` | Controls mirror vertex edits and the canvas axis. |
| `turboism.ui.canvas.hint` | `application` | Controls mirror vertex edits and the canvas axis. |

## Privacy and data

No network connections or telemetry. Edits join the native gesture’s Undo/Redo; no plugin data is persisted.

## Status and limitations

Preview. Matching searches within approximately 20 screen pixels of the reflection at the current axis angle. Pairs must be mutual and unambiguous; missing vertices are not created. Mesh topology editing uses its own mirror tool.

## Troubleshooting

If the axis is absent, enable the plugin and its canvas button, select an ArtMesh in ordinary modeling mode, and check runtime hook availability. If no counterpart moves, check that a corresponding vertex exists near the reflected position.

## Changelog

### 0.1.0

- Initial release.

## Support and license

[Issues](https://github.com/turboism/Turboism/issues) · [Project License](../../LICENSE)

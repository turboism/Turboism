---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.geometric-mesh-layout
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: modeling
tags: mesh, artmesh, editing
turboismApi: "[0.67.0,1.0.0)"
requiresCubism: true
interface: embedded
---

# Geometric Mesh Layout

## What it does

Contributes the Geometric Mesh Layout tool to the native mesh-editing toolbar. Clicking the tool enables setting a center anchor on the canvas and configuring a dialog to generate circular or regular-polygon vertices and edges.

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

1. Enter manual mesh-editing mode for an ArtMesh.
2. Click the **Geometric Mesh Layout** button in the mesh-edit toolbar.
3. Click or drag on the canvas to set the center anchor.
4. In the configuration dialog, select a shape preset (**Circle**, **Triangle**, **Quadrilateral**, **Hexagon**, **Octagon**) or customize the **Sides** and **Radius**, and optionally enable **Create Center Point**, **Connect Perimeter to Center**, and **Concentric Rings** (with equally spaced ring count slider).
5. Click **OK** to generate the vertices and edges.

## Capabilities

| Capability | Effect |
|---|---|
| `cubism.mesh.custom-tools` | Registers the custom tool in the native mesh-editing toolbar. |

## Permissions

| Permission | Scope | Effect |
|---|---|---|
| `turboism.ui.toolbar.mesh.contribute` | `application` | Contributes the Geometric Mesh Layout button to the mesh toolbar. |
| `turboism.cubism.model.read` | `application` | Reads editable-mesh vertex coordinates and camera projection. |
| `turboism.cubism.model.write` | `application` | Adds generated vertices and edges to the active editable mesh. |

## Privacy and data

No network connections or telemetry. Generated vertices and edges participate in native mesh-edit Undo/Redo.

## Status and limitations

Operates exclusively within single-ArtMesh manual mesh-editing mode.

## Troubleshooting

If the tool button is not visible, ensure an ArtMesh is open in manual mesh-editing mode and the plugin is enabled.

## Changelog

### 0.1.0

- Initial release.

## Support and license

[Issues](https://github.com/turboism/Turboism/issues) · [Project License](../../LICENSE)

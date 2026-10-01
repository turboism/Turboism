---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.selection-brush
version: 0.1.0
kind: feature
status: development
delivery: development-only
category: modeling
tags: mesh, selection, brush
turboismApi: "[0.1.0,0.2.0)"
requiresCubism: true
interface: embedded
---

# Selection Brush

The official Selection Brush contributes a mesh-toolbar tool and a radius slider to the Developer Preview. Its production code uses only Turboism's public SDK.

## What it does

- Selects vertices in the active ArtMesh's native mesh editor.
- Uses an 8–128 component-pixel radius, with a default of 32.
- Tests a circle on primary press and round-cap capsules while dragging. Hits are unioned and committed once on release.
- Locks radius and selection mode at press: plain replaces, Shift adds, and Ctrl removes. Ctrl takes precedence over Shift; changes during a stroke affect the next stroke.
- Leaves native selection unchanged for empty, cancelled, failed, stale, or Escape-terminated strokes.
- Projects native editable-mesh GL positions through the current modeling camera. Selection does not change authored geometry, dirty state, or Undo history.

## Requirements and compatibility

- Turboism API: `[0.1.0,0.2.0)`; the custom mesh-tool contracts are Preview API.
- Requires Cubism and an active ArtMesh mesh-edit session on an exact reviewed 5.2.03, 5.3.02, or 5.3.03 artifact.
- Interface mode: embedded. No plugin dependencies are declared.

## Install and enable

Use the Developer Preview bundle and enable Selection Brush in Plugin Management. Restart the Editor after changing plugin enabled state; the current manager applies these changes on the next launch.

## How to use

1. Select an ArtMesh and enter its mesh editor.
2. Open Cubism's Tool Details palette, selecting its tab if the Turboism palette is in front. Activate Selection Brush in the mesh toolbar and choose a radius; scroll the palette if the slider is below the visible area.
3. Press and drag over vertices, then release to apply the selection. Hold Shift to add or Ctrl to remove when starting the stroke.
4. Press Escape or choose a native mesh tool to deactivate the brush.

Six state icons are packaged under `icons/`: `selection-brush.png`, `selection-brush-active.png`, `selection-brush-rollover.png`, `selection-brush-selected.png`, `selection-brush-disabled.png`, and `selection-brush-disabled-selected.png`. Each resource belongs to its exact plugin generation.

## Capabilities

| Capability | User effect |
| --- | --- |
| `cubism.mesh.custom-tools` | Contributes and activates the brush in a verified native mesh-edit session. |

## Permissions

| Permission | Scope | Purpose |
| --- | --- | --- |
| `turboism.ui.toolbar.mesh.contribute` | application | Adds the tool button and radius slider. |
| `turboism.cubism.model.read` | application | Reads native editable-mesh vertex positions for hit testing. |
| `turboism.cubism.model.write` | application | Updates native vertex selection. |

## Privacy and data

The plugin makes no network connections and sends no telemetry. It reads active mesh data and writes native vertex selection. Radius is held in memory; no plugin files are written. Lifecycle and failure records may appear in the runtime log.

## Status and limitations

This is a development plugin with Preview SDK contracts. It acts on one active mesh session. Deactivation, Escape, native-tool switching, session end, toolbar rebuild, plugin disposal, host replacement, and runtime close invalidate stale handles and remove the owned overlay and listeners. Plugin changes require an Editor restart.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| Tool is missing | Enable the plugin, restart the Editor, and enter ArtMesh mesh-edit mode on a reviewed host. |
| Selection is unchanged | Confirm the stroke hits vertices and inspect the runtime log for rejected-session or projection diagnostics. |
| Radius change takes effect later | Radius is locked when the stroke starts; the new value applies to the next stroke. |

## Support and license

- Website: [turboism.dev](https://turboism.dev)
- Publisher: Turboism Contributors
- License: Project License

Developer contracts are documented in `sdk/src/main/java/dev/turboism/sdk/cubism/mesh/`.

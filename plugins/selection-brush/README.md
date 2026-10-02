---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.selection-brush
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: modeling
tags: mesh, selection, brush
turboismApi: "[0.1.0,0.2.0)"
requiresCubism: true
interface: embedded
---

# Selection Brush

The official Selection Brush contributes tools for ordinary modeling and mesh editing. It is included in Turboism release packages and uses only Turboism's public SDK.

## What it does

- Selects vertices in the active ArtMesh's native mesh editor.
- In ordinary modeling, selects current ArtMesh vertices and Warp Deformer control points across selected editable objects. The top-toolbar button sits immediately after the native Brush Selection tool, before its divider.
- Uses an 8–128 component-pixel radius, with a default of 32.
- Tests a circle on primary press and round-cap capsules while dragging. Hits are unioned and committed once on release.
- Locks radius and selection mode at press: plain replaces, Shift adds, and Ctrl removes. Ctrl takes precedence over Shift; changes during a stroke affect the next stroke.
- Leaves native selection unchanged for empty, cancelled, failed, stale, or Escape-terminated strokes.
- Paints the brush preview in `#7B68EE` at 55% opacity. Native canvas buttons keep their own mouse input, so mesh-edit confirmation works while the brush is active.
- Follows native lasso coordinates and the current displayed form before camera projection. Ordinary modeling uses native owner-qualified point references. Selection does not change authored geometry or dirty state. Mesh selection preserves Undo history; ordinary selection follows the native selection-only history behavior.

## Requirements and compatibility

- Turboism API: `[0.1.0,0.2.0)`; the custom mesh-tool contracts are Preview API.
- Requires an exact reviewed Cubism 5.2.03, 5.3.02, or 5.3.03 artifact. Ordinary modeling additionally requires its independently admitted point-selection and tool-lifecycle capability; missing that feature leaves mesh tools available.
- Interface mode: embedded. No plugin dependencies are declared.

## Install and enable

Install the plugin through Turboism's official Full or Thin installer, or use the Full archive. Select Selection Brush in the installer or enable it in Plugin Management. Restart the Editor after changing plugin enabled state; the current manager applies these changes on the next launch. Its delivery status is store candidate.

## How to use

1. Select an ArtMesh and enter its mesh editor.
2. Open Cubism's Tool Details palette, selecting its tab if the Turboism palette is in front. Activate Selection Brush in the mesh toolbar and choose a radius; scroll the palette if the slider is below the visible area.
3. Press and drag over vertices, then release to apply the selection. Hold Shift to add or Ctrl to remove when starting the stroke.
4. Press Escape or choose a native mesh tool to deactivate the brush. To finish mesh editing, click the native confirmation icon; it also removes the brush overlay.

Six 32×32 state icons use the native mesh toolbar's neutral palette and state backgrounds. The default state is transparent and has no frame. The original artwork combines vertex squares and a brush with a circular head, distinguishing it from native paint tools. It is packaged under `icons/`: `selection-brush.png`, `selection-brush-active.png`, `selection-brush-rollover.png`, `selection-brush-selected.png`, `selection-brush-disabled.png`, and `selection-brush-disabled-selected.png`. Each resource belongs to its exact plugin generation. Regenerate all six from the shared drawing with `python3 plugins/selection-brush/artwork/generate_icons.py` (requires Pillow).

For ordinary modeling, select editable ArtMeshes or Warp Deformers and activate the new top-toolbar button. Brush over visible points and release to apply the selection. Object selection is retained. Release also invalidates the native bounding-box cache so the next native redraw uses the selected points, following Select/Move behavior. Like native selection, two or more selected points define the box; zero or one selected point uses object bounds. Click the button again, press Escape, select a native tool, or enter mesh editing to stop it. Both modes share the plugin generation's in-memory radius; use the mesh toolbar slider to adjust it. Ordinary mode currently has no separate radius control.

Ordinary tools switch exclusively: activating Selection Brush clears the native tool highlights, including the visible Select/Move button beside Lasso, and choosing a native tool removes the brush overlay and its selected state. Brush release, Undo/Redo and Space panning keep Select/Move unselected while the brush is active. The new button does not take keyboard focus or draw a border or focus outline. Hold Space and drag to use native canvas panning; releasing Space keeps the brush active.

## Capabilities

| Capability | User effect |
| --- | --- |
| `cubism.mesh.custom-tools` | Contributes and activates the brush in a verified native mesh-edit session. |
| `cubism.modeling.custom-tools` | Contributes and activates the ordinary modeling tool through the service directory. |

## Permissions

| Permission | Scope | Purpose |
| --- | --- | --- |
| `turboism.ui.toolbar.mesh.contribute` | application | Adds the tool button and radius slider. |
| `turboism.ui.toolbar.main.contribute` | application | Adds the ordinary modeling toggle next to Brush Selection. |
| `turboism.cubism.model.read` | application | Reads current editable vertex and control-point positions. |
| `turboism.cubism.model.write` | application | Updates native point selection, including ordinary selection-only history. |

## Privacy and data

The plugin makes no network connections and sends no telemetry. It reads active mesh data and writes native vertex selection. Radius is held in memory; no plugin files are written. Lifecycle and failure records may appear in the runtime log.

## Status and limitations

The plugin has Preview status and is included in official release packages. Its tool services use Preview SDK contracts. Deactivation, Escape, native-tool switching, mode/document changes, plugin disposal, host replacement, and runtime close invalidate stale handles and remove the owned overlay and listeners. Toolbar reconciliation replaces widgets while retaining an otherwise valid activation. Plugin changes require an Editor restart. The basic ordinary-mode and mesh matrix, including Select/Move exclusivity, border/focus behavior and bounding-box refresh, passed exact-host validation on reviewed 5.2.03, 5.3.02 and 5.3.03 on 2026-10-02. Manual acceptance of the same production build passed on 5.2.03 that day. Non-default parameter forms, the nested-parent matrix, native menu/shortcut switching and complex document/owner/toolbar lifecycle scenarios still need broader host evidence. See [validation results](../../scripts/preview/README-mesh-edit-validation.md).

## Troubleshooting

| Symptom | Check |
| --- | --- |
| Tool is missing | Enable the plugin, restart a reviewed Editor, and select editable ArtMeshes/Warp Deformers or enter ArtMesh mesh-edit mode. |
| Selection is unchanged | Confirm the stroke hits vertices and inspect the runtime log for rejected-session or projection diagnostics. |
| Radius change takes effect later | Radius is locked when the stroke starts; the new value applies to the next stroke. |

## Support and license

- Website: [turboism.dev](https://turboism.dev)
- Publisher: Turboism Contributors
- License: Project License

Developer contracts are documented in `sdk/src/main/java/dev/turboism/sdk/cubism/mesh/` and `sdk/src/main/java/dev/turboism/sdk/cubism/modeling/`. Obtain ordinary tools through `context.services().get(ModelingToolRegistry.class)`; there is no legacy `PluginContext` getter.

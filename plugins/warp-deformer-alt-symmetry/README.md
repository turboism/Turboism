---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.warp-deformer-alt-symmetry
version: 0.1.0
kind: feature
status: development
delivery: development-only
category: modeling
tags: deformer, warp, editing, symmetry
turboismApi: "[0.1.0,0.2.0)"
requiresCubism: true
interface: none
---

# Warp Deformer Alt Symmetry

> **Official Turboism plugin** · **Status: Development**

Extends Cubism's native bounding-box Alt symmetric semantics to Warp Deformer
control points, with a canvas-strip mirror-axis button and a drawing-area hint.

| Detail | Value |
|---|---|
| Version | `0.1.0` |
| Plugin ID | `dev.turboism.plugin.warp-deformer-alt-symmetry` |
| Category | `modeling` |
| Tags | deformer, warp, editing, symmetry |
| Turboism API | `[0.1.0,0.2.0)` |
| Requires Cubism | Yes |
| Interface | `none` |
| License | Project License |

## What it does

- Mirrors Warp Deformer control points across the grid axes while you drag with
  `Alt` (vertical axis) or `Alt+Shift` (horizontal axis), joining native Undo/Redo.
- Contributes a three-state mirror-axis button (off / vertical / horizontal) to
  the canvas-top control strip.
- Shows the active mirror mode in the host's native drawing-area hint while armed.

## Requirements and compatibility

- **Turboism API:** `[0.1.0,0.2.0)`.
- **Cubism:** Requires Cubism. Turboism currently admits exact reviewed Editor
  artifacts `5.2.03`, `5.3.02`, and `5.3.03`; this plugin exposes each host-facing
  feature only when its declared services and capabilities are available.
- **Interface mode:** `none`.
- **Plugin dependencies:** None declared.

## Install and enable

This is a **development-only** module, not a published store listing or
release-delivery plugin. Load it only through the repository's development
runtime and enable it in **Plugin Management** when validating the
mirror-axis workflow.

## How to use

1. Arm the mirror axis by clicking the strip button: off → vertical → horizontal.
2. Drag a control point with `Alt` (vertical) or `Alt+Shift` (horizontal); the
   mirrored counterpart follows with the negated displacement.
3. Click the button again until it returns to the off state to disarm; the hint
   clears on its own.

## Capabilities

| Declared capability | User effect |
|---|---|
| `cubism.deformer.read` | Reads Warp Deformer grids to snapshot and diff dragged points. |
| `cubism.deformer.alt-axis-mirror` | Mirrors the dragged control points across the armed grid axis. |

## Permissions

| Permission | Scope | Why it is requested |
|---|---|---|
| `turboism.cubism.model.read` | `application` | Snapshots Warp Deformer grids on Alt press and diffs them on release to detect the dragged control points. |
| `turboism.cubism.model.write` | `application` | Commits the mirrored control-point positions through replaceGrid so the symmetric move joins native Undo/Redo. |
| `turboism.ui.toolbar.contribute` | `application` | Adds the mirror-axis toggle button to the canvas-top control strip. |
| `turboism.ui.canvas.hint` | `application` | Shows the armed-axis hint over the drawing area in the host's native lower-right hint surface and clears it on disarm. |

## Privacy and data

### Network

Makes no network connections.

### Local data

Does not persist plugin data. The mirror reads model snapshots and writes only
the mirrored control-point positions of the gesture in progress.

### Telemetry

No telemetry is sent by this plugin.

Plugin lifecycle and failure records can appear in Turboism's session log and
Cubism's host log with the plugin ID attached.

## Status and limitations

- **Status:** Development.
- Undo needs two steps for v1: one for the native drag, one for the mirrored
  commit (the SDK exposes no history-merge API yet).
- Points resting on the active mirror axis only follow the native drag.
- The strip button and hint are omitted when the exact host integration is
  unavailable.

## Troubleshooting

| Symptom | What to check |
|---|---|
| Strip button is missing | Confirm the plugin is enabled and the modeling view is active; check the host log for `BUTTON_MOUNTED`. |
| Drag does not mirror | Confirm a state other than off is armed, the dragged object is a Warp Deformer, and the mirrored point exists across the axis. |
| Hint does not appear | Check that the canvas-hint permission is granted and the host exposes the native hint surface. |

## Support and license

- **Project website:** [https://turboism.dev](https://turboism.dev)
- **Publisher:** Turboism Contributors
- Distributed under the project license.

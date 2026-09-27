---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.boundingbox-warp-mirror
version: 0.1.0
kind: feature
status: development
delivery: development-only
category: modeling
tags: deformer, warp, editing, symmetry, boundingbox
turboismApi: "[0.1.0,0.2.0)"
requiresCubism: true
interface: none
---

# BoundingBox Warp Mirror

> **Official Turboism plugin** · **Status: Development**

Ports the legacy BoundingBox overlay mirror workflow: an overlay button opens a
mirror-direction dialog and applies a one-shot, whole-object mirror to every selected
Warp Deformer. It complements `warp-deformer-alt-symmetry` (the interactive Alt drag
mirror), which is untouched by this plugin.

| Detail | Value |
|---|---|
| Version | `0.1.0` |
| Plugin ID | `dev.turboism.plugin.boundingbox-warp-mirror` |
| Category | `modeling` |
| Tags | deformer, warp, editing, symmetry, boundingbox |
| Turboism API | `[0.1.0,0.2.0)` |
| Requires Cubism | Yes |
| Interface | `none` |
| License | Project License |

## What it does

- Contributes one button to the native bounding-box overlay (`boundingbox-warp-mirror.mirror`).
- Clicking it opens a modal direction chooser with four directions — Left → Right,
  Right → Left, Top → Bottom, Bottom → Top — plus the「不影响子物体」"do not affect
  child objects" checkbox, checked by default.
- Confirming mirrors the entire control-point grid of each selected Warp Deformer.
  Cancelling performs no write.
- With preservation enabled, every descendant (ArtMesh, nested Warp, and Rotation
  deformer) keeps its current canvas geometry; when the runtime cannot prove that
  guarantee it rejects the operation instead of silently skipping descendants.
- Parent write plus all descendant compensations commit inside one host transaction —
  a single Undo entry — and failures roll back with a typed, user-visible reason.

## Requirements and compatibility

- Requires a running Cubism Editor session backed by the Turboism runtime.
- Requires the exact verified Editor model slice; on unsupported host versions the
  service reports `UNAVAILABLE` and the overlay button still renders.
- Direction labels describe the actual result; the legacy dialog's inverted left/right
  wiring is intentionally corrected.

## Install and enable

Bundled with the Turboism distribution. Enable or disable the plugin from the plugin
management surface; the overlay button appears while the plugin is enabled.

## How to use

1. Select one or more Warp Deformers (parents are applied before children).
2. Click the mirror button on the bounding-box overlay.
3. Pick a direction, keep or clear「不影响子物体」, and confirm.
4. Blocked or failed targets are listed in a notice; nothing is written for them.

## Capabilities

- `cubism.deformer.read`
- `cubism.deformer.warp-mirror`

## Permissions

- `turboism.cubism.model.read`
- `turboism.cubism.model.write`
- `turboism.ui.overlay.contribute`

## Privacy and data

The plugin reads live selection and model geometry only; it stores nothing and sends
nothing anywhere.

## Status and limitations

- Development status. Whole-object mirroring only — control-point subsets are not
  supported. ArtMesh and Rotation Deformers are never mirror targets.
- Rejects locked targets/descendants, Glue-bearing models, non-stored keyform edit
  contexts, degenerate grids, and unsolvable compensation instead of weakening the
  preservation guarantee.

## Troubleshooting

- Button does nothing on click: check that at least one Warp Deformer is selected.
- A target is reported blocked: read the typed reason in the notice (lock state, Glue
  presence, interpolated edit context, or unsolvable compensation).

## Support and license

Project License. Report issues through the standard Turboism support channel.

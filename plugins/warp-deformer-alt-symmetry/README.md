# Warp Deformer Alt Symmetry

Extends Cubism's native bounding-box Alt symmetric semantics to **Warp Deformer
control points**.

## Behaviour

| Gesture | Mirror axis | Effect |
| --- | --- | --- |
| `Alt` + drag a control point | Vertical grid axis | The point's mirror across the vertical grid axis moves with the X displacement negated (Y follows). |
| `Alt+Shift` + drag a control point | Horizontal grid axis | The point's mirror across the horizontal grid axis moves with the Y displacement negated (X follows). |

* Points resting on the active mirror axis are their own counterparts and only
  follow the native drag.
* Multi-selection drags mirror every moved point.
* Undo needs two steps for v1: one for the native drag, one for the mirrored
  commit (the SDK exposes no history-merge API yet).

## How it works

Host-validation evidence showed that native viewport drags never route through
the SDK `DeformerHooks` grid-replace family, while the SDK `replaceGrid` write
path round-trips and joins native Undo/Redo. The plugin therefore:

1. snapshots every Warp Deformer grid on `Alt` mouse press (`turboism.cubism.model.read`);
2. diffs the grids on mouse release to find the points the native drag moved;
3. computes the axis-mirrored counterpart positions and commits them through
   `replaceGrid` (`turboism.cubism.model.write`).

The diff-based ingress requires no canvas-to-grid projection and is a no-op
when the gesture does not move Warp Deformer control points.

## Validation

* `AltAxisMirrorPlannerTest` — offline planner contract (axis mapping, negated
  components, on-axis skip, multi-point, epsilon jitter, size mismatch).
* Exact-host end-to-end validation: see
  `validation/warp-deformer-alt-symmetry-host-probe/` and
  `./gradlew validateWarpAltSymmetryHost5303`.

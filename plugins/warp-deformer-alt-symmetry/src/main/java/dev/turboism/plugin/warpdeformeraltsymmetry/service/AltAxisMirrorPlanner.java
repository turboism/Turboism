package dev.turboism.plugin.warpdeformeraltsymmetry.service;

import dev.turboism.sdk.cubism.model.Point2;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Plans the axis-symmetric counterpart motion for a Warp Deformer control-point
 * drag, generalising the native bounding-box Alt semantics.
 *
 * <p>The planner is index based and delta based: the counterpart of a moved
 * control point is its mirror across the chosen grid axis, and the counterpart
 * receives the gesture displacement with the mirrored component negated. No
 * canvas projection is required, so the plan is derived purely from the grid
 * snapshots taken before and after the native drag.</p>
 *
 * <p>Semantics (agreed product decision for v1):</p>
 * <ul>
 *   <li>{@link Axis#VERTICAL} (Alt): mirror across the vertical grid axis —
 *       the partner of column {@code c} is column {@code width - 1 - c} in the
 *       same row; the partner's X displacement is negated, Y follows.</li>
 *   <li>{@link Axis#HORIZONTAL} (Alt+Shift): mirror across the horizontal grid
 *       axis — the partner of row {@code r} is row {@code height - 1 - r} in
 *       the same column; the partner's Y displacement is negated, X follows.</li>
 * </ul>
 *
 * <p>Points resting on the mirror axis are their own counterparts and are
 * skipped: the native drag already moved them. When both a point and its
 * counterpart moved in the same gesture, the mirrored assignment wins so the
 * committed grid is exactly symmetric for the moved set.</p>
 */
public final class AltAxisMirrorPlanner {

    /** Displacement below this magnitude is treated as "did not move". */
    public static final float MOVE_EPSILON = 1.0e-3f;

    /** Mirror axis selected by the gesture modifiers. */
    public enum Axis { VERTICAL, HORIZONTAL }

    private AltAxisMirrorPlanner() {
    }

    /**
     * Computes the counterpart assignments for one committed drag.
     *
     * @param rows    grid transform divisions (rows), so the grid has rows+1 point rows
     * @param columns grid transform divisions (columns), so the grid has columns+1 point columns
     * @param before  control points captured on mouse press, row-major
     * @param after   control points captured on mouse release, row-major
     * @param axis    mirror axis chosen by the gesture modifiers
     * @return counterpart index to new position; empty when nothing must be mirrored
     */
    public static Map<Integer, Point2> planMirror(
        final int rows,
        final int columns,
        final List<Point2> before,
        final List<Point2> after,
        final Axis axis
    ) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        Objects.requireNonNull(axis, "axis");
        final int width = columns + 1;
        final int height = rows + 1;
        if (before.size() != after.size() || before.size() != width * height) {
            throw new IllegalArgumentException(
                "grid snapshots must both contain (rows + 1) * (columns + 1) points"
            );
        }
        final Map<Integer, Point2> assignments = new LinkedHashMap<>();
        for (int index = 0; index < after.size(); index++) {
            final float dx = after.get(index).x() - before.get(index).x();
            final float dy = after.get(index).y() - before.get(index).y();
            if (Math.abs(dx) <= MOVE_EPSILON && Math.abs(dy) <= MOVE_EPSILON) {
                continue;
            }
            final int row = index / width;
            final int column = index % width;
            final int partner = axis == Axis.VERTICAL
                ? row * width + (width - 1 - column)
                : (height - 1 - row) * width + column;
            if (partner == index) {
                continue;
            }
            final Point2 current = after.get(partner);
            final Point2 target = axis == Axis.VERTICAL
                ? new Point2(current.x() - dx, current.y() + dy)
                : new Point2(current.x() + dx, current.y() - dy);
            if (Math.abs(target.x() - current.x()) <= MOVE_EPSILON
                && Math.abs(target.y() - current.y()) <= MOVE_EPSILON) {
                continue;
            }
            assignments.put(partner, target);
        }
        return assignments;
    }
}

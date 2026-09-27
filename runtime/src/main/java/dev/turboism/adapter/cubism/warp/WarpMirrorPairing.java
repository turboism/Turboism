package dev.turboism.adapter.cubism.warp;

import dev.turboism.sdk.cubism.mirror.WarpMirrorDirection;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Index-symmetric warp mirror pairing.
 *
 * <p>The legacy nearest-source pairing ({@code buildWarpMirroredPositions}) was
 * never fixed for irregular grids: it greedily matched each target-side point to
 * whichever unused source sat nearest its reflection, so a deformed grid could
 * mis-pair points and produce an output that is not symmetric at all. This
 * implementation mirrors by grid index instead — target point {@code (c, r)}
 * always adopts the reflection of its structural counterpart
 * {@code (lineCount-1-c, r)} — so the result is a true mirror image of the
 * source half by construction, on any grid shape.</p>
 *
 * <p>The fold line is the grid's own centre seam in its current (deformed)
 * state rather than the bounding-box centre: for an even point count it runs
 * midway between the two centre columns/rows, for an odd count through the
 * centre line itself. Centre-line points are skipped by index, never rewritten,
 * which keeps the seam anchored where the two halves currently join.</p>
 *
 * <p>{@link WarpMirrorDirection#LEFT_TO_RIGHT} copies the left half onto the
 * right; {@link WarpMirrorDirection#TOP_TO_BOTTOM} copies the top half onto the
 * bottom (canvas y grows downward).</p>
 */
public final class WarpMirrorPairing {

    private WarpMirrorPairing() { }

    /**
     * Computes mirrored control-point positions for a whole warp grid.
     *
     * @param positions flattened x/y control points, row-major,
     *                  length {@code pointColumns*pointRows*2}
     * @param pointColumns grid control-point columns ({@code grid.columns() + 1})
     * @param pointRows grid control-point rows ({@code grid.rows() + 1})
     * @param direction mirror direction
     * @return the pairing result, or {@code null} when the grid is degenerate
     *         or contains non-finite positions
     */
    public static Result mirror(
        final float[] positions,
        final int pointColumns,
        final int pointRows,
        final WarpMirrorDirection direction
    ) {
        if (positions == null || pointColumns < 2 || pointRows < 2
            || positions.length != pointColumns * pointRows * 2) {
            return null;
        }
        for (float value : positions) {
            if (!Float.isFinite(value)) {
                return null;
            }
        }

        final boolean verticalAxis = direction == WarpMirrorDirection.LEFT_TO_RIGHT
            || direction == WarpMirrorDirection.RIGHT_TO_LEFT;
        // Points along the mirrored axis direction (columns for left/right,
        // rows for top/bottom); each perpendicular line is mirrored pairwise.
        final int lineCount = verticalAxis ? pointColumns : pointRows;
        final int lineSize = verticalAxis ? pointRows : pointColumns;
        final int centerLeft = (lineCount - 1) / 2;
        final int centerRight = lineCount / 2;
        final float axis = foldAxis(positions, pointColumns, verticalAxis, centerLeft, centerRight);
        // The labelled source side is spatial, not storage order: a warp whose
        // flattened column/row order runs opposite to the canvas still mirrors
        // the side the user named. Index pairing stays symmetric either way.
        final boolean ascending = lineMean(positions, pointColumns, verticalAxis, 0)
            <= lineMean(positions, pointColumns, verticalAxis, lineCount - 1);
        final boolean wantsLowSide = direction == WarpMirrorDirection.LEFT_TO_RIGHT
            || direction == WarpMirrorDirection.TOP_TO_BOTTOM;
        final boolean lowerIsSource = wantsLowSide == ascending;

        final float[] mirrored = positions.clone();
        final Set<Integer> changedPointIndices = new LinkedHashSet<>();
        int pairedCount = 0;
        for (int line = 0; line < lineSize; line++) {
            for (int index = 0; index < lineCount; index++) {
                // Targets are the indices strictly on the far side of the fold:
                // for an odd line count the centre index stays untouched.
                final boolean target = lowerIsSource
                    ? index > centerLeft
                    : index < centerRight;
                if (!target) {
                    continue;
                }
                final int source = lineCount - 1 - index;
                final int targetPoint = pointIndex(line, index, pointColumns, verticalAxis);
                final int sourcePoint = pointIndex(line, source, pointColumns, verticalAxis);
                final int targetOffset = targetPoint * 2;
                final int sourceOffset = sourcePoint * 2;
                if (verticalAxis) {
                    mirrored[targetOffset] = 2f * axis - positions[sourceOffset];
                    mirrored[targetOffset + 1] = positions[sourceOffset + 1];
                } else {
                    mirrored[targetOffset] = positions[sourceOffset];
                    mirrored[targetOffset + 1] = 2f * axis - positions[sourceOffset + 1];
                }
                changedPointIndices.add(targetPoint);
                pairedCount++;
            }
        }
        if (pairedCount <= 0) {
            return null;
        }
        return new Result(mirrored, pairedCount, Set.copyOf(changedPointIndices));
    }

    /**
     * Fold position along the mirrored axis: the mean of the two centre
     * columns' (or rows') mean coordinates. For an odd point count both
     * indices coincide, so the axis passes through the centre line itself.
     */
    private static float foldAxis(
        final float[] positions,
        final int pointColumns,
        final boolean verticalAxis,
        final int centerLeft,
        final int centerRight
    ) {
        return (lineMean(positions, pointColumns, verticalAxis, centerLeft)
            + lineMean(positions, pointColumns, verticalAxis, centerRight)) * 0.5f;
    }

    /**
     * Mean coordinate along the mirrored axis direction of one grid column
     * (vertical axis) or row (horizontal axis).
     */
    private static float lineMean(
        final float[] positions,
        final int pointColumns,
        final boolean verticalAxis,
        final int index
    ) {
        final int lineSize = verticalAxis ? (positions.length / 2) / pointColumns : pointColumns;
        float sum = 0f;
        for (int line = 0; line < lineSize; line++) {
            final int point = verticalAxis
                ? line * pointColumns + index
                : index * pointColumns + line;
            sum += positions[point * 2 + (verticalAxis ? 0 : 1)];
        }
        return sum / lineSize;
    }

    /** Flattened index of grid point {@code index} on perpendicular {@code line}. */
    private static int pointIndex(
        final int line,
        final int index,
        final int pointColumns,
        final boolean verticalAxis
    ) {
        return verticalAxis
            ? line * pointColumns + index
            : index * pointColumns + line;
    }

    /**
     * Mirroring output.
     *
     * @param positions mirrored flattened positions (same length as the input)
     * @param pairedCount number of target-side points rewritten
     * @param changedPointIndices indices of rewritten control points
     */
    public record Result(float[] positions, int pairedCount, Set<Integer> changedPointIndices) { }
}

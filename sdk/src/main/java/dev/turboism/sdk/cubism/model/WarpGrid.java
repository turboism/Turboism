package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.PreviewApi;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Immutable Warp Deformer grid committed as one Editor operation. */
@PreviewApi
public record WarpGrid(
    int rows,
    int columns,
    boolean quadTransform,
    List<Point2> controlPoints
) {

    public WarpGrid {
        if (rows < 1 || columns < 1) {
            throw new IllegalArgumentException("rows and columns must be positive");
        }
        controlPoints = List.copyOf(Objects.requireNonNull(controlPoints, "controlPoints"));
        controlPoints.forEach(value -> Objects.requireNonNull(value, "control point"));
        final long expected = (long) (rows + 1) * (columns + 1);
        if (expected > Integer.MAX_VALUE || controlPoints.size() != (int) expected) {
            throw new IllegalArgumentException(
                "controlPoints size must equal (rows + 1) * (columns + 1)"
            );
        }
    }

    /**
     * Returns a copy of this grid with one control point moved. Row and column
     * counts are preserved, so the resulting grid stays well formed.
     *
     * @param index position of the control point in row-major order
     * @param x new horizontal coordinate
     * @param y new vertical coordinate
     * @return a new grid; this instance is unchanged
     * @throws IndexOutOfBoundsException when {@code index} is outside the
     *     control-point range
     */
    public WarpGrid withControlPoint(final int index, final float x, final float y) {
        final ArrayList<Point2> changed = new ArrayList<>(controlPoints);
        changed.set(index, new Point2(x, y));
        return new WarpGrid(rows, columns, quadTransform, changed);
    }
}

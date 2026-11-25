package dev.turboism.sdk.cubism.temporarydeform;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.model.Point2;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Activation-bound context supplied to a {@link TemporaryDeformSession}.
 *
 * <p>All methods fail closed after the session deactivates or the host view changes.</p>
 */
@CubismEditor(from = "5.0.00")
public interface TemporaryDeformContext {

    /** Returns the editable Warp Deformers bound to this temporary deform activation. */
    List<WarpDeformerTarget> selectedWarpDeformers();

    /** Refreshes the Tool Details panel to reflect updated option descriptors. */
    void refreshToolDetails();

    /** Requests a modeling canvas repaint. */
    void requestRepaint();

    /** Exits the active temporary deform tool cleanly. */
    void unbind();

    /** Native-backed handle to one selected editable Warp Deformer during a temporary deform session. */
    @CubismEditor(from = "5.0.00")
    interface WarpDeformerTarget {

        /** Returns the deformer's stable identifier. */
        DeformerId id();

        /** Returns the modeling edit level currently active in the view (typically 1, 2, or 3). */
        int editLevel();

        /** Returns the conversion-division control grid in canvas coordinates. */
        GridLayer conversionGrid();

        /** Returns the Bezier control-point anchor grid in canvas coordinates when available. */
        Optional<GridLayer> bezierGrid();

        /**
         * Translates conversion control points relative to the current stroke baseline.
         *
         * @param deltaByPointIndex canvas-space displacement keyed by row-major point index
         */
        void applyConversionDeltas(Map<Integer, Point2> deltaByPointIndex);

        /**
         * Translates Bezier anchor points (and their tangent handles) relative to the current
         * stroke baseline, then projects the conversion division grid from the updated Bezier surface.
         *
         * @param deltaByAnchorIndex canvas-space displacement keyed by row-major anchor index
         */
        void applyBezierDeltas(Map<Integer, Point2> deltaByAnchorIndex);
    }

    /**
     * Immutable snapshot of one 2D control-point grid in canvas coordinates.
     *
     * @param rows number of control-point rows ({@code row + 1})
     * @param columns number of control-point columns ({@code col + 1})
     * @param points row-major point list of size {@code rows * columns}
     */
    @CubismEditor(from = "5.0.00")
    record GridLayer(int rows, int columns, List<Point2> points) {
        public GridLayer {
            if (rows < 1 || columns < 1) {
                throw new IllegalArgumentException("rows and columns must be positive");
            }
            points = List.copyOf(Objects.requireNonNull(points, "points"));
            points.forEach(point -> Objects.requireNonNull(point, "point"));
            if ((long) rows * columns != points.size()) {
                throw new IllegalArgumentException("points size must equal rows * columns");
            }
        }
    }
}

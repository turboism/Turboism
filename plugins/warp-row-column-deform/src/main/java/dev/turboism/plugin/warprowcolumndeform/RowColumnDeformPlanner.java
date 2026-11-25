package dev.turboism.plugin.warprowcolumndeform;

import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.cubism.temporarydeform.TemporaryDeformContext;
import dev.turboism.sdk.cubism.temporarydeform.TemporaryDeformRenderScene;
import dev.turboism.sdk.ui.appearance.UiColor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Pure stateless geometry, hit-testing, displacement, and overlay scene builder for moving whole
 * rows or columns of Warp Deformer control points.
 */
final class RowColumnDeformPlanner {

    static final int EDIT_LEVEL_BEZIER = 2;
    static final float BASE_HANDLE_OFFSET_PX = 24.0F;
    static final float MIN_CAMERA_SCALE = 0.05F;
    static final float MAX_CAMERA_SCALE = 20.0F;

    private static final float DEGENERATE_LENGTH_EPS = 1.0E-4F;
    private static final float UNIT_VECTOR_X = 1.0F;
    private static final float UNIT_VECTOR_Y = 0.0F;
    private static final float BASE_LINE_WIDTH = 1.0F;
    private static final float HIGHLIGHT_LINE_WIDTH = 2.5F;
    private static final float INVALID_ALPHA_SCALE = 0.35F;

    private static final int NORMAL_POINT_PX = 5;
    private static final int HIGHLIGHT_POINT_PX = 8;
    private static final int EDGE_HANDLE_PX = 10;
    private static final int ACTIVE_HANDLE_PX = 13;

    private static final float CHANNEL_MAX = 255.0F;
    private static final UiColor BASE_GRID_COLOR = rgba(120, 185, 255, 165);
    private static final UiColor ROW_HIGHLIGHT_COLOR = rgba(0, 212, 200, 240);
    private static final UiColor COLUMN_HIGHLIGHT_COLOR = rgba(255, 128, 72, 240);
    private static final UiColor POINT_COLOR = rgba(235, 245, 255, 215);
    private static final UiColor ROW_HANDLE_COLOR = rgba(22, 196, 180, 235);
    private static final UiColor COLUMN_HANDLE_COLOR = rgba(246, 114, 72, 235);
    private static final UiColor ACTIVE_HANDLE_COLOR = rgba(255, 224, 96, 255);
    private static final UiColor BORDER_DARK = rgba(20, 24, 32, 240);

    private RowColumnDeformPlanner() {}

    /** Selects the active grid layer for one Warp Deformer based on Edit Level and user override. */
    static GridGeometry resolveGrid(
            final int targetIndex,
            final TemporaryDeformContext.WarpDeformerTarget target,
            final GridKind preferredKind) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(preferredKind, "preferredKind");
        final Optional<TemporaryDeformContext.GridLayer> bezier = target.bezierGrid();
        final boolean useBezier =
                switch (preferredKind) {
                    case BEZIER -> bezier.isPresent();
                    case CONVERSION -> false;
                    case AUTO -> target.editLevel() <= EDIT_LEVEL_BEZIER && bezier.isPresent();
                };
        final TemporaryDeformContext.GridLayer layer = useBezier ? bezier.orElseThrow() : target.conversionGrid();
        return new GridGeometry(targetIndex, layer.rows(), layer.columns(), layer.points(), useBezier);
    }

    /** Scales screen-space handle offset into document coordinates using Cubism's document-units-per-pixel scale. */
    static float canvasHandleOffset(final float cameraScale) {
        final float safeScale = Math.max(MIN_CAMERA_SCALE, Math.min(MAX_CAMERA_SCALE, cameraScale));
        return BASE_HANDLE_OFFSET_PX * safeScale;
    }

    /** Computes outward edge-handle positions around every row (left/right) and column (top/bottom). */
    static List<HandlePlacement> computeEdgeHandles(final List<GridGeometry> grids, final float handleOffsetCanvas) {
        Objects.requireNonNull(grids, "grids");
        final List<HandlePlacement> handles = new ArrayList<>();
        for (final GridGeometry grid : grids) {
            for (int r = 0; r < grid.rows(); r++) {
                final Point2 left = grid.pointAt(r, 0);
                final Point2 leftInner = grid.columns() > 1 ? grid.pointAt(r, 1) : left;
                final Point2 right = grid.pointAt(r, grid.columns() - 1);
                final Point2 rightInner = grid.columns() > 1 ? grid.pointAt(r, grid.columns() - 2) : right;
                handles.add(new HandlePlacement(
                        grid.targetIndex(),
                        Axis.ROW,
                        r,
                        outwardOffset(left, leftInner, -UNIT_VECTOR_X, -UNIT_VECTOR_Y, handleOffsetCanvas)));
                handles.add(new HandlePlacement(
                        grid.targetIndex(),
                        Axis.ROW,
                        r,
                        outwardOffset(right, rightInner, UNIT_VECTOR_X, UNIT_VECTOR_Y, handleOffsetCanvas)));
            }
            for (int c = 0; c < grid.columns(); c++) {
                final Point2 top = grid.pointAt(0, c);
                final Point2 topInner = grid.rows() > 1 ? grid.pointAt(1, c) : top;
                final Point2 bottom = grid.pointAt(grid.rows() - 1, c);
                final Point2 bottomInner = grid.rows() > 1 ? grid.pointAt(grid.rows() - 2, c) : bottom;
                handles.add(new HandlePlacement(
                        grid.targetIndex(),
                        Axis.COLUMN,
                        c,
                        outwardOffset(top, topInner, UNIT_VECTOR_Y, UNIT_VECTOR_X, handleOffsetCanvas)));
                handles.add(new HandlePlacement(
                        grid.targetIndex(),
                        Axis.COLUMN,
                        c,
                        outwardOffset(bottom, bottomInner, UNIT_VECTOR_Y, -UNIT_VECTOR_X, handleOffsetCanvas)));
            }
        }
        return List.copyOf(handles);
    }

    /** Hits outward edge handles first, then interior grid lines/points according to {@link AxisMode}. */
    static Optional<SelectionTarget> hitTest(
            final List<GridGeometry> grids,
            final float pointerX,
            final float pointerY,
            final float hitRadius,
            final AxisMode axisMode,
            final float handleOffsetCanvas) {
        Objects.requireNonNull(grids, "grids");
        Objects.requireNonNull(axisMode, "axisMode");
        final float effectiveRadius = Math.max(DEGENERATE_LENGTH_EPS, hitRadius);
        final float radiusSq = effectiveRadius * effectiveRadius;

        HandlePlacement bestHandle = null;
        float bestHandleDistSq = Float.POSITIVE_INFINITY;
        for (final HandlePlacement handle : computeEdgeHandles(grids, handleOffsetCanvas)) {
            if (!axisPermitted(axisMode, handle.axis())) {
                continue;
            }
            final float distSq = distanceSq(
                    pointerX, pointerY, handle.position().x(), handle.position().y());
            if (distSq <= radiusSq && distSq < bestHandleDistSq) {
                bestHandleDistSq = distSq;
                bestHandle = handle;
            }
        }
        if (bestHandle != null) {
            return Optional.of(
                    new SelectionTarget(bestHandle.targetIndex(), bestHandle.axis(), bestHandle.lineIndex(), true));
        }

        SelectionTarget bestSegment = null;
        float bestSegmentDistSq = Float.POSITIVE_INFINITY;
        for (final GridGeometry grid : grids) {
            if (axisPermitted(axisMode, Axis.ROW)) {
                for (int r = 0; r < grid.rows(); r++) {
                    final float distSq = distanceToRowSq(grid, r, pointerX, pointerY);
                    if (distSq <= radiusSq && distSq < bestSegmentDistSq) {
                        bestSegmentDistSq = distSq;
                        bestSegment = new SelectionTarget(grid.targetIndex(), Axis.ROW, r, false);
                    }
                }
            }
            if (axisPermitted(axisMode, Axis.COLUMN)) {
                for (int c = 0; c < grid.columns(); c++) {
                    final float distSq = distanceToColumnSq(grid, c, pointerX, pointerY);
                    if (distSq <= radiusSq && distSq < bestSegmentDistSq) {
                        bestSegmentDistSq = distSq;
                        bestSegment = new SelectionTarget(grid.targetIndex(), Axis.COLUMN, c, false);
                    }
                }
            }
        }
        return Optional.ofNullable(bestSegment);
    }

    /** Computes uniform displacement vectors for every point on the selected row or column. */
    static Map<Integer, Point2> computeDeltas(
            final GridGeometry grid,
            final SelectionTarget selection,
            final Point2 pressPos,
            final Point2 currentPos,
            final boolean shiftLocked) {
        Objects.requireNonNull(grid, "grid");
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(pressPos, "pressPos");
        Objects.requireNonNull(currentPos, "currentPos");
        if (selection.targetIndex() != grid.targetIndex()) {
            return Map.of();
        }
        float dx = currentPos.x() - pressPos.x();
        float dy = currentPos.y() - pressPos.y();
        if (shiftLocked) {
            if (Math.abs(dx) >= Math.abs(dy)) {
                dy = 0.0F;
            } else {
                dx = 0.0F;
            }
        }
        final Point2 delta = new Point2(dx, dy);
        final Map<Integer, Point2> result = new LinkedHashMap<>();
        if (selection.axis() == Axis.ROW) {
            final int row = selection.lineIndex();
            if (row >= 0 && row < grid.rows()) {
                for (int c = 0; c < grid.columns(); c++) {
                    result.put(row * grid.columns() + c, delta);
                }
            }
        } else {
            final int col = selection.lineIndex();
            if (col >= 0 && col < grid.columns()) {
                for (int r = 0; r < grid.rows(); r++) {
                    result.put(r * grid.columns() + col, delta);
                }
            }
        }
        return Map.copyOf(result);
    }

    /** Builds multi-layer canvas line and point overlay geometry for the current interaction state. */
    static TemporaryDeformRenderScene buildRenderScene(
            final List<GridGeometry> grids,
            final SelectionTarget hovered,
            final SelectionTarget active,
            final AxisMode axisMode,
            final float cameraScale,
            final boolean valid) {
        Objects.requireNonNull(grids, "grids");
        Objects.requireNonNull(axisMode, "axisMode");
        if (grids.isEmpty()) {
            return TemporaryDeformRenderScene.EMPTY;
        }
        final float alphaFactor = valid ? 1.0F : INVALID_ALPHA_SCALE;
        final List<TemporaryDeformRenderScene.LineLayer> lineLayers = new ArrayList<>();
        final List<TemporaryDeformRenderScene.PointLayer> pointLayers = new ArrayList<>();

        final List<List<Point2>> baseLines = new ArrayList<>();
        final List<Point2> allPoints = new ArrayList<>();
        for (final GridGeometry grid : grids) {
            for (int r = 0; r < grid.rows(); r++) {
                baseLines.add(rowPolyline(grid, r));
            }
            for (int c = 0; c < grid.columns(); c++) {
                baseLines.add(columnPolyline(grid, c));
            }
            allPoints.addAll(grid.points());
        }
        if (!baseLines.isEmpty()) {
            lineLayers.add(new TemporaryDeformRenderScene.LineLayer(
                    baseLines, scaleAlpha(BASE_GRID_COLOR, alphaFactor), BASE_LINE_WIDTH));
        }

        final SelectionTarget highlighted = active != null ? active : hovered;
        final List<Point2> highlightedPoints = new ArrayList<>();
        if (highlighted != null && highlighted.targetIndex() < grids.size()) {
            final GridGeometry targetGrid = grids.get(highlighted.targetIndex());
            final List<Point2> poly = highlighted.axis() == Axis.ROW
                    ? rowPolyline(targetGrid, highlighted.lineIndex())
                    : columnPolyline(targetGrid, highlighted.lineIndex());
            if (!poly.isEmpty()) {
                final UiColor lineColor = highlighted.axis() == Axis.ROW ? ROW_HIGHLIGHT_COLOR : COLUMN_HIGHLIGHT_COLOR;
                lineLayers.add(new TemporaryDeformRenderScene.LineLayer(
                        List.of(poly), scaleAlpha(lineColor, alphaFactor), HIGHLIGHT_LINE_WIDTH));
                highlightedPoints.addAll(poly);
            }
        }

        if (!allPoints.isEmpty()) {
            pointLayers.add(new TemporaryDeformRenderScene.PointLayer(
                    allPoints, NORMAL_POINT_PX, scaleAlpha(POINT_COLOR, alphaFactor), BORDER_DARK));
        }
        if (!highlightedPoints.isEmpty()) {
            final UiColor ptColor = highlighted.axis() == Axis.ROW ? ROW_HIGHLIGHT_COLOR : COLUMN_HIGHLIGHT_COLOR;
            pointLayers.add(new TemporaryDeformRenderScene.PointLayer(
                    highlightedPoints, HIGHLIGHT_POINT_PX, scaleAlpha(ptColor, alphaFactor), BORDER_DARK));
        }

        final float handleOffset = canvasHandleOffset(cameraScale);
        final List<Point2> rowHandlePoints = new ArrayList<>();
        final List<Point2> colHandlePoints = new ArrayList<>();
        final List<Point2> activeHandlePoints = new ArrayList<>();
        for (final HandlePlacement handle : computeEdgeHandles(grids, handleOffset)) {
            if (!axisPermitted(axisMode, handle.axis())) {
                continue;
            }
            final boolean isSelectedHandle = highlighted != null
                    && highlighted.targetIndex() == handle.targetIndex()
                    && highlighted.axis() == handle.axis()
                    && highlighted.lineIndex() == handle.lineIndex();
            if (isSelectedHandle) {
                activeHandlePoints.add(handle.position());
            } else if (handle.axis() == Axis.ROW) {
                rowHandlePoints.add(handle.position());
            } else {
                colHandlePoints.add(handle.position());
            }
        }
        if (!rowHandlePoints.isEmpty()) {
            pointLayers.add(new TemporaryDeformRenderScene.PointLayer(
                    rowHandlePoints, EDGE_HANDLE_PX, scaleAlpha(ROW_HANDLE_COLOR, alphaFactor), BORDER_DARK));
        }
        if (!colHandlePoints.isEmpty()) {
            pointLayers.add(new TemporaryDeformRenderScene.PointLayer(
                    colHandlePoints, EDGE_HANDLE_PX, scaleAlpha(COLUMN_HANDLE_COLOR, alphaFactor), BORDER_DARK));
        }
        if (!activeHandlePoints.isEmpty()) {
            pointLayers.add(new TemporaryDeformRenderScene.PointLayer(
                    activeHandlePoints, ACTIVE_HANDLE_PX, scaleAlpha(ACTIVE_HANDLE_COLOR, alphaFactor), BORDER_DARK));
        }
        return new TemporaryDeformRenderScene(lineLayers, pointLayers);
    }

    private static boolean axisPermitted(final AxisMode mode, final Axis axis) {
        return mode == AxisMode.AUTO
                || (mode == AxisMode.ROW && axis == Axis.ROW)
                || (mode == AxisMode.COLUMN && axis == Axis.COLUMN);
    }

    private static Point2 outwardOffset(
            final Point2 edgePoint,
            final Point2 innerPoint,
            final float fallbackX,
            final float fallbackY,
            final float distance) {
        float dx = edgePoint.x() - innerPoint.x();
        float dy = edgePoint.y() - innerPoint.y();
        final float len = (float) Math.hypot(dx, dy);
        if (len > DEGENERATE_LENGTH_EPS) {
            dx /= len;
            dy /= len;
        } else {
            dx = fallbackX;
            dy = fallbackY;
        }
        return new Point2(edgePoint.x() + dx * distance, edgePoint.y() + dy * distance);
    }

    private static float distanceToRowSq(final GridGeometry grid, final int row, final float px, final float py) {
        float best = Float.POSITIVE_INFINITY;
        for (int c = 0; c < grid.columns(); c++) {
            final Point2 p = grid.pointAt(row, c);
            best = Math.min(best, distanceSq(px, py, p.x(), p.y()));
            if (c + 1 < grid.columns()) {
                best = Math.min(best, distanceToSegmentSq(px, py, p, grid.pointAt(row, c + 1)));
            }
        }
        return best;
    }

    private static float distanceToColumnSq(final GridGeometry grid, final int col, final float px, final float py) {
        float best = Float.POSITIVE_INFINITY;
        for (int r = 0; r < grid.rows(); r++) {
            final Point2 p = grid.pointAt(r, col);
            best = Math.min(best, distanceSq(px, py, p.x(), p.y()));
            if (r + 1 < grid.rows()) {
                best = Math.min(best, distanceToSegmentSq(px, py, p, grid.pointAt(r + 1, col)));
            }
        }
        return best;
    }

    private static float distanceToSegmentSq(final float px, final float py, final Point2 a, final Point2 b) {
        final float vx = b.x() - a.x();
        final float vy = b.y() - a.y();
        final float lenSq = vx * vx + vy * vy;
        if (lenSq <= DEGENERATE_LENGTH_EPS * DEGENERATE_LENGTH_EPS) {
            return distanceSq(px, py, a.x(), a.y());
        }
        final float t = Math.max(0.0F, Math.min(1.0F, ((px - a.x()) * vx + (py - a.y()) * vy) / lenSq));
        final float projX = a.x() + t * vx;
        final float projY = a.y() + t * vy;
        return distanceSq(px, py, projX, projY);
    }

    private static float distanceSq(final float ax, final float ay, final float bx, final float by) {
        final float dx = ax - bx;
        final float dy = ay - by;
        return dx * dx + dy * dy;
    }

    private static List<Point2> rowPolyline(final GridGeometry grid, final int row) {
        if (row < 0 || row >= grid.rows()) {
            return List.of();
        }
        final List<Point2> line = new ArrayList<>(grid.columns());
        for (int c = 0; c < grid.columns(); c++) {
            line.add(grid.pointAt(row, c));
        }
        return List.copyOf(line);
    }

    private static List<Point2> columnPolyline(final GridGeometry grid, final int col) {
        if (col < 0 || col >= grid.columns()) {
            return List.of();
        }
        final List<Point2> line = new ArrayList<>(grid.rows());
        for (int r = 0; r < grid.rows(); r++) {
            line.add(grid.pointAt(r, col));
        }
        return List.copyOf(line);
    }

    private static UiColor rgba(final int r, final int g, final int b, final int a) {
        return new UiColor(r / CHANNEL_MAX, g / CHANNEL_MAX, b / CHANNEL_MAX, a / CHANNEL_MAX);
    }

    private static UiColor scaleAlpha(final UiColor color, final float alphaFactor) {
        if (alphaFactor >= 1.0F) {
            return color;
        }
        final float alpha = Math.max(0.0F, Math.min(1.0F, color.alpha() * alphaFactor));
        return new UiColor(color.red(), color.green(), color.blue(), alpha);
    }

    /** Direct or automatic axis restriction mode shown in Tool Details. */
    enum AxisMode {
        AUTO("auto"),
        ROW("row"),
        COLUMN("column");

        private final String optionValue;

        AxisMode(final String optionValue) {
            this.optionValue = optionValue;
        }

        String optionValue() {
            return optionValue;
        }

        static AxisMode fromOptionValue(final String value) {
            for (final AxisMode mode : values()) {
                if (mode.optionValue.equals(value)) {
                    return mode;
                }
            }
            return AUTO;
        }
    }

    /** Grid granularity preference synchronized with Edit Level 2 vs 3. */
    enum GridKind {
        AUTO("auto"),
        BEZIER("bezier"),
        CONVERSION("conversion");

        private final String optionValue;

        GridKind(final String optionValue) {
            this.optionValue = optionValue;
        }

        String optionValue() {
            return optionValue;
        }

        static GridKind fromOptionValue(final String value) {
            for (final GridKind kind : values()) {
                if (kind.optionValue.equals(value)) {
                    return kind;
                }
            }
            return AUTO;
        }
    }

    /** Row or column selection kind. */
    enum Axis {
        ROW,
        COLUMN
    }

    /** Snapshot of one resolved target deformer grid in row-major order. */
    record GridGeometry(int targetIndex, int rows, int columns, List<Point2> points, boolean bezier) {
        GridGeometry {
            points = List.copyOf(Objects.requireNonNull(points, "points"));
        }

        Point2 pointAt(final int row, final int col) {
            return points.get(row * columns + col);
        }
    }

    /** Canvas coordinate of one outward edge handle. */
    record HandlePlacement(int targetIndex, Axis axis, int lineIndex, Point2 position) {}

    /** Active or hovered row/column target within one warp deformer. */
    record SelectionTarget(int targetIndex, Axis axis, int lineIndex, boolean fromHandle) {}
}

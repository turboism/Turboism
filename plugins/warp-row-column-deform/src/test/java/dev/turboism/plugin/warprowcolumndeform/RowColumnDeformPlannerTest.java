package dev.turboism.plugin.warprowcolumndeform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.cubism.temporarydeform.TemporaryDeformContext;
import dev.turboism.sdk.cubism.temporarydeform.TemporaryDeformRenderScene;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RowColumnDeformPlannerTest {

    @Test
    void autoGridSelectionFollowsEditLevel2BezierAndLevel3Conversion() {
        final TemporaryDeformContext.GridLayer bezier = grid2x2(1.0F);
        final TemporaryDeformContext.GridLayer conversion = grid3x3();

        final var level2Target = stubTarget(2, conversion, bezier);
        assertTrue(RowColumnDeformPlanner.resolveGrid(0, level2Target, RowColumnDeformPlanner.GridKind.AUTO)
                .bezier());

        final var level3Target = stubTarget(3, conversion, bezier);
        assertFalse(RowColumnDeformPlanner.resolveGrid(0, level3Target, RowColumnDeformPlanner.GridKind.AUTO)
                .bezier());
        assertTrue(RowColumnDeformPlanner.resolveGrid(0, level3Target, RowColumnDeformPlanner.GridKind.BEZIER)
                .bezier());
        assertFalse(RowColumnDeformPlanner.resolveGrid(0, level2Target, RowColumnDeformPlanner.GridKind.CONVERSION)
                .bezier());
    }

    @Test
    void hitsOutwardEdgeHandlesForRowAndColumnAndComputesUniformDeltasWithShiftLock() {
        final var grid = new RowColumnDeformPlanner.GridGeometry(
                0,
                3,
                3,
                List.of(
                        new Point2(0, 0),
                        new Point2(100, 0),
                        new Point2(200, 0),
                        new Point2(0, 100),
                        new Point2(100, 100),
                        new Point2(200, 100),
                        new Point2(0, 200),
                        new Point2(100, 200),
                        new Point2(200, 200)),
                false);

        final Optional<RowColumnDeformPlanner.SelectionTarget> rowHandleHit = RowColumnDeformPlanner.hitTest(
                List.of(grid), -24.0F, 100.0F, 12.0F, RowColumnDeformPlanner.AxisMode.AUTO, 24.0F);
        assertTrue(rowHandleHit.isPresent());
        assertEquals(RowColumnDeformPlanner.Axis.ROW, rowHandleHit.get().axis());
        assertEquals(1, rowHandleHit.get().lineIndex());
        assertTrue(rowHandleHit.get().fromHandle());

        final Map<Integer, Point2> rowDeltas = RowColumnDeformPlanner.computeDeltas(
                grid, rowHandleHit.get(), new Point2(-24.0F, 100.0F), new Point2(6.0F, 110.0F), false);
        assertEquals(3, rowDeltas.size());
        assertEquals(new Point2(30.0F, 10.0F), rowDeltas.get(3));
        assertEquals(new Point2(30.0F, 10.0F), rowDeltas.get(4));
        assertEquals(new Point2(30.0F, 10.0F), rowDeltas.get(5));

        final Optional<RowColumnDeformPlanner.SelectionTarget> colHandleHit = RowColumnDeformPlanner.hitTest(
                List.of(grid), 200.0F, 224.0F, 12.0F, RowColumnDeformPlanner.AxisMode.COLUMN, 24.0F);
        assertTrue(colHandleHit.isPresent());
        assertEquals(RowColumnDeformPlanner.Axis.COLUMN, colHandleHit.get().axis());
        assertEquals(2, colHandleHit.get().lineIndex());

        final Map<Integer, Point2> shiftColDeltas = RowColumnDeformPlanner.computeDeltas(
                grid, colHandleHit.get(), new Point2(200.0F, 224.0F), new Point2(205.0F, 260.0F), true);
        assertEquals(3, shiftColDeltas.size());
        assertEquals(new Point2(0.0F, 36.0F), shiftColDeltas.get(2));
        assertEquals(new Point2(0.0F, 36.0F), shiftColDeltas.get(5));
        assertEquals(new Point2(0.0F, 36.0F), shiftColDeltas.get(8));

        final TemporaryDeformRenderScene scene = RowColumnDeformPlanner.buildRenderScene(
                List.of(grid), colHandleHit.get(), null, RowColumnDeformPlanner.AxisMode.AUTO, 1.0F, true);
        assertEquals(2, scene.lineLayers().size());
        assertEquals(5, scene.pointLayers().size());

        assertEquals(48.0F, RowColumnDeformPlanner.canvasHandleOffset(2.0F), 1.0E-3F);
        assertEquals(12.0F, RowColumnDeformPlanner.canvasHandleOffset(0.5F), 1.0E-3F);
    }

    private static TemporaryDeformContext.GridLayer grid2x2(final float scale) {
        return new TemporaryDeformContext.GridLayer(
                2,
                2,
                List.of(
                        new Point2(0, 0),
                        new Point2(100 * scale, 0),
                        new Point2(0, 100 * scale),
                        new Point2(100 * scale, 100 * scale)));
    }

    private static TemporaryDeformContext.GridLayer grid3x3() {
        final List<Point2> pts = new ArrayList<>();
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                pts.add(new Point2(c * 50.0F, r * 50.0F));
            }
        }
        return new TemporaryDeformContext.GridLayer(3, 3, pts);
    }

    private static TemporaryDeformContext.WarpDeformerTarget stubTarget(
            final int editLevel,
            final TemporaryDeformContext.GridLayer conversion,
            final TemporaryDeformContext.GridLayer bezier) {
        return new TemporaryDeformContext.WarpDeformerTarget() {
            @Override
            public DeformerId id() {
                return new DeformerId("D_TEST");
            }

            @Override
            public int editLevel() {
                return editLevel;
            }

            @Override
            public TemporaryDeformContext.GridLayer conversionGrid() {
                return conversion;
            }

            @Override
            public Optional<TemporaryDeformContext.GridLayer> bezierGrid() {
                return Optional.ofNullable(bezier);
            }

            @Override
            public void applyConversionDeltas(final Map<Integer, Point2> deltaByPointIndex) {}

            @Override
            public void applyBezierDeltas(final Map<Integer, Point2> deltaByAnchorIndex) {}
        };
    }
}

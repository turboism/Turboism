package dev.turboism.plugin.warpdeformeraltsymmetry.service;

import dev.turboism.sdk.cubism.model.Point2;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Offline behaviour contract of the axis-symmetric mirror planner. */
final class AltAxisMirrorPlannerTest {

    private static List<Point2> flatGrid(
        final int width, final int height, final float base
    ) {
        final List<Point2> points = new ArrayList<>();
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                points.add(new Point2(base + column, base + row));
            }
        }
        return points;
    }

    private static List<Point2> moved(
        final List<Point2> source, final int width,
        final int row, final int column, final float dx, final float dy
    ) {
        final List<Point2> copy = new ArrayList<>(source);
        final int index = row * width + column;
        copy.set(index, new Point2(
            copy.get(index).x() + dx, copy.get(index).y() + dy));
        return copy;
    }

    @Test
    void verticalAltMirrorsAcrossVerticalGridAxisWithNegatedX() {
        // 1 division each way -> 2x2 points; drag top-left corner right/down.
        final List<Point2> before = flatGrid(2, 2, 0f);
        final List<Point2> after = moved(before, 2, 0, 0, 10f, 4f);
        final Map<Integer, Point2> plan = AltAxisMirrorPlanner.planMirror(
            1, 1, before, after, AltAxisMirrorPlanner.Axis.VERTICAL);
        // Partner of (row 0, col 0) in a 2-wide grid is (row 0, col 1) = index 1.
        assertEquals(1, plan.size());
        final Point2 target = plan.get(1);
        // X negated (-10), Y follows (+4): (1,0) -> (1 - 10, 0 + 4).
        assertEquals(-9f, target.x(), 1.0e-4f);
        assertEquals(4f, target.y(), 1.0e-4f);
    }

    @Test
    void horizontalAltShiftMirrorsAcrossHorizontalGridAxisWithNegatedY() {
        final List<Point2> before = flatGrid(2, 2, 0f);
        final List<Point2> after = moved(before, 2, 0, 0, 10f, 4f);
        final Map<Integer, Point2> plan = AltAxisMirrorPlanner.planMirror(
            1, 1, before, after, AltAxisMirrorPlanner.Axis.HORIZONTAL);
        // Partner of (row 0, col 0) in a 2-tall grid is (row 1, col 0) = index 2.
        assertEquals(1, plan.size());
        final Point2 target = plan.get(2);
        // X follows (+10), Y negated (-4): (0,1) -> (0 + 10, 1 - 4).
        assertEquals(10f, target.x(), 1.0e-4f);
        assertEquals(-3f, target.y(), 1.0e-4f);
    }

    @Test
    void onAxisPointIsItsOwnPartnerAndSkipped() {
        // 2 divisions -> 3x3 points; centre column of the vertical axis.
        final List<Point2> before = flatGrid(3, 3, 0f);
        final List<Point2> after = moved(before, 3, 0, 1, 6f, 0f);
        final Map<Integer, Point2> plan = AltAxisMirrorPlanner.planMirror(
            2, 2, before, after, AltAxisMirrorPlanner.Axis.VERTICAL);
        assertTrue(plan.isEmpty(), "a point on the mirror axis has no counterpart");
    }

    @Test
    void interiorPointMirrorsToDiametricallyOppositeInteriorPoint() {
        // 5 divisions -> 6x6 points; drag an interior point on row 2, col 1.
        final int width = 6;
        final List<Point2> before = flatGrid(width, width, 100f);
        final List<Point2> after = moved(before, width, 2, 1, -7f, 3f);
        final Map<Integer, Point2> plan = AltAxisMirrorPlanner.planMirror(
            5, 5, before, after, AltAxisMirrorPlanner.Axis.VERTICAL);
        assertEquals(1, plan.size());
        final int partnerIndex = 2 * width + (width - 1 - 1);
        final Point2 source = before.get(2 * width + 1);
        final Point2 partner = before.get(partnerIndex);
        final Point2 target = plan.get(partnerIndex);
        assertEquals(partner.x() + 7f, target.x(), 1.0e-4f);
        assertEquals(partner.y() + 3f, target.y(), 1.0e-4f);
        // Guard against an accidental assignment onto the dragged point itself.
        assertTrue(plan.keySet().stream().noneMatch(i -> i == 2 * width + 1));
        // And the source point actually moved in the diff.
        assertEquals(-7f, after.get(2 * width + 1).x() - source.x(), 1.0e-4f);
    }

    @Test
    void multiPointDragMirrorsEveryMovedPoint() {
        final int width = 4;
        final List<Point2> before = flatGrid(width, width, 0f);
        List<Point2> after = moved(before, width, 0, 0, 5f, 0f);
        after = moved(after, width, 1, 2, 0f, -3f);
        final Map<Integer, Point2> plan = AltAxisMirrorPlanner.planMirror(
            3, 3, before, after, AltAxisMirrorPlanner.Axis.VERTICAL);
        assertEquals(2, plan.size());
        assertTrue(plan.containsKey(0 * width + (width - 1 - 0)));
        assertTrue(plan.containsKey(1 * width + (width - 1 - 2)));
    }

    @Test
    void noMotionYieldsEmptyPlan() {
        final List<Point2> grid = flatGrid(3, 3, 5f);
        assertTrue(AltAxisMirrorPlanner.planMirror(
            2, 2, grid, grid, AltAxisMirrorPlanner.Axis.VERTICAL).isEmpty());
    }

    @Test
    void subEpsilonJitterIsIgnored() {
        final List<Point2> before = flatGrid(2, 2, 0f);
        final List<Point2> after = moved(before, 2, 0, 0, 5.0e-4f, 5.0e-4f);
        assertTrue(AltAxisMirrorPlanner.planMirror(
            1, 1, before, after, AltAxisMirrorPlanner.Axis.VERTICAL).isEmpty());
    }

    @Test
    void mismatchedSnapshotSizesAreRejected() {
        final List<Point2> before = flatGrid(3, 3, 0f);
        final List<Point2> after = flatGrid(2, 2, 0f);
        assertThrows(IllegalArgumentException.class, () ->
            AltAxisMirrorPlanner.planMirror(
                2, 2, before, after, AltAxisMirrorPlanner.Axis.VERTICAL));
    }
}

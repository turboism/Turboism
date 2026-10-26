package dev.turboism.plugin.mcp;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.cubism.id.*;
import dev.turboism.sdk.cubism.model.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class McpWarpQualityTest {
    static List<Point2> points(float... v) {
        var r = new ArrayList<Point2>();
        for (int i = 0; i < v.length; i += 2) r.add(new Point2(v[i], v[i + 1]));
        return r;
    }

    static final List<Point2> UNIT = points(0, 0, 1, 0, 0, 1, 1, 1);

    static WarpAuthoringSnapshot snapshot(List<Point2> reference, List<Point2> target) {
        return new WarpAuthoringSnapshot(
                new DeformerId("warp"),
                "source",
                1,
                1,
                false,
                List.of(new WarpAuthoringSnapshot.Axis("axis", new ParameterId("x"), -1, 0, 1, List.of(0f, 1f))),
                List.of(
                        new WarpAuthoringSnapshot.Cell("ref", List.of(new WarpAuthoringSnapshot.Coordinate("axis", 0))),
                        new WarpAuthoringSnapshot.Cell(
                                "target", List.of(new WarpAuthoringSnapshot.Coordinate("axis", 1)))),
                List.of(
                        new WarpAuthoringSnapshot.Form("ref", reference),
                        new WarpAuthoringSnapshot.Form("target", target)));
    }

    static McpWarpQuality.Report report(List<Point2> reference, List<Point2> target, double threshold, int max) {
        return McpWarpQuality.analyze(snapshot(reference, target), "ref", List.of("target"), threshold, max);
    }

    static Set<Object> codes(McpWarpQuality.Report r) {
        var c = new HashSet<Object>();
        r.findings().forEach(f -> c.add(f.get("code")));
        return c;
    }

    @Test
    void regularTranslatedAndMirroredReferenceAreValid() {
        assertEquals(0, report(UNIT, points(3, 4, 5, 4, 3, 7, 5, 7), 1e-8, 200).findingCount());
        var mirrored = points(0, 0, -1, 0, 0, 1, -1, 1);
        assertEquals(0, report(mirrored, mirrored, 0, 200).findingCount());
        assertEquals(Set.of("CELL_ORIENTATION_REVERSED"), codes(report(UNIT, mirrored, 0, 200)));
    }

    @Test
    void bowTieAndConcaveCellsHaveMixedJacobianSigns() {
        for (var p : List.of(points(0, 0, 1, 1, 0, 1, 1, 0), points(0, 0, 1, 0, 0, 1, -1, -1))) {
            var r = report(UNIT, p, 0, 200);
            assertTrue(codes(r).contains("FOLDED_BILINEAR_CELL"));
            assertEquals(1, r.skippedComparisons());
            assertEquals(List.of(0, 1, 2, 3), r.findings().get(0).get("controlPointIndices"));
        }
    }

    @Test
    void collapsedCornersThinCellsAndToleranceAreExplicit() {
        assertTrue(codes(report(UNIT, points(0, 0, 1, 0, 0, 1, 1, 0), 0, 200)).contains("DEGENERATE_BILINEAR_CELL"));
        var thin = points(0, 0, 1, 0, 0, 1e-6f, 1, 1e-6f);
        assertEquals(0, report(UNIT, thin, 0, 200).findingCount());
        assertTrue(codes(report(UNIT, thin, 1e-5, 200)).contains("DEGENERATE_BILINEAR_CELL"));
        var zero = report(UNIT, Collections.nCopies(4, new Point2(0, 0)), 0, 200);
        assertEquals(0.0, zero.findings().get(0).get("observedMinimumAbsJacobianRatio"));
    }

    @Test
    void badReferenceSkipsComparisonAndTruncationDoesNotStopCounting() {
        var bad = Collections.nCopies(4, new Point2(0, 0));
        var r = report(bad, bad, 0, 1);
        assertEquals(2, r.findingCount());
        assertEquals(1, r.findings().size());
        assertEquals(1, r.referenceIrregularCells());
        assertEquals(1, r.skippedComparisons());
        assertThrows(UnsupportedOperationException.class, () -> r.findings().add(Map.of()));
    }

    @Test
    void exactSignsHandleExtremeFiniteFloatCoordinates() {
        for (float scale : List.of(Float.MIN_VALUE, Float.MIN_NORMAL, Float.MAX_VALUE)) {
            var p = points(0, 0, scale, 0, 0, scale, scale, scale);
            assertEquals(0, report(p, p, 0, 200).findingCount(), "scale=" + scale);
        }
        // Near parallel float vectors with a unit exact determinant but double-product cancellation.
        var p = points(0, 0, 16777216, 16777215, 16777215, 16777214, 33554432, 33554428);
        var cell = McpWarpQuality.cell(p, List.of(0, 1, 2, 3), 0);
        assertTrue(cell.minimumRatio() > 0);
        assertFalse(cell.degenerate());
    }

    @Test
    void validatesScopeAndRejectsBudgetBeforeProducingPartialReport() {
        var s = snapshot(UNIT, UNIT);
        assertThrows(
                IllegalArgumentException.class,
                () -> McpWarpQuality.analyze(s, "ref", List.of("target", "target"), 0, 1));
        assertThrows(NoSuchElementException.class, () -> McpWarpQuality.analyze(s, "missing", List.of("target"), 0, 1));
        for (double v : new double[] {-1, 2, Double.NaN, Double.POSITIVE_INFINITY})
            assertThrows(IllegalArgumentException.class, () -> report(UNIT, UNIT, v, 1));
        var p = Collections.nCopies(10002, new Point2(0, 0));
        var big = new WarpAuthoringSnapshot(
                s.deformerId(),
                s.sourceGuid(),
                1,
                5000,
                false,
                s.axes(),
                s.cells(),
                List.of(new WarpAuthoringSnapshot.Form("ref", p), new WarpAuthoringSnapshot.Form("target", p)));
        assertThrows(
                McpWarpQuality.BudgetExceeded.class, () -> McpWarpQuality.analyze(big, "ref", List.of("target"), 0, 1));
    }

    @Test
    void rowMajorCellsReportActualRowColumnAndIndices() {
        var reference = points(0, 0, 1, 0, 2, 0, 0, 1, 1, 1, 2, 1);
        var target = points(0, 0, 1, 0, 2, 0, 0, 1, 1, 1, 1, 0);
        var b = snapshot(UNIT, UNIT);
        var s = new WarpAuthoringSnapshot(
                b.deformerId(),
                b.sourceGuid(),
                1,
                2,
                true,
                b.axes(),
                b.cells(),
                List.of(
                        new WarpAuthoringSnapshot.Form("ref", reference),
                        new WarpAuthoringSnapshot.Form("target", target)));
        var r = McpWarpQuality.analyze(s, "ref", List.of("target"), 0, 200);
        assertEquals(2, r.cellCount());
        assertFalse(r.findings().isEmpty());
        for (var f : r.findings()) {
            assertEquals(0, f.get("row"));
            assertEquals(1, f.get("column"));
            assertEquals(List.of(1, 2, 4, 5), f.get("controlPointIndices"));
        }
    }

    @Test
    void sharedOrdinaryFormsAreReadableButUnreferencedFormsAreNot() {
        var b = snapshot(UNIT, UNIT);
        var s = new WarpAuthoringSnapshot(
                b.deformerId(),
                b.sourceGuid(),
                1,
                1,
                false,
                b.axes(),
                List.of(
                        new WarpAuthoringSnapshot.Cell("ref", List.of(new WarpAuthoringSnapshot.Coordinate("axis", 0))),
                        new WarpAuthoringSnapshot.Cell(
                                "ref", List.of(new WarpAuthoringSnapshot.Coordinate("axis", 1)))),
                b.forms());
        assertEquals(0, McpWarpQuality.analyze(s, "ref", List.of("ref"), 0, 1).findingCount());
        assertThrows(
                UnsupportedOperationException.class, () -> McpWarpQuality.analyze(s, "ref", List.of("target"), 0, 1));
    }

    @Test
    void cellSignsBoundDenseBilinearJacobianSamples() {
        var rng = new Random(20261025);
        for (int n = 0; n < 200; n++) {
            float[] coords = new float[8];
            for (int i = 0; i < 8; i++) coords[i] = rng.nextInt(21) - 10;
            var p = points(coords);
            var c = McpWarpQuality.cell(p, List.of(0, 1, 2, 3), 0);
            if (!c.regular()) continue;
            for (int u = 0; u <= 10; u++)
                for (int v = 0; v <= 10; v++) {
                    double x = u / 10.0, y = v / 10.0;
                    double ux = (1 - y) * (p.get(1).x() - p.get(0).x())
                            + y * (p.get(3).x() - p.get(2).x());
                    double uy = (1 - y) * (p.get(1).y() - p.get(0).y())
                            + y * (p.get(3).y() - p.get(2).y());
                    double vx = (1 - x) * (p.get(2).x() - p.get(0).x())
                            + x * (p.get(3).x() - p.get(1).x());
                    double vy = (1 - x) * (p.get(2).y() - p.get(0).y())
                            + x * (p.get(3).y() - p.get(1).y());
                    assertEquals(c.sign(), (int) Math.signum(ux * vy - uy * vx));
                }
        }
    }
}

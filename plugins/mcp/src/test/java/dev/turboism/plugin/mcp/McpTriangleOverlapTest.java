package dev.turboism.plugin.mcp;

import static dev.turboism.plugin.mcp.McpGeometryQualityTest.points;
import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.cubism.model.Point2;
import java.util.*;
import org.junit.jupiter.api.Test;

class McpTriangleOverlapTest {
    static final List<Integer> TWO = List.of(0, 1, 2, 3, 4, 5);

    @Test
    void detectsCrossingContainmentCoincidenceAndBothWindings() {
        assertOverlap(points(0, 0, 6, 0, 3, 6, 0, 4, 6, 4, 3, -2), true);
        assertOverlap(points(0, 0, 10, 0, 0, 10, 1, 1, 2, 1, 1, 2), true);
        assertOverlap(points(0, 0, 10, 0, 0, 10, 1, 2, 2, 1, 1, 1), true);
        assertOverlap(points(0, 0, 2, 0, 0, 2, 0, 2, 2, 0, 0, 0), true);
    }

    @Test
    void excludesSharedEdgesVerticesAndTangentBoundaryContacts() {
        assertOverlap(points(0, 0, 2, 0, 0, 2, 2, 0, 2, 2, 0, 2), false);
        assertOverlap(points(0, 0, 2, 0, 0, 2, 0, 0, -1, 0, 0, -1), false);
        assertOverlap(points(0, 0, 2, 0, 0, 2, 1, 0, 1, -1, 2, -1), false);
        assertOverlap(points(0, 0, 2, 0, 0, 2, 1, 0, 3, 0, 2, -1), false);
        assertOverlap(points(0, 0, 1, 0, 0, 1, 3, 3, 4, 3, 3, 4), false);
    }

    @Test
    void adjacencyIsNotAnExemptionForFoldedInteriors() {
        var triangles = List.of(0, 1, 2, 0, 1, 3);
        var hits = new ArrayList<List<Integer>>();
        var result = McpTriangleOverlap.check(
                points(0, 0, 2, 0, 0, 2, 1, 1), triangles, 10000, (a, b) -> hits.add(List.of(a, b)));
        assertTrue(result.complete());
        assertEquals(List.of(List.of(0, 1)), hits);
        assertOverlap(points(0, 0, 3, 0, 0, 3, 0, 0, 2, 1, 1, 2), true);
    }

    @Test
    void zeroAreaTrianglesHaveNoPositiveAreaInterior() {
        assertOverlap(points(0, 0, 2, 0, 0, 2, -1, 1, 1, 1, 3, 1), false);
        assertOverlap(points(0, 0, 0, 0, 0, 0, 0, 0, 2, 0, 0, 2), false);
    }

    @Test
    void exactFallbackRetainsSliverLostByDoubleCancellation() {
        float large = 1e30F, small = 1e-30F;
        assertOverlap(points(large, large, -large, -large, small, 0, large, large, -large, -large, small, 0), true);
    }

    @Test
    void extremeAndSubnormalFloatInputsStayFiniteAndCorrect() {
        float high = Float.MAX_VALUE, low = Float.MIN_VALUE;
        assertOverlap(points(high, high, -high, high, 0, -high, high, high, -high, high, 0, -high), true);
        assertOverlap(points(0, 0, low, 0, 0, low, 0, 0, low, 0, 0, low), true);
        assertOverlap(points(-0F, 0, 1, 0, 0, 1, 0, -0F, -1, 0, 0, -1), false);
    }

    @Test
    void budgetExhaustionNeverPretendsCompleteAndIncludesFallbackCost() {
        var result = McpTriangleOverlap.check(points(0, 0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1), TWO, 1, (a, b) -> fail());
        assertFalse(result.complete());
        assertTrue(result.workUsed() <= 1);
        float large = 1e30F, small = 1e-30F;
        result = McpTriangleOverlap.check(
                points(large, large, -large, -large, small, 0), List.of(0, 1, 2), 10, (a, b) -> fail());
        assertFalse(result.complete());
        assertTrue(result.workUsed() <= 10);
    }

    @Test
    void spatialSweepAvoidsQuadraticSparsePairsButBudgetsDenseCandidateVisits() {
        var points = new ArrayList<Point2>();
        var topology = new ArrayList<Integer>();
        for (int i = 0; i < 1000; i++) {
            points.addAll(points(3 * i, 0, 3 * i + 1, 0, 3 * i, 1));
            topology.addAll(List.of(3 * i, 3 * i + 1, 3 * i + 2));
        }
        var sparse = McpTriangleOverlap.check(points, topology, 20000, (a, b) -> fail());
        assertTrue(sparse.complete());
        assertEquals(0, sparse.pairsTested());
        assertEquals(999, sparse.sweepVisits());
        points.clear();
        for (int i = 0; i < 1000; i++) points.addAll(points(0, 3 * i, 1, 3 * i, 0, 3 * i + 1));
        var dense = McpTriangleOverlap.check(points, topology, 20000, (a, b) -> fail());
        assertFalse(dense.complete());
        assertEquals(0, dense.pairsTested());
        assertTrue(dense.sweepVisits() > 0);
        assertTrue(dense.workUsed() <= 20000);
    }

    @Test
    void agreesWithIndependentPolygonClippingForRandomIntegerTriangles() {
        var random = new Random(76219);
        for (int sample = 0; sample < 2000; sample++) {
            var p = new ArrayList<Point2>();
            for (int i = 0; i < 6; i++) p.add(new Point2(random.nextInt(21) - 10, random.nextInt(21) - 10));
            boolean expected = clippedArea(p.subList(0, 3), p.subList(3, 6)) > 1e-10;
            assertOverlap(p, expected);
        }
    }

    @Test
    void translationsScaleAndVertexPermutationsPreserveOverlap() {
        var base = points(0, 0, 6, 0, 3, 6, 0, 4, 6, 4, 3, -2);
        for (float scale : new float[] {.001F, 1, 1024}) {
            var transformed = new ArrayList<Point2>();
            for (var p : base) transformed.add(new Point2(p.x() * scale + 17, p.y() * scale - 4));
            for (int i = 0; i < 3; i++) {
                var permuted = List.of(
                        transformed.get(i),
                        transformed.get((i + 2) % 3),
                        transformed.get((i + 1) % 3),
                        transformed.get(3 + (i + 1) % 3),
                        transformed.get(3 + (i + 2) % 3),
                        transformed.get(3 + i));
                assertOverlap(permuted, true);
            }
        }
    }

    private static void assertOverlap(List<Point2> positions, boolean expected) {
        var pairs = new ArrayList<List<Integer>>();
        var result = McpTriangleOverlap.check(positions, TWO, 100000, (a, b) -> pairs.add(List.of(a, b)));
        assertTrue(result.complete(), positions.toString());
        assertEquals(expected, !pairs.isEmpty(), positions.toString());
        if (expected) assertEquals(List.of(List.of(0, 1)), pairs);
    }

    // Independent Sutherland-Hodgman clipping/area oracle, only for bounded integer samples.
    private record P(double x, double y) {}

    private static double clippedArea(List<Point2> a, List<Point2> b) {
        double sign = Math.signum(cross(
                new P(b.get(0).x(), b.get(0).y()),
                new P(b.get(1).x(), b.get(1).y()),
                new P(b.get(2).x(), b.get(2).y())));
        if (sign == 0) return 0;
        List<P> polygon = new ArrayList<>();
        for (var p : a) polygon.add(new P(p.x(), p.y()));
        for (int i = 0; i < 3; i++) {
            var edgeA = new P(b.get(i).x(), b.get(i).y());
            var edgeB = new P(b.get((i + 1) % 3).x(), b.get((i + 1) % 3).y());
            if (polygon.isEmpty()) return 0;
            var next = new ArrayList<P>();
            var previous = polygon.get(polygon.size() - 1);
            double previousDistance = sign * cross(edgeA, edgeB, previous);
            for (var current : polygon) {
                double distance = sign * cross(edgeA, edgeB, current);
                if ((distance >= 0) != (previousDistance >= 0)) {
                    double t = previousDistance / (previousDistance - distance);
                    next.add(new P(
                            previous.x + t * (current.x - previous.x), previous.y + t * (current.y - previous.y)));
                }
                if (distance >= 0) next.add(current);
                previous = current;
                previousDistance = distance;
            }
            polygon = next;
        }
        double area = 0;
        for (int i = 0; i < polygon.size(); i++) {
            var first = polygon.get(i);
            var next = polygon.get((i + 1) % polygon.size());
            area += first.x * next.y - first.y * next.x;
        }
        return Math.abs(area) * .5;
    }

    private static double cross(P a, P b, P c) {
        return (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x);
    }
}

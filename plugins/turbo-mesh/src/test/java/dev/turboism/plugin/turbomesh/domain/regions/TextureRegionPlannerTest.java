package dev.turboism.plugin.turbomesh.domain.regions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * Verifies the ported planner against the offline prototype record in
 * {@code prototypes/texture-regions/NOTES.md}.
 *
 * <p>Every sample check runs the published API only, so the assertions describe the contract the
 * algorithm SPI will expose later.</p>
 */
class TextureRegionPlannerTest {

    /** Lowest silhouette IoU the prototype accepted for any sample. */
    private static final double MIN_ALPHA_IOU = 0.97;

    /** Allowed uncovered or overlapping area, in square pixels. */
    private static final double AREA_TOLERANCE_PX2 = 1.0e-3;

    /** Collinearity tolerance of constraint coverage, in pixels. */
    private static final double COLLINEAR_TOLERANCE_PX = 1.0e-4;

    /** Vertices belong to a triangle interior beyond this distance, in pixels. */
    private static final double INSIDE_EPSILON_PX = 1.0e-6;

    /** Allowed relative deviation from the prototype vertex count. */
    private static final double VERTEX_COUNT_TOLERANCE = 0.25;

    private static final String HAIR = "/texture-regions/sample.png";

    private static final String USB = "/texture-regions/sample-usb.png";

    @Test
    void hairSparseMatchesPrototype() throws IOException {
        final Sample sample = load(HAIR);
        final RegionPlacementPlan plan = RegionPlacementPlanner.plan(
                sample.argb(), sample.width(), sample.height(), RegionPlacementParams.SPARSE);
        verify(plan, 139);
        report("hair-sparse", plan);
        RegionDumpWriter.dumpIfRequested(plan, sample, "hair-sparse");
    }

    @Test
    void hairDetailedMatchesPrototype() throws IOException {
        final Sample sample = load(HAIR);
        final RegionPlacementPlan plan = RegionPlacementPlanner.plan(
                sample.argb(), sample.width(), sample.height(), RegionPlacementParams.DETAILED);
        verify(plan, 204);
        report("hair-detailed", plan);
        RegionDumpWriter.dumpIfRequested(plan, sample, "hair-detailed");
    }

    @Test
    void usbSparseMatchesPrototype() throws IOException {
        final Sample sample = load(USB);
        final RegionPlacementPlan plan = RegionPlacementPlanner.plan(
                sample.argb(), sample.width(), sample.height(), RegionPlacementParams.HARD_SURFACE);
        verify(plan, 134);
        assertAlphaHoles(plan, 2);
        report("usb-sparse", plan);
        RegionDumpWriter.dumpIfRequested(plan, sample, "usb-sparse");
    }

    @Test
    void usbDetailedMatchesPrototype() throws IOException {
        final Sample sample = load(USB);
        final RegionPlacementPlan plan = RegionPlacementPlanner.plan(
                sample.argb(),
                sample.width(),
                sample.height(),
                new RegionPlacementParams(0.01, null, 1, 3, null, null, null));
        verify(plan, 221);
        assertAlphaHoles(plan, 2);
        report("usb-detailed", plan);
        RegionDumpWriter.dumpIfRequested(plan, sample, "usb-detailed");
    }

    @Test
    void filledCircleUsesConcentricRings() {
        assertConcentricRings(syntheticShape(512, 512, 256, 256, 200, 200), "circle");
    }

    @Test
    void filledEllipseUsesConcentricRings() {
        assertConcentricRings(syntheticShape(512, 512, 256, 256, 220, 140), "ellipse");
    }

    /** Prints one metric line per sample, because the delivery record quotes these numbers. */
    private static void report(final String name, final RegionPlacementPlan plan) {
        System.out.printf(
                "%s: regions=%d vertices=%d->%d triangles=%d iou=%.4f family=%s rings=%d%n",
                name,
                plan.regions().regionCount(),
                plan.vertexCountBeforeMerge(),
                plan.vertexCountAfterMerge(),
                plan.triangleCount(),
                plan.alphaIou(),
                plan.shapeFamily(),
                plan.domainRings().size());
    }

    private static void assertConcentricRings(final Sample sample, final String name) {
        final RegionPlacementPlan plan = RegionPlacementPlanner.plan(
                sample.argb(), sample.width(), sample.height(), RegionPlacementParams.SPARSE);
        assertEquals(ShapeFamily.CONCENTRIC_RINGS, plan.shapeFamily(), name + " must use nested rings");
        final int loops = closedDirectionLoops(plan);
        assertTrue(loops >= 2, name + " must keep at least two closed direction rings but kept " + loops);
        verify(plan, null);
        report(name, plan);
        RegionDumpWriter.dumpIfRequested(plan, sample, name);
    }

    /** Every published guarantee of the plan that holds for all samples. */
    private static void verify(final RegionPlacementPlan plan, final Integer prototypeVertexCount) {
        assertTrue(plan.triangleCount() > 0, "plan must contain triangles");
        assertTrue(plan.regions().regionCount() > 0, "plan must report texture regions");
        assertTrue(
                plan.vertexCountAfterMerge() <= plan.vertexCountBeforeMerge(),
                "average merging must not invent vertices");
        assertTrue(plan.alphaIou() >= MIN_ALPHA_IOU, "alpha IoU " + plan.alphaIou() + " must reach " + MIN_ALPHA_IOU);
        if (prototypeVertexCount != null) {
            final double low = prototypeVertexCount * (1.0 - VERTEX_COUNT_TOLERANCE);
            final double high = prototypeVertexCount * (1.0 + VERTEX_COUNT_TOLERANCE);
            assertTrue(
                    plan.vertexCountAfterMerge() >= low && plan.vertexCountAfterMerge() <= high,
                    "vertex count " + plan.vertexCountAfterMerge() + " must stay within [" + low + ", " + high + "]");
        }
        assertOutlineRingsClosed(plan);
        assertConstraintsCovered(plan);
        assertNoTriangleCrossesRegionBorder(plan);
        assertNoOverlapOrHole(plan);
    }

    /** Each domain ring must be a closed loop of published outline constraints. */
    private static void assertOutlineRingsClosed(final RegionPlacementPlan plan) {
        final Set<Long> outlineEdges = new HashSet<>();
        for (final RegionPlacementPlan.Constraint constraint : plan.constraints()) {
            if (constraint.role() == ConstraintRole.OUTLINE) {
                outlineEdges.add(edgeKey(constraint.a(), constraint.b()));
            }
        }
        assertFalse(plan.domainRings().isEmpty(), "plan must publish at least one domain ring");
        for (final int[] ring : plan.domainRings()) {
            assertTrue(ring.length >= 3, "ring needs three vertices but held " + ring.length);
            for (int i = 0; i < ring.length; i++) {
                final int next = ring[(i + 1) % ring.length];
                assertTrue(
                        outlineEdges.contains(edgeKey(ring[i], next)),
                        "ring edge " + ring[i] + "-" + next + " must be an outline constraint");
            }
        }
    }

    /**
     * Every constraint must survive triangulation, either as one edge or as a collinear chain.
     *
     * <p>Existing vertices can subdivide a constraint, so coverage rather than identity is the
     * contract. The tolerance matches the prototype, which reported float32 UV residuals.</p>
     */
    private static void assertConstraintsCovered(final RegionPlacementPlan plan) {
        final List<Point> vertices = points(plan);
        final Set<Long> triangleEdges = new HashSet<>();
        final int[] indices = plan.triangleIndices();
        for (int t = 0; t + 2 < indices.length; t += 3) {
            triangleEdges.add(edgeKey(indices[t], indices[t + 1]));
            triangleEdges.add(edgeKey(indices[t + 1], indices[t + 2]));
            triangleEdges.add(edgeKey(indices[t + 2], indices[t]));
        }
        final List<int[]> spanning = new ArrayList<>(triangleEdges.size());
        for (final long key : triangleEdges) {
            spanning.add(new int[] {(int) (key >>> 32), (int) (key & 0xFFFF_FFFFL)});
        }
        final List<String> missing = new ArrayList<>();
        for (final RegionPlacementPlan.Constraint constraint : plan.constraints()) {
            final int a = constraint.a();
            final int b = constraint.b();
            if (triangleEdges.contains(edgeKey(a, b))) {
                continue;
            }
            if (!coveredByChain(vertices, spanning, a, b)) {
                missing.add(a + "-" + b + "(" + constraint.role() + ")");
            }
        }
        assertTrue(missing.isEmpty(), "constraints missing from the triangulation: " + missing);
    }

    private static boolean coveredByChain(
            final List<Point> vertices, final List<int[]> triangleEdges, final int a, final int b) {
        final Point start = vertices.get(a);
        final Point end = vertices.get(b);
        final double dx = end.x() - start.x();
        final double dy = end.y() - start.y();
        final double length = Math.hypot(dx, dy);
        if (length <= 0.0) {
            return false;
        }
        final double unitX = dx / length;
        final double unitY = dy / length;
        final List<double[]> spans = new ArrayList<>();
        for (final int[] edge : triangleEdges) {
            final Point first = vertices.get(edge[0]);
            final Point second = vertices.get(edge[1]);
            final double firstAxial = (first.x() - start.x()) * unitX + (first.y() - start.y()) * unitY;
            final double firstOff = Math.abs((first.x() - start.x()) * unitY - (first.y() - start.y()) * unitX);
            final double secondAxial = (second.x() - start.x()) * unitX + (second.y() - start.y()) * unitY;
            final double secondOff = Math.abs((second.x() - start.x()) * unitY - (second.y() - start.y()) * unitX);
            if (firstOff > COLLINEAR_TOLERANCE_PX || secondOff > COLLINEAR_TOLERANCE_PX) {
                continue;
            }
            spans.add(new double[] {Math.min(firstAxial, secondAxial), Math.max(firstAxial, secondAxial)});
        }
        spans.sort((first, second) -> Double.compare(first[0], second[0]));
        double reached = 0.0;
        for (final double[] span : spans) {
            if (span[0] > reached + COLLINEAR_TOLERANCE_PX) {
                break;
            }
            reached = Math.max(reached, span[1]);
        }
        return reached >= length - COLLINEAR_TOLERANCE_PX;
    }

    /**
     * No triangle may span two vector cells.
     *
     * <p>Constraints are triangulation edges, so a crossing would put a constraint vertex strictly
     * inside a triangle. That test needs no cell geometry and catches every such case.</p>
     */
    private static void assertNoTriangleCrossesRegionBorder(final RegionPlacementPlan plan) {
        final List<Point> vertices = points(plan);
        final Set<Integer> constraintVertices = new HashSet<>();
        for (final RegionPlacementPlan.Constraint constraint : plan.constraints()) {
            constraintVertices.add(constraint.a());
            constraintVertices.add(constraint.b());
        }
        final int[] indices = plan.triangleIndices();
        int crossing = 0;
        for (int t = 0; t + 2 < indices.length; t += 3) {
            final Point a = vertices.get(indices[t]);
            final Point b = vertices.get(indices[t + 1]);
            final Point c = vertices.get(indices[t + 2]);
            for (final int candidate : constraintVertices) {
                if (candidate == indices[t] || candidate == indices[t + 1] || candidate == indices[t + 2]) {
                    continue;
                }
                if (strictlyInside(vertices.get(candidate), a, b, c)) {
                    crossing++;
                    break;
                }
            }
        }
        assertEquals(0, crossing, "triangles must not cross a region or outline constraint");
    }

    private static boolean strictlyInside(final Point p, final Point a, final Point b, final Point c) {
        final double first = cross(a, b, p);
        final double second = cross(b, c, p);
        final double third = cross(c, a, p);
        final double epsilon = INSIDE_EPSILON_PX;
        final boolean allPositive = first > epsilon && second > epsilon && third > epsilon;
        final boolean allNegative = first < -epsilon && second < -epsilon && third < -epsilon;
        return allPositive || allNegative;
    }

    private static double cross(final Point from, final Point to, final Point p) {
        return (to.x() - from.x()) * (p.y() - from.y()) - (to.y() - from.y()) * (p.x() - from.x());
    }

    /** Triangles must tile the domain exactly: no double cover, no gap beyond the tolerance. */
    private static void assertNoOverlapOrHole(final RegionPlacementPlan plan) {
        final List<Point> vertices = points(plan);
        final int[] indices = plan.triangleIndices();
        final Map<Long, Integer> edgeUse = new HashMap<>();
        double triangleArea = 0.0;
        for (int t = 0; t + 2 < indices.length; t += 3) {
            final Point a = vertices.get(indices[t]);
            final Point b = vertices.get(indices[t + 1]);
            final Point c = vertices.get(indices[t + 2]);
            final double area = Math.abs(cross(a, b, c)) * 0.5;
            assertTrue(area > 0.0, "triangle " + t / 3 + " is degenerate");
            triangleArea += area;
            edgeUse.merge(edgeKey(indices[t], indices[t + 1]), 1, Integer::sum);
            edgeUse.merge(edgeKey(indices[t + 1], indices[t + 2]), 1, Integer::sum);
            edgeUse.merge(edgeKey(indices[t + 2], indices[t]), 1, Integer::sum);
        }
        for (final Map.Entry<Long, Integer> entry : edgeUse.entrySet()) {
            assertTrue(entry.getValue() <= 2, "edge " + entry.getKey() + " is covered more than twice");
        }
        final double domainArea = domainArea(vertices, plan.domainRings());
        assertTrue(
                Math.abs(triangleArea - domainArea) <= AREA_TOLERANCE_PX2,
                "triangle area " + triangleArea + " must match domain area " + domainArea);
    }

    private static double domainArea(final List<Point> vertices, final List<int[]> rings) {
        double largest = 0.0;
        double total = 0.0;
        for (final int[] ring : rings) {
            double sum = 0.0;
            for (int i = 0; i < ring.length; i++) {
                final Point current = vertices.get(ring[i]);
                final Point next = vertices.get(ring[(i + 1) % ring.length]);
                sum += current.x() * next.y() - next.x() * current.y();
            }
            final double area = Math.abs(sum) * 0.5;
            largest = Math.max(largest, area);
        }
        double holes = 0.0;
        for (final int[] ring : rings) {
            double sum = 0.0;
            for (int i = 0; i < ring.length; i++) {
                final Point current = vertices.get(ring[i]);
                final Point next = vertices.get(ring[(i + 1) % ring.length]);
                sum += current.x() * next.y() - next.x() * current.y();
            }
            final double area = Math.abs(sum) * 0.5;
            if (area < largest) {
                holes += area;
            }
        }
        total += largest - holes;
        return total;
    }

    /** The USB sample must keep its two alpha holes as inner domain rings. */
    private static void assertAlphaHoles(final RegionPlacementPlan plan, final int expected) {
        final List<Point> vertices = points(plan);
        final List<int[]> rings = plan.domainRings();
        assertEquals(expected + 1, rings.size(), "domain must publish one exterior ring plus every hole");
        double largest = 0.0;
        for (final int[] ring : rings) {
            largest = Math.max(largest, Math.abs(ringArea(vertices, ring)));
        }
        int holes = 0;
        for (final int[] ring : rings) {
            final double area = Math.abs(ringArea(vertices, ring));
            if (area >= largest) {
                continue;
            }
            assertTrue(area > 1.0, "hole ring area " + area + " is too small to be an alpha hole");
            final Point probe = ringCentroid(vertices, ring);
            assertEquals(0, plan.regions().labelAt(probe.x(), probe.y()), "hole ring must enclose empty pixels");
            holes++;
        }
        assertEquals(expected, holes, "alpha hole count");
    }

    private static double ringArea(final List<Point> vertices, final int[] ring) {
        double sum = 0.0;
        for (int i = 0; i < ring.length; i++) {
            final Point current = vertices.get(ring[i]);
            final Point next = vertices.get(ring[(i + 1) % ring.length]);
            sum += current.x() * next.y() - next.x() * current.y();
        }
        return 0.5 * sum;
    }

    private static Point ringCentroid(final List<Point> vertices, final int[] ring) {
        double sumX = 0.0;
        double sumY = 0.0;
        for (final int index : ring) {
            sumX += vertices.get(index).x();
            sumY += vertices.get(index).y();
        }
        return new Point(sumX / ring.length, sumY / ring.length);
    }

    /** Counts closed loops formed by direction constraints; a ring vertex must have degree two. */
    private static int closedDirectionLoops(final RegionPlacementPlan plan) {
        final Map<Integer, List<Integer>> neighbours = new HashMap<>();
        for (final RegionPlacementPlan.Constraint constraint : plan.constraints()) {
            if (constraint.role() != ConstraintRole.DIRECTION_LINE) {
                continue;
            }
            neighbours
                    .computeIfAbsent(constraint.a(), ignored -> new ArrayList<>())
                    .add(constraint.b());
            neighbours
                    .computeIfAbsent(constraint.b(), ignored -> new ArrayList<>())
                    .add(constraint.a());
        }
        for (final Map.Entry<Integer, List<Integer>> entry : neighbours.entrySet()) {
            assertEquals(2, entry.getValue().size(), "direction ring vertex must have degree two");
        }
        final Set<Integer> visited = new HashSet<>();
        int loops = 0;
        for (final int start : neighbours.keySet()) {
            if (!visited.add(start)) {
                continue;
            }
            loops++;
            int current = start;
            int previous = -1;
            while (true) {
                final List<Integer> options = neighbours.get(current);
                final int next = options.get(0) == previous ? options.get(1) : options.get(0);
                if (next == start || !visited.add(next)) {
                    break;
                }
                previous = current;
                current = next;
            }
        }
        return loops;
    }

    private static List<Point> points(final RegionPlacementPlan plan) {
        final List<Point> points = new ArrayList<>(plan.vertices().size());
        for (final dev.turboism.sdk.cubism.model.Point2 vertex : plan.vertices()) {
            points.add(new Point(vertex.x(), vertex.y()));
        }
        return points;
    }

    private static long edgeKey(final int a, final int b) {
        return ((long) Math.min(a, b) << 32) | (Math.max(a, b) & 0xFFFF_FFFFL);
    }

    private static Sample load(final String resource) throws IOException {
        try (InputStream stream = TextureRegionPlannerTest.class.getResourceAsStream(resource)) {
            assertNotNull(stream, "missing test resource " + resource);
            final BufferedImage image = ImageIO.read(stream);
            final int width = image.getWidth();
            final int height = image.getHeight();
            final int[] argb = new int[width * height];
            image.getRGB(0, 0, width, height, argb, 0, width);
            return new Sample(argb, width, height);
        }
    }

    /** Solid filled ellipse with a flat colour, which the segmenter treats as one region. */
    private static Sample syntheticShape(
            final int width,
            final int height,
            final double centerX,
            final double centerY,
            final double radiusX,
            final double radiusY) {
        final int[] argb = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                final double nx = (x + 0.5 - centerX) / radiusX;
                final double ny = (y + 0.5 - centerY) / radiusY;
                final boolean inside = nx * nx + ny * ny <= 1.0;
                argb[y * width + x] = inside ? 0xFF40_80C0 : 0;
            }
        }
        return new Sample(argb, width, height);
    }

    /** Source pixels of one test sample. */
    record Sample(int[] argb, int width, int height) {

        Sample {
            argb = argb.clone();
        }

        @Override
        public int[] argb() {
            return argb.clone();
        }
    }

    /** Double-precision plan vertex. */
    private record Point(double x, double y) {}
}

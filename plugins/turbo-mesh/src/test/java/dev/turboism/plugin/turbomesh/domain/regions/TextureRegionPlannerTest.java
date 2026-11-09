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

    /** Lowest triangle IoU the prototype expectation keeps for the checked-in samples. */
    private static final double MIN_SAMPLE_TRIANGLE_IOU = 0.97;

    /** Lowest triangle-area share of the opaque area a plan may leave uncovered. */
    private static final double MIN_AREA_RATIO = 0.97;

    /** Lowest triangle IoU a synthetic shape must reach against its own alpha mask. */
    private static final double MIN_SYNTHETIC_TRIANGLE_IOU = 0.95;

    /** Covered share every opaque component needs, well above zero and below a good mesh. */
    private static final double MIN_COMPONENT_COVERAGE = 0.9;

    /** Opaque components below this area are raster noise rather than real parts, in pixels. */
    private static final int MIN_COMPONENT_PX = 200;

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

    @Test
    void bodyWithTaperingStrandsCoversTheWholeSilhouette() {
        assertCoverageContract(strandBody(420, 460), "strands");
    }

    @Test
    void disconnectedComponentsEachGetTriangles() {
        assertCoverageContract(twoComponents(420, 260), "two-components");
    }

    @Test
    void lobesJoinedByThinNeckKeepTheirCoverage() {
        assertCoverageContract(linkedLobes(440, 400), "linked-lobes");
    }

    @Test
    void annulusAndCrescentKeepTheirCoverage() {
        assertCoverageContract(annulusAndCrescent(460, 300), "annulus-crescent");
    }

    /** The contract that failed on a real hair layer: the returned triangles must fill the shape. */
    private static void assertCoverageContract(final Sample sample, final String name) {
        final RegionPlacementPlan plan = RegionPlacementPlanner.plan(
                sample.argb(), sample.width(), sample.height(), RegionPlacementParams.SPARSE);
        final List<Point> vertices = points(plan);
        final int[] indices = plan.triangleIndices();
        report(name, plan);
        assertTrue(vertices.size() > 0, name + " must publish vertices");
        assertTrue(indices.length >= 3, name + " must publish triangles");
        assertAllVerticesReferenced(name, plan);
        final double maskArea = maskArea(sample);
        final double triangleArea = triangleArea(vertices, indices);
        final double ratio = triangleArea / maskArea;
        assertTrue(
                ratio >= MIN_AREA_RATIO,
                name + " triangles cover " + ratio + " of the opaque area, below " + MIN_AREA_RATIO);
        assertTrue(
                plan.triangleIou() >= MIN_SYNTHETIC_TRIANGLE_IOU,
                name + " triangle IoU " + plan.triangleIou() + " must reach " + MIN_SYNTHETIC_TRIANGLE_IOU);
        assertEachComponentCovered(name, plan, sample);
        assertOutlineRingsClosed(plan);
        assertConstraintsCovered(plan);
        assertNoTriangleCrossesRegionBorder(plan);
        assertNoOverlap(plan);
        assertTrianglesStayInsideDomain(name, plan);
        RegionDumpWriter.dumpIfRequested(plan, sample, name);
    }

    /** No plan vertex may sit outside every triangle, because such a vertex moves no material. */
    private static void assertAllVerticesReferenced(final String name, final RegionPlacementPlan plan) {
        final boolean[] referenced = new boolean[plan.vertices().size()];
        for (final int index : plan.triangleIndices()) {
            referenced[index] = true;
        }
        int unused = 0;
        for (final boolean value : referenced) {
            if (!value) {
                unused++;
            }
        }
        assertEquals(0, unused, name + " must not publish unreferenced vertices");
    }

    /** Every opaque part above the noise floor must be covered by the returned triangles. */
    private static void assertEachComponentCovered(
            final String name, final RegionPlacementPlan plan, final Sample sample) {
        final int width = sample.width();
        final int height = sample.height();
        final boolean[] mask = maskOf(sample);
        final boolean[] covered = PixelCoverage.coveredPixels(plan.vertices(), plan.triangleIndices(), width, height);
        final int[] component = components(mask, width, height);
        final int[] sizes = new int[maxValue(component) + 1];
        final int[] hits = new int[sizes.length];
        for (int i = 0; i < mask.length; i++) {
            if (component[i] == 0) {
                continue;
            }
            sizes[component[i]]++;
            if (covered[i]) {
                hits[component[i]]++;
            }
        }
        int checked = 0;
        for (int id = 1; id < sizes.length; id++) {
            if (sizes[id] < MIN_COMPONENT_PX) {
                continue;
            }
            checked++;
            final double share = (double) hits[id] / sizes[id];
            assertTrue(
                    share >= MIN_COMPONENT_COVERAGE,
                    name + " covers only " + share + " of opaque component " + id + " with " + sizes[id] + " px");
        }
        assertTrue(checked > 0, name + " must contain at least one opaque component");
    }

    private static double triangleArea(final List<Point> vertices, final int[] indices) {
        double area = 0.0;
        for (int t = 0; t + 2 < indices.length; t += 3) {
            final Point a = vertices.get(indices[t]);
            final Point b = vertices.get(indices[t + 1]);
            final Point c = vertices.get(indices[t + 2]);
            area += Math.abs(cross(a, b, c)) * 0.5;
        }
        return area;
    }

    private static double maskArea(final Sample sample) {
        double area = 0.0;
        for (final boolean value : maskOf(sample)) {
            if (value) {
                area += 1.0;
            }
        }
        return area;
    }

    /** Solid alpha pixels of a sample, using the planner's own solidity threshold. */
    private static boolean[] maskOf(final Sample sample) {
        final boolean[] mask = new boolean[sample.width() * sample.height()];
        final int[] argb = sample.argb();
        for (int i = 0; i < argb.length; i++) {
            mask[i] = (argb[i] >>> 24) >= RegionRaster.ALPHA_SOLID;
        }
        return mask;
    }

    /** Eight-connected labelling of the opaque mask; {@code 0} marks background. */
    private static int[] components(final boolean[] mask, final int width, final int height) {
        final int[] labels = new int[mask.length];
        final List<Integer> queue = new ArrayList<>();
        int next = 0;
        for (int start = 0; start < mask.length; start++) {
            if (!mask[start] || labels[start] != 0) {
                continue;
            }
            next++;
            labels[start] = next;
            queue.add(start);
            for (int head = 0; head < queue.size(); head++) {
                final int cell = queue.get(head);
                final int x = cell % width;
                final int y = cell / width;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        final int nx = x + dx;
                        final int ny = y + dy;
                        if (nx < 0 || ny < 0 || nx >= width || ny >= height) {
                            continue;
                        }
                        final int neighbour = ny * width + nx;
                        if (mask[neighbour] && labels[neighbour] == 0) {
                            labels[neighbour] = next;
                            queue.add(neighbour);
                        }
                    }
                }
            }
            queue.clear();
        }
        return labels;
    }

    private static int maxValue(final int[] values) {
        int max = 0;
        for (final int value : values) {
            max = Math.max(max, value);
        }
        return max;
    }

    /** Prints one metric line per sample, because the delivery record quotes these numbers. */
    private static void report(final String name, final RegionPlacementPlan plan) {
        System.out.printf(
                "%s: regions=%d vertices=%d->%d triangles=%d triangleIou=%.4f family=%s rings=%d fallback=%s%n",
                name,
                plan.regions().regionCount(),
                plan.vertexCountBeforeMerge(),
                plan.vertexCountAfterMerge(),
                plan.triangleCount(),
                plan.triangleIou(),
                plan.shapeFamily(),
                plan.domainRings().size(),
                plan.fallback());
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

    /** Verifies every published guarantee that holds for all samples. */
    private static void verify(final RegionPlacementPlan plan, final Integer prototypeVertexCount) {
        assertTrue(plan.triangleCount() > 0, "plan must contain triangles");
        assertTrue(plan.regions().regionCount() > 0, "plan must report texture regions");
        assertAllVerticesReferenced("plan", plan);
        assertTrue(
                plan.vertexCountAfterMerge() <= plan.vertexCountBeforeMerge(),
                "average merging must not invent vertices");
        assertTrue(
                plan.triangleIou() >= MIN_SAMPLE_TRIANGLE_IOU,
                "triangle IoU " + plan.triangleIou() + " must reach " + MIN_SAMPLE_TRIANGLE_IOU);
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
        final double triangleArea = assertNoOverlap(plan);
        final List<Point> vertices = points(plan);
        final double domainArea = domainArea(vertices, plan.domainRings());
        assertTrue(
                Math.abs(triangleArea - domainArea) <= AREA_TOLERANCE_PX2,
                "triangle area " + triangleArea + " must match domain area " + domainArea);
    }

    /** No triangle may be degenerate and no edge may carry more than two triangles. */
    private static double assertNoOverlap(final RegionPlacementPlan plan) {
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
        return triangleArea;
    }

    /**
     * Share of the triangle area that has to lie inside the even-odd domain of the ring set.
     *
     * <p>The triangulator classifies triangles by centroid and keeps one carrier triangle per
     * constraint edge, and a triangle that straddles a ring may therefore reach a little beyond the
     * outline. Measuring the area instead of the centroid keeps that documented trade-off honest
     * without hiding a shifted mesh. The bound matches the planner's own coverage contract.</p>
     */
    private static final double MIN_INSIDE_SHARE = 0.97;

    /** Samples per triangle axis for the inside-share estimate. */
    private static final int INSIDE_SAMPLES = 4;

    private static void assertTrianglesStayInsideDomain(final String name, final RegionPlacementPlan plan) {
        final List<Point> vertices = points(plan);
        final List<int[]> rings = plan.domainRings();
        final int[] indices = plan.triangleIndices();
        double total = 0.0;
        double inside = 0.0;
        for (int t = 0; t + 2 < indices.length; t += 3) {
            final Point a = vertices.get(indices[t]);
            final Point b = vertices.get(indices[t + 1]);
            final Point c = vertices.get(indices[t + 2]);
            final double area = Math.abs(cross(a, b, c)) * 0.5;
            if (area <= 0.0) {
                continue;
            }
            int samples = 0;
            int hits = 0;
            final double minX = Math.min(a.x(), Math.min(b.x(), c.x()));
            final double maxX = Math.max(a.x(), Math.max(b.x(), c.x()));
            final double minY = Math.min(a.y(), Math.min(b.y(), c.y()));
            final double maxY = Math.max(a.y(), Math.max(b.y(), c.y()));
            for (int row = 0; row < INSIDE_SAMPLES; row++) {
                for (int column = 0; column < INSIDE_SAMPLES; column++) {
                    final Point probe = new Point(
                            minX + (column + 0.5) * (maxX - minX) / INSIDE_SAMPLES,
                            minY + (row + 0.5) * (maxY - minY) / INSIDE_SAMPLES);
                    if (!inTriangle(a, b, c, probe)) {
                        continue;
                    }
                    samples++;
                    boolean contained = false;
                    for (final int[] ring : rings) {
                        if (contains(vertices, ring, probe)) {
                            contained = !contained;
                        }
                    }
                    if (contained) {
                        hits++;
                    }
                }
            }
            if (samples == 0) {
                continue;
            }
            total += area;
            inside += area * hits / samples;
        }
        final double share = total <= 0.0 ? 0.0 : inside / total;
        System.out.printf("%s: insideShare=%.4f%n", name, share);
        assertTrue(
                share >= MIN_INSIDE_SHARE, name + " keeps only " + share + " of its triangle area inside the domain");
    }

    private static boolean inTriangle(final Point a, final Point b, final Point c, final Point probe) {
        final double first = cross(a, b, probe);
        final double second = cross(b, c, probe);
        final double third = cross(c, a, probe);
        final boolean negative = first < 0.0 || second < 0.0 || third < 0.0;
        final boolean positive = first > 0.0 || second > 0.0 || third > 0.0;
        return !(negative && positive);
    }

    /**
     * Net enclosed area of the ring set, following the same nesting rule as the triangulator.
     *
     * <p>A ring inside an even number of other rings bounds material, an odd one bounds a hole.
     * Ring order and the largest ring therefore say nothing about disjoint parts.</p>
     */
    private static double domainArea(final List<Point> vertices, final List<int[]> rings) {
        double total = 0.0;
        for (int i = 0; i < rings.size(); i++) {
            final Point probe = ringCentroid(vertices, rings.get(i));
            int depth = 0;
            for (int j = 0; j < rings.size(); j++) {
                if (i != j && contains(vertices, rings.get(j), probe)) {
                    depth++;
                }
            }
            final double area = Math.abs(ringArea(vertices, rings.get(i)));
            total += depth % 2 == 0 ? area : -area;
        }
        return total;
    }

    private static boolean contains(final List<Point> vertices, final int[] ring, final Point probe) {
        boolean inside = false;
        for (int i = 0, j = ring.length - 1; i < ring.length; j = i++) {
            final Point current = vertices.get(ring[i]);
            final Point previous = vertices.get(ring[j]);
            if ((current.y() > probe.y()) != (previous.y() > probe.y())
                    && probe.x()
                            < (previous.x() - current.x()) * (probe.y() - current.y()) / (previous.y() - current.y())
                                    + current.x()) {
                inside = !inside;
            }
        }
        return inside;
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

    /**
     * Wide body with four tapering strands at different angles.
     *
     * <p>Each strand narrows to one pixel where it leaves the body, so the outline runs a long
     * concave neck on both sides. That neck is the geometry that near-point merging and ring
     * chaining used to fold, which dropped whole lobes of a real hair layer.</p>
     */
    private static Sample strandBody(final int width, final int height) {
        final double bodyX = width * 0.42;
        final double bodyY = height * 0.44;
        final double bodyRadiusX = width * 0.17;
        final double bodyRadiusY = height * 0.2;
        final double[] strandAngles = {0.3, 0.95, 2.0, 2.7, 3.6};
        final double[] strandLengths = {0.32, 0.26, 0.3, 0.22, 0.28};
        final double rootHalfWidth = 0.9;
        return painted(width, height, (x, y) -> {
            final double nx = (x - bodyX) / bodyRadiusX;
            final double ny = (y - bodyY) / bodyRadiusY;
            if (nx * nx + ny * ny <= 1.0) {
                return true;
            }
            for (int s = 0; s < strandAngles.length; s++) {
                final double dirX = Math.cos(strandAngles[s]);
                final double dirY = Math.sin(strandAngles[s]);
                final double along = (x - bodyX) * dirX + (y - bodyY) * dirY;
                final double across = -(x - bodyX) * dirY + (y - bodyY) * dirX;
                final double length = strandLengths[s] * height;
                if (along < 0.0 || along > length) {
                    continue;
                }
                final double halfWidth = rootHalfWidth * (1.0 - along / length);
                if (Math.abs(across) <= halfWidth) {
                    return true;
                }
            }
            return false;
        });
    }

    /** Two opaque discs with a wide empty gap, which no single closing outline can enclose. */
    private static Sample twoComponents(final int width, final int height) {
        final double leftX = width * 0.26;
        final double rightX = width * 0.72;
        final double centerY = height * 0.5;
        final double leftRadius = height * 0.32;
        final double rightRadius = height * 0.2;
        return painted(
                width,
                height,
                (x, y) -> inDisc(x, y, leftX, centerY, leftRadius) || inDisc(x, y, rightX, centerY, rightRadius));
    }

    /**
     * Two round lobes joined by a neck thinner than the near-point merge radius.
     *
     * <p>The neck is the outline's narrowest passage, so averaging its two sides collapses the
     * only ring that encloses both lobes. That is the geometry that emptied a real hair layer.</p>
     */
    private static Sample linkedLobes(final int width, final int height) {
        final double leftX = width * 0.3;
        final double rightX = width * 0.7;
        final double centerY = height * 0.5;
        final double lobeRadius = height * 0.24;
        final double neckHalfHeight = 0.6;
        return painted(
                width,
                height,
                (x, y) -> inDisc(x, y, leftX, centerY, lobeRadius)
                        || inDisc(x, y, rightX, centerY, lobeRadius)
                        || (x >= leftX && x <= rightX && Math.abs(y - centerY) <= neckHalfHeight));
    }

    /** Thick annulus next to a thin crescent; both shapes force inner rings and slivers. */
    private static Sample annulusAndCrescent(final int width, final int height) {
        final double ringX = width * 0.3;
        final double ringY = height * 0.5;
        final double ringOuter = height * 0.42;
        final double ringInner = height * 0.2;
        final double crescentX = width * 0.76;
        final double crescentY = height * 0.5;
        final double crescentOuter = height * 0.4;
        final double crescentInner = height * 0.34;
        final double crescentShift = height * 0.12;
        return painted(width, height, (x, y) -> {
            final boolean annulus = inDisc(x, y, ringX, ringY, ringOuter) && !inDisc(x, y, ringX, ringY, ringInner);
            final boolean crescent = inDisc(x, y, crescentX, crescentY, crescentOuter)
                    && !inDisc(x, y, crescentX + crescentShift, crescentY, crescentInner);
            return annulus || crescent;
        });
    }

    private static boolean inDisc(
            final double x, final double y, final double centerX, final double centerY, final double radius) {
        final double dx = x - centerX;
        final double dy = y - centerY;
        return dx * dx + dy * dy <= radius * radius;
    }

    /** Pixel-centre membership test of a painted synthetic shape. */
    @FunctionalInterface
    private interface Region {
        boolean contains(double x, double y);
    }

    /** Fills every pixel whose centre the shape accepts, with one flat colour. */
    private static Sample painted(final int width, final int height, final Region inside) {
        final int[] argb = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                argb[y * width + x] = inside.contains(x + 0.5, y + 0.5) ? 0xFF40_80C0 : 0;
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

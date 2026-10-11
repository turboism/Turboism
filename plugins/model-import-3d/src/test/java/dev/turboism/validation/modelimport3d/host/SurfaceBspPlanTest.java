package dev.turboism.validation.modelimport3d.host;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.cubism.model.Point2;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class SurfaceBspPlanTest {
    private static final SurfaceBspPlan.Budget BUDGET = new SurfaceBspPlan.Budget(2000, 2000, 64, 2_000_000);

    private static SurfaceProjection.Vertex v(double x, double y, double z) {
        return new SurfaceProjection.Vertex(x, y, z);
    }

    private static SurfaceSourceSplit.Source source(int id, SurfaceProjection.Vertex... points) {
        return new SurfaceSourceSplit.Source(
                0, 0, 0, id, List.of(points), List.of(new Point2(0, 0), new Point2(1, 0), new Point2(0, 1)));
    }

    private static List<SurfaceSourceSplit.Source> intersecting() {
        return List.of(
                source(0, v(-1, -1, -1), v(1, -1, 1), v(0, 1, 0)),
                source(1, v(-1, -1, -1), v(1, -1, -1), v(0, 1, 1)),
                source(2, v(-1, -1, 2), v(1, -1, 0), v(0, 1, -1)));
    }

    private static void bar(List<SurfaceSourceSplit.Source> out, double ax, double ay, double bx, double by) {
        double length = Math.hypot(bx - ax, by - ay), dx = -(by - ay) / length * .16, dy = (bx - ax) / length * .16;
        var a = v(ax + dx, ay + dy, 1);
        var b = v(bx + dx, by + dy, -1);
        var c = v(bx - dx, by - dy, -1);
        var d = v(ax - dx, ay - dy, 1);
        out.add(source(out.size(), a, b, c));
        out.add(source(out.size(), a, c, d));
    }

    private static List<SurfaceSourceSplit.Source> cycle() {
        var result = new ArrayList<SurfaceSourceSplit.Source>();
        bar(result, -1, -.7, 1, -.7);
        bar(result, 1, -.7, 0, 1);
        bar(result, 0, 1, -1, -.7);
        return result;
    }

    @Test
    void crossingSurfacesMatchIndependentRayDepthAcrossJointViews() {
        verify(intersecting());
    }

    @Test
    void cyclicOverlapRequiresSplitsAndMatchesIndependentRayDepth() {
        var sources = cycle();
        var plan = SurfaceBspPlan.compile(sources, BUDGET);
        assertTrue(plan.fragments().size() > sources.size());
        verify(sources);
    }

    @Test
    void spatialPartitionCandidatesAlsoMatchIndependentRays() {
        var sources = new ArrayList<SurfaceSourceSplit.Source>();
        for (int copy = 0; copy < 3; copy++)
            for (var original : cycle()) {
                var positions = new ArrayList<SurfaceProjection.Vertex>();
                for (var p : original.positions())
                    positions.add(v(p.x() * .75 + (copy - 1) * .2, p.y() * .75, p.z() * .75 + (copy - 1) * .3));
                sources.add(source(sources.size(), positions.toArray(SurfaceProjection.Vertex[]::new)));
            }
        verify(sources);
    }

    @Test
    void budgetsAndDuplicateIdentitiesFailBeforeNativeUse() {
        var sources = intersecting();
        assertThrows(
                IllegalArgumentException.class,
                () -> SurfaceBspPlan.compile(sources, new SurfaceBspPlan.Budget(3, 20, 20, 10000)));
        assertThrows(
                IllegalArgumentException.class,
                () -> SurfaceBspPlan.compile(sources, new SurfaceBspPlan.Budget(200, 1, 20, 10000)));
        assertThrows(
                IllegalArgumentException.class,
                () -> SurfaceBspPlan.compile(sources, new SurfaceBspPlan.Budget(200, 20, 1, 10000)));
        assertThrows(
                IllegalArgumentException.class,
                () -> SurfaceBspPlan.compile(sources, new SurfaceBspPlan.Budget(200, 20, 20, 1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> SurfaceBspPlan.compile(List.of(sources.get(0), sources.get(0)), BUDGET));
        assertThrows(
                IllegalArgumentException.class,
                () -> SurfaceBspPlan.compile(List.of(source(0, v(0, 0, 0), v(1, 0, 0), v(2, 0, 0))), BUDGET));
        var plan = SurfaceBspPlan.compile(sources, BUDGET);
        assertThrows(IllegalArgumentException.class, () -> plan.backToFront(v(0, 0, 0)));
    }

    @Test
    void coplanarTiesRemainDeterministicWithoutLosingEitherSource() {
        var a = source(0, v(0, 0, 0), v(1, 0, 0), v(0, 1, 0));
        var b = source(1, v(0, 0, 0), v(1, 0, 0), v(0, 1, 0));
        var plan = SurfaceBspPlan.compile(List.of(a, b), BUDGET);
        assertEquals(2, plan.fragments().size());
        assertEquals(List.of(0, 1), plan.backToFront(v(0, 0, 1)));
        assertEquals(plan, SurfaceBspPlan.compile(List.of(a, b), BUDGET));
    }

    private static void verify(List<SurfaceSourceSplit.Source> sources) {
        var plan = SurfaceBspPlan.compile(sources, BUDGET);
        int checked = 0;
        for (double yaw : new double[] {-30, -17, 0, 13, 30})
            for (double pitch : new double[] {-30, -11, 0, 19, 30}) {
                double y = Math.toRadians(yaw),
                        p = Math.toRadians(pitch),
                        cy = Math.cos(y),
                        sy = Math.sin(y),
                        cp = Math.cos(p),
                        sp = Math.sin(p);
                var direction = v(-sy, cy * sp, cy * cp);
                var order = plan.backToFront(direction);
                assertEquals(plan.fragments().size(), new HashSet<>(order).size());
                var projected = new ArrayList<List<double[]>>();
                for (var fragment : plan.fragments()) {
                    var polygon = fragment.polygon();
                    var points = new ArrayList<double[]>();
                    for (var corner : polygon.corners()) {
                        var q = corner.position(polygon.source());
                        points.add(
                                new double[] {cy * q.x() + sy * sp * q.y() + sy * cp * q.z(), cp * q.y() - sp * q.z()});
                    }
                    projected.add(points);
                }
                for (int row = 0; row < 51; row++)
                    for (int col = 0; col < 51; col++) {
                        double x = -1.8 + (col + .371) * 3.6 / 51, screenY = -1.8 + (row + .613) * 3.6 / 51;
                        var origin = v(
                                cy * x + sy * 10,
                                sy * sp * x + cp * screenY - cy * sp * 10,
                                sy * cp * x - sp * screenY - cy * cp * 10);
                        double nearest = Double.POSITIVE_INFINITY;
                        var depths = new double[sources.size()];
                        for (int i = 0; i < sources.size(); i++) {
                            depths[i] = ray(sources.get(i), origin, direction);
                            nearest = Math.min(nearest, depths[i]);
                        }
                        int winner = -1;
                        for (int index : order)
                            if (inside(projected.get(index), x, screenY))
                                winner = plan.fragments()
                                        .get(index)
                                        .polygon()
                                        .source()
                                        .triangle();
                        if (Double.isInfinite(nearest)) assertEquals(-1, winner);
                        else {
                            assertTrue(winner >= 0, "missing visible source");
                            assertEquals(nearest, depths[winner], 1e-9, "painter winner must be nearest source");
                            checked++;
                        }
                    }
            }
        assertTrue(checked > 1000, "meaningful overlap coverage");
    }
    /** Actual painter samples only polygon coverage; it never uses depth to choose a winner. */
    private static boolean inside(List<double[]> points, double x, double y) {
        boolean positive = false, negative = false;
        double area = 0;
        for (int i = 0; i < points.size(); i++) {
            var a = points.get(i);
            var b = points.get((i + 1) % points.size());
            double cross = (b[0] - a[0]) * (y - a[1]) - (b[1] - a[1]) * (x - a[0]);
            positive |= cross > 1e-12;
            negative |= cross < -1e-12;
            area += a[0] * b[1] - a[1] * b[0];
        }
        return Math.abs(area) > 1e-12 && !(positive && negative);
    }
    /** Independent Moller-Trumbore reference against uncut source triangles. */
    private static double ray(
            SurfaceSourceSplit.Source source, SurfaceProjection.Vertex origin, SurfaceProjection.Vertex direction) {
        var p = source.positions();
        double[] e1 = sub(p.get(1), p.get(0)), e2 = sub(p.get(2), p.get(0));
        double[] d = {direction.x(), direction.y(), direction.z()}, h = cross(d, e2);
        double determinant = dot(e1, h);
        if (Math.abs(determinant) < 1e-12) return Double.POSITIVE_INFINITY;
        double[] s = sub(origin, p.get(0));
        double u = dot(s, h) / determinant;
        if (u < 0 || u > 1) return Double.POSITIVE_INFINITY;
        double[] q = cross(s, e1);
        double w = dot(d, q) / determinant;
        if (w < 0 || u + w > 1) return Double.POSITIVE_INFINITY;
        double t = dot(e2, q) / determinant;
        return t >= 0 ? t : Double.POSITIVE_INFINITY;
    }

    private static double[] sub(SurfaceProjection.Vertex a, SurfaceProjection.Vertex b) {
        return new double[] {a.x() - b.x(), a.y() - b.y(), a.z() - b.z()};
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }
}

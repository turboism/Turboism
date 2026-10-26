package dev.turboism.plugin.mcp;

import dev.turboism.sdk.cubism.model.Point2;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.BiConsumer;

/** Positive-area intersections of finite-float triangles; topology adjacency alone never implies exclusion. */
final class McpTriangleOverlap {
    record Result(boolean complete, long pairsTested, long sweepVisits, long workUsed) {}

    static Result check(
            List<Point2> points, List<Integer> topology, long budget, BiConsumer<Integer, Integer> overlap) {
        var work = new Work(budget);
        long tested = 0, visits = 0;
        try {
            int count = topology.size() / 3;
            work.take((long) count * (count == 0 ? 0 : 32 - Integer.numberOfLeadingZeros(count)));
            var faces = new ArrayList<Face>(count);
            for (int i = 0; i < count; i++) {
                work.take(1);
                var a = points.get(topology.get(3 * i));
                var b = points.get(topology.get(3 * i + 1));
                var c = points.get(topology.get(3 * i + 2));
                int winding = orientation(a, b, c, work);
                // A zero-area triangle has no interior; area/degeneracy is reported by the caller.
                if (winding != 0)
                    faces.add(new Face(
                            i,
                            List.of(a, b, c),
                            winding,
                            Math.min(a.x(), Math.min(b.x(), c.x())),
                            Math.max(a.x(), Math.max(b.x(), c.x())),
                            Math.min(a.y(), Math.min(b.y(), c.y())),
                            Math.max(a.y(), Math.max(b.y(), c.y()))));
            }
            faces.sort(Comparator.comparingDouble(Face::minX).thenComparingInt(Face::index));
            var active = new LinkedList<Face>();
            for (var current : faces) {
                for (var iterator = active.iterator(); iterator.hasNext(); ) {
                    work.take(1);
                    visits++;
                    var previous = iterator.next();
                    if (previous.maxX <= current.minX) {
                        iterator.remove();
                        continue;
                    }
                    if (previous.maxY <= current.minY || current.maxY <= previous.minY) continue;
                    boolean hit = overlaps(previous, current, work);
                    tested++;
                    if (hit)
                        overlap.accept(
                                Math.min(previous.index, current.index), Math.max(previous.index, current.index));
                }
                active.add(current);
            }
            return new Result(true, tested, visits, work.used);
        } catch (Exhausted ignored) {
            return new Result(false, tested, visits, work.used);
        }
    }

    private static boolean overlaps(Face a, Face b, Work work) {
        // Convex separating-axis test: every edge must have at least one other-triangle
        // vertex strictly on its interior side, in both directions. Boundary contact is excluded.
        return noSeparatingEdge(a, b, work) && noSeparatingEdge(b, a, work);
    }

    private static boolean noSeparatingEdge(Face owner, Face other, Work work) {
        for (int i = 0; i < 3; i++) {
            var a = owner.corners.get(i);
            var b = owner.corners.get((i + 1) % 3);
            boolean inside = false;
            for (var point : other.corners) {
                if (orientation(a, b, point, work) == owner.winding) {
                    inside = true;
                    break;
                }
            }
            if (!inside) return false;
        }
        return true;
    }

    private static int orientation(Point2 a, Point2 b, Point2 c, Work work) {
        work.take(1);
        if (same(a, b) || same(a, c) || same(b, c)) return 0;
        double acx = (double) a.x() - c.x(), acy = (double) a.y() - c.y();
        double bcx = (double) b.x() - c.x(), bcy = (double) b.y() - c.y();
        double left = acx * bcy, right = acy * bcx, determinant = left - right;
        // Conservative orient2d roundoff bound. Float inputs cannot under/overflow double products.
        double error = 4e-16 * (Math.abs(left) + Math.abs(right));
        if (Math.abs(determinant) > error) return determinant > 0 ? 1 : -1;
        work.take(64);
        // new BigDecimal(double), deliberately not valueOf: preserve the exact finite binary float.
        var exactAcx = exact(a.x()).subtract(exact(c.x()));
        var exactAcy = exact(a.y()).subtract(exact(c.y()));
        var exactBcx = exact(b.x()).subtract(exact(c.x()));
        var exactBcy = exact(b.y()).subtract(exact(c.y()));
        return exactAcx.multiply(exactBcy).subtract(exactAcy.multiply(exactBcx)).signum();
    }

    private static boolean same(Point2 a, Point2 b) {
        return a.x() == b.x() && a.y() == b.y();
    }

    private static BigDecimal exact(float value) {
        return new BigDecimal((double) value);
    }

    private record Face(
            int index, List<Point2> corners, int winding, double minX, double maxX, double minY, double maxY) {}

    private static final class Work {
        final long maximum;
        long used;

        Work(long maximum) {
            if (maximum < 0) throw new IllegalArgumentException();
            this.maximum = maximum;
        }

        void take(long amount) {
            if (amount > maximum - used) throw new Exhausted();
            used += amount;
        }
    }

    private static final class Exhausted extends RuntimeException {
        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }
}

package dev.turboism.plugin.turbomesh.domain.regions;

/**
 * Floating-point helpers for pixel-space segments and rings.
 *
 * <p>All routines treat pixels as points on a continuous plane whose pixel centres sit at integer
 * coordinates. Tolerance-based predicates use {@link #ON_SEGMENT_EPS} so that clip points computed
 * from the same segment endpoints stay recognisable as lying on that segment.</p>
 */
final class PixelGeometry {

    /** Perpendicular distance below which a point counts as lying on a segment, in pixels. */
    static final double ON_SEGMENT_EPS = 1.0e-7;

    /** One full counter-clockwise revolution, in radians. */
    private static final double FULL_TURN_RADIANS = 2.0 * Math.PI;

    private PixelGeometry() {}

    static double cross(
            final double ax, final double ay, final double bx, final double by, final double px, final double py) {
        return (bx - ax) * (py - ay) - (by - ay) * (px - ax);
    }

    /** Projection parameter of {@code p} on the segment direction, unclamped. */
    static double projection(
            final double ax, final double ay, final double bx, final double by, final double px, final double py) {
        final double dx = bx - ax;
        final double dy = by - ay;
        final double len2 = dx * dx + dy * dy;
        if (len2 <= 0.0) {
            return 0.0;
        }
        return ((px - ax) * dx + (py - ay) * dy) / len2;
    }

    /** Perpendicular distance from {@code p} to the infinite line through {@code a} and {@code b}. */
    static double lineDistance(
            final double ax, final double ay, final double bx, final double by, final double px, final double py) {
        final double length = Math.hypot(bx - ax, by - ay);
        if (length <= 0.0) {
            return Math.hypot(px - ax, py - ay);
        }
        return Math.abs(cross(ax, ay, bx, by, px, py)) / length;
    }

    /** True when {@code p} lies on the segment within {@link #ON_SEGMENT_EPS}. */
    static boolean onSegment(
            final double ax, final double ay, final double bx, final double by, final double px, final double py) {
        if (lineDistance(ax, ay, bx, by, px, py) > ON_SEGMENT_EPS) {
            return false;
        }
        final double t = projection(ax, ay, bx, by, px, py);
        final double length = Math.hypot(bx - ax, by - ay);
        final double slack = length <= 0.0 ? ON_SEGMENT_EPS : ON_SEGMENT_EPS / length;
        return t >= -slack && t <= 1.0 + slack;
    }

    /**
     * Counter-clockwise turn from one direction to the next, in {@code (0, 2*PI]}.
     *
     * <p>Face tracing needs one answer at a junction: the outgoing edge that turns furthest left
     * keeps the face on the left of the incoming edge. A straight continuation reports
     * {@code 2*PI}, so it loses to every real left turn.</p>
     *
     * @return turn angle in radians, strictly positive
     */
    static double leftTurn(
            final double incomingX, final double incomingY, final double outgoingX, final double outgoingY) {
        final double cross = incomingX * outgoingY - incomingY * outgoingX;
        final double dot = incomingX * outgoingX + incomingY * outgoingY;
        final double turn = Math.atan2(cross, dot);
        return turn <= 0.0 ? turn + FULL_TURN_RADIANS : turn;
    }

    /** True when the two segments cross at a single point that lies inside both of them. */
    static boolean properCrossing(
            final double ax,
            final double ay,
            final double bx,
            final double by,
            final double cx,
            final double cy,
            final double dx,
            final double dy) {
        final double d1 = cross(cx, cy, dx, dy, ax, ay);
        final double d2 = cross(cx, cy, dx, dy, bx, by);
        final double d3 = cross(ax, ay, bx, by, cx, cy);
        final double d4 = cross(ax, ay, bx, by, dx, dy);
        return ((d1 > 0.0 && d2 < 0.0) || (d1 < 0.0 && d2 > 0.0)) && ((d3 > 0.0 && d4 < 0.0) || (d3 < 0.0 && d4 > 0.0));
    }

    /** Intersection point of two non-parallel segments, or {@code null} when they are parallel. */
    static double[] intersectionPoint(
            final double ax,
            final double ay,
            final double bx,
            final double by,
            final double cx,
            final double cy,
            final double dx,
            final double dy) {
        final double r1x = bx - ax;
        final double r1y = by - ay;
        final double r2x = dx - cx;
        final double r2y = dy - cy;
        final double denominator = r1x * r2y - r1y * r2x;
        if (Math.abs(denominator) <= 1.0e-12 * Math.hypot(r1x, r1y) * Math.hypot(r2x, r2y)) {
            return null;
        }
        final double t = ((cx - ax) * r2y - (cy - ay) * r2x) / denominator;
        return new double[] {ax + t * r1x, ay + t * r1y};
    }

    /** True when both segments overlap on a common line. */
    static boolean collinearOverlap(
            final double ax,
            final double ay,
            final double bx,
            final double by,
            final double cx,
            final double cy,
            final double dx,
            final double dy) {
        if (lineDistance(ax, ay, bx, by, cx, cy) > ON_SEGMENT_EPS
                || lineDistance(ax, ay, bx, by, dx, dy) > ON_SEGMENT_EPS) {
            return false;
        }
        final double t0 = projection(ax, ay, bx, by, cx, cy);
        final double t1 = projection(ax, ay, bx, by, dx, dy);
        final double low = Math.min(t0, t1);
        final double high = Math.max(t0, t1);
        final double slack = 1.0e-9;
        return high > slack && low < 1.0 - slack;
    }
}

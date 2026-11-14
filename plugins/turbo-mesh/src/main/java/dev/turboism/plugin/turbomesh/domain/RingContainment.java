package dev.turboism.plugin.turbomesh.domain;

import dev.turboism.sdk.cubism.model.Point2;
import java.util.List;

/**
 * Even-odd containment tests for closed rings of {@link Point2} in UV space.
 *
 * <p>Ring nesting decides which sampled boundary is a silhouette and which is a hole, so the
 * predicate must answer the same way for the same ring pair in every caller. Both entry points use
 * the same ray cast; they differ only in whether a point on the boundary counts as inside.</p>
 */
public final class RingContainment {

    private RingContainment() {}

    /**
     * Even-odd ray-cast containment. A point exactly on the boundary may report either answer.
     *
     * @param ring closed ring; the last vertex is implicitly joined to the first
     * @param u horizontal coordinate of the probe point
     * @param v vertical coordinate of the probe point
     * @return {@code true} when the ray cast crosses the ring an odd number of times
     */
    public static boolean pointInRing(final List<Point2> ring, final double u, final double v) {
        boolean inside = false;
        for (int i = 0, j = ring.size() - 1; i < ring.size(); j = i++) {
            final Point2 a = ring.get(i);
            final Point2 b = ring.get(j);
            if ((a.y() > v) != (b.y() > v) && u < (b.x() - a.x()) * (v - a.y()) / (b.y() - a.y()) + a.x()) {
                inside = !inside;
            }
        }
        return inside;
    }

    /**
     * Whether {@code inner} sits inside {@code ring}.
     *
     * <p>Probes the midpoint of {@code inner}'s first edge. A single probe is enough because callers
     * only compare rings that do not intersect.</p>
     *
     * @param ring candidate container
     * @param inner candidate contained ring
     * @return {@code true} when the probe point lies inside {@code ring}
     */
    public static boolean ringHoldsRing(final List<Point2> ring, final List<Point2> inner) {
        final Point2 a = inner.get(0);
        final Point2 b = inner.get(1 % inner.size());
        return pointInRing(ring, (a.x() + b.x()) / 2.0, (a.y() + b.y()) / 2.0);
    }
}

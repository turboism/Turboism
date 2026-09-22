package dev.turboism.plugin.atlasdalsoo.layout;

import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.util.ArrayList;
import java.util.List;

import dev.turboism.sdk.cubism.textureatlas.TextureAtlasOutline;

/**
 * Outline normalization for the polygon backend.
 *
 * <p>All operations are pure JDK ({@link PathIterator}/{@link Area}) and
 * deterministic. Disjoint rings are preserved as separate rings where the caller
 * needs them, or stitched into one simple ring with minimal bridge edges for the
 * packing kernel. Holes are never silently dropped: they are either reported by
 * {@link Rings#hasHoles()} or filled conservatively by {@link #fillHoles}, and the
 * result is always marked so callers can flag the approximation.</p>
 */
public final class OutlineGeometry {

    private OutlineGeometry() {
    }

    /** Flattened ring decomposition of one or more shapes. */
    public record Rings(List<double[][]> outerRings, List<double[][]> holeRings,
        boolean hasHoles) {
        public TextureAtlasOutline toOutline() {
            return new TextureAtlasOutline(outerRings);
        }
    }

    /**
     * Flattens shapes into rings in their own coordinate space.
     *
     * <p>Shapes are unioned with {@link Area} first, so overlapping regions merge
     * exactly; the union's winding rule then separates outer rings (positive
     * signed area) from hole rings (negative). Rings with fewer than three unique
     * vertices or near-zero area are discarded.</p>
     */
    public static Rings decompose(final List<? extends Shape> shapes,
        final double flatness) {
        if (shapes == null || shapes.isEmpty()) {
            return new Rings(List.of(), List.of(), false);
        }
        Area union = null;
        for (final Shape shape : shapes) {
            if (shape == null) {
                continue;
            }
            final Area part = new Area(shape);
            if (union == null) {
                union = part;
            } else {
                union.add(part);
            }
        }
        if (union == null || union.isEmpty()) {
            return new Rings(List.of(), List.of(), false);
        }
        return decomposeArea(union, flatness);
    }

    /**
     * Flattens an {@link Area} into outer and hole rings. Rings are classified
     * by containment — a ring whose vertices all lie inside another ring is a
     * hole — which does not rely on {@code Area}'s winding output convention.
     * Returned rings are normalized to positive (counter-clockwise) signed area.
     */
    public static Rings decomposeArea(final Area area, final double flatness) {
        final List<double[][]> raw = flatten(area, flatness);
        final List<double[][]> rings = new ArrayList<>();
        for (final double[][] ring : raw) {
            if (Math.abs(signedArea(ring)) >= 1e-6 && uniqueVertices(ring) >= 3) {
                rings.add(ring);
            }
        }
        final List<Area> areas = new ArrayList<>();
        for (final double[][] ring : rings) {
            areas.add(toArea(ring));
        }
        final List<double[][]> outer = new ArrayList<>();
        final List<double[][]> holes = new ArrayList<>();
        for (int i = 0; i < rings.size(); i++) {
            boolean contained = false;
            for (int j = 0; j < rings.size(); j++) {
                if (i != j && contains(areas.get(j), rings.get(i))) {
                    contained = true;
                    break;
                }
            }
            final double[][] ring = rings.get(i);
            if (contained) {
                holes.add(signedArea(ring) > 0 ? reverse(ring) : ring);
            } else {
                outer.add(signedArea(ring) < 0 ? reverse(ring) : ring);
            }
        }
        return new Rings(List.copyOf(outer), List.copyOf(holes), !holes.isEmpty());
    }

    private static Area toArea(final double[][] ring) {
        final java.awt.geom.Path2D.Double path = new java.awt.geom.Path2D.Double();
        path.moveTo(ring[0][0], ring[0][1]);
        for (int i = 1; i < ring.length; i++) {
            path.lineTo(ring[i][0], ring[i][1]);
        }
        path.closePath();
        return new Area(path);
    }

    private static boolean contains(final Area outer, final double[][] ring) {
        for (final double[] vertex : ring) {
            if (!outer.contains(vertex[0], vertex[1])) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns rings with holes filled (outer rings only). The result is a
     * conservative over-approximation and must be flagged by the caller.
     */
    public static Rings fillHoles(final Rings rings) {
        return new Rings(rings.outerRings(), List.of(), rings.hasHoles());
    }

    /** Flattens one shape into raw closed rings (no winding classification). */
    public static List<double[][]> flatten(final Shape shape, final double flatness) {
        final List<double[][]> rings = new ArrayList<>();
        final PathIterator it = shape.getPathIterator(null, flatness);
        final double[] seg = new double[6];
        List<double[]> current = null;
        while (!it.isDone()) {
            switch (it.currentSegment(seg)) {
                case PathIterator.SEG_MOVETO -> {
                    current = new ArrayList<>();
                    current.add(new double[] {seg[0], seg[1]});
                }
                case PathIterator.SEG_LINETO -> {
                    if (current != null) {
                        current.add(new double[] {seg[0], seg[1]});
                    }
                }
                case PathIterator.SEG_CLOSE -> {
                    if (current != null && current.size() >= 3) {
                        rings.add(current.toArray(new double[0][]));
                    }
                    current = null;
                }
                default -> { /* flatness eliminates curves */ }
            }
            it.next();
        }
        if (current != null && current.size() >= 3) {
            rings.add(current.toArray(new double[0][]));
        }
        return rings;
    }

    /**
     * Stitches several disjoint outer rings into one simple ring by connecting
     * nearest vertex pairs with zero-width bridges (each bridge edge is traversed
     * twice, once per direction). Concavity is preserved exactly; the bridge adds
     * no area. Deterministic for a fixed input order.
     */
    public static double[][] stitch(final List<double[][]> rings) {
        if (rings.size() == 1) {
            return rings.get(0);
        }
        final List<double[][]> remaining = new ArrayList<>(rings);
        double[][] merged = remaining.remove(0);
        while (!remaining.isEmpty()) {
            int bestRing = 0, bestA = 0, bestB = 0;
            double bestDist = Double.MAX_VALUE;
            for (int r = 0; r < remaining.size(); r++) {
                final double[][] other = remaining.get(r);
                for (int i = 0; i < merged.length; i++) {
                    for (int j = 0; j < other.length; j++) {
                        final double dx = merged[i][0] - other[j][0];
                        final double dy = merged[i][1] - other[j][1];
                        final double d = dx * dx + dy * dy;
                        if (d < bestDist) {
                            bestDist = d;
                            bestRing = r;
                            bestA = i;
                            bestB = j;
                        }
                    }
                }
            }
            final double[][] other = remaining.remove(bestRing);
            merged = bridge(merged, bestA, other, bestB);
        }
        return merged;
    }

    /** Inserts {@code other} into {@code ring} via a bridge between vertex {@code a} and {@code b}. */
    private static double[][] bridge(final double[][] ring, final int a,
        final double[][] other, final int b) {
        final ArrayList<double[]> out = new ArrayList<>(ring.length + other.length + 2);
        for (int i = 0; i <= a; i++) {
            out.add(ring[i]);
        }
        for (int j = 0; j < other.length; j++) {
            out.add(other[(b + j) % other.length]);
        }
        out.add(other[b]); // bridge back
        out.add(ring[a]);
        for (int i = a + 1; i < ring.length; i++) {
            out.add(ring[i]);
        }
        return out.toArray(new double[0][]);
    }

    /**
     * Dilates a ring outward by {@code distance} (flat-cap, miter-join stroke
     * unioned with the fill) and returns the outer boundary ring. Conservative:
     * the result never under-approximates the true offset.
     */
    public static double[][] dilate(final double[][] ring, final double distance) {
        if (distance <= 0) {
            return ring;
        }
        final Path2D.Double path = new Path2D.Double();
        path.moveTo(ring[0][0], ring[0][1]);
        for (int i = 1; i < ring.length; i++) {
            path.lineTo(ring[i][0], ring[i][1]);
        }
        path.closePath();
        final Area dilated = new Area(path);
        final Shape stroke = new java.awt.BasicStroke(
            (float) (distance * 2), java.awt.BasicStroke.CAP_BUTT,
            java.awt.BasicStroke.JOIN_MITER, 4f).createStrokedShape(path);
        dilated.add(new Area(stroke));
        // small flatness keeps the flattened ring within ~0.25px of the true
        // dilation; callers absorb this via DalsooPolygonPlanner's EDGE_SLACK
        final Rings rings = decomposeArea(dilated,
            Math.min(0.25, Math.max(0.05, distance * 0.05)));
        if (rings.outerRings().isEmpty()) {
            return ring;
        }
        return stitch(rings.outerRings());
    }

    /** Ramer-Douglas-Peucker simplification preserving the ring's endpoints. */
    public static double[][] simplify(final double[][] ring, final double epsilon) {
        if (epsilon <= 0 || ring.length <= 4) {
            return ring;
        }
        final boolean[] keep = new boolean[ring.length];
        keep[0] = true;
        keep[ring.length - 1] = true;
        rdp(ring, 0, ring.length - 1, epsilon, keep);
        int count = 0;
        for (final boolean k : keep) {
            if (k) {
                count++;
            }
        }
        if (count < 3) {
            return ring;
        }
        final double[][] out = new double[count][];
        int n = 0;
        for (int i = 0; i < ring.length; i++) {
            if (keep[i]) {
                out[n++] = ring[i];
            }
        }
        return out;
    }

    private static void rdp(final double[][] ring, final int from, final int to,
        final double epsilon, final boolean[] keep) {
        if (to <= from + 1) {
            return;
        }
        final double[] a = ring[from];
        final double[] b = ring[to];
        double maxDist = -1;
        int index = -1;
        for (int i = from + 1; i < to; i++) {
            final double d = pointLineDistance(a, b, ring[i]);
            if (d > maxDist) {
                maxDist = d;
                index = i;
            }
        }
        if (maxDist > epsilon) {
            keep[index] = true;
            rdp(ring, from, index, epsilon, keep);
            rdp(ring, index, to, epsilon, keep);
        }
    }

    private static double pointLineDistance(final double[] a, final double[] b,
        final double[] p) {
        final double dx = b[0] - a[0];
        final double dy = b[1] - a[1];
        final double len = Math.hypot(dx, dy);
        if (len < 1e-9) {
            return Math.hypot(p[0] - a[0], p[1] - a[1]);
        }
        return Math.abs(dy * p[0] - dx * p[1] + b[0] * a[1] - b[1] * a[0]) / len;
    }

    /** Whether a ring is convex (no reflex vertex). */
    public static boolean isConvex(final double[][] ring) {
        final int n = ring.length;
        double sign = 0;
        for (int i = 0; i < n; i++) {
            final double[] a = ring[i];
            final double[] b = ring[(i + 1) % n];
            final double[] c = ring[(i + 2) % n];
            final double cross = (b[0] - a[0]) * (c[1] - b[1])
                - (b[1] - a[1]) * (c[0] - b[0]);
            if (Math.abs(cross) < 1e-9) {
                continue;
            }
            if (sign == 0) {
                sign = Math.signum(cross);
            } else if (Math.signum(cross) != sign) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether a ring is within {@code tolerancePx} of an axis-aligned rectangle
     * (used by the AUTO backend to route near-rectangles to the rectangle path).
     */
    public static boolean isNearRect(final double[][] ring, final double tolerancePx) {
        if (ring.length > 8 || !isConvex(ring)) {
            return false;
        }
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (final double[] p : ring) {
            minX = Math.min(minX, p[0]);
            minY = Math.min(minY, p[1]);
            maxX = Math.max(maxX, p[0]);
            maxY = Math.max(maxY, p[1]);
        }
        for (final double[] p : ring) {
            final boolean onX = Math.abs(p[0] - minX) <= tolerancePx
                || Math.abs(p[0] - maxX) <= tolerancePx;
            final boolean onY = Math.abs(p[1] - minY) <= tolerancePx
                || Math.abs(p[1] - maxY) <= tolerancePx;
            if (!onX || !onY) {
                return false;
            }
        }
        return true;
    }

    static double signedArea(final double[][] ring) {
        double sum = 0;
        for (int i = 0; i < ring.length; i++) {
            final double[] a = ring[i];
            final double[] b = ring[(i + 1) % ring.length];
            sum += a[0] * b[1] - b[0] * a[1];
        }
        return sum * 0.5;
    }

    private static int uniqueVertices(final double[][] ring) {
        int count = 0;
        for (int i = 0; i < ring.length; i++) {
            boolean dup = false;
            for (int j = 0; j < i; j++) {
                if (Math.abs(ring[i][0] - ring[j][0]) < 1e-6
                    && Math.abs(ring[i][1] - ring[j][1]) < 1e-6) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                count++;
            }
        }
        return count;
    }

    private static double[][] reverse(final double[][] ring) {
        final double[][] out = new double[ring.length][];
        for (int i = 0; i < ring.length; i++) {
            out[i] = ring[ring.length - 1 - i];
        }
        return out;
    }

    /** Transforms every vertex by a 2x3 affine matrix ({@code AffineTransform} order). */
    public static double[][] transform(final double[][] ring, final double[] m) {
        final double[][] out = new double[ring.length][];
        for (int i = 0; i < ring.length; i++) {
            final double x = ring[i][0], y = ring[i][1];
            out[i] = new double[] {
                m[0] * x + m[2] * y + m[4],
                m[1] * x + m[3] * y + m[5]
            };
        }
        return out;
    }

    /** Converts a placement transform {@code T(x,y)·R(deg)·S(s)} into matrix form. */
    public static double[] placementMatrix(final double x, final double y,
        final double angleDeg, final double scale) {
        final AffineTransform at = new AffineTransform();
        at.translate(x, y);
        at.rotate(Math.toRadians(angleDeg));
        at.scale(scale, scale);
        final double[] m = new double[6];
        at.getMatrix(m);
        return m;
    }
}

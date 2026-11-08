package dev.turboism.plugin.turbomesh.domain.regions;

import java.util.ArrayList;
import java.util.List;

/**
 * Closed material region with optional holes, expressed in source-pixel coordinates.
 *
 * <p>The shape carries the exact geometry that becomes hard mesh constraints, so direction guides
 * can be clipped against it and their endpoints then land on real constraint edges.</p>
 */
final class PolygonShape {

    /** Chord pieces shorter than this length are numerical noise, in pixels. */
    static final double MIN_PIECE_LENGTH = 1.0e-6;

    private final Polyline exterior;
    private final List<Polyline> holes;

    PolygonShape(final Polyline exterior, final List<Polyline> holes) {
        if (!exterior.closed()) {
            throw new IllegalArgumentException("exterior ring must be closed");
        }
        this.exterior = exterior.signedArea() < 0.0 ? exterior.reversed() : exterior;
        final List<Polyline> normalized = new ArrayList<>(holes.size());
        for (final Polyline hole : holes) {
            normalized.add(hole.signedArea() > 0.0 ? hole.reversed() : hole);
        }
        this.holes = List.copyOf(normalized);
    }

    Polyline exterior() {
        return exterior;
    }

    List<Polyline> holes() {
        return holes;
    }

    List<Polyline> rings() {
        final List<Polyline> rings = new ArrayList<>(holes.size() + 1);
        rings.add(exterior);
        rings.addAll(holes);
        return rings;
    }

    double area() {
        double area = Math.abs(exterior.signedArea());
        for (final Polyline hole : holes) {
            area -= Math.abs(hole.signedArea());
        }
        return area;
    }

    boolean contains(final double x, final double y) {
        if (!exterior.contains(x, y)) {
            return false;
        }
        for (final Polyline hole : holes) {
            if (hole.contains(x, y)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Keeps only the parts of {@code path} that lie inside the material.
     *
     * @return one polyline per inside run, in path order; empty when the path misses the material
     */
    List<Polyline> clip(final Polyline path) {
        final int segments = path.segmentCount();
        if (segments <= 0) {
            return List.of();
        }
        final List<double[]> breaks = new ArrayList<>();
        final List<Boolean> insideFlags = new ArrayList<>();
        breaks.add(new double[] {path.x(0), path.y(0)});
        for (int s = 0; s < segments; s++) {
            final int next = path.nextIndex(s);
            final double ax = path.x(s);
            final double ay = path.y(s);
            final double bx = path.x(next);
            final double by = path.y(next);
            for (final double t : crossingParameters(ax, ay, bx, by)) {
                appendBreak(breaks, insideFlags, ax, ay, bx, by, t);
            }
            appendBreak(breaks, insideFlags, ax, ay, bx, by, 1.0);
        }
        return group(breaks, insideFlags, path.closed());
    }

    /**
     * Longest inside chord of the infinite line through {@code (px, py)} with direction
     * {@code (dx, dy)}.
     *
     * @return endpoints {@code {x1, y1, x2, y2}} or {@code null} when the line misses the material
     */
    double[] longestInsideChord(final double px, final double py, final double dx, final double dy) {
        final List<Double> hits = new ArrayList<>();
        for (final Polyline ring : rings()) {
            for (int s = 0; s < ring.segmentCount(); s++) {
                final int next = ring.nextIndex(s);
                final double cx = ring.x(s);
                final double cy = ring.y(s);
                final double ex = ring.x(next);
                final double ey = ring.y(next);
                final double segX = ex - cx;
                final double segY = ey - cy;
                final double denominator = dx * segY - dy * segX;
                if (Math.abs(denominator) <= 1.0e-12) {
                    continue;
                }
                final double t = ((cx - px) * segY - (cy - py) * segX) / denominator;
                final double u =
                        Math.abs(segY) >= Math.abs(segX) ? (py + t * dy - cy) / segY : (px + t * dx - cx) / segX;
                if (u >= -1.0e-9 && u <= 1.0 + 1.0e-9) {
                    hits.add(t);
                }
            }
        }
        if (hits.size() < 2) {
            return null;
        }
        hits.sort(Double::compare);
        double bestLength = 0.0;
        double[] best = null;
        for (int i = 0; i + 1 < hits.size(); i++) {
            final double t0 = hits.get(i);
            final double t1 = hits.get(i + 1);
            if (t1 - t0 <= 0.0) {
                continue;
            }
            final double mid = 0.5 * (t0 + t1);
            if (!contains(px + mid * dx, py + mid * dy)) {
                continue;
            }
            final double length = t1 - t0;
            if (length > bestLength) {
                bestLength = length;
                best = new double[] {px + t0 * dx, py + t0 * dy, px + t1 * dx, py + t1 * dy};
            }
        }
        return best;
    }

    private List<Double> crossingParameters(final double ax, final double ay, final double bx, final double by) {
        final List<Double> ts = new ArrayList<>();
        for (final Polyline ring : rings()) {
            for (int s = 0; s < ring.segmentCount(); s++) {
                final int next = ring.nextIndex(s);
                final double[] hit = PixelGeometry.intersectionPoint(
                        ax, ay, bx, by, ring.x(s), ring.y(s), ring.x(next), ring.y(next));
                if (hit == null) {
                    continue;
                }
                final double t = PixelGeometry.projection(ax, ay, bx, by, hit[0], hit[1]);
                if (t > 0.0 && t < 1.0) {
                    ts.add(t);
                }
            }
        }
        ts.sort(Double::compare);
        final List<Double> unique = new ArrayList<>(ts.size());
        for (final double t : ts) {
            if (unique.isEmpty() || t - unique.get(unique.size() - 1) > 1.0e-9) {
                unique.add(t);
            }
        }
        return unique;
    }

    private void appendBreak(
            final List<double[]> breaks,
            final List<Boolean> insideFlags,
            final double ax,
            final double ay,
            final double bx,
            final double by,
            final double t) {
        final double[] previous = breaks.get(breaks.size() - 1);
        final double[] current =
                t >= 1.0 ? new double[] {bx, by} : new double[] {ax + t * (bx - ax), ay + t * (by - ay)};
        insideFlags.add(contains(0.5 * (previous[0] + current[0]), 0.5 * (previous[1] + current[1])));
        breaks.add(current);
    }

    private List<Polyline> group(
            final List<double[]> breaks, final List<Boolean> insideFlags, final boolean closedPath) {
        final List<int[]> runs = new ArrayList<>();
        int index = 0;
        while (index < insideFlags.size()) {
            if (!insideFlags.get(index)) {
                index++;
                continue;
            }
            final int start = index;
            while (index < insideFlags.size() && insideFlags.get(index)) {
                index++;
            }
            runs.add(new int[] {start, index});
        }
        final List<Polyline> pieces = new ArrayList<>(runs.size());
        for (final int[] run : runs) {
            pieces.add(piece(breaks, run[0], run[1]));
        }
        if (closedPath && runs.size() >= 2 && insideFlags.get(0) && insideFlags.get(insideFlags.size() - 1)) {
            final Polyline tail = pieces.remove(pieces.size() - 1);
            final Polyline head = pieces.remove(0);
            pieces.add(0, join(tail, head));
        }
        pieces.removeIf(piece -> piece == null || piece.length() <= MIN_PIECE_LENGTH);
        return pieces;
    }

    private static Polyline piece(final List<double[]> breaks, final int from, final int to) {
        final int count = to - from + 1;
        final double[] xs = new double[count];
        final double[] ys = new double[count];
        for (int i = 0; i < count; i++) {
            xs[i] = breaks.get(from + i)[0];
            ys[i] = breaks.get(from + i)[1];
        }
        final boolean closed = count > 2
                && Math.abs(xs[0] - xs[count - 1]) <= Polyline.COINCIDENT_EPS
                && Math.abs(ys[0] - ys[count - 1]) <= Polyline.COINCIDENT_EPS;
        if (closed) {
            return Polyline.closedFromRepeated(xs, ys);
        }
        return new Polyline(xs, ys, false);
    }

    private static Polyline join(final Polyline tail, final Polyline head) {
        final double[] xs = new double[tail.size() + head.size()];
        final double[] ys = new double[tail.size() + head.size()];
        for (int i = 0; i < tail.size(); i++) {
            xs[i] = tail.x(i);
            ys[i] = tail.y(i);
        }
        for (int i = 0; i < head.size(); i++) {
            xs[tail.size() + i] = head.x(i);
            ys[tail.size() + i] = head.y(i);
        }
        return new Polyline(xs, ys, false);
    }
}

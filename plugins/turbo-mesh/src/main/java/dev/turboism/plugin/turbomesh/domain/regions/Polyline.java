package dev.turboism.plugin.turbomesh.domain.regions;

import dev.turboism.plugin.turbomesh.domain.TextureEdges;
import dev.turboism.sdk.cubism.model.Point2;
import java.util.ArrayList;
import java.util.List;

/**
 * Immutable polyline in source-pixel coordinates.
 *
 * <p>A closed polyline owns an implicit segment from its last point back to its first one, so the
 * first point never appears twice. Segment geometry keeps {@code double} precision because sites
 * further down the pipeline are deduplicated at sub-micropixel distance.</p>
 */
record Polyline(double[] xs, double[] ys, boolean closed) {

    /** Coordinates closer than this distance count as the same position. */
    static final double COINCIDENT_EPS = 1.0e-9;

    Polyline {
        if (xs.length != ys.length) {
            throw new IllegalArgumentException("xs and ys must have equal length");
        }
    }

    static Polyline of(final List<Point2> points, final boolean closed) {
        final double[] xs = new double[points.size()];
        final double[] ys = new double[points.size()];
        for (int i = 0; i < points.size(); i++) {
            xs[i] = points.get(i).x();
            ys[i] = points.get(i).y();
        }
        return new Polyline(xs, ys, closed);
    }

    /** Builds a closed polyline from a ring that repeats its first point at the end. */
    static Polyline closedFromRepeated(final double[] xs, final double[] ys) {
        final int n = xs.length;
        if (n > 1 && Math.abs(xs[0] - xs[n - 1]) <= COINCIDENT_EPS && Math.abs(ys[0] - ys[n - 1]) <= COINCIDENT_EPS) {
            return new Polyline(java.util.Arrays.copyOf(xs, n - 1), java.util.Arrays.copyOf(ys, n - 1), true);
        }
        return new Polyline(xs, ys, true);
    }

    int size() {
        return xs.length;
    }

    int segmentCount() {
        return closed ? xs.length : Math.max(0, xs.length - 1);
    }

    int nextIndex(final int i) {
        return (i + 1) % xs.length;
    }

    double x(final int i) {
        return xs[i];
    }

    double y(final int i) {
        return ys[i];
    }

    double segmentLength(final int i) {
        final int j = nextIndex(i);
        return Math.hypot(xs[j] - xs[i], ys[j] - ys[i]);
    }

    double length() {
        double total = 0.0;
        for (int i = 0; i < segmentCount(); i++) {
            total += segmentLength(i);
        }
        return total;
    }

    /** Shoelace area; positive for the ring orientation that encloses its face. */
    double signedArea() {
        if (!closed) {
            return 0.0;
        }
        double sum = 0.0;
        for (int i = 0; i < xs.length; i++) {
            final int j = nextIndex(i);
            sum += xs[i] * ys[j] - xs[j] * ys[i];
        }
        return 0.5 * sum;
    }

    /** Area-weighted centroid of a closed ring. */
    double[] centroid() {
        if (!closed) {
            throw new IllegalStateException("centroid requires a closed polyline");
        }
        double cx = 0.0;
        double cy = 0.0;
        double area = 0.0;
        for (int i = 0; i < xs.length; i++) {
            final int j = nextIndex(i);
            final double cross = xs[i] * ys[j] - xs[j] * ys[i];
            area += cross;
            cx += (xs[i] + xs[j]) * cross;
            cy += (ys[i] + ys[j]) * cross;
        }
        if (Math.abs(area) <= COINCIDENT_EPS) {
            double sx = 0.0;
            double sy = 0.0;
            for (int i = 0; i < xs.length; i++) {
                sx += xs[i];
                sy += ys[i];
            }
            return new double[] {sx / xs.length, sy / xs.length};
        }
        return new double[] {cx / (3.0 * area), cy / (3.0 * area)};
    }

    /** Even-odd containment test against the ring the polyline traces. */
    boolean contains(final double px, final double py) {
        boolean inside = false;
        for (int i = 0; i < segmentCount(); i++) {
            final int j = nextIndex(i);
            final double yi = ys[i];
            final double yj = ys[j];
            if ((yi > py) == (yj > py)) {
                continue;
            }
            final double t = (py - yi) / (yj - yi);
            if (px < xs[i] + t * (xs[j] - xs[i])) {
                inside = !inside;
            }
        }
        return inside;
    }

    Polyline reversed() {
        final double[] rx = new double[xs.length];
        final double[] ry = new double[ys.length];
        for (int i = 0; i < xs.length; i++) {
            rx[i] = xs[xs.length - 1 - i];
            ry[i] = ys[ys.length - 1 - i];
        }
        return new Polyline(rx, ry, closed);
    }

    /** Shortens the polyline so that every segment stays below {@code maxSegmentLength}. */
    Polyline resampled(final double maxSegmentLength) {
        if (maxSegmentLength <= 0.0) {
            throw new IllegalArgumentException("maxSegmentLength must be positive");
        }
        final List<double[]> out = new ArrayList<>();
        out.add(new double[] {xs[0], ys[0]});
        for (int i = 0; i < segmentCount(); i++) {
            final int j = nextIndex(i);
            final double ax = xs[i];
            final double ay = ys[i];
            final double bx = xs[j];
            final double by = ys[j];
            final int parts = Math.max(1, (int) Math.ceil(Math.hypot(bx - ax, by - ay) / maxSegmentLength));
            for (int step = 1; step <= parts; step++) {
                // The final step reuses the exact source coordinate so shared junctions stay
                // bit-identical and the site registry never splits them.
                if (step == parts) {
                    out.add(new double[] {bx, by});
                } else {
                    final double t = (double) step / parts;
                    out.add(new double[] {ax + t * (bx - ax), ay + t * (by - ay)});
                }
            }
        }
        if (closed) {
            out.remove(out.size() - 1);
            return new Polyline(column(out, 0), column(out, 1), true);
        }
        return new Polyline(column(out, 0), column(out, 1), false);
    }

    /** Douglas–Peucker simplification at the given pixel tolerance. */
    Polyline simplified(final double tolerance) {
        if (tolerance <= 0.0 || xs.length <= 2) {
            return this;
        }
        final List<Point2> points = new ArrayList<>(xs.length);
        for (int i = 0; i < xs.length; i++) {
            points.add(new Point2((float) xs[i], (float) ys[i]));
        }
        return of(TextureEdges.douglasPeucker(points, tolerance, closed), closed);
    }

    private static double[] column(final List<double[]> rows, final int column) {
        final double[] out = new double[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            out[i] = rows.get(i)[column];
        }
        return out;
    }
}

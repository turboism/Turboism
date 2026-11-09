package dev.turboism.plugin.turbomesh.domain.regions;

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
}

package dev.turboism.adapter.cubism.mesh;

import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;

/** Measures the visible footprint and total draw area of a triangulated mesh. */
record MeshTriangleCoverage(Area footprint, double doubleArea) {
    private static final int XY_STRIDE = 2;
    private static final int TRIANGLE_STRIDE = 3;
    private static final int PATH_COORDINATE_CAPACITY = 6;
    private static final double RELATIVE_TOLERANCE = 1e-6;

    static MeshTriangleCoverage of(final float[] xy, final int[] indices) {
        final Path2D.Double path = new Path2D.Double();
        double sum = 0;
        for (int index = 0; index < indices.length; index += TRIANGLE_STRIDE) {
            final int a = indices[index] * XY_STRIDE;
            final int b = indices[index + 1] * XY_STRIDE;
            final int c = indices[index + 2] * XY_STRIDE;
            final double ax = xy[a];
            final double ay = xy[a + 1];
            final double bx = xy[b];
            final double by = xy[b + 1];
            final double cx = xy[c];
            final double cy = xy[c + 1];
            final double signed = cross(bx - ax, by - ay, cx - ax, cy - ay);
            sum += Math.abs(signed);
            path.moveTo(ax, ay);
            // Use one winding direction so overlapping triangles cannot cancel coverage.
            if (signed >= 0) {
                path.lineTo(bx, by);
                path.lineTo(cx, cy);
            } else {
                path.lineTo(cx, cy);
                path.lineTo(bx, by);
            }
            path.closePath();
        }
        return new MeshTriangleCoverage(new Area(path), sum);
    }

    boolean isEmpty() {
        return footprint.isEmpty();
    }

    boolean matches(final MeshTriangleCoverage candidate) {
        if (candidate == null) return false;
        final double tolerance = doubleArea * RELATIVE_TOLERANCE;
        if (Math.abs(candidate.doubleArea - doubleArea) > tolerance) return false;
        final Area difference = new Area(footprint);
        difference.exclusiveOr(candidate.footprint);
        return planarDoubleArea(difference) <= tolerance;
    }

    private static double planarDoubleArea(final Area area) {
        final PathIterator path = area.getPathIterator(null);
        final double[] coordinates = new double[PATH_COORDINATE_CAPACITY];
        double originX = 0;
        double originY = 0;
        double priorX = 0;
        double priorY = 0;
        double sum = 0;
        while (!path.isDone()) {
            switch (path.currentSegment(coordinates)) {
                case PathIterator.SEG_MOVETO -> {
                    originX = coordinates[0];
                    originY = coordinates[1];
                    priorX = 0;
                    priorY = 0;
                }
                case PathIterator.SEG_LINETO -> {
                    final double x = coordinates[0] - originX;
                    final double y = coordinates[1] - originY;
                    sum += cross(priorX, priorY, x, y);
                    priorX = x;
                    priorY = y;
                }
                default -> {}
            }
            path.next();
        }
        return Math.abs(sum);
    }

    private static double cross(final double ax, final double ay, final double bx, final double by) {
        return ax * by - ay * bx;
    }
}

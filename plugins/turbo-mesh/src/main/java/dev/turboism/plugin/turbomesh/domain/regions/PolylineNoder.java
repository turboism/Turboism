package dev.turboism.plugin.turbomesh.domain.regions;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits polylines at every point where they meet.
 *
 * <p>Texture borders and direction guides must share junction vertices before the mesh builder
 * sees them. Without noding, a guide would end on the interior of a border edge, and the
 * triangulator would have to invent a Steiner point that no region owns.</p>
 */
final class PolylineNoder {

    private PolylineNoder() {}

    /** @return paths re-cut so that any two of them meet only at shared endpoints */
    static List<BoundaryPath> node(final List<BoundaryPath> paths) {
        final List<double[]> segments = new ArrayList<>();
        for (final BoundaryPath path : paths) {
            final Polyline line = path.line();
            for (int i = 0; i < line.segmentCount(); i++) {
                final int next = line.nextIndex(i);
                segments.add(new double[] {line.x(i), line.y(i), line.x(next), line.y(next)});
            }
        }
        final List<List<double[]>> splits = new ArrayList<>(segments.size());
        for (int i = 0; i < segments.size(); i++) {
            splits.add(new ArrayList<>(2));
        }
        for (int i = 0; i < segments.size(); i++) {
            for (int j = i + 1; j < segments.size(); j++) {
                splitAtSharedPoints(segments.get(i), segments.get(j), splits.get(i), splits.get(j));
            }
        }
        final List<BoundaryPath> out = new ArrayList<>(paths.size());
        int segmentIndex = 0;
        for (final BoundaryPath source : paths) {
            final Polyline line = source.line();
            final List<double[]> chain = new ArrayList<>();
            for (int i = 0; i < line.segmentCount(); i++) {
                final double[] segment = segments.get(segmentIndex);
                final List<double[]> points = splits.get(segmentIndex);
                segmentIndex++;
                if (chain.isEmpty()) {
                    chain.add(new double[] {line.x(i), line.y(i)});
                }
                points.sort((first, second) -> Double.compare(
                        PixelGeometry.projection(segment[0], segment[1], segment[2], segment[3], first[0], first[1]),
                        PixelGeometry.projection(
                                segment[0], segment[1], segment[2], segment[3], second[0], second[1])));
                for (final double[] point : points) {
                    append(chain, point);
                }
                final int next = line.nextIndex(i);
                append(chain, new double[] {line.x(next), line.y(next)});
            }
            final Polyline noded = toPolyline(chain, line.closed());
            if (noded != null) {
                out.add(source.withLine(noded));
            }
        }
        return out;
    }

    private static void splitAtSharedPoints(
            final double[] first,
            final double[] second,
            final List<double[]> firstSplits,
            final List<double[]> secondSplits) {
        final double ax = first[0];
        final double ay = first[1];
        final double bx = first[2];
        final double by = first[3];
        final double cx = second[0];
        final double cy = second[1];
        final double dx = second[2];
        final double dy = second[3];
        final boolean cOnFirst = isInterior(ax, ay, bx, by, cx, cy);
        final boolean dOnFirst = isInterior(ax, ay, bx, by, dx, dy);
        final boolean aOnSecond = isInterior(cx, cy, dx, dy, ax, ay);
        final boolean bOnSecond = isInterior(cx, cy, dx, dy, bx, by);
        if (cOnFirst) {
            firstSplits.add(new double[] {cx, cy});
        }
        if (dOnFirst) {
            firstSplits.add(new double[] {dx, dy});
        }
        if (aOnSecond) {
            secondSplits.add(new double[] {ax, ay});
        }
        if (bOnSecond) {
            secondSplits.add(new double[] {bx, by});
        }
        if (cOnFirst || dOnFirst || aOnSecond || bOnSecond) {
            return;
        }
        if (!PixelGeometry.properCrossing(ax, ay, bx, by, cx, cy, dx, dy)) {
            return;
        }
        final double[] hit = PixelGeometry.intersectionPoint(ax, ay, bx, by, cx, cy, dx, dy);
        if (hit != null) {
            firstSplits.add(hit);
            secondSplits.add(hit);
        }
    }

    /** True when the point lies inside the segment, excluding both endpoints. */
    private static boolean isInterior(
            final double ax, final double ay, final double bx, final double by, final double px, final double py) {
        if (!PixelGeometry.onSegment(ax, ay, bx, by, px, py)) {
            return false;
        }
        final double length = Math.hypot(bx - ax, by - ay);
        if (length <= 0.0) {
            return false;
        }
        final double t = PixelGeometry.projection(ax, ay, bx, by, px, py);
        final double slack = PixelGeometry.ON_SEGMENT_EPS / length;
        return t > slack && t < 1.0 - slack;
    }

    private static void append(final List<double[]> chain, final double[] point) {
        if (!chain.isEmpty()) {
            final double[] last = chain.get(chain.size() - 1);
            if (Math.abs(last[0] - point[0]) <= PixelGeometry.ON_SEGMENT_EPS
                    && Math.abs(last[1] - point[1]) <= PixelGeometry.ON_SEGMENT_EPS) {
                return;
            }
        }
        chain.add(point);
    }

    private static Polyline toPolyline(final List<double[]> chain, final boolean closed) {
        if (chain.isEmpty()) {
            return null;
        }
        final double[] xs = new double[chain.size()];
        final double[] ys = new double[chain.size()];
        for (int i = 0; i < chain.size(); i++) {
            xs[i] = chain.get(i)[0];
            ys[i] = chain.get(i)[1];
        }
        if (closed && xs.length > 2) {
            return Polyline.closedFromRepeated(xs, ys);
        }
        return new Polyline(xs, ys, false);
    }
}

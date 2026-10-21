package dev.turboism.plugin.atlasnesting.layout;

import dev.turboism.sdk.cubism.textureatlas.TextureAtlasOutline;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Conservative scan conversion: filled interiors plus a supercover of every edge. */
final class OutlineRasterizer {
    record Raster(BitMask mask, double originX, double originY, Rectangle2D rawBounds) {}

    /** Rotate whole occupied cells exactly; no re-buffering or re-rasterization. */
    static Raster quarterTurn(final Raster source, final int turns, final int cell) {
        if (turns == 0) return source;
        final BitMask from = source.mask();
        final BitMask to =
                new BitMask((turns & 1) == 0 ? from.width : from.height, (turns & 1) == 0 ? from.height : from.width);
        for (int y = 0; y < from.height; y++) {
            for (int w = 0; w < from.stride; w++) {
                long bits = from.words[y * from.stride + w];
                while (bits != 0) {
                    final int x = w * 64 + Long.numberOfTrailingZeros(bits);
                    final int nx = turns == 1 ? from.height - 1 - y : turns == 2 ? from.width - 1 - x : y;
                    final int ny = turns == 1 ? x : turns == 2 ? from.height - 1 - y : from.width - 1 - x;
                    to.span(ny, nx, nx);
                    bits &= bits - 1;
                }
            }
        }
        final double ox = turns == 1
                ? -source.originY() - from.height * (double) cell
                : turns == 2 ? -source.originX() - from.width * (double) cell : source.originY();
        final double oy = turns == 1
                ? source.originX()
                : turns == 2
                        ? -source.originY() - from.height * (double) cell
                        : -source.originX() - from.width * (double) cell;
        final Rectangle2D bounds = AffineTransform.getQuadrantRotateInstance(turns)
                .createTransformedShape(source.rawBounds())
                .getBounds2D();
        to.prepare();
        return new Raster(to, ox, oy, bounds);
    }

    static Shape shape(final TextureAtlasOutline outline, final AffineTransform transform) {
        // Every SDK ring is an outer boundary. Independent regions are unioned,
        // never stitched or interpreted as holes by an even/odd winding rule.
        final Area union = new Area();
        for (final double[][] ring : outline.rings()) {
            final Path2D.Double path = new Path2D.Double();
            path.moveTo(ring[0][0], ring[0][1]);
            for (int i = 1; i < ring.length; i++) path.lineTo(ring[i][0], ring[i][1]);
            path.closePath();
            union.add(new Area(path));
        }
        return transform.createTransformedShape(union);
    }

    static Raster raster(final Shape raw, final double gap, final int cell) {
        return raster(raw, gap, cell, null);
    }

    static Raster raster(final Shape raw, final double gap, final int cell, final Rectangle2D clip) {
        final Rectangle2D rawBounds = raw.getBounds2D();
        // Rasterize the raw fill and the union of edge capsules directly. Building
        // Area(BasicStroke(...)) first is expensive for noisy real mesh contours.
        // An edge capsule is its normal-offset rectangle plus endpoint discs:
        // together these contain the exact round-join outward buffer.
        final double radius = gap > 0 ? (gap + 0.3) * 0.5 : 0;
        Rectangle2D bounds = new Rectangle2D.Double(
                rawBounds.getMinX() - radius,
                rawBounds.getMinY() - radius,
                rawBounds.getWidth() + radius * 2,
                rawBounds.getHeight() + radius * 2);
        if (clip != null) bounds = bounds.createIntersection(clip);
        final double ox = Math.floor(bounds.getMinX() / cell) * cell;
        final double oy = Math.floor(bounds.getMinY() / cell) * cell;
        // An edge exactly on the upper grid boundary belongs to the last interior
        // cell; lower/upper edges are still covered by the clipped supercover.
        final int width = Math.max(1, (int) Math.ceil((bounds.getMaxX() - ox) / cell));
        final int height = Math.max(1, (int) Math.ceil((bounds.getMaxY() - oy) / cell));
        final BitMask mask = new BitMask(width, height);
        final AffineTransform grid = AffineTransform.getScaleInstance(1.0 / cell, 1.0 / cell);
        grid.translate(-ox, -oy);
        final List<double[]> edges = edges(raw.getPathIterator(grid, 0.1 / cell));
        final double[] crosses = new double[edges.size()];
        for (int y = 0; y < height; y++) {
            final double scan = y + 0.5;
            int count = 0;
            for (final double[] e : edges) {
                if ((e[1] <= scan && scan < e[3]) || (e[3] <= scan && scan < e[1])) {
                    crosses[count++] = e[0] + (scan - e[1]) * (e[2] - e[0]) / (e[3] - e[1]);
                }
            }
            Arrays.sort(crosses, 0, count);
            for (int i = 0; i + 1 < count; i += 2) {
                mask.span(y, (int) Math.floor(crosses[i]), (int) Math.floor(crosses[i + 1]));
            }
        }
        // Cover every boundary over the full cell slab, including arbitrarily
        // thin slanted pieces. Capsules also cover cells touched by the padding.
        for (final double[] e : edges) capsule(mask, e, radius / cell);
        mask.prepare();
        return new Raster(mask, ox, oy, rawBounds);
    }

    private static void capsule(final BitMask mask, final double[] edge, final double radius) {
        final double dx = edge[2] - edge[0], dy = edge[3] - edge[1];
        final double length = Math.hypot(dx, dy);
        final double nx = length == 0 ? 0 : -dy * radius / length;
        final double ny = length == 0 ? 0 : dx * radius / length;
        final double[] xs = {edge[0] + nx, edge[2] + nx, edge[2] - nx, edge[0] - nx};
        final double[] ys = {edge[1] + ny, edge[3] + ny, edge[3] - ny, edge[1] - ny};
        final int y0 = Math.max(0, (int) Math.floor(Math.min(edge[1], edge[3]) - radius));
        final int y1 = Math.min(mask.height - 1, (int) Math.floor(Math.max(edge[1], edge[3]) + radius));
        for (int y = y0; y <= y1; y++) {
            double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
            // Clip the offset rectangle to this complete row slab. Its vertices
            // and intersections with the slab edges contain every x extremum.
            for (int i = 0; i < 4; i++) {
                if (ys[i] >= y && ys[i] <= y + 1) {
                    min = Math.min(min, xs[i]);
                    max = Math.max(max, xs[i]);
                }
                final int j = (i + 1) & 3;
                if (ys[i] == ys[j]) continue;
                for (int boundary = y; boundary <= y + 1; boundary++) {
                    final double t = (boundary - ys[i]) / (ys[j] - ys[i]);
                    if (t >= 0 && t <= 1) {
                        final double x = xs[i] + t * (xs[j] - xs[i]);
                        min = Math.min(min, x);
                        max = Math.max(max, x);
                    }
                }
            }
            for (int endpoint = 0; endpoint < 4; endpoint += 2) {
                final double distance = Math.max(0, Math.max(y - edge[endpoint + 1], edge[endpoint + 1] - (y + 1)));
                if (distance > radius) continue;
                final double extent = Math.sqrt(Math.max(0, radius * radius - distance * distance));
                min = Math.min(min, edge[endpoint] - extent);
                max = Math.max(max, edge[endpoint] + extent);
            }
            if (min <= max) mask.span(y, (int) Math.floor(min), (int) Math.floor(max));
        }
    }

    private static List<double[]> edges(final PathIterator iterator) {
        final List<double[]> result = new ArrayList<>();
        final double[] point = new double[6];
        double x = 0, y = 0, startX = 0, startY = 0;
        while (!iterator.isDone()) {
            switch (iterator.currentSegment(point)) {
                case PathIterator.SEG_MOVETO -> {
                    x = startX = point[0];
                    y = startY = point[1];
                }
                case PathIterator.SEG_LINETO -> {
                    result.add(new double[] {x, y, point[0], point[1]});
                    x = point[0];
                    y = point[1];
                }
                case PathIterator.SEG_CLOSE -> result.add(new double[] {x, y, startX, startY});
                default -> throw new IllegalArgumentException("Expected flattened outline");
            }
            iterator.next();
        }
        return result;
    }

    private OutlineRasterizer() {}
}

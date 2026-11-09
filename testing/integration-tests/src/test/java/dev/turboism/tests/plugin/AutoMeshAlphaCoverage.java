package dev.turboism.tests.plugin;

import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.cubism.model.TexturePixels;
import java.util.List;

/**
 * Alpha Intersection-over-Union of one mesh against the texture pixels it samples.
 *
 * <p>IoU answers the only question a mesh replacement must answer visually: does the new mesh still
 * cover the same opaque pixels as the texture region it was generated from? A mesh with the same
 * vertex count can still cover half the silhouette, so the count alone is not evidence.</p>
 *
 * <p>The comparison rasterizes the triangles through the texture's own uv-to-pixel affine, because a
 * mesh uv is normalized over the host's cached-image canvas rather than over the raster. Using
 * {@code u * width} would shift every triangle on a padded canvas and report a wrong IoU.</p>
 *
 * <p>Rasterization samples pixel centres on a bounded grid. The bound keeps a large texture from
 * turning one probe step into minutes of work, and a centre sample is the same rule the Editor's own
 * hit testing uses.</p>
 */
final class AutoMeshAlphaCoverage {

    /** Alpha value above which a pixel counts as opaque. */
    static final int DEFAULT_ALPHA_THRESHOLD = 10;

    /** Longest raster edge the sampler evaluates; a larger texture is sampled with a stride. */
    static final int DEFAULT_MAX_RASTER_EDGE = 512;

    /** Barycentric tolerance that keeps a sample exactly on a shared edge inside both triangles. */
    private static final double EDGE_TOLERANCE = -1.0e-9;

    private static final int TRIANGLE_VERTICES = 3;
    private static final int ALPHA_SHIFT = 24;
    private static final int CHANNEL_MASK = 0xFF;
    private static final double CENTRE_OFFSET = 0.5;

    private AutoMeshAlphaCoverage() {}

    /**
     * Returns the IoU of one mesh against the opaque pixels of its texture.
     *
     * @param geometry the mesh to rasterize
     * @param texture the texture the mesh samples
     * @param alphaThreshold smallest alpha value that counts as opaque
     * @param maxRasterEdge longest raster edge the sampler evaluates
     * @return the IoU in {@code [0, 1]}, or {@code 0} when either side covers nothing
     */
    static double intersectionOverUnion(
            final ArtMeshGeometry geometry,
            final TexturePixels texture,
            final int alphaThreshold,
            final int maxRasterEdge) {
        final Raster raster = Raster.of(texture, maxRasterEdge);
        final boolean[] covered = rasterize(geometry, texture.uvToPixel(), raster);
        int intersection = 0;
        int union = 0;
        for (int index = 0; index < covered.length; index++) {
            final boolean opaque = raster.opaque(index, alphaThreshold);
            if (covered[index] && opaque) {
                intersection++;
            }
            if (covered[index] || opaque) {
                union++;
            }
        }
        return union == 0 ? 0.0 : intersection / (double) union;
    }

    /**
     * Returns the fraction of opaque texture pixels the mesh covers.
     *
     * <p>Recall separates "kept the shape" from "kept the shape and nothing else": a mesh that keeps
     * the silhouette scores near one even when it adds no pixels, so the probe reports both.</p>
     *
     * @param geometry the mesh to rasterize
     * @param texture the texture the mesh samples
     * @param alphaThreshold smallest alpha value that counts as opaque
     * @param maxRasterEdge longest raster edge the sampler evaluates
     * @return the covered fraction of opaque pixels in {@code [0, 1]}
     */
    static double opaqueRecall(
            final ArtMeshGeometry geometry,
            final TexturePixels texture,
            final int alphaThreshold,
            final int maxRasterEdge) {
        final Raster raster = Raster.of(texture, maxRasterEdge);
        final boolean[] covered = rasterize(geometry, texture.uvToPixel(), raster);
        int opaque = 0;
        int coveredOpaque = 0;
        for (int index = 0; index < covered.length; index++) {
            if (!raster.opaque(index, alphaThreshold)) {
                continue;
            }
            opaque++;
            if (covered[index]) {
                coveredOpaque++;
            }
        }
        return opaque == 0 ? 0.0 : coveredOpaque / (double) opaque;
    }

    /**
     * Rasterizes the mesh triangles into a coverage mask.
     *
     * @param geometry the mesh to rasterize
     * @param uvToPixel the texture's uv-to-pixel affine
     * @param raster the sampling grid
     * @return one flag per sampled pixel, row-major
     */
    static boolean[] rasterize(final ArtMeshGeometry geometry, final double[] uvToPixel, final Raster raster) {
        final boolean[] covered = new boolean[raster.width() * raster.height()];
        final List<Point2> uvs = geometry.uvs();
        final List<Integer> indices = geometry.triangleIndices();
        final double[] xs = new double[uvs.size()];
        final double[] ys = new double[uvs.size()];
        for (int index = 0; index < uvs.size(); index++) {
            final Point2 uv = uvs.get(index);
            xs[index] = uvToPixel[0] * uv.x() + uvToPixel[1] * uv.y() + uvToPixel[2];
            ys[index] = uvToPixel[3] * uv.x() + uvToPixel[4] * uv.y() + uvToPixel[5];
        }
        for (int offset = 0; offset + TRIANGLE_VERTICES - 1 < indices.size(); offset += TRIANGLE_VERTICES) {
            fillTriangle(
                    xs, ys, indices.get(offset), indices.get(offset + 1), indices.get(offset + 2), raster, covered);
        }
        return covered;
    }

    private static void fillTriangle(
            final double[] xs,
            final double[] ys,
            final int a,
            final int b,
            final int c,
            final Raster raster,
            final boolean[] covered) {
        final double area = cross(xs[a], ys[a], xs[b], ys[b], xs[c], ys[c]);
        if (area == 0.0) {
            return;
        }
        final double scale = raster.scale();
        final int minX = clamp(Math.floor(Math.min(xs[a], Math.min(xs[b], xs[c])) * scale), raster.width());
        final int maxX = clamp(Math.ceil(Math.max(xs[a], Math.max(xs[b], xs[c])) * scale), raster.width());
        final int minY = clamp(Math.floor(Math.min(ys[a], Math.min(ys[b], ys[c])) * scale), raster.height());
        final int maxY = clamp(Math.ceil(Math.max(ys[a], Math.max(ys[b], ys[c])) * scale), raster.height());
        for (int y = minY; y < maxY; y++) {
            final double sampleY = (y + CENTRE_OFFSET) / scale;
            for (int x = minX; x < maxX; x++) {
                final double sampleX = (x + CENTRE_OFFSET) / scale;
                if (inside(xs, ys, a, b, c, sampleX, sampleY, area)) {
                    covered[y * raster.width() + x] = true;
                }
            }
        }
    }

    /**
     * Reports whether a sample lies inside the triangle, using the sign of the three edge crosses.
     *
     * <p>The tolerance keeps a sample exactly on an edge inside both triangles that share it, so a
     * shared edge is never lost to floating-point rounding.</p>
     */
    private static boolean inside(
            final double[] xs,
            final double[] ys,
            final int a,
            final int b,
            final int c,
            final double x,
            final double y,
            final double area) {
        final double sign = Math.signum(area);
        final double first = cross(xs[b], ys[b], xs[c], ys[c], x, y) * sign;
        final double second = cross(xs[c], ys[c], xs[a], ys[a], x, y) * sign;
        final double third = cross(xs[a], ys[a], xs[b], ys[b], x, y) * sign;
        return first >= EDGE_TOLERANCE && second >= EDGE_TOLERANCE && third >= EDGE_TOLERANCE;
    }

    private static double cross(
            final double ax, final double ay, final double bx, final double by, final double cx, final double cy) {
        return (bx - ax) * (cy - ay) - (cx - ax) * (by - ay);
    }

    private static int clamp(final double value, final int limit) {
        return (int) Math.max(0.0, Math.min(limit, value));
    }

    /**
     * Bounded sampling grid over one texture raster.
     *
     * <p>The stride keeps the longest edge at or below the requested bound, so a 4096-pixel texture
     * is sampled on a 512-pixel grid instead of sixteen million samples.</p>
     */
    static final class Raster {

        private final int width;
        private final int height;
        private final double scale;
        private final int sourceWidth;
        private final int sourceHeight;
        private final int[] argb;

        private Raster(
                final int width,
                final int height,
                final double scale,
                final int sourceWidth,
                final int sourceHeight,
                final int[] argb) {
            this.width = width;
            this.height = height;
            this.scale = scale;
            this.sourceWidth = sourceWidth;
            this.sourceHeight = sourceHeight;
            this.argb = argb;
        }

        /**
         * Builds the sampling grid of one texture.
         *
         * @param texture the texture to sample
         * @param maxRasterEdge longest accepted edge; a smaller value samples coarser
         * @return the grid
         */
        static Raster of(final TexturePixels texture, final int maxRasterEdge) {
            final int longest = Math.max(texture.width(), texture.height());
            final double scale = longest <= maxRasterEdge ? 1.0 : maxRasterEdge / (double) longest;
            final int width = Math.max(1, (int) Math.round(texture.width() * scale));
            final int height = Math.max(1, (int) Math.round(texture.height() * scale));
            return new Raster(width, height, scale, texture.width(), texture.height(), texture.argb());
        }

        /** @return the sampled width in pixels */
        int width() {
            return width;
        }

        /** @return the sampled height in pixels */
        int height() {
            return height;
        }

        /** @return the ratio between sampled and source pixels */
        double scale() {
            return scale;
        }

        /**
         * Reports whether the source pixel behind one sampled cell is opaque.
         *
         * <p>The cell maps back to its source pixel through the stride, so a coarse grid still reads
         * the same silhouette instead of the top-left corner of the texture.</p>
         *
         * @param index the row-major sampled cell index
         * @param alphaThreshold smallest alpha value that counts as opaque
         * @return {@code true} when the source pixel alpha exceeds the threshold
         */
        boolean opaque(final int index, final int alphaThreshold) {
            final int cellX = index % width;
            final int cellY = index / width;
            final int sourceX = Math.min(sourceWidth - 1, (int) (cellX / scale));
            final int sourceY = Math.min(sourceHeight - 1, (int) (cellY / scale));
            final int source = sourceY * sourceWidth + sourceX;
            if (source < 0 || source >= argb.length) {
                return false;
            }
            return ((argb[source] >>> ALPHA_SHIFT) & CHANNEL_MASK) > alphaThreshold;
        }
    }
}

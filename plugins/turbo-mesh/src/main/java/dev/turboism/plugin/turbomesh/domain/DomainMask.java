package dev.turboism.plugin.turbomesh.domain;

import dev.turboism.sdk.cubism.model.Point2;
import java.util.List;

/**
 * Boolean occupancy raster over a UV rectangle. Square cells cover the mesh's UV bounding box
 * (plus a small pad); {@code solid} marks cells covered by the shape. When a texture-driven
 * source lands, the same structure carries an alpha threshold mask plus optional luminance/edge
 * channels, so region logic never changes between silhouette and texture modes.
 *
 * <p>A two-pass chamfer distance transform ({@link #distanceToEmptyUv}) gives every cell its
 * distance to the nearest empty cell in UV units — the protection-margin ruler.</p>
 */
public final class DomainMask {

    public static final double PAD_UV = 0.0015;

    private final double u0;
    private final double v0;
    private final double cellUv;
    private final int width;
    private final int height;
    private final boolean[] solid;
    private float[] distanceCells;
    private final float[] luminance;
    private final float[] edge;
    private final int[] rgb;

    private DomainMask(
            final double u0,
            final double v0,
            final double cellUv,
            final int width,
            final int height,
            final boolean[] solid,
            final float[] luminance,
            final float[] edge,
            final int[] rgb) {
        this.u0 = u0;
        this.v0 = v0;
        this.cellUv = cellUv;
        this.width = width;
        this.height = height;
        this.solid = solid;
        this.luminance = luminance;
        this.edge = edge;
        this.rgb = rgb;
    }

    /**
     * Rasterizes the mesh's own UV triangles into a mask — the geometry-only stand-in for the
     * texture alpha footprint. {@code maxDim} caps the larger grid dimension.
     */
    public static DomainMask rasterize(final List<Point2> uvs, final List<Integer> triangleIndices, final int maxDim) {
        if (uvs.isEmpty()) {
            throw new IllegalArgumentException("cannot rasterize an empty uv set");
        }
        double minU = Double.MAX_VALUE, maxU = -Double.MAX_VALUE;
        double minV = Double.MAX_VALUE, maxV = -Double.MAX_VALUE;
        for (final Point2 p : uvs) {
            minU = Math.min(minU, p.x());
            maxU = Math.max(maxU, p.x());
            minV = Math.min(minV, p.y());
            maxV = Math.max(maxV, p.y());
        }
        minU -= PAD_UV;
        minV -= PAD_UV;
        maxU += PAD_UV;
        maxV += PAD_UV;
        final double cell = Math.max(maxU - minU, maxV - minV) / maxDim;
        if (!(cell > 0.0)) {
            throw new IllegalArgumentException("uv bounds are degenerate");
        }
        final int width = Math.max(2, (int) Math.ceil((maxU - minU) / cell));
        final int height = Math.max(2, (int) Math.ceil((maxV - minV) / cell));
        final boolean[] solid = new boolean[width * height];
        for (int t = 0; t + 2 < triangleIndices.size(); t += 3) {
            final Point2 a = uvs.get(triangleIndices.get(t));
            final Point2 b = uvs.get(triangleIndices.get(t + 1));
            final Point2 c = uvs.get(triangleIndices.get(t + 2));
            rasterizeTriangle(solid, width, height, minU, minV, cell, a, b, c);
        }
        return new DomainMask(minU, minV, cell, width, height, solid, new float[0], new float[0], new int[0]);
    }

    /**
     * Builds a mask from texture pixels: {@code argb} is a full-width texture row-major raster
     * and the mask covers {@code [u0,u1] x [v0,v1]} of it at the given cell size. Cells whose
     * center alpha is at least {@code threshold} are solid; luminance and edge (gradient
     * magnitude) channels are filled for line-art segmentation.
     */
    public static DomainMask fromPixels(
            final int[] argb,
            final int imageWidth,
            final int imageHeight,
            final double u0,
            final double v0,
            final double u1,
            final double v1,
            final int maxDim,
            final int alphaThreshold) {
        return fromPixels(argb, imageWidth, imageHeight, null, u0, v0, u1, v1, maxDim, alphaThreshold);
    }

    /**
     * Same as {@link #fromPixels(int[], int, int, double, double, double, double, int, int)}
     * but samples through {@code uvToPixel}, the 2x3 row-major affine that maps normalized
     * mesh uv to raster coordinates (the host's cached image can be padded or cropped
     * relative to naive {@code u * imageWidth}). A {@code null} affine is the identity.
     */
    public static DomainMask fromPixels(
            final int[] argb,
            final int imageWidth,
            final int imageHeight,
            final double[] uvToPixel,
            final double u0,
            final double v0,
            final double u1,
            final double v1,
            final int maxDim,
            final int alphaThreshold) {
        if (argb == null || argb.length < (long) imageWidth * imageHeight) {
            throw new IllegalArgumentException("pixel raster is smaller than the image");
        }
        if (!(u1 > u0) || !(v1 > v0)) {
            throw new IllegalArgumentException("uv rect is degenerate");
        }
        if (uvToPixel != null && uvToPixel.length != 6) {
            throw new IllegalArgumentException("uv-to-pixel affine must have 6 elements");
        }
        final double cell = Math.max(u1 - u0, v1 - v0) / maxDim;
        final int width = Math.max(2, (int) Math.ceil((u1 - u0) / cell));
        final int height = Math.max(2, (int) Math.ceil((v1 - v0) / cell));
        final boolean[] solid = new boolean[width * height];
        final float[] luminance = new float[width * height];
        final int[] rgb = new int[width * height];
        final double[] p = new double[2];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                final double u = u0 + (x + 0.5) * cell;
                final double v = v0 + (y + 0.5) * cell;
                mapUv(uvToPixel, imageWidth, imageHeight, u, v, p);
                final int px = clampPixel((int) p[0], imageWidth);
                final int py = clampPixel((int) p[1], imageHeight);
                final int argbPx = argb[py * imageWidth + px];
                solid[y * width + x] = (argbPx >>> 24) >= alphaThreshold;
                luminance[y * width + x] = (0.2126f * ((argbPx >>> 16) & 0xFF)
                                + 0.7152f * ((argbPx >>> 8) & 0xFF)
                                + 0.0722f * (argbPx & 0xFF))
                        / 255f;
                rgb[y * width + x] = argbPx & 0xFFFFFF;
            }
        }
        final float[] edge = new float[width * height];
        for (int y = 1; y + 1 < height; y++) {
            for (int x = 1; x + 1 < width; x++) {
                final float gx = luminance[y * width + x + 1] - luminance[y * width + x - 1];
                final float gy = luminance[(y + 1) * width + x] - luminance[(y - 1) * width + x];
                edge[y * width + x] = (float) Math.sqrt(gx * gx + gy * gy);
            }
        }
        // Morphological closing (bridges hairline gaps/alpha feathering) + tiny island suppression
        final boolean[] cleanedSolid = closeAndFilter(solid, width, height, 1, 12);
        return new DomainMask(u0, v0, cell, width, height, cleanedSolid, luminance, edge, rgb);
    }

    /**
     * Returns a copy with every cell crossed by {@code ring} (plus its 8-neighbours) marked
     * solid. Boundary constraints live on the source outline even when the alpha silhouette
     * cuts inside it — this keeps their covering triangles alive through domain filtering.
     */
    public DomainMask unionRing(final List<Point2> ring) {
        final boolean[] widened = solid.clone();
        final float[] lum = luminance.length == 0 ? luminance : luminance.clone();
        final float[] edg = edge.length == 0 ? edge : edge.clone();
        final int[] color = rgb.length == 0 ? rgb : rgb.clone();
        final int n = ring.size();
        for (int i = 0; i < n; i++) {
            final Point2 a = ring.get(i);
            final Point2 b = ring.get((i + 1) % n);
            final double dx = b.x() - a.x();
            final double dy = b.y() - a.y();
            final double len = Math.hypot(dx, dy);
            final int steps = Math.max(1, (int) Math.ceil(len / (cellUv * 0.5)));
            for (int k = 0; k <= steps; k++) {
                final double t = (double) k / steps;
                markSolid(widened, cellAtUv(a.x() + dx * t, a.y() + dy * t));
            }
        }
        return new DomainMask(u0, v0, cellUv, width, height, widened, lum, edg, color);
    }

    private void markSolid(final boolean[] cells, final int cell) {
        if (cell < 0) {
            return;
        }
        final int x = cell % width;
        final int y = cell / width;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                final int nx = x + dx;
                final int ny = y + dy;
                if (nx >= 0 && nx < width && ny >= 0 && ny < height) {
                    cells[ny * width + nx] = true;
                }
            }
        }
    }

    private static void mapUv(
            final double[] affine,
            final int imageWidth,
            final int imageHeight,
            final double u,
            final double v,
            final double[] out) {
        if (affine == null) {
            out[0] = u * imageWidth;
            out[1] = v * imageHeight;
            return;
        }
        out[0] = affine[0] * u + affine[1] * v + affine[2];
        out[1] = affine[3] * u + affine[4] * v + affine[5];
    }

    private static int clampPixel(final int value, final int bound) {
        return Math.max(0, Math.min(bound - 1, value));
    }

    private static void rasterizeTriangle(
            final boolean[] solid,
            final int width,
            final int height,
            final double u0,
            final double v0,
            final double cell,
            final Point2 a,
            final Point2 b,
            final Point2 c) {
        final double minU = Math.min(a.x(), Math.min(b.x(), c.x()));
        final double maxU = Math.max(a.x(), Math.max(b.x(), c.x()));
        final double minV = Math.min(a.y(), Math.min(b.y(), c.y()));
        final double maxV = Math.max(a.y(), Math.max(b.y(), c.y()));
        final int x0 = Math.max(0, (int) ((minU - u0) / cell) - 1);
        final int x1 = Math.min(width - 1, (int) ((maxU - u0) / cell) + 1);
        final int y0 = Math.max(0, (int) ((minV - v0) / cell) - 1);
        final int y1 = Math.min(height - 1, (int) ((maxV - v0) / cell) + 1);
        final double d = (b.y() - c.y()) * (a.x() - c.x()) + (c.x() - b.x()) * (a.y() - c.y());
        if (Math.abs(d) < 1.0e-15) {
            return;
        }
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                final double u = u0 + (x + 0.5) * cell;
                final double v = v0 + (y + 0.5) * cell;
                final double w0 = ((b.y() - c.y()) * (u - c.x()) + (c.x() - b.x()) * (v - c.y())) / d;
                final double w1 = ((c.y() - a.y()) * (u - c.x()) + (a.x() - c.x()) * (v - c.y())) / d;
                final double w2 = 1.0 - w0 - w1;
                if (w0 >= 0 && w1 >= 0 && w2 >= 0) {
                    solid[y * width + x] = true;
                }
            }
        }
    }

    /** Cell-edge size in UV units; cells are square. */
    public double cellUv() {
        return cellUv;
    }

    /** Raster width in cells. */
    public int width() {
        return width;
    }

    /** Raster height in cells. */
    public int height() {
        return height;
    }

    /** Cell index or {@code -1} outside the raster. */
    public int cellAtUv(final double u, final double v) {
        // floor, not truncation: UVs just below the raster origin must not fold into cell 0
        // or an out-of-shape point reads as solid.
        final int x = (int) Math.floor((u - u0) / cellUv);
        final int y = (int) Math.floor((v - v0) / cellUv);
        if (x < 0 || x >= width || y < 0 || y >= height) {
            return -1;
        }
        return y * width + x;
    }

    /** UV of a cell's center. */
    public double[] cellCenterUv(final int cell) {
        final int x = cell % width;
        final int y = cell / width;
        return new double[] {u0 + (x + 0.5) * cellUv, v0 + (y + 0.5) * cellUv};
    }

    /** Whether the cell index is inside the raster and covered by the domain. */
    public boolean solidCell(final int cell) {
        return cell >= 0 && solid[cell];
    }

    /** Occupancy at a UV position (nearest cell). Out-of-raster counts as empty. */
    public boolean solidAtUv(final double u, final double v) {
        return solidCell(cellAtUv(u, v));
    }

    /** Optional luminance channel, present only for pixel-sourced masks. */
    public float luminanceAtUv(final double u, final double v) {
        final int cell = cellAtUv(u, v);
        return cell >= 0 && luminance.length > 0 ? luminance[cell] : 0f;
    }

    /** Optional gradient-magnitude channel, present only for pixel-sourced masks. */
    public float edgeAtUv(final double u, final double v) {
        final int cell = cellAtUv(u, v);
        return cell >= 0 && edge.length > 0 ? edge[cell] : 0f;
    }

    /** Whether texture channels (luminance/edge/rgb) were captured for this mask. */
    public boolean hasTextureChannels() {
        return luminance.length > 0;
    }

    /** Per-cell luminance in [0,1], or {@code 0} outside the raster / absent channel. */
    float luminanceAt(final int cell) {
        return cell >= 0 && cell < solid.length && luminance.length > 0 ? luminance[cell] : 0f;
    }

    /** Per-cell RGB (alpha stripped), or {@code 0} outside the raster / absent channel. */
    int rgbAt(final int cell) {
        return cell >= 0 && cell < solid.length && rgb.length > 0 ? rgb[cell] : 0;
    }

    /** Public read for feature analysis: per-cell luminance in [0,1]; {@code 0} when absent. */
    public float luminanceAtCell(final int cell) {
        return luminanceAt(cell);
    }

    /** Public read for feature analysis: per-cell RGB (alpha stripped); {@code 0} when absent. */
    public int rgbAtCell(final int cell) {
        return rgbAt(cell);
    }

    /** Public read for feature analysis: per-cell gradient magnitude; {@code 0} when absent. */
    public float edgeAtCell(final int cell) {
        return edgeAt(cell);
    }

    /** Per-cell luminance gradient magnitude, or {@code 0} absent. */
    float edgeAt(final int cell) {
        return cell >= 0 && cell < solid.length && edge.length > 0 ? edge[cell] : 0f;
    }

    /**
     * Distance in UV units from a UV position to the nearest empty cell; {@code 0} outside/empty.
     * Computed lazily by a two-pass chamfer scan over cell units.
     */
    public double distanceToEmptyUv(final double u, final double v) {
        final int cell = cellAtUv(u, v);
        if (cell < 0 || !solid[cell]) {
            return 0;
        }
        return distanceTransform()[cell] * cellUv;
    }

    /** Cell-unit chamfer distance to the nearest empty cell for every cell. */
    public float[] distanceTransform() {
        if (distanceCells != null) {
            return distanceCells;
        }
        final float[] dist = new float[width * height];
        final float inf = Float.MAX_VALUE / 4;
        for (int i = 0; i < dist.length; i++) {
            dist[i] = solid[i] ? inf : 0f;
        }
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                final int i = y * width + x;
                if (dist[i] == 0) {
                    continue;
                }
                if (x > 0) {
                    dist[i] = Math.min(dist[i], dist[i - 1] + 1f);
                }
                if (y > 0) {
                    dist[i] = Math.min(dist[i], dist[i - width] + 1f);
                }
                if (x > 0 && y > 0) {
                    dist[i] = Math.min(dist[i], dist[i - width - 1] + 1.4142135f);
                }
                if (x + 1 < width && y > 0) {
                    dist[i] = Math.min(dist[i], dist[i - width + 1] + 1.4142135f);
                }
            }
        }
        for (int y = height - 1; y >= 0; y--) {
            for (int x = width - 1; x >= 0; x--) {
                final int i = y * width + x;
                if (dist[i] == 0) {
                    continue;
                }
                if (x + 1 < width) {
                    dist[i] = Math.min(dist[i], dist[i + 1] + 1f);
                }
                if (y + 1 < height) {
                    dist[i] = Math.min(dist[i], dist[i + width] + 1f);
                }
                if (x + 1 < width && y + 1 < height) {
                    dist[i] = Math.min(dist[i], dist[i + width + 1] + 1.4142135f);
                }
                if (x > 0 && y + 1 < height) {
                    dist[i] = Math.min(dist[i], dist[i + width - 1] + 1.4142135f);
                }
            }
        }
        distanceCells = dist;
        return dist;
    }

    private static boolean[] closeAndFilter(
            final boolean[] input, final int w, final int h, final int radius, final int minIslandCells) {
        if (radius <= 0) {
            return input;
        }
        final boolean[] dilated = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!input[y * w + x]) continue;
                for (int dy = -radius; dy <= radius; dy++) {
                    final int ny = y + dy;
                    if (ny < 0 || ny >= h) continue;
                    for (int dx = -radius; dx <= radius; dx++) {
                        final int nx = x + dx;
                        if (nx < 0 || nx >= w) continue;
                        if (dx * dx + dy * dy <= radius * radius + 1) {
                            dilated[ny * w + nx] = true;
                        }
                    }
                }
            }
        }
        final boolean[] closed = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!dilated[y * w + x]) continue;
                boolean allSolid = true;
                for (int dy = -radius; dy <= radius && allSolid; dy++) {
                    final int ny = y + dy;
                    if (ny < 0 || ny >= h) {
                        allSolid = false;
                        break;
                    }
                    for (int dx = -radius; dx <= radius; dx++) {
                        final int nx = x + dx;
                        if (nx < 0 || nx >= w || !dilated[ny * w + nx]) {
                            allSolid = false;
                            break;
                        }
                    }
                }
                closed[y * w + x] = allSolid;
            }
        }
        final boolean[] result = new boolean[w * h];
        for (int i = 0; i < result.length; i++) {
            result[i] = input[i] || closed[i];
        }
        if (minIslandCells > 0) {
            final boolean[] seen = new boolean[w * h];
            final int[] q = new int[w * h];
            final int[] comp = new int[w * h];
            for (int i = 0; i < result.length; i++) {
                if (!result[i] || seen[i]) continue;
                int head = 0, tail = 0;
                q[tail++] = i;
                seen[i] = true;
                int count = 0;
                while (head < tail) {
                    final int curr = q[head++];
                    comp[count++] = curr;
                    final int cx = curr % w;
                    final int cy = curr / w;
                    for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                        final int nx = cx + d[0], ny = cy + d[1];
                        if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue;
                        final int nidx = ny * w + nx;
                        if (result[nidx] && !seen[nidx]) {
                            seen[nidx] = true;
                            q[tail++] = nidx;
                        }
                    }
                }
                if (count < minIslandCells) {
                    for (int c = 0; c < count; c++) {
                        result[comp[c]] = false;
                    }
                }
            }
        }
        return result;
    }
}

package dev.turboism.plugin.turbomesh.domain.features;

import dev.turboism.plugin.turbomesh.domain.DomainMask;
import dev.turboism.sdk.cubism.model.Point2;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure geometry/texture-independent feature analysis over one shape mask: a chamfer distance
 * field, multi-scale boundary corner detection, a mask structure-tensor direction field, and the
 * assembled vertex-interest density field. Everything is deterministic array math — the same
 * mask and options always produce the same analysis.
 */
public final class MeshFeatureAnalyzer {

    /** One protected boundary corner: ring index, ring vertex index, position, sharpest scale. */
    public record Corner(int ring, int vertex, Point2 at, double turn, int scale) {}

    /** Complete analysis snapshot for one mask. Arrays are cell-indexed ({@code y * width + x}). */
    public record Analysis(
            DomainMask mask,
            float[] density,
            float[] direction,
            float[] coherence,
            float[] textureEdge,
            float[] cornerDistanceCells,
            List<List<Corner>> corners) {

        /** Local material width in UV units: twice the distance to the nearest empty cell. */
        public double widthAtCell(final int cell) {
            return 2.0 * mask.distanceTransform()[cell] * mask.cellUv();
        }
    }

    private MeshFeatureAnalyzer() {}

    /**
     * Runs the full analysis. {@code rings} are the closed boundary polylines (not repeating the
     * first vertex) of the shape being placed.
     */
    public static Analysis analyze(
            final DomainMask mask, final List<List<Point2>> rings, final FeatureOptions options) {
        final int w = mask.width();
        final int h = mask.height();
        final int n = w * h;
        final float[] dist = mask.distanceTransform();
        final double cellUv = mask.cellUv();

        // 1. Multi-scale corner detection per ring.
        final List<List<Corner>> corners = new ArrayList<>(rings.size());
        final boolean[] cornerCell = new boolean[n];
        for (int r = 0; r < rings.size(); r++) {
            final List<Corner> detected = detectCorners(r, rings.get(r), options);
            corners.add(detected);
            for (final Corner c : detected) {
                final int cell = mask.cellAtUv(c.at().x(), c.at().y());
                if (cell >= 0) {
                    markNeighborhood(cornerCell, cell, w, h, 1);
                }
            }
        }

        // 2. Corner proximity field: chamfer distance to any corner-marked cell.
        final float[] cornerDist = chamfer(cornerCell, w, h);

        // 3. Direction field. The mask structure tensor gives the alpha-silhouette direction;
        //    pixel-sourced masks additionally contribute a texture structure tensor over Lab
        //    gradients, which wins wherever its coherence is higher (artwork edges beat the
        //    occupancy silhouette at describing local artwork direction).
        final int n2 = w * h;
        final float[] direction = new float[n2];
        final float[] coherence = new float[n2];
        structureTensor(mask, direction, coherence);
        final float[] textureEdge = new float[n2];
        if (mask.hasTextureChannels() && options.weightTexture() > 0.0) {
            textureTensor(mask, direction, coherence, textureEdge, options);
        }

        // 4. Density assembly.
        final float[] density = new float[n2];
        final double bandCells = Math.max(1.0, options.boundaryBandUv() / cellUv);
        final double cornerBandCells = Math.max(1.0, options.cornerBandUv() / cellUv);
        final double narrowCells = options.narrowWidthUv() / cellUv;
        for (int c = 0; c < n2; c++) {
            if (!mask.solidCell(c)) {
                continue;
            }
            final double boundary = Math.exp(-dist[c] / bandCells);
            final double corner = Math.exp(-cornerDist[c] / cornerBandCells);
            final double widthUv = 2.0 * dist[c] * cellUv;
            final double narrow = Math.max(0.0, Math.min(1.0, 1.0 - widthUv / options.narrowWidthUv()));
            double d = options.baseDensity()
                    + options.weightBoundary() * boundary
                    + options.weightCorner() * corner
                    + options.weightNarrow() * narrow
                    + options.weightTexture() * textureEdge[c];
            density[c] = (float) Math.max(0.0, Math.min(1.0, d));
        }
        return new Analysis(mask, density, direction, coherence, textureEdge, cornerDist, corners);
    }

    /**
     * Multi-scale corner detection: a vertex is a corner when the chordal turn over a window of
     * {@code scale} vertices on both sides exceeds the threshold; the finest qualifying scale
     * wins, and neighbors within that scale are suppressed as one corner cluster.
     */
    static List<Corner> detectCorners(final int ring, final List<Point2> poly, final FeatureOptions options) {
        final int n = poly.size();
        final List<Corner> out = new ArrayList<>();
        if (n < 5) {
            return out;
        }
        final Corner[] best = new Corner[n];
        for (int scale = 1; scale <= options.cornerScales(); scale++) {
            if (2 * scale + 1 > n) {
                break;
            }
            for (int i = 0; i < n; i++) {
                if (best[i] != null) {
                    continue; // already claimed by a finer scale
                }
                final Point2 prev = poly.get((i - scale + n) % n);
                final Point2 cur = poly.get(i);
                final Point2 next = poly.get((i + scale) % n);
                final double ux = cur.x() - prev.x();
                final double uy = cur.y() - prev.y();
                final double vx = next.x() - cur.x();
                final double vy = next.y() - cur.y();
                final double lu = Math.hypot(ux, uy);
                final double lv = Math.hypot(vx, vy);
                if (lu < 1.0e-9 || lv < 1.0e-9) {
                    continue;
                }
                final double turn = Math.acos(Math.max(-1.0, Math.min(1.0, (ux * vx + uy * vy) / (lu * lv))));
                if (turn < options.cornerTurnRadians()) {
                    continue;
                }
                best[i] = new Corner(ring, i, cur, turn, scale);
            }
        }
        // Non-maximum suppression: within `scale` vertices of an accepted corner, drop weaker ones.
        final boolean[] dropped = new boolean[n];
        for (int i = 0; i < n; i++) {
            final Corner c = best[i];
            if (c == null || dropped[i]) {
                continue;
            }
            for (int k = 1; k <= c.scale() && k < n; k++) {
                dropped[(i + k) % n] = true;
                dropped[(i - k + n) % n] = true;
            }
            out.add(c);
        }
        out.sort((a, b) -> Integer.compare(a.vertex(), b.vertex()));
        return out;
    }

    /** Gradient-structure tensor over the solid field; direction = feature-line orientation. */
    private static void structureTensor(final DomainMask mask, final float[] direction, final float[] coherence) {
        final int w = mask.width();
        final int h = mask.height();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                final int c = y * w + x;
                if (!mask.solidCell(c)) {
                    continue;
                }
                // Gradients of the solid indicator, central difference with clamped neighbors.
                final float gx = solid(mask, x + 1, y) - solid(mask, x - 1, y);
                final float gy = solid(mask, x, y + 1) - solid(mask, x, y - 1);
                // Average the tensor over a 3x3 window for stability.
                double a = 0.0;
                double b = 0.0;
                double d = 0.0;
                int count = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        final int nx = Math.max(0, Math.min(w - 1, x + dx));
                        final int ny = Math.max(0, Math.min(h - 1, y + dy));
                        final int nc = ny * w + nx;
                        if (!mask.solidCell(nc)) {
                            continue;
                        }
                        final float sx = solid(mask, nx + 1, ny) - solid(mask, nx - 1, ny);
                        final float sy = solid(mask, nx, ny + 1) - solid(mask, nx, ny - 1);
                        a += (double) sx * sx;
                        b += (double) sx * sy;
                        d += (double) sy * sy;
                        count++;
                    }
                }
                if (count == 0) {
                    continue;
                }
                a /= count;
                b /= count;
                d /= count;
                final double trace = a + d;
                if (trace < 1.0e-12) {
                    continue; // flat interior: no direction signal
                }
                final double disc = Math.sqrt(Math.max(0.0, (a - d) * (a - d) + 4.0 * b * b));
                final double lambda1 = (trace + disc) * 0.5;
                final double lambda2 = Math.max(0.0, (trace - disc) * 0.5);
                final float coh = (float) ((lambda1 - lambda2) / (lambda1 + lambda2));
                coherence[c] = coh;
                if (b == 0.0 && a >= d) {
                    // Dominant gradient along x: feature lines run vertically.
                    direction[c] = (float) (Math.PI / 2.0);
                } else if (b == 0.0) {
                    direction[c] = 0.0f;
                } else {
                    // Dominant gradient eigenvector angle, rotated 90 degrees onto the line.
                    final double theta = 0.5 * Math.atan2(2.0 * b, a - d) + Math.PI / 2.0;
                    direction[c] = (float) theta;
                }
            }
        }
    }

    private static float solid(final DomainMask mask, final int x, final int y) {
        if (x < 0 || y < 0 || x >= mask.width() || y >= mask.height()) {
            return 0f;
        }
        return mask.solidCell(y * mask.width() + x) ? 1f : 0f;
    }

    /**
     * Texture structure tensor over perceptual Lab gradients plus a multi-scale luminance edge
     * score. Tensor direction overwrites the mask tensor where the texture coherence is higher;
     * the edge score feeds the density field as a soft signal only — artwork edges never become
     * hard constraints on their own.
     */
    private static void textureTensor(
            final DomainMask mask,
            final float[] direction,
            final float[] coherence,
            final float[] textureEdge,
            final FeatureOptions options) {
        final int w = mask.width();
        final int h = mask.height();
        final int n = w * h;
        // Perceptual Lab per solid cell (D65); gradients of L dominate, a/b weight colour edges.
        final float[] lum = new float[n];
        final float[] aa = new float[n];
        final float[] bb = new float[n];
        boolean anyTexture = false;
        for (int c = 0; c < n; c++) {
            if (!mask.solidCell(c)) {
                continue;
            }
            final int rgb = mask.rgbAtCell(c);
            final float[] lab =
                    srgbToLab(((rgb >>> 16) & 0xFF) / 255f, ((rgb >>> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f);
            lum[c] = lab[0];
            aa[c] = lab[1];
            bb[c] = lab[2];
            anyTexture = true;
        }
        if (!anyTexture) {
            return;
        }
        // Lab L spans [0, 100]; textureContrast is normalized so the default 0.25 saturates the
        // edge score near a ~10 L-unit-per-cell gradient (crisp artwork edge) and leaves gentle
        // shading gradients well below it.
        final float contrastRef = (float) (options.textureContrast() * 40.0);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                final int c = y * w + x;
                if (!mask.solidCell(c)) {
                    continue;
                }
                // Two-scale Lab gradient: plain Sobel plus a 2-step Sobel (coarser structures).
                float gx = 0f;
                float gy = 0f;
                float g2x = 0f;
                float g2y = 0f;
                for (final float[] channel : new float[][] {lum, aa, bb}) {
                    gx += labGrad(channel, w, h, x, y, 1, 0);
                    gy += labGrad(channel, w, h, x, y, 0, 1);
                    g2x += labGrad(channel, w, h, x, y, 2, 0);
                    g2y += labGrad(channel, w, h, x, y, 0, 2);
                }
                final float mag = (float) Math.hypot(gx, gy);
                final float mag2 = (float) Math.hypot(g2x, g2y) * 0.5f;
                textureEdge[c] = (float) Math.max(0.0, Math.min(1.0, Math.max(mag, mag2) / contrastRef));
                // Windowed tensor (5x5) over the combined gradient.
                double ta = 0.0;
                double tb = 0.0;
                double td = 0.0;
                int count = 0;
                for (int dy = -2; dy <= 2; dy++) {
                    for (int dx = -2; dx <= 2; dx++) {
                        final int nx = Math.max(0, Math.min(w - 1, x + dx));
                        final int ny = Math.max(0, Math.min(h - 1, y + dy));
                        final int nc = ny * w + nx;
                        if (!mask.solidCell(nc)) {
                            continue;
                        }
                        float sx = labGrad(lum, w, h, nx, ny, 1, 0);
                        float sy = labGrad(lum, w, h, nx, ny, 0, 1);
                        ta += (double) sx * sx;
                        tb += (double) sx * sy;
                        td += (double) sy * sy;
                        count++;
                    }
                }
                if (count == 0) {
                    continue;
                }
                ta /= count;
                tb /= count;
                td /= count;
                final double trace = ta + td;
                if (trace < 1.0e-12) {
                    continue;
                }
                final double disc = Math.sqrt(Math.max(0.0, (ta - td) * (ta - td) + 4.0 * tb * tb));
                final double lambda1 = (trace + disc) * 0.5;
                final double lambda2 = Math.max(0.0, (trace - disc) * 0.5);
                final float coh = (float) ((lambda1 - lambda2) / (lambda1 + lambda2));
                if (coh > coherence[c]) {
                    coherence[c] = coh;
                    direction[c] = tb == 0.0 && ta >= td
                            ? (float) (Math.PI / 2.0)
                            : (float) (0.5 * Math.atan2(2.0 * tb, ta - td) + Math.PI / 2.0);
                }
            }
        }
    }

    /** Central-difference Lab-channel gradient times 0.5 at a given step. */
    private static float labGrad(
            final float[] channel, final int w, final int h, final int x, final int y, final int sx, final int sy) {
        final int x1 = Math.max(0, Math.min(w - 1, x + sx));
        final int y1 = Math.max(0, Math.min(h - 1, y + sy));
        final int x0 = Math.max(0, Math.min(w - 1, x - sx));
        final int y0 = Math.max(0, Math.min(h - 1, y - sy));
        return (channel[y1 * w + x1] - channel[y0 * w + x0]) * 0.5f;
    }

    /** sRGB (linearized) to CIELAB, D65 reference white; L in [0, 100], a/b in roughly [-128, 128]. */
    public static float[] srgbToLab(final float r, final float g, final float b) {
        final double lr = linearize(r);
        final double lg = linearize(g);
        final double lb = linearize(b);
        double x = (0.4124564 * lr + 0.3575761 * lg + 0.1804375 * lb) / 0.95047;
        double y = 0.2126729 * lr + 0.7151522 * lg + 0.0721750 * lb;
        double z = (0.0193339 * lr + 0.1191920 * lg + 0.9503041 * lb) / 1.08883;
        final double fx = fLab(x);
        final double fy = fLab(y);
        final double fz = fLab(z);
        return new float[] {(float) (116.0 * fy - 16.0), (float) (500.0 * (fx - fy)), (float) (200.0 * (fy - fz))};
    }

    private static double linearize(final double c) {
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static double fLab(final double t) {
        return t > 0.008856 ? Math.cbrt(t) : (903.3 * t + 16.0) / 116.0;
    }

    private static void markNeighborhood(
            final boolean[] cells, final int cell, final int w, final int h, final int radius) {
        final int cx = cell % w;
        final int cy = cell / w;
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                final int x = cx + dx;
                final int y = cy + dy;
                if (x >= 0 && y >= 0 && x < w && y < h) {
                    cells[y * w + x] = true;
                }
            }
        }
    }

    /** Two-pass chamfer distance in cell units to the nearest marked cell. */
    static float[] chamfer(final boolean[] marked, final int w, final int h) {
        final float inf = Float.MAX_VALUE / 4;
        final float[] dist = new float[w * h];
        for (int i = 0; i < dist.length; i++) {
            dist[i] = marked[i] ? 0f : inf;
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                relax(dist, w, h, x, y, -1, 0, 1f);
                relax(dist, w, h, x, y, 0, -1, 1f);
                relax(dist, w, h, x, y, -1, -1, 1.4142135f);
                relax(dist, w, h, x, y, 1, -1, 1.4142135f);
            }
        }
        for (int y = h - 1; y >= 0; y--) {
            for (int x = w - 1; x >= 0; x--) {
                relax(dist, w, h, x, y, 1, 0, 1f);
                relax(dist, w, h, x, y, 0, 1, 1f);
                relax(dist, w, h, x, y, 1, 1, 1.4142135f);
                relax(dist, w, h, x, y, -1, 1, 1.4142135f);
            }
        }
        return dist;
    }

    private static void relax(
            final float[] dist,
            final int w,
            final int h,
            final int x,
            final int y,
            final int dx,
            final int dy,
            final float cost) {
        final int nx = x + dx;
        final int ny = y + dy;
        if (nx < 0 || ny < 0 || nx >= w || ny >= h) {
            return;
        }
        final int i = y * w + x;
        final int j = ny * w + nx;
        if (dist[j] + cost < dist[i]) {
            dist[i] = dist[j] + cost;
        }
    }
}

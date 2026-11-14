package dev.turboism.plugin.turbomesh.domain;

import dev.turboism.sdk.cubism.model.Point2;
import java.util.ArrayList;
import java.util.List;

/**
 * Autonomous interior placement inside one {@link RegionSegmenter.Region}. Candidate sites come
 * from a jittered square grid laid out at a fixed UV spacing — without texture pixel data there
 * is no signal that justifies denser fill, so the interior stays deliberately sparse; every
 * candidate must keep {@code interiorMarginUv} of clearance to its own region boundary,
 * measured by a region-local chamfer distance transform. Splitmix64 keyed by
 * {@code (seed, regionId)} keeps output deterministic per region.
 */
public final class InteriorSampler {

    private InteriorSampler() {}

    /**
     * Places interior points inside {@code region} on a {@code spacingUv} jittered grid.
     *
     * @return UV-space points; boundary/constraint sampling happens elsewhere
     */
    public static List<Point2> sample(
            final DomainMask mask,
            final RegionSegmenter.Region region,
            final double spacingUv,
            final PlacementSettings settings) {
        if (region.cellCount() == 0) {
            return List.of();
        }
        final int w = mask.width();
        // Region-local distance transform against the region boundary.
        final boolean[] member = new boolean[w * mask.height()];
        for (final int cell : region.cells()) {
            member[cell] = true;
        }
        final float[] dist = chamfer(member, w, mask.height());

        final double cellUv = mask.cellUv();
        final double spacing = Math.max(settings.minSpacingUv() * 1.5, spacingUv);

        final double[] origin = mask.cellCenterUv(0);
        final double u0 = origin[0] - 0.5 * cellUv;
        final double v0 = origin[1] - 0.5 * cellUv;
        final double marginUv = settings.interiorMarginUv();
        final int stepCells = Math.max(1, (int) Math.round(spacing / cellUv));
        final List<Point2> out = new ArrayList<>();
        long rng = mix64(settings.seed() ^ (0x9E3779B97F4A7C15L * (region.id() + 1)));

        int rowIndex = 0;
        for (int y = region.minY(); y <= region.maxY(); y += stepCells, rowIndex++) {
            final double rowOffset = (rowIndex & 1) == 0 ? 0.0 : spacing * 0.5; // hex offset
            for (int x = region.minX(); x <= region.maxX(); x += stepCells) {
                final int cell = y * w + x;
                if (!member[cell]) {
                    continue;
                }
                final double cu = u0 + x * cellUv + rowOffset;
                final double cv = v0 + y * cellUv;
                // jitter within ±0.3 spacing
                rng = mix64(rng);
                final double ju = (toUnit(rng) - 0.5) * 0.6 * spacing;
                rng = mix64(rng);
                final double jv = (toUnit(rng) - 0.5) * 0.6 * spacing;
                final double u = cu + ju;
                final double v = cv + jv;
                final int at = mask.cellAtUv(u, v);
                if (at < 0 || !member[at]) {
                    continue;
                }
                if (dist[at] * cellUv < marginUv) {
                    continue;
                }
                out.add(new Point2((float) u, (float) v));
            }
        }
        return out;
    }

    /** Two-pass chamfer distance in cell units to the nearest non-member cell. */
    private static float[] chamfer(final boolean[] member, final int w, final int h) {
        final float inf = Float.MAX_VALUE / 4;
        final float[] dist = new float[w * h];
        for (int i = 0; i < dist.length; i++) {
            dist[i] = member[i] ? inf : 0f;
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                final int i = y * w + x;
                if (dist[i] == 0) {
                    continue;
                }
                if (x > 0) dist[i] = Math.min(dist[i], dist[i - 1] + 1f);
                if (y > 0) dist[i] = Math.min(dist[i], dist[i - w] + 1f);
                if (x > 0 && y > 0) dist[i] = Math.min(dist[i], dist[i - w - 1] + 1.4142135f);
                if (x + 1 < w && y > 0) dist[i] = Math.min(dist[i], dist[i - w + 1] + 1.4142135f);
            }
        }
        for (int y = h - 1; y >= 0; y--) {
            for (int x = w - 1; x >= 0; x--) {
                final int i = y * w + x;
                if (dist[i] == 0) {
                    continue;
                }
                if (x + 1 < w) dist[i] = Math.min(dist[i], dist[i + 1] + 1f);
                if (y + 1 < h) dist[i] = Math.min(dist[i], dist[i + w] + 1f);
                if (x + 1 < w && y + 1 < h) dist[i] = Math.min(dist[i], dist[i + w + 1] + 1.4142135f);
                if (x > 0 && y + 1 < h) dist[i] = Math.min(dist[i], dist[i + w - 1] + 1.4142135f);
            }
        }
        return dist;
    }

    /** Splitmix64 finalizer: deterministic hash used to derive per-region and per-run RNG seeds. */
    public static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /**
     * Maps the high 53 bits of {@code bits} onto {@code [0, 1)}.
     *
     * <p>53 bits is the mantissa width of a {@code double}, so every distinct input maps to a
     * distinct unit value.</p>
     *
     * @param bits random bits, typically from {@link #mix64}
     * @return a value in {@code [0, 1)}
     */
    public static double toUnit(final long bits) {
        return (bits >>> 11) * (1.0 / (1L << 53));
    }
}

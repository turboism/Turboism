package dev.turboism.adapter.cubism.textureatlas.cache;

import java.util.List;

/**
 * Scheduling seam for rebuilding independent atlas pages.
 *
 * <p>Each {@code CTextureAtlas} is one page whose CPU rasterization writes only its own pixel
 * buffer, while {@code CImageResource}, {@code CCachedImageManager}, and progress notifications run
 * on the caller owner thread. Both {@link Policy#SERIAL} and bounded {@link Policy#PARALLEL_UNIFORM}
 * policies are available through {@link #forPolicy(Policy)}.</p>
 */
public interface AtlasPageScheduler {

    /** Runs each page's rebuild task; serial implementations run them in list order. */
    void runPages(List<? extends Runnable> pageRebuilds);

    /** Available scheduling strategies for multi-page atlas rebuilds. */
    enum Policy {
        /** Run page rebuilds one at a time on the caller's thread — host-identical order. */
        SERIAL,
        /** Runs CPU page rasterization across a bounded daemon worker pool. */
        PARALLEL_UNIFORM
    }

    /** The serial scheduler — identical ordering and failure semantics to the host loop. */
    static AtlasPageScheduler serial() {
        return new SerialAtlasPageScheduler();
    }

    /** A bounded daemon worker pool scheduler for independent multi-page CPU rebuilds. */
    static AtlasPageScheduler parallel() {
        return new ParallelAtlasPageScheduler();
    }

    /** Resolves the scheduler implementation matching {@code policy}. */
    static AtlasPageScheduler forPolicy(final Policy policy) {
        return policy == Policy.PARALLEL_UNIFORM ? parallel() : serial();
    }

    /**
     * Admission heuristic for a future parallel policy: true when the page's tile areas are
     * uniform enough that no single tile dominates the wall-clock cost. Uniformity here means
     * the largest tile covers at most half the mean area scale — i.e. parallelism can actually
     * split the work instead of leaving one straggler.
     *
     * @param tileAreasPx per-tile pixel areas (post-transform footprint on the page)
     * @param tolerance max allowed ratio of largest tile area to the mean (e.g. 4.0)
     */
    static boolean uniformTileAreas(final long[] tileAreasPx, final double tolerance) {
        if (tileAreasPx == null || tileAreasPx.length == 0) return false;
        long sum = 0;
        long max = 0;
        for (final long area : tileAreasPx) {
            sum += area;
            if (area > max) max = area;
        }
        final double mean = (double) sum / tileAreasPx.length;
        return mean > 0 && max <= mean * tolerance;
    }
}

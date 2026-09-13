package dev.turboism.bootstrap.atlascache;

import java.util.List;

/**
 * Scheduling seam for rebuilding independent atlas pages.
 *
 * <p>Each {@code CTextureAtlas} is one page: its rebuild writes only its own
 * {@code cachedAtlasImage} and reads only its own {@code modelImages}, so page rebuilds are
 * data-independent. The host runs them sequentially inside the shared "Update TextureAtlas"
 * progress op ({@code TextureManagerHandler}'s per-atlas loop). This interface is the seam a
 * page-level scheduler would plug into — the loop body stays identical, only the iteration
 * strategy changes.</p>
 *
 * <p><b>Serial is the default and the only wired policy.</b> Parallel execution is reserved
 * behind {@link Policy#PARALLEL_UNIFORM} for a future, separately-reviewed patch. The known
 * constraints that gate it:</p>
 * <ul>
 *   <li>{@code UtCache} is a static pool, but every accessor is {@code synchronized} —
 *       pool state itself is safe; oversized-buffer semantics are unchanged per worker.</li>
 *   <li>{@code CModelImage.getFilteredImage()} lazily builds; a parallel scheduler must not
 *       run two builds of the same image concurrently (the host's assignment is a plain
 *       field write — last-writer-wins identical content is probably benign but unproven).</li>
 *   <li>The progress reporter {@code a.a} is invoked per image from the rebuild loop; its
 *       thread-safety is unverified — a parallel impl must serialize progress callbacks.</li>
 *   <li>Draw order inside a page is user-visible; page order across pages is not, but
 *       exception/cancellation semantics (progress cancel mid-loop) must match serial.</li>
 *   <li>Parallelism pays only when tiles are similarly sized — a page dominated by one huge
 *       tile gains nothing. {@link #uniformTileAreas} is the admission heuristic.</li>
 * </ul>
 */
public interface AtlasPageScheduler {

    /** Runs each page's rebuild task; serial implementations run them in list order. */
    void runPages(List<? extends Runnable> pageRebuilds);

    /** The wired policy; only {@link #SERIAL} is implemented today. */
    enum Policy {
        /** Run page rebuilds one at a time on the caller's thread — host-identical order. */
        SERIAL,
        /**
         * Reserved: run pages on a bounded executor when the tiles are similarly sized.
         * Never selected automatically — requires a separately reviewed host patch.
         */
        PARALLEL_UNIFORM
    }

    /** The serial scheduler — identical ordering and failure semantics to the host loop. */
    static AtlasPageScheduler serial() {
        return new SerialAtlasPageScheduler();
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

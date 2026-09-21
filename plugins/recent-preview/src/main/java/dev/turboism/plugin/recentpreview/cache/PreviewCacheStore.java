package dev.turboism.plugin.recentpreview.cache;

import dev.turboism.sdk.cubism.recentfile.RecentFileSummary;
import dev.turboism.sdk.cubism.screenshot.ScreenshotImage;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;

/** Plugin-confined preview cache keyed by the opaque recent-file id. */
public interface PreviewCacheStore {

    /**
     * Stores a preview screenshot for one recent file.
     *
     * @param file the recent file the image belongs to; its opaque id keys the cache entry
     * @param image the captured screenshot to persist as a bounded PNG
     * @return a stage completing with the write outcome; image and index write refusals are
     *     reported as {@link PreviewCacheWriteResult} values, while underlying storage
     *     failures may instead complete the stage exceptionally — there is no blanket
     *     conversion of failures into results
     * @throws NullPointerException if either argument is null
     */
    CompletionStage<PreviewCacheWriteResult> store(RecentFileSummary file, ScreenshotImage image);

    /**
     * Stores a preview only while publication is allowed.
     *
     * <p>This default implementation evaluates {@code publicationAllowed} once: when it fails,
     * the stage completes with {@link PreviewCacheWriteResult#DISABLED} without calling
     * {@link #store(RecentFileSummary, ScreenshotImage)}; otherwise it delegates. Overrides
     * may re-check the supplier at later staging points, where staged or even published
     * content can already exist and is then only cleaned up on a best-effort basis — a
     * {@code DISABLED} outcome therefore does not guarantee that nothing was ever written or
     * that a previously cached entry was restored.</p>
     *
     * @param file the recent file the image belongs to
     * @param image the captured screenshot to persist
     * @param publicationAllowed decides whether the write may proceed; may be consulted more
     *     than once and may throw or change its answer between checks
     * @return a stage completing with the write outcome, or completing exceptionally when a
     *     later gate check or a storage operation fails
     * @throws NullPointerException if any argument is null
     */
    default CompletionStage<PreviewCacheWriteResult> store(
        final RecentFileSummary file,
        final ScreenshotImage image,
        final BooleanSupplier publicationAllowed
    ) {
        if (!publicationAllowed.getAsBoolean()) {
            return CompletableFuture.completedStage(PreviewCacheWriteResult.DISABLED);
        }
        return store(file, image);
    }
}

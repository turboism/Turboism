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
     * @return a stage completing with the write outcome; failures are reported through
     *     {@link PreviewCacheWriteResult} rather than thrown
     */
    CompletionStage<PreviewCacheWriteResult> store(RecentFileSummary file, ScreenshotImage image);

    /**
     * Stores a preview only while publication is allowed.
     *
     * <p>When {@code publicationAllowed} returns false the stage completes with
     * {@link PreviewCacheWriteResult#DISABLED} and nothing is written. This default
     * implementation checks the supplier once, then delegates to
     * {@link #store(RecentFileSummary, ScreenshotImage)}; implementations may re-check it at
     * later staging points.</p>
     *
     * @param file the recent file the image belongs to
     * @param image the captured screenshot to persist
     * @param publicationAllowed decides whether the write may proceed
     * @return a stage completing with the write outcome
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

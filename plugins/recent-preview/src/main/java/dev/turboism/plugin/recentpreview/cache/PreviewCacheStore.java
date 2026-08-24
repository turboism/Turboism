package dev.turboism.plugin.recentpreview.cache;

import dev.turboism.sdk.cubism.recentfile.RecentFileSummary;
import dev.turboism.sdk.cubism.screenshot.ScreenshotImage;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;

/** Plugin-confined preview cache keyed by the opaque recent-file id. */
public interface PreviewCacheStore {

    CompletionStage<PreviewCacheWriteResult> store(RecentFileSummary file, ScreenshotImage image);

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

package dev.turboism.plugin.recentpreview.cache;

import dev.turboism.sdk.cubism.recentfile.RecentFileSummary;
import dev.turboism.sdk.cubism.screenshot.ScreenshotImage;

import java.util.concurrent.CompletionStage;

/** Plugin-confined preview cache keyed by the opaque recent-file id. */
public interface PreviewCacheStore {

    CompletionStage<PreviewCacheWriteResult> store(RecentFileSummary file, ScreenshotImage image);
}

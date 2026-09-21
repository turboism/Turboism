package dev.turboism.plugin.recentpreview.cache;

import dev.turboism.sdk.cubism.recentfile.RecentFileId;
import dev.turboism.sdk.cubism.recentfile.RecentFileSummary;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;

/**
 * Plugin preview cache surface: bounded PNG writes plus bounded PNG reads for the
 * current recent files. Implemented by {@link PreviewCacheIndex}.
 */
public interface PreviewCache extends PreviewCacheStore {

    /**
     * Loads the cached preview PNGs for the given recent files.
     *
     * <p>The read is bounded: inputs are consulted in list order but only up to the
     * implementation's bound, so the tail of a long list is never read.</p>
     *
     * @param files the recent files to look up; must not be null
     * @return a stage completing with a map from recent-file id to PNG bytes; files without a
     *     readable cached preview — and any beyond the read bound — are absent rather than
     *     failing the read. The returned map makes no iteration-order promise.
     */
    CompletionStage<Map<RecentFileId, byte[]>> loadPng(List<RecentFileSummary> files);
}

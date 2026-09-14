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
     * Loads the cached preview PNG for each given recent file.
     *
     * @param files the recent files to look up; must not be null
     * @return a stage completing with a map from recent-file id to PNG bytes in input order;
     *     files without a readable cached preview are absent from the map rather than failing
     *     the read
     */
    CompletionStage<Map<RecentFileId, byte[]>> loadPng(List<RecentFileSummary> files);
}

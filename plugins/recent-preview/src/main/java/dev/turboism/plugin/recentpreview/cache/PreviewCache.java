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

    CompletionStage<Map<RecentFileId, byte[]>> loadPng(List<RecentFileSummary> files);
}

package dev.turboism.plugin.recentpreview.cache;

/** Outcome of one plugin cache write attempt. */
public enum PreviewCacheWriteResult {
    STORED,
    IMAGE_WRITE_FAILED,
    INDEX_WRITE_FAILED,
    RECENT_FILE_UNAVAILABLE,
    DISABLED
}

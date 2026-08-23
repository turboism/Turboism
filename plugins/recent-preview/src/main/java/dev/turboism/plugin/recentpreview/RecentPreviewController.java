package dev.turboism.plugin.recentpreview;

import dev.turboism.plugin.recentpreview.cache.PreviewCache;
import dev.turboism.plugin.recentpreview.cache.PreviewCacheWriteResult;
import dev.turboism.sdk.cubism.recentfile.RecentFileId;
import dev.turboism.sdk.cubism.recentfile.RecentFileService;
import dev.turboism.sdk.cubism.recentfile.RecentFileSummary;
import dev.turboism.sdk.cubism.screenshot.ScreenshotCaptureRequest;
import dev.turboism.sdk.cubism.screenshot.ScreenshotCaptureService;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Captures preview thumbnails for recent files and serves them from an in-memory
 * copy of the disk cache. The disk cache ({@link PreviewCacheStore}) remains the
 * source of truth across sessions; the memory map exists only so the popup renderer
 * can answer synchronously on the host UI thread.
 */
public final class RecentPreviewController {

    static final int THUMBNAIL_SIZE = 150;

    private final RecentFileService recentFiles;
    private final ScreenshotCaptureService screenshots;
    private final PreviewCache cache;
    private volatile boolean enabled;
    private volatile List<RecentFileSummary> files = List.of();
    private final Map<RecentFileId, byte[]> images = new ConcurrentHashMap<>();
    private final java.util.Set<RecentFileId> inFlight = ConcurrentHashMap.newKeySet();

    /** Last last-modified value a capture was dispatched for, per id (poll-track dedupe). */
    private final Map<RecentFileId, Long> lastCapturedModified = new ConcurrentHashMap<>();
    public RecentPreviewController(
        final RecentFileService recentFiles,
        final ScreenshotCaptureService screenshots,
        final PreviewCache cache
    ) {
        this.recentFiles = Objects.requireNonNull(recentFiles, "recentFiles");
        this.screenshots = Objects.requireNonNull(screenshots, "screenshots");
        this.cache = Objects.requireNonNull(cache, "cache");
    }

    /**
     * Allows capture and serving of previews. Does not itself read the host recent-file
     * list or populate the memory cache; call {@code refresh()} for that.
     */
    public void enable() {
        enabled = true;
    }

    /**
     * Stops preview activity and drops all in-memory state: cached images, the recent-file
     * projection, in-flight capture tracking, and capture dedupe marks.
     *
     * <p>The on-disk cache is left untouched, so previews survive to the next session.</p>
     */
    public void disable() {
        enabled = false;
        images.clear();
        enabled = false;
        images.clear();
        inFlight.clear();
        lastCapturedModified.clear();
        files = List.of();
    }

    /**
     * @return whether previews are currently being served and captured; read from a
     *     volatile field, so it is safe to call from the host UI thread
     */
    public boolean isEnabled() {
        return enabled;
    }

    /** Re-reads the host recent-file projection; empty once disabled. */
    public CompletionStage<List<RecentFileSummary>> refresh() {
        if (!enabled) {
            return CompletableFuture.completedStage(List.of());
        }
        files = List.copyOf(recentFiles.list());
        return CompletableFuture.completedStage(files);
    }

    /** Fills the memory map from the disk cache for the current recent files. */
    public CompletionStage<Void> preload() {
        return refresh().thenCompose(current -> {
            if (current.isEmpty()) return CompletableFuture.completedStage(null);
            return cache.loadPng(current).thenAccept(loaded -> {
                if (!enabled) return;
                images.clear();
                images.putAll(loaded);
            });
        });
    }

    /**
     * Hook-track capture (exact timing): always dispatches for the id; only truly
     * concurrent duplicates are suppressed via the in-flight set. Records the file's
     * current last-modified value so the poll track does not re-fire for the same
     * content state.
     */
    public CompletionStage<PreviewCacheWriteResult> capture(final RecentFileId id) {
        return dispatchCapture(id, false);
    }

    /**
     * Poll-track capture (robustness): skipped when a capture was already dispatched
     * for the same id + last-modified (by the hook track or an earlier poll tick),
     * so a poll tick never double-fires an exact-timing hook capture. Records the
     * dispatched key for later ticks.
     */
    public CompletionStage<PreviewCacheWriteResult> pollCapture(final RecentFileId id) {
        return dispatchCapture(id, true);
    }

    private CompletionStage<PreviewCacheWriteResult> dispatchCapture(
        final RecentFileId id,
        final boolean dedupe
    ) {
        Objects.requireNonNull(id, "id");
        if (!enabled) {
            return CompletableFuture.completedStage(PreviewCacheWriteResult.DISABLED);
        }
        if (!inFlight.add(id)) {
            // A capture is already running for this id; the first request refreshes the popup.
            return CompletableFuture.completedStage(PreviewCacheWriteResult.DISABLED);
        }
        RecentFileSummary file = find(id);
        if (file == null) {
            files = List.copyOf(recentFiles.list());
            file = find(id);
        }
        if (file == null) {
            inFlight.remove(id);
            return CompletableFuture.completedStage(PreviewCacheWriteResult.RECENT_FILE_UNAVAILABLE);
        }
        final Optional<Instant> modified = file.lastModified();
        if (dedupe && modified.isPresent()
            && modified.get().toEpochMilli() == lastCapturedModified.getOrDefault(id, Long.MIN_VALUE)) {
            inFlight.remove(id);
            return CompletableFuture.completedStage(PreviewCacheWriteResult.DISABLED);
        }
        if (modified.isPresent()) {
            lastCapturedModified.put(id, modified.get().toEpochMilli());
        } else {
            lastCapturedModified.remove(id);
        }
        final RecentFileSummary target = file;
        return screenshots.capture(new ScreenshotCaptureRequest(id, THUMBNAIL_SIZE, THUMBNAIL_SIZE))
            .whenComplete((ignored, failure) -> inFlight.remove(id))
            .thenCompose(result -> {
                if (!enabled) {
                    return CompletableFuture.completedStage(PreviewCacheWriteResult.DISABLED);
                }
                if (!id.equals(result.id())) {
                    return CompletableFuture.completedStage(PreviewCacheWriteResult.RECENT_FILE_UNAVAILABLE);
                }
                return cache.store(target, result.image()).thenApply(stored -> {
                    if (stored == PreviewCacheWriteResult.STORED) {
                        images.put(id, result.image().png());
                    }
                    return stored;
                });
            });
    }

    /** The cached PNG bytes for one id, or empty when not cached. */
    public Optional<byte[]> image(final RecentFileId id) {
        return Optional.ofNullable(images.get(Objects.requireNonNull(id, "id")));
    }

    /**
     * Maps an opened/saved model to a recent-file id. The file-name hint from the
     * {@code before*} hook is preferred (it is the real file name with extension);
     * the model name is matched against the display-name stem as a fallback.
     */
    public Optional<RecentFileId> resolveId(final String modelName, final Optional<String> fileNameHint) {
        final List<RecentFileSummary> current = files;
        if (current.isEmpty()) {
            files = List.copyOf(recentFiles.list());
        }
        return resolveIdIn(files, modelName, fileNameHint);
    }

    static Optional<RecentFileId> resolveIdIn(
        final List<RecentFileSummary> candidates,
        final String modelName,
        final Optional<String> fileNameHint
    ) {
        if (fileNameHint.isPresent()) {
            for (RecentFileSummary candidate : candidates) {
                if (candidate.displayName().equals(fileNameHint.get())) {
                    return Optional.of(candidate.id());
                }
            }
        }
        if (modelName == null || modelName.isBlank()) {
            return Optional.empty();
        }
        for (RecentFileSummary candidate : candidates) {
            if (matchesModelName(candidate.displayName(), modelName)) {
                return Optional.of(candidate.id());
            }
        }
        return Optional.empty();
    }

    private static boolean matchesModelName(final String displayName, final String modelName) {
        if (displayName.equals(modelName)) return true;
        return displayName.startsWith(modelName + ".");
    }

    private RecentFileSummary find(final RecentFileId id) {
        for (RecentFileSummary candidate : files) {
            if (candidate.id().equals(id)) return candidate;
        }
        return null;
    }
}

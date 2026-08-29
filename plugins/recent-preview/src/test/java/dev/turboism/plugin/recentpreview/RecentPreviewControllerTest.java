package dev.turboism.plugin.recentpreview;

import dev.turboism.plugin.recentpreview.cache.PreviewCache;
import dev.turboism.plugin.recentpreview.cache.PreviewCacheWriteResult;
import dev.turboism.sdk.cubism.recentfile.RecentFileId;
import dev.turboism.sdk.cubism.recentfile.RecentFileService;
import dev.turboism.sdk.cubism.recentfile.RecentFileSummary;
import dev.turboism.sdk.cubism.screenshot.ScreenshotCaptureRequest;
import dev.turboism.sdk.cubism.screenshot.ScreenshotCaptureResult;
import dev.turboism.sdk.cubism.screenshot.ScreenshotCaptureService;
import dev.turboism.sdk.cubism.screenshot.ScreenshotCaptureTargetUnavailableException;
import dev.turboism.sdk.cubism.screenshot.ScreenshotImage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class RecentPreviewControllerTest {

    @Test
    void refreshesRecentFilesAndCapturesSelectedEntryAtLegacyThumbnailBounds() {
        final RecentFileSummary file = new RecentFileSummary(new RecentFileId("recent-1"), "model.cmo3");
        final RecordingCapture captures = new RecordingCapture();
        final RecordingCache cache = new RecordingCache();
        final RecentPreviewController controller = new RecentPreviewController(
            () -> List.of(file), captures, cache
        );

        controller.enable();
        assertEquals(List.of(file), controller.refresh().toCompletableFuture().join());
        assertEquals(PreviewCacheWriteResult.STORED,
            controller.capture(file.id()).toCompletableFuture().join());
        assertEquals(new ScreenshotCaptureRequest(file.id(), 150, 150), captures.request);
        assertEquals(file, cache.file);
        assertTrue(controller.image(file.id()).isPresent());
    }

    @Test
    void avoidsWritingWhenCaptureReturnsAStaleProjectId() {
        final RecentFileSummary file = new RecentFileSummary(new RecentFileId("recent-1"), "model.cmo3");
        final RecordingCapture captures = new RecordingCapture();
        captures.resultId = new RecentFileId("recent-2");
        final RecordingCache cache = new RecordingCache();
        final RecentPreviewController controller = new RecentPreviewController(
            () -> List.of(file), captures, cache
        );

        controller.enable();
        controller.refresh().toCompletableFuture().join();

        assertEquals(PreviewCacheWriteResult.RECENT_FILE_UNAVAILABLE,
            controller.capture(file.id()).toCompletableFuture().join());
        assertEquals(null, cache.file);
        assertTrue(controller.image(file.id()).isEmpty());
    }

    @Test
    void treatsTargetUnavailableCaptureAsAnExpectedNoWrite() {
        final RecentFileSummary file = new RecentFileSummary(new RecentFileId("recent-1"), "model.cmo3");
        final RecordingCapture captures = new RecordingCapture();
        captures.failure = new ScreenshotCaptureTargetUnavailableException();
        final RecordingCache cache = new RecordingCache();
        final RecentPreviewController controller = new RecentPreviewController(
            () -> List.of(file), captures, cache
        );

        controller.enable();
        controller.refresh().toCompletableFuture().join();

        assertEquals(PreviewCacheWriteResult.RECENT_FILE_UNAVAILABLE,
            controller.capture(file.id()).toCompletableFuture().join());
        assertEquals(null, cache.file);
        assertTrue(controller.image(file.id()).isEmpty());
    }

    @Test
    void preservesGenuineCaptureFailure() {
        final RecentFileSummary file = new RecentFileSummary(new RecentFileId("recent-1"), "model.cmo3");
        final RecordingCapture captures = new RecordingCapture();
        captures.failure = new IllegalStateException("PNG writer is unavailable");
        final RecentPreviewController controller = new RecentPreviewController(
            () -> List.of(file), captures, new RecordingCache()
        );

        controller.enable();
        controller.refresh().toCompletableFuture().join();

        assertThrows(java.util.concurrent.CompletionException.class,
            () -> controller.capture(file.id()).toCompletableFuture().join());
    }

    @Test
    void disabledControllerRejectsCaptureWithoutCallingRuntime() {
        final RecordingCapture captures = new RecordingCapture();
        final RecentPreviewController controller = new RecentPreviewController(
            List::of, captures, new RecordingCache()
        );

        assertEquals(PreviewCacheWriteResult.DISABLED,
            controller.capture(new RecentFileId("recent-1")).toCompletableFuture().join());
        assertEquals(null, captures.request);
    }

    @Test
    void captureRefreshesTheFileListWhenTheIdIsUnknown() {
        final RecordingCapture captures = new RecordingCapture();
        final RecordingCache cache = new RecordingCache();
        final RecentFileSummary file = new RecentFileSummary(new RecentFileId("recent-1"), "model.cmo3");
        final int[] listCalls = {0};
        final RecentPreviewController controller = new RecentPreviewController(
            () -> {
                listCalls[0]++;
                return List.of(file);
            },
            captures, cache
        );

        controller.enable();

        assertEquals(PreviewCacheWriteResult.STORED,
            controller.capture(file.id()).toCompletableFuture().join());
        assertEquals(1, listCalls[0]);
    }

    @Test
    void duplicateCaptureIsDeduplicatedWhileInFlight() {
        final RecentFileSummary file = new RecentFileSummary(new RecentFileId("recent-1"), "model.cmo3");
        final CompletableFuture<ScreenshotCaptureResult> pending = new CompletableFuture<>();
        final RecordingCapture captures = new RecordingCapture();
        captures.pending = pending;
        final RecentPreviewController controller = new RecentPreviewController(
            () -> List.of(file), captures, new RecordingCache()
        );

        controller.enable();
        controller.refresh().toCompletableFuture().join();
        final CompletionStage<PreviewCacheWriteResult> first = controller.capture(file.id());
        final CompletionStage<PreviewCacheWriteResult> second = controller.capture(file.id());

        assertEquals(PreviewCacheWriteResult.DISABLED, second.toCompletableFuture().join());
        assertEquals(1, captures.calls);

        pending.complete(new ScreenshotCaptureResult(file.id(), new ScreenshotImage(1, 1, png())));
        assertEquals(PreviewCacheWriteResult.STORED, first.toCompletableFuture().join());
    }

    @Test
    void staleCaptureCompletionAfterReenableCannotPublishAnOldThumbnail() {
        final RecentFileSummary file = new RecentFileSummary(new RecentFileId("recent-1"), "model.cmo3");
        final CompletableFuture<ScreenshotCaptureResult> oldPending = new CompletableFuture<>();
        final RecordingCapture captures = new RecordingCapture();
        captures.pending = oldPending;
        final RecordingCache cache = new RecordingCache();
        final RecentPreviewController controller = new RecentPreviewController(
            () -> List.of(file), captures, cache
        );

        controller.enable();
        controller.refresh().toCompletableFuture().join();
        final CompletionStage<PreviewCacheWriteResult> stale = controller.capture(file.id());
        controller.disable();
        controller.enable();
        controller.refresh().toCompletableFuture().join();

        oldPending.complete(new ScreenshotCaptureResult(file.id(), new ScreenshotImage(1, 1, png())));

        assertEquals(PreviewCacheWriteResult.DISABLED, stale.toCompletableFuture().join());
        assertEquals(null, cache.file, "the old generation must not write the persistent cache");
        assertTrue(controller.image(file.id()).isEmpty());
    }

    @Test
    void staleCompletionCannotClearANewGenerationCaptureForTheSameId() {
        final RecentFileSummary file = new RecentFileSummary(new RecentFileId("recent-1"), "model.cmo3");
        final CompletableFuture<ScreenshotCaptureResult> oldPending = new CompletableFuture<>();
        final CompletableFuture<ScreenshotCaptureResult> newPending = new CompletableFuture<>();
        final RecordingCapture captures = new RecordingCapture();
        captures.pendingResults.add(oldPending);
        captures.pendingResults.add(newPending);
        final RecentPreviewController controller = new RecentPreviewController(
            () -> List.of(file), captures, new RecordingCache()
        );

        controller.enable();
        controller.refresh().toCompletableFuture().join();
        final CompletionStage<PreviewCacheWriteResult> stale = controller.capture(file.id());
        controller.disable();
        controller.enable();
        controller.refresh().toCompletableFuture().join();
        final CompletionStage<PreviewCacheWriteResult> current = controller.capture(file.id());

        oldPending.complete(new ScreenshotCaptureResult(file.id(), new ScreenshotImage(1, 1, png())));
        assertEquals(PreviewCacheWriteResult.DISABLED, stale.toCompletableFuture().join());
        assertEquals(PreviewCacheWriteResult.DISABLED,
            controller.capture(file.id()).toCompletableFuture().join(),
            "the current generation must remain in flight after the stale completion");

        newPending.complete(new ScreenshotCaptureResult(file.id(), new ScreenshotImage(1, 1, png())));
        assertEquals(PreviewCacheWriteResult.STORED, current.toCompletableFuture().join());
        assertEquals(2, captures.calls);
    }

    @Test
    void resolvesIdByFileNameHintThenByModelNameStem() {
        final RecentFileSummary file = new RecentFileSummary(new RecentFileId("recent-1"), "model.cmo3");
        final RecentPreviewController controller = new RecentPreviewController(
            () -> List.of(file), new RecordingCapture(), new RecordingCache()
        );

        assertEquals(Optional.of(file.id()),
            controller.resolveId("Model", Optional.of("model.cmo3")));
        assertEquals(Optional.of(file.id()),
            controller.resolveId("model", Optional.empty()));
        assertEquals(Optional.empty(),
            controller.resolveId("other", Optional.empty()));
        assertEquals(Optional.empty(),
            controller.resolveId("other", Optional.of("unrelated.cmo3")));
    }

    @Test
    void preloadFillsMemoryMapFromTheDiskCache() {
        final RecentFileSummary file = new RecentFileSummary(new RecentFileId("recent-1"), "model.cmo3");
        final RecordingCache cache = new RecordingCache();
        cache.png = png();
        final RecentPreviewController controller = new RecentPreviewController(
            () -> List.of(file), new RecordingCapture(), cache
        );

        controller.enable();
        controller.preload().toCompletableFuture().join();

        assertTrue(controller.image(file.id()).isPresent());
        assertNotNull(controller.image(file.id()).get());

        controller.disable();
        assertFalse(controller.image(file.id()).isPresent());
    }

    @Test
    void stalePreloadCompletionAfterReenableCannotPublishOldCacheData() {
        final RecentFileSummary file = new RecentFileSummary(new RecentFileId("recent-1"), "model.cmo3");
        final CompletableFuture<java.util.Map<RecentFileId, byte[]>> oldPending = new CompletableFuture<>();
        final RecordingCache cache = new RecordingCache();
        cache.pendingLoad = oldPending;
        final RecentPreviewController controller = new RecentPreviewController(
            () -> List.of(file), new RecordingCapture(), cache
        );

        controller.enable();
        final CompletionStage<Void> stale = controller.preload();
        controller.disable();
        controller.enable();

        oldPending.complete(java.util.Map.of(file.id(), png()));

        stale.toCompletableFuture().join();
        assertTrue(controller.image(file.id()).isEmpty());
    }

    @Test
    void captureKeepsMissingLastModifiedSummary() {
        final RecentFileSummary file = new RecentFileSummary(
            new RecentFileId("recent-1"), "model.cmo3",
            Optional.of(Instant.parse("2026-08-05T12:00:00Z")), Optional.empty()
        );
        final RecordingCapture captures = new RecordingCapture();
        final RecordingCache cache = new RecordingCache();
        final RecentPreviewController controller = new RecentPreviewController(
            () -> List.of(file), captures, cache
        );

        controller.enable();
        assertEquals(PreviewCacheWriteResult.STORED,
            controller.capture(file.id()).toCompletableFuture().join());
        assertEquals(file, cache.file);
    }

    @Test
    void pollCaptureIsDeduplicatedAgainstTheHookTrackByIdAndLastModified() {
        final List<RecentFileSummary> files = new ArrayList<>(List.of(new RecentFileSummary(
            new RecentFileId("recent-1"), "model.cmo3",
            Optional.of(Instant.parse("2026-08-05T12:00:00Z")), Optional.empty()
        )));
        final RecordingCapture captures = new RecordingCapture();
        final RecordingCache cache = new RecordingCache();
        final RecentPreviewController controller = new RecentPreviewController(
            () -> List.copyOf(files), captures, cache
        );

        controller.enable();
        controller.refresh().toCompletableFuture().join();

        // Hook track captures the opened state.
        assertEquals(PreviewCacheWriteResult.STORED,
            controller.capture(files.get(0).id()).toCompletableFuture().join());
        assertEquals(1, captures.calls);

        // Poll track observing the same id + lastModified must not double-fire.
        assertEquals(PreviewCacheWriteResult.DISABLED,
            controller.pollCapture(files.get(0).id()).toCompletableFuture().join());
        assertEquals(1, captures.calls);

        // The file was rewritten (save happened): the poll track fires again.
        files.set(0, new RecentFileSummary(
            new RecentFileId("recent-1"), "model.cmo3",
            Optional.of(Instant.parse("2026-08-05T13:00:00Z")), Optional.empty()
        ));
        controller.refresh().toCompletableFuture().join();
        assertEquals(PreviewCacheWriteResult.STORED,
            controller.pollCapture(files.get(0).id()).toCompletableFuture().join());
        assertEquals(2, captures.calls);

        // The rewritten state is now deduplicated for later ticks too.
        assertEquals(PreviewCacheWriteResult.DISABLED,
            controller.pollCapture(files.get(0).id()).toCompletableFuture().join());
        assertEquals(2, captures.calls);
    }

    private static byte[] png() {
        return java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
        );
    }

    private static final class RecordingCapture implements ScreenshotCaptureService {
        private final List<ScreenshotCaptureRequest> requests = new ArrayList<>();
        private ScreenshotCaptureRequest request;
        private RecentFileId resultId;
        private RuntimeException failure;
        private CompletableFuture<ScreenshotCaptureResult> pending;
        private final java.util.ArrayDeque<CompletableFuture<ScreenshotCaptureResult>> pendingResults =
            new java.util.ArrayDeque<>();
        private int calls;

        @Override
        public CompletionStage<ScreenshotCaptureResult> capture(final ScreenshotCaptureRequest value) {
            calls++;
            requests.add(value);
            request = value;
            if (failure != null) {
                return CompletableFuture.failedStage(failure);
            }
            if (!pendingResults.isEmpty()) {
                return pendingResults.removeFirst();
            }
            if (pending != null) {
                return pending;
            }
            return CompletableFuture.completedStage(new ScreenshotCaptureResult(
                resultId == null ? value.id() : resultId, new ScreenshotImage(1, 1, png())
            ));
        }
    }

    private static final class RecordingCache implements PreviewCache {
        private RecentFileSummary file;
        private byte[] png;
        private CompletableFuture<java.util.Map<RecentFileId, byte[]>> pendingLoad;

        @Override
        public CompletionStage<PreviewCacheWriteResult> store(
            final RecentFileSummary value,
            final ScreenshotImage image
        ) {
            file = value;
            return CompletableFuture.completedStage(PreviewCacheWriteResult.STORED);
        }

        @Override
        public CompletionStage<java.util.Map<RecentFileId, byte[]>> loadPng(
            final List<RecentFileSummary> files
        ) {
            if (pendingLoad != null) {
                return pendingLoad;
            }
            final java.util.Map<RecentFileId, byte[]> loaded = new java.util.HashMap<>();
            if (png != null) {
                for (RecentFileSummary candidate : files) {
                    loaded.put(candidate.id(), png);
                }
            }
            return CompletableFuture.completedStage(loaded);
        }
    }
}

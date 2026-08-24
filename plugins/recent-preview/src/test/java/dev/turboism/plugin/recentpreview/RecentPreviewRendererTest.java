package dev.turboism.plugin.recentpreview;

import dev.turboism.plugin.recentpreview.cache.PreviewCache;
import dev.turboism.plugin.recentpreview.cache.PreviewCacheWriteResult;
import dev.turboism.sdk.cubism.recentfile.RecentFileId;
import dev.turboism.sdk.cubism.recentfile.RecentFileSummary;
import dev.turboism.sdk.cubism.recentpreview.RecentPreviewContent;
import dev.turboism.sdk.cubism.screenshot.ScreenshotCaptureResult;
import dev.turboism.sdk.cubism.screenshot.ScreenshotCaptureService;
import dev.turboism.sdk.cubism.screenshot.ScreenshotImage;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.ui.PanelView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RecentPreviewRendererTest {

    @Test
    void rendersCachedImageWithFileNameAndLastModifiedRows() {
        final RecentFileSummary summary = new RecentFileSummary(
            new RecentFileId("recent-1"), "model.cmo3",
            Optional.of(Instant.parse("2026-08-05T12:00:00Z")),
            Optional.of("Z:/work/model.cmo3")
        );
        final RecentPreviewController controller = new RecentPreviewController(
            () -> List.of(summary), new NoopCapture(), new NoopCache()
        );
        controller.enable();
        // Prime the memory map through the real capture path.
        assertEquals(PreviewCacheWriteResult.STORED,
            controller.capture(summary.id()).toCompletableFuture().join());
        final RecentPreviewRendererImpl renderer = new RecentPreviewRendererImpl(
            controller, id -> { }, new NoopLogger()
        );

        final RecentPreviewContent content = renderer.render(summary).orElseThrow();

        assertEquals(summary.id(), content.id());
        final PanelView.Column column = (PanelView.Column) content.view();
        assertEquals(3, column.children().size());
        final PanelView.Image image = (PanelView.Image) column.children().get(0);
        assertArrayEquals(png(), image.pngBytes());
        assertEquals("model.cmo3", image.altText());
        assertEquals("model.cmo3", ((PanelView.Text) column.children().get(1)).value());
        assertEquals(
            RecentPreviewRendererImpl.LAST_MODIFIED_FORMAT.format(Instant.parse("2026-08-05T12:00:00Z")),
            ((PanelView.Text) column.children().get(2)).value());

        // The absolute path must not leak into the popup: no rendered text may
        // contain a path separator, and none may carry path content.
        final String rendered = String.join("\n", textValues(content.view()));
        assertFalse(rendered.contains("/") || rendered.contains("\\"),
            "rendered popup text must not contain a path separator: " + rendered);
        assertFalse(rendered.toLowerCase(java.util.Locale.ROOT).contains("path"),
            "rendered popup text must not contain path content: " + rendered);
    }

    /** Flattens all {@link PanelView.Text} values of a view tree. */
    private static java.util.List<String> textValues(final PanelView view) {
        if (view instanceof PanelView.Text text) return java.util.List.of(text.value());
        if (view instanceof PanelView.Column column) {
            final java.util.List<String> values = new java.util.ArrayList<>();
            column.children().forEach(child -> values.addAll(textValues(child)));
            return values;
        }
        return java.util.List.of();
    }

    @Test
    void missingCacheReturnsLoadingContentAndRequestsCaptureOnce() {
        final RecentFileSummary summary = new RecentFileSummary(new RecentFileId("recent-1"), "model.cmo3");
        final AtomicInteger requests = new AtomicInteger();
        final RecentPreviewController controller = new RecentPreviewController(
            List::of, new NoopCapture(), new NoopCache()
        );
        controller.enable();
        final RecentPreviewRendererImpl renderer = new RecentPreviewRendererImpl(
            controller, id -> requests.incrementAndGet(), new NoopLogger(), "Loading preview…"
        );

        final RecentPreviewContent first = renderer.render(summary).orElseThrow();
        final RecentPreviewContent second = renderer.render(summary).orElseThrow();

        assertEquals(1, requests.get());
        assertEquals(List.of("model.cmo3", "", "Loading preview…"), textValues(first.view()));
        assertEquals(textValues(first.view()), textValues(second.view()));
    }

    @Test
    void failedLoadingAttemptHidesOnceThenAllowsRetry() {
        final RecentFileSummary summary = new RecentFileSummary(new RecentFileId("recent-1"), "model.cmo3");
        final AtomicInteger requests = new AtomicInteger();
        final RecentPreviewController controller = new RecentPreviewController(
            List::of, new NoopCapture(), new NoopCache()
        );
        controller.enable();
        final RecentPreviewRendererImpl renderer = new RecentPreviewRendererImpl(
            controller, id -> requests.incrementAndGet(), new NoopLogger(), "Loading preview…"
        );

        assertTrue(renderer.render(summary).isPresent());
        renderer.captureFailed(summary.id());
        assertTrue(renderer.render(summary).isEmpty(), "the completion refresh must clear the loading popup once");
        assertTrue(renderer.render(summary).isPresent(), "a later user hover may retry");
        assertEquals(2, requests.get());
    }

    @Test
    void formatsLastModifiedAsLocalDateTimeOrEmpty() {
        assertEquals("", RecentPreviewRendererImpl.formatLastModified(Optional.empty()));
        final String formatted = RecentPreviewRendererImpl.formatLastModified(
            Optional.of(Instant.parse("2026-08-05T12:00:00Z"))
        );
        assertTrue(formatted.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"));
        assertEquals(formatted, java.time.ZonedDateTime.ofInstant(
            Instant.parse("2026-08-05T12:00:00Z"), ZoneId.systemDefault()
        ).format(RecentPreviewRendererImpl.LAST_MODIFIED_FORMAT));
    }

    private static byte[] png() {
        return java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
        );
    }

    private static final class NoopCapture implements ScreenshotCaptureService {
        @Override
        public CompletionStage<ScreenshotCaptureResult> capture(
            final dev.turboism.sdk.cubism.screenshot.ScreenshotCaptureRequest request
        ) {
            return CompletableFuture.completedFuture(new ScreenshotCaptureResult(
                request.id(), new ScreenshotImage(1, 1, png())
            ));
        }
    }

    private static final class NoopCache implements PreviewCache {
        @Override
        public CompletionStage<PreviewCacheWriteResult> store(
            final RecentFileSummary file, final ScreenshotImage image
        ) {
            return CompletableFuture.completedStage(PreviewCacheWriteResult.STORED);
        }

        @Override
        public CompletionStage<java.util.Map<RecentFileId, byte[]>> loadPng(
            final List<RecentFileSummary> files
        ) {
            return CompletableFuture.completedStage(java.util.Map.of());
        }
    }

    private static final class NoopLogger implements PluginLogger {
        @Override public void debug(String message) { }
        @Override public void info(String message) { }
        @Override public void warn(String message) { }
        @Override public void error(String message) { }
        @Override public void error(String message, Throwable throwable) { }
    }
}

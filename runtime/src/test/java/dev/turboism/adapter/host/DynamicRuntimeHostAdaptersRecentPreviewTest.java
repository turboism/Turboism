package dev.turboism.adapter.host;

import dev.turboism.adapter.RuntimeHostAdapters;
import dev.turboism.adapter.cubism.RecentFileAdapter;
import dev.turboism.adapter.cubism.RecentPreviewContributionAdapter;
import dev.turboism.adapter.cubism.ScreenshotCaptureAdapter;
import dev.turboism.sdk.cubism.recentfile.RecentFileId;
import dev.turboism.sdk.cubism.recentfile.RecentFileSummary;
import dev.turboism.sdk.cubism.recentpreview.RecentPreviewRenderer;
import dev.turboism.sdk.cubism.screenshot.ScreenshotCaptureRequest;
import dev.turboism.sdk.cubism.screenshot.ScreenshotCaptureResult;
import dev.turboism.sdk.cubism.screenshot.ScreenshotImage;
import dev.turboism.sdk.plugin.Registration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DynamicRuntimeHostAdaptersRecentPreviewTest {
    private static final byte[] PNG = java.util.Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
    );

    @Test
    void forwardsRecentFileAndScreenshotCallsToCurrentConnection() throws Exception {
        final DynamicRuntimeHostAdapters dynamic = new DynamicRuntimeHostAdapters();
        final RecentFileSummary file = new RecentFileSummary(new RecentFileId("one"), "One.cmo3");
        final ScreenshotCaptureRequest request = new ScreenshotCaptureRequest(file.id(), 150, 150);
        final ScreenshotCaptureResult result = new ScreenshotCaptureResult(
            file.id(), new ScreenshotImage(1, 1, PNG)
        );
        dynamic.connect(connected(List.of(file), result));

        assertEquals(List.of(file), dynamic.view().recentFiles().list());
        assertEquals(result, dynamic.view().screenshots().capture(request).toCompletableFuture().join());

        dynamic.deactivate();
        assertEquals(List.of(), dynamic.view().recentFiles().list());
        assertThrows(java.util.concurrent.CompletionException.class,
            () -> dynamic.view().screenshots().capture(request).toCompletableFuture().join());
    }

    @Test
    void deactivateWaitsForInFlightScreenshotStageToSettle() throws Exception {
        final DynamicRuntimeHostAdapters dynamic = new DynamicRuntimeHostAdapters();
        final CompletableFuture<ScreenshotCaptureResult> pending = new CompletableFuture<>();
        final ScreenshotCaptureRequest request =
            new ScreenshotCaptureRequest(new RecentFileId("one"), 150, 150);
        final ScreenshotCaptureResult result = new ScreenshotCaptureResult(
            request.id(), new ScreenshotImage(1, 1, PNG)
        );
        dynamic.connect(connected(List.of(), ignored -> pending));

        final CompletableFuture<ScreenshotCaptureResult> capture =
            dynamic.view().screenshots().capture(request).toCompletableFuture();
        final CompletableFuture<Void> deactivated = CompletableFuture.runAsync(() -> {
            try {
                dynamic.deactivate();
            } catch (Exception failure) {
                throw new java.util.concurrent.CompletionException(failure);
            }
        });

        Thread.sleep(200);
        assertFalse(deactivated.isDone(),
            "deactivate must not complete while a screenshot stage is still in flight");

        pending.complete(result);
        assertEquals(result, capture.join());
        deactivated.get(5, java.util.concurrent.TimeUnit.SECONDS);
    }

    @Test
    void contributionRegistrationsAreClosedByDeactivate() throws Exception {
        final DynamicRuntimeHostAdapters dynamic = new DynamicRuntimeHostAdapters();
        final boolean[] closed = {false};
        final RuntimeHostAdapters safe = RuntimeHostAdapters.safeMode();
        final RuntimeHostAdapters connected = new RuntimeHostAdapters(
            safe.themeStatus(), safe.renderStatus(), safe.projectWorkspace(), safe.clipMaskRead(),
            safe.statusToolbar(), safe.uiSurface(),
            safe.recentFiles(),
            safe.screenshots(),
            RecentPreviewContributionAdapter.connected(new RecentPreviewContributionAdapter.HostOperations() {
                @Override
                public Registration contribute(final RecentPreviewRenderer renderer) {
                    return () -> closed[0] = true;
                }

                @Override
                public void refresh() {
                }
            })
        );
        dynamic.connect(connected);

        final Registration registration = dynamic.view().recentPreviews()
            .contribute(summary -> Optional.empty());
        assertEquals(1, dynamic.trackedRegistrationCountForTest());

        dynamic.deactivate();
        assertTrue(closed[0], "session deactivate must close contribution registrations");
        registration.close();
    }

    @Test
    void contributionAfterDeactivateFailsClosed() throws Exception {
        final DynamicRuntimeHostAdapters dynamic = new DynamicRuntimeHostAdapters();
        dynamic.deactivate();
        assertThrows(UnsupportedOperationException.class,
            () -> dynamic.view().recentPreviews().contribute(summary -> Optional.empty()));
        dynamic.view().recentPreviews().refresh();
    }

    private static RuntimeHostAdapters connected(
        final List<RecentFileSummary> files,
        final ScreenshotCaptureResult result
    ) {
        return connected(files, ignored -> CompletableFuture.completedStage(result));
    }

    private static RuntimeHostAdapters connected(
        final List<RecentFileSummary> files,
        final java.util.function.Function<ScreenshotCaptureRequest,
            java.util.concurrent.CompletionStage<ScreenshotCaptureResult>> capture
    ) {
        final RuntimeHostAdapters safe = RuntimeHostAdapters.safeMode();
        return new RuntimeHostAdapters(
            safe.themeStatus(), safe.renderStatus(), safe.projectWorkspace(), safe.clipMaskRead(),
            safe.statusToolbar(), safe.uiSurface(),
            RecentFileAdapter.connected(new RecentFileAdapter.HostOperations() {
                @Override public List<RecentFileSummary> list() { return files; }
                @Override public Optional<RecentFileId> current() {
                    return files.stream().findFirst().map(RecentFileSummary::id);
                }
            }),
            ScreenshotCaptureAdapter.connected(capture::apply),
            safe.recentPreviews()
        );
    }
}

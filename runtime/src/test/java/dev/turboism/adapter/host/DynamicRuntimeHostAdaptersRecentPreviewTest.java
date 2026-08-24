package dev.turboism.adapter.host;

import dev.turboism.adapter.RuntimeHostAdapters;
import dev.turboism.adapter.cubism.backup.AutoBackupAdapter;
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

import java.io.File;
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
            }),
            safe.autoBackup()
        );
        dynamic.connect(connected);

        final Registration registration = dynamic.view().recentPreviews()
            .contribute(summary -> Optional.empty());
        assertEquals(1, dynamic.trackedRecentPreviewRegistrationCountForTest());

        dynamic.deactivate();
        assertTrue(closed[0], "session deactivate must close contribution registrations");
        registration.close();
    }

    @Test
    void contributionRebindsAcrossReconnectAndClosesTheCurrentDelegate() throws Exception {
        final DynamicRuntimeHostAdapters dynamic = new DynamicRuntimeHostAdapters();
        final java.util.concurrent.atomic.AtomicInteger firstContributions =
            new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger firstCloses =
            new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger secondContributions =
            new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger secondCloses =
            new java.util.concurrent.atomic.AtomicInteger();
        dynamic.connect(connectedPreview(firstContributions, firstCloses));

        final Registration registration = dynamic.view().recentPreviews()
            .contribute(summary -> Optional.empty());
        assertEquals(1, firstContributions.get());

        dynamic.deactivate();
        assertEquals(1, firstCloses.get(), "deactivate must detach the old popup bridge");

        dynamic.connect(connectedPreview(secondContributions, secondCloses));
        assertEquals(1, secondContributions.get(),
            "the still-open plugin contribution must attach to the replacement host");

        registration.close();
        assertEquals(1, secondCloses.get(), "plugin close must detach the replacement bridge");
        assertEquals(0, dynamic.trackedRecentPreviewRegistrationCountForTest());
        dynamic.deactivate();
        assertEquals(1, secondCloses.get(), "later deactivate must not close it twice");
    }

    @Test
    void reconnectAttemptsEveryOpenContributionWhenOneCannotAttach() throws Exception {
        final DynamicRuntimeHostAdapters dynamic = new DynamicRuntimeHostAdapters();
        final java.util.concurrent.atomic.AtomicInteger firstContributions =
            new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger firstCloses =
            new java.util.concurrent.atomic.AtomicInteger();
        dynamic.connect(connectedPreview(firstContributions, firstCloses));
        final Registration one = dynamic.view().recentPreviews().contribute(summary -> Optional.empty());
        final Registration two = dynamic.view().recentPreviews().contribute(summary -> Optional.empty());
        dynamic.deactivate();

        final java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        final RuntimeHostAdapters safe = RuntimeHostAdapters.safeMode();
        final RuntimeHostAdapters replacement = new RuntimeHostAdapters(
            safe.themeStatus(), safe.renderStatus(), safe.projectWorkspace(), safe.clipMaskRead(),
            safe.statusToolbar(), safe.uiSurface(), safe.recentFiles(), safe.screenshots(),
            RecentPreviewContributionAdapter.connected(new RecentPreviewContributionAdapter.HostOperations() {
                @Override
                public Registration contribute(final RecentPreviewRenderer renderer) {
                    if (attempts.incrementAndGet() == 1) {
                        throw new IllegalStateException("first attach failed");
                    }
                    return () -> { };
                }

                @Override
                public void refresh() {
                }
            }),
            safe.autoBackup()
        );

        dynamic.connect(replacement);
        assertEquals(2, attempts.get(), "one failed renderer must not prevent later renderers attaching");

        one.close();
        two.close();
        dynamic.deactivate();
    }

    @Test
    void failedRebindDoesNotPublishTheReplacementDelegateEarly() throws Exception {
        final DynamicRuntimeHostAdapters dynamic = new DynamicRuntimeHostAdapters();
        final RecentFileSummary original =
            new RecentFileSummary(new RecentFileId("original"), "Original.cmo3");
        final RecentFileSummary replacement =
            new RecentFileSummary(new RecentFileId("replacement"), "Replacement.cmo3");
        dynamic.connect(connectedWithPreview(List.of(original), ignored -> () -> { }));
        final Registration registration = dynamic.view().recentPreviews()
            .contribute(summary -> Optional.empty());
        dynamic.deactivate();

        final java.util.concurrent.atomic.AtomicBoolean readReplacementDuringRebind =
            new java.util.concurrent.atomic.AtomicBoolean();
        dynamic.connect(connectedWithPreview(List.of(replacement), ignored -> {
            readReplacementDuringRebind.set(dynamic.view().recentFiles().list().equals(List.of(replacement)));
            throw new IllegalStateException("attach failed");
        }));

        assertFalse(readReplacementDuringRebind.get(),
            "the replacement adapter must not be callable until persistent contributions rebind");
        assertEquals(List.of(replacement), dynamic.view().recentFiles().list());
        registration.close();
        dynamic.deactivate();
    }

    @Test
    void contributionAfterDeactivateFailsClosed() throws Exception {
        final DynamicRuntimeHostAdapters dynamic = new DynamicRuntimeHostAdapters();
        dynamic.deactivate();
        assertThrows(UnsupportedOperationException.class,
            () -> dynamic.view().recentPreviews().contribute(summary -> Optional.empty()));
        dynamic.view().recentPreviews().refresh();
    }

    @Test
    void autoBackupSlotForwardsToTheConnectedAdapterAndFailsClosedBeforeConnect() {
        final DynamicRuntimeHostAdapters dynamic = new DynamicRuntimeHostAdapters();
        assertThrows(UnsupportedOperationException.class,
            () -> dynamic.view().autoBackup().settings(),
            "before connect the view must fail closed (safe-mode adapter)");

        dynamic.connect(connectedAutoBackup());
        final AutoBackupAdapter.Snapshot settings = dynamic.view().autoBackup().settings();
        assertTrue(settings.enabled());
        assertEquals(3, settings.intervalMinutes());
        assertEquals(128, settings.maxMB());
        assertEquals(new File("backup").getPath(), settings.backupDir().getPath());

        assertEquals(1, dynamic.view().autoBackup().documents().size());
        dynamic.view().autoBackup().triggerBackupNow();
        assertEquals(1, triggerCalls.get(), "triggerBackupNow must forward to the connected adapter");
    }

    @Test
    void autoBackupSlotFailsClosedAfterDeactivate() throws Exception {
        final DynamicRuntimeHostAdapters dynamic = new DynamicRuntimeHostAdapters();
        dynamic.connect(connectedAutoBackup());
        dynamic.deactivate();
        assertThrows(UnsupportedOperationException.class,
            () -> dynamic.view().autoBackup().settings(),
            "after deactivate the view must fail closed");
    }

    private static final java.util.concurrent.atomic.AtomicInteger triggerCalls =
        new java.util.concurrent.atomic.AtomicInteger();

    private static RuntimeHostAdapters connectedPreview(
        final java.util.concurrent.atomic.AtomicInteger contributions,
        final java.util.concurrent.atomic.AtomicInteger closes
    ) {
        return connectedWithPreview(List.of(), ignored -> {
            contributions.incrementAndGet();
            return closes::incrementAndGet;
        });
    }

    private static RuntimeHostAdapters connectedWithPreview(
        final List<RecentFileSummary> files,
        final java.util.function.Function<RecentPreviewRenderer, Registration> contribute
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
            safe.screenshots(),
            RecentPreviewContributionAdapter.connected(new RecentPreviewContributionAdapter.HostOperations() {
                @Override
                public Registration contribute(final RecentPreviewRenderer renderer) {
                    return contribute.apply(renderer);
                }

                @Override
                public void refresh() {
                }
            }),
            safe.autoBackup()
        );
    }

    private static RuntimeHostAdapters connectedAutoBackup() {
        final RuntimeHostAdapters safe = RuntimeHostAdapters.safeMode();
        return new RuntimeHostAdapters(
            safe.themeStatus(), safe.renderStatus(), safe.projectWorkspace(), safe.clipMaskRead(),
            safe.statusToolbar(), safe.uiSurface(),
            safe.recentFiles(),
            safe.screenshots(),
            safe.recentPreviews(),
            AutoBackupAdapter.connected(new AutoBackupAdapter.HostOperations() {
                @Override
                public AutoBackupAdapter.Snapshot settings() {
                    return new AutoBackupAdapter.Snapshot(true, 3, 128, new File("backup"));
                }

                @Override
                public AutoBackupAdapter.Snapshot applySettings(final AutoBackupAdapter.Snapshot target) {
                    return target;
                }

                @Override
                public List<AutoBackupAdapter.Document> documents() {
                    return List.of(new AutoBackupAdapter.Document(
                        "model.cmo3", new File("model.cmo3"), 1_000L, 900L, true));
                }

                @Override
                public void triggerBackupNow() {
                    triggerCalls.incrementAndGet();
                }

                @Override
                public File saveDocumentFor(
                    final File matchFile, final java.util.List<String> documentUids,
                    final long timestampMillis
                ) {
                    return null;
                }
            })
        );
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
            safe.recentPreviews(),
            safe.autoBackup()
        );
    }
}

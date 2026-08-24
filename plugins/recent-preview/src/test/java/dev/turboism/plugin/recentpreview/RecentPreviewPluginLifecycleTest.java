package dev.turboism.plugin.recentpreview;

import dev.turboism.sdk.cubism.ProjectContentKind;
import dev.turboism.sdk.cubism.ProjectContentSnapshot;
import dev.turboism.sdk.cubism.ProjectFileOperation;
import dev.turboism.sdk.cubism.ProjectFileOperationType;
import dev.turboism.sdk.cubism.recentfile.RecentFileId;
import dev.turboism.sdk.cubism.recentfile.RecentFileService;
import dev.turboism.sdk.cubism.recentfile.RecentFileSummary;
import dev.turboism.sdk.cubism.recentpreview.RecentPreviewContributionService;
import dev.turboism.sdk.cubism.recentpreview.RecentPreviewRenderer;
import dev.turboism.sdk.cubism.screenshot.ScreenshotCaptureRequest;
import dev.turboism.sdk.cubism.screenshot.ScreenshotCaptureResult;
import dev.turboism.sdk.cubism.screenshot.ScreenshotCaptureService;
import dev.turboism.sdk.cubism.screenshot.ScreenshotImage;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.storage.PluginStorage;
import dev.turboism.sdk.plugin.CancellationToken;
import dev.turboism.sdk.storage.StorageListResult;
import dev.turboism.sdk.storage.StorageMutationResult;
import dev.turboism.sdk.storage.StoragePath;
import dev.turboism.sdk.storage.StorageReadResult;
import dev.turboism.sdk.storage.StorageWriteResult;
import dev.turboism.sdk.task.FixedDelayTaskRequest;
import dev.turboism.sdk.task.PluginTaskAction;
import dev.turboism.sdk.task.PluginTaskRequest;
import dev.turboism.sdk.task.PluginTaskScheduler;
import dev.turboism.sdk.task.TaskHandle;
import dev.turboism.sdk.task.TaskId;
import dev.turboism.sdk.task.TaskOutcome;
import dev.turboism.sdk.task.TaskProgress;
import dev.turboism.sdk.task.TaskSubmission;
import dev.turboism.sdk.task.TaskSubmissionStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RecentPreviewPluginLifecycleTest {

    @Test
    void enableContributesRendererAndDisableClosesIt() {
        final RecentFileSummary file = new RecentFileSummary(new RecentFileId("one"), "One.cmo3");
        final List<RecentPreviewRenderer> renderers = new ArrayList<>();
        final RecentPreviewPlugin plugin = new RecentPreviewPlugin();

        plugin.init(context(file, renderers, new int[]{0}, new AtomicInteger(), List.of()));
        plugin.enable();

        assertEquals(1, renderers.size());
        assertTrue(plugin.isEnabled());

        plugin.disable();
        assertFalse(plugin.isEnabled());
        assertTrue(renderers.isEmpty());
    }

    @Test
    void openedModelAndBeforeSaveHookTriggerCapturesAtExactTiming() {
        final RecentFileSummary file = new RecentFileSummary(new RecentFileId("one"), "One.cmo3");
        final List<RecentPreviewRenderer> renderers = new ArrayList<>();
        final int[] captures = {0};
        final AtomicInteger refreshes = new AtomicInteger();
        final List<String> warnings = new ArrayList<>();
        final RecentPreviewPlugin plugin = new RecentPreviewPlugin();

        plugin.init(context(file, renderers, captures, refreshes, warnings));
        plugin.enable();

        plugin.beforeOpenModel(new ProjectFileOperation(
            ProjectContentKind.MODEL, ProjectFileOperationType.OPEN,
            Optional.of("content-1"), "One.cmo3", Optional.of("One.cmo3")
        ));
        plugin.onModelOpened(new ProjectContentSnapshot(
            "content-1", "One", ProjectContentKind.MODEL,
            Optional.empty(), List.of()
        ));
        awaitCaptures(captures, 1);

        // beforeSaveModel must capture before the save snapshot hook fires.
        plugin.beforeSaveModel(new ProjectFileOperation(
            ProjectContentKind.MODEL, ProjectFileOperationType.SAVE,
            Optional.of("content-1"), "One.cmo3", Optional.of("One.cmo3")
        ));
        awaitCaptures(captures, 2);

        // onModelSaved stays as the post-save safety net.
        plugin.onModelSaved(new ProjectContentSnapshot(
            "content-1", "One", ProjectContentKind.MODEL,
            Optional.empty(), List.of()
        ));
        awaitCaptures(captures, 3);

        assertTrue(refreshes.get() >= 1);
        assertTrue(warnings.isEmpty());
    }

    @Test
    void pollTickCapturesTheCurrentDocumentAndDedupesAgainstHooks() throws Exception {
        final List<RecentFileSummary> files = new ArrayList<>(List.of(new RecentFileSummary(
            new RecentFileId("one"), "One.cmo3",
            Optional.of(Instant.parse("2026-08-05T12:00:00Z")), Optional.empty()
        )));
        final List<RecentPreviewRenderer> renderers = new ArrayList<>();
        final int[] captures = {0};
        final AtomicInteger refreshes = new AtomicInteger();
        final List<String> warnings = new ArrayList<>();
        final RecordingTaskScheduler tasks = new RecordingTaskScheduler();
        final AtomicLong now = new AtomicLong(1_000_000L);
        final RecentPreviewPlugin plugin = new RecentPreviewPlugin();
        plugin.pollClock = now::get;

        plugin.init(context(files, renderers, captures, refreshes, warnings, tasks));
        plugin.enable();

        assertEquals(1, tasks.fixedDelay.size(), "enable() must start the poll task");
        final PluginTaskAction action = tasks.fixedDelay.get(0).action();

        // Hook track already captured the opened document; a poll tick with the
        // same id + lastModified must not double-fire.
        plugin.onModelOpened(new ProjectContentSnapshot(
            "content-1", "One", ProjectContentKind.MODEL, Optional.empty(), List.of()
        ));
        awaitCaptures(captures, 1);
        action.run(noopToken());
        assertEquals(1, captures[0], "poll tick must dedupe against the hook capture");

        // The current document's file was rewritten (save happened): the poll
        // track fires a fresh capture once the emission interval has passed.
        files.set(0, new RecentFileSummary(
            new RecentFileId("one"), "One.cmo3",
            Optional.of(Instant.parse("2026-08-05T13:00:00Z")), Optional.empty()
        ));
        now.addAndGet(RecentPreviewPoller.MIN_CAPTURE_INTERVAL.toMillis() + 1);
        action.run(noopToken());
        assertEquals(2, captures[0], "a lastModified change must trigger a poll capture");

        // Disabling closes the repeating task and makes an already-retained action harmless.
        plugin.disable();
        assertEquals(1, tasks.closedHandles.get());
        action.run(noopToken());
        assertEquals(2, captures[0]);
    }

    @Test
    void failedPollCaptureClearsHoverLoadingStateAndAllowsRetry() throws Exception {
        final RecentFileSummary file = new RecentFileSummary(
            new RecentFileId("one"), "One.cmo3",
            Optional.of(Instant.parse("2026-08-05T12:00:00Z")), Optional.empty()
        );
        final List<RecentPreviewRenderer> renderers = new ArrayList<>();
        final AtomicInteger refreshes = new AtomicInteger();
        final List<String> warnings = new ArrayList<>();
        final RecordingTaskScheduler tasks = new RecordingTaskScheduler();
        final CompletableFuture<ScreenshotCaptureResult> pending = new CompletableFuture<>();
        final AtomicInteger captures = new AtomicInteger();
        final ScreenshotCaptureService screenshots = request -> {
            captures.incrementAndGet();
            return pending;
        };
        final RecentPreviewPlugin plugin = new RecentPreviewPlugin();

        plugin.init(context(
            List.of(file), renderers, screenshots, refreshes, warnings, tasks
        ));
        plugin.enable();
        tasks.fixedDelay.get(0).action().run(noopToken());
        assertEquals(1, captures.get());

        final RecentPreviewRenderer renderer = renderers.get(0);
        assertTrue(renderer.render(file).isPresent(), "hover must show loading while the poll capture runs");
        awaitCondition(() -> captures.get() >= 1);
        pending.completeExceptionally(new IllegalStateException("capture failed"));
        awaitCondition(() -> refreshes.get() >= 1);

        assertTrue(renderer.render(file).isEmpty(), "the completion refresh hides failed loading once");
        assertTrue(renderer.render(file).isPresent(), "a later hover may request capture again");
        awaitCondition(() -> captures.get() >= 2);
        assertTrue(warnings.stream().anyMatch(value -> value.contains("poll capture failed")));
    }

    @Test
    void disableStopsFurtherHookCaptures() {
        final RecentFileSummary file = new RecentFileSummary(new RecentFileId("one"), "One.cmo3");
        final List<RecentPreviewRenderer> renderers = new ArrayList<>();
        final int[] captures = {0};
        final RecentPreviewPlugin plugin = new RecentPreviewPlugin();

        plugin.init(context(file, renderers, captures, new AtomicInteger(), List.of()));
        plugin.enable();
        plugin.disable();

        plugin.onModelOpened(new ProjectContentSnapshot(
            "content-1", "One", ProjectContentKind.MODEL, Optional.empty(), List.of()
        ));
        assertEquals(0, captures[0]);
        assertTrue(renderers.isEmpty());
    }

    @Test
    void shutdownClearsAllReferencesAndRegistrations() {
        final RecentFileSummary file = new RecentFileSummary(new RecentFileId("one"), "One.cmo3");
        final List<RecentPreviewRenderer> renderers = new ArrayList<>();
        final RecentPreviewPlugin plugin = new RecentPreviewPlugin();

        plugin.init(context(file, renderers, new int[]{0}, new AtomicInteger(), List.of()));
        plugin.enable();
        plugin.shutdown();

        assertFalse(plugin.isEnabled());
        assertTrue(renderers.isEmpty());
    }

    @Test
    void unavailableContributionServiceDegradesToNoPopupWithoutCrashing() {
        final RecentFileSummary file = new RecentFileSummary(new RecentFileId("one"), "One.cmo3");
        final List<RecentPreviewRenderer> renderers = new ArrayList<>();
        final int[] captures = {0};
        final AtomicInteger refreshes = new AtomicInteger();
        final List<String> warnings = new ArrayList<>();
        final RecentPreviewPlugin plugin = new RecentPreviewPlugin();

        // recentPreviews() throws UnsupportedOperationException (safe mode).
        plugin.init(unsafeContext(file, captures, refreshes, warnings));
        plugin.enable();

        assertTrue(plugin.isEnabled());
        assertTrue(renderers.isEmpty());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("popup contribution unavailable")));
    }

    private static void awaitCaptures(final int[] captures, final int expected) {
        awaitCondition(() -> captures[0] >= expected);
        assertEquals(expected, captures[0]);
    }

    private static void awaitCondition(final java.util.function.BooleanSupplier condition) {
        final long deadline = System.nanoTime() + 5_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertTrue(condition.getAsBoolean());
    }

    private static PluginContext context(
        final RecentFileSummary file,
        final List<RecentPreviewRenderer> renderers,
        final int[] captures,
        final AtomicInteger refreshes,
        final List<String> warnings
    ) {
        return context(
            List.of(file), renderers, captures, refreshes, warnings, new RecordingTaskScheduler()
        );
    }

    private static PluginContext context(
        final List<RecentFileSummary> files,
        final List<RecentPreviewRenderer> renderers,
        final int[] captures,
        final AtomicInteger refreshes,
        final List<String> warnings,
        final RecordingTaskScheduler tasks
    ) {
        final ScreenshotCaptureService screenshots = request -> {
            captures[0]++;
            return CompletableFuture.completedStage(new ScreenshotCaptureResult(
                request.id(), new ScreenshotImage(1, 1, png())
            ));
        };
        return context(files, renderers, screenshots, refreshes, warnings, tasks);
    }

    private static PluginContext context(
        final List<RecentFileSummary> files,
        final List<RecentPreviewRenderer> renderers,
        final ScreenshotCaptureService screenshots,
        final AtomicInteger refreshes,
        final List<String> warnings,
        final RecordingTaskScheduler tasks
    ) {
        final DisposableScope scope = new DisposableScope();
        final PluginLogger logger = (PluginLogger) Proxy.newProxyInstance(
            PluginLogger.class.getClassLoader(), new Class<?>[]{PluginLogger.class},
            (proxy, method, args) -> {
                if (method.getName().equals("warn") && args != null && args.length > 0) {
                    warnings.add(String.valueOf(args[0]));
                }
                return null;
            }
        );
        final PluginStorage storage = (PluginStorage) Proxy.newProxyInstance(
            PluginStorage.class.getClassLoader(), new Class<?>[]{PluginStorage.class},
            (proxy, method, args) -> {
                if (method.getName().equals("readBytes")) {
                    return CompletableFuture.completedStage(new StorageReadResult<>(
                        Optional.empty(), Optional.empty(), false
                    ));
                }
                if (method.getName().equals("writeBytesAtomic")) {
                    return CompletableFuture.completedStage(new StorageWriteResult(true, Optional.empty()));
                }
                if (method.getName().equals("writeUtf8Atomic")) {
                    return CompletableFuture.completedStage(new StorageWriteResult(true, Optional.empty()));
                }
                if (method.getName().equals("moveAtomic") || method.getName().equals("delete")) {
                    return CompletableFuture.completedStage(new StorageMutationResult(true, Optional.empty()));
                }
                if (method.getName().equals("list")) {
                    return CompletableFuture.completedStage(new StorageListResult(List.of(), Optional.empty(), false));
                }
                throw new UnsupportedOperationException(method.getName());
            }
        );
        final RecentFileService recentFiles = () -> List.copyOf(files);
        final RecentPreviewContributionService popups = new RecentPreviewContributionService() {
            @Override
            public Registration contribute(final RecentPreviewRenderer renderer) {
                renderers.add(renderer);
                return () -> renderers.remove(renderer);
            }

            @Override
            public void refresh() {
                refreshes.incrementAndGet();
            }
        };
        return (PluginContext) Proxy.newProxyInstance(
            PluginContext.class.getClassLoader(), new Class<?>[]{PluginContext.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "storage" -> storage;
                case "recentFiles" -> recentFiles;
                case "screenshots" -> screenshots;
                case "recentPreviews" -> popups;
                case "logger" -> logger;
                case "disposableScope" -> scope;
                case "tasks" -> tasks;
                default -> throw new UnsupportedOperationException(method.getName());
            }
        );
    }

    private static final class RecordingTaskScheduler implements PluginTaskScheduler {
        private final List<FixedDelayTaskRequest> fixedDelay = new ArrayList<>();
        private final AtomicInteger closedHandles = new AtomicInteger();

        @Override
        public TaskSubmission submit(final PluginTaskRequest request) {
            throw new UnsupportedOperationException("submit");
        }

        @Override
        public TaskSubmission scheduleWithFixedDelay(final FixedDelayTaskRequest request) {
            fixedDelay.add(request);
            return new TaskSubmission(TaskSubmissionStatus.ACCEPTED, new TaskHandle() {
                private boolean closed;

                @Override
                public TaskId id() {
                    return request.id();
                }

                @Override
                public TaskProgress progress() {
                    return new TaskProgress(0, Optional.empty());
                }

                @Override
                public boolean cancel() {
                    closed = true;
                    return !closed;
                }

                @Override
                public CompletionStage<TaskOutcome> completion() {
                    return null;
                }

                @Override
                public void close() {
                    if (!closed) {
                        closed = true;
                        closedHandles.incrementAndGet();
                    }
                }
            }, Optional.empty());
        }
    }

    private static CancellationToken noopToken() {
        return new CancellationToken() {
            @Override
            public boolean isCancellationRequested() {
                return false;
            }

            @Override
            public void checkCanceled() {
            }
        };
    }

    private static PluginContext unsafeContext(
        final RecentFileSummary file,
        final int[] captures,
        final AtomicInteger refreshes,
        final List<String> warnings
    ) {
        final PluginContext safe = context(file, new ArrayList<>(), captures, refreshes, warnings);
        return (PluginContext) Proxy.newProxyInstance(
            PluginContext.class.getClassLoader(), new Class<?>[]{PluginContext.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "recentPreviews" -> throw new UnsupportedOperationException("not available");
                default -> java.lang.reflect.Proxy.getInvocationHandler(safe).invoke(proxy, method, args);
            }
        );
    }

    private static byte[] png() {
        return java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
        );
    }
}

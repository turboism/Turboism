package dev.turboism.plugin.recentpreview;

import dev.turboism.plugin.recentpreview.cache.PreviewCacheIndex;
import dev.turboism.plugin.recentpreview.cache.PreviewCacheWriteResult;
import dev.turboism.sdk.cubism.CubismPlugin;
import dev.turboism.sdk.cubism.ProjectContentSnapshot;
import dev.turboism.sdk.cubism.ProjectFileOperation;
import dev.turboism.sdk.cubism.recentfile.RecentFileId;
import dev.turboism.sdk.cubism.recentfile.RecentFileSummary;
import dev.turboism.sdk.cubism.recentpreview.RecentPreviewContributionService;
import dev.turboism.sdk.cubism.recentpreview.RecentPreviewRenderer;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.task.FixedDelayTaskRequest;
import dev.turboism.sdk.task.PluginTaskKind;
import dev.turboism.sdk.task.PluginTaskPriority;
import dev.turboism.sdk.task.PluginTaskScheduler;
import dev.turboism.sdk.task.TaskHandle;
import dev.turboism.sdk.task.TaskId;
import dev.turboism.sdk.task.TaskSubmission;
import dev.turboism.sdk.plugin.Registration;

import javax.swing.SwingUtilities;

import java.util.Objects;
import java.util.Optional;
import java.time.Duration;
import java.util.List;

/**
 * Recent-file preview thumbnail plugin: captures a bounded preview when a model is
 * opened or saved, keeps PNGs plus a no-path index in plugin-confined cache storage,
 * and contributes popup content (thumbnail + file name / last edit time) to
 * the host Recent Files hover bridge. All host services are optional: an unavailable
 * service logs a warning and the plugin degrades to no capture/no popup.
 */
public final class RecentPreviewPlugin implements CubismPlugin {

    private PluginContext context;
    private RecentPreviewController controller;
    private PreviewCacheIndex cacheIndex;
    private RecentPreviewRendererImpl renderer;
    private Registration contribution;
    private volatile boolean enabled;
    private volatile Optional<String> fileNameHint = Optional.empty();
    /** Fixed-delay cadence of the poll-track fallback. */
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(3);

    private static final String POLL_TASK_ID = "recent-preview-poll";
    private static final String LOADING_TEXT_KEY = "preview.loading";
    private static final String DEFAULT_LOADING_TEXT = "Loading preview…";

    private RecentPreviewPoller poller;

    /** Poller clock (package-private so tests can drive the min-interval). */
    RecentPreviewPoller.Clock pollClock = System::currentTimeMillis;
    private TaskHandle pollTask;

    @Override
    public void init(final PluginContext context) {
        this.context = Objects.requireNonNull(context, "context");
        cacheIndex = new PreviewCacheIndex(context.storage());
        controller = new RecentPreviewController(
            context.recentFiles(),
            context.screenshots(),
            cacheIndex
        );
        renderer = new RecentPreviewRendererImpl(
            controller, this::requestCapture, context.logger(), loadingText(context)
        );
        context.disposableScope().register(() -> closeContribution());
        context.logger().info("Recent Preview plugin initialized");
    }

    @Override
    public void enable() {
        requireContext();
        if (enabled) return;
        enabled = true;
        controller.enable();
        controller.preload().whenComplete((ignored, failure) -> {
            if (enabled) refreshPopup();
        });
        contributeRenderer();
        startPoller();
    }

    @Override
    public void disable() {
        if (!enabled && contribution == null) {
            return;
        }
        enabled = false;
        stopPoller();
        closeContribution();
        if (controller != null) controller.disable();
        if (renderer != null) renderer.clearTransientState();
        fileNameHint = Optional.empty();
    }

    @Override
    public void shutdown() {
        disable();
        renderer = null;
        controller = null;
        cacheIndex = null;
        context = null;
    }

    boolean isEnabled() {
        return enabled;
    }

    // --- ModelFileHooks -----------------------------------------------------

    @Override
    public void beforeOpenModel(final ProjectFileOperation operation) {
        fileNameHint = operation.fileName().isPresent()
            ? operation.fileName()
            : Optional.of(operation.displayName());
    }

    @Override
    public void beforeSaveModel(final ProjectFileOperation operation) {
        fileNameHint = operation.fileName().isPresent()
            ? operation.fileName()
            : Optional.of(operation.displayName());
        // Exact-timing track: capture the current scene right before the save writes
        // the file, so the thumbnail matches the saved content (onModelSaved remains
        // as a safety net).
        captureForOperation(operation);
    }

    @Override
    public void onModelOpened(final ProjectContentSnapshot model) {
        captureForModel(model);
    }

    @Override
    public void onModelSaved(final ProjectContentSnapshot model) {
        captureForModel(model);
    }

    private void captureForModel(final ProjectContentSnapshot model) {
        if (!enabled || model == null) return;
        final Optional<RecentFileId> id = controller.resolveId(model.name(), fileNameHint);
        if (id.isEmpty()) return;
        requestCapture(id.get());
    }

    /** before* hook resolution: the operation carries the real file name (hint). */
    private void captureForOperation(final ProjectFileOperation operation) {
        if (!enabled || operation == null) return;
        final Optional<String> hint = operation.fileName().isPresent()
            ? operation.fileName()
            : Optional.of(operation.displayName());
        final Optional<RecentFileId> id = controller.resolveId(operation.displayName(), hint);
        if (id.isEmpty()) return;
        requestCapture(id.get());
    }

    // --- capture + popup refresh ---------------------------------------------

    private void requestCapture(final RecentFileId id) {
        if (SwingUtilities.isEventDispatchThread()) {
            // Never run the host capture inline inside the popup render; let the
            // popup show immediately and refresh it when the capture completes.
            SwingUtilities.invokeLater(() -> doCapture(id));
        } else {
            doCapture(id);
        }
    }

    // --- poll-track fallback --------------------------------------------------

    /**
     * Starts the fixed-delay poller; degrades to the hook track alone when the task
     * scheduler is unavailable (safe mode). Idempotent.
     */
    private void startPoller() {
        stopPoller();
        if (context == null) return;
        final PluginTaskScheduler tasks;
        try {
            tasks = context.tasks();
        } catch (RuntimeException unavailable) {
            context.logger().warn("Recent preview poller unavailable; hook track only");
            return;
        }
        poller = new RecentPreviewPoller(pollClock);
        final TaskSubmission submission;
        try {
            submission = tasks.scheduleWithFixedDelay(new FixedDelayTaskRequest(
                new TaskId(POLL_TASK_ID),
                PluginTaskKind.LOW_FREQUENCY_REFRESH,
                PluginTaskPriority.LOW,
                POLL_INTERVAL,
                POLL_INTERVAL,
                ignored -> pollOnce()
            ));
        } catch (RuntimeException rejected) {
            context.logger().warn("Recent preview poller rejected: " + rejected.getClass().getSimpleName());
            return;
        }
        if (submission.accepted()) {
            pollTask = submission.handle();
        } else {
            context.logger().warn("Recent preview poller not accepted: "
                + submission.rejectionReason().map(Object::toString).orElse("unknown"));
        }
    }

    /** Cancels the poll task and drops the poller; idempotent. */
    private void stopPoller() {
        final TaskHandle active = pollTask;
        pollTask = null;
        poller = null;
        if (active != null) {
            try {
                active.close();
            } catch (RuntimeException ignored) {
                // The runtime already cleaned up; cancelling is best effort.
            }
        }
    }

    /**
     * One poll tick: refresh the recent-file projection, then request a poll-track
     * capture when the current document changed or its file was rewritten. The
     * controller dedupes against the hook track (id + lastModified); the popup is
     * refreshed only when new content was actually stored.
     */
    private void pollOnce() {
        if (!enabled || controller == null || poller == null) return;
        final List<RecentFileSummary> files;
        try {
            files = controller.refresh().toCompletableFuture().join();
        } catch (RuntimeException failure) {
            context.logger().warn("Recent preview poll refresh failed: "
                + failure.getClass().getSimpleName());
            return;
        }
        final Optional<RecentFileId> target = poller.sample(files);
        if (target.isEmpty()) return;
        final RecentFileId id = target.orElseThrow();
        controller.pollCapture(id).whenComplete((result, failure) -> {
            if (!enabled) return;
            if (failure != null) {
                renderer.captureFailed(id);
                context.logger().warn("Recent preview poll capture failed: "
                    + failure.getClass().getSimpleName());
                refreshPopup();
            } else if (result == PreviewCacheWriteResult.STORED) {
                renderer.captureStored(id);
                refreshPopup();
            } else if (result == PreviewCacheWriteResult.RECENT_FILE_UNAVAILABLE) {
                renderer.captureFailed(id);
                refreshPopup();
            } else if (result != PreviewCacheWriteResult.DISABLED) {
                renderer.captureFailed(id);
                context.logger().warn("Recent preview poll capture not stored: " + result);
                refreshPopup();
            }
        });
    }

    private void doCapture(final RecentFileId id) {
        if (!enabled) return;
        controller.capture(id).whenComplete((result, failure) -> {
            if (!enabled) return;
            if (failure != null) {
                renderer.captureFailed(id);
                context.logger().warn("Recent preview capture failed: " + failure.getClass().getSimpleName());
                refreshPopup();
                return;
            }
            if (result == PreviewCacheWriteResult.STORED) {
                renderer.captureStored(id);
                refreshPopup();
            } else if (result == PreviewCacheWriteResult.RECENT_FILE_UNAVAILABLE) {
                renderer.captureFailed(id);
                refreshPopup();
            } else if (result != PreviewCacheWriteResult.DISABLED) {
                renderer.captureFailed(id);
                context.logger().warn("Recent preview capture not stored: " + result);
                refreshPopup();
            }
        });
    }

    private static String loadingText(final PluginContext context) {
        try {
            final String localized = context.localization().text(LOADING_TEXT_KEY);
            return localized == null || localized.isBlank() ? DEFAULT_LOADING_TEXT : localized;
        } catch (RuntimeException unavailable) {
            return DEFAULT_LOADING_TEXT;
        }
    }

    private void refreshPopup() {
        final RecentPreviewContributionService service = context.recentPreviews();
        try {
            service.refresh();
        } catch (RuntimeException unavailable) {
            // Safe mode / missing bridge: nothing to refresh, never crash the EDT.
            context.logger().warn("Recent preview popup refresh unavailable: "
                + unavailable.getClass().getSimpleName());
        }
    }

    private void contributeRenderer() {
        final RecentPreviewRenderer active = renderer;
        if (active == null) return;
        try {
            contribution = context.disposableScope().register(
                context.recentPreviews().contribute(active)
            );
        } catch (RuntimeException unavailable) {
            // Safe mode / missing bridge: no popup contribution, capture still works.
            context.logger().warn("Recent preview popup contribution unavailable: "
                + unavailable.getClass().getSimpleName());
        }
    }

    private void closeContribution() {
        final Registration active = contribution;
        contribution = null;
        if (active != null) {
            try {
                active.close();
            } catch (RuntimeException ignored) {
                // The runtime already cleaned up; closing is best effort.
            }
        }
    }

    private void requireContext() {
        if (context == null) {
            throw new IllegalStateException("Recent Preview plugin must be initialized before enable.");
        }
    }
}

package dev.turboism.plugin.clipmaskviewer;

import dev.turboism.plugin.clipmaskviewer.b1.domain.ClipMaskViewerState;
import dev.turboism.plugin.clipmaskviewer.ui.ClipMaskViewerWindow;
import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.cubism.event.SelectionChangedEvent;
import dev.turboism.sdk.cubism.service.clipmask.CubismClipMaskService.ClipMaskRecord;
import dev.turboism.sdk.event.SubscribeEvent;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.plugin.TurboismPlugin;
import dev.turboism.sdk.task.PluginTaskKind;
import dev.turboism.sdk.task.PluginTaskPriority;
import dev.turboism.sdk.task.PluginTaskRequest;
import dev.turboism.sdk.task.TaskHandle;
import dev.turboism.sdk.task.TaskId;
import dev.turboism.sdk.task.TaskOutcome;
import dev.turboism.sdk.task.TaskOutcomeStatus;
import dev.turboism.sdk.task.TaskSubmission;
import dev.turboism.sdk.ui.CollapsibleSectionContribution;
import dev.turboism.sdk.ui.EmbeddedPanelId;
import dev.turboism.sdk.ui.PanelView;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 剪贴蒙版检查器（clipmask-viewer）官方插件。
 *
 * <p>窗口先显示加载提示；宿主快照读取在随后一个 EDT 回合完成，纯 Java 索引、查重和统计
 * 则交给插件任务线程。最终不可变结果只在 EDT 应用，因此大型关系集不会把分析工作压在
 * Cubism 的事件处理回合里。</p>
 */
public final class ClipMaskViewerPlugin implements TurboismPlugin {

    static final String OPEN_VIEWER_ACTION_ID = "clipmask-viewer.open.viewer";
    static final String SECTION_ID = "clipmask-viewer.section";
    static final String BUTTON_ID = "clipmask-viewer.open";
    static final String TURBOISM_PANEL_ID = "turboism.panel.main";
    static final String MENU_ROOT = "Turboism";
    static final int SECTION_ORDER = 100;

    private final UiAccess ui;
    private final Object lifecycleLock = new Object();
    private final AtomicReference<WindowView> window = new AtomicReference<>();
    private final AtomicReference<TaskHandle> currentRefresh = new AtomicReference<>();
    private PluginContext context;
    private PluginLocalization localization;
    private PluginLogger logger;
    private boolean initialized;
    private boolean enabled;
    private long generation;

    public ClipMaskViewerPlugin() {
        this(new SwingUiAccess());
    }

    ClipMaskViewerPlugin(final UiAccess ui) {
        this.ui = Objects.requireNonNull(ui, "ui");
    }

    @Override
    public void init(final PluginContext context) {
        this.context = Objects.requireNonNull(context, "context");
        this.localization = context.localization();
        this.logger = context.logger();
        context.tasks();
        synchronized (lifecycleLock) {
            initialized = true;
        }
        context.disposableScope().register(this::cancelCurrentRefresh);
        context.disposableScope().register(this::disposeWindow);
        context.logger().info("ClipMaskViewerPlugin initialized");
    }

    @Override
    public void enable() {
        synchronized (lifecycleLock) {
            if (!initialized) {
                throw new IllegalStateException("ClipMaskViewerPlugin must be initialized before enable.");
            }
            enabled = true;
            generation++;
        }
        try {
            registerAction();
            context.disposableScope().register(contributeSection());
            context.disposableScope().register(contributeMenu());
        } catch (RuntimeException failure) {
            closeScopeQuietly();
            throw failure;
        }
        logger.info(
            "ClipMaskViewerPlugin enabled: open-viewer action, Turboism tab section and menu enrolled in disposable scope"
        );
    }

    /** Applies the latest detached Cubism selection to the open viewer window. */
    @SubscribeEvent
    public void onSelectionChanged(final SelectionChangedEvent event) {
        final WindowView current = window.get();
        if (current != null) {
            current.applySelection(event.currentSelection());
        }
    }

    @Override
    public void disable() {
        deactivate();
    }

    @Override
    public void shutdown() {
        deactivate();
    }

    private void deactivate() {
        synchronized (lifecycleLock) {
            if (!initialized) {
                return;
            }
            enabled = false;
            generation++;
        }
        cancelCurrentRefresh();
        disposeWindow();
    }

    private void registerAction() {
        final Registration registration = context.actions().register(
            OPEN_VIEWER_ACTION_ID,
            new ActionRegistry.Action() {
                @Override
                public String id() {
                    return OPEN_VIEWER_ACTION_ID;
                }

                @Override
                public String label() {
                    return localization.text("button.open");
                }

                @Override
                public Consumer<ActionRegistry.ActionContext> handler() {
                    return ignored -> openViewer();
                }
            }
        );
        context.disposableScope().register(registration);
    }

    private Registration contributeSection() {
        final PanelView content = PanelView.column(
            PanelView.button(BUTTON_ID, localization.text("button.open"), OPEN_VIEWER_ACTION_ID)
        );
        return context.uiHost().contributeCollapsibleSection(new CollapsibleSectionContribution(
            EmbeddedPanelId.of(TURBOISM_PANEL_ID),
            SECTION_ID,
            localization.text("section.title"),
            SECTION_ORDER,
            true,
            content
        ));
    }

    private Registration contributeMenu() {
        return context.menus().contribute(new MenuRegistry.MenuContribution() {
            @Override
            public String menuPath() {
                return MENU_ROOT + "/" + localization.text("menu.label");
            }

            @Override
            public String actionId() {
                return OPEN_VIEWER_ACTION_ID;
            }

            @Override
            public int order() {
                return SECTION_ORDER;
            }
        });
    }

    /** action 入口：打开或前置查看器窗口（EDT 调度；headless 下安全跳过）。 */
    private void openViewer() {
        final long openGeneration;
        synchronized (lifecycleLock) {
            if (!enabled) {
                return;
            }
            openGeneration = generation;
        }
        if (ui.isHeadless()) {
            logger.warn("Clip Mask Viewer cannot open because the JVM is headless");
            return;
        }
        ui.invokeLater(() -> showWindow(openGeneration));
    }

    private void showWindow(final long openGeneration) {
        final WindowView existing = window.get();
        if (existing != null) {
            existing.showAndFront();
            requestRefresh(existing);
            return;
        }
        final WindowView[] created = new WindowView[1];
        created[0] = ui.create(
            localization,
            context,
            () -> requestRefresh(created[0]),
            () -> windowClosed(created[0])
        );
        final WindowView view = created[0];
        synchronized (lifecycleLock) {
            if (!enabled || generation != openGeneration || !window.compareAndSet(null, view)) {
                view.dispose();
                return;
            }
        }
        view.showAndFront();
        requestRefresh(view);
    }

    private void requestRefresh(final WindowView expectedView) {
        final long requestGeneration;
        synchronized (lifecycleLock) {
            if (!enabled || expectedView == null || window.get() != expectedView) {
                return;
            }
            requestGeneration = ++generation;
        }
        cancelCurrentRefresh();
        expectedView.showLoading();
        // Yield the current EDT event so the modeless window can paint its loading state before
        // the exact-host snapshot is captured on the required host/UI thread.
        ui.invokeLater(() -> captureRecords(requestGeneration, expectedView));
    }

    private void captureRecords(
        final long requestGeneration,
        final WindowView expectedView
    ) {
        if (!isCurrent(requestGeneration, expectedView)) {
            return;
        }
        final List<ClipMaskRecord> records;
        try {
            records = List.copyOf(context.cubismClipMasks().collectClipMaskRecords());
        } catch (RuntimeException failure) {
            logger.warn("Clip Mask Viewer host snapshot failed safely: " + failure.getMessage());
            applyFailure(requestGeneration, expectedView);
            return;
        }
        submitAnalysis(requestGeneration, expectedView, records);
    }

    private void submitAnalysis(
        final long requestGeneration,
        final WindowView expectedView,
        final List<ClipMaskRecord> records
    ) {
        final AtomicReference<ClipMaskViewerState.Snapshot> result = new AtomicReference<>();
        final TaskSubmission submission = context.tasks().submit(new PluginTaskRequest(
            new TaskId("clipmask-viewer-refresh-" + requestGeneration),
            PluginTaskKind.COMPUTE,
            PluginTaskPriority.NORMAL,
            token -> {
                token.checkCanceled();
                result.set(ClipMaskViewerState.analyze(records));
                token.checkCanceled();
            }
        ));
        if (!submission.accepted()) {
            logger.warn("Clip Mask Viewer analysis rejected safely: "
                + submission.rejectionReason().map(Enum::name).orElse("UNKNOWN"));
            applyFailure(requestGeneration, expectedView);
            return;
        }
        final TaskHandle handle = submission.handle();
        if (!isCurrent(requestGeneration, expectedView)) {
            handle.cancel();
            return;
        }
        currentRefresh.set(handle);
        handle.completion().whenComplete((outcome, failure) -> ui.invokeLater(
            () -> applyAnalysis(requestGeneration, expectedView, handle, result.get(), outcome, failure)
        ));
    }

    private void applyAnalysis(
        final long requestGeneration,
        final WindowView expectedView,
        final TaskHandle handle,
        final ClipMaskViewerState.Snapshot snapshot,
        final TaskOutcome outcome,
        final Throwable failure
    ) {
        currentRefresh.compareAndSet(handle, null);
        if (!isCurrent(requestGeneration, expectedView)) {
            return;
        }
        if (failure != null || outcome == null || outcome.status() != TaskOutcomeStatus.SUCCEEDED
            || snapshot == null) {
            logger.warn("Clip Mask Viewer analysis failed safely");
            expectedView.showUnavailable();
            return;
        }
        expectedView.showSnapshot(snapshot);
        logger.info("Clip Mask Viewer refreshed: records=" + snapshot.records().size()
            + ", masks=" + snapshot.countUniqueMasks());
    }

    private void applyFailure(final long requestGeneration, final WindowView expectedView) {
        if (isCurrent(requestGeneration, expectedView)) {
            expectedView.showUnavailable();
        }
    }

    private boolean isCurrent(final long expectedGeneration, final WindowView expectedView) {
        synchronized (lifecycleLock) {
            return enabled && generation == expectedGeneration && window.get() == expectedView;
        }
    }

    private void windowClosed(final WindowView expectedView) {
        if (!window.compareAndSet(expectedView, null)) {
            return;
        }
        synchronized (lifecycleLock) {
            generation++;
        }
        cancelCurrentRefresh();
    }

    private void cancelCurrentRefresh() {
        final TaskHandle active = currentRefresh.getAndSet(null);
        if (active != null) {
            active.cancel();
        }
    }

    private void disposeWindow() {
        final Runnable dispose = () -> {
            final WindowView view = window.getAndSet(null);
            if (view != null) {
                view.dispose();
            }
        };
        try {
            ui.invokeAndWait(dispose);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            logger.warn("Clip Mask Viewer EDT disposal interrupted");
        } catch (InvocationTargetException | RuntimeException exception) {
            logger.warn("Clip Mask Viewer EDT disposal failed safely");
        }
    }

    private void closeScopeQuietly() {
        try {
            context.disposableScope().close();
        } catch (Exception closeFailure) {
            logger.warn("ClipMaskViewerPlugin enable rollback close failed: " + closeFailure.getMessage());
        }
    }

    public interface UiAccess {
        boolean isHeadless();

        void invokeLater(Runnable action);

        void invokeAndWait(Runnable action) throws InterruptedException, InvocationTargetException;

        WindowView create(
            PluginLocalization localization,
            PluginContext context,
            Runnable refreshAction,
            Runnable onClosed
        );
    }

    public interface WindowView {
        void showAndFront();

        void showLoading();

        void showSnapshot(ClipMaskViewerState.Snapshot snapshot);

        void showUnavailable();

        void applySelection(dev.turboism.sdk.cubism.service.query.SelectionSummary summary);

        void dispose();
    }

    private static final class SwingUiAccess implements UiAccess {
        @Override
        public boolean isHeadless() {
            return GraphicsEnvironment.isHeadless();
        }

        @Override
        public void invokeLater(final Runnable action) {
            SwingUtilities.invokeLater(action);
        }

        @Override
        public void invokeAndWait(final Runnable action)
            throws InterruptedException, InvocationTargetException {
            if (SwingUtilities.isEventDispatchThread()) {
                action.run();
            } else {
                SwingUtilities.invokeAndWait(action);
            }
        }

        @Override
        public WindowView create(
            final PluginLocalization localization,
            final PluginContext context,
            final Runnable refreshAction,
            final Runnable onClosed
        ) {
            return new ClipMaskViewerWindow(localization, context, refreshAction, onClosed);
        }
    }
}

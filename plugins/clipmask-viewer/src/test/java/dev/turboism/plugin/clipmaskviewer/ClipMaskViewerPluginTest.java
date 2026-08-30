package dev.turboism.plugin.clipmaskviewer;

import dev.turboism.plugin.clipmaskviewer.b1.domain.ClipMaskViewerState;
import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.cubism.service.clipmask.CubismClipMaskService;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.permission.PluginPermission;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.BoundingBoxOverlayButton;
import dev.turboism.sdk.ui.CollapsibleSectionContribution;
import dev.turboism.sdk.ui.context.ContextSourceSnapshot;
import dev.turboism.sdk.ui.DialogRequest;
import dev.turboism.sdk.ui.EmbeddedPanelContribution;
import dev.turboism.sdk.ui.EmbeddedPanelId;
import dev.turboism.sdk.ui.FileChooserRequest;
import dev.turboism.sdk.ui.OverlayContribution;
import dev.turboism.sdk.ui.PanelView;
import dev.turboism.sdk.ui.StatusNotification;
import dev.turboism.sdk.ui.UiHostCapabilityService;
import dev.turboism.sdk.ui.ViewportSnapshot;
import dev.turboism.sdk.ui.context.ContextMenuRegistry;
import dev.turboism.sdk.ui.toolbar.MainToolbarRegistry;
import dev.turboism.sdk.ui.toolbar.PaletteToolbarRegistry;
import dev.turboism.sdk.task.PluginTaskRequest;
import dev.turboism.sdk.task.PluginTaskScheduler;
import dev.turboism.sdk.task.TaskFailure;
import dev.turboism.sdk.task.TaskHandle;
import dev.turboism.sdk.task.TaskId;
import dev.turboism.sdk.task.TaskOutcome;
import dev.turboism.sdk.task.TaskOutcomeStatus;
import dev.turboism.sdk.task.TaskProgress;
import dev.turboism.sdk.task.TaskRunOutcome;
import dev.turboism.sdk.task.TaskRunOutcomeStatus;
import dev.turboism.sdk.task.TaskSubmission;
import dev.turboism.sdk.task.TaskSubmissionStatus;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClipMaskViewerPluginTest {

    @Test
    void enableRegistersActionSectionAndMenu() {
        final Fixture fixture = new Fixture();
        fixture.plugin.init(fixture.context);
        fixture.plugin.enable();

        assertEquals(
            List.of(ClipMaskViewerPlugin.OPEN_VIEWER_ACTION_ID),
            fixture.actions.ids()
        );
        assertEquals(
            List.of(new CollapsibleSectionContribution(
                EmbeddedPanelId.of("turboism.panel.main"),
                "clipmask-viewer.section",
                "section.title",
                100,
                true,
                PanelView.column(PanelView.button(
                    "clipmask-viewer.open", "button.open", "clipmask-viewer.open.viewer"))
            )),
            fixture.uiHost.sections
        );
        assertEquals(1, fixture.menus.contributions.size());
        assertEquals("Turboism/menu.label", fixture.menus.contributions.get(0).menuPath());
        assertEquals(ClipMaskViewerPlugin.OPEN_VIEWER_ACTION_ID,
            fixture.menus.contributions.get(0).actionId());
    }

    @Test
    void enableBeforeInitFailsClosed() {
        final Fixture fixture = new Fixture();

        assertThrows(IllegalStateException.class, () -> fixture.plugin.enable());
    }

    @Test
    void openViewerActionCreatesAndShowsWindowOnce() {
        final Fixture fixture = new Fixture();
        fixture.plugin.init(fixture.context);
        fixture.plugin.enable();

        fixture.actions.byId(ClipMaskViewerPlugin.OPEN_VIEWER_ACTION_ID)
            .handler()
            .accept(new ActionRegistry.ActionContext() { });
        fixture.ui.runNext();

        assertEquals(1, fixture.ui.createCount);
        assertEquals(1, fixture.ui.view.showCount);
        assertEquals(1, fixture.ui.view.loadingCount);
        assertEquals(0, fixture.clipMasks.readCount);

        fixture.ui.runNext();

        assertEquals(1, fixture.clipMasks.readCount);
        assertEquals(1, fixture.tasks.pendingCount());
        assertEquals(0, fixture.ui.view.snapshotCount);

        fixture.tasks.runNext();
        fixture.ui.runNext();

        assertEquals(1, fixture.ui.view.snapshotCount);
        assertEquals(1, fixture.ui.view.snapshot.records().size());
    }

    @Test
    void staleAnalysisDoesNotReplaceAClosedOrNewerWindow() {
        final Fixture fixture = new Fixture();
        fixture.plugin.init(fixture.context);
        fixture.plugin.enable();
        fixture.actions.byId(ClipMaskViewerPlugin.OPEN_VIEWER_ACTION_ID)
            .handler().accept(new ActionRegistry.ActionContext() { });
        fixture.ui.runNext();
        fixture.ui.runNext();
        final FakeWindowView first = fixture.ui.view;

        first.simulateClose();
        fixture.tasks.runNext();
        fixture.ui.runNext();

        assertEquals(0, first.snapshotCount);
    }

    @Test
    void secondOpenFrontsExistingWindowWithoutRecreating() {
        final Fixture fixture = new Fixture();
        fixture.plugin.init(fixture.context);
        fixture.plugin.enable();
        fixture.openViewer();

        fixture.openViewer();

        assertEquals(1, fixture.ui.createCount);
        assertEquals(2, fixture.ui.view.showCount);
    }

    @Test
    void closedWindowCanBeReopenedByAnotherClick() {
        final Fixture fixture = new Fixture();
        fixture.plugin.init(fixture.context);
        fixture.plugin.enable();
        fixture.openViewer();

        fixture.ui.view.simulateClose();

        fixture.openViewer();

        assertEquals(2, fixture.ui.createCount);
        assertFalse(fixture.ui.firstView().disposed);
    }

    @Test
    void disableDisposesWindowAndScopeCloseReleasesRegistrations() throws Exception {
        final Fixture fixture = new Fixture();
        fixture.plugin.init(fixture.context);
        fixture.plugin.enable();
        fixture.openViewer();

        fixture.plugin.disable();

        assertTrue(fixture.ui.view.disposed);
        assertEquals(1, fixture.ui.invokeAndWaitCount);

        fixture.scope.close();

        assertTrue(fixture.actions.ids().isEmpty());
        assertTrue(fixture.uiHost.sections.isEmpty());
        assertTrue(fixture.menus.contributions.isEmpty());
    }

    @Test
    void headlessEnableSkipsWindowCreation() {
        final Fixture fixture = new Fixture();
        fixture.ui.headless = true;
        fixture.plugin.init(fixture.context);
        fixture.plugin.enable();

        fixture.openViewer();

        assertEquals(0, fixture.ui.createCount);
        assertTrue(fixture.logger.warns.stream()
            .anyMatch(message -> message.contains("headless")));
    }

    private static final class Fixture {
        private final FakeUi ui = new FakeUi();
        private final RecordingActionRegistry actions = new RecordingActionRegistry();
        private final RecordingMenuRegistry menus = new RecordingMenuRegistry();
        private final RecordingUiHost uiHost = new RecordingUiHost();
        private final RecordingLogger logger = new RecordingLogger();
        private final FakeTaskScheduler tasks = new FakeTaskScheduler();
        private final FakeClipMasks clipMasks = new FakeClipMasks();
        private final DisposableScope scope = new DisposableScope();
        private final PluginLocalization localization = new FakeLocalization();
        private final PluginContext context = (PluginContext) Proxy.newProxyInstance(
            PluginContext.class.getClassLoader(),
            new Class<?>[] { PluginContext.class },
            (proxy, method, args) -> switch (method.getName()) {
                case "localization" -> localization;
                case "logger" -> logger;
                case "disposableScope" -> scope;
                case "actions" -> actions;
                case "menus" -> menus;
                case "uiHost" -> uiHost;
                case "tasks" -> tasks;
                case "cubismClipMasks" -> clipMasks;
                case "permissions" -> List.<PluginPermission>of();
                case "toString" -> "FakePluginContext";
                default -> throw new UnsupportedOperationException(method.getName());
            }
        );
        private final ClipMaskViewerPlugin plugin = new ClipMaskViewerPlugin(ui);

        private void openViewer() {
            actions.byId(ClipMaskViewerPlugin.OPEN_VIEWER_ACTION_ID)
                .handler()
                .accept(new ActionRegistry.ActionContext() { });
            ui.runNext();
            ui.runNext();
            tasks.runNext();
            ui.runNext();
        }
    }

    private static final class FakeUi implements ClipMaskViewerPlugin.UiAccess {
        private final Queue<Runnable> queued = new ArrayDeque<>();
        private FakeWindowView view = new FakeWindowView();
        private boolean headless;
        private int createCount;
        private int invokeAndWaitCount;

        @Override
        public boolean isHeadless() {
            return headless;
        }

        @Override
        public void invokeLater(final Runnable action) {
            queued.add(action);
        }

        @Override
        public void invokeAndWait(final Runnable action)
            throws InterruptedException, InvocationTargetException {
            invokeAndWaitCount++;
            action.run();
        }

        @Override
        public ClipMaskViewerPlugin.WindowView create(
            final PluginLocalization localization,
            final PluginContext context,
            final Runnable refreshAction,
            final Runnable onClosed
        ) {
            createCount++;
            view.refreshAction = refreshAction;
            view.onClosed = onClosed;
            return view;
        }

        private void runNext() {
            final Runnable action = queued.poll();
            if (action != null) {
                action.run();
            }
        }

        private FakeWindowView firstView() {
            return view;
        }
    }

    private static final class FakeWindowView implements ClipMaskViewerPlugin.WindowView {
        private Runnable refreshAction;
        private Runnable onClosed;
        private boolean disposed;
        private int showCount;
        private int loadingCount;
        private int snapshotCount;
        private int unavailableCount;
        private ClipMaskViewerState.Snapshot snapshot;

        @Override
        public void showAndFront() {
            showCount++;
        }

        @Override
        public void showLoading() {
            loadingCount++;
        }

        @Override
        public void showSnapshot(final ClipMaskViewerState.Snapshot snapshot) {
            snapshotCount++;
            this.snapshot = snapshot;
        }

        @Override
        public void showUnavailable() {
            unavailableCount++;
        }

        @Override
        public void applySelection(
            final dev.turboism.sdk.cubism.service.query.SelectionSummary summary
        ) {
        }

        @Override
        public void dispose() {
            disposed = true;
        }

        private void simulateClose() {
            onClosed.run();
        }
    }

    private static final class FakeClipMasks implements CubismClipMaskService {
        private int readCount;

        @Override
        public List<ClipMaskRecord> collectClipMaskRecords() {
            readCount++;
            return List.of(new ClipMaskRecord("user-1", "ArtMesh1", "User 1", false,
                List.of("mask-1")));
        }
    }

    private static final class FakeTaskScheduler implements PluginTaskScheduler {
        private final Queue<FakeTaskHandle> pending = new ArrayDeque<>();

        @Override
        public TaskSubmission submit(final PluginTaskRequest request) {
            final FakeTaskHandle handle = new FakeTaskHandle(request);
            pending.add(handle);
            return new TaskSubmission(TaskSubmissionStatus.ACCEPTED, handle, Optional.empty());
        }

        @Override
        public TaskSubmission scheduleWithFixedDelay(
            final dev.turboism.sdk.task.FixedDelayTaskRequest request
        ) {
            throw new UnsupportedOperationException("unused");
        }

        int pendingCount() {
            return pending.size();
        }

        void runNext() {
            final FakeTaskHandle handle = pending.poll();
            if (handle != null) {
                handle.run();
            }
        }
    }

    private static final class FakeTaskHandle implements TaskHandle {
        private final PluginTaskRequest request;
        private final CompletableFuture<TaskOutcome> completion = new CompletableFuture<>();
        private boolean canceled;

        FakeTaskHandle(final PluginTaskRequest request) {
            this.request = request;
        }

        void run() {
            if (canceled) {
                completion.complete(canceled());
                return;
            }
            try {
                request.action().run(new dev.turboism.sdk.plugin.CancellationToken() {
                    @Override public boolean isCancellationRequested() { return canceled; }
                    @Override public void checkCanceled() {
                        if (canceled) throw new dev.turboism.sdk.plugin.TaskCanceledException();
                    }
                });
                completion.complete(success());
            } catch (dev.turboism.sdk.plugin.TaskCanceledException canceledFailure) {
                completion.complete(canceled());
            } catch (Exception failure) {
                completion.complete(failed(failure));
            }
        }

        @Override public TaskId id() { return request.id(); }
        @Override public TaskProgress progress() { return new TaskProgress(0, Optional.empty()); }
        @Override public boolean cancel() {
            if (completion.isDone()) return false;
            canceled = true;
            completion.complete(canceled());
            return true;
        }
        @Override public java.util.concurrent.CompletionStage<TaskOutcome> completion() {
            return completion;
        }
        @Override public void close() { cancel(); }

        private TaskOutcome success() {
            return new TaskOutcome(id(), TaskOutcomeStatus.SUCCEEDED, 1,
                Optional.of(new TaskRunOutcome(1, TaskRunOutcomeStatus.SUCCEEDED, Optional.empty())),
                Optional.empty());
        }

        private TaskOutcome canceled() {
            return new TaskOutcome(id(), TaskOutcomeStatus.CANCELED, 0, Optional.empty(), Optional.empty());
        }

        private TaskOutcome failed(final Exception failure) {
            final String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            final TaskFailure detail = new TaskFailure("TEST_FAILURE", message);
            return new TaskOutcome(id(), TaskOutcomeStatus.FAILED, 1,
                Optional.of(new TaskRunOutcome(1, TaskRunOutcomeStatus.FAILED, Optional.of(detail))),
                Optional.of(detail));
        }
    }

    private static final class RecordingActionRegistry implements ActionRegistry {
        private final List<Action> actions = new ArrayList<>();

        @Override
        public Registration register(final String id, final Action action) {
            actions.add(action);
            return new Registration() {
                @Override
                public void close() {
                    actions.remove(action);
                }
            };
        }

        private List<String> ids() {
            return actions.stream().map(Action::id).toList();
        }

        private Action byId(final String id) {
            return actions.stream().filter(action -> id.equals(action.id())).findFirst().orElseThrow();
        }
    }

    private static final class RecordingMenuRegistry implements MenuRegistry {
        private final List<MenuContribution> contributions = new ArrayList<>();

        @Override
        public Registration contribute(final MenuContribution contribution) {
            contributions.add(contribution);
            return new Registration() {
                @Override
                public void close() {
                    contributions.remove(contribution);
                }
            };
        }
    }

    private static final class RecordingUiHost implements UiHostCapabilityService {
        private final List<CollapsibleSectionContribution> sections = new ArrayList<>();
        private final List<StatusNotification> notifications = new ArrayList<>();

        @Override
        public Registration contributeCollapsibleSection(final CollapsibleSectionContribution contribution) {
            sections.add(contribution);
            return new Registration() {
                @Override
                public void close() {
                    sections.remove(contribution);
                }
            };
        }

        @Override
        public Registration notifyStatus(final StatusNotification notification) {
            notifications.add(notification);
            return new Registration() {
                @Override
                public void close() {
                    notifications.remove(notification);
                }
            };
        }

        @Override public Registration contributeOverlay(OverlayContribution c) { throw unavailable(); }
        @Override public Registration contributeBoundingBoxOverlayButton(BoundingBoxOverlayButton c) { throw unavailable(); }
        @Override public ContextSourceSnapshot contextSource() { throw unavailable(); }
        @Override public ViewportSnapshot viewport() { throw unavailable(); }
        @Override public Registration openDialog(DialogRequest r) { throw unavailable(); }
        @Override public boolean confirmDialog(DialogRequest r) { throw unavailable(); }
        @Override public Registration contributeEmbeddedPanel(EmbeddedPanelContribution c) { throw unavailable(); }
        @Override public java.util.Optional<String> requestFile(FileChooserRequest r) { throw unavailable(); }
        @Override public Registration contributeContextMenu(ContextMenuRegistry.ContextMenuContribution c) { throw unavailable(); }
        @Override public Registration contributeMainToolbar(MainToolbarRegistry.MainToolbarContribution c) { throw unavailable(); }
        @Override public Registration contributePaletteToolbar(PaletteToolbarRegistry.PaletteToolbarContribution c) { throw unavailable(); }

        private UnsupportedOperationException unavailable() {
            return new UnsupportedOperationException("unused in test");
        }
    }

    private static final class FakeLocalization implements PluginLocalization {
        @Override
        public Locale locale() {
            return Locale.ENGLISH;
        }

        @Override
        public String text(final String key) {
            return key;
        }

        @Override
        public String format(final String key, final Object... arguments) {
            return key;
        }

        @Override
        public boolean contains(final String key) {
            return true;
        }
    }

    private static final class RecordingLogger implements PluginLogger {
        private final List<String> infos = new ArrayList<>();
        private final List<String> warns = new ArrayList<>();

        @Override
        public void debug(String message) { }
        @Override
        public void info(String message) { infos.add(message); }
        @Override
        public void warn(String message) { warns.add(message); }
        @Override
        public void error(String message) { }
        @Override
        public void error(String message, Throwable throwable) { }
    }
}

package dev.turboism.plugin.projectinspector;

import dev.turboism.sdk.cubism.ProjectSnapshot;
import dev.turboism.sdk.cubism.WorkspaceSnapshot;
import dev.turboism.sdk.hostread.AsyncHostReadError;
import dev.turboism.sdk.hostread.AsyncHostReadErrorCode;
import dev.turboism.sdk.hostread.AsyncHostReadHandle;
import dev.turboism.sdk.hostread.AsyncHostReadIntent;
import dev.turboism.sdk.hostread.AsyncHostReadRequest;
import dev.turboism.sdk.hostread.AsyncHostReadResult;
import dev.turboism.sdk.hostread.AsyncHostReadService;
import dev.turboism.sdk.hostread.AsyncHostReadStatus;
import dev.turboism.sdk.hostread.AsyncHostReadSubmission;
import dev.turboism.sdk.hostread.AsyncHostReadSubmissionStatus;
import dev.turboism.sdk.hostread.ProjectWorkspaceSnapshot;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import org.junit.jupiter.api.Test;

import javax.swing.WindowConstants;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectInspectorLifecycleTest {

    @Test
    void enableBeforeInitFailsClosed() {
        final ProjectInspectorPlugin plugin = new ProjectInspectorPlugin(new FakeUi());

        assertThrows(IllegalStateException.class, plugin::enable);
    }

    @Test
    void oldSuccessFailureAndCoalescedCallbacksCannotOverwriteLatestSnapshot() {
        final Fixture fixture = new Fixture();
        final ControlledHandle old = fixture.host.accepted();
        fixture.plugin.enable();
        fixture.ui.runNext();

        final ControlledHandle latest = fixture.host.accepted();
        fixture.ui.view.refresh();
        final ProjectWorkspaceSnapshot latestSnapshot = snapshot("latest", "Latest", "workspace-latest");
        latest.completeSuccess(latestSnapshot);
        fixture.ui.runNext();
        assertSame(latestSnapshot, fixture.ui.view.snapshot);

        final ControlledHandle coalesced = fixture.host.coalesced();
        fixture.ui.view.refresh();
        final ControlledHandle afterCoalesced = fixture.host.accepted();
        fixture.ui.view.refresh();
        final ProjectWorkspaceSnapshot finalSnapshot = snapshot("final", "Final", "workspace-final");
        afterCoalesced.completeSuccess(finalSnapshot);
        fixture.ui.runNext();
        coalesced.completeSuccess(snapshot("coalesced", "Coalesced", "workspace-coalesced"));
        fixture.ui.runNext();
        assertSame(finalSnapshot, fixture.ui.view.snapshot);

        old.completeSuccess(snapshot("old", "Old", "workspace-old"));
        fixture.ui.runNext();
        assertSame(finalSnapshot, fixture.ui.view.snapshot);

        final ControlledHandle failed = fixture.host.accepted();
        fixture.ui.view.refresh();
        final ControlledHandle afterFailure = fixture.host.accepted();
        fixture.ui.view.refresh();
        final ProjectWorkspaceSnapshot afterFailureSnapshot = snapshot("new", "New", "workspace-new");
        afterFailure.completeSuccess(afterFailureSnapshot);
        fixture.ui.runNext();
        failed.completeFailure(AsyncHostReadErrorCode.RUNTIME_FAILURE);
        fixture.ui.runNext();
        assertSame(afterFailureSnapshot, fixture.ui.view.snapshot);
        assertEquals(0, fixture.ui.view.unavailableCount);
    }

    @Test
    void rejectedRefreshCannotOverwriteAFollowingSuccessfulRefresh() {
        final Fixture fixture = new Fixture();
        fixture.host.rejected(AsyncHostReadErrorCode.BACKPRESSURE);
        fixture.plugin.enable();
        fixture.ui.runNext();

        final ControlledHandle latest = fixture.host.accepted();
        fixture.ui.view.refresh();
        final ProjectWorkspaceSnapshot snapshot = snapshot("latest", "Latest", "workspace");
        latest.completeSuccess(snapshot);
        fixture.ui.runNext();
        fixture.ui.runNext();

        assertSame(snapshot, fixture.ui.view.snapshot);
        assertEquals(0, fixture.ui.view.unavailableCount);
        assertTrue(fixture.logger.warns.stream().anyMatch(message -> message.endsWith("BACKPRESSURE")));
    }

    @Test
    void acceptedAndCoalescedRefreshesMayShareOneHandleWithoutClearingTheCurrentReadEarly() {
        final Fixture fixture = new Fixture();
        final ControlledHandle shared = fixture.host.accepted();
        fixture.host.coalesceWith(shared);
        fixture.plugin.enable();
        fixture.ui.runNext();
        fixture.ui.view.refresh();

        fixture.plugin.disable();

        assertTrue(shared.canceled);
    }

    @Test
    void disableShutdownAndReenableInvalidateOldCallbacks() {
        final Fixture fixture = new Fixture();
        final ControlledHandle beforeDisable = fixture.host.accepted();
        fixture.plugin.enable();
        fixture.ui.runNext();
        fixture.plugin.disable();
        assertTrue(beforeDisable.canceled);
        assertTrue(fixture.ui.view.disposed);

        final ControlledHandle afterReenable = fixture.host.accepted();
        fixture.ui.replaceView();
        fixture.plugin.enable();
        fixture.ui.runNext();
        beforeDisable.completeSuccess(snapshot("disabled", "Disabled", "disabled"));
        fixture.ui.runNext();
        assertEquals(null, fixture.ui.view.snapshot);

        final ProjectWorkspaceSnapshot current = snapshot("current", "Current", "current");
        afterReenable.completeSuccess(current);
        fixture.ui.runNext();
        assertSame(current, fixture.ui.view.snapshot);

        final FakeView currentView = fixture.ui.view;
        final ControlledHandle beforeShutdown = fixture.host.accepted();
        fixture.ui.view.refresh();
        fixture.plugin.shutdown();
        assertTrue(beforeShutdown.canceled);
        beforeShutdown.completeFailure(AsyncHostReadErrorCode.RUNTIME_FAILURE);
        fixture.ui.runNext();
        assertSame(current, currentView.snapshot);
    }

    @Test
    void queuedShowWindowDoesNotOpenAfterDisable() {
        final Fixture fixture = new Fixture();
        fixture.plugin.enable();
        fixture.plugin.disable();
        fixture.ui.runNext();

        assertEquals(0, fixture.ui.createCount);
        assertEquals(0, fixture.host.requests.size());
    }

    @Test
    void disableBetweenSubmitAndHandleInstallCancelsTheSubmittedHandle() {
        final Fixture fixture = new Fixture();
        final ControlledHandle submitted = fixture.host.accepted();
        fixture.host.afterSubmit = fixture.plugin::disable;

        fixture.plugin.enable();
        fixture.ui.runNext();

        assertTrue(submitted.canceled);
        assertTrue(fixture.ui.view.disposed);
    }

    @Test
    void staleResultCannotClearANewerInstalledHandle() {
        final Fixture fixture = new Fixture();
        final ControlledHandle old = fixture.host.accepted();
        fixture.plugin.enable();
        fixture.ui.runNext();
        final ControlledHandle newer = fixture.host.accepted();
        fixture.ui.view.refresh();

        old.completeSuccess(snapshot("old", "Old", "old"));
        fixture.ui.runNext();
        fixture.plugin.disable();

        assertTrue(newer.canceled);
    }

    @Test
    void disposeRunsSynchronouslyThroughUiAccessAndUsesHideOnClosePolicy() {
        final Fixture fixture = new Fixture();
        fixture.host.accepted();
        fixture.plugin.enable();
        fixture.ui.runNext();
        fixture.plugin.disable();

        assertEquals(1, fixture.ui.invokeAndWaitCount);
        assertTrue(fixture.ui.view.disposed);
        assertEquals(WindowConstants.HIDE_ON_CLOSE, ProjectInspectorPlugin.windowCloseOperation());
    }

    @Test
    void interruptedDisposeRestoresInterruptAndPropagatesStableFailure() {
        final Fixture fixture = new Fixture();
        fixture.ui.invokeAndWaitFailure = new InterruptedException("private interruption");

        final IllegalStateException failure = assertThrows(
            IllegalStateException.class,
            fixture.plugin::disable
        );

        assertEquals("Project Inspector window disposal failed.", failure.getMessage());
        assertTrue(Thread.currentThread().isInterrupted());
        Thread.interrupted();
    }

    @Test
    void invocationFailurePropagatesStableFailureThroughDisposableScope() {
        final Fixture fixture = new Fixture();
        fixture.ui.invokeAndWaitFailure = new InvocationTargetException(
            new IllegalStateException("private /home/user selector")
        );

        final IllegalStateException failure = assertThrows(
            IllegalStateException.class,
            fixture.scope::close
        );

        assertEquals("Project Inspector window disposal failed.", failure.getMessage());
        assertTrue(failure.getCause() instanceof InvocationTargetException);
        assertFalse(failure.getMessage().contains("/home/user"));
    }

    @Test
    void headlessEnableDoesNotCreateWindowOrSubmitRead() {
        final Fixture fixture = new Fixture();
        fixture.ui.headless = true;
        fixture.plugin.enable();

        assertEquals(0, fixture.ui.createCount);
        assertEquals(0, fixture.host.requests.size());
        assertTrue(fixture.logger.warns.stream().anyMatch(message -> message.contains("headless")));
    }

    @Test
    void appliesRealSnapshotAndShowsOnlyLocalizedUnavailableWhileLoggingCode() {
        final Fixture fixture = new Fixture();
        final ControlledHandle success = fixture.host.accepted();
        fixture.plugin.enable();
        fixture.ui.runNext();
        final ProjectWorkspaceSnapshot real = snapshot("project-id", "Real Project", "Real Workspace");
        success.completeSuccess(real);
        fixture.ui.runNext();
        assertSame(real, fixture.ui.view.snapshot);

        final ControlledHandle failure = fixture.host.accepted();
        fixture.ui.view.refresh();
        failure.completeFailure(AsyncHostReadErrorCode.CAPABILITY_UNAVAILABLE);
        fixture.ui.runNext();

        assertEquals("Unavailable", fixture.ui.view.unavailableText);
        assertFalse(fixture.ui.view.unavailableText.contains("CAPABILITY_UNAVAILABLE"));
        assertTrue(fixture.logger.warns.stream().anyMatch(message -> message.endsWith("CAPABILITY_UNAVAILABLE")));
    }

    private static ProjectWorkspaceSnapshot snapshot(
        final String projectId,
        final String projectName,
        final String workspaceName
    ) {
        return new ProjectWorkspaceSnapshot(
            Optional.of(new ProjectSnapshot(projectId, projectName, Optional.empty(), List.of())),
            Optional.of(new WorkspaceSnapshot("workspace-id", workspaceName, "workspace", List.of(projectId)))
        );
    }

    private static final class Fixture {
        private final FakeUi ui = new FakeUi();
        private final FakeHostReads host = new FakeHostReads();
        private final RecordingLogger logger = new RecordingLogger();
        private final DisposableScope scope = new DisposableScope();
        private final ProjectInspectorPlugin plugin = new ProjectInspectorPlugin(ui);

        private Fixture() {
            final PluginLocalization localization = new FakeLocalization();
            final PluginContext context = (PluginContext) Proxy.newProxyInstance(
                PluginContext.class.getClassLoader(),
                new Class<?>[] {PluginContext.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hostReads" -> host;
                    case "localization" -> localization;
                    case "logger" -> logger;
                    case "disposableScope" -> scope;
                    case "permissions" -> List.of();
                    case "toString" -> "FakePluginContext";
                    default -> null;
                }
            );
            plugin.init(context);
        }
    }

    private static final class FakeUi implements ProjectInspectorPlugin.UiAccess {
        private final Queue<Runnable> queued = new ArrayDeque<>();
        private FakeView view = new FakeView();
        private FakeView previousView;
        private boolean headless;
        private int createCount;
        private int invokeAndWaitCount;
        private Exception invokeAndWaitFailure;

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
            if (invokeAndWaitFailure instanceof InterruptedException interrupted) {
                throw interrupted;
            }
            if (invokeAndWaitFailure instanceof InvocationTargetException invocation) {
                throw invocation;
            }
            if (invokeAndWaitFailure instanceof RuntimeException runtime) {
                throw runtime;
            }
            action.run();
        }

        @Override
        public ProjectInspectorPlugin.InspectorView create(
            final PluginLocalization localization,
            final Runnable refreshAction
        ) {
            createCount++;
            view.refreshAction = refreshAction;
            view.localization = localization;
            return view;
        }

        private void runNext() {
            final Runnable action = queued.poll();
            if (action != null) {
                action.run();
            }
        }

        private void replaceView() {
            previousView = view;
            view = new FakeView();
        }
    }

    private static final class FakeView implements ProjectInspectorPlugin.InspectorView {
        private PluginLocalization localization;
        private Runnable refreshAction;
        private ProjectWorkspaceSnapshot snapshot;
        private String unavailableText;
        private int unavailableCount;
        private boolean disposed;

        private void refresh() {
            refreshAction.run();
        }

        @Override public void showAndFront() {}
        @Override public void showReading() {}
        @Override public void showSnapshot(final ProjectWorkspaceSnapshot value, final Instant refreshedAt) {
            snapshot = value;
        }
        @Override public void showUnavailable(final Instant refreshedAt) {
            unavailableCount++;
            unavailableText = localization.text("status.unavailable");
        }
        @Override public void dispose() { disposed = true; }
    }

    private static final class FakeHostReads implements AsyncHostReadService {
        private final Queue<AsyncHostReadSubmission> submissions = new ArrayDeque<>();
        private final List<AsyncHostReadRequest> requests = new ArrayList<>();
        private Runnable afterSubmit = () -> { };

        private ControlledHandle accepted() {
            return handle(AsyncHostReadSubmissionStatus.ACCEPTED);
        }

        private ControlledHandle coalesced() {
            return handle(AsyncHostReadSubmissionStatus.COALESCED);
        }

        private void coalesceWith(final ControlledHandle handle) {
            submissions.add(new AsyncHostReadSubmission(
                AsyncHostReadSubmissionStatus.COALESCED,
                Optional.of(handle),
                Optional.empty()
            ));
        }

        private ControlledHandle handle(final AsyncHostReadSubmissionStatus status) {
            final ControlledHandle handle = new ControlledHandle();
            submissions.add(new AsyncHostReadSubmission(status, Optional.of(handle), Optional.empty()));
            return handle;
        }

        private void rejected(final AsyncHostReadErrorCode code) {
            submissions.add(new AsyncHostReadSubmission(
                AsyncHostReadSubmissionStatus.REJECTED,
                Optional.empty(),
                Optional.of(new AsyncHostReadError(code, "rejected"))
            ));
        }

        @Override
        public AsyncHostReadSubmission submit(final AsyncHostReadRequest request) {
            requests.add(request);
            final AsyncHostReadSubmission submission = submissions.remove();
            afterSubmit.run();
            return submission;
        }
    }

    private static final class ControlledHandle implements AsyncHostReadHandle {
        private final CompletableFuture<AsyncHostReadResult> completion = new CompletableFuture<>();
        private boolean canceled;

        private void completeSuccess(final ProjectWorkspaceSnapshot snapshot) {
            completion.complete(AsyncHostReadResult.success(intent(), snapshot));
        }

        private void completeFailure(final AsyncHostReadErrorCode code) {
            completion.complete(new AsyncHostReadResult(
                intent(),
                AsyncHostReadStatus.FAILED,
                Optional.empty(),
                Optional.of(new AsyncHostReadError(code, "failed"))
            ));
        }

        @Override public AsyncHostReadIntent intent() { return AsyncHostReadIntent.PROJECT_WORKSPACE_SNAPSHOT; }
        @Override public AsyncHostReadStatus status() { return AsyncHostReadStatus.QUEUED; }
        @Override public boolean cancel() { canceled = true; return true; }
        @Override public CompletionStage<AsyncHostReadResult> completion() { return completion; }
        @Override public void close() { cancel(); }
    }

    private static final class FakeLocalization implements PluginLocalization {
        @Override public Locale locale() { return Locale.ENGLISH; }
        @Override public String text(final String key) {
            return key.equals("status.unavailable") ? "Unavailable" : key;
        }
        @Override public String format(final String key, final Object... arguments) { return text(key); }
        @Override public boolean contains(final String key) { return true; }
    }

    private static final class RecordingLogger implements PluginLogger {
        private final List<String> warns = new ArrayList<>();
        @Override public void debug(final String message) {}
        @Override public void info(final String message) {}
        @Override public void warn(final String message) { warns.add(message); }
        @Override public void error(final String message) {}
        @Override public void error(final String message, final Throwable throwable) {}
    }
}

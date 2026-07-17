package dev.turboism.hostread;

import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.PluginExecutorRegistry;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.sdk.cubism.ProjectSnapshot;
import dev.turboism.sdk.cubism.WorkspaceSnapshot;
import dev.turboism.sdk.hostread.AsyncHostReadErrorCode;
import dev.turboism.sdk.hostread.AsyncHostReadHandle;
import dev.turboism.sdk.hostread.AsyncHostReadIntent;
import dev.turboism.sdk.hostread.AsyncHostReadRequest;
import dev.turboism.sdk.hostread.AsyncHostReadResult;
import dev.turboism.sdk.hostread.AsyncHostReadStatus;
import dev.turboism.sdk.hostread.AsyncHostReadSubmission;
import dev.turboism.sdk.hostread.AsyncHostReadSubmissionStatus;
import dev.turboism.sdk.hostread.ProjectWorkspaceSnapshot;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.task.RuntimePluginTaskScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeAsyncHostReadServiceTest {

    private final List<DisposableScope> scopes = new ArrayList<>();
    private RuntimeScheduler scheduler;
    private SharedAsyncHostReadLane lane;

    @AfterEach
    void closeRuntime() throws Exception {
        for (int index = scopes.size() - 1; index >= 0; index--) {
            scopes.get(index).close();
        }
        if (lane != null) {
            lane.close();
        }
        if (scheduler != null && !scheduler.isClosed()) {
            scheduler.shutdown();
        }
    }

    @Test
    void returnsCombinedProjectWorkspaceSnapshotThroughSharedHostLane() throws Exception {
        final FakeSource source = new FakeSource();
        final RuntimeAsyncHostReadService service = service("plugin-a", granted(), source);

        final AsyncHostReadSubmission submission = service.submit(request(Duration.ofSeconds(2)));
        final AsyncHostReadResult result = await(submission);

        assertEquals(AsyncHostReadSubmissionStatus.ACCEPTED, submission.status());
        assertEquals(AsyncHostReadStatus.SUCCEEDED, result.status());
        final ProjectWorkspaceSnapshot value = (ProjectWorkspaceSnapshot) result.value().orElseThrow();
        assertEquals("Project", value.project().orElseThrow().name());
        assertEquals("workspace", value.workspace().orElseThrow().displayName());
        assertEquals(1, source.calls.get());
        assertTrue(lane.workerThreadName().startsWith("turboism-host-read-shared"));
        assertFalse(lane.workerThreadName().contains("plugin-a"));
    }

    @Test
    void permissionDeniedAndSafeModeUnavailableAreClosedFailures() throws Exception {
        final FakeSource deniedSource = new FakeSource();
        final AsyncHostReadSubmission denied = service("plugin-denied", Set.of(), deniedSource)
            .submit(request(Duration.ofSeconds(2)));
        assertEquals(AsyncHostReadSubmissionStatus.REJECTED, denied.status());
        assertEquals(AsyncHostReadErrorCode.PERMISSION_DENIED, denied.error().orElseThrow().code());
        assertEquals(0, deniedSource.calls.get());

        final FakeSource safeMode = new FakeSource();
        safeMode.result = ProjectWorkspaceHostReadResult.failed(
            AsyncHostReadErrorCode.CAPABILITY_UNAVAILABLE
        );
        final AsyncHostReadResult unavailable = await(
            service("plugin-safe", granted(), safeMode).submit(request(Duration.ofSeconds(2)))
        );
        assertEquals(AsyncHostReadStatus.FAILED, unavailable.status());
        assertEquals(AsyncHostReadErrorCode.CAPABILITY_UNAVAILABLE,
            unavailable.error().orElseThrow().code());
    }

    @Test
    void duplicateInflightRequestCoalescesAndDistinctPluginsShareBoundedLane() throws Exception {
        final FakeSource source = new FakeSource();
        source.block = true;
        final RuntimeAsyncHostReadService firstService = service("plugin-a", granted(), source, 1);
        final RuntimeAsyncHostReadService secondService = service("plugin-b", granted(), source, 1);

        final AsyncHostReadSubmission first = firstService.submit(request(Duration.ofSeconds(2)));
        assertTrue(source.started.await(1, TimeUnit.SECONDS));
        final AsyncHostReadSubmission coalesced = firstService.submit(request(Duration.ofSeconds(2)));
        final AsyncHostReadSubmission queued = secondService.submit(request(Duration.ofSeconds(2)));
        final RuntimeAsyncHostReadService thirdService = service("plugin-c", granted(), source, 1);
        final AsyncHostReadSubmission rejected = thirdService.submit(request(Duration.ofSeconds(2)));

        assertEquals(AsyncHostReadSubmissionStatus.COALESCED, coalesced.status());
        assertSame(first.handle().orElseThrow(), coalesced.handle().orElseThrow());
        assertEquals(AsyncHostReadSubmissionStatus.ACCEPTED, queued.status());
        assertEquals(AsyncHostReadSubmissionStatus.REJECTED, rejected.status());
        assertEquals(AsyncHostReadErrorCode.BACKPRESSURE, rejected.error().orElseThrow().code());
        source.release.countDown();
        assertEquals(AsyncHostReadStatus.SUCCEEDED, await(first).status());
        assertEquals(AsyncHostReadStatus.SUCCEEDED, await(queued).status());
        assertEquals(2, source.calls.get());
    }

    @Test
    void cancelBeforeStartRemovesQueuedWorkWithoutCallingHost() throws Exception {
        final FakeSource source = new FakeSource();
        source.block = true;
        final RuntimeAsyncHostReadService blockerService = service("plugin-blocker", granted(), source, 2);
        final AsyncHostReadHandle blocker = blockerService.submit(request(Duration.ofSeconds(2)))
            .handle().orElseThrow();
        assertTrue(source.started.await(1, TimeUnit.SECONDS));

        final AsyncHostReadHandle queued = service("plugin-queued", granted(), source, 2)
            .submit(request(Duration.ofSeconds(2))).handle().orElseThrow();
        assertEquals(AsyncHostReadStatus.QUEUED, queued.status());
        assertTrue(queued.cancel());
        assertEquals(AsyncHostReadStatus.CANCELED,
            queued.completion().toCompletableFuture().get(1, TimeUnit.SECONDS).status());

        source.release.countDown();
        assertEquals(AsyncHostReadStatus.SUCCEEDED,
            blocker.completion().toCompletableFuture().get(1, TimeUnit.SECONDS).status());
        Thread.sleep(50L);
        assertEquals(1, source.calls.get());
    }

    @Test
    void totalDeadlineExpiresWhileQueuedAndNeverCallsHostForTimedOutWork() throws Exception {
        final FakeSource source = new FakeSource();
        source.block = true;
        final AsyncHostReadHandle blocker = service("plugin-blocker", granted(), source, 2)
            .submit(request(Duration.ofSeconds(2))).handle().orElseThrow();
        assertTrue(source.started.await(1, TimeUnit.SECONDS));

        final AsyncHostReadHandle queued = service("plugin-timeout-queued", granted(), source, 2)
            .submit(request(Duration.ofMillis(100))).handle().orElseThrow();
        final AsyncHostReadResult timeout = queued.completion().toCompletableFuture()
            .get(1, TimeUnit.SECONDS);
        assertEquals(AsyncHostReadStatus.FAILED, timeout.status());
        assertEquals(AsyncHostReadErrorCode.TIMEOUT, timeout.error().orElseThrow().code());

        source.release.countDown();
        assertEquals(AsyncHostReadStatus.SUCCEEDED,
            blocker.completion().toCompletableFuture().get(1, TimeUnit.SECONDS).status());
        Thread.sleep(50L);
        assertEquals(1, source.calls.get());
    }

    @Test
    void terminalHandleDoesNotCoalesceWithNextRequestForTheSameKey() throws Exception {
        final FakeSource source = new FakeSource();
        final RuntimeAsyncHostReadService service = service("plugin-stale", granted(), source);

        final AsyncHostReadSubmission first = service.submit(request(Duration.ofSeconds(2)));
        assertEquals(AsyncHostReadStatus.SUCCEEDED, await(first).status());
        final AsyncHostReadSubmission second = service.submit(request(Duration.ofSeconds(2)));

        assertEquals(AsyncHostReadSubmissionStatus.ACCEPTED, second.status());
        assertFalse(first.handle().orElseThrow() == second.handle().orElseThrow());
        assertEquals(AsyncHostReadStatus.SUCCEEDED, await(second).status());
        assertEquals(2, source.calls.get());
    }

    @Test
    void timeoutAndCancellationSettleOnceAndDiscardLateHostValues() throws Exception {
        final FakeSource timedSource = new FakeSource();
        timedSource.block = true;
        final AsyncHostReadHandle timed = service("plugin-timeout", granted(), timedSource)
            .submit(request(Duration.ofMillis(100))).handle().orElseThrow();
        assertTrue(timedSource.started.await(1, TimeUnit.SECONDS));
        final AsyncHostReadResult timeout = timed.completion().toCompletableFuture().get(1, TimeUnit.SECONDS);
        assertEquals(AsyncHostReadStatus.FAILED, timeout.status());
        assertEquals(AsyncHostReadErrorCode.TIMEOUT, timeout.error().orElseThrow().code());
        timedSource.release.countDown();
        Thread.sleep(50L);
        assertEquals(AsyncHostReadStatus.FAILED, timed.status());

        final FakeSource canceledSource = new FakeSource();
        canceledSource.block = true;
        final AsyncHostReadHandle canceled = service("plugin-cancel", granted(), canceledSource)
            .submit(request(Duration.ofSeconds(2))).handle().orElseThrow();
        assertTrue(canceledSource.started.await(1, TimeUnit.SECONDS));
        assertTrue(canceled.cancel());
        assertFalse(canceled.cancel());
        assertEquals(AsyncHostReadStatus.CANCELED,
            canceled.completion().toCompletableFuture().get(1, TimeUnit.SECONDS).status());
        canceledSource.release.countDown();
    }

    @Test
    void pluginCloseCancelsOnlyOwnedHandlesAndForeignForgedAndTerminalAreClosed() throws Exception {
        final FakeSource source = new FakeSource();
        source.block = true;
        final RuntimeAsyncHostReadService first = service("plugin-a", granted(), source);
        final RuntimeAsyncHostReadService second = service("plugin-b", granted(), source);
        final AsyncHostReadHandle owned = first.submit(request(Duration.ofSeconds(2))).handle().orElseThrow();
        assertTrue(source.started.await(1, TimeUnit.SECONDS));
        final AsyncHostReadHandle foreign = second.submit(request(Duration.ofSeconds(2))).handle().orElseThrow();

        assertThrows(IllegalArgumentException.class, () -> first.cancel(foreign));
        assertThrows(IllegalArgumentException.class, () -> first.cancel(new ForgedHandle()));
        source.release.countDown();
        first.close();
        assertTrue(Set.of(AsyncHostReadStatus.CANCELED, AsyncHostReadStatus.SUCCEEDED).contains(
            owned.completion().toCompletableFuture().get(1, TimeUnit.SECONDS).status()
        ));
        assertFalse(first.cancel(owned));
        assertEquals(AsyncHostReadSubmissionStatus.REJECTED,
            first.submit(request(Duration.ofSeconds(2))).status());
        assertEquals(AsyncHostReadStatus.SUCCEEDED,
            foreign.completion().toCompletableFuture().get(1, TimeUnit.SECONDS).status());
        assertFalse(lane.isClosed());
    }

    @Test
    void sanitizesUnexpectedFailureAndDispatchesLateContinuationThroughPluginExecutor() throws Exception {
        final FakeSource source = new FakeSource();
        source.failure = new IllegalStateException("private /home/user selector=cubism.secret");
        final RuntimeAsyncHostReadService service = service("plugin-sanitize", granted(), source);
        final AsyncHostReadHandle handle = service.submit(request(Duration.ofSeconds(2))).handle().orElseThrow();
        final AsyncHostReadResult result = handle.completion().toCompletableFuture().get(1, TimeUnit.SECONDS);
        assertEquals(AsyncHostReadErrorCode.RUNTIME_FAILURE, result.error().orElseThrow().code());
        assertEquals("Async host read failed safely.", result.error().orElseThrow().message());

        final CountDownLatch continued = new CountDownLatch(1);
        final AtomicReference<String> continuationThread = new AtomicReference<>();
        handle.completion().thenRun(() -> {
            continuationThread.set(Thread.currentThread().getName());
            continued.countDown();
        });
        assertTrue(continued.await(1, TimeUnit.SECONDS));
        assertTrue(continuationThread.get().contains("plugin-sanitize"));
        assertFalse(continuationThread.get().startsWith("turboism-host-read-shared"));
    }

    @Test
    void continuationRegistrationFailsClosedAfterServiceCloseBegins() throws Exception {
        final FakeSource source = new FakeSource();
        source.block = true;
        source.ignoreInterrupt = true;
        final RuntimeAsyncHostReadService service = service("plugin-closed-continuation", granted(), source);
        final AsyncHostReadHandle handle = service.submit(request(Duration.ofSeconds(2)))
            .handle().orElseThrow();
        assertTrue(source.started.await(1, TimeUnit.SECONDS));

        final CompletableFuture<Void> closing = CompletableFuture.runAsync(service::close);
        Thread.sleep(75L);
        assertThrows(IllegalStateException.class, () -> handle.completion().thenRun(() -> { }));
        assertThrows(IllegalStateException.class, () -> handle.completion().thenAccept(ignored -> { }));
        assertThrows(IllegalStateException.class, () -> handle.completion().thenAcceptAsync(ignored -> { }));
        assertThrows(IllegalStateException.class, () -> handle.completion().toCompletableFuture().minimalCompletionStage());
        source.release.countDown();
        closing.get(1, TimeUnit.SECONDS);
    }

    @Test
    void closeWaitsForPhysicalWrapperThenContinuationQuiescence() throws Exception {
        final FakeSource source = new FakeSource();
        source.block = true;
        source.ignoreInterrupt = true;
        final RuntimeAsyncHostReadService service = service("plugin-quiescence", granted(), source);
        final AsyncHostReadHandle handle = service.submit(request(Duration.ofSeconds(2))).handle().orElseThrow();
        assertTrue(source.started.await(1, TimeUnit.SECONDS));

        final CompletableFuture<Void> closed = CompletableFuture.runAsync(service::close);
        Thread.sleep(75L);
        assertFalse(closed.isDone(), "close must retain the plugin while the host wrapper is still running");
        assertEquals(AsyncHostReadStatus.CANCELED,
            handle.completion().toCompletableFuture().get(1, TimeUnit.SECONDS).status());
        source.release.countDown();
        closed.get(1, TimeUnit.SECONDS);
        assertFalse(lane.isClosed());
    }

    @Test
    void closeWaitsForBlockedPluginContinuationBeforeReturning() throws Exception {
        final FakeSource source = new FakeSource();
        final RuntimeAsyncHostReadService service = service("plugin-continuation", granted(), source);
        final AsyncHostReadHandle handle = service.submit(request(Duration.ofSeconds(2)))
            .handle().orElseThrow();
        final CountDownLatch continuationStarted = new CountDownLatch(1);
        final CountDownLatch releaseContinuation = new CountDownLatch(1);
        handle.completion().thenRun(() -> {
            continuationStarted.countDown();
            try {
                releaseContinuation.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(continuationStarted.await(1, TimeUnit.SECONDS));

        final CompletableFuture<Void> closed = CompletableFuture.runAsync(service::close);
        Thread.sleep(75L);
        assertFalse(closed.isDone(), "close must wait for admitted plugin continuations");
        releaseContinuation.countDown();
        closed.get(1, TimeUnit.SECONDS);
    }

    @Test
    void disposableScopeQuiescesHostReadsBeforeSchedulerAndClassLoaderSentinel() throws Exception {
        final FakeSource source = new FakeSource();
        source.block = true;
        source.ignoreInterrupt = true;
        final List<String> closeOrder = new ArrayList<>();
        if (scheduler == null) {
            scheduler = new RuntimeScheduler(
                new DefaultWorkBudgetPolicy(),
                new PluginExecutorRegistry(2_000L, 1, 64, ignored -> {}, Clock.systemUTC()),
                SidecarDispatcher.noop(),
                ignored -> {}
            );
            lane = new SharedAsyncHostReadLane(32);
        }
        final DisposableScope scope = new DisposableScope();
        scopes.add(scope);
        scope.register(() -> closeOrder.add("classloader"));
        final RuntimePluginTaskScheduler tasks = new RuntimePluginTaskScheduler(
            "plugin-ordering",
            scheduler,
            scope
        );
        final RuntimeAsyncHostReadService service = new RuntimeAsyncHostReadService(
            "plugin-ordering",
            granted(),
            source,
            lane,
            tasks,
            scope
        );
        scope.register(() -> closeOrder.add("plugin-ui"));
        service.submit(request(Duration.ofSeconds(2)));
        assertTrue(source.started.await(1, TimeUnit.SECONDS));

        final CompletableFuture<Void> closed = CompletableFuture.runAsync(() -> {
            try {
                scope.close();
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        });
        Thread.sleep(75L);
        assertEquals(List.of("plugin-ui"), closeOrder);
        source.release.countDown();
        closed.get(1, TimeUnit.SECONDS);
        assertEquals(List.of("plugin-ui", "classloader"), closeOrder);
    }

    @Test
    void sharedLaneCloseCancelsAllRemainingPluginOperations() throws Exception {
        final FakeSource source = new FakeSource();
        source.block = true;
        final AsyncHostReadHandle first = service("plugin-a", granted(), source)
            .submit(request(Duration.ofSeconds(2))).handle().orElseThrow();
        assertTrue(source.started.await(1, TimeUnit.SECONDS));
        final AsyncHostReadHandle second = service("plugin-b", granted(), source)
            .submit(request(Duration.ofSeconds(2))).handle().orElseThrow();

        source.release.countDown();
        lane.close();
        assertTrue(first.status() == AsyncHostReadStatus.CANCELED
            || first.status() == AsyncHostReadStatus.SUCCEEDED);
        assertTrue(second.status() == AsyncHostReadStatus.CANCELED
            || second.status() == AsyncHostReadStatus.SUCCEEDED);
    }

    private RuntimeAsyncHostReadService service(
        final String pluginId,
        final Set<String> permissions,
        final FakeSource source
    ) {
        return service(pluginId, permissions, source, 32);
    }

    private RuntimeAsyncHostReadService service(
        final String pluginId,
        final Set<String> permissions,
        final FakeSource source,
        final int capacity
    ) {
        if (scheduler == null) {
            scheduler = new RuntimeScheduler(
                new DefaultWorkBudgetPolicy(),
                new PluginExecutorRegistry(2_000L, 1, 64, ignored -> {}, Clock.systemUTC()),
                SidecarDispatcher.noop(),
                ignored -> {}
            );
            lane = new SharedAsyncHostReadLane(capacity);
        }
        final DisposableScope scope = new DisposableScope();
        scopes.add(scope);
        final RuntimePluginTaskScheduler tasks = new RuntimePluginTaskScheduler(pluginId, scheduler, scope);
        return new RuntimeAsyncHostReadService(pluginId, permissions, source, lane, tasks, scope);
    }

    private static Set<String> granted() {
        return Set.of("turboism.cubism.project.read");
    }

    private static AsyncHostReadRequest request(final Duration timeout) {
        return new AsyncHostReadRequest(AsyncHostReadIntent.PROJECT_WORKSPACE_SNAPSHOT, timeout);
    }

    private static AsyncHostReadResult await(final AsyncHostReadSubmission submission) throws Exception {
        return submission.handle().orElseThrow().completion().toCompletableFuture().get(2, TimeUnit.SECONDS);
    }

    private static ProjectWorkspaceHostReadResult success() {
        return ProjectWorkspaceHostReadResult.available(new ProjectWorkspaceSnapshot(
            Optional.of(new ProjectSnapshot("project", "Project", Optional.empty(), List.of())),
            Optional.of(new WorkspaceSnapshot("workspace", "workspace", List.of("project")))
        ));
    }

    private static final class FakeSource implements ProjectWorkspaceHostReadSource {
        private final AtomicInteger calls = new AtomicInteger();
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private ProjectWorkspaceHostReadResult result = success();
        private boolean block;
        private boolean ignoreInterrupt;
        private RuntimeException failure;

        @Override
        public ProjectWorkspaceHostReadResult read() {
            calls.incrementAndGet();
            started.countDown();
            if (failure != null) {
                throw failure;
            }
            while (block && release.getCount() > 0) {
                try {
                    release.await();
                } catch (InterruptedException exception) {
                    if (!ignoreInterrupt) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            return result;
        }
    }

    private static final class ForgedHandle implements AsyncHostReadHandle {
        @Override public AsyncHostReadIntent intent() { return AsyncHostReadIntent.PROJECT_WORKSPACE_SNAPSHOT; }
        @Override public AsyncHostReadStatus status() { return AsyncHostReadStatus.QUEUED; }
        @Override public boolean cancel() { return false; }
        @Override public java.util.concurrent.CompletionStage<AsyncHostReadResult> completion() {
            return new CompletableFuture<>();
        }
        @Override public void close() {}
    }
}

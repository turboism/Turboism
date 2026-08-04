package dev.turboism.ui.workspace;

import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.ui.workspace.WorkspaceId;
import dev.turboism.sdk.ui.workspace.WorkspaceInfo;
import dev.turboism.sdk.ui.workspace.WorkspaceOperationResult;
import dev.turboism.sdk.ui.workspace.WorkspaceStatus;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceCoordinatorTest {

    @Test
    void readsSwitchesAndMutatesOnlyOnEdt() throws Exception {
        RecordingProvider provider = new RecordingProvider();
        WorkspaceCoordinator coordinator = new WorkspaceCoordinator();
        coordinator.connect(provider);
        RuntimeWorkspaceService service = new RuntimeWorkspaceService(
            (permission, operation) -> { },
            coordinator
        );

        WorkspaceStatus status = service.current().toCompletableFuture().join();
        WorkspaceOperationResult switched = service.switchTo(new WorkspaceId("animation"))
            .toCompletableFuture().join();
        WorkspaceOperationResult updated = service.updateDefault().toCompletableFuture().join();
        WorkspaceOperationResult reset = service.resetToDefault().toCompletableFuture().join();
        SwingUtilities.invokeAndWait(() -> { });

        assertEquals("modeling", status.current().orElseThrow().id().value());
        assertEquals(List.of("modeling", "animation"), status.available().stream()
            .map(info -> info.id().value()).toList());
        assertEquals(WorkspaceOperationResult.Outcome.CHANGED, switched.outcome());
        assertEquals(WorkspaceOperationResult.Outcome.CHANGED, updated.outcome());
        assertEquals(WorkspaceOperationResult.Outcome.CHANGED, reset.outcome());
        assertEquals("animation", reset.status().current().orElseThrow().id().value());
        assertTrue(provider.allCallsOnEdt);
    }

    @Test
    void noChangeNotFoundPermissionAndDisconnectFailClosed() {
        RecordingProvider provider = new RecordingProvider();
        WorkspaceCoordinator coordinator = new WorkspaceCoordinator();
        coordinator.connect(provider);
        RuntimeWorkspaceService allowed = new RuntimeWorkspaceService(
            (permission, operation) -> { },
            coordinator
        );

        assertEquals(
            WorkspaceOperationResult.Outcome.NO_CHANGE,
            allowed.switchTo(new WorkspaceId("modeling")).toCompletableFuture().join().outcome()
        );
        assertEquals(
            WorkspaceOperationResult.Outcome.NOT_FOUND,
            allowed.switchTo(new WorkspaceId("missing")).toCompletableFuture().join().outcome()
        );
        assertEquals(0, provider.switchCount);

        RuntimeWorkspaceService denied = new RuntimeWorkspaceService(
            (permission, operation) -> { throw new CubismPermissionException("denied"); },
            coordinator
        );
        assertThrows(CubismPermissionException.class, () -> denied.updateDefault());
        assertEquals(0, provider.updateCount);
        assertThrows(CubismPermissionException.class, () -> denied.current());
        assertEquals(2, provider.readCount, "denied current() must not touch the provider");

        coordinator.disconnect(provider);
        assertEquals(
            WorkspaceStatus.Availability.UNAVAILABLE,
            allowed.current().toCompletableFuture().join().availability()
        );
        assertEquals(
            WorkspaceOperationResult.Outcome.UNAVAILABLE,
            allowed.resetToDefault().toCompletableFuture().join().outcome()
        );
    }

    @Test
    void scopeCloseMakesCapturedServiceUnavailableWithoutTouchingProvider() throws Exception {
        RecordingProvider provider = new RecordingProvider();
        WorkspaceCoordinator coordinator = new WorkspaceCoordinator();
        coordinator.connect(provider);
        RuntimeWorkspaceService service = new RuntimeWorkspaceService(
            (permission, operation) -> { },
            coordinator
        );
        DisposableScope scope = new DisposableScope();
        scope.register(service);
        scope.close();

        assertEquals(
            WorkspaceStatus.Availability.UNAVAILABLE,
            service.current().toCompletableFuture().get(1, TimeUnit.SECONDS).availability()
        );
        assertEquals(
            WorkspaceOperationResult.Outcome.UNAVAILABLE,
            service.switchTo(new WorkspaceId("animation"))
                .toCompletableFuture().get(1, TimeUnit.SECONDS).outcome()
        );
        assertEquals(
            WorkspaceOperationResult.Outcome.UNAVAILABLE,
            service.updateDefault().toCompletableFuture().get(1, TimeUnit.SECONDS).outcome()
        );
        assertEquals(
            WorkspaceOperationResult.Outcome.UNAVAILABLE,
            service.resetToDefault().toCompletableFuture().get(1, TimeUnit.SECONDS).outcome()
        );
        assertEquals(0, provider.switchCount);
        assertEquals(0, provider.updateCount);
        assertEquals(0, provider.readCount, "closed service must never touch the provider");
    }

    @Test
    void queuedMutationAfterDisconnectNeverTouchesOldProvider() throws Exception {
        RecordingProvider provider = new RecordingProvider();
        WorkspaceCoordinator coordinator = new WorkspaceCoordinator();
        coordinator.connect(provider);
        RuntimeWorkspaceService service = new RuntimeWorkspaceService(
            (permission, operation) -> { },
            coordinator
        );
        CountDownLatch edtRelease = blockEdt();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<Thread> workerRef = new AtomicReference<>();
        CompletableFuture<WorkspaceOperationResult> queued = CompletableFuture.supplyAsync(() -> {
            workerRef.set(Thread.currentThread());
            return service.switchTo(new WorkspaceId("animation")).toCompletableFuture().join();
        }, executor);
        try {
            awaitState(workerRef, Thread.State.WAITING, "switchTo queued on the blocked EDT");
            coordinator.disconnect(provider);
        } finally {
            edtRelease.countDown();
        }

        try {
            assertEquals(WorkspaceOperationResult.Outcome.UNAVAILABLE, queued.get(5, TimeUnit.SECONDS).outcome());
            assertEquals(0, provider.switchCount, "queued mutation must not touch the disconnected provider");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void providerExceptionsFailClosedAsTypedResults() {
        ThrowingProvider provider = new ThrowingProvider();
        WorkspaceCoordinator coordinator = new WorkspaceCoordinator();
        coordinator.connect(provider);

        assertEquals(
            WorkspaceStatus.Availability.UNAVAILABLE,
            coordinator.current().availability()
        );
        assertEquals(
            WorkspaceOperationResult.Outcome.FAILED,
            coordinator.switchTo(new WorkspaceId("animation")).outcome()
        );
        assertEquals(WorkspaceStatus.Availability.UNAVAILABLE,
            coordinator.switchTo(new WorkspaceId("animation")).status().availability());
    }

    @Test
    void disconnectCannotCompleteThroughInFlightMutation() throws Exception {
        BlockingProvider provider = new BlockingProvider();
        WorkspaceCoordinator coordinator = new WorkspaceCoordinator();
        coordinator.connect(provider);
        RuntimeWorkspaceService service = new RuntimeWorkspaceService(
            (permission, operation) -> { },
            coordinator
        );

        ExecutorService executor = Executors.newSingleThreadExecutor();
        CompletableFuture<WorkspaceOperationResult> mutating = CompletableFuture.supplyAsync(
            () -> service.switchTo(new WorkspaceId("animation")).toCompletableFuture().join(),
            executor
        );
        try {
            assertTrue(provider.switchStarted.await(5, TimeUnit.SECONDS),
                "the host operation must be running on the EDT");

            AtomicReference<Thread> disconnectorRef = new AtomicReference<>();
            CompletableFuture<Void> disconnecting = CompletableFuture.runAsync(() -> {
                disconnectorRef.set(Thread.currentThread());
                coordinator.disconnect(provider);
            });
            awaitState(disconnectorRef, Thread.State.BLOCKED,
                "disconnect attempt to block on the coordinator monitor");
            assertFalse(disconnecting.isDone(),
                () -> "disconnect must not complete through an in-flight mutation");

            provider.switchRelease.countDown();
            WorkspaceOperationResult result = mutating.get(5, TimeUnit.SECONDS);
            disconnecting.get(5, TimeUnit.SECONDS);

            assertEquals(WorkspaceOperationResult.Outcome.CHANGED, result.outcome(),
                "the in-flight mutation completes once the host operation returns");
            assertEquals(1, provider.switchCount);
            assertEquals(
                WorkspaceStatus.Availability.UNAVAILABLE,
                coordinator.current().availability(),
                "after disconnect completes the provider is gone"
            );
        } finally {
            provider.switchRelease.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void queuedServiceMutationAfterCloseNeverTouchesProvider() throws Exception {
        RecordingProvider provider = new RecordingProvider();
        WorkspaceCoordinator coordinator = new WorkspaceCoordinator();
        coordinator.connect(provider);
        RuntimeWorkspaceService service = new RuntimeWorkspaceService(
            (permission, operation) -> { },
            coordinator
        );
        CountDownLatch edtRelease = blockEdt();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<Thread> workerRef = new AtomicReference<>();
        CompletableFuture<WorkspaceOperationResult> queued = CompletableFuture.supplyAsync(() -> {
            workerRef.set(Thread.currentThread());
            return service.switchTo(new WorkspaceId("animation")).toCompletableFuture().join();
        }, executor);
        try {
            awaitState(workerRef, Thread.State.WAITING, "switchTo queued on the blocked EDT");

            AtomicReference<Thread> closerRef = new AtomicReference<>();
            CompletableFuture<Void> closing = CompletableFuture.runAsync(() -> {
                closerRef.set(Thread.currentThread());
                service.close();
            });
            awaitState(closerRef, Thread.State.WAITING,
                "close attempt to block in the EDT fence");
            assertFalse(closing.isDone(),
                () -> "close must fence the EDT and wait for the queued operation");

            edtRelease.countDown();
            assertEquals(
                WorkspaceOperationResult.Outcome.UNAVAILABLE,
                queued.get(5, TimeUnit.SECONDS).outcome(),
                "an operation queued before close executes after close as typed UNAVAILABLE"
            );
            closing.get(5, TimeUnit.SECONDS);
            assertEquals(0, provider.switchCount, "closed service must never touch the provider");
            assertEquals(0, provider.readCount);
        } finally {
            edtRelease.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void permissionCheckRunsOnCallerThreadWhileHostCallsStayOnEdt() throws Exception {
        RecordingProvider provider = new RecordingProvider();
        WorkspaceCoordinator coordinator = new WorkspaceCoordinator();
        coordinator.connect(provider);
        AtomicReference<Thread> edtThread = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> edtThread.set(Thread.currentThread()));
        AtomicReference<Thread> permissionThread = new AtomicReference<>();
        RuntimeWorkspaceService service = new RuntimeWorkspaceService(
            (permission, operation) -> permissionThread.set(Thread.currentThread()),
            coordinator
        );

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            CompletableFuture<WorkspaceStatus> status = CompletableFuture.supplyAsync(
                () -> service.current().toCompletableFuture().join(), executor);
            assertEquals(
                WorkspaceStatus.Availability.AVAILABLE,
                status.get(5, TimeUnit.SECONDS).availability()
            );
            assertFalse(permissionThread.get() == edtThread.get(),
                () -> "permission check must run on the caller thread, not the EDT");
            assertTrue(provider.allCallsOnEdt, "host calls must still run on the EDT");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void reentrantReplacementFailsClosedWithoutStaleState() {
        // Throw/fallback path: the operation reentrantly disconnects itself before throwing.
        WorkspaceCoordinator throwingCoordinator = new WorkspaceCoordinator();
        ReentrantProvider throwing = new ReentrantProvider(throwingCoordinator);
        throwing.replaceAndThrow = true;
        throwingCoordinator.connect(throwing);
        WorkspaceOperationResult failed = throwingCoordinator.switchTo(new WorkspaceId("animation"));
        assertEquals(WorkspaceOperationResult.Outcome.FAILED, failed.outcome());
        assertEquals(WorkspaceStatus.Availability.UNAVAILABLE, failed.status().availability(),
            "fallback status must not be read from a stale provider");
        assertEquals(Optional.of("workspace.provider.replaced"), failed.diagnosticCode());
        assertEquals(1, throwing.switchCount);

        // Post-state read path: the operation succeeds but the read reentrantly disconnects.
        WorkspaceCoordinator postReadCoordinator = new WorkspaceCoordinator();
        ReentrantProvider postRead = new ReentrantProvider(postReadCoordinator);
        postRead.replaceInRead = true;
        postReadCoordinator.connect(postRead);
        WorkspaceOperationResult replaced = postReadCoordinator.switchTo(new WorkspaceId("animation"));
        assertEquals(WorkspaceOperationResult.Outcome.FAILED, replaced.outcome());
        assertEquals(WorkspaceStatus.Availability.UNAVAILABLE, replaced.status().availability());
        assertEquals(Optional.of("workspace.provider.replaced"), replaced.diagnosticCode());

        // Current read path: the read reentrantly disconnects.
        WorkspaceCoordinator readCoordinator = new WorkspaceCoordinator();
        ReentrantProvider read = new ReentrantProvider(readCoordinator);
        read.replaceInRead = true;
        readCoordinator.connect(read);
        assertEquals(WorkspaceStatus.Availability.UNAVAILABLE, readCoordinator.current().availability());
    }

    private static final class BlockingProvider implements WorkspaceHostProvider {
        private final WorkspaceInfo animation = new WorkspaceInfo(new WorkspaceId("animation"), "Animation");
        private final CountDownLatch switchStarted = new CountDownLatch(1);
        private final CountDownLatch switchRelease = new CountDownLatch(1);
        private int switchCount;

        @Override public WorkspaceStatus readStatus() {
            return new WorkspaceStatus(
                WorkspaceStatus.Availability.AVAILABLE,
                Optional.of(animation),
                List.of(animation),
                Optional.empty()
            );
        }

        @Override public WorkspaceOperationResult.Outcome switchTo(final WorkspaceId workspaceId) {
            switchCount++;
            switchStarted.countDown();
            try {
                switchRelease.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return WorkspaceOperationResult.Outcome.CHANGED;
        }

        @Override public WorkspaceOperationResult.Outcome updateDefault() {
            return WorkspaceOperationResult.Outcome.CHANGED;
        }

        @Override public WorkspaceOperationResult.Outcome resetToDefault() {
            return WorkspaceOperationResult.Outcome.CHANGED;
        }
    }

    private static CountDownLatch blockEdt() throws Exception {
        CountDownLatch edtBlocked = new CountDownLatch(1);
        CountDownLatch edtRelease = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            edtBlocked.countDown();
            try {
                edtRelease.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(edtBlocked.await(5, TimeUnit.SECONDS), "EDT blocker must start");
        return edtRelease;
    }

    private static void awaitState(
        final AtomicReference<Thread> threadRef,
        final Thread.State expected,
        final String description
    ) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        Thread thread = null;
        while (thread == null || thread.getState() != expected) {
            thread = threadRef.get();
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for " + description);
            }
            Thread.sleep(5);
        }
    }

    private static final class ReentrantProvider implements WorkspaceHostProvider {
        private final WorkspaceCoordinator coordinator;
        private final WorkspaceInfo animation = new WorkspaceInfo(new WorkspaceId("animation"), "Animation");
        private boolean replaceInRead;
        private boolean replaceAndThrow;
        private int switchCount;

        ReentrantProvider(final WorkspaceCoordinator coordinator) {
            this.coordinator = coordinator;
        }

        @Override public WorkspaceStatus readStatus() {
            if (replaceInRead) coordinator.disconnect(this);
            return new WorkspaceStatus(
                WorkspaceStatus.Availability.AVAILABLE,
                Optional.of(animation),
                List.of(animation),
                Optional.empty()
            );
        }

        @Override public WorkspaceOperationResult.Outcome switchTo(final WorkspaceId workspaceId) {
            switchCount++;
            if (replaceAndThrow) {
                coordinator.disconnect(this);
                throw new IllegalStateException("host switch failed");
            }
            return WorkspaceOperationResult.Outcome.CHANGED;
        }

        @Override public WorkspaceOperationResult.Outcome updateDefault() {
            return WorkspaceOperationResult.Outcome.CHANGED;
        }

        @Override public WorkspaceOperationResult.Outcome resetToDefault() {
            return WorkspaceOperationResult.Outcome.CHANGED;
        }
    }

    private static final class ThrowingProvider implements WorkspaceHostProvider {
        @Override public WorkspaceStatus readStatus() {
            throw new IllegalStateException("host read failed");
        }
        @Override public WorkspaceOperationResult.Outcome switchTo(final WorkspaceId workspaceId) {
            throw new IllegalStateException("host switch failed");
        }
        @Override public WorkspaceOperationResult.Outcome updateDefault() {
            throw new IllegalStateException("host update failed");
        }
        @Override public WorkspaceOperationResult.Outcome resetToDefault() {
            throw new IllegalStateException("host reset failed");
        }
    }

    private static final class RecordingProvider implements WorkspaceHostProvider {
        private final WorkspaceInfo modeling = new WorkspaceInfo(new WorkspaceId("modeling"), "Modeling");
        private final WorkspaceInfo animation = new WorkspaceInfo(new WorkspaceId("animation"), "Animation");
        private WorkspaceInfo current = modeling;
        private boolean allCallsOnEdt = true;
        private int switchCount;
        private int updateCount;
        private int readCount;

        @Override public WorkspaceStatus readStatus() {
            recordThread();
            readCount++;
            return new WorkspaceStatus(
                WorkspaceStatus.Availability.AVAILABLE,
                Optional.of(current),
                List.of(modeling, animation),
                Optional.empty()
            );
        }

        @Override public WorkspaceOperationResult.Outcome switchTo(final WorkspaceId workspaceId) {
            recordThread();
            if (current.id().equals(workspaceId)) return WorkspaceOperationResult.Outcome.NO_CHANGE;
            if (!animation.id().equals(workspaceId) && !modeling.id().equals(workspaceId)) {
                return WorkspaceOperationResult.Outcome.NOT_FOUND;
            }
            switchCount++;
            current = animation.id().equals(workspaceId) ? animation : modeling;
            return WorkspaceOperationResult.Outcome.CHANGED;
        }

        @Override public WorkspaceOperationResult.Outcome updateDefault() {
            recordThread();
            updateCount++;
            return WorkspaceOperationResult.Outcome.CHANGED;
        }

        @Override public WorkspaceOperationResult.Outcome resetToDefault() {
            recordThread();
            return WorkspaceOperationResult.Outcome.CHANGED;
        }

        private void recordThread() {
            allCallsOnEdt &= SwingUtilities.isEventDispatchThread();
        }
    }
}

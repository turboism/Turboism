package dev.turboism.ui.workspace.layout;

import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.ui.workspace.layout.WorkspaceLayoutSnapshot;
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

class WorkspaceLayoutCoordinatorTest {

    @Test
    void readsOnlyOnEdtThroughTheService() throws Exception {
        RecordingProvider provider = new RecordingProvider();
        WorkspaceLayoutCoordinator coordinator = new WorkspaceLayoutCoordinator();
        coordinator.connect(provider);
        RuntimeWorkspaceLayoutService service = service(coordinator);

        WorkspaceLayoutSnapshot snapshot = service.current().toCompletableFuture().join();
        SwingUtilities.invokeAndWait(() -> { });

        assertEquals(WorkspaceLayoutSnapshot.Availability.AVAILABLE, snapshot.availability());
        assertEquals(List.of("tab-a"), tabs(snapshot.root().orElseThrow()));
        assertTrue(provider.allCallsOnEdt);
        assertEquals(1, provider.readCount);
    }

    @Test
    void unconnectedAndDisconnectedCoordinatorsFailClosed() {
        WorkspaceLayoutCoordinator coordinator = new WorkspaceLayoutCoordinator();

        assertEquals(
            WorkspaceLayoutSnapshot.Availability.UNAVAILABLE,
            coordinator.current().availability()
        );
        assertEquals(
            Optional.of("workspace.layout.provider.unavailable"),
            coordinator.current().diagnosticCode()
        );

        RecordingProvider provider = new RecordingProvider();
        coordinator.connect(provider);
        assertEquals(
            WorkspaceLayoutSnapshot.Availability.AVAILABLE,
            coordinator.current().availability()
        );
        coordinator.disconnect(provider);
        assertEquals(
            WorkspaceLayoutSnapshot.Availability.UNAVAILABLE,
            coordinator.current().availability()
        );
        assertEquals(1, provider.readCount, "disconnected reads must not touch the provider");
    }

    @Test
    void closedCoordinatorFailsClosedEvenWithAConnectedProvider() {
        RecordingProvider provider = new RecordingProvider();
        WorkspaceLayoutCoordinator coordinator = new WorkspaceLayoutCoordinator();
        coordinator.connect(provider);
        coordinator.close();

        assertEquals(
            WorkspaceLayoutSnapshot.Availability.UNAVAILABLE,
            coordinator.current().availability()
        );
        assertEquals(0, provider.readCount);
    }

    @Test
    void scopeCloseMakesCapturedServiceUnavailableWithoutTouchingProvider() throws Exception {
        RecordingProvider provider = new RecordingProvider();
        WorkspaceLayoutCoordinator coordinator = new WorkspaceLayoutCoordinator();
        coordinator.connect(provider);
        RuntimeWorkspaceLayoutService service = service(coordinator);
        DisposableScope scope = new DisposableScope();
        scope.register(service);
        scope.close();

        WorkspaceLayoutSnapshot snapshot = service.current().toCompletableFuture().get(1, TimeUnit.SECONDS);
        assertEquals(WorkspaceLayoutSnapshot.Availability.UNAVAILABLE, snapshot.availability());
        assertEquals(Optional.of("workspace.layout.unavailable"), snapshot.diagnosticCode());
        assertEquals(0, provider.readCount, "closed service must never touch the provider");
    }

    @Test
    void queuedReadAfterDisconnectNeverTouchesOldProvider() throws Exception {
        RecordingProvider provider = new RecordingProvider();
        WorkspaceLayoutCoordinator coordinator = new WorkspaceLayoutCoordinator();
        coordinator.connect(provider);
        RuntimeWorkspaceLayoutService service = service(coordinator);
        CountDownLatch edtRelease = blockEdt();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<Thread> workerRef = new AtomicReference<>();
        CompletableFuture<WorkspaceLayoutSnapshot> queued = CompletableFuture.supplyAsync(() -> {
            workerRef.set(Thread.currentThread());
            return service.current().toCompletableFuture().join();
        }, executor);
        try {
            awaitState(workerRef, Thread.State.WAITING, "current queued on the blocked EDT");
            coordinator.disconnect(provider);
        } finally {
            edtRelease.countDown();
        }

        try {
            assertEquals(
                WorkspaceLayoutSnapshot.Availability.UNAVAILABLE,
                queued.get(5, TimeUnit.SECONDS).availability()
            );
            assertEquals(0, provider.readCount, "queued read must not touch the disconnected provider");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void providerExceptionsFailClosedAsTypedSnapshots() {
        ThrowingProvider provider = new ThrowingProvider();
        WorkspaceLayoutCoordinator coordinator = new WorkspaceLayoutCoordinator();
        coordinator.connect(provider);

        WorkspaceLayoutSnapshot snapshot = coordinator.current();
        assertEquals(WorkspaceLayoutSnapshot.Availability.UNAVAILABLE, snapshot.availability());
        assertEquals(Optional.empty(), snapshot.root());
    }

    @Test
    void permissionCheckRunsOnCallerThreadWhileHostReadsStayOnEdt() throws Exception {
        RecordingProvider provider = new RecordingProvider();
        WorkspaceLayoutCoordinator coordinator = new WorkspaceLayoutCoordinator();
        coordinator.connect(provider);
        AtomicReference<Thread> edtThread = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> edtThread.set(Thread.currentThread()));
        AtomicReference<Thread> permissionThread = new AtomicReference<>();
        RuntimeWorkspaceLayoutService service = new RuntimeWorkspaceLayoutService(
            (permission, operation) -> permissionThread.set(Thread.currentThread()),
            coordinator
        );

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            CompletableFuture<WorkspaceLayoutSnapshot> snapshot = CompletableFuture.supplyAsync(
                () -> service.current().toCompletableFuture().join(), executor);
            assertEquals(
                WorkspaceLayoutSnapshot.Availability.AVAILABLE,
                snapshot.get(5, TimeUnit.SECONDS).availability()
            );
            assertFalse(permissionThread.get() == edtThread.get(),
                () -> "permission check must run on the caller thread, not the EDT");
            assertTrue(provider.allCallsOnEdt, "host reads must still run on the EDT");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void deniedPermissionThrowsOnTheCallerThreadWithoutTouchingTheProvider() {
        RecordingProvider provider = new RecordingProvider();
        WorkspaceLayoutCoordinator coordinator = new WorkspaceLayoutCoordinator();
        coordinator.connect(provider);
        RuntimeWorkspaceLayoutService denied = new RuntimeWorkspaceLayoutService(
            (permission, operation) -> { throw new CubismPermissionException("denied"); },
            coordinator
        );

        assertThrows(CubismPermissionException.class, () -> denied.current());
        assertEquals(0, provider.readCount, "denied current() must not touch the provider");
    }

    @Test
    void reentrantReplacementFailsClosedWithoutStaleState() {
        WorkspaceLayoutCoordinator coordinator = new WorkspaceLayoutCoordinator();
        ReentrantProvider provider = new ReentrantProvider(coordinator);
        coordinator.connect(provider);

        WorkspaceLayoutSnapshot snapshot = coordinator.current();
        assertEquals(WorkspaceLayoutSnapshot.Availability.UNAVAILABLE, snapshot.availability(),
            "a read that reentrantly disconnects its provider must not return stale state");
        assertEquals(Optional.of("workspace.layout.provider.unavailable"), snapshot.diagnosticCode());
    }

    @Test
    void queuedServiceReadAfterCloseNeverTouchesProvider() throws Exception {
        RecordingProvider provider = new RecordingProvider();
        WorkspaceLayoutCoordinator coordinator = new WorkspaceLayoutCoordinator();
        coordinator.connect(provider);
        RuntimeWorkspaceLayoutService service = service(coordinator);
        CountDownLatch edtRelease = blockEdt();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<Thread> workerRef = new AtomicReference<>();
        CompletableFuture<WorkspaceLayoutSnapshot> queued = CompletableFuture.supplyAsync(() -> {
            workerRef.set(Thread.currentThread());
            return service.current().toCompletableFuture().join();
        }, executor);
        try {
            awaitState(workerRef, Thread.State.WAITING, "current queued on the blocked EDT");

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
                WorkspaceLayoutSnapshot.Availability.UNAVAILABLE,
                queued.get(5, TimeUnit.SECONDS).availability(),
                "an operation queued before close executes after close as typed UNAVAILABLE"
            );
            closing.get(5, TimeUnit.SECONDS);
            assertEquals(0, provider.readCount, "closed service must never touch the provider");
        } finally {
            edtRelease.countDown();
            executor.shutdownNow();
        }
    }

    private static RuntimeWorkspaceLayoutService service(final WorkspaceLayoutCoordinator coordinator) {
        return new RuntimeWorkspaceLayoutService(
            (permission, operation) -> { },
            coordinator
        );
    }

    private static List<String> tabs(final dev.turboism.sdk.ui.workspace.layout.DockComponent component) {
        return ((dev.turboism.sdk.ui.workspace.layout.PaletteDock) component).tabs().stream()
            .map(dev.turboism.sdk.ui.workspace.layout.PaletteTab::paletteId)
            .toList();
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

    private static final class ReentrantProvider implements WorkspaceLayoutHostProvider {
        private final WorkspaceLayoutCoordinator coordinator;

        ReentrantProvider(final WorkspaceLayoutCoordinator coordinator) {
            this.coordinator = coordinator;
        }

        @Override
        public WorkspaceLayoutSnapshot readLayout() {
            coordinator.disconnect(this);
            return new WorkspaceLayoutSnapshot(
                WorkspaceLayoutSnapshot.Availability.AVAILABLE,
                Optional.of(new dev.turboism.sdk.ui.workspace.layout.PaletteDock(List.of())),
                Optional.empty()
            );
        }
    }

    private static final class ThrowingProvider implements WorkspaceLayoutHostProvider {
        @Override
        public WorkspaceLayoutSnapshot readLayout() {
            throw new IllegalStateException("host read failed");
        }
    }

    private static final class RecordingProvider implements WorkspaceLayoutHostProvider {
        private boolean allCallsOnEdt = true;
        private int readCount;

        @Override
        public WorkspaceLayoutSnapshot readLayout() {
            allCallsOnEdt &= SwingUtilities.isEventDispatchThread();
            readCount++;
            return new WorkspaceLayoutSnapshot(
                WorkspaceLayoutSnapshot.Availability.AVAILABLE,
                Optional.of(new dev.turboism.sdk.ui.workspace.layout.PaletteDock(
                    List.of(new dev.turboism.sdk.ui.workspace.layout.PaletteTab("tab-a"))
                )),
                Optional.empty()
            );
        }
    }
}

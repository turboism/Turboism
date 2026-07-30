package dev.turboism.adapter.host;

import dev.turboism.adapter.RuntimeHostAdapters;
import dev.turboism.adapter.cubism.ProjectWorkspaceAdapter;
import dev.turboism.adapter.ui.StatusToolbarAdapter;
import dev.turboism.adapter.ui.UiSurfaceAdapter;
import dev.turboism.sdk.cubism.ProjectSnapshot;
import dev.turboism.sdk.cubism.WorkspaceSnapshot;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.DialogRequest;
import dev.turboism.sdk.ui.FileChooserRequest;
import dev.turboism.sdk.ui.StatusNotification;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class HostSessionConcurrencyTest {

    @Test
    void closeWaitsForBlockedAdapterCallAndClosesReturnedRegistrationBeforeConnection() throws Exception {
        AtomicReference<HostInstanceDescriptor> current = new AtomicReference<>(HostSessionTest.descriptor("session-a"));
        CountDownLatch operationEntered = new CountDownLatch(1);
        CountDownLatch releaseOperation = new CountDownLatch(1);
        AtomicInteger registrationCloses = new AtomicInteger();
        AtomicInteger connectionCloses = new AtomicInteger();
        AtomicReference<Throwable> callFailure = new AtomicReference<>();
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        RuntimeHostAdapters safe = RuntimeHostAdapters.safeMode();
        StatusToolbarAdapter blockingStatus = HostSessionTest.statusAdapter(registrationCloses::incrementAndGet);
        StatusToolbarAdapter hostStatus = new StatusToolbarAdapter() {
            @Override public AdapterResult<Registration> notifyStatus(StatusNotification ignored) {
                operationEntered.countDown();
                HostSessionTest.awaitLatch(releaseOperation);
                return blockingStatus.notifyStatus(ignored);
            }
        };
        HostSession session = new HostSession(
            () -> Optional.ofNullable(current.get()),
            ignored -> HostSessionTest.connection(new RuntimeHostAdapters(
                safe.themeStatus(), safe.renderStatus(), safe.projectWorkspace(), safe.clipMaskRead(),
                hostStatus, safe.uiSurface()
            ), connectionCloses)
        );
        session.refresh();

        Thread call = new Thread(() -> HostSessionTest.captureFailure(() -> session.adapters().statusToolbar().notifyStatus(
            new StatusNotification("status-1", "INFO", "Connected")
        ), callFailure), "blocked-adapter-call");
        call.start();
        assertTrue(operationEntered.await(5, TimeUnit.SECONDS));
        Thread closing = new Thread(() -> HostSessionTest.captureFailure(session::close, closeFailure), "session-close");
        closing.start();
        HostSessionTest.awaitWaiting(closing);

        assertEquals(0, connectionCloses.get());
        assertFalse(session.adapters().projectWorkspace().activeProject().isAvailable());
        releaseOperation.countDown();
        HostSessionTest.join(call);
        HostSessionTest.join(closing);

        assertNull(callFailure.get());
        assertNull(closeFailure.get());
        assertEquals(1, registrationCloses.get());
        assertEquals(1, connectionCloses.get());
        assertEquals(HostSession.State.CLOSED, session.state());
    }

    @Test
    void replacementWaitsForBlockedOldCallBeforeClosingOldConnection() throws Exception {
        AtomicReference<HostInstanceDescriptor> current = new AtomicReference<>(HostSessionTest.descriptor("session-a"));
        CountDownLatch operationEntered = new CountDownLatch(1);
        CountDownLatch releaseOperation = new CountDownLatch(1);
        AtomicInteger oldConnectionCloses = new AtomicInteger();
        AtomicReference<Throwable> callFailure = new AtomicReference<>();
        AtomicReference<Throwable> replacementFailure = new AtomicReference<>();
        RuntimeHostAdapters safe = RuntimeHostAdapters.safeMode();
        HostSession session = new HostSession(
            () -> Optional.ofNullable(current.get()),
            descriptor -> descriptor.sessionId().equals("session-a")
                ? HostSessionTest.connection(new RuntimeHostAdapters(
                    safe.themeStatus(), safe.renderStatus(), ProjectWorkspaceAdapter.Impl.connected(
                        new ProjectWorkspaceAdapter.HostOperations() {
                            @Override public String hostVersion() { return "5.3.02"; }
                            @Override public boolean supportsProjectWorkspaceRead() { return true; }
                            @Override public Optional<ProjectSnapshot> activeProject() {
                                operationEntered.countDown();
                                HostSessionTest.awaitLatch(releaseOperation);
                                return Optional.of(new ProjectSnapshot("session-a", "Demo", Optional.empty(), List.of()));
                            }
                            @Override public Optional<WorkspaceSnapshot> workspace() { return Optional.empty(); }
                        }
                    ), safe.clipMaskRead(), safe.statusToolbar(), safe.uiSurface()
                ), oldConnectionCloses)
                : HostAdapterConnection.of(HostSessionTest.adapters("session-b"))
        );
        session.refresh();
        Thread call = new Thread(() -> HostSessionTest.captureFailure(
            () -> session.adapters().projectWorkspace().activeProject(), callFailure
        ), "blocked-project-call");
        call.start();
        assertTrue(operationEntered.await(5, TimeUnit.SECONDS));

        current.set(HostSessionTest.descriptor("session-b"));
        Thread replacement = new Thread(() -> HostSessionTest.captureFailure(session::refresh, replacementFailure), "replacement");
        replacement.start();
        HostSessionTest.awaitWaiting(replacement);
        assertEquals(0, oldConnectionCloses.get());
        assertFalse(session.adapters().projectWorkspace().activeProject().isAvailable());

        releaseOperation.countDown();
        HostSessionTest.join(call);
        HostSessionTest.join(replacement);
        assertNull(callFailure.get());
        assertNull(replacementFailure.get());
        assertEquals(1, oldConnectionCloses.get());
        assertEquals("session-b", session.adapters().projectWorkspace().activeProject()
            .value().orElseThrow().orElseThrow().projectId());
    }

    @Test
    void replacementFallsBackBeforeBlockingConnectionAndClosesOldConnectionOnce() throws Exception {
        AtomicReference<HostInstanceDescriptor> current = new AtomicReference<>(HostSessionTest.descriptor("session-a"));
        AtomicInteger oldConnectionCloses = new AtomicInteger();
        CountDownLatch replacementStarted = new CountDownLatch(1);
        CountDownLatch allowReplacement = new CountDownLatch(1);
        HostSession session = new HostSession(
            () -> Optional.ofNullable(current.get()),
            descriptor -> {
                if (descriptor.sessionId().equals("session-b")) {
                    replacementStarted.countDown();
                    if (!allowReplacement.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("replacement timed out");
                    }
                    return HostAdapterConnection.of(HostSessionTest.adapters("session-b"));
                }
                return HostSessionTest.connection(HostSessionTest.adapters("session-a"), oldConnectionCloses);
            }
        );
        assertEquals(HostSession.State.ACTIVE, session.refresh());

        current.set(HostSessionTest.descriptor("session-b"));
        Thread replacement = new Thread(session::refresh, "host-session-replacement-test");
        replacement.start();
        assertTrue(replacementStarted.await(5, TimeUnit.SECONDS));

        assertFalse(session.adapters().projectWorkspace().activeProject().isAvailable());
        assertEquals(1, oldConnectionCloses.get());

        allowReplacement.countDown();
        HostSessionTest.join(replacement);
        assertEquals("session-b", session.adapters().projectWorkspace().activeProject()
            .value().orElseThrow().orElseThrow().projectId());

        current.set(null);
        session.refresh();
        assertEquals(1, oldConnectionCloses.get());
    }

    @Test
    void registrationCleanupRunsOutsideLifecycleWriteLock() throws Exception {
        AtomicReference<HostInstanceDescriptor> current = new AtomicReference<>(HostSessionTest.descriptor("session-a"));
        AtomicReference<HostSession> sessionRef = new AtomicReference<>();
        AtomicReference<Boolean> callbackSawSafeMode = new AtomicReference<>(false);
        RuntimeHostAdapters safe = RuntimeHostAdapters.safeMode();
        StatusToolbarAdapter statusToolbar = new StatusToolbarAdapter() {
            @Override public AdapterResult<Registration> notifyStatus(StatusNotification notification) {
                return AdapterResult.available(() -> {
                    Thread callback = new Thread(() -> callbackSawSafeMode.set(
                        !sessionRef.get().adapters().projectWorkspace().activeProject().isAvailable()
                    ));
                    callback.start();
                    try {
                        callback.join(2_000);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(exception);
                    }
                    if (callback.isAlive()) {
                        throw new IllegalStateException("callback blocked on lifecycle lock");
                    }
                });
            }
        };
        HostSession session = new HostSession(
            () -> Optional.ofNullable(current.get()),
            descriptor -> HostAdapterConnection.of(new RuntimeHostAdapters(
                safe.themeStatus(), safe.renderStatus(), HostSessionTest.adapters("session-a").projectWorkspace(),
                safe.clipMaskRead(), statusToolbar, safe.uiSurface()
            ))
        );
        sessionRef.set(session);
        session.refresh();
        session.adapters().statusToolbar().notifyStatus(new StatusNotification("status-1", "INFO", "Connected"));

        current.set(null);
        assertEquals(HostSession.State.SAFE_MODE, session.refresh());
        assertEquals(Boolean.TRUE, callbackSawSafeMode.get());
    }

    @Test
    void sourceAndCleanupCallbacksCanReadStateFromAnotherThreadWithoutDeadlock() {
        AtomicReference<HostSession> sessionRef = new AtomicReference<>();
        AtomicReference<HostInstanceDescriptor> current = new AtomicReference<>(HostSessionTest.descriptor("session-a"));
        AtomicInteger closes = new AtomicInteger();
        HostSession session = new HostSession(
            () -> {
                HostSessionTest.assertCallbackCanReadState(sessionRef.get());
                return Optional.ofNullable(current.get());
            },
            ignored -> new HostAdapterConnection() {
                @Override public RuntimeHostAdapters adapters() { return HostSessionTest.adapters("session-a"); }
                @Override public void close() {
                    HostSessionTest.assertCallbackCanReadState(sessionRef.get());
                    closes.incrementAndGet();
                }
            }
        );
        sessionRef.set(session);
        assertEquals(HostSession.State.ACTIVE, session.refresh());
        current.set(null);
        assertEquals(HostSession.State.SAFE_MODE, session.refresh());
        assertEquals(1, closes.get());
    }

    @Test
    void concurrentRegistrationCloseWaitsForOwnerAndPropagatesFailureThenRetries() throws Exception {
        AtomicReference<HostInstanceDescriptor> current = new AtomicReference<>(HostSessionTest.descriptor("session-a"));
        AtomicInteger delegateCloses = new AtomicInteger();
        CountDownLatch ownerEntered = new CountDownLatch(1);
        CountDownLatch releaseOwner = new CountDownLatch(1);
        RuntimeHostAdapters safe = RuntimeHostAdapters.safeMode();
        StatusToolbarAdapter status = HostSessionTest.statusAdapter(() -> {
            int attempt = delegateCloses.incrementAndGet();
            ownerEntered.countDown();
            HostSessionTest.awaitLatch(releaseOwner);
            if (attempt == 1) {
                throw new IllegalStateException("first-close-failed");
            }
        });
        HostSession session = new HostSession(
            () -> Optional.ofNullable(current.get()),
            ignored -> HostAdapterConnection.of(new RuntimeHostAdapters(
                safe.themeStatus(), safe.renderStatus(), safe.projectWorkspace(), safe.clipMaskRead(),
                status, safe.uiSurface()
            ))
        );
        session.refresh();
        Registration registration = session.adapters().statusToolbar().notifyStatus(
            new StatusNotification("status-1", "INFO", "Connected")
        ).value().orElseThrow();
        AtomicReference<Throwable> ownerFailure = new AtomicReference<>();
        AtomicReference<Throwable> waiterFailure = new AtomicReference<>();
        Thread owner = new Thread(() -> HostSessionTest.captureFailure(registration::close, ownerFailure));
        Thread waiter = new Thread(() -> HostSessionTest.captureFailure(registration::close, waiterFailure));

        owner.start();
        assertTrue(ownerEntered.await(5, TimeUnit.SECONDS));
        waiter.start();
        HostSessionTest.awaitWaiting(waiter);
        releaseOwner.countDown();
        HostSessionTest.join(owner);
        HostSessionTest.join(waiter);

        assertEquals(1, delegateCloses.get());
        assertTrue(ownerFailure.get() instanceof IllegalStateException);
        assertTrue(waiterFailure.get() instanceof IllegalStateException);
        registration.close();
        assertEquals(2, delegateCloses.get());
    }

    @Test
    void trackedRegistrationErrorWakesWaiterAndRemainsRetryable() throws Exception {
        AtomicReference<HostInstanceDescriptor> current = new AtomicReference<>(HostSessionTest.descriptor("session-a"));
        AtomicInteger delegateCloses = new AtomicInteger();
        CountDownLatch ownerEntered = new CountDownLatch(1);
        CountDownLatch releaseOwner = new CountDownLatch(1);
        RuntimeHostAdapters safe = RuntimeHostAdapters.safeMode();
        HostSession session = new HostSession(
            () -> Optional.ofNullable(current.get()),
            ignored -> HostAdapterConnection.of(new RuntimeHostAdapters(
                safe.themeStatus(), safe.renderStatus(), safe.projectWorkspace(), safe.clipMaskRead(),
                HostSessionTest.statusAdapter(() -> {
                    int attempt = delegateCloses.incrementAndGet();
                    ownerEntered.countDown();
                    HostSessionTest.awaitLatch(releaseOwner);
                    if (attempt == 1) {
                        throw new AssertionError("first-close-error");
                    }
                }), safe.uiSurface()
            ))
        );
        session.refresh();
        Registration registration = session.adapters().statusToolbar().notifyStatus(
            new StatusNotification("status-1", "INFO", "Connected")
        ).value().orElseThrow();
        AtomicReference<Throwable> ownerFailure = new AtomicReference<>();
        AtomicReference<Throwable> waiterFailure = new AtomicReference<>();
        Thread owner = new Thread(() -> HostSessionTest.captureFailure(registration::close, ownerFailure));
        Thread waiter = new Thread(() -> HostSessionTest.captureFailure(registration::close, waiterFailure));
        owner.start();
        assertTrue(ownerEntered.await(5, TimeUnit.SECONDS));
        waiter.start();
        HostSessionTest.awaitWaiting(waiter);
        releaseOwner.countDown();
        HostSessionTest.join(owner);
        HostSessionTest.join(waiter);

        assertTrue(ownerFailure.get() instanceof AssertionError);
        assertTrue(waiterFailure.get() instanceof AssertionError);
        registration.close();
        assertEquals(2, delegateCloses.get());
    }

    @Test
    void sessionAndOrdinaryRegistrationCloseDoNotDeadlockOrDoubleClose() throws Exception {
        AtomicReference<HostInstanceDescriptor> current = new AtomicReference<>(HostSessionTest.descriptor("session-a"));
        AtomicInteger delegateCloses = new AtomicInteger();
        CountDownLatch ownerEntered = new CountDownLatch(1);
        CountDownLatch releaseOwner = new CountDownLatch(1);
        RuntimeHostAdapters safe = RuntimeHostAdapters.safeMode();
        HostSession session = new HostSession(
            () -> Optional.ofNullable(current.get()),
            ignored -> HostAdapterConnection.of(new RuntimeHostAdapters(
                safe.themeStatus(), safe.renderStatus(), safe.projectWorkspace(), safe.clipMaskRead(),
                HostSessionTest.statusAdapter(() -> {
                    delegateCloses.incrementAndGet();
                    ownerEntered.countDown();
                    HostSessionTest.awaitLatch(releaseOwner);
                }), safe.uiSurface()
            ))
        );
        session.refresh();
        Registration registration = session.adapters().statusToolbar().notifyStatus(
            new StatusNotification("status-1", "INFO", "Connected")
        ).value().orElseThrow();
        Thread ordinary = new Thread(registration::close);
        ordinary.start();
        assertTrue(ownerEntered.await(5, TimeUnit.SECONDS));
        current.set(null);
        Thread clearing = new Thread(session::refresh);
        clearing.start();
        releaseOwner.countDown();
        HostSessionTest.join(ordinary);
        HostSessionTest.join(clearing);

        assertEquals(1, delegateCloses.get());
        assertEquals(HostSession.State.SAFE_MODE, session.state());
    }

    @Test
    void closeIntentPreventsQueuedRefreshFromCallingSource() throws Exception {
        AtomicInteger sourceCalls = new AtomicInteger();
        CountDownLatch firstSourceEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstSource = new CountDownLatch(1);
        AtomicReference<Throwable> refreshAFailure = new AtomicReference<>();
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        AtomicReference<Throwable> refreshBFailure = new AtomicReference<>();
        HostSession session = new HostSession(
            () -> {
                sourceCalls.incrementAndGet();
                firstSourceEntered.countDown();
                HostSessionTest.awaitLatch(releaseFirstSource);
                return Optional.empty();
            },
            ignored -> fail("connector must not run")
        );
        Thread refreshA = new Thread(() -> HostSessionTest.captureFailure(session::refresh, refreshAFailure), "refresh-a");
        Thread closing = new Thread(() -> HostSessionTest.captureFailure(session::close, closeFailure), "close");
        Thread refreshB = new Thread(() -> HostSessionTest.captureFailure(session::refresh, refreshBFailure), "refresh-b");
        refreshA.start();
        assertTrue(firstSourceEntered.await(5, TimeUnit.SECONDS));
        closing.start();
        HostSessionTest.awaitWaiting(closing);
        refreshB.start();
        HostSessionTest.join(refreshB);
        releaseFirstSource.countDown();
        HostSessionTest.join(refreshA);
        HostSessionTest.join(closing);

        assertNull(refreshAFailure.get());
        assertNull(closeFailure.get());
        assertNull(refreshBFailure.get());
        assertEquals(1, sourceCalls.get());
        assertEquals(HostSession.State.CLOSED, session.state());
    }

    @Test
    void sameThreadSourceAndConnectorRefreshReentryDoesNotWait() {
        AtomicReference<HostSession> sourceSession = new AtomicReference<>();
        AtomicReference<HostSession> connectorSession = new AtomicReference<>();
        HostSession session = new HostSession(
            () -> {
                assertEquals(HostSession.State.SAFE_MODE, sourceSession.get().refresh());
                return Optional.of(HostSessionTest.descriptor("session-a"));
            },
            ignored -> {
                assertEquals(HostSession.State.SAFE_MODE, connectorSession.get().refresh());
                return HostAdapterConnection.of(HostSessionTest.adapters("session-a"));
            }
        );
        sourceSession.set(session);
        connectorSession.set(session);
        assertEquals(HostSession.State.ACTIVE, session.refresh());
    }

    @Test
    void sameThreadCloseDuringRefreshRecordsIntentAndPreventsConnection() {
        AtomicReference<HostSession> sessionRef = new AtomicReference<>();
        AtomicInteger connections = new AtomicInteger();
        HostSession session = new HostSession(
            () -> {
                sessionRef.get().close();
                return Optional.of(HostSessionTest.descriptor("session-a"));
            },
            ignored -> {
                connections.incrementAndGet();
                return HostAdapterConnection.of(HostSessionTest.adapters("session-a"));
            }
        );
        sessionRef.set(session);
        assertEquals(HostSession.State.CLOSED, session.refresh());
        assertEquals(0, connections.get());
    }

    @Test
    void registrationAndConnectionCloseCallbacksMayRefreshSameThread() {
        AtomicReference<HostInstanceDescriptor> current = new AtomicReference<>(HostSessionTest.descriptor("session-a"));
        AtomicReference<HostSession> sessionRef = new AtomicReference<>();
        AtomicInteger callbackRefreshes = new AtomicInteger();
        RuntimeHostAdapters safe = RuntimeHostAdapters.safeMode();
        HostSession session = new HostSession(
            () -> Optional.ofNullable(current.get()),
            ignored -> new HostAdapterConnection() {
                @Override public RuntimeHostAdapters adapters() {
                    return new RuntimeHostAdapters(
                        safe.themeStatus(), safe.renderStatus(), safe.projectWorkspace(), safe.clipMaskRead(),
                        HostSessionTest.statusAdapter(() -> {
                            sessionRef.get().refresh();
                            callbackRefreshes.incrementAndGet();
                        }), safe.uiSurface()
                    );
                }
                @Override public void close() {
                    sessionRef.get().refresh();
                    callbackRefreshes.incrementAndGet();
                }
            }
        );
        sessionRef.set(session);
        session.refresh();
        session.adapters().statusToolbar().notifyStatus(new StatusNotification("status-1", "INFO", "Connected"));
        current.set(null);
        assertEquals(HostSession.State.SAFE_MODE, session.refresh());
        assertEquals(2, callbackRefreshes.get());
    }

    @Test
    void adapterOperationTriggeredCloseIsDeferredUntilOutermostCallCompletes() {
        AtomicReference<HostSession> sessionRef = new AtomicReference<>();
        AtomicInteger registrationCloses = new AtomicInteger();
        AtomicInteger connectionCloses = new AtomicInteger();
        RuntimeHostAdapters safe = RuntimeHostAdapters.safeMode();
        HostSession session = new HostSession(
            () -> Optional.of(HostSessionTest.descriptor("session-a")),
            ignored -> HostSessionTest.connection(new RuntimeHostAdapters(
                safe.themeStatus(), safe.renderStatus(), safe.projectWorkspace(), safe.clipMaskRead(),
                HostSessionTest.statusAdapter(() -> { }),                 new UiSurfaceAdapter() {
                    @Override public AdapterResult<Registration> openDialog(DialogRequest ignored) {
                        sessionRef.get().close();
                        return AdapterResult.available(registrationCloses::incrementAndGet);
                    }
                    @Override public AdapterResult<Boolean> confirmDialog(DialogRequest ignored) { return AdapterResult.available(false); }
                    @Override public AdapterResult<Optional<String>> requestFile(FileChooserRequest ignored) { return AdapterResult.available(Optional.empty()); }
                }
            ), connectionCloses)
        );
        sessionRef.set(session);
        session.refresh();
        session.adapters().uiSurface().openDialog(new DialogRequest("dialog", "Dialog", "Body"));
        assertEquals(HostSession.State.CLOSED, session.state());
        assertEquals(1, registrationCloses.get());
        assertEquals(1, connectionCloses.get());
        assertFalse(session.adapters().projectWorkspace().activeProject().isAvailable());
    }

    @Test
    void adapterTriggeredRefreshIsRejectedWithoutChangingActiveState() {
        AtomicReference<HostSession> sessionRef = new AtomicReference<>();
        AtomicReference<HostSession.State> reentrantResult = new AtomicReference<>();
        RuntimeHostAdapters safe = RuntimeHostAdapters.safeMode();
        HostSession session = new HostSession(
            () -> Optional.of(HostSessionTest.descriptor("session-a")),
            ignored -> HostAdapterConnection.of(new RuntimeHostAdapters(
                safe.themeStatus(), safe.renderStatus(), safe.projectWorkspace(), safe.clipMaskRead(),
                HostSessionTest.statusAdapter(() -> reentrantResult.set(sessionRef.get().refresh())),
                safe.uiSurface()
            ))
        );
        sessionRef.set(session);
        session.refresh();
        session.adapters().statusToolbar().notifyStatus(
            new StatusNotification("status-1", "INFO", "Connected")
        ).value().orElseThrow().close();
        assertEquals(HostSession.State.ACTIVE, reentrantResult.get());
        assertEquals(HostSession.State.ACTIVE, session.state());
        assertTrue(session.lastFailure().isEmpty());
    }
}

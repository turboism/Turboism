package dev.turboism.adapter.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.turboism.adapter.RuntimeHostAdapters;
import dev.turboism.adapter.ui.StatusToolbarAdapter;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.StatusNotification;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class HostSessionCleanupErrorTest {

    @Test
    void registrationAssertionErrorCommitsFailedAndRemainsRetryable() {
        AtomicReference<HostInstanceDescriptor> current =
                new AtomicReference<>(HostSessionTest.descriptor("session-a"));
        AtomicInteger closes = new AtomicInteger();
        RuntimeHostAdapters safe = RuntimeHostAdapters.safeMode();
        StatusToolbarAdapter status = HostSessionTest.statusAdapter(() -> failFirst(closes));
        HostSession session = new HostSession(
                () -> Optional.ofNullable(current.get()),
                ignored -> HostAdapterConnection.of(new RuntimeHostAdapters(
                        safe.themeStatus(),
                        safe.renderStatus(),
                        safe.projectWorkspace(),
                        safe.clipMaskRead(),
                        status,
                        safe.uiSurface())));
        session.refresh();
        Registration registration = session.adapters()
                .statusToolbar()
                .notifyStatus(new StatusNotification("status", "INFO", "connected"))
                .value()
                .orElseThrow();

        assertThrows(AssertionError.class, session::close);
        assertCleanupFailed(session);
        session.close();

        assertEquals(2, closes.get());
        assertEquals(HostSession.State.CLOSED, session.state());
        registration.close();
        assertEquals(2, closes.get());
    }

    @Test
    void activeConnectionAssertionErrorCommitsFailedAndRemainsRetryable() {
        AtomicInteger closes = new AtomicInteger();
        HostSession session = new HostSession(
                () -> Optional.of(HostSessionTest.descriptor("session-a")), ignored -> new HostAdapterConnection() {
                    @Override
                    public RuntimeHostAdapters adapters() {
                        return HostSessionTest.adapters("session-a");
                    }

                    @Override
                    public void close() {
                        failFirst(closes);
                    }
                });
        session.refresh();

        assertThrows(AssertionError.class, session::close);
        assertCleanupFailed(session);
        session.close();

        assertEquals(2, closes.get());
        assertEquals(HostSession.State.CLOSED, session.state());
    }

    @Test
    void candidateAssertionErrorCommitsFailedRetainsCandidateAndRemainsRetryable() {
        AtomicInteger closes = new AtomicInteger();
        HostSession session = new HostSession(
                () -> Optional.of(HostSessionTest.descriptor("session-a")), ignored -> new HostAdapterConnection() {
                    @Override
                    public RuntimeHostAdapters adapters() {
                        throw new IllegalStateException("candidate adapters failed");
                    }

                    @Override
                    public void close() {
                        failFirst(closes);
                    }
                });

        assertThrows(AssertionError.class, session::refresh);
        assertCleanupFailed(session);
        session.close();

        assertEquals(2, closes.get());
        assertEquals(HostSession.State.CLOSED, session.state());
    }

    @Test
    void closeIntentCandidateCleanupErrorIsNotReclassifiedOrDoubleClosed() {
        AtomicInteger closes = new AtomicInteger();
        AtomicReference<HostSession> sessionRef = new AtomicReference<>();
        HostSession session = new HostSession(
                () -> Optional.of(HostSessionTest.descriptor("session-a")), ignored -> new HostAdapterConnection() {
                    @Override
                    public RuntimeHostAdapters adapters() {
                        sessionRef.get().close();
                        return HostSessionTest.adapters("session-a");
                    }

                    @Override
                    public void close() {
                        failFirst(closes);
                    }
                });
        sessionRef.set(session);

        assertThrows(AssertionError.class, session::refresh);
        assertCleanupFailed(session);
        assertEquals(1, closes.get());

        session.close();
        assertEquals(2, closes.get());
        assertEquals(HostSession.State.CLOSED, session.state());
        session.close();
        assertEquals(2, closes.get());
    }

    @Test
    void coordinatorCleanupFailureDoesNotTruncateChainAndRemainsRetryable() {
        AtomicReference<HostInstanceDescriptor> current =
                new AtomicReference<>(HostSessionTest.descriptor("session-a"));
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        HostSession session = new HostSession(
                () -> Optional.ofNullable(current.get()),
                ignored -> HostAdapterConnection.of(HostSessionTest.adapters("session-a")));
        session.coordinatorCleanupStepsForTest = java.util.List.of(() -> failFirst(first), second::incrementAndGet);
        session.refresh();

        assertThrows(AssertionError.class, session::close);
        // A throwing coordinator must not truncate the chain: the step after it still ran.
        assertEquals(1, first.get());
        assertEquals(1, second.get());
        assertCleanupFailed(session);

        session.close();
        assertEquals(2, first.get());
        assertEquals(2, second.get());
        assertEquals(HostSession.State.CLOSED, session.state());
    }

    private static void failFirst(final AtomicInteger attempts) {
        if (attempts.incrementAndGet() == 1) {
            throw new AssertionError("first cleanup failed");
        }
    }

    private static void assertCleanupFailed(final HostSession session) {
        assertEquals(HostSession.State.FAILED, session.state());
        assertEquals(
                HostSessionFailure.Code.CLEANUP_FAILED,
                session.lastFailure().orElseThrow().code());
    }
}

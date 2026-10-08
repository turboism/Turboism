package dev.turboism.ui.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.GraphicsEnvironment;
import java.lang.reflect.InvocationTargetException;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Deterministic coverage of {@link EdtDispatch}: inline EDT execution, bounded acceptance
 * with abandon, interruption semantics, started-but-unfinished waits, the unresponsive
 * circuit breaker, process-exit mode, and deferred cleanup execution.
 */
class EdtDispatchTest {

    private static final Duration SHORT_ACCEPT = Duration.ofMillis(150);

    @AfterEach
    void resetDispatchState() {
        EdtDispatch.resetStateForTesting();
    }

    @Test
    void callRunsInlineOnTheEdt() throws Exception {
        final AtomicReference<Thread> edt = new AtomicReference<>();
        final AtomicReference<Thread> taskThread = new AtomicReference<>();
        final String[] result = new String[1];
        SwingUtilities.invokeAndWait(() -> {
            edt.set(Thread.currentThread());
            result[0] = EdtDispatch.call("inline", () -> {
                taskThread.set(Thread.currentThread());
                return "ok";
            });
        });
        assertEquals("ok", result[0]);
        assertSame(edt.get(), taskThread.get(), "call on the EDT must run inline");
    }

    @Test
    void callDeliversResultFromWorkerThread() {
        assertEquals(7, EdtDispatch.call("result", () -> 7));
    }

    @Test
    void dispatchedBodiesRunInsideAHostReadEpoch() throws Exception {
        final AtomicLong outerEpoch = new AtomicLong();
        final AtomicLong innerEpoch = new AtomicLong();
        final AtomicLong writesAfterNested = new AtomicLong(-1L);
        SwingUtilities.invokeAndWait(() -> EdtDispatch.call("outer-epoch", () -> {
            outerEpoch.set(HostReadEpoch.current());
            EdtDispatch.call("inner-epoch", () -> {
                innerEpoch.set(HostReadEpoch.current());
                return null;
            });
            writesAfterNested.set(HostReadEpoch.writes());
            return null;
        }));
        assertTrue(outerEpoch.get() != 0L, "a dispatched body must run inside a host-read epoch");
        assertTrue(
                innerEpoch.get() != 0L && innerEpoch.get() != outerEpoch.get(),
                "a nested dispatch must open a fresh epoch");
        assertEquals(
                1L, writesAfterNested.get(), "closing a nested body must mark the enclosing epoch as possibly mutated");
        assertEquals(0L, HostReadEpoch.current(), "no epoch may leak onto the caller thread");
    }

    @Test
    void queuedBodiesRunInsideAHostReadEpoch() {
        assertTrue(
                EdtDispatch.call("queued-epoch", () -> HostReadEpoch.current() != 0L),
                "a queued body must run inside a host-read epoch");
    }

    @Test
    void postedBodiesRunInsideAHostReadEpoch() throws Exception {
        final AtomicBoolean inEpoch = new AtomicBoolean();
        SwingUtilities.invokeAndWait(
                () -> EdtDispatch.post("posted-epoch", () -> inEpoch.set(HostReadEpoch.current() != 0L)));
        assertTrue(inEpoch.get(), "a posted task must run inside a host-read epoch");
    }

    @Test
    void acceptTimeoutAbandonsAndSkipsTheQueuedBody() throws Exception {
        final CountDownLatch wedged = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            wedged.countDown();
            await(release);
        });
        assertTrue(wedged.await(5, TimeUnit.SECONDS));
        try {
            final AtomicBoolean ran = new AtomicBoolean();
            final EdtDispatchException failure = assertThrows(
                    EdtDispatchException.class,
                    () -> EdtDispatch.call("abandon", SHORT_ACCEPT, () -> {
                        ran.set(true);
                        return null;
                    }));
            assertEquals(EdtDispatchException.Reason.ACCEPT_TIMEOUT, failure.reason());
            assertEquals("abandon", failure.label());
            release.countDown();
            drainEdt();
            assertFalse(ran.get(), "abandoned task must skip its body");
        } finally {
            release.countDown();
        }
    }

    @Test
    void interruptBeforeStartAbandonsAndSkipsTheBody() throws Exception {
        final CountDownLatch wedged = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            wedged.countDown();
            await(release);
        });
        assertTrue(wedged.await(5, TimeUnit.SECONDS));
        try {
            final AtomicBoolean ran = new AtomicBoolean();
            final CountDownLatch callerDone = new CountDownLatch(1);
            final AtomicReference<Throwable> outcome = new AtomicReference<>();
            final Thread caller = new Thread(
                    () -> {
                        try {
                            EdtDispatch.call("interrupted", Duration.ofSeconds(30), () -> {
                                ran.set(true);
                                return null;
                            });
                        } catch (Throwable failure) {
                            outcome.set(failure);
                        } finally {
                            callerDone.countDown();
                        }
                    },
                    "edt-dispatch-test-caller");
            caller.setDaemon(true);
            caller.start();
            // Interrupting before or during the acceptance wait is equivalent:
            // the await observes the pending interrupt either way.
            caller.interrupt();
            assertTrue(callerDone.await(5, TimeUnit.SECONDS));
            final Throwable failure = outcome.get();
            assertTrue(failure instanceof EdtDispatchException);
            assertEquals(EdtDispatchException.Reason.INTERRUPTED, ((EdtDispatchException) failure).reason());
            release.countDown();
            drainEdt();
            assertFalse(ran.get(), "interrupted caller's queued task must skip its body");
        } finally {
            release.countDown();
        }
    }

    @Test
    void startedTaskIsAwaitedBeyondTheAcceptBound() throws Exception {
        final CountDownLatch bodyStarted = new CountDownLatch(1);
        final CountDownLatch releaseBody = new CountDownLatch(1);
        final CountDownLatch callerDone = new CountDownLatch(1);
        final AtomicReference<Object> outcome = new AtomicReference<>();
        final Thread caller = new Thread(
                () -> {
                    try {
                        outcome.set(EdtDispatch.call("slow-body", SHORT_ACCEPT, () -> {
                            bodyStarted.countDown();
                            await(releaseBody);
                            return "finished";
                        }));
                    } finally {
                        callerDone.countDown();
                    }
                },
                "edt-dispatch-test-slow");
        caller.setDaemon(true);
        caller.start();
        try {
            assertTrue(bodyStarted.await(5, TimeUnit.SECONDS));
            // The accept bound has long expired but the caller must keep waiting once the
            // body has started: no timeout is legal after RUNNING.
            assertFalse(
                    callerDone.await(4 * SHORT_ACCEPT.toMillis(), TimeUnit.MILLISECONDS),
                    "caller must not time out after the task started");
            releaseBody.countDown();
            assertTrue(callerDone.await(5, TimeUnit.SECONDS));
            assertEquals("finished", outcome.get());
        } finally {
            releaseBody.countDown();
        }
    }

    @Test
    void postStartInterruptWithoutCompensationIsDeferredUntilCompletion() throws Exception {
        final CountDownLatch bodyStarted = new CountDownLatch(1);
        final CountDownLatch releaseBody = new CountDownLatch(1);
        final CountDownLatch callerDone = new CountDownLatch(1);
        final AtomicReference<Object> outcome = new AtomicReference<>();
        final AtomicBoolean interruptRestored = new AtomicBoolean();
        final Thread caller = new Thread(
                () -> {
                    try {
                        outcome.set(EdtDispatch.call("deferred-interrupt", Duration.ofSeconds(30), () -> {
                            bodyStarted.countDown();
                            await(releaseBody);
                            return "delivered";
                        }));
                    } finally {
                        interruptRestored.set(Thread.currentThread().isInterrupted());
                        callerDone.countDown();
                    }
                },
                "edt-dispatch-test-defer");
        caller.setDaemon(true);
        caller.start();
        try {
            assertTrue(bodyStarted.await(5, TimeUnit.SECONDS));
            caller.interrupt();
            assertFalse(
                    callerDone.await(300, TimeUnit.MILLISECONDS),
                    "post-start interrupt must not abandon a running task");
            releaseBody.countDown();
            assertTrue(callerDone.await(5, TimeUnit.SECONDS));
            assertEquals("delivered", outcome.get());
            assertTrue(interruptRestored.get(), "the deferred interrupt must be restored on the caller thread");
        } finally {
            releaseBody.countDown();
        }
    }

    @Test
    void postStartInterruptWithCompensationReleasesTheCaller() throws Exception {
        Assumptions.assumeFalse(
                GraphicsEnvironment.isHeadless(), "modal dialog handoff requires a visible AWT display");
        // A real modal Dialog blocks the EDT in a nested event pump that still dispatches
        // invokeLater work — the faithful stand-in for RuntimeChoiceDialogs.show(). A raw
        // latch wait would block the pump too and is the documented unrecoverable case.
        final AtomicReference<java.awt.Dialog> dialog = new AtomicReference<>();
        final CountDownLatch bodyStarted = new CountDownLatch(1);
        final CountDownLatch callerDone = new CountDownLatch(1);
        final AtomicReference<Thread> compensationThread = new AtomicReference<>();
        final AtomicReference<Throwable> outcome = new AtomicReference<>();
        final Runnable compensation = new Runnable() {
            @Override
            public void run() {
                compensationThread.set(Thread.currentThread());
                final java.awt.Dialog showing = dialog.get();
                if (showing != null && showing.isVisible()) {
                    showing.dispose();
                } else {
                    // The interrupt may land between task start and setVisible: repost so the
                    // modal pump retries once the dialog is actually up.
                    SwingUtilities.invokeLater(this);
                }
            }
        };
        final Thread caller = new Thread(
                () -> {
                    try {
                        EdtDispatch.call(
                                "modal",
                                Duration.ofSeconds(30),
                                (Callable<Object>) () -> {
                                    final java.awt.Dialog modal = new java.awt.Dialog((java.awt.Frame) null, true);
                                    dialog.set(modal);
                                    bodyStarted.countDown();
                                    modal.setVisible(true);
                                    return "answer";
                                },
                                compensation);
                    } catch (Throwable failure) {
                        outcome.set(failure);
                    } finally {
                        callerDone.countDown();
                    }
                },
                "edt-dispatch-test-modal");
        caller.setDaemon(true);
        caller.start();
        try {
            assertTrue(bodyStarted.await(5, TimeUnit.SECONDS));
            caller.interrupt();
            assertTrue(callerDone.await(10, TimeUnit.SECONDS));
            final Throwable failure = outcome.get();
            assertTrue(failure instanceof EdtDispatchException);
            assertEquals(EdtDispatchException.Reason.INTERRUPTED, ((EdtDispatchException) failure).reason());
            assertTrue(
                    compensationThread.get().getName().contains("AWT-EventQueue"), "compensation must run on the EDT");
        } finally {
            final java.awt.Dialog modal = dialog.get();
            if (modal != null) {
                SwingUtilities.invokeLater(modal::dispose);
            }
        }
    }

    @Test
    void taskFailuresPropagateUnchanged() {
        final IllegalArgumentException runtime = new IllegalArgumentException("boom");
        final IllegalArgumentException thrown = assertThrows(
                IllegalArgumentException.class,
                () -> EdtDispatch.call("runtime-failure", () -> {
                    throw runtime;
                }));
        assertSame(runtime, thrown);
        final AssertionError error = new AssertionError("broken");
        assertThrows(
                AssertionError.class,
                () -> EdtDispatch.call("error-failure", () -> {
                    throw error;
                }));
        assertThrows(
                IllegalStateException.class,
                () -> EdtDispatch.call("checked-failure", () -> {
                    throw new Exception("checked");
                }));
    }

    @Test
    void fatalEdtErrorStillSurfacesToTheCaller() {
        // A VirtualMachineError on the EDT must reach the caller as that Error — recording it
        // before rethrowing keeps outcome() from reporting a phantom null success that would
        // surface as an unboxing NPE (or a silently "succeeded" write) downstream.
        final StackOverflowError fatal = new StackOverflowError("simulated fatal");
        final StackOverflowError thrown = assertThrows(
                StackOverflowError.class,
                () -> EdtDispatch.call("fatal-error", () -> {
                    throw fatal;
                }));
        assertSame(fatal, thrown);
    }

    @Test
    void acceptTimeoutTripsTheUnresponsiveCircuitUntilTheProbeRuns() throws Exception {
        // The breaker only trips on caller bounds at or above the default; lowering the trip
        // bound keeps this test deterministic instead of parking on the real 30s bound.
        EdtDispatch.breakerTripBoundForTesting(SHORT_ACCEPT);
        final CountDownLatch wedged = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            wedged.countDown();
            await(release);
        });
        assertTrue(wedged.await(5, TimeUnit.SECONDS));
        try {
            assertThrows(EdtDispatchException.class, () -> EdtDispatch.call("first-timeout", SHORT_ACCEPT, () -> null));
            assertTrue(EdtDispatch.edtUnresponsiveForTesting(), "circuit must be tripped");
            // A second call while the EDT is still wedged uses the short unresponsive bound
            // and reports a distinguishable reason.
            final EdtDispatchException second = assertThrows(
                    EdtDispatchException.class,
                    () -> EdtDispatch.call("second-timeout", Duration.ofSeconds(30), () -> null));
            assertEquals(EdtDispatchException.Reason.EDT_UNRESPONSIVE, second.reason());
            release.countDown();
            drainEdt(); // lets the probe runnable execute and clear the circuit
            assertFalse(EdtDispatch.edtUnresponsiveForTesting(), "probe must restore normal bounds");
            assertEquals("back", EdtDispatch.call("recovered", () -> "back"));
        } finally {
            release.countDown();
        }
    }

    @Test
    void shortAcceptTimeoutsNeverTripTheUnresponsiveCircuit() throws Exception {
        // A caller racing its own short bound only proves the EDT was busy for that long;
        // it must not degrade every other dispatch onto the unresponsive bound.
        final CountDownLatch wedged = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            wedged.countDown();
            await(release);
        });
        assertTrue(wedged.await(5, TimeUnit.SECONDS));
        try {
            final EdtDispatchException first = assertThrows(
                    EdtDispatchException.class,
                    () -> EdtDispatch.call("short-bound-timeout", SHORT_ACCEPT, () -> null));
            assertEquals(EdtDispatchException.Reason.ACCEPT_TIMEOUT, first.reason());
            assertFalse(EdtDispatch.edtUnresponsiveForTesting(), "a short-bounded timeout must not trip the breaker");

            final AtomicBoolean cleanupRan = new AtomicBoolean();
            EdtDispatch.runEventually("short-bound-cleanup", SHORT_ACCEPT, () -> cleanupRan.set(true));
            assertFalse(
                    EdtDispatch.edtUnresponsiveForTesting(),
                    "a short-bounded runEventually deferral must not trip the breaker");

            // Later dispatches keep their own bounds instead of the unresponsive bound.
            final EdtDispatchException second = assertThrows(
                    EdtDispatchException.class, () -> EdtDispatch.call("still-bounded", SHORT_ACCEPT, () -> null));
            assertEquals(EdtDispatchException.Reason.ACCEPT_TIMEOUT, second.reason());

            release.countDown();
            drainEdt();
            assertTrue(cleanupRan.get(), "the deferred cleanup still runs when the EDT drains");
        } finally {
            release.countDown();
        }
    }

    @Test
    void exitModeShortensBoundsAndLetsCleanupReturnImmediately() throws Exception {
        final CountDownLatch wedged = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            wedged.countDown();
            await(release);
        });
        assertTrue(wedged.await(5, TimeUnit.SECONDS));
        try {
            EdtDispatch.enterExitModeForTesting();
            final AtomicBoolean cleanupRan = new AtomicBoolean();
            final CountDownLatch cleanupCallerDone = new CountDownLatch(1);
            final Thread cleaner = new Thread(
                    () -> {
                        EdtDispatch.runEventually("exit-cleanup", () -> cleanupRan.set(true));
                        cleanupCallerDone.countDown();
                    },
                    "edt-dispatch-test-exit-cleanup");
            cleaner.setDaemon(true);
            cleaner.start();
            assertTrue(
                    cleanupCallerDone.await(5, TimeUnit.SECONDS),
                    "cleanup dispatch must not block during process exit");
            final EdtDispatchException failure = assertThrows(
                    EdtDispatchException.class,
                    () -> EdtDispatch.call("exit-call", Duration.ofSeconds(30), () -> null));
            assertEquals(EdtDispatchException.Reason.EDT_UNRESPONSIVE, failure.reason());
            release.countDown();
            drainEdt();
            assertTrue(cleanupRan.get(), "deferred cleanup must still execute once the EDT drains");
        } finally {
            release.countDown();
        }
    }

    @Test
    void runEventuallyDefersInsteadOfAbandoningOnAcceptTimeout() throws Exception {
        final CountDownLatch wedged = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            wedged.countDown();
            await(release);
        });
        assertTrue(wedged.await(5, TimeUnit.SECONDS));
        try {
            final AtomicInteger executions = new AtomicInteger();
            final CountDownLatch callerDone = new CountDownLatch(1);
            final Thread caller = new Thread(
                    () -> {
                        EdtDispatch.runEventually("cleanup", SHORT_ACCEPT, executions::incrementAndGet);
                        callerDone.countDown();
                    },
                    "edt-dispatch-test-eventual");
            caller.setDaemon(true);
            caller.start();
            assertTrue(callerDone.await(5, TimeUnit.SECONDS), "caller must return on timeout");
            assertEquals(0, executions.get(), "body must not run while the EDT is wedged");
            release.countDown();
            drainEdt();
            assertEquals(1, executions.get(), "deferred cleanup must run exactly once");
        } finally {
            release.countDown();
        }
    }

    @Test
    void runEventuallyPropagatesFailuresWhenTheCallerStillWaits() {
        final IllegalStateException failure = new IllegalStateException("cleanup broke");
        final IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> EdtDispatch.runEventually("failing-cleanup", () -> {
                    throw failure;
                }));
        assertSame(failure, thrown);
    }

    @Test
    void callExactRethrowsCheckedTaskFailuresUnwrapped() throws Exception {
        final Exception checked = new java.io.IOException("checked-boom");
        final Exception thrown = assertThrows(
                java.io.IOException.class,
                () -> EdtDispatch.callExact("exact-checked", SHORT_ACCEPT, () -> {
                    throw checked;
                }));
        assertSame(checked, thrown, "callExact must deliver the task's checked failure unchanged");
    }

    @Test
    void callExactRunsInlineOnTheEdtAndStillRethrowsChecked() throws Exception {
        final AtomicReference<Throwable> thrown = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                EdtDispatch.callExact("exact-inline", SHORT_ACCEPT, () -> {
                    throw new java.io.IOException("inline-checked");
                });
            } catch (Throwable failure) {
                thrown.set(failure);
            }
        });
        assertTrue(thrown.get() instanceof java.io.IOException);
    }

    @Test
    void postRunsInlineOnTheEdtAndQueuesWithoutBlocking() throws Exception {
        final AtomicBoolean inlineRan = new AtomicBoolean();
        SwingUtilities.invokeAndWait(() -> EdtDispatch.post("inline-post", () -> inlineRan.set(true)));
        assertTrue(inlineRan.get(), "post on the EDT must run inline");

        final CountDownLatch wedged = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            wedged.countDown();
            await(release);
        });
        assertTrue(wedged.await(5, TimeUnit.SECONDS));
        try {
            final AtomicBoolean ran = new AtomicBoolean();
            // The EDT is wedged; post must return immediately and still deliver the task.
            EdtDispatch.post("deferred-post", () -> ran.set(true));
            assertFalse(ran.get());
            release.countDown();
            drainEdt();
            assertTrue(ran.get(), "a posted task must run when the EDT drains");
        } finally {
            release.countDown();
        }
    }

    @Test
    void abandonedTransitionOnlyAppliesToQueuedTasks() {
        final java.util.concurrent.atomic.AtomicInteger state =
                new java.util.concurrent.atomic.AtomicInteger(EdtDispatch.QUEUED);
        assertTrue(EdtDispatch.tryAbandon(state));
        assertEquals(EdtDispatch.ABANDONED, state.get());

        final java.util.concurrent.atomic.AtomicInteger running =
                new java.util.concurrent.atomic.AtomicInteger(EdtDispatch.RUNNING);
        assertFalse(EdtDispatch.tryAbandon(running), "a running task can never be abandoned");
        assertEquals(EdtDispatch.RUNNING, running.get());

        final java.util.concurrent.atomic.AtomicInteger done =
                new java.util.concurrent.atomic.AtomicInteger(EdtDispatch.DONE);
        assertFalse(EdtDispatch.tryAbandon(done));
        assertEquals(EdtDispatch.DONE, done.get());
    }

    private static void drainEdt() throws InterruptedException, InvocationTargetException {
        SwingUtilities.invokeAndWait(() -> {});
    }

    private static void await(final CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}

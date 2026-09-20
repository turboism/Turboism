package dev.turboism.adapter.cubism.edit;

import dev.turboism.adapter.cubism.edit.EditSessionDialogPrimitives.InvisibleModal;
import dev.turboism.adapter.cubism.edit.EditSessionDialogPrimitives.StatusDialog;
import dev.turboism.adapter.cubism.edit.EditSessionDialogPrimitives.TimerHandle;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@link EditSessionDialogLock} through fake {@link EditSessionDialogPrimitives} — no real
 * Swing dialog or timer is ever created, and the pulse clock is injected so the official
 * 100ms-poll / 10000ms-timeout cadence is exercised deterministically.
 */
final class EditSessionDialogLockTest {

    @Test
    void nonSilentEngageShowsTheStatusDialogImmediatelyWithoutAModal() {
        final Primitives primitives = new Primitives();
        final AtomicLong now = new AtomicLong();
        final EditSessionDialogLock lock = new EditSessionDialogLock(
            primitives,
            new EditSessionUiLockContext(Optional.of(new Object()), () -> {}),
            now::get
        );

        lock.engage(false);

        // Official: silent=false shows the status dialog at once and never creates the
        // invisible modal interceptor.
        assertEquals(List.of(false), primitives.windowEnabled);
        assertEquals(0, primitives.modalCount.get());
        assertTrue(primitives.status.shown);
        assertTrue(primitives.armedDelays.isEmpty());
    }

    @Test
    void silentEngageShowsTheInvisibleModalAtOnceAndPollsThePulseTimeout() {
        final Primitives primitives = new Primitives();
        final AtomicLong now = new AtomicLong();
        final EditSessionDialogLock lock = new EditSessionDialogLock(
            primitives,
            new EditSessionUiLockContext(Optional.of(new Object()), () -> {}),
            now::get
        );

        lock.engage(true);

        assertEquals(List.of(false), primitives.windowEnabled);
        assertTrue(primitives.modal.shown);
        assertFalse(primitives.status.shown);
        assertEquals(List.of(EditSessionDialogLock.MODAL_PULSE_POLL_MS), primitives.armedDelays);

        // A poll tick before the timeout re-arms; the modal keeps holding the input block.
        now.addAndGet(500);
        primitives.fire(EditSessionDialogLock.MODAL_PULSE_POLL_MS);
        assertFalse(primitives.modal.hidden);
        assertFalse(primitives.status.shown);

        // Once the official 10000ms timeout elapses, the pulse releases the modal and reveals
        // the status dialog.
        now.addAndGet(EditSessionDialogLock.SILENT_PULSE_TIMEOUT_MS);
        primitives.fire(EditSessionDialogLock.MODAL_PULSE_POLL_MS);
        assertTrue(primitives.modal.hidden);
        assertTrue(primitives.modal.disposed);
        assertTrue(primitives.status.shown);
    }

    @Test
    void disengageDuringThePulseReleasesTheModalWithoutRevealingStatus() {
        final Primitives primitives = new Primitives();
        final AtomicLong now = new AtomicLong();
        final EditSessionDialogLock lock = new EditSessionDialogLock(
            primitives,
            new EditSessionUiLockContext(Optional.of(new Object()), () -> {}),
            now::get
        );
        lock.engage(true);

        lock.disengage();

        assertEquals(List.of(false, true), primitives.windowEnabled);
        assertTrue(primitives.modal.disposed);
        assertFalse(primitives.status.shown);
        assertTrue(primitives.timers.stream().allMatch(FakeTimer::cancelled));

        // A late-firing poll must not resurrect a disposed dialog.
        primitives.fireAll();
        assertEquals(1, primitives.modal.showCount);
        assertFalse(primitives.status.shown);
    }

    @Test
    void engageIsIdempotentAndDisengageIsSafeWithoutEngage() {
        final Primitives primitives = new Primitives();
        final AtomicLong now = new AtomicLong();
        final EditSessionDialogLock lock = new EditSessionDialogLock(
            primitives,
            new EditSessionUiLockContext(Optional.empty(), () -> {}),
            now::get
        );

        lock.disengage();
        lock.engage(true);
        lock.engage(true);

        assertEquals(1, primitives.modalCount.get());
        assertEquals(1, primitives.statusCount.get());
        // No window handle: nothing to enable or restore.
        assertTrue(primitives.windowEnabled.isEmpty());
    }

    @Test
    void cancelControlInvokesTheCancelRequest() {
        final Primitives primitives = new Primitives();
        final AtomicBoolean cancelRequested = new AtomicBoolean();
        final EditSessionDialogLock lock = new EditSessionDialogLock(
            primitives,
            new EditSessionUiLockContext(Optional.empty(), () -> cancelRequested.set(true)),
            () -> 0L
        );
        lock.engage(false);

        primitives.context.cancelRequest().run();

        assertTrue(cancelRequested.get());
    }

    @Test
    void logAndProgressReachTheStatusDialogWhileEngaged() {
        final Primitives primitives = new Primitives();
        final EditSessionDialogLock lock = new EditSessionDialogLock(
            primitives,
            new EditSessionUiLockContext(Optional.empty(), () -> {}),
            () -> 0L
        );

        lock.log("dropped");
        lock.engage(false);
        lock.log("kept");
        lock.progress(0.5);
        lock.disengage();
        lock.log("dropped-too");

        assertEquals(List.of("kept"), primitives.status.logs);
        assertEquals(List.of(0.5), primitives.status.progressValues);
    }

    private static final class Primitives implements EditSessionDialogPrimitives {
        final List<Boolean> windowEnabled = new ArrayList<>();
        final List<Integer> armedDelays = new ArrayList<>();
        final List<FakeTimer> timers = new ArrayList<>();
        final FakeModal modal = new FakeModal();
        final FakeStatus status = new FakeStatus();
        final AtomicInteger modalCount = new AtomicInteger();
        final AtomicInteger statusCount = new AtomicInteger();
        EditSessionUiLockContext context;

        @Override
        public InvisibleModal createInvisibleModal(final EditSessionUiLockContext context) {
            this.context = context;
            modalCount.incrementAndGet();
            return modal;
        }

        @Override
        public StatusDialog createStatusDialog(final EditSessionUiLockContext context) {
            this.context = context;
            statusCount.incrementAndGet();
            return status;
        }

        @Override
        public TimerHandle timer(final int delayMs, final Runnable action) {
            final FakeTimer timer = new FakeTimer(delayMs, action);
            armedDelays.add(delayMs);
            timers.add(timer);
            return timer;
        }

        @Override
        public void setWindowEnabled(final Object window, final boolean enabled) {
            windowEnabled.add(enabled);
        }

        void fire(final int delayMs) {
            // Snapshot: a pulse tick may re-arm the next 100ms timer while firing.
            List.copyOf(timers).stream()
                .filter(timer -> timer.delayMs == delayMs && !timer.cancelled())
                .forEach(FakeTimer::fire);
        }

        void fireAll() {
            List.copyOf(timers).forEach(FakeTimer::fire);
        }
    }

    private static final class FakeTimer implements TimerHandle {
        final int delayMs;
        private final Runnable action;
        private boolean cancelled;
        private boolean fired;

        FakeTimer(final int delayMs, final Runnable action) {
            this.delayMs = delayMs;
            this.action = action;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }

        boolean cancelled() {
            return cancelled;
        }

        void fire() {
            if (!cancelled && !fired) {
                fired = true;
                action.run();
            }
        }
    }

    private static final class FakeModal implements InvisibleModal {
        boolean shown;
        boolean hidden;
        boolean disposed;
        int showCount;

        @Override
        public void show() {
            shown = true;
            showCount++;
        }

        @Override
        public void hide() {
            hidden = true;
        }

        @Override
        public void dispose() {
            disposed = true;
        }
    }

    private static final class FakeStatus implements StatusDialog {
        boolean shown;
        boolean disposed;
        final List<String> logs = new ArrayList<>();
        final List<Double> progressValues = new ArrayList<>();

        @Override
        public void show() {
            shown = true;
        }

        @Override
        public void log(final String message) {
            logs.add(message);
        }

        @Override
        public void progress(final double value) {
            progressValues.add(value);
        }

        @Override
        public void dispose() {
            disposed = true;
        }
    }
}

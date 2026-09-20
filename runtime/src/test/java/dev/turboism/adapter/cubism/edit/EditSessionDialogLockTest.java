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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@link EditSessionDialogLock} through fake {@link EditSessionDialogPrimitives} — no real
 * Swing dialog or timer is ever created.
 */
final class EditSessionDialogLockTest {

    @Test
    void engageDisablesTheWindowAndArmsTheInvisibleModalTimer() {
        final Primitives primitives = new Primitives();
        final EditSessionDialogLock lock = new EditSessionDialogLock(
            primitives,
            new EditSessionUiLockContext(Optional.of(new Object()), () -> {})
        );

        lock.engage(false);

        assertEquals(List.of(false), primitives.windowEnabled);
        assertEquals(
            List.of(
                EditSessionDialogLock.INVISIBLE_MODAL_DELAY_MS
            ),
            primitives.armedDelays
        );
        assertFalse(primitives.modal.shown);
        assertTrue(primitives.status.shown);

        primitives.fire(EditSessionDialogLock.INVISIBLE_MODAL_DELAY_MS);
        assertTrue(primitives.modal.shown);
    }

    @Test
    void silentEngageHidesTheStatusDialogUntilTheRevealTimerFires() {
        final Primitives primitives = new Primitives();
        final EditSessionDialogLock lock = new EditSessionDialogLock(
            primitives,
            new EditSessionUiLockContext(Optional.of(new Object()), () -> {})
        );

        lock.engage(true);

        assertFalse(primitives.status.shown);
        assertEquals(
            List.of(
                EditSessionDialogLock.INVISIBLE_MODAL_DELAY_MS,
                EditSessionDialogLock.SILENT_REVEAL_DELAY_MS
            ),
            primitives.armedDelays
        );

        primitives.fire(EditSessionDialogLock.SILENT_REVEAL_DELAY_MS);
        assertTrue(primitives.status.shown);
    }

    @Test
    void disengageRestoresTheWindowDisposesDialogsAndCancelsTimers() {
        final Primitives primitives = new Primitives();
        final EditSessionDialogLock lock = new EditSessionDialogLock(
            primitives,
            new EditSessionUiLockContext(Optional.of(new Object()), () -> {})
        );
        lock.engage(true);

        lock.disengage();

        assertEquals(List.of(false, true), primitives.windowEnabled);
        assertTrue(primitives.modal.disposed);
        assertTrue(primitives.status.disposed);
        assertTrue(primitives.timers.stream().allMatch(FakeTimer::cancelled));

        // A late-firing timer must not resurrect a disposed dialog.
        primitives.fireAll();
        assertFalse(primitives.modal.shown);
        assertFalse(primitives.status.shown);
    }

    @Test
    void engageIsIdempotentAndDisengageIsSafeWithoutEngage() {
        final Primitives primitives = new Primitives();
        final EditSessionDialogLock lock = new EditSessionDialogLock(
            primitives,
            new EditSessionUiLockContext(Optional.empty(), () -> {})
        );

        lock.disengage();
        lock.engage(false);
        lock.engage(false);

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
            new EditSessionUiLockContext(Optional.empty(), () -> cancelRequested.set(true))
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
            new EditSessionUiLockContext(Optional.empty(), () -> {})
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
            timers.stream()
                .filter(timer -> timer.delayMs == delayMs && !timer.cancelled())
                .forEach(FakeTimer::fire);
        }

        void fireAll() {
            timers.forEach(FakeTimer::fire);
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
        boolean disposed;

        @Override
        public void show() {
            shown = true;
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

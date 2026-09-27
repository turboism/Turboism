package dev.turboism.adapter.cubism.edit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link SwingEditSessionDialogPrimitives} through {@link EditSessionDialogLock}
 * against real {@link JDialog}s — the production Swing timing that fake primitives cannot
 * reproduce.
 *
 * <p>The production session dispatch is EDT-confined (the official WS dispatcher runs on the
 * host UI thread), so a transient silent session opens and cancels inside one dispatch task.
 * The invisible modal's show is posted to the EDT and can therefore run after {@code
 * disengage} already disposed the dialog: an unguarded {@code setVisible(true)} resurrects a
 * disposed {@code APPLICATION_MODAL} shell that nothing ever releases. These tests drive that
 * exact ordering and scan {@link Window#getWindows()} for leftovers.</p>
 */
final class SwingEditSessionDialogPrimitivesTest {

    /** Mirrors the leaked-dialog count observed on the 5.3.03 probe run. */
    private static final int TRANSIENT_SESSIONS = 15;
    private static final long AWAIT_TIMEOUT_MS = 10_000L;
    private static final String STATUS_DIALOG_TITLE = "Cubism Edit Session";

    @Test
    void transientSilentSessionsTerminatedInsideOneEdtTaskLeaveNoVisibleModal() {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        final JFrame owner = new JFrame("owner");
        onEdt(() -> owner.setVisible(true));
        try {
            for (int i = 0; i < TRANSIENT_SESSIONS; i++) {
                onEdt(() -> {
                    // One WS request is one dispatch task: the transient silent session
                    // engages and disengages before the modal's queued show can run.
                    final EditSessionDialogLock lock = new EditSessionDialogLock(
                        new SwingEditSessionDialogPrimitives(),
                        new EditSessionUiLockContext(Optional.of(owner), () -> {}));
                    lock.engage(true);
                    lock.disengage();
                });
            }
            // Drain: every show/hide runnable queued by the sessions above has now run.
            onEdt(() -> {});
            onEdt(() -> assertTrue(
                visibleSessionDialogs().isEmpty(),
                "visible session dialogs after transient sessions: "
                    + visibleSessionDialogs()));
        } finally {
            onEdt(() -> {
                disposeSessionDialogs();
                owner.dispose();
            });
        }
    }

    @Test
    void aModalShowingAcrossTasksDisposesOnDisengage() {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        final JFrame owner = new JFrame("owner");
        onEdt(() -> owner.setVisible(true));
        try {
            final EditSessionDialogLock lock = new EditSessionDialogLock(
                new SwingEditSessionDialogPrimitives(),
                new EditSessionUiLockContext(Optional.of(owner), () -> {}));
            onEdt(() -> lock.engage(true));
            // The queued APPLICATION_MODAL show blocks in a nested event pump; a scan
            // queued after it still runs inside that pump and observes the dialog while
            // it is showing — this proves the show is not over-suppressed.
            awaitTrue(() -> onEdt(() -> !visibleSessionDialogs().isEmpty()));
            onEdt(lock::disengage);
            onEdt(() -> {});
            onEdt(() -> assertTrue(
                visibleSessionDialogs().isEmpty(),
                "visible session dialogs after disengage: " + visibleSessionDialogs()));
        } finally {
            onEdt(() -> {
                disposeSessionDialogs();
                owner.dispose();
            });
        }
    }

    @Test
    void aLateHideAfterDisposeDoesNotResurrectTheModal() {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        final JFrame owner = new JFrame("owner");
        onEdt(() -> owner.setVisible(true));
        try {
            final EditSessionDialogPrimitives primitives =
                new SwingEditSessionDialogPrimitives();
            final EditSessionUiLockContext context =
                new EditSessionUiLockContext(Optional.of(owner), () -> {});
            final EditSessionDialogPrimitives.InvisibleModal modal =
                primitives.createInvisibleModal(context);
            onEdt(() -> {
                modal.hide();
                modal.dispose();
            });
            onEdt(() -> {});
            onEdt(() -> assertTrue(
                visibleSessionDialogs().isEmpty(),
                "visible session dialogs after hide+dispose: " + visibleSessionDialogs()));
        } finally {
            onEdt(() -> {
                disposeSessionDialogs();
                owner.dispose();
            });
        }
    }

    /** Runs work on the EDT, rethrowing failures as test assertions. */
    private static void onEdt(final Runnable work) {
        try {
            SwingUtilities.invokeAndWait(work);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        } catch (InvocationTargetException failure) {
            final Throwable cause = failure.getCause();
            if (cause instanceof AssertionError assertion) {
                throw assertion;
            }
            throw new AssertionError(cause);
        }
    }

    /** Runs work on the EDT and returns its result. */
    private static <T> T onEdt(final java.util.concurrent.Callable<T> work) {
        final java.util.concurrent.atomic.AtomicReference<T> result =
            new java.util.concurrent.atomic.AtomicReference<>();
        onEdt(() -> {
            try {
                result.set(work.call());
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        });
        return result.get();
    }

    private static void awaitTrue(final java.util.function.Supplier<Boolean> condition) {
        final long deadlineNanos =
            System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(AWAIT_TIMEOUT_MS);
        while (System.nanoTime() < deadlineNanos) {
            if (Boolean.TRUE.equals(condition.get())) {
                return;
            }
            try {
                Thread.sleep(25L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        assertTrue(condition.get(), "condition not met within " + AWAIT_TIMEOUT_MS + "ms");
    }

    /** EDT-only scan: titles of showing dialogs this lock can produce. */
    private static List<String> visibleSessionDialogs() {
        final List<String> showing = new ArrayList<>();
        for (final Window window : Window.getWindows()) {
            if (window instanceof JDialog dialog && dialog.isShowing()
                && (SwingEditSessionDialogPrimitives.INVISIBLE_MODAL_TITLE.equals(
                        dialog.getTitle())
                    || STATUS_DIALOG_TITLE.equals(dialog.getTitle()))) {
                showing.add(dialog.getTitle() + "@" + Integer.toHexString(
                    System.identityHashCode(dialog)));
            }
        }
        return showing;
    }

    /** EDT-only cleanup: releases leftover session dialogs so a failed run cannot wedge. */
    private static void disposeSessionDialogs() {
        for (final Window window : Window.getWindows()) {
            if (window instanceof JDialog dialog
                && (SwingEditSessionDialogPrimitives.INVISIBLE_MODAL_TITLE.equals(
                        dialog.getTitle())
                    || STATUS_DIALOG_TITLE.equals(dialog.getTitle()))) {
                dialog.setVisible(false);
                dialog.dispose();
            }
        }
    }
}

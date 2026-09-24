package dev.turboism.adapter.cubism.edit;

/**
 * The host-UI primitives {@link EditSessionDialogLock} orchestrates, isolated so headless tests
 * can drive the lock's state machine without creating real dialogs or timers.
 *
 * <p>Every factory method is invoked on the host UI thread.</p>
 */
public interface EditSessionDialogPrimitives {

    /**
     * Creates (but does not show) the invisible {@code APPLICATION_MODAL} dialog that swallows
     * host input while the session is open — the official "Invisible Modal Dialog".
     */
    InvisibleModal createInvisibleModal(EditSessionUiLockContext context);

    /**
     * Creates (but does not show) the session status dialog carrying the log area, the progress
     * bar, and the cancel control wired to {@link EditSessionUiLockContext#cancelRequest()}.
     */
    StatusDialog createStatusDialog(EditSessionUiLockContext context);

    /** Arms a one-shot host-UI timer that runs {@code action} after {@code delayMs}. */
    TimerHandle timer(int delayMs, Runnable action);

    /** Enables or disables the main window while the lock is engaged. */
    void setWindowEnabled(Object window, boolean enabled);

    /** The invisible application-modal input interceptor. */
    interface InvisibleModal {
        /**
         * Shows the dialog; the host pumps a nested event queue that swallows input.
         * Implementations may present asynchronously so the caller's dispatch task is not
         * parked for the pulse duration — the modal's own nested loop intercepts host input
         * either way.
         */
        void show();

        /**
         * Hides the dialog without disposing it — the official {@code setVisible(false)}
         * that releases the modal input block when the silent pulse ends.
         */
        void hide();

        /** Disposes the dialog. */
        void dispose();
    }

    /** The visible session status dialog. */
    interface StatusDialog {
        /** Shows the dialog. */
        void show();

        /** Appends one log line. */
        void log(String message);

        /** Reports a {@code [0.0, 1.0]} progress value. */
        void progress(double value);

        /** Disposes the dialog. */
        void dispose();
    }

    /** A scheduled host-UI action that can still be cancelled. */
    interface TimerHandle {
        /** Cancels the pending action; safe when it already fired. */
        void cancel();
    }
}

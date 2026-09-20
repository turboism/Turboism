package dev.turboism.adapter.cubism.edit;

import dev.turboism.adapter.cubism.edit.EditSessionDialogPrimitives.InvisibleModal;
import dev.turboism.adapter.cubism.edit.EditSessionDialogPrimitives.StatusDialog;
import dev.turboism.adapter.cubism.edit.EditSessionDialogPrimitives.TimerHandle;

import java.util.Objects;

/**
 * The {@link EditSessionUiLock} that ports the official editing UI lock:
 *
 * <ul>
 *   <li>the main window is disabled for the session's duration,</li>
 *   <li>an invisible {@code APPLICATION_MODAL} dialog (the official {@code 300x200}
 *   "Invisible Modal Dialog") is shown after the official {@code 100ms} delay to swallow host
 *   input while leaving the host event thread pumping,</li>
 *   <li>a status dialog carries the log area, progress bar, and cancel control — shown
 *   immediately for non-silent sessions, or force-revealed after the official
 *   {@code 10000ms} silent timeout.</li>
 * </ul>
 *
 * <p>All behavior runs through {@link EditSessionDialogPrimitives}, so tests exercise this
 * orchestration with fake primitives instead of real Swing dialogs. Timer callbacks are guarded:
 * a late firing after {@link #disengage} must never resurrect a disposed dialog.</p>
 */
public final class EditSessionDialogLock implements EditSessionUiLock {

    /** Official invisible-dialog delay: the modal interceptor appears 100ms after engage. */
    public static final int INVISIBLE_MODAL_DELAY_MS = 100;

    /** Official silent timeout: a silent session's status dialog is force-shown after 10s. */
    public static final int SILENT_REVEAL_DELAY_MS = 10_000;

    private final EditSessionDialogPrimitives primitives;
    private final EditSessionUiLockContext context;

    private boolean engaged;
    private InvisibleModal modal;
    private StatusDialog status;
    private TimerHandle modalTimer;
    private TimerHandle silentTimer;

    public EditSessionDialogLock(
        final EditSessionDialogPrimitives primitives,
        final EditSessionUiLockContext context
    ) {
        this.primitives = Objects.requireNonNull(primitives, "primitives");
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public synchronized void engage(final boolean silent) {
        if (engaged) {
            return;
        }
        engaged = true;
        context.mainWindow().ifPresent(window -> primitives.setWindowEnabled(window, false));
        modal = primitives.createInvisibleModal(context);
        status = primitives.createStatusDialog(context);
        final InvisibleModal engagedModal = modal;
        modalTimer = primitives.timer(INVISIBLE_MODAL_DELAY_MS, () -> {
            synchronized (this) {
                if (engaged && modal == engagedModal) {
                    engagedModal.show();
                }
            }
        });
        if (silent) {
            final StatusDialog engagedStatus = status;
            silentTimer = primitives.timer(SILENT_REVEAL_DELAY_MS, () -> {
                synchronized (this) {
                    if (engaged && status == engagedStatus) {
                        engagedStatus.show();
                    }
                }
            });
        } else {
            status.show();
        }
    }

    @Override
    public synchronized void log(final String message) {
        if (engaged && status != null) {
            status.log(message);
        }
    }

    @Override
    public synchronized void progress(final double value) {
        if (engaged && status != null) {
            status.progress(value);
        }
    }

    @Override
    public synchronized void disengage() {
        if (!engaged) {
            return;
        }
        engaged = false;
        if (silentTimer != null) {
            silentTimer.cancel();
            silentTimer = null;
        }
        if (modalTimer != null) {
            modalTimer.cancel();
            modalTimer = null;
        }
        if (status != null) {
            status.dispose();
            status = null;
        }
        if (modal != null) {
            modal.dispose();
            modal = null;
        }
        context.mainWindow().ifPresent(window -> primitives.setWindowEnabled(window, true));
    }
}

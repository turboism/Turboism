package dev.turboism.adapter.cubism.edit;

import dev.turboism.adapter.cubism.edit.EditSessionDialogPrimitives.InvisibleModal;
import dev.turboism.adapter.cubism.edit.EditSessionDialogPrimitives.StatusDialog;
import dev.turboism.adapter.cubism.edit.EditSessionDialogPrimitives.TimerHandle;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * The {@link EditSessionUiLock} that ports the official editing UI lock
 * (evidence: {@code host-evidence/edit-api-54alpha2/internal-implementation.md} §5,
 * {@code api-internal-routes.json} {@code EditBegin} ui-lock route — javap-verified against
 * {@code com.live2d.cubism.doc.webSocket.a.e$a} of the 5.4 alpha2 jar):
 *
 * <ul>
 *   <li>the main window is disabled for the session's duration
 *   ({@code a.d.a(CECompletePack,true)}),</li>
 *   <li><b>silent sessions only:</b> an invisible {@code APPLICATION_MODAL} dialog (the official
 *   {@code 300x200} "Invisible Modal Dialog", opacity 0) is shown immediately as a startup input
 *   pulse — {@code a.e$a.a(Window,int)} arms a repeating {@code Timer(100ms)} before
 *   {@code setVisible(true)} and hides the dialog once {@code elapsed >= timeoutMs} or the
 *   session flag {@code e.f()} clears. Only after the pulse ends does the visible status dialog
 *   appear ({@code if (e.f()) e.a(V)}),</li>
 *   <li><b>non-silent sessions:</b> no invisible modal is created; the status dialog shows
 *   immediately ({@code e.a(V)}),</li>
 *   <li>the status dialog carries the log area, progress bar, and cancel control; the silent
 *   pulse timeout is the official {@code 10000ms} ({@code sipush 10000} at the call site).</li>
 * </ul>
 *
 * <p>All behavior runs through {@link EditSessionDialogPrimitives}, so tests exercise this
 * orchestration with fake primitives instead of real Swing dialogs. Timer callbacks are guarded:
 * a late firing after {@link #disengage} must never resurrect a disposed dialog.</p>
 */
public final class EditSessionDialogLock implements EditSessionUiLock {

    /**
     * Official poll period of the invisible-modal pulse: {@code new Timer(100, tick)} inside
     * {@code a.e$a.a(Window,int)} checks every 100ms whether the silent timeout elapsed or the
     * session flag cleared.
     */
    public static final int MODAL_PULSE_POLL_MS = 100;

    /**
     * Official silent timeout passed to {@code a.e$a.a(Window,int)}: the invisible modal holds
     * the startup input block for at most 10000ms, then the status dialog is revealed.
     */
    public static final int SILENT_PULSE_TIMEOUT_MS = 10_000;

    private final EditSessionDialogPrimitives primitives;
    private final EditSessionUiLockContext context;
    private final LongSupplier millis;

    private boolean engaged;
    private InvisibleModal modal;
    private StatusDialog status;
    private TimerHandle pulseTimer;
    private long modalShownAtMs;

    public EditSessionDialogLock(
        final EditSessionDialogPrimitives primitives,
        final EditSessionUiLockContext context
    ) {
        this(primitives, context, System::currentTimeMillis);
    }

    EditSessionDialogLock(
        final EditSessionDialogPrimitives primitives,
        final EditSessionUiLockContext context,
        final LongSupplier millis
    ) {
        this.primitives = Objects.requireNonNull(primitives, "primitives");
        this.context = Objects.requireNonNull(context, "context");
        this.millis = Objects.requireNonNull(millis, "millis");
    }

    @Override
    public synchronized void engage(final boolean silent) {
        if (engaged) {
            return;
        }
        engaged = true;
        context.mainWindow().ifPresent(window -> primitives.setWindowEnabled(window, false));
        status = primitives.createStatusDialog(context);
        if (!silent) {
            status.show();
            return;
        }
        // Silent session — official a.e$a.a(window, 10000): the invisible modal is shown at
        // once and holds the input block for up to the 10s pulse timeout; the status dialog
        // is only revealed when the pulse ends while the session is still active.
        modal = primitives.createInvisibleModal(context);
        modalShownAtMs = millis.getAsLong();
        armPulse();
        modal.show();
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
        if (pulseTimer != null) {
            pulseTimer.cancel();
            pulseTimer = null;
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

    /** Re-arms the official 100ms pulse check until the silent timeout has elapsed. */
    private void armPulse() {
        pulseTimer = primitives.timer(MODAL_PULSE_POLL_MS, () -> {
            synchronized (this) {
                if (!engaged || modal == null) {
                    return;
                }
                if (millis.getAsLong() - modalShownAtMs < SILENT_PULSE_TIMEOUT_MS) {
                    armPulse();
                    return;
                }
                endPulse();
            }
        });
    }

    /**
     * Ends the silent input pulse — the official {@code elapsed >= timeoutMs} tick: hides and
     * disposes the invisible modal, then reveals the status dialog while the session is active.
     */
    private void endPulse() {
        final InvisibleModal pulse = modal;
        modal = null;
        pulseTimer = null;
        pulse.hide();
        pulse.dispose();
        if (engaged && status != null) {
            status.show();
        }
    }
}

package dev.turboism.sdk.cubism.edit;

import java.util.Objects;
import java.util.Optional;

/**
 * Options for opening an {@link EditSession}, equivalent to the optional fields of the official
 * {@code EditBegin} request plus the notification opt-in of {@code NotifyUndoCancel}.
 *
 * @param silent when {@code true}, asks the editor to keep the editing dialog minimized while the
 *     session is open (the official {@code Silent} flag). The editor may still surface fatal
 *     errors; callers must not depend on total silence.
 * @param undoCancelListener when present, registers a {@link EditSessionListener} for the
 *     duration of the session, equivalent to {@code NotifyUndoCancel} with {@code Enabled = true}
 */
public record EditSessionOptions(boolean silent, Optional<EditSessionListener> undoCancelListener) {

    public EditSessionOptions {
        undoCancelListener = Objects.requireNonNull(undoCancelListener, "undoCancelListener");
    }

    /** Returns the default options: not silent, no undo-cancel listener. */
    public static EditSessionOptions defaults() {
        return new EditSessionOptions(false, Optional.empty());
    }

    /** Returns options that request a silent (minimized) editing dialog. */
    public static EditSessionOptions silentDialog() {
        return new EditSessionOptions(true, Optional.empty());
    }

    /** Returns a copy of these options with the given undo-cancel listener registered. */
    public EditSessionOptions withUndoCancelListener(final EditSessionListener listener) {
        return new EditSessionOptions(silent, Optional.of(Objects.requireNonNull(listener, "listener")));
    }
}

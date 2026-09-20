package dev.turboism.sdk.cubism.edit;

import java.util.Objects;

/**
 * Thrown when an edit operation cannot run because the session was cancelled.
 *
 * <p>The {@link #source()} records who cancelled the session so plugins can distinguish a user
 * cancelling the modal dialog from a host-forced teardown or their own earlier {@code cancel()}.
 */
public final class EditCancelledException extends EditSessionException {
    /** Stable diagnostic code carried by every cancellation failure. */
    public static final String CODE = "cubism.edit.cancelled";

    private final CancelSource source;

    /** Creates a cancellation failure recording who cancelled the session. */
    public EditCancelledException(final CancelSource source) {
        super(CODE, "Cubism edit session was cancelled by " + Objects.requireNonNull(source, "source"));
        this.source = source;
    }

    /** Creates a cancellation failure with a more specific diagnostic code. */
    public EditCancelledException(final String code, final String message, final CancelSource source) {
        super(code, message);
        this.source = Objects.requireNonNull(source, "source");
    }

    /** Returns who cancelled the session, never {@code null}. */
    public CancelSource source() {
        return source;
    }
}

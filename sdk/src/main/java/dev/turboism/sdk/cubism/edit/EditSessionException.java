package dev.turboism.sdk.cubism.edit;

import dev.turboism.sdk.cubism.CubismServiceException;

/**
 * Base type for all edit-session failures.
 *
 * <p>Every failure a plugin can observe while opening, driving, or closing an edit session is an
 * {@code EditSessionException} carrying a stable {@link #code()}. Callers that want one catch
 * block for the whole editing surface catch this type; callers that care about the specific
 * failure branch on the concrete subclass or the code.
 */
public class EditSessionException extends CubismServiceException {
    /** Creates a failure with a stable diagnostic code and a human-readable message. */
    public EditSessionException(final String code, final String message) {
        super(code, message);
    }

    /** Creates a failure with a stable diagnostic code, a message, and an underlying cause. */
    public EditSessionException(final String code, final String message, final Throwable cause) {
        super(code, message, cause);
    }
}

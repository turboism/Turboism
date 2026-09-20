package dev.turboism.sdk.cubism.edit;

import dev.turboism.sdk.CubismEditor;
import java.util.Objects;

/**
 * Thrown when an edit operation cannot run because no admitted edit session backs it.
 *
 * <p>This is the fail-closed failure of the editing surface: whenever the connected Editor build
 * lacks verified bindings for an operation, when editing is not approved, or when the session
 * handle comes from {@link EditSession#unavailable()}, the call raises this exception instead of
 * guessing at editor internals. The exception never means the model was partially edited.
 */
@CubismEditor({"5.2.03", "5.3.02", "5.3.03"})
public final class EditUnavailableException extends EditSessionException {
    /** Stable diagnostic code carried by every unavailable failure. */
    public static final String CODE = "cubism.edit.unavailable";

    /** Creates an unavailable failure naming the operation that could not run. */
    public EditUnavailableException(final String operation) {
        super(CODE, "Cubism edit operation is unavailable: " + Objects.requireNonNull(operation, "operation"));
    }

    /** Creates an unavailable failure with a more specific diagnostic code. */
    public EditUnavailableException(final String code, final String message) {
        super(code, message);
    }
}

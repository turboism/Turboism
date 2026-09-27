package dev.turboism.adapter.cubism.integration;

import java.util.Objects;

/**
 * A typed protocol failure: the handler layer's way to fail a claimed editing request with an
 * official-shaped {@link EditApiErrorCode} instead of an exception trace.
 *
 * <p>Handlers raise this for every expected refusal — malformed {@code Data}, missing required
 * fields, stale or mismatched {@code ModelUID}s, unresolvable object ids, engine-refused
 * operations. The router serializes it as a {@code Type="Error"} envelope; unexpected throwables
 * are mapped to {@link EditApiErrorCode#INVALID_EDIT_OPERATION} so a claimed message always
 * produces an official error shape.</p>
 */
public final class EditApiFailure extends Exception {

    private final EditApiErrorCode code;

    public EditApiFailure(final EditApiErrorCode code) {
        super(code.wireName());
        this.code = Objects.requireNonNull(code, "code");
    }

    /** The wire error code carried in {@code Data.ErrorType}. */
    public EditApiErrorCode code() {
        return code;
    }
}

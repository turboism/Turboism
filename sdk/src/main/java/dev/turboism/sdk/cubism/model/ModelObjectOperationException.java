package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.PreviewApi;

import java.util.Objects;

/** Stable failure classification for model-object automation. */
@PreviewApi
public final class ModelObjectOperationException extends RuntimeException {

    @PreviewApi
    public enum Code {
        UNAVAILABLE,
        NOT_FOUND,
        CONFLICT,
        INVALID_REQUEST,
        STALE,
        FAILED
    }

    private final Code code;

    public ModelObjectOperationException(final Code code, final String message) {
        super(Objects.requireNonNull(message, "message"));
        this.code = Objects.requireNonNull(code, "code");
    }

    public ModelObjectOperationException(
        final Code code,
        final String message,
        final Throwable cause
    ) {
        super(Objects.requireNonNull(message, "message"), cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    /**
     * @return the stable classification callers should branch on, in
     *     preference to parsing the message: whether the operation was
     *     unavailable, targeted something absent, conflicted, was malformed,
     *     acted on stale state, or simply failed
     */
    public Code code() {
        return code;
    }
}

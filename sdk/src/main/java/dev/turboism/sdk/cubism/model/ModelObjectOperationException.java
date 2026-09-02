package dev.turboism.sdk.cubism.model;


import java.util.Objects;

/** Stable failure classification for model-object automation. */
public final class ModelObjectOperationException extends RuntimeException {

    public enum Code {
        UNAVAILABLE,
        NOT_FOUND,
        CONFLICT,
        INVALID_REQUEST,
        STALE,
        COMMITTED,
        FAILED
    }

    private final Code code;
    private final java.util.Optional<ModelObjectReference> committedReference;

    public ModelObjectOperationException(final Code code, final String message) {
        this(code, message, null, java.util.Optional.empty());
    }

    public ModelObjectOperationException(
        final Code code,
        final String message,
        final Throwable cause
    ) {
        this(code, message, cause, java.util.Optional.empty());
    }

    public ModelObjectOperationException(
        final Code code,
        final String message,
        final Throwable cause,
        final java.util.Optional<ModelObjectReference> committedReference
    ) {
        super(Objects.requireNonNull(message, "message"), cause);
        this.code = Objects.requireNonNull(code, "code");
        this.committedReference = Objects.requireNonNull(
            committedReference,
            "committedReference"
        );
        if (code != Code.COMMITTED && this.committedReference.isPresent()) {
            throw new IllegalArgumentException(
                "committedReference is only valid for COMMITTED failures"
            );
        }
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

    /**
     * @return the stable object reference captured before a committed operation's
     *     descriptor readback failed; empty for every other failure classification
     */
    public java.util.Optional<ModelObjectReference> committedReference() {
        return committedReference;
    }
}

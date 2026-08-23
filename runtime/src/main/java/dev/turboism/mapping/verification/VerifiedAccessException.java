package dev.turboism.mapping.verification;

import java.util.Objects;

/** Sanitized runtime failure while invoking a verified host selector. */
public final class VerifiedAccessException extends RuntimeException {

    private final String alias;
    private final FailureKind failureKind;

    public VerifiedAccessException(
        final String alias,
        final FailureKind failureKind,
        final String message,
        final Throwable cause
    ) {
        super(requireText(message, "message"), cause);
        this.alias = requireText(alias, "alias");
        this.failureKind = Objects.requireNonNull(failureKind, "failureKind");
    }

    /**
     * @return the verified selector alias that failed, which names the host
     *     member without exposing it
     */
    public String alias() {
        return alias;
    }

    /**
     * @return whether the call site itself could not be used
     *     ({@code RESOLUTION}) or the host method ran and threw
     *     ({@code INVOCATION}); the host throwable itself is never carried
     */
    public FailureKind failureKind() {
        return failureKind;
    }

    public enum FailureKind {
        RESOLUTION,
        INVOCATION
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}

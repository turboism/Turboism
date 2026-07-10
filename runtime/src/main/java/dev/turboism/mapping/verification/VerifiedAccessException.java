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

    public String alias() {
        return alias;
    }

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

package dev.turboism.adapter.cubism.core;

import java.util.Objects;

/** Sanitized failure for active-model acquisition or scoped lease access. */
record CoreModelFailure(Code code, String message) {

    CoreModelFailure {
        code = Objects.requireNonNull(code, "code");
        message = requireText(message, "message");
    }

    enum Code {
        ADAPTER_UNAVAILABLE,
        MODEL_UNAVAILABLE,
        TRANSITION_IN_PROGRESS,
        SOURCE_CLOSED,
        LEASE_CLOSED,
        STALE_GENERATION
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}

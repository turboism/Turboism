package dev.turboism.adapter.cubism.core;

import java.util.Objects;

/** Sanitized, fail-closed reason for Core provider admission or probing failure. */
public record CoreProviderFailure(Code code, String message) {

    public CoreProviderFailure {
        code = Objects.requireNonNull(code, "code");
        message = requireText(message, "message");
    }

    public enum Code {
        ADAPTER_UNAVAILABLE,
        EVIDENCE_REJECTED,
        RESOLUTION_FAILED,
        INVOCATION_FAILED,
        INVALID_VERSION,
        VERSION_MISMATCH,
        LEASE_CLOSED,
        STALE_GENERATION,
        INVALID_STRUCTURE
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}

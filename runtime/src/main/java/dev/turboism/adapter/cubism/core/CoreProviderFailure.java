package dev.turboism.adapter.cubism.core;

import dev.turboism.internal.Requirements;
import java.util.Objects;

/** Sanitized, fail-closed reason for Core provider admission or probing failure. */
public record CoreProviderFailure(Code code, String message) {

    public CoreProviderFailure {
        code = Objects.requireNonNull(code, "code");
        message = Requirements.text(message, "message");
    }

    /** The fail-closed failure category surfaced to callers of the Core provider seam. */
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
}

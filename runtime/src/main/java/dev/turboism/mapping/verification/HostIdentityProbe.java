package dev.turboism.mapping.verification;

import java.util.Objects;
import java.util.Optional;

/**
 * Outcome of probing a host artifact for its declared Cubism identity.
 *
 * <p>A probe never throws for malformed input: every negative outcome is a
 * bounded status plus a diagnostic detail, so callers (bootstrap, installer
 * discovery, the CLI) can report why identity was not established instead of
 * guessing one.</p>
 *
 * @param status the probe verdict
 * @param identity the declared identity; present only for {@link Status#DECLARED}
 * @param detail stable lower-case diagnostic detail, never blank
 */
public record HostIdentityProbe(Status status, Optional<CubismHostIdentity> identity, String detail) {

    public HostIdentityProbe {
        status = Objects.requireNonNull(status, "status");
        identity = Objects.requireNonNull(identity, "identity");
        detail = Objects.requireNonNull(detail, "detail");
        if (detail.isBlank()) {
            throw new IllegalArgumentException("detail must not be blank");
        }
        if (status == Status.DECLARED && identity.isEmpty()) {
            throw new IllegalArgumentException("a declared probe must carry the identity");
        }
        if (status != Status.DECLARED && identity.isPresent()) {
            throw new IllegalArgumentException("a rejected probe must not carry an identity");
        }
    }

    /**
     * @return whether the artifact declared a complete Cubism identity
     */
    public boolean declared() {
        return status == Status.DECLARED;
    }

    static HostIdentityProbe declared(final CubismHostIdentity identity) {
        return new HostIdentityProbe(Status.DECLARED, Optional.of(identity), "declared");
    }

    static HostIdentityProbe rejected(final Status status, final String detail) {
        return new HostIdentityProbe(status, Optional.empty(), detail);
    }

    /** Probe verdicts; every rejection is a distinct fail-closed reason. */
    public enum Status {
        /** The artifact declared a complete Cubism product/version/build identity. */
        DECLARED,
        /** The file is absent, unreadable, or not a zip archive. */
        UNREADABLE,
        /** The artifact is not a Cubism Editor application archive. */
        NOT_CUBISM,
        /** No class in the declaration scope produced a usable identity. */
        DECLARATION_MISSING,
        /** A declaration class was malformed or truncated. */
        DECLARATION_MALFORMED,
        /** Declaration classes or fields disagreed, or required values were absent. */
        DECLARATION_AMBIGUOUS,
        /** The bounded declaration scan exceeded its class or byte budget. */
        SCAN_LIMIT_EXCEEDED
    }
}

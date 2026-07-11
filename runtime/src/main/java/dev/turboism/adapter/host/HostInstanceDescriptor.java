package dev.turboism.adapter.host;

import java.util.Objects;

/** Local-only inputs needed to connect one exact host session. */
public record HostInstanceDescriptor(
    String sessionId,
    HostVerificationEvidence verificationEvidence
) {
    public HostInstanceDescriptor {
        sessionId = requireText(sessionId, "sessionId");
        verificationEvidence = Objects.requireNonNull(verificationEvidence, "verificationEvidence");
    }

    @Override
    public String toString() {
        return "HostInstanceDescriptor[sessionId=" + sessionId + ", connectionMaterial=redacted]";
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}

package dev.turboism.adapter.host;

import dev.turboism.adapter.HostVerificationEvidence;
import dev.turboism.internal.Requirements;
import java.util.Objects;

/** Local-only inputs needed to connect one exact host session. */
public record HostInstanceDescriptor(String sessionId, HostVerificationEvidence verificationEvidence) {
    public HostInstanceDescriptor {
        sessionId = Requirements.text(sessionId, "sessionId");
        verificationEvidence = Objects.requireNonNull(verificationEvidence, "verificationEvidence");
    }

    @Override
    public String toString() {
        return "HostInstanceDescriptor[sessionId=" + sessionId + ", connectionMaterial=redacted]";
    }
}

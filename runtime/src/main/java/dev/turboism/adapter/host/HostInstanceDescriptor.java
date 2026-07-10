package dev.turboism.adapter.host;

import java.nio.file.Path;
import java.util.Objects;

/** Local-only inputs needed to connect one exact host session. */
public record HostInstanceDescriptor(
    String sessionId,
    Path reviewedVerificationRecord,
    Path verifiedHostArtifact,
    ClassLoader hostClassLoader
) {
    public HostInstanceDescriptor {
        sessionId = requireText(sessionId, "sessionId");
        reviewedVerificationRecord = Objects.requireNonNull(
            reviewedVerificationRecord,
            "reviewedVerificationRecord"
        );
        verifiedHostArtifact = Objects.requireNonNull(verifiedHostArtifact, "verifiedHostArtifact");
        hostClassLoader = Objects.requireNonNull(hostClassLoader, "hostClassLoader");
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

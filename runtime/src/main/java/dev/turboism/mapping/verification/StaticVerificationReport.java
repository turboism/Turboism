package dev.turboism.mapping.verification;

import java.util.List;
import java.util.Objects;

public record StaticVerificationReport(
    HostArtifactFingerprint expectedFingerprint,
    HostArtifactFingerprint actualFingerprint,
    boolean artifactMatched,
    List<StaticSelectorResult> results
) {
    public StaticVerificationReport {
        expectedFingerprint = Objects.requireNonNull(expectedFingerprint, "expectedFingerprint");
        actualFingerprint = Objects.requireNonNull(actualFingerprint, "actualFingerprint");
        results = List.copyOf(Objects.requireNonNull(results, "results"));
    }

    public boolean allSelectorsVerified() {
        return artifactMatched
            && !results.isEmpty()
            && results.stream().allMatch(result -> result.status() == StaticVerificationStatus.VERIFIED_STATIC);
    }
}

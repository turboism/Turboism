package dev.turboism.mapping.verification;

import java.util.List;
import java.util.Objects;

/**
 * Result of verifying one host artifact against one reviewed record.
 *
 * @param expectedFingerprint the reviewed exact-version identity
 * @param actualFingerprint what the artifact on disk actually measured; its
 *     version field is not attested
 * @param artifactMatched whether size and hash agreed, and therefore whether
 *     the selector results mean anything
 * @param results one verdict per requested selector, defensively copied
 */
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

    /**
     * @return {@code true} only if the artifact matched and every selector
     *     verified statically; an empty result list is deliberately not a pass
     */
    public boolean allSelectorsVerified() {
        return artifactMatched
            && !results.isEmpty()
            && results.stream().allMatch(result -> result.status() == StaticVerificationStatus.VERIFIED_STATIC);
    }
}

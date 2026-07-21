package dev.turboism.mapping.verification;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/** Test-only construction helper; production code cannot forge verified plans. */
public final class TestVerifiedResolvers {

    private TestVerifiedResolvers() {
    }

    public static VerifiedMemberResolver create(
        final String adapterSliceId,
        final Set<String> capabilityIds,
        final List<StaticSelector> selectors,
        final ClassLoader classLoader
    ) {
        return create(
            "5.3.02",
            adapterSliceId,
            capabilityIds,
            selectors,
            classLoader
        );
    }

    public static VerifiedMemberResolver create(
        final String cubismVersion,
        final String adapterSliceId,
        final Set<String> capabilityIds,
        final List<StaticSelector> selectors,
        final ClassLoader classLoader
    ) {
        HostArtifactFingerprint fingerprint = new HostArtifactFingerprint(
            cubismVersion,
            1,
            "a".repeat(64)
        );
        StaticVerificationRecord record = new StaticVerificationRecord(
            "fixture.static",
            adapterSliceId,
            List.copyOf(capabilityIds),
            cubismVersion,
            "fixture-" + cubismVersion,
            fingerprint,
            "docs/migration/verification/static/fixture.json",
            "runtime-adapter",
            "test",
            Instant.parse("2026-07-10T00:00:00Z"),
            "Fail closed.",
            selectors
        );
        StaticVerificationReport report = new StaticVerificationReport(
            fingerprint,
            new HostArtifactFingerprint("artifact-version-unattested", 1, "a".repeat(64)),
            true,
            selectors.stream().map(selector -> new StaticSelectorResult(
                selector,
                StaticVerificationStatus.VERIFIED_STATIC,
                "verified"
            )).toList()
        );
        return new VerifiedMemberResolver(VerifiedAccessPlan.from(record, report), classLoader);
    }
}

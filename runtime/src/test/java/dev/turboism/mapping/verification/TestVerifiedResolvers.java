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
        return create(cubismVersion, adapterSliceId, capabilityIds,
            capabilityIds.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                id -> id, id -> List.of("structure"))), selectors, classLoader);
    }

    public static VerifiedMemberResolver create(
        final String cubismVersion,
        final String adapterSliceId,
        final Set<String> capabilityIds,
        final java.util.Map<String, List<String>> conditions,
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
            conditions,
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

    /**
     * Builds a resolver bound in compatibility mode: the record is authored for
     * {@code cubismVersion} (the reviewed generation), the host declares
     * {@code declaredVersion}, and the artifact fingerprint is measured rather
     * than review-pinned.
     *
     * @param cubismVersion the bound contract's source generation
     * @param declaredVersion the host's own declared version
     */
    public static VerifiedMemberResolver createCompatible(
        final String cubismVersion,
        final String declaredVersion,
        final String adapterSliceId,
        final Set<String> capabilityIds,
        final List<StaticSelector> selectors,
        final ClassLoader classLoader
    ) {
        return createCompatible(cubismVersion, declaredVersion, adapterSliceId, capabilityIds,
            capabilityIds.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                id -> id, id -> List.of("structure"))), selectors, classLoader);
    }

    public static VerifiedMemberResolver createCompatible(
        final String cubismVersion,
        final String declaredVersion,
        final String adapterSliceId,
        final Set<String> capabilityIds,
        final java.util.Map<String, List<String>> conditions,
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
            conditions,
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
        HostArtifactDigest liveArtifact =
            new HostArtifactDigest(1, "b".repeat(64));
        StaticSelectorVerifier.StructureVerificationReport report =
            new StaticSelectorVerifier.StructureVerificationReport(
                liveArtifact,
                selectors.stream().map(selector -> new StaticSelectorResult(
                    selector,
                    StaticVerificationStatus.VERIFIED_STATIC,
                    "verified"
                )).toList()
            );
        return new VerifiedMemberResolver(
            VerifiedAccessPlan.fromCompatibility(
                record, report, declaredVersion,
                new HostArtifactFingerprint("artifact-version-unattested", 1, "b".repeat(64))
            ),
            classLoader
        );
    }
}

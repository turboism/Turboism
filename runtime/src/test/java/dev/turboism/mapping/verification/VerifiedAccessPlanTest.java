package dev.turboism.mapping.verification;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VerifiedAccessPlanTest {

    @Test
    void exposesOnlyAliasesBackedByAnAllVerifiedReport() {
        StaticSelector selector = StaticSelector.method(
            "fixture.value",
            "fixture/Host",
            "value",
            "()Ljava/lang/String;",
            StaticSelector.ACCESS_PUBLIC
        );
        HostArtifactFingerprint fingerprint = new HostArtifactFingerprint(
            "5.3.02",
            100,
            "a".repeat(64)
        );
        StaticVerificationRecord record = record(fingerprint, selector);
        StaticVerificationReport report = new StaticVerificationReport(
            fingerprint,
            fingerprint,
            true,
            List.of(new StaticSelectorResult(
                selector,
                StaticVerificationStatus.VERIFIED_STATIC,
                "verified"
            ))
        );

        VerifiedAccessPlan plan = VerifiedAccessPlan.from(record, report);

        assertEquals(selector, plan.selector("fixture.value"));
        assertThrows(IllegalArgumentException.class, () -> plan.selector("fixture.unverified"));
    }

    @Test
    void rejectsSameAliasWhenSelectorTupleWasSubstituted() {
        StaticSelector verifiedSelector = StaticSelector.method(
            "fixture.value",
            "verified/Host",
            "value",
            "()Ljava/lang/String;",
            StaticSelector.ACCESS_PUBLIC
        );
        StaticSelector substitutedSelector = StaticSelector.method(
            "fixture.value",
            "other/Host",
            "otherValue",
            "()Ljava/lang/String;",
            StaticSelector.ACCESS_PUBLIC
        );
        HostArtifactFingerprint fingerprint = new HostArtifactFingerprint("5.3.02", 100, "a".repeat(64));
        StaticVerificationRecord record = record(fingerprint, substitutedSelector);
        StaticVerificationReport report = new StaticVerificationReport(
            fingerprint,
            new HostArtifactFingerprint("artifact-version-unattested", 100, "a".repeat(64)),
            true,
            List.of(new StaticSelectorResult(
                verifiedSelector,
                StaticVerificationStatus.VERIFIED_STATIC,
                "verified"
            ))
        );

        assertThrows(IllegalArgumentException.class, () -> VerifiedAccessPlan.from(record, report));
    }

    @Test
    void rejectsArtifactMismatchEvenWhenSelectorRowsLookVerified() {
        StaticSelector selector = StaticSelector.classSelector("fixture.host", "fixture/Host");
        HostArtifactFingerprint expected = new HostArtifactFingerprint("5.3.02", 100, "a".repeat(64));
        HostArtifactFingerprint actual = new HostArtifactFingerprint("5.3.02", 100, "b".repeat(64));
        StaticVerificationRecord record = record(expected, selector);
        StaticVerificationReport report = new StaticVerificationReport(
            expected,
            actual,
            false,
            List.of(new StaticSelectorResult(
                selector,
                StaticVerificationStatus.ARTIFACT_MISMATCH,
                "mismatch"
            ))
        );

        assertThrows(IllegalArgumentException.class, () -> VerifiedAccessPlan.from(record, report));
    }

    private static StaticVerificationRecord record(
        final HostArtifactFingerprint fingerprint,
        final StaticSelector selector
    ) {
        return new StaticVerificationRecord(
            "fixture.static",
            "adapter.project-workspace.readonly",
            List.of("cubism.project.read"),
            "5.3.02",
            "cubism-5.3.02",
            fingerprint,
            "docs/migration/verification/static/fixture.json",
            "runtime-adapter",
            "test",
            Instant.parse("2026-07-10T00:00:00Z"),
            "Fail closed.",
            List.of(selector)
        );
    }
}

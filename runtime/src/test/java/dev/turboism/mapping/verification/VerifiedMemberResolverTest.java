package dev.turboism.mapping.verification;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VerifiedMemberResolverTest {

    @Test
    void invokesOnlyExactVerifiedStaticAndInstanceMethods() {
        VerifiedMemberResolver resolver = new VerifiedMemberResolver(
            plan(
                StaticSelector.staticMethod(
                    "fixture.static-value",
                    internalName(SyntheticHost.class),
                    "staticValue",
                    "()Ljava/lang/String;",
                    StaticSelector.ACCESS_PUBLIC
                ),
                StaticSelector.method(
                    "fixture.instance-value",
                    internalName(SyntheticHost.class),
                    "instanceValue",
                    "()Ljava/lang/String;",
                    StaticSelector.ACCESS_PUBLIC
                )
            ),
            SyntheticHost.class.getClassLoader()
        );

        assertEquals("static", resolver.invokeStatic("fixture.static-value"));
        assertEquals("instance", resolver.invoke("fixture.instance-value", new SyntheticHost()));
        assertThrows(VerifiedAccessException.class, () -> resolver.invokeStatic("fixture.unverified"));
    }

    @Test
    void sanitizesHostExceptions() {
        VerifiedMemberResolver resolver = new VerifiedMemberResolver(
            plan(StaticSelector.method(
                "fixture.failure",
                internalName(SyntheticHost.class),
                "fail",
                "()Ljava/lang/String;",
                StaticSelector.ACCESS_PUBLIC
            )),
            SyntheticHost.class.getClassLoader()
        );

        VerifiedAccessException failure = assertThrows(
            VerifiedAccessException.class,
            () -> resolver.invoke("fixture.failure", new SyntheticHost())
        );

        assertEquals("fixture.failure", failure.alias());
        assertFalse(failure.getMessage().contains("private-host-detail"));
    }

    private static VerifiedAccessPlan plan(final StaticSelector... selectors) {
        HostArtifactFingerprint fingerprint = new HostArtifactFingerprint("5.3.02", 1, "a".repeat(64));
        StaticVerificationRecord record = new StaticVerificationRecord(
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
            List.of(selectors)
        );
        StaticVerificationReport report = new StaticVerificationReport(
            fingerprint,
            fingerprint,
            true,
            List.of(selectors).stream()
                .map(selector -> new StaticSelectorResult(
                    selector,
                    StaticVerificationStatus.VERIFIED_STATIC,
                    "verified"
                ))
                .toList()
        );
        return VerifiedAccessPlan.from(record, report);
    }

    private static String internalName(final Class<?> type) {
        return type.getName().replace('.', '/');
    }

    public static final class SyntheticHost {
        public static String staticValue() {
            return "static";
        }

        public String instanceValue() {
            return "instance";
        }

        public String fail() {
            throw new IllegalStateException("private-host-detail");
        }
    }
}

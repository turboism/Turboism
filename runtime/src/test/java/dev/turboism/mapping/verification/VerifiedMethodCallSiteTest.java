package dev.turboism.mapping.verification;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerifiedMethodCallSiteTest {

    @Test
    void bindsStaticAndInstanceMethodsOnceAndReleasesThemOnClose() {
        final VerifiedMemberResolver resolver = resolver();
        final VerifiedMethodCallSite staticSite = resolver.bindStatic("fixture.host.create");
        final VerifiedMethodCallSite valueSite = resolver.bind("fixture.host.value");

        final FixtureHost host = (FixtureHost) staticSite.invokeStatic("ready");
        assertEquals("ready", valueSite.invoke(host));

        staticSite.close();
        valueSite.close();
        assertTrue(staticSite.isClosed());
        assertTrue(valueSite.isClosed());
        assertEquals(
            VerifiedAccessException.FailureKind.RESOLUTION,
            assertThrows(
                VerifiedAccessException.class,
                () -> valueSite.invoke(host)
            ).failureKind()
        );
    }

    @Test
    void descriptorDriftFailsDuringBinding() {
        final VerifiedAccessException failure = assertThrows(
            VerifiedAccessException.class,
            () -> resolver().bind("fixture.host.bad-descriptor")
        );
        assertEquals(
            VerifiedAccessException.FailureKind.RESOLUTION,
            failure.failureKind()
        );
    }

    @Test
    void wrongTargetAndTargetFailureRemainSanitized() {
        final VerifiedMemberResolver resolver = resolver();
        final VerifiedMethodCallSite valueSite = resolver.bind("fixture.host.value");
        final VerifiedMethodCallSite failureSite = resolver.bind("fixture.host.fail");

        assertEquals(
            VerifiedAccessException.FailureKind.RESOLUTION,
            assertThrows(
                VerifiedAccessException.class,
                () -> valueSite.invoke("wrong")
            ).failureKind()
        );
        assertEquals(
            VerifiedAccessException.FailureKind.INVOCATION,
            assertThrows(
                VerifiedAccessException.class,
                () -> failureSite.invoke(new FixtureHost("ready"))
            ).failureKind()
        );
    }

    private static VerifiedMemberResolver resolver() {
        final String owner = FixtureHost.class.getName().replace('.', '/');
        return TestVerifiedResolvers.create(
            "fixture.callsites",
            Set.of("fixture.read"),
            List.of(
                StaticSelector.staticMethod(
                    "fixture.host.create",
                    owner,
                    "create",
                    "(Ljava/lang/String;)L" + owner + ";",
                    StaticSelector.ACCESS_PUBLIC
                ),
                StaticSelector.method(
                    "fixture.host.value",
                    owner,
                    "value",
                    "()Ljava/lang/String;",
                    StaticSelector.ACCESS_PUBLIC
                ),
                StaticSelector.method(
                    "fixture.host.fail",
                    owner,
                    "fail",
                    "()V",
                    StaticSelector.ACCESS_PUBLIC
                ),
                StaticSelector.method(
                    "fixture.host.bad-descriptor",
                    owner,
                    "value",
                    "()I",
                    StaticSelector.ACCESS_PUBLIC
                )
            ),
            FixtureHost.class.getClassLoader()
        );
    }

    public record FixtureHost(String value) {

        public static FixtureHost create(final String value) {
            return new FixtureHost(value);
        }

        public void fail() {
            throw new IllegalStateException("fixture failure");
        }
    }
}

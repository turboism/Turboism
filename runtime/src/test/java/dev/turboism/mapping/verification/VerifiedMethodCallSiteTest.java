package dev.turboism.mapping.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.mapping.verification.fixture.PackagePrivateMethodHost;
import dev.turboism.mapping.verification.fixture.PackagePrivateMethodOnlyHost;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

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
                assertThrows(VerifiedAccessException.class, () -> valueSite.invoke(host))
                        .failureKind());
    }

    @Test
    void descriptorDriftFailsDuringBinding() {
        final VerifiedAccessException failure =
                assertThrows(VerifiedAccessException.class, () -> resolver().bind("fixture.host.bad-descriptor"));
        assertEquals(VerifiedAccessException.FailureKind.RESOLUTION, failure.failureKind());
    }

    @Test
    void wrongTargetAndTargetFailureRemainSanitized() {
        final VerifiedMemberResolver resolver = resolver();
        final VerifiedMethodCallSite valueSite = resolver.bind("fixture.host.value");
        final VerifiedMethodCallSite failureSite = resolver.bind("fixture.host.fail");

        assertEquals(
                VerifiedAccessException.FailureKind.RESOLUTION,
                assertThrows(VerifiedAccessException.class, () -> valueSite.invoke("wrong"))
                        .failureKind());
        final var failure =
                assertThrows(VerifiedAccessException.class, () -> failureSite.invoke(new FixtureHost("ready")));
        assertEquals(VerifiedAccessException.FailureKind.INVOCATION, failure.failureKind());
        assertEquals(VerifiedAccessException.HostFailureCategory.ILLEGAL_STATE, failure.hostFailureCategory());
        assertEquals(null, failure.getCause());
    }

    @Test
    void argumentMismatchesStayResolutionFailuresAndDoNotPoisonTheSite() {
        final VerifiedMemberResolver resolver = resolver();
        final VerifiedMethodCallSite factory = resolver.bindStatic("fixture.host.create");
        final VerifiedMethodCallSite echo = resolver.bindStatic("fixture.host.echo");
        final VerifiedMethodCallSite value = resolver.bind("fixture.host.value");
        final FixtureHost host = new FixtureHost("ready");

        assertEquals(
                VerifiedAccessException.FailureKind.RESOLUTION,
                assertThrows(VerifiedAccessException.class, factory::invokeStatic)
                        .failureKind());
        assertEquals(
                VerifiedAccessException.FailureKind.RESOLUTION,
                assertThrows(VerifiedAccessException.class, () -> factory.invokeStatic("ready", "extra"))
                        .failureKind());
        assertEquals(
                VerifiedAccessException.FailureKind.RESOLUTION,
                assertThrows(VerifiedAccessException.class, () -> factory.invokeStatic(42))
                        .failureKind());
        assertEquals(
                VerifiedAccessException.FailureKind.RESOLUTION,
                assertThrows(VerifiedAccessException.class, () -> echo.invokeStatic((Object) null))
                        .failureKind());
        assertEquals(
                VerifiedAccessException.FailureKind.RESOLUTION,
                assertThrows(VerifiedAccessException.class, () -> echo.invokeStatic("not-an-int"))
                        .failureKind());
        assertEquals(
                VerifiedAccessException.FailureKind.RESOLUTION,
                assertThrows(VerifiedAccessException.class, () -> value.invoke(host, "extra"))
                        .failureKind());

        assertEquals("int:7", echo.invokeStatic(7));
        assertEquals("int:7", echo.invokeStatic((byte) 7));
        assertEquals("ready", value.invoke(host));
    }

    @Test
    void hostNullPointerFailureRemainsAnInvocationFailure() {
        final VerifiedMethodCallSite site = resolver().bind("fixture.host.fail-null");

        final var failure = assertThrows(VerifiedAccessException.class, () -> site.invoke(new FixtureHost("ready")));

        assertEquals(VerifiedAccessException.FailureKind.INVOCATION, failure.failureKind());
        assertEquals(VerifiedAccessException.HostFailureCategory.NULL_POINTER, failure.hostFailureCategory());
        assertNull(failure.getCause());
    }

    @Test
    void hostFatalErrorPropagatesTheSameInstance() {
        final VerifiedMethodCallSite site = resolver().bind("fixture.host.fail-oom");

        assertSame(
                FixtureHost.FATAL, assertThrows(OutOfMemoryError.class, () -> site.invoke(new FixtureHost("ready"))));
    }

    @Test
    void opensOnlyPublicMethodsDeclaredOnNonPublicOwners() {
        final VerifiedMemberResolver resolver = resolver();
        final VerifiedMethodCallSite publicOnPackagePrivateOwner = resolver.bind("fixture.host.package-private-owner");
        final VerifiedMethodCallSite packagePrivateMethod = resolver.bind("fixture.host.package-private-method");

        assertEquals("package-private-owner", publicOnPackagePrivateOwner.invoke(PackagePrivateMethodHost.create()));

        final var failure = assertThrows(
                VerifiedAccessException.class,
                () -> packagePrivateMethod.invoke(PackagePrivateMethodOnlyHost.create()));
        assertEquals(VerifiedAccessException.FailureKind.RESOLUTION, failure.failureKind());
        assertNull(failure.getCause());
    }

    private static VerifiedMemberResolver resolver() {
        final String owner = FixtureHost.class.getName().replace('.', '/');
        final String packagePrivateOwner =
                PackagePrivateMethodHost.type().getName().replace('.', '/');
        final String packagePrivateMethodOwner =
                PackagePrivateMethodOnlyHost.class.getName().replace('.', '/');
        return TestVerifiedResolvers.create(
                "fixture.callsites",
                Set.of("fixture.read"),
                List.of(
                        StaticSelector.staticMethod(
                                "fixture.host.create",
                                owner,
                                "create",
                                "(Ljava/lang/String;)L" + owner + ";",
                                StaticSelector.ACCESS_PUBLIC),
                        StaticSelector.method(
                                "fixture.host.value",
                                owner,
                                "value",
                                "()Ljava/lang/String;",
                                StaticSelector.ACCESS_PUBLIC),
                        StaticSelector.method("fixture.host.fail", owner, "fail", "()V", StaticSelector.ACCESS_PUBLIC),
                        StaticSelector.staticMethod(
                                "fixture.host.echo",
                                owner,
                                "echo",
                                "(I)Ljava/lang/String;",
                                StaticSelector.ACCESS_PUBLIC),
                        StaticSelector.method(
                                "fixture.host.fail-null", owner, "failNull", "()V", StaticSelector.ACCESS_PUBLIC),
                        StaticSelector.method(
                                "fixture.host.fail-oom", owner, "failOom", "()V", StaticSelector.ACCESS_PUBLIC),
                        StaticSelector.method(
                                "fixture.host.package-private-owner",
                                packagePrivateOwner,
                                "value",
                                "()Ljava/lang/String;",
                                StaticSelector.ACCESS_PUBLIC),
                        StaticSelector.method(
                                "fixture.host.package-private-method",
                                packagePrivateMethodOwner,
                                "hidden",
                                "()Ljava/lang/String;",
                                0),
                        StaticSelector.method(
                                "fixture.host.bad-descriptor", owner, "value", "()I", StaticSelector.ACCESS_PUBLIC)),
                FixtureHost.class.getClassLoader());
    }

    public record FixtureHost(String value) {

        static final OutOfMemoryError FATAL = new OutOfMemoryError("fixture fatal");

        public static FixtureHost create(final String value) {
            return new FixtureHost(value);
        }

        public static String echo(final int value) {
            return "int:" + value;
        }

        public void fail() {
            throw new IllegalStateException("fixture failure");
        }

        public void failNull() {
            throw new NullPointerException("fixture null failure");
        }

        public void failOom() {
            throw FATAL;
        }
    }
}

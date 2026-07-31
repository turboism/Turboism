package dev.turboism.mapping.verification;

import dev.turboism.mapping.verification.fixture.PackagePrivateConstructorHost;
import dev.turboism.mapping.verification.fixture.PackagePrivateMethodHost;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerifiedMemberResolverTest {

    @Test
    void exposesOnlyTheAttestedDefiningClassloader() {
        ClassLoader loader = SyntheticHost.class.getClassLoader();
        VerifiedMemberResolver resolver = new VerifiedMemberResolver(
            plan(StaticSelector.classSelector(
                "fixture.host-class",
                internalName(SyntheticHost.class)
            )),
            loader
        );

        assertEquals(loader, resolver.hostClassLoader());
    }

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
    void invokesExactVerifiedPublicMethodsDeclaredByPackagePrivateHostTypes() {
        VerifiedMemberResolver resolver = new VerifiedMemberResolver(
            plan(StaticSelector.method(
                "fixture.package-private-owner-method",
                internalName(PackagePrivateMethodHost.type()),
                "value",
                "()Ljava/lang/String;",
                StaticSelector.ACCESS_PUBLIC
            )),
            SyntheticHost.class.getClassLoader()
        );

        assertEquals(
            "package-private-owner",
            resolver.invoke("fixture.package-private-owner-method", PackagePrivateMethodHost.create())
        );
    }

    @Test
    void readsOnlyExactVerifiedPublicStaticFields() {
        VerifiedMemberResolver resolver = new VerifiedMemberResolver(
            plan(StaticSelector.field(
                "fixture.static-field",
                internalName(SyntheticHost.class),
                "STATIC_VALUE",
                "Ljava/lang/String;",
                StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC
            )),
            SyntheticHost.class.getClassLoader()
        );

        assertEquals("field", resolver.readStaticField("fixture.static-field"));
        assertThrows(
            VerifiedAccessException.class,
            () -> resolver.readStaticField("fixture.unverified")
        );
    }

    @Test
    void readsOnlyExactVerifiedPrivateInstanceFields() {
        final VerifiedMemberResolver resolver = new VerifiedMemberResolver(
            plan(StaticSelector.field(
                "fixture.instance-field",
                internalName(SyntheticHost.class),
                "value",
                "Ljava/lang/String;",
                0
            )),
            SyntheticHost.class.getClassLoader()
        );

        assertEquals("instance", resolver.readField("fixture.instance-field", new SyntheticHost()));
        assertThrows(VerifiedAccessException.class, () -> resolver.readField("fixture.instance-field", new Object()));
    }

    @Test
    void constructsOnlyExactVerifiedConstructors() {
        VerifiedMemberResolver resolver = new VerifiedMemberResolver(
            plan(StaticSelector.constructor(
                "fixture.constructor",
                internalName(SyntheticHost.class),
                "(Ljava/lang/String;)V",
                StaticSelector.ACCESS_PUBLIC
            )),
            SyntheticHost.class.getClassLoader()
        );

        SyntheticHost host = (SyntheticHost) resolver.construct("fixture.constructor", "constructed");

        assertEquals("constructed", host.instanceValue());
        assertThrows(VerifiedAccessException.class, () -> resolver.construct("fixture.unverified"));
    }

    @Test
    void constructsExactVerifiedPackagePrivateHostConstructors() {
        VerifiedMemberResolver resolver = new VerifiedMemberResolver(
            plan(StaticSelector.constructor(
                "fixture.package-private-constructor",
                internalName(PackagePrivateConstructorHost.class),
                "(Ljava/lang/String;)V",
                0
            )),
            SyntheticHost.class.getClassLoader()
        );

        PackagePrivateConstructorHost host = (PackagePrivateConstructorHost) resolver.construct(
            "fixture.package-private-constructor",
            "constructed"
        );

        assertEquals("constructed", host.value());
    }

    @Test
    void doesNotOpenUnverifiedPrivateHostConstructors() {
        VerifiedMemberResolver resolver = new VerifiedMemberResolver(
            plan(StaticSelector.constructor(
                "fixture.private-constructor",
                internalName(PrivateConstructorHost.class),
                "()V",
                0
            )),
            SyntheticHost.class.getClassLoader()
        );

        assertThrows(
            VerifiedAccessException.class,
            () -> resolver.construct("fixture.private-constructor")
        );
    }

    @Test
    void createsOnlyExactVerifiedSingleAbstractMethodHostProxies() {
        VerifiedMemberResolver resolver = new VerifiedMemberResolver(
            plan(
                StaticSelector.classSelector(
                    "fixture.callback",
                    internalName(SyntheticCallback.class)
                ),
                StaticSelector.classSelector(
                    "fixture.not-interface",
                    internalName(SyntheticHost.class)
                ),
                StaticSelector.classSelector(
                    "fixture.not-sam",
                    internalName(NotSingleAbstractMethod.class)
                )
            ),
            SyntheticHost.class.getClassLoader()
        );
        AtomicReference<Object> argument = new AtomicReference<>();

        SyntheticCallback callback = (SyntheticCallback) resolver.createFunctionalProxy(
            "fixture.callback",
            value -> {
                argument.set(value);
                return "handled";
            }
        );

        assertEquals("handled", callback.apply("event"));
        assertEquals("event", argument.get());
        assertThrows(
            VerifiedAccessException.class,
            () -> resolver.createFunctionalProxy("fixture.not-interface", ignored -> null)
        );
        assertThrows(
            VerifiedAccessException.class,
            () -> resolver.createFunctionalProxy("fixture.not-sam", ignored -> null)
        );
    }

    @Test
    void createsCallbackProxyOnlyFromAnExactVerifiedMethodParameter() {
        VerifiedMemberResolver resolver = new VerifiedMemberResolver(
            plan(StaticSelector.staticMethod(
                "fixture.callback-consumer",
                internalName(SyntheticHost.class),
                "callCallback",
                "(L" + internalName(SyntheticZeroCallback.class) + ";)Ljava/lang/Object;",
                StaticSelector.ACCESS_PUBLIC
            )),
            SyntheticHost.class.getClassLoader()
        );
        AtomicReference<Object> argument = new AtomicReference<>("not-called");

        SyntheticZeroCallback callback = (SyntheticZeroCallback)
            resolver.createFunctionalArgumentProxy(
                "fixture.callback-consumer",
                0,
                value -> {
                    argument.set(value);
                    return "handled";
                }
            );

        assertEquals("handled", SyntheticHost.callCallback(callback));
        assertEquals(null, argument.get());
        assertThrows(
            VerifiedAccessException.class,
            () -> resolver.createFunctionalArgumentProxy(
                "fixture.callback-consumer",
                1,
                ignored -> null
            )
        );
        assertThrows(
            VerifiedAccessException.class,
            () -> resolver.createFunctionalArgumentProxy(
                "fixture.unverified",
                0,
                ignored -> null
            )
        );
    }

    @Test
    void createsCallbackProxyOnlyFromAnExactVerifiedConstructorParameter() {
        VerifiedMemberResolver resolver = new VerifiedMemberResolver(
            plan(StaticSelector.constructor(
                "fixture.callback-constructor",
                internalName(SyntheticConstructorConsumer.class),
                "(L" + internalName(SyntheticZeroCallback.class) + ";)V",
                StaticSelector.ACCESS_PUBLIC
            )),
            SyntheticHost.class.getClassLoader()
        );
        AtomicReference<Object> argument = new AtomicReference<>("not-called");

        SyntheticZeroCallback callback = (SyntheticZeroCallback)
            resolver.createFunctionalConstructorArgumentProxy(
                "fixture.callback-constructor",
                0,
                value -> {
                    argument.set(value);
                    return "handled";
                }
            );
        SyntheticConstructorConsumer consumer = (SyntheticConstructorConsumer) resolver.construct(
            "fixture.callback-constructor",
            callback
        );

        assertEquals("handled", consumer.invoke());
        assertEquals(null, argument.get());
        assertThrows(
            VerifiedAccessException.class,
            () -> resolver.createFunctionalConstructorArgumentProxy(
                "fixture.callback-constructor",
                1,
                ignored -> null
            )
        );
        assertThrows(
            VerifiedAccessException.class,
            () -> resolver.createFunctionalConstructorArgumentProxy(
                "fixture.unverified",
                0,
                ignored -> null
            )
        );
    }

    @Test
    void checksInstancesOnlyAgainstExactVerifiedClassAliases() {
        VerifiedMemberResolver resolver = new VerifiedMemberResolver(
            plan(StaticSelector.classSelector(
                "fixture.host-class",
                internalName(SyntheticHost.class)
            )),
            SyntheticHost.class.getClassLoader()
        );

        assertTrue(resolver.isInstance("fixture.host-class", new SyntheticHost()));
        assertFalse(resolver.isInstance("fixture.host-class", new Object()));
        assertFalse(resolver.isInstance("fixture.host-class", null));
        assertThrows(VerifiedAccessException.class, () -> resolver.isInstance("fixture.unverified", new SyntheticHost()));
    }

    @Test
    void rejectsMethodAliasForInstanceCheck() {
        VerifiedMemberResolver resolver = new VerifiedMemberResolver(
            plan(StaticSelector.method(
                "fixture.instance-value",
                internalName(SyntheticHost.class),
                "instanceValue",
                "()Ljava/lang/String;",
                StaticSelector.ACCESS_PUBLIC
            )),
            SyntheticHost.class.getClassLoader()
        );

        assertThrows(VerifiedAccessException.class, () -> resolver.isInstance("fixture.instance-value", new SyntheticHost()));
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

    public interface SyntheticCallback {
        Object apply(Object value);
    }

    public interface SyntheticZeroCallback {
        Object apply();
    }

    public interface NotSingleAbstractMethod {
        Object first(Object value);
        Object second(Object value);
    }

    public static final class PrivateConstructorHost {
        private PrivateConstructorHost() { }
    }

    public static final class SyntheticConstructorConsumer {
        private final SyntheticZeroCallback callback;

        public SyntheticConstructorConsumer(final SyntheticZeroCallback callback) {
            this.callback = callback;
        }

        public Object invoke() {
            return callback.apply();
        }
    }

    public static final class SyntheticHost {
        public static final String STATIC_VALUE = "field";
        private final String value;

        public SyntheticHost() {
            this("instance");
        }

        public SyntheticHost(final String value) {
            this.value = value;
        }

        public static String staticValue() {
            return "static";
        }

        public static Object callCallback(final SyntheticZeroCallback callback) {
            return callback.apply();
        }

        public String instanceValue() {
            return value;
        }

        public String fail() {
            throw new IllegalStateException("private-host-detail");
        }
    }
}

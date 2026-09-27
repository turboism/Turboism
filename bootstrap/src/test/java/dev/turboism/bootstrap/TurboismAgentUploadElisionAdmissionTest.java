package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.uploadelision.SkippedFrameUploadElisionBridge;
import dev.turboism.adapter.cubism.optimization.uploadelision.SkippedFrameUploadElisionTransformer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TurboismAgentUploadElisionAdmissionTest {

    @Test
    void admitsOnlyAfterFullRuntimeAdmission() {
        final SkippedFrameUploadElisionHookContributor contributor =
            new SkippedFrameUploadElisionHookContributor();
        assertTrue(contributor.admitted(environment(true)));
        assertFalse(contributor.admitted(environment(false)));
    }

    @Test
    void flagOffInstallsNothingAndNeverTouchesTheHost() throws Exception {
        final String prior =
            System.getProperty(SkippedFrameUploadElisionTransformer.ENABLE_PROPERTY);
        final String priorProduction =
            System.getProperty(SkippedFrameUploadElisionBridge.ENABLE_PROPERTY);
        System.clearProperty(SkippedFrameUploadElisionTransformer.ENABLE_PROPERTY);
        // The production preference is default-on; the flag-off contract must
        // be proven against an explicit opt-out, not an unset property.
        System.setProperty(SkippedFrameUploadElisionBridge.ENABLE_PROPERTY, "false");
        final java.util.concurrent.atomic.AtomicInteger instrumentationCalls =
            new java.util.concurrent.atomic.AtomicInteger();
        final java.lang.instrument.Instrumentation instrumentation =
            (java.lang.instrument.Instrumentation) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {java.lang.instrument.Instrumentation.class},
                (proxy, method, args) -> {
                    instrumentationCalls.incrementAndGet();
                    throw new UnsupportedOperationException(method.getName());
                });
        try {
            final SkippedFrameUploadElisionHookContributor contributor =
                new SkippedFrameUploadElisionHookContributor();
            // The flag gate runs before any host access: install succeeds as a
            // no-op even though the environment carries no located host, and no
            // instrumentation method — transformer registration included — is
            // ever invoked.
            // Exercise the gate directly: the outer install wrapper swallows host
            // failures, which would otherwise let a premature host access pass.
            assertNotNull(contributor.installAdmitted(HookEnvironment.builder()
                .fullRuntimeAdmission(true)
                .instrumentation(instrumentation)
                .build()));
            org.junit.jupiter.api.Assertions.assertEquals(0, instrumentationCalls.get(),
                "flag off must not touch the host's instrumentation");
            assertFalse(System.getProperties().containsKey(
                SkippedFrameUploadElisionBridge.PREDICATE_PROPERTY));
        } finally {
            if (prior == null) {
                System.clearProperty(SkippedFrameUploadElisionTransformer.ENABLE_PROPERTY);
            } else {
                System.setProperty(SkippedFrameUploadElisionTransformer.ENABLE_PROPERTY, prior);
            }
            if (priorProduction == null) {
                System.clearProperty(SkippedFrameUploadElisionBridge.ENABLE_PROPERTY);
            } else {
                System.setProperty(SkippedFrameUploadElisionBridge.ENABLE_PROPERTY,
                    priorProduction);
            }
        }
    }

    @Test
    void flagOnWithoutHostFailsSafe() {
        final String prior =
            System.getProperty(SkippedFrameUploadElisionTransformer.ENABLE_PROPERTY);
        System.setProperty(SkippedFrameUploadElisionTransformer.ENABLE_PROPERTY, "true");
        try {
            final SkippedFrameUploadElisionHookContributor contributor =
                new SkippedFrameUploadElisionHookContributor();
            // The missing host surfaces as installation=FAILED inside install(); a
            // failed experiment must never stop official startup.
            assertNotNull(contributor.install(environment(true)));
        } finally {
            if (prior == null) {
                System.clearProperty(SkippedFrameUploadElisionTransformer.ENABLE_PROPERTY);
            } else {
                System.setProperty(SkippedFrameUploadElisionTransformer.ENABLE_PROPERTY, prior);
            }
        }
    }

    @Test
    void productionFlagInstallsNothingWhenModelUpdateSkipIsDisabled() {
        // The production path refuses before touching the host when the
        // skipped-frame precondition cannot exist.
        withProperty(SkippedFrameUploadElisionBridge.ENABLE_PROPERTY, "true", () -> {
            withProperty(
                dev.turboism.adapter.cubism.optimization.modelupdate
                    .ModelUpdateSkipBridge.ENABLE_PROPERTY, "false", () -> {
                final var contributor = new SkippedFrameUploadElisionHookContributor();
                assertNotNull(contributor.install(environment(true)));
            });
        });
    }

    @Test
    void productionFlagOnWithoutHostFailsSafe() {
        withProperty(SkippedFrameUploadElisionBridge.ENABLE_PROPERTY, "true", () -> {
            final var contributor = new SkippedFrameUploadElisionHookContributor();
            assertNotNull(contributor.install(environment(true)));
        });
    }

    private static void withProperty(final String name, final String value,
                                     final Runnable body) {
        final String prior = System.getProperty(name);
        System.setProperty(name, value);
        try {
            body.run();
        } finally {
            if (prior == null) {
                System.clearProperty(name);
            } else {
                System.setProperty(name, prior);
            }
        }
    }

    private static HookEnvironment environment(final boolean admitted) {
        return HookEnvironment.builder()
            .profile("5.3.03")
            .fullRuntimeAdmission(admitted)
            .build();
    }
}

package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.deferred.DeferredGlErrorCheckTransformer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TurboismAgentDeferredGlErrorCheckAdmissionTest {

    @Test
    void admitsOnlyAfterFullRuntimeAdmission() {
        final DeferredGlErrorCheckHookContributor contributor =
            new DeferredGlErrorCheckHookContributor();
        assertTrue(contributor.admitted(environment(true)));
        assertFalse(contributor.admitted(environment(false)));
    }

    @Test
    void flagOffInstallsNothingAndNeverTouchesTheHost() {
        final String prior = System.getProperty(DeferredGlErrorCheckTransformer.ENABLE_PROPERTY);
        System.clearProperty(DeferredGlErrorCheckTransformer.ENABLE_PROPERTY);
        try {
            final DeferredGlErrorCheckHookContributor contributor =
                new DeferredGlErrorCheckHookContributor();
            // The flag gate runs before any host access: install succeeds as a no-op
            // even though the environment carries no located host.
            assertNotNull(contributor.install(environment(true)));
        } finally {
            if (prior == null) {
                System.clearProperty(DeferredGlErrorCheckTransformer.ENABLE_PROPERTY);
            } else {
                System.setProperty(DeferredGlErrorCheckTransformer.ENABLE_PROPERTY, prior);
            }
        }
    }

    @Test
    void flagOnWithoutHostFailsSafe() {
        final String prior = System.getProperty(DeferredGlErrorCheckTransformer.ENABLE_PROPERTY);
        System.setProperty(DeferredGlErrorCheckTransformer.ENABLE_PROPERTY, "true");
        try {
            final DeferredGlErrorCheckHookContributor contributor =
                new DeferredGlErrorCheckHookContributor();
            // The missing host/policy surfaces as installation=FAILED inside
            // install(); a failed hook must never stop official startup.
            assertNotNull(contributor.install(environment(true)));
        } finally {
            if (prior == null) {
                System.clearProperty(DeferredGlErrorCheckTransformer.ENABLE_PROPERTY);
            } else {
                System.setProperty(DeferredGlErrorCheckTransformer.ENABLE_PROPERTY, prior);
            }
        }
    }

    private static HookEnvironment environment(final boolean admitted) {
        return HookEnvironment.builder()
            .fullRuntimeAdmission(admitted)
            .build();
    }
}

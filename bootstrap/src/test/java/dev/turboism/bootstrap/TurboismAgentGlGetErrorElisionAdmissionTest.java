package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.glerror.GlGetErrorElisionTransformer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TurboismAgentGlGetErrorElisionAdmissionTest {

    @Test
    void admitsOnlyAfterFullRuntimeAdmission() {
        final GlGetErrorElisionHookContributor contributor =
            new GlGetErrorElisionHookContributor();
        assertTrue(contributor.admitted(environment(true)));
        assertFalse(contributor.admitted(environment(false)));
    }

    @Test
    void flagOffInstallsNothingAndNeverTouchesTheHost() {
        final String prior = System.getProperty(GlGetErrorElisionTransformer.ENABLE_PROPERTY);
        System.clearProperty(GlGetErrorElisionTransformer.ENABLE_PROPERTY);
        try {
            final GlGetErrorElisionHookContributor contributor =
                new GlGetErrorElisionHookContributor();
            // The flag gate runs before any host access: install succeeds as a no-op
            // even though the environment carries no located host.
            assertNotNull(contributor.install(environment(true)));
        } finally {
            if (prior == null) {
                System.clearProperty(GlGetErrorElisionTransformer.ENABLE_PROPERTY);
            } else {
                System.setProperty(GlGetErrorElisionTransformer.ENABLE_PROPERTY, prior);
            }
        }
    }

    @Test
    void flagOnWithoutHostFailsSafe() {
        final String prior = System.getProperty(GlGetErrorElisionTransformer.ENABLE_PROPERTY);
        System.setProperty(GlGetErrorElisionTransformer.ENABLE_PROPERTY, "true");
        try {
            final GlGetErrorElisionHookContributor contributor =
                new GlGetErrorElisionHookContributor();
            // The missing host surfaces as installation=FAILED inside install(); a
            // failed experiment must never stop official startup.
            assertNotNull(contributor.install(environment(true)));
        } finally {
            if (prior == null) {
                System.clearProperty(GlGetErrorElisionTransformer.ENABLE_PROPERTY);
            } else {
                System.setProperty(GlGetErrorElisionTransformer.ENABLE_PROPERTY, prior);
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

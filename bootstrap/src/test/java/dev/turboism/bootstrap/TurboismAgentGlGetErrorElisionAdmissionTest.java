package dev.turboism.bootstrap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.adapter.cubism.optimization.glerror.GlGetErrorElisionTransformer;
import org.junit.jupiter.api.Test;

final class TurboismAgentGlGetErrorElisionAdmissionTest {

    @Test
    void admitsOnlyAfterFullRuntimeAdmission() {
        final GlGetErrorElisionHookContributor contributor = new GlGetErrorElisionHookContributor();
        assertTrue(contributor.admitted(HookEnvironments.admitted(true)));
        assertFalse(contributor.admitted(HookEnvironments.admitted(false)));
    }

    @Test
    void flagOffInstallsNothingAndNeverTouchesTheHost() {
        final String prior = System.getProperty(GlGetErrorElisionTransformer.ENABLE_PROPERTY);
        System.clearProperty(GlGetErrorElisionTransformer.ENABLE_PROPERTY);
        try {
            final GlGetErrorElisionHookContributor contributor = new GlGetErrorElisionHookContributor();
            // The flag gate runs before any host access: install succeeds as a no-op
            // even though the environment carries no located host.
            assertNotNull(contributor.install(HookEnvironments.admitted(true)));
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
            final GlGetErrorElisionHookContributor contributor = new GlGetErrorElisionHookContributor();
            // The missing host surfaces as installation=FAILED inside install(); a
            // failed experiment must never stop official startup.
            assertNotNull(contributor.install(HookEnvironments.admitted(true)));
        } finally {
            if (prior == null) {
                System.clearProperty(GlGetErrorElisionTransformer.ENABLE_PROPERTY);
            } else {
                System.setProperty(GlGetErrorElisionTransformer.ENABLE_PROPERTY, prior);
            }
        }
    }
}

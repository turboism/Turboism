package dev.turboism.bootstrap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.adapter.cubism.optimization.composite.CanvasCompositeElisionTransformer;
import org.junit.jupiter.api.Test;

final class TurboismAgentCanvasCompositeElisionAdmissionTest {

    @Test
    void admitsOnlyAfterFullRuntimeAdmission() {
        final CanvasCompositeElisionHookContributor contributor = new CanvasCompositeElisionHookContributor();
        assertTrue(contributor.admitted(environment(true)));
        assertFalse(contributor.admitted(environment(false)));
    }

    @Test
    void flagOffInstallsNothingAndNeverTouchesTheHost() {
        final String prior = System.getProperty(CanvasCompositeElisionTransformer.ENABLE_PROPERTY);
        System.clearProperty(CanvasCompositeElisionTransformer.ENABLE_PROPERTY);
        try {
            final CanvasCompositeElisionHookContributor contributor = new CanvasCompositeElisionHookContributor();
            // The flag gate runs before any host access: install succeeds as a no-op
            // even though the environment carries no located host.
            assertNotNull(contributor.install(environment(true)));
        } finally {
            if (prior == null) {
                System.clearProperty(CanvasCompositeElisionTransformer.ENABLE_PROPERTY);
            } else {
                System.setProperty(CanvasCompositeElisionTransformer.ENABLE_PROPERTY, prior);
            }
        }
    }

    @Test
    void flagOnWithoutHostFailsSafe() {
        final String prior = System.getProperty(CanvasCompositeElisionTransformer.ENABLE_PROPERTY);
        System.setProperty(CanvasCompositeElisionTransformer.ENABLE_PROPERTY, "true");
        try {
            final CanvasCompositeElisionHookContributor contributor = new CanvasCompositeElisionHookContributor();
            // The missing host surfaces as installation=FAILED inside install(); a
            // failed experiment must never stop official startup.
            assertNotNull(contributor.install(environment(true)));
        } finally {
            if (prior == null) {
                System.clearProperty(CanvasCompositeElisionTransformer.ENABLE_PROPERTY);
            } else {
                System.setProperty(CanvasCompositeElisionTransformer.ENABLE_PROPERTY, prior);
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

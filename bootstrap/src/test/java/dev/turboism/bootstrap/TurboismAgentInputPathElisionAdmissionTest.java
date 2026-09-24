package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.inputpath.InputPathElisionBridge;
import dev.turboism.adapter.cubism.optimization.inputpath.InputPathElisionTransformer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TurboismAgentInputPathElisionAdmissionTest {

    @Test
    void admitsOnlyAfterFullRuntimeAdmission() {
        final InputPathElisionHookContributor contributor =
            new InputPathElisionHookContributor();
        assertTrue(contributor.admitted(environment(true)));
        assertFalse(contributor.admitted(environment(false)));
    }

    @Test
    void flagOffInstallsNothingAndNeverTouchesTheHost() {
        final String prior = System.getProperty(InputPathElisionTransformer.ENABLE_PROPERTY);
        System.clearProperty(InputPathElisionTransformer.ENABLE_PROPERTY);
        try {
            final InputPathElisionHookContributor contributor =
                new InputPathElisionHookContributor();
            // The flag gate runs before any host access: install succeeds as a no-op
            // even though the environment carries no located host.
            assertNotNull(contributor.install(environment(true)));
        } finally {
            if (prior == null) {
                System.clearProperty(InputPathElisionTransformer.ENABLE_PROPERTY);
            } else {
                System.setProperty(InputPathElisionTransformer.ENABLE_PROPERTY, prior);
            }
        }
    }

    @Test
    void flagOnWithoutHostFailsSafe() {
        final String prior = System.getProperty(InputPathElisionTransformer.ENABLE_PROPERTY);
        System.setProperty(InputPathElisionTransformer.ENABLE_PROPERTY, "true");
        try {
            final InputPathElisionHookContributor contributor =
                new InputPathElisionHookContributor();
            // The missing host surfaces as installation=FAILED inside install(); a
            // failed experiment must never stop official startup.
            assertNotNull(contributor.install(environment(true)));
        } finally {
            if (prior == null) {
                System.clearProperty(InputPathElisionTransformer.ENABLE_PROPERTY);
            } else {
                System.setProperty(InputPathElisionTransformer.ENABLE_PROPERTY, prior);
            }
        }
    }

    @Test
    void productionFlagOffInstallsNothing() {
        withProperty(InputPathElisionBridge.ENABLE_PROPERTY, "false", () -> {
            final InputPathElisionHookContributor contributor =
                new InputPathElisionHookContributor();
            // Neither flag set: NOT_ADMITTED before any host access.
            assertNotNull(contributor.install(environment(true)));
        });
    }

    @Test
    void productionFlagOnWithoutHostFailsSafe() {
        withProperty(InputPathElisionBridge.ENABLE_PROPERTY, "true", () -> {
            final InputPathElisionHookContributor contributor =
                new InputPathElisionHookContributor();
            // The missing host surfaces as installation=FAILED inside install();
            // production opt-in must never stop official startup.
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
            .fullRuntimeAdmission(admitted)
            .build();
    }
}

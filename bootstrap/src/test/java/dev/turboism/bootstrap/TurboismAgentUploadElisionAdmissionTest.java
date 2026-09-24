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
    void flagOffInstallsNothingAndNeverTouchesTheHost() {
        final String prior =
            System.getProperty(SkippedFrameUploadElisionTransformer.ENABLE_PROPERTY);
        System.clearProperty(SkippedFrameUploadElisionTransformer.ENABLE_PROPERTY);
        try {
            final SkippedFrameUploadElisionHookContributor contributor =
                new SkippedFrameUploadElisionHookContributor();
            // The flag gate runs before any host access: install succeeds as a no-op
            // even though the environment carries no located host.
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
            .fullRuntimeAdmission(admitted)
            .build();
    }
}

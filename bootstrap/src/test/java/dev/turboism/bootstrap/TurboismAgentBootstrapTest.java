package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.startup.StartupSuppressionInstaller;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TurboismAgentBootstrapTest {

    @Test
    void rejectedOptionsDoNotPoisonTheNextStartAttempt() {
        final AtomicInteger hookRegistrations = new AtomicInteger();

        TurboismAgent.requestStartForTesting(
            StartupSuppressionInstaller.AttachmentMode.PREMAIN,
            "unsafe=true",
            null,
            hook -> hookRegistrations.incrementAndGet()
        );
        TurboismAgent.requestStartForTesting(
            StartupSuppressionInstaller.AttachmentMode.PREMAIN,
            null,
            null,
            hook -> {
                hookRegistrations.incrementAndGet();
                throw new SecurityException("test rejection");
            }
        );

        assertEquals(1, hookRegistrations.get());
    }

    @Test
    void rejectedHookRegistrationCanBeRetriedWithOneHookAttemptPerStart() {
        final AtomicInteger hookRegistrations = new AtomicInteger();
        final TurboismAgent.ShutdownHookRegistrar rejected = hook -> {
            hookRegistrations.incrementAndGet();
            throw new SecurityException("test rejection");
        };

        TurboismAgent.requestStartForTesting(
            StartupSuppressionInstaller.AttachmentMode.PREMAIN,
            null,
            null,
            rejected
        );
        TurboismAgent.requestStartForTesting(
            StartupSuppressionInstaller.AttachmentMode.PREMAIN,
            null,
            null,
            rejected
        );

        assertEquals(2, hookRegistrations.get());
    }
}

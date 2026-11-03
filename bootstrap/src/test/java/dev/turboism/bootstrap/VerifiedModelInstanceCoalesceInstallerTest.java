package dev.turboism.bootstrap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.adapter.cubism.optimization.modelinstances.ModelInstanceCoalesceBridge;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class VerifiedModelInstanceCoalesceInstallerTest {

    private static final Path MISSING_ARTIFACT = Path.of("missing-cubism-host.jar");

    @Test
    void rejectsUnrequestedOldJvmAndUnboundArtifact() {
        assertFalse(VerifiedModelInstanceCoalesceInstaller.admitted(MISSING_ARTIFACT, false, 17));
        assertFalse(VerifiedModelInstanceCoalesceInstaller.admitted(MISSING_ARTIFACT, true, 16));
        assertFalse(VerifiedModelInstanceCoalesceInstaller.admitted(MISSING_ARTIFACT, true, 17));
        assertFalse(VerifiedModelInstanceCoalesceInstaller.admitted(MISSING_ARTIFACT, true, 25));
    }

    @Test
    void installsBeforeRuntimeAndCleansUpOnFailure() throws Exception {
        NativeOptimizationStartupOrder.assertBeforeRuntime();
    }

    @Test
    void preferenceDefaultsOnAndCanBeDisabled() {
        final String prior = System.getProperty(ModelInstanceCoalesceBridge.ENABLE_PROPERTY);
        try {
            System.getProperties().remove(ModelInstanceCoalesceBridge.ENABLE_PROPERTY);
            assertTrue(ModelInstanceCoalesceBridge.enabledByPreference());
            System.getProperties().put(ModelInstanceCoalesceBridge.ENABLE_PROPERTY, "false");
            assertFalse(ModelInstanceCoalesceBridge.enabledByPreference());
        } finally {
            if (prior == null) {
                System.getProperties().remove(ModelInstanceCoalesceBridge.ENABLE_PROPERTY);
            } else {
                System.getProperties().put(ModelInstanceCoalesceBridge.ENABLE_PROPERTY, prior);
            }
        }
    }
}

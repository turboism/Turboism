package dev.turboism.bootstrap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.adapter.cubism.optimization.inspector.InspectorPropertyBypassBridge;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class VerifiedInspectorPropertyBypassInstallerTest {

    private static final Path MISSING_ARTIFACT = Path.of("missing-cubism-host.jar");

    @Test
    void rejectsUnrequestedOldJvmAndUnsupportedArtifact() {
        assertFalse(VerifiedInspectorPropertyBypassInstaller.admitted(MISSING_ARTIFACT, false, 17));
        assertFalse(VerifiedInspectorPropertyBypassInstaller.admitted(MISSING_ARTIFACT, true, 16));
        assertFalse(VerifiedInspectorPropertyBypassInstaller.admitted(MISSING_ARTIFACT, true, 17));
        assertFalse(VerifiedInspectorPropertyBypassInstaller.admitted(MISSING_ARTIFACT, true, 25));
    }

    @Test
    void installsBeforeRuntimeStartup() throws Exception {
        NativeOptimizationStartupOrder.assertBeforeRuntime();
    }

    @Test
    void preferenceDefaultsOffAndValidationCanForceIt() {
        final String prior = System.getProperty(InspectorPropertyBypassBridge.ENABLE_PROPERTY);
        final String validation = System.getProperty(InspectorPropertyBypassBridge.VALIDATION_PROPERTY);
        try {
            System.getProperties().remove(InspectorPropertyBypassBridge.ENABLE_PROPERTY);
            System.getProperties().remove(InspectorPropertyBypassBridge.VALIDATION_PROPERTY);
            InspectorPropertyBypassBridge.setConfiguredDefault(false);
            assertFalse(InspectorPropertyBypassBridge.enabledByPreference());

            System.getProperties().put(InspectorPropertyBypassBridge.VALIDATION_PROPERTY, "true");
            assertTrue(InspectorPropertyBypassBridge.enabledByPreference());

            System.getProperties().put(InspectorPropertyBypassBridge.ENABLE_PROPERTY, "false");
            assertFalse(InspectorPropertyBypassBridge.enabledByPreference());

            System.getProperties().put(InspectorPropertyBypassBridge.ENABLE_PROPERTY, "true");
            assertTrue(InspectorPropertyBypassBridge.enabledByPreference());
        } finally {
            restore(InspectorPropertyBypassBridge.ENABLE_PROPERTY, prior);
            restore(InspectorPropertyBypassBridge.VALIDATION_PROPERTY, validation);
            InspectorPropertyBypassBridge.setConfiguredDefault(false);
        }
    }

    private static void restore(final String key, final String value) {
        if (value == null) System.getProperties().remove(key);
        else System.getProperties().put(key, value);
    }
}

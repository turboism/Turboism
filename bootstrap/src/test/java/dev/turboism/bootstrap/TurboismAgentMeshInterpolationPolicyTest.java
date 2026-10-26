package dev.turboism.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.config.RuntimeStartupConfig;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class TurboismAgentMeshInterpolationPolicyTest {
    static RuntimeStartupConfig policy(boolean safe, Set<String> disabled) {
        return new RuntimeStartupConfig(safe, false, false, false, false, false, false, disabled);
    }

    @Test
    void candidateRequiresExplicitRequest() {
        assertFalse(TurboismAgent.meshInterpolationEnabled(policy(false, Set.of()), false));
        assertTrue(TurboismAgent.meshInterpolationEnabled(policy(false, Set.of()), true));
    }

    @Test
    void verifiedProductInterpolationDefaultsOffWithExplicitOptIn() {
        assertFalse(TurboismAgent.meshInterpolationRequested(null));
        assertTrue(TurboismAgent.meshInterpolationRequested("true"));
        assertFalse(TurboismAgent.meshInterpolationRequested("false"));
        assertFalse(TurboismAgent.meshInterpolationRequested("invalid"));
    }

    @Test
    void modelSerializationHasIndependentPolicyAndExplicitDisable() {
        assertTrue(TurboismAgent.modelSerializationEnabled(policy(false, Set.of()), true));
        assertFalse(TurboismAgent.modelSerializationEnabled(policy(true, Set.of()), true));
        assertFalse(TurboismAgent.modelSerializationEnabled(policy(false, Set.of("cubism.model.serialization")), true));
        assertFalse(TurboismAgent.modelSerializationEnabled(policy(false, Set.of()), false));
        assertFalse(TurboismAgent.modelSerializationEnabled(null, true));
    }

    @Test
    void safeModeDisabledHookAndMissingPolicyDecline() {
        assertFalse(TurboismAgent.meshInterpolationEnabled(policy(true, Set.of()), true));
        assertFalse(TurboismAgent.meshInterpolationEnabled(policy(false, Set.of("cubism.mesh.interpolation")), true));
        assertFalse(TurboismAgent.meshInterpolationEnabled(null, true));
    }
}

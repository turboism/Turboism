package dev.turboism.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.config.RuntimeStartupConfig;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class TurboismAgentShaderUniformPolicyTest {
    static RuntimeStartupConfig policy(boolean safe, Set<String> disabled) {
        return new RuntimeStartupConfig(safe, false, false, false, false, false, false, disabled);
    }

    @Test
    void candidateRequiresExplicitRequest() {
        assertFalse(TurboismAgent.shaderUniformEnabled(policy(false, Set.of()), false));
        assertTrue(TurboismAgent.shaderUniformEnabled(policy(false, Set.of()), true));
    }

    @Test
    void verifiedProductShaderUniformDefaultsOffWithExplicitOptIn() {
        assertFalse(TurboismAgent.shaderUniformRequested(null));
        assertTrue(TurboismAgent.shaderUniformRequested("true"));
        assertFalse(TurboismAgent.shaderUniformRequested("false"));
        assertFalse(TurboismAgent.shaderUniformRequested("invalid"));
    }

    @Test
    void safeModeDisabledHookAndMissingPolicyDecline() {
        assertFalse(TurboismAgent.shaderUniformEnabled(policy(true, Set.of()), true));
        assertFalse(TurboismAgent.shaderUniformEnabled(policy(false, Set.of("cubism.shader.uniform")), true));
        assertFalse(TurboismAgent.shaderUniformEnabled(null, true));
    }
}

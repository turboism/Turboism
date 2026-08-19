package dev.turboism.bootstrap;

import dev.turboism.config.RuntimeStartupConfig;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TurboismAgentMeshMirrorPolicyTest {
    private static final String HOOK_ID = "cubism.mesh.mirror-axis";

    @Test
    void disabledExactPolicyPreventsEarlyInstallation() {
        final RuntimeStartupConfig policy = new RuntimeStartupConfig(
            false, false, false, false, false, false, false, Set.of(HOOK_ID)
        );

        assertFalse(TurboismAgent.meshMirrorHookEnabled(policy));
    }

    @Test
    void nullAndEmptyEnabledPoliciesRemainFailClosedOrEnabledAsConfigured() {
        assertFalse(TurboismAgent.meshMirrorHookEnabled(null));
        assertTrue(TurboismAgent.meshMirrorHookEnabled(new RuntimeStartupConfig(
            false, false, false, false, false, false, false, Set.of()
        )));
    }

    @Test
    void enabledExactPolicyPermitsEarlyInstallation() {
        final RuntimeStartupConfig policy = new RuntimeStartupConfig(
            false, false, false, false, false, false, false, Set.of()
        );

        assertTrue(TurboismAgent.meshMirrorHookEnabled(policy));
    }
}

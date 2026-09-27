package dev.turboism.bootstrap;

import dev.turboism.config.RuntimeStartupConfig;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
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
        assertFalse(MeshMirrorHookContributor.hookEnabled(policy));
    }

    @Test
    void nullAndEmptyEnabledPoliciesRemainFailClosedOrEnabledAsConfigured() {
        assertFalse(MeshMirrorHookContributor.hookEnabled(null));
        assertTrue(MeshMirrorHookContributor.hookEnabled(new RuntimeStartupConfig(
            false, false, false, false, false, false, false, Set.of()
        )));
    }

    @Test
    void enabledExactPolicyPermitsEarlyInstallation() {
        final RuntimeStartupConfig policy = new RuntimeStartupConfig(
            false, false, false, false, false, false, false, Set.of()
        );
        assertTrue(MeshMirrorHookContributor.hookEnabled(policy));
    }

    @Test
    void admitsExact5303AfterFullRuntimeAdmission() {
        assertTrue(dev.turboism.adapter.cubism.mesh.MeshMirrorHostProfile.forArtifact(
            ReviewedHostArtifacts.CUBISM_5_3_03
        ).isPresent());
        assertTrue(MeshMirrorHookContributor.runtimeAdmitted(
            ReviewedHostArtifacts.CUBISM_5_3_03
        ));
        assertTrue(MeshMirrorHookContributor.runtimeAdmitted(
            ReviewedHostArtifacts.CUBISM_5_3_02
        ));
        assertFalse(MeshMirrorHookContributor.runtimeAdmitted(
            new dev.turboism.mapping.verification.HostArtifactDigest(1L, "0".repeat(64))
        ));
        assertTrue(ReviewedHostArtifacts.admitsFullRuntime("5.3.03"));
    }
}

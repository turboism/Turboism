package dev.turboism.bootstrap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.config.RuntimeStartupConfig;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class TurboismAgentMeshMirrorPolicyTest {
    private static final String HOOK_ID = "cubism.mesh.mirror-axis";

    @Test
    void disabledExactPolicyPreventsEarlyInstallation() {
        final RuntimeStartupConfig policy =
                new RuntimeStartupConfig(false, false, false, false, false, false, false, Set.of(HOOK_ID));
        assertFalse(MeshMirrorHookContributor.hookEnabled(policy));
    }

    @Test
    void nullAndEmptyEnabledPoliciesRemainFailClosedOrEnabledAsConfigured() {
        assertFalse(MeshMirrorHookContributor.hookEnabled(null));
        assertTrue(MeshMirrorHookContributor.hookEnabled(
                new RuntimeStartupConfig(false, false, false, false, false, false, false, Set.of())));
    }

    @Test
    void enabledExactPolicyPermitsEarlyInstallation() {
        final RuntimeStartupConfig policy =
                new RuntimeStartupConfig(false, false, false, false, false, false, false, Set.of());
        assertTrue(MeshMirrorHookContributor.hookEnabled(policy));
    }

    @Test
    void admitsReviewedProfilesByDeclaredVersion() {
        assertTrue(
                dev.turboism.adapter.cubism.mesh.MeshMirrorHostProfile.forArtifact(ReviewedHostArtifacts.CUBISM_5_3_03)
                        .isPresent());
        assertTrue(dev.turboism.adapter.cubism.mesh.MeshMirrorHostProfile.forReviewedVersion("5.3.03")
                .isPresent());
        assertTrue(dev.turboism.adapter.cubism.mesh.MeshMirrorHostProfile.forReviewedVersion("5.3.02")
                .isPresent());
        assertTrue(dev.turboism.adapter.cubism.mesh.MeshMirrorHostProfile.forReviewedVersion("9.9.99")
                .isEmpty());
        assertTrue(ReviewedHostArtifacts.admitsFullRuntime("5.3.03"));
    }
}

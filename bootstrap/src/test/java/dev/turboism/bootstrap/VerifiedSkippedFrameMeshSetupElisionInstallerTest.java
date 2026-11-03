package dev.turboism.bootstrap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.adapter.cubism.optimization.meshsetup.SkippedFrameMeshSetupElisionTarget;
import dev.turboism.config.RuntimeStartupConfig;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.util.Set;
import org.junit.jupiter.api.Test;

class VerifiedSkippedFrameMeshSetupElisionInstallerTest {

    private static final HostArtifactDigest MISSING_ARTIFACT =
            new HostArtifactDigest(1L, "0000000000000000000000000000000000000000000000000000000000000000");

    @Test
    void rejectsUnrequestedOldJvmSafeModeDisabledHookAndUnreviewedArtifact() {
        final var normal = new RuntimeStartupConfig(false, false, false, false);
        assertFalse(VerifiedSkippedFrameMeshSetupElisionInstaller.admitted(
                ReviewedHostArtifacts.CUBISM_5_3_02, normal, false, 17));
        assertFalse(VerifiedSkippedFrameMeshSetupElisionInstaller.admitted(
                ReviewedHostArtifacts.CUBISM_5_3_02, normal, true, 16));
        assertFalse(VerifiedSkippedFrameMeshSetupElisionInstaller.admitted(
                ReviewedHostArtifacts.CUBISM_5_3_02, new RuntimeStartupConfig(true, false, false, false), true, 17));
        assertFalse(VerifiedSkippedFrameMeshSetupElisionInstaller.admitted(
                ReviewedHostArtifacts.CUBISM_5_3_02,
                new RuntimeStartupConfig(
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        Set.of(VerifiedSkippedFrameMeshSetupElisionInstaller.HOOK_ID)),
                true,
                17));
        assertFalse(VerifiedSkippedFrameMeshSetupElisionInstaller.admitted(MISSING_ARTIFACT, normal, true, 17));
        assertFalse(VerifiedSkippedFrameMeshSetupElisionInstaller.admitted(MISSING_ARTIFACT, normal, true, 25));
    }

    @Test
    void admitsEveryReviewedArtifact() {
        final var normal = new RuntimeStartupConfig(false, false, false, false);
        for (final HostArtifactDigest digest : ReviewedHostArtifacts.all()) {
            assertTrue(SkippedFrameMeshSetupElisionTarget.of(digest).isPresent(), digest.toString());
            assertTrue(
                    VerifiedSkippedFrameMeshSetupElisionInstaller.admitted(digest, normal, true, 17),
                    digest.toString());
        }
    }

    @Test
    void installsBeforeRuntimeStartup() throws Exception {
        NativeOptimizationStartupOrder.assertBeforeRuntime();
    }
}

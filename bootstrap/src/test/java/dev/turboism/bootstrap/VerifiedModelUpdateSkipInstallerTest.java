package dev.turboism.bootstrap;

import dev.turboism.config.RuntimeStartupConfig;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VerifiedModelUpdateSkipInstallerTest {
    @Test void admitsOnlyRequestedExactArtifactJvmAndPolicy() {
        var normal = new RuntimeStartupConfig(false, false, false, false);
        for (HostArtifactDigest digest : ReviewedHostArtifacts.all()) {
            assertTrue(VerifiedModelUpdateSkipInstaller.admitted(digest, normal, true, 17),
                digest.sha256());
        }
        assertFalse(VerifiedModelUpdateSkipInstaller.admitted(
            ReviewedHostArtifacts.CUBISM_5_3_03, normal, false, 17));
        assertTrue(VerifiedModelUpdateSkipInstaller.admitted(
            ReviewedHostArtifacts.CUBISM_5_3_03, normal, true, 25));
        assertFalse(VerifiedModelUpdateSkipInstaller.admitted(
            ReviewedHostArtifacts.CUBISM_5_3_03, normal, true, 16));
        assertFalse(VerifiedModelUpdateSkipInstaller.admitted(
            new HostArtifactDigest(1L, "9".repeat(64)), normal, true, 17));
        assertFalse(VerifiedModelUpdateSkipInstaller.admitted(
            ReviewedHostArtifacts.CUBISM_5_3_03,
            new RuntimeStartupConfig(true, false, false, false), true, 17));
        assertFalse(VerifiedModelUpdateSkipInstaller.admitted(
            ReviewedHostArtifacts.CUBISM_5_3_03,
            new RuntimeStartupConfig(false, false, false, false, false, false, false,
                Set.of(VerifiedModelUpdateSkipInstaller.HOOK_ID)), true, 17));
    }

    @Test void installsBeforeRuntimeAndCleansUpOnFailure() throws Exception {
        NativeOptimizationStartupOrder.assertBeforeRuntime(
            "installModelUpdateSkip", "closeModelUpdateSkip");
    }
}

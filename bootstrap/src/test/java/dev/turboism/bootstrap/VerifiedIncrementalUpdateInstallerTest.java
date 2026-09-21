package dev.turboism.bootstrap;

import dev.turboism.config.RuntimeStartupConfig;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VerifiedIncrementalUpdateInstallerTest {
    @Test void admitsOnlyRequestedExactArtifactJvmAndPolicy() {
        var normal = new RuntimeStartupConfig(false, false, false, false);
        for (HostArtifactDigest digest : ReviewedHostArtifacts.all()) {
            assertTrue(VerifiedIncrementalUpdateInstaller.admitted(digest, normal, true, 17),
                digest.sha256());
        }
        assertFalse(VerifiedIncrementalUpdateInstaller.admitted(
            ReviewedHostArtifacts.CUBISM_5_3_03, normal, false, 17));
        assertTrue(VerifiedIncrementalUpdateInstaller.admitted(
            ReviewedHostArtifacts.CUBISM_5_3_03, normal, true, 25));
        assertFalse(VerifiedIncrementalUpdateInstaller.admitted(
            ReviewedHostArtifacts.CUBISM_5_3_03, normal, true, 16));
        assertFalse(VerifiedIncrementalUpdateInstaller.admitted(
            new HostArtifactDigest(1L, "9".repeat(64)), normal, true, 17));
        assertFalse(VerifiedIncrementalUpdateInstaller.admitted(
            ReviewedHostArtifacts.CUBISM_5_3_03,
            new RuntimeStartupConfig(true, false, false, false), true, 17));
        assertFalse(VerifiedIncrementalUpdateInstaller.admitted(
            ReviewedHostArtifacts.CUBISM_5_3_03,
            new RuntimeStartupConfig(false, false, false, false, false, false, false,
                Set.of(VerifiedIncrementalUpdateInstaller.HOOK_ID)), true, 17));
    }

    @Test void installsBeforeRuntimeAndCleansUpOnFailure() throws Exception {
        NativeOptimizationStartupOrder.assertBeforeRuntime();
    }
}

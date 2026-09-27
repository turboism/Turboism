package dev.turboism.bootstrap;

import dev.turboism.config.RuntimeStartupConfig;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VerifiedWarpPositionProjectionInstallerTest {
    @Test void admitsOnlyRequestedExactArtifactJvmAndPolicy() {
        var normal = new RuntimeStartupConfig(false,false,false,false);
        assertTrue(VerifiedWarpPositionProjectionInstaller.admitted(ReviewedHostArtifacts.CUBISM_5_3_02,normal,true,17));
        assertFalse(VerifiedWarpPositionProjectionInstaller.admitted(ReviewedHostArtifacts.CUBISM_5_3_02,normal,false,17));
        assertFalse(VerifiedWarpPositionProjectionInstaller.admitted(ReviewedHostArtifacts.CUBISM_5_3_02,normal,true,25));
        assertFalse(VerifiedWarpPositionProjectionInstaller.admitted(ReviewedHostArtifacts.CUBISM_5_3_03,normal,true,17));
        assertFalse(VerifiedWarpPositionProjectionInstaller.admitted(ReviewedHostArtifacts.CUBISM_5_2_03,normal,true,17));
        assertFalse(VerifiedWarpPositionProjectionInstaller.admitted(ReviewedHostArtifacts.CUBISM_5_3_02,
            new RuntimeStartupConfig(true,false,false,false),true,17));
        assertFalse(VerifiedWarpPositionProjectionInstaller.admitted(ReviewedHostArtifacts.CUBISM_5_3_02,
            new RuntimeStartupConfig(false,false,false,false,false,false,false,Set.of(VerifiedWarpPositionProjectionInstaller.HOOK_ID)),true,17));
    }

    @Test void installsBeforeRuntimeAndCleansUpOnFailure() throws Exception {
        NativeOptimizationStartupOrder.assertBeforeRuntime();
    }
}

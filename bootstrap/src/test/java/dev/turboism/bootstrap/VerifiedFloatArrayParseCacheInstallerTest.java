package dev.turboism.bootstrap;

import dev.turboism.config.RuntimeStartupConfig;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VerifiedFloatArrayParseCacheInstallerTest {
    @Test void rejectsDefaultOffUnknownVersionSafeModeAndDisabledHook() {
        var config = new RuntimeStartupConfig(false, false, false, false);
        assertFalse(VerifiedFloatArrayParseCacheInstaller.admitted(ReviewedHostArtifacts.CUBISM_5_3_02, config, false));
        assertTrue(VerifiedFloatArrayParseCacheInstaller.admitted(ReviewedHostArtifacts.CUBISM_5_3_02, config, true));
        assertFalse(VerifiedFloatArrayParseCacheInstaller.admitted(ReviewedHostArtifacts.CUBISM_5_3_03, config, true));
        assertFalse(VerifiedFloatArrayParseCacheInstaller.admitted(ReviewedHostArtifacts.CUBISM_5_2_03, config, true));
        assertFalse(VerifiedFloatArrayParseCacheInstaller.admitted(ReviewedHostArtifacts.CUBISM_5_3_02,
            new RuntimeStartupConfig(true, false, false, false), true));
        assertFalse(VerifiedFloatArrayParseCacheInstaller.admitted(ReviewedHostArtifacts.CUBISM_5_3_02,
            new RuntimeStartupConfig(false, false, false, false, false, false, false,
                Set.of(VerifiedFloatArrayParseCacheInstaller.HOOK_ID)), true));
    }

    @Test void nativeParserInstallsOnceBeforeRuntimeStartupAndHasFailureCleanup() throws Exception {
        NativeOptimizationStartupOrder.assertBeforeRuntime();
    }
}

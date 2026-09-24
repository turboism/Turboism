package dev.turboism.bootstrap;

import dev.turboism.config.RuntimeStartupConfig;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class VerifiedTextureUploadPreparationInstallerTest {
    @TempDir Path home;

    @Test void admitsOnlyRequestedExactHostOnVerifiedJvmOutsideSafeMode() {
        var normal=new RuntimeStartupConfig(false,false,false,false);
        assertTrue(VerifiedTextureUploadPreparationInstaller.admitted(ReviewedHostArtifacts.CUBISM_5_3_02,normal,true,17));
        assertFalse(VerifiedTextureUploadPreparationInstaller.admitted(ReviewedHostArtifacts.CUBISM_5_3_02,normal,false,17));
        assertFalse(VerifiedTextureUploadPreparationInstaller.admitted(ReviewedHostArtifacts.CUBISM_5_3_02,normal,true,25));
        assertFalse(VerifiedTextureUploadPreparationInstaller.admitted(ReviewedHostArtifacts.CUBISM_5_3_03,normal,true,17));
        assertFalse(VerifiedTextureUploadPreparationInstaller.admitted(ReviewedHostArtifacts.CUBISM_5_2_03,normal,true,17));
        assertFalse(VerifiedTextureUploadPreparationInstaller.admitted(ReviewedHostArtifacts.CUBISM_5_3_02,
            new RuntimeStartupConfig(true,false,false,false),true,17));
        assertFalse(VerifiedTextureUploadPreparationInstaller.admitted(ReviewedHostArtifacts.CUBISM_5_3_02,
            new RuntimeStartupConfig(false,false,false,false,false,false,false,Set.of(VerifiedTextureUploadPreparationInstaller.HOOK_ID)),true,17));
    }

    @Test void invalidConfigurationFailsClosedForEveryNativeOptimization() throws Exception {
        assertTrue(NativeOptimizationPolicy.load(home).hookEnabled(VerifiedTextureUploadPreparationInstaller.HOOK_ID));
        Files.writeString(home.resolve("config.json"),"{invalid");
        var rejected=NativeOptimizationPolicy.load(home);
        assertFalse(rejected.hookEnabled(VerifiedTextureUploadPreparationInstaller.HOOK_ID));
        assertFalse(rejected.hookEnabled(VerifiedImageArchiveReuseInstaller.HOOK_ID));
        assertFalse(rejected.hookEnabled(VerifiedFloatArrayParseCacheInstaller.HOOK_ID));
    }

    @Test void startupOrderingAndFailureCleanupArePresent() throws Exception {
        NativeOptimizationStartupOrder.assertBeforeRuntime();
    }
}

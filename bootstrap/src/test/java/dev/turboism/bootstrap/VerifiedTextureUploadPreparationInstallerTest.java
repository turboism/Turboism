package dev.turboism.bootstrap;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.turboism.config.RuntimeStartupConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VerifiedTextureUploadPreparationInstallerTest {
    @TempDir
    Path home;

    private static final Path MISSING_ARTIFACT = Path.of("missing-cubism-host.jar");

    @Test
    void rejectsUnrequestedWrongJvmSafeModeDisabledHookAndUnboundArtifact() {
        var normal = new RuntimeStartupConfig(false, false, false, false);
        assertFalse(VerifiedTextureUploadPreparationInstaller.admitted(MISSING_ARTIFACT, normal, false, 17));
        assertFalse(VerifiedTextureUploadPreparationInstaller.admitted(MISSING_ARTIFACT, normal, true, 25));
        assertFalse(VerifiedTextureUploadPreparationInstaller.admitted(MISSING_ARTIFACT, normal, true, 16));
        assertFalse(VerifiedTextureUploadPreparationInstaller.admitted(
                MISSING_ARTIFACT, new RuntimeStartupConfig(true, false, false, false), true, 17));
        assertFalse(VerifiedTextureUploadPreparationInstaller.admitted(
                MISSING_ARTIFACT,
                new RuntimeStartupConfig(
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        Set.of(VerifiedTextureUploadPreparationInstaller.HOOK_ID)),
                true,
                17));
        assertFalse(
                VerifiedTextureUploadPreparationInstaller.admitted(MISSING_ARTIFACT, normal, true, 17),
                "an artifact without a reviewed texture-upload contract must not admit");
    }

    @Test
    void admitsRequestedArtifactWhoseShaderClassMatchesAReviewedContract() {
        final Path artifact = System.getProperty("turboism.test.cubismEditorJar") == null
                ? null
                : Path.of(System.getProperty("turboism.test.cubismEditorJar"));
        assumeTrue(
                artifact != null && Files.isRegularFile(artifact),
                "no turboism.test.cubismEditorJar evidence supplied");
        assertTrue(VerifiedTextureUploadPreparationInstaller.admitted(
                artifact, new RuntimeStartupConfig(false, false, false, false), true, 17));
    }

    @Test
    void invalidConfigurationFailsClosedForEveryNativeOptimization() throws Exception {
        assertTrue(NativeOptimizationPolicy.load(home).hookEnabled(VerifiedTextureUploadPreparationInstaller.HOOK_ID));
        Files.writeString(home.resolve("config.json"), "{invalid");
        var rejected = NativeOptimizationPolicy.load(home);
        assertFalse(rejected.hookEnabled(VerifiedTextureUploadPreparationInstaller.HOOK_ID));
        assertFalse(rejected.hookEnabled(VerifiedImageArchiveReuseInstaller.HOOK_ID));
        assertFalse(rejected.hookEnabled(VerifiedFloatArrayParseCacheInstaller.HOOK_ID));
    }

    @Test
    void startupOrderingAndFailureCleanupArePresent() throws Exception {
        NativeOptimizationStartupOrder.assertBeforeRuntime();
    }
}

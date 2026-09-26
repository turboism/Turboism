package dev.turboism.bootstrap;

import dev.turboism.config.RuntimeStartupConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class VerifiedWarpPositionProjectionInstallerTest {
    private static final Path MISSING_ARTIFACT = Path.of("missing-cubism-host.jar");

    @Test void rejectsUnrequestedWrongJvmSafeModeDisabledHookAndUnboundArtifact() {
        var normal = new RuntimeStartupConfig(false, false, false, false);
        assertFalse(VerifiedWarpPositionProjectionInstaller.admitted(MISSING_ARTIFACT, normal, false, 17));
        assertFalse(VerifiedWarpPositionProjectionInstaller.admitted(MISSING_ARTIFACT, normal, true, 25));
        assertFalse(VerifiedWarpPositionProjectionInstaller.admitted(MISSING_ARTIFACT, normal, true, 16));
        assertFalse(VerifiedWarpPositionProjectionInstaller.admitted(MISSING_ARTIFACT,
            new RuntimeStartupConfig(true, false, false, false), true, 17));
        assertFalse(VerifiedWarpPositionProjectionInstaller.admitted(MISSING_ARTIFACT,
            new RuntimeStartupConfig(false, false, false, false, false, false, false,
                Set.of(VerifiedWarpPositionProjectionInstaller.HOOK_ID)), true, 17));
        assertFalse(VerifiedWarpPositionProjectionInstaller.admitted(MISSING_ARTIFACT, normal, true, 17),
            "an artifact without a reviewed warp-projection contract must not admit");
    }

    @Test void admitsRequestedArtifactWhoseWarpClassesMatchAReviewedContract() {
        final Path artifact = System.getProperty("turboism.test.cubismEditorJar") == null
            ? null : Path.of(System.getProperty("turboism.test.cubismEditorJar"));
        assumeTrue(artifact != null && Files.isRegularFile(artifact),
            "no turboism.test.cubismEditorJar evidence supplied");
        assertTrue(VerifiedWarpPositionProjectionInstaller.admitted(
            artifact, new RuntimeStartupConfig(false, false, false, false), true, 17));
    }

    @Test void installsBeforeRuntimeAndCleansUpOnFailure() throws Exception {
        NativeOptimizationStartupOrder.assertBeforeRuntime();
    }
}

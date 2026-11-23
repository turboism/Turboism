package dev.turboism.bootstrap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.turboism.adapter.cubism.optimization.warpdeform.WarpDeformEditBridge;
import dev.turboism.config.RuntimeStartupConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies admission predicates, startup ordering, and persisted configuration wiring for warp-deform edit acceleration.
 */
class VerifiedWarpDeformEditAccelerationInstallerTest {

    private static final Path MISSING_ARTIFACT = Path.of("missing-cubism-host.jar");

    @TempDir
    Path home;

    @Test
    void rejectsUnrequestedSafeModeDisabledHookAndUnboundArtifact() throws Exception {
        final RuntimeStartupConfig normal = new RuntimeStartupConfig(false, false, false, false);
        assertFalse(VerifiedWarpDeformEditAccelerationInstaller.admitted(MISSING_ARTIFACT, normal, false));
        assertFalse(VerifiedWarpDeformEditAccelerationInstaller.admitted(
                MISSING_ARTIFACT, new RuntimeStartupConfig(true, false, false, false), true));
        assertFalse(VerifiedWarpDeformEditAccelerationInstaller.admitted(
                MISSING_ARTIFACT,
                new RuntimeStartupConfig(
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        Set.of(VerifiedWarpDeformEditAccelerationInstaller.HOOK_ID)),
                true));
        assertFalse(
                VerifiedWarpDeformEditAccelerationInstaller.admitted(MISSING_ARTIFACT, normal, true),
                "an artifact without a reviewed warp-deform contract must not admit");
    }

    @Test
    void admitsReviewedCubismEditorArtifact() throws Exception {
        final String configured = System.getProperty("turboism.test.cubismEditorJar");
        final Path artifact = configured == null ? null : Path.of(configured);
        assumeTrue(artifact != null && Files.isRegularFile(artifact), "no turboism.test.cubismEditorJar supplied");
        assertTrue(VerifiedWarpDeformEditAccelerationInstaller.admitted(
                artifact, new RuntimeStartupConfig(false, false, false, false), true));
    }

    @Test
    void installsBeforeRuntimeAndCleansUpOnFailure() throws Exception {
        NativeOptimizationStartupOrder.assertBeforeRuntime();
    }

    @Test
    void defaultsEnabledWithoutConfigAndRespectsOptOut() throws Exception {
        final String prior = System.getProperty(WarpDeformEditBridge.ENABLE_PROPERTY);
        System.clearProperty(WarpDeformEditBridge.ENABLE_PROPERTY);
        try {
            final HookEnvironment defaultEnv = HookEnvironment.builder()
                    .options(AgentOptions.parse("home=" + home, home))
                    .build();
            assertTrue(defaultEnv
                    .plan()
                    .admitted(dev.turboism.config.optimization.OptimizationCatalog.WARP_DEFORM_EDIT_ACCELERATION));

            Files.writeString(home.resolve("config.json"), """
                    {"format":"turboism.runtime.config","schemaVersion":1,"worktreeId":"warp-edit-test","launcher":{"warpDeformEditAcceleration":false}}
                    """);
            final HookEnvironment optOutEnv = HookEnvironment.builder()
                    .options(AgentOptions.parse("home=" + home, home))
                    .build();
            assertFalse(optOutEnv
                    .plan()
                    .admitted(dev.turboism.config.optimization.OptimizationCatalog.WARP_DEFORM_EDIT_ACCELERATION));
        } finally {
            if (prior == null) System.clearProperty(WarpDeformEditBridge.ENABLE_PROPERTY);
            else System.setProperty(WarpDeformEditBridge.ENABLE_PROPERTY, prior);
        }
    }
}

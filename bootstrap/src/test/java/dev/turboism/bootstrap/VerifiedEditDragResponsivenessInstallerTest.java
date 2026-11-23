package dev.turboism.bootstrap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.turboism.adapter.cubism.optimization.drag.EditDragResponsivenessBridge;
import dev.turboism.config.RuntimeStartupConfig;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies admission gates and startup policy checks for the edit drag responsiveness installer. */
class VerifiedEditDragResponsivenessInstallerTest {

    private static final Path MISSING_ARTIFACT = Path.of("missing-cubism-host.jar");
    private static final String ARTIFACTS_DIR_NAME = "cubism-host-artifacts";
    private static final List<Path> FALLBACK_ARTIFACT_ROOTS = List.of(
            Path.of(System.getProperty("user.home"), ARTIFACTS_DIR_NAME),
            ReviewedCubismArtifactTestPaths.root());

    @TempDir
    Path home;

    @Test
    void rejectsUnrequestedSafeModeDisabledHookAndUnboundArtifact() throws Exception {
        final RuntimeStartupConfig normal = new RuntimeStartupConfig(false, false, false, false);
        assertFalse(VerifiedEditDragResponsivenessInstaller.admitted(MISSING_ARTIFACT, normal, false, 17));
        assertFalse(VerifiedEditDragResponsivenessInstaller.admitted(
                MISSING_ARTIFACT, new RuntimeStartupConfig(true, false, false, false), true, 17));
        assertFalse(VerifiedEditDragResponsivenessInstaller.admitted(
                MISSING_ARTIFACT,
                new RuntimeStartupConfig(
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        Set.of(VerifiedEditDragResponsivenessInstaller.HOOK_ID)),
                true,
                17));
        assertFalse(VerifiedEditDragResponsivenessInstaller.admitted(MISSING_ARTIFACT, normal, true, 16));
        assertFalse(VerifiedEditDragResponsivenessInstaller.admitted(MISSING_ARTIFACT, normal, true, 17));
    }

    @Test
    void admitsAvailableReviewedCubismArtifacts() throws Exception {
        int admitted = 0;
        final RuntimeStartupConfig normal = new RuntimeStartupConfig(false, false, false, false);
        for (final String version : ReviewedHostArtifacts.reviewedCubismVersions()) {
            final Path jar = findLocalJar(version);
            if (jar == null) {
                continue;
            }
            assertTrue(
                    VerifiedEditDragResponsivenessInstaller.admitted(jar, normal, true, 17),
                    "expected admission for Cubism " + version);
            assertTrue(
                    VerifiedEditDragResponsivenessInstaller.admitted(jar, normal, true, 21),
                    "expected admission on JVM 21 for Cubism " + version);
            admitted++;
        }
        assumeTrue(admitted > 0, "no local reviewed Cubism JARs present");
    }

    @Test
    void installsBeforeRuntimeAndRespectsWarpDeformOptOut() throws Exception {
        NativeOptimizationStartupOrder.assertBeforeRuntime();
        final String prior = System.getProperty(EditDragResponsivenessBridge.ENABLE_PROPERTY);
        System.clearProperty(EditDragResponsivenessBridge.ENABLE_PROPERTY);
        try {
            final HookEnvironment defaultEnv = HookEnvironment.builder()
                    .options(AgentOptions.parse("home=" + home, home))
                    .build();
            assertTrue(NativeOptimizationPolicy.warpDeformEditAccelerationEnabled(defaultEnv));

            Files.writeString(home.resolve("config.json"), """
                    {"format":"turboism.runtime.config","schemaVersion":1,"worktreeId":"drag-resp-test","launcher":{"warpDeformEditAcceleration":false}}
                    """);
            final HookEnvironment optOutEnv = HookEnvironment.builder()
                    .options(AgentOptions.parse("home=" + home, home))
                    .build();
            assertFalse(NativeOptimizationPolicy.warpDeformEditAccelerationEnabled(optOutEnv));
        } finally {
            if (prior == null) {
                System.clearProperty(EditDragResponsivenessBridge.ENABLE_PROPERTY);
            } else {
                System.setProperty(EditDragResponsivenessBridge.ENABLE_PROPERTY, prior);
            }
        }
    }

    private static Path findLocalJar(final String version) {
        final String singleProp = System.getProperty("turboism.test.cubismEditorJar");
        if (singleProp != null && !singleProp.isBlank()) {
            final Path candidate = Path.of(singleProp);
            if (Files.isRegularFile(candidate) && candidate.toString().contains(version)) {
                return candidate;
            }
        }
        for (final Path root : FALLBACK_ARTIFACT_ROOTS) {
            final Path candidate = ReviewedCubismArtifactTestPaths.jar(root, version);
            if (candidate != null && Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }
}

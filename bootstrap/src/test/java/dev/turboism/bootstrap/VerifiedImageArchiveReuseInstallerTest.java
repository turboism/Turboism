package dev.turboism.bootstrap;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.turboism.config.RuntimeStartupConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;

class VerifiedImageArchiveReuseInstallerTest {
    private static final Path MISSING_ARTIFACT = Path.of("missing-cubism-host.jar");

    @Test
    void rejectsUnrequestedSafeModeDisabledHookAndUnboundArtifact() {
        var ordinary = new RuntimeStartupConfig(false, false, false, false);
        assertFalse(VerifiedImageArchiveReuseInstaller.admitted(MISSING_ARTIFACT, ordinary, false));
        assertFalse(VerifiedImageArchiveReuseInstaller.admitted(
                MISSING_ARTIFACT, new RuntimeStartupConfig(true, false, false, false), true));
        assertFalse(VerifiedImageArchiveReuseInstaller.admitted(
                MISSING_ARTIFACT,
                new RuntimeStartupConfig(
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        Set.of(VerifiedImageArchiveReuseInstaller.HOOK_ID)),
                true));
        assertFalse(
                VerifiedImageArchiveReuseInstaller.admitted(MISSING_ARTIFACT, ordinary, true),
                "an artifact without a reviewed image-archive contract must not admit");
    }

    @Test
    void admitsRequestedArtifactWhoseImageClassesMatchAReviewedContract() {
        final Path artifact = System.getProperty("turboism.test.cubismEditorJar") == null
                ? null
                : Path.of(System.getProperty("turboism.test.cubismEditorJar"));
        assumeTrue(
                artifact != null && Files.isRegularFile(artifact),
                "no turboism.test.cubismEditorJar evidence supplied");
        assertTrue(VerifiedImageArchiveReuseInstaller.admitted(
                artifact, new RuntimeStartupConfig(false, false, false, false), true));
    }

    @Test
    void installsAfterAdmissionButBeforeRuntimeStartup() throws Exception {
        NativeOptimizationStartupOrder.assertBeforeRuntime();
    }
}

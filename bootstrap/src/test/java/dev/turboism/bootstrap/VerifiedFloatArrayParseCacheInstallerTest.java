package dev.turboism.bootstrap;

import dev.turboism.config.RuntimeStartupConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class VerifiedFloatArrayParseCacheInstallerTest {
    private static final Path MISSING_ARTIFACT = Path.of("missing-cubism-host.jar");

    @Test void rejectsUnrequestedSafeModeDisabledHookAndUnboundArtifact() {
        var config = new RuntimeStartupConfig(false, false, false, false);
        assertFalse(VerifiedFloatArrayParseCacheInstaller.admitted(MISSING_ARTIFACT, config, false));
        assertFalse(VerifiedFloatArrayParseCacheInstaller.admitted(MISSING_ARTIFACT,
            new RuntimeStartupConfig(true, false, false, false), true));
        assertFalse(VerifiedFloatArrayParseCacheInstaller.admitted(MISSING_ARTIFACT,
            new RuntimeStartupConfig(false, false, false, false, false, false, false,
                Set.of(VerifiedFloatArrayParseCacheInstaller.HOOK_ID)), true));
        assertFalse(VerifiedFloatArrayParseCacheInstaller.admitted(MISSING_ARTIFACT, config, true),
            "an artifact without a reviewed float-parser contract must not admit");
    }

    @Test void admitsRequestedArtifactWhoseParserClassMatchesAReviewedContract() {
        final Path artifact = System.getProperty("turboism.test.cubismEditorJar") == null
            ? null : Path.of(System.getProperty("turboism.test.cubismEditorJar"));
        assumeTrue(artifact != null && Files.isRegularFile(artifact),
            "no turboism.test.cubismEditorJar evidence supplied");
        assertTrue(VerifiedFloatArrayParseCacheInstaller.admitted(
            artifact, new RuntimeStartupConfig(false, false, false, false), true));
    }

    @Test void nativeParserInstallsOnceBeforeRuntimeStartupAndHasFailureCleanup() throws Exception {
        NativeOptimizationStartupOrder.assertBeforeRuntime();
    }
}

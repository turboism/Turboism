package dev.turboism.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.adapter.cubism.textureatlas.interactive.AtlasInteractiveRenderTarget;
import dev.turboism.config.PerformanceSettingsConfig;
import dev.turboism.config.RuntimeStartupConfig;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Verifies bootstrap admission, startup ordering, and toggle defaults for
 * {@link VerifiedAtlasInteractiveRenderInstaller}.
 */
class VerifiedAtlasInteractiveRenderInstallerTest {

    private static final Path MISSING_ARTIFACT = Path.of("missing-cubism-host.jar");
    private static final int MIN_SUPPORTED_JVM = 17;
    private static final int UNSUPPORTED_JVM = 16;
    private static final int FUTURE_JVM = 25;

    @Test
    void rejectsUnrequestedOldJvmAndUnboundArtifact() throws Exception {
        final RuntimeStartupConfig enabledConfig = new RuntimeStartupConfig(false, true, true, true);
        assertFalse(VerifiedAtlasInteractiveRenderInstaller.admitted(
                MISSING_ARTIFACT, enabledConfig, false, MIN_SUPPORTED_JVM));
        assertFalse(VerifiedAtlasInteractiveRenderInstaller.admitted(
                MISSING_ARTIFACT, enabledConfig, true, UNSUPPORTED_JVM));
        assertFalse(VerifiedAtlasInteractiveRenderInstaller.admitted(
                MISSING_ARTIFACT, enabledConfig, true, MIN_SUPPORTED_JVM));
        assertFalse(
                VerifiedAtlasInteractiveRenderInstaller.admitted(MISSING_ARTIFACT, enabledConfig, true, FUTURE_JVM));
    }

    @Test
    void installsBeforeRuntimeInNativeStartupOrder() throws Exception {
        NativeOptimizationStartupOrder.assertBeforeRuntime();
    }

    @Test
    void declaresExpectedWeaveContractCounts() {
        assertEquals(
                AtlasInteractiveRenderTarget.EXPECTED_MATCHES,
                VerifiedAtlasInteractiveRenderInstaller.EXPECTED_MATCHES);
        assertEquals(
                AtlasInteractiveRenderTarget.EXPECTED_MATCHES, VerifiedAtlasInteractiveRenderInstaller.EXPECTED_OWNERS);
        assertEquals(
                AtlasInteractiveRenderTarget.SUPPORTED_VERSIONS.size(),
                AtlasInteractiveRenderTarget.all().size());
    }

    @Test
    void respectsSafeModeAndDisabledHookId() {
        final RuntimeStartupConfig enabledConfig = new RuntimeStartupConfig(false, true, true, true);
        assertTrue(VerifiedInstall.admits(
                VerifiedAtlasInteractiveRenderInstaller.ADMISSION, enabledConfig, true, MIN_SUPPORTED_JVM));
        assertFalse(VerifiedInstall.admits(
                VerifiedAtlasInteractiveRenderInstaller.ADMISSION, enabledConfig, false, MIN_SUPPORTED_JVM));

        final RuntimeStartupConfig safeModeConfig = new RuntimeStartupConfig(true, true, true, true);
        assertFalse(VerifiedInstall.admits(
                VerifiedAtlasInteractiveRenderInstaller.ADMISSION, safeModeConfig, true, MIN_SUPPORTED_JVM));

        final RuntimeStartupConfig hookDisabledConfig = new RuntimeStartupConfig(
                false, true, true, true, true, true, true, Set.of(VerifiedAtlasInteractiveRenderInstaller.HOOK_ID));
        assertFalse(VerifiedInstall.admits(
                VerifiedAtlasInteractiveRenderInstaller.ADMISSION, hookDisabledConfig, true, MIN_SUPPORTED_JVM));
        assertEquals("true", PerformanceSettingsConfig.Field.ATLAS_INTERACTIVE_RENDER.defaultValue(false));
    }
}

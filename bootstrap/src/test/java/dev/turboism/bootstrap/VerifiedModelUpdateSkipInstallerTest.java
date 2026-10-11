package dev.turboism.bootstrap;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.turboism.adapter.cubism.optimization.modelupdate.ModelUpdateSkipBridge;
import dev.turboism.config.RuntimeStartupConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VerifiedModelUpdateSkipInstallerTest {
    private static final Path MISSING_ARTIFACT = Path.of("missing-cubism-host.jar");

    @TempDir
    Path home;

    @Test
    void rejectsUnrequestedOldJvmSafeModeDisabledHookAndUnboundArtifact() {
        var normal = new RuntimeStartupConfig(false, false, false, false);
        assertFalse(VerifiedModelUpdateSkipInstaller.admitted(MISSING_ARTIFACT, normal, false, 17));
        assertFalse(VerifiedModelUpdateSkipInstaller.admitted(MISSING_ARTIFACT, normal, true, 16));
        assertFalse(VerifiedModelUpdateSkipInstaller.admitted(
                MISSING_ARTIFACT, new RuntimeStartupConfig(true, false, false, false), true, 17));
        assertFalse(VerifiedModelUpdateSkipInstaller.admitted(
                MISSING_ARTIFACT,
                new RuntimeStartupConfig(
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        Set.of(VerifiedModelUpdateSkipInstaller.HOOK_ID)),
                true,
                17));
        assertFalse(
                VerifiedModelUpdateSkipInstaller.admitted(MISSING_ARTIFACT, normal, true, 17),
                "an artifact without a reviewed model-update contract must not admit");
        assertFalse(
                VerifiedModelUpdateSkipInstaller.admitted(MISSING_ARTIFACT, normal, true, 25),
                "newer JVM still requires the reviewed target contract");
    }

    @Test
    void admitsRequestedArtifactWhoseUpdateClassesMatchAReviewedContract() {
        final Path artifact = System.getProperty("turboism.test.cubismEditorJar") == null
                ? null
                : Path.of(System.getProperty("turboism.test.cubismEditorJar"));
        assumeTrue(
                artifact != null && Files.isRegularFile(artifact),
                "no turboism.test.cubismEditorJar evidence supplied");
        assertTrue(VerifiedModelUpdateSkipInstaller.admitted(
                artifact, new RuntimeStartupConfig(false, false, false, false), true, 17));
    }

    @Test
    void installsBeforeRuntimeAndCleansUpOnFailure() throws Exception {
        NativeOptimizationStartupOrder.assertBeforeRuntime();
    }

    @Test
    void persistedLauncherModelUpdateSkipFalseDisablesWithoutSystemProperty() throws Exception {
        final String prior = System.getProperty(ModelUpdateSkipBridge.ENABLE_PROPERTY);
        System.clearProperty(ModelUpdateSkipBridge.ENABLE_PROPERTY);
        try {
            Files.writeString(home.resolve("config.json"), """
                    {"format":"turboism.runtime.config","schemaVersion":1,"worktreeId":"mus-test","launcher":{"modelUpdateSkip":false}}
                    """);
            final HookEnvironment env = HookEnvironment.builder()
                    .options(AgentOptions.parse("home=" + home, home))
                    .fullRuntimeAdmission(true)
                    .build();
            assertFalse(NativeOptimizationPolicy.modelUpdateSkipEnabled(env));
            assertFalse(ModelUpdateSkipBridge.flagEnabled());
            assertNotNull(new ModelUpdateSkipHookContributor().installAdmitted(env));
        } finally {
            ModelUpdateSkipBridge.setConfiguredDefault(true);
            if (prior == null) System.clearProperty(ModelUpdateSkipBridge.ENABLE_PROPERTY);
            else System.setProperty(ModelUpdateSkipBridge.ENABLE_PROPERTY, prior);
        }
    }
}

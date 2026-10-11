package dev.turboism.bootstrap;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.turboism.adapter.cubism.optimization.modelupdate.incremental.IncrementalUpdateBridge;
import dev.turboism.config.RuntimeStartupConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VerifiedIncrementalUpdateInstallerTest {
    private static final Path MISSING_ARTIFACT = Path.of("missing-cubism-host.jar");

    @TempDir
    Path home;

    @Test
    void rejectsUnrequestedOldJvmSafeModeDisabledHookAndUnboundArtifact() {
        var normal = new RuntimeStartupConfig(false, false, false, false);
        assertFalse(VerifiedIncrementalUpdateInstaller.admitted(MISSING_ARTIFACT, normal, false, 17));
        assertFalse(VerifiedIncrementalUpdateInstaller.admitted(MISSING_ARTIFACT, normal, true, 16));
        assertFalse(VerifiedIncrementalUpdateInstaller.admitted(
                MISSING_ARTIFACT, new RuntimeStartupConfig(true, false, false, false), true, 17));
        assertFalse(VerifiedIncrementalUpdateInstaller.admitted(
                MISSING_ARTIFACT,
                new RuntimeStartupConfig(
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        Set.of(VerifiedIncrementalUpdateInstaller.HOOK_ID)),
                true,
                17));
        assertFalse(
                VerifiedIncrementalUpdateInstaller.admitted(MISSING_ARTIFACT, normal, true, 17),
                "an artifact without a reviewed incremental-update contract must not admit");
        assertFalse(
                VerifiedIncrementalUpdateInstaller.admitted(MISSING_ARTIFACT, normal, true, 25),
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
        assertTrue(VerifiedIncrementalUpdateInstaller.admitted(
                artifact, new RuntimeStartupConfig(false, false, false, false), true, 17));
    }

    @Test
    void installsBeforeRuntimeAndCleansUpOnFailure() throws Exception {
        NativeOptimizationStartupOrder.assertBeforeRuntime();
    }

    @Test
    void persistedLauncherIncrementalUpdateTrueEnablesWithoutSystemProperty() throws Exception {
        final String prior = System.getProperty(IncrementalUpdateBridge.ENABLE_PROPERTY);
        System.clearProperty(IncrementalUpdateBridge.ENABLE_PROPERTY);
        try {
            Files.writeString(
                    home.resolve("config.json"),
                    """
                    {"format":"turboism.runtime.config","schemaVersion":1,"worktreeId":"inc-test","launcher":{"incrementalUpdate":true}}
                    """);
            final HookEnvironment env = HookEnvironment.builder()
                    .options(AgentOptions.parse("home=" + home, home))
                    .fullRuntimeAdmission(true)
                    .build();
            assertTrue(NativeOptimizationPolicy.incrementalUpdateEnabled(env));
            assertTrue(IncrementalUpdateBridge.flagEnabled());
        } finally {
            IncrementalUpdateBridge.setConfiguredDefault(false);
            if (prior == null) System.clearProperty(IncrementalUpdateBridge.ENABLE_PROPERTY);
            else System.setProperty(IncrementalUpdateBridge.ENABLE_PROPERTY, prior);
        }
    }
}

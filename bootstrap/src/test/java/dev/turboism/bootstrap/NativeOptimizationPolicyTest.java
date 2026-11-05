package dev.turboism.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.config.NativeOptimizationDiagnostics;
import dev.turboism.config.optimization.Optimization;
import dev.turboism.config.optimization.OptimizationCatalog;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NativeOptimizationPolicyTest {
    @TempDir
    Path home;

    /** Declared override property to its previous value; {@code null} means it was not set. */
    private final Map<String, String> properties = new LinkedHashMap<>();

    @BeforeEach
    void clearOverrides() {
        for (Optimization optimization : OptimizationCatalog.persisted()) {
            for (String property : optimization.overrideProperties()) {
                properties.put(property, System.getProperty(property));
                System.clearProperty(property);
            }
        }
    }

    @AfterEach
    void restoreOverrides() {
        properties.forEach((property, previous) -> {
            if (previous == null) System.clearProperty(property);
            else System.setProperty(property, previous);
        });
        properties.clear();
        dev.turboism.adapter.cubism.optimization.modelupdate.ModelUpdateSkipBridge.setConfiguredDefault(false);
        dev.turboism.adapter.cubism.optimization.modelupdate.incremental.IncrementalUpdateBridge.setConfiguredDefault(
                false);
        dev.turboism.adapter.cubism.optimization.uniform.UniformLocationHookBridge.setConfiguredDefault(false);
        dev.turboism.adapter.cubism.optimization.image.ImageArchiveReuseBridge.setConfiguredDefault(false);
        dev.turboism.adapter.cubism.optimization.serialization.FloatArrayParseBridge.setConfiguredDefault(false);
        dev.turboism.adapter.cubism.optimization.image.TextureUploadPreparationBridge.setConfiguredDefault(false);
        dev.turboism.adapter.cubism.optimization.geometry.WarpPositionProjectionBridge.setConfiguredDefault(false);
        dev.turboism.adapter.cubism.optimization.inspector.InspectorPropertyBypassBridge.setConfiguredDefault(false);
    }

    private HookEnvironment environment() {
        return HookEnvironment.builder()
                .options(AgentOptions.parse("home=" + home, home))
                .build();
    }

    private void writeChoices(boolean enabled) throws Exception {
        String launcher = OptimizationCatalog.persisted().stream()
                .filter(optimization -> optimization.access() == Optimization.Access.PERSISTED_LAUNCHER)
                .map(optimization -> "\"" + optimization.key() + "\":" + enabled)
                .collect(java.util.stream.Collectors.joining(","));
        String root = OptimizationCatalog.persisted().stream()
                .filter(optimization -> optimization.access() == Optimization.Access.PERSISTED_ROOT)
                .map(optimization -> "\"" + optimization.key() + "\":" + enabled)
                .collect(java.util.stream.Collectors.joining(","));
        java.nio.file.Files.writeString(
                home.resolve("config.json"),
                "{\"format\":\"turboism.runtime.config\",\"schemaVersion\":1,\"worktreeId\":\"optimization-test\","
                        + root + ",\"launcher\":{" + launcher + "}}");
    }

    @Test
    void hooksShareOneLaunchSnapshotAndNextLaunchReadsNewGlobalChoices() throws Exception {
        writeChoices(false);
        HookEnvironment first = environment();
        assertFalse(NativeOptimizationPolicy.modelUpdateSkipEnabled(first.forHook("first")));
        writeChoices(true);
        assertFalse(NativeOptimizationPolicy.incrementalUpdateEnabled(first.forHook("second")));
        assertFalse(NativeOptimizationPolicy.uniformLocationCacheEnabled(first));
        assertFalse(NativeOptimizationPolicy.uploadElisionEnabled(first));
        assertFalse(NativeOptimizationPolicy.meshSetupElisionEnabled(first));
        assertFalse(NativeOptimizationPolicy.inputPathElisionEnabled(first));
        assertFalse(NativeOptimizationPolicy.mesaGlThreadEnabled(first));
        for (Optimization optimization : OptimizationCatalog.persisted())
            assertFalse(first.optimizationConfig().choice(optimization).enabled());
        HookEnvironment next = environment();
        for (Optimization optimization : OptimizationCatalog.persisted())
            assertTrue(next.optimizationConfig().choice(optimization).enabled());
        assertTrue(NativeOptimizationPolicy.modelUpdateSkipEnabled(next));
        assertTrue(NativeOptimizationPolicy.incrementalUpdateEnabled(next));
        assertTrue(NativeOptimizationPolicy.uniformLocationCacheEnabled(next));
        assertTrue(NativeOptimizationPolicy.uploadElisionEnabled(next));
        assertTrue(NativeOptimizationPolicy.meshSetupElisionEnabled(next));
        assertTrue(NativeOptimizationPolicy.inputPathElisionEnabled(next));
        assertTrue(NativeOptimizationPolicy.mesaGlThreadEnabled(next));
        assertTrue(NativeOptimizationPolicy.meshInterpolationEnabled(next));
        assertTrue(NativeOptimizationPolicy.shaderUniformEnabled(next));
        assertTrue(NativeOptimizationPolicy.matrixScratchEnabled(next));
        assertTrue(NativeOptimizationPolicy.imageArchiveReuseEnabled(next));
        assertTrue(NativeOptimizationPolicy.floatArrayParseCacheEnabled(next));
        assertTrue(NativeOptimizationPolicy.textureUploadPreparationEnabled(next));
        assertTrue(NativeOptimizationPolicy.warpPositionProjectionEnabled(next));
        assertTrue(NativeOptimizationPolicy.reinitPairElisionEnabled(next));
    }

    @Test
    void refusedContributorNeverReportsAnInstalledOptimization() throws Exception {
        HookEnvironment env = environment();
        NativeOptimizationPolicy.modelUpdateSkipEnabled(env);
        NativeOptimizationHookContributor refused =
                new NativeOptimizationHookContributor("TURBOISM_MODEL_UPDATE_SKIP") {
                    @Override
                    AutoCloseable installAdmitted(HookEnvironment environment) {
                        return noOp();
                    }
                };
        try (AutoCloseable ignored = refused.install(env)) {
            refused.bind(env);
            assertEquals(
                    "not-installed",
                    NativeOptimizationDiagnostics.snapshot()
                            .get(OptimizationCatalog.MODEL_UPDATE_SKIP)
                            .state());
        }
    }

    @Test
    void successfulContributorReportsInstallationAndCloseSeparatelyFromPreference() throws Exception {
        writeChoices(true);
        HookEnvironment env = environment();
        NativeOptimizationPolicy.modelUpdateSkipEnabled(env);
        NativeOptimizationHookContributor installed =
                new NativeOptimizationHookContributor("TURBOISM_MODEL_UPDATE_SKIP") {
                    @Override
                    AutoCloseable installAdmitted(HookEnvironment environment) {
                        return () -> {};
                    }
                };
        try (AutoCloseable ignored = installed.install(env)) {
            assertEquals(
                    "installed",
                    NativeOptimizationDiagnostics.snapshot()
                            .get(OptimizationCatalog.MODEL_UPDATE_SKIP)
                            .state());
        }
        assertEquals(
                "closed",
                NativeOptimizationDiagnostics.snapshot()
                        .get(OptimizationCatalog.MODEL_UPDATE_SKIP)
                        .reason());
    }
}

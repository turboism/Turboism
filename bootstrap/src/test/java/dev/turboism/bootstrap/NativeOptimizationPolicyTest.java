package dev.turboism.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.config.NativeOptimizationConfig.Option;
import dev.turboism.config.NativeOptimizationDiagnostics;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NativeOptimizationPolicyTest {
    @TempDir
    Path home;

    private final Map<Option, String> properties = new EnumMap<>(Option.class);

    @BeforeEach
    void clearOverrides() {
        for (Option option : Option.values()) {
            properties.put(option, System.getProperty(option.property()));
            System.clearProperty(option.property());
        }
    }

    @AfterEach
    void restoreOverrides() {
        for (Option option : Option.values()) {
            String previous = properties.get(option);
            if (previous == null) System.clearProperty(option.property());
            else System.setProperty(option.property(), previous);
        }
        dev.turboism.adapter.cubism.optimization.modelupdate.ModelUpdateSkipBridge.setConfiguredDefault(false);
        dev.turboism.adapter.cubism.optimization.modelupdate.incremental.IncrementalUpdateBridge.setConfiguredDefault(
                false);
        dev.turboism.adapter.cubism.optimization.uniform.UniformLocationHookBridge.setConfiguredDefault(false);
        dev.turboism.adapter.cubism.optimization.image.ImageArchiveReuseBridge.setConfiguredDefault(false);
        dev.turboism.adapter.cubism.optimization.serialization.FloatArrayParseBridge.setConfiguredDefault(false);
        dev.turboism.adapter.cubism.optimization.image.TextureUploadPreparationBridge.setConfiguredDefault(false);
        dev.turboism.adapter.cubism.optimization.geometry.WarpPositionProjectionBridge.setConfiguredDefault(false);
    }

    private HookEnvironment environment() {
        return HookEnvironment.builder()
                .options(AgentOptions.parse("home=" + home, home))
                .build();
    }

    private void writeChoices(boolean enabled) throws Exception {
        String launcher = java.util.Arrays.stream(Option.values())
                .filter(option -> option.field().launcher())
                .map(option -> "\"" + option.key() + "\":" + enabled)
                .collect(java.util.stream.Collectors.joining(","));
        String root = java.util.Arrays.stream(Option.values())
                .filter(option -> !option.field().launcher())
                .map(option -> "\"" + option.key() + "\":" + enabled)
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
        assertFalse(NativeOptimizationPolicy.inputPathElisionEnabled(first));
        assertFalse(NativeOptimizationPolicy.mesaGlThreadEnabled(first));
        for (Option option : Option.values())
            assertFalse(first.optimizationConfig().choice(option).enabled());
        HookEnvironment next = environment();
        for (Option option : Option.values())
            assertTrue(next.optimizationConfig().choice(option).enabled());
        assertTrue(NativeOptimizationPolicy.modelUpdateSkipEnabled(next));
        assertTrue(NativeOptimizationPolicy.incrementalUpdateEnabled(next));
        assertTrue(NativeOptimizationPolicy.uniformLocationCacheEnabled(next));
        assertTrue(NativeOptimizationPolicy.uploadElisionEnabled(next));
        assertTrue(NativeOptimizationPolicy.inputPathElisionEnabled(next));
        assertTrue(NativeOptimizationPolicy.mesaGlThreadEnabled(next));
        assertTrue(NativeOptimizationPolicy.meshInterpolationEnabled(next));
        assertTrue(NativeOptimizationPolicy.shaderUniformEnabled(next));
        assertTrue(NativeOptimizationPolicy.matrixScratchEnabled(next));
        assertTrue(NativeOptimizationPolicy.imageArchiveReuseEnabled(next));
        assertTrue(NativeOptimizationPolicy.floatArrayParseCacheEnabled(next));
        assertTrue(NativeOptimizationPolicy.textureUploadPreparationEnabled(next));
        assertTrue(NativeOptimizationPolicy.warpPositionProjectionEnabled(next));
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
                            .get(Option.MODEL_UPDATE_SKIP)
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
                            .get(Option.MODEL_UPDATE_SKIP)
                            .state());
        }
        assertEquals(
                "closed",
                NativeOptimizationDiagnostics.snapshot()
                        .get(Option.MODEL_UPDATE_SKIP)
                        .reason());
    }
}

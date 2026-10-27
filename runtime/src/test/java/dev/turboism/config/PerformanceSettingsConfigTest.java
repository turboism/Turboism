package dev.turboism.config;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.config.PerformanceSettingsConfig.Field;
import dev.turboism.internal.core.CubismJvmSettingsService;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PerformanceSettingsConfigTest {
    @TempDir
    Path home;

    @Test
    void renderingOptimizationsRequireExplicitOptInOnEveryPlatform() {
        for (boolean proton : new boolean[] {false, true}) {
            for (Field field : Field.values()) {
                if (field.nativeOptimization()) assertEquals("false", field.defaultValue(proton), field.key());
            }
            assertEquals("true", Field.ZGC.defaultValue(proton));
            assertEquals("graalvm", Field.CUBISM_JVM.defaultValue(proton));
        }
    }

    @Test
    void legacyToggleNormalizationCannotImplicitlyEnableRenderingOptimizations() {
        assertFalse(AtlasTileBboxPreference.DEFAULT_ENABLED);
        assertFalse(AtlasCacheReusePreference.DEFAULT_ENABLED);
        assertFalse(MeshTriangulationPreference.DEFAULT_ENABLED);
        assertFalse(TriangulationEdgeIndexPreference.DEFAULT_ENABLED);
        assertFalse(AtlasTileBboxPreference.normalize("invalid"));
        assertFalse(AtlasCacheReusePreference.normalize("invalid"));
        assertFalse(MeshTriangulationPreference.normalize("invalid"));
        assertFalse(TriangulationEdgeIndexPreference.normalize("invalid"));
        assertFalse(dev.turboism.internal.core.AtlasCacheReuseSettingsService.unavailable()
                .read());
        assertFalse(CubismJvmSettingsService.unavailable().modelUpdateSkip());
        assertFalse(CubismJvmSettingsService.unavailable().uniformLocationCache());
        assertTrue(CubismJvmSettingsService.unavailable().zgc());
    }

    @Test
    void allFifteenFieldsRoundTripWithoutLosingOtherPreferencesAndReloadOnNextLaunch() {
        var repository = new RuntimeConfigRepository(home, ignored -> {});
        for (Field field : Field.values()) {
            String value = field.booleanValue()
                    ? "false"
                    : switch (field) {
                        case CUBISM_JVM -> "bundled";
                        case GRAAL_VM_PATH -> "D:/GraalVM";
                        case MEMORY_PROFILE -> "balanced4g";
                        default -> throw new AssertionError(field);
                    };
            PerformanceSettingsConfig.save(repository, field, value);
        }
        var first = PerformanceSettingsConfig.load(home, ignored -> {});
        assertEquals(16, Field.values().length);
        for (Field field : Field.values()) {
            assertEquals("global-config", first.value(field).source());
            assertEquals(
                    PerformanceSettingsConfig.saved(repository.read(), field, false),
                    first.value(field).value());
        }
        PerformanceSettingsConfig.save(repository, Field.ATLAS_CACHE_REUSE, "true");
        assertEquals("false", first.value(Field.ATLAS_CACHE_REUSE).value());
        assertEquals(
                "true",
                PerformanceSettingsConfig.load(home, ignored -> {})
                        .value(Field.ATLAS_CACHE_REUSE)
                        .value());
        assertEquals("balanced4g", first.value(Field.MEMORY_PROFILE).value());
    }

    @Test
    void pageShowsAllFieldsAndSeparatesPendingChangesFromObservedSessionResults() throws Exception {
        var repository = new RuntimeConfigRepository(home, ignored -> {});
        PerformanceSettingsConfig.save(repository, Field.REDUCE_AUTO_BACKUP, "false");
        PerformanceSettingsConfig.save(repository, Field.ATLAS_CACHE_REUSE, "false");
        NativeOptimizationDiagnostics.startup(PerformanceSettingsConfig.load(home, ignored -> {}));
        NativeOptimizationDiagnostics.installed("TURBOISM_ATLAS_CACHE_REUSE", "not-installed", "preference-disabled");
        try (var service = new CubismJvmSettingsFileService(repository, home, Map.of())) {
            new AtlasCacheReuseSettingsFileService(repository).save(true);
            service.saveReduceAutoBackup(true);
            service.saveZgc(true);
            service.saveMemoryProfile(CubismJvmSettingsService.MemoryProfile.SYSTEM);
            assertFalse(
                    service.startupReduceAutoBackup(), "editing preferences must not mutate this session's request");
            var statuses = service.performanceStatuses().stream()
                    .collect(Collectors.toMap(CubismJvmSettingsService.PerformanceStatus::key, s -> s));
            assertEquals(16, statuses.size());
            var atlas = statuses.get("atlasCacheReuse");
            assertEquals("true", atlas.saved());
            assertEquals("false", atlas.requested());
            assertTrue(atlas.pendingRestart());
            assertTrue(atlas.actual().startsWith("not-installed"));
            assertTrue(statuses.get("reduceAutoBackup").pendingRestart());
            assertEquals("not-observed", statuses.get("reduceAutoBackup").actual());
            assertEquals(
                    System.getProperty("java.home"), statuses.get("graalVmPath").actual());
            assertTrue(repository.read().path("launcher").path("zgc").booleanValue());
            assertEquals(
                    "system",
                    repository.read().path("launcher").path("memoryProfile").textValue());
        }
    }

    @Test
    void independentLegacyReadersUseValidatedGlobalPathsIncludingRelativeHome() {
        var repository = new RuntimeConfigRepository(home, ignored -> {});
        PerformanceSettingsConfig.save(repository, Field.TRIANGULATION_EDGE_INDEX, "false");
        assertFalse(TriangulationEdgeIndexPreference.read(home));
        assertFalse(TriangulationEdgeIndexPreference.read(
                Path.of("").toAbsolutePath().relativize(home)));
        assertFalse(new TriangulationEdgeIndexSettingsFileService(repository).read());
    }

    @Test
    void meshInterpolationJvmOverridePrecedenceAndFallback() {
        var repository = new RuntimeConfigRepository(home, ignored -> {});
        PerformanceSettingsConfig.save(repository, Field.MESH_INTERPOLATION, "false");

        // 1. Without overrides, reads global config false
        var initial = PerformanceSettingsConfig.load(home, ignored -> {});
        assertEquals("false", initial.value(Field.MESH_INTERPOLATION).value());
        assertEquals("global-config", initial.value(Field.MESH_INTERPOLATION).source());

        // 2. Legacy override turboism.meshInterpolation.enabled=true wins over config
        System.setProperty("turboism.meshInterpolation.enabled", "true");
        try {
            var legacy = PerformanceSettingsConfig.load(home, ignored -> {});
            assertEquals("true", legacy.value(Field.MESH_INTERPOLATION).value());
            assertEquals("jvm-override", legacy.value(Field.MESH_INTERPOLATION).source());

            // 3. Modern override turboism.optimization.meshInterpolation=false wins over legacy
            System.setProperty("turboism.optimization.meshInterpolation", "false");
            try {
                var modern = PerformanceSettingsConfig.load(home, ignored -> {});
                assertEquals("false", modern.value(Field.MESH_INTERPOLATION).value());
                assertEquals(
                        "jvm-override", modern.value(Field.MESH_INTERPOLATION).source());
            } finally {
                System.clearProperty("turboism.optimization.meshInterpolation");
            }
        } finally {
            System.clearProperty("turboism.meshInterpolation.enabled");
        }

        // 4. Invalid config forces false and source=invalid-config even with override
        try {
            java.nio.file.Files.writeString(home.resolve("config.json"), "{broken");
            System.setProperty("turboism.meshInterpolation.enabled", "true");
            var invalid = PerformanceSettingsConfig.load(home, ignored -> {});
            assertEquals("false", invalid.value(Field.MESH_INTERPOLATION).value());
            assertEquals(
                    "invalid-config", invalid.value(Field.MESH_INTERPOLATION).source());
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            System.clearProperty("turboism.meshInterpolation.enabled");
        }
    }
}

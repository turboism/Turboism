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

    private static final java.util.Set<Field> DEFAULT_ENABLED_NATIVE_FIELDS = java.util.Set.of(
            Field.MODEL_UPDATE_SKIP,
            Field.UPLOAD_ELISION,
            Field.MESH_SETUP_ELISION,
            Field.MESH_INTERPOLATION,
            Field.SHADER_UNIFORM,
            Field.MATRIX_SCRATCH,
            Field.WARP_POSITION_PROJECTION,
            Field.CLIP_MASK_DEDUPLICATION,
            Field.CLIP_MASK_SCISSOR_CLEAR,
            Field.EXTENDED_INTERPOLATION_INCREMENTAL_SETUP,
            Field.MODEL_INSTANCE_COALESCE,
            Field.EXTENDED_INTERPOLATION_SIBLING_ELISION,
            Field.WARP_GRID_INTERPOLATION,
            Field.WARP_NORMAL_POINT_ELISION,
            Field.CAMERA_MATRIX_CACHE,
            Field.RENDER_PASS_TRAVERSAL,
            Field.MESH_HIT_TEST,
            Field.SCENE_HANDLER_CACHE,
            Field.CURSOR_VALIDATION_ELISION,
            Field.PARAMETER_ROW_REPAINT,
            Field.WARP_DEFORM_EDIT_ACCELERATION,
            Field.MESH_TRIANGULATION_HASH,
            Field.TRIANGULATION_EDGE_INDEX,
            Field.ATLAS_TILE_BBOX,
            Field.ATLAS_CACHE_REUSE,
            Field.ATLAS_LOADING,
            Field.ATLAS_INTERACTIVE_RENDER,
            Field.TRIANGULATION_SPATIAL_INDEX,
            Field.MODEL_OPEN_ACCELERATION,
            Field.RUNTIME_EXPORT_ACCELERATION,
            Field.MESH_EDIT_ACCELERATION,
            Field.MEMORY_LEAK_MITIGATION);

    @Test
    void nativeOptimizationsUseTheirReviewedDefaultsOnBothPlatforms() {
        for (boolean proton : new boolean[] {false, true}) {
            for (Field field : Field.values()) {
                if (field.nativeOptimization()) {
                    assertEquals(
                            Boolean.toString(DEFAULT_ENABLED_NATIVE_FIELDS.contains(field)),
                            field.defaultValue(proton),
                            field.key());
                }
            }
            assertEquals("true", Field.ZGC.defaultValue(proton));
            assertEquals("graalvm", Field.CUBISM_JVM.defaultValue(proton));
        }
    }

    @Test
    void legacyToggleNormalizationMatchesVerifiedAssetLoadDefaults() {
        assertTrue(AtlasTileBboxPreference.DEFAULT_ENABLED);
        assertTrue(AtlasCacheReusePreference.DEFAULT_ENABLED);
        assertTrue(MeshTriangulationPreference.DEFAULT_ENABLED);
        assertTrue(TriangulationEdgeIndexPreference.DEFAULT_ENABLED);
        assertTrue(AtlasTileBboxPreference.normalize("invalid"));
        assertTrue(AtlasCacheReusePreference.normalize("invalid"));
        assertTrue(MeshTriangulationPreference.normalize("invalid"));
        assertTrue(TriangulationEdgeIndexPreference.normalize("invalid"));
        assertFalse(AtlasTileBboxPreference.normalize(false));
        assertFalse(AtlasCacheReusePreference.normalize(false));
        assertFalse(MeshTriangulationPreference.normalize(false));
        assertFalse(TriangulationEdgeIndexPreference.normalize(false));
        assertFalse(dev.turboism.internal.core.OptimizationSettingsService.unavailable()
                .enabled("atlasCacheReuse"));
        assertFalse(dev.turboism.internal.core.OptimizationSettingsService.unavailable()
                .enabled("modelUpdateSkip"));
        assertFalse(dev.turboism.internal.core.OptimizationSettingsService.unavailable()
                .enabled("uniformLocationCache"));
        assertTrue(CubismJvmSettingsService.unavailable().zgc());
    }

    @Test
    void allFieldsRoundTripWithoutLosingOtherPreferencesAndReloadOnNextLaunch() {
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
        assertEquals(45, Field.values().length);
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
            new OptimizationSettingsFileService(repository).setEnabled("atlasCacheReuse", true);
            service.saveReduceAutoBackup(true);
            service.saveZgc(true);
            service.saveMemoryProfile(CubismJvmSettingsService.MemoryProfile.SYSTEM);
            assertFalse(
                    service.startupReduceAutoBackup(), "editing preferences must not mutate this session's request");
            var statuses = service.performanceStatuses().stream()
                    .collect(Collectors.toMap(CubismJvmSettingsService.PerformanceStatus::key, s -> s));
            assertEquals(Field.values().length, statuses.size());
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
        assertFalse(new OptimizationSettingsFileService(repository).enabled("meshTriangulationEdgeIndex"));
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

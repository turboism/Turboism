package dev.turboism.config;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.config.PerformanceSettingsConfig.Field;
import dev.turboism.config.optimization.Optimization;
import dev.turboism.config.optimization.OptimizationCatalog;
import dev.turboism.config.optimization.OptimizationPreset;
import dev.turboism.internal.core.CubismJvmSettingsService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
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
            Field.MESA_GL_THREAD,
            Field.MESH_INTERPOLATION,
            Field.SHADER_UNIFORM,
            Field.MATRIX_SCRATCH,
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
        assertEquals(48, Field.values().length);
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

    @Test
    void migratesV0702SavedConfigToBalancedAndRecordsMarker() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var root = json.createObjectNode();
        root.put("format", "turboism.runtime.config");
        root.put("schemaVersion", 1);
        root.put("worktreeId", "turboism-runtime");
        root.putArray("pluginDirs").add("plugins");
        var launcher = root.putObject("launcher");
        launcher.put("cubismJvm", "graalvm");
        launcher.put("zgc", true);
        // Simulate v0.70.2 Settings OK: existing default-ON switches persisted true, 13 promoted
        // switches + unpromoted default-OFF switches persisted false, no optimizationDefaultsVersion.
        for (Optimization optimization : OptimizationCatalog.persisted()) {
            if (Set.of(
                            OptimizationCatalog.WARP_DEFORM_EDIT_ACCELERATION,
                            OptimizationCatalog.ATLAS_INTERACTIVE_RENDER,
                            OptimizationCatalog.MESH_EDIT_ACCELERATION,
                            OptimizationCatalog.MEMORY_LEAK_MITIGATION)
                    .contains(optimization)) {
                continue;
            }
            boolean v0702DefaultOn = OptimizationCatalog.isDefaultEnabled(optimization)
                    && !OptimizationCatalog.isLegacyPromotedDefaultKey(optimization.key());
            var parent = optimization.access() == Optimization.Access.PERSISTED_LAUNCHER ? launcher : root;
            parent.put(optimization.key(), v0702DefaultOn);
        }
        Files.writeString(
                home.resolve("config.json"),
                json.writerWithDefaultPrettyPrinter().writeValueAsString(root));

        var loaded = PerformanceSettingsConfig.load(home, ignored -> {});
        for (Optimization promoted : OptimizationCatalog.legacyPromotedDefaults()) {
            assertEquals("true", loaded.value(Field.forKey(promoted.key())).value(), promoted.key());
        }
        assertEquals("false", loaded.value(Field.UNIFORM_LOCATION_CACHE).value());
        assertEquals("false", loaded.value(Field.WARP_POSITION_PROJECTION).value());
        assertEquals("false", loaded.value(Field.INCREMENTAL_UPDATE).value());
        assertEquals("true", loaded.value(Field.WARP_DEFORM_EDIT_ACCELERATION).value());

        var persisted = json.readTree(home.resolve("config.json").toFile());
        assertEquals(
                PerformanceSettingsConfig.CURRENT_OPTIMIZATION_DEFAULTS_VERSION,
                persisted
                        .path(PerformanceSettingsConfig.OPTIMIZATION_DEFAULTS_VERSION_FIELD)
                        .asInt());
        try (var service = new OptimizationSettingsFileService(home)) {
            assertEquals(OptimizationPreset.BALANCED, OptimizationPreset.detect(service));
        }
    }

    @Test
    void migratesExplicitOffPresetWithoutPromotingOptimizations() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var root = json.createObjectNode();
        root.put("format", "turboism.runtime.config");
        root.put("schemaVersion", 1);
        root.put("worktreeId", "turboism-runtime");
        var launcher = root.putObject("launcher");
        launcher.put("cubismJvm", "graalvm");
        for (Optimization optimization : OptimizationCatalog.persisted()) {
            if (Set.of(
                            OptimizationCatalog.WARP_DEFORM_EDIT_ACCELERATION,
                            OptimizationCatalog.ATLAS_INTERACTIVE_RENDER,
                            OptimizationCatalog.MESH_EDIT_ACCELERATION,
                            OptimizationCatalog.MEMORY_LEAK_MITIGATION)
                    .contains(optimization)) {
                continue;
            }
            var parent = optimization.access() == Optimization.Access.PERSISTED_LAUNCHER ? launcher : root;
            parent.put(optimization.key(), false);
        }
        Files.writeString(
                home.resolve("config.json"),
                json.writerWithDefaultPrettyPrinter().writeValueAsString(root));

        var loaded = PerformanceSettingsConfig.load(home, ignored -> {});
        for (Optimization optimization : OptimizationCatalog.persisted()) {
            assertEquals("false", loaded.value(Field.forKey(optimization.key())).value(), optimization.key());
        }
        var persisted = json.readTree(home.resolve("config.json").toFile());
        assertEquals(
                PerformanceSettingsConfig.CURRENT_OPTIMIZATION_DEFAULTS_VERSION,
                persisted
                        .path(PerformanceSettingsConfig.OPTIMIZATION_DEFAULTS_VERSION_FIELD)
                        .asInt());
        try (var service = new OptimizationSettingsFileService(home)) {
            assertEquals(OptimizationPreset.OFF, OptimizationPreset.detect(service));
        }
    }

    @Test
    void postMigrationExplicitFalseOptOutPersistsIdempotentlyAcrossReloads() throws Exception {
        migratesV0702SavedConfigToBalancedAndRecordsMarker();
        try (var service = new OptimizationSettingsFileService(home)) {
            service.setEnabled(OptimizationCatalog.WARP_GRID_INTERPOLATION.key(), false);
            service.setEnabled(OptimizationCatalog.MODEL_INSTANCE_COALESCE.key(), false);
        }
        var reloaded = PerformanceSettingsConfig.load(home, ignored -> {});
        assertEquals("false", reloaded.value(Field.WARP_GRID_INTERPOLATION).value());
        assertEquals(
                "global-config", reloaded.value(Field.WARP_GRID_INTERPOLATION).source());
        assertEquals("false", reloaded.value(Field.MODEL_INSTANCE_COALESCE).value());
        assertEquals("true", reloaded.value(Field.MESH_INTERPOLATION).value());
        try (var service = new OptimizationSettingsFileService(home)) {
            assertFalse(service.enabled(OptimizationCatalog.WARP_GRID_INTERPOLATION.key()));
            assertFalse(service.enabled(OptimizationCatalog.MODEL_INSTANCE_COALESCE.key()));
            assertEquals(OptimizationPreset.CUSTOM, OptimizationPreset.detect(service));
        }
    }

    @Test
    void migratesVersion1ConfigPromotingOnlyMesaGlThreadWhilePreservingOtherOptOuts() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var root = json.createObjectNode();
        root.put("format", "turboism.runtime.config");
        root.put("schemaVersion", 1);
        root.put("optimizationDefaultsVersion", 1);
        root.put("worktreeId", "turboism-runtime");
        root.putArray("pluginDirs").add("plugins");
        root.put("atlasLoading", true);
        var launcher = root.putObject("launcher");
        launcher.put("cubismJvm", "graalvm");
        launcher.put("modelUpdateSkip", true);
        launcher.put("mesaGlThread", false);
        launcher.put("shaderUniform", false);
        launcher.put("matrixScratch", false);
        launcher.put("warpGridInterpolation", false);
        launcher.put("uniformLocationCache", false);
        Files.writeString(
                home.resolve("config.json"),
                json.writerWithDefaultPrettyPrinter().writeValueAsString(root));

        var loaded = PerformanceSettingsConfig.load(home, ignored -> {});
        assertEquals("true", loaded.value(Field.MESA_GL_THREAD).value());
        assertEquals("false", loaded.value(Field.SHADER_UNIFORM).value());
        assertEquals("false", loaded.value(Field.MATRIX_SCRATCH).value());
        assertEquals("false", loaded.value(Field.WARP_GRID_INTERPOLATION).value());
        assertEquals("false", loaded.value(Field.UNIFORM_LOCATION_CACHE).value());

        var persisted = json.readTree(home.resolve("config.json").toFile());
        assertEquals(
                PerformanceSettingsConfig.CURRENT_OPTIMIZATION_DEFAULTS_VERSION,
                persisted
                        .path(PerformanceSettingsConfig.OPTIMIZATION_DEFAULTS_VERSION_FIELD)
                        .asInt());
        assertTrue(persisted.path("launcher").path("mesaGlThread").booleanValue());
        assertFalse(persisted.path("launcher").path("shaderUniform").booleanValue());
        assertFalse(persisted.path("launcher").path("matrixScratch").booleanValue());
        assertFalse(persisted.path("launcher").path("warpGridInterpolation").booleanValue());
    }

    @Test
    void migratesVersion1MinimalConfigWithMesaGlThreadOptOut() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var root = json.createObjectNode();
        root.put("format", "turboism.runtime.config");
        root.put("schemaVersion", 1);
        root.put("optimizationDefaultsVersion", 1);
        root.put("worktreeId", "turboism-runtime");
        root.putArray("pluginDirs").add("plugins");
        var launcher = root.putObject("launcher");
        launcher.put("cubismJvm", "graalvm");
        launcher.put("mesaGlThread", false);
        Files.writeString(
                home.resolve("config.json"),
                json.writerWithDefaultPrettyPrinter().writeValueAsString(root));

        var loaded = PerformanceSettingsConfig.load(home, ignored -> {});
        assertEquals("true", loaded.value(Field.MESA_GL_THREAD).value());
        var persisted = json.readTree(home.resolve("config.json").toFile());
        assertEquals(
                PerformanceSettingsConfig.CURRENT_OPTIMIZATION_DEFAULTS_VERSION,
                persisted
                        .path(PerformanceSettingsConfig.OPTIMIZATION_DEFAULTS_VERSION_FIELD)
                        .asInt());
        assertTrue(persisted.path("launcher").path("mesaGlThread").booleanValue());
    }

    @Test
    void migratesVersion1ExplicitOffPresetWithoutEnablingMesaGlThread() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var root = json.createObjectNode();
        root.put("format", "turboism.runtime.config");
        root.put("schemaVersion", 1);
        root.put("optimizationDefaultsVersion", 1);
        root.put("worktreeId", "turboism-runtime");
        var launcher = root.putObject("launcher");
        launcher.put("cubismJvm", "graalvm");
        for (Optimization optimization : OptimizationCatalog.persisted()) {
            var parent = optimization.access() == Optimization.Access.PERSISTED_LAUNCHER ? launcher : root;
            parent.put(optimization.key(), false);
        }
        Files.writeString(
                home.resolve("config.json"),
                json.writerWithDefaultPrettyPrinter().writeValueAsString(root));

        var loaded = PerformanceSettingsConfig.load(home, ignored -> {});
        for (Optimization optimization : OptimizationCatalog.persisted()) {
            assertEquals("false", loaded.value(Field.forKey(optimization.key())).value(), optimization.key());
        }
        var persisted = json.readTree(home.resolve("config.json").toFile());
        assertEquals(
                PerformanceSettingsConfig.CURRENT_OPTIMIZATION_DEFAULTS_VERSION,
                persisted
                        .path(PerformanceSettingsConfig.OPTIMIZATION_DEFAULTS_VERSION_FIELD)
                        .asInt());
        assertFalse(persisted.path("launcher").path("mesaGlThread").booleanValue());
        try (var service = new OptimizationSettingsFileService(home)) {
            assertEquals(OptimizationPreset.OFF, OptimizationPreset.detect(service));
        }
    }

    @Test
    void preservesVersion2ExplicitMesaGlThreadOptOutIdempotently() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var root = json.createObjectNode();
        root.put("format", "turboism.runtime.config");
        root.put("schemaVersion", 1);
        root.put("optimizationDefaultsVersion", PerformanceSettingsConfig.CURRENT_OPTIMIZATION_DEFAULTS_VERSION);
        root.put("worktreeId", "turboism-runtime");
        root.putArray("pluginDirs").add("plugins");
        root.put("atlasLoading", true);
        var launcher = root.putObject("launcher");
        launcher.put("cubismJvm", "graalvm");
        launcher.put("modelUpdateSkip", true);
        launcher.put("mesaGlThread", false);
        Files.writeString(
                home.resolve("config.json"),
                json.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        byte[] before = Files.readAllBytes(home.resolve("config.json"));

        var loaded = PerformanceSettingsConfig.load(home, ignored -> {});
        assertEquals("false", loaded.value(Field.MESA_GL_THREAD).value());
        assertEquals("global-config", loaded.value(Field.MESA_GL_THREAD).source());
        assertArrayEquals(before, Files.readAllBytes(home.resolve("config.json")));
    }

    @Test
    void defaultsAndFreshSaveStampVersion2AndPreserveMesaGlThreadOptOut() throws Exception {
        assertEquals(
                PerformanceSettingsConfig.CURRENT_OPTIMIZATION_DEFAULTS_VERSION,
                RuntimeConfigRepository.defaults()
                        .path(PerformanceSettingsConfig.OPTIMIZATION_DEFAULTS_VERSION_FIELD)
                        .asInt());
        var repository = new RuntimeConfigRepository(home, ignored -> {});
        PerformanceSettingsConfig.save(repository, Field.MESA_GL_THREAD, "false");
        var loaded = PerformanceSettingsConfig.load(home, ignored -> {});
        assertEquals("false", loaded.value(Field.MESA_GL_THREAD).value());
        assertEquals(
                PerformanceSettingsConfig.CURRENT_OPTIMIZATION_DEFAULTS_VERSION,
                repository
                        .read()
                        .path(PerformanceSettingsConfig.OPTIMIZATION_DEFAULTS_VERSION_FIELD)
                        .asInt());
    }
}

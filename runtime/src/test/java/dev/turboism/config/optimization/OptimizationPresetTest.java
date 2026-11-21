package dev.turboism.config.optimization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.config.OptimizationSettingsFileService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class OptimizationPresetTest {

    private static final List<String> BUNDLES = List.of(
            "messages.properties",
            "messages_en.properties",
            "messages_ja.properties",
            "messages_ko.properties",
            "messages_zh_Hans.properties",
            "messages_zh_Hant.properties");

    private static final Set<String> TRADE_OFF_KEYS = Set.of(
            OptimizationCatalog.INCREMENTAL_UPDATE.key(),
            OptimizationCatalog.INCREMENTAL_UPDATE_AFFECTERS.key(),
            OptimizationCatalog.ASYNC_READBACK.key(),
            OptimizationCatalog.MESA_GL_THREAD.key());

    @TempDir
    Path home;

    @Test
    void balancedEnablesTheThirtyFiveUniversalOptimizationsAndLeavesFourTradeOffsOff() {
        final Map<String, Boolean> balanced = OptimizationPreset.BALANCED.flags();
        assertEquals(39, balanced.size());
        assertEquals(
                35, balanced.values().stream().filter(Boolean::booleanValue).count());
        for (final Optimization optimization : OptimizationCatalog.persisted()) {
            final boolean expected = !TRADE_OFF_KEYS.contains(optimization.key());
            assertEquals(expected, balanced.get(optimization.key()), optimization.key());
        }

        final Map<String, Boolean> off = OptimizationPreset.OFF.flags();
        assertEquals(39, off.size());
        assertTrue(off.values().stream().noneMatch(Boolean::booleanValue));

        final Map<String, Boolean> heavy = OptimizationPreset.HEAVY_EDITING.flags();
        assertEquals(39, heavy.size());
        assertTrue(heavy.values().stream().allMatch(Boolean::booleanValue));
    }

    @Test
    void detectsOffBalancedHeavyEditingAndFallsBackToCustomOnAnySingleFlagDeviation() {
        assertEquals(OptimizationPreset.OFF, OptimizationPreset.detect(OptimizationPreset.OFF.flags()));
        assertEquals(OptimizationPreset.BALANCED, OptimizationPreset.detect(OptimizationPreset.BALANCED.flags()));
        assertEquals(
                OptimizationPreset.HEAVY_EDITING, OptimizationPreset.detect(OptimizationPreset.HEAVY_EDITING.flags()));

        for (final Optimization optimization : OptimizationCatalog.persisted()) {
            final Map<String, Boolean> mutatedBalanced = new LinkedHashMap<>(OptimizationPreset.BALANCED.flags());
            mutatedBalanced.put(optimization.key(), !mutatedBalanced.get(optimization.key()));
            assertEquals(
                    OptimizationPreset.CUSTOM,
                    OptimizationPreset.detect(mutatedBalanced),
                    "Flipping " + optimization.key() + " from BALANCED must resolve to CUSTOM");
        }
    }

    @Test
    void customPresetRefusesFixedFlagMaterializationAndLookupRejectsUnknownValues() {
        assertFalse(OptimizationPreset.CUSTOM.appliesFlags());
        assertEquals(Optional.empty(), OptimizationPreset.CUSTOM.targetFlag(OptimizationCatalog.MODEL_UPDATE_SKIP));
        assertThrows(IllegalStateException.class, OptimizationPreset.CUSTOM::flags);
        assertThrows(
                IllegalStateException.class,
                () -> OptimizationPreset.CUSTOM.apply(
                        dev.turboism.internal.core.OptimizationSettingsService.unavailable()));
        assertThrows(IllegalArgumentException.class, () -> OptimizationPreset.fromConfigValue("unknown"));
        assertThrows(IllegalArgumentException.class, () -> OptimizationPreset.fromConfigValue(null));
        assertThrows(
                IllegalArgumentException.class,
                () -> OptimizationPreset.BALANCED.targetFlag(OptimizationCatalog.SORTING_INFO_CACHE));
    }

    @Test
    void appliesPresetsThroughFileServiceWithoutSchemaChanges() throws Exception {
        try (OptimizationSettingsFileService service = new OptimizationSettingsFileService(home)) {
            assertEquals(OptimizationPreset.CUSTOM, OptimizationPreset.detect(service));

            OptimizationPreset.BALANCED.apply(service);
            assertEquals(OptimizationPreset.BALANCED, OptimizationPreset.detect(service));
            assertTrue(service.enabled(OptimizationCatalog.MATRIX_SCRATCH.key()));
            assertTrue(service.enabled(OptimizationCatalog.WARP_POSITION_PROJECTION.key()));
            assertTrue(service.enabled(OptimizationCatalog.INPUT_PATH_ELISION.key()));
            assertTrue(service.enabled(OptimizationCatalog.EXTENDED_INTERPOLATION_SIBLING_ELISION.key()));
            assertFalse(service.enabled(OptimizationCatalog.INCREMENTAL_UPDATE.key()));
            assertFalse(service.enabled(OptimizationCatalog.ASYNC_READBACK.key()));
            assertFalse(service.enabled(OptimizationCatalog.MESA_GL_THREAD.key()));

            OptimizationPreset.HEAVY_EDITING.apply(service);
            assertEquals(OptimizationPreset.HEAVY_EDITING, OptimizationPreset.detect(service));
            assertTrue(service.enabled(OptimizationCatalog.INCREMENTAL_UPDATE.key()));
            assertTrue(service.enabled(OptimizationCatalog.ASYNC_READBACK.key()));

            OptimizationPreset.OFF.apply(service);
            assertEquals(OptimizationPreset.OFF, OptimizationPreset.detect(service));
        }
    }

    @Test
    void presetAndRecommendationKeysExistInAllSixLocalizationBundles() throws IOException {
        final List<String> requiredKeys = List.of(
                "settings.performance.preset",
                "settings.performance.preset.tooltip",
                "settings.performance.preset.off",
                "settings.performance.preset.balanced",
                "settings.performance.preset.heavy-editing",
                "settings.performance.preset.custom",
                "settings.performance.recommendation.tooltip");
        for (final String bundleName : BUNDLES) {
            final Properties bundle = loadBundle(bundleName);
            for (final String key : requiredKeys) {
                final String value = bundle.getProperty(key);
                assertNotNull(value, bundleName + " missing key " + key);
                assertFalse(value.isBlank(), bundleName + " blank key " + key);
            }
        }
    }

    private static Properties loadBundle(final String name) throws IOException {
        try (InputStream stream =
                OptimizationPresetTest.class.getClassLoader().getResourceAsStream("META-INF/turboism/i18n/" + name)) {
            assertNotNull(stream, name + " missing");
            final Properties properties = new Properties();
            properties.load(stream);
            return properties;
        }
    }
}

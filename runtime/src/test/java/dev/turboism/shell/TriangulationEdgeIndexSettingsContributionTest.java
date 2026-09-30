package dev.turboism.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.internal.core.TriangulationEdgeIndexSettingsService;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.ui.settings.SettingsControl;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** Contract tests for the triangulation edge-index preference and its Performance-tab toggle. */
class TriangulationEdgeIndexSettingsContributionTest {

    @Test
    void defaultIsEnabledWhenNothingWasPersisted() {
        assertTrue(TriangulationEdgeIndexSettingsService.DEFAULT_ENABLED);
        assertTrue(TriangulationEdgeIndexSettingsService.unavailable().read());
    }

    @Test
    void unavailableServiceRefusesToPersistInsteadOfReportingSuccess() {
        assertThrows(
                IllegalStateException.class,
                () -> TriangulationEdgeIndexSettingsService.unavailable().save(false));
    }

    @Test
    void contributionIsAToggleOnThePerformanceTab() {
        final AtomicBoolean stored = new AtomicBoolean(true);
        final TriangulationEdgeIndexSettingsService settings = new TriangulationEdgeIndexSettingsService() {
            @Override
            public boolean read() {
                return stored.get();
            }

            @Override
            public boolean save(final boolean value) {
                stored.set(value);
                return value;
            }
        };

        final var contribution = TriangulationEdgeIndexSettingsContribution.create(localization(), settings);

        assertEquals("triangulation-edge-index", contribution.id());
        assertEquals("performance", contribution.tab().id());
        assertTrue(
                contribution.control() instanceof SettingsControl.Toggle,
                "the preference must render as an independent checkbox");

        final SettingsControl.Toggle toggle = (SettingsControl.Toggle) contribution.control();
        assertTrue(Boolean.TRUE.equals(toggle.binding().read()), "the toggle reflects the persisted preference");
        toggle.binding().write(false);
        assertFalse(stored.get(), "writing the toggle persists the preference");
        assertFalse(Boolean.TRUE.equals(toggle.binding().read()), "the toggle reads back the persisted value");
    }

    @Test
    void everyBundleCarriesTheToggleLabel() {
        final String key = TriangulationEdgeIndexSettingsContribution.LABEL_KEY;
        final Map<String, String> labels = Map.of(
                "messages.properties", "Index mesh triangulation edge lookups",
                "messages_en.properties", "Index mesh triangulation edge lookups",
                "messages_ja.properties", "メッシュ三角分割の辺検索を索引化",
                "messages_ko.properties", "메시 삼각분할의 엣지 조회 인덱싱",
                "messages_zh_Hans.properties", "索引网格三角化的边查询",
                "messages_zh_Hant.properties", "將網格三角化的邊查詢索引化");
        for (final var label : labels.entrySet()) {
            final var path = java.nio.file.Path.of("src/main/resources/META-INF/turboism/i18n", label.getKey());
            try (final var reader = java.nio.file.Files.newBufferedReader(path)) {
                final var catalog = new java.util.Properties();
                catalog.load(reader);
                assertEquals(label.getValue(), catalog.getProperty(key),
                        label.getKey() + " must resolve the exact translated label");
            } catch (java.io.IOException failure) {
                throw new AssertionError("cannot read " + label.getKey(), failure);
            }
        }
    }

    private static PluginLocalization localization() {
        final Map<String, String> texts = Map.of(
                "settings.tab.performance",
                "Performance",
                TriangulationEdgeIndexSettingsContribution.LABEL_KEY,
                "Index mesh triangulation edge lookups");
        return new PluginLocalization() {
            @Override
            public String text(final String key) {
                return texts.getOrDefault(key, key);
            }

            @Override
            public String format(final String key, final Object... arguments) {
                return text(key);
            }

            @Override
            public boolean contains(final String key) {
                return texts.containsKey(key);
            }

            @Override
            public Locale locale() {
                return Locale.ENGLISH;
            }
        };
    }
}

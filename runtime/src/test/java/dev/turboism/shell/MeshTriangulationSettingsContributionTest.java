package dev.turboism.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.internal.core.MeshTriangulationSettingsService;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.ui.settings.SettingsControl;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** Contract tests for the mesh triangulation preference and its Performance-tab toggle. */
class MeshTriangulationSettingsContributionTest {

    @Test
    void defaultIsDisabledWhenNothingWasPersisted() {
        assertFalse(MeshTriangulationSettingsService.DEFAULT_ENABLED);
        assertFalse(MeshTriangulationSettingsService.unavailable().read());
    }

    @Test
    void unavailableServiceRefusesToPersistInsteadOfReportingSuccess() {
        assertThrows(
                IllegalStateException.class,
                () -> MeshTriangulationSettingsService.unavailable().save(false));
    }

    @Test
    void contributionIsAToggleOnThePerformanceTab() {
        final AtomicBoolean stored = new AtomicBoolean(true);
        final MeshTriangulationSettingsService settings = new MeshTriangulationSettingsService() {
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

        final var contribution = MeshTriangulationSettingsContribution.create(localization(), settings);

        assertEquals("mesh-triangulation-hash", contribution.id());
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

    private static PluginLocalization localization() {
        final Map<String, String> texts = Map.of(
                "settings.tab.performance",
                "Performance",
                MeshTriangulationSettingsContribution.LABEL_KEY,
                "Fix mesh triangulation hash degeneracy");
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

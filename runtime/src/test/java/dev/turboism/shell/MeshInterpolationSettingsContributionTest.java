package dev.turboism.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.internal.core.CubismJvmSettingsService;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.ui.settings.SettingsControl;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** Contract tests for the mesh interpolation preference and its Performance-tab toggle. */
class MeshInterpolationSettingsContributionTest {

    @Test
    void defaultIsDisabledWhenNothingWasPersisted() {
        assertFalse(CubismJvmSettingsService.unavailable().meshInterpolation());
    }

    @Test
    void unavailableServiceRefusesToPersistInsteadOfReportingSuccess() {
        assertThrows(
                IllegalStateException.class,
                () -> CubismJvmSettingsService.unavailable().saveMeshInterpolation(true));
    }

    @Test
    void failedSaveThrowsInsteadOfSilentSuccess() {
        final CubismJvmSettingsService refusingSettings = new CubismJvmSettingsService() {
            @Override
            public CubismJvm read() {
                return CubismJvm.BUNDLED;
            }

            @Override
            public CubismJvm save(CubismJvm value) {
                return value;
            }

            @Override
            public boolean meshInterpolation() {
                return false;
            }

            @Override
            public boolean saveMeshInterpolation(boolean value) {
                // Returns opposite of requested, simulating failed save
                return !value;
            }
        };

        final var contribution =
                CubismJvmSettingsContribution.createMeshInterpolationToggle(localization(), refusingSettings);
        final SettingsControl.Toggle toggle = (SettingsControl.Toggle) contribution.control();
        assertThrows(IllegalStateException.class, () -> toggle.binding().write(true));
    }

    @Test
    void contributionIsAToggleOnThePerformanceTab() {
        final AtomicBoolean stored = new AtomicBoolean(false);
        final CubismJvmSettingsService settings = new CubismJvmSettingsService() {
            @Override
            public CubismJvm read() {
                return CubismJvm.BUNDLED;
            }

            @Override
            public CubismJvm save(CubismJvm value) {
                return value;
            }

            @Override
            public boolean meshInterpolation() {
                return stored.get();
            }

            @Override
            public boolean saveMeshInterpolation(final boolean value) {
                stored.set(value);
                return value;
            }
        };

        final var contribution = CubismJvmSettingsContribution.createMeshInterpolationToggle(localization(), settings);

        assertEquals("mesh-interpolation", contribution.id());
        assertEquals("performance", contribution.tab().id());
        assertEquals(86, contribution.index().orElseThrow());
        assertTrue(
                contribution.control() instanceof SettingsControl.Toggle,
                "the preference must render as an independent checkbox");

        final SettingsControl.Toggle toggle = (SettingsControl.Toggle) contribution.control();
        assertFalse(Boolean.TRUE.equals(toggle.binding().read()), "the toggle reflects initial preference false");
        toggle.binding().write(true);
        assertTrue(stored.get(), "writing the toggle persists the preference");
        assertTrue(Boolean.TRUE.equals(toggle.binding().read()), "the toggle reads back the persisted value");
    }

    @Test
    void packagedTranslationsResolveTheRealToggleWithoutFallback() throws Exception {
        final String key = "settings.optimization.mesh-interpolation";
        for (String suffix : new String[] {"", "_en", "_ja", "_ko", "_zh_Hans", "_zh_Hant"}) {
            String resource = "/META-INF/turboism/i18n/messages" + suffix + ".properties";
            var texts = new java.util.Properties();
            try (var stream = MeshInterpolationSettingsContributionTest.class.getResourceAsStream(resource)) {
                org.junit.jupiter.api.Assertions.assertNotNull(stream, resource);
                texts.load(new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8));
            }
            String label = texts.getProperty(key);
            org.junit.jupiter.api.Assertions.assertNotNull(label, resource);
            assertFalse(label.isBlank(), resource);
            assertFalse(label.contains("\uFFFD"), resource);
            var contribution = CubismJvmSettingsContribution.createMeshInterpolationToggle(
                    localization(Map.of(
                            key, label, "settings.tab.performance", texts.getProperty("settings.tab.performance"))),
                    CubismJvmSettingsService.unavailable());
            assertEquals(label, contribution.control().label(), resource);
        }
    }

    private static PluginLocalization localization() {
        final Map<String, String> texts = Map.of(
                "settings.tab.performance",
                "Performance",
                "settings.optimization.mesh-interpolation",
                "Accelerate ArtMesh and deformer extended interpolation");
        return localization(texts);
    }

    private static PluginLocalization localization(final Map<String, String> texts) {
        return new PluginLocalization() {
            @Override
            public boolean contains(final String key) {
                return texts.containsKey(key);
            }

            @Override
            public Locale locale() {
                return Locale.ENGLISH;
            }

            @Override
            public String text(final String key) {
                final String text = texts.get(key);
                if (text == null) {
                    throw new IllegalArgumentException("Unknown key: " + key);
                }
                return text;
            }

            @Override
            public String format(final String key, final Object... arguments) {
                return String.format(locale(), text(key), arguments);
            }
        };
    }
}

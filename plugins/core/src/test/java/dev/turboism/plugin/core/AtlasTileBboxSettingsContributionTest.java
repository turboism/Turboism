package dev.turboism.plugin.core;

import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.ui.settings.SettingsControl;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Contract tests for the atlas tile-bbox preference and its Performance-tab toggle. */
class AtlasTileBboxSettingsContributionTest {

    @Test
    void defaultIsEnabledWhenNothingWasPersisted() {
        assertTrue(AtlasTileBboxSettingsService.DEFAULT_ENABLED);
        assertTrue(AtlasTileBboxSettingsService.unavailable().read());
    }

    @Test
    void unavailableServiceRefusesToPersistInsteadOfReportingSuccess() {
        assertThrows(IllegalStateException.class,
            () -> AtlasTileBboxSettingsService.unavailable().save(false));
    }

    @Test
    void contributionIsAToggleOnThePerformanceTab() {
        final AtomicBoolean stored = new AtomicBoolean(true);
        final AtlasTileBboxSettingsService settings = new AtlasTileBboxSettingsService() {
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

        final var contribution = AtlasTileBboxSettingsContribution.create(
            localization(), settings);

        assertEquals("atlas-tile-bbox", contribution.id());
        assertEquals("performance", contribution.tab().id());
        assertTrue(contribution.control() instanceof SettingsControl.Toggle,
            "the preference must render as an independent checkbox");

        final SettingsControl.Toggle toggle = (SettingsControl.Toggle) contribution.control();
        assertTrue(Boolean.TRUE.equals(toggle.binding().read()),
            "the toggle reflects the persisted preference");
        toggle.binding().write(false);
        assertFalse(stored.get(), "writing the toggle persists the preference");
        assertFalse(Boolean.TRUE.equals(toggle.binding().read()),
            "the toggle reads back the persisted value");
    }

    @Test
    void everyBundleCarriesTheToggleLabel() {
        final String key = AtlasTileBboxSettingsContribution.LABEL_KEY;
        for (final String bundle : new String[] {
            "messages.properties", "messages_en.properties", "messages_ja.properties",
            "messages_ko.properties", "messages_zh_Hans.properties", "messages_zh_Hant.properties",
        }) {
            final var path = java.nio.file.Path.of(
                "src/main/resources/META-INF/turboism/i18n", bundle);
            assertTrue(java.nio.file.Files.exists(path), "missing bundle: " + bundle);
            try {
                assertTrue(java.nio.file.Files.readString(path).contains(key),
                    bundle + " must define " + key);
            } catch (java.io.IOException failure) {
                throw new AssertionError("cannot read " + bundle, failure);
            }
        }
    }

    private static PluginLocalization localization() {
        final Map<String, String> texts = Map.of(
            "settings.tab.performance", "Performance",
            AtlasTileBboxSettingsContribution.LABEL_KEY,
            "Accelerate atlas image processing (tile-bounded)"
        );
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

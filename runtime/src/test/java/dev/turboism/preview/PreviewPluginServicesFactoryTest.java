package dev.turboism.preview;

import dev.turboism.sdk.i18n.PluginLocalization;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class PreviewPluginServicesFactoryTest {

    @Test
    void generatedSettingsTabUsesLocalizedPluginName() {
        assertEquals(
            "物理演算エディター",
            PreviewPluginServicesFactory.pluginDisplayName(
                "Physics Editor",
                localization(true, "物理演算エディター")
            )
        );
        assertEquals(
            "Physics Editor",
            PreviewPluginServicesFactory.pluginDisplayName(
                "Physics Editor",
                localization(false, "plugin.name")
            )
        );
    }

    private static PluginLocalization localization(
        final boolean containsName,
        final String pluginName
    ) {
        return new PluginLocalization() {
            @Override public Locale locale() { return Locale.JAPANESE; }
            @Override public String text(final String key) {
                return "plugin.name".equals(key) ? pluginName : key;
            }
            @Override public String format(final String key, final Object... arguments) {
                return text(key);
            }
            @Override public boolean contains(final String key) {
                return containsName && "plugin.name".equals(key);
            }
        };
    }
}

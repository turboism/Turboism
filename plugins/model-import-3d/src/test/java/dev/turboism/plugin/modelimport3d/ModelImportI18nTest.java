package dev.turboism.plugin.modelimport3d;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class ModelImportI18nTest {
    private static final List<String> BUNDLES = List.of(
            "messages.properties",
            "messages_en.properties",
            "messages_ja.properties",
            "messages_ko.properties",
            "messages_zh_Hans.properties",
            "messages_zh_Hant.properties");

    /** Keys the import windows and the progress window read at run time. */
    private static final List<String> REQUIRED_KEYS =
            List.of("dialog.preset.hint", "progress.mesh", "progress.writing", "preset.torus");

    @Test
    void everyBundleCarriesTheImportAndProgressKeys() throws Exception {
        for (String bundle : BUNDLES) {
            final Properties properties = load(bundle);
            for (String key : REQUIRED_KEYS) {
                assertTrue(properties.containsKey(key), bundle + " is missing " + key);
                assertFalse(properties.getProperty(key).isBlank(), bundle + " has a blank " + key);
            }
        }
    }

    @Test
    void solidTorusPresetIsNamedAsTheDonutOperatorsSee() throws Exception {
        for (String bundle : BUNDLES) {
            final String label = load(bundle).getProperty("preset.torus");
            assertTrue(
                    label.contains("Donut") || label.contains("甜甜圈") || label.contains("ドーナツ") || label.contains("도넛"),
                    bundle + " still calls the solid torus something else: " + label);
        }
        assertEquals("甜甜圈", load("messages_zh_Hans.properties").getProperty("preset.torus"));
    }

    private static Properties load(final String bundle) throws Exception {
        final String path = "META-INF/turboism/i18n/" + bundle;
        try (InputStream in = ModelImportI18nTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, "missing bundle " + path);
            final Properties properties = new Properties();
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return properties;
        }
    }
}

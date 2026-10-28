package dev.turboism.plugin.deformerbatchrename;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.test.action.RecordingActionRegistry;
import dev.turboism.test.menu.RecordingMenuRegistry;
import dev.turboism.test.plugin.FakePluginContext;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DeformerBatchRenamePluginTest {
    private static final Map<String, String> CATALOGS = Map.of(
            "messages", "META-INF/turboism/i18n/messages.properties",
            "en", "META-INF/turboism/i18n/messages_en.properties",
            "ja", "META-INF/turboism/i18n/messages_ja.properties",
            "ko", "META-INF/turboism/i18n/messages_ko.properties",
            "zh_Hans", "META-INF/turboism/i18n/messages_zh_Hans.properties",
            "zh_Hant", "META-INF/turboism/i18n/messages_zh_Hant.properties");

    @Test
    void registersLocalizedMenuAndDisposesContributions() throws Exception {
        var actions = new RecordingActionRegistry();
        var menus = new RecordingMenuRegistry();
        var context = FakePluginContext.builder()
                .actions(actions)
                .menus(menus)
                .localization(new PluginLocalization() {
                    @Override
                    public Locale locale() {
                        return Locale.SIMPLIFIED_CHINESE;
                    }

                    @Override
                    public String text(String key) {
                        return "变形器批量重命名";
                    }

                    @Override
                    public String format(String key, Object... args) {
                        return text(key);
                    }

                    @Override
                    public boolean contains(String key) {
                        return true;
                    }
                })
                .build();
        var plugin = new DeformerBatchRenamePlugin();
        plugin.init(context);
        plugin.enable();
        assertEquals(1, actions.actions().size());
        assertEquals(
                "变形器批量重命名", actions.require(DeformerBatchRenamePlugin.ACTION_ID).label());
        assertEquals(1, menus.contributions().size());
        assertEquals("Turboism/变形器批量重命名", menus.contributions().get(0).menuPath());
        assertEquals(
                DeformerBatchRenamePlugin.ACTION_ID,
                menus.contributions().get(0).actionId());
        plugin.disable();
        context.disposableScope().close();
        assertTrue(actions.actions().isEmpty());
        assertTrue(menus.contributions().isEmpty());
    }

    @Test
    void everyLocaleCatalogMatchesBaselineAndHasNonBlankValues() throws IOException {
        Set<String> baseline = readBaselineKeys();
        assertFalse(baseline.isEmpty());
        for (Map.Entry<String, String> entry : CATALOGS.entrySet()) {
            Properties props = readCatalog(entry.getValue());
            assertEquals(baseline, props.stringPropertyNames(), "Key mismatch in " + entry.getKey());
            for (String key : props.stringPropertyNames()) {
                String value = props.getProperty(key);
                assertNotNull(value, "Null value for " + key + " in " + entry.getKey());
                assertFalse(value.isBlank(), "Blank value for " + key + " in " + entry.getKey());
            }
        }
    }

    private static Properties readCatalog(String path) throws IOException {
        Properties props = new Properties();
        try (InputStream stream =
                DeformerBatchRenamePluginTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(stream, "Missing resource " + path);
            props.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
        return props;
    }

    private static Set<String> readBaselineKeys() throws IOException {
        Set<String> keys = new HashSet<>();
        try (InputStream stream = DeformerBatchRenamePluginTest.class
                .getClassLoader()
                .getResourceAsStream("META-INF/turboism/i18n/baseline-keys.txt")) {
            assertNotNull(stream, "Missing baseline-keys.txt");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                        keys.add(trimmed);
                    }
                }
            }
        }
        return keys;
    }
}

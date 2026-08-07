package dev.turboism.plugin.core;

import dev.turboism.sdk.i18n.PluginLocalization;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreWindowsTest {

    private static final PluginLocalization I18N = new PluginLocalization() {
        @Override public Locale locale() { return Locale.ENGLISH; }
        @Override public String text(final String key) {
            return switch (key) {
                case "about.thanks" -> "Special thanks to @希娜莉丝";
                default -> key;
            };
        }
        @Override public String format(final String key, final Object... arguments) {
            return "Version " + arguments[0];
        }
        @Override public boolean contains(final String key) { return true; }
    };

    @Test
    void aboutHtml_containsNameVersionAndThanks() {
        final String html = CoreWindows.aboutHtml(I18N, "0.1.0");
        assertTrue(html.contains("Turboism"));
        assertTrue(html.contains("Version 0.1.0"));
        assertTrue(html.contains("@希娜莉丝"));
    }

    @Test
    void frameworkVersion_readsPackagedResource() {
        final String version = CoreWindows.frameworkVersion();
        assertTrue(version.matches("\\d+\\.\\d+\\.\\d+"), "framework version: " + version);
        assertEquals("0.1.0", version);
    }
}

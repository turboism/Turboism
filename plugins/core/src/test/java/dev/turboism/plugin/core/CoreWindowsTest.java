package dev.turboism.plugin.core;

import dev.turboism.sdk.i18n.PluginLocalization;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreWindowsTest {

    private static final PluginLocalization I18N = new PluginLocalization() {
        @Override public Locale locale() { return Locale.ENGLISH; }
        @Override public String text(final String key) {
            return switch (key) {
                case "about.thanks" -> "Thanks to<br>@希娜莉丝 and all contributors";
                default -> key;
            };
        }
        @Override public String format(final String key, final Object... arguments) {
            return "Version " + arguments[0];
        }
        @Override public boolean contains(final String key) { return true; }
    };

    @Test
    void aboutHtml_containsVersionTaglineThanksAndLogoImage() {
        final String html = CoreWindows.aboutHtml(I18N, "0.42.0");
        assertTrue(html.contains("0.42.0"));
        assertTrue(html.contains("Live2D Cubism Extension Framework"));
        assertTrue(html.contains("@希娜莉丝"));
        assertTrue(html.contains("<img src=\"file:"));
        assertTrue(html.contains("alt=\"Turboism\""));
    }

    @Test
    void gradientLogoPng_isRenderedAndReadable() throws Exception {
        final java.nio.file.Path logo = CoreWindows.gradientLogoPng();
        assertTrue(Files.exists(logo));
        assertTrue(Files.size(logo) > 0);
        assertEquals("Turboism", CoreWindows.ABOUT_LOGO_TEXT);
    }

    @Test
    void frameworkVersion_readsPackagedResource() {
        final String version = CoreWindows.frameworkVersion();
        assertTrue(version.matches("\\d+\\.\\d+\\.\\d+"), "framework version: " + version);
        assertEquals("0.42.0", version);
    }
}

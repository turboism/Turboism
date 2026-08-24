package dev.turboism.plugin.core;

import dev.turboism.sdk.i18n.PluginLocalization;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.plugin.core.CubismJvmSettingsService.CubismJvm;

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
    void cubismJvmContributionTargetsPerformanceAndKeepsGraalVmFirst() {
        final var contribution = CubismJvmSettingsContribution.create(
            I18N,
            cubismJvm(CubismJvm.GRAALVM, true)
        );
        final var choice = (dev.turboism.sdk.ui.settings.SettingsControl.Choice) contribution.control();

        assertEquals("performance", contribution.tab().id());
        assertEquals("graalvm", choice.options().get(0).value());
        assertEquals("graalvm", choice.binding().read());
    }

    @Test
    void unavailableGraalVmInitialValueFallsBackToBundled() {
        assertEquals(
            CubismJvm.BUNDLED,
            CubismJvmSettingsContribution.acceptedInitial(
                cubismJvm(CubismJvm.GRAALVM, false)
            )
        );
        assertEquals(
            CubismJvm.GRAALVM,
            CubismJvmSettingsContribution.acceptedInitial(
                cubismJvm(CubismJvm.GRAALVM, true)
            )
        );
    }

    private static CubismJvmSettingsService cubismJvm(
        final CubismJvm value,
        final boolean available
    ) {
        return new CubismJvmSettingsService() {
            @Override public CubismJvm read() { return value; }
            @Override public CubismJvm save(final CubismJvm next) { return next; }
            @Override public boolean graalVmAvailable() { return available; }
        };
    }

    @Test
    void graalVmDownloadUriUsesTheOfficialDownloadPage() {
        assertEquals(
            "https://www.graalvm.org/downloads/",
            CubismJvmSettingsService.GRAALVM_DOWNLOAD_URI.toString()
        );
    }

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

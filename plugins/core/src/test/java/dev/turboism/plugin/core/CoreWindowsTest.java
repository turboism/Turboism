package dev.turboism.plugin.core;

import dev.turboism.sdk.i18n.PluginLocalization;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void missingGraalVmOffersManagedInstallAndSelectsItOnlyAfterSuccess() throws Exception {
        final CubismJvm[] saved = {CubismJvm.BUNDLED};
        final CubismJvmSettingsService service = new CubismJvmSettingsService() {
            @Override public CubismJvm read() { return saved[0]; }
            @Override public CubismJvm save(final CubismJvm next) { return saved[0] = next; }
            @Override public boolean graalVmAvailable() { return false; }
            @Override public ManagedRuntimeOperation installManagedRuntime() {
                final ManagedRuntimeStatus ready = new ManagedRuntimeStatus(
                    ManagedRuntimeState.READY,
                    MANAGED_GRAAL_VERSION,
                    MANAGED_JAVA_VERSION,
                    Optional.of(java.nio.file.Path.of("managed/bin/java.exe")),
                    1L,
                    1L,
                    "",
                    "ready"
                );
                return new ManagedRuntimeOperation() {
                    @Override public ManagedRuntimeStatus status() { return ready; }
                    @Override public java.util.concurrent.CompletionStage<ManagedRuntimeStatus> completion() {
                        return CompletableFuture.completedFuture(ready);
                    }
                    @Override public boolean cancel() { return false; }
                };
            }
        };
        final var contribution = CubismJvmSettingsContribution.create(I18N, service);
        final var choice = (dev.turboism.sdk.ui.settings.SettingsControl.Choice) contribution.control();

        final var decision = choice.validator().validate("bundled", "graalvm");
        assertFalse(decision.accepted());
        assertTrue(decision.action().isPresent());
        assertTrue(decision.link().isPresent());
        assertEquals(CubismJvm.BUNDLED, saved[0]);

        final var result = decision.action().orElseThrow().action().start().completion()
            .toCompletableFuture().get();
        assertTrue(result.succeeded());
        assertEquals(CubismJvm.GRAALVM, saved[0]);
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
        assertEquals("0.43.1", version);
    }
}

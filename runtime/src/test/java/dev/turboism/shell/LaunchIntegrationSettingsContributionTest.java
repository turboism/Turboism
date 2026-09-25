package dev.turboism.shell;

import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.ui.settings.SettingsContribution;
import dev.turboism.sdk.ui.settings.SettingsControl;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Contract tests for the Startup-tab official BAT launch integration toggle. */
class LaunchIntegrationSettingsContributionTest {

    @Test
    void toggleMirrorsServiceStateOnTheStartupTab() throws IOException {
        final Path home = Files.createTempDirectory("turboism-bat-home");
        final AtomicBoolean applied = new AtomicBoolean();
        final BatLaunchIntegrationService service = new BatLaunchIntegrationService(
            home, "Windows 11", command -> {
                applied.set(true);
                return new BatLaunchIntegrationService.InvocationResult(0, "");
            });

        final SettingsContribution contribution = LaunchIntegrationSettingsContribution.create(
            localization(), service);

        assertEquals(LaunchIntegrationSettingsContribution.CONTRIBUTION_ID, contribution.id());
        assertEquals("startup", contribution.tab().id());
        assertEquals(45, contribution.index().getAsInt());
        assertTrue(contribution.control() instanceof SettingsControl.Toggle,
            "the integration must render as an independent checkbox");
        final SettingsControl.Toggle toggle = (SettingsControl.Toggle) contribution.control();
        assertEquals("launch.batIntegration", toggle.id());
        assertEquals("Load Turboism from existing Cubism shortcuts", toggle.label());

        assertFalse(Boolean.TRUE.equals(toggle.binding().read()),
            "a missing state file mirrors 'not integrated'");
        toggle.binding().write(true);
        assertTrue(applied.get(), "writing the toggle delegates to the configurator service");
    }

    @Test
    void noteExplainsElevationAndRestartEffect() {
        final SettingsContribution note = LaunchIntegrationSettingsContribution.createNote(localization());
        assertEquals(LaunchIntegrationSettingsContribution.CONTRIBUTION_ID + ".note", note.id());
        assertEquals("startup", note.tab().id());
        assertEquals(46, note.index().getAsInt());
        assertTrue(note.control() instanceof SettingsControl.Note,
            "the annotation must render as a read-only note");
        assertEquals("Applies after the Editor restarts",
            ((SettingsControl.Note) note.control()).label());
    }

    @Test
    void everyBundleCarriesTheLaunchIntegrationKeys() {
        for (final String key : new String[] {
            LaunchIntegrationSettingsContribution.TOGGLE_LABEL_KEY,
            LaunchIntegrationSettingsContribution.NOTE_LABEL_KEY,
        }) {
            for (final String bundle : new String[] {
                "messages.properties", "messages_en.properties", "messages_ja.properties",
                "messages_ko.properties", "messages_zh_Hans.properties", "messages_zh_Hant.properties",
            }) {
                final Path path = Path.of("src/main/resources/META-INF/turboism/i18n", bundle);
                assertTrue(Files.exists(path), "missing bundle: " + bundle);
                try {
                    assertTrue(Files.readString(path).contains(key),
                        bundle + " must define " + key);
                } catch (final IOException failure) {
                    throw new AssertionError("cannot read " + bundle, failure);
                }
            }
        }
    }

    private static PluginLocalization localization() {
        final Map<String, String> texts = Map.of(
            "settings.tab.startup", "Startup",
            LaunchIntegrationSettingsContribution.TOGGLE_LABEL_KEY,
            "Load Turboism from existing Cubism shortcuts",
            LaunchIntegrationSettingsContribution.NOTE_LABEL_KEY,
            "Applies after the Editor restarts"
        );
        return new PluginLocalization() {
            @Override public String text(final String key) {
                return texts.getOrDefault(key, key);
            }
            @Override public String format(final String key, final Object... arguments) {
                return text(key);
            }
            @Override public boolean contains(final String key) {
                return texts.containsKey(key);
            }
            @Override public Locale locale() {
                return Locale.ENGLISH;
            }
        };
    }
}

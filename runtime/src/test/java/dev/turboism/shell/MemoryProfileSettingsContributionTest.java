package dev.turboism.shell;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.internal.core.CubismJvmSettingsService;
import dev.turboism.internal.core.CubismJvmSettingsService.MemoryProfile;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.ui.settings.SettingsControl;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class MemoryProfileSettingsContributionTest {

    private static class Settings implements CubismJvmSettingsService {
        final AtomicReference<MemoryProfile> stored = new AtomicReference<>(MemoryProfile.SYSTEM);

        @Override
        public CubismJvm read() {
            return CubismJvm.BUNDLED;
        }

        @Override
        public CubismJvm save(CubismJvm value) {
            return value;
        }

        @Override
        public MemoryProfile memoryProfile() {
            return stored.get();
        }

        @Override
        public MemoryProfile saveMemoryProfile(MemoryProfile value) {
            stored.set(value);
            return value;
        }
    }

    @Test
    void choiceExposesEveryTierAndPersistsSelections() {
        Settings settings = new Settings();
        var contribution = CubismJvmSettingsContribution.createMemoryProfile(i18n(), settings);
        assertEquals("cubism-memory-profile", contribution.id());
        assertEquals("performance", contribution.tab().id());
        var choice = assertInstanceOf(SettingsControl.Choice.class, contribution.control());
        assertEquals(
                java.util.List.of("system", "balanced4g", "balanced4gFastSoft"),
                choice.options().stream().map(SettingsControl.Option::value).toList());
        assertEquals("system", choice.binding().read());
        choice.binding().write("balanced4gFastSoft");
        assertEquals(MemoryProfile.BALANCED_4G_FAST_SOFT, settings.stored.get());
        choice.binding().write("system");
        assertEquals(MemoryProfile.SYSTEM, settings.stored.get());
    }

    @Test
    void labelStatesTheRestartRequirement() {
        var choice = (SettingsControl.Choice) CubismJvmSettingsContribution.createMemoryProfile(i18n(), new Settings())
                .control();
        assertTrue(
                choice.label().contains("settings.locale.restart-required"),
                "the control label carries the shared restart-required wording");
    }

    @Test
    void noteExplainsCompositionAndExplicitXmxPrecedence() {
        var contribution = CubismJvmSettingsContribution.createMemoryProfileNote(i18n());
        assertEquals("cubism-memory-profile-note", contribution.id());
        assertEquals("performance", contribution.tab().id());
        var note = assertInstanceOf(SettingsControl.Note.class, contribution.control());
        assertEquals("settings.cubism-jvm.memory-profile-note", note.label());
        for (String bundle : new String[] {
            "messages.properties",
            "messages_en.properties",
            "messages_ja.properties",
            "messages_ko.properties",
            "messages_zh_Hans.properties",
            "messages_zh_Hant.properties"
        }) {
            try {
                String text = Files.readString(Path.of("src/main/resources/META-INF/turboism/i18n", bundle));
                String noteText = text.lines()
                        .filter(line -> line.startsWith("settings.cubism-jvm.memory-profile-note="))
                        .findFirst()
                        .orElseThrow();
                String value = noteText.substring(noteText.indexOf('=') + 1);
                assertTrue(value.contains("-Xmx"), bundle);
                assertTrue(value.toLowerCase(Locale.ROOT).contains("zgc"), bundle);
            } catch (java.io.IOException failure) {
                throw new AssertionError(failure);
            }
        }
    }

    @Test
    void everySupportedLocaleDefinesEveryMemoryProfileKeyOnce() throws Exception {
        String[] keys = {
            "settings.cubism-jvm.memory-profile=",
            "settings.cubism-jvm.memory-profile.system=",
            "settings.cubism-jvm.memory-profile.balanced4g=",
            "settings.cubism-jvm.memory-profile.balanced4g-fast-soft=",
            "settings.cubism-jvm.memory-profile-note="
        };
        for (String bundle : new String[] {
            "messages.properties",
            "messages_en.properties",
            "messages_ja.properties",
            "messages_ko.properties",
            "messages_zh_Hans.properties",
            "messages_zh_Hant.properties"
        }) {
            String text = Files.readString(Path.of("src/main/resources/META-INF/turboism/i18n", bundle));
            for (String key : keys) {
                assertEquals(
                        1L, text.lines().filter(line -> line.startsWith(key)).count(), bundle + " " + key);
            }
        }
    }

    private static PluginLocalization i18n() {
        return new PluginLocalization() {
            @Override
            public String text(String key) {
                return key;
            }

            @Override
            public String format(String key, Object... arguments) {
                return key;
            }

            @Override
            public boolean contains(String key) {
                return true;
            }

            @Override
            public Locale locale() {
                return Locale.ENGLISH;
            }
        };
    }
}

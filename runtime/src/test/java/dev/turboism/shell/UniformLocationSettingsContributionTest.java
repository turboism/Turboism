package dev.turboism.shell;

import dev.turboism.adapter.cubism.optimization.uniform.UniformLocationHookBridge;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.ui.settings.SettingsControl;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UniformLocationSettingsContributionTest {
    private static final String FLAG = UniformLocationHookBridge.ENABLE_PROPERTY;
    private final String original = System.getProperty(FLAG);

    @AfterEach void restore() {
        if (original == null) System.clearProperty(FLAG); else System.setProperty(FLAG, original);
    }

    private static class Settings implements CubismJvmSettingsService {
        final AtomicBoolean stored = new AtomicBoolean(true);
        @Override public CubismJvm read() { return CubismJvm.BUNDLED; }
        @Override public CubismJvm save(CubismJvm value) { return value; }
        @Override public Optional<Path> graalVmJava() { return Optional.empty(); }
        @Override public boolean uniformLocationCache() { return stored.get(); }
        @Override public boolean saveUniformLocationCache(boolean value) {
            stored.set(value); return value;
        }
    }

    @Test void checkboxDefaultsOnAndPersistsBeforeApplyingLiveSwitch() {
        Settings settings = new Settings();
        var contribution = CubismJvmSettingsContribution.createUniformLocationCacheToggle(i18n(), settings);
        assertEquals("uniform-location-cache", contribution.id());
        assertEquals("performance", contribution.tab().id());
        var toggle = assertInstanceOf(SettingsControl.Toggle.class, contribution.control());
        assertEquals(Boolean.TRUE, toggle.binding().read());
        toggle.binding().write(false);
        assertFalse(settings.stored.get());
        assertEquals("false", System.getProperty(FLAG));
        toggle.binding().write(true);
        assertTrue(settings.stored.get());
        assertNull(System.getProperty(FLAG), "true uses the runtime's default-on policy");
    }

    @Test void failedPersistenceDoesNotChangeRuntimeFlag() {
        Settings settings = new Settings() {
            @Override public boolean saveUniformLocationCache(boolean value) {
                throw new IllegalStateException("simulated storage failure");
            }
        };
        System.setProperty(FLAG, "true");
        var toggle = (SettingsControl.Toggle) CubismJvmSettingsContribution
            .createUniformLocationCacheToggle(i18n(), settings).control();
        assertThrows(IllegalStateException.class, () -> toggle.binding().write(false));
        assertEquals("true", System.getProperty(FLAG));
        assertTrue(settings.stored.get());
    }

    @Test void rejectedSaveDoesNotPretendTheFlagChanged() {
        Settings settings = new Settings() {
            @Override public boolean saveUniformLocationCache(boolean value) { return !value; }
        };
        System.setProperty(FLAG, "false");
        var toggle = (SettingsControl.Toggle) CubismJvmSettingsContribution
            .createUniformLocationCacheToggle(i18n(), settings).control();
        assertThrows(IllegalStateException.class, () -> toggle.binding().write(true));
        assertEquals("false", System.getProperty(FLAG));
    }

    @Test void experimentalIncrementalToggleRequiresExplicitTrueWithoutAffectingUniform() {
        String key = dev.turboism.adapter.cubism.optimization.modelupdate.incremental.IncrementalUpdateBridge.ENABLE_PROPERTY;
        String previousIncremental = System.getProperty(key);
        AtomicBoolean requested = new AtomicBoolean(false);
        Settings settings = new Settings() {
            @Override public boolean incrementalUpdate() { return requested.get(); }
            @Override public boolean saveIncrementalUpdate(boolean value) { requested.set(value); return value; }
        };
        try {
            System.clearProperty(key); System.clearProperty(FLAG);
            var toggle = (SettingsControl.Toggle) CubismJvmSettingsContribution.createIncrementalUpdateToggle(i18n(), settings).control();
            assertEquals(Boolean.FALSE, toggle.binding().read());
            toggle.binding().write(true);
            assertEquals("true", System.getProperty(key));
            assertNull(System.getProperty(FLAG));
            toggle.binding().write(false);
            assertNull(System.getProperty(key));
            assertFalse(requested.get());
        } finally {
            if (previousIncremental == null) System.clearProperty(key); else System.setProperty(key, previousIncremental);
        }
    }

    @Test void everySupportedLocaleHasExactlyOneLabel() throws Exception {
        for (String bundle : new String[] {"messages.properties", "messages_en.properties",
                "messages_ja.properties", "messages_ko.properties", "messages_zh_Hans.properties",
                "messages_zh_Hant.properties"}) {
            String text = Files.readString(Path.of("src/main/resources/META-INF/turboism/i18n", bundle));
            assertEquals(1L, text.lines().filter(line -> line.startsWith(
                "settings.optimization.uniform-location-cache=")).count(), bundle);
        }
    }

    private static PluginLocalization i18n() {
        return new PluginLocalization() {
            @Override public String text(String key) { return key; }
            @Override public String format(String key, Object... arguments) { return key; }
            @Override public boolean contains(String key) { return true; }
            @Override public Locale locale() { return Locale.ENGLISH; }
        };
    }
}

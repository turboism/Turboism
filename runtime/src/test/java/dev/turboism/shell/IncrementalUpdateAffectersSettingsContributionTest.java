package dev.turboism.shell;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.adapter.cubism.optimization.modelupdate.incremental.IncrementalUpdateBridge;
import dev.turboism.internal.core.CubismJvmSettingsService;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.ui.settings.SettingsControl;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class IncrementalUpdateAffectersSettingsContributionTest {
    private final String original = System.getProperty(IncrementalUpdateBridge.AFFECTER_PROPERTY);

    @AfterEach
    void restore() {
        if (original == null) System.clearProperty(IncrementalUpdateBridge.AFFECTER_PROPERTY);
        else System.setProperty(IncrementalUpdateBridge.AFFECTER_PROPERTY, original);
        IncrementalUpdateBridge.setAffecterConfiguredDefault(false);
    }

    private static class Settings implements CubismJvmSettingsService {
        final AtomicBoolean stored = new AtomicBoolean(true);

        @Override
        public CubismJvm read() {
            return CubismJvm.BUNDLED;
        }

        @Override
        public CubismJvm save(CubismJvm value) {
            return value;
        }

        @Override
        public Optional<Path> graalVmJava() {
            return Optional.empty();
        }

        @Override
        public boolean incrementalUpdateAffecters() {
            return stored.get();
        }

        @Override
        public boolean saveIncrementalUpdateAffecters(boolean value) {
            stored.set(value);
            return value;
        }
    }

    @Test
    void toggleSitsAfterIncrementalUpdateAndPersists() {
        Settings settings = new Settings();
        var contribution = CubismJvmSettingsContribution.createIncrementalUpdateAffectersToggle(i18n(), settings);
        assertEquals("incremental-update-affecters", contribution.id());
        assertEquals("performance", contribution.tab().id());
        assertEquals(OptionalInt.of(73), contribution.index());
        var toggle = assertInstanceOf(SettingsControl.Toggle.class, contribution.control());
        assertEquals("settings.optimization.incremental-update-affecters", toggle.label());
        assertEquals(Boolean.TRUE, toggle.binding().read());
        toggle.binding().write(false);
        assertFalse(settings.stored.get());
    }

    @Test
    void rejectedPersistenceDoesNotPretendThePreferenceChanged() {
        Settings settings = new Settings() {
            @Override
            public boolean saveIncrementalUpdateAffecters(boolean value) {
                return !value;
            }
        };
        var toggle = (SettingsControl.Toggle)
                CubismJvmSettingsContribution.createIncrementalUpdateAffectersToggle(i18n(), settings)
                        .control();
        assertThrows(IllegalStateException.class, () -> toggle.binding().write(false));
        assertTrue(settings.stored.get());
    }

    @Test
    void tooltipIsDeclaredForThePerformancePage() {
        assertEquals(
                Optional.of("settings.optimization.incremental-update-affecters.tooltip"),
                CubismJvmSettingsContribution.tooltipKey("incremental-update-affecters"));
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

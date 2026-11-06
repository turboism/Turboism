package dev.turboism.internal.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.internal.core.KeybindingService.Row;
import dev.turboism.internal.core.KeybindingService.Scope;
import dev.turboism.sdk.runtime.RuntimeSettingsService;
import java.util.List;
import org.junit.jupiter.api.Test;

class SettingsAndShellContractTest {

    @Test
    void unavailableOptimizationSettingsReportOffAndRefuseEverySave() {
        final OptimizationSettingsService settings = OptimizationSettingsService.unavailable();
        assertFalse(settings.isAvailable());
        for (final String key : List.of("modelUpdateSkip", "atlasCacheReuse", "meshTriangulationEdgeIndex")) {
            assertFalse(settings.enabled(key), key + " reads as off");
            assertThrows(IllegalStateException.class, () -> settings.setEnabled(key, true), key + " refuses to save");
        }
    }

    @Test
    void optimizationSettingsDefaultsOffAndAcceptsOneKey() {
        final java.util.Map<String, Boolean> stored = new java.util.HashMap<>();
        final OptimizationSettingsService settings = new OptimizationSettingsService() {
            @Override
            public boolean enabled(final String key) {
                return stored.getOrDefault(key, false);
            }

            @Override
            public void setEnabled(final String key, final boolean enabled) {
                stored.put(key, enabled);
            }
        };
        assertTrue(settings.isAvailable());
        assertFalse(settings.enabled("modelUpdateSkip"));
        settings.setEnabled("modelUpdateSkip", true);
        assertTrue(settings.enabled("modelUpdateSkip"));
    }

    @Test
    void unavailableKeybindingServiceIsInert() {
        final KeybindingService keys = KeybindingService.unavailable();
        assertEquals(List.of(), keys.snapshot());
        keys.bind("r", "ctrl A");
        keys.disable("r");
        keys.reset("r");
        keys.setNativeStroke("r", "ctrl B");
        keys.removeRow("r");
        assertEquals("", keys.addNativeCommand("label", "ctrl C"));
        keys.suspendInterception().close();
    }

    @Test
    void keybindingRowNormalizesNullTextAndReportsBinding() {
        final Row row = new Row("id", Scope.PLUGIN, null, null, null, null, null, null, null, false, false, false);
        assertEquals("", row.owner());
        assertEquals("", row.effectiveStroke());
        assertFalse(row.bound());
        final Row bound = new Row("id", Scope.NATIVE, "", "l", "", "", "", "ctrl A", "ctrl A", false, false, true);
        assertTrue(bound.bound());
        assertThrows(
                NullPointerException.class,
                () -> new Row(null, Scope.NATIVE, "", "", "", "", "", "", "", false, false, false));
        assertThrows(
                NullPointerException.class, () -> new Row("id", null, "", "", "", "", "", "", "", false, false, false));
    }

    @Test
    void shellServicesConvenienceConstructorWiresUnavailableDefaults() {
        final CorePluginManagement plugins = new CorePluginManagement() {
            @Override
            public List<PluginInfo> plugins() {
                return List.of();
            }

            @Override
            public OperationResult install() {
                return OperationResult.rejected("x");
            }

            @Override
            public OperationResult uninstall(final String pluginId) {
                return OperationResult.rejected("x");
            }

            @Override
            public OperationResult setEnabled(final String pluginId, final boolean enabled) {
                return OperationResult.rejected("x");
            }
        };
        final ShellServices services = new ShellServices(RuntimeSettingsService.unavailable(), plugins);
        assertSame(plugins, services.plugins());
        assertFalse(services.update().available());
        assertEquals(List.of(), services.keybindings().snapshot());
        assertEquals(
                CubismJvmSettingsService.CubismJvm.GRAALVM,
                services.cubismJvmSettings().read());
        assertThrows(NullPointerException.class, () -> new ShellServices(null, plugins));
        assertThrows(NullPointerException.class, () -> new ShellServices(RuntimeSettingsService.unavailable(), null));
    }

    @Test
    void floatingPanelActionUnavailableRefuses() {
        final ShellServices.FloatingPanelActions actions = ShellServices.FloatingPanelActions.unavailable();
        assertThrows(IllegalStateException.class, () -> actions.togglePanelFloating(null));
    }
}

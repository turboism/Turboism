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
    void booleanSettingServicesReportDefaultAndRefuseSave() {
        final AtlasCacheReuseSettingsService cache = AtlasCacheReuseSettingsService.unavailable();
        assertEquals(AtlasCacheReuseSettingsService.DEFAULT_ENABLED, cache.read());
        assertThrows(IllegalStateException.class, () -> cache.save(true));

        final AtlasTileBboxSettingsService bbox = AtlasTileBboxSettingsService.unavailable();
        assertEquals(AtlasTileBboxSettingsService.DEFAULT_ENABLED, bbox.read());
        assertThrows(IllegalStateException.class, () -> bbox.save(false));

        final MeshTriangulationSettingsService mesh = MeshTriangulationSettingsService.unavailable();
        assertEquals(MeshTriangulationSettingsService.DEFAULT_ENABLED, mesh.read());
        assertThrows(IllegalStateException.class, () -> mesh.save(true));

        final TriangulationEdgeIndexSettingsService edge = TriangulationEdgeIndexSettingsService.unavailable();
        assertEquals(TriangulationEdgeIndexSettingsService.DEFAULT_ENABLED, edge.read());
        assertThrows(IllegalStateException.class, () -> edge.save(true));
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

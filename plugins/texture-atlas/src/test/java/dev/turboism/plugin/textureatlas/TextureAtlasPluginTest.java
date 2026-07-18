package dev.turboism.plugin.textureatlas;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.diagnostics.DiagnosticReport;
import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.permission.PluginPermission;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.PluginPaths;
import dev.turboism.sdk.ui.UiScheduler;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextureAtlasPluginTest {

    @Test
    void lifecycleIsStateOnlyAndRequiresInitializationBeforeEnable() {
        TextureAtlasPlugin plugin = new TextureAtlasPlugin();

        assertThrows(IllegalStateException.class, plugin::enable);
        plugin.init(new ShellPluginContext());
        plugin.enable();

        assertTrue(plugin.isEnabled());
        plugin.disable();
        assertFalse(plugin.isEnabled());
        plugin.shutdown();
        assertFalse(plugin.isEnabled());
    }

    private static final class ShellPluginContext implements PluginContext {
        private final PluginLogger logger = new PluginLogger() {
            @Override public void debug(String message) {}
            @Override public void info(String message) {}
            @Override public void warn(String message) {}
            @Override public void error(String message) {}
            @Override public void error(String message, Throwable throwable) {}
        };

        @Override public PluginDescriptor descriptor() { throw unused(); }
        @Override public PluginLogger logger() { return logger; }
        @Override public PluginPaths paths() { throw unused(); }
        @Override public CubismFacade cubism() { throw unused(); }
        @Override public List<PluginPermission> permissions() { return List.of(); }
        @Override public EventBus eventBus() { throw unused(); }
        @Override public ActionRegistry actions() { throw unused(); }
        @Override public MenuRegistry menus() { throw unused(); }
        @Override public UiScheduler uiScheduler() { throw unused(); }
        @Override public DiagnosticReport diagnostics() { throw unused(); }
        @Override public DisposableScope disposableScope() { throw unused(); }

        private static UnsupportedOperationException unused() {
            return new UnsupportedOperationException("not used by a migration shell");
        }
    }
}

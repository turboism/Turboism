package dev.turboism.plugin.textureatlas;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutService;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextureAtlasPluginTest {

    @Test
    void lifecycleComposesAutomaticLayoutServiceAndRevokesCapturedAccessWhenDisabled() {
        TextureAtlasPlugin plugin = new TextureAtlasPlugin();

        assertThrows(IllegalStateException.class, plugin::enable);
        plugin.init(new ShellPluginContext());
        assertThrows(IllegalStateException.class, plugin::autoLayoutService);
        plugin.enable();

        assertTrue(plugin.isEnabled());
        final TextureAtlasAutoLayoutService captured = plugin.autoLayoutService();
        assertTrue(captured != null);
        plugin.disable();
        assertFalse(plugin.isEnabled());
        assertThrows(IllegalStateException.class, plugin::autoLayoutService);
        assertEquals(dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutFailureCode.RUNTIME_CLOSED, captured.applyAutomaticLayout().failureCode().orElseThrow());

        plugin.enable();
        assertTrue(plugin.autoLayoutService() == captured);
        plugin.shutdown();
        assertFalse(plugin.isEnabled());
        assertEquals(dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutFailureCode.RUNTIME_CLOSED, captured.applyAutomaticLayout().failureCode().orElseThrow());
        assertThrows(IllegalStateException.class, plugin::autoLayoutService);
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
        @Override public CubismFacade cubism() {
            return new CubismFacade() {
                @Override public dev.turboism.sdk.cubism.CubismRuntimeSnapshot runtime() { throw unused(); }
                @Override public java.util.Optional<dev.turboism.sdk.cubism.ProjectSnapshot> activeProject() { return java.util.Optional.empty(); }
                @Override public java.util.Optional<dev.turboism.sdk.cubism.DocumentSnapshot> activeDocument() { return java.util.Optional.empty(); }
                @Override public java.util.Optional<dev.turboism.sdk.cubism.ModelSnapshot> activeModel() { return java.util.Optional.empty(); }
                @Override public boolean isHostPresent() { return false; }
                @Override public dev.turboism.sdk.cubism.transaction.TransactionManager transactionManager() { throw unused(); }
                @Override public TextureAtlasLayoutService textureAtlasLayouts() {
                    return new TextureAtlasLayoutService() {
                        @Override public java.util.Optional<dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutSnapshot> current() {
                            return java.util.Optional.empty();
                        }
                        @Override public dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutApplyResult apply(
                            final dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutTarget target,
                            final dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutPlan plan
                        ) {
                            return dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutApplyResult.failed(
                                dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutFailureCode.CAPABILITY_UNAVAILABLE,
                                "unavailable"
                            );
                        }
                    };
                }
            };
        }
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

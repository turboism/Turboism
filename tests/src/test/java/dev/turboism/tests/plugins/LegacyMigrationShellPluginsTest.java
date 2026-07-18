package dev.turboism.tests.plugins;

import dev.turboism.plugin.boundingbox.BoundingBoxPlugin;
import dev.turboism.plugin.contextmenu.ContextMenuPlugin;
import dev.turboism.plugin.projectpanel.ProjectPanelPlugin;
import dev.turboism.plugin.psdimport.PsdImportPlugin;
import dev.turboism.plugin.textureatlas.TextureAtlasPlugin;
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
import dev.turboism.sdk.plugin.TurboismPlugin;
import dev.turboism.sdk.ui.UiScheduler;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class LegacyMigrationShellPluginsTest {

    @ParameterizedTest
    @MethodSource("shellPlugins")
    void shellHasNoRegistrationsOrHostAccess(final Supplier<TurboismPlugin> shellFactory) {
        TurboismPlugin plugin = shellFactory.get();
        ShellPluginContext context = new ShellPluginContext();

        assertDoesNotThrow(() -> {
            plugin.init(context);
            plugin.enable();
            plugin.disable();
            plugin.enable();
            plugin.disable();
            plugin.shutdown();
        });
        context.assertNoOperationalAccess();
    }

    private static Stream<Supplier<TurboismPlugin>> shellPlugins() {
        return Stream.of(
            BoundingBoxPlugin::new,
            ContextMenuPlugin::new,
            ProjectPanelPlugin::new,
            PsdImportPlugin::new,
            TextureAtlasPlugin::new
        );
    }

    private static final class ShellPluginContext implements PluginContext {
        private boolean operationalAccessed;
        private final PluginLogger logger = new PluginLogger() {
            @Override public void debug(String message) {}
            @Override public void info(String message) {}
            @Override public void warn(String message) {}
            @Override public void error(String message) {}
            @Override public void error(String message, Throwable throwable) {}
        };

        @Override public PluginDescriptor descriptor() { return accessed(); }
        @Override public PluginLogger logger() { return logger; }
        @Override public PluginPaths paths() { return accessed(); }
        @Override public CubismFacade cubism() { return accessed(); }
        @Override public List<PluginPermission> permissions() { return accessed(); }
        @Override public EventBus eventBus() { return accessed(); }
        @Override public ActionRegistry actions() { return accessed(); }
        @Override public MenuRegistry menus() { return accessed(); }
        @Override public UiScheduler uiScheduler() { return accessed(); }
        @Override public DiagnosticReport diagnostics() { return accessed(); }
        @Override public DisposableScope disposableScope() { return accessed(); }

        void assertNoOperationalAccess() {
            if (operationalAccessed) {
                throw new AssertionError("migration shell must not access an operational PluginContext service");
            }
        }

        private <T> T accessed() {
            operationalAccessed = true;
            throw new AssertionError("migration shell must not access an operational PluginContext service");
        }
    }
}

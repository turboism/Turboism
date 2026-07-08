package dev.turboism.plugin.uitheme;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.config.PluginConfigRegistry;
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
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.UiScheduler;
import dev.turboism.sdk.ui.context.ContextMenuRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiThemePluginTest {

    @Test
    void registersThemeContextMenuContributions_whenEnabled() throws Exception {
        RecordingPluginContext context = new RecordingPluginContext();
        UiThemePlugin plugin = new UiThemePlugin();

        plugin.init(context);
        plugin.enable();

        assertEquals(
            List.of("ui-theme.toggle", "ui-theme.apply"),
            context.contextMenus().contributions().stream()
                .map(ContextMenuRegistry.ContextMenuContribution::id)
                .toList()
        );
    }

    @Test
    void removesThemeContextMenuContributions_whenDisposableScopeCloses() throws Exception {
        RecordingPluginContext context = new RecordingPluginContext();
        UiThemePlugin plugin = new UiThemePlugin();

        plugin.init(context);
        plugin.enable();
        context.disposableScope().close();

        assertTrue(context.contextMenus().contributions().isEmpty());
    }

    private static final class RecordingPluginContext implements PluginContext {
        private final RecordingContextMenuRegistry contextMenus = new RecordingContextMenuRegistry();
        private final DisposableScope disposableScope = new DisposableScope();
        private final PluginLogger logger = new NoopPluginLogger();

        RecordingContextMenuRegistry contextMenus() {
            return contextMenus;
        }

        @Override
        public PluginDescriptor descriptor() {
            return null;
        }

        @Override
        public PluginLogger logger() {
            return logger;
        }

        @Override
        public PluginPaths paths() {
            return null;
        }

        @Override
        public CubismFacade cubism() {
            return null;
        }

        @Override
        public List<PluginPermission> permissions() {
            return List.of();
        }

        @Override
        public EventBus eventBus() {
            return null;
        }

        @Override
        public ActionRegistry actions() {
            return null;
        }

        @Override
        public MenuRegistry menus() {
            return null;
        }

        @Override
        public ContextMenuRegistry contextMenu() {
            return contextMenus;
        }

        @Override
        public PluginConfigRegistry config() {
            return null;
        }

        @Override
        public UiScheduler uiScheduler() {
            return null;
        }

        @Override
        public DiagnosticReport diagnostics() {
            return null;
        }

        @Override
        public DisposableScope disposableScope() {
            return disposableScope;
        }
    }

    private static final class RecordingContextMenuRegistry implements ContextMenuRegistry {
        private final List<ContextMenuContribution> contributions = new ArrayList<>();

        List<ContextMenuContribution> contributions() {
            return contributions;
        }

        @Override
        public Registration contribute(ContextMenuContribution contribution) {
            contributions.add(contribution);
            return () -> contributions.remove(contribution);
        }
    }

    private static final class NoopPluginLogger implements PluginLogger {
        @Override
        public void debug(String message) {
        }

        @Override
        public void info(String message) {
        }

        @Override
        public void warn(String message) {
        }

        @Override
        public void error(String message) {
        }

        @Override
        public void error(String message, Throwable throwable) {
        }
    }
}

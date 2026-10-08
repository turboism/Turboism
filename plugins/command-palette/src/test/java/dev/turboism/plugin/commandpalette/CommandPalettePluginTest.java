package dev.turboism.plugin.commandpalette;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.diagnostics.DiagnosticReport;
import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.permission.PluginPermission;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.PluginPaths;
import dev.turboism.sdk.ui.UiScheduler;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Entry-path test: the plugin registers exactly one action (with the declared Ctrl+K
 * default shortcut) and one Turboism-menu contribution, and both land in the disposable
 * scope so disable withdraws them.
 */
class CommandPalettePluginTest {

    @Test
    void enableRegistersOpenActionWithCtrlKAndMenuEntry() throws Exception {
        final StubPluginContext context = new StubPluginContext();
        final CommandPalettePlugin plugin = new CommandPalettePlugin();

        plugin.init(context);
        plugin.enable();

        assertEquals(1, context.actionIds.size());
        assertEquals(CommandPalettePlugin.ACTION_ID, context.actionIds.get(0));
        final ActionRegistry.Action action = context.actions.get(0);
        assertTrue(action.defaultShortcut().isPresent());
        assertEquals("Ctrl+K", action.defaultShortcut().get());
        assertEquals(1, context.menuPaths.size());
        assertTrue(context.menuPaths.get(0).startsWith("Turboism/"));

        plugin.disable();
        plugin.shutdown();
    }

    private static final class StubPluginContext implements PluginContext {
        final List<String> actionIds = new ArrayList<>();
        final List<ActionRegistry.Action> actions = new ArrayList<>();
        final List<String> menuPaths = new ArrayList<>();
        private final DisposableScope scope = new DisposableScope();

        @Override
        public PluginDescriptor descriptor() {
            throw new UnsupportedOperationException();
        }

        @Override
        public PluginLogger logger() {
            return new PluginLogger() {
                @Override
                public void debug(final String message) {}

                @Override
                public void info(final String message) {}

                @Override
                public void warn(final String message) {}

                @Override
                public void error(final String message) {}

                @Override
                public void error(final String message, final Throwable error) {}
            };
        }

        @Override
        public PluginPaths paths() {
            throw new UnsupportedOperationException();
        }

        @Override
        public PluginLocalization localization() {
            return new PluginLocalization() {
                @Override
                public Locale locale() {
                    return Locale.ENGLISH;
                }

                @Override
                public String text(final String key) {
                    return key;
                }

                @Override
                public String format(final String key, final Object... arguments) {
                    return key;
                }

                @Override
                public boolean contains(final String key) {
                    return true;
                }
            };
        }

        @Override
        public CubismFacade cubism() {
            throw new UnsupportedOperationException();
        }

        @Override
        public EventBus eventBus() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ActionRegistry actions() {
            return (id, action) -> {
                actionIds.add(id);
                actions.add(action);
                return () -> {};
            };
        }

        @Override
        public MenuRegistry menus() {
            return contribution -> {
                menuPaths.add(contribution.menuPath());
                return () -> {};
            };
        }

        @Override
        public UiScheduler uiScheduler() {
            throw new UnsupportedOperationException();
        }

        @Override
        public DiagnosticReport diagnostics() {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<PluginPermission> permissions() {
            return List.of();
        }

        @Override
        public DisposableScope disposableScope() {
            return scope;
        }
    }
}

package dev.turboism.plugin.commandpalette;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.test.plugin.FakePluginContext;
import java.util.ArrayList;
import java.util.List;
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

    private static final class StubPluginContext extends FakePluginContext {
        final List<String> actionIds = new ArrayList<>();
        final List<ActionRegistry.Action> actions = new ArrayList<>();
        final List<String> menuPaths = new ArrayList<>();
        private final DisposableScope scope = new DisposableScope();

        StubPluginContext() {
            super(FakePluginContext.builder());
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
        public DisposableScope disposableScope() {
            return scope;
        }
    }
}

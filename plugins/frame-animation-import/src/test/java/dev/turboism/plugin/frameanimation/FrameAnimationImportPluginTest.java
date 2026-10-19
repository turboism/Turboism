package dev.turboism.plugin.frameanimation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginServiceDirectory;
import dev.turboism.sdk.plugin.PluginServices;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.UiHostCapabilityService;
import dev.turboism.sdk.ui.context.ContextMenuRegistry;
import dev.turboism.test.plugin.FakePluginContext;
import dev.turboism.test.ui.RecordingUiHost;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import org.junit.jupiter.api.Test;

class FrameAnimationImportPluginTest {

    @Test
    void pluginLifecycleEnrollsActionAndContextMenus() {
        final FrameAnimationImportPlugin plugin = new FrameAnimationImportPlugin();
        final Fixture fixture = new Fixture();

        plugin.init(fixture.context);
        plugin.enable();

        assertEquals(1, fixture.actions.actions.size());
        assertEquals(
                FrameAnimationImportPlugin.ACTION_ID,
                fixture.actions.actions.get(0).id());

        // Context menu items registered in service registry
        assertEquals(2, fixture.contextMenus.contributions.size());

        // Top-level Turboism menu item registered
        assertEquals(1, fixture.menus.contributions.size());
        assertEquals(
                "Turboism/Plugins/Import Frame Animation...",
                fixture.menus.contributions.get(0).menuPath());

        plugin.disable();
        plugin.shutdown();
    }

    private static final class Fixture {
        final RecordingActions actions = new RecordingActions();
        final RecordingContextMenus contextMenus = new RecordingContextMenus();
        final RecordingMenus menus = new RecordingMenus();
        final RecordingUiHost uiHost = new RecordingUiHost();
        final DisposableScope scope = new DisposableScope();
        final PluginContext context = new TestContext(actions, contextMenus, menus, uiHost, scope);
    }

    private static final class TestContext extends FakePluginContext {
        final RecordingActions actions;
        final RecordingContextMenus contextMenus;
        final RecordingMenus menus;
        final RecordingUiHost uiHost;
        final DisposableScope scope;

        TestContext(
                final RecordingActions actions,
                final RecordingContextMenus contextMenus,
                final RecordingMenus menus,
                final RecordingUiHost uiHost,
                final DisposableScope scope) {
            super(FakePluginContext.builder());
            this.actions = actions;
            this.contextMenus = contextMenus;
            this.menus = menus;
            this.uiHost = uiHost;
            this.scope = scope;
        }

        @Override
        public MenuRegistry menus() {
            return menus;
        }

        @Override
        public DisposableScope disposableScope() {
            return scope;
        }

        @Override
        public PluginLocalization localization() {
            return new PluginLocalization() {
                private final ResourceBundle bundle =
                        ResourceBundle.getBundle("META-INF.turboism.i18n.messages", Locale.ROOT);

                @Override
                public Locale locale() {
                    return Locale.ROOT;
                }

                @Override
                public String text(final String key) {
                    return bundle.containsKey(key) ? bundle.getString(key) : key;
                }

                @Override
                public String format(final String key, final Object... arguments) {
                    return java.text.MessageFormat.format(text(key), arguments);
                }

                @Override
                public boolean contains(final String key) {
                    return bundle.containsKey(key);
                }
            };
        }

        @Override
        public ActionRegistry actions() {
            return actions;
        }

        @Override
        public PluginServiceDirectory services() {
            return PluginServices.builder()
                    .supply(ContextMenuRegistry.class, () -> contextMenus)
                    .supply(UiHostCapabilityService.class, () -> uiHost)
                    .fallback(PluginServices.of(this))
                    .build();
        }
    }

    private static final class RecordingMenus implements MenuRegistry {
        final List<MenuContribution> contributions = new ArrayList<>();

        @Override
        public Registration contribute(final MenuContribution contribution) {
            contributions.add(contribution);
            return () -> {};
        }
    }

    private static final class RecordingActions implements ActionRegistry {
        final List<Action> actions = new ArrayList<>();

        @Override
        public Registration register(final String actionId, final Action action) {
            actions.add(action);
            return () -> {};
        }

        public List<Action> all() {
            return actions;
        }
    }

    private static final class RecordingContextMenus implements ContextMenuRegistry {
        final List<ContextMenuContribution> contributions = new ArrayList<>();

        @Override
        public Registration contribute(final ContextMenuContribution contribution) {
            contributions.add(contribution);
            return () -> {};
        }

        public List<ContextMenuContribution> contributionsFor(final Location location) {
            return contributions.stream().filter(c -> c.location() == location).toList();
        }
    }
}

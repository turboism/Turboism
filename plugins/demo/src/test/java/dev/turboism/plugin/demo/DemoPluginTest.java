package dev.turboism.plugin.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.toolbar.MainToolbarRegistry;
import dev.turboism.sdk.ui.toolbar.PaletteToolbarRegistry;
import dev.turboism.test.action.RecordingActionRegistry;
import dev.turboism.test.event.RecordingEventBus;
import dev.turboism.test.menu.RecordingMenuRegistry;
import dev.turboism.test.plugin.FakePluginContext;
import dev.turboism.test.plugin.RecordingPluginLogger;
import dev.turboism.test.ui.RecordingContextMenuRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * plugins:demo previously had no tests (NO-SOURCE); this is the one focused entry-path
 * test: the demo plugin initializes and enables when localization is available, with
 * stable semantic action IDs and all six explicit contribution registrations enrolled in scope.
 */
class DemoPluginTest {

    @Test
    void initAndEnableWorkWithLocalizationAvailable() throws Exception {
        RecordingPluginContext context = new RecordingPluginContext();
        DemoPlugin plugin = new DemoPlugin();

        plugin.init(context);
        plugin.enable();

        assertEquals(
                List.of("demo.hello"),
                context.actions().actions().stream()
                        .map(ActionRegistry.Action::id)
                        .toList(),
                "the stable semantic action ID is unchanged");
        assertEquals("demo.hello.label", context.actions().actions().get(0).label());
        assertEquals(1, context.menus().contributions().size());
        assertEquals(1, context.mainToolbar().contributions().size());
        assertEquals(1, context.paletteToolbar().contributions().size());
        assertEquals(1, context.contextMenu().contributions().size());
        assertEquals(1, context.eventBus().published().size());
        assertTrue(context.eventBus().published().get(0) instanceof DemoPlugin.DemoEvent);
    }

    @Test
    void closingTheScopeUnregistersAllDemoContributions() throws Exception {
        RecordingPluginContext context = new RecordingPluginContext();
        DemoPlugin plugin = new DemoPlugin();

        plugin.init(context);
        plugin.enable();
        context.disposableScope().close();

        assertTrue(context.actions().actions().isEmpty());
        assertTrue(context.menus().contributions().isEmpty());
        assertTrue(context.mainToolbar().contributions().isEmpty());
        assertTrue(context.paletteToolbar().contributions().isEmpty());
        assertTrue(context.contextMenu().contributions().isEmpty());
    }

    private static final class RecordingPluginContext extends FakePluginContext {
        private final DisposableScope disposableScope = new DisposableScope();
        private final RecordingActionRegistry actions = new RecordingActionRegistry();
        private final RecordingMenuRegistry menus = new RecordingMenuRegistry();
        private final RecordingMainToolbar mainToolbar = new RecordingMainToolbar();
        private final RecordingPaletteToolbar paletteToolbar = new RecordingPaletteToolbar();
        private final RecordingContextMenuRegistry contextMenu = new RecordingContextMenuRegistry();
        private final RecordingEventBus eventBus = new RecordingEventBus();
        private final PluginLogger logger = new RecordingPluginLogger();
        private final PluginLocalization localization = new PluginLocalization() {
            @Override
            public Locale locale() {
                return Locale.ENGLISH;
            }

            @Override
            public String text(final String key) {
                return switch (key) {
                    case "demo.hello.label" -> "demo.hello.label";
                    case "demo.menu" -> "demo.menu";
                    case "demo.toolbar.label" -> "demo.toolbar.label";
                    case "demo.palette.label" -> "demo.palette.label";
                    case "demo.context.hello" -> "demo.context.hello";
                    default -> key;
                };
            }

            @Override
            public String format(final String key, final Object... args) {
                return text(key);
            }

            @Override
            public boolean contains(final String key) {
                return true;
            }
        };

        RecordingPluginContext() {
            super(FakePluginContext.builder());
        }

        @Override
        public PluginLogger logger() {
            return logger;
        }

        @Override
        public PluginLocalization localization() {
            return localization;
        }

        @Override
        public RecordingEventBus eventBus() {
            return eventBus;
        }

        @Override
        public RecordingActionRegistry actions() {
            return actions;
        }

        @Override
        public RecordingMenuRegistry menus() {
            return menus;
        }

        @Override
        public DisposableScope disposableScope() {
            return disposableScope;
        }

        public RecordingMainToolbar mainToolbar() {
            return mainToolbar;
        }

        public RecordingPaletteToolbar paletteToolbar() {
            return paletteToolbar;
        }

        @Override
        public RecordingContextMenuRegistry contextMenu() {
            return contextMenu;
        }

        @Override
        public dev.turboism.sdk.plugin.PluginServiceDirectory services() {
            return dev.turboism.sdk.plugin.PluginServices.builder()
                    .supply(dev.turboism.sdk.ui.toolbar.MainToolbarRegistry.class, () -> this.mainToolbar())
                    .supply(dev.turboism.sdk.ui.toolbar.PaletteToolbarRegistry.class, () -> this.paletteToolbar())
                    .supply(dev.turboism.sdk.ui.context.ContextMenuRegistry.class, () -> this.contextMenu())
                    .fallback(dev.turboism.sdk.plugin.PluginServices.of(this))
                    .build();
        }
    }

    private static final class RecordingMainToolbar implements MainToolbarRegistry {
        private final List<MainToolbarContribution> contributions = new ArrayList<>();

        List<MainToolbarContribution> contributions() {
            return contributions;
        }

        @Override
        public Registration contribute(final MainToolbarContribution contribution) {
            contributions.add(contribution);
            return () -> contributions.remove(contribution);
        }

        @Override
        public Registration contributeButton(final MainToolbarButtonContribution contribution) {
            throw new UnsupportedOperationException("not used by the demo plugin");
        }
    }

    private static final class RecordingPaletteToolbar implements PaletteToolbarRegistry {
        private final List<PaletteToolbarContribution> contributions = new ArrayList<>();

        List<PaletteToolbarContribution> contributions() {
            return contributions;
        }

        @Override
        public Registration contribute(final PaletteToolbarContribution contribution) {
            contributions.add(contribution);
            return () -> contributions.remove(contribution);
        }
    }
}

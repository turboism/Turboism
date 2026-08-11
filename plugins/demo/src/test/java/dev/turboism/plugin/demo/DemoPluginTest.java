package dev.turboism.plugin.demo;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.config.PluginConfigRegistry;
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
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.UiHostCapabilityService;
import dev.turboism.sdk.ui.UiScheduler;
import dev.turboism.sdk.ui.context.ContextMenuRegistry;
import dev.turboism.sdk.ui.toolbar.MainToolbarRegistry;
import dev.turboism.sdk.ui.toolbar.PaletteToolbarRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * plugins:demo previously had no tests (NO-SOURCE); this is the one focused entry-path
 * test: the demo plugin initializes and enables when localization is available, with
 * stable semantic action IDs and all seven registrations enrolled in the scope.
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
            context.actions().actions().stream().map(ActionRegistry.Action::id).toList(),
            "the stable semantic action ID is unchanged"
        );
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

    private static final class RecordingPluginContext implements PluginContext {
        private final DisposableScope disposableScope = new DisposableScope();
        private final RecordingActionRegistry actions = new RecordingActionRegistry();
        private final RecordingMenuRegistry menus = new RecordingMenuRegistry();
        private final RecordingMainToolbar mainToolbar = new RecordingMainToolbar();
        private final RecordingPaletteToolbar paletteToolbar = new RecordingPaletteToolbar();
        private final RecordingContextMenu contextMenu = new RecordingContextMenu();
        private final RecordingEventBus eventBus = new RecordingEventBus();
        private final PluginLogger logger = new NoopPluginLogger();
        private final PluginLocalization localization = new PluginLocalization() {
            @Override public Locale locale() { return Locale.ENGLISH; }
            @Override public String text(final String key) {
                return switch (key) {
                    case "demo.hello.label" -> "demo.hello.label";
                    case "demo.menu" -> "demo.menu";
                    case "demo.toolbar.label" -> "demo.toolbar.label";
                    case "demo.palette.label" -> "demo.palette.label";
                    case "demo.context.hello" -> "demo.context.hello";
                    default -> key;
                };
            }
            @Override public String format(final String key, final Object... args) { return text(key); }
            @Override public boolean contains(final String key) { return true; }
        };

        @Override public PluginDescriptor descriptor() { throw unsupported(); }
        @Override public PluginLogger logger() { return logger; }
        @Override public PluginPaths paths() { throw unsupported(); }
        @Override public CubismFacade cubism() { throw unsupported(); }
        @Override public List<PluginPermission> permissions() { return List.of(); }
        @Override public PluginLocalization localization() { return localization; }
        @Override public RecordingEventBus eventBus() { return eventBus; }
        @Override public RecordingActionRegistry actions() { return actions; }
        @Override public RecordingMenuRegistry menus() { return menus; }
        @Override public UiHostCapabilityService uiHost() { throw unsupported(); }
        @Override public PluginConfigRegistry config() {
            return new PluginConfigRegistry() {
                @Override public Registration readScope(final String relativePath) {
                    return () -> { };
                }
                @Override public Registration writeScope(final String relativePath) { return () -> { }; }
                @Override public Optional<String> readString(final String relativePath, final String key) {
                    return Optional.empty();
                }
                @Override public void writeString(final String relativePath, final String key, final String value) { }
                @Override public java.util.concurrent.CompletionStage<Void> registerSchema(
                    final dev.turboism.sdk.config.ConfigSchema schema,
                    final List<dev.turboism.sdk.config.ConfigMigration> migrations
                ) { return java.util.concurrent.CompletableFuture.completedFuture(null); }
                @Override public <T> java.util.concurrent.CompletionStage<dev.turboism.sdk.config.ConfigReadResult<T>> read(
                    final dev.turboism.sdk.config.ConfigKey<T> key
                ) { throw unsupported(); }
                @Override public <T> java.util.concurrent.CompletionStage<dev.turboism.sdk.config.ConfigWriteResult> write(
                    final dev.turboism.sdk.config.ConfigKey<T> key,
                    final T value,
                    final long expectedRevision
                ) { throw unsupported(); }
            };
        }
        @Override public UiScheduler uiScheduler() { throw unsupported(); }
        @Override public DiagnosticReport diagnostics() { throw unsupported(); }
        @Override public DisposableScope disposableScope() { return disposableScope; }
        @Override public RecordingMainToolbar mainToolbar() { return mainToolbar; }
        @Override public RecordingPaletteToolbar paletteToolbar() { return paletteToolbar; }
        @Override public RecordingContextMenu contextMenu() { return contextMenu; }


        private static UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("not used by the demo plugin entry-path test");
        }
    }

    private static final class RecordingActionRegistry implements ActionRegistry {
        private final List<Action> actions = new ArrayList<>();
        List<Action> actions() { return actions; }
        @Override public Registration register(final String id, final Action action) {
            actions.add(action);
            return () -> actions.remove(action);
        }
    }

    private static final class RecordingMenuRegistry implements MenuRegistry {
        private final List<MenuContribution> contributions = new ArrayList<>();
        List<MenuContribution> contributions() { return contributions; }
        @Override public Registration contribute(final MenuContribution contribution) {
            contributions.add(contribution);
            return () -> contributions.remove(contribution);
        }
    }

    private static final class RecordingMainToolbar implements MainToolbarRegistry {
        private final List<MainToolbarContribution> contributions = new ArrayList<>();
        List<MainToolbarContribution> contributions() { return contributions; }
        @Override public Registration contribute(final MainToolbarContribution contribution) {
            contributions.add(contribution);
            return () -> contributions.remove(contribution);
        }
        @Override public Registration contributeButton(final MainToolbarButtonContribution contribution) {
            throw new UnsupportedOperationException("not used by the demo plugin");
        }
    }

    private static final class RecordingPaletteToolbar implements PaletteToolbarRegistry {
        private final List<PaletteToolbarContribution> contributions = new ArrayList<>();
        List<PaletteToolbarContribution> contributions() { return contributions; }
        @Override public Registration contribute(final PaletteToolbarContribution contribution) {
            contributions.add(contribution);
            return () -> contributions.remove(contribution);
        }
    }

    private static final class RecordingContextMenu implements ContextMenuRegistry {
        private final List<ContextMenuContribution> contributions = new ArrayList<>();
        List<ContextMenuContribution> contributions() { return contributions; }
        @Override public Registration contribute(final ContextMenuContribution contribution) {
            contributions.add(contribution);
            return () -> contributions.remove(contribution);
        }
    }

    private static final class RecordingEventBus implements EventBus {
        private final List<Object> published = new ArrayList<>();
        List<Object> published() { return published; }
        @Override public <T extends EventBus.TurboismEvent> Registration subscribe(
            final Class<T> type, final Consumer<T> listener
        ) {
            return () -> { };
        }
        @Override public void publish(final TurboismEvent event) {
            published.add(event);
        }
    }

    private static final class NoopPluginLogger implements PluginLogger {
        @Override public void debug(String message) { }
        @Override public void info(String message) { }
        @Override public void warn(String message) { }
        @Override public void error(String message) { }
        @Override public void error(String message, Throwable throwable) { }
    }
}

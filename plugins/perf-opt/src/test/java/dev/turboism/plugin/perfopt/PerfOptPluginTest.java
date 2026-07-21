package dev.turboism.plugin.perfopt;

import dev.turboism.plugin.perfopt.b1.application.DefaultPluginConfigRegistry;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerfOptPluginTest {

    @Test
    void enableRegistersFpsOverlayActionAndMenuContribution(@TempDir Path dataDir) throws Exception {
        // Given: a plugin context with in-memory action and menu registries.
        RecordingActionRegistry actions = new RecordingActionRegistry();
        RecordingMenuRegistry menus = new RecordingMenuRegistry();
        DisposableScope scope = new DisposableScope();
        PerfOptPlugin plugin = new PerfOptPlugin();
        plugin.init(new TestPluginContext(dataDir, actions, menus, scope));

        // When: the plugin is enabled.
        plugin.enable();

        // Then: the FPS overlay toggle is exposed through an action and menu contribution.
        assertEquals("perfopt.fps-overlay.toggle", actions.actionId());
        assertEquals("Toggle FPS Overlay", actions.action().label());
        assertEquals("Tools/Performance", menus.contribution().menuPath());
        assertEquals("perfopt.fps-overlay.toggle", menus.contribution().actionId());
        assertEquals(200, menus.contribution().order());

        // When: the disposable scope is closed by the runtime lifecycle.
        scope.close();

        // Then: both registrations are cleaned up.
        assertFalse(actions.isRegistered());
        assertFalse(menus.isRegistered());
    }

    @Test
    void fpsOverlayActionTogglesPlaceholderState(@TempDir Path dataDir) throws Exception {
        // Given: an enabled plugin with a stub FPS overlay action.
        RecordingActionRegistry actions = new RecordingActionRegistry();
        PerfOptPlugin plugin = new PerfOptPlugin();
        plugin.init(new TestPluginContext(dataDir, actions, new RecordingMenuRegistry(), new DisposableScope()));
        plugin.enable();

        // When: the action is invoked twice.
        actions.action().handler().accept(new ActionRegistry.ActionContext() {});
        boolean enabledAfterFirstToggle = plugin.isFpsOverlayEnabled();
        actions.action().handler().accept(new ActionRegistry.ActionContext() {});

        // Then: only placeholder state changes; no renderer integration is required.
        assertTrue(enabledAfterFirstToggle);
        assertFalse(plugin.isFpsOverlayEnabled());
    }

    private static final class RecordingActionRegistry implements ActionRegistry {
        private String actionId;
        private Action action;
        private boolean registered;

        @Override
        public Registration register(String id, Action action) {
            this.actionId = id;
            this.action = action;
            this.registered = true;
            return () -> registered = false;
        }

        private String actionId() {
            return actionId;
        }

        private Action action() {
            return action;
        }

        private boolean isRegistered() {
            return registered;
        }
    }

    private static final class RecordingMenuRegistry implements MenuRegistry {
        private MenuContribution contribution;
        private boolean registered;

        @Override
        public Registration contribute(MenuContribution contribution) {
            this.contribution = contribution;
            this.registered = true;
            return () -> registered = false;
        }

        private MenuContribution contribution() {
            return contribution;
        }

        private boolean isRegistered() {
            return registered;
        }
    }

    private record TestPluginContext(
        Path dataDir,
        ActionRegistry actions,
        MenuRegistry menus,
        DisposableScope disposableScope
    ) implements PluginContext {

        @Override
        public PluginDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public PluginLogger logger() {
            return logger;
        }

        @Override
        public PluginPaths paths() {
            return new PluginPaths() {
                @Override public Path dataDir() { return dataDir; }
                @Override public Path logsDir() { return dataDir; }
                @Override public Path stateDir() { return dataDir; }
                @Override public Path cacheDir() { return dataDir; }
            };
        }

        @Override
        public PluginConfigRegistry config() {
            return new DefaultPluginConfigRegistry();
        }

        @Override
        public CubismFacade cubism() {
            throw new UnsupportedOperationException("PerfOptPlugin test does not expose Cubism");
        }

        @Override
        public List<PluginPermission> permissions() {
            return List.of();
        }

        @Override
        public EventBus eventBus() {
            return new EventBus() {
                @Override public <T extends TurboismEvent> Registration subscribe(Class<T> eventType, java.util.function.Consumer<T> handler) { return () -> { }; }
                @Override public void publish(TurboismEvent event) { }
            };
        }

        @Override
        public UiScheduler uiScheduler() {
            return new UiScheduler() {
                @Override public Registration runOnUiThread(Runnable work) { work.run(); return () -> { }; }
                @Override public Registration runOnUiThreadLater(Runnable work, Duration delay) { return () -> { }; }
            };
        }

        @Override
        public DiagnosticReport diagnostics() {
            return new DiagnosticReport() {
                @Override public Instant createdAt() { return Instant.EPOCH; }
                @Override public List<Problem> problems() { return List.of(); }
            };
        }
    }

    private static final PluginLogger logger = new PluginLogger() {
        @Override public void debug(String message) { }
        @Override public void info(String message) { }
        @Override public void warn(String message) { }
        @Override public void error(String message) { }
        @Override public void error(String message, Throwable throwable) { }
    };

    private static final PluginDescriptor descriptor = new PluginDescriptor() {
        @Override public String id() { return "dev.turboism.plugin.perfopt"; }
        @Override public String name() { return "Performance Overlay Plugin"; }
        @Override public String version() { return "0.1.0"; }
        @Override public String description() { return "FPS overlay toggle shell."; }
        @Override public List<String> entrypoints() { return List.of(PerfOptPlugin.class.getName()); }
        @Override public String turboismApi() { return "[0.1.0,0.2.0)"; }
        @Override public List<Author> authors() { return List.of(); }
        @Override public String license() { return "Project License"; }
        @Override public Optional<String> website() { return Optional.of("https://turboism.dev"); }
        @Override public List<String> resources() { return List.of(); }
        @Override public I18n i18n() {
            return new I18n() {
                @Override public String baseName() { return "META-INF/turboism/i18n/messages"; }
                @Override public List<String> locales() { return List.of(); }
            };
        }
        @Override public List<DependencyRef> dependencies() { return List.of(); }
        @Override public List<PermissionRef> permissions() { return List.of(); }
        @Override public List<String> capabilities() { return List.of(); }
        @Override public Environment environment() { return environment; }
    };

    private static final PluginDescriptor.Environment environment = new PluginDescriptor.Environment() {
        @Override public boolean requiresCubism() { return false; }
        @Override public String ui() { return "action"; }
    };
}

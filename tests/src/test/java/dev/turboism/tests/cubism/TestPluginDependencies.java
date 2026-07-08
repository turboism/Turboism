package dev.turboism.tests.cubism;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.diagnostics.DiagnosticReport;
import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.PluginPaths;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.UiScheduler;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

final class TestPluginDependencies {

    private TestPluginDependencies() {
    }

    static PluginDescriptor descriptor() {
        return new PluginDescriptor() {
            @Override public String id() { return CubismQueryIntegrationSupport.PLUGIN_ID; }
            @Override public String name() { return "Query Tests"; }
            @Override public String version() { return "0.1.0"; }
            @Override public String description() { return "Integration test descriptor"; }
            @Override public Map<String, String> entrypoints() { return Map.of("plugin", "dev.turboism.tests.QueryPlugin"); }
            @Override public String turboismApi() { return "[0.1.0,0.2.0)"; }
            @Override public List<Author> authors() { return List.of(); }
            @Override public String license() { return "Project License"; }
            @Override public Optional<String> homepage() { return Optional.empty(); }
            @Override public List<DependencyRef> dependencies() { return List.of(); }
            @Override public List<PermissionRef> permissions() { return List.of(); }
            @Override public List<String> capabilities() { return List.of(); }
            @Override
            public Environment environment() {
                return new Environment() {
                    @Override public boolean requiresCubism() { return false; }
                    @Override public String ui() { return "none"; }
                };
            }
        };
    }

    static PluginLogger silentLogger() {
        return new PluginLogger() {
            @Override public void debug(final String message) { }
            @Override public void info(final String message) { }
            @Override public void warn(final String message) { }
            @Override public void error(final String message) { }
            @Override public void error(final String message, final Throwable throwable) { }
        };
    }

    static PluginPaths paths() {
        return new PluginPaths() {
            @Override public Path dataDir() { return Path.of("build", "query-tests", "data"); }
            @Override public Path logsDir() { return Path.of("build", "query-tests", "logs"); }
            @Override public Path stateDir() { return Path.of("build", "query-tests", "state"); }
            @Override public Path cacheDir() { return Path.of("build", "query-tests", "cache"); }
        };
    }

    static EventBus noOpEventBus() {
        return new EventBus() {
            @Override
            public <T extends TurboismEvent> Registration subscribe(final Class<T> type, final Consumer<T> listener) {
                return () -> { };
            }

            @Override
            public <T extends TurboismEvent> void publish(final T event) {
            }
        };
    }

    static ActionRegistry noOpActions() {
        return (id, action) -> () -> { };
    }

    static MenuRegistry noOpMenus() {
        return contribution -> () -> { };
    }

    static UiScheduler directUiScheduler() {
        return new UiScheduler() {
            @Override public void runOnUiThread(final Runnable work) { work.run(); }
            @Override public void runOnUiThreadLater(final Runnable work) { work.run(); }
        };
    }

    static DiagnosticReport emptyDiagnostics() {
        return new DiagnosticReport() {
            @Override public Instant createdAt() { return CubismQueryIntegrationSupport.FIXED_CLOCK.instant(); }
            @Override public List<Problem> problems() { return List.of(); }
        };
    }
}

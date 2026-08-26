package dev.turboism.tests.cubism;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.diagnostics.DiagnosticReport;
import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.PluginDescriptor.PermissionRef;
import dev.turboism.sdk.plugin.PluginPaths;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.UiScheduler;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

final class TestPluginDependencies {

    private TestPluginDependencies() {
    }

    static PluginDescriptor descriptor(String... permissionIds) {
        List<PermissionRef> permissionRefs = List.of(permissionIds).stream()
            .<PermissionRef>map(id -> new PermissionRef() {
                @Override public String id() { return id; }
                @Override public String scope() { return "read"; }
                @Override public Optional<String> reason() { return Optional.empty(); }
            })
            .toList();
        return new PluginDescriptor() {
            @Override public String id() { return CubismQueryIntegrationSupport.PLUGIN_ID; }
            @Override public String name() { return "Query Tests"; }
            @Override public String version() { return "0.1.0"; }
            @Override public String description() { return "Integration test descriptor"; }
            @Override public List<String> entrypoints() { return List.of("dev.turboism.tests.QueryPlugin"); }
            @Override public String turboismApi() { return "[0.1.0,0.2.0)"; }
            @Override public List<Author> authors() { return List.of(); }
            @Override public String license() { return "Project License"; }
            @Override public Optional<String> website() { return Optional.of("https://turboism.dev"); }
            @Override public List<String> resources() { return List.of(); }
            @Override public I18n i18n() { return emptyI18n(); }
            @Override public List<DependencyRef> dependencies() { return List.of(); }
            @Override public List<PermissionRef> permissions() { return permissionRefs; }
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

    private static PluginDescriptor.I18n emptyI18n() {
        return new PluginDescriptor.I18n() {
            @Override public String baseName() { return "META-INF/turboism/i18n/messages"; }
            @Override public List<String> locales() { return List.of(); }
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
            @Override public Registration runOnUiThread(final Runnable work) { work.run(); return () -> { }; }
            @Override public Registration runOnUiThreadLater(final Runnable work, final Duration delay) { work.run(); return () -> { }; }
        };
    }

    static DiagnosticReport emptyDiagnostics() {
        return new DiagnosticReport() {
            @Override public Instant createdAt() { return CubismQueryIntegrationSupport.FIXED_CLOCK.instant(); }
            @Override public List<Problem> problems() { return List.of(); }
        };
    }
}

package dev.turboism.adapter.cubism.write;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.CubismRuntimeSnapshot;
import dev.turboism.sdk.cubism.SelectionSnapshot;
import dev.turboism.sdk.diagnostics.DiagnosticReport;
import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.permission.PluginPermission;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.PluginPaths;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.ui.UiScheduler;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

final class TestPluginContext implements PluginContext {

    private final String pluginId;

    TestPluginContext(final String pluginId) {
        this.pluginId = pluginId;
    }

    @Override public PluginDescriptor descriptor() { return new TestDescriptor(pluginId); }
    @Override public PluginLogger logger() { return new NoopLogger(); }
    @Override public PluginPaths paths() { return new TestPaths(); }
    @Override public CubismFacade cubism() { return NoCubismFacade.INSTANCE; }
    @Override public List<PluginPermission> permissions() { return List.of(); }
    @Override public EventBus eventBus() { return new NoopEventBus(); }
    @Override public ActionRegistry actions() { return (id, action) -> () -> { }; }
    @Override public MenuRegistry menus() { return contribution -> () -> { }; }
    @Override public UiScheduler uiScheduler() { return new DirectUiScheduler(); }
    @Override public DiagnosticReport diagnostics() { return new TestDiagnostics(); }
    @Override public DisposableScope disposableScope() { return new DisposableScope(); }

    private record TestDescriptor(String id) implements PluginDescriptor {
        @Override public String name() { return "Test Plugin"; }
        @Override public String version() { return "0.1.0"; }
        @Override public String description() { return "Test plugin"; }
        @Override public List<String> entrypoints() { return List.of("dev.turboism.test.TestPlugin"); }
        @Override public String turboismApi() { return "[0.1.0,0.2.0)"; }
        @Override public List<Author> authors() { return List.of(); }
        @Override public String license() { return "Project License"; }
        @Override public Optional<String> website() { return Optional.of("https://turboism.dev"); }
        @Override public List<String> resources() { return List.of(); }
        @Override public I18n i18n() { return new TestI18n(); }
        @Override public List<DependencyRef> dependencies() { return List.of(); }
        @Override public List<PermissionRef> permissions() { return List.of(); }
        @Override public List<String> capabilities() { return List.of(); }
        @Override public Environment environment() { return new TestEnvironment(); }
    }

    private record TestI18n() implements PluginDescriptor.I18n {
        @Override public String baseName() { return "META-INF/turboism/i18n/messages"; }
        @Override public List<String> locales() { return List.of(); }
    }

    private record TestEnvironment() implements PluginDescriptor.Environment {
        @Override public boolean requiresCubism() { return false; }
        @Override public String ui() { return "none"; }
    }

    private record TestPaths() implements PluginPaths {
        @Override public Path dataDir() { return Path.of("data"); }
        @Override public Path logsDir() { return Path.of("logs"); }
        @Override public Path stateDir() { return Path.of("state"); }
        @Override public Path cacheDir() { return Path.of("cache"); }
    }

    private static final class NoopEventBus implements EventBus {
        @Override public <T extends TurboismEvent> dev.turboism.sdk.plugin.Registration subscribe(
            final Class<T> type,
            final java.util.function.Consumer<T> listener
        ) { return () -> { }; }
        @Override public <T extends TurboismEvent> void publish(final T event) { }
    }

    private static final class DirectUiScheduler implements UiScheduler {
        @Override public dev.turboism.sdk.plugin.Registration runOnUiThread(final Runnable work) {
            work.run();
            return () -> { };
        }

        @Override public dev.turboism.sdk.plugin.Registration runOnUiThreadLater(
            final Runnable work,
            final java.time.Duration delay
        ) {
            work.run();
            return () -> { };
        }
    }

    private record TestDiagnostics() implements DiagnosticReport {
        @Override public Instant createdAt() { return Instant.EPOCH; }
        @Override public List<Problem> problems() { return List.of(); }
    }

    private record NoopLogger() implements PluginLogger {
        @Override public void debug(String message) { }
        @Override public void info(String message) { }
        @Override public void warn(String message) { }
        @Override public void error(String message) { }
        @Override public void error(String message, Throwable throwable) { }
    }

    private enum NoCubismFacade implements CubismFacade {
        INSTANCE;

        @Override public CubismRuntimeSnapshot runtime() {
            return new CubismRuntimeSnapshot(Optional.empty(), Optional.empty(), Optional.empty(),
                new SelectionSnapshot(List.of(), Optional.empty(), Optional.empty(), Optional.empty()),
                List.of(), List.of(), List.of(), List.of());
        }
        @Override public Optional<dev.turboism.sdk.cubism.ProjectSnapshot> activeProject() { return Optional.empty(); }
        @Override public Optional<dev.turboism.sdk.cubism.DocumentSnapshot> activeDocument() { return Optional.empty(); }
        @Override public Optional<dev.turboism.sdk.cubism.ModelSnapshot> activeModel() { return Optional.empty(); }
        @Override public boolean isHostPresent() { return false; }
        @Override public dev.turboism.sdk.cubism.transaction.TransactionManager transactionManager() {
            throw new UnsupportedOperationException("not available");
        }
    }
}

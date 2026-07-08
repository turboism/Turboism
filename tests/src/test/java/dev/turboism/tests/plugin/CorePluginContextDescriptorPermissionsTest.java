package dev.turboism.tests.plugin;

import dev.turboism.adapter.cubism.HostSnapshotSource;
import dev.turboism.core.plugin.context.CorePluginContext;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.PluginExecutorRegistry;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.diagnostics.CubismFacadeAuditEvent;
import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.permission.PluginPermission;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.PluginPaths;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.UiScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorePluginContextDescriptorPermissionsTest {

    private static final String PLUGIN_ID = "dev.turboism.plugin.descriptor-permission";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-08T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void defaultConstructorUsesDescriptorPermissionsAndDeniesAllWhenEmpty(@TempDir Path dataDir) {
        // Given
        List<CubismFacadeAuditEvent> auditEvents = new CopyOnWriteArrayList<>();
        CorePluginContext context = context(dataDir, List.of(), auditEvents::add);

        // Then
        assertThrows(CubismPermissionException.class, () ->
            context.actions().register("test.action", new ActionRegistry.Action() {
                @Override public String id() { return "test.action"; }
                @Override public String label() { return "Test"; }
                @Override public Consumer<ActionRegistry.ActionContext> handler() { return ctx -> { }; }
            })
        );
        assertThrows(CubismPermissionException.class, () ->
            context.menus().contribute(new MenuRegistry.MenuContribution() {
                @Override public String menuPath() { return "Test"; }
                @Override public String actionId() { return "test.action"; }
                @Override public int order() { return 1; }
            })
        );
        assertThrows(CubismPermissionException.class, () ->
            context.eventBus().subscribe(EventBus.TurboismEvent.class, event -> { })
        );
        assertThrows(CubismPermissionException.class, () ->
            context.mainToolbar().contribute(new dev.turboism.sdk.ui.toolbar.MainToolbarRegistry.MainToolbarContribution(
                "test", "test", "label", "icon", "end", 1
            ))
        );
        assertThrows(CubismPermissionException.class, () ->
            context.paletteToolbar().contribute(new dev.turboism.sdk.ui.toolbar.PaletteToolbarRegistry.PaletteToolbarContribution(
                "test", "test", "label", "icon", "parameters", "end", 1
            ))
        );
        assertThrows(CubismPermissionException.class, () ->
            context.config().readScope("test/config.json")
        );
        assertTrue(auditEvents.size() >= 6, "Expected at least one audit event per denied operation");
    }

    private static CorePluginContext context(Path dataDir, List<PluginPermission> permissions, Consumer<CubismFacadeAuditEvent> auditSink) {
        List<dev.turboism.core.diagnostics.CallbackBudgetEvent> events = new CopyOnWriteArrayList<>();
        RuntimeScheduler scheduler = new RuntimeScheduler(
            new DefaultWorkBudgetPolicy(),
            new PluginExecutorRegistry(1, 4, events::add, CLOCK),
            SidecarDispatcher.noop(),
            events::add
        );
        return new CorePluginContext(new CorePluginContext.Dependencies(
            descriptor(),
            logger(),
            paths(dataDir),
            permissions,
            uiScheduler(),
            scheduler,
            diagnostics(),
            new DisposableScope(),
            noopHostSnapshotSource(),
            auditSink,
            CLOCK
        ));
    }

    private static PluginDescriptor descriptor() {
        return new PluginDescriptor() {
            @Override public String id() { return PLUGIN_ID; }
            @Override public String name() { return "Descriptor Permission Test"; }
            @Override public String version() { return "0.1.0"; }
            @Override public String description() { return "Test"; }
            @Override public Map<String, String> entrypoints() { return Map.of(); }
            @Override public String turboismApi() { return "[0.1.0,0.2.0)"; }
            @Override public List<Author> authors() { return List.of(); }
            @Override public String license() { return "Project License"; }
            @Override public Optional<String> homepage() { return Optional.empty(); }
            @Override public List<DependencyRef> dependencies() { return List.of(); }
            @Override public List<PermissionRef> permissions() { return List.of(); }
            @Override public List<String> capabilities() { return List.of(); }
            @Override public Environment environment() { return new Environment() {
                @Override public boolean requiresCubism() { return false; }
                @Override public String ui() { return "none"; }
            }; }
        };
    }

    private static PluginLogger logger() {
        return new PluginLogger() {
            @Override public void debug(String message) { }
            @Override public void info(String message) { }
            @Override public void warn(String message) { }
            @Override public void error(String message) { }
            @Override public void error(String message, Throwable throwable) { }
        };
    }

    private static PluginPaths paths(Path dataDir) {
        return new PluginPaths() {
            @Override public Path dataDir() { return dataDir; }
            @Override public Path logsDir() { return dataDir; }
            @Override public Path stateDir() { return dataDir; }
            @Override public Path cacheDir() { return dataDir; }
        };
    }

    private static UiScheduler uiScheduler() {
        return new UiScheduler() {
            @Override public dev.turboism.sdk.plugin.Registration runOnUiThread(Runnable work) { work.run(); return () -> { }; }
            @Override public dev.turboism.sdk.plugin.Registration runOnUiThreadLater(Runnable work, java.time.Duration delay) { return () -> { }; }
        };
    }

    private static dev.turboism.sdk.diagnostics.DiagnosticReport diagnostics() {
        return new dev.turboism.sdk.diagnostics.DiagnosticReport() {
            @Override public Instant createdAt() { return CLOCK.instant(); }
            @Override public List<Problem> problems() { return List.of(); }
        };
    }

    private static HostSnapshotSource noopHostSnapshotSource() {
        return new HostSnapshotSource() {
            @Override public Optional<HostSnapshotSource.HostProject> activeProject() { return Optional.empty(); }
            @Override public Optional<HostSnapshotSource.HostDocument> activeDocument() { return Optional.empty(); }
            @Override public Optional<HostSnapshotSource.HostModel> activeModel() { return Optional.empty(); }
            @Override public HostSnapshotSource.HostSelection selection() { return new HostSnapshotSource.HostSelection(List.of(), Optional.empty(), Optional.empty(), Optional.empty()); }
            @Override public boolean isHostPresent() { return false; }
            @Override public long invalidationToken() { return 0; }
        };
    }
}

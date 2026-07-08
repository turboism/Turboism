package dev.turboism.tests.plugin;

import dev.turboism.adapter.cubism.HostSnapshotSource;
import dev.turboism.core.plugin.context.CorePluginContext;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.PluginExecutorRegistry;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.diagnostics.CubismFacadeAuditEvent;
import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.event.EventBus.TurboismEvent;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.PluginPaths;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.UiScheduler;
import dev.turboism.sdk.ui.toolbar.MainToolbarRegistry;
import dev.turboism.sdk.ui.toolbar.PaletteToolbarRegistry;
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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorePluginContextDescriptorPermissionsTest {

    private static final String PLUGIN_ID = "dev.turboism.plugin.descriptor-permission";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-08T00:00:00Z"), ZoneOffset.UTC);

    record TestEvent(String payload) implements TurboismEvent {
    }

    @Test
    void defaultConstructorDeniesEveryM8OperationWhenDescriptorHasNoPermissions(@TempDir Path dataDir) {
        // Given: a descriptor with NO declared permissions; the convenience constructor derives the list.
        List<CubismFacadeAuditEvent> auditEvents = new CopyOnWriteArrayList<>();
        CorePluginContext context = context(dataDir, descriptorWithPermissions(), auditEvents::add);

        // Then: every M8 seam is denied because the descriptor declares no permissions.
        assertThrows(CubismPermissionException.class, () ->
            context.actions().register("test.action", actionDefinition("test.action", "Test"))
        );
        assertThrows(CubismPermissionException.class, () ->
            context.menus().contribute(menuContribution("Test", "test.action", 1))
        );
        assertThrows(CubismPermissionException.class, () ->
            context.eventBus().subscribe(TurboismEvent.class, e -> { })
        );
        assertThrows(CubismPermissionException.class, () ->
            context.eventBus().publish(new TestEvent("test"))
        );
        assertThrows(CubismPermissionException.class, () ->
            context.mainToolbar().contribute(mainToolbarContribution("test", "label", "icon"))
        );
        assertThrows(CubismPermissionException.class, () ->
            context.paletteToolbar().contribute(paletteToolbarContribution("test", "label", "icon"))
        );
        assertThrows(CubismPermissionException.class, () ->
            context.contextMenu().contribute(contextMenuContribution("test", "label"))
        );
        assertThrows(CubismPermissionException.class, () ->
            context.config().readScope("test/config.json")
        );
        assertThrows(CubismPermissionException.class, () ->
            context.config().writeString("test/config.json", "key", "value")
        );
        assertTrue(auditEvents.size() >= 8, "Expected at least one audit event per denied operation");
    }

    @Test
    void defaultConstructorGrantsM8OperationsWhenDescriptorDeclaresPermissions(@TempDir Path dataDir) {
        // Given: a descriptor that declares all M8 permissions.
        List<CubismFacadeAuditEvent> auditEvents = new CopyOnWriteArrayList<>();
        CorePluginContext context = context(dataDir, descriptorWithAllM8Permissions(), auditEvents::add);

        // Then: all M8 registrations and uses succeed without throwing.
        assertDoesNotThrow(() -> {
            Registration action = context.actions().register("test.action", actionDefinition("test.action", "Test"));
            Registration menu = context.menus().contribute(menuContribution("Test", "test.action", 1));
            Registration subscription = context.eventBus().subscribe(TurboismEvent.class, e -> { });
            Registration mainToolbar = context.mainToolbar().contribute(mainToolbarContribution("test", "label", "icon"));
            Registration paletteToolbar = context.paletteToolbar().contribute(paletteToolbarContribution("test", "label", "icon"));
            Registration contextMenu = context.contextMenu().contribute(contextMenuContribution("test", "label"));
            Registration configReadScope = context.config().readScope("test/config.json");
            Registration configWriteScope = context.config().writeScope("test/config.json");
            context.config().writeString("test/config.json", "key", "value");
            context.eventBus().publish(new TestEvent("test"));

            action.close();
            menu.close();
            subscription.close();
            mainToolbar.close();
            paletteToolbar.close();
            contextMenu.close();
            configReadScope.close();
            configWriteScope.close();
        });

        // And the cubism side is audited from the descriptor-derived permission list, but no host is present.
        assertTrue(auditEvents.isEmpty(), "No host Cubism reads were attempted");
    }

    private static CorePluginContext context(Path dataDir, PluginDescriptor descriptor, Consumer<CubismFacadeAuditEvent> auditSink) {
        return new CorePluginContext(new CorePluginContext.Dependencies(
            descriptor,
            logger(),
            paths(dataDir),
            uiScheduler(),
            scheduler(),
            diagnostics(),
            new DisposableScope(),
            noopHostSnapshotSource(),
            auditSink,
            CLOCK
        ));
    }

    private static RuntimeScheduler scheduler() {
        List<dev.turboism.core.diagnostics.CallbackBudgetEvent> events = new CopyOnWriteArrayList<>();
        return new RuntimeScheduler(
            new DefaultWorkBudgetPolicy(),
            new PluginExecutorRegistry(1, 4, events::add, CLOCK),
            SidecarDispatcher.noop(),
            events::add
        );
    }

    private static ActionRegistry.Action actionDefinition(String id, String label) {
        return new ActionRegistry.Action() {
            @Override public String id() { return id; }
            @Override public String label() { return label; }
            @Override public Consumer<ActionRegistry.ActionContext> handler() { return ctx -> { }; }
        };
    }

    private static MenuRegistry.MenuContribution menuContribution(String menuPath, String actionId, int order) {
        return new MenuRegistry.MenuContribution() {
            @Override public String menuPath() { return menuPath; }
            @Override public String actionId() { return actionId; }
            @Override public int order() { return order; }
        };
    }

    private static MainToolbarRegistry.MainToolbarContribution mainToolbarContribution(String id, String label, String icon) {
        return new MainToolbarRegistry.MainToolbarContribution(id, id, label, icon, "end", 1);
    }

    private static PaletteToolbarRegistry.PaletteToolbarContribution paletteToolbarContribution(String id, String label, String icon) {
        return new PaletteToolbarRegistry.PaletteToolbarContribution(id, id, label, icon, "parameters", "end", 1);
    }

    private static dev.turboism.sdk.ui.context.ContextMenuRegistry.ContextMenuContribution contextMenuContribution(String id, String label) {
        return new dev.turboism.sdk.ui.context.ContextMenuRegistry.ContextMenuContribution(id, label, null, "parameter", 1);
    }

    private static PluginDescriptor descriptorWithPermissions() {
        return descriptorWithPermissions(List.of());
    }

    private static PluginDescriptor descriptorWithAllM8Permissions() {
        return descriptorWithPermissions(List.of(
            "turboism.action.register",
            "turboism.ui.menu.contribute",
            "turboism.event.subscribe",
            "turboism.event.publish",
            "turboism.ui.toolbar.main.contribute",
            "turboism.ui.toolbar.palette.contribute",
            "turboism.ui.context-menu.contribute",
            "turboism.config.plugin.read",
            "turboism.config.plugin.write"
        ));
    }

    private static PluginDescriptor descriptorWithPermissions(List<String> ids) {
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
            @Override public List<PermissionRef> permissions() {
                return ids.stream()
                    .<PermissionRef>map(id -> new PermissionRef() {
                        @Override public String id() { return id; }
                        @Override public String scope() { return "application"; }
                        @Override public Optional<String> reason() { return Optional.empty(); }
                    })
                    .toList();
            }
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
            @Override public Registration runOnUiThread(Runnable work) { work.run(); return () -> { }; }
            @Override public Registration runOnUiThreadLater(Runnable work, Duration delay) { return () -> { }; }
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
            @Override public Optional<HostProject> activeProject() { return Optional.empty(); }
            @Override public Optional<HostDocument> activeDocument() { return Optional.empty(); }
            @Override public Optional<HostModel> activeModel() { return Optional.empty(); }
            @Override public HostSelection selection() { return new HostSelection(List.of(), Optional.empty(), Optional.empty(), Optional.empty()); }
            @Override public boolean isHostPresent() { return false; }
            @Override public long invalidationToken() { return 0; }
        };
    }
}

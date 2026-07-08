package dev.turboism.core.plugin.context;

import dev.turboism.adapter.cubism.CubismFacadeImpl;
import dev.turboism.adapter.cubism.HostSnapshotSource;
import dev.turboism.diagnostics.CubismFacadeAuditEvent;
import dev.turboism.permissions.CubismPermissionGate;
import dev.turboism.sdk.action.ActionRegistry;
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
import dev.turboism.sdk.ui.UiScheduler;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

public final class CorePluginContext implements PluginContext {

    private final Dependencies dependencies;
    private final CubismFacade cubismFacade;

    public CorePluginContext(final Dependencies dependencies) {
        this.dependencies = Objects.requireNonNull(dependencies, "dependencies");
        this.cubismFacade = new CubismFacadeImpl(
            dependencies.hostSnapshotSource(),
            new CubismPermissionGate(
                dependencies.descriptor().id(),
                dependencies.permissions(),
                dependencies.cubismAuditSink(),
                dependencies.clock()
            )
        );
    }

    @Override
    public PluginDescriptor descriptor() {
        return dependencies.descriptor();
    }

    @Override
    public PluginLogger logger() {
        return dependencies.logger();
    }

    @Override
    public PluginPaths paths() {
        return dependencies.paths();
    }

    @Override
    public CubismFacade cubism() {
        return cubismFacade;
    }

    @Override
    public List<PluginPermission> permissions() {
        return dependencies.permissions();
    }

    @Override
    public EventBus eventBus() {
        return dependencies.eventBus();
    }

    @Override
    public ActionRegistry actions() {
        return dependencies.actions();
    }

    @Override
    public MenuRegistry menus() {
        return dependencies.menus();
    }

    @Override
    public UiScheduler uiScheduler() {
        return dependencies.uiScheduler();
    }

    @Override
    public DiagnosticReport diagnostics() {
        return dependencies.diagnostics();
    }

    @Override
    public DisposableScope disposableScope() {
        return dependencies.disposableScope();
    }

    public record Dependencies(
        PluginDescriptor descriptor,
        PluginLogger logger,
        PluginPaths paths,
        List<PluginPermission> permissions,
        EventBus eventBus,
        ActionRegistry actions,
        MenuRegistry menus,
        UiScheduler uiScheduler,
        DiagnosticReport diagnostics,
        DisposableScope disposableScope,
        HostSnapshotSource hostSnapshotSource,
        Consumer<CubismFacadeAuditEvent> cubismAuditSink,
        Clock clock
    ) {
        public Dependencies {
            descriptor = Objects.requireNonNull(descriptor, "descriptor");
            logger = Objects.requireNonNull(logger, "logger");
            paths = Objects.requireNonNull(paths, "paths");
            permissions = List.copyOf(Objects.requireNonNull(permissions, "permissions"));
            eventBus = Objects.requireNonNull(eventBus, "eventBus");
            actions = Objects.requireNonNull(actions, "actions");
            menus = Objects.requireNonNull(menus, "menus");
            uiScheduler = Objects.requireNonNull(uiScheduler, "uiScheduler");
            diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
            disposableScope = Objects.requireNonNull(disposableScope, "disposableScope");
            hostSnapshotSource = Objects.requireNonNull(hostSnapshotSource, "hostSnapshotSource");
            cubismAuditSink = Objects.requireNonNull(cubismAuditSink, "cubismAuditSink");
            clock = Objects.requireNonNull(clock, "clock");
        }
    }
}

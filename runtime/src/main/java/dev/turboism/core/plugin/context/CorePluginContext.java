package dev.turboism.core.plugin.context;

import dev.turboism.adapter.cubism.CubismFacadeImpl;
import dev.turboism.adapter.cubism.HostSnapshotSource;
import dev.turboism.adapter.cubism.service.query.ModelHierarchyQueryServiceImpl;
import dev.turboism.adapter.cubism.service.query.ParameterQueryServiceImpl;
import dev.turboism.adapter.cubism.service.query.SelectionQueryServiceImpl;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.diagnostics.CubismFacadeAuditEvent;
import dev.turboism.permissions.CubismPermissionGate;
import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.service.query.ModelHierarchyQueryService;
import dev.turboism.sdk.cubism.service.query.ParameterQueryService;
import dev.turboism.sdk.cubism.service.query.SelectionQueryService;
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
    private final ParameterQueryService parameterQueryService;
    private final SelectionQueryService selectionQueryService;
    private final ModelHierarchyQueryService modelHierarchyQueryService;

    public CorePluginContext(final Dependencies dependencies) {
        this.dependencies = Objects.requireNonNull(dependencies, "dependencies");
        final CubismPermissionGate permissionGate = new CubismPermissionGate(
            dependencies.descriptor().id(),
            dependencies.permissions(),
            dependencies.cubismAuditSink(),
            dependencies.clock()
        );
        final CubismFacadeImpl facade = new CubismFacadeImpl(dependencies.hostSnapshotSource(), permissionGate);
        this.cubismFacade = facade;
        this.parameterQueryService = new ParameterQueryServiceImpl(facade, permissionGate);
        this.selectionQueryService = new SelectionQueryServiceImpl(facade, permissionGate, dependencies.runtimeScheduler());
        this.modelHierarchyQueryService = new ModelHierarchyQueryServiceImpl(facade, permissionGate);
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
    public ParameterQueryService parameterQuery() {
        return parameterQueryService;
    }

    @Override
    public SelectionQueryService selectionQuery() {
        return selectionQueryService;
    }

    @Override
    public ModelHierarchyQueryService modelHierarchyQuery() {
        return modelHierarchyQueryService;
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
        RuntimeScheduler runtimeScheduler,
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
            runtimeScheduler = Objects.requireNonNull(runtimeScheduler, "runtimeScheduler");
            diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
            disposableScope = Objects.requireNonNull(disposableScope, "disposableScope");
            hostSnapshotSource = Objects.requireNonNull(hostSnapshotSource, "hostSnapshotSource");
            cubismAuditSink = Objects.requireNonNull(cubismAuditSink, "cubismAuditSink");
            clock = Objects.requireNonNull(clock, "clock");
        }
    }

}

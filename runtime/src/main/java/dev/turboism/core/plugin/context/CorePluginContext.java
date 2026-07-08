package dev.turboism.core.plugin.context;

import dev.turboism.adapter.cubism.HostSnapshotSource;
import dev.turboism.config.RuntimePluginConfigRegistry;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.diagnostics.CubismFacadeAuditEvent;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.service.query.ModelHierarchyQueryService;
import dev.turboism.sdk.cubism.service.query.ParameterQueryService;
import dev.turboism.sdk.cubism.service.query.SelectionQueryService;
import dev.turboism.sdk.config.PluginConfigRegistry;
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
import dev.turboism.sdk.ui.toolbar.MainToolbarRegistry;
import dev.turboism.sdk.ui.toolbar.PaletteToolbarRegistry;
import dev.turboism.ui.toolbar.RuntimeMainToolbarRegistry;
import dev.turboism.ui.toolbar.RuntimePaletteToolbarRegistry;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

public final class CorePluginContext implements PluginContext {

    private final Dependencies dependencies;
    private final CubismContextServices cubismServices;
    private final MainToolbarRegistry mainToolbarRegistry;
    private final PaletteToolbarRegistry paletteToolbarRegistry;
    private final PluginConfigRegistry pluginConfigRegistry;

    public CorePluginContext(final Dependencies dependencies) {
        this(dependencies, new DefaultCubismServicesFactory());
    }

    CorePluginContext(final Dependencies dependencies, final CubismServicesFactory cubismServicesFactory) {
        this.dependencies = Objects.requireNonNull(dependencies, "dependencies");
        this.cubismServices = Objects.requireNonNull(cubismServicesFactory, "cubismServicesFactory")
            .create(this.dependencies);
        this.mainToolbarRegistry = dependencies.mainToolbar();
        this.paletteToolbarRegistry = dependencies.paletteToolbar();
        this.pluginConfigRegistry = dependencies.config();
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
        return cubismServices.cubismFacade();
    }

    @Override
    public ParameterQueryService parameterQuery() {
        return cubismServices.parameterQueryService();
    }

    @Override
    public SelectionQueryService selectionQuery() {
        return cubismServices.selectionQueryService();
    }

    @Override
    public ModelHierarchyQueryService modelHierarchyQuery() {
        return cubismServices.modelHierarchyQueryService();
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
    public MainToolbarRegistry mainToolbar() {
        return mainToolbarRegistry;
    }

    @Override
    public PaletteToolbarRegistry paletteToolbar() {
        return paletteToolbarRegistry;
    }

    @Override
    public PluginConfigRegistry config() {
        return pluginConfigRegistry;
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
        MainToolbarRegistry mainToolbar,
        PaletteToolbarRegistry paletteToolbar,
        PluginConfigRegistry config,
        UiScheduler uiScheduler,
        RuntimeScheduler runtimeScheduler,
        DiagnosticReport diagnostics,
        DisposableScope disposableScope,
        HostSnapshotSource hostSnapshotSource,
        Consumer<CubismFacadeAuditEvent> cubismAuditSink,
        Clock clock
    ) {
        public Dependencies(
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
            this(
                descriptor,
                logger,
                paths,
                permissions,
                eventBus,
                actions,
                menus,
                new RuntimeMainToolbarRegistry(
                    permissionChecker(permissions, descriptor.id(), cubismAuditSink, clock),
                    runtimeScheduler,
                    descriptor.id()
                ),
                new RuntimePaletteToolbarRegistry(
                    permissionChecker(permissions, descriptor.id(), cubismAuditSink, clock),
                    runtimeScheduler,
                    descriptor.id()
                ),
                new RuntimePluginConfigRegistry(
                    permissionChecker(permissions, descriptor.id(), cubismAuditSink, clock),
                    runtimeScheduler,
                    paths.dataDir(),
                    problem -> logger.warn(problem.code() + ": " + problem.message() + " @ " + problem.path())
                ),
                uiScheduler,
                runtimeScheduler,
                diagnostics,
                disposableScope,
                hostSnapshotSource,
                cubismAuditSink,
                clock
            );
        }

        private static PermissionChecker permissionChecker(
            List<PluginPermission> permissions,
            String pluginId,
            Consumer<CubismFacadeAuditEvent> cubismAuditSink,
            Clock clock
        ) {
            return PermissionChecker.from(new CubismPermissionGate(pluginId, permissions, cubismAuditSink, clock));
        }

        public Dependencies {
            descriptor = Objects.requireNonNull(descriptor, "descriptor");
            logger = Objects.requireNonNull(logger, "logger");
            paths = Objects.requireNonNull(paths, "paths");
            permissions = List.copyOf(Objects.requireNonNull(permissions, "permissions"));
            eventBus = Objects.requireNonNull(eventBus, "eventBus");
            actions = Objects.requireNonNull(actions, "actions");
            menus = Objects.requireNonNull(menus, "menus");
            mainToolbar = Objects.requireNonNull(mainToolbar, "mainToolbar");
            paletteToolbar = Objects.requireNonNull(paletteToolbar, "paletteToolbar");
            config = Objects.requireNonNull(config, "config");
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

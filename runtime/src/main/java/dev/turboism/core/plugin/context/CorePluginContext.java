package dev.turboism.core.plugin.context;

import dev.turboism.adapter.cubism.HostSnapshotSource;
import dev.turboism.adapter.cubism.service.read.M12ReadSnapshotSource;
import dev.turboism.config.RuntimePluginConfigRegistry;
import dev.turboism.core.action.RuntimeActionRegistry;
import dev.turboism.core.diagnostics.StartupReport;
import dev.turboism.core.event.RuntimeEventBus;
import dev.turboism.core.menu.RuntimeMenuRegistry;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.diagnostics.CubismFacadeAuditEvent;
import dev.turboism.permissions.CubismPermissionGate;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.service.query.ModelHierarchyQueryService;
import dev.turboism.sdk.cubism.service.query.ParameterQueryService;
import dev.turboism.sdk.cubism.service.query.SelectionQueryService;
import dev.turboism.sdk.cubism.service.read.CubismReadCapabilityService;
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
import dev.turboism.sdk.ui.UiHostCapabilityService;
import dev.turboism.sdk.ui.UiScheduler;
import dev.turboism.sdk.ui.context.ContextMenuRegistry;
import dev.turboism.sdk.ui.toolbar.MainToolbarRegistry;
import dev.turboism.sdk.ui.toolbar.PaletteToolbarRegistry;
import dev.turboism.ui.RuntimeUiHostCapabilityService;
import dev.turboism.ui.UiHostStateSource;
import dev.turboism.ui.context.RuntimeContextMenuRegistry;
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
    private final ContextMenuRegistry contextMenuRegistry;
    private final PluginConfigRegistry pluginConfigRegistry;
    private final UiHostCapabilityService uiHostCapabilityService;

    public CorePluginContext(final Dependencies dependencies) {
        this(dependencies, new DefaultCubismServicesFactory());
    }

    CorePluginContext(final Dependencies dependencies, final CubismServicesFactory cubismServicesFactory) {
        this.dependencies = Objects.requireNonNull(dependencies, "dependencies");
        this.cubismServices = Objects.requireNonNull(cubismServicesFactory, "cubismServicesFactory")
            .create(this.dependencies);
        this.mainToolbarRegistry = dependencies.mainToolbar();
        this.paletteToolbarRegistry = dependencies.paletteToolbar();
        this.contextMenuRegistry = dependencies.contextMenu();
        this.pluginConfigRegistry = dependencies.config();
        this.uiHostCapabilityService = new RuntimeUiHostCapabilityService(
            PermissionChecker.from(new CubismPermissionGate(
                this.dependencies.descriptor().id(),
                this.dependencies.permissions(),
                this.dependencies.cubismAuditSink(),
                this.dependencies.clock()
            )),
            this.dependencies.descriptor().id(),
            this.dependencies.uiHostStateSource(),
            this.dependencies.disposableScope()
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
    public CubismReadCapabilityService cubismRead() {
        return cubismServices.cubismReadCapabilityService();
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
    public UiHostCapabilityService uiHost() {
        return uiHostCapabilityService;
    }

    @Override
    public ContextMenuRegistry contextMenu() {
        return contextMenuRegistry;
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

    /**
     * Production dependencies of a plugin context. The convenience constructor derives
     * {@link #permissions} from {@link PluginDescriptor#permissions()} and constructs the
     * runtime registries from that list. This is the only path intended for plugin loading.
     *
     * <p>The canonical record constructor accepts a caller-supplied {@code permissions} list
     * and pre-built registries; it exists for tests and internal runtime wiring only. Callers
     * outside the runtime package should never use it to load a plugin with permissions that
     * differ from its descriptor.</p>
     */
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
        ContextMenuRegistry contextMenu,
        PluginConfigRegistry config,
        UiScheduler uiScheduler,
        RuntimeScheduler runtimeScheduler,
        DiagnosticReport diagnostics,
        DisposableScope disposableScope,
        HostSnapshotSource hostSnapshotSource,
        M12ReadSnapshotSource m12ReadSnapshotSource,
        UiHostStateSource uiHostStateSource,
        Consumer<CubismFacadeAuditEvent> cubismAuditSink,
        Clock clock
    ) {
        /**
         * Production convenience constructor: all runtime registries are created from the
         * permissions declared in the plugin descriptor. This guarantees that the runtime
         * permission model cannot diverge from the descriptor.
         */
        public Dependencies(
            PluginDescriptor descriptor,
            PluginLogger logger,
            PluginPaths paths,
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
                uiScheduler,
                runtimeScheduler,
                diagnostics,
                disposableScope,
                hostSnapshotSource,
                M12ReadSnapshotSource.EMPTY,
                UiHostStateSource.DEFAULT,
                cubismAuditSink,
                clock
            );
        }

        public Dependencies(
            PluginDescriptor descriptor,
            PluginLogger logger,
            PluginPaths paths,
            UiScheduler uiScheduler,
            RuntimeScheduler runtimeScheduler,
            DiagnosticReport diagnostics,
            DisposableScope disposableScope,
            HostSnapshotSource hostSnapshotSource,
            M12ReadSnapshotSource m12ReadSnapshotSource,
            UiHostStateSource uiHostStateSource,
            Consumer<CubismFacadeAuditEvent> cubismAuditSink,
            Clock clock
        ) {
            this(
                descriptor,
                logger,
                paths,
                uiScheduler,
                runtimeScheduler,
                diagnostics,
                disposableScope,
                hostSnapshotSource,
                m12ReadSnapshotSource,
                uiHostStateSource,
                cubismAuditSink,
                clock,
                defaultServices(
                    descriptor,
                    permissionsFromDescriptor(descriptor),
                    paths,
                    runtimeScheduler,
                    cubismAuditSink,
                    clock,
                    logger
                )
            );
        }

        private static List<PluginPermission> permissionsFromDescriptor(PluginDescriptor descriptor) {
            return descriptor.permissions().stream()
                .<PluginPermission>map(ref -> new DescriptorPermission(ref.id(), ref.scope(), ref.reason().orElse("")))
                .toList();
        }

        private record DescriptorPermission(String id, String scope, String reason) implements PluginPermission {
        }

        private Dependencies(
            PluginDescriptor descriptor,
            PluginLogger logger,
            PluginPaths paths,
            UiScheduler uiScheduler,
            RuntimeScheduler runtimeScheduler,
            DiagnosticReport diagnostics,
            DisposableScope disposableScope,
            HostSnapshotSource hostSnapshotSource,
            M12ReadSnapshotSource m12ReadSnapshotSource,
            UiHostStateSource uiHostStateSource,
            Consumer<CubismFacadeAuditEvent> cubismAuditSink,
            Clock clock,
            DefaultServices services
        ) {
            this(
                descriptor,
                logger,
                paths,
                permissionsFromDescriptor(descriptor),
                services.eventBus,
                services.actions,
                services.menus,
                services.mainToolbar,
                services.paletteToolbar,
                services.contextMenu,
                services.config,
                uiScheduler,
                runtimeScheduler,
                diagnostics,
                disposableScope,
                hostSnapshotSource,
                m12ReadSnapshotSource,
                uiHostStateSource,
                cubismAuditSink,
                clock
            );
        }

        private static DefaultServices defaultServices(
            PluginDescriptor descriptor,
            List<PluginPermission> permissions,
            PluginPaths paths,
            RuntimeScheduler runtimeScheduler,
            Consumer<CubismFacadeAuditEvent> cubismAuditSink,
            Clock clock,
            PluginLogger logger
        ) {
            PermissionChecker checker = PermissionChecker.from(
                new CubismPermissionGate(descriptor.id(), permissions, cubismAuditSink, clock)
            );
            Consumer<StartupReport.DiagnosticProblem> diagnosticSink = problem ->
                logger.warn(problem.code() + ": " + problem.message() + " @ " + problem.path());
            return new DefaultServices(
                new RuntimeEventBus(runtimeScheduler, descriptor.id(), checker),
                new RuntimeActionRegistry(runtimeScheduler, diagnosticSink, descriptor.id(), checker),
                new RuntimeMenuRegistry(runtimeScheduler, descriptor.id(), checker),
                new RuntimeMainToolbarRegistry(checker, runtimeScheduler, descriptor.id()),
                new RuntimePaletteToolbarRegistry(checker, runtimeScheduler, descriptor.id()),
                new RuntimeContextMenuRegistry(checker, descriptor.id()),
                new RuntimePluginConfigRegistry(checker, runtimeScheduler, paths.dataDir(), descriptor.id(), diagnosticSink)
            );
        }

        private record DefaultServices(
            EventBus eventBus,
            ActionRegistry actions,
            MenuRegistry menus,
            MainToolbarRegistry mainToolbar,
            PaletteToolbarRegistry paletteToolbar,
            ContextMenuRegistry contextMenu,
            PluginConfigRegistry config
        ) {
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
            contextMenu = Objects.requireNonNull(contextMenu, "contextMenu");
            config = Objects.requireNonNull(config, "config");
            uiScheduler = Objects.requireNonNull(uiScheduler, "uiScheduler");
            runtimeScheduler = Objects.requireNonNull(runtimeScheduler, "runtimeScheduler");
            diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
            disposableScope = Objects.requireNonNull(disposableScope, "disposableScope");
            hostSnapshotSource = Objects.requireNonNull(hostSnapshotSource, "hostSnapshotSource");
            m12ReadSnapshotSource = Objects.requireNonNull(m12ReadSnapshotSource, "m12ReadSnapshotSource");
            uiHostStateSource = Objects.requireNonNull(uiHostStateSource, "uiHostStateSource");
            cubismAuditSink = Objects.requireNonNull(cubismAuditSink, "cubismAuditSink");
            clock = Objects.requireNonNull(clock, "clock");
        }
    }
}

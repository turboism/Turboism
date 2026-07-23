package dev.turboism.tests.plugin;

import dev.turboism.adapter.cubism.CubismFacadeImpl;
import dev.turboism.adapter.cubism.HostSnapshotSource;
import dev.turboism.config.RuntimePluginConfigRegistry;
import dev.turboism.core.action.RuntimeActionRegistry;
import dev.turboism.core.diagnostics.PluginWorkBudgetEvent;
import dev.turboism.core.diagnostics.StartupReport;
import dev.turboism.core.event.RuntimeEventBus;
import dev.turboism.core.menu.RuntimeMenuRegistry;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.permissions.CubismPermissionGate;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.CubismRuntimeSnapshot;
import dev.turboism.sdk.cubism.SelectionSnapshot;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.Parameter;
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
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.UiHostCapabilityService;
import dev.turboism.sdk.ui.UiScheduler;
import dev.turboism.sdk.ui.context.ContextMenuRegistry;
import dev.turboism.sdk.ui.toolbar.MainToolbarRegistry;
import dev.turboism.sdk.ui.toolbar.PaletteToolbarRegistry;
import dev.turboism.test.ui.FakeDirectUiScheduler;
import dev.turboism.test.ui.toolbar.FakeToolbarVisibilityTracker;
import dev.turboism.ui.RuntimeUiHostCapabilityService;
import dev.turboism.ui.UiHostStateSource;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

final class M8PluginTestSupport {

    static final String PLUGIN_ID = "dev.turboism.plugin.probe";
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-08T00:00:00Z"), ZoneOffset.UTC);

    private M8PluginTestSupport() {
    }

    static Harness harness(Path dataDir, PermissionChecker permissions) {
        return harness(dataDir, permissions, UiHostStateSource.DEFAULT, null);
    }

    static Harness harness(
        Path dataDir,
        PermissionChecker permissions,
        UiHostStateSource uiHostStateSource,
        CubismReadCapabilityService cubismRead
    ) {
        return harness(
            dataDir,
            permissions,
            uiHostStateSource,
            cubismRead,
            defaultCubismFacade(permissions, cubismRead)
        );
    }

    static Harness harness(
        Path dataDir,
        PermissionChecker permissions,
        UiHostStateSource uiHostStateSource,
        CubismReadCapabilityService cubismRead,
        CubismFacade cubismFacade
    ) {
        List<PluginWorkBudgetEvent> events = new CopyOnWriteArrayList<>();
        RuntimeScheduler scheduler = new RuntimeScheduler(
            new DefaultWorkBudgetPolicy(),
            new PluginWorkExecutorRegistry(1, 8, events::add, CLOCK),
            (task, callback) -> {
                callback.run();
                return java.util.concurrent.CompletableFuture.completedFuture(
                    dev.turboism.core.runtime.sidecar.SidecarResult.success("")
                );
            },
            events::add
        );
        StartupReport report = new StartupReport();
        FakeToolbarVisibilityTracker toolbarTracker = new FakeToolbarVisibilityTracker();
        MenuTracker menuTracker = new MenuTracker();
        DisposableScope scope = new DisposableScope();
        RuntimeActionRegistry actions = new RuntimeActionRegistry(scheduler, problem -> addProblem(report, problem), PLUGIN_ID, permissions);
        RuntimeMenuRegistry menus = new RuntimeMenuRegistry(scheduler, PLUGIN_ID, permissions);
        dev.turboism.ui.toolbar.RuntimeMainToolbarRegistry mainToolbarDelegate = new dev.turboism.ui.toolbar.RuntimeMainToolbarRegistry(permissions, scheduler, PLUGIN_ID);
        dev.turboism.ui.toolbar.RuntimePaletteToolbarRegistry paletteToolbarDelegate = new dev.turboism.ui.toolbar.RuntimePaletteToolbarRegistry(permissions, scheduler, PLUGIN_ID);
        dev.turboism.ui.context.RuntimeContextMenuRegistry contextMenu = new dev.turboism.ui.context.RuntimeContextMenuRegistry(permissions, PLUGIN_ID);
        RuntimeMainToolbarRegistryAdapter mainToolbar = new RuntimeMainToolbarRegistryAdapter(mainToolbarDelegate, toolbarTracker);
        RuntimePaletteToolbarRegistryAdapter paletteToolbar = new RuntimePaletteToolbarRegistryAdapter(paletteToolbarDelegate, toolbarTracker);
        RuntimePluginConfigRegistry config = new RuntimePluginConfigRegistry(permissions, scheduler, dataDir, "dev.turboism.plugin.m8-test", problem -> addProblem(report, problem));
        RuntimeUiHostCapabilityService uiHost = new RuntimeUiHostCapabilityService(
            permissions,
            PLUGIN_ID,
            uiHostStateSource,
            scope
        );
        TestPluginContext context = new TestPluginContext(
            scope,
            actions,
            new RuntimeEventBus(scheduler, PLUGIN_ID, permissions),
            new MenuRegistryAdapter(menus, menuTracker),
            mainToolbar,
            paletteToolbar,
            contextMenu,
            config,
            uiHost,
            PermissionCheckedCubismReadCapabilityService.wrap(permissions, cubismRead),
            cubismFacade,
            dataDir
        );
        return new Harness(
            scheduler,
            actions,
            context,
            uiHost,
            toolbarTracker,
            menuTracker,
            mainToolbar,
            paletteToolbar,
            config,
            report,
            events
        );
    }

    static PluginDescriptor descriptor() {
        return new PluginDescriptor() {
            @Override public String id() { return PLUGIN_ID; }
            @Override public String name() { return "M8 Probe"; }
            @Override public String version() { return "0.1.0"; }
            @Override public String description() { return "M8 test probe"; }
            @Override public List<String> entrypoints() { return List.of("dev.turboism.test.plugin.PermissionProbePlugin"); }
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
            @Override public Environment environment() { return new TestEnvironment(); }
        };
    }

    private static CubismFacade defaultCubismFacade(
        final PermissionChecker permissions,
        final CubismReadCapabilityService cubismRead
    ) {
        if (cubismRead == null) {
            return NoCubismFacade.INSTANCE;
        }
        final TestCubismModel model = TestCubismModel.from(cubismRead);
        final List<PluginPermission> granted = allCubismPermissions().stream()
            .filter(permission -> hasPermission(permissions, permission.id()))
            .toList();
        return new CubismFacadeImpl(
            EmptyHostSnapshotSource.INSTANCE,
            new CubismPermissionGate(
                PLUGIN_ID,
                granted,
                ignored -> { },
                CLOCK
            ),
            () -> model
        );
    }

    private static List<PluginPermission> allCubismPermissions() {
        return List.of(
            permission(CubismFacadeImpl.PROJECT_READ_PERMISSION),
            permission(CubismFacadeImpl.MODEL_READ_PERMISSION),
            permission(CubismFacadeImpl.MODEL_WRITE_PERMISSION)
        );
    }

    private static boolean hasPermission(
        final PermissionChecker checker,
        final String permissionId
    ) {
        try {
            checker.check(permissionId, "test-harness.probe");
            return true;
        } catch (dev.turboism.sdk.permission.CubismPermissionException denied) {
            return false;
        }
    }

    private static PluginPermission permission(final String id) {
        return new PluginPermission() {
            @Override public String id() { return id; }
            @Override public String scope() { return "application"; }
            @Override public String reason() { return "test harness"; }
        };
    }

    private static void addProblem(StartupReport report, StartupReport.DiagnosticProblem problem) {
        report.addProblem(problem.code(), problem.message(), problem.path(), problem.severity());
    }

    record Harness(
        RuntimeScheduler scheduler,
        RuntimeActionRegistry actions,
        TestPluginContext context,
        RuntimeUiHostCapabilityService uiHost,
        FakeToolbarVisibilityTracker toolbarTracker,
        MenuTracker menuTracker,
        RuntimeMainToolbarRegistryAdapter mainToolbar,
        RuntimePaletteToolbarRegistryAdapter paletteToolbar,
        RuntimePluginConfigRegistry config,
        StartupReport report,
        List<PluginWorkBudgetEvent> workEvents
    ) implements AutoCloseable {
        void executeAction(final String actionId) {
            actions.execute(actionId, new ActionRegistry.ActionContext() {
            });
        }

        @Override
        public void close() {
            scheduler.shutdown();
        }
    }

    static final class MenuTracker {
        private final Set<String> visibleActionIds = ConcurrentHashMap.newKeySet();
        void markVisible(String actionId) { visibleActionIds.add(actionId); }
        void markHidden(String actionId) { visibleActionIds.remove(actionId); }
        boolean isVisible(String actionId) { return visibleActionIds.contains(actionId); }
    }

    private record TestEnvironment() implements PluginDescriptor.Environment {
        @Override public boolean requiresCubism() { return false; }
        @Override public String ui() { return "none"; }
    }

    private record TestPaths(Path dataDir) implements PluginPaths {
        @Override public Path logsDir() { return dataDir.resolveSibling("logs"); }
        @Override public Path stateDir() { return dataDir.resolveSibling("state"); }
        @Override public Path cacheDir() { return dataDir.resolveSibling("cache"); }
    }

    private record TestDiagnostics() implements DiagnosticReport {
        @Override public Instant createdAt() { return CLOCK.instant(); }
        @Override public List<Problem> problems() { return List.of(); }
    }

    private static final class TestLogger implements PluginLogger {
        @Override public void debug(String message) { }
        @Override public void info(String message) { }
        @Override public void warn(String message) { }
        @Override public void error(String message) { }
        @Override public void error(String message, Throwable throwable) { }
    }

    static final class TestPluginContext implements PluginContext {
        private final DisposableScope scope;
        private final ActionRegistry actions;
        private final EventBus eventBus;
        private final MenuRegistry menus;
        private final MainToolbarRegistry mainToolbar;
        private final PaletteToolbarRegistry paletteToolbar;
        private final ContextMenuRegistry contextMenu;
        private final PluginConfigRegistry config;
        private final RuntimeUiHostCapabilityService uiHost;
        private final CubismReadCapabilityService cubismRead;
        private final CubismFacade cubismFacade;
        private final PluginPaths paths;

        TestPluginContext(
            DisposableScope scope,
            ActionRegistry actions,
            EventBus eventBus,
            MenuRegistry menus,
            MainToolbarRegistry mainToolbar,
            PaletteToolbarRegistry paletteToolbar,
            ContextMenuRegistry contextMenu,
            PluginConfigRegistry config,
            RuntimeUiHostCapabilityService uiHost,
            CubismReadCapabilityService cubismRead,
            CubismFacade cubismFacade,
            Path dataDir
        ) {
            this.scope = scope;
            this.actions = actions;
            this.eventBus = eventBus;
            this.menus = menus;
            this.mainToolbar = mainToolbar;
            this.paletteToolbar = paletteToolbar;
            this.contextMenu = contextMenu;
            this.config = config;
            this.uiHost = uiHost;
            this.cubismRead = cubismRead;
            this.cubismFacade = java.util.Objects.requireNonNull(cubismFacade, "cubismFacade");
            this.paths = new TestPaths(dataDir);
        }

        @Override public PluginDescriptor descriptor() { return M8PluginTestSupport.descriptor(); }
        @Override public PluginLogger logger() { return new TestLogger(); }
        @Override public PluginPaths paths() { return paths; }
        @Override public CubismFacade cubism() { return cubismFacade; }
        @Override public CubismReadCapabilityService cubismRead() {
            if (cubismRead == null) {
                throw new UnsupportedOperationException("cubismRead service is not available");
            }
            return cubismRead;
        }
        @Override public List<PluginPermission> permissions() { return List.of(); }
        @Override public EventBus eventBus() { return eventBus; }
        @Override public ActionRegistry actions() { return actions; }
        @Override public MenuRegistry menus() { return menus; }
        @Override public MainToolbarRegistry mainToolbar() { return mainToolbar; }
        @Override public PaletteToolbarRegistry paletteToolbar() { return paletteToolbar; }
        @Override public ContextMenuRegistry contextMenu() { return contextMenu; }
        @Override public UiHostCapabilityService uiHost() { return uiHost; }
        RuntimeUiHostCapabilityService runtimeUiHost() { return uiHost; }
        @Override public PluginConfigRegistry config() { return config; }
        @Override public UiScheduler uiScheduler() { return new FakeDirectUiScheduler(); }
        @Override public DiagnosticReport diagnostics() { return new TestDiagnostics(); }
        @Override public DisposableScope disposableScope() { return scope; }
    }

    private record RuntimeMainToolbarRegistryAdapter(
        MainToolbarRegistry delegate,
        FakeToolbarVisibilityTracker tracker
    ) implements MainToolbarRegistry {
        @Override
        public Registration contribute(MainToolbarContribution contribution) {
            Registration registration = delegate.contribute(contribution);
            tracker.markVisible(PLUGIN_ID, contribution.contributionId(), "main");
            return () -> {
                registration.close();
                tracker.markHidden(PLUGIN_ID, contribution.contributionId());
            };
        }
    }

    private record RuntimePaletteToolbarRegistryAdapter(
        PaletteToolbarRegistry delegate,
        FakeToolbarVisibilityTracker tracker
    ) implements PaletteToolbarRegistry {
        @Override
        public Registration contribute(PaletteToolbarContribution contribution) {
            Registration registration = delegate.contribute(contribution);
            tracker.markVisible(PLUGIN_ID, contribution.contributionId(), "palette");
            return () -> {
                registration.close();
                tracker.markHidden(PLUGIN_ID, contribution.contributionId());
            };
        }
    }

    private record MenuRegistryAdapter(MenuRegistry delegate, MenuTracker tracker) implements MenuRegistry {
        @Override
        public Registration contribute(MenuContribution contribution) {
            Registration registration = delegate.contribute(contribution);
            tracker.markVisible(contribution.actionId());
            return () -> {
                registration.close();
                tracker.markHidden(contribution.actionId());
            };
        }
    }

    private enum EmptyHostSnapshotSource implements HostSnapshotSource {
        INSTANCE;

        private static final HostSelection EMPTY_SELECTION = new HostSelection(
            List.of(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()
        );

        @Override public Optional<HostProject> activeProject() { return Optional.empty(); }
        @Override public Optional<HostDocument> activeDocument() { return Optional.empty(); }
        @Override public Optional<HostModel> activeModel() { return Optional.empty(); }
        @Override public HostSelection selection() { return EMPTY_SELECTION; }
        @Override public boolean isHostPresent() { return true; }
        @Override public long invalidationToken() { return 0L; }
    }

    private static final class TestCubismModel implements CubismModel {
        private final List<TestParameter> parameters;

        private TestCubismModel(final List<TestParameter> parameters) {
            this.parameters = parameters;
        }

        private static TestCubismModel from(final CubismReadCapabilityService cubismRead) {
            try {
                return new TestCubismModel(cubismRead.parameters().stream()
                    .map(snapshot -> new TestParameter(
                        snapshot.id(),
                        (float) snapshot.value(),
                        (float) snapshot.minValue(),
                        (float) snapshot.maxValue(),
                        (float) snapshot.defaultValue()
                    ))
                    .toList());
            } catch (UnsupportedOperationException unavailable) {
                return new TestCubismModel(List.of());
            }
        }

        @Override public ModelId id() { return new ModelId("model-1"); }
        @Override public dev.turboism.sdk.cubism.model.Parameters parameters() {
            return new dev.turboism.sdk.cubism.model.Parameters() {
                @Override public List<Parameter> all() { return List.copyOf(parameters); }
                @Override public Parameter find(final ParameterId id) {
                    return parameters.stream()
                        .filter(parameter -> parameter.id().equals(id))
                        .findFirst()
                        .orElseThrow(() -> new java.util.NoSuchElementException(id.value()));
                }
            };
        }
        @Override public dev.turboism.sdk.cubism.model.Parts parts() { throw unavailable(); }
        @Override public dev.turboism.sdk.cubism.model.Drawables drawables() { throw unavailable(); }
        @Override public dev.turboism.sdk.cubism.model.Deformers deformers() { throw unavailable(); }
        @Override public dev.turboism.sdk.cubism.model.Glues glues() { throw unavailable(); }
        @Override public void update() { throw unavailable(); }

        private static UnsupportedOperationException unavailable() {
            return new UnsupportedOperationException("not used by this test harness");
        }
    }

    private static final class TestParameter implements Parameter {
        private final ParameterId id;
        private final float minimum;
        private final float maximum;
        private final float defaultValue;
        private float value;

        private TestParameter(
            final String id,
            final float value,
            final float minimum,
            final float maximum,
            final float defaultValue
        ) {
            this.id = new ParameterId(id);
            this.value = value;
            this.minimum = minimum;
            this.maximum = maximum;
            this.defaultValue = defaultValue;
        }

        @Override public ParameterId id() { return id; }
        @Override public float getValue() { return value; }
        @Override public float getMinimumValue() { return minimum; }
        @Override public float getMaximumValue() { return maximum; }
        @Override public float getDefaultValue() { return defaultValue; }
        @Override public void setValue(final float value) { this.value = value; }
    }

    private enum NoCubismFacade implements CubismFacade {
        INSTANCE;

        @Override public CubismRuntimeSnapshot runtime() {
            return new CubismRuntimeSnapshot(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                new SelectionSnapshot(List.of(), Optional.empty(), Optional.empty(), Optional.empty()),
                List.of(),
                List.of(),
                List.of(),
                List.of()
            );
        }
        @Override public Optional<dev.turboism.sdk.cubism.ProjectSnapshot> activeProject() { return Optional.empty(); }
        @Override public Optional<dev.turboism.sdk.cubism.DocumentSnapshot> activeDocument() { return Optional.empty(); }
        @Override public Optional<dev.turboism.sdk.cubism.ModelSnapshot> activeModel() { return Optional.empty(); }
        @Override public boolean isHostPresent() { return false; }
        @Override public dev.turboism.sdk.cubism.transaction.TransactionManager transactionManager() { throw new UnsupportedOperationException("transaction manager is not available"); }
    }
}

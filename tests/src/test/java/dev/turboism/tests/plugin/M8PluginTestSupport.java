package dev.turboism.tests.plugin;

import dev.turboism.config.RuntimePluginConfigRegistry;
import dev.turboism.core.action.RuntimeActionRegistry;
import dev.turboism.core.diagnostics.CallbackBudgetEvent;
import dev.turboism.core.diagnostics.StartupReport;
import dev.turboism.core.event.RuntimeEventBus;
import dev.turboism.core.menu.RuntimeMenuRegistry;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.PluginExecutorRegistry;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.CubismRuntimeSnapshot;
import dev.turboism.sdk.cubism.SelectionSnapshot;
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
import dev.turboism.sdk.ui.UiScheduler;
import dev.turboism.sdk.ui.toolbar.MainToolbarRegistry;
import dev.turboism.sdk.ui.toolbar.PaletteToolbarRegistry;
import dev.turboism.test.ui.FakeDirectUiScheduler;
import dev.turboism.test.ui.toolbar.FakeToolbarVisibilityTracker;

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
        List<CallbackBudgetEvent> events = new CopyOnWriteArrayList<>();
        RuntimeScheduler scheduler = new RuntimeScheduler(
            new DefaultWorkBudgetPolicy(),
            new PluginExecutorRegistry(1, 8, events::add, CLOCK),
            SidecarDispatcher.noop(),
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
        RuntimeMainToolbarRegistryAdapter mainToolbar = new RuntimeMainToolbarRegistryAdapter(mainToolbarDelegate, toolbarTracker);
        RuntimePaletteToolbarRegistryAdapter paletteToolbar = new RuntimePaletteToolbarRegistryAdapter(paletteToolbarDelegate, toolbarTracker);
        RuntimePluginConfigRegistry config = new RuntimePluginConfigRegistry(permissions, scheduler, dataDir, problem -> addProblem(report, problem));
        TestPluginContext context = new TestPluginContext(
            scope,
            actions,
            new RuntimeEventBus(scheduler, PLUGIN_ID, permissions),
            new MenuRegistryAdapter(menus, menuTracker),
            mainToolbar,
            paletteToolbar,
            config,
            dataDir
        );
        return new Harness(scheduler, context, toolbarTracker, menuTracker, mainToolbar, paletteToolbar, config, report);
    }

    static PluginDescriptor descriptor() {
        return new PluginDescriptor() {
            @Override public String id() { return PLUGIN_ID; }
            @Override public String name() { return "M8 Probe"; }
            @Override public String version() { return "0.1.0"; }
            @Override public String description() { return "M8 test probe"; }
            @Override public Map<String, String> entrypoints() { return Map.of("plugin", "dev.turboism.test.plugin.PermissionProbePlugin"); }
            @Override public String turboismApi() { return "[0.1.0,0.2.0)"; }
            @Override public List<Author> authors() { return List.of(); }
            @Override public String license() { return "Project License"; }
            @Override public Optional<String> homepage() { return Optional.empty(); }
            @Override public List<DependencyRef> dependencies() { return List.of(); }
            @Override public List<PermissionRef> permissions() { return List.of(); }
            @Override public List<String> capabilities() { return List.of(); }
            @Override public Environment environment() { return new TestEnvironment(); }
        };
    }

    private static void addProblem(StartupReport report, StartupReport.DiagnosticProblem problem) {
        report.addProblem(problem.code(), problem.message(), problem.path(), problem.severity());
    }

    record Harness(
        RuntimeScheduler scheduler,
        TestPluginContext context,
        FakeToolbarVisibilityTracker toolbarTracker,
        MenuTracker menuTracker,
        RuntimeMainToolbarRegistryAdapter mainToolbar,
        RuntimePaletteToolbarRegistryAdapter paletteToolbar,
        RuntimePluginConfigRegistry config,
        StartupReport report
    ) implements AutoCloseable {
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
        private final PluginConfigRegistry config;
        private final PluginPaths paths;

        TestPluginContext(
            DisposableScope scope,
            ActionRegistry actions,
            EventBus eventBus,
            MenuRegistry menus,
            MainToolbarRegistry mainToolbar,
            PaletteToolbarRegistry paletteToolbar,
            PluginConfigRegistry config,
            Path dataDir
        ) {
            this.scope = scope;
            this.actions = actions;
            this.eventBus = eventBus;
            this.menus = menus;
            this.mainToolbar = mainToolbar;
            this.paletteToolbar = paletteToolbar;
            this.config = config;
            this.paths = new TestPaths(dataDir);
        }

        @Override public PluginDescriptor descriptor() { return descriptor(); }
        @Override public PluginLogger logger() { return new TestLogger(); }
        @Override public PluginPaths paths() { return paths; }
        @Override public CubismFacade cubism() { return NoCubismFacade.INSTANCE; }
        @Override public List<PluginPermission> permissions() { return List.of(); }
        @Override public EventBus eventBus() { return eventBus; }
        @Override public ActionRegistry actions() { return actions; }
        @Override public MenuRegistry menus() { return menus; }
        @Override public MainToolbarRegistry mainToolbar() { return mainToolbar; }
        @Override public PaletteToolbarRegistry paletteToolbar() { return paletteToolbar; }
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
    }
}

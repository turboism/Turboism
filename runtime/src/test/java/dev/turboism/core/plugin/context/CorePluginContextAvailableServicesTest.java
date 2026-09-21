package dev.turboism.core.plugin.context;

import dev.turboism.adapter.RuntimeHostAdapters;
import dev.turboism.adapter.cubism.HostSnapshotSource;
import dev.turboism.core.diagnostics.PluginWorkBudgetEvent;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.sdk.cubism.filechooser.FileChooserHistoryService;
import dev.turboism.sdk.diagnostics.DiagnosticReport;
import dev.turboism.sdk.mcp.McpConnectionService;
import dev.turboism.sdk.mcp.McpHttpConnection;
import dev.turboism.sdk.performance.PerformanceProbeService;
import dev.turboism.sdk.performance.PerformanceSnapshot;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.PluginPaths;
import dev.turboism.sdk.plugin.PluginService;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.script.ScriptDescriptor;
import dev.turboism.sdk.script.ScriptId;
import dev.turboism.sdk.script.ScriptRunHandle;
import dev.turboism.sdk.script.ScriptRunRequest;
import dev.turboism.sdk.script.ScriptService;
import dev.turboism.sdk.ui.UiScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers {@link CorePluginContext#availableServices()}: the reported set must reflect what the
 * context installed — null-installed and {@code unavailable()}-sentinel slots are absent even
 * behind the version-gating proxy, and late installs become visible on the next call.
 */
class CorePluginContextAvailableServicesTest {

    private static final Clock CLOCK =
        Clock.fixed(Instant.parse("2026-09-12T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void safeModeReportsSentinelsAndNullsAbsentAndInstalledServicesPresent() {
        final CorePluginContext context =
            new CorePluginContext(dependencies(TEMP), RuntimeHostAdapters.safeMode());
        final Set<PluginService> available = context.availableServices();

        final Set<PluginService> expectedAbsent = Set.of(
            PluginService.LOCALIZATION,
            PluginService.TASKS,
            PluginService.HOST_READS,
            PluginService.STORAGE,
            PluginService.SCRIPTS,
            PluginService.USER_FILES,
            PluginService.FILE_CHOOSER_HISTORY,
            PluginService.PHYSICS_EDITOR,
            PluginService.SCENE_TABLE,
            PluginService.CUBISM_LOG,
            PluginService.APPEARANCE,
            PluginService.RECENT_FILES,
            PluginService.SCREENSHOTS,
            PluginService.RECENT_PREVIEWS,
            PluginService.WORKSPACE,
            PluginService.WORKSPACE_LAYOUT,
            PluginService.RUNTIME_SETTINGS,
            PluginService.MCP_CONNECTIONS
        );
        final Set<PluginService> expectedPresent = Set.of(
            PluginService.PARAMETER_QUERY,
            PluginService.SELECTION_QUERY,
            PluginService.MODEL_HIERARCHY_QUERY,
            PluginService.CUBISM_READ,
            PluginService.MODEL_OBJECTS,
            PluginService.CUBISM_CLIP_MASKS,
            PluginService.MESH_MIRROR_AXIS,
            PluginService.MESH_EDIT,
            PluginService.MESH_EDIT_PARTICIPATION,
            PluginService.MESH_MIRROR_COUNTERPARTS,
            PluginService.MESH_MIRROR_TOOL_ELIGIBILITY,
            PluginService.MESH_MIRROR_MOVE_PARTICIPATION,
            PluginService.MESH_EDIT_UI,
            PluginService.EDITOR_COMMANDS,
            PluginService.BACKUP,
            PluginService.MAIN_TOOLBAR,
            PluginService.PALETTE_TOOLBAR,
            PluginService.PALETTE_FILTER,
            PluginService.UI_HOST,
            PluginService.HOST_DIALOGS,
            PluginService.CONTEXT_MENU,
            PluginService.CONFIG,
            PluginService.PERFORMANCE_STATS
        );

        for (PluginService service : PluginService.values()) {
            assertEquals(
                expectedPresent.contains(service),
                available.contains(service),
                "unexpected availability for " + service
                    + " (expectedAbsent=" + expectedAbsent.contains(service) + ")"
            );
        }
        for (PluginService service : expectedAbsent) {
            assertFalse(available.contains(service), service + " should be absent in safe mode");
        }
        // Every enum member is classified by exactly one of the two sets.
        assertEquals(PluginService.values().length,
            expectedAbsent.size() + expectedPresent.size());
    }

    @Test
    void lateServiceInstallsBecomeVisibleWithoutContextReconstruction() {
        final CorePluginContext context =
            new CorePluginContext(dependencies(TEMP), RuntimeHostAdapters.safeMode());
        assertFalse(context.availableServices().contains(PluginService.SCRIPTS));
        assertFalse(context.availableServices().contains(PluginService.MCP_CONNECTIONS));

        context.installScriptService(new ScriptService() {
            @Override public List<ScriptDescriptor> list() { return List.of(); }
            @Override public Optional<ScriptDescriptor> find(final ScriptId id) {
                return Optional.empty();
            }
            @Override public ScriptRunHandle run(final ScriptRunRequest request) {
                throw new UnsupportedOperationException("test fake");
            }
        });
        context.installMcpConnectionService(new McpConnectionService() {
            @Override public Optional<McpHttpConnection> current() { return Optional.empty(); }
            @Override public Registration publish(final McpHttpConnection connection) {
                return () -> { };
            }
        });

        assertTrue(context.availableServices().contains(PluginService.SCRIPTS));
        assertTrue(context.availableServices().contains(PluginService.MCP_CONNECTIONS));
    }

    @Test
    void wrappedUnavailableSentinelIsReportedAbsent() {
        final CorePluginContext context = new CorePluginContext(
            dependencies(TEMP),
            RuntimeHostAdapters.safeMode(),
            null,
            null,
            null,
            null,
            null,
            FileChooserHistoryService.unavailable()
        );
        assertFalse(context.availableServices().contains(PluginService.FILE_CHOOSER_HISTORY));
    }

    @Test
    void performanceSubscriptionsBelongToTheActualContextScope() throws Exception {
        final CorePluginContext.Dependencies dependencies = performanceDependencies(TEMP);
        final ManualPerformanceProbe shared = new ManualPerformanceProbe();
        dependencies.eventBroker().observationBaseline(PerformanceProbeService.class).set(shared);
        final CorePluginContext context = new CorePluginContext(dependencies, RuntimeHostAdapters.safeMode());
        final PerformanceProbeService retained = context.performanceStats();
        try {
            retained.sample(Duration.ofSeconds(1), ignored -> { });
            assertEquals(1, shared.consumers.size());
            dependencies.disposableScope().close();
            assertEquals(0, shared.consumers.size(), "scope disposal must detach shared registrations");
            assertThrows(IllegalStateException.class, retained::snapshot);
            assertThrows(IllegalStateException.class,
                () -> retained.sample(Duration.ofSeconds(1), ignored -> { }));
            assertEquals(0, shared.closes.get(), "a plugin never owns the shared sampler");
        } finally {
            dependencies.disposableScope().close();
            dependencies.runtimeScheduler().shutdown();
        }
    }

    @Test
    void disposingOnePerformanceScopePreservesAnotherPluginSubscription() throws Exception {
        final CorePluginContext.Dependencies first = performanceDependencies(TEMP);
        final CorePluginContext.Dependencies second = performanceDependencies(TEMP);
        final ManualPerformanceProbe shared = new ManualPerformanceProbe();
        first.eventBroker().observationBaseline(PerformanceProbeService.class).set(shared);
        second.eventBroker().observationBaseline(PerformanceProbeService.class).set(shared);
        final AtomicInteger firstCalls = new AtomicInteger();
        final AtomicInteger secondCalls = new AtomicInteger();
        try {
            new CorePluginContext(first, RuntimeHostAdapters.safeMode()).performanceStats()
                .sample(Duration.ofSeconds(1), ignored -> firstCalls.incrementAndGet());
            new CorePluginContext(second, RuntimeHostAdapters.safeMode()).performanceStats()
                .sample(Duration.ofSeconds(1), ignored -> secondCalls.incrementAndGet());
            first.disposableScope().close();
            shared.emit();
            assertEquals(0, firstCalls.get());
            assertEquals(1, secondCalls.get());
            assertEquals(1, shared.consumers.size());
            assertEquals(0, shared.closes.get());
        } finally {
            first.disposableScope().close();
            second.disposableScope().close();
            first.runtimeScheduler().shutdown();
            second.runtimeScheduler().shutdown();
        }
    }

    @Test
    void lateDelegateRegistrationIsClosedWhenScopeDisposesDuringAdmission() throws Exception {
        final CorePluginContext.Dependencies dependencies = performanceDependencies(TEMP);
        final ManualPerformanceProbe shared = new ManualPerformanceProbe();
        dependencies.eventBroker().observationBaseline(PerformanceProbeService.class).set(shared);
        final PerformanceProbeService service =
            new CorePluginContext(dependencies, RuntimeHostAdapters.safeMode()).performanceStats();
        shared.onAdmission = () -> {
            try { dependencies.disposableScope().close(); }
            catch (Exception failure) { throw new IllegalStateException(failure); }
        };
        try {
            assertThrows(IllegalStateException.class,
                () -> service.sample(Duration.ofSeconds(1), ignored -> { }));
            assertEquals(0, shared.consumers.size(), "late returned delegate handles cannot be orphaned");
        } finally {
            dependencies.disposableScope().close();
            dependencies.runtimeScheduler().shutdown();
        }
    }

    @Test
    void fallbackSamplerAndLazyAccessFailClosedWithThePluginScope() throws Exception {
        final CorePluginContext.Dependencies dependencies = performanceDependencies(TEMP);
        final CorePluginContext context = new CorePluginContext(dependencies, RuntimeHostAdapters.safeMode());
        try {
            final PerformanceProbeService fallback = context.performanceStats();
            fallback.sample(Duration.ofHours(1), ignored -> { });
            dependencies.disposableScope().close();
            assertThrows(IllegalStateException.class, fallback::snapshot);
            assertThrows(IllegalStateException.class,
                () -> context.performanceStats().sample(Duration.ofHours(1), ignored -> { }));
        } finally {
            dependencies.disposableScope().close();
            dependencies.runtimeScheduler().shutdown();
        }
    }

    private static CorePluginContext.Dependencies performanceDependencies(final Path dataDir) {
        return dependencies(dataDir, List.of(new PluginDescriptor.PermissionRef() {
            @Override public String id() { return "turboism.performance.stats.read"; }
            @Override public String scope() { return "application"; }
            @Override public Optional<String> reason() { return Optional.of("scope regression"); }
        }));
    }

    private static final class ManualPerformanceProbe implements PerformanceProbeService, AutoCloseable {
        private final List<Consumer<PerformanceSnapshot>> consumers = new CopyOnWriteArrayList<>();
        private final AtomicInteger closes = new AtomicInteger();
        private Runnable onAdmission = () -> { };
        @Override public PerformanceSnapshot snapshot() {
            return new PerformanceSnapshot(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
        @Override public Registration sample(final Duration interval, final Consumer<PerformanceSnapshot> consumer) {
            onAdmission.run();
            consumers.add(consumer);
            return () -> consumers.remove(consumer);
        }
        private void emit() { consumers.forEach(consumer -> consumer.accept(snapshot())); }
        @Override public void close() { closes.incrementAndGet(); consumers.clear(); }
    }

    private static CorePluginContext.Dependencies dependencies(final Path dataDir) {
        return dependencies(dataDir, List.of());
    }

    private static CorePluginContext.Dependencies dependencies(
        final Path dataDir, final List<PluginDescriptor.PermissionRef> permissions
    ) {
        return new CorePluginContext.Dependencies(
            descriptor(permissions),
            logger(),
            paths(dataDir),
            uiScheduler(),
            scheduler(),
            diagnostics(),
            new DisposableScope(),
            noopHostSnapshotSource(),
            ignored -> { },
            CLOCK
        );
    }

    private static PluginDescriptor descriptor(final List<PluginDescriptor.PermissionRef> requestedPermissions) {
        return new PluginDescriptor() {
            @Override public String id() { return "dev.turboism.test.AvailableServicesTest"; }
            @Override public String name() { return "Available Services Test"; }
            @Override public String version() { return "0.1.0"; }
            @Override public String description() { return "Test"; }
            @Override public List<String> entrypoints() {
                return List.of("dev.turboism.test.AvailableServicesPlugin");
            }
            @Override public String turboismApi() { return "[0.1.0,0.2.0)"; }
            @Override public List<Author> authors() { return List.of(); }
            @Override public String license() { return "Project License"; }
            @Override public Optional<String> website() {
                return Optional.of("https://turboism.dev");
            }
            @Override public List<String> resources() { return List.of(); }
            @Override public I18n i18n() {
                return new I18n() {
                    @Override public String baseName() {
                        return "META-INF/turboism/i18n/messages";
                    }
                    @Override public List<String> locales() { return List.of(); }
                };
            }
            @Override public List<DependencyRef> dependencies() { return List.of(); }
            @Override public List<PermissionRef> permissions() { return requestedPermissions; }
            @Override public List<String> capabilities() { return List.of(); }
            @Override public Environment environment() {
                return new Environment() {
                    @Override public boolean requiresCubism() { return false; }
                    @Override public String ui() { return "none"; }
                };
            }
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
            @Override public Registration runOnUiThread(Runnable work) {
                work.run();
                return () -> { };
            }
            @Override public Registration runOnUiThreadLater(Runnable work, Duration delay) {
                return () -> { };
            }
        };
    }

    private static RuntimeScheduler scheduler() {
        List<PluginWorkBudgetEvent> events = new CopyOnWriteArrayList<>();
        return new RuntimeScheduler(
            new DefaultWorkBudgetPolicy(),
            new PluginWorkExecutorRegistry(1, 4, events::add, CLOCK),
            (task, callback) -> {
                callback.run();
                return java.util.concurrent.CompletableFuture.completedFuture(
                    dev.turboism.core.runtime.sidecar.SidecarResult.success("")
                );
            },
            events::add
        );
    }

    private static DiagnosticReport diagnostics() {
        return new DiagnosticReport() {
            @Override public Instant createdAt() { return CLOCK.instant(); }
            @Override public List<Problem> problems() { return List.of(); }
        };
    }

    private static HostSnapshotSource noopHostSnapshotSource() {
        return new HostSnapshotSource() {
            @Override public Optional<HostProject> activeProject() { return Optional.empty(); }
            @Override public Optional<HostDocument> activeDocument() { return Optional.empty(); }
            @Override public Optional<HostModel> activeModel() { return Optional.empty(); }
            @Override public HostSelection selection() {
                return new HostSelection(
                    List.of(), Optional.empty(), Optional.empty(), Optional.empty());
            }
            @Override public boolean isHostPresent() { return false; }
            @Override public long invalidationToken() { return 0; }
        };
    }

    @TempDir
    static Path TEMP;
}

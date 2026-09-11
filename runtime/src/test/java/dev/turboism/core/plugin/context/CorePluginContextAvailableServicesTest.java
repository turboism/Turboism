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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    private static CorePluginContext.Dependencies dependencies(final Path dataDir) {
        return new CorePluginContext.Dependencies(
            descriptor(),
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

    private static PluginDescriptor descriptor() {
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
            @Override public List<PermissionRef> permissions() { return List.of(); }
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

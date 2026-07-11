package dev.turboism.adapter.host;

import dev.turboism.adapter.RuntimeHostAdapters;
import dev.turboism.adapter.cubism.HostSnapshotSource;
import dev.turboism.adapter.cubism.ProjectWorkspaceAdapter;
import dev.turboism.core.diagnostics.CallbackBudgetEvent;
import dev.turboism.core.plugin.context.CorePluginContext;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.PluginExecutorRegistry;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.sdk.cubism.ProjectSnapshot;
import dev.turboism.sdk.cubism.WorkspaceSnapshot;
import dev.turboism.sdk.diagnostics.DiagnosticReport;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostSessionPluginContextIntegrationTest {

    private static final Clock CLOCK = Clock.fixed(
        Instant.parse("2026-07-10T00:00:00Z"),
        ZoneOffset.UTC
    );

    @TempDir
    Path tempDir;

    @Test
    void existingPluginContextObservesSessionAdapterChangesThroughStableCompositionView() {
        java.util.concurrent.atomic.AtomicReference<HostInstanceDescriptor> current = new java.util.concurrent.atomic.AtomicReference<>();
        HostSession session = new HostSession(
            () -> Optional.ofNullable(current.get()),
            descriptor -> HostAdapterConnection.of(adapters(descriptor.sessionId()))
        );
        RuntimeScheduler scheduler = scheduler();
        CorePluginContext context = new CorePluginContext(
            dependencies(tempDir, scheduler),
            session
        );

        try {
            assertTrue(context.cubismRead().activeProject().isEmpty());
            assertTrue(context.cubismRead().workspace().isEmpty());

            current.set(new HostInstanceDescriptor(
                "session-project",
                HostVerificationEvidence.projectOnly(new HostVerificationEvidence.Slice(
                    Path.of("records/reviewed.json"),
                    Path.of("host/Live2D_Cubism.jar"),
                    getClass().getClassLoader()
                ))
            ));
            session.refresh();
            assertEquals(
                "session-project",
                context.cubismRead().activeProject().orElseThrow().projectId()
            );
            assertEquals(
                "session-project-workspace",
                context.cubismRead().workspace().orElseThrow().workspaceId()
            );

            current.set(null);
            session.refresh();
            assertTrue(context.cubismRead().activeProject().isEmpty());
            assertTrue(context.cubismRead().workspace().isEmpty());
        } finally {
            session.close();
            scheduler.shutdown();
        }
    }

    private static CorePluginContext.Dependencies dependencies(
        final Path dataDir,
        final RuntimeScheduler scheduler
    ) {
        return new CorePluginContext.Dependencies(
            descriptor(),
            logger(),
            paths(dataDir),
            uiScheduler(),
            scheduler,
            diagnostics(),
            new DisposableScope(),
            emptyHostSnapshotSource(),
            ignored -> { },
            CLOCK
        );
    }

    private static RuntimeScheduler scheduler() {
        List<CallbackBudgetEvent> events = new CopyOnWriteArrayList<>();
        return new RuntimeScheduler(
            new DefaultWorkBudgetPolicy(),
            new PluginExecutorRegistry(1, 4, events::add, CLOCK),
            SidecarDispatcher.noop(),
            events::add
        );
    }

    private static PluginDescriptor descriptor() {
        return new PluginDescriptor() {
            @Override public String id() { return "dev.turboism.plugin.host-session-test"; }
            @Override public String name() { return "Host Session Test"; }
            @Override public String version() { return "0.1.0"; }
            @Override public String description() { return "Host session integration test"; }
            @Override public Map<String, String> entrypoints() { return Map.of(); }
            @Override public String turboismApi() { return "[0.1.0,0.2.0)"; }
            @Override public List<Author> authors() { return List.of(); }
            @Override public String license() { return "Project License"; }
            @Override public Optional<String> homepage() { return Optional.empty(); }
            @Override public List<DependencyRef> dependencies() { return List.of(); }
            @Override public List<PermissionRef> permissions() {
                return List.of(new PermissionRef() {
                    @Override public String id() { return "turboism.cubism.project.read"; }
                    @Override public String scope() { return "application"; }
                    @Override public Optional<String> reason() {
                        return Optional.of("Verify dynamic project reads");
                    }
                });
            }
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

    private static PluginPaths paths(final Path dataDir) {
        return new PluginPaths() {
            @Override public Path dataDir() { return dataDir; }
            @Override public Path logsDir() { return dataDir.resolve("logs"); }
            @Override public Path stateDir() { return dataDir.resolve("state"); }
            @Override public Path cacheDir() { return dataDir.resolve("cache"); }
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

    private static DiagnosticReport diagnostics() {
        return new DiagnosticReport() {
            @Override public Instant createdAt() { return CLOCK.instant(); }
            @Override public List<Problem> problems() { return List.of(); }
        };
    }

    private static HostSnapshotSource emptyHostSnapshotSource() {
        return new HostSnapshotSource() {
            @Override public Optional<HostProject> activeProject() { return Optional.empty(); }
            @Override public Optional<HostDocument> activeDocument() { return Optional.empty(); }
            @Override public Optional<HostModel> activeModel() { return Optional.empty(); }
            @Override public HostSelection selection() {
                return new HostSelection(
                    List.of(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty()
                );
            }
            @Override public boolean isHostPresent() { return false; }
            @Override public long invalidationToken() { return 0; }
        };
    }

    private static RuntimeHostAdapters adapters(final String projectId) {
        RuntimeHostAdapters safe = RuntimeHostAdapters.safeMode();
        ProjectWorkspaceAdapter projectWorkspace = ProjectWorkspaceAdapter.Impl.connected(
            new ProjectWorkspaceAdapter.HostOperations() {
                @Override public String hostVersion() { return "5.3.02"; }
                @Override public boolean supportsProjectWorkspaceRead() { return true; }
                @Override public Optional<ProjectSnapshot> activeProject() {
                    return Optional.of(new ProjectSnapshot(
                        projectId,
                        "Demo",
                        Optional.empty(),
                        List.of()
                    ));
                }
                @Override public Optional<WorkspaceSnapshot> workspace() {
                    return Optional.of(new WorkspaceSnapshot(
                        projectId + "-workspace",
                        "Workspace",
                        List.of(projectId)
                    ));
                }
            }
        );
        return new RuntimeHostAdapters(
            safe.themeStatus(),
            safe.renderStatus(),
            projectWorkspace,
            safe.clipMaskRead(),
            safe.statusToolbar(),
            safe.mainToolbar(),
            safe.uiSurface()
        );
    }
}

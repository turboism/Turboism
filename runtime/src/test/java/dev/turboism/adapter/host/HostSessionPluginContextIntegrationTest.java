package dev.turboism.adapter.host;

import dev.turboism.adapter.RuntimeHostAdapters;
import dev.turboism.adapter.cubism.ClipMaskReadAdapter;
import dev.turboism.adapter.cubism.HostSnapshotSource;
import dev.turboism.adapter.cubism.ProjectWorkspaceAdapter;
import dev.turboism.core.diagnostics.CallbackBudgetEvent;
import dev.turboism.core.plugin.context.CorePluginContext;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.PluginExecutorRegistry;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.diagnostics.CubismFacadeAuditEvent;
import dev.turboism.sdk.cubism.ClipMaskSnapshot;
import dev.turboism.sdk.cubism.ProjectSnapshot;
import dev.turboism.sdk.cubism.WorkspaceSnapshot;
import dev.turboism.sdk.diagnostics.DiagnosticReport;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.permission.CubismPermissionException;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostSessionPluginContextIntegrationTest {

    private static final Clock CLOCK = Clock.fixed(
        Instant.parse("2026-07-10T00:00:00Z"),
        ZoneOffset.UTC
    );

    @TempDir
    Path tempDir;

    @Test
    void existingPluginContextReadsDualTypedSlicesThroughStableCompositionView() {
        AtomicReference<HostInstanceDescriptor> current = new AtomicReference<>();
        AtomicInteger clipOperations = new AtomicInteger();
        HostSession session = new HostSession(
            () -> Optional.ofNullable(current.get()),
            descriptor -> HostAdapterConnection.of(adapters(descriptor.sessionId(), clipOperations))
        );
        RuntimeScheduler scheduler = scheduler();
        CorePluginContext context = new CorePluginContext(
            dependencies(tempDir, scheduler, descriptor(List.of(
                "turboism.cubism.project.read",
                "turboism.cubism.model.read"
            )), ignored -> { }),
            session
        );

        try {
            assertTrue(context.cubismRead().activeProject().isEmpty());
            assertTrue(context.cubismRead().workspace().isEmpty());
            assertTrue(context.cubismRead().clipMasks().isEmpty());

            current.set(dualDescriptor("session-project", "reviewed"));
            assertEquals(HostSession.State.ACTIVE, session.refresh());
            assertEquals(
                "session-project",
                context.cubismRead().activeProject().orElseThrow().projectId()
            );
            assertEquals(
                "session-project-workspace",
                context.cubismRead().workspace().orElseThrow().workspaceId()
            );
            assertEquals(
                new ClipMaskSnapshot("session-project-mesh", List.of("session-project-mask"), false),
                context.cubismRead().clipMasks().get(0)
            );
            assertEquals(1, clipOperations.get());

            current.set(null);
            assertEquals(HostSession.State.SAFE_MODE, session.refresh());
            assertTrue(context.cubismRead().activeProject().isEmpty());
            assertTrue(context.cubismRead().workspace().isEmpty());
            assertTrue(context.cubismRead().clipMasks().isEmpty());
        } finally {
            session.close();
            scheduler.shutdown();
        }
    }

    @Test
    void missingModelReadPermissionRejectsClipBeforeTouchingHostAndAudits() {
        AtomicInteger clipOperations = new AtomicInteger();
        List<CubismFacadeAuditEvent> auditEvents = new CopyOnWriteArrayList<>();
        HostSession session = new HostSession(
            () -> Optional.of(dualDescriptor("permission-session", "reviewed")),
            descriptor -> HostAdapterConnection.of(adapters(descriptor.sessionId(), clipOperations))
        );
        RuntimeScheduler scheduler = scheduler();
        CorePluginContext context = new CorePluginContext(
            dependencies(
                tempDir,
                scheduler,
                descriptor(List.of("turboism.cubism.project.read")),
                auditEvents::add
            ),
            session
        );

        try {
            assertEquals(HostSession.State.ACTIVE, session.refresh());
            assertThrows(CubismPermissionException.class, () -> context.cubismRead().clipMasks());
            assertEquals(0, clipOperations.get());
            assertTrue(auditEvents.stream().anyMatch(event ->
                event.permissionId().equals("turboism.cubism.model.read")
                    && event.operationId().equals("cubismRead.clipMasks")
                    && event.capabilityId().equals("cubism.clipmask.read")
            ));
        } finally {
            session.close();
            scheduler.shutdown();
        }
    }

    @Test
    void failedDualReplacementMakesSameContextSafeAndKeepsFailureSanitized() {
        AtomicReference<HostInstanceDescriptor> current = new AtomicReference<>(
            dualDescriptor("active-session", "reviewed-a")
        );
        AtomicInteger clipOperations = new AtomicInteger();
        HostSession session = new HostSession(
            () -> Optional.of(current.get()),
            descriptor -> {
                if (descriptor.sessionId().equals("replacement-session")) {
                    throw new IllegalStateException("private-host-path=C:/Users/secret/Cubism.jar");
                }
                return HostAdapterConnection.of(adapters(descriptor.sessionId(), clipOperations));
            }
        );
        RuntimeScheduler scheduler = scheduler();
        CorePluginContext context = new CorePluginContext(
            dependencies(tempDir, scheduler, descriptor(List.of(
                "turboism.cubism.project.read",
                "turboism.cubism.model.read"
            )), ignored -> { }),
            session
        );

        try {
            assertEquals(HostSession.State.ACTIVE, session.refresh());
            assertFalse(context.cubismRead().activeProject().isEmpty());
            assertFalse(context.cubismRead().workspace().isEmpty());
            assertFalse(context.cubismRead().clipMasks().isEmpty());

            current.set(dualDescriptor("replacement-session", "reviewed-b"));
            assertEquals(HostSession.State.FAILED, session.refresh());
            assertTrue(context.cubismRead().activeProject().isEmpty());
            assertTrue(context.cubismRead().workspace().isEmpty());
            assertTrue(context.cubismRead().clipMasks().isEmpty());
            assertEquals(
                new HostSessionFailure(
                    HostSessionFailure.Code.CONNECTION_FAILED,
                    "Host adapter connection failed safely."
                ),
                session.lastFailure().orElseThrow()
            );
        } finally {
            session.close();
            scheduler.shutdown();
        }
    }

    private HostInstanceDescriptor dualDescriptor(final String sessionId, final String recordStem) {
        ClassLoader loader = getClass().getClassLoader();
        Path artifact = Path.of("host/Live2D_Cubism.jar");
        return new HostInstanceDescriptor(
            sessionId,
            HostVerificationEvidence.withClipMask(
                new HostVerificationEvidence.Slice(
                    Path.of("records/" + recordStem + "-project.json"), artifact, loader
                ),
                new HostVerificationEvidence.Slice(
                    Path.of("records/" + recordStem + "-clip.json"), artifact, loader
                )
            )
        );
    }

    private static CorePluginContext.Dependencies dependencies(
        final Path dataDir,
        final RuntimeScheduler scheduler,
        final PluginDescriptor descriptor,
        final java.util.function.Consumer<CubismFacadeAuditEvent> auditSink
    ) {
        return new CorePluginContext.Dependencies(
            descriptor,
            logger(),
            paths(dataDir),
            uiScheduler(),
            scheduler,
            diagnostics(),
            new DisposableScope(),
            emptyHostSnapshotSource(),
            auditSink,
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

    private static PluginDescriptor descriptor(final List<String> permissionIds) {
        return new PluginDescriptor() {
            @Override public String id() { return "dev.turboism.plugin.host-session-test"; }
            @Override public String name() { return "Host Session Test"; }
            @Override public String version() { return "0.1.0"; }
            @Override public String description() { return "Host session integration test"; }
            @Override public List<String> entrypoints() { return List.of("dev.turboism.test.HostSessionPlugin"); }
            @Override public String turboismApi() { return "[0.1.0,0.2.0)"; }
            @Override public List<Author> authors() { return List.of(); }
            @Override public String license() { return "Project License"; }
            @Override public Optional<String> website() { return Optional.of("https://turboism.dev"); }
            @Override public List<String> resources() { return List.of(); }
            @Override public I18n i18n() { return emptyI18n(); }
            @Override public List<DependencyRef> dependencies() { return List.of(); }
            @Override public List<PermissionRef> permissions() {
                return permissionIds.stream().<PermissionRef>map(permissionId -> new PermissionRef() {
                    @Override public String id() { return permissionId; }
                    @Override public String scope() { return "application"; }
                    @Override public Optional<String> reason() {
                        return Optional.of("Verify dynamic read composition");
                    }
                }).toList();
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

    private static PluginDescriptor.I18n emptyI18n() {
        return new PluginDescriptor.I18n() {
            @Override public String baseName() { return "META-INF/turboism/i18n/messages"; }
            @Override public List<String> locales() { return List.of(); }
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

    private static RuntimeHostAdapters adapters(
        final String projectId,
        final AtomicInteger clipOperations
    ) {
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
        ClipMaskReadAdapter clipMask = ClipMaskReadAdapter.Impl.connected(
            new ClipMaskReadAdapter.HostOperations() {
                @Override public String hostVersion() { return "5.3.02"; }
                @Override public boolean supportsClipMaskRead() { return true; }
                @Override public List<ClipMaskSnapshot> clipMasks() {
                    clipOperations.incrementAndGet();
                    return List.of(new ClipMaskSnapshot(
                        projectId + "-mesh",
                        List.of(projectId + "-mask"),
                        false
                    ));
                }
            }
        );
        return new RuntimeHostAdapters(
            safe.themeStatus(),
            safe.renderStatus(),
            projectWorkspace,
            clipMask,
            safe.statusToolbar(),
            safe.mainToolbar(),
            safe.uiSurface()
        );
    }
}

package dev.turboism.preview;

import dev.turboism.adapter.RuntimeHostAdapters;
import dev.turboism.adapter.cubism.ProjectWorkspaceAdapter;
import dev.turboism.adapter.host.HostInstanceDescriptor;
import dev.turboism.adapter.host.HostSession;
import dev.turboism.adapter.host.HostSessionTestSupport;
import dev.turboism.core.plugin.context.CorePluginContext;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.failure.RuntimeFailureCollector;
import dev.turboism.hostread.SharedAsyncHostReadLane;
import dev.turboism.sdk.cubism.ArtMeshSnapshot;
import dev.turboism.sdk.cubism.DeformerSnapshot;
import dev.turboism.sdk.cubism.DeformerType;
import dev.turboism.sdk.cubism.DocumentKind;
import dev.turboism.sdk.cubism.DocumentSnapshot;
import dev.turboism.sdk.cubism.ModelSnapshot;
import dev.turboism.sdk.cubism.ParameterSnapshot;
import dev.turboism.sdk.cubism.ProjectContentKind;
import dev.turboism.sdk.cubism.ProjectContentSnapshot;
import dev.turboism.sdk.cubism.ProjectResourceSnapshot;
import dev.turboism.sdk.cubism.ProjectSnapshot;
import dev.turboism.sdk.cubism.ResourceKind;
import dev.turboism.sdk.cubism.WorkspaceSnapshot;
import dev.turboism.sdk.cubism.filechooser.FileChooserHistoryService;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Crosses the real preview production composition path
 * ({@code PreviewPluginContextFactory} → {@code PreviewPluginServicesFactory} →
 * {@code CorePluginContext}) with a HostSession-backed adapter view and proves the
 * canonical {@code PluginContext.cubism()} surface and the deprecated
 * {@code PluginContext.cubismRead()} overlap reads observe the same session data
 * and fail closed in safe mode.
 */
class PreviewPluginContextFactoryCompositionTest {

    @TempDir
    Path tempDir;

    @Test
    void previewCompositionPublishesSessionSnapshotReadsToCanonicalAndLegacyFacadeSurfaces() throws Exception {
        final AtomicReference<HostInstanceDescriptor> current = new AtomicReference<>();
        final HostSession session = HostSessionTestSupport.connectedSession(
            () -> Optional.ofNullable(current.get()),
            adapters("preview-project")
        );
        final RuntimeScheduler scheduler = PreviewRuntimeTestSupport.rejectedScheduler();
        final Path home = tempDir.resolve("home");
        try (PreviewLog log = new PreviewLog(home.resolve("logs/turboism.log"))) {
            final SharedAsyncHostReadLane lane = new SharedAsyncHostReadLane(8);
            try {
                final PreviewPluginContextFactory factory = new PreviewPluginContextFactory(
                    home,
                    scheduler,
                    session.adapterAccess(),
                    lane,
                    log,
                    new RuntimeFailureCollector(),
                    FileChooserHistoryService.unavailable()
                );
                final DisposableScope scope = new DisposableScope();
                try {
                    final CorePluginContext context = factory.create(
                        descriptor(),
                        PreviewPluginContextFactoryCompositionTest.class.getClassLoader(),
                        scope
                    ).context();

                    assertTrue(context.cubism().activeProject().isEmpty());
                    assertTrue(context.cubismRead().activeProject().isEmpty());

                    current.set(HostSessionTestSupport.descriptor("preview-project"));
                    assertEquals(HostSession.State.ACTIVE, session.refresh());

                    assertEquals(
                        "preview-project",
                        context.cubism().activeProject().orElseThrow().projectId()
                    );
                    assertEquals(context.cubism().activeProject(), context.cubismRead().activeProject());
                    assertEquals(context.cubism().activeDocument(), context.cubismRead().activeDocument());
                    assertEquals(context.cubism().activeModel(), context.cubismRead().activeModel());
                    assertEquals(
                        "preview-project-model",
                        context.cubism().activeModel().orElseThrow().modelId()
                    );
                    assertEquals(
                        DocumentKind.MODEL,
                        context.cubism().activeDocument().orElseThrow().kind()
                    );

                    current.set(null);
                    assertEquals(HostSession.State.SAFE_MODE, session.refresh());
                    assertTrue(context.cubism().activeProject().isEmpty());
                    assertTrue(context.cubismRead().activeProject().isEmpty());
                } finally {
                    scope.close();
                }
            } finally {
                lane.close();
            }
        } finally {
            session.close();
            scheduler.shutdown();
        }
    }

    private static RuntimeHostAdapters adapters(final String projectId) {
        final RuntimeHostAdapters safe = RuntimeHostAdapters.safeMode();
        final ProjectWorkspaceAdapter projectWorkspace = ProjectWorkspaceAdapter.Impl.connected(
            new ProjectWorkspaceAdapter.HostOperations() {
                @Override public String hostVersion() { return "5.3.02"; }
                @Override public boolean supportsProjectWorkspaceRead() { return true; }
                @Override public Optional<ProjectSnapshot> activeProject() {
                    return Optional.of(projectSnapshot(projectId));
                }
                @Override public Optional<DocumentSnapshot> activeDocument() {
                    return Optional.of(modelDocument(projectId));
                }
                @Override public Optional<WorkspaceSnapshot> workspace() {
                    return Optional.of(new WorkspaceSnapshot(
                        projectId + "-workspace", "Workspace", List.of(projectId)
                    ));
                }
            }
        );
        return new RuntimeHostAdapters(
            safe.themeStatus(), safe.renderStatus(), projectWorkspace, safe.clipMaskRead(),
            safe.statusToolbar(), safe.uiSurface()
        );
    }

    private static ProjectSnapshot projectSnapshot(final String projectId) {
        final DocumentSnapshot document = modelDocument(projectId);
        return new ProjectSnapshot(
            projectId,
            "Demo",
            Optional.empty(),
            List.of(document),
            List.of(new ProjectContentSnapshot(
                projectId + "-content",
                "Model content",
                ProjectContentKind.MODEL,
                Optional.of(Path.of("models/" + projectId + ".cmo3")),
                List.of(document.documentId()),
                List.of(new ProjectResourceSnapshot(
                    projectId + "-texture",
                    "Texture",
                    ResourceKind.IMAGE,
                    Optional.of("textures/texture.png")
                ))
            ))
        );
    }

    private static DocumentSnapshot modelDocument(final String projectId) {
        final ParameterSnapshot parameter = new ParameterSnapshot(
            "ParamA", "Parameter A", 1.0, 1.0, 0.0, 2.0, true, true
        );
        final ArtMeshSnapshot artMesh = new ArtMeshSnapshot(
            projectId + "-mesh", "ArtMesh", Optional.of(projectId + "-texture"), true, true
        );
        final DeformerSnapshot deformer = new DeformerSnapshot(
            projectId + "-deformer", "Root Deformer", DeformerType.ROOT, Optional.empty(), List.of()
        );
        final ModelSnapshot model = new ModelSnapshot(
            projectId + "-model",
            "Model",
            List.of(parameter, artMesh, deformer),
            List.of(parameter),
            List.of(artMesh),
            List.of(deformer)
        );
        return new DocumentSnapshot(
            projectId + "-document",
            "Model",
            "models/" + projectId + ".cmo3",
            Optional.empty(),
            Optional.of(model),
            DocumentKind.MODEL,
            Optional.of(projectId + "-content"),
            Optional.empty()
        );
    }

    private static PluginDescriptor descriptor() {
        return new PluginDescriptor() {
            @Override public String id() { return "dev.turboism.plugin.preview-composition-test"; }
            @Override public String name() { return "Preview Composition Test"; }
            @Override public String version() { return "0.1.0"; }
            @Override public String description() { return "Preview composition integration test"; }
            @Override public List<String> entrypoints() { return List.of("dev.turboism.test.PreviewCompositionPlugin"); }
            @Override public String turboismApi() { return "[0.1.0,0.2.0)"; }
            @Override public List<Author> authors() { return List.of(); }
            @Override public String license() { return "Project License"; }
            @Override public Optional<String> website() { return Optional.of("https://turboism.dev"); }
            @Override public List<String> resources() { return List.of(); }
            @Override public I18n i18n() { return emptyI18n(); }
            @Override public List<DependencyRef> dependencies() { return List.of(); }
            @Override public List<PermissionRef> permissions() {
                return List.of(
                    permission("turboism.cubism.project.read"),
                    permission("turboism.cubism.model.read")
                );
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

    private static PluginDescriptor.PermissionRef permission(final String id) {
        return new PluginDescriptor.PermissionRef() {
            @Override public String id() { return id; }
            @Override public String scope() { return "application"; }
            @Override public Optional<String> reason() { return Optional.of("Verify preview composition reads"); }
        };
    }

    private static PluginDescriptor.I18n emptyI18n() {
        return new PluginDescriptor.I18n() {
            @Override public String baseName() { return "META-INF/turboism/i18n/messages"; }
            @Override public List<String> locales() { return List.of(); }
        };
    }
}

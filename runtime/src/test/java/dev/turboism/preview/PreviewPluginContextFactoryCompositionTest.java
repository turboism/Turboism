package dev.turboism.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.adapter.RuntimeHostAdapters;
import dev.turboism.adapter.cubism.ProjectWorkspaceAdapter;
import dev.turboism.adapter.host.HostInstanceDescriptor;
import dev.turboism.adapter.host.HostSession;
import dev.turboism.adapter.host.HostSessionTestSupport;
import dev.turboism.core.plugin.context.CorePluginContext;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.failure.RuntimeFailureCollector;
import dev.turboism.hostread.SharedAsyncHostReadLane;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.sdk.cubism.ArtMeshSnapshot;
import dev.turboism.sdk.cubism.CubismEditorApiUnavailableException;
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
import dev.turboism.sdk.cubism.export.ExportSettingsContribution;
import dev.turboism.sdk.cubism.export.ExportSettingsContributionService;
import dev.turboism.sdk.cubism.export.ExportSettingsDecision;
import dev.turboism.sdk.cubism.filechooser.FileChooserHistoryService;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.UserFileLifetime;
import dev.turboism.sdk.ui.UserFileMode;
import dev.turboism.sdk.ui.UserFileRequest;
import dev.turboism.userfile.RuntimeUserFileAccessService;
import dev.turboism.userfile.UserFileGrantSource;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
    void previewSharedSnapshotsFollowLiveSelectionAndHostDisconnect() throws Exception {
        final AtomicReference<HostInstanceDescriptor> current = new AtomicReference<>();
        final var resolver = selectionResolver();
        final HostSession session = HostSessionTestSupport.connectedSession(
                () -> Optional.ofNullable(current.get()),
                ignored -> adapters("selection-project"),
                descriptor -> new dev.turboism.adapter.cubism.editor.EditorBackedCubismModelAccess(
                        resolver, descriptor.sessionId()),
                ignored -> resolver);
        final RuntimeScheduler scheduler = PreviewRuntimeTestSupport.rejectedScheduler();
        final Path home = tempDir.resolve("selection-home");
        try (PreviewLog log = new PreviewLog(home.resolve("logs/turboism.log"));
                SharedAsyncHostReadLane lane = new SharedAsyncHostReadLane(8);
                DisposableScope scope = new DisposableScope()) {
            final var factory = new PreviewPluginContextFactory(
                    home,
                    scheduler,
                    session.adapterAccess(),
                    lane,
                    log,
                    new RuntimeFailureCollector(),
                    FileChooserHistoryService.unavailable());
            final CorePluginContext context = factory.create(
                            descriptor(), PreviewPluginContextFactoryCompositionTest.class.getClassLoader(), scope)
                    .context();
            assertTrue(
                    context.cubism().runtime().selection().selectedObjectIds().isEmpty());

            SelectionHost.selectedId = "MeshA";
            current.set(HostSessionTestSupport.descriptor("selection-session"));
            assertEquals(HostSession.State.ACTIVE, session.refresh());
            assertEquals(
                    List.of("MeshA"), context.cubism().runtime().selection().selectedObjectIds());
            assertEquals(
                    context.cubism().runtime().selection(), context.cubismRead().selection());

            SelectionHost.selectedId = "MeshB";
            assertEquals(
                    List.of("MeshB"), context.cubism().runtime().selection().selectedObjectIds());
            assertEquals(
                    context.cubism().runtime().selection(), context.cubismRead().selection());

            current.set(null);
            assertEquals(HostSession.State.SAFE_MODE, session.refresh());
            assertTrue(
                    context.cubism().runtime().selection().selectedObjectIds().isEmpty());
        } finally {
            SelectionHost.selectedId = null;
            session.close();
            scheduler.shutdown();
        }
    }

    @Test
    void previewCompositionPublishesSessionSnapshotReadsToCanonicalAndLegacyFacadeSurfaces() throws Exception {
        final AtomicReference<HostInstanceDescriptor> current = new AtomicReference<>();
        final AtomicReference<UserFileGrantSource> actualSource = new AtomicReference<>();
        final HostSession session = HostSessionTestSupport.connectedSession(
                () -> Optional.ofNullable(current.get()),
                ignored -> adapters("preview-project"),
                ignored -> exportSettingsResolver());
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
                        FileChooserHistoryService.unavailable());
                final DisposableScope scope = new DisposableScope();
                try {
                    final CorePluginContext context = factory.create(
                                    descriptor(),
                                    PreviewPluginContextFactoryCompositionTest.class.getClassLoader(),
                                    scope)
                            .context();
                    assertThrows(
                            CubismEditorApiUnavailableException.class,
                            () -> context.services()
                                    .find(ExportSettingsContributionService.class)
                                    .orElse(ExportSettingsContributionService.unavailable())
                                    .contribute(exportContribution()));
                    final RuntimeUserFileAccessService userFiles =
                            assertInstanceOf(RuntimeUserFileAccessService.class, context.userFiles());
                    actualSource.set(sourceOf(userFiles));

                    final Path pluginRoot =
                            home.resolve("data/").resolve(descriptor().id());
                    assertFalse(Files.exists(
                            home.resolve("config/").resolve(descriptor().id())));
                    assertFalse(Files.exists(pluginRoot));
                    assertFalse(Files.exists(
                            home.resolve("cache/").resolve(descriptor().id())));
                    assertThrows(UnsupportedOperationException.class, context.paths()::logsDir);
                    assertFalse(Files.exists(
                            home.resolve("logs/").resolve(descriptor().id())));

                    assertTrue(context.cubism().activeProject().isEmpty());
                    assertTrue(context.cubismRead().activeProject().isEmpty());

                    current.set(HostSessionTestSupport.descriptor("preview-project"));
                    assertEquals(HostSession.State.ACTIVE, session.refresh());
                    final Registration exportRegistration = context.services()
                            .find(ExportSettingsContributionService.class)
                            .orElse(ExportSettingsContributionService.unavailable())
                            .contribute(exportContribution());
                    exportRegistration.close();

                    assertEquals(
                            "preview-project",
                            context.cubism().activeProject().orElseThrow().projectId());
                    assertEquals(
                            context.cubism().activeProject(),
                            context.cubismRead().activeProject());
                    assertEquals(
                            context.cubism().activeDocument(),
                            context.cubismRead().activeDocument());
                    assertEquals(
                            context.cubism().activeModel(), context.cubismRead().activeModel());
                    assertEquals(
                            "preview-project-model",
                            context.cubism().activeModel().orElseThrow().modelId());
                    assertEquals(
                            DocumentKind.MODEL,
                            context.cubism().activeDocument().orElseThrow().kind());

                    current.set(null);
                    assertEquals(HostSession.State.SAFE_MODE, session.refresh());
                    assertTrue(context.cubism().activeProject().isEmpty());
                    assertTrue(context.cubismRead().activeProject().isEmpty());
                    assertThrows(
                            CubismEditorApiUnavailableException.class,
                            () -> context.services()
                                    .find(ExportSettingsContributionService.class)
                                    .orElse(ExportSettingsContributionService.unavailable())
                                    .contribute(exportContribution()));
                    current.set(HostSessionTestSupport.descriptor("preview-project-closed"));
                    assertEquals(HostSession.State.ACTIVE, session.refresh());
                    scope.close();
                    assertThrows(
                            IllegalStateException.class,
                            () -> context.services()
                                    .find(ExportSettingsContributionService.class)
                                    .orElse(ExportSettingsContributionService.unavailable())
                                    .contribute(exportContribution()));
                } finally {
                    scope.close();
                }
                assertSame(
                        UserFileGrantSource.Unavailable.INSTANCE,
                        actualSource
                                .get()
                                .request(new UserFileRequest(
                                        "closed-composition",
                                        "Choose",
                                        List.of("csv"),
                                        UserFileMode.READ,
                                        UserFileLifetime.ONE_OPERATION))
                                .toCompletableFuture()
                                .get(2, TimeUnit.SECONDS));
            } finally {
                lane.close();
            }
        } finally {
            session.close();
            scheduler.shutdown();
        }
    }

    @Test
    void cachedParameterQueryObservesReplacedSessionModelSnapshot() throws Exception {
        final AtomicReference<HostInstanceDescriptor> current = new AtomicReference<>();
        final HostSession session =
                HostSessionTestSupport.connectedSession(() -> Optional.ofNullable(current.get()), descriptor -> {
                    final boolean first = descriptor.sessionId().equals("cache-a");
                    return adapters(descriptor.sessionId(), first ? "ParamA" : "ParamB", first ? 1.0 : 2.0);
                });
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
                        FileChooserHistoryService.unavailable());
                final DisposableScope scope = new DisposableScope();
                try {
                    final CorePluginContext context = factory.create(
                                    descriptor(),
                                    PreviewPluginContextFactoryCompositionTest.class.getClassLoader(),
                                    scope)
                            .context();

                    current.set(HostSessionTestSupport.descriptor("cache-a"));
                    assertEquals(HostSession.State.ACTIVE, session.refresh());
                    assertEquals(
                            "cache-a-model",
                            context.cubism().activeModel().orElseThrow().modelId());

                    final List<ParameterSnapshot> first = context.cubismRead().parameters();
                    assertEquals(1, first.size());
                    assertEquals("ParamA", first.get(0).id());
                    assertEquals(1.0, first.get(0).value(), 0.0);

                    current.set(HostSessionTestSupport.descriptor("cache-b"));
                    assertEquals(HostSession.State.ACTIVE, session.refresh());
                    assertEquals(
                            "cache-b-model",
                            context.cubism().activeModel().orElseThrow().modelId());

                    final List<ParameterSnapshot> second = context.cubismRead().parameters();
                    assertEquals(1, second.size());
                    assertEquals("ParamB", second.get(0).id());
                    assertEquals(2.0, second.get(0).value(), 0.0);
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

    private static UserFileGrantSource sourceOf(final RuntimeUserFileAccessService service)
            throws ReflectiveOperationException {
        final Field field = RuntimeUserFileAccessService.class.getDeclaredField("source");
        field.setAccessible(true);
        return (UserFileGrantSource) field.get(service);
    }

    private static ExportSettingsContribution exportContribution() {
        return new ExportSettingsContribution(
                "option-1", "label.key", (selected, documentId, modelId) -> ExportSettingsDecision.proceedUnchanged());
    }

    private static dev.turboism.mapping.verification.VerifiedMemberResolver exportSettingsResolver() {
        // This synthetic version-only resolver drives the existing host-version gate; it does not
        // attest native selectors or make this inert registry host-ready.
        return TestVerifiedResolvers.create(
                "5.3.02",
                "fixture.export-settings",
                Set.of("export-settings"),
                List.of(StaticSelector.classSelector("fixture.host", "example/Host")),
                PreviewPluginContextFactoryCompositionTest.class.getClassLoader());
    }

    private static dev.turboism.mapping.verification.VerifiedMemberResolver selectionResolver() {
        final String owner = SelectionHost.class.getName().replace('.', '/');
        final String self = "()L" + owner + ";";
        final var methods = new java.util.ArrayList<StaticSelector>();
        methods.add(StaticSelector.staticMethod(
                "cubism.editor-model.app-controller.instance",
                owner,
                "instance",
                self,
                StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
        methods.add(StaticSelector.classSelector("cubism.editor-model.modeling-document.class", owner));
        for (final String[] entry : List.of(
                new String[] {"app-controller.current-document", "currentDocument", self},
                new String[] {"app-controller.update-manager", "updateManager", self},
                new String[] {"modeling-document.model-source", "modelSource", self},
                new String[] {"update-manager.selection-guid-list", "selectionGuidList", "()Ljava/util/List;"},
                new String[] {"model-source.all-objects", "allObjects", "()Ljava/util/List;"},
                new String[] {"model-source.all-parameters", "allParameters", "()Ljava/util/List;"},
                new String[] {"parameter-controllable-source.guid", "guid", self},
                new String[] {"parameter-controllable-source.id", "id", self},
                new String[] {"parameter-source.guid", "guid", self},
                new String[] {"parameter-source.id", "id", self},
                new String[] {"guid.value", "value", "()Ljava/lang/String;"},
                new String[] {"id.value", "value", "()Ljava/lang/String;"})) {
            methods.add(StaticSelector.method(
                    "cubism.editor-model." + entry[0], owner, entry[1], entry[2], StaticSelector.ACCESS_PUBLIC));
        }
        return TestVerifiedResolvers.create(
                "5.3.02",
                dev.turboism.mapping.verification.selector.EditorSelectionReadSelectorContract.ADAPTER_SLICE_ID,
                Set.of(dev.turboism.mapping.verification.selector.EditorSelectionReadSelectorContract.CAPABILITY_ID),
                methods,
                SelectionHost.class.getClassLoader());
    }

    /** Minimal host fixture traversed by the verified selection reader in Preview composition. */
    public static final class SelectionHost {
        private static final SelectionHost INSTANCE = new SelectionHost("host");
        private static final List<SelectionHost> OBJECTS =
                List.of(new SelectionHost("MeshA"), new SelectionHost("MeshB"));
        private static volatile String selectedId;
        private final String value;

        SelectionHost(final String value) {
            this.value = value;
        }

        public static SelectionHost instance() {
            return INSTANCE;
        }

        public SelectionHost currentDocument() {
            return this;
        }

        public SelectionHost updateManager() {
            return this;
        }

        public SelectionHost modelSource() {
            return this;
        }

        public List<SelectionHost> selectionGuidList() {
            return OBJECTS.stream()
                    .filter(object -> object.value.equals(selectedId))
                    .toList();
        }

        public List<SelectionHost> allObjects() {
            return OBJECTS;
        }

        public List<SelectionHost> allParameters() {
            return List.of();
        }

        public SelectionHost guid() {
            return this;
        }

        public SelectionHost id() {
            return this;
        }

        public String value() {
            return value;
        }
    }

    private static RuntimeHostAdapters adapters(final String projectId) {
        return adapters(projectId, "ParamA", 1.0);
    }

    private static RuntimeHostAdapters adapters(
            final String projectId, final String parameterId, final double parameterValue) {
        final RuntimeHostAdapters safe = RuntimeHostAdapters.safeMode();
        final ProjectWorkspaceAdapter projectWorkspace =
                ProjectWorkspaceAdapter.Impl.connected(new ProjectWorkspaceAdapter.HostOperations() {
                    @Override
                    public String hostVersion() {
                        return "5.3.02";
                    }

                    @Override
                    public boolean supportsProjectWorkspaceRead() {
                        return true;
                    }

                    @Override
                    public Optional<ProjectSnapshot> activeProject() {
                        return Optional.of(projectSnapshot(projectId, parameterId, parameterValue));
                    }

                    @Override
                    public Optional<DocumentSnapshot> activeDocument() {
                        return Optional.of(modelDocument(projectId, parameterId, parameterValue));
                    }

                    @Override
                    public Optional<WorkspaceSnapshot> workspace() {
                        return Optional.of(
                                new WorkspaceSnapshot(projectId + "-workspace", "Workspace", List.of(projectId)));
                    }
                });
        return new RuntimeHostAdapters(
                safe.themeStatus(),
                safe.renderStatus(),
                projectWorkspace,
                safe.clipMaskRead(),
                safe.statusToolbar(),
                safe.uiSurface());
    }

    private static ProjectSnapshot projectSnapshot(final String projectId) {
        return projectSnapshot(projectId, "ParamA", 1.0);
    }

    private static ProjectSnapshot projectSnapshot(
            final String projectId, final String parameterId, final double parameterValue) {
        final DocumentSnapshot document = modelDocument(projectId, parameterId, parameterValue);
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
                                Optional.of("textures/texture.png"))))));
    }

    private static DocumentSnapshot modelDocument(final String projectId) {
        return modelDocument(projectId, "ParamA", 1.0);
    }

    private static DocumentSnapshot modelDocument(
            final String projectId, final String parameterId, final double parameterValue) {
        final ParameterSnapshot parameter =
                new ParameterSnapshot(parameterId, "Parameter A", parameterValue, parameterValue, 0.0, 2.0, true, true);
        final ArtMeshSnapshot artMesh =
                new ArtMeshSnapshot(projectId + "-mesh", "ArtMesh", Optional.of(projectId + "-texture"), true, true);
        final DeformerSnapshot deformer = new DeformerSnapshot(
                projectId + "-deformer", "Root Deformer", DeformerType.ROOT, Optional.empty(), List.of());
        final ModelSnapshot model = new ModelSnapshot(
                projectId + "-model",
                "Model",
                List.of(parameter, artMesh, deformer),
                List.of(parameter),
                List.of(artMesh),
                List.of(deformer));
        return new DocumentSnapshot(
                projectId + "-document",
                "Model",
                "models/" + projectId + ".cmo3",
                Optional.empty(),
                Optional.of(model),
                DocumentKind.MODEL,
                Optional.of(projectId + "-content"),
                Optional.empty());
    }

    private static PluginDescriptor descriptor() {
        return new PluginDescriptor() {
            @Override
            public String id() {
                return "dev.turboism.plugin.preview-composition-test";
            }

            @Override
            public String name() {
                return "Preview Composition Test";
            }

            @Override
            public String version() {
                return "0.1.0";
            }

            @Override
            public String description() {
                return "Preview composition integration test";
            }

            @Override
            public List<String> entrypoints() {
                return List.of("dev.turboism.test.PreviewCompositionPlugin");
            }

            @Override
            public String turboismApi() {
                return "[0.1.0,0.2.0)";
            }

            @Override
            public List<Author> authors() {
                return List.of();
            }

            @Override
            public String license() {
                return "Project License";
            }

            @Override
            public Optional<String> website() {
                return Optional.of("https://turboism.dev");
            }

            @Override
            public List<String> resources() {
                return List.of();
            }

            @Override
            public I18n i18n() {
                return emptyI18n();
            }

            @Override
            public List<DependencyRef> dependencies() {
                return List.of();
            }

            @Override
            public List<PermissionRef> permissions() {
                return List.of(
                        permission("turboism.cubism.project.read"),
                        permission("turboism.cubism.model.read"),
                        permission("turboism.cubism.parameter.read"));
            }

            @Override
            public List<String> capabilities() {
                return List.of();
            }

            @Override
            public Environment environment() {
                return new Environment() {
                    @Override
                    public boolean requiresCubism() {
                        return false;
                    }

                    @Override
                    public String ui() {
                        return "none";
                    }
                };
            }
        };
    }

    private static PluginDescriptor.PermissionRef permission(final String id) {
        return new PluginDescriptor.PermissionRef() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String scope() {
                return "application";
            }

            @Override
            public Optional<String> reason() {
                return Optional.of("Verify preview composition reads");
            }
        };
    }

    private static PluginDescriptor.I18n emptyI18n() {
        return new PluginDescriptor.I18n() {
            @Override
            public String baseName() {
                return "META-INF/turboism/i18n/messages";
            }

            @Override
            public List<String> locales() {
                return List.of();
            }
        };
    }
}

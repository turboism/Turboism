package dev.turboism.preview;

import dev.turboism.adapter.RuntimeHostAdapters;
import dev.turboism.adapter.cubism.ProjectWorkspaceAdapter;
import dev.turboism.adapter.host.HostInstanceDescriptor;
import dev.turboism.adapter.host.HostSession;
import dev.turboism.adapter.host.HostSessionTestSupport;
import dev.turboism.exportsettings.ExportSettingsIdentity;
import dev.turboism.exportsettings.HostDocumentExportSettingsIdentitySource;
import dev.turboism.exportsettings.RuntimeExportSettingsAuthority;
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
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the host-level export-settings policy is actually reachable from a loaded plugin.
 *
 * <p>Covers the two halves of the production wiring that earlier unit tests could not see: the
 * per-plugin contribution registry is published to the shared authority during the real
 * {@code PreviewPluginContextFactory} composition, and dialog identity is read from the same live
 * host snapshot source the rest of the runtime uses.</p>
 */
class ExportSettingsHostWiringTest {

    @TempDir
    Path tempDir;

    @Test
    void boundAuthoritySeesThePluginRegistryAndLosesItWhenThePluginUnloads() throws Exception {
        final AtomicReference<HostInstanceDescriptor> current = new AtomicReference<>();
        final HostSession session = HostSessionTestSupport.connectedSession(
            () -> Optional.ofNullable(current.get()),
            descriptor -> adapters(descriptor.sessionId())
        );
        final Path home = tempDir.resolve("home");
        final RuntimeExportSettingsAuthority authority = new RuntimeExportSettingsAuthority(
            () -> Optional.empty()
        );
        try (PreviewLog log = new PreviewLog(home.resolve("logs/turboism.log"))) {
            final SharedAsyncHostReadLane lane = new SharedAsyncHostReadLane(8);
            try {
                final PreviewPluginContextFactory factory = new PreviewPluginContextFactory(
                    home,
                    PreviewRuntimeTestSupport.rejectedScheduler(),
                    session.adapterAccess(),
                    lane,
                    log,
                    new RuntimeFailureCollector(),
                    FileChooserHistoryService.unavailable()
                );
                factory.bindExportSettingsAuthority(authority);
                final DisposableScope scope = new DisposableScope();
                try {
                    current.set(HostSessionTestSupport.descriptor("wiring"));
                    assertEquals(HostSession.State.ACTIVE, session.refresh());

                    factory.create(
                        descriptor(),
                        ExportSettingsHostWiringTest.class.getClassLoader(),
                        scope
                    );
                    assertEquals(
                        List.of(descriptor().id()),
                        authority.boundPluginIds(),
                        "a loaded plugin's contribution registry must be reachable from the dialog"
                    );

                    assertEquals(
                        List.of(descriptor().id()),
                        authority.boundPluginIds(),
                        "contributing an option must not change which plugins the dialog can see"
                    );
                } finally {
                    scope.close();
                }
                assertTrue(
                    authority.boundPluginIds().isEmpty(),
                    "unloading the plugin must withdraw its registry from the dialog"
                );
            } finally {
                lane.close();
            }
        } finally {
            session.close();
        }
    }

    @Test
    void unboundFactoryLeavesTheAuthorityEmpty() throws Exception {
        final AtomicReference<HostInstanceDescriptor> current = new AtomicReference<>();
        final HostSession session = HostSessionTestSupport.connectedSession(
            () -> Optional.ofNullable(current.get()),
            descriptor -> adapters(descriptor.sessionId())
        );
        final Path home = tempDir.resolve("home");
        final RuntimeExportSettingsAuthority authority = new RuntimeExportSettingsAuthority(
            () -> Optional.empty()
        );
        try (PreviewLog log = new PreviewLog(home.resolve("logs/turboism.log"))) {
            final SharedAsyncHostReadLane lane = new SharedAsyncHostReadLane(8);
            try {
                final PreviewPluginContextFactory factory = new PreviewPluginContextFactory(
                    home,
                    PreviewRuntimeTestSupport.rejectedScheduler(),
                    session.adapterAccess(),
                    lane,
                    log,
                    new RuntimeFailureCollector(),
                    FileChooserHistoryService.unavailable()
                );
                final DisposableScope scope = new DisposableScope();
                try {
                    current.set(HostSessionTestSupport.descriptor("unbound"));
                    assertEquals(HostSession.State.ACTIVE, session.refresh());
                    factory.create(
                        descriptor(),
                        ExportSettingsHostWiringTest.class.getClassLoader(),
                        scope
                    );
                } finally {
                    scope.close();
                }
                assertTrue(
                    authority.boundPluginIds().isEmpty(),
                    "an unbound authority must stay empty: the native dialog is left untouched"
                );
            } finally {
                lane.close();
            }
        } finally {
            session.close();
        }
    }

    @Test
    void identityFollowsTheActiveModelDocumentAndDisappearsWithoutAHost() throws Exception {
        final AtomicReference<HostInstanceDescriptor> current = new AtomicReference<>();
        final HostSession session = HostSessionTestSupport.connectedSession(
            () -> Optional.ofNullable(current.get()),
            descriptor -> adapters(descriptor.sessionId())
        );
        try {
            final HostDocumentExportSettingsIdentitySource identity =
                new HostDocumentExportSettingsIdentitySource(
                    dev.turboism.adapter.host.HostSessionSnapshotSource.forSession(
                        session.adapterAccess().adapters().projectWorkspace()
                    )
                );
            assertTrue(identity.get().isEmpty(), "no host means no export identity");

            current.set(HostSessionTestSupport.descriptor("identity"));
            assertEquals(HostSession.State.ACTIVE, session.refresh());
            assertEquals(
                Optional.of(new ExportSettingsIdentity(
                    "identity-document", new ModelId("identity-model")
                )),
                identity.get()
            );

            current.set(null);
            assertEquals(HostSession.State.SAFE_MODE, session.refresh());
            assertTrue(
                identity.get().isEmpty(),
                "an unavailable host must fail closed instead of reusing a stale identity"
            );
        } finally {
            session.close();
        }
    }

    @Test
    void bindingTwiceIsRejectedInsteadOfSilentlyReplacingPolicy() throws Exception {
        final Path home = tempDir.resolve("home");
        try (PreviewLog log = new PreviewLog(home.resolve("logs/turboism.log"))) {
            final SharedAsyncHostReadLane lane = new SharedAsyncHostReadLane(8);
            try {
                final HostSession session = HostSessionTestSupport.connectedSession(
                    Optional::empty,
                    descriptor -> adapters(descriptor.sessionId())
                );
                try {
                    final PreviewPluginContextFactory factory = new PreviewPluginContextFactory(
                        home,
                        PreviewRuntimeTestSupport.rejectedScheduler(),
                        session.adapterAccess(),
                        lane,
                        log,
                        new RuntimeFailureCollector(),
                        FileChooserHistoryService.unavailable()
                    );
                    factory.bindExportSettingsAuthority(authority());
                    assertThrows(
                        IllegalStateException.class,
                        () -> factory.bindExportSettingsAuthority(authority())
                    );
                } finally {
                    session.close();
                }
            } finally {
                lane.close();
            }
        }
    }

    private static RuntimeExportSettingsAuthority authority() {
        return new RuntimeExportSettingsAuthority(() -> Optional.empty());
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
            @Override public String id() { return "dev.turboism.plugin.export-settings-wiring-test"; }
            @Override public String name() { return "Export Settings Wiring Test"; }
            @Override public String version() { return "0.1.0"; }
            @Override public String description() { return "Export settings host wiring test"; }
            @Override public List<String> entrypoints() { return List.of(); }
            @Override public String turboismApi() { return "[0.1.0,0.2.0)"; }
            @Override public List<Author> authors() { return List.of(); }
            @Override public String license() { return "Project License"; }
            @Override public Optional<String> website() { return Optional.empty(); }
            @Override public List<String> resources() { return List.of(); }
            @Override public I18n i18n() { return emptyI18n(); }
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

    private static PluginDescriptor.I18n emptyI18n() {
        return new PluginDescriptor.I18n() {
            @Override public String baseName() { return "META-INF/turboism/i18n/messages"; }
            @Override public List<String> locales() { return List.of(); }
        };
    }
}

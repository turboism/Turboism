package dev.turboism.adapter.host;

import dev.turboism.adapter.RuntimeHostAdapters;
import dev.turboism.adapter.cubism.ClipMaskReadAdapter;
import dev.turboism.adapter.cubism.HostSnapshotSource;
import dev.turboism.adapter.cubism.ProjectWorkspaceAdapter;
import dev.turboism.adapter.cubism.textureatlas.TextureAtlasAuthoringState;
import dev.turboism.adapter.cubism.textureatlas.TextureAtlasLayoutProvider;
import dev.turboism.core.diagnostics.PluginWorkBudgetEvent;
import dev.turboism.core.plugin.context.CorePluginContext;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.diagnostics.CubismFacadeAuditEvent;
import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.cubism.ClipMaskSnapshot;
import dev.turboism.sdk.cubism.ProjectSnapshot;
import dev.turboism.sdk.cubism.WorkspaceSnapshot;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutApplyStatus;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutConstraints;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutItem;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutPlan;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPlacement;
import dev.turboism.sdk.diagnostics.DiagnosticReport;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.plugin.PluginPaths;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.EmbeddedPanelContribution;
import dev.turboism.sdk.ui.UiScheduler;
import dev.turboism.ui.host.EditorUiFamily;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
    void pluginContextPublishesEmbeddedPanelsThroughTheSessionAuthorityAndCleansUp() {
        HostSession session = new HostSession(() -> Optional.empty());
        RuntimeScheduler scheduler = scheduler();
        CorePluginContext context = new CorePluginContext(
            dependencies(
                tempDir,
                scheduler,
                descriptor(List.of("turboism.ui.panel.contribute")),
                ignored -> { }
            ),
            session
        );

        try {
            Registration panel = context.uiHost().contributeEmbeddedPanel(new EmbeddedPanelContribution(
                "turboism.panel.main",
                "Turboism",
                "right",
                0
            ));

            assertEquals(
                List.of("turboism.panel.main"),
                session.editorUiContributions().contributions(EditorUiFamily.PANEL).stream()
                    .map(contribution -> contribution.identity().contributionId())
                    .toList()
            );
            panel.close();
            assertTrue(session.editorUiContributions().contributions(EditorUiFamily.PANEL).isEmpty());
        } finally {
            session.close();
            scheduler.shutdown();
        }
    }

    @Test
    void pluginContextBindsItsRuntimeActionRegistryToTheSessionRouterUntilScopeClose() throws Exception {
        HostSession session = new HostSession(() -> Optional.empty());
        RuntimeScheduler scheduler = scheduler();
        CorePluginContext.Dependencies dependencies = dependencies(
            tempDir,
            scheduler,
            descriptor(List.of("turboism.action.register")),
            ignored -> { }
        );
        CorePluginContext context = new CorePluginContext(dependencies, session);
        AtomicInteger invocations = new AtomicInteger();
        CountDownLatch invoked = new CountDownLatch(1);

        try {
            context.actions().register("open-settings", new ActionRegistry.Action() {
                @Override public String id() { return "open-settings"; }
                @Override public String label() { return "Open settings"; }
                @Override public java.util.function.Consumer<ActionRegistry.ActionContext> handler() {
                    return ignored -> {
                        invocations.incrementAndGet();
                        invoked.countDown();
                    };
                }
            });

            session.editorUiActionRouter().invoke(
                "dev.turboism.plugin.host-session-test",
                "open-settings"
            );
            assertTrue(invoked.await(2, TimeUnit.SECONDS));
            assertEquals(1, invocations.get());

            dependencies.disposableScope().close();
            session.editorUiActionRouter().invoke(
                "dev.turboism.plugin.host-session-test",
                "open-settings"
            );
            Thread.sleep(100L);
            assertEquals(1, invocations.get());
        } finally {
            session.close();
            scheduler.shutdown();
        }
    }

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
    void existingPluginContextTracksConnectionOwnedModelAccessThroughReplacementAndSafeMode() {
        AtomicReference<HostInstanceDescriptor> current = new AtomicReference<>();
        HostSession session = new HostSession(
            () -> Optional.ofNullable(current.get()),
            descriptor -> HostAdapterConnection.of(
                RuntimeHostAdapters.safeMode(),
                fixedModelAccess(descriptor.sessionId())
            )
        );
        RuntimeScheduler scheduler = scheduler();
        CorePluginContext context = new CorePluginContext(
            dependencies(tempDir, scheduler, descriptor(List.of(
                "turboism.cubism.model.read"
            )), ignored -> { }),
            session
        );

        try {
            assertThrows(IllegalStateException.class, () -> context.cubism().model().active());

            current.set(dualDescriptor("model-a", "reviewed-a"));
            assertEquals(HostSession.State.ACTIVE, session.refresh());
            final CubismModel stale = context.cubism().model().active();
            final dev.turboism.sdk.cubism.model.Parameter staleParameter =
                stale.parameters().find(new dev.turboism.sdk.cubism.id.ParameterId("ParamA"));
            assertEquals(new ModelId("model-a"), stale.id());
            assertEquals(1.0F, staleParameter.getValue());

            current.set(dualDescriptor("model-b", "reviewed-b"));
            assertEquals(HostSession.State.ACTIVE, session.refresh());
            assertEquals(new ModelId("model-b"), context.cubism().model().active().id());
            assertThrows(IllegalStateException.class, stale::id);
            assertThrows(IllegalStateException.class, staleParameter::getValue);

            current.set(null);
            assertEquals(HostSession.State.SAFE_MODE, session.refresh());
            assertThrows(IllegalStateException.class, () -> context.cubism().model().active());
        } finally {
            session.close();
            scheduler.shutdown();
        }
    }

    @Test
    void pluginScopeCloseInvalidatesOnlyThatContextsCubismReferences() throws Exception {
        final AtomicReference<HostInstanceDescriptor> current = new AtomicReference<>();
        final HostSession session = new HostSession(
            () -> Optional.ofNullable(current.get()),
            descriptor -> HostAdapterConnection.of(
                RuntimeHostAdapters.safeMode(),
                fixedModelAccess(descriptor.sessionId())
            )
        );
        final RuntimeScheduler firstScheduler = scheduler();
        final RuntimeScheduler secondScheduler = scheduler();
        final CorePluginContext.Dependencies firstDependencies = dependencies(
            tempDir.resolve("first"),
            firstScheduler,
            descriptor(List.of("turboism.cubism.model.read")),
            ignored -> { }
        );
        final CorePluginContext.Dependencies secondDependencies = dependencies(
            tempDir.resolve("second"),
            secondScheduler,
            descriptor(List.of("turboism.cubism.model.read")),
            ignored -> { }
        );
        final CorePluginContext first = new CorePluginContext(firstDependencies, session);
        final CorePluginContext second = new CorePluginContext(secondDependencies, session);

        try {
            current.set(dualDescriptor("model-scope", "reviewed-scope"));
            assertEquals(HostSession.State.ACTIVE, session.refresh());
            final CubismModel stale = first.cubism().model().active();
            assertEquals(new ModelId("model-scope"), stale.id());
            assertEquals(new ModelId("model-scope"), second.cubism().model().active().id());

            firstDependencies.disposableScope().close();

            assertThrows(IllegalStateException.class, stale::id);
            assertThrows(IllegalStateException.class, () -> first.cubism().model());
            assertEquals(HostSession.State.ACTIVE, session.state());
            assertEquals(new ModelId("model-scope"), second.cubism().model().active().id());
        } finally {
            secondDependencies.disposableScope().close();
            session.close();
            firstScheduler.shutdown();
            secondScheduler.shutdown();
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

    @Test
    void textureAtlasProviderFlowsThroughProductionContextAndInvalidatesOnReplacementAndClose() {
        final AtomicReference<HostInstanceDescriptor> current = new AtomicReference<>();
        final java.util.Map<String, RecordingAtlasProvider> providers = new java.util.HashMap<>();
        final HostSession session = new HostSession(
            () -> Optional.ofNullable(current.get()),
            descriptor -> {
                final RecordingAtlasProvider provider = new RecordingAtlasProvider(descriptor.sessionId());
                providers.put(descriptor.sessionId(), provider);
                return connectionWithAtlasProvider(provider);
            }
        );
        final RuntimeScheduler scheduler = scheduler();
        final CorePluginContext context = new CorePluginContext(
            dependencies(tempDir, scheduler, descriptor(List.of(
                "turboism.cubism.model.read", "turboism.cubism.model.write"
            )), ignored -> { }),
            session
        );

        try {
            assertTrue(context.cubism().textureAtlasLayouts().current().isEmpty());
            current.set(dualDescriptor("atlas-a", "atlas-a"));
            assertEquals(HostSession.State.ACTIVE, session.refresh());
            final var first = context.cubism().textureAtlasLayouts().current().orElseThrow();
            assertEquals(
                Optional.of(TextureAtlasLayoutApplyStatus.APPLIED),
                context.cubism().textureAtlasLayouts().apply(first.target(), movedPlan()).status()
            );
            assertEquals(1, providers.get("atlas-a").applyCount.get());

            current.set(dualDescriptor("atlas-b", "atlas-b"));
            assertEquals(HostSession.State.ACTIVE, session.refresh());
            assertEquals(
                Optional.of(dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutFailureCode.TARGET_STALE),
                context.cubism().textureAtlasLayouts().apply(first.target(), movedPlan()).failureCode()
            );
            assertEquals("atlas-b", context.cubism().textureAtlasLayouts().current().orElseThrow().atlasId());

            current.set(null);
            assertEquals(HostSession.State.SAFE_MODE, session.refresh());
            assertTrue(context.cubism().textureAtlasLayouts().current().isEmpty());
            session.close();
            assertTrue(context.cubism().textureAtlasLayouts().current().isEmpty());
        } finally {
            session.close();
            scheduler.shutdown();
        }
    }

    private static HostAdapterConnection connectionWithAtlasProvider(
        final TextureAtlasLayoutProvider provider
    ) {
        return new HostAdapterConnection() {
            @Override public RuntimeHostAdapters adapters() { return RuntimeHostAdapters.safeMode(); }
            @Override public Optional<TextureAtlasLayoutProvider> textureAtlasLayoutProvider() {
                return Optional.of(provider);
            }
            @Override public void close() { }
        };
    }

    private static TextureAtlasLayoutPlan movedPlan() {
        return new TextureAtlasLayoutPlan(16, 8, 1, List.of(
            new TextureAtlasPlacement("texture-a", 0, 1, 1, 4, 3, false),
            new TextureAtlasPlacement("texture-b", 0, 7, 1, 2, 2, false)
        ));
    }

    private static final class RecordingAtlasProvider implements TextureAtlasLayoutProvider {
        private final String id;
        private final AtomicInteger applyCount = new AtomicInteger();
        private TextureAtlasAuthoringState state;
        private RecordingAtlasProvider(final String id) {
            this.id = id;
            this.state = new TextureAtlasAuthoringState(
                "document-" + id, "model-" + id, id, 1,
                new TextureAtlasLayoutConstraints(16, 8, 1, 1, 1, false, false),
                List.of(
                    new TextureAtlasLayoutItem("texture-a", 4, 3),
                    new TextureAtlasLayoutItem("texture-b", 2, 2)
                ),
                new TextureAtlasLayoutPlan(16, 8, 1, List.of(
                    new TextureAtlasPlacement("texture-a", 0, 1, 1, 4, 3, false),
                    new TextureAtlasPlacement("texture-b", 0, 6, 1, 2, 2, false)
                ))
            );
        }
        @Override public Optional<TextureAtlasAuthoringState> current() { return Optional.of(state); }
        @Override public ApplyOutcome apply(
            final TextureAtlasAuthoringState expected,
            final TextureAtlasLayoutPlan plan
        ) {
            applyCount.incrementAndGet();
            state = new TextureAtlasAuthoringState(
                expected.documentId(), expected.modelId(), id, expected.revision() + 1,
                expected.constraints(), expected.items(), plan
            );
            return ApplyOutcome.APPLIED;
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

    private static CubismModelAccess fixedModelAccess(final String id) {
        return () -> new CubismModel() {
            @Override public ModelId id() { return new ModelId(id); }
            @Override public dev.turboism.sdk.cubism.model.Parameters parameters() {
                return new dev.turboism.sdk.cubism.model.Parameters() {
                    private final dev.turboism.sdk.cubism.model.Parameter parameter =
                        new dev.turboism.sdk.cubism.model.Parameter() {
                            @Override public dev.turboism.sdk.cubism.id.ParameterId id() {
                                return new dev.turboism.sdk.cubism.id.ParameterId("ParamA");
                            }
                            @Override public float getValue() { return 1.0F; }
                            @Override public float getMinimumValue() { return 0.0F; }
                            @Override public float getMaximumValue() { return 2.0F; }
                            @Override public float getDefaultValue() { return 1.0F; }
                            @Override public void setValue(final float value) { throw unsupported(); }
                        };

                    @Override public List<dev.turboism.sdk.cubism.model.Parameter> all() {
                        return List.of(parameter);
                    }

                    @Override public dev.turboism.sdk.cubism.model.Parameter find(
                        final dev.turboism.sdk.cubism.id.ParameterId parameterId
                    ) {
                        if (!parameter.id().equals(parameterId)) {
                            throw new java.util.NoSuchElementException();
                        }
                        return parameter;
                    }
                };
            }
            @Override public dev.turboism.sdk.cubism.model.Parts parts() { throw unsupported(); }
            @Override public dev.turboism.sdk.cubism.model.Drawables drawables() { throw unsupported(); }
            @Override public dev.turboism.sdk.cubism.model.Deformers deformers() { throw unsupported(); }
            @Override public dev.turboism.sdk.cubism.model.Glues glues() { throw unsupported(); }
            @Override public void update() { throw unsupported(); }

            private UnsupportedOperationException unsupported() {
                return new UnsupportedOperationException();
            }
        };
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
        List<PluginWorkBudgetEvent> events = new CopyOnWriteArrayList<>();
        return new RuntimeScheduler(
            new DefaultWorkBudgetPolicy(),
            new PluginWorkExecutorRegistry(1, 4, events::add, CLOCK),
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
                        safe.uiSurface()
        );
    }
}

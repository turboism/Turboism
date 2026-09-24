package dev.turboism.core.plugin.context;

import dev.turboism.adapter.RuntimeHostAdapters;
import dev.turboism.adapter.cubism.ClipMaskReadAdapter;
import dev.turboism.adapter.cubism.HostSnapshotSource;
import dev.turboism.adapter.cubism.ProjectWorkspaceAdapter;
import dev.turboism.adapter.cubism.RenderStatusAdapter;
import dev.turboism.adapter.cubism.command.EditorCommandAdapter;
import dev.turboism.adapter.cubism.command.EditorFileCommandResolver;
import dev.turboism.adapter.cubism.lifecycle.EditorObjectLifecycleCoordinator;
import dev.turboism.adapter.cubism.lifecycle.ParameterLifecycleCoordinator;
import dev.turboism.adapter.cubism.lifecycle.PartLifecycleCoordinator;
import dev.turboism.adapter.cubism.physics.PhysicsEditorCoordinator;
import dev.turboism.adapter.cubism.service.read.M12ReadSnapshotSource;
import dev.turboism.adapter.ui.StatusToolbarAdapterImpl;
import dev.turboism.adapter.ui.ThemeStatusAdapter;
import dev.turboism.adapter.ui.ThemeStatusAdapterImpl;
import dev.turboism.adapter.ui.UiSurfaceAdapterImpl;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.sdk.cubism.ClipMaskSnapshot;
import dev.turboism.sdk.cubism.PsdDocumentSnapshot;
import dev.turboism.sdk.cubism.ProjectSnapshot;
import dev.turboism.sdk.cubism.RenderStatusSnapshot;
import dev.turboism.sdk.cubism.TextureAtlasSnapshot;
import dev.turboism.sdk.cubism.WorkspaceSnapshot;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.model.BlendMode;
import dev.turboism.sdk.cubism.model.Color;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.sdk.cubism.model.Deformers;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.Drawables;
import dev.turboism.sdk.cubism.model.FloatSequence;
import dev.turboism.sdk.cubism.model.Glues;
import dev.turboism.sdk.cubism.model.IntSequence;
import dev.turboism.sdk.cubism.model.Parameters;
import dev.turboism.sdk.cubism.model.Parts;
import dev.turboism.sdk.cubism.physics.PhysicsEditorContribution;
import dev.turboism.sdk.cubism.physics.PhysicsEditorService;
import dev.turboism.sdk.cubism.service.clipmask.CubismClipMaskService;
import dev.turboism.sdk.cubism.service.read.CubismReadCapabilityService;
import dev.turboism.sdk.diagnostics.DiagnosticReport;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.PluginPaths;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.theme.ThemeStatusSnapshot;
import dev.turboism.sdk.ui.UiScheduler;
import dev.turboism.ui.UiHostStateSource;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the {@code DefaultCubismServicesFactory} wiring for retained read and physics services.
 * Every service is obtained through the production factory plus the real plugin
 * {@link DisposableScope} and the version interceptor, so these assertions cover the composition a
 * plugin actually runs against: once the owning scope closes, the six direct read paths and the
 * downstream clip-mask collection must reject before touching any host source, adapter or model,
 * and a physics contribution must be released without closing the shared coordinator or another
 * plugin's registration.
 */
class ReadPhysicsScopeCompositionTest {

    @Test
    void retainedDirectReadPathsFailClosedAfterPluginScopeCloses() throws Exception {
        final HostProbe probe = new HostProbe(List.of());
        final DisposableScope scope = new DisposableScope();
        final RuntimeScheduler scheduler = scheduler();
        try {
            final DefaultCubismServicesFactory factory =
                DefaultCubismServicesFactoryTestSupport.withModelAccess(
                    probe.adapters(), probe.modelAccess()
                );
            final CubismContextServices services = factory.create(
                dependencies("test.read-scope", scope, scheduler, probe)
            );
            final CubismReadCapabilityService reads = services.cubismReadCapabilityService();

            // While the plugin is active every direct read reaches its own host source.
            reads.psdDocuments();
            reads.clipMasks();
            reads.textureAtlases();
            reads.renderStatus();
            reads.workspace();
            reads.themeStatus();
            assertEquals(6, probe.accesses.get());
            services.cubismFacade().model().active();
            assertTrue(probe.accesses.get() > 6);

            scope.close();
            final int accessesAtClose = probe.accesses.get();

            assertThrows(IllegalStateException.class, reads::psdDocuments);
            assertThrows(IllegalStateException.class, reads::clipMasks);
            assertThrows(IllegalStateException.class, reads::textureAtlases);
            assertThrows(IllegalStateException.class, reads::renderStatus);
            assertThrows(IllegalStateException.class, reads::workspace);
            assertThrows(IllegalStateException.class, reads::themeStatus);
            assertThrows(IllegalStateException.class, reads::selection);
            assertThrows(IllegalStateException.class, reads::parameters);
            assertThrows(IllegalStateException.class, reads::modelObjects);
            assertThrows(IllegalStateException.class, reads::meshes);
            assertThrows(IllegalStateException.class, reads::deformers);
            assertThrows(IllegalStateException.class, services.cubismFacade()::model);
            assertEquals(accessesAtClose, probe.accesses.get());
        } finally {
            scope.close();
            if (!scheduler.isClosed()) scheduler.shutdown();
        }
    }

    @Test
    void retainedClipMaskServiceFailsClosedAfterPluginScopeCloses() throws Exception {
        final HostProbe probe = new HostProbe(
            List.of(new ClipMaskSnapshot("guid-x", List.of("guid-m"), false))
        );
        final DisposableScope scope = new DisposableScope();
        final RuntimeScheduler scheduler = scheduler();
        try {
            final DefaultCubismServicesFactory factory =
                DefaultCubismServicesFactoryTestSupport.withModelAccess(
                    probe.adapters(), probe.modelAccess()
                );
            final CubismContextServices services = factory.create(
                dependencies("test.clipmask-scope", scope, scheduler, probe)
            );
            final CubismClipMaskService clipMasks = services.cubismClipMaskService();

            final List<CubismClipMaskService.ClipMaskRecord> records =
                clipMasks.collectClipMaskRecords();
            assertEquals("Face", records.get(0).displayName());
            assertTrue(probe.accesses.get() > 0);

            scope.close();
            final int accessesAtClose = probe.accesses.get();

            assertThrows(IllegalStateException.class, clipMasks::collectClipMaskRecords);
            assertEquals(accessesAtClose, probe.accesses.get());
        } finally {
            scope.close();
            if (!scheduler.isClosed()) scheduler.shutdown();
        }
    }

    @Test
    void secondPluginServicesRemainUsableAfterFirstPluginScopeCloses() throws Exception {
        // Host adapters are shared per factory, so both plugins' host traffic is counted by probeA.
        final HostProbe probeA = new HostProbe(
            List.of(new ClipMaskSnapshot("guid-b", List.of(), false))
        );
        final HostProbe probeB = new HostProbe(List.of());
        final DisposableScope scopeA = new DisposableScope();
        final DisposableScope scopeB = new DisposableScope();
        final RuntimeScheduler scheduler = scheduler();
        try {
            final DefaultCubismServicesFactory factory =
                DefaultCubismServicesFactoryTestSupport.withModelAccess(
                    probeA.adapters(), probeA.modelAccess()
                );
            final CubismContextServices servicesA = factory.create(
                dependencies("test.scope-a", scopeA, scheduler, probeA)
            );
            final CubismContextServices servicesB = factory.create(
                dependencies("test.scope-b", scopeB, scheduler, probeB)
            );

            scopeA.close();
            final int accessesAtClose = probeA.accesses.get();

            assertThrows(IllegalStateException.class, servicesA.cubismReadCapabilityService()::clipMasks);
            assertThrows(IllegalStateException.class, servicesA.cubismClipMaskService()::collectClipMaskRecords);
            assertThrows(
                IllegalStateException.class,
                () -> servicesA.physicsEditorService().contribute(new PhysicsEditorContribution(true, false))
            );
            assertEquals(accessesAtClose, probeA.accesses.get());

            // The still-active plugin keeps reading through the shared adapters and model access.
            assertEquals(1, servicesB.cubismReadCapabilityService().clipMasks().size());
            servicesB.cubismFacade().model().active();
            assertTrue(probeA.accesses.get() > accessesAtClose);
        } finally {
            scopeA.close();
            scopeB.close();
            if (!scheduler.isClosed()) scheduler.shutdown();
        }
    }

    @Test
    void physicsContributionIsReleasedWhenOwningScopeClosesWithoutManualCleanup() throws Exception {
        final HostProbe probe = new HostProbe(List.of());
        final PhysicsEditorCoordinator coordinator = new PhysicsEditorCoordinator();
        final DisposableScope scopeA = new DisposableScope();
        final DisposableScope scopeB = new DisposableScope();
        final RuntimeScheduler scheduler = scheduler();
        try {
            final DefaultCubismServicesFactory factory =
                DefaultCubismServicesFactoryTestSupport.withEditorCommands(
                    probe.adapters(),
                    probe.modelAccess(),
                    new ParameterLifecycleCoordinator(),
                    new PartLifecycleCoordinator(),
                    new EditorObjectLifecycleCoordinator(),
                    coordinator,
                    EditorCommandAdapter.unavailable(),
                    EditorFileCommandResolver.unavailable()
                );
            final CubismContextServices servicesA = factory.create(
                dependencies("test.physics-a", scopeA, scheduler, probe)
            );
            final CubismContextServices servicesB = factory.create(
                dependencies("test.physics-b", scopeB, scheduler, probe)
            );

            // The plugin never closes its registration; scope close must revoke the contribution.
            final Registration stale = servicesA.physicsEditorService()
                .contribute(new PhysicsEditorContribution(true, false));
            scopeA.close();

            final Registration second = servicesB.physicsEditorService()
                .contribute(new PhysicsEditorContribution(false, true));
            // Closing the released handle afterwards is a no-op and must not revoke the new owner.
            stale.close();
            assertThrows(
                IllegalStateException.class,
                () -> coordinator.contribute(new PhysicsEditorContribution(true, false))
            );
            second.close();
        } finally {
            scopeA.close();
            scopeB.close();
            if (!scheduler.isClosed()) scheduler.shutdown();
        }
    }

    @Test
    void disabledPhysicsServiceRejectsNewContributions() throws Exception {
        final HostProbe probe = new HostProbe(List.of());
        final PhysicsEditorCoordinator coordinator = new PhysicsEditorCoordinator();
        final DisposableScope scope = new DisposableScope();
        final RuntimeScheduler scheduler = scheduler();
        try {
            final DefaultCubismServicesFactory factory =
                DefaultCubismServicesFactoryTestSupport.withEditorCommands(
                    probe.adapters(),
                    probe.modelAccess(),
                    new ParameterLifecycleCoordinator(),
                    new PartLifecycleCoordinator(),
                    new EditorObjectLifecycleCoordinator(),
                    coordinator,
                    EditorCommandAdapter.unavailable(),
                    EditorFileCommandResolver.unavailable()
                );
            final CubismContextServices services = factory.create(
                dependencies("test.physics-stale", scope, scheduler, probe)
            );
            final PhysicsEditorService service = services.physicsEditorService();

            final Registration first = service.contribute(new PhysicsEditorContribution(true, false));
            scope.close();
            first.close();

            final IllegalStateException rejected = assertThrows(
                IllegalStateException.class,
                () -> service.contribute(new PhysicsEditorContribution(false, true))
            );
            assertTrue(rejected.getMessage().contains("disabled"));
            // The failed attempt must not occupy the shared contribution slot.
            coordinator.contribute(new PhysicsEditorContribution(true, false)).close();
        } finally {
            scope.close();
            if (!scheduler.isClosed()) scheduler.shutdown();
        }
    }

    @Test
    void contributeRacingScopeCloseLeavesNoLiveContribution() throws Exception {
        final HostProbe probe = new HostProbe(List.of());
        final PhysicsEditorCoordinator coordinator = new PhysicsEditorCoordinator();
        final DisposableScope scopeA = new DisposableScope();
        final DisposableScope scopeB = new DisposableScope();
        final RuntimeScheduler scheduler = scheduler();
        try {
            final DefaultCubismServicesFactory factory =
                DefaultCubismServicesFactoryTestSupport.withEditorCommands(
                    probe.adapters(),
                    probe.modelAccess(),
                    new ParameterLifecycleCoordinator(),
                    new PartLifecycleCoordinator(),
                    new EditorObjectLifecycleCoordinator(),
                    coordinator,
                    EditorCommandAdapter.unavailable(),
                    EditorFileCommandResolver.unavailable()
                );
            final CubismContextServices servicesA = factory.create(
                dependencies("test.physics-race-a", scopeA, scheduler, probe)
            );
            final CubismContextServices servicesB = factory.create(
                dependencies("test.physics-race-b", scopeB, scheduler, probe)
            );
            final PhysicsEditorService serviceA = servicesA.physicsEditorService();

            final AtomicBoolean contributedDuringClose = new AtomicBoolean();
            final AtomicBoolean rejectedDuringClose = new AtomicBoolean();
            // This closeable runs while the scope is already closing: the contribution attempt
            // must be rejected without leaving a live registration on the shared coordinator.
            scopeA.register(() -> {
                try {
                    serviceA.contribute(new PhysicsEditorContribution(false, true));
                    contributedDuringClose.set(true);
                } catch (IllegalStateException expected) {
                    rejectedDuringClose.set(true);
                }
            });
            scopeA.close();

            assertFalse(contributedDuringClose.get());
            assertTrue(rejectedDuringClose.get());
            servicesB.physicsEditorService()
                .contribute(new PhysicsEditorContribution(true, false))
                .close();
        } finally {
            scopeA.close();
            scopeB.close();
            if (!scheduler.isClosed()) scheduler.shutdown();
        }
    }

    @Test
    void closingOnePluginScopeDoesNotRevokeAnotherContribution() throws Exception {
        final HostProbe probe = new HostProbe(List.of());
        final PhysicsEditorCoordinator coordinator = new PhysicsEditorCoordinator();
        final DisposableScope scopeA = new DisposableScope();
        final DisposableScope scopeB = new DisposableScope();
        final RuntimeScheduler scheduler = scheduler();
        try {
            final DefaultCubismServicesFactory factory =
                DefaultCubismServicesFactoryTestSupport.withEditorCommands(
                    probe.adapters(),
                    probe.modelAccess(),
                    new ParameterLifecycleCoordinator(),
                    new PartLifecycleCoordinator(),
                    new EditorObjectLifecycleCoordinator(),
                    coordinator,
                    EditorCommandAdapter.unavailable(),
                    EditorFileCommandResolver.unavailable()
                );
            final CubismContextServices servicesA = factory.create(
                dependencies("test.physics-other-a", scopeA, scheduler, probe)
            );
            final CubismContextServices servicesB = factory.create(
                dependencies("test.physics-other-b", scopeB, scheduler, probe)
            );

            final Registration owned = servicesB.physicsEditorService()
                .contribute(new PhysicsEditorContribution(true, false));
            scopeA.close();

            // The surviving plugin still owns the slot; the coordinator itself stays open.
            assertThrows(
                IllegalStateException.class,
                () -> coordinator.contribute(new PhysicsEditorContribution(false, true))
            );
            owned.close();
            owned.close();
            servicesB.physicsEditorService()
                .contribute(new PhysicsEditorContribution(false, true))
                .close();
        } finally {
            scopeA.close();
            scopeB.close();
            if (!scheduler.isClosed()) scheduler.shutdown();
        }
    }

    @Test
    void physicsServiceStaysPermissionGatedWhileScopeIsActive() throws Exception {
        final HostProbe probe = new HostProbe(List.of());
        final DisposableScope scope = new DisposableScope();
        final RuntimeScheduler scheduler = scheduler();
        try {
            final DefaultCubismServicesFactory factory =
                DefaultCubismServicesFactoryTestSupport.withModelAccess(
                    probe.adapters(), probe.modelAccess()
                );
            final CubismContextServices services = factory.create(
                dependencies(
                    "test.physics-readonly",
                    scope,
                    scheduler,
                    probe,
                    "turboism.cubism.model.read",
                    "turboism.cubism.project.read"
                )
            );

            assertThrows(
                UnsupportedOperationException.class,
                () -> services.physicsEditorService().contribute(new PhysicsEditorContribution(true, false))
            );
        } finally {
            scope.close();
            if (!scheduler.isClosed()) scheduler.shutdown();
        }
    }

    private static CorePluginContext.Dependencies dependencies(
        final String pluginId,
        final DisposableScope scope,
        final RuntimeScheduler scheduler,
        final HostProbe probe,
        final String... permissions
    ) {
        return new CorePluginContext.Dependencies(
            descriptor(pluginId, permissions),
            logger(),
            paths(),
            uiScheduler(),
            scheduler,
            diagnostics(),
            scope,
            probe.snapshotSource(),
            probe.m12(),
            UiHostStateSource.DEFAULT,
            ignored -> { },
            CLOCK
        );
    }

    /**
     * Shared fake-host probe: every M12 source, host adapter, snapshot source and model access call
     * increments one counter so a disabled plugin can be proven to reach nothing at all.
     */
    private static final class HostProbe {
        final AtomicInteger accesses = new AtomicInteger();
        private final List<ClipMaskSnapshot> clipMasks;

        HostProbe(final List<ClipMaskSnapshot> clipMasks) {
            this.clipMasks = clipMasks;
        }

        RuntimeHostAdapters adapters() {
            return new RuntimeHostAdapters(
                ThemeStatusAdapterImpl.connected(new ThemeStatusAdapter.HostOperations() {
                    @Override public String hostVersion() { return "5.3.02"; }
                    @Override public boolean supportsThemeStatusRead() { return true; }
                    @Override public Optional<ThemeStatusSnapshot> themeStatus() {
                        accesses.incrementAndGet();
                        return Optional.empty();
                    }
                }),
                RenderStatusAdapter.Impl.connected(new RenderStatusAdapter.HostOperations() {
                    @Override public String hostVersion() { return "5.3.02"; }
                    @Override public boolean supportsRenderStatusRead() { return true; }
                    @Override public Optional<RenderStatusSnapshot> renderStatus() {
                        accesses.incrementAndGet();
                        return Optional.empty();
                    }
                }),
                ProjectWorkspaceAdapter.Impl.connected(new ProjectWorkspaceAdapter.HostOperations() {
                    @Override public String hostVersion() { return "5.3.02"; }
                    @Override public boolean supportsProjectWorkspaceRead() { return true; }
                    @Override public Optional<ProjectSnapshot> activeProject() {
                        accesses.incrementAndGet();
                        return Optional.empty();
                    }
                    @Override public Optional<WorkspaceSnapshot> workspace() {
                        accesses.incrementAndGet();
                        return Optional.empty();
                    }
                }),
                ClipMaskReadAdapter.Impl.connected(new ClipMaskReadAdapter.HostOperations() {
                    @Override public String hostVersion() { return "5.3.02"; }
                    @Override public boolean supportsClipMaskRead() { return true; }
                    @Override public List<ClipMaskSnapshot> clipMasks() {
                        accesses.incrementAndGet();
                        return clipMasks;
                    }
                }),
                StatusToolbarAdapterImpl.safeMode(),
                UiSurfaceAdapterImpl.safeMode()
            );
        }

        M12ReadSnapshotSource m12() {
            return new M12ReadSnapshotSource() {
                @Override public List<PsdDocumentSnapshot> psdDocuments() {
                    accesses.incrementAndGet();
                    return List.of();
                }
                @Override public List<ClipMaskSnapshot> clipMasks() {
                    accesses.incrementAndGet();
                    return List.of();
                }
                @Override public List<TextureAtlasSnapshot> textureAtlases() {
                    accesses.incrementAndGet();
                    return List.of();
                }
                @Override public Optional<RenderStatusSnapshot> renderStatus() {
                    accesses.incrementAndGet();
                    return Optional.empty();
                }
                @Override public Optional<WorkspaceSnapshot> workspace() {
                    accesses.incrementAndGet();
                    return Optional.empty();
                }
                @Override public Optional<ThemeStatusSnapshot> themeStatus() {
                    accesses.incrementAndGet();
                    return Optional.empty();
                }
            };
        }

        HostSnapshotSource snapshotSource() {
            return new HostSnapshotSource() {
                @Override public Optional<HostProject> activeProject() {
                    accesses.incrementAndGet();
                    return Optional.empty();
                }
                @Override public Optional<HostDocument> activeDocument() {
                    accesses.incrementAndGet();
                    return Optional.empty();
                }
                @Override public Optional<HostModel> activeModel() {
                    accesses.incrementAndGet();
                    return Optional.empty();
                }
                @Override public HostSelection selection() {
                    accesses.incrementAndGet();
                    return new HostSelection(List.of(), Optional.empty(), Optional.empty(), Optional.empty());
                }
                @Override public boolean isHostPresent() {
                    return false;
                }
                @Override public long invalidationToken() {
                    return 0;
                }
            };
        }

        CubismModelAccess modelAccess() {
            return () -> {
                accesses.incrementAndGet();
                return new CubismModel() {
                    @Override public ModelId id() { return new ModelId("model-1"); }
                    @Override public Parameters parameters() { throw new UnsupportedOperationException(); }
                    @Override public Parts parts() { throw new UnsupportedOperationException(); }
                    @Override public Drawables drawables() {
                        return new Drawables() {
                            @Override public List<Drawable> all() { return List.of(DRAWABLE); }
                            @Override public Drawable find(final ArtMeshId id) {
                                throw new NoSuchElementException();
                            }
                        };
                    }
                    @Override public Deformers deformers() { throw new UnsupportedOperationException(); }
                    @Override public Glues glues() { throw new UnsupportedOperationException(); }
                    @Override public void update() { }
                };
            };
        }
    }

    private static final Drawable DRAWABLE = new Drawable() {
        @Override public ArtMeshId id() { return new ArtMeshId("ArtMesh_1"); }
        @Override public String guid() { return "guid-x"; }
        @Override public String name() { return "Face"; }
        @Override public byte constantFlag() { throw new UnsupportedOperationException(); }
        @Override public byte dynamicFlag() { throw new UnsupportedOperationException(); }
        @Override public BlendMode blendMode() { throw new UnsupportedOperationException(); }
        @Override public int textureIndex() { throw new UnsupportedOperationException(); }
        @Override public int drawOrder() { throw new UnsupportedOperationException(); }
        @Override public int renderOrder() { throw new UnsupportedOperationException(); }
        @Override public float getOpacity() { throw new UnsupportedOperationException(); }
        @Override public IntSequence masks() { throw new UnsupportedOperationException(); }
        @Override public FloatSequence vertexPositions() { throw new UnsupportedOperationException(); }
        @Override public FloatSequence vertexUvs() { throw new UnsupportedOperationException(); }
        @Override public IntSequence indices() { throw new UnsupportedOperationException(); }
        @Override public Color multiplyColor() { throw new UnsupportedOperationException(); }
        @Override public Color screenColor() { throw new UnsupportedOperationException(); }
        @Override public int parentPartIndex() { throw new UnsupportedOperationException(); }
        @Override public int parentDeformerIndex() { throw new UnsupportedOperationException(); }
        @Override public IntSequence parameters() { throw new UnsupportedOperationException(); }
    };

    private static PluginDescriptor descriptor(final String id, final String... permissions) {
        final List<PluginDescriptor.PermissionRef> refs =
            java.util.Arrays.stream(permissions)
                .<PluginDescriptor.PermissionRef>map(permission -> new PluginDescriptor.PermissionRef() {
                    @Override public String id() { return permission; }
                    @Override public String scope() { return "application"; }
                    @Override public Optional<String> reason() { return Optional.empty(); }
                })
                .toList();
        return new PluginDescriptor() {
            @Override public String id() { return id; }
            @Override public String name() { return id; }
            @Override public String version() { return "0.1.0"; }
            @Override public String description() { return "Test"; }
            @Override public List<String> entrypoints() { return List.of("dev.turboism.test.ScopePlugin"); }
            @Override public String turboismApi() { return "[0.1.0,0.2.0)"; }
            @Override public List<Author> authors() { return List.of(); }
            @Override public String license() { return "Project License"; }
            @Override public Optional<String> website() { return Optional.empty(); }
            @Override public List<String> resources() { return List.of(); }
            @Override public I18n i18n() { return new I18n() {
                @Override public String baseName() { return "META-INF/turboism/i18n/messages"; }
                @Override public List<String> locales() { return List.of(); }
            }; }
            @Override public List<DependencyRef> dependencies() { return List.of(); }
            @Override public List<PermissionRef> permissions() { return refs; }
            @Override public List<String> capabilities() { return List.of(); }
            @Override public Environment environment() { return new Environment() {
                @Override public boolean requiresCubism() { return false; }
                @Override public String ui() { return "none"; }
            }; }
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

    private static PluginPaths paths() {
        return new PluginPaths() {
            @Override public Path dataDir() { return Path.of("."); }
            @Override public Path logsDir() { return Path.of("."); }
            @Override public Path stateDir() { return Path.of("."); }
            @Override public Path cacheDir() { return Path.of("."); }
        };
    }

    private static UiScheduler uiScheduler() {
        return new UiScheduler() {
            @Override public Registration runOnUiThread(Runnable work) { work.run(); return () -> { }; }
            @Override public Registration runOnUiThreadLater(Runnable work, Duration delay) { return () -> { }; }
        };
    }

    private static RuntimeScheduler scheduler() {
        return new RuntimeScheduler(
            new DefaultWorkBudgetPolicy(),
            new PluginWorkExecutorRegistry(1, 4, ignored -> { }, CLOCK),
            SidecarDispatcher.noop(),
            ignored -> { }
        );
    }

    private static DiagnosticReport diagnostics() {
        return new DiagnosticReport() {
            @Override public Instant createdAt() { return CLOCK.instant(); }
            @Override public List<Problem> problems() { return List.of(); }
        };
    }

    private static final Clock CLOCK = Clock.systemUTC();

    private static final String[] ALL_READ_WRITE = {
        "turboism.cubism.model.read",
        "turboism.cubism.model.write",
        "turboism.cubism.project.read"
    };

    private static CorePluginContext.Dependencies dependencies(
        final String pluginId,
        final DisposableScope scope,
        final RuntimeScheduler scheduler,
        final HostProbe probe
    ) {
        return dependencies(pluginId, scope, scheduler, probe, ALL_READ_WRITE);
    }
}

package dev.turboism.adapter.cubism;

import static dev.turboism.adapter.cubism.CubismFacadeTestFixtures.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.adapter.cubism.core.TestCoreApiFixture;
import dev.turboism.adapter.cubism.lifecycle.ParameterLifecycleCoordinator;
import dev.turboism.diagnostics.CubismFacadeAuditEvent;
import dev.turboism.permissions.CubismPermissionGate;
import dev.turboism.sdk.cubism.CubismPlugin;
import dev.turboism.sdk.cubism.CubismRuntimeSnapshot;
import dev.turboism.sdk.cubism.clipmask.ClipMaskReplacement;
import dev.turboism.sdk.cubism.history.CubismHistory;
import dev.turboism.sdk.cubism.history.HistoryEntry;
import dev.turboism.sdk.cubism.history.HistoryMoveResult;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.AnimationAttribute;
import dev.turboism.sdk.cubism.model.AnimationCurveType;
import dev.turboism.sdk.cubism.model.AnimationDocument;
import dev.turboism.sdk.cubism.model.AnimationScene;
import dev.turboism.sdk.cubism.model.AnimationTrack;
import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.Color;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.sdk.cubism.model.Deformer;
import dev.turboism.sdk.cubism.model.Deformers;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.Drawables;
import dev.turboism.sdk.cubism.model.Glues;
import dev.turboism.sdk.cubism.model.IntSequence;
import dev.turboism.sdk.cubism.model.ParameterDefinition;
import dev.turboism.sdk.cubism.model.ParameterType;
import dev.turboism.sdk.cubism.model.Parameters;
import dev.turboism.sdk.cubism.model.Parts;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.cubism.model.RotationDeformer;
import dev.turboism.sdk.cubism.model.RotationDeformerForm;
import dev.turboism.sdk.cubism.model.WarpDeformer;
import dev.turboism.sdk.cubism.model.WarpGrid;
import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.permission.PluginPermission;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class CubismFacadeImplTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-07-07T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void grantedPermissionsReturnSnapshots() {
        final List<CubismFacadeAuditEvent> auditEvents = new ArrayList<>();
        final CubismFacadeImpl facade = facadeWith(
                sampleSource(),
                auditEvents,
                List.of(
                        permission(CubismFacadeImpl.PROJECT_READ_PERMISSION),
                        permission(CubismFacadeImpl.MODEL_READ_PERMISSION)));

        final CubismRuntimeSnapshot runtime = facade.runtime();

        assertEquals("project-1", facade.activeProject().orElseThrow().projectId());
        assertEquals("document-1", facade.activeDocument().orElseThrow().documentId());
        assertEquals("model-1", facade.activeModel().orElseThrow().modelId());
        assertEquals("project-1", runtime.project().orElseThrow().projectId());
        assertEquals("model-1", runtime.model().orElseThrow().modelId());
        assertEquals(3, runtime.modelObjects().size());
        assertTrue(auditEvents.isEmpty());
    }

    @ParameterizedTest
    @CsvSource({"PROJECT, activeProject", "MODEL, activeModel"})
    void deniedPermissionThrowsAndRecordsAuditEvent(final String kind, final String methodName) {
        final List<CubismFacadeAuditEvent> auditEvents = new ArrayList<>();
        final String expectedPermission = "PROJECT".equals(kind)
                ? CubismFacadeImpl.PROJECT_READ_PERMISSION
                : CubismFacadeImpl.MODEL_READ_PERMISSION;
        final String grantedPermission = "PROJECT".equals(kind)
                ? CubismFacadeImpl.MODEL_READ_PERMISSION
                : CubismFacadeImpl.PROJECT_READ_PERMISSION;
        final CubismFacadeImpl facade = facadeWith(sampleSource(), auditEvents, List.of(permission(grantedPermission)));

        final CubismPermissionException error = assertThrows(CubismPermissionException.class, () -> {
            if ("PROJECT".equals(kind)) facade.activeProject();
            else facade.activeModel();
        });

        assertTrue(error.getMessage().contains(expectedPermission));
        assertEquals(1, auditEvents.size());
        assertEquals("plugin.demo", auditEvents.get(0).pluginId());
        assertEquals(expectedPermission, auditEvents.get(0).permissionId());
        assertEquals(methodName, auditEvents.get(0).methodName());
        if ("PROJECT".equals(kind)) {
            assertEquals(FIXED_CLOCK.instant(), auditEvents.get(0).timestamp());
        }
    }

    @Test
    void runtimeRedactsProjectWhenOnlyProjectPermissionIsDenied() {
        final List<CubismFacadeAuditEvent> auditEvents = new ArrayList<>();
        final CubismFacadeImpl facade =
                facadeWith(sampleSource(), auditEvents, List.of(permission(CubismFacadeImpl.MODEL_READ_PERMISSION)));

        final CubismRuntimeSnapshot runtime = facade.runtime();

        assertTrue(runtime.project().isEmpty());
        assertEquals("document-1", runtime.document().orElseThrow().documentId());
        assertEquals("model-1", runtime.model().orElseThrow().modelId());
        assertEquals("runtime", auditEvents.get(0).methodName());
        assertEquals(
                CubismFacadeImpl.PROJECT_READ_PERMISSION, auditEvents.get(0).permissionId());
    }

    @Test
    void noActiveHostReturnsEmptyOptionalsAndEmptyRuntime() {
        final List<CubismFacadeAuditEvent> auditEvents = new ArrayList<>();
        final CubismFacadeImpl facade = facadeWith(
                emptySource(),
                auditEvents,
                List.of(
                        permission(CubismFacadeImpl.PROJECT_READ_PERMISSION),
                        permission(CubismFacadeImpl.MODEL_READ_PERMISSION)));

        assertFalse(facade.isHostPresent());
        assertTrue(facade.activeProject().isEmpty());
        assertTrue(facade.activeDocument().isEmpty());
        assertTrue(facade.activeModel().isEmpty());
        assertTrue(facade.runtime().project().isEmpty());
        assertTrue(facade.runtime().document().isEmpty());
        assertTrue(facade.runtime().model().isEmpty());
        assertTrue(auditEvents.isEmpty());
    }

    @Test
    void historyWrapperPreservesBindingAwareMovesAndReadGuards() {
        final HistorySnapshot expected = new HistorySnapshot(
                HistorySnapshot.Availability.AVAILABLE,
                3,
                4,
                1,
                List.of(new HistoryEntry(0, "First", true)),
                true,
                false,
                "document",
                "manager");
        final int[] bindingMoves = {0};
        final CubismHistory history = new CubismHistory() {
            @Override
            public HistorySnapshot snapshot() {
                return expected;
            }

            @Override
            public HistoryMoveResult moveTo(
                    final long expectedGeneration, final long expectedRevision, final int position) {
                throw new AssertionError("legacy move must not be used");
            }

            @Override
            public HistoryMoveResult moveTo(final HistorySnapshot snapshot, final int position) {
                bindingMoves[0]++;
                assertEquals(expected, snapshot);
                assertEquals(0, position);
                return new HistoryMoveResult(HistoryMoveResult.Outcome.MOVED, expected, Optional.empty());
            }

            @Override
            public boolean isCurrentBinding(final HistorySnapshot snapshot) {
                return snapshot.equals(expected);
            }
        };
        final CubismFacadeImpl facade = new CubismFacadeImpl(
                sampleSource(),
                new CubismPermissionGate(
                        "plugin.demo",
                        List.of(
                                permission(CubismFacadeImpl.MODEL_READ_PERMISSION),
                                permission(CubismFacadeImpl.MODEL_WRITE_PERMISSION)),
                        ignored -> {},
                        FIXED_CLOCK),
                () -> emptyModel("history-model"),
                new ParameterLifecycleCoordinator(),
                new dev.turboism.adapter.cubism.lifecycle.PartLifecycleCoordinator(),
                new dev.turboism.adapter.cubism.lifecycle.EditorObjectLifecycleCoordinator(),
                () -> true,
                history);

        final CubismHistory wrapped = facade.history();

        assertTrue(wrapped.isCurrentBinding(expected));
        assertEquals(
                HistoryMoveResult.Outcome.MOVED, wrapped.moveTo(expected, 0).outcome());
        assertEquals(1, bindingMoves[0]);
    }

    @Test
    void coreRuntimeChecksModelPermissionBeforeUnavailableRoute() {
        final CubismFacadeImpl facade = facadeWith(emptySource(), new ArrayList<>(), List.of());

        assertThrows(CubismPermissionException.class, facade::coreRuntime);
    }

    @Test
    void retainedCoreRuntimeHandlesRecheckPluginScopeBeforeProviderCalls() {
        final AtomicBoolean active = new AtomicBoolean(true);
        final int[] calls = new int[3];
        final dev.turboism.sdk.cubism.core.CoreRuntimeInfo backend =
                new dev.turboism.sdk.cubism.core.CoreRuntimeInfo() {
                    @Override
                    public dev.turboism.sdk.cubism.core.CoreVersion version() {
                        calls[0]++;
                        return new dev.turboism.sdk.cubism.core.CoreVersion(5, 3, 2);
                    }

                    @Override
                    public dev.turboism.sdk.cubism.core.CoreCapabilities capabilities() {
                        calls[1]++;
                        return new dev.turboism.sdk.cubism.core.CoreCapabilities(true, true, true);
                    }

                    @Override
                    public dev.turboism.sdk.cubism.core.MocInspector mocInspector() {
                        calls[2]++;
                        return new dev.turboism.sdk.cubism.core.MocInspector() {
                            @Override
                            public dev.turboism.sdk.cubism.core.MocVersion latestVersion() {
                                return dev.turboism.sdk.cubism.core.MocVersion.V5_3;
                            }

                            @Override
                            public dev.turboism.sdk.cubism.core.MocInfo inspect(
                                    final dev.turboism.sdk.cubism.core.MocData data) {
                                return new dev.turboism.sdk.cubism.core.MocInfo(
                                        dev.turboism.sdk.cubism.core.MocVersion.V5_3,
                                        dev.turboism.sdk.cubism.core.MocConsistency.CONSISTENT);
                            }
                        };
                    }
                };
        final CubismFacadeImpl facade = new CubismFacadeImpl(
                emptySource(),
                new CubismPermissionGate(
                        "plugin.demo",
                        List.of(permission(CubismFacadeImpl.MODEL_READ_PERMISSION)),
                        ignored -> {},
                        FIXED_CLOCK),
                new ImmutableSnapshotFactory(),
                () -> emptyModel("core-model"),
                backend,
                new ParameterLifecycleCoordinator(),
                new dev.turboism.adapter.cubism.lifecycle.PartLifecycleCoordinator(),
                new dev.turboism.adapter.cubism.lifecycle.EditorObjectLifecycleCoordinator(),
                active::get);
        final var runtime = facade.coreRuntime();
        assertEquals(new dev.turboism.sdk.cubism.core.CoreVersion(5, 3, 2), runtime.version());
        assertTrue(runtime.capabilities().mocInspection());
        final var inspector = runtime.mocInspector();
        assertEquals(1, calls[0]);
        assertEquals(1, calls[1]);
        assertEquals(1, calls[2]);

        active.set(false);

        assertThrows(IllegalStateException.class, runtime::version);
        assertThrows(IllegalStateException.class, inspector::latestVersion);
        assertEquals(1, calls[0]);
        assertEquals(1, calls[2]);
    }

    @Test
    void retainedOwnedMocHandlesRejectReadsAfterScopeCloseButStillReleaseAllocations() {
        TestCoreApiFixture.Moc.prepare(
                new TestCoreApiFixture.Model(
                        new TestCoreApiFixture.CanvasInfo(new float[] {1000F, 500F}, new float[] {500F, 250F}, 100F),
                        new TestCoreApiFixture.Parameters(
                                new String[] {"ParamAngleX"},
                                new TestCoreApiFixture.ParameterType[] {new TestCoreApiFixture.ParameterType(0)},
                                new float[] {-30F},
                                new float[] {30F},
                                new float[] {0F},
                                new float[] {10F},
                                new int[] {0},
                                new float[][] {new float[0]},
                                new boolean[] {false})),
                42L);
        final var active = new java.util.concurrent.atomic.AtomicBoolean(true);
        try (dev.turboism.adapter.cubism.core.RuntimeCoreModelBackend backend =
                dev.turboism.adapter.cubism.core.RuntimeCoreModelBackend.admit(
                                dev.turboism.adapter.cubism.core.TestCoreApiFixture.resolverWithExtras(
                                        "5.3.02", List.of(), java.util.Set.of()),
                                dev.turboism.adapter.cubism.core.CoreVersionExpectation.exact(11, 12, 13))
                        .value()
                        .orElseThrow()) {
            final CubismFacadeImpl facade = new CubismFacadeImpl(
                    emptySource(),
                    new CubismPermissionGate(
                            "plugin.demo",
                            List.of(permission(CubismFacadeImpl.MODEL_READ_PERMISSION)),
                            ignored -> {},
                            Clock.systemUTC()),
                    new dev.turboism.adapter.cubism.ImmutableSnapshotFactory(),
                    backend.modelAccess(),
                    backend.coreRuntimeInfo(),
                    new dev.turboism.adapter.cubism.lifecycle.ParameterLifecycleCoordinator(),
                    new dev.turboism.adapter.cubism.lifecycle.PartLifecycleCoordinator(),
                    new dev.turboism.adapter.cubism.lifecycle.EditorObjectLifecycleCoordinator(),
                    active::get);
            final var runtime = facade.coreRuntime();
            final var loader = runtime.mocLoader();
            final var data = dev.turboism.sdk.cubism.core.MocData.copyOf(new byte[] {6, 1});
            try (var moc = loader.load(data);
                    var model = moc.instantiateModel()) {
                model.update();
                assertTrue(model.parameters().size() > 0);
                active.set(false);
                final List<Runnable> retainedOperations = List.of(
                        runtime::mocLoader,
                        () -> loader.load(data),
                        moc::version,
                        moc::consistency,
                        moc::nativeHandle,
                        moc::instantiateModel,
                        model::nativeHandle,
                        model::canvasInfo,
                        model::parameters,
                        model::parts,
                        model::drawables,
                        model::glues,
                        model::deformers,
                        model::update);
                for (Runnable operation : retainedOperations) {
                    assertThrows(IllegalStateException.class, operation::run);
                }
                // try-with-resources must still release both allocations after deactivation.
            }
        }
    }

    @Test
    void retainedModelServicesRecheckPluginScopeBeforeBackendCalls() {
        final AtomicBoolean active = new AtomicBoolean(true);
        final int[] activeCalls = {0};
        final int[] collectionCalls = {0};
        final int[] parameterCalls = {0};
        final int[] canvasCalls = {0};
        final dev.turboism.sdk.cubism.model.Canvas backendCanvas = new dev.turboism.sdk.cubism.model.Canvas() {
            @Override
            public float widthPixels() {
                canvasCalls[0]++;
                return 100.0F;
            }

            @Override
            public float heightPixels() {
                return 100.0F;
            }

            @Override
            public float originXPixels() {
                return 0.0F;
            }

            @Override
            public float originYPixels() {
                return 0.0F;
            }

            @Override
            public float pixelsPerUnit() {
                return 100.0F;
            }
        };
        final dev.turboism.sdk.cubism.model.Parameter backendParameter = new dev.turboism.sdk.cubism.model.Parameter() {
            @Override
            public ParameterId id() {
                return new ParameterId("ParamA");
            }

            @Override
            public float getValue() {
                parameterCalls[0]++;
                return 0.0F;
            }

            @Override
            public float getMinimumValue() {
                return -1.0F;
            }

            @Override
            public float getMaximumValue() {
                return 1.0F;
            }

            @Override
            public float getDefaultValue() {
                return 0.0F;
            }

            @Override
            public void setValue(final float value) {
                parameterCalls[0]++;
            }
        };
        final Parameters backendParameters = new Parameters() {
            @Override
            public List<dev.turboism.sdk.cubism.model.Parameter> all() {
                collectionCalls[0]++;
                return List.of(backendParameter);
            }

            @Override
            public dev.turboism.sdk.cubism.model.Parameter find(final ParameterId id) {
                collectionCalls[0]++;
                throw new java.util.NoSuchElementException();
            }
        };
        final CubismModel backendModel = new CubismModel() {
            @Override
            public ModelId id() {
                return new ModelId("model-a");
            }

            @Override
            public Parameters parameters() {
                return backendParameters;
            }

            @Override
            public dev.turboism.sdk.cubism.model.Canvas canvas() {
                return backendCanvas;
            }

            @Override
            public Parts parts() {
                throw new UnsupportedOperationException();
            }

            @Override
            public Drawables drawables() {
                throw new UnsupportedOperationException();
            }

            @Override
            public Deformers deformers() {
                throw new UnsupportedOperationException();
            }

            @Override
            public Glues glues() {
                throw new UnsupportedOperationException();
            }

            @Override
            public void update() {
                throw new UnsupportedOperationException();
            }
        };
        final CubismFacadeImpl facade = new CubismFacadeImpl(
                emptySource(),
                new CubismPermissionGate(
                        "plugin.demo",
                        List.of(permission(CubismFacadeImpl.MODEL_READ_PERMISSION)),
                        ignored -> {},
                        FIXED_CLOCK),
                new ImmutableSnapshotFactory(),
                () -> {
                    activeCalls[0]++;
                    return backendModel;
                },
                new ParameterLifecycleCoordinator(),
                new dev.turboism.adapter.cubism.lifecycle.PartLifecycleCoordinator(),
                new dev.turboism.adapter.cubism.lifecycle.EditorObjectLifecycleCoordinator(),
                active::get);
        final CubismModelAccess retainedAccess = facade.model();
        final CubismModel retainedModel = retainedAccess.active();
        final dev.turboism.sdk.cubism.model.Canvas retainedCanvas = retainedModel.canvas();
        final Parameters retainedParameters = retainedModel.parameters();
        final dev.turboism.sdk.cubism.model.Parameter retainedParameter =
                retainedParameters.all().get(0);
        activeCalls[0] = 0;
        collectionCalls[0] = 0;

        active.set(false);

        assertThrows(IllegalStateException.class, retainedAccess::active);
        assertThrows(IllegalStateException.class, retainedParameters::all);
        assertThrows(IllegalStateException.class, retainedParameter::getValue);
        assertThrows(IllegalStateException.class, retainedCanvas::widthPixels);
        assertEquals(0, activeCalls[0]);
        assertEquals(0, collectionCalls[0]);
        assertEquals(0, parameterCalls[0]);
        assertEquals(0, canvasCalls[0]);
    }

    @Test
    void retainedTextureAtlasServicesRecheckPluginScopeBeforeDelegateCalls() {
        final AtomicBoolean active = new AtomicBoolean(true);
        final int[] calls = new int[8];
        final var layout = new dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutService() {
            @Override
            public Optional<dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutSnapshot> current() {
                calls[0]++;
                return Optional.empty();
            }

            @Override
            public dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutApplyResult apply(
                    final dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutTarget target,
                    final dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutPlan plan) {
                calls[1]++;
                throw new UnsupportedOperationException();
            }
        };
        final var session = new dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasEditorSession(() -> {
            calls[2]++;
            return null;
        });
        final var ui = new dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasEditorUi();
        final var algorithms =
                new dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasLayoutAlgorithmRegistry();
        final CubismFacadeImpl facade = new CubismFacadeImpl(
                emptySource(),
                new CubismPermissionGate("plugin.demo", List.of(), ignored -> {}, FIXED_CLOCK),
                new ImmutableSnapshotFactory(),
                () -> emptyModel("texture-model"),
                new ParameterLifecycleCoordinator(),
                new dev.turboism.adapter.cubism.lifecycle.PartLifecycleCoordinator(),
                layout,
                new dev.turboism.adapter.cubism.lifecycle.EditorObjectLifecycleCoordinator(),
                active::get,
                ui,
                session,
                algorithms);
        final var retainedLayouts = facade.textureAtlasLayouts();
        final var retainedSession = facade.textureAtlasEditorSession();
        final var retainedUi = facade.textureAtlasEditorUi();
        final var retainedPanel = retainedUi.attach();
        final var retainedAlgorithms = facade.textureAtlasAlgorithms();
        final var algorithm =
                new dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutAlgorithm("test", "Test", false, null);
        final var retainedRegistration = retainedAlgorithms.register(algorithm);

        active.set(false);

        assertThrows(IllegalStateException.class, retainedLayouts::current);
        assertThrows(IllegalStateException.class, retainedSession::summary);
        assertThrows(IllegalStateException.class, retainedUi::attach);
        assertThrows(IllegalStateException.class, () -> retainedPanel.setText("stale"));
        assertThrows(IllegalStateException.class, () -> retainedAlgorithms.find("test"));
        assertThrows(IllegalStateException.class, retainedAlgorithms::algorithms);
        assertThrows(IllegalStateException.class, retainedRegistration::close);
        assertEquals(0, calls[0]);
        assertEquals(0, calls[1]);
        assertEquals(0, calls[2]);
    }

    @Test
    void fixedWritesValidateArgumentsBeforeUnavailableDelegates() {
        final dev.turboism.sdk.cubism.model.Part backendPart = new dev.turboism.sdk.cubism.model.Part() {
            @Override
            public dev.turboism.sdk.cubism.model.PartId id() {
                return new dev.turboism.sdk.cubism.model.PartId("PartA");
            }

            @Override
            public void setName(final String name) {}

            @Override
            public float getOpacity() {
                return 1.0F;
            }

            @Override
            public int parentIndex() {
                return -1;
            }

            @Override
            public void setOpacity(final float opacity) {}
        };
        final CubismModel backendModel = new CubismModel() {
            @Override
            public ModelId id() {
                return new ModelId("model-a");
            }

            @Override
            public Parameters parameters() {
                throw new UnsupportedOperationException();
            }

            @Override
            public Parts parts() {
                return new Parts() {
                    @Override
                    public List<dev.turboism.sdk.cubism.model.Part> all() {
                        return List.of(backendPart);
                    }

                    @Override
                    public dev.turboism.sdk.cubism.model.Part find(final dev.turboism.sdk.cubism.model.PartId id) {
                        return backendPart;
                    }
                };
            }

            @Override
            public Drawables drawables() {
                throw new UnsupportedOperationException();
            }

            @Override
            public Deformers deformers() {
                throw new UnsupportedOperationException();
            }

            @Override
            public Glues glues() {
                throw new UnsupportedOperationException();
            }

            @Override
            public void update() {
                throw new UnsupportedOperationException();
            }
        };
        final CubismFacadeImpl facade = new CubismFacadeImpl(
                emptySource(),
                new CubismPermissionGate(
                        "plugin.demo",
                        List.of(
                                permission(CubismFacadeImpl.MODEL_READ_PERMISSION),
                                permission(CubismFacadeImpl.MODEL_WRITE_PERMISSION)),
                        ignored -> {},
                        FIXED_CLOCK),
                () -> backendModel);
        final CubismModel model = facade.model().active();
        final dev.turboism.sdk.cubism.model.Part part = model.parts().all().get(0);

        assertThrows(NullPointerException.class, () -> model.setName(null));
        assertThrows(IllegalArgumentException.class, () -> model.setName("  \t"));
        assertThrows(NullPointerException.class, () -> part.setShortName(null));
        assertThrows(IllegalArgumentException.class, () -> part.setShortName(Optional.of("  ")));
        assertThrows(NullPointerException.class, () -> part.setEditColor(null));
    }

    @Test
    void modelDelegatesToRuntimeOwnedAccessAfterPermissionCheck() {
        final List<CubismFacadeAuditEvent> auditEvents = new ArrayList<>();
        final CubismModelAccess expected = () -> emptyModel("core-model");
        final CubismFacadeImpl facade = new CubismFacadeImpl(
                sampleSource(),
                new CubismPermissionGate(
                        "plugin.demo",
                        List.of(permission(CubismFacadeImpl.MODEL_READ_PERMISSION)),
                        auditEvents::add,
                        FIXED_CLOCK),
                expected);

        assertEquals(new ModelId("core-model"), facade.model().active().id());
        assertTrue(auditEvents.isEmpty());
    }

    @Test
    void clipMaskReplacementRequiresModelWritePermissionBeforeDelegation() {
        final List<List<ClipMaskReplacement>> calls = new ArrayList<>();
        final CubismModelAccess backend = () -> emptyModel("model-a", calls::add);
        final ClipMaskReplacement replacement = new ClipMaskReplacement(
                new ArtMeshId("target"), List.of(), false, List.of(new ArtMeshId("mask")), false);
        final CubismFacadeImpl denied = new CubismFacadeImpl(
                sampleSource(),
                new CubismPermissionGate(
                        "plugin.demo",
                        List.of(permission(CubismFacadeImpl.MODEL_READ_PERMISSION)),
                        ignored -> {},
                        FIXED_CLOCK),
                backend);

        assertThrows(
                CubismPermissionException.class,
                () -> denied.model().active().replaceArtMeshClipMasks(List.of(replacement)));
        assertEquals(List.of(), calls);

        final CubismFacadeImpl allowed = new CubismFacadeImpl(
                sampleSource(),
                new CubismPermissionGate(
                        "plugin.demo",
                        List.of(
                                permission(CubismFacadeImpl.MODEL_READ_PERMISSION),
                                permission(CubismFacadeImpl.MODEL_WRITE_PERMISSION)),
                        ignored -> {},
                        FIXED_CLOCK),
                backend);
        allowed.model().active().replaceArtMeshClipMasks(List.of(replacement));
        assertEquals(List.of(List.of(replacement)), calls);
    }

    @Test
    void modelPermissionDenialDoesNotInvokeRuntimeAccess() {
        final List<CubismFacadeAuditEvent> auditEvents = new ArrayList<>();
        final boolean[] invoked = {false};
        final CubismFacadeImpl facade = new CubismFacadeImpl(
                sampleSource(),
                new CubismPermissionGate("plugin.demo", List.of(), auditEvents::add, FIXED_CLOCK),
                () -> {
                    invoked[0] = true;
                    return emptyModel("unexpected");
                });

        assertThrows(CubismPermissionException.class, facade::model);
        assertFalse(invoked[0]);
        assertEquals("model", auditEvents.get(0).methodName());
    }

    @Test
    void pluginPermissionWrapperPreservesParameterMetadata() {
        final CubismFacadeImpl facade = new CubismFacadeImpl(
                sampleSource(),
                new CubismPermissionGate(
                        "plugin.demo",
                        List.of(permission(CubismFacadeImpl.MODEL_READ_PERMISSION)),
                        ignored -> {},
                        FIXED_CLOCK),
                () -> modelWithParameter(new float[] {1.0F}));

        final var parameter = facade.model().active().parameters().find(new ParameterId("ParamA"));
        assertEquals(3, parameter.index());
        assertEquals(0, parameter.keyValues().size());
        assertEquals(Optional.of("Parameter A"), parameter.name());
        assertEquals(ParameterType.BLEND_SHAPE, parameter.type());
        assertEquals(Optional.of(false), parameter.repeat());
        assertEquals(Optional.of(true), parameter.combined());
        assertEquals(Optional.of(new ParameterId("ParamB")), parameter.combinedWith());
        assertEquals(List.of(), parameter.getParameterBindings());
    }

    @ParameterizedTest
    @ValueSource(strings = {"defaultKeyformLocked", "editLevel"})
    void modelDirectWritesRequireModelWritePermission(final String writeType) {
        final List<CubismFacadeAuditEvent> auditEvents = new ArrayList<>();
        final boolean isKeyform = "defaultKeyformLocked".equals(writeType);
        final boolean[] lockedWrites = {false};
        final dev.turboism.sdk.cubism.model.ModelEditLevel[] levelWrites = {null};

        final CubismModelAccess backend = () -> new CubismModel() {
            @Override
            public ModelId id() {
                return new ModelId("model-a");
            }

            @Override
            public boolean defaultKeyformLocked() {
                return false;
            }

            @Override
            public void setDefaultKeyformLocked(final boolean locked) {
                lockedWrites[0] = locked;
            }

            @Override
            public dev.turboism.sdk.cubism.model.ModelEditLevel editLevel() {
                return dev.turboism.sdk.cubism.model.ModelEditLevel.LEVEL_1;
            }

            @Override
            public void setEditLevel(final dev.turboism.sdk.cubism.model.ModelEditLevel level) {
                levelWrites[0] = level;
            }

            @Override
            public dev.turboism.sdk.cubism.model.Parameters parameters() {
                throw new UnsupportedOperationException();
            }

            @Override
            public dev.turboism.sdk.cubism.model.Parts parts() {
                throw new UnsupportedOperationException();
            }

            @Override
            public dev.turboism.sdk.cubism.model.Drawables drawables() {
                throw new UnsupportedOperationException();
            }

            @Override
            public dev.turboism.sdk.cubism.model.Deformers deformers() {
                throw new UnsupportedOperationException();
            }

            @Override
            public dev.turboism.sdk.cubism.model.Glues glues() {
                throw new UnsupportedOperationException();
            }

            @Override
            public void update() {
                throw new UnsupportedOperationException();
            }
        };

        final CubismFacadeImpl denied = new CubismFacadeImpl(
                sampleSource(),
                new CubismPermissionGate(
                        "plugin.demo",
                        List.of(permission(CubismFacadeImpl.MODEL_READ_PERMISSION)),
                        auditEvents::add,
                        FIXED_CLOCK),
                backend);

        if (isKeyform) {
            assertThrows(
                    CubismPermissionException.class,
                    () -> denied.model().active().setDefaultKeyformLocked(true));
            assertFalse(lockedWrites[0]);
            assertEquals("model.setDefaultKeyformLocked", auditEvents.get(0).operationId());
        } else {
            assertThrows(
                    CubismPermissionException.class,
                    () -> denied.model().active().setEditLevel(dev.turboism.sdk.cubism.model.ModelEditLevel.LEVEL_2));
            assertNull(levelWrites[0]);
            assertEquals("model.setEditLevel", auditEvents.get(0).operationId());
        }

        final CubismFacadeImpl allowed = new CubismFacadeImpl(
                sampleSource(),
                new CubismPermissionGate(
                        "plugin.demo",
                        List.of(
                                permission(CubismFacadeImpl.MODEL_READ_PERMISSION),
                                permission(CubismFacadeImpl.MODEL_WRITE_PERMISSION)),
                        ignored -> {},
                        FIXED_CLOCK),
                backend);
        if (isKeyform) {
            allowed.model().active().setDefaultKeyformLocked(true);
            assertTrue(lockedWrites[0]);
        } else {
            allowed.model().active().setEditLevel(dev.turboism.sdk.cubism.model.ModelEditLevel.LEVEL_2);
            assertEquals(dev.turboism.sdk.cubism.model.ModelEditLevel.LEVEL_2, levelWrites[0]);
        }
    }

    @Test
    void combinedWritesRequireModelWritePermissionBeforeInvokingBackend() {
        final List<CubismFacadeAuditEvent> auditEvents = new ArrayList<>();
        final List<String> calls = new ArrayList<>();
        final CubismModelAccess backend = () -> modelWithCombinedCalls(calls);
        final CubismFacadeImpl denied = new CubismFacadeImpl(
                sampleSource(),
                new CubismPermissionGate(
                        "plugin.demo",
                        List.of(permission(CubismFacadeImpl.MODEL_READ_PERMISSION)),
                        auditEvents::add,
                        FIXED_CLOCK),
                backend);
        final var deniedParameter = denied.model().active().parameters().find(new ParameterId("ParamA"));

        assertThrows(CubismPermissionException.class, () -> deniedParameter.combineWith(new ParameterId("ParamB")));
        assertThrows(CubismPermissionException.class, deniedParameter::uncombine);
        assertEquals(List.of(), calls);
        assertEquals(
                List.of("parameter.combineWith", "parameter.uncombine"),
                auditEvents.stream().map(CubismFacadeAuditEvent::methodName).toList());
        assertEquals(
                List.of(CubismFacadeImpl.MODEL_WRITE_PERMISSION, CubismFacadeImpl.MODEL_WRITE_PERMISSION),
                auditEvents.stream().map(CubismFacadeAuditEvent::permissionId).toList());
        assertEquals(
                List.of("parameter.combineWith", "parameter.uncombine"),
                auditEvents.stream().map(CubismFacadeAuditEvent::operationId).toList());

        final CubismFacadeImpl allowed = new CubismFacadeImpl(
                sampleSource(),
                new CubismPermissionGate(
                        "plugin.demo",
                        List.of(
                                permission(CubismFacadeImpl.MODEL_READ_PERMISSION),
                                permission(CubismFacadeImpl.MODEL_WRITE_PERMISSION)),
                        ignored -> {},
                        FIXED_CLOCK),
                backend);
        final var allowedParameter = allowed.model().active().parameters().find(new ParameterId("ParamA"));
        allowedParameter.combineWith(new ParameterId("ParamB"));
        allowedParameter.uncombine();
        assertEquals(List.of("combine:ParamB", "uncombine"), calls);
    }

    @ParameterizedTest
    @ValueSource(strings = {"setValue", "resetToDefault"})
    void parameterDirectWritesRequireModelWritePermissionBeforeInvokingBackend(final String operation) {
        final List<CubismFacadeAuditEvent> auditEvents = new ArrayList<>();
        final float[] value = {1.0F};
        final CubismModelAccess backend = () -> modelWithParameter(value);
        final CubismFacadeImpl denied = new CubismFacadeImpl(
                sampleSource(),
                new CubismPermissionGate(
                        "plugin.demo",
                        List.of(permission(CubismFacadeImpl.MODEL_READ_PERMISSION)),
                        auditEvents::add,
                        FIXED_CLOCK),
                backend);
        final dev.turboism.sdk.cubism.model.Parameter deniedParameter =
                denied.model().active().parameters().find(new ParameterId("ParamA"));

        if ("setValue".equals(operation)) {
            assertThrows(CubismPermissionException.class, () -> deniedParameter.setValue(2.0F));
            assertEquals("parameter.setValue", auditEvents.get(0).operationId());
        } else {
            assertThrows(CubismPermissionException.class, deniedParameter::resetToDefault);
            assertEquals("parameter.resetToDefault", auditEvents.get(0).operationId());
        }
        assertEquals(1.0F, value[0]);
        assertEquals(CubismFacadeImpl.MODEL_WRITE_PERMISSION, auditEvents.get(0).permissionId());

        final CubismFacadeImpl allowed = new CubismFacadeImpl(
                sampleSource(),
                new CubismPermissionGate(
                        "plugin.demo",
                        List.of(
                                permission(CubismFacadeImpl.MODEL_READ_PERMISSION),
                                permission(CubismFacadeImpl.MODEL_WRITE_PERMISSION)),
                        auditEvents::add,
                        FIXED_CLOCK),
                backend);
        final dev.turboism.sdk.cubism.model.Parameter allowedParameter =
                allowed.model().active().parameters().find(new ParameterId("ParamA"));
        if ("setValue".equals(operation)) {
            allowedParameter.setValue(3.0F);
            assertEquals(3.0F, value[0]);
        } else {
            allowedParameter.resetToDefault();
            assertEquals(0.0F, value[0]);
        }
    }

    @Test
    void parameterDefinitionUpdateRequiresPermissionAndAvoidsStalePostRead() {
        final List<CubismFacadeAuditEvent> auditEvents = new ArrayList<>();
        final float[] value = {1.0F};
        final ParameterDefinition[] updated = {null};
        final CubismModelAccess backend = () -> modelWithParameter(value, updated);
        final CubismFacadeImpl denied = new CubismFacadeImpl(
                sampleSource(),
                new CubismPermissionGate(
                        "plugin.demo",
                        List.of(permission(CubismFacadeImpl.MODEL_READ_PERMISSION)),
                        auditEvents::add,
                        FIXED_CLOCK),
                backend);
        final ParameterDefinition definition = new ParameterDefinition(
                new ParameterId("ParamRenamed"), "Renamed", -2.0F, 0.0F, 2.0F, ParameterType.NORMAL, true);

        assertThrows(
                CubismPermissionException.class,
                () -> denied.model()
                        .active()
                        .parameters()
                        .find(new ParameterId("ParamA"))
                        .updateDefinition(definition));
        assertEquals(null, updated[0]);
        assertEquals(CubismFacadeImpl.MODEL_WRITE_PERMISSION, auditEvents.get(0).permissionId());
        assertEquals("parameter.updateDefinition", auditEvents.get(0).operationId());

        final CubismFacadeImpl allowed = new CubismFacadeImpl(
                sampleSource(),
                new CubismPermissionGate(
                        "plugin.demo",
                        List.of(
                                permission(CubismFacadeImpl.MODEL_READ_PERMISSION),
                                permission(CubismFacadeImpl.MODEL_WRITE_PERMISSION)),
                        auditEvents::add,
                        FIXED_CLOCK),
                backend);
        allowed.model().active().parameters().find(new ParameterId("ParamA")).updateDefinition(definition);
        assertEquals(definition, updated[0]);
    }

    @Test
    void editorObjectWritesRequireModelWritePermissionBeforeBackendMutation() {
        final int[] mutations = {0};
        final MockDrawable drawable = new MockDrawable("ArtMeshA", () -> mutations[0]++);
        final MockWarpDeformer warp = new MockWarpDeformer("WarpA", () -> mutations[0]++);
        final MockRotationDeformer rotation = new MockRotationDeformer("RotationA", () -> mutations[0]++);
        final CubismModel model = editorObjectModel(drawable, warp, rotation);
        final CubismFacadeImpl denied = facadeWith(
                sampleSource(),
                new ArrayList<>(),
                List.of(permission(CubismFacadeImpl.MODEL_READ_PERMISSION)),
                () -> model);
        final ArtMeshGeometry geometry = drawable.geometry;
        final WarpGrid grid = warp.grid;
        final RotationDeformerForm form = new RotationDeformerForm(1, 2, 3, 1, false, false);

        assertThrows(
                CubismPermissionException.class,
                () -> denied.model().active().drawables().find(drawable.id()).setOpacity(0.5F));
        assertThrows(
                CubismPermissionException.class,
                () -> denied.model().active().drawables().find(drawable.id()).replaceGeometry(geometry));
        assertThrows(
                CubismPermissionException.class,
                () -> denied.model().active().warpDeformers().find(warp.id()).replaceGrid(grid));
        assertThrows(
                CubismPermissionException.class,
                () -> denied.model()
                        .active()
                        .rotationDeformers()
                        .find(rotation.id())
                        .replaceForm(form));
        assertEquals(0, mutations[0]);

        final CubismFacadeImpl allowed = facadeWith(
                sampleSource(),
                new ArrayList<>(),
                List.of(
                        permission(CubismFacadeImpl.MODEL_READ_PERMISSION),
                        permission(CubismFacadeImpl.MODEL_WRITE_PERMISSION)),
                () -> model);
        allowed.model().active().drawables().find(drawable.id()).setOpacity(0.5F);
        allowed.model().active().drawables().find(drawable.id()).replaceGeometry(geometry);
        allowed.model().active().warpDeformers().find(warp.id()).replaceGrid(grid);
        allowed.model().active().rotationDeformers().find(rotation.id()).replaceForm(form);
        assertEquals(4, mutations[0]);
    }

    @Test
    void deformerApplyToChildrenChecksPermissionAndWrapperOwnerBeforeForwarding() {
        final Deformer backend = new Deformer() {
            @Override
            public DeformerId id() {
                return new DeformerId("WarpA");
            }

            @Override
            public int parentDeformerIndex() {
                return -1;
            }

            @Override
            public IntSequence parameters() {
                return emptyInts();
            }
        };
        final int[] calls = {0};
        final Deformer[] forwarded = {null};
        final Deformers backendDeformers = new Deformers() {
            @Override
            public List<Deformer> all() {
                return List.of(backend);
            }

            @Override
            public Deformer find(final DeformerId id) {
                return backend;
            }

            @Override
            public void applyToChildren(final Deformer deformer) {
                calls[0]++;
                forwarded[0] = deformer;
            }
        };
        final CubismModel model = new CubismModel() {
            @Override
            public ModelId id() {
                return new ModelId("model-1");
            }

            @Override
            public Parameters parameters() {
                throw new UnsupportedOperationException();
            }

            @Override
            public Parts parts() {
                throw new UnsupportedOperationException();
            }

            @Override
            public Drawables drawables() {
                throw new UnsupportedOperationException();
            }

            @Override
            public Deformers deformers() {
                return backendDeformers;
            }

            @Override
            public Glues glues() {
                throw new UnsupportedOperationException();
            }

            @Override
            public void update() {
                throw new UnsupportedOperationException();
            }
        };
        final CubismFacadeImpl denied = facadeWith(
                sampleSource(),
                new ArrayList<>(),
                List.of(permission(CubismFacadeImpl.MODEL_READ_PERMISSION)),
                () -> model);
        final Deformers deniedDeformers = denied.model().active().deformers();
        final Deformer deniedWrapper = deniedDeformers.find(new DeformerId("WarpA"));

        assertThrows(CubismPermissionException.class, () -> deniedDeformers.applyToChildren(deniedWrapper));
        assertEquals(0, calls[0]);

        final CubismFacadeImpl allowed = facadeWith(
                sampleSource(),
                new ArrayList<>(),
                List.of(
                        permission(CubismFacadeImpl.MODEL_READ_PERMISSION),
                        permission(CubismFacadeImpl.MODEL_WRITE_PERMISSION)),
                () -> model);
        final Deformers allowedDeformers = allowed.model().active().deformers();
        assertThrows(IllegalArgumentException.class, () -> allowedDeformers.applyToChildren(deniedWrapper));
        assertEquals(0, calls[0]);

        allowedDeformers.applyToChildren(allowedDeformers.find(new DeformerId("WarpA")));
        assertEquals(1, calls[0]);
        assertTrue(forwarded[0] == backend);
    }

    @Test
    void legacyConstructorsKeepUnifiedModelAccessExplicitlyUnavailable() {
        final CubismFacadeImpl facade = facadeWith(
                sampleSource(), new ArrayList<>(), List.of(permission(CubismFacadeImpl.MODEL_READ_PERMISSION)));

        assertThrows(IllegalStateException.class, () -> facade.model().active());
    }

    @Test
    void pluginScopeInvalidatesPreviouslyObtainedModelAndEditorObjectReferences() {
        final AtomicBoolean active = new AtomicBoolean(true);
        final Drawable drawable = new Drawable() {
            @Override
            public ArtMeshId id() {
                return new ArtMeshId("ArtMeshA");
            }

            @Override
            public ArtMeshGeometry geometry() {
                return new ArtMeshGeometry(
                        List.of(new Point2(0, 0), new Point2(1, 0), new Point2(0, 1)),
                        List.of(new Point2(0, 0), new Point2(1, 0), new Point2(0, 1)),
                        List.of(0, 1, 2));
            }

            @Override
            public byte constantFlag() {
                return 0;
            }

            @Override
            public byte dynamicFlag() {
                return 0;
            }

            @Override
            public dev.turboism.sdk.cubism.model.BlendMode blendMode() {
                return dev.turboism.sdk.cubism.model.BlendMode.NORMAL;
            }

            @Override
            public int textureIndex() {
                return 0;
            }

            @Override
            public int drawOrder() {
                return 0;
            }

            @Override
            public int renderOrder() {
                return 0;
            }

            @Override
            public float getOpacity() {
                return 1.0F;
            }

            @Override
            public IntSequence masks() {
                return emptyInts();
            }

            @Override
            public dev.turboism.sdk.cubism.model.FloatSequence vertexPositions() {
                return emptyFloats();
            }

            @Override
            public dev.turboism.sdk.cubism.model.FloatSequence vertexUvs() {
                return emptyFloats();
            }

            @Override
            public IntSequence indices() {
                return emptyInts();
            }

            @Override
            public Color multiplyColor() {
                return new Color(1, 1, 1, 1);
            }

            @Override
            public Color screenColor() {
                return new Color(0, 0, 0, 1);
            }

            @Override
            public int parentPartIndex() {
                return -1;
            }

            @Override
            public int parentDeformerIndex() {
                return -1;
            }

            @Override
            public IntSequence parameters() {
                return emptyInts();
            }

            @Override
            public List<dev.turboism.sdk.cubism.model.ParameterBinding> getParameterBindings() {
                return List.of();
            }
        };
        final WarpDeformer backendWarp = new WarpDeformer() {
            @Override
            public DeformerId id() {
                return new DeformerId("WarpA");
            }

            @Override
            public WarpGrid grid() {
                return new WarpGrid(
                        1, 1, false, List.of(new Point2(0, 0), new Point2(1, 0), new Point2(0, 1), new Point2(1, 1)));
            }

            @Override
            public void replaceGrid(final WarpGrid grid) {}

            @Override
            public int parentDeformerIndex() {
                return -1;
            }

            @Override
            public IntSequence parameters() {
                return emptyInts();
            }

            @Override
            public List<dev.turboism.sdk.cubism.model.ParameterBinding> getParameterBindings() {
                return List.of();
            }
        };
        final RotationDeformer backendRotation = new RotationDeformer() {
            @Override
            public DeformerId id() {
                return new DeformerId("RotationA");
            }

            @Override
            public RotationDeformerForm form() {
                return new RotationDeformerForm(0, 0, 0, 1, false, false);
            }

            @Override
            public float baseAngle() {
                return 0.0F;
            }

            @Override
            public void setBaseAngle(final float angle) {}

            @Override
            public void replaceForm(final RotationDeformerForm form) {}

            @Override
            public int parentDeformerIndex() {
                return -1;
            }

            @Override
            public IntSequence parameters() {
                return emptyInts();
            }

            @Override
            public List<dev.turboism.sdk.cubism.model.ParameterBinding> getParameterBindings() {
                return List.of();
            }
        };
        final CubismFacadeImpl facade = new CubismFacadeImpl(
                sampleSource(),
                new CubismPermissionGate(
                        "plugin.demo",
                        List.of(
                                permission(CubismFacadeImpl.MODEL_READ_PERMISSION),
                                permission(CubismFacadeImpl.MODEL_WRITE_PERMISSION)),
                        ignored -> {},
                        FIXED_CLOCK),
                () -> editorObjectModel(drawable, backendWarp, backendRotation),
                new ParameterLifecycleCoordinator(),
                new dev.turboism.adapter.cubism.lifecycle.PartLifecycleCoordinator(),
                active::get);
        final CubismModel model = facade.model().active();
        final Drawable mesh = model.drawables().all().get(0);
        final WarpDeformer warp = model.warpDeformers().all().get(0);
        final RotationDeformer rotation = model.rotationDeformers().all().get(0);
        assertEquals(List.of(), mesh.getParameterBindings());
        assertEquals(List.of(), warp.getParameterBindings());
        assertEquals(List.of(), rotation.getParameterBindings());

        active.set(false);

        assertThrows(IllegalStateException.class, facade::runtime);
        assertThrows(IllegalStateException.class, facade::model);
    }

    @Test
    void parameterSetterUsesTheRuntimeLifecycleAroundTheBackendWrite() {
        final float[] value = {1.0F};
        final List<String> events = new ArrayList<>();
        final List<String> semanticEvents = new ArrayList<>();
        final ParameterLifecycleCoordinator lifecycle = new ParameterLifecycleCoordinator();
        final dev.turboism.adapter.cubism.lifecycle.SemanticOperationLifecycleCoordinator semantic =
                new dev.turboism.adapter.cubism.lifecycle.SemanticOperationLifecycleCoordinator();
        semantic.register(new dev.turboism.adapter.cubism.lifecycle.SemanticOperationLifecycleCoordinator.PluginHooks(
                descriptor("plugin.semantic"),
                List.of(new dev.turboism.sdk.cubism.hook.SemanticOperationHooks() {
                    @Override
                    public void beforeCubismOperation(final dev.turboism.sdk.cubism.event.CubismOperationEvent event) {
                        semanticEvents.add("before:" + event.operation());
                    }

                    @Override
                    public void onCubismOperationConfirmed(
                            final dev.turboism.sdk.cubism.event.CubismOperationEvent event) {
                        semanticEvents.add("on:" + event.operation());
                    }

                    @Override
                    public void afterCubismOperation(final dev.turboism.sdk.cubism.event.CubismOperationEvent event) {
                        semanticEvents.add("after:" + event.operation());
                    }
                }),
                logger()));
        lifecycle.register(new ParameterLifecycleCoordinator.PluginHooks(
                descriptor("plugin.hooks"),
                List.of(new CubismPlugin() {
                    @Override
                    public float beforeSetParameterValue(
                            final dev.turboism.sdk.cubism.model.Parameter parameter, final float requested) {
                        return requested * 0.5F;
                    }

                    @Override
                    public void onParameterValueChanged(
                            final dev.turboism.sdk.cubism.model.Parameter parameter,
                            final float oldValue,
                            final float newValue) {
                        events.add("on:" + oldValue + "->" + newValue);
                    }

                    @Override
                    public void afterSetParameterValue(
                            final dev.turboism.sdk.cubism.model.Parameter parameter, final float finalValue) {
                        events.add("after:" + finalValue);
                    }
                }),
                logger()));
        final CubismFacadeImpl facade = new CubismFacadeImpl(
                sampleSource(),
                new CubismPermissionGate(
                        "plugin.caller",
                        List.of(
                                permission(CubismFacadeImpl.MODEL_READ_PERMISSION),
                                permission(CubismFacadeImpl.MODEL_WRITE_PERMISSION)),
                        ignored -> {},
                        FIXED_CLOCK),
                () -> modelWithParameter(value),
                lifecycle,
                new dev.turboism.adapter.cubism.lifecycle.PartLifecycleCoordinator(),
                new dev.turboism.adapter.cubism.lifecycle.EditorObjectLifecycleCoordinator(
                        new dev.turboism.adapter.cubism.lifecycle.DrawableLifecycleCoordinator(),
                        new dev.turboism.adapter.cubism.lifecycle.DeformerLifecycleCoordinator(),
                        semantic),
                () -> true);

        facade.model().active().parameters().find(new ParameterId("ParamA")).setValue(10.0F);
        lifecycle.awaitIdle();
        semantic.awaitIdle();

        assertEquals(5.0F, value[0]);
        assertEquals(List.of("on:1.0->5.0", "after:5.0"), events);
        assertEquals(
                List.of("before:SET_PARAMETER_VALUE", "on:SET_PARAMETER_VALUE", "after:SET_PARAMETER_VALUE"),
                semanticEvents);
    }

    @Test
    void deniedEditorObjectWriteDoesNotEnterBeforeHooks() {
        final int[] mutations = {0};
        final List<String> events = new ArrayList<>();
        final MockDrawable drawable = new MockDrawable("ArtMeshA", () -> mutations[0]++);
        final var lifecycle = new dev.turboism.adapter.cubism.lifecycle.EditorObjectLifecycleCoordinator();
        lifecycle
                .drawable()
                .register(new dev.turboism.adapter.cubism.lifecycle.DrawableLifecycleCoordinator.PluginHooks(
                        descriptor("hook.plugin"),
                        List.of(new dev.turboism.sdk.cubism.hook.DrawableHooks() {
                            @Override
                            public float beforeSetDrawableOpacity(Drawable value, float requested) {
                                events.add("before");
                                return requested * 0.5F;
                            }
                        }),
                        logger()));
        final CubismFacadeImpl facade = new CubismFacadeImpl(
                sampleSource(),
                new CubismPermissionGate(
                        "plugin.caller",
                        List.of(permission(CubismFacadeImpl.MODEL_READ_PERMISSION)),
                        ignored -> {},
                        FIXED_CLOCK),
                () -> editorObjectModel(drawable, new MockWarpDeformer("WarpA"), new MockRotationDeformer("RotationA")),
                new ParameterLifecycleCoordinator(),
                new dev.turboism.adapter.cubism.lifecycle.PartLifecycleCoordinator(),
                lifecycle,
                () -> true);

        assertThrows(
                CubismPermissionException.class,
                () -> facade.model().active().drawables().find(drawable.id()).setOpacity(0.5F));
        lifecycle.drawable().awaitIdle();
        assertEquals(List.of(), events);
        assertEquals(0, mutations[0]);
        assertEquals(1.0F, drawable.opacity);
    }

    @Test
    void genericDeformerProjectionPreservesSubtypeWritesAndLifecycle() {
        // Before hooks run on the caller; observer hooks run on the callback worker.
        final java.util.Map<String, Integer> counts = new java.util.concurrent.ConcurrentHashMap<>();
        final java.util.function.Consumer<String> count = key -> counts.merge(key, 1, Integer::sum);
        final MockWarpDeformer backendWarp = new MockWarpDeformer("WarpA");
        final MockRotationDeformer backendRotation = new MockRotationDeformer("RotationA");
        final var lifecycle = new dev.turboism.adapter.cubism.lifecycle.EditorObjectLifecycleCoordinator();
        lifecycle
                .deformer()
                .register(new dev.turboism.adapter.cubism.lifecycle.DeformerLifecycleCoordinator.PluginHooks(
                        descriptor("hook.plugin"),
                        List.of(new dev.turboism.sdk.cubism.hook.DeformerHooks() {
                            @Override
                            public float beforeSetDeformerOpacity(Deformer value, float requested) {
                                count.accept(value.id().value() + ":opacity:before");
                                return requested;
                            }

                            @Override
                            public void onDeformerOpacityChanged(Deformer value, float oldValue, float newValue) {
                                count.accept(value.id().value() + ":opacity:on");
                            }

                            @Override
                            public void afterSetDeformerOpacity(Deformer value, float finalValue) {
                                count.accept(value.id().value() + ":opacity:after");
                            }

                            @Override
                            public boolean beforeSetDeformerVisible(Deformer value, boolean requested) {
                                count.accept(value.id().value() + ":visible:before");
                                return requested;
                            }

                            @Override
                            public void onDeformerVisibilityChanged(
                                    Deformer value, boolean oldValue, boolean newValue) {
                                count.accept(value.id().value() + ":visible:on");
                            }

                            @Override
                            public void afterSetDeformerVisible(Deformer value, boolean finalValue) {
                                count.accept(value.id().value() + ":visible:after");
                            }

                            @Override
                            public boolean beforeSetDeformerLocked(Deformer value, boolean requested) {
                                count.accept(value.id().value() + ":locked:before");
                                return requested;
                            }

                            @Override
                            public void onDeformerLockChanged(Deformer value, boolean oldValue, boolean newValue) {
                                count.accept(value.id().value() + ":locked:on");
                            }

                            @Override
                            public void afterSetDeformerLocked(Deformer value, boolean finalValue) {
                                count.accept(value.id().value() + ":locked:after");
                            }

                            @Override
                            public WarpGrid beforeReplaceWarpDeformerGrid(WarpDeformer value, WarpGrid requested) {
                                count.accept(value.id().value() + ":grid:before");
                                return requested;
                            }

                            @Override
                            public void onWarpDeformerGridChanged(
                                    WarpDeformer value, WarpGrid oldValue, WarpGrid newValue) {
                                count.accept(value.id().value() + ":grid:on");
                            }

                            @Override
                            public void afterReplaceWarpDeformerGrid(WarpDeformer value, WarpGrid finalValue) {
                                count.accept(value.id().value() + ":grid:after");
                            }

                            @Override
                            public float beforeSetRotationDeformerBaseAngle(RotationDeformer value, float requested) {
                                count.accept(value.id().value() + ":angle:before");
                                return requested;
                            }

                            @Override
                            public void onRotationDeformerBaseAngleChanged(
                                    RotationDeformer value, float oldValue, float newValue) {
                                count.accept(value.id().value() + ":angle:on");
                            }

                            @Override
                            public void afterSetRotationDeformerBaseAngle(RotationDeformer value, float finalValue) {
                                count.accept(value.id().value() + ":angle:after");
                            }

                            @Override
                            public RotationDeformerForm beforeReplaceRotationDeformerForm(
                                    RotationDeformer value, RotationDeformerForm requested) {
                                count.accept(value.id().value() + ":form:before");
                                return requested;
                            }

                            @Override
                            public void onRotationDeformerFormChanged(
                                    RotationDeformer value,
                                    RotationDeformerForm oldValue,
                                    RotationDeformerForm newValue) {
                                count.accept(value.id().value() + ":form:on");
                            }

                            @Override
                            public void afterReplaceRotationDeformerForm(
                                    RotationDeformer value, RotationDeformerForm finalValue) {
                                count.accept(value.id().value() + ":form:after");
                            }
                        }),
                        logger()));
        final CubismModel backendModel = editorObjectModel(
                new Drawable() {
                    @Override
                    public ArtMeshId id() {
                        return new ArtMeshId("ArtMeshA");
                    }

                    @Override
                    public float getOpacity() {
                        return 1.0F;
                    }

                    @Override
                    public void setOpacity(float value) {}

                    @Override
                    public byte constantFlag() {
                        return 0;
                    }

                    @Override
                    public byte dynamicFlag() {
                        return 0;
                    }

                    @Override
                    public dev.turboism.sdk.cubism.model.BlendMode blendMode() {
                        return dev.turboism.sdk.cubism.model.BlendMode.NORMAL;
                    }

                    @Override
                    public int textureIndex() {
                        return 0;
                    }

                    @Override
                    public int drawOrder() {
                        return 0;
                    }

                    @Override
                    public int renderOrder() {
                        return 0;
                    }

                    @Override
                    public IntSequence masks() {
                        return emptyInts();
                    }

                    @Override
                    public dev.turboism.sdk.cubism.model.FloatSequence vertexPositions() {
                        return emptyFloats();
                    }

                    @Override
                    public dev.turboism.sdk.cubism.model.FloatSequence vertexUvs() {
                        return emptyFloats();
                    }

                    @Override
                    public IntSequence indices() {
                        return emptyInts();
                    }

                    @Override
                    public Color multiplyColor() {
                        return new Color(1, 1, 1, 1);
                    }

                    @Override
                    public Color screenColor() {
                        return new Color(0, 0, 0, 1);
                    }

                    @Override
                    public int parentPartIndex() {
                        return -1;
                    }

                    @Override
                    public int parentDeformerIndex() {
                        return -1;
                    }

                    @Override
                    public IntSequence parameters() {
                        return emptyInts();
                    }
                },
                backendWarp,
                backendRotation);
        final CubismFacadeImpl facade = new CubismFacadeImpl(
                sampleSource(),
                new CubismPermissionGate(
                        "plugin.caller",
                        List.of(
                                permission(CubismFacadeImpl.MODEL_READ_PERMISSION),
                                permission(CubismFacadeImpl.MODEL_WRITE_PERMISSION)),
                        ignored -> {},
                        FIXED_CLOCK),
                () -> backendModel,
                new ParameterLifecycleCoordinator(),
                new dev.turboism.adapter.cubism.lifecycle.PartLifecycleCoordinator(),
                lifecycle,
                () -> true);
        final List<Deformer> generic = facade.model().active().deformers().all();
        final WarpDeformer warp = assertInstanceOf(WarpDeformer.class, generic.get(0));
        final RotationDeformer rotation = assertInstanceOf(
                RotationDeformer.class, facade.model().active().deformers().find(backendRotation.id()));
        final WarpGrid replacementGrid = new WarpGrid(
                1, 1, true, List.of(new Point2(0, 0), new Point2(2, 0), new Point2(0, 2), new Point2(2, 2)));
        final RotationDeformerForm replacementForm = new RotationDeformerForm(1, 2, 3, 1.25F, true, false);

        warp.setOpacity(0.5F);
        warp.setVisible(false);
        warp.setLocked(true);
        warp.replaceGrid(replacementGrid);
        rotation.setOpacity(0.6F);
        rotation.setVisible(false);
        rotation.setLocked(true);
        rotation.setBaseAngle(15.0F);
        rotation.replaceForm(replacementForm);
        lifecycle.deformer().awaitIdle();

        assertEquals(0.5F, backendWarp.opacity);
        assertFalse(backendWarp.visible);
        assertTrue(backendWarp.locked);
        assertEquals(replacementGrid, backendWarp.grid);
        assertEquals(0.6F, backendRotation.opacity);
        assertEquals(15.0F, backendRotation.baseAngle);
        assertEquals(replacementForm, backendRotation.form);
        assertFalse(backendRotation.visible);
        assertTrue(backendRotation.locked);
        assertEquals(1, counts.get("WarpA:opacity:before"));
        assertEquals(1, counts.get("WarpA:opacity:on"));
        assertEquals(1, counts.get("WarpA:opacity:after"));
        assertEquals(1, counts.get("WarpA:visible:before"));
        assertEquals(1, counts.get("WarpA:visible:on"));
        assertEquals(1, counts.get("WarpA:visible:after"));
        assertEquals(1, counts.get("WarpA:locked:before"));
        assertEquals(1, counts.get("WarpA:locked:on"));
        assertEquals(1, counts.get("WarpA:locked:after"));
        assertEquals(1, counts.get("WarpA:grid:before"));
        assertEquals(1, counts.get("WarpA:grid:on"));
        assertEquals(1, counts.get("WarpA:grid:after"));
        assertEquals(1, counts.get("RotationA:opacity:before"));
        assertEquals(1, counts.get("RotationA:opacity:on"));
        assertEquals(1, counts.get("RotationA:opacity:after"));
        assertEquals(1, counts.get("RotationA:visible:before"));
        assertEquals(1, counts.get("RotationA:visible:on"));
        assertEquals(1, counts.get("RotationA:visible:after"));
        assertEquals(1, counts.get("RotationA:locked:before"));
        assertEquals(1, counts.get("RotationA:locked:on"));
        assertEquals(1, counts.get("RotationA:locked:after"));
        assertEquals(1, counts.get("RotationA:angle:before"));
        assertEquals(1, counts.get("RotationA:angle:on"));
        assertEquals(1, counts.get("RotationA:angle:after"));
        assertEquals(1, counts.get("RotationA:form:before"));
        assertEquals(1, counts.get("RotationA:form:on"));
        assertEquals(1, counts.get("RotationA:form:after"));
    }

    @Test
    void animationMutationsRequireModelWriteAndNeverReachTheBackend() {
        final List<String> calls = new ArrayList<>();
        final CubismFacadeImpl facade = facadeWith(
                emptySource(),
                new ArrayList<>(),
                List.of(permission(CubismFacadeImpl.MODEL_READ_PERMISSION)),
                () -> animationGraphModel(calls));
        final CubismModel model = facade.model().active();
        final AnimationDocument document = model.animationDocuments().get(0);
        final AnimationScene scene = document.scenes().get(0);
        final AnimationTrack childTrack = scene.tracks().get(1).children().get(0);
        final AnimationAttribute attribute = childTrack.attributes().get(0);
        calls.clear();

        assertThrows(CubismPermissionException.class, () -> scene.rename("x"));
        assertThrows(CubismPermissionException.class, () -> scene.seekTo(0));
        assertThrows(CubismPermissionException.class, scene::activate);
        assertThrows(CubismPermissionException.class, () -> scene.setDefaultCurveType(AnimationCurveType.LINEAR));
        assertThrows(CubismPermissionException.class, () -> attribute.setKeyframe(5, 0.5));
        assertThrows(CubismPermissionException.class, () -> attribute.setKeyframe(5, 0.5, AnimationCurveType.BEZIER));
        assertThrows(CubismPermissionException.class, () -> attribute.setKeyframe(5, 1.0F, 2.0F));
        assertThrows(CubismPermissionException.class, () -> attribute.removeKeyframe(5));
        assertThrows(CubismPermissionException.class, () -> attribute.offsetKeyframes(1));
        assertThrows(CubismPermissionException.class, () -> attribute.scaleKeyframeTimes(2.0, 0));
        assertThrows(CubismPermissionException.class, () -> attribute.quantizeKeyframes(5));
        assertThrows(CubismPermissionException.class, () -> attribute.copyKeyframesFrom(attribute, false));
        assertThrows(CubismPermissionException.class, () -> attribute.applyCurveType(AnimationCurveType.LINEAR));
        assertThrows(CubismPermissionException.class, () -> attribute.applyCurveType(AnimationCurveType.LINEAR, 0, 10));
        assertThrows(CubismPermissionException.class, () -> attribute.recordKeyframe(5, AnimationCurveType.LINEAR));
        assertThrows(
                CubismPermissionException.class, () -> attribute.bakeEvaluated(0, 60, 5, AnimationCurveType.LINEAR));
        assertEquals(List.of(), calls);
    }

    @Test
    void authorizedAnimationWritesReachBackendThroughDeepTraversal() {
        final List<String> calls = new ArrayList<>();
        final CubismFacadeImpl facade = facadeWith(
                emptySource(),
                new ArrayList<>(),
                List.of(
                        permission(CubismFacadeImpl.MODEL_READ_PERMISSION),
                        permission(CubismFacadeImpl.MODEL_WRITE_PERMISSION)),
                () -> animationGraphModel(calls));
        final CubismModel model = facade.model().active();
        final AnimationDocument document = model.animationDocuments().get(0);
        final AnimationScene scene = document.scenes().get(0);
        final AnimationTrack modelTrack = scene.tracks().get(0);
        final AnimationTrack childTrack = scene.tracks().get(1).children().get(0);
        final AnimationAttribute attribute = childTrack.attributes().get(0);
        final AnimationAttribute source = modelTrack.attributes().get(0);

        assertEquals("AnimDoc", document.animationName());
        assertEquals("SceneA", scene.name());
        assertEquals(7, scene.playheadFrame());
        assertEquals("attr-leaf", attribute.id());
        attribute.setKeyframe(5, 0.5);
        scene.rename("Renamed");
        assertEquals(0, attribute.copyKeyframesFrom(source, false));
        assertEquals(
                List.of(
                        "model.animationDocuments",
                        "doc.scenes",
                        "scene.tracks",
                        "scene.tracks",
                        "track.children",
                        "track.attributes",
                        "track.attributes",
                        "doc.animationName",
                        "scene.name",
                        "scene.playheadFrame",
                        "attr.id",
                        "attr.setKeyframe",
                        "scene.rename",
                        "attr.copyKeyframesFrom"),
                calls);
    }

    @Test
    void retainedAnimationReferencesFailClosedAfterPluginScopeCloses() {
        final AtomicBoolean active = new AtomicBoolean(true);
        final List<String> calls = new ArrayList<>();
        final CubismFacadeImpl facade = new CubismFacadeImpl(
                emptySource(),
                new CubismPermissionGate(
                        "plugin.demo",
                        List.of(
                                permission(CubismFacadeImpl.MODEL_READ_PERMISSION),
                                permission(CubismFacadeImpl.MODEL_WRITE_PERMISSION)),
                        ignored -> {},
                        FIXED_CLOCK),
                new ImmutableSnapshotFactory(),
                () -> animationGraphModel(calls),
                new ParameterLifecycleCoordinator(),
                new dev.turboism.adapter.cubism.lifecycle.PartLifecycleCoordinator(),
                new dev.turboism.adapter.cubism.lifecycle.EditorObjectLifecycleCoordinator(),
                active::get);
        final CubismModel model = facade.model().active();
        final List<AnimationDocument> documents = model.animationDocuments();
        final AnimationDocument document = documents.get(0);
        final AnimationScene scene = document.scenes().get(0);
        final AnimationTrack modelTrack = scene.tracks().get(0);
        final AnimationTrack groupTrack = scene.tracks().get(1);
        final AnimationTrack childTrack = groupTrack.children().get(0);
        final AnimationAttribute attribute = childTrack.attributes().get(0);
        calls.clear();

        active.set(false);

        assertThrows(IllegalStateException.class, model::animationDocuments);
        assertThrows(IllegalStateException.class, document::animationName);
        assertThrows(IllegalStateException.class, document::sceneCount);
        assertThrows(IllegalStateException.class, document::currentSceneName);
        assertThrows(IllegalStateException.class, document::sceneNames);
        assertThrows(IllegalStateException.class, document::scenes);
        assertThrows(IllegalStateException.class, scene::name);
        assertThrows(IllegalStateException.class, scene::markers);
        assertThrows(IllegalStateException.class, scene::playheadFrame);
        assertThrows(IllegalStateException.class, scene::tracks);
        assertThrows(IllegalStateException.class, () -> scene.rename("x"));
        assertThrows(IllegalStateException.class, () -> scene.seekTo(1));
        assertThrows(IllegalStateException.class, modelTrack::name);
        assertThrows(IllegalStateException.class, modelTrack::attributes);
        assertThrows(IllegalStateException.class, groupTrack::children);
        assertThrows(IllegalStateException.class, childTrack::attributes);
        assertThrows(IllegalStateException.class, attribute::id);
        assertThrows(IllegalStateException.class, attribute::parameterId);
        assertThrows(IllegalStateException.class, attribute::keyframes);
        assertThrows(IllegalStateException.class, () -> attribute.setKeyframe(1, 0.0));
        assertThrows(IllegalStateException.class, () -> attribute.copyKeyframesFrom(attribute, false));
        assertEquals(List.of(), calls);
    }

    @Test
    void animationCopyKeyframesFromValidatesSourceOwnership() {
        final List<String> calls = new ArrayList<>();
        final CubismFacadeImpl facade = facadeWith(
                emptySource(),
                new ArrayList<>(),
                List.of(
                        permission(CubismFacadeImpl.MODEL_READ_PERMISSION),
                        permission(CubismFacadeImpl.MODEL_WRITE_PERMISSION)),
                () -> animationGraphModel(calls));
        final CubismModel model = facade.model().active();
        final AnimationAttribute target = deepAnimationAttribute(model);
        final AnimationAttribute legal = model.animationDocuments()
                .get(0)
                .scenes()
                .get(0)
                .tracks()
                .get(0)
                .attributes()
                .get(0);
        final AnimationAttribute foreign = foreignAnimationAttribute("foreign");
        final CubismFacadeImpl otherFacade = facadeWith(
                emptySource(),
                new ArrayList<>(),
                List.of(
                        permission(CubismFacadeImpl.MODEL_READ_PERMISSION),
                        permission(CubismFacadeImpl.MODEL_WRITE_PERMISSION)),
                () -> animationGraphModel(new ArrayList<>()));
        final AnimationAttribute otherOwner =
                deepAnimationAttribute(otherFacade.model().active());
        final AnimationAttribute separateView =
                deepAnimationAttribute(facade.model().active());
        calls.clear();

        // A raw attribute that never passed through this facade is foreign.
        assertThrows(IllegalArgumentException.class, () -> target.copyKeyframesFrom(foreign, false));

        // An attribute wrapped by a different facade instance is foreign.
        assertThrows(IllegalArgumentException.class, () -> target.copyKeyframesFrom(otherOwner, false));

        assertThrows(NullPointerException.class, () -> target.copyKeyframesFrom(null, false));
        assertEquals(List.of(), calls);

        assertEquals(0, target.copyKeyframesFrom(legal, false));

        // A source obtained through a second active() view of the same facade
        // over the unchanged backend shares the same authorization ownership
        // and session generation; it must stay legal.
        assertEquals(0, target.copyKeyframesFrom(separateView, false));
        assertEquals(List.of("attr.copyKeyframesFrom", "attr.copyKeyframesFrom"), calls);
    }

    private CubismFacadeImpl facadeWith(
            final HostSnapshotSource source,
            final List<CubismFacadeAuditEvent> auditEvents,
            final List<PluginPermission> permissions) {
        return new CubismFacadeImpl(
                source, new CubismPermissionGate("plugin.demo", permissions, auditEvents::add, FIXED_CLOCK));
    }

    private CubismFacadeImpl facadeWith(
            final HostSnapshotSource source,
            final List<CubismFacadeAuditEvent> auditEvents,
            final List<PluginPermission> permissions,
            final CubismModelAccess modelAccess) {
        return new CubismFacadeImpl(
                source,
                new CubismPermissionGate("plugin.demo", permissions, auditEvents::add, FIXED_CLOCK),
                modelAccess);
    }
}

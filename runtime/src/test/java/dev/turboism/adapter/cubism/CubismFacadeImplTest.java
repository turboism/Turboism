package dev.turboism.adapter.cubism;

import dev.turboism.diagnostics.CubismFacadeAuditEvent;
import dev.turboism.adapter.cubism.lifecycle.ParameterLifecycleCoordinator;
import dev.turboism.permissions.CubismPermissionGate;
import dev.turboism.sdk.cubism.CubismRuntimeSnapshot;
import dev.turboism.sdk.cubism.clipmask.ClipMaskReplacement;
import dev.turboism.sdk.cubism.DeformerType;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.id.ParameterGroupId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.Drawables;
import dev.turboism.sdk.cubism.model.Deformers;
import dev.turboism.sdk.cubism.model.Deformer;
import dev.turboism.sdk.cubism.model.Glues;
import dev.turboism.sdk.cubism.model.IntSequence;
import dev.turboism.sdk.cubism.model.Parameters;
import dev.turboism.sdk.cubism.model.Parts;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.cubism.model.RotationDeformer;
import dev.turboism.sdk.cubism.model.RotationDeformerForm;
import dev.turboism.sdk.cubism.model.RotationDeformers;
import dev.turboism.sdk.cubism.model.WarpDeformer;
import dev.turboism.sdk.cubism.model.WarpDeformers;
import dev.turboism.sdk.cubism.model.WarpGrid;
import dev.turboism.sdk.cubism.model.Color;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.sdk.cubism.model.ParameterDefinition;
import dev.turboism.sdk.cubism.model.ParameterGroup;
import dev.turboism.sdk.cubism.model.ParameterGroups;
import dev.turboism.sdk.cubism.model.ParameterType;
import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.permission.PluginPermission;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import dev.turboism.sdk.cubism.CubismPlugin;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CubismFacadeImplTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-07-07T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void grantedPermissionsReturnSnapshots() {
        final List<CubismFacadeAuditEvent> auditEvents = new ArrayList<>();
        final CubismFacadeImpl facade = facadeWith(sampleSource(), auditEvents, List.of(
            permission(CubismFacadeImpl.PROJECT_READ_PERMISSION),
            permission(CubismFacadeImpl.MODEL_READ_PERMISSION)
        ));

        final CubismRuntimeSnapshot runtime = facade.runtime();

        assertEquals("project-1", facade.activeProject().orElseThrow().projectId());
        assertEquals("document-1", facade.activeDocument().orElseThrow().documentId());
        assertEquals("model-1", facade.activeModel().orElseThrow().modelId());
        assertEquals("project-1", runtime.project().orElseThrow().projectId());
        assertEquals("model-1", runtime.model().orElseThrow().modelId());
        assertEquals(3, runtime.modelObjects().size());
        assertTrue(auditEvents.isEmpty());
    }

    @Test
    void deniedProjectPermissionThrowsAndRecordsAuditEvent() {
        final List<CubismFacadeAuditEvent> auditEvents = new ArrayList<>();
        final CubismFacadeImpl facade = facadeWith(sampleSource(), auditEvents, List.of(
            permission(CubismFacadeImpl.MODEL_READ_PERMISSION)
        ));

        final CubismPermissionException error = assertThrows(
            CubismPermissionException.class,
            facade::activeProject
        );

        assertTrue(error.getMessage().contains(CubismFacadeImpl.PROJECT_READ_PERMISSION));
        assertEquals(1, auditEvents.size());
        assertEquals("plugin.demo", auditEvents.get(0).pluginId());
        assertEquals(CubismFacadeImpl.PROJECT_READ_PERMISSION, auditEvents.get(0).permissionId());
        assertEquals("activeProject", auditEvents.get(0).methodName());
        assertEquals(FIXED_CLOCK.instant(), auditEvents.get(0).timestamp());
    }

    @Test
    void runtimeRedactsProjectWhenOnlyProjectPermissionIsDenied() {
        final List<CubismFacadeAuditEvent> auditEvents = new ArrayList<>();
        final CubismFacadeImpl facade = facadeWith(sampleSource(), auditEvents, List.of(
            permission(CubismFacadeImpl.MODEL_READ_PERMISSION)
        ));

        final CubismRuntimeSnapshot runtime = facade.runtime();

        assertTrue(runtime.project().isEmpty());
        assertEquals("document-1", runtime.document().orElseThrow().documentId());
        assertEquals("model-1", runtime.model().orElseThrow().modelId());
        assertEquals("runtime", auditEvents.get(0).methodName());
        assertEquals(CubismFacadeImpl.PROJECT_READ_PERMISSION, auditEvents.get(0).permissionId());
    }

    @Test
    void deniedModelPermissionThrowsAndRecordsAuditEvent() {
        final List<CubismFacadeAuditEvent> auditEvents = new ArrayList<>();
        final CubismFacadeImpl facade = facadeWith(sampleSource(), auditEvents, List.of(
            permission(CubismFacadeImpl.PROJECT_READ_PERMISSION)
        ));

        final CubismPermissionException error = assertThrows(
            CubismPermissionException.class,
            facade::activeModel
        );

        assertTrue(error.getMessage().contains(CubismFacadeImpl.MODEL_READ_PERMISSION));
        assertEquals(1, auditEvents.size());
        assertEquals(CubismFacadeImpl.MODEL_READ_PERMISSION, auditEvents.get(0).permissionId());
        assertEquals("activeModel", auditEvents.get(0).methodName());
    }

    @Test
    void noActiveHostReturnsEmptyOptionalsAndEmptyRuntime() {
        final List<CubismFacadeAuditEvent> auditEvents = new ArrayList<>();
        final CubismFacadeImpl facade = facadeWith(emptySource(), auditEvents, List.of(
            permission(CubismFacadeImpl.PROJECT_READ_PERMISSION),
            permission(CubismFacadeImpl.MODEL_READ_PERMISSION)
        ));

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
                @Override public dev.turboism.sdk.cubism.core.CoreVersion version() {
                    calls[0]++;
                    return new dev.turboism.sdk.cubism.core.CoreVersion(5, 3, 2);
                }
                @Override public dev.turboism.sdk.cubism.core.CoreCapabilities capabilities() {
                    calls[1]++;
                    return new dev.turboism.sdk.cubism.core.CoreCapabilities(true, true, true);
                }
                @Override public dev.turboism.sdk.cubism.core.MocInspector mocInspector() {
                    calls[2]++;
                    return new dev.turboism.sdk.cubism.core.MocInspector() {
                        @Override public dev.turboism.sdk.cubism.core.MocVersion latestVersion() {
                            return dev.turboism.sdk.cubism.core.MocVersion.V5_3;
                        }
                        @Override public dev.turboism.sdk.cubism.core.MocInfo inspect(
                            final dev.turboism.sdk.cubism.core.MocData data
                        ) {
                            return new dev.turboism.sdk.cubism.core.MocInfo(
                                dev.turboism.sdk.cubism.core.MocVersion.V5_3,
                                dev.turboism.sdk.cubism.core.MocConsistency.CONSISTENT
                            );
                        }
                    };
                }
            };
        final CubismFacadeImpl facade = new CubismFacadeImpl(
            emptySource(),
            new CubismPermissionGate(
                "plugin.demo",
                List.of(permission(CubismFacadeImpl.MODEL_READ_PERMISSION)),
                ignored -> { },
                FIXED_CLOCK
            ),
            new ImmutableSnapshotFactory(),
            (context, document) -> { throw new UnsupportedOperationException(); },
            () -> emptyModel("core-model"),
            backend,
            new ParameterLifecycleCoordinator(),
            new dev.turboism.adapter.cubism.lifecycle.PartLifecycleCoordinator(),
            new dev.turboism.adapter.cubism.lifecycle.EditorObjectLifecycleCoordinator(),
            active::get
        );
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
    void retainedModelServicesRecheckPluginScopeBeforeBackendCalls() {
        final AtomicBoolean active = new AtomicBoolean(true);
        final int[] activeCalls = {0};
        final int[] collectionCalls = {0};
        final int[] parameterCalls = {0};
        final int[] canvasCalls = {0};
        final dev.turboism.sdk.cubism.model.Canvas backendCanvas =
            new dev.turboism.sdk.cubism.model.Canvas() {
                @Override public float widthPixels() { canvasCalls[0]++; return 100.0F; }
                @Override public float heightPixels() { return 100.0F; }
                @Override public float originXPixels() { return 0.0F; }
                @Override public float originYPixels() { return 0.0F; }
                @Override public float pixelsPerUnit() { return 100.0F; }
            };
        final dev.turboism.sdk.cubism.model.Parameter backendParameter =
            new dev.turboism.sdk.cubism.model.Parameter() {
                @Override public ParameterId id() { return new ParameterId("ParamA"); }
                @Override public float getValue() { parameterCalls[0]++; return 0.0F; }
                @Override public float getMinimumValue() { return -1.0F; }
                @Override public float getMaximumValue() { return 1.0F; }
                @Override public float getDefaultValue() { return 0.0F; }
                @Override public void setValue(final float value) { parameterCalls[0]++; }
            };
        final Parameters backendParameters = new Parameters() {
            @Override public List<dev.turboism.sdk.cubism.model.Parameter> all() {
                collectionCalls[0]++;
                return List.of(backendParameter);
            }
            @Override public dev.turboism.sdk.cubism.model.Parameter find(final ParameterId id) {
                collectionCalls[0]++;
                throw new java.util.NoSuchElementException();
            }
        };
        final CubismModel backendModel = new CubismModel() {
            @Override public ModelId id() { return new ModelId("model-a"); }
            @Override public Parameters parameters() { return backendParameters; }
            @Override public dev.turboism.sdk.cubism.model.Canvas canvas() { return backendCanvas; }
            @Override public Parts parts() { throw new UnsupportedOperationException(); }
            @Override public Drawables drawables() { throw new UnsupportedOperationException(); }
            @Override public Deformers deformers() { throw new UnsupportedOperationException(); }
            @Override public Glues glues() { throw new UnsupportedOperationException(); }
            @Override public void update() { throw new UnsupportedOperationException(); }
        };
        final CubismFacadeImpl facade = new CubismFacadeImpl(
            emptySource(),
            new CubismPermissionGate(
                "plugin.demo",
                List.of(permission(CubismFacadeImpl.MODEL_READ_PERMISSION)),
                ignored -> { },
                FIXED_CLOCK
            ),
            new ImmutableSnapshotFactory(),
            (context, document) -> { throw new UnsupportedOperationException(); },
            () -> {
                activeCalls[0]++;
                return backendModel;
            },
            new ParameterLifecycleCoordinator(),
            new dev.turboism.adapter.cubism.lifecycle.PartLifecycleCoordinator(),
            new dev.turboism.adapter.cubism.lifecycle.EditorObjectLifecycleCoordinator(),
            active::get
        );
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
                final dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutPlan plan
            ) {
                calls[1]++;
                throw new UnsupportedOperationException();
            }
        };
        final var session = new dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasEditorSession(
            () -> {
                calls[2]++;
                return null;
            },
            () -> null
        );
        final var ui = new dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasEditorUi();
        final var algorithms = new dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasLayoutAlgorithmRegistry();
        final CubismFacadeImpl facade = new CubismFacadeImpl(
            emptySource(),
            new CubismPermissionGate("plugin.demo", List.of(), ignored -> { }, FIXED_CLOCK),
            new ImmutableSnapshotFactory(),
            (context, document) -> { throw new UnsupportedOperationException(); },
            () -> emptyModel("texture-model"),
            new ParameterLifecycleCoordinator(),
            new dev.turboism.adapter.cubism.lifecycle.PartLifecycleCoordinator(),
            layout,
            new dev.turboism.adapter.cubism.lifecycle.EditorObjectLifecycleCoordinator(),
            active::get,
            ui,
            session,
            algorithms
        );
        final var retainedLayouts = facade.textureAtlasLayouts();
        final var retainedSession = facade.textureAtlasEditorSession();
        final var retainedUi = facade.textureAtlasEditorUi();
        final var retainedPanel = retainedUi.attach();
        final var retainedAlgorithms = facade.textureAtlasAlgorithms();
        final var algorithm = new dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutAlgorithm(
            "test", "Test", false, null
        );
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
        final dev.turboism.sdk.cubism.model.Part backendPart =
            new dev.turboism.sdk.cubism.model.Part() {
                @Override public dev.turboism.sdk.cubism.model.PartId id() {
                    return new dev.turboism.sdk.cubism.model.PartId("PartA");
                }
                @Override public void setName(final String name) { }
                @Override public float getOpacity() { return 1.0F; }
                @Override public int parentIndex() { return -1; }
                @Override public void setOpacity(final float opacity) { }
            };
        final CubismModel backendModel = new CubismModel() {
            @Override public ModelId id() { return new ModelId("model-a"); }
            @Override public Parameters parameters() { throw new UnsupportedOperationException(); }
            @Override public Parts parts() {
                return new Parts() {
                    @Override public List<dev.turboism.sdk.cubism.model.Part> all() {
                        return List.of(backendPart);
                    }
                    @Override public dev.turboism.sdk.cubism.model.Part find(
                        final dev.turboism.sdk.cubism.model.PartId id
                    ) {
                        return backendPart;
                    }
                };
            }
            @Override public Drawables drawables() { throw new UnsupportedOperationException(); }
            @Override public Deformers deformers() { throw new UnsupportedOperationException(); }
            @Override public Glues glues() { throw new UnsupportedOperationException(); }
            @Override public void update() { throw new UnsupportedOperationException(); }
        };
        final CubismFacadeImpl facade = new CubismFacadeImpl(
            emptySource(),
            new CubismPermissionGate(
                "plugin.demo",
                List.of(
                    permission(CubismFacadeImpl.MODEL_READ_PERMISSION),
                    permission(CubismFacadeImpl.MODEL_WRITE_PERMISSION)
                ),
                ignored -> { },
                FIXED_CLOCK
            ),
            () -> backendModel
        );
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
                FIXED_CLOCK
            ),
            expected
        );

        assertEquals(new ModelId("core-model"), facade.model().active().id());
        assertTrue(auditEvents.isEmpty());
    }

    @Test
    void clipMaskReplacementRequiresModelWritePermissionBeforeDelegation() {
        final List<List<ClipMaskReplacement>> calls = new ArrayList<>();
        final CubismModelAccess backend = () -> emptyModel("model-a", calls::add);
        final ClipMaskReplacement replacement = new ClipMaskReplacement(
            new ArtMeshId("target"), List.of(), false, List.of(new ArtMeshId("mask")), false
        );
        final CubismFacadeImpl denied = new CubismFacadeImpl(
            sampleSource(),
            new CubismPermissionGate(
                "plugin.demo",
                List.of(permission(CubismFacadeImpl.MODEL_READ_PERMISSION)),
                ignored -> { },
                FIXED_CLOCK
            ),
            backend
        );

        assertThrows(
            CubismPermissionException.class,
            () -> denied.model().active().replaceArtMeshClipMasks(List.of(replacement))
        );
        assertEquals(List.of(), calls);

        final CubismFacadeImpl allowed = new CubismFacadeImpl(
            sampleSource(),
            new CubismPermissionGate(
                "plugin.demo",
                List.of(
                    permission(CubismFacadeImpl.MODEL_READ_PERMISSION),
                    permission(CubismFacadeImpl.MODEL_WRITE_PERMISSION)
                ),
                ignored -> { },
                FIXED_CLOCK
            ),
            backend
        );
        allowed.model().active().replaceArtMeshClipMasks(List.of(replacement));
        assertEquals(List.of(List.of(replacement)), calls);
    }

    @Test
    void modelPermissionDenialDoesNotInvokeRuntimeAccess() {
        final List<CubismFacadeAuditEvent> auditEvents = new ArrayList<>();
        final boolean[] invoked = {false};
        final CubismFacadeImpl facade = new CubismFacadeImpl(
            sampleSource(),
            new CubismPermissionGate(
                "plugin.demo",
                List.of(),
                auditEvents::add,
                FIXED_CLOCK
            ),
            () -> {
                invoked[0] = true;
                return emptyModel("unexpected");
            }
        );

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
                ignored -> { },
                FIXED_CLOCK
            ),
            () -> modelWithParameter(new float[]{1.0F})
        );

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

    @Test
    void defaultKeyformLockWritesRequireModelWritePermission() {
        final List<CubismFacadeAuditEvent> auditEvents = new ArrayList<>();
        final List<Boolean> writes = new ArrayList<>();
        final CubismModelAccess backend = () -> new CubismModel() {
            @Override public ModelId id() { return new ModelId("model-a"); }
            @Override public boolean defaultKeyformLocked() { return false; }
            @Override public void setDefaultKeyformLocked(final boolean locked) {
                writes.add(locked);
            }
            @Override public dev.turboism.sdk.cubism.model.Parameters parameters() {
                throw new UnsupportedOperationException();
            }
            @Override public dev.turboism.sdk.cubism.model.Parts parts() {
                throw new UnsupportedOperationException();
            }
            @Override public dev.turboism.sdk.cubism.model.Drawables drawables() {
                throw new UnsupportedOperationException();
            }
            @Override public dev.turboism.sdk.cubism.model.Deformers deformers() {
                throw new UnsupportedOperationException();
            }
            @Override public dev.turboism.sdk.cubism.model.Glues glues() {
                throw new UnsupportedOperationException();
            }
            @Override public void update() { throw new UnsupportedOperationException(); }
        };
        final CubismFacadeImpl denied = new CubismFacadeImpl(
            sampleSource(),
            new CubismPermissionGate(
                "plugin.demo",
                List.of(permission(CubismFacadeImpl.MODEL_READ_PERMISSION)),
                auditEvents::add,
                FIXED_CLOCK
            ),
            backend
        );

        assertThrows(
            CubismPermissionException.class,
            () -> denied.model().active().setDefaultKeyformLocked(true)
        );
        assertEquals(List.of(), writes);
        assertEquals("model.setDefaultKeyformLocked", auditEvents.get(0).operationId());

        final CubismFacadeImpl allowed = new CubismFacadeImpl(
            sampleSource(),
            new CubismPermissionGate(
                "plugin.demo",
                List.of(
                    permission(CubismFacadeImpl.MODEL_READ_PERMISSION),
                    permission(CubismFacadeImpl.MODEL_WRITE_PERMISSION)
                ),
                ignored -> { },
                FIXED_CLOCK
            ),
            backend
        );
        allowed.model().active().setDefaultKeyformLocked(true);
        assertEquals(List.of(true), writes);
    }

    @Test
    void modelEditLevelWritesRequireModelWritePermission() {
        final List<CubismFacadeAuditEvent> auditEvents = new ArrayList<>();
        final List<dev.turboism.sdk.cubism.model.ModelEditLevel> writes = new ArrayList<>();
        final CubismModelAccess backend = () -> new CubismModel() {
            @Override public ModelId id() { return new ModelId("model-a"); }
            @Override public dev.turboism.sdk.cubism.model.ModelEditLevel editLevel() {
                return dev.turboism.sdk.cubism.model.ModelEditLevel.LEVEL_1;
            }
            @Override public void setEditLevel(
                final dev.turboism.sdk.cubism.model.ModelEditLevel level
            ) { writes.add(level); }
            @Override public dev.turboism.sdk.cubism.model.Parameters parameters() { throw new UnsupportedOperationException(); }
            @Override public dev.turboism.sdk.cubism.model.Parts parts() { throw new UnsupportedOperationException(); }
            @Override public dev.turboism.sdk.cubism.model.Drawables drawables() { throw new UnsupportedOperationException(); }
            @Override public dev.turboism.sdk.cubism.model.Deformers deformers() { throw new UnsupportedOperationException(); }
            @Override public dev.turboism.sdk.cubism.model.Glues glues() { throw new UnsupportedOperationException(); }
            @Override public void update() { throw new UnsupportedOperationException(); }
        };
        final CubismFacadeImpl denied = new CubismFacadeImpl(
            sampleSource(),
            new CubismPermissionGate(
                "plugin.demo",
                List.of(permission(CubismFacadeImpl.MODEL_READ_PERMISSION)),
                auditEvents::add,
                FIXED_CLOCK
            ),
            backend
        );

        assertThrows(
            CubismPermissionException.class,
            () -> denied.model().active().setEditLevel(
                dev.turboism.sdk.cubism.model.ModelEditLevel.LEVEL_2
            )
        );
        assertEquals(List.of(), writes);
        assertEquals("model.setEditLevel", auditEvents.get(0).operationId());

        final CubismFacadeImpl allowed = new CubismFacadeImpl(
            sampleSource(),
            new CubismPermissionGate(
                "plugin.demo",
                List.of(
                    permission(CubismFacadeImpl.MODEL_READ_PERMISSION),
                    permission(CubismFacadeImpl.MODEL_WRITE_PERMISSION)
                ),
                ignored -> { },
                FIXED_CLOCK
            ),
            backend
        );
        allowed.model().active().setEditLevel(
            dev.turboism.sdk.cubism.model.ModelEditLevel.LEVEL_2
        );
        assertEquals(List.of(dev.turboism.sdk.cubism.model.ModelEditLevel.LEVEL_2), writes);
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
                FIXED_CLOCK
            ),
            backend
        );
        final var deniedParameter = denied.model().active().parameters()
            .find(new ParameterId("ParamA"));

        assertThrows(
            CubismPermissionException.class,
            () -> deniedParameter.combineWith(new ParameterId("ParamB"))
        );
        assertThrows(CubismPermissionException.class, deniedParameter::uncombine);
        assertEquals(List.of(), calls);
        assertEquals(
            List.of("parameter.combineWith", "parameter.uncombine"),
            auditEvents.stream().map(CubismFacadeAuditEvent::methodName).toList()
        );
        assertEquals(
            List.of(
                CubismFacadeImpl.MODEL_WRITE_PERMISSION,
                CubismFacadeImpl.MODEL_WRITE_PERMISSION
            ),
            auditEvents.stream().map(CubismFacadeAuditEvent::permissionId).toList()
        );
        assertEquals(
            List.of("parameter.combineWith", "parameter.uncombine"),
            auditEvents.stream().map(CubismFacadeAuditEvent::operationId).toList()
        );

        final CubismFacadeImpl allowed = new CubismFacadeImpl(
            sampleSource(),
            new CubismPermissionGate(
                "plugin.demo",
                List.of(
                    permission(CubismFacadeImpl.MODEL_READ_PERMISSION),
                    permission(CubismFacadeImpl.MODEL_WRITE_PERMISSION)
                ),
                ignored -> { },
                FIXED_CLOCK
            ),
            backend
        );
        final var allowedParameter = allowed.model().active().parameters()
            .find(new ParameterId("ParamA"));
        allowedParameter.combineWith(new ParameterId("ParamB"));
        allowedParameter.uncombine();
        assertEquals(List.of("combine:ParamB", "uncombine"), calls);
    }

    @Test
    void parameterSetterRequiresModelWritePermissionBeforeInvokingBackend() {
        final List<CubismFacadeAuditEvent> auditEvents = new ArrayList<>();
        final float[] value = {1.0F};
        final CubismModelAccess backend = () -> modelWithParameter(value);
        final CubismFacadeImpl denied = new CubismFacadeImpl(
            sampleSource(),
            new CubismPermissionGate(
                "plugin.demo",
                List.of(permission(CubismFacadeImpl.MODEL_READ_PERMISSION)),
                auditEvents::add,
                FIXED_CLOCK
            ),
            backend
        );
        final dev.turboism.sdk.cubism.model.Parameter deniedParameter = denied.model()
            .active()
            .parameters()
            .find(new ParameterId("ParamA"));

        assertThrows(CubismPermissionException.class, () -> deniedParameter.setValue(2.0F));
        assertEquals(1.0F, value[0]);
        assertEquals(CubismFacadeImpl.MODEL_WRITE_PERMISSION, auditEvents.get(0).permissionId());
        assertEquals("parameter.setValue", auditEvents.get(0).operationId());

        final CubismFacadeImpl allowed = new CubismFacadeImpl(
            sampleSource(),
            new CubismPermissionGate(
                "plugin.demo",
                List.of(
                    permission(CubismFacadeImpl.MODEL_READ_PERMISSION),
                    permission(CubismFacadeImpl.MODEL_WRITE_PERMISSION)
                ),
                auditEvents::add,
                FIXED_CLOCK
            ),
            backend
        );
        allowed.model().active().parameters().find(new ParameterId("ParamA")).setValue(3.0F);
        assertEquals(3.0F, value[0]);
    }

    @Test
    void parameterResetRequiresModelWritePermissionBeforeInvokingBackend() {
        final List<CubismFacadeAuditEvent> auditEvents = new ArrayList<>();
        final float[] value = {1.0F};
        final CubismModelAccess backend = () -> modelWithParameter(value);
        final CubismFacadeImpl denied = new CubismFacadeImpl(
            sampleSource(),
            new CubismPermissionGate(
                "plugin.demo",
                List.of(permission(CubismFacadeImpl.MODEL_READ_PERMISSION)),
                auditEvents::add,
                FIXED_CLOCK
            ),
            backend
        );
        final var deniedParameter = denied.model().active().parameters()
            .find(new ParameterId("ParamA"));

        assertThrows(CubismPermissionException.class, deniedParameter::resetToDefault);
        assertEquals(1.0F, value[0]);
        assertEquals(CubismFacadeImpl.MODEL_WRITE_PERMISSION, auditEvents.get(0).permissionId());
        assertEquals("parameter.resetToDefault", auditEvents.get(0).operationId());

        final CubismFacadeImpl allowed = new CubismFacadeImpl(
            sampleSource(),
            new CubismPermissionGate(
                "plugin.demo",
                List.of(
                    permission(CubismFacadeImpl.MODEL_READ_PERMISSION),
                    permission(CubismFacadeImpl.MODEL_WRITE_PERMISSION)
                ),
                auditEvents::add,
                FIXED_CLOCK
            ),
            backend
        );
        allowed.model().active().parameters().find(new ParameterId("ParamA"))
            .resetToDefault();
        assertEquals(0.0F, value[0]);
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
                FIXED_CLOCK
            ),
            backend
        );
        final ParameterDefinition definition = new ParameterDefinition(
            new ParameterId("ParamRenamed"),
            "Renamed",
            -2.0F,
            0.0F,
            2.0F,
            ParameterType.NORMAL,
            true
        );

        assertThrows(CubismPermissionException.class, () -> denied.model().active()
            .parameters().find(new ParameterId("ParamA")).updateDefinition(definition));
        assertEquals(null, updated[0]);
        assertEquals(CubismFacadeImpl.MODEL_WRITE_PERMISSION, auditEvents.get(0).permissionId());
        assertEquals("parameter.updateDefinition", auditEvents.get(0).operationId());

        final CubismFacadeImpl allowed = new CubismFacadeImpl(
            sampleSource(),
            new CubismPermissionGate(
                "plugin.demo",
                List.of(
                    permission(CubismFacadeImpl.MODEL_READ_PERMISSION),
                    permission(CubismFacadeImpl.MODEL_WRITE_PERMISSION)
                ),
                auditEvents::add,
                FIXED_CLOCK
            ),
            backend
        );
        allowed.model().active().parameters().find(new ParameterId("ParamA"))
            .updateDefinition(definition);
        assertEquals(definition, updated[0]);
    }

    @Test
    void editorObjectWritesRequireModelWritePermissionBeforeBackendMutation() {
        final int[] mutations = {0};
        final Drawable drawable = new Drawable() {
            @Override public ArtMeshId id() { return new ArtMeshId("ArtMeshA"); }
            @Override public void setOpacity(final float opacity) { mutations[0]++; }
            @Override public float getOpacity() { return 1.0F; }
            @Override public ArtMeshGeometry geometry() {
                return new ArtMeshGeometry(
                    List.of(new Point2(0, 0), new Point2(1, 0), new Point2(0, 1)),
                    List.of(new Point2(0, 0), new Point2(1, 0), new Point2(0, 1)),
                    List.of(0, 1, 2)
                );
            }
            @Override public void replaceGeometry(final ArtMeshGeometry geometry) { mutations[0]++; }
            @Override public byte constantFlag() { return 0; }
            @Override public byte dynamicFlag() { return 0; }
            @Override public dev.turboism.sdk.cubism.model.BlendMode blendMode() {
                return dev.turboism.sdk.cubism.model.BlendMode.NORMAL;
            }
            @Override public int textureIndex() { return 0; }
            @Override public int drawOrder() { return 0; }
            @Override public int renderOrder() { return 0; }
            @Override public IntSequence masks() { return emptyInts(); }
            @Override public dev.turboism.sdk.cubism.model.FloatSequence vertexPositions() {
                return emptyFloats();
            }
            @Override public dev.turboism.sdk.cubism.model.FloatSequence vertexUvs() {
                return emptyFloats();
            }
            @Override public IntSequence indices() { return emptyInts(); }
            @Override public Color multiplyColor() { return new Color(1, 1, 1, 1); }
            @Override public Color screenColor() { return new Color(0, 0, 0, 1); }
            @Override public int parentPartIndex() { return -1; }
            @Override public int parentDeformerIndex() { return -1; }
            @Override public IntSequence parameters() { return emptyInts(); }
        };
        final WarpDeformer warp = new WarpDeformer() {
            @Override public DeformerId id() { return new DeformerId("WarpA"); }
            @Override public void setOpacity(final float opacity) { mutations[0]++; }
            @Override public void replaceGrid(final WarpGrid grid) { mutations[0]++; }
            @Override public int parentDeformerIndex() { return -1; }
            @Override public WarpGrid grid() {
                return new WarpGrid(1, 1, false, List.of(
                    new Point2(0, 0), new Point2(1, 0), new Point2(0, 1), new Point2(1, 1)
                ));
            }
            @Override public IntSequence parameters() { return emptyInts(); }
        };
        final RotationDeformer rotation = new RotationDeformer() {
            @Override public DeformerId id() { return new DeformerId("RotationA"); }
            @Override public float baseAngle() { return 0.0F; }
            @Override public void setBaseAngle(final float angle) { mutations[0]++; }
            @Override public RotationDeformerForm form() {
                return new RotationDeformerForm(0, 0, 0, 1, false, false);
            }
            @Override public void replaceForm(final RotationDeformerForm form) { mutations[0]++; }
            @Override public int parentDeformerIndex() { return -1; }
            @Override public IntSequence parameters() { return emptyInts(); }
        };
        final CubismModel model = editorObjectModel(drawable, warp, rotation);
        final CubismFacadeImpl denied = facadeWith(
            sampleSource(),
            new ArrayList<>(),
            List.of(permission(CubismFacadeImpl.MODEL_READ_PERMISSION)),
            () -> model
        );
        final ArtMeshGeometry geometry = new ArtMeshGeometry(
            List.of(new Point2(0, 0), new Point2(1, 0), new Point2(0, 1)),
            List.of(new Point2(0, 0), new Point2(1, 0), new Point2(0, 1)),
            List.of(0, 1, 2)
        );
        final WarpGrid grid = new WarpGrid(1, 1, false, List.of(
            new Point2(0, 0), new Point2(1, 0), new Point2(0, 1), new Point2(1, 1)
        ));
        final RotationDeformerForm form = new RotationDeformerForm(1, 2, 3, 1, false, false);

        assertThrows(CubismPermissionException.class,
            () -> denied.model().active().drawables().find(drawable.id()).setOpacity(0.5F));
        assertThrows(CubismPermissionException.class,
            () -> denied.model().active().drawables().find(drawable.id()).replaceGeometry(geometry));
        assertThrows(CubismPermissionException.class,
            () -> denied.model().active().warpDeformers().find(warp.id()).replaceGrid(grid));
        assertThrows(CubismPermissionException.class,
            () -> denied.model().active().rotationDeformers().find(rotation.id()).replaceForm(form));
        assertEquals(0, mutations[0]);

        final CubismFacadeImpl allowed = facadeWith(
            sampleSource(),
            new ArrayList<>(),
            List.of(
                permission(CubismFacadeImpl.MODEL_READ_PERMISSION),
                permission(CubismFacadeImpl.MODEL_WRITE_PERMISSION)
            ),
            () -> model
        );
        allowed.model().active().drawables().find(drawable.id()).setOpacity(0.5F);
        allowed.model().active().drawables().find(drawable.id()).replaceGeometry(geometry);
        allowed.model().active().warpDeformers().find(warp.id()).replaceGrid(grid);
        allowed.model().active().rotationDeformers().find(rotation.id()).replaceForm(form);
        assertEquals(4, mutations[0]);
    }

    @Test
    void legacyConstructorsKeepUnifiedModelAccessExplicitlyUnavailable() {
        final CubismFacadeImpl facade = facadeWith(
            sampleSource(),
            new ArrayList<>(),
            List.of(permission(CubismFacadeImpl.MODEL_READ_PERMISSION))
        );

        assertThrows(UnsupportedOperationException.class, () -> facade.model().active());
    }

    @Test
    void pluginScopeInvalidatesPreviouslyObtainedModelAndEditorObjectReferences() {
        final AtomicBoolean active = new AtomicBoolean(true);
        final Drawable drawable = new Drawable() {
            @Override public ArtMeshId id() { return new ArtMeshId("ArtMeshA"); }
            @Override public ArtMeshGeometry geometry() {
                return new ArtMeshGeometry(
                    List.of(new Point2(0, 0), new Point2(1, 0), new Point2(0, 1)),
                    List.of(new Point2(0, 0), new Point2(1, 0), new Point2(0, 1)),
                    List.of(0, 1, 2)
                );
            }
            @Override public byte constantFlag() { return 0; }
            @Override public byte dynamicFlag() { return 0; }
            @Override public dev.turboism.sdk.cubism.model.BlendMode blendMode() {
                return dev.turboism.sdk.cubism.model.BlendMode.NORMAL;
            }
            @Override public int textureIndex() { return 0; }
            @Override public int drawOrder() { return 0; }
            @Override public int renderOrder() { return 0; }
            @Override public float getOpacity() { return 1.0F; }
            @Override public IntSequence masks() { return emptyInts(); }
            @Override public dev.turboism.sdk.cubism.model.FloatSequence vertexPositions() { return emptyFloats(); }
            @Override public dev.turboism.sdk.cubism.model.FloatSequence vertexUvs() { return emptyFloats(); }
            @Override public IntSequence indices() { return emptyInts(); }
            @Override public Color multiplyColor() { return new Color(1, 1, 1, 1); }
            @Override public Color screenColor() { return new Color(0, 0, 0, 1); }
            @Override public int parentPartIndex() { return -1; }
            @Override public int parentDeformerIndex() { return -1; }
            @Override public IntSequence parameters() { return emptyInts(); }
            @Override public List<dev.turboism.sdk.cubism.model.ParameterBinding> getParameterBindings() { return List.of(); }
        };
        final WarpDeformer backendWarp = new WarpDeformer() {
            @Override public DeformerId id() { return new DeformerId("WarpA"); }
            @Override public WarpGrid grid() {
                return new WarpGrid(1, 1, false, List.of(
                    new Point2(0, 0), new Point2(1, 0), new Point2(0, 1), new Point2(1, 1)
                ));
            }
            @Override public void replaceGrid(final WarpGrid grid) { }
            @Override public int parentDeformerIndex() { return -1; }
            @Override public IntSequence parameters() { return emptyInts(); }
            @Override public List<dev.turboism.sdk.cubism.model.ParameterBinding> getParameterBindings() { return List.of(); }
        };
        final RotationDeformer backendRotation = new RotationDeformer() {
            @Override public DeformerId id() { return new DeformerId("RotationA"); }
            @Override public RotationDeformerForm form() {
                return new RotationDeformerForm(0, 0, 0, 1, false, false);
            }
            @Override public float baseAngle() { return 0.0F; }
            @Override public void setBaseAngle(final float angle) { }
            @Override public void replaceForm(final RotationDeformerForm form) { }
            @Override public int parentDeformerIndex() { return -1; }
            @Override public IntSequence parameters() { return emptyInts(); }
            @Override public List<dev.turboism.sdk.cubism.model.ParameterBinding> getParameterBindings() { return List.of(); }
        };
        final CubismFacadeImpl facade = new CubismFacadeImpl(
            sampleSource(),
            new CubismPermissionGate(
                "plugin.demo",
                List.of(
                    permission(CubismFacadeImpl.MODEL_READ_PERMISSION),
                    permission(CubismFacadeImpl.MODEL_WRITE_PERMISSION)
                ),
                ignored -> { },
                FIXED_CLOCK
            ),
            () -> editorObjectModel(drawable, backendWarp, backendRotation),
            new ParameterLifecycleCoordinator(),
            new dev.turboism.adapter.cubism.lifecycle.PartLifecycleCoordinator(),
            active::get
        );
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
                @Override public void beforeCubismOperation(
                    final dev.turboism.sdk.cubism.event.CubismOperationEvent event
                ) { semanticEvents.add("before:" + event.operation()); }
                @Override public void onCubismOperationConfirmed(
                    final dev.turboism.sdk.cubism.event.CubismOperationEvent event
                ) { semanticEvents.add("on:" + event.operation()); }
                @Override public void afterCubismOperation(
                    final dev.turboism.sdk.cubism.event.CubismOperationEvent event
                ) { semanticEvents.add("after:" + event.operation()); }
            }),
            logger()
        ));
        lifecycle.register(new ParameterLifecycleCoordinator.PluginHooks(
            descriptor("plugin.hooks"),
            List.of(new CubismPlugin() {
                @Override public float beforeSetParameterValue(
                    final dev.turboism.sdk.cubism.model.Parameter parameter,
                    final float requested
                ) {
                    return requested * 0.5F;
                }
                @Override public void onParameterValueChanged(
                    final dev.turboism.sdk.cubism.model.Parameter parameter,
                    final float oldValue,
                    final float newValue
                ) {
                    events.add("on:" + oldValue + "->" + newValue);
                }
                @Override public void afterSetParameterValue(
                    final dev.turboism.sdk.cubism.model.Parameter parameter,
                    final float finalValue
                ) {
                    events.add("after:" + finalValue);
                }
            }),
            logger()
        ));
        final CubismFacadeImpl facade = new CubismFacadeImpl(
            sampleSource(),
            new CubismPermissionGate(
                "plugin.caller",
                List.of(
                    permission(CubismFacadeImpl.MODEL_READ_PERMISSION),
                    permission(CubismFacadeImpl.MODEL_WRITE_PERMISSION)
                ),
                ignored -> { },
                FIXED_CLOCK
            ),
            () -> modelWithParameter(value),
            lifecycle,
            new dev.turboism.adapter.cubism.lifecycle.PartLifecycleCoordinator(),
            new dev.turboism.adapter.cubism.lifecycle.EditorObjectLifecycleCoordinator(
                new dev.turboism.adapter.cubism.lifecycle.DrawableLifecycleCoordinator(),
                new dev.turboism.adapter.cubism.lifecycle.DeformerLifecycleCoordinator(),
                semantic
            ),
            () -> true
        );

        facade.model().active().parameters().find(new ParameterId("ParamA")).setValue(10.0F);
        lifecycle.awaitIdle();
        semantic.awaitIdle();

        assertEquals(5.0F, value[0]);
        assertEquals(List.of("on:1.0->5.0", "after:5.0"), events);
        assertEquals(List.of(
            "before:SET_PARAMETER_VALUE",
            "on:SET_PARAMETER_VALUE",
            "after:SET_PARAMETER_VALUE"
        ), semanticEvents);
    }

    @Test
    void deniedEditorObjectWriteDoesNotEnterBeforeHooks() {
        final int[] mutations = {0};
        final List<String> events = new ArrayList<>();
        final float[] opacity = {1.0F};
        final Drawable drawable = new Drawable() {
            @Override public ArtMeshId id() { return new ArtMeshId("ArtMeshA"); }
            @Override public float getOpacity() { return opacity[0]; }
            @Override public void setOpacity(final float value) { mutations[0]++; opacity[0] = value; }
            @Override public byte constantFlag() { return 0; }
            @Override public byte dynamicFlag() { return 0; }
            @Override public dev.turboism.sdk.cubism.model.BlendMode blendMode() { return dev.turboism.sdk.cubism.model.BlendMode.NORMAL; }
            @Override public int textureIndex() { return 0; }
            @Override public int drawOrder() { return 0; }
            @Override public int renderOrder() { return 0; }
            @Override public IntSequence masks() { return emptyInts(); }
            @Override public dev.turboism.sdk.cubism.model.FloatSequence vertexPositions() { return emptyFloats(); }
            @Override public dev.turboism.sdk.cubism.model.FloatSequence vertexUvs() { return emptyFloats(); }
            @Override public IntSequence indices() { return emptyInts(); }
            @Override public Color multiplyColor() { return new Color(1, 1, 1, 1); }
            @Override public Color screenColor() { return new Color(0, 0, 0, 1); }
            @Override public int parentPartIndex() { return -1; }
            @Override public int parentDeformerIndex() { return -1; }
            @Override public IntSequence parameters() { return emptyInts(); }
        };
        final var lifecycle = new dev.turboism.adapter.cubism.lifecycle.EditorObjectLifecycleCoordinator();
        lifecycle.drawable().register(new dev.turboism.adapter.cubism.lifecycle.DrawableLifecycleCoordinator.PluginHooks(
            descriptor("hook.plugin"),
            List.of(new dev.turboism.sdk.cubism.hook.DrawableHooks() {
                @Override public float beforeSetDrawableOpacity(Drawable value, float requested) {
                    events.add("before"); return requested * 0.5F;
                }
            }),
            logger()
        ));
        final CubismFacadeImpl facade = new CubismFacadeImpl(
            sampleSource(),
            new CubismPermissionGate(
                "plugin.caller",
                List.of(permission(CubismFacadeImpl.MODEL_READ_PERMISSION)),
                ignored -> { },
                FIXED_CLOCK
            ),
            () -> editorObjectModel(drawable, new WarpDeformer() {
                @Override public DeformerId id() { return new DeformerId("WarpA"); }
                @Override public int parentDeformerIndex() { return -1; }
                @Override public IntSequence parameters() { return emptyInts(); }
                @Override public WarpGrid grid() { throw new UnsupportedOperationException(); }
                @Override public void replaceGrid(WarpGrid value) { throw new UnsupportedOperationException(); }
            }, new RotationDeformer() {
                @Override public DeformerId id() { return new DeformerId("RotationA"); }
                @Override public int parentDeformerIndex() { return -1; }
                @Override public IntSequence parameters() { return emptyInts(); }
                @Override public float baseAngle() { throw new UnsupportedOperationException(); }
                @Override public void setBaseAngle(float value) { throw new UnsupportedOperationException(); }
                @Override public RotationDeformerForm form() { throw new UnsupportedOperationException(); }
                @Override public void replaceForm(RotationDeformerForm value) { throw new UnsupportedOperationException(); }
            }),
            new ParameterLifecycleCoordinator(),
            new dev.turboism.adapter.cubism.lifecycle.PartLifecycleCoordinator(),
            lifecycle,
            () -> true
        );

        assertThrows(CubismPermissionException.class, () ->
            facade.model().active().drawables().find(drawable.id()).setOpacity(0.5F)
        );
        lifecycle.drawable().awaitIdle();
        assertEquals(List.of(), events);
        assertEquals(0, mutations[0]);
        assertEquals(1.0F, opacity[0]);
    }


    @Test
    void genericDeformerProjectionPreservesSubtypeWritesAndLifecycle() {
        final java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        final java.util.function.Consumer<String> count = key -> counts.merge(key, 1, Integer::sum);
        final boolean[] rotationVisible = {true};
        final boolean[] rotationLocked = {false};
        final float[] warpOpacity = {1.0F};
        final boolean[] warpVisible = {true};
        final boolean[] warpLocked = {false};
        final WarpGrid[] warpGrid = {new WarpGrid(1, 1, false, List.of(
            new Point2(0, 0), new Point2(1, 0), new Point2(0, 1), new Point2(1, 1)
        ))};
        final WarpDeformer backendWarp = new WarpDeformer() {
            @Override public DeformerId id() { return new DeformerId("WarpA"); }
            @Override public boolean visible() { return warpVisible[0]; }
            @Override public void setVisible(boolean value) { warpVisible[0] = value; }
            @Override public boolean locked() { return warpLocked[0]; }
            @Override public void setLocked(boolean value) { warpLocked[0] = value; }
            @Override public float getOpacity() { return warpOpacity[0]; }
            @Override public void setOpacity(float value) { warpOpacity[0] = value; }
            @Override public WarpGrid grid() { return warpGrid[0]; }
            @Override public void replaceGrid(WarpGrid value) { warpGrid[0] = value; }
            @Override public int parentDeformerIndex() { return -1; }
            @Override public IntSequence parameters() { return emptyInts(); }
        };
        final float[] rotationOpacity = {1.0F};
        final float[] rotationAngle = {0.0F};
        final RotationDeformerForm[] rotationForm = {
            new RotationDeformerForm(0, 0, 0, 1, false, false)
        };
        final RotationDeformer backendRotation = new RotationDeformer() {
            @Override public DeformerId id() { return new DeformerId("RotationA"); }
            @Override public boolean visible() { return rotationVisible[0]; }
            @Override public void setVisible(boolean value) { rotationVisible[0] = value; }
            @Override public boolean locked() { return rotationLocked[0]; }
            @Override public void setLocked(boolean value) { rotationLocked[0] = value; }
            @Override public float getOpacity() { return rotationOpacity[0]; }
            @Override public void setOpacity(float value) { rotationOpacity[0] = value; }
            @Override public float baseAngle() { return rotationAngle[0]; }
            @Override public void setBaseAngle(float value) { rotationAngle[0] = value; }
            @Override public RotationDeformerForm form() { return rotationForm[0]; }
            @Override public void replaceForm(RotationDeformerForm value) { rotationForm[0] = value; }
            @Override public int parentDeformerIndex() { return -1; }
            @Override public IntSequence parameters() { return emptyInts(); }
        };
        final var lifecycle = new dev.turboism.adapter.cubism.lifecycle.EditorObjectLifecycleCoordinator();
        lifecycle.deformer().register(new dev.turboism.adapter.cubism.lifecycle.DeformerLifecycleCoordinator.PluginHooks(
            descriptor("hook.plugin"),
            List.of(new dev.turboism.sdk.cubism.hook.DeformerHooks() {
                @Override public float beforeSetDeformerOpacity(Deformer value, float requested) {
                    count.accept(value.id().value() + ":opacity:before"); return requested;
                }
                @Override public void onDeformerOpacityChanged(Deformer value, float oldValue, float newValue) {
                    count.accept(value.id().value() + ":opacity:on");
                }
                @Override public void afterSetDeformerOpacity(Deformer value, float finalValue) {
                    count.accept(value.id().value() + ":opacity:after");
                }
                @Override public boolean beforeSetDeformerVisible(Deformer value, boolean requested) {
                    count.accept(value.id().value() + ":visible:before"); return requested;
                }
                @Override public void onDeformerVisibilityChanged(Deformer value, boolean oldValue, boolean newValue) {
                    count.accept(value.id().value() + ":visible:on");
                }
                @Override public void afterSetDeformerVisible(Deformer value, boolean finalValue) {
                    count.accept(value.id().value() + ":visible:after");
                }
                @Override public boolean beforeSetDeformerLocked(Deformer value, boolean requested) {
                    count.accept(value.id().value() + ":locked:before"); return requested;
                }
                @Override public void onDeformerLockChanged(Deformer value, boolean oldValue, boolean newValue) {
                    count.accept(value.id().value() + ":locked:on");
                }
                @Override public void afterSetDeformerLocked(Deformer value, boolean finalValue) {
                    count.accept(value.id().value() + ":locked:after");
                }
                @Override public WarpGrid beforeReplaceWarpDeformerGrid(WarpDeformer value, WarpGrid requested) {
                    count.accept(value.id().value() + ":grid:before"); return requested;
                }
                @Override public void onWarpDeformerGridChanged(WarpDeformer value, WarpGrid oldValue, WarpGrid newValue) {
                    count.accept(value.id().value() + ":grid:on");
                }
                @Override public void afterReplaceWarpDeformerGrid(WarpDeformer value, WarpGrid finalValue) {
                    count.accept(value.id().value() + ":grid:after");
                }
                @Override public float beforeSetRotationDeformerBaseAngle(RotationDeformer value, float requested) {
                    count.accept(value.id().value() + ":angle:before"); return requested;
                }
                @Override public void onRotationDeformerBaseAngleChanged(
                    RotationDeformer value, float oldValue, float newValue
                ) { count.accept(value.id().value() + ":angle:on"); }
                @Override public void afterSetRotationDeformerBaseAngle(RotationDeformer value, float finalValue) {
                    count.accept(value.id().value() + ":angle:after");
                }
                @Override public RotationDeformerForm beforeReplaceRotationDeformerForm(
                    RotationDeformer value, RotationDeformerForm requested
                ) { count.accept(value.id().value() + ":form:before"); return requested; }
                @Override public void onRotationDeformerFormChanged(
                    RotationDeformer value, RotationDeformerForm oldValue, RotationDeformerForm newValue
                ) { count.accept(value.id().value() + ":form:on"); }
                @Override public void afterReplaceRotationDeformerForm(
                    RotationDeformer value, RotationDeformerForm finalValue
                ) { count.accept(value.id().value() + ":form:after"); }
            }), logger()
        ));
        final CubismModel backendModel = editorObjectModel(
            new Drawable() {
                @Override public ArtMeshId id() { return new ArtMeshId("ArtMeshA"); }
                @Override public float getOpacity() { return 1.0F; }
                @Override public void setOpacity(float value) { }
                @Override public byte constantFlag() { return 0; }
                @Override public byte dynamicFlag() { return 0; }
                @Override public dev.turboism.sdk.cubism.model.BlendMode blendMode() {
                    return dev.turboism.sdk.cubism.model.BlendMode.NORMAL;
                }
                @Override public int textureIndex() { return 0; }
                @Override public int drawOrder() { return 0; }
                @Override public int renderOrder() { return 0; }
                @Override public IntSequence masks() { return emptyInts(); }
                @Override public dev.turboism.sdk.cubism.model.FloatSequence vertexPositions() {
                    return emptyFloats();
                }
                @Override public dev.turboism.sdk.cubism.model.FloatSequence vertexUvs() {
                    return emptyFloats();
                }
                @Override public IntSequence indices() { return emptyInts(); }
                @Override public Color multiplyColor() { return new Color(1, 1, 1, 1); }
                @Override public Color screenColor() { return new Color(0, 0, 0, 1); }
                @Override public int parentPartIndex() { return -1; }
                @Override public int parentDeformerIndex() { return -1; }
                @Override public IntSequence parameters() { return emptyInts(); }
            },
            backendWarp,
            backendRotation
        );
        final CubismFacadeImpl facade = new CubismFacadeImpl(
            sampleSource(),
            new CubismPermissionGate(
                "plugin.caller",
                List.of(
                    permission(CubismFacadeImpl.MODEL_READ_PERMISSION),
                    permission(CubismFacadeImpl.MODEL_WRITE_PERMISSION)
                ),
                ignored -> { }, FIXED_CLOCK
            ),
            () -> backendModel,
            new ParameterLifecycleCoordinator(),
            new dev.turboism.adapter.cubism.lifecycle.PartLifecycleCoordinator(),
            lifecycle,
            () -> true
        );
        final List<Deformer> generic = facade.model().active().deformers().all();
        final WarpDeformer warp = assertInstanceOf(WarpDeformer.class, generic.get(0));
        final RotationDeformer rotation = assertInstanceOf(
            RotationDeformer.class,
            facade.model().active().deformers().find(backendRotation.id())
        );
        final WarpGrid replacementGrid = new WarpGrid(1, 1, true, List.of(
            new Point2(0, 0), new Point2(2, 0), new Point2(0, 2), new Point2(2, 2)
        ));
        final RotationDeformerForm replacementForm =
            new RotationDeformerForm(1, 2, 3, 1.25F, true, false);

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

        assertEquals(0.5F, warpOpacity[0]);
        assertFalse(warpVisible[0]);
        assertTrue(warpLocked[0]);
        assertEquals(replacementGrid, warpGrid[0]);
        assertEquals(0.6F, rotationOpacity[0]);
        assertEquals(15.0F, rotationAngle[0]);
        assertEquals(replacementForm, rotationForm[0]);
        assertFalse(rotationVisible[0]);
        assertTrue(rotationLocked[0]);
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

    private CubismFacadeImpl facadeWith(
        final HostSnapshotSource source,
        final List<CubismFacadeAuditEvent> auditEvents,
        final List<PluginPermission> permissions
    ) {
        return new CubismFacadeImpl(source, new CubismPermissionGate(
            "plugin.demo",
            permissions,
            auditEvents::add,
            FIXED_CLOCK
        ));
    }

    private CubismFacadeImpl facadeWith(
        final HostSnapshotSource source,
        final List<CubismFacadeAuditEvent> auditEvents,
        final List<PluginPermission> permissions,
        final CubismModelAccess modelAccess
    ) {
        return new CubismFacadeImpl(source, new CubismPermissionGate(
            "plugin.demo",
            permissions,
            auditEvents::add,
            FIXED_CLOCK
        ), modelAccess);
    }

    private static CubismModel modelWithParameter(final float[] value) {
        return modelWithParameter(value, new ParameterDefinition[] {null});
    }

    private static CubismModel modelWithCombinedCalls(final List<String> calls) {
        return new CubismModel() {
            private final dev.turboism.sdk.cubism.model.Parameter parameter =
                new dev.turboism.sdk.cubism.model.Parameter() {
                    @Override public ParameterId id() { return new ParameterId("ParamA"); }
                    @Override public float getValue() { return 0.0F; }
                    @Override public float getMinimumValue() { return -1.0F; }
                    @Override public float getMaximumValue() { return 1.0F; }
                    @Override public float getDefaultValue() { return 0.0F; }
                    @Override public void setValue(final float value) { }
                    @Override public void combineWith(final ParameterId partnerId) {
                        calls.add("combine:" + partnerId.value());
                    }
                    @Override public void uncombine() { calls.add("uncombine"); }
                };
            @Override public ModelId id() { return new ModelId("model-a"); }
            @Override public dev.turboism.sdk.cubism.model.Parameters parameters() {
                return new dev.turboism.sdk.cubism.model.Parameters() {
                    @Override public List<dev.turboism.sdk.cubism.model.Parameter> all() {
                        return List.of(parameter);
                    }
                    @Override public dev.turboism.sdk.cubism.model.Parameter find(
                        final ParameterId id
                    ) { return parameter; }
                };
            }
            @Override public dev.turboism.sdk.cubism.model.Parts parts() { throw unsupported(); }
            @Override public dev.turboism.sdk.cubism.model.Drawables drawables() { throw unsupported(); }
            @Override public dev.turboism.sdk.cubism.model.Deformers deformers() { throw unsupported(); }
            @Override public dev.turboism.sdk.cubism.model.Glues glues() { throw unsupported(); }
            @Override public void update() { throw new UnsupportedOperationException(); }
            private UnsupportedOperationException unsupported() {
                return new UnsupportedOperationException();
            }
        };
    }

    private static CubismModel modelWithParameter(
        final float[] value,
        final ParameterDefinition[] updated
    ) {
        return new CubismModel() {
            private final dev.turboism.sdk.cubism.model.Parameter parameter =
                new dev.turboism.sdk.cubism.model.Parameter() {
                    @Override public ParameterId id() {
                        if (updated[0] != null) {
                            throw new IllegalStateException("parameter identity changed");
                        }
                        return new ParameterId("ParamA");
                    }
                    @Override public int index() { return 3; }
                    @Override public dev.turboism.sdk.cubism.model.FloatSequence keyValues() {
                        return emptyFloats();
                    }
                    @Override public Optional<String> name() { return Optional.of("Parameter A"); }
                    @Override public ParameterType type() { return ParameterType.BLEND_SHAPE; }
                    @Override public Optional<Boolean> repeat() { return Optional.of(false); }
                    @Override public Optional<Boolean> combined() { return Optional.of(true); }
                    @Override public Optional<ParameterId> combinedWith() {
                        return Optional.of(new ParameterId("ParamB"));
                    }
                    @Override public List<dev.turboism.sdk.cubism.model.ParameterBinding> getParameterBindings() { return List.of(); }
                    @Override public float getValue() { return value[0]; }
                    @Override public float getMinimumValue() { return -1.0F; }
                    @Override public float getMaximumValue() { return 1.0F; }
                    @Override public float getDefaultValue() { return 0.0F; }
                    @Override public void setValue(final float next) { value[0] = next; }
                    @Override public void updateDefinition(final ParameterDefinition definition) {
                        updated[0] = definition;
                    }
                };
            @Override public ModelId id() { return new ModelId("model-a"); }
            @Override public dev.turboism.sdk.cubism.model.Parameters parameters() {
                return new dev.turboism.sdk.cubism.model.Parameters() {
                    @Override public List<dev.turboism.sdk.cubism.model.Parameter> all() {
                        return List.of(parameter);
                    }
                    @Override public dev.turboism.sdk.cubism.model.Parameter find(
                        final ParameterId id
                    ) {
                        if (!parameter.id().equals(id)) throw new java.util.NoSuchElementException();
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

    private static CubismModel editorObjectModel(
        final Drawable drawable,
        final WarpDeformer warp,
        final RotationDeformer rotation
    ) {
        return new CubismModel() {
            @Override public ModelId id() { return new ModelId("model-1"); }
            @Override public Parameters parameters() { throw new UnsupportedOperationException(); }
            @Override public Parts parts() { throw new UnsupportedOperationException(); }
            @Override public Drawables drawables() {
                return new Drawables() {
                    @Override public List<Drawable> all() { return List.of(drawable); }
                    @Override public Drawable find(final ArtMeshId id) { return drawable; }
                };
            }
            @Override public Deformers deformers() {
                return new Deformers() {
                    @Override public List<Deformer> all() { return List.of(warp, rotation); }
                    @Override public Deformer find(final DeformerId id) {
                        if (warp.id().equals(id)) return warp;
                        if (rotation.id().equals(id)) return rotation;
                        throw new java.util.NoSuchElementException(id.value());
                    }
                };
            }
            @Override public WarpDeformers warpDeformers() {
                return new WarpDeformers() {
                    @Override public List<WarpDeformer> all() { return List.of(warp); }
                    @Override public WarpDeformer find(final DeformerId id) { return warp; }
                };
            }
            @Override public RotationDeformers rotationDeformers() {
                return new RotationDeformers() {
                    @Override public List<RotationDeformer> all() { return List.of(rotation); }
                    @Override public RotationDeformer find(final DeformerId id) { return rotation; }
                };
            }
            @Override public Glues glues() { throw new UnsupportedOperationException(); }
            @Override public void update() { throw new UnsupportedOperationException(); }
        };
    }

    private static IntSequence emptyInts() {
        return new IntSequence() {
            @Override public int size() { return 0; }
            @Override public int get(final int index) { throw new IndexOutOfBoundsException(index); }
        };
    }

    private static dev.turboism.sdk.cubism.model.FloatSequence emptyFloats() {
        return new dev.turboism.sdk.cubism.model.FloatSequence() {
            @Override public int size() { return 0; }
            @Override public float get(final int index) { throw new IndexOutOfBoundsException(index); }
        };
    }

    private static CubismModel emptyModel(final String id) {
        return emptyModel(id, ignored -> { });
    }

    private static CubismModel emptyModel(
        final String id,
        final java.util.function.Consumer<List<ClipMaskReplacement>> clipMaskWriter
    ) {
        return new CubismModel() {
            @Override public ModelId id() { return new ModelId(id); }
            @Override public dev.turboism.sdk.cubism.model.Parameters parameters() { throw unsupported(); }
            @Override public dev.turboism.sdk.cubism.model.Parts parts() { throw unsupported(); }
            @Override public dev.turboism.sdk.cubism.model.Drawables drawables() { throw unsupported(); }
            @Override public dev.turboism.sdk.cubism.model.Deformers deformers() { throw unsupported(); }
            @Override public dev.turboism.sdk.cubism.model.Glues glues() { throw unsupported(); }
            @Override public void update() { throw unsupported(); }
            @Override public void replaceArtMeshClipMasks(final List<ClipMaskReplacement> replacements) {
                clipMaskWriter.accept(List.copyOf(replacements));
            }

            private UnsupportedOperationException unsupported() {
                return new UnsupportedOperationException();
            }
        };
    }

    private static PluginPermission permission(final String id) {
        return new PluginPermission() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String scope() {
                return "read";
            }

            @Override
            public String reason() {
                return "test";
            }
        };
    }

    private static PluginDescriptor descriptor(final String id) {
        return new PluginDescriptor() {
            @Override public String id() { return id; }
            @Override public String name() { return id; }
            @Override public String version() { return "1.0.0"; }
            @Override public String description() { return "test"; }
            @Override public List<String> entrypoints() { return List.of(); }
            @Override public String turboismApi() { return "[0.1.0,0.2.0)"; }
            @Override public List<Author> authors() { return List.of(); }
            @Override public String license() { return "UNLICENSED"; }
            @Override public Optional<String> website() { return Optional.empty(); }
            @Override public List<String> resources() { return List.of(); }
            @Override public I18n i18n() { return new I18n() {
                @Override public String baseName() { return "messages"; }
                @Override public List<String> locales() { return List.of(); }
            }; }
            @Override public List<DependencyRef> dependencies() { return List.of(); }
            @Override public List<PermissionRef> permissions() { return List.of(); }
            @Override public List<String> capabilities() { return List.of(); }
            @Override public Environment environment() { return new Environment() {
                @Override public boolean requiresCubism() { return false; }
                @Override public String ui() { return "none"; }
            }; }
        };
    }

    private static PluginLogger logger() {
        return new PluginLogger() {
            @Override public void debug(final String message) { }
            @Override public void info(final String message) { }
            @Override public void warn(final String message) { }
            @Override public void error(final String message) { }
            @Override public void error(final String message, final Throwable throwable) { }
        };
    }

    private static HostSnapshotSource sampleSource() {
        final HostSnapshotSource.HostModel model = new HostSnapshotSource.HostModel(
            "model-1",
            "Model",
            List.of(new HostSnapshotSource.HostParameter("param-1", "Param", 1.0, 0.0, -1.0, 1.0, true, true)),
            List.of(new HostSnapshotSource.HostArtMesh("mesh-1", "Mesh", Optional.of("texture-1"), true, true)),
            List.of(new HostSnapshotSource.HostDeformer("deformer-1", "Deformer", DeformerType.ROOT, Optional.empty(), List.of()))
        );
        final HostSnapshotSource.HostDocument document = new HostSnapshotSource.HostDocument(
            "document-1",
            "Document",
            "models/demo/model.cdi3.json",
            Optional.of(Path.of("models/demo/model.cdi3.json")),
            Optional.of(model)
        );
        final HostSnapshotSource.HostProject project = new HostSnapshotSource.HostProject(
            "project-1",
            "Project",
            Optional.of(Path.of("projects/demo")),
            List.of(document)
        );
        return new StubHostSnapshotSource(
            Optional.of(project),
            Optional.of(document),
            Optional.of(model),
            new HostSnapshotSource.HostSelection(List.of("param-1"), Optional.of("param-1"), Optional.empty(), Optional.empty()),
            true
        );
    }

    private static HostSnapshotSource emptySource() {
        return new StubHostSnapshotSource(
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            new HostSnapshotSource.HostSelection(List.of(), Optional.empty(), Optional.empty(), Optional.empty()),
            false
        );
    }

    private record StubHostSnapshotSource(
        Optional<HostProject> project,
        Optional<HostDocument> document,
        Optional<HostModel> model,
        HostSelection selection,
        boolean hostPresent
    ) implements HostSnapshotSource {
        @Override
        public Optional<HostProject> activeProject() {
            return project;
        }

        @Override
        public Optional<HostDocument> activeDocument() {
            return document;
        }

        @Override
        public Optional<HostModel> activeModel() {
            return model;
        }

        @Override
        public boolean isHostPresent() {
            return hostPresent;
        }

        @Override
        public long invalidationToken() {
            return 0L;
        }
    }
}

package dev.turboism.adapter.cubism;

import dev.turboism.diagnostics.CubismFacadeAuditEvent;
import dev.turboism.adapter.cubism.lifecycle.ParameterLifecycleCoordinator;
import dev.turboism.permissions.CubismPermissionGate;
import dev.turboism.sdk.cubism.CubismRuntimeSnapshot;
import dev.turboism.sdk.cubism.DeformerType;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.id.ParameterGroupId;
import dev.turboism.sdk.cubism.id.ParameterId;
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
import dev.turboism.sdk.cubism.CubismPlugin;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertEquals(Optional.of("Parameter A"), parameter.name());
        assertEquals(ParameterType.BLEND_SHAPE, parameter.type());
        assertEquals(Optional.of(false), parameter.repeat());
        assertEquals(Optional.of(true), parameter.combined());
        assertEquals(Optional.of(new ParameterId("ParamB")), parameter.combinedWith());
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
    void parameterGroupColorWritesRequireModelWritePermission() {
        final List<CubismFacadeAuditEvent> auditEvents = new ArrayList<>();
        final List<Color> writes = new ArrayList<>();
        final ParameterGroup group = new ParameterGroup() {
            @Override public ParameterGroupId id() { return new ParameterGroupId("GroupFace"); }
            @Override public Optional<String> name() { return Optional.of("Face"); }
            @Override public Color labelColor() {
                return new Color(0.25F, 0.5F, 0.75F, 1.0F);
            }
            @Override public void setLabelColor(final Color color) { writes.add(color); }
            @Override public Optional<ParameterGroupId> parentId() { return Optional.empty(); }
            @Override public List<ParameterGroupId> childGroupIds() { return List.of(); }
            @Override public List<ParameterId> parameterIds() { return List.of(); }
        };
        final ParameterGroups groups = new ParameterGroups() {
            @Override public List<ParameterGroup> all() { return List.of(group); }
            @Override public ParameterGroup root() { return group; }
            @Override public ParameterGroup find(final ParameterGroupId id) { return group; }
        };
        final CubismModelAccess backend = () -> new CubismModel() {
            @Override public ModelId id() { return new ModelId("model-a"); }
            @Override public dev.turboism.sdk.cubism.model.Parameters parameters() {
                throw new UnsupportedOperationException();
            }
            @Override public ParameterGroups parameterGroups() { return groups; }
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
        final Color color = new Color(0.1F, 0.2F, 0.3F, 1.0F);
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
            () -> denied.model().active().parameterGroups()
                .find(new ParameterGroupId("GroupFace"))
                .setLabelColor(color)
        );
        assertEquals(List.of(), writes);
        assertEquals("parameterGroup.setLabelColor", auditEvents.get(0).operationId());

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
        allowed.model().active().parameterGroups().root().setLabelColor(color);
        assertEquals(List.of(color), writes);
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
    void parameterDefinitionUpdateRequiresModelWritePermissionBeforeInvokingBackend() {
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
    void legacyConstructorsKeepUnifiedModelAccessExplicitlyUnavailable() {
        final CubismFacadeImpl facade = facadeWith(
            sampleSource(),
            new ArrayList<>(),
            List.of(permission(CubismFacadeImpl.MODEL_READ_PERMISSION))
        );

        assertThrows(UnsupportedOperationException.class, () -> facade.model().active());
    }

    @Test
    void parameterSetterUsesTheRuntimeLifecycleAroundTheBackendWrite() {
        final float[] value = {1.0F};
        final List<String> events = new ArrayList<>();
        final ParameterLifecycleCoordinator lifecycle = new ParameterLifecycleCoordinator();
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
            lifecycle
        );

        facade.model().active().parameters().find(new ParameterId("ParamA")).setValue(10.0F);
        lifecycle.awaitIdle();

        assertEquals(5.0F, value[0]);
        assertEquals(List.of("on:1.0->5.0", "after:5.0"), events);
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
            @Override public void update() { throw unsupported(); }
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
                    @Override public ParameterId id() { return new ParameterId("ParamA"); }
                    @Override public Optional<String> name() { return Optional.of("Parameter A"); }
                    @Override public ParameterType type() { return ParameterType.BLEND_SHAPE; }
                    @Override public Optional<Boolean> repeat() { return Optional.of(false); }
                    @Override public Optional<Boolean> combined() { return Optional.of(true); }
                    @Override public Optional<ParameterId> combinedWith() {
                        return Optional.of(new ParameterId("ParamB"));
                    }
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

    private static CubismModel emptyModel(final String id) {
        return new CubismModel() {
            @Override public ModelId id() { return new ModelId(id); }
            @Override public dev.turboism.sdk.cubism.model.Parameters parameters() { throw unsupported(); }
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

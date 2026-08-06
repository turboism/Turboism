package dev.turboism.adapter.cubism;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.permissions.CubismPermissionGate;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.id.ParameterGroupId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.AnimationDocument;
import dev.turboism.sdk.cubism.model.AutoYure;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.Drawables;
import dev.turboism.sdk.cubism.model.ModelProfile;
import dev.turboism.sdk.cubism.model.IntSequence;
import dev.turboism.sdk.cubism.model.MorphTarget;
import dev.turboism.sdk.cubism.model.MorphTargets;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.cubism.model.ParameterDefinition;
import dev.turboism.sdk.cubism.model.ParameterGroup;
import dev.turboism.sdk.cubism.model.ParameterGroups;
import dev.turboism.sdk.cubism.model.Parameters;
import dev.turboism.sdk.cubism.model.Part;
import dev.turboism.sdk.cubism.model.ParameterType;
import dev.turboism.sdk.cubism.model.PartId;
import dev.turboism.sdk.cubism.model.Parts;
import dev.turboism.sdk.cubism.model.PhysicsSettings;
import dev.turboism.sdk.cubism.model.PhysicsSettingsSource;
import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.permission.PluginPermission;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Focused permission-gate coverage for the Wave 1 SDK method forwardings added
 * to the PermissionChecked wrapper layer of {@link CubismFacadeImpl}.
 */
class PermissionCheckedModelForwardingTest {

    private static final Clock FIXED_CLOCK =
        Clock.fixed(Instant.parse("2026-07-07T00:00:00Z"), ZoneOffset.UTC);

    private static final ModelProfile PROFILE = new ModelProfile() {
        @Override public float pixelsPerUnit() { return 1.0F; }
        @Override public float originXPixels() { return 2.0F; }
        @Override public float originYPixels() { return 3.0F; }
    };

    private static final PhysicsSettings PHYSICS = new PhysicsSettings() {
        @Override public float gravityX() { return 0.0F; }
        @Override public float gravityY() { return 1.0F; }
        @Override public float windX() { return 0.0F; }
        @Override public float windY() { return 0.0F; }
        @Override public Integer settingFps() { return 30; }
        @Override public List<PhysicsSettingsSource> sources() { return List.of(); }
    };

    private static final AutoYure AUTO_YURE = () -> List.of();

    private static final AnimationDocument ANIMATION = new AnimationDocument() {
        @Override public String animationName() { return "anim-1"; }
        @Override public int sceneCount() { return 1; }
        @Override public Optional<String> currentSceneName() { return Optional.of("scene-1"); }
        @Override public List<String> sceneNames() { return List.of("scene-1"); }
    };

    private static final MorphTargets EMPTY_MORPH_TARGETS = new MorphTargets() {
        @Override public List<MorphTarget> all() { return List.of(); }
        @Override public MorphTarget find(final ParameterId id) { throw new java.util.NoSuchElementException(); }
    };

    private static final ParameterDefinition DEFINITION = new ParameterDefinition(
        new ParameterId("ParamNew"),
        "Param New",
        0.0F,
        0.5F,
        1.0F,
        ParameterType.NORMAL,
        false
    );

    private static final ParameterId PARAMETER_ID = new ParameterId("ParamA");
    private static final ParameterGroupId GROUP_ID = new ParameterGroupId("GroupA");
    private static final PartId PART_ID = new PartId("PartA");

    @Test
    void modelReadForwardingsDelegateToBackendWhenReadIsGranted() {
        final List<String> calls = new ArrayList<>();
        final CubismModel model = facade(
            List.of(readPermission(true), writePermission(true)), calls
        ).model().active();

        assertSame(PROFILE, model.profile());
        assertSame(PHYSICS, model.physicsSettings());
        assertSame(AUTO_YURE, model.autoYure());
        assertEquals(List.of(ANIMATION), model.animationDocuments());
        assertEquals(List.of("profile", "physicsSettings", "autoYure", "animationDocuments"), calls);
    }

    @Test
    void modelReadForwardingsFailClosedWithoutReadPermission() {
        final List<String> calls = new ArrayList<>();
        final boolean[] readGranted = {true};
        final CubismModel model = facade(List.of(readPermission(readGranted), writePermission(true)), calls)
            .model().active();
        readGranted[0] = false;

        assertThrows(CubismPermissionException.class, model::profile);
        assertThrows(CubismPermissionException.class, model::physicsSettings);
        assertThrows(CubismPermissionException.class, model::autoYure);
        assertThrows(CubismPermissionException.class, model::animationDocuments);
        assertTrue(calls.isEmpty(), "delegate must not be invoked when read permission is denied");
    }

    @Test
    void parameterWritesDelegateAndWrapReturnValuesWhenWriteIsGranted() {
        final List<String> calls = new ArrayList<>();
        final RecordingModel delegate = new RecordingModel(calls);
        final Parameters parameters = facade(
            delegate, List.of(readPermission(true), writePermission(true))
        ).model().active().parameters();

        final Parameter created = parameters.create(DEFINITION);
        final Parameter createdInFolder = parameters.create(DEFINITION, Optional.of(GROUP_ID));
        final Parameter copied = parameters.copy(PARAMETER_ID);
        parameters.remove(PARAMETER_ID);

        assertNotSame(created, delegate.parameter(), "create must return a PermissionChecked wrapper");
        assertNotSame(createdInFolder, delegate.parameter(), "create must return a PermissionChecked wrapper");
        assertNotSame(copied, delegate.parameter(), "copy must return a PermissionChecked wrapper");
    }

    @Test
    void parameterWritesFailClosedWithoutWritePermission() {
        final List<String> calls = new ArrayList<>();
        final Parameters parameters = facade(
            List.of(readPermission(true)), calls
        ).model().active().parameters();

        assertThrows(CubismPermissionException.class, () -> parameters.create(DEFINITION));
        assertThrows(
            CubismPermissionException.class,
            () -> parameters.create(DEFINITION, Optional.of(GROUP_ID))
        );
        assertThrows(CubismPermissionException.class, () -> parameters.copy(PARAMETER_ID));
        assertThrows(CubismPermissionException.class, () -> parameters.remove(PARAMETER_ID));
        assertTrue(calls.isEmpty(), "delegate must not be invoked when write permission is denied");
    }

    @Test
    void parameterGroupWritesDelegateAndWrapReturnValueWhenWriteIsGranted() {
        final List<String> calls = new ArrayList<>();
        final RecordingModel delegate = new RecordingModel(calls);
        final ParameterGroups groups = facade(
            delegate, List.of(readPermission(true), writePermission(true))
        ).model().active().parameterGroups();

        final ParameterGroup created = groups.addGroup("Group B");
        groups.removeGroup(GROUP_ID);
        groups.moveParameter(PARAMETER_ID, GROUP_ID);

        assertNotSame(created, delegate.group(), "addGroup must return a PermissionChecked wrapper");
    }

    @Test
    void wrappedResultsRemainPermissionCheckedAfterWriteRevocation() {
        final List<String> calls = new ArrayList<>();
        final boolean[] writeGranted = {true};
        final CubismModel model = facade(
            List.of(readPermission(true), writePermission(writeGranted)), calls
        ).model().active();

        final Parameter created = model.parameters().create(DEFINITION);
        final ParameterGroup addedGroup = model.parameterGroups().addGroup("Group B");
        final Part addedPart = model.parts().add(PART_ID);

        writeGranted[0] = false;

        assertThrows(CubismPermissionException.class, () -> created.setValue(1.0F));
        assertThrows(CubismPermissionException.class, () -> addedGroup.rename("renamed"));
        assertThrows(CubismPermissionException.class, () -> addedPart.setVisible(true));
        // The denied writes must not reach the delegate.
        assertEquals(
            List.of("create:ParamNew", "addGroup:Group B", "add:PartA"),
            calls
        );
    }

    @Test
    void parameterGroupWritesFailClosedWithoutWritePermission() {
        final List<String> calls = new ArrayList<>();
        final ParameterGroups groups = facade(
            List.of(readPermission(true)), calls
        ).model().active().parameterGroups();

        assertThrows(CubismPermissionException.class, () -> groups.addGroup("Group B"));
        assertThrows(CubismPermissionException.class, () -> groups.removeGroup(GROUP_ID));
        assertThrows(
            CubismPermissionException.class,
            () -> groups.moveParameter(PARAMETER_ID, GROUP_ID)
        );
        assertTrue(calls.isEmpty(), "delegate must not be invoked when write permission is denied");
    }

    @Test
    void partWritesDelegateAndWrapReturnValuesWhenWriteIsGranted() {
        final List<String> calls = new ArrayList<>();
        final RecordingModel delegate = new RecordingModel(calls);
        final Parts parts = facade(
            delegate, List.of(readPermission(true), writePermission(true))
        ).model().active().parts();

        final Part added = parts.add(PART_ID);
        final Part addedUnder = parts.add(PART_ID, PART_ID);
        final Part copied = parts.copy(PART_ID);
        parts.remove(PART_ID);

        assertNotSame(added, delegate.part(), "add must return a PermissionChecked wrapper");
        assertNotSame(addedUnder, delegate.part(), "add must return a PermissionChecked wrapper");
        assertNotSame(copied, delegate.part(), "copy must return a PermissionChecked wrapper");

        // String overloads route through the PartId overloads (SDK default), so they
        // are permission-checked too.
        final Part addedByString = parts.add("PartString");
        final Part addedUnderByString = parts.add("PartString", PART_ID);
        assertNotSame(addedByString, delegate.part(), "String add must return a PermissionChecked wrapper");
        assertNotSame(addedUnderByString, delegate.part(), "String add must return a PermissionChecked wrapper");
    }

    @Test
    void partWritesFailClosedWithoutWritePermission() {
        final List<String> calls = new ArrayList<>();
        final Parts parts = facade(
            List.of(readPermission(true)), calls
        ).model().active().parts();

        assertThrows(CubismPermissionException.class, () -> parts.add(PART_ID));
        assertThrows(CubismPermissionException.class, () -> parts.add(PART_ID, PART_ID));
        assertThrows(CubismPermissionException.class, () -> parts.add("PartString"));
        assertThrows(CubismPermissionException.class, () -> parts.copy(PART_ID));
        assertThrows(CubismPermissionException.class, () -> parts.remove(PART_ID));
        assertTrue(calls.isEmpty(), "delegate must not be invoked when write permission is denied");
    }

    @Test
    void parameterGroupRenameDelegatesWhenWriteIsGrantedAndFailsClosedWithout() {
        final List<String> calls = new ArrayList<>();
        final ParameterGroup group = facade(
            List.of(readPermission(true), writePermission(true)), calls
        ).model().active().parameterGroups().root();

        group.rename("Renamed");
        assertEquals(List.of("rename:Renamed"), calls);

        calls.clear();
        final boolean[] writeGranted = {true};
        final ParameterGroup guarded = facade(
            List.of(readPermission(true), writePermission(writeGranted)), calls
        ).model().active().parameterGroups().root();
        writeGranted[0] = false;
        assertThrows(CubismPermissionException.class, () -> guarded.rename("Renamed"));
        assertTrue(calls.isEmpty(), "delegate must not be invoked when write permission is denied");
    }

    @Test
    void morphTargetsReadsDelegateToBackendWhenReadIsGranted() {
        final List<String> calls = new ArrayList<>();
        final CubismModel model = facade(
            List.of(readPermission(true), writePermission(true)), calls
        ).model().active();

        assertSame(EMPTY_MORPH_TARGETS, model.parts().find(PART_ID).morphTargets());
        assertSame(EMPTY_MORPH_TARGETS, model.drawables().find(new ArtMeshId("ArtMeshA")).morphTargets());
        assertEquals(List.of("part.morphTargets", "drawable.morphTargets"), calls);
    }

    @Test
    void morphTargetsReadsFailClosedWithoutReadPermission() {
        final List<String> calls = new ArrayList<>();
        final boolean[] readGranted = {true};
        final CubismModel model = facade(
            List.of(readPermission(readGranted), writePermission(true)), calls
        ).model().active();
        final Part part = model.parts().find(PART_ID);
        final Drawable drawable = model.drawables().find(new ArtMeshId("ArtMeshA"));
        readGranted[0] = false;

        assertThrows(CubismPermissionException.class, part::morphTargets);
        assertThrows(CubismPermissionException.class, drawable::morphTargets);
        assertTrue(calls.isEmpty(), "delegate must not be invoked when read permission is denied");
    }

    // ---------------------------------------------------------------- fixtures

    private static CubismFacadeImpl facade(
        final RecordingModel delegate,
        final List<PluginPermission> permissions
    ) {
        return new CubismFacadeImpl(
            new HostSnapshotSource() {
                @Override public Optional<HostProject> activeProject() { return Optional.empty(); }
                @Override public Optional<HostDocument> activeDocument() { return Optional.empty(); }
                @Override public Optional<HostModel> activeModel() { return Optional.empty(); }
                @Override public HostSelection selection() {
                    return new HostSelection(List.of(), Optional.empty(), Optional.empty(), Optional.empty());
                }
                @Override public boolean isHostPresent() { return false; }
                @Override public long invalidationToken() { return 0L; }
            },
            new CubismPermissionGate("plugin.demo", permissions, ignored -> { }, FIXED_CLOCK),
            (CubismModelAccess) () -> delegate
        );
    }

    private static CubismFacadeImpl facade(
        final List<PluginPermission> permissions,
        final List<String> calls
    ) {
        return facade(new RecordingModel(calls), permissions);
    }

    private static PluginPermission readPermission(final boolean[] granted) {
        return revocable(CubismFacadeImpl.MODEL_READ_PERMISSION, granted);
    }

    private static PluginPermission readPermission(final boolean granted) {
        return revocable(CubismFacadeImpl.MODEL_READ_PERMISSION, new boolean[] {granted});
    }

    private static PluginPermission writePermission(final boolean[] granted) {
        return revocable(CubismFacadeImpl.MODEL_WRITE_PERMISSION, granted);
    }

    private static PluginPermission writePermission(final boolean granted) {
        return revocable(CubismFacadeImpl.MODEL_WRITE_PERMISSION, new boolean[] {granted});
    }

    private static PluginPermission revocable(final String id, final boolean[] granted) {
        return new PluginPermission() {
            @Override public String id() { return granted[0] ? id : "revoked"; }
            @Override public String scope() { return "read"; }
            @Override public String reason() { return "test"; }
        };
    }

    private static IntSequence emptyIntSequence() {
        return new IntSequence() {
            @Override public int size() { return 0; }
            @Override public int get(final int index) { throw new IndexOutOfBoundsException(index); }
        };
    }

    private static dev.turboism.sdk.cubism.model.FloatSequence emptyFloatSequence() {
        return new dev.turboism.sdk.cubism.model.FloatSequence() {
            @Override public int size() { return 0; }
            @Override public float get(final int index) { throw new IndexOutOfBoundsException(index); }
        };
    }

    /** Delegate model recording every Wave 1 method invocation into {@code calls}. */
    private static final class RecordingModel implements CubismModel {

        private final List<String> calls;
        private final Parameter parameter = new Parameter() {
            @Override public ParameterId id() { return PARAMETER_ID; }
            @Override public float getValue() { return 0.0F; }
            @Override public float getMinimumValue() { return -1.0F; }
            @Override public float getMaximumValue() { return 1.0F; }
            @Override public float getDefaultValue() { return 0.0F; }
            @Override public void setValue(final float value) { }
        };
        private final ParameterGroup group = new ParameterGroup() {
            @Override public ParameterGroupId id() { return GROUP_ID; }
            @Override public Optional<String> name() { return Optional.of("Group A"); }
            @Override public Optional<ParameterGroupId> parentId() { return Optional.empty(); }
            @Override public List<ParameterGroupId> childGroupIds() { return List.of(); }
            @Override public List<ParameterId> parameterIds() { return List.of(); }
            @Override public void rename(final String name) { calls.add("rename:" + name); }
        };
        private final Part part = new Part() {
            @Override public PartId id() { return PART_ID; }
            @Override public void setName(final String name) { }
            @Override public float getOpacity() { return 1.0F; }
            @Override public int parentIndex() { return 0; }
            @Override public void setOpacity(final float opacity) { }
            @Override public MorphTargets morphTargets() {
                calls.add("part.morphTargets");
                return EMPTY_MORPH_TARGETS;
            }
        };
        private final Drawable drawable = new Drawable() {
            @Override public ArtMeshId id() { return new ArtMeshId("ArtMeshA"); }
            @Override public byte constantFlag() { return 0; }
            @Override public byte dynamicFlag() { return 0; }
            @Override public dev.turboism.sdk.cubism.model.BlendMode blendMode() {
                return dev.turboism.sdk.cubism.model.BlendMode.NORMAL;
            }
            @Override public int textureIndex() { return 0; }
            @Override public int drawOrder() { return 0; }
            @Override public int renderOrder() { return 0; }
            @Override public float getOpacity() { return 1.0F; }
            @Override public IntSequence masks() { return emptyIntSequence(); }
            @Override public dev.turboism.sdk.cubism.model.FloatSequence vertexPositions() {
                return emptyFloatSequence();
            }
            @Override public dev.turboism.sdk.cubism.model.FloatSequence vertexUvs() {
                return emptyFloatSequence();
            }
            @Override public IntSequence indices() { return emptyIntSequence(); }
            @Override public dev.turboism.sdk.cubism.model.Color multiplyColor() {
                return new dev.turboism.sdk.cubism.model.Color(1, 1, 1, 1);
            }
            @Override public dev.turboism.sdk.cubism.model.Color screenColor() {
                return new dev.turboism.sdk.cubism.model.Color(0, 0, 0, 1);
            }
            @Override public int parentPartIndex() { return -1; }
            @Override public int parentDeformerIndex() { return -1; }
            @Override public IntSequence parameters() { return emptyIntSequence(); }
            @Override public MorphTargets morphTargets() {
                calls.add("drawable.morphTargets");
                return EMPTY_MORPH_TARGETS;
            }
        };

        private RecordingModel(final List<String> calls) {
            this.calls = calls;
        }

        Parameter parameter() { return parameter; }

        ParameterGroup group() { return group; }

        Part part() { return part; }

        @Override public ModelId id() { return new ModelId("model-a"); }

        @Override public ModelProfile profile() { calls.add("profile"); return PROFILE; }

        @Override public PhysicsSettings physicsSettings() { calls.add("physicsSettings"); return PHYSICS; }

        @Override public AutoYure autoYure() { calls.add("autoYure"); return AUTO_YURE; }

        @Override public List<AnimationDocument> animationDocuments() {
            calls.add("animationDocuments");
            return List.of(ANIMATION);
        }

        @Override public Parameters parameters() {
            return new Parameters() {
                @Override public List<Parameter> all() { return List.of(parameter); }
                @Override public Parameter find(final ParameterId id) { return parameter; }
                @Override public Parameter create(final ParameterDefinition definition) {
                    calls.add("create:" + definition.id().value());
                    return parameter;
                }
                @Override public Parameter create(
                    final ParameterDefinition definition,
                    final Optional<ParameterGroupId> folderId
                ) {
                    calls.add("create:" + definition.id().value());
                    return parameter;
                }
                @Override public Parameter copy(final ParameterId id) {
                    calls.add("copy:" + id.value());
                    return parameter;
                }
                @Override public void remove(final ParameterId id) {
                    calls.add("remove:" + id.value());
                }
            };
        }

        @Override public ParameterGroups parameterGroups() {
            return new ParameterGroups() {
                @Override public List<ParameterGroup> all() { return List.of(group); }
                @Override public ParameterGroup root() { return group; }
                @Override public ParameterGroup find(final ParameterGroupId id) { return group; }
                @Override public ParameterGroup addGroup(final String name) {
                    calls.add("addGroup:" + name);
                    return group;
                }
                @Override public void removeGroup(final ParameterGroupId id) {
                    calls.add("removeGroup:" + id.value());
                }
                @Override public void moveParameter(
                    final ParameterId parameterId,
                    final ParameterGroupId targetGroupId
                ) {
                    calls.add("moveParameter:" + parameterId.value());
                }
            };
        }

        @Override public Parts parts() {
            return new Parts() {
                @Override public List<Part> all() { return List.of(part); }
                @Override public Part find(final PartId id) { return part; }
                @Override public Part add(final PartId id) { calls.add("add:" + id.value()); return part; }
                @Override public Part add(final PartId id, final PartId parentId) {
                    calls.add("add:" + id.value());
                    return part;
                }
                @Override public Part copy(final PartId id) { calls.add("copy:" + id.value()); return part; }
                @Override public void remove(final PartId id) { calls.add("remove:" + id.value()); }
            };
        }

        @Override public Drawables drawables() {
            return new Drawables() {
                @Override public List<Drawable> all() { return List.of(drawable); }
                @Override public Drawable find(final ArtMeshId id) { return drawable; }
            };
        }

        @Override public dev.turboism.sdk.cubism.model.Deformers deformers() {
            throw new UnsupportedOperationException();
        }

        @Override public dev.turboism.sdk.cubism.model.Glues glues() {
            throw new UnsupportedOperationException();
        }

        @Override public void update() { throw new UnsupportedOperationException(); }
    }
}

package dev.turboism.adapter.host;

import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.adapter.cubism.NativeLabelColorAuthoring;
import dev.turboism.adapter.cubism.NativeLabelColorTarget;
import dev.turboism.sdk.cubism.id.ParameterGroupId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.model.PartId;
import dev.turboism.sdk.cubism.model.Color;
import dev.turboism.sdk.ui.appearance.NativeLabelColor;
import dev.turboism.sdk.ui.appearance.NativeLabelColorState;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.sdk.cubism.model.ModelEditLevel;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.cubism.model.ParameterDefinition;
import dev.turboism.sdk.cubism.model.ParameterGroup;
import dev.turboism.sdk.cubism.model.ParameterGroups;
import dev.turboism.sdk.cubism.model.ParameterType;
import dev.turboism.sdk.cubism.model.Parameters;
import dev.turboism.sdk.cubism.model.AnimationDocument;
import dev.turboism.sdk.cubism.model.AutoYure;
import dev.turboism.sdk.cubism.model.ModelProfile;
import dev.turboism.sdk.cubism.model.MorphTargets;
import dev.turboism.sdk.cubism.model.Part;
import dev.turboism.sdk.cubism.model.PhysicsSettings;
import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.Deformers;
import dev.turboism.sdk.cubism.model.Glues;
import dev.turboism.sdk.cubism.model.IntSequence;
import dev.turboism.sdk.cubism.model.Parts;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.Drawables;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.cubism.model.RotationDeformer;
import dev.turboism.sdk.cubism.model.RotationDeformerForm;
import dev.turboism.sdk.cubism.model.RotationDeformers;
import dev.turboism.sdk.cubism.model.WarpDeformer;
import dev.turboism.sdk.cubism.model.WarpDeformers;
import dev.turboism.sdk.cubism.model.WarpGrid;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;

class DynamicCubismModelAccessTest {

    @Test
    void replacementInvalidatesModelCollectionsAndChildren() {
        DynamicCubismModelAccess access = new DynamicCubismModelAccess();
        access.connect(modelAccess("model-a", 1.0F));

        CubismModel staleModel = access.active();
        assertTrue(staleModel.defaultKeyformLocked());
        Parameters staleParameters = staleModel.parameters();
        assertEquals(ModelEditLevel.LEVEL_1, staleModel.editLevel());
        staleModel.setEditLevel(ModelEditLevel.LEVEL_2);
        assertEquals(ModelEditLevel.LEVEL_2, staleModel.editLevel());
        Parameter staleParameter = staleParameters.find(new ParameterId("ParamA"));
        assertEquals(1.0F, staleParameter.getValue());

        access.connect(modelAccess("model-b", 2.0F));

        assertThrows(IllegalStateException.class, staleModel::id);
        assertThrows(IllegalStateException.class, staleModel::defaultKeyformLocked);
        assertThrows(
            IllegalStateException.class,
            () -> staleModel.setDefaultKeyformLocked(false)
        );
        assertThrows(IllegalStateException.class, staleModel::editLevel);
        assertThrows(
            IllegalStateException.class,
            () -> staleModel.setEditLevel(ModelEditLevel.LEVEL_3)
        );
        assertThrows(IllegalStateException.class, staleParameters::all);
        assertThrows(IllegalStateException.class, staleParameter::getValue);
        assertEquals(new ModelId("model-b"), access.active().id());
    }

    @Test
    void sessionWrappersPreserveParameterGroupsAndGuardThemByGeneration() {
        final DynamicCubismModelAccess access = new DynamicCubismModelAccess();
        access.connect(() -> model("model-a", parameter(1.0F), parameterGroups()));

        final ParameterGroups groups = access.active().parameterGroups();
        final ParameterGroup group = groups.find(new ParameterGroupId("GroupFace"));
        assertEquals(Optional.of("Face"), group.name());
        assertEquals(List.of(new ParameterId("ParamA")), group.parameterIds());

        access.deactivate();
        assertThrows(IllegalStateException.class, groups::all);
        assertThrows(IllegalStateException.class, group::name);
    }

    @Test
    void sessionDrawableForwardsGuidToDelegateAndPropagatesUnsupported() {
        final DynamicCubismModelAccess access = new DynamicCubismModelAccess();
        access.connect(() -> modelWithDrawable(drawable("guid-x", false, null)));

        final Drawable wrapped = access.active().drawables().find(new ArtMeshId("ArtMeshA"));
        assertEquals("guid-x", wrapped.guid());

        access.deactivate();
        assertThrows(IllegalStateException.class, wrapped::guid);

        access.connect(() -> modelWithDrawable(drawable(null, true, null)));
        final Drawable unsupported = access.active().drawables().find(new ArtMeshId("ArtMeshA"));
        assertThrows(UnsupportedOperationException.class, unsupported::guid);
    }

    @Test
    void sessionWrappersForwardWave1WritesToDelegateAndReturnGuardedWrappers() {
        final RecordingModel recording = new RecordingModel();
        final DynamicCubismModelAccess access = new DynamicCubismModelAccess();
        access.connect(() -> recording);

        final Part added = access.active().parts().add(new PartId("PartAdded"));
        final Part addedUnder = access.active().parts().add(
            new PartId("PartAdded"), new PartId("PartRoot")
        );
        final Part copied = access.active().parts().copy(new PartId("PartSource"));
        access.active().parts().remove(new PartId("PartGone"));
        assertEquals(new PartId("PartReturned"), added.id());
        assertEquals(new PartId("PartReturned"), addedUnder.id());
        assertEquals(new PartId("PartReturned"), copied.id());

        final Parameter created = access.active().parameters().create(definition());
        final Parameter createdInFolder = access.active().parameters().create(
            definition(), Optional.of(new ParameterGroupId("GroupNew"))
        );
        final Parameter duplicated = access.active().parameters().copy(new ParameterId("ParamA"));
        access.active().parameters().remove(new ParameterId("ParamA"));
        assertEquals(new ParameterId("ParamA"), created.id());
        assertEquals(new ParameterId("ParamA"), createdInFolder.id());
        assertEquals(new ParameterId("ParamA"), duplicated.id());

        final ParameterGroup addedGroup = access.active().parameterGroups().addGroup("GroupNew");
        access.active().parameterGroups().removeGroup(new ParameterGroupId("GroupGone"));
        access.active().parameterGroups().moveParameter(
            new ParameterId("ParamA"), new ParameterGroupId("GroupNew")
        );
        assertEquals(new ParameterGroupId("GroupNew"), addedGroup.id());

        final ParameterGroup wrappedGroup =
            access.active().parameterGroups().find(new ParameterGroupId("GroupNew"));
        wrappedGroup.rename("Renamed");
        assertEquals(Optional.of("Renamed"), wrappedGroup.name());

        assertEquals(List.of(
            "parts.add", "parts.add.parent", "parts.copy", "parts.remove",
            "parameters.create", "parameters.create.folder", "parameters.copy", "parameters.remove",
            "groups.addGroup", "groups.removeGroup", "groups.moveParameter", "group.rename"
        ), recording.calls);

        access.deactivate();
        assertThrows(IllegalStateException.class, added::id);
        assertThrows(IllegalStateException.class, created::id);
        assertThrows(IllegalStateException.class, addedGroup::id);
        assertThrows(IllegalStateException.class, wrappedGroup::name);
    }

    @Test
    void wave1WritesFailClosedWithoutAnActiveSession() {
        final RecordingModel recording = new RecordingModel();
        final DynamicCubismModelAccess access = new DynamicCubismModelAccess();
        access.connect(() -> recording);

        final Parts parts = access.active().parts();
        final Parameters parameters = access.active().parameters();
        final ParameterGroups groups = access.active().parameterGroups();
        final ParameterGroup group =
            groups.find(new ParameterGroupId("GroupNew"));

        access.deactivate();

        assertThrows(IllegalStateException.class, () -> parts.add(new PartId("PartAdded")));
        assertThrows(IllegalStateException.class, () -> parts.add(
            new PartId("PartAdded"), new PartId("PartRoot")
        ));
        assertThrows(IllegalStateException.class, () -> parts.copy(new PartId("PartSource")));
        assertThrows(IllegalStateException.class, () -> parts.remove(new PartId("PartGone")));
        assertThrows(IllegalStateException.class, () -> parameters.create(definition()));
        assertThrows(IllegalStateException.class, () -> parameters.create(
            definition(), Optional.of(new ParameterGroupId("GroupNew"))
        ));
        assertThrows(IllegalStateException.class, () -> parameters.copy(new ParameterId("ParamA")));
        assertThrows(IllegalStateException.class, () -> parameters.remove(new ParameterId("ParamA")));
        assertThrows(IllegalStateException.class, () -> groups.addGroup("GroupNew"));
        assertThrows(IllegalStateException.class, () -> groups.removeGroup(
            new ParameterGroupId("GroupGone")
        ));
        assertThrows(IllegalStateException.class, () -> groups.moveParameter(
            new ParameterId("ParamA"), new ParameterGroupId("GroupNew")
        ));
        assertThrows(IllegalStateException.class, () -> group.rename("Renamed"));

        assertEquals(List.of(), recording.calls);
    }

    @Test
    void sessionModelForwardsWave1ReadsAndGuardsThemByGeneration() {
        final RecordingModel recording = new RecordingModel();
        final DynamicCubismModelAccess access = new DynamicCubismModelAccess();
        access.connect(() -> recording);

        final CubismModel model = access.active();
        assertSame(recording.profile, model.profile());
        assertSame(recording.physics, model.physicsSettings());
        assertSame(recording.autoYure, model.autoYure());
        assertEquals(List.of(recording.animation), model.animationDocuments());
        assertSame(
            recording.partTargets,
            model.parts().find(new PartId("PartReturned")).morphTargets()
        );
        assertSame(
            recording.drawableTargets,
            model.drawables().find(new ArtMeshId("ArtMeshA")).morphTargets()
        );

        access.deactivate();
        assertThrows(IllegalStateException.class, model::profile);
        assertThrows(IllegalStateException.class, model::physicsSettings);
        assertThrows(IllegalStateException.class, model::autoYure);
        assertThrows(IllegalStateException.class, model::animationDocuments);
        assertThrows(IllegalStateException.class, () -> model.parts().find(
            new PartId("PartReturned")
        ).morphTargets());
        assertThrows(IllegalStateException.class, () -> model.drawables().find(
            new ArtMeshId("ArtMeshA")
        ).morphTargets());
    }

    private static CubismModel modelWithDrawable(final Drawable drawable) {
        return new CubismModel() {
            @Override public ModelId id() { return new ModelId("model-a"); }
            @Override public Parameters parameters() { throw unsupported(); }
            @Override public dev.turboism.sdk.cubism.model.Parts parts() { throw unsupported(); }
            @Override public dev.turboism.sdk.cubism.model.Drawables drawables() {
                return new dev.turboism.sdk.cubism.model.Drawables() {
                    @Override public List<Drawable> all() { return List.of(drawable); }
                    @Override public Drawable find(final ArtMeshId id) { return drawable; }
                };
            }
            @Override public dev.turboism.sdk.cubism.model.Deformers deformers() { throw unsupported(); }
            @Override public dev.turboism.sdk.cubism.model.Glues glues() { throw unsupported(); }
            @Override public void update() { throw unsupported(); }
        };
    }

    private static Drawable drawable(
        final String guid,
        final boolean throwGuid,
        final MorphTargets morphTargets
    ) {
        return new Drawable() {
            @Override public ArtMeshId id() { return new ArtMeshId("ArtMeshA"); }
            @Override public String guid() {
                if (throwGuid) {
                    throw unsupported();
                }
                return guid;
            }
            @Override public IntSequence parameters() { return emptyInts(); }
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
            @Override public dev.turboism.sdk.cubism.model.FloatSequence vertexPositions() {
                return emptyFloats();
            }
            @Override public dev.turboism.sdk.cubism.model.FloatSequence vertexUvs() {
                return emptyFloats();
            }
            @Override public IntSequence indices() { return emptyInts(); }
            @Override public Color multiplyColor() { return new Color(1.0F, 1.0F, 1.0F, 1.0F); }
            @Override public Color screenColor() { return new Color(0.0F, 0.0F, 0.0F, 1.0F); }
            @Override public int parentPartIndex() { return -1; }
            @Override public int parentDeformerIndex() { return -1; }
            @Override public MorphTargets morphTargets() { return morphTargets; }
        };
    }

    /** Recording fixture: Wave 1 write/read delegate calls land in {@link #calls}. */
    private static final class RecordingModel implements CubismModel {
        final List<String> calls = new java.util.ArrayList<>();
        final ModelProfile profile = new ModelProfile() {
            @Override public float pixelsPerUnit() { return 1.0F; }
            @Override public float originXPixels() { return 0.0F; }
            @Override public float originYPixels() { return 0.0F; }
        };
        final PhysicsSettings physics = new PhysicsSettings() {
            @Override public float gravityX() { return 0.0F; }
            @Override public float gravityY() { return -1.0F; }
            @Override public float windX() { return 0.0F; }
            @Override public float windY() { return 0.0F; }
            @Override public Integer settingFps() { return 30; }
            @Override public List<dev.turboism.sdk.cubism.model.PhysicsSettingsSource> sources() {
                return List.of();
            }
        };
        final AutoYure autoYure = new AutoYure() {
            @Override public List<dev.turboism.sdk.cubism.model.AutoYureBinding> bindings() {
                return List.of();
            }
        };
        final AnimationDocument animation = new AnimationDocument() {
            @Override public String animationName() { return "AnimA"; }
            @Override public int sceneCount() { return 1; }
            @Override public Optional<String> currentSceneName() { return Optional.empty(); }
            @Override public List<String> sceneNames() { return List.of(); }
        };
        final MorphTargets partTargets = morphTargets();
        final MorphTargets drawableTargets = morphTargets();
        final Part part = new Part() {
            @Override public PartId id() { return new PartId("PartReturned"); }
            @Override public void setName(final String name) { throw unsupported(); }
            @Override public MorphTargets morphTargets() { return partTargets; }
            @Override public void setOpacity(final float opacity) { throw unsupported(); }
            @Override public float getOpacity() { return 1.0F; }
            @Override public int parentIndex() { return -1; }
        };
        final Drawable drawable = drawable("guid-record", false, drawableTargets);
        final Parameter parameter = parameter(0.5F);
        final ParameterGroup group = new ParameterGroup() {
            private String name = "New";
            @Override public ParameterGroupId id() { return new ParameterGroupId("GroupNew"); }
            @Override public Optional<String> name() { return Optional.of(name); }
            @Override public Optional<ParameterGroupId> parentId() { return Optional.empty(); }
            @Override public List<ParameterGroupId> childGroupIds() { return List.of(); }
            @Override public List<ParameterId> parameterIds() { return List.of(); }
            @Override public void rename(final String newName) {
                calls.add("group.rename");
                name = newName;
            }
        };

        @Override public ModelId id() { return new ModelId("model-record"); }

        @Override public Parameters parameters() {
            return new Parameters() {
                @Override public List<Parameter> all() { return List.of(parameter); }
                @Override public Parameter find(final ParameterId id) { return parameter; }
                @Override public Parameter create(final ParameterDefinition definition) {
                    calls.add("parameters.create");
                    return parameter;
                }
                @Override public Parameter create(
                    final ParameterDefinition definition,
                    final Optional<ParameterGroupId> folderId
                ) {
                    calls.add("parameters.create.folder");
                    return parameter;
                }
                @Override public Parameter copy(final ParameterId id) {
                    calls.add("parameters.copy");
                    return parameter;
                }
                @Override public void remove(final ParameterId id) {
                    calls.add("parameters.remove");
                }
            };
        }

        @Override public ParameterGroups parameterGroups() {
            return new ParameterGroups() {
                @Override public List<ParameterGroup> all() { return List.of(group); }
                @Override public ParameterGroup root() { return group; }
                @Override public ParameterGroup find(final ParameterGroupId id) {
                    if (!group.id().equals(id)) throw new java.util.NoSuchElementException();
                    return group;
                }
                @Override public ParameterGroup addGroup(final String name) {
                    calls.add("groups.addGroup");
                    return group;
                }
                @Override public void removeGroup(final ParameterGroupId id) {
                    calls.add("groups.removeGroup");
                }
                @Override public void moveParameter(
                    final ParameterId parameterId,
                    final ParameterGroupId targetGroupId
                ) {
                    calls.add("groups.moveParameter");
                }
            };
        }

        @Override public Parts parts() {
            return new Parts() {
                @Override public List<Part> all() { return List.of(part); }
                @Override public Part find(final PartId id) { return part; }
                @Override public Part add(final PartId id) {
                    calls.add("parts.add");
                    return part;
                }
                @Override public Part add(final PartId id, final PartId parentId) {
                    calls.add("parts.add.parent");
                    return part;
                }
                @Override public Part copy(final PartId id) {
                    calls.add("parts.copy");
                    return part;
                }
                @Override public void remove(final PartId id) {
                    calls.add("parts.remove");
                }
            };
        }

        @Override public Drawables drawables() {
            return new Drawables() {
                @Override public List<Drawable> all() { return List.of(drawable); }
                @Override public Drawable find(final ArtMeshId id) { return drawable; }
            };
        }

        @Override public Deformers deformers() { throw unsupported(); }
        @Override public Glues glues() { throw unsupported(); }
        @Override public void update() { throw unsupported(); }

        @Override public ModelProfile profile() {
            calls.add("model.profile");
            return profile;
        }

        @Override public PhysicsSettings physicsSettings() {
            calls.add("model.physicsSettings");
            return physics;
        }

        @Override public AutoYure autoYure() {
            calls.add("model.autoYure");
            return autoYure;
        }

        @Override public List<AnimationDocument> animationDocuments() {
            calls.add("model.animationDocuments");
            return List.of(animation);
        }
    }

    private static ParameterDefinition definition() {
        return new ParameterDefinition(
            new ParameterId("ParamNew"),
            "New",
            0.0F,
            0.0F,
            1.0F,
            ParameterType.NORMAL,
            false
        );
    }

    private static MorphTargets morphTargets() {
        return new MorphTargets() {
            @Override public List<dev.turboism.sdk.cubism.model.MorphTarget> all() {
                return List.of();
            }
            @Override public dev.turboism.sdk.cubism.model.MorphTarget find(final ParameterId id) {
                throw new java.util.NoSuchElementException(id.value());
            }
        };
    }
    @Test
    void nativeLabelColorSeamFollowsTheSessionLeaseAndFailsClosed() {
        final DynamicCubismModelAccess access = new DynamicCubismModelAccess();
        final NativeLabelColorTarget folder = new NativeLabelColorTarget(
            NativeLabelColorTarget.Palette.PARAMETER_GROUP,
            "GroupFace"
        );
        final NativeLabelColorState nativeState = new NativeLabelColorState(
            new NativeLabelColor.Default(),
            Optional.empty()
        );
        access.connect(authoringModelAccess(nativeState));

        assertEquals(nativeState, access.readNativeLabelColor(folder));

        access.deactivate();
        assertThrows(IllegalStateException.class, () -> access.readNativeLabelColor(folder));
        assertThrows(IllegalStateException.class, () -> access.setNativeLabelColor(
            folder, new NativeLabelColor.Default()
        ));

        access.connect(() -> model("model-a", parameter(1.0F), parameterGroups()));
        assertThrows(UnsupportedOperationException.class, () -> access.readNativeLabelColor(folder));
        assertThrows(UnsupportedOperationException.class, () -> access.setNativeLabelColor(
            folder, new NativeLabelColor.Default()
        ));
    }

    private static CubismModelAccess authoringModelAccess(final NativeLabelColorState nativeState) {
        return new AuthoringModelAccess(nativeState);
    }

    private static final class AuthoringModelAccess
        implements CubismModelAccess, NativeLabelColorAuthoring {
        private final CubismModel delegate = model("model-a", parameter(1.0F));
        private final NativeLabelColorState nativeState;

        AuthoringModelAccess(final NativeLabelColorState nativeState) {
            this.nativeState = nativeState;
        }

        @Override public CubismModel active() { return delegate; }

        @Override public NativeLabelColorState readNativeLabelColor(
            final NativeLabelColorTarget target
        ) {
            return nativeState;
        }

        @Override public void setNativeLabelColor(
            final NativeLabelColorTarget target,
            final NativeLabelColor color
        ) {
            throw new AssertionError("unexpected native write");
        }
    }

    @Test
    void sessionWrappersPreserveParameterMetadataAndGuardItByGeneration() {
        final DynamicCubismModelAccess access = new DynamicCubismModelAccess();
        final Parameter delegate = new Parameter() {
            @Override public ParameterId id() { return new ParameterId("ParamCheek"); }
            @Override public int index() { return 7; }
            @Override public dev.turboism.sdk.cubism.model.FloatSequence keyValues() {
                return new dev.turboism.sdk.cubism.model.FloatSequence() {
                    @Override public int size() { return 1; }
                    @Override public float get(final int index) {
                        if (index != 0) throw new IndexOutOfBoundsException(index);
                        return 0.25F;
                    }
                };
            }
            @Override public Optional<String> name() { return Optional.of("Cheek"); }
            @Override public ParameterType type() { return ParameterType.BLEND_SHAPE; }
            @Override public Optional<Boolean> repeat() { return Optional.of(false); }
            @Override public Optional<Boolean> combined() { return Optional.of(true); }
            @Override public Optional<ParameterId> combinedWith() {
                return Optional.of(new ParameterId("ParamSmile"));
            }
            @Override public float getValue() { return 0.5F; }
            @Override public float getMinimumValue() { return 0.0F; }
            @Override public float getMaximumValue() { return 1.0F; }
            @Override public float getDefaultValue() { return 0.0F; }
            @Override public void setValue(final float value) { throw unsupported(); }
            @Override public List<dev.turboism.sdk.cubism.model.ParameterBinding> getParameterBindings() { return List.of(); }
        };
        access.connect(() -> model("model-a", delegate));

        final Parameter parameter = access.active().parameters().find(new ParameterId("ParamCheek"));
        final dev.turboism.sdk.cubism.model.FloatSequence keyValues = parameter.keyValues();
        assertEquals(7, parameter.index());
        assertEquals(1, keyValues.size());
        assertEquals(0.25F, keyValues.get(0));
        assertEquals(Optional.of("Cheek"), parameter.name());
        assertEquals(ParameterType.BLEND_SHAPE, parameter.type());
        assertEquals(Optional.of(false), parameter.repeat());
        assertEquals(Optional.of(true), parameter.combined());
        assertEquals(Optional.of(new ParameterId("ParamSmile")), parameter.combinedWith());
        assertEquals(List.of(), parameter.getParameterBindings());

        access.deactivate();
        assertThrows(IllegalStateException.class, parameter::index);
        assertThrows(IllegalStateException.class, keyValues::size);
        assertThrows(IllegalStateException.class, parameter::name);
        assertThrows(IllegalStateException.class, parameter::type);
        assertThrows(IllegalStateException.class, parameter::repeat);
        assertThrows(IllegalStateException.class, parameter::combined);
        assertThrows(IllegalStateException.class, parameter::combinedWith);
        assertThrows(IllegalStateException.class, parameter::getParameterBindings);
        assertThrows(
            IllegalStateException.class,
            () -> parameter.combineWith(new ParameterId("ParamSmile"))
        );
        assertThrows(IllegalStateException.class, parameter::uncombine);
    }

    @Test
    void resetIsForwardedOnlyWhileTheSessionGenerationIsCurrent() {
        final DynamicCubismModelAccess access = new DynamicCubismModelAccess();
        final float[] value = {0.75F};
        final Parameter delegate = new Parameter() {
            @Override public ParameterId id() { return new ParameterId("ParamA"); }
            @Override public float getValue() { return value[0]; }
            @Override public float getMinimumValue() { return -1.0F; }
            @Override public float getMaximumValue() { return 1.0F; }
            @Override public float getDefaultValue() { return -0.25F; }
            @Override public void setValue(final float nextValue) { value[0] = nextValue; }
        };
        access.connect(() -> model("model-a", delegate));
        final Parameter parameter = access.active().parameters().find(new ParameterId("ParamA"));

        parameter.resetToDefault();
        assertEquals(-0.25F, value[0]);

        value[0] = 0.5F;
        access.connect(modelAccess("model-b", 2.0F));
        assertThrows(IllegalStateException.class, parameter::resetToDefault);
        assertEquals(0.5F, value[0]);
    }

    @Test
    void definitionUpdateIsForwardedOnlyWhileTheSessionGenerationIsCurrent() {
        final DynamicCubismModelAccess access = new DynamicCubismModelAccess();
        final AtomicReference<ParameterDefinition> updated = new AtomicReference<>();
        final Parameter delegate = new Parameter() {
            @Override public ParameterId id() { return new ParameterId("ParamA"); }
            @Override public float getValue() { return 0.0F; }
            @Override public float getMinimumValue() { return -1.0F; }
            @Override public float getMaximumValue() { return 1.0F; }
            @Override public float getDefaultValue() { return 0.0F; }
            @Override public void setValue(final float value) { throw unsupported(); }
            @Override public void updateDefinition(final ParameterDefinition definition) {
                updated.set(definition);
            }
        };
        access.connect(() -> model("model-a", delegate));
        final Parameter parameter = access.active().parameters().find(new ParameterId("ParamA"));
        final ParameterDefinition definition = new ParameterDefinition(
            new ParameterId("ParamRenamed"),
            "Renamed",
            -2.0F,
            0.0F,
            2.0F,
            ParameterType.NORMAL,
            false
        );

        parameter.updateDefinition(definition);
        assertEquals(definition, updated.get());

        access.connect(modelAccess("model-b", 2.0F));
        assertThrows(IllegalStateException.class, () -> parameter.updateDefinition(definition));
        assertEquals(definition, updated.get());
    }

    @Test
    void rapidReplacementAndDeactivationInvalidateEveryPriorGeneration() {
        DynamicCubismModelAccess access = new DynamicCubismModelAccess();
        java.util.List<CubismModel> staleModels = new java.util.ArrayList<>();
        java.util.List<Parameter> staleParameters = new java.util.ArrayList<>();

        for (int generation = 0; generation < 200; generation++) {
            access.connect(modelAccess("model-" + generation, generation));
            CubismModel model = access.active();
            staleModels.add(model);
            staleParameters.add(model.parameters().find(new ParameterId("ParamA")));
            assertEquals(new ModelId("model-" + generation), model.id());
        }
        access.deactivate();

        for (CubismModel model : staleModels) {
            assertThrows(IllegalStateException.class, model::id);
        }
        for (Parameter parameter : staleParameters) {
            assertThrows(IllegalStateException.class, parameter::getValue);
        }
        assertThrows(IllegalStateException.class, access::active);
    }

    @Test
    void sessionWrappersGuardEditorObjectReadsWritesAndFormsByGeneration() {
        final DynamicCubismModelAccess access = new DynamicCubismModelAccess();
        final float[] drawableOpacity = {1.0F};
        final float[] warpOpacity = {1.0F};
        final ArtMeshGeometry[] geometry = {new ArtMeshGeometry(
            List.of(new Point2(0.0F, 0.0F), new Point2(1.0F, 0.0F), new Point2(0.0F, 1.0F)),
            List.of(new Point2(0.0F, 0.0F), new Point2(1.0F, 0.0F), new Point2(0.0F, 1.0F)),
            List.of(0, 1, 2)
        )};
        final WarpGrid[] grid = {new WarpGrid(
            1,
            1,
            false,
            List.of(new Point2(0.0F, 0.0F), new Point2(1.0F, 0.0F),
                new Point2(0.0F, 1.0F), new Point2(1.0F, 1.0F))
        )};
        final RotationDeformerForm[] rotationForm = {
            new RotationDeformerForm(0.0F, 0.0F, 0.0F, 1.0F, false, false)
        };
        final Drawable drawable = new Drawable() {
            @Override public ArtMeshId id() { return new ArtMeshId("ArtMeshA"); }
            @Override public float getOpacity() { return drawableOpacity[0]; }
            @Override public void setOpacity(final float value) { drawableOpacity[0] = value; }
            @Override public ArtMeshGeometry geometry() { return geometry[0]; }
            @Override public void replaceGeometry(final ArtMeshGeometry value) { geometry[0] = value; }
            @Override public byte constantFlag() { return 0; }
            @Override public byte dynamicFlag() { return 0; }
            @Override public dev.turboism.sdk.cubism.model.BlendMode blendMode() {
                return dev.turboism.sdk.cubism.model.BlendMode.NORMAL;
            }
            @Override public int textureIndex() { return 0; }
            @Override public int drawOrder() { return 0; }
            @Override public int renderOrder() { return 0; }
            @Override public Color multiplyColor() { return new Color(1.0F, 1.0F, 1.0F, 1.0F); }
            @Override public Color screenColor() { return new Color(0.0F, 0.0F, 0.0F, 1.0F); }
            @Override public IntSequence masks() { return emptyInts(); }
            @Override public dev.turboism.sdk.cubism.model.FloatSequence vertexPositions() {
                return emptyFloats();
            }
            @Override public dev.turboism.sdk.cubism.model.FloatSequence vertexUvs() {
                return emptyFloats();
            }
            @Override public IntSequence indices() { return emptyInts(); }
            @Override public IntSequence parameters() { return emptyInts(); }
            @Override public int parentDeformerIndex() { return -1; }
            @Override public int parentPartIndex() { return -1; }
        };
        final WarpDeformer warp = new WarpDeformer() {
            @Override public DeformerId id() { return new DeformerId("WarpA"); }
            @Override public float getOpacity() { return warpOpacity[0]; }
            @Override public void setOpacity(final float value) { warpOpacity[0] = value; }
            @Override public WarpGrid grid() { return grid[0]; }
            @Override public void replaceGrid(final WarpGrid value) { grid[0] = value; }
            @Override public int parentDeformerIndex() { return -1; }
            @Override public IntSequence parameters() { return emptyInts(); }
        };
        final RotationDeformer rotation = new RotationDeformer() {
            @Override public DeformerId id() { return new DeformerId("RotationA"); }
            @Override public float baseAngle() { return 30.0F; }
            @Override public RotationDeformerForm form() { return rotationForm[0]; }
            @Override public void replaceForm(final RotationDeformerForm value) {
                rotationForm[0] = value;
            }
            @Override public void setBaseAngle(final float angle) { }
            @Override public int parentDeformerIndex() { return -1; }
            @Override public IntSequence parameters() { return emptyInts(); }
        };
        access.connect(() -> new CubismModel() {
            @Override public ModelId id() { return new ModelId("model-a"); }
            @Override public Parameters parameters() { throw unsupported(); }
            @Override public Parts parts() { throw unsupported(); }
            @Override public Drawables drawables() {
                return new Drawables() {
                    @Override public List<Drawable> all() { return List.of(drawable); }
                    @Override public Drawable find(final ArtMeshId id) { return drawable; }
                };
            }
            @Override public Deformers deformers() { throw unsupported(); }
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
            @Override public Glues glues() { throw unsupported(); }
            @Override public void update() { throw unsupported(); }
        });

        final Drawable wrappedDrawable = access.active().drawables().find(new ArtMeshId("ArtMeshA"));
        final WarpDeformer wrappedWarp = access.active().warpDeformers().find(new DeformerId("WarpA"));
        final RotationDeformer wrappedRotation = access.active().rotationDeformers()
            .find(new DeformerId("RotationA"));
        wrappedDrawable.setOpacity(0.75F);
        wrappedWarp.setOpacity(0.5F);
        wrappedRotation.replaceForm(new RotationDeformerForm(10.0F, 2.0F, 3.0F, 1.5F, true, false));
        assertEquals(0.75F, wrappedDrawable.getOpacity());
        assertEquals(0.5F, wrappedWarp.getOpacity());
        assertEquals(10.0F, wrappedRotation.form().angle());

        access.deactivate();
        assertThrows(IllegalStateException.class, wrappedDrawable::geometry);
        assertThrows(IllegalStateException.class, () -> wrappedDrawable.setOpacity(0.25F));
        assertThrows(IllegalStateException.class, wrappedWarp::grid);
        assertThrows(IllegalStateException.class, () -> wrappedWarp.setOpacity(0.25F));
        assertThrows(IllegalStateException.class, wrappedRotation::form);
        assertThrows(
            IllegalStateException.class,
            () -> wrappedRotation.replaceForm(rotationForm[0])
        );
    }

    @Test
    void replacementDuringDelegateReadCannotReturnAnApparentlyCurrentValue() throws Exception {
        DynamicCubismModelAccess access = new DynamicCubismModelAccess();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        access.connect(() -> model("model-a", new Parameter() {
            @Override public ParameterId id() { return new ParameterId("ParamA"); }
            @Override public float getValue() {
                entered.countDown();
                await(release);
                return 1.0F;
            }
            @Override public float getMinimumValue() { return 0.0F; }
            @Override public float getMaximumValue() { return 2.0F; }
            @Override public float getDefaultValue() { return 1.0F; }
            @Override public void setValue(final float value) { throw unsupported(); }
        }));
        Parameter stale = access.active().parameters().find(new ParameterId("ParamA"));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread reader = new Thread(() -> {
            try {
                stale.getValue();
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });

        reader.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        CountDownLatch replacementFinished = new CountDownLatch(1);
        Thread replacement = new Thread(() -> {
            access.connect(modelAccess("model-b", 2.0F));
            replacementFinished.countDown();
        });
        replacement.start();
        assertTrue(
            !replacementFinished.await(100, TimeUnit.MILLISECONDS),
            "replacement must wait for the in-flight delegate read"
        );
        release.countDown();
        reader.join(5_000);
        replacement.join(5_000);

        assertEquals(null, failure.get());
        assertEquals(new ModelId("model-b"), access.active().id());
    }

    private static CubismModelAccess modelAccess(final String id, final float value) {
        return () -> model(id, parameter(value));
    }

    private static CubismModel model(final String id, final Parameter parameter) {
        return model(id, parameter, null);
    }

    private static CubismModel model(
        final String id,
        final Parameter parameter,
        final ParameterGroups parameterGroups
    ) {
        return new CubismModel() {
            private boolean locked = true;
            @Override public ModelId id() { return new ModelId(id); }
            @Override public boolean defaultKeyformLocked() { return locked; }
            @Override public void setDefaultKeyformLocked(final boolean value) { locked = value; }
            private ModelEditLevel editLevel = ModelEditLevel.LEVEL_1;
            @Override public ModelEditLevel editLevel() { return editLevel; }
            @Override public void setEditLevel(final ModelEditLevel value) { editLevel = value; }
            @Override public Parameters parameters() {
                return new Parameters() {
                    @Override public List<Parameter> all() { return List.of(parameter); }
                    @Override public Parameter find(final ParameterId parameterId) {
                        if (!parameter.id().equals(parameterId)) {
                            throw new java.util.NoSuchElementException();
                        }
                        return parameter;
                    }
                };
            }
            @Override public ParameterGroups parameterGroups() {
                if (parameterGroups == null) return CubismModel.super.parameterGroups();
                return parameterGroups;
            }
            @Override public dev.turboism.sdk.cubism.model.Parts parts() { throw unsupported(); }
            @Override public dev.turboism.sdk.cubism.model.Drawables drawables() { throw unsupported(); }
            @Override public dev.turboism.sdk.cubism.model.Deformers deformers() { throw unsupported(); }
            @Override public dev.turboism.sdk.cubism.model.Glues glues() { throw unsupported(); }
            @Override public void update() { throw unsupported(); }
        };
    }

    private static ParameterGroups parameterGroups() {
        final ParameterGroup root = parameterGroup(
            "GroupRoot",
            "Root",
            Optional.empty(),
            List.of(new ParameterGroupId("GroupFace")),
            List.of()
        );
        final ParameterGroup face = parameterGroup(
            "GroupFace",
            "Face",
            Optional.of(new ParameterGroupId("GroupRoot")),
            List.of(),
            List.of(new ParameterId("ParamA"))
        );
        return new ParameterGroups() {
            @Override public List<ParameterGroup> all() { return List.of(root, face); }
            @Override public ParameterGroup root() { return root; }
            @Override public ParameterGroup find(final ParameterGroupId id) {
                return all().stream().filter(group -> group.id().equals(id)).findFirst()
                    .orElseThrow();
            }
        };
    }
    private static ParameterGroup parameterGroup(
        final String id,
        final String name,
        final Optional<ParameterGroupId> parentId,
        final List<ParameterGroupId> childGroupIds,
        final List<ParameterId> parameterIds
    ) {
        return new ParameterGroup() {
            @Override public ParameterGroupId id() { return new ParameterGroupId(id); }
            @Override public Optional<String> name() { return Optional.of(name); }
            @Override public Optional<ParameterGroupId> parentId() { return parentId; }
            @Override public List<ParameterGroupId> childGroupIds() { return childGroupIds; }
            @Override public List<ParameterId> parameterIds() { return parameterIds; }
        };
    }

    private static Parameter parameter(final float value) {
        return new Parameter() {
            @Override public ParameterId id() { return new ParameterId("ParamA"); }
            @Override public float getValue() { return value; }
            @Override public float getMinimumValue() { return 0.0F; }
            @Override public float getMaximumValue() { return 2.0F; }
            @Override public float getDefaultValue() { return 1.0F; }
            @Override public void setValue(final float ignored) { throw unsupported(); }
        };
    }

    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException();
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

    private static void await(final CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }
}

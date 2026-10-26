package dev.turboism.adapter.host;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.turboism.adapter.cubism.CubismAnimationTestFixtures;
import dev.turboism.adapter.cubism.NativeLabelColorAuthoring;
import dev.turboism.adapter.cubism.NativeLabelColorTarget;
import dev.turboism.adapter.cubism.model.RuntimeModelObjectCreateProvider;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.id.ParameterBindingPointId;
import dev.turboism.sdk.cubism.id.ParameterGroupId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.AnimationAttribute;
import dev.turboism.sdk.cubism.model.AnimationDocument;
import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.AutoYure;
import dev.turboism.sdk.cubism.model.Color;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.sdk.cubism.model.Deformers;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.Drawables;
import dev.turboism.sdk.cubism.model.Glues;
import dev.turboism.sdk.cubism.model.IntSequence;
import dev.turboism.sdk.cubism.model.ModelEditLevel;
import dev.turboism.sdk.cubism.model.ModelObjectCreateRequest;
import dev.turboism.sdk.cubism.model.ModelObjectKind;
import dev.turboism.sdk.cubism.model.ModelObjectReference;
import dev.turboism.sdk.cubism.model.ModelProfile;
import dev.turboism.sdk.cubism.model.MorphTargets;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.cubism.model.ParameterBindingOperations;
import dev.turboism.sdk.cubism.model.ParameterBindingPoint;
import dev.turboism.sdk.cubism.model.ParameterBindingTarget;
import dev.turboism.sdk.cubism.model.ParameterDefinition;
import dev.turboism.sdk.cubism.model.ParameterGroup;
import dev.turboism.sdk.cubism.model.ParameterGroups;
import dev.turboism.sdk.cubism.model.ParameterInterpolation;
import dev.turboism.sdk.cubism.model.ParameterType;
import dev.turboism.sdk.cubism.model.Parameters;
import dev.turboism.sdk.cubism.model.Part;
import dev.turboism.sdk.cubism.model.PartId;
import dev.turboism.sdk.cubism.model.Parts;
import dev.turboism.sdk.cubism.model.PhysicsSettings;
import dev.turboism.sdk.ui.appearance.NativeLabelColor;
import dev.turboism.sdk.ui.appearance.NativeLabelColorState;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Reusable recording doubles for DynamicCubismModelAccessTest contracts. */
final class DynamicCubismModelTestFixtures {
    private DynamicCubismModelTestFixtures() {}

    static CubismModel animationGraphModel(final List<String> calls) {
        return CubismAnimationTestFixtures.animationGraphModel(calls, false);
    }

    static AnimationAttribute animationAttribute(final String id, final List<String> calls) {
        return CubismAnimationTestFixtures.animationAttribute(id, calls, false);
    }

    static AnimationAttribute rawAnimationAttribute(final String id) {
        return animationAttribute(id, new ArrayList<>());
    }

    static CubismModel modelWithDrawable(final Drawable drawable) {
        return new CubismModel() {
            @Override
            public ModelId id() {
                return new ModelId("model-a");
            }

            @Override
            public Parameters parameters() {
                throw unsupported();
            }

            @Override
            public dev.turboism.sdk.cubism.model.Parts parts() {
                throw unsupported();
            }

            @Override
            public dev.turboism.sdk.cubism.model.Drawables drawables() {
                return new dev.turboism.sdk.cubism.model.Drawables() {
                    @Override
                    public List<Drawable> all() {
                        return List.of(drawable);
                    }

                    @Override
                    public Drawable find(final ArtMeshId id) {
                        return drawable;
                    }

                    @Override
                    public Drawable createWithSourceGeometry(
                            final String name,
                            final dev.turboism.sdk.cubism.model.Part parent,
                            final int index,
                            final ArtMeshGeometry geometry,
                            final List<dev.turboism.sdk.cubism.model.Point2> defaultPositions) {
                        assertEquals("Source", name);
                        assertEquals(List.of(), geometry.positions());
                        assertEquals(List.of(), defaultPositions);
                        return drawable;
                    }
                };
            }

            @Override
            public dev.turboism.sdk.cubism.model.Deformers deformers() {
                throw unsupported();
            }

            @Override
            public dev.turboism.sdk.cubism.model.Glues glues() {
                throw unsupported();
            }

            @Override
            public void update() {
                throw unsupported();
            }
        };
    }

    static Drawable drawable(final String guid, final boolean throwGuid, final MorphTargets morphTargets) {
        return new Drawable() {
            @Override
            public ArtMeshId id() {
                return new ArtMeshId("ArtMeshA");
            }

            @Override
            public String guid() {
                if (throwGuid) {
                    throw unsupported();
                }
                return guid;
            }

            @Override
            public IntSequence parameters() {
                return emptyInts();
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
                return new Color(1.0F, 1.0F, 1.0F, 1.0F);
            }

            @Override
            public Color screenColor() {
                return new Color(0.0F, 0.0F, 0.0F, 1.0F);
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
            public MorphTargets morphTargets() {
                return morphTargets;
            }
        };
    }

    static final class RecordingCreateAccess implements CubismModelAccess, RuntimeModelObjectCreateProvider {
        final CubismModel model;
        CubismModel createdAgainst;

        RecordingCreateAccess(final String id) {
            this.model = model(id, parameter(1.0F));
        }

        @Override
        public CubismModel active() {
            return model;
        }

        @Override
        public void requireCreateSupported(final ModelObjectCreateRequest request) {
            assertEquals(ModelObjectKind.PART, request.kind());
        }

        @Override
        public ModelObjectReference createModelObject(
                final CubismModel activeModel, final ModelObjectCreateRequest request) {
            createdAgainst = activeModel;
            return new ModelObjectReference(request.kind(), "PartStable");
        }
    }

    static final class RecordingModel implements CubismModel {
        @Override
        public dev.turboism.sdk.cubism.model.ParameterBindingBatchOperations parameterBindingBatch() {
            return new dev.turboism.sdk.cubism.model.ParameterBindingBatchOperations() {
                public void invert(List<ParameterBindingTarget> targets) {
                    throw new UnsupportedOperationException();
                }

                public void transfer(dev.turboism.sdk.cubism.model.ParameterBindingTransferPlan plan) {
                    throw new UnsupportedOperationException();
                }

                public void setInterpolations(
                        ParameterBindingTarget target, java.util.Map<ParameterId, ParameterInterpolation> settings) {
                    calls.add("interpolation.batch:" + target.id() + ":" + settings.size());
                }
            };
        }

        @Override
        public ParameterBindingOperations parameterBindings(final ParameterId parameterId) {
            return new ParameterBindingOperations() {
                @Override
                public ParameterInterpolation interpolation(ParameterBindingTarget target) {
                    calls.add("interpolation.read:" + target.id());
                    return new ParameterInterpolation(ParameterInterpolation.Method.ELLIPSE, 6, 1);
                }

                @Override
                public void setInterpolation(ParameterBindingTarget target, ParameterInterpolation settings) {
                    calls.add("interpolation.write:" + target.id() + ":" + settings.insertPointCount());
                }

                @Override
                public void bindValues(ParameterBindingTarget target, List<Float> values) {
                    calls.add("binding.values:" + target.id() + ":" + values);
                }

                public void bind(ParameterBindingTarget t, List<ParameterBindingPoint> p) {
                    throw new UnsupportedOperationException();
                }

                public void createPoint(ParameterBindingTarget t, ParameterBindingPoint p) {
                    throw new UnsupportedOperationException();
                }

                public void movePoint(ParameterBindingTarget t, ParameterBindingPointId p, float v) {
                    throw new UnsupportedOperationException();
                }

                public void deletePoint(ParameterBindingTarget t, ParameterBindingPointId p) {
                    throw new UnsupportedOperationException();
                }

                public void unbind(ParameterBindingTarget t) {
                    throw new UnsupportedOperationException();
                }
            };
        }

        final List<String> calls = new java.util.ArrayList<>();
        final ModelProfile profile = new ModelProfile() {
            @Override
            public float pixelsPerUnit() {
                return 1.0F;
            }

            @Override
            public float originXPixels() {
                return 0.0F;
            }

            @Override
            public float originYPixels() {
                return 0.0F;
            }
        };
        final PhysicsSettings physics = new PhysicsSettings() {
            @Override
            public float gravityX() {
                return 0.0F;
            }

            @Override
            public float gravityY() {
                return -1.0F;
            }

            @Override
            public float windX() {
                return 0.0F;
            }

            @Override
            public float windY() {
                return 0.0F;
            }

            @Override
            public Integer settingFps() {
                return 30;
            }

            @Override
            public List<dev.turboism.sdk.cubism.model.PhysicsSettingsSource> sources() {
                return List.of();
            }
        };
        final AutoYure autoYure = new AutoYure() {
            @Override
            public List<dev.turboism.sdk.cubism.model.AutoYureBinding> bindings() {
                return List.of();
            }
        };
        final AnimationDocument animation = new AnimationDocument() {
            @Override
            public String animationName() {
                return "AnimA";
            }

            @Override
            public int sceneCount() {
                return 1;
            }

            @Override
            public Optional<String> currentSceneName() {
                return Optional.empty();
            }

            @Override
            public List<String> sceneNames() {
                return List.of();
            }
        };
        final MorphTargets partTargets = morphTargets();
        final MorphTargets drawableTargets = morphTargets();
        final Part part = new Part() {
            @Override
            public PartId id() {
                return new PartId("PartReturned");
            }

            @Override
            public void setName(final String name) {
                throw unsupported();
            }

            @Override
            public MorphTargets morphTargets() {
                return partTargets;
            }

            @Override
            public void setOpacity(final float opacity) {
                throw unsupported();
            }

            @Override
            public float getOpacity() {
                return 1.0F;
            }

            @Override
            public int parentIndex() {
                return -1;
            }
        };
        final Drawable drawable = drawable("guid-record", false, drawableTargets);
        final Parameter parameter = parameter(0.5F);
        final ParameterGroup group = new ParameterGroup() {
            String name = "New";

            @Override
            public ParameterGroupId id() {
                return new ParameterGroupId("GroupNew");
            }

            @Override
            public Optional<String> name() {
                return Optional.of(name);
            }

            @Override
            public Optional<ParameterGroupId> parentId() {
                return Optional.empty();
            }

            @Override
            public List<ParameterGroupId> childGroupIds() {
                return List.of();
            }

            @Override
            public List<ParameterId> parameterIds() {
                return List.of();
            }

            @Override
            public List<dev.turboism.sdk.cubism.model.ParameterGroupChild> orderedChildren() {
                calls.add("group.orderedChildren");
                return List.of(dev.turboism.sdk.cubism.model.ParameterGroupChild.parameter(new ParameterId("ParamA")));
            }

            @Override
            public void rename(final String newName) {
                calls.add("group.rename");
                name = newName;
            }
        };

        @Override
        public ModelId id() {
            return new ModelId("model-record");
        }

        @Override
        public Parameters parameters() {
            return new Parameters() {
                @Override
                public List<Parameter> all() {
                    return List.of(parameter);
                }

                @Override
                public Parameter find(final ParameterId id) {
                    return parameter;
                }

                @Override
                public Parameter create(final ParameterDefinition definition) {
                    calls.add("parameters.create");
                    return parameter;
                }

                @Override
                public Parameter create(
                        final ParameterDefinition definition, final Optional<ParameterGroupId> folderId) {
                    calls.add("parameters.create.folder");
                    return parameter;
                }

                @Override
                public Parameter copy(final ParameterId id) {
                    calls.add("parameters.copy");
                    return parameter;
                }

                @Override
                public void remove(final ParameterId id) {
                    calls.add("parameters.remove");
                }
            };
        }

        @Override
        public ParameterGroups parameterGroups() {
            return new ParameterGroups() {
                @Override
                public List<ParameterGroup> all() {
                    return List.of(group);
                }

                @Override
                public ParameterGroup root() {
                    return group;
                }

                @Override
                public ParameterGroup find(final ParameterGroupId id) {
                    if (!group.id().equals(id)) throw new java.util.NoSuchElementException();
                    return group;
                }

                @Override
                public ParameterGroup addGroup(final String name) {
                    calls.add("groups.addGroup");
                    return group;
                }

                @Override
                public void removeGroup(final ParameterGroupId id) {
                    calls.add("groups.removeGroup");
                }

                @Override
                public void moveParameter(final ParameterId parameterId, final ParameterGroupId targetGroupId) {
                    calls.add("groups.moveParameter");
                }

                @Override
                public void moveParameter(
                        final ParameterId parameterId, final ParameterGroupId targetGroupId, final int insertIndex) {
                    calls.add("groups.moveParameter:" + insertIndex);
                }
            };
        }

        @Override
        public Parts parts() {
            return new Parts() {
                @Override
                public List<Part> all() {
                    return List.of(part);
                }

                @Override
                public Part find(final PartId id) {
                    return part;
                }

                @Override
                public Part add(final PartId id) {
                    calls.add("parts.add");
                    return part;
                }

                @Override
                public Part add(final PartId id, final PartId parentId) {
                    calls.add("parts.add.parent");
                    return part;
                }

                @Override
                public Part copy(final PartId id) {
                    calls.add("parts.copy");
                    return part;
                }

                @Override
                public void remove(final PartId id) {
                    calls.add("parts.remove");
                }
            };
        }

        @Override
        public Drawables drawables() {
            return new Drawables() {
                @Override
                public List<Drawable> all() {
                    return List.of(drawable);
                }

                @Override
                public Drawable find(final ArtMeshId id) {
                    return drawable;
                }
            };
        }

        @Override
        public Deformers deformers() {
            throw unsupported();
        }

        @Override
        public Glues glues() {
            throw unsupported();
        }

        @Override
        public void update() {
            throw unsupported();
        }

        @Override
        public ModelProfile profile() {
            calls.add("model.profile");
            return profile;
        }

        @Override
        public PhysicsSettings physicsSettings() {
            calls.add("model.physicsSettings");
            return physics;
        }

        @Override
        public AutoYure autoYure() {
            calls.add("model.autoYure");
            return autoYure;
        }

        @Override
        public List<AnimationDocument> animationDocuments() {
            calls.add("model.animationDocuments");
            return List.of(animation);
        }

        dev.turboism.sdk.cubism.model.ModelTextures textures = emptyTextures();

        @Override
        public dev.turboism.sdk.cubism.model.ModelTextures textures() {
            calls.add("model.textures");
            return textures;
        }
    }

    static dev.turboism.sdk.cubism.model.ModelTextures emptyTextures() {
        return new dev.turboism.sdk.cubism.model.ModelTextures() {
            @Override
            public List<dev.turboism.sdk.cubism.model.RawTexture> rawImages() {
                return List.of();
            }

            @Override
            public List<dev.turboism.sdk.cubism.model.ModelImageGroup> modelImageGroups() {
                return List.of();
            }

            @Override
            public List<dev.turboism.sdk.cubism.model.AtlasTexture> textureAtlases() {
                return List.of();
            }

            @Override
            public void addModelImageGroup(final String name) {}

            @Override
            public void removeModelImage(final dev.turboism.sdk.cubism.id.ModelImageId id) {}

            @Override
            public dev.turboism.sdk.cubism.id.TextureAtlasId addTextureAtlas(
                    final String name, final int widthPixels, final int heightPixels) {
                return new dev.turboism.sdk.cubism.id.TextureAtlasId("atlas");
            }

            @Override
            public void removeTextureAtlas(final dev.turboism.sdk.cubism.id.TextureAtlasId id) {}

            @Override
            public void removeRawImage(final dev.turboism.sdk.cubism.id.RawImageId id) {}
        };
    }

    static ParameterDefinition definition() {
        return new ParameterDefinition(
                new ParameterId("ParamNew"), "New", 0.0F, 0.0F, 1.0F, ParameterType.NORMAL, false);
    }

    static MorphTargets morphTargets() {
        return new MorphTargets() {
            @Override
            public List<dev.turboism.sdk.cubism.model.MorphTarget> all() {
                return List.of();
            }

            @Override
            public dev.turboism.sdk.cubism.model.MorphTarget find(final ParameterId id) {
                throw new java.util.NoSuchElementException(id.value());
            }
        };
    }

    static CubismModelAccess authoringModelAccess(final NativeLabelColorState nativeState) {
        return new AuthoringModelAccess(nativeState);
    }

    static final class AuthoringModelAccess implements CubismModelAccess, NativeLabelColorAuthoring {
        final CubismModel delegate = model("model-a", parameter(1.0F));
        final NativeLabelColorState nativeState;

        AuthoringModelAccess(final NativeLabelColorState nativeState) {
            this.nativeState = nativeState;
        }

        @Override
        public CubismModel active() {
            return delegate;
        }

        @Override
        public NativeLabelColorState readNativeLabelColor(final NativeLabelColorTarget target) {
            return nativeState;
        }

        @Override
        public void setNativeLabelColor(final NativeLabelColorTarget target, final NativeLabelColor color) {
            throw new AssertionError("unexpected native write");
        }
    }

    static CubismModelAccess modelAccess(final String id, final float value) {
        return () -> model(id, parameter(value));
    }

    static CubismModel model(final String id, final Parameter parameter) {
        return model(id, parameter, null);
    }

    static CubismModel model(final String id, final Parameter parameter, final ParameterGroups parameterGroups) {
        return new CubismModel() {
            boolean locked = true;

            @Override
            public ModelId id() {
                return new ModelId(id);
            }

            @Override
            public boolean defaultKeyformLocked() {
                return locked;
            }

            @Override
            public void setDefaultKeyformLocked(final boolean value) {
                locked = value;
            }

            ModelEditLevel editLevel = ModelEditLevel.LEVEL_1;

            @Override
            public ModelEditLevel editLevel() {
                return editLevel;
            }

            @Override
            public void setEditLevel(final ModelEditLevel value) {
                editLevel = value;
            }

            @Override
            public Parameters parameters() {
                return new Parameters() {
                    @Override
                    public List<Parameter> all() {
                        return List.of(parameter);
                    }

                    @Override
                    public Parameter find(final ParameterId parameterId) {
                        if (!parameter.id().equals(parameterId)) {
                            throw new java.util.NoSuchElementException();
                        }
                        return parameter;
                    }
                };
            }

            @Override
            public ParameterGroups parameterGroups() {
                if (parameterGroups == null) return CubismModel.super.parameterGroups();
                return parameterGroups;
            }

            @Override
            public dev.turboism.sdk.cubism.model.Parts parts() {
                throw unsupported();
            }

            @Override
            public dev.turboism.sdk.cubism.model.Drawables drawables() {
                throw unsupported();
            }

            @Override
            public dev.turboism.sdk.cubism.model.Deformers deformers() {
                throw unsupported();
            }

            @Override
            public dev.turboism.sdk.cubism.model.Glues glues() {
                throw unsupported();
            }

            @Override
            public void update() {
                throw unsupported();
            }
        };
    }

    static ParameterGroups parameterGroups() {
        final ParameterGroup root = parameterGroup(
                "GroupRoot", "Root", Optional.empty(), List.of(new ParameterGroupId("GroupFace")), List.of());
        final ParameterGroup face = parameterGroup(
                "GroupFace",
                "Face",
                Optional.of(new ParameterGroupId("GroupRoot")),
                List.of(),
                List.of(new ParameterId("ParamA")));
        return new ParameterGroups() {
            @Override
            public List<ParameterGroup> all() {
                return List.of(root, face);
            }

            @Override
            public ParameterGroup root() {
                return root;
            }

            @Override
            public ParameterGroup find(final ParameterGroupId id) {
                return all().stream()
                        .filter(group -> group.id().equals(id))
                        .findFirst()
                        .orElseThrow();
            }
        };
    }

    static ParameterGroup parameterGroup(
            final String id,
            final String name,
            final Optional<ParameterGroupId> parentId,
            final List<ParameterGroupId> childGroupIds,
            final List<ParameterId> parameterIds) {
        return new ParameterGroup() {
            @Override
            public ParameterGroupId id() {
                return new ParameterGroupId(id);
            }

            @Override
            public Optional<String> name() {
                return Optional.of(name);
            }

            @Override
            public Optional<ParameterGroupId> parentId() {
                return parentId;
            }

            @Override
            public List<ParameterGroupId> childGroupIds() {
                return childGroupIds;
            }

            @Override
            public List<ParameterId> parameterIds() {
                return parameterIds;
            }
        };
    }

    static Parameter parameter(final float value) {
        return new Parameter() {
            @Override
            public ParameterId id() {
                return new ParameterId("ParamA");
            }

            @Override
            public float getValue() {
                return value;
            }

            @Override
            public float getMinimumValue() {
                return 0.0F;
            }

            @Override
            public float getMaximumValue() {
                return 2.0F;
            }

            @Override
            public float getDefaultValue() {
                return 1.0F;
            }

            @Override
            public void setValue(final float ignored) {
                throw unsupported();
            }
        };
    }

    static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException();
    }

    static IntSequence emptyInts() {
        return new IntSequence() {
            @Override
            public int size() {
                return 0;
            }

            @Override
            public int get(final int index) {
                throw new IndexOutOfBoundsException(index);
            }
        };
    }

    static dev.turboism.sdk.cubism.model.FloatSequence emptyFloats() {
        return new dev.turboism.sdk.cubism.model.FloatSequence() {
            @Override
            public int size() {
                return 0;
            }

            @Override
            public float get(final int index) {
                throw new IndexOutOfBoundsException(index);
            }
        };
    }

    static void await(final CountDownLatch latch) {
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

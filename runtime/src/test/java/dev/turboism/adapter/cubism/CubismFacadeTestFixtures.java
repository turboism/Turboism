package dev.turboism.adapter.cubism;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.turboism.permissions.CubismPermissionGate;
import dev.turboism.sdk.cubism.DeformerType;
import dev.turboism.sdk.cubism.clipmask.ClipMaskReplacement;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DeformerId;
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
import dev.turboism.sdk.cubism.model.Deformer;
import dev.turboism.sdk.cubism.model.Deformers;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.Drawables;
import dev.turboism.sdk.cubism.model.Glues;
import dev.turboism.sdk.cubism.model.IntSequence;
import dev.turboism.sdk.cubism.model.ModelProfile;
import dev.turboism.sdk.cubism.model.MorphTarget;
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
import dev.turboism.sdk.cubism.model.PhysicsSettingsSource;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.cubism.model.RotationDeformer;
import dev.turboism.sdk.cubism.model.RotationDeformerForm;
import dev.turboism.sdk.cubism.model.RotationDeformers;
import dev.turboism.sdk.cubism.model.WarpDeformer;
import dev.turboism.sdk.cubism.model.WarpDeformers;
import dev.turboism.sdk.cubism.model.WarpGrid;
import dev.turboism.sdk.permission.PluginPermission;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Shared recording doubles for Cubism facade and permission-forwarding contracts. */
final class CubismFacadeTestFixtures {
    private CubismFacadeTestFixtures() {}

    static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-07-07T00:00:00Z"), ZoneOffset.UTC);

    static AnimationAttribute deepAnimationAttribute(final CubismModel model) {
        return model.animationDocuments()
                .get(0)
                .scenes()
                .get(0)
                .tracks()
                .get(1)
                .children()
                .get(0)
                .attributes()
                .get(0);
    }

    static CubismModel animationGraphModel(final List<String> calls) {
        return CubismAnimationTestFixtures.animationGraphModel(calls, true);
    }

    static AnimationAttribute animationAttribute(final String id, final List<String> calls) {
        return CubismAnimationTestFixtures.animationAttribute(id, calls, true);
    }

    static AnimationAttribute foreignAnimationAttribute(final String id) {
        return animationAttribute(id, new ArrayList<>());
    }

    static CubismModel modelWithParameter(final float[] value) {
        return modelWithParameter(value, new ParameterDefinition[] {null});
    }

    static CubismModel modelWithCombinedCalls(final List<String> calls) {
        return new CubismModel() {
            final dev.turboism.sdk.cubism.model.Parameter parameter = new dev.turboism.sdk.cubism.model.Parameter() {
                @Override
                public ParameterId id() {
                    return new ParameterId("ParamA");
                }

                @Override
                public float getValue() {
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
                public void setValue(final float value) {}

                @Override
                public void combineWith(final ParameterId partnerId) {
                    calls.add("combine:" + partnerId.value());
                }

                @Override
                public void uncombine() {
                    calls.add("uncombine");
                }
            };

            @Override
            public ModelId id() {
                return new ModelId("model-a");
            }

            @Override
            public dev.turboism.sdk.cubism.model.Parameters parameters() {
                return new dev.turboism.sdk.cubism.model.Parameters() {
                    @Override
                    public List<dev.turboism.sdk.cubism.model.Parameter> all() {
                        return List.of(parameter);
                    }

                    @Override
                    public dev.turboism.sdk.cubism.model.Parameter find(final ParameterId id) {
                        return parameter;
                    }
                };
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
                throw new UnsupportedOperationException();
            }

            UnsupportedOperationException unsupported() {
                return new UnsupportedOperationException();
            }
        };
    }

    static CubismModel modelWithParameter(final float[] value, final ParameterDefinition[] updated) {
        return new CubismModel() {
            final dev.turboism.sdk.cubism.model.Parameter parameter = new dev.turboism.sdk.cubism.model.Parameter() {
                @Override
                public ParameterId id() {
                    if (updated[0] != null) {
                        throw new IllegalStateException("parameter identity changed");
                    }
                    return new ParameterId("ParamA");
                }

                @Override
                public int index() {
                    return 3;
                }

                @Override
                public dev.turboism.sdk.cubism.model.FloatSequence keyValues() {
                    return emptyFloats();
                }

                @Override
                public Optional<String> name() {
                    return Optional.of("Parameter A");
                }

                @Override
                public ParameterType type() {
                    return ParameterType.BLEND_SHAPE;
                }

                @Override
                public Optional<Boolean> repeat() {
                    return Optional.of(false);
                }

                @Override
                public Optional<Boolean> combined() {
                    return Optional.of(true);
                }

                @Override
                public Optional<ParameterId> combinedWith() {
                    return Optional.of(new ParameterId("ParamB"));
                }

                @Override
                public List<dev.turboism.sdk.cubism.model.ParameterBinding> getParameterBindings() {
                    return List.of();
                }

                @Override
                public float getValue() {
                    return value[0];
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
                public void setValue(final float next) {
                    value[0] = next;
                }

                @Override
                public void updateDefinition(final ParameterDefinition definition) {
                    updated[0] = definition;
                }
            };

            @Override
            public ModelId id() {
                return new ModelId("model-a");
            }

            @Override
            public dev.turboism.sdk.cubism.model.Parameters parameters() {
                return new dev.turboism.sdk.cubism.model.Parameters() {
                    @Override
                    public List<dev.turboism.sdk.cubism.model.Parameter> all() {
                        return List.of(parameter);
                    }

                    @Override
                    public dev.turboism.sdk.cubism.model.Parameter find(final ParameterId id) {
                        if (!parameter.id().equals(id)) throw new java.util.NoSuchElementException();
                        return parameter;
                    }
                };
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

            UnsupportedOperationException unsupported() {
                return new UnsupportedOperationException();
            }
        };
    }

    static CubismModel editorObjectModel(
            final Drawable drawable, final WarpDeformer warp, final RotationDeformer rotation) {
        return new CubismModel() {
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
                return new Deformers() {
                    @Override
                    public List<Deformer> all() {
                        return List.of(warp, rotation);
                    }

                    @Override
                    public Deformer find(final DeformerId id) {
                        if (warp.id().equals(id)) return warp;
                        if (rotation.id().equals(id)) return rotation;
                        throw new java.util.NoSuchElementException(id.value());
                    }
                };
            }

            @Override
            public WarpDeformers warpDeformers() {
                return new WarpDeformers() {
                    @Override
                    public List<WarpDeformer> all() {
                        return List.of(warp);
                    }

                    @Override
                    public WarpDeformer find(final DeformerId id) {
                        return warp;
                    }
                };
            }

            @Override
            public RotationDeformers rotationDeformers() {
                return new RotationDeformers() {
                    @Override
                    public List<RotationDeformer> all() {
                        return List.of(rotation);
                    }

                    @Override
                    public RotationDeformer find(final DeformerId id) {
                        return rotation;
                    }
                };
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

    static CubismModel emptyModel(final String id) {
        return emptyModel(id, ignored -> {});
    }

    static CubismModel emptyModel(
            final String id, final java.util.function.Consumer<List<ClipMaskReplacement>> clipMaskWriter) {
        return new CubismModel() {
            @Override
            public ModelId id() {
                return new ModelId(id);
            }

            @Override
            public dev.turboism.sdk.cubism.model.Parameters parameters() {
                throw unsupported();
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

            @Override
            public void replaceArtMeshClipMasks(final List<ClipMaskReplacement> replacements) {
                clipMaskWriter.accept(List.copyOf(replacements));
            }

            UnsupportedOperationException unsupported() {
                return new UnsupportedOperationException();
            }
        };
    }

    static PluginPermission permission(final String id) {
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

    static PluginDescriptor descriptor(final String id) {
        return new PluginDescriptor() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String name() {
                return id;
            }

            @Override
            public String version() {
                return "1.0.0";
            }

            @Override
            public String description() {
                return "test";
            }

            @Override
            public List<String> entrypoints() {
                return List.of();
            }

            @Override
            public String turboismApi() {
                return "[0.67.0,1.0.0)";
            }

            @Override
            public List<Author> authors() {
                return List.of();
            }

            @Override
            public String license() {
                return "UNLICENSED";
            }

            @Override
            public Optional<String> website() {
                return Optional.empty();
            }

            @Override
            public List<String> resources() {
                return List.of();
            }

            @Override
            public I18n i18n() {
                return new I18n() {
                    @Override
                    public String baseName() {
                        return "messages";
                    }

                    @Override
                    public List<String> locales() {
                        return List.of();
                    }
                };
            }

            @Override
            public List<DependencyRef> dependencies() {
                return List.of();
            }

            @Override
            public List<PermissionRef> permissions() {
                return List.of();
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

    static class MockDrawable implements Drawable {
        final ArtMeshId id;
        float opacity = 1.0F;
        Runnable onMutate;
        final ArtMeshGeometry geometry = new ArtMeshGeometry(
                List.of(new Point2(0, 0), new Point2(1, 0), new Point2(0, 1)),
                List.of(new Point2(0, 0), new Point2(1, 0), new Point2(0, 1)),
                List.of(0, 1, 2));

        MockDrawable(String id) {
            this(id, () -> {});
        }

        MockDrawable(String id, Runnable onMutate) {
            this.id = new ArtMeshId(id);
            this.onMutate = onMutate;
        }

        @Override
        public ArtMeshId id() {
            return id;
        }

        @Override
        public void setOpacity(float opacity) {
            this.opacity = opacity;
            onMutate.run();
        }

        @Override
        public float getOpacity() {
            return opacity;
        }

        @Override
        public ArtMeshGeometry geometry() {
            return geometry;
        }

        @Override
        public void replaceGeometry(ArtMeshGeometry g) {
            onMutate.run();
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
    }

    static class MockWarpDeformer implements WarpDeformer {
        final DeformerId id;
        float opacity = 1.0F;
        boolean visible = true;
        boolean locked = false;
        Runnable onMutate;
        WarpGrid grid = new WarpGrid(
                1, 1, false, List.of(new Point2(0, 0), new Point2(1, 0), new Point2(0, 1), new Point2(1, 1)));

        MockWarpDeformer(String id) {
            this(id, () -> {});
        }

        MockWarpDeformer(String id, Runnable onMutate) {
            this.id = new DeformerId(id);
            this.onMutate = onMutate;
        }

        @Override
        public DeformerId id() {
            return id;
        }

        @Override
        public boolean visible() {
            return visible;
        }

        @Override
        public void setVisible(boolean value) {
            this.visible = value;
            onMutate.run();
        }

        @Override
        public boolean locked() {
            return locked;
        }

        @Override
        public void setLocked(boolean value) {
            this.locked = value;
            onMutate.run();
        }

        @Override
        public void setOpacity(float opacity) {
            this.opacity = opacity;
            onMutate.run();
        }

        @Override
        public float getOpacity() {
            return opacity;
        }

        @Override
        public void replaceGrid(WarpGrid grid) {
            this.grid = grid;
            onMutate.run();
        }

        @Override
        public WarpGrid grid() {
            return grid;
        }

        @Override
        public int parentDeformerIndex() {
            return -1;
        }

        @Override
        public IntSequence parameters() {
            return emptyInts();
        }
    }

    static class MockRotationDeformer implements RotationDeformer {
        final DeformerId id;
        float baseAngle = 0.0F;
        float opacity = 1.0F;
        boolean visible = true;
        boolean locked = false;
        Runnable onMutate;
        RotationDeformerForm form = new RotationDeformerForm(0, 0, 0, 1, false, false);

        MockRotationDeformer(String id) {
            this(id, () -> {});
        }

        MockRotationDeformer(String id, Runnable onMutate) {
            this.id = new DeformerId(id);
            this.onMutate = onMutate;
        }

        @Override
        public DeformerId id() {
            return id;
        }

        @Override
        public boolean visible() {
            return visible;
        }

        @Override
        public void setVisible(boolean value) {
            this.visible = value;
            onMutate.run();
        }

        @Override
        public boolean locked() {
            return locked;
        }

        @Override
        public void setLocked(boolean value) {
            this.locked = value;
            onMutate.run();
        }

        @Override
        public float getOpacity() {
            return opacity;
        }

        @Override
        public void setOpacity(float value) {
            this.opacity = value;
            onMutate.run();
        }

        @Override
        public float baseAngle() {
            return baseAngle;
        }

        @Override
        public void setBaseAngle(float angle) {
            this.baseAngle = angle;
            onMutate.run();
        }

        @Override
        public RotationDeformerForm form() {
            return form;
        }

        @Override
        public void replaceForm(RotationDeformerForm form) {
            this.form = form;
            onMutate.run();
        }

        @Override
        public int parentDeformerIndex() {
            return -1;
        }

        @Override
        public IntSequence parameters() {
            return emptyInts();
        }
    }

    static PluginLogger logger() {
        return new PluginLogger() {
            @Override
            public void debug(final String message) {}

            @Override
            public void info(final String message) {}

            @Override
            public void warn(final String message) {}

            @Override
            public void error(final String message) {}

            @Override
            public void error(final String message, final Throwable throwable) {}
        };
    }

    static HostSnapshotSource sampleSource() {
        final HostSnapshotSource.HostModel model = new HostSnapshotSource.HostModel(
                "model-1",
                "Model",
                List.of(new HostSnapshotSource.HostParameter("param-1", "Param", 1.0, 0.0, -1.0, 1.0, true, true)),
                List.of(new HostSnapshotSource.HostArtMesh("mesh-1", "Mesh", Optional.of("texture-1"), true, true)),
                List.of(new HostSnapshotSource.HostDeformer(
                        "deformer-1", "Deformer", DeformerType.ROOT, Optional.empty(), List.of())));
        final HostSnapshotSource.HostDocument document = new HostSnapshotSource.HostDocument(
                "document-1",
                "Document",
                "models/demo/model.cdi3.json",
                Optional.of(Path.of("models/demo/model.cdi3.json")),
                Optional.of(model));
        final HostSnapshotSource.HostProject project = new HostSnapshotSource.HostProject(
                "project-1", "Project", Optional.of(Path.of("projects/demo")), List.of(document));
        return new StubHostSnapshotSource(
                Optional.of(project),
                Optional.of(document),
                Optional.of(model),
                new HostSnapshotSource.HostSelection(
                        List.of("param-1"), Optional.of("param-1"), Optional.empty(), Optional.empty()),
                true);
    }

    static HostSnapshotSource emptySource() {
        return new StubHostSnapshotSource(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                new HostSnapshotSource.HostSelection(List.of(), Optional.empty(), Optional.empty(), Optional.empty()),
                false);
    }

    record StubHostSnapshotSource(
            Optional<HostProject> project,
            Optional<HostDocument> document,
            Optional<HostModel> model,
            HostSelection selection,
            boolean hostPresent)
            implements HostSnapshotSource {
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

    static final ModelProfile PROFILE = new ModelProfile() {
        @Override
        public float pixelsPerUnit() {
            return 1.0F;
        }

        @Override
        public float originXPixels() {
            return 2.0F;
        }

        @Override
        public float originYPixels() {
            return 3.0F;
        }
    };

    static final PhysicsSettings PHYSICS = new PhysicsSettings() {
        @Override
        public float gravityX() {
            return 0.0F;
        }

        @Override
        public float gravityY() {
            return 1.0F;
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
        public List<PhysicsSettingsSource> sources() {
            return List.of();
        }
    };

    static final AutoYure AUTO_YURE = () -> List.of();

    static final AnimationDocument ANIMATION = new AnimationDocument() {
        @Override
        public String animationName() {
            return "anim-1";
        }

        @Override
        public int sceneCount() {
            return 1;
        }

        @Override
        public Optional<String> currentSceneName() {
            return Optional.of("scene-1");
        }

        @Override
        public List<String> sceneNames() {
            return List.of("scene-1");
        }
    };

    static final MorphTargets EMPTY_MORPH_TARGETS = new MorphTargets() {
        @Override
        public List<MorphTarget> all() {
            return List.of();
        }

        @Override
        public MorphTarget find(final ParameterId id) {
            throw new java.util.NoSuchElementException();
        }
    };

    static final ParameterDefinition DEFINITION = new ParameterDefinition(
            new ParameterId("ParamNew"), "Param New", 0.0F, 0.5F, 1.0F, ParameterType.NORMAL, false);

    static final ParameterId PARAMETER_ID = new ParameterId("ParamA");
    static final dev.turboism.sdk.cubism.model.ModelTextures TEXTURES =
            new dev.turboism.sdk.cubism.model.ModelTextures() {
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
    static final ParameterGroupId GROUP_ID = new ParameterGroupId("GroupA");
    static final PartId PART_ID = new PartId("PartA");
    static final ArtMeshId MESH_ID = new ArtMeshId("ArtMeshA");
    static final dev.turboism.sdk.cubism.id.ModelImageId MODEL_IMAGE_ID =
            new dev.turboism.sdk.cubism.id.ModelImageId("image-a");
    static final dev.turboism.sdk.cubism.model.CubismRenderedPixels PIXELS =
            new dev.turboism.sdk.cubism.model.CubismRenderedPixels(2, 1, new int[] {1, 2});
    static final dev.turboism.sdk.cubism.model.ModelImageBinding BINDING =
            new dev.turboism.sdk.cubism.model.ModelImageBinding(
                    "plugin.demo",
                    new ModelId("model-a"),
                    1L,
                    new dev.turboism.sdk.cubism.id.RawImageId("raw-a"),
                    MODEL_IMAGE_ID,
                    Optional.of(MESH_ID));

    static final dev.turboism.sdk.cubism.model.CubismMocExportSettings EXPORT_SETTINGS =
            new dev.turboism.sdk.cubism.model.CubismMocExportSettings(0, 0, 100, false, false, false, false, false);
    static final dev.turboism.sdk.cubism.model.CubismMocExport EXPORT = exportFixture();

    static dev.turboism.sdk.cubism.model.CubismMocExport exportFixture() {
        byte[] bytes = new byte[64];
        bytes[0] = 'M';
        bytes[1] = 'O';
        bytes[2] = 'C';
        bytes[3] = '3';
        return new dev.turboism.sdk.cubism.model.CubismMocExport(bytes, List.of(), EXPORT_SETTINGS);
    }

    static final dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot AUTHORING =
            new dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot(
                    new ArtMeshId("ArtMeshA"),
                    "source-guid",
                    new dev.turboism.sdk.cubism.model.ArtMeshGeometry(List.of(), List.of(), List.of()),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of());
    static final dev.turboism.sdk.cubism.model.ArtMeshEvaluationSnapshot EVALUATION =
            new dev.turboism.sdk.cubism.model.ArtMeshEvaluationSnapshot(
                    new ArtMeshId("ArtMeshA"),
                    "source-guid",
                    java.util.Optional.empty(),
                    new dev.turboism.sdk.cubism.model.ArtMeshEvaluationSnapshot.ParameterState(7, List.of()),
                    new dev.turboism.sdk.cubism.model.ArtMeshEvaluationSnapshot.ParameterState(13, List.of()),
                    -1);

    static CubismFacadeImpl facade(final RecordingModel delegate, final List<PluginPermission> permissions) {
        return facade(delegate, permissions, () -> true);
    }

    static CubismFacadeImpl facade(
            final RecordingModel delegate,
            final List<PluginPermission> permissions,
            final java.util.function.BooleanSupplier activeScope) {
        return facade(
                delegate,
                permissions,
                activeScope,
                new dev.turboism.adapter.cubism.lifecycle.EditorObjectLifecycleCoordinator());
    }

    static CubismFacadeImpl facade(
            final RecordingModel delegate,
            final List<PluginPermission> permissions,
            final java.util.function.BooleanSupplier activeScope,
            final dev.turboism.adapter.cubism.lifecycle.EditorObjectLifecycleCoordinator lifecycle) {
        return new CubismFacadeImpl(
                emptySource(),
                new CubismPermissionGate("plugin.demo", permissions, ignored -> {}, FIXED_CLOCK),
                (CubismModelAccess) () -> delegate,
                new dev.turboism.adapter.cubism.lifecycle.ParameterLifecycleCoordinator(),
                new dev.turboism.adapter.cubism.lifecycle.PartLifecycleCoordinator(),
                new dev.turboism.adapter.cubism.textureatlas.TextureAtlasLayoutCoordinator(),
                lifecycle,
                activeScope);
    }

    static CubismFacadeImpl facade(final List<PluginPermission> permissions, final List<String> calls) {
        return facade(new RecordingModel(calls), permissions);
    }

    static PluginPermission readPermission(final boolean[] granted) {
        return revocable(CubismFacadeImpl.MODEL_READ_PERMISSION, granted);
    }

    static PluginPermission readPermission(final boolean granted) {
        return revocable(CubismFacadeImpl.MODEL_READ_PERMISSION, new boolean[] {granted});
    }

    static PluginPermission writePermission(final boolean[] granted) {
        return revocable(CubismFacadeImpl.MODEL_WRITE_PERMISSION, granted);
    }

    static PluginPermission writePermission(final boolean granted) {
        return revocable(CubismFacadeImpl.MODEL_WRITE_PERMISSION, new boolean[] {granted});
    }

    static PluginPermission revocable(final String id, final boolean[] granted) {
        return new PluginPermission() {
            @Override
            public String id() {
                return granted[0] ? id : "revoked";
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

    static dev.turboism.sdk.cubism.model.ModelTextures recordingTextures(final List<String> calls) {
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

            @Override
            public dev.turboism.sdk.cubism.model.ModelImageBinding registerImageAndBind(
                    final dev.turboism.sdk.cubism.model.AuthorizedImageContent image, final ArtMeshId target) {
                calls.add("registerImageAndBind:" + target.value());
                return BINDING;
            }

            @Override
            public dev.turboism.sdk.cubism.model.ModelImageBinding bindModelImage(
                    final dev.turboism.sdk.cubism.id.ModelImageId image, final ArtMeshId target) {
                calls.add("bindModelImage:" + target.value());
                return BINDING;
            }
        };
    }

    static dev.turboism.sdk.cubism.model.AuthorizedImageContent imageGrant() {
        final byte[] png = new byte[33];
        final byte[] signature = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(signature, 0, png, 0, signature.length);
        png[11] = 13;
        png[12] = 'I';
        png[13] = 'H';
        png[14] = 'D';
        png[15] = 'R';
        png[18] = 1;
        png[23] = (byte) 128;
        return dev.turboism.sdk.cubism.model.AuthorizedImageContent.of(png, "forwarding.png");
    }

    /** Delegate model recording every Wave 1 method invocation into {@code calls}. */
    static final class RecordingModel implements CubismModel {
        List<Drawables.SourceGeometryRequest> batchMeshRequests;
        List<Object> initializedWarpArgs;

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

        boolean bindingProjectionAvailable;
        final List<String> calls;
        boolean failKeyformWrite;
        final Parameter parameter = new Parameter() {
            @Override
            public ParameterId id() {
                return PARAMETER_ID;
            }

            @Override
            public String guid() {
                calls.add("parameter.guid");
                return "native-parameter-guid";
            }

            @Override
            public float getValue() {
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
            public List<dev.turboism.sdk.cubism.model.ParameterBinding> getParameterBindings() {
                if (!bindingProjectionAvailable)
                    throw new UnsupportedOperationException("Unavailable fixture binding projection");
                return List.of();
            }

            @Override
            public void setValue(final float value) {}
        };
        final ParameterGroup group = new ParameterGroup() {
            @Override
            public ParameterGroupId id() {
                return GROUP_ID;
            }

            @Override
            public Optional<String> name() {
                return Optional.of("Group A");
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
                calls.add("orderedChildren");
                return List.of(dev.turboism.sdk.cubism.model.ParameterGroupChild.parameter(PARAMETER_ID));
            }

            @Override
            public void rename(final String name) {
                calls.add("rename:" + name);
            }
        };
        final Part part = new Part() {
            @Override
            public PartId id() {
                return PART_ID;
            }

            @Override
            public void setName(final String name) {}

            @Override
            public float getOpacity() {
                return 1.0F;
            }

            @Override
            public int parentIndex() {
                return 0;
            }

            @Override
            public void setOpacity(final float opacity) {}

            @Override
            public MorphTargets morphTargets() {
                calls.add("part.morphTargets");
                return EMPTY_MORPH_TARGETS;
            }
        };
        final Drawable drawable = new Drawable() {
            @Override
            public ArtMeshId id() {
                return new ArtMeshId("ArtMeshA");
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
            public dev.turboism.sdk.cubism.model.Color multiplyColor() {
                return new dev.turboism.sdk.cubism.model.Color(1, 1, 1, 1);
            }

            @Override
            public dev.turboism.sdk.cubism.model.Color screenColor() {
                return new dev.turboism.sdk.cubism.model.Color(0, 0, 0, 1);
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
            public MorphTargets morphTargets() {
                calls.add("drawable.morphTargets");
                return EMPTY_MORPH_TARGETS;
            }

            final List<dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.MorphKey> createdMorphs =
                    new ArrayList<>();

            @Override
            public dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot authoringSnapshot() {
                calls.add("drawable.authoringSnapshot");
                if (createdMorphs.isEmpty()) return AUTHORING;
                return new dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot(
                        AUTHORING.artMeshId(),
                        AUTHORING.sourceGuid(),
                        AUTHORING.sourceGeometry(),
                        List.of(),
                        List.of(),
                        createdMorphs.stream()
                                .map(k -> new dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.Form(
                                        k.formGuid(), List.of(), 500))
                                .toList(),
                        createdMorphs);
            }

            @Override
            public dev.turboism.sdk.cubism.model.ArtMeshEvaluationSnapshot evaluationSnapshot() {
                calls.add("drawable.evaluationSnapshot");
                return EVALUATION;
            }

            @Override
            public void setOrdinaryKeyformValues(
                    dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot expected,
                    List<dev.turboism.sdk.cubism.model.ArtMeshKeyformValues> edits) {
                calls.add("drawable.keyformSparseValuesBatch");
            }

            @Override
            public void setOrdinaryKeyforms(
                    dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot expected,
                    List<dev.turboism.sdk.cubism.model.ArtMeshKeyformPositions> positions,
                    List<dev.turboism.sdk.cubism.model.ArtMeshKeyformDrawOrder> orders) {
                calls.add("drawable.keyformValuesBatch");
            }

            @Override
            public void setOrdinaryKeyformPositions(
                    final dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot expected,
                    final List<dev.turboism.sdk.cubism.model.ArtMeshKeyformPositions> edits) {
                calls.add("drawable.keyformBatch");
            }

            @Override
            public dev.turboism.sdk.cubism.model.ArtMeshKeyform ordinaryKeyform(
                    final List<dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.Coordinate> coordinates) {
                calls.add("drawable.ordinaryKeyform");
                return new dev.turboism.sdk.cubism.model.ArtMeshKeyform() {
                    List<dev.turboism.sdk.cubism.model.Point2> positions = List.of();
                    int order = 500;

                    @Override
                    public List<dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.Coordinate> coordinates() {
                        calls.add("keyform.coordinates");
                        return List.copyOf(coordinates);
                    }

                    @Override
                    public dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.Form snapshot() {
                        calls.add("keyform.snapshot");
                        return new dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.Form(
                                "form-guid", positions, order);
                    }

                    @Override
                    public boolean defaultKeyform() {
                        calls.add("keyform.default");
                        return false;
                    }

                    @Override
                    public void setPositions(final List<dev.turboism.sdk.cubism.model.Point2> value) {
                        calls.add("keyform.setPositions");
                        if (failKeyformWrite) throw new IllegalStateException("keyform write failed");
                        positions = List.copyOf(value);
                    }

                    @Override
                    public void setDrawOrder(final int value) {
                        calls.add("keyform.setDrawOrder");
                        if (failKeyformWrite) throw new IllegalStateException("keyform write failed");
                        order = value;
                    }
                };
            }

            @Override
            public dev.turboism.sdk.cubism.model.ArtMeshMorphKeyform morphKeyform(final String guid, final float key) {
                calls.add("drawable.morphKeyform");
                return new dev.turboism.sdk.cubism.model.ArtMeshMorphKeyform() {
                    List<dev.turboism.sdk.cubism.model.Point2> positions = List.of();
                    int order = 500;

                    @Override
                    public String parameterGuid() {
                        calls.add("morph.parameterGuid");
                        return guid;
                    }

                    @Override
                    public float keyValue() {
                        calls.add("morph.keyValue");
                        return key;
                    }

                    @Override
                    public dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.Form snapshot() {
                        calls.add("morph.snapshot");
                        return new dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.Form(
                                "morph-form", positions, order);
                    }

                    @Override
                    public dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.Form baseSnapshot() {
                        calls.add("morph.baseSnapshot");
                        return new dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.Form("base", List.of(), 500);
                    }

                    @Override
                    public void setPositions(final List<dev.turboism.sdk.cubism.model.Point2> value) {
                        calls.add("morph.setPositions");
                        if (failKeyformWrite) throw new IllegalStateException("morph write failed");
                        positions = List.copyOf(value);
                    }

                    @Override
                    public void setDrawOrder(final int value) {
                        calls.add("morph.setDrawOrder");
                        if (failKeyformWrite) throw new IllegalStateException("morph write failed");
                        order = value;
                    }
                };
            }

            @Override
            public dev.turboism.sdk.cubism.model.ArtMeshMorphKeyform createMorphKeyform(
                    final String guid, final float key) {
                calls.add("drawable.createMorphKeyform");
                if (failKeyformWrite) throw new IllegalStateException("morph create failed");
                createdMorphs.add(new dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.MorphKey(
                        guid, "created-" + createdMorphs.size(), Optional.of(key)));
                return morphKeyform(guid, key);
            }
        };

        RecordingModel(final List<String> calls) {
            this.calls = calls;
        }

        Parameter parameter() {
            return parameter;
        }

        ParameterGroup group() {
            return group;
        }

        Part part() {
            return part;
        }

        @Override
        public ModelId id() {
            return new ModelId("model-a");
        }

        @Override
        public ModelProfile profile() {
            calls.add("profile");
            return PROFILE;
        }

        @Override
        public PhysicsSettings physicsSettings() {
            calls.add("physicsSettings");
            return PHYSICS;
        }

        @Override
        public AutoYure autoYure() {
            calls.add("autoYure");
            return AUTO_YURE;
        }

        @Override
        public List<AnimationDocument> animationDocuments() {
            calls.add("animationDocuments");
            return List.of(ANIMATION);
        }

        dev.turboism.sdk.cubism.model.ModelTextures textures = TEXTURES;

        RecordingModel withTextures(final dev.turboism.sdk.cubism.model.ModelTextures value) {
            textures = value;
            return this;
        }

        @Override
        public dev.turboism.sdk.cubism.model.ModelTextures textures() {
            calls.add("textures");
            return textures;
        }

        @Override
        public dev.turboism.sdk.cubism.model.CubismMocExport exportMoc(
                final dev.turboism.sdk.cubism.model.CubismMocExportSettings settings) {
            assertSame(EXPORT_SETTINGS, settings);
            calls.add("exportMoc");
            return EXPORT;
        }

        @Override
        public dev.turboism.sdk.cubism.model.CubismRenderedPixels renderPixels(final int width, final int height) {
            calls.add("renderPixels");
            return PIXELS;
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
                    calls.add("create:" + definition.id().value());
                    return parameter;
                }

                @Override
                public Parameter create(
                        final ParameterDefinition definition, final Optional<ParameterGroupId> folderId) {
                    calls.add("create:" + definition.id().value());
                    return parameter;
                }

                @Override
                public Parameter copy(final ParameterId id) {
                    calls.add("copy:" + id.value());
                    return parameter;
                }

                @Override
                public void remove(final ParameterId id) {
                    calls.add("remove:" + id.value());
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
                    return group;
                }

                @Override
                public ParameterGroup addGroup(final String name) {
                    calls.add("addGroup:" + name);
                    return group;
                }

                @Override
                public void removeGroup(final ParameterGroupId id) {
                    calls.add("removeGroup:" + id.value());
                }

                @Override
                public void moveParameter(final ParameterId parameterId, final ParameterGroupId targetGroupId) {
                    calls.add("moveParameter:" + parameterId.value());
                }

                @Override
                public void moveParameter(
                        final ParameterId parameterId, final ParameterGroupId targetGroupId, final int insertIndex) {
                    calls.add("moveParameter:" + parameterId.value() + ":" + insertIndex);
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
                public List<Part> createMany(final List<String> names, final Part parent) {
                    assertSame(part, parent);
                    calls.add("parts.createMany:" + names);
                    return names.stream().map(ignored -> part).toList();
                }

                @Override
                public Part add(final PartId id) {
                    calls.add("add:" + id.value());
                    return part;
                }

                @Override
                public Part add(final PartId id, final PartId parentId) {
                    calls.add("add:" + id.value());
                    return part;
                }

                @Override
                public Part copy(final PartId id) {
                    calls.add("copy:" + id.value());
                    return part;
                }

                @Override
                public void remove(final PartId id) {
                    calls.add("remove:" + id.value());
                }
            };
        }

        @Override
        public Drawables drawables() {
            return new Drawables() {
                @Override
                public List<Drawable> createManyWithSourceGeometry(final List<SourceGeometryRequest> requests) {
                    calls.add("drawables.createManyWithSourceGeometry");
                    batchMeshRequests = requests;
                    return requests.stream().map(ignored -> drawable).toList();
                }

                @Override
                public List<Drawable> all() {
                    return List.of(drawable);
                }

                @Override
                public Drawable find(final ArtMeshId id) {
                    return drawable;
                }

                @Override
                public dev.turboism.sdk.cubism.model.ArtMeshSourceTriangulation previewSourceTriangulation(
                        final dev.turboism.sdk.cubism.model.ArtMeshGeometry geometry) {
                    calls.add("drawable.previewSourceTriangulation");
                    return new dev.turboism.sdk.cubism.model.ArtMeshSourceTriangulation(
                            geometry.positions(), geometry.triangleIndices());
                }

                @Override
                public Drawable createWithSourceGeometry(
                        final String name,
                        final Part parent,
                        final int index,
                        final dev.turboism.sdk.cubism.model.ArtMeshGeometry geometry,
                        final List<dev.turboism.sdk.cubism.model.Point2> defaultPositions) {
                    assertEquals(AUTHORING.sourceGeometry(), geometry);
                    assertEquals(List.of(), defaultPositions);
                    calls.add("drawable.createWithSourceGeometry:" + name);
                    return drawable;
                }
            };
        }

        @Override
        public dev.turboism.sdk.cubism.model.Deformers deformers() {
            return new dev.turboism.sdk.cubism.model.Deformers() {
                @Override
                public List<dev.turboism.sdk.cubism.model.Deformer> all() {
                    return List.of();
                }

                @Override
                public dev.turboism.sdk.cubism.model.Deformer find(dev.turboism.sdk.cubism.id.DeformerId id) {
                    throw new java.util.NoSuchElementException();
                }

                @Override
                public dev.turboism.sdk.cubism.model.WarpDeformer createWarp(
                        String name,
                        Part parent,
                        int index,
                        Optional<dev.turboism.sdk.cubism.id.DeformerId> target,
                        dev.turboism.sdk.cubism.model.WarpGrid grid) {
                    initializedWarpArgs = java.util.Arrays.asList(name, parent, index, target, grid);
                    calls.add("warp.createInitialized");
                    return warpDeformers().find(new dev.turboism.sdk.cubism.id.DeformerId("WarpA"));
                }
            };
        }

        @Override
        public dev.turboism.sdk.cubism.model.RotationDeformers rotationDeformers() {
            final var id = new dev.turboism.sdk.cubism.id.DeformerId("RollA");
            final var snapshot = new dev.turboism.sdk.cubism.model.RotationAuthoringSnapshot(
                    id,
                    "rotation-source",
                    0,
                    List.of(),
                    List.of(),
                    List.of(new dev.turboism.sdk.cubism.model.RotationAuthoringSnapshot.Form(
                            "rotation-form",
                            new dev.turboism.sdk.cubism.model.RotationDeformerForm(0, 0, 0, 1, false, false))));
            final var rotation = new dev.turboism.sdk.cubism.model.RotationDeformer() {
                @Override
                public dev.turboism.sdk.cubism.id.DeformerId id() {
                    return id;
                }

                @Override
                public int parentDeformerIndex() {
                    return -1;
                }

                @Override
                public dev.turboism.sdk.cubism.model.IntSequence parameters() {
                    throw new UnsupportedOperationException();
                }

                @Override
                public float baseAngle() {
                    return 0;
                }

                @Override
                public void setBaseAngle(float value) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public dev.turboism.sdk.cubism.model.RotationDeformerForm form() {
                    throw new UnsupportedOperationException();
                }

                @Override
                public void replaceForm(dev.turboism.sdk.cubism.model.RotationDeformerForm value) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public dev.turboism.sdk.cubism.model.RotationAuthoringSnapshot authoringSnapshot() {
                    calls.add("rotation.authoringSnapshot");
                    return snapshot;
                }

                @Override
                public void setOrdinaryKeyformForms(
                        dev.turboism.sdk.cubism.model.RotationAuthoringSnapshot expected,
                        List<dev.turboism.sdk.cubism.model.RotationKeyformForm> edits) {
                    calls.add("rotation.setOrdinaryKeyformForms");
                }
            };
            return new dev.turboism.sdk.cubism.model.RotationDeformers() {
                @Override
                public List<dev.turboism.sdk.cubism.model.RotationDeformer> all() {
                    return List.of(rotation);
                }

                @Override
                public dev.turboism.sdk.cubism.model.RotationDeformer find(
                        dev.turboism.sdk.cubism.id.DeformerId target) {
                    return rotation;
                }
            };
        }

        @Override
        public dev.turboism.sdk.cubism.model.WarpDeformers warpDeformers() {
            final var id = new dev.turboism.sdk.cubism.id.DeformerId("WarpA");
            final var snapshot = new dev.turboism.sdk.cubism.model.WarpAuthoringSnapshot(
                    id,
                    "warp-source",
                    1,
                    1,
                    true,
                    List.of(),
                    List.of(),
                    List.of(new dev.turboism.sdk.cubism.model.WarpAuthoringSnapshot.Form(
                            "warp-form",
                            List.of(
                                    new dev.turboism.sdk.cubism.model.Point2(0, 0),
                                    new dev.turboism.sdk.cubism.model.Point2(1, 0),
                                    new dev.turboism.sdk.cubism.model.Point2(0, 1),
                                    new dev.turboism.sdk.cubism.model.Point2(1, 1)))));
            final var morphKeys = new ArrayList<dev.turboism.sdk.cubism.model.WarpMorphSnapshot.Key>();
            final var warp = new dev.turboism.sdk.cubism.model.WarpDeformer() {
                @Override
                public dev.turboism.sdk.cubism.id.DeformerId id() {
                    return id;
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
                public dev.turboism.sdk.cubism.model.WarpGrid grid() {
                    throw new UnsupportedOperationException();
                }

                @Override
                public void replaceGrid(dev.turboism.sdk.cubism.model.WarpGrid grid) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public dev.turboism.sdk.cubism.model.WarpMorphSnapshot morphSnapshot() {
                    calls.add("warp.morphSnapshot");
                    return new dev.turboism.sdk.cubism.model.WarpMorphSnapshot(
                            id,
                            "warp-source",
                            new dev.turboism.sdk.cubism.model.WarpMorphSnapshot.Form(
                                    "base", snapshot.forms().get(0).controlPoints(), 1),
                            morphKeys);
                }

                @Override
                public void createOpacityMorphKeyform(ParameterId parameter, float key, float opacity) {
                    calls.add("warp.createOpacityMorphKeyform");
                    if (failKeyformWrite) throw new IllegalStateException("Warp write failed");
                    morphKeys.add(new dev.turboism.sdk.cubism.model.WarpMorphSnapshot.Key(
                            parameter.value(),
                            key,
                            new dev.turboism.sdk.cubism.model.WarpMorphSnapshot.Form(
                                    "key-" + morphKeys.size(),
                                    snapshot.forms().get(0).controlPoints(),
                                    opacity)));
                }

                @Override
                public void createMorphKeyform(
                        ParameterId parameter, float key, List<dev.turboism.sdk.cubism.model.Point2> controls) {
                    calls.add("warp.createMorphKeyform");
                    if (failKeyformWrite) throw new IllegalStateException("Warp write failed");
                    morphKeys.add(new dev.turboism.sdk.cubism.model.WarpMorphSnapshot.Key(
                            parameter.value(),
                            key,
                            new dev.turboism.sdk.cubism.model.WarpMorphSnapshot.Form(
                                    "key-" + morphKeys.size(), controls, 1)));
                }

                @Override
                public dev.turboism.sdk.cubism.model.WarpAuthoringSnapshot authoringSnapshot() {
                    calls.add("warp.authoringSnapshot");
                    return snapshot;
                }

                @Override
                public void setOrdinaryKeyformPositions(
                        dev.turboism.sdk.cubism.model.WarpAuthoringSnapshot expected,
                        List<dev.turboism.sdk.cubism.model.WarpKeyformPositions> edits) {
                    calls.add("warp.setOrdinaryKeyformPositions");
                }
            };
            return new dev.turboism.sdk.cubism.model.WarpDeformers() {
                @Override
                public List<dev.turboism.sdk.cubism.model.WarpDeformer> all() {
                    return List.of(warp);
                }

                @Override
                public dev.turboism.sdk.cubism.model.WarpDeformer find(dev.turboism.sdk.cubism.id.DeformerId target) {
                    return warp;
                }
            };
        }

        @Override
        public dev.turboism.sdk.cubism.model.Glues glues() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void update() {
            throw new UnsupportedOperationException();
        }
    }
}

package dev.turboism.adapter.cubism;

import dev.turboism.sdk.cubism.DeformerType;
import dev.turboism.sdk.cubism.clipmask.ClipMaskReplacement;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.AnimationAttribute;
import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.Color;
import dev.turboism.sdk.cubism.model.CubismModel;
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

/** Reusable recording doubles for CubismFacadeImplTest contracts. */
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
}

package dev.turboism.plugin.psdclipmaskimport;

import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.CubismRuntimeSnapshot;
import dev.turboism.sdk.cubism.DocumentSnapshot;
import dev.turboism.sdk.cubism.ModelSnapshot;
import dev.turboism.sdk.cubism.ProjectSnapshot;
import dev.turboism.sdk.cubism.clipmask.ClipMaskReplacement;
import dev.turboism.sdk.cubism.clipmask.PsdClipMaskDocumentSnapshot;
import dev.turboism.sdk.cubism.clipmask.PsdClipMaskDocumentSnapshot.PsdLayerSnapshot;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.model.BlendMode;
import dev.turboism.sdk.cubism.model.Color;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.Drawables;
import dev.turboism.sdk.cubism.model.FloatSequence;
import dev.turboism.sdk.cubism.model.IntSequence;
import dev.turboism.sdk.ui.DialogRequest;
import dev.turboism.test.plugin.FakePluginContext;
import dev.turboism.test.ui.RecordingUiHost;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class PsdClipMaskImportTestFixtures {

    static final ArtMeshId TARGET_A = new ArtMeshId("ArtMeshA");
    static final ArtMeshId TARGET_B = new ArtMeshId("ArtMeshB");
    static final ArtMeshId TARGET_OVERWRITE = new ArtMeshId("ArtMeshOverwrite");
    static final ArtMeshId MISSING = new ArtMeshId("NotInModel");
    static final ArtMeshId MASK_A = new ArtMeshId("ArtMeshMaskA");
    static final ArtMeshId MASK_B = new ArtMeshId("ArtMeshMaskB");

    static PsdClipMaskImportService service(final RecordingHost host) {
        return new PsdClipMaskImportService(host.modelAccess, host.context, host.ui);
    }

    static final class RecordingHost {
        final RecordingUiHost ui = new RecordingUiHost();
        final RecordingPluginContext context = new RecordingPluginContext();
        final CubismModelAccess modelAccess;
        final List<PsdClipMaskDocumentSnapshot> psds = new ArrayList<>(List.of(psdFace()));
        final java.util.Map<ArtMeshId, Boolean> invertedState = new java.util.HashMap<>();
        final List<List<ClipMaskReplacement>> batches = new ArrayList<>();
        ModelId activeModelId = new ModelId("model-a");
        RuntimeException modelFailure;
        RuntimeException backendFailure;

        RecordingHost() {
            modelAccess = new CubismModelAccess() {
                @Override
                public CubismModel active() {
                    return new CubismModel() {
                        @Override
                        public ModelId id() {
                            return activeModelId;
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
                        public Drawables drawables() {
                            final List<Drawable> drawables = List.of(
                                    drawable(TARGET_A, List.of(), invertedState),
                                    drawable(TARGET_B, List.of(), invertedState),
                                    drawable(TARGET_OVERWRITE, List.of(MASK_B), invertedState),
                                    drawable(MASK_A, List.of(), invertedState),
                                    drawable(MASK_B, List.of(), invertedState));
                            return new Drawables() {
                                @Override
                                public List<Drawable> all() {
                                    return drawables;
                                }

                                @Override
                                public Drawable find(final ArtMeshId requested) {
                                    return drawables.stream()
                                            .filter(d -> d.id().equals(requested))
                                            .findFirst()
                                            .orElseThrow(
                                                    () -> new IllegalArgumentException("no drawable " + requested));
                                }
                            };
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

                        @Override
                        public List<PsdClipMaskDocumentSnapshot> psdDocuments() {
                            if (modelFailure != null) {
                                throw modelFailure;
                            }
                            return psds;
                        }

                        @Override
                        public void replaceArtMeshClipMasks(final List<ClipMaskReplacement> replacements) {
                            if (backendFailure != null) {
                                throw backendFailure;
                            }
                            batches.add(List.copyOf(replacements));
                        }
                    };
                }
            };
        }

        static PsdClipMaskDocumentSnapshot psdFace() {
            return new PsdClipMaskDocumentSnapshot(
                    "psd-face",
                    "textures/face.psd",
                    List.of(
                            new PsdLayerSnapshot(
                                    "mask-base",
                                    "Mask Base",
                                    true,
                                    List.of(MASK_A, MASK_B),
                                    Optional.empty(),
                                    List.of()),
                            new PsdLayerSnapshot(
                                    "clipped-a",
                                    "Clipped A",
                                    true,
                                    List.of(TARGET_A),
                                    Optional.of("mask-base"),
                                    List.of()),
                            new PsdLayerSnapshot(
                                    "clipped-b",
                                    "Clipped B",
                                    true,
                                    List.of(TARGET_B),
                                    Optional.of("mask-base"),
                                    List.of()),
                            new PsdLayerSnapshot(
                                    "clipped-overwrite",
                                    "Clipped Overwrite",
                                    true,
                                    List.of(TARGET_OVERWRITE),
                                    Optional.of("mask-base"),
                                    List.of()),
                            new PsdLayerSnapshot(
                                    "clipped-missing",
                                    "Clipped Missing",
                                    true,
                                    List.of(MISSING),
                                    Optional.of("mask-base"),
                                    List.of()),
                            new PsdLayerSnapshot(
                                    "clipped-dupe",
                                    "Clipped Dupe",
                                    true,
                                    List.of(TARGET_A),
                                    Optional.of("mask-base"),
                                    List.of())));
        }

        static PsdClipMaskDocumentSnapshot psdWithMissingMask() {
            return new PsdClipMaskDocumentSnapshot(
                    "psd-face",
                    "textures/face.psd",
                    List.of(
                            new PsdLayerSnapshot(
                                    "mask-base",
                                    "Mask Base",
                                    true,
                                    List.of(MASK_A, MISSING),
                                    Optional.empty(),
                                    List.of()),
                            new PsdLayerSnapshot(
                                    "clipped-missing",
                                    "Clipped Missing Mask",
                                    true,
                                    List.of(TARGET_A),
                                    Optional.of("mask-base"),
                                    List.of())));
        }

        static PsdClipMaskDocumentSnapshot psdWithoutRelationship() {
            return new PsdClipMaskDocumentSnapshot(
                    "psd-face",
                    "textures/face.psd",
                    List.of(new PsdLayerSnapshot(
                            "plain-layer", "Plain", true, List.of(MASK_A), Optional.empty(), List.of())));
        }

        static PsdClipMaskDocumentSnapshot psdWithSelfOnlyMasks() {
            return new PsdClipMaskDocumentSnapshot(
                    "psd-face",
                    "textures/face.psd",
                    List.of(
                            new PsdLayerSnapshot(
                                    "self-base", "Self Base", true, List.of(TARGET_A), Optional.empty(), List.of()),
                            new PsdLayerSnapshot(
                                    "clipped-self",
                                    "Clipped Self",
                                    true,
                                    List.of(TARGET_A),
                                    Optional.of("self-base"),
                                    List.of())));
        }
    }

    static Drawable drawable(
            final ArtMeshId id, final List<ArtMeshId> masks, final java.util.Map<ArtMeshId, Boolean> invertedState) {
        return new Drawable() {
            @Override
            public ArtMeshId id() {
                return id;
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
            public BlendMode blendMode() {
                return BlendMode.NORMAL;
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
            public FloatSequence vertexPositions() {
                return emptyFloats();
            }

            @Override
            public FloatSequence vertexUvs() {
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
            public List<ArtMeshId> maskIds() {
                return masks;
            }

            @Override
            public boolean invertedMask() {
                return Boolean.TRUE.equals(invertedState.get(id));
            }
        };
    }

    static final class RecordingPluginContext extends FakePluginContext {

        String activeDocumentId = "document-1";
        String activeDocumentModelId = "model-a";
        boolean documentHasModel = true;

        RecordingPluginContext() {
            super(FakePluginContext.builder().descriptor(new TestPluginDescriptor()));
        }

        @Override
        public dev.turboism.sdk.i18n.PluginLocalization localization() {
            return new FakeLocalization();
        }

        @Override
        public CubismFacade cubism() {
            return new CubismFacade() {
                @Override
                public CubismRuntimeSnapshot runtime() {
                    throw new UnsupportedOperationException();
                }

                @Override
                public Optional<ProjectSnapshot> activeProject() {
                    return Optional.empty();
                }

                @Override
                public Optional<DocumentSnapshot> activeDocument() {
                    return Optional.of(new DocumentSnapshot(
                            activeDocumentId,
                            "Doc",
                            "documents/" + activeDocumentId + ".cdi3.json",
                            Optional.empty(),
                            documentHasModel
                                    ? Optional.of(new ModelSnapshot(
                                            activeDocumentModelId,
                                            "Model A",
                                            List.of(),
                                            List.of(),
                                            List.of(),
                                            List.of()))
                                    : Optional.empty()));
                }

                @Override
                public Optional<ModelSnapshot> activeModel() {
                    return Optional.of(
                            new ModelSnapshot("model-a", "Model A", List.of(), List.of(), List.of(), List.of()));
                }

                @Override
                public boolean isHostPresent() {
                    return true;
                }

                @Override
                public CubismModelAccess model() {
                    throw new UnsupportedOperationException();
                }
            };
        }
    }

    static final class RecordingUiHost extends dev.turboism.test.ui.RecordingUiHost {
        Runnable onConfirm = () -> {};
        public DialogRequest lastPreview;

        DialogRequest lastPreview() {
            return lastPreview;
        }

        @Override
        public boolean confirmDialog(final DialogRequest request) {
            this.lastPreview = request;
            final boolean result = super.confirmDialog(request);
            if (result && onConfirm != null) {
                onConfirm.run();
            }
            return result;
        }
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

    static FloatSequence emptyFloats() {
        return new FloatSequence() {
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

    /**
     * Test localization backed by the real base catalog on the test classpath.
     * {@code format} applies MessageFormat exactly like the production runtime,
     * so assertions prove real template formatting, never key-name output.
     */
    static final class FakeLocalization implements dev.turboism.sdk.i18n.PluginLocalization {
        final java.util.Map<String, String> catalog = loadBaseCatalog();

        @Override
        public java.util.Locale locale() {
            return java.util.Locale.ENGLISH;
        }

        @Override
        public String text(final String key) {
            return catalog.getOrDefault(key, key);
        }

        @Override
        public String format(final String key, final Object... arguments) {
            return new java.text.MessageFormat(catalog.getOrDefault(key, key), java.util.Locale.ENGLISH)
                    .format(arguments);
        }

        @Override
        public boolean contains(final String key) {
            return catalog.containsKey(key);
        }

        static java.util.Map<String, String> loadBaseCatalog() {
            final java.util.Map<String, String> values = new java.util.LinkedHashMap<>();
            try (java.io.InputStream stream = FakeLocalization.class
                    .getClassLoader()
                    .getResourceAsStream("META-INF/turboism/i18n/messages.properties")) {
                if (stream == null) {
                    throw new IllegalStateException("base catalog missing from test classpath");
                }
                final java.util.Properties properties = new java.util.Properties();
                properties.load(stream);
                properties.forEach((key, value) -> values.put((String) key, (String) value));
            } catch (java.io.IOException failure) {
                throw new IllegalStateException("cannot load base catalog", failure);
            }
            return java.util.Map.copyOf(values);
        }
    }
}

package dev.turboism.adapter.cubism.core;

import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.sdk.cubism.model.Deformers;
import dev.turboism.sdk.cubism.model.Drawables;
import dev.turboism.sdk.cubism.model.Glues;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.cubism.model.ParameterDefinition;
import dev.turboism.sdk.cubism.model.ParameterDefinitions;
import dev.turboism.sdk.cubism.model.ParameterType;
import dev.turboism.sdk.cubism.model.Parameters;
import dev.turboism.sdk.cubism.model.WarpDeformers;
import dev.turboism.sdk.cubism.model.Parts;
import dev.turboism.sdk.cubism.model.Canvas;
import dev.turboism.sdk.cubism.model.Part;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.Deformer;
import dev.turboism.sdk.cubism.model.Glue;
import dev.turboism.sdk.cubism.model.DrawableEvaluationState;
import dev.turboism.sdk.cubism.model.GlueId;
import dev.turboism.sdk.cubism.model.PartId;
import dev.turboism.sdk.cubism.model.BlendMode;
import dev.turboism.sdk.cubism.model.Color;
import dev.turboism.sdk.cubism.model.FloatSequence;
import dev.turboism.sdk.cubism.model.IntSequence;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

/**
 * Unified SDK model access backed by short-lived reads from one borrowed Cubism Core model.
 *
 * <p>Each SDK object is pinned to the generation observed when it was created. Every read acquires
 * a fresh lease and projects adapter-owned values, so no raw Core object or array survives the
 * call boundary. Editor-attached mutation remains explicitly unavailable until the verified
 * Editor authoring path is installed.</p>
 */
public final class CoreBackedCubismModelAccess implements CubismModelAccess {

    private static final String READ_ONLY_MESSAGE =
        "Editor-attached Cubism model mutation is unavailable until the verified Editor authoring backend is installed.";

    private final ActiveCoreModelSource source;
    private final CorePublicApiProvider provider;
    private final CoreStructuralTracer tracer;


    private final CoreEvaluatedJoin evaluatedJoin;
    CoreBackedCubismModelAccess(
        final ActiveCoreModelSource source,
        final CorePublicApiProvider provider,
        final CoreStructuralTracer tracer
    ) {
        this.source = Objects.requireNonNull(source, "source");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.tracer = Objects.requireNonNull(tracer, "tracer");
        this.evaluatedJoin = new CoreEvaluatedJoin(source, provider, tracer);
    }

    @Override
    public CubismModel active() {
        final CoreStructuralSnapshot snapshot = readCurrentSnapshot();
        return new RuntimeCubismModel(snapshot.generation(), snapshot.modelIdentity());
    }

    private CoreStructuralSnapshot readCurrentSnapshot() {
        final CoreModelAcquisition acquisition = source.acquire(provider);
        if (!acquisition.isAcquired()) {
            throw acquisitionFailure(acquisition.failure().orElseThrow());
        }
        try (CoreModelLease lease = acquisition.lease().orElseThrow()) {
            return tracedSnapshot(lease);
        }
    }

    private CoreStructuralSnapshot readGeneration(final long expectedGeneration) {
        final CoreStructuralSnapshot snapshot = readCurrentSnapshot();
        if (snapshot.generation() != expectedGeneration) {
            throw staleFailure();
        }
        return snapshot;
    }

    private CoreStructuralSnapshot tracedSnapshot(final CoreModelLease lease) {
        final CoreProviderResult<CoreStructuralSnapshot> result = tracer.trace(lease);
        if (result.isSuccess()) {
            return result.value().orElseThrow();
        }
        final CoreProviderFailure failure = result.failure().orElseThrow();
        throw switch (failure.code()) {
            case STALE_GENERATION, LEASE_CLOSED -> staleFailure();
            default -> new IllegalStateException(failure.message());
        };
    }

    private static IllegalStateException acquisitionFailure(
        final CoreModelFailure failure
    ) {
        return switch (failure.code()) {
            case STALE_GENERATION, LEASE_CLOSED -> staleFailure();
            case MODEL_UNAVAILABLE -> new IllegalStateException(
                "No verified active Cubism Core model is available."
            );
            default -> new IllegalStateException(failure.message());
        };
    }

    private static IllegalStateException staleFailure() {
        return new IllegalStateException(
            "Cubism model reference is stale for the active model generation."
        );
    }

    private static UnsupportedOperationException readOnlyFailure() {
        return new UnsupportedOperationException(READ_ONLY_MESSAGE);
    }

    private final class RuntimeCubismModel implements CubismModel {

        private final long generation;
        private final ModelId id;
        private final Parameters parameters;
        private final Parts parts;
        private final Drawables drawables;
        private final Deformers deformers;
        private final Glues glues;

        private RuntimeCubismModel(
            final long generation,
            final String modelIdentity
        ) {
            this.generation = generation;
            this.id = new ModelId(modelIdentity);
            this.parameters = new RuntimeParameters(generation);
            this.parts = new RuntimeParts(generation);
            this.drawables = new RuntimeDrawables(generation);
            this.deformers = new RuntimeDeformers(generation);
            this.glues = new RuntimeGlues(generation);
        }

        @Override
        public ModelId id() {
            readGeneration(generation);
            return id;
        }

        @Override
        public Parameters parameters() {
            readGeneration(generation);
            return parameters;
        }

        @Override
        public boolean defaultKeyformLocked() {
            readGeneration(generation);
            return CubismModel.super.defaultKeyformLocked();
        }

        @Override
        public void setDefaultKeyformLocked(final boolean locked) {
            readGeneration(generation);
            CubismModel.super.setDefaultKeyformLocked(locked);
        }

        @Override
        public dev.turboism.sdk.cubism.model.ModelEditLevel editLevel() {
            readGeneration(generation);
            return CubismModel.super.editLevel();
        }

        @Override
        public void setEditLevel(final dev.turboism.sdk.cubism.model.ModelEditLevel level) {
            readGeneration(generation);
            CubismModel.super.setEditLevel(level);
        }

        @Override
        public dev.turboism.sdk.cubism.model.ParameterGroups parameterGroups() {
            readGeneration(generation);
            return CubismModel.super.parameterGroups();
        }

        @Override
        public Canvas canvas() {
            return new RuntimeCanvas(generation);
        }

        @Override
        public Parts parts() {
            readGeneration(generation);
            return parts;
        }

        @Override
        public Drawables drawables() {
            readGeneration(generation);
            return drawables;
        }

        @Override
        public Deformers deformers() {
            readGeneration(generation);
            return deformers;
        }

        @Override
        public Glues glues() {
            readGeneration(generation);
            return glues;
        }

        @Override
        public void update() {
            readGeneration(generation);
            throw readOnlyFailure();
        }

        @Override
        public dev.turboism.sdk.cubism.core.MocInfo mocInfo() {
            readGeneration(generation);
            return evaluatedJoin.mocInfo();
        }

        @Override
        public ParameterDefinitions parameterDefinitions() {
            final CoreStructuralSnapshot snapshot = readGeneration(generation);
            return new ParameterDefinitions() {
                @Override public List<ParameterDefinition> all() {
                    return readGeneration(generation).parameters().stream()
                        .map(CoreBackedCubismModelAccess.this::parameterDefinition)
                        .toList();
                }

                @Override public ParameterDefinition find(final ParameterId id) {
                    Objects.requireNonNull(id, "id");
                    return parameterDefinition(definition(readGeneration(generation), id));
                }
            };
        }

        @Override
        public WarpDeformers warpDeformers() {
            readGeneration(generation);
            throw new UnsupportedOperationException(
                "Cubism Core exposes no Warp/Rotation deformer kind, so a Core-backed "
                    + "Warp Deformer projection would mislabel Rotation Deformers; use the "
                    + "Editor-backed model for the verified warp/rotation split."
            );
        }
    }

    private final class RuntimeCanvas implements Canvas {
        private final long generation;
        private RuntimeCanvas(final long generation) { this.generation = generation; }
        private CoreCanvasSnapshot current() { return readGeneration(generation).canvas(); }
        @Override public float widthPixels() { return current().widthPixels(); }
        @Override public float heightPixels() { return current().heightPixels(); }
        @Override public float originXPixels() { return current().originXPixels(); }
        @Override public float originYPixels() { return current().originYPixels(); }
        @Override public float pixelsPerUnit() { return current().pixelsPerUnit(); }
    }

    private final class RuntimeParameters implements Parameters {

        private final long generation;

        private RuntimeParameters(final long generation) {
            this.generation = generation;
        }

        @Override
        public List<Parameter> all() {
            return readGeneration(generation).parameters().stream()
                .map(definition -> (Parameter) new RuntimeParameter(
                    generation,
                    new ParameterId(definition.id())
                ))
                .toList();
        }

        @Override
        public Parameter find(final ParameterId id) {
            final ParameterId requiredId = Objects.requireNonNull(id, "id");
            definition(readGeneration(generation), requiredId);
            return new RuntimeParameter(generation, requiredId);
        }
    }

    private final class RuntimeParameter implements Parameter {
        private final long generation;
        private final ParameterId id;
        private RuntimeParameter(final long generation, final ParameterId id) {
            this.generation = generation;
            this.id = id;
        }
        private CoreParameterDefinition current() {
            return definition(readGeneration(generation), id);
        }
        @Override public ParameterId id() { current(); return id; }
        @Override public int index() {
            final CoreStructuralSnapshot snapshot = readGeneration(generation);
            return snapshot.parameters().indexOf(definition(snapshot, id));
        }
        @Override public FloatSequence keyValues() {
            return ImmutableCoreSequences.floats(current().keyValues());
        }
        @Override public ParameterType type() {
            return switch (current().typeNumber()) {
                case 0 -> ParameterType.NORMAL;
                case 1 -> ParameterType.BLEND_SHAPE;
                default -> ParameterType.UNKNOWN;
            };
        }
        @Override public Optional<Boolean> repeat() { return current().repeat(); }
        @Override public float getValue() { return current().currentValue(); }
        @Override public float getMinimumValue() { return current().minimumValue(); }
        @Override public float getMaximumValue() { return current().maximumValue(); }
        @Override public float getDefaultValue() { return current().defaultValue(); }
        @Override public void setValue(final float value) { current(); throw readOnlyFailure(); }
    }

    private final class RuntimeParts implements Parts {
        private final long generation;
        private RuntimeParts(final long generation) { this.generation = generation; }
        @Override public List<Part> all() { return readGeneration(generation).parts().stream().map(value -> (Part) new RuntimePart(generation, new PartId(value.id()))).toList(); }
        @Override public Part find(final PartId id) { part(readGeneration(generation), Objects.requireNonNull(id, "id")); return new RuntimePart(generation, id); }
    }

    private final class RuntimePart implements Part {
        private final long generation; private final PartId id;
        private RuntimePart(final long generation, final PartId id) { this.generation = generation; this.id = id; }
        private CorePartDefinition current() { return part(readGeneration(generation), id); }
        @Override public PartId id() { current(); return id; }
        @Override public int index() {
            final CoreStructuralSnapshot snapshot = readGeneration(generation);
            return snapshot.parts().indexOf(part(snapshot, id));
        }
        @Override public Optional<PartId> parentId() {
            final CoreStructuralSnapshot snapshot = readGeneration(generation);
            final int parentIndex = part(snapshot, id).parentIndex();
            return parentIndex < 0
                ? Optional.empty()
                : Optional.of(new PartId(snapshot.parts().get(parentIndex).id()));
        }
        @Override public List<PartId> childIds() {
            final CoreStructuralSnapshot snapshot = readGeneration(generation);
            final int partIndex = snapshot.parts().indexOf(part(snapshot, id));
            return java.util.stream.IntStream.range(0, snapshot.parts().size())
                .filter(index -> snapshot.parts().get(index).parentIndex() == partIndex)
                .mapToObj(index -> new PartId(snapshot.parts().get(index).id()))
                .toList();
        }
        @Override public void setName(final String name) { current(); throw readOnlyFailure(); }
        @Override public float getOpacity() { return current().opacity(); }
        @Override public int parentIndex() { return current().parentIndex(); }
        @Override public void setOpacity(final float opacity) { current(); throw readOnlyFailure(); }
    }

    private final class RuntimeDrawables implements Drawables {
        private final long generation;
        private RuntimeDrawables(final long generation) { this.generation = generation; }
        @Override public List<Drawable> all() { return readGeneration(generation).drawables().stream().map(value -> (Drawable) new RuntimeDrawable(generation, new ArtMeshId(value.id()))).toList(); }
        @Override public Drawable find(final ArtMeshId id) { drawable(readGeneration(generation), Objects.requireNonNull(id, "id")); return new RuntimeDrawable(generation, id); }
    }

    private final class RuntimeDrawable implements Drawable {
        private final long generation; private final ArtMeshId id;
        private RuntimeDrawable(final long generation, final ArtMeshId id) { this.generation = generation; this.id = id; }
        private CoreDrawableDefinition current() { return drawable(readGeneration(generation), id); }
        @Override public ArtMeshId id() { current(); return id; }
        @Override public int index() {
            final CoreStructuralSnapshot snapshot = readGeneration(generation);
            return snapshot.drawables().indexOf(drawable(snapshot, id));
        }
        @Override public boolean doubleSided() {
            return (Byte.toUnsignedInt(current().constantFlag()) & 0x04) != 0;
        }
        @Override public DrawableEvaluationState evaluationState() {
            final int flags = Byte.toUnsignedInt(current().dynamicFlag());
            return new DrawableEvaluationState(
                (flags & 0x01) != 0,
                (flags & 0x02) != 0,
                (flags & 0x04) != 0,
                (flags & 0x08) != 0,
                (flags & 0x10) != 0,
                (flags & 0x20) != 0,
                (flags & 0x40) != 0
            );
        }
        @Override public Optional<PartId> parentPartId() {
            final CoreStructuralSnapshot snapshot = readGeneration(generation);
            final int parentIndex = drawable(snapshot, id).parentPartIndex();
            return parentIndex < 0
                ? Optional.empty()
                : Optional.of(new PartId(snapshot.parts().get(parentIndex).id()));
        }
        @Override public Optional<DeformerId> parentDeformerId() {
            final CoreStructuralSnapshot snapshot = readGeneration(generation);
            final int parentIndex = drawable(snapshot, id).parentDeformerIndex();
            return parentIndex < 0
                ? Optional.empty()
                : Optional.of(new DeformerId(snapshot.deformers().get(parentIndex).id()));
        }
        @Override public List<ParameterId> parameterIds() {
            final CoreStructuralSnapshot snapshot = readGeneration(generation);
            return CoreBackedCubismModelAccess.parameterIds(snapshot, drawable(snapshot, id).parameters());
        }
        @Override public List<ArtMeshId> maskIds() {
            final CoreStructuralSnapshot snapshot = readGeneration(generation);
            return drawable(snapshot, id).masks().stream()
                .map(index -> new ArtMeshId(snapshot.drawables().get(index).id()))
                .toList();
        }
        @Override public byte constantFlag() { return current().constantFlag(); }
        @Override public byte dynamicFlag() { return current().dynamicFlag(); }
        @Override public BlendMode blendMode() { return current().blendMode(); }
        @Override public int textureIndex() { return current().textureIndex(); }
        @Override public int drawOrder() { return current().drawOrder(); }
        @Override public int renderOrder() { return current().renderOrder(); }
        @Override public float getOpacity() { return current().opacity(); }
        @Override public IntSequence masks() { return ImmutableCoreSequences.ints(current().masks()); }
        @Override public FloatSequence vertexPositions() { return ImmutableCoreSequences.floats(current().vertexPositions()); }
        @Override public FloatSequence vertexUvs() { return ImmutableCoreSequences.floats(current().vertexUvs()); }
        @Override public IntSequence indices() { return ImmutableCoreSequences.ints(current().indices()); }
        @Override public Color multiplyColor() { return current().multiplyColor(); }
        @Override public Color screenColor() { return current().screenColor(); }
        @Override public int parentPartIndex() { return current().parentPartIndex(); }
        @Override public int parentDeformerIndex() { return current().parentDeformerIndex(); }
        @Override public IntSequence parameters() { return ImmutableCoreSequences.ints(current().parameters()); }
    }

    private final class RuntimeDeformers implements Deformers {
        private final long generation;
        private RuntimeDeformers(final long generation) { this.generation = generation; }
        @Override public List<Deformer> all() { return readGeneration(generation).deformers().stream().map(value -> (Deformer) new RuntimeDeformer(generation, new DeformerId(value.id()))).toList(); }
        @Override public Deformer find(final DeformerId id) { deformer(readGeneration(generation), Objects.requireNonNull(id, "id")); return new RuntimeDeformer(generation, id); }
    }

    private final class RuntimeDeformer implements Deformer {
        private final long generation; private final DeformerId id;
        private RuntimeDeformer(final long generation, final DeformerId id) { this.generation = generation; this.id = id; }
        private CoreDeformerDefinition current() { return deformer(readGeneration(generation), id); }
        @Override public DeformerId id() { current(); return id; }
        @Override public int index() {
            final CoreStructuralSnapshot snapshot = readGeneration(generation);
            return snapshot.deformers().indexOf(deformer(snapshot, id));
        }
        @Override public Optional<DeformerId> parentDeformerId() {
            final CoreStructuralSnapshot snapshot = readGeneration(generation);
            final int parentIndex = deformer(snapshot, id).parentDeformerIndex();
            return parentIndex < 0
                ? Optional.empty()
                : Optional.of(new DeformerId(snapshot.deformers().get(parentIndex).id()));
        }
        @Override public List<ParameterId> parameterIds() {
            final CoreStructuralSnapshot snapshot = readGeneration(generation);
            return CoreBackedCubismModelAccess.parameterIds(snapshot, deformer(snapshot, id).parameters());
        }
        @Override public int parentDeformerIndex() { return current().parentDeformerIndex(); }
        @Override public IntSequence parameters() { return ImmutableCoreSequences.ints(current().parameters()); }
    }

    private final class RuntimeGlues implements Glues {
        private final long generation;
        private RuntimeGlues(final long generation) { this.generation = generation; }
        @Override public List<Glue> all() { return readGeneration(generation).glues().stream().map(value -> (Glue) new RuntimeGlue(generation, new GlueId(value.id()))).toList(); }
        @Override public Glue find(final GlueId id) { glue(readGeneration(generation), Objects.requireNonNull(id, "id")); return new RuntimeGlue(generation, id); }
    }

    private final class RuntimeGlue implements Glue {
        private final long generation; private final GlueId id;
        private RuntimeGlue(final long generation, final GlueId id) { this.generation = generation; this.id = id; }
        private CoreGlueDefinition current() { return glue(readGeneration(generation), id); }
        @Override public GlueId id() { current(); return id; }
        @Override public int index() {
            final CoreStructuralSnapshot snapshot = readGeneration(generation);
            return snapshot.glues().indexOf(glue(snapshot, id));
        }
        @Override public ArtMeshId drawableAId() {
            final CoreStructuralSnapshot snapshot = readGeneration(generation);
            return new ArtMeshId(snapshot.drawables().get(glue(snapshot, id).drawableA()).id());
        }
        @Override public ArtMeshId drawableBId() {
            final CoreStructuralSnapshot snapshot = readGeneration(generation);
            return new ArtMeshId(snapshot.drawables().get(glue(snapshot, id).drawableB()).id());
        }
        @Override public List<ParameterId> parameterIds() {
            final CoreStructuralSnapshot snapshot = readGeneration(generation);
            return CoreBackedCubismModelAccess.parameterIds(snapshot, glue(snapshot, id).parameters());
        }
        @Override public int drawableA() { return current().drawableA(); }
        @Override public int drawableB() { return current().drawableB(); }
        @Override public IntSequence parameters() { return ImmutableCoreSequences.ints(current().parameters()); }
    }

    private static CoreParameterDefinition definition(
        final CoreStructuralSnapshot snapshot,
        final ParameterId id
    ) {
        return snapshot.parameters().stream()
            .filter(parameter -> parameter.id().equals(id.value()))
            .findFirst()
            .orElseThrow(() -> new NoSuchElementException(
                "Cubism parameter is absent: " + id.value()
            ));
    }

    private static CorePartDefinition part(final CoreStructuralSnapshot snapshot, final PartId id) {
        return snapshot.parts().stream().filter(value -> value.id().equals(id.value())).findFirst().orElseThrow(() -> absent("Part", id.value()));
    }
    private static CoreDrawableDefinition drawable(final CoreStructuralSnapshot snapshot, final ArtMeshId id) {
        return snapshot.drawables().stream().filter(value -> value.id().equals(id.value())).findFirst().orElseThrow(() -> absent("Drawable", id.value()));
    }
    private static CoreDeformerDefinition deformer(final CoreStructuralSnapshot snapshot, final DeformerId id) {
        return snapshot.deformers().stream().filter(value -> value.id().equals(id.value())).findFirst().orElseThrow(() -> absent("Deformer", id.value()));
    }
    private static CoreGlueDefinition glue(final CoreStructuralSnapshot snapshot, final GlueId id) {
        return snapshot.glues().stream().filter(value -> value.id().equals(id.value())).findFirst().orElseThrow(() -> absent("Glue", id.value()));
    }

    private static List<ParameterId> parameterIds(
        final CoreStructuralSnapshot snapshot,
        final List<Integer> indices
    ) {
        return indices.stream()
            .map(index -> new ParameterId(snapshot.parameters().get(index).id()))
            .toList();
    }

    private ParameterDefinition parameterDefinition(final CoreParameterDefinition definition) {
        return new ParameterDefinition(
            new ParameterId(definition.id()),
            definition.id(),
            definition.minimumValue(),
            definition.defaultValue(),
            definition.maximumValue(),
            switch (definition.typeNumber()) {
                case 0 -> ParameterType.NORMAL;
                case 1 -> ParameterType.BLEND_SHAPE;
                default -> throw new IllegalStateException(
                    "Core parameter type is unsupported: " + definition.typeNumber()
                );
            },
            definition.repeat().orElse(false)
        );
    }
    private static NoSuchElementException absent(final String family, final String id) {
        return new NoSuchElementException("Cubism " + family + " is absent: " + id);
    }

}

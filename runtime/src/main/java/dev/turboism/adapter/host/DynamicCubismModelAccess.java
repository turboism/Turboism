package dev.turboism.adapter.host;

import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.sdk.cubism.model.Canvas;
import dev.turboism.sdk.cubism.model.Color;
import dev.turboism.sdk.cubism.model.BlendMode;
import dev.turboism.sdk.cubism.model.Deformer;
import dev.turboism.sdk.cubism.model.Deformers;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.Drawables;
import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.RotationDeformer;
import dev.turboism.sdk.cubism.model.RotationDeformerForm;
import dev.turboism.sdk.cubism.model.RotationDeformers;
import dev.turboism.sdk.cubism.model.WarpDeformer;
import dev.turboism.sdk.cubism.model.WarpDeformers;
import dev.turboism.sdk.cubism.model.WarpGrid;
import dev.turboism.sdk.cubism.model.Glue;
import dev.turboism.sdk.cubism.model.GlueId;
import dev.turboism.sdk.cubism.model.Glues;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.cubism.model.ParameterGroup;
import dev.turboism.sdk.cubism.model.ParameterGroups;
import dev.turboism.sdk.cubism.model.Parameters;
import dev.turboism.sdk.cubism.model.Part;
import dev.turboism.sdk.cubism.model.PartId;
import dev.turboism.sdk.cubism.model.Parts;
import dev.turboism.adapter.cubism.NativeControlAppearanceAuthoring;
import dev.turboism.sdk.ui.appearance.ControlAppearanceTarget;
import dev.turboism.sdk.ui.appearance.NativeControlAppearance;
import dev.turboism.sdk.ui.appearance.NativeControlBackground;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Stable plugin-facing model access whose delegate follows one HostSession connection. */
final class DynamicCubismModelAccess implements CubismModelAccess, NativeControlAppearanceAuthoring {

    private final Object callGate = new Object();
    private CubismModelAccess current = UnavailableCubismModelAccess.INSTANCE;
    private boolean acceptingCalls;
    private long generation;
    private int inFlight;

    @Override
    public CubismModel active() {
        final AccessLease lease = acquireActiveLease();
        try {
            return new SessionModel(
                lease.generation(),
                Objects.requireNonNull(lease.modelAccess().active(), "active model")
            );
        } finally {
            release(lease);
        }
    }

    void connect(final CubismModelAccess modelAccess) {
        final CubismModelAccess next = Objects.requireNonNull(modelAccess, "modelAccess");
        final boolean interrupted;
        synchronized (callGate) {
            acceptingCalls = false;
            interrupted = awaitNoInFlight();
            current = next;
            generation++;
            acceptingCalls = true;
        }
        restoreInterrupt(interrupted);
    }

    void deactivate() {
        final boolean interrupted;
        synchronized (callGate) {
            acceptingCalls = false;
            interrupted = awaitNoInFlight();
            current = UnavailableCubismModelAccess.INSTANCE;
            generation++;
        }
        restoreInterrupt(interrupted);
    }

    private AccessLease acquireActiveLease() {
        synchronized (callGate) {
            if (!acceptingCalls) {
                throw new IllegalStateException(
                    "No verified active Cubism Core model is available."
                );
            }
            inFlight++;
            return new AccessLease(current, generation);
        }
    }

    private AccessLease acquireLease(final long expectedGeneration) {
        synchronized (callGate) {
            if (!acceptingCalls || generation != expectedGeneration) {
                throw staleFailure();
            }
            inFlight++;
            return new AccessLease(current, generation);
        }
    }

    private void release(final AccessLease lease) {
        synchronized (callGate) {
            inFlight--;
            if (inFlight == 0) {
                callGate.notifyAll();
            }
        }
    }

    private boolean awaitNoInFlight() {
        boolean interrupted = false;
        while (inFlight != 0) {
            try {
                callGate.wait();
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        return interrupted;
    }

    private static void restoreInterrupt(final boolean interrupted) {
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static IllegalStateException staleFailure() {
        return new IllegalStateException(
            "Cubism model reference is stale for the active host session."
        );
    }

    private <T> T current(
        final long expectedGeneration,
        final Function<CubismModel, T> operation,
        final CubismModel model
    ) {
        return guarded(expectedGeneration, () -> operation.apply(model));
    }

    private final class SessionModel implements CubismModel {

        private final long generation;
        private final CubismModel delegate;

        private SessionModel(final long generation, final CubismModel delegate) {
            this.generation = generation;
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public dev.turboism.sdk.cubism.id.ModelId id() {
            return current(generation, CubismModel::id, delegate);
        }

        @Override public String name() {
            return current(generation, CubismModel::name, delegate);
        }
        @Override public void setName(final String name) {
            guardedVoid(generation, () -> delegate.setName(name));
        }
        @Override public dev.turboism.sdk.cubism.core.MocInfo mocInfo() {
            return current(generation, CubismModel::mocInfo, delegate);
        }
        @Override public dev.turboism.sdk.cubism.model.ParameterDefinitions parameterDefinitions() {
            return new SessionParameterDefinitions(
                generation,
                current(generation, CubismModel::parameterDefinitions, delegate)
            );
        }

        @Override
        public boolean defaultKeyformLocked() {
            return current(generation, CubismModel::defaultKeyformLocked, delegate);
        }

        @Override
        public void setDefaultKeyformLocked(final boolean locked) {
            guardedVoid(generation, () -> delegate.setDefaultKeyformLocked(locked));
        }

        @Override
        public Parameters parameters() {
            return new SessionParameters(generation, current(
                generation,
                CubismModel::parameters,
                delegate
            ));
        }

        @Override
        public ParameterGroups parameterGroups() {
            return new SessionParameterGroups(
                generation,
                current(generation, CubismModel::parameterGroups, delegate)
            );
        }

        @Override
        public dev.turboism.sdk.cubism.model.ParameterBindingOperations parameterBindings(
            final dev.turboism.sdk.cubism.id.ParameterId parameterId
        ) {
            final dev.turboism.sdk.cubism.model.ParameterBindingOperations operations = current(
                generation,
                model -> model.parameterBindings(parameterId),
                delegate
            );
            return new dev.turboism.sdk.cubism.model.ParameterBindingOperations() {
                @Override public void bind(
                    final dev.turboism.sdk.cubism.model.ParameterBindingTarget target,
                    final List<dev.turboism.sdk.cubism.model.ParameterBindingPoint> points
                ) {
                    guardedVoid(generation, () -> operations.bind(target, points));
                }
                @Override public void createPoint(
                    final dev.turboism.sdk.cubism.model.ParameterBindingTarget target,
                    final dev.turboism.sdk.cubism.model.ParameterBindingPoint point
                ) {
                    guardedVoid(generation, () -> operations.createPoint(target, point));
                }
                @Override public void movePoint(
                    final dev.turboism.sdk.cubism.model.ParameterBindingTarget target,
                    final dev.turboism.sdk.cubism.id.ParameterBindingPointId pointId,
                    final float value
                ) {
                    guardedVoid(generation, () -> operations.movePoint(target, pointId, value));
                }
                @Override public void deletePoint(
                    final dev.turboism.sdk.cubism.model.ParameterBindingTarget target,
                    final dev.turboism.sdk.cubism.id.ParameterBindingPointId pointId
                ) {
                    guardedVoid(generation, () -> operations.deletePoint(target, pointId));
                }
                @Override public void unbind(
                    final dev.turboism.sdk.cubism.model.ParameterBindingTarget target
                ) {
                    guardedVoid(generation, () -> operations.unbind(target));
                }
            };
        }

        @Override
        public dev.turboism.sdk.cubism.model.ParameterBindingBatchOperations parameterBindingBatch() {
            final dev.turboism.sdk.cubism.model.ParameterBindingBatchOperations operations = current(
                generation,
                CubismModel::parameterBindingBatch,
                delegate
            );
            return new dev.turboism.sdk.cubism.model.ParameterBindingBatchOperations() {
                @Override public void invert(
                    final List<dev.turboism.sdk.cubism.model.ParameterBindingTarget> targets
                ) {
                    guardedVoid(generation, () -> operations.invert(targets));
                }
                @Override public void transfer(
                    final dev.turboism.sdk.cubism.model.ParameterBindingTransferPlan plan
                ) {
                    guardedVoid(generation, () -> operations.transfer(plan));
                }
            };
        }

        @Override
        public Canvas canvas() {
            return new SessionCanvas(generation, current(generation, CubismModel::canvas, delegate));
        }

        @Override
        public Parts parts() {
            return new SessionParts(generation, current(generation, CubismModel::parts, delegate));
        }

        @Override
        public Drawables drawables() {
            return new SessionDrawables(
                generation,
                current(generation, CubismModel::drawables, delegate)
            );
        }

        @Override
        public Deformers deformers() {
            return new SessionDeformers(
                generation,
                current(generation, CubismModel::deformers, delegate)
            );
        }

        @Override
        public WarpDeformers warpDeformers() {
            return new SessionWarpDeformers(
                generation,
                current(generation, CubismModel::warpDeformers, delegate)
            );
        }

        @Override
        public RotationDeformers rotationDeformers() {
            return new SessionRotationDeformers(
                generation,
                current(generation, CubismModel::rotationDeformers, delegate)
            );
        }

        @Override
        public Glues glues() {
            return new SessionGlues(generation, current(generation, CubismModel::glues, delegate));
        }

        @Override
        public void update() {
            guardedVoid(generation, delegate::update);
        }
    }

    private final class SessionCanvas implements Canvas {
        private final long generation;
        private final Canvas delegate;
        private SessionCanvas(final long generation, final Canvas delegate) {
            this.generation = generation;
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }
        @Override public float widthPixels() { return guarded(generation, delegate::widthPixels); }
        @Override public float heightPixels() { return guarded(generation, delegate::heightPixels); }
        @Override public float originXPixels() { return guarded(generation, delegate::originXPixels); }
        @Override public float originYPixels() { return guarded(generation, delegate::originYPixels); }
        @Override public float pixelsPerUnit() { return guarded(generation, delegate::pixelsPerUnit); }
    }


    private final class SessionParameterDefinitions
        implements dev.turboism.sdk.cubism.model.ParameterDefinitions {
        private final long generation;
        private final dev.turboism.sdk.cubism.model.ParameterDefinitions delegate;
        private SessionParameterDefinitions(
            final long generation,
            final dev.turboism.sdk.cubism.model.ParameterDefinitions delegate
        ) {
            this.generation = generation;
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }
        @Override public List<dev.turboism.sdk.cubism.model.ParameterDefinition> all() {
            return guarded(generation, delegate::all);
        }
        @Override public dev.turboism.sdk.cubism.model.ParameterDefinition find(
            final dev.turboism.sdk.cubism.id.ParameterId id
        ) {
            return guarded(generation, () -> delegate.find(id));
        }
    }

    private final class SessionParameters implements Parameters {
        private final long generation;
        private final Parameters delegate;

        private SessionParameters(final long generation, final Parameters delegate) {
            this.generation = generation;
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public List<Parameter> all() {
            return guarded(generation, () -> delegate.all().stream()
                .map(value -> (Parameter) new SessionParameter(generation, value))
                .toList());
        }

        @Override
        public Parameter find(final dev.turboism.sdk.cubism.id.ParameterId id) {
            return guarded(
                generation,
                () -> new SessionParameter(generation, delegate.find(id))
            );
        }
    }

    private final class SessionParameterGroups implements ParameterGroups {
        private final long generation;
        private final ParameterGroups delegate;
        private SessionParameterGroups(final long generation, final ParameterGroups delegate) {
            this.generation = generation;
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }
        @Override public List<ParameterGroup> all() {
            return guarded(generation, () -> delegate.all().stream()
                .map(value -> (ParameterGroup) new SessionParameterGroup(generation, value))
                .toList());
        }
        @Override public ParameterGroup root() {
            return guarded(
                generation,
                () -> new SessionParameterGroup(generation, delegate.root())
            );
        }
        @Override public ParameterGroup find(
            final dev.turboism.sdk.cubism.id.ParameterGroupId id
        ) {
            return guarded(
                generation,
                () -> new SessionParameterGroup(generation, delegate.find(id))
            );
        }
    }

    private final class SessionParameterGroup implements ParameterGroup {
        private final long generation;
        private final ParameterGroup delegate;
        private SessionParameterGroup(final long generation, final ParameterGroup delegate) {
            this.generation = generation;
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }
        @Override public dev.turboism.sdk.cubism.id.ParameterGroupId id() {
            return guarded(generation, delegate::id);
        }
        @Override public java.util.Optional<String> name() {
            return guarded(generation, delegate::name);
        }
        @Override public java.util.Optional<dev.turboism.sdk.cubism.id.ParameterGroupId> parentId() {
            return guarded(generation, delegate::parentId);
        }
        @Override public List<dev.turboism.sdk.cubism.id.ParameterGroupId> childGroupIds() {
            return guarded(generation, delegate::childGroupIds);
        }
        @Override public List<dev.turboism.sdk.cubism.id.ParameterId> parameterIds() {
            return guarded(generation, delegate::parameterIds);
        }
    }

    private final class SessionParameter implements Parameter {
        private final long generation;
        private final Parameter delegate;

        private SessionParameter(final long generation, final Parameter delegate) {
            this.generation = generation;
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override public dev.turboism.sdk.cubism.id.ParameterId id() {
            return guarded(generation, delegate::id);
        }

        @Override public int index() { return guarded(generation, delegate::index); }
        @Override public dev.turboism.sdk.cubism.model.FloatSequence keyValues() {
            return new SessionFloatSequence(generation, guarded(generation, delegate::keyValues));
        }
        @Override public java.util.Optional<String> name() {
            return guarded(generation, delegate::name);
        }
        @Override public dev.turboism.sdk.cubism.model.ParameterType type() {
            return guarded(generation, delegate::type);
        }
        @Override public java.util.Optional<Boolean> repeat() {
            return guarded(generation, delegate::repeat);
        }
        @Override public java.util.Optional<Boolean> combined() {
            return guarded(generation, delegate::combined);
        }
        @Override public java.util.Optional<dev.turboism.sdk.cubism.id.ParameterId> combinedWith() {
            return guarded(generation, delegate::combinedWith);
        }
        @Override public List<dev.turboism.sdk.cubism.model.ParameterBinding> getParameterBindings() {
            return guarded(generation, delegate::getParameterBindings);
        }
        @Override public void combineWith(
            final dev.turboism.sdk.cubism.id.ParameterId partnerId
        ) {
            guardedVoid(generation, () -> delegate.combineWith(partnerId));
        }
        @Override public void uncombine() {
            guardedVoid(generation, delegate::uncombine);
        }
        @Override public float getValue() { return guarded(generation, delegate::getValue); }
        @Override public float getMinimumValue() { return guarded(generation, delegate::getMinimumValue); }
        @Override public float getMaximumValue() { return guarded(generation, delegate::getMaximumValue); }
        @Override public float getDefaultValue() { return guarded(generation, delegate::getDefaultValue); }
        @Override public void setValue(final float value) {
            guardedVoid(generation, () -> delegate.setValue(value));
        }
        @Override public void updateDefinition(
            final dev.turboism.sdk.cubism.model.ParameterDefinition definition
        ) {
            guardedVoid(generation, () -> delegate.updateDefinition(definition));
        }
    }

    private final class SessionParts implements Parts {
        private final long generation;
        private final Parts delegate;
        private SessionParts(final long generation, final Parts delegate) {
            this.generation = generation;
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }
        @Override public List<Part> all() {
            return guarded(generation, () -> delegate.all().stream()
                .map(value -> (Part) new SessionPart(generation, value)).toList());
        }
        @Override public Part find(final PartId id) {
            return guarded(generation, () -> new SessionPart(generation, delegate.find(id)));
        }
    }

    private final class SessionPart implements Part {
        private final long generation;
        private final Part delegate;
        private SessionPart(final long generation, final Part delegate) {
            this.generation = generation;
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }
        @Override public PartId id() { return guarded(generation, delegate::id); }

        @Override public int index() { return guarded(generation, delegate::index); }
        @Override public String name() { return guarded(generation, delegate::name); }
        @Override public void setName(final String name) {
            guardedVoid(generation, () -> delegate.setName(name));
        }

        @Override public java.util.Optional<String> shortName() {
            return guarded(generation, delegate::shortName);
        }
        @Override public void setShortName(final java.util.Optional<String> value) {
            guardedVoid(generation, () -> delegate.setShortName(value));
        }
        @Override public java.util.Optional<PartId> parentId() {
            return guarded(generation, delegate::parentId);
        }
        @Override public List<PartId> childIds() {
            return guarded(generation, delegate::childIds);
        }
        @Override public boolean visible() { return guarded(generation, delegate::visible); }
        @Override public void setVisible(final boolean value) {
            guardedVoid(generation, () -> delegate.setVisible(value));
        }
        @Override public boolean visibleInHierarchy() {
            return guarded(generation, delegate::visibleInHierarchy);
        }
        @Override public boolean locked() { return guarded(generation, delegate::locked); }
        @Override public void setLocked(final boolean value) {
            guardedVoid(generation, () -> delegate.setLocked(value));
        }
        @Override public boolean lockedInHierarchy() {
            return guarded(generation, delegate::lockedInHierarchy);
        }
        @Override public java.util.Optional<Color> editColor() {
            return guarded(generation, delegate::editColor);
        }
        @Override public void setEditColor(final java.util.Optional<Color> value) {
            guardedVoid(generation, () -> delegate.setEditColor(value));
        }
        @Override public boolean sketch() { return guarded(generation, delegate::sketch); }
        @Override public void setSketch(final boolean value) {
            guardedVoid(generation, () -> delegate.setSketch(value));
        }
        @Override public int defaultOrder() { return guarded(generation, delegate::defaultOrder); }
        @Override public void setDefaultOrder(final int value) {
            guardedVoid(generation, () -> delegate.setDefaultOrder(value));
        }
        @Override public float getOpacity() { return guarded(generation, delegate::getOpacity); }
        @Override public int parentIndex() { return guarded(generation, delegate::parentIndex); }
        @Override public void setOpacity(final float opacity) {
            guardedVoid(generation, () -> delegate.setOpacity(opacity));
        }
    }

    private final class SessionDrawables implements Drawables {
        private final long generation;
        private final Drawables delegate;
        private SessionDrawables(final long generation, final Drawables delegate) {
            this.generation = generation;
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }
        @Override public List<Drawable> all() {
            return guarded(generation, () -> delegate.all().stream()
                .map(value -> (Drawable) new SessionDrawable(generation, value)).toList());
        }
        @Override public Drawable find(final ArtMeshId id) {
            return guarded(generation, () -> new SessionDrawable(generation, delegate.find(id)));
        }
    }

    private final class SessionDrawable implements Drawable {
        private final long generation;
        private final Drawable delegate;
        private SessionDrawable(final long generation, final Drawable delegate) {
            this.generation = generation;
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }
        @Override public ArtMeshId id() { return guarded(generation, delegate::id); }

        @Override public int index() { return guarded(generation, delegate::index); }
        @Override public boolean doubleSided() {
            return guarded(generation, delegate::doubleSided);
        }
        @Override public dev.turboism.sdk.cubism.model.DrawableEvaluationState evaluationState() {
            return guarded(generation, delegate::evaluationState);
        }
        @Override public java.util.Optional<PartId> parentPartId() {
            return guarded(generation, delegate::parentPartId);
        }
        @Override public java.util.Optional<DeformerId> parentDeformerId() {
            return guarded(generation, delegate::parentDeformerId);
        }
        @Override public List<dev.turboism.sdk.cubism.id.ParameterId> parameterIds() {
            return guarded(generation, delegate::parameterIds);
        }
        @Override public List<ArtMeshId> maskIds() {
            return guarded(generation, delegate::maskIds);
        }
        @Override public String name() { return guarded(generation, delegate::name); }
        @Override public boolean visible() { return guarded(generation, delegate::visible); }
        @Override public void setVisible(final boolean visible) {
            guardedVoid(generation, () -> delegate.setVisible(visible));
        }
        @Override public boolean locked() { return guarded(generation, delegate::locked); }
        @Override public void setLocked(final boolean locked) {
            guardedVoid(generation, () -> delegate.setLocked(locked));
        }
        @Override public boolean visibleInHierarchy() {
            return guarded(generation, delegate::visibleInHierarchy);
        }
        @Override public boolean lockedInHierarchy() {
            return guarded(generation, delegate::lockedInHierarchy);
        }
        @Override public byte constantFlag() { return guarded(generation, delegate::constantFlag); }
        @Override public byte dynamicFlag() { return guarded(generation, delegate::dynamicFlag); }
        @Override public BlendMode blendMode() { return guarded(generation, delegate::blendMode); }
        @Override public int textureIndex() { return guarded(generation, delegate::textureIndex); }
        @Override public int drawOrder() { return guarded(generation, delegate::drawOrder); }
        @Override public int renderOrder() { return guarded(generation, delegate::renderOrder); }
        @Override public float getOpacity() { return guarded(generation, delegate::getOpacity); }
        @Override public void setOpacity(final float opacity) {
            guardedVoid(generation, () -> delegate.setOpacity(opacity));
        }
        @Override public ArtMeshGeometry geometry() {
            return guarded(generation, delegate::geometry);
        }
        @Override public void replaceGeometry(final ArtMeshGeometry geometry) {
            guardedVoid(generation, () -> delegate.replaceGeometry(geometry));
        }
        @Override public dev.turboism.sdk.cubism.model.IntSequence masks() {
            return new SessionIntSequence(generation, guarded(generation, delegate::masks));
        }
        @Override public boolean invertedMask() {
            return guarded(generation, delegate::invertedMask);
        }
        @Override public boolean culling() { return guarded(generation, delegate::culling); }
        @Override public String userData() { return guarded(generation, delegate::userData); }
        @Override public dev.turboism.sdk.cubism.model.FloatSequence vertexPositions() {
            return new SessionFloatSequence(
                generation,
                guarded(generation, delegate::vertexPositions)
            );
        }
        @Override public dev.turboism.sdk.cubism.model.FloatSequence vertexUvs() {
            return new SessionFloatSequence(
                generation,
                guarded(generation, delegate::vertexUvs)
            );
        }
        @Override public dev.turboism.sdk.cubism.model.IntSequence indices() {
            return new SessionIntSequence(generation, guarded(generation, delegate::indices));
        }
        @Override public Color multiplyColor() { return guarded(generation, delegate::multiplyColor); }
        @Override public Color screenColor() { return guarded(generation, delegate::screenColor); }
        @Override public int parentPartIndex() { return guarded(generation, delegate::parentPartIndex); }
        @Override public int parentDeformerIndex() { return guarded(generation, delegate::parentDeformerIndex); }
        @Override public dev.turboism.sdk.cubism.model.IntSequence parameters() {
            return new SessionIntSequence(generation, guarded(generation, delegate::parameters));
        }
        @Override public List<dev.turboism.sdk.cubism.model.ParameterBinding> getParameterBindings() {
            return guarded(generation, delegate::getParameterBindings);
        }
    }

    private final class SessionFloatSequence
        implements dev.turboism.sdk.cubism.model.FloatSequence {
        private final long generation;
        private final dev.turboism.sdk.cubism.model.FloatSequence delegate;
        private SessionFloatSequence(
            final long generation,
            final dev.turboism.sdk.cubism.model.FloatSequence delegate
        ) {
            this.generation = generation;
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }
        @Override public int size() { return guarded(generation, delegate::size); }
        @Override public float get(final int index) {
            return guarded(generation, () -> delegate.get(index));
        }
    }

    private final class SessionIntSequence
        implements dev.turboism.sdk.cubism.model.IntSequence {
        private final long generation;
        private final dev.turboism.sdk.cubism.model.IntSequence delegate;
        private SessionIntSequence(
            final long generation,
            final dev.turboism.sdk.cubism.model.IntSequence delegate
        ) {
            this.generation = generation;
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }
        @Override public int size() { return guarded(generation, delegate::size); }
        @Override public int get(final int index) {
            return guarded(generation, () -> delegate.get(index));
        }
    }

    private final class SessionDeformers implements Deformers {
        private final long generation;
        private final Deformers delegate;
        private SessionDeformers(final long generation, final Deformers delegate) {
            this.generation = generation;
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }
        @Override public List<Deformer> all() {
            return guarded(generation, () -> delegate.all().stream()
                .map(value -> (Deformer) new SessionDeformer(generation, value)).toList());
        }
        @Override public Deformer find(final DeformerId id) {
            return guarded(generation, () -> new SessionDeformer(generation, delegate.find(id)));
        }
    }

    private final class SessionDeformer implements Deformer {
        private final long generation;
        private final Deformer delegate;
        private SessionDeformer(final long generation, final Deformer delegate) {
            this.generation = generation;
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }
        @Override public DeformerId id() { return guarded(generation, delegate::id); }

        @Override public int index() { return guarded(generation, delegate::index); }
        @Override public java.util.Optional<PartId> parentPartId() {
            return guarded(generation, delegate::parentPartId);
        }
        @Override public java.util.Optional<DeformerId> parentDeformerId() {
            return guarded(generation, delegate::parentDeformerId);
        }
        @Override public List<dev.turboism.sdk.cubism.id.ParameterId> parameterIds() {
            return guarded(generation, delegate::parameterIds);
        }
        @Override public String name() { return guarded(generation, delegate::name); }
        @Override public boolean visible() { return guarded(generation, delegate::visible); }
        @Override public void setVisible(final boolean visible) {
            guardedVoid(generation, () -> delegate.setVisible(visible));
        }
        @Override public boolean locked() { return guarded(generation, delegate::locked); }
        @Override public void setLocked(final boolean locked) {
            guardedVoid(generation, () -> delegate.setLocked(locked));
        }
        @Override public boolean visibleInHierarchy() {
            return guarded(generation, delegate::visibleInHierarchy);
        }
        @Override public boolean lockedInHierarchy() {
            return guarded(generation, delegate::lockedInHierarchy);
        }
        @Override public float getOpacity() { return guarded(generation, delegate::getOpacity); }
        @Override public void setOpacity(final float opacity) {
            guardedVoid(generation, () -> delegate.setOpacity(opacity));
        }
        @Override public Color multiplyColor() { return guarded(generation, delegate::multiplyColor); }
        @Override public Color screenColor() { return guarded(generation, delegate::screenColor); }
        @Override public int parentPartIndex() { return guarded(generation, delegate::parentPartIndex); }
        @Override public int parentDeformerIndex() { return guarded(generation, delegate::parentDeformerIndex); }
        @Override public dev.turboism.sdk.cubism.model.IntSequence parameters() {
            return new SessionIntSequence(generation, guarded(generation, delegate::parameters));
        }
        @Override public List<dev.turboism.sdk.cubism.model.ParameterBinding> getParameterBindings() {
            return guarded(generation, delegate::getParameterBindings);
        }
    }

    private final class SessionWarpDeformers implements WarpDeformers {
        private final long generation;
        private final WarpDeformers delegate;
        private SessionWarpDeformers(final long generation, final WarpDeformers delegate) {
            this.generation = generation;
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }
        @Override public List<WarpDeformer> all() {
            return guarded(generation, () -> delegate.all().stream()
                .map(value -> (WarpDeformer) new SessionWarpDeformer(generation, value))
                .toList());
        }
        @Override public WarpDeformer find(final DeformerId id) {
            return guarded(
                generation,
                () -> new SessionWarpDeformer(generation, delegate.find(id))
            );
        }
    }

    private final class SessionWarpDeformer implements WarpDeformer {
        private final long generation;
        private final WarpDeformer delegate;
        private SessionWarpDeformer(final long generation, final WarpDeformer delegate) {
            this.generation = generation;
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }
        @Override public DeformerId id() { return guarded(generation, delegate::id); }

        @Override public int index() { return guarded(generation, delegate::index); }
        @Override public java.util.Optional<PartId> parentPartId() {
            return guarded(generation, delegate::parentPartId);
        }
        @Override public java.util.Optional<DeformerId> parentDeformerId() {
            return guarded(generation, delegate::parentDeformerId);
        }
        @Override public List<dev.turboism.sdk.cubism.id.ParameterId> parameterIds() {
            return guarded(generation, delegate::parameterIds);
        }
        @Override public String name() { return guarded(generation, delegate::name); }
        @Override public boolean visible() { return guarded(generation, delegate::visible); }
        @Override public void setVisible(final boolean visible) {
            guardedVoid(generation, () -> delegate.setVisible(visible));
        }
        @Override public boolean locked() { return guarded(generation, delegate::locked); }
        @Override public void setLocked(final boolean locked) {
            guardedVoid(generation, () -> delegate.setLocked(locked));
        }
        @Override public boolean visibleInHierarchy() {
            return guarded(generation, delegate::visibleInHierarchy);
        }
        @Override public boolean lockedInHierarchy() {
            return guarded(generation, delegate::lockedInHierarchy);
        }
        @Override public float getOpacity() { return guarded(generation, delegate::getOpacity); }
        @Override public void setOpacity(final float opacity) {
            guardedVoid(generation, () -> delegate.setOpacity(opacity));
        }
        @Override public Color multiplyColor() { return guarded(generation, delegate::multiplyColor); }
        @Override public Color screenColor() { return guarded(generation, delegate::screenColor); }
        @Override public int parentPartIndex() { return guarded(generation, delegate::parentPartIndex); }
        @Override public int parentDeformerIndex() { return guarded(generation, delegate::parentDeformerIndex); }
        @Override public dev.turboism.sdk.cubism.model.IntSequence parameters() {
            return new SessionIntSequence(generation, guarded(generation, delegate::parameters));
        }
        @Override public List<dev.turboism.sdk.cubism.model.ParameterBinding> getParameterBindings() {
            return guarded(generation, delegate::getParameterBindings);
        }
        @Override public WarpGrid grid() { return guarded(generation, delegate::grid); }
        @Override public void replaceGrid(final WarpGrid grid) {
            guardedVoid(generation, () -> delegate.replaceGrid(grid));
        }
    }

    private final class SessionRotationDeformers implements RotationDeformers {
        private final long generation;
        private final RotationDeformers delegate;
        private SessionRotationDeformers(
            final long generation,
            final RotationDeformers delegate
        ) {
            this.generation = generation;
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }
        @Override public List<RotationDeformer> all() {
            return guarded(generation, () -> delegate.all().stream()
                .map(value -> (RotationDeformer) new SessionRotationDeformer(generation, value))
                .toList());
        }
        @Override public RotationDeformer find(final DeformerId id) {
            return guarded(
                generation,
                () -> new SessionRotationDeformer(generation, delegate.find(id))
            );
        }
    }

    private final class SessionRotationDeformer implements RotationDeformer {
        private final long generation;
        private final RotationDeformer delegate;
        private SessionRotationDeformer(
            final long generation,
            final RotationDeformer delegate
        ) {
            this.generation = generation;
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }
        @Override public DeformerId id() { return guarded(generation, delegate::id); }

        @Override public int index() { return guarded(generation, delegate::index); }
        @Override public java.util.Optional<PartId> parentPartId() {
            return guarded(generation, delegate::parentPartId);
        }
        @Override public java.util.Optional<DeformerId> parentDeformerId() {
            return guarded(generation, delegate::parentDeformerId);
        }
        @Override public List<dev.turboism.sdk.cubism.id.ParameterId> parameterIds() {
            return guarded(generation, delegate::parameterIds);
        }
        @Override public String name() { return guarded(generation, delegate::name); }
        @Override public boolean visible() { return guarded(generation, delegate::visible); }
        @Override public void setVisible(final boolean visible) {
            guardedVoid(generation, () -> delegate.setVisible(visible));
        }
        @Override public boolean locked() { return guarded(generation, delegate::locked); }
        @Override public void setLocked(final boolean locked) {
            guardedVoid(generation, () -> delegate.setLocked(locked));
        }
        @Override public boolean visibleInHierarchy() {
            return guarded(generation, delegate::visibleInHierarchy);
        }
        @Override public boolean lockedInHierarchy() {
            return guarded(generation, delegate::lockedInHierarchy);
        }
        @Override public float getOpacity() { return guarded(generation, delegate::getOpacity); }
        @Override public void setOpacity(final float opacity) {
            guardedVoid(generation, () -> delegate.setOpacity(opacity));
        }
        @Override public Color multiplyColor() { return guarded(generation, delegate::multiplyColor); }
        @Override public Color screenColor() { return guarded(generation, delegate::screenColor); }
        @Override public int parentPartIndex() { return guarded(generation, delegate::parentPartIndex); }
        @Override public int parentDeformerIndex() { return guarded(generation, delegate::parentDeformerIndex); }
        @Override public dev.turboism.sdk.cubism.model.IntSequence parameters() {
            return new SessionIntSequence(generation, guarded(generation, delegate::parameters));
        }
        @Override public List<dev.turboism.sdk.cubism.model.ParameterBinding> getParameterBindings() {
            return guarded(generation, delegate::getParameterBindings);
        }
        @Override public float baseAngle() { return guarded(generation, delegate::baseAngle); }
        @Override public void setBaseAngle(final float angle) {
            guardedVoid(generation, () -> delegate.setBaseAngle(angle));
        }
        @Override public RotationDeformerForm form() {
            return guarded(generation, delegate::form);
        }
        @Override public void replaceForm(final RotationDeformerForm form) {
            guardedVoid(generation, () -> delegate.replaceForm(form));
        }
    }

    private final class SessionGlues implements Glues {
        private final long generation;
        private final Glues delegate;
        private SessionGlues(final long generation, final Glues delegate) {
            this.generation = generation;
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }
        @Override public List<Glue> all() {
            return guarded(generation, () -> delegate.all().stream()
                .map(value -> (Glue) new SessionGlue(generation, value)).toList());
        }
        @Override public Glue find(final GlueId id) {
            return guarded(generation, () -> new SessionGlue(generation, delegate.find(id)));
        }
    }

    private final class SessionGlue implements Glue {
        private final long generation;
        private final Glue delegate;
        private SessionGlue(final long generation, final Glue delegate) {
            this.generation = generation;
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }
        @Override public GlueId id() { return guarded(generation, delegate::id); }

        @Override public int index() { return guarded(generation, delegate::index); }
        @Override public ArtMeshId drawableAId() {
            return guarded(generation, delegate::drawableAId);
        }
        @Override public ArtMeshId drawableBId() {
            return guarded(generation, delegate::drawableBId);
        }
        @Override public List<dev.turboism.sdk.cubism.id.ParameterId> parameterIds() {
            return guarded(generation, delegate::parameterIds);
        }
        @Override public int drawableA() { return guarded(generation, delegate::drawableA); }
        @Override public int drawableB() { return guarded(generation, delegate::drawableB); }
        @Override public dev.turboism.sdk.cubism.model.IntSequence parameters() {
            return new SessionIntSequence(generation, guarded(generation, delegate::parameters));
        }
    }

    private <T> T guarded(final long expectedGeneration, final java.util.function.Supplier<T> call) {
        final AccessLease lease = acquireLease(expectedGeneration);
        try {
            return call.get();
        } finally {
            release(lease);
        }
    }

    private void guardedVoid(final long expectedGeneration, final Runnable call) {
        final AccessLease lease = acquireLease(expectedGeneration);
        try {
            call.run();
        } finally {
            release(lease);
        }
    }

    @Override
    public NativeControlAppearance snapshot(final ControlAppearanceTarget target) {
        final AccessLease lease = acquireActiveLease();
        try {
            return authoring(lease).snapshot(target);
        } finally {
            release(lease);
        }
    }

    @Override
    public void setNativeBackground(
        final ControlAppearanceTarget target,
        final NativeControlBackground background
    ) {
        final AccessLease lease = acquireActiveLease();
        try {
            authoring(lease).setNativeBackground(target, background);
        } finally {
            release(lease);
        }
    }

    private static NativeControlAppearanceAuthoring authoring(final AccessLease lease) {
        if (!(lease.modelAccess() instanceof NativeControlAppearanceAuthoring authoring)) {
            throw new UnsupportedOperationException(
                "Native control appearance authoring is unavailable for the active host session."
            );
        }
        return authoring;
    }

    private record AccessLease(CubismModelAccess modelAccess, long generation) {
    }
}

package dev.turboism.adapter.host;

import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.ArtMeshSourceTriangulation;
import dev.turboism.sdk.cubism.model.BlendMode;
import dev.turboism.sdk.cubism.model.Color;
import dev.turboism.sdk.cubism.model.Deformer;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.Drawables;
import dev.turboism.sdk.cubism.model.Part;
import dev.turboism.sdk.cubism.model.PartId;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.ui.appearance.model.DrawableAppearance;
import java.util.List;
import java.util.Objects;

final class SessionDrawables implements Drawables {
    private final DynamicCubismModelAccess host;
    final long generation;
    final long modelGeneration;
    final ModelId modelId;
    final Drawables delegate;

    SessionDrawables(
            final DynamicCubismModelAccess host,
            final long generation,
            final long modelGeneration,
            final ModelId modelId,
            final Drawables delegate) {
        this.host = Objects.requireNonNull(host, "host");
        this.generation = generation;
        this.modelGeneration = modelGeneration;
        this.modelId = Objects.requireNonNull(modelId, "modelId");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public List<Drawable> all() {
        return host.guarded(
                generation,
                () -> delegate.all().stream()
                        .map(value -> (Drawable) new SessionDrawable(host, generation, modelGeneration, modelId, value))
                        .toList());
    }

    @Override
    public Drawable find(final ArtMeshId id) {
        return host.guarded(
                generation, () -> new SessionDrawable(host, generation, modelGeneration, modelId, delegate.find(id)));
    }

    @Override
    public Drawable create(final String name, final Part parent, final int index, final ArtMeshGeometry geometry) {
        return host.guarded(
                generation,
                () -> new SessionDrawable(
                        host,
                        generation,
                        modelGeneration,
                        modelId,
                        delegate.create(name, host.unwrapPart(generation, parent), index, geometry)));
    }

    @Override
    public void remove(final Drawable drawable) {
        host.guardedVoid(generation, () -> delegate.remove(host.unwrapDrawable(generation, drawable)));
    }

    @Override
    public Drawable createWithSourceGeometry(
            final String name,
            final Part parent,
            final int index,
            final ArtMeshGeometry sourceGeometry,
            final List<Point2> defaultPositions) {
        return host.guarded(
                generation,
                () -> new SessionDrawable(
                        host,
                        generation,
                        modelGeneration,
                        modelId,
                        delegate.createWithSourceGeometry(
                                name, host.unwrapPart(generation, parent), index, sourceGeometry, defaultPositions)));
    }

    @Override
    public List<Drawable> createManyWithSourceGeometry(final List<SourceGeometryRequest> requests) {
        return host.guarded(
                generation,
                () -> delegate.createManyWithSourceGeometry(List.copyOf(requests)).stream()
                        .map(value -> (Drawable) new SessionDrawable(host, generation, modelGeneration, modelId, value))
                        .toList());
    }

    @Override
    public ArtMeshSourceTriangulation previewSourceTriangulation(final ArtMeshGeometry geometry) {
        return host.guarded(
                generation, () -> delegate.previewSourceTriangulation(Objects.requireNonNull(geometry, "geometry")));
    }

    @Override
    public void select(final List<ArtMeshId> ids) {
        final List<ArtMeshId> checked = List.copyOf(Objects.requireNonNull(ids, "ids"));
        host.guardedVoid(generation, () -> delegate.select(checked));
    }
}

final class SessionDrawable implements Drawable {
    final DynamicCubismModelAccess host;
    final long generation;
    final long modelGeneration;
    final ModelId modelId;
    final Drawable delegate;

    SessionDrawable(
            final DynamicCubismModelAccess host,
            final long generation,
            final long modelGeneration,
            final ModelId modelId,
            final Drawable delegate) {
        this.host = Objects.requireNonNull(host, "host");
        this.generation = generation;
        this.modelGeneration = modelGeneration;
        this.modelId = Objects.requireNonNull(modelId, "modelId");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public ArtMeshId id() {
        return host.guarded(generation, delegate::id);
    }

    @Override
    public dev.turboism.sdk.cubism.model.MorphTargets morphTargets() {
        return host.guarded(generation, delegate::morphTargets);
    }

    @Override
    public DrawableAppearance ui() {
        final ArtMeshId id = id();
        return host.appearanceDrawable(modelId, id, modelGeneration);
    }

    @Override
    public int index() {
        return host.guarded(generation, delegate::index);
    }

    @Override
    public boolean doubleSided() {
        return host.guarded(generation, delegate::doubleSided);
    }

    @Override
    public dev.turboism.sdk.cubism.model.DrawableEvaluationState evaluationState() {
        return host.guarded(generation, delegate::evaluationState);
    }

    @Override
    public java.util.Optional<PartId> parentPartId() {
        return host.guarded(generation, delegate::parentPartId);
    }

    @Override
    public java.util.Optional<DeformerId> parentDeformerId() {
        return host.guarded(generation, delegate::parentDeformerId);
    }

    @Override
    public List<dev.turboism.sdk.cubism.id.ParameterId> parameterIds() {
        return host.guarded(generation, delegate::parameterIds);
    }

    @Override
    public List<ArtMeshId> maskIds() {
        return host.guarded(generation, delegate::maskIds);
    }

    @Override
    public String name() {
        return host.guarded(generation, delegate::name);
    }

    @Override
    public String guid() {
        return host.guarded(generation, delegate::guid);
    }

    @Override
    public void setName(final String name) {
        host.guardedVoid(generation, () -> delegate.setName(name));
    }

    @Override
    public void setId(final String id) {
        host.guardedVoid(generation, () -> delegate.setId(id));
    }

    @Override
    public void setTargetDeformer(final java.util.Optional<DeformerId> targetDeformer) {
        host.guardedVoid(generation, () -> delegate.setTargetDeformer(targetDeformer));
    }

    @Override
    public void setClippingMaskIds(final List<ArtMeshId> maskIds) {
        host.guardedVoid(generation, () -> delegate.setClippingMaskIds(maskIds));
    }

    @Override
    public void setInvertedMask(final boolean inverted) {
        host.guardedVoid(generation, () -> delegate.setInvertedMask(inverted));
    }

    @Override
    public void setDrawOrder(final int drawOrder) {
        host.guardedVoid(generation, () -> delegate.setDrawOrder(drawOrder));
    }

    @Override
    public void setMultiplyColor(final Color color) {
        host.guardedVoid(generation, () -> delegate.setMultiplyColor(color));
    }

    @Override
    public void setScreenColor(final Color color) {
        host.guardedVoid(generation, () -> delegate.setScreenColor(color));
    }

    @Override
    public void setColorComposition(final dev.turboism.sdk.cubism.model.ColorComposition composition) {
        host.guardedVoid(generation, () -> delegate.setColorComposition(composition));
    }

    @Override
    public void setAlphaComposition(final dev.turboism.sdk.cubism.model.AlphaComposition composition) {
        host.guardedVoid(generation, () -> delegate.setAlphaComposition(composition));
    }

    @Override
    public void setCulling(final boolean culling) {
        host.guardedVoid(generation, () -> delegate.setCulling(culling));
    }

    @Override
    public void setUserData(final String userData) {
        host.guardedVoid(generation, () -> delegate.setUserData(userData));
    }

    @Override
    public void setParent(final Part parent, final int index) {
        host.guardedVoid(generation, () -> delegate.setParent(host.unwrapPart(generation, parent), index));
    }

    @Override
    public void setParent(final Deformer parent, final int index) {
        host.guardedVoid(generation, () -> delegate.setParent(host.unwrapDeformer(generation, parent), index));
    }

    @Override
    public boolean visible() {
        return host.guarded(generation, delegate::visible);
    }

    @Override
    public void setVisible(final boolean visible) {
        host.guardedVoid(generation, () -> delegate.setVisible(visible));
    }

    @Override
    public boolean locked() {
        return host.guarded(generation, delegate::locked);
    }

    @Override
    public void setLocked(final boolean locked) {
        host.guardedVoid(generation, () -> delegate.setLocked(locked));
    }

    @Override
    public boolean visibleInHierarchy() {
        return host.guarded(generation, delegate::visibleInHierarchy);
    }

    @Override
    public boolean lockedInHierarchy() {
        return host.guarded(generation, delegate::lockedInHierarchy);
    }

    @Override
    public byte constantFlag() {
        return host.guarded(generation, delegate::constantFlag);
    }

    @Override
    public byte dynamicFlag() {
        return host.guarded(generation, delegate::dynamicFlag);
    }

    @Override
    public BlendMode blendMode() {
        return host.guarded(generation, delegate::blendMode);
    }

    @Override
    public int textureIndex() {
        return host.guarded(generation, delegate::textureIndex);
    }

    @Override
    public int drawOrder() {
        return host.guarded(generation, delegate::drawOrder);
    }

    @Override
    public int renderOrder() {
        return host.guarded(generation, delegate::renderOrder);
    }

    @Override
    public float getOpacity() {
        return host.guarded(generation, delegate::getOpacity);
    }

    @Override
    public void setOpacity(final float opacity) {
        host.guardedVoid(generation, () -> delegate.setOpacity(opacity));
    }

    @Override
    public ArtMeshGeometry geometry() {
        return host.guarded(generation, delegate::geometry);
    }

    @Override
    public void replaceGeometry(final ArtMeshGeometry geometry) {
        host.guardedVoid(generation, () -> delegate.replaceGeometry(geometry));
    }

    @Override
    public dev.turboism.sdk.cubism.model.IntSequence masks() {
        return new SessionIntSequence(host, generation, host.guarded(generation, delegate::masks));
    }

    @Override
    public boolean invertedMask() {
        return host.guarded(generation, delegate::invertedMask);
    }

    @Override
    public boolean culling() {
        return host.guarded(generation, delegate::culling);
    }

    @Override
    public String userData() {
        return host.guarded(generation, delegate::userData);
    }

    @Override
    public dev.turboism.sdk.cubism.model.FloatSequence vertexPositions() {
        return new SessionFloatSequence(host, generation, host.guarded(generation, delegate::vertexPositions));
    }

    @Override
    public dev.turboism.sdk.cubism.model.FloatSequence vertexUvs() {
        return new SessionFloatSequence(host, generation, host.guarded(generation, delegate::vertexUvs));
    }

    @Override
    public dev.turboism.sdk.cubism.model.IntSequence indices() {
        return new SessionIntSequence(host, generation, host.guarded(generation, delegate::indices));
    }

    @Override
    public Color multiplyColor() {
        return host.guarded(generation, delegate::multiplyColor);
    }

    @Override
    public Color screenColor() {
        return host.guarded(generation, delegate::screenColor);
    }

    @Override
    public int parentPartIndex() {
        return host.guarded(generation, delegate::parentPartIndex);
    }

    @Override
    public int parentDeformerIndex() {
        return host.guarded(generation, delegate::parentDeformerIndex);
    }

    @Override
    public dev.turboism.sdk.cubism.model.IntSequence parameters() {
        return new SessionIntSequence(host, generation, host.guarded(generation, delegate::parameters));
    }

    @Override
    public List<dev.turboism.sdk.cubism.model.ParameterBinding> getParameterBindings() {
        return host.guarded(generation, delegate::getParameterBindings);
    }

    @Override
    public List<dev.turboism.sdk.cubism.model.ParameterBinding> getNormalParameterBindings() {
        return host.guarded(generation, delegate::getNormalParameterBindings);
    }

    @Override
    public List<dev.turboism.sdk.cubism.model.ParameterBinding> getCombinedParameterBindings() {
        return host.guarded(generation, delegate::getCombinedParameterBindings);
    }

    @Override
    public dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot authoringSnapshot() {
        return host.guarded(generation, delegate::authoringSnapshot);
    }

    @Override
    public dev.turboism.sdk.cubism.model.ArtMeshEvaluationSnapshot evaluationSnapshot() {
        return host.guarded(generation, delegate::evaluationSnapshot);
    }

    @Override
    public dev.turboism.sdk.cubism.model.ArtMeshKeyform ordinaryKeyform(
            final List<dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.Coordinate> coordinates) {
        final var target = host.guarded(generation, () -> delegate.ordinaryKeyform(coordinates));
        return new dev.turboism.sdk.cubism.model.ArtMeshKeyform() {
            @Override
            public List<dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.Coordinate> coordinates() {
                return host.guarded(generation, target::coordinates);
            }

            @Override
            public dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.Form snapshot() {
                return host.guarded(generation, target::snapshot);
            }

            @Override
            public boolean defaultKeyform() {
                return host.guarded(generation, target::defaultKeyform);
            }

            @Override
            public void setPositions(final List<Point2> positions) {
                host.guardedVoid(generation, () -> target.setPositions(positions));
            }

            @Override
            public void setDrawOrder(final int drawOrder) {
                host.guardedVoid(generation, () -> target.setDrawOrder(drawOrder));
            }
        };
    }

    @Override
    public void setOrdinaryKeyformPositions(
            final dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot expected,
            final List<dev.turboism.sdk.cubism.model.ArtMeshKeyformPositions> edits) {
        host.guardedVoid(generation, () -> delegate.setOrdinaryKeyformPositions(expected, edits));
    }

    @Override
    public void setOrdinaryKeyformDrawOrders(
            final dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot expected,
            final List<dev.turboism.sdk.cubism.model.ArtMeshKeyformDrawOrder> edits) {
        host.guardedVoid(generation, () -> delegate.setOrdinaryKeyformDrawOrders(expected, edits));
    }

    @Override
    public dev.turboism.sdk.cubism.model.ArtMeshMorphKeyform morphKeyform(
            final String parameterGuid, final float keyValue) {
        return guardedMorphKeyform(host.guarded(generation, () -> delegate.morphKeyform(parameterGuid, keyValue)));
    }

    @Override
    public dev.turboism.sdk.cubism.model.ArtMeshMorphKeyform createMorphKeyform(
            final String parameterGuid, final float keyValue) {
        return guardedMorphKeyform(
                host.guarded(generation, () -> delegate.createMorphKeyform(parameterGuid, keyValue)));
    }

    private dev.turboism.sdk.cubism.model.ArtMeshMorphKeyform guardedMorphKeyform(
            final dev.turboism.sdk.cubism.model.ArtMeshMorphKeyform target) {
        return new dev.turboism.sdk.cubism.model.ArtMeshMorphKeyform() {
            @Override
            public String parameterGuid() {
                return host.guarded(generation, target::parameterGuid);
            }

            @Override
            public float keyValue() {
                return host.guarded(generation, target::keyValue);
            }

            @Override
            public dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.Form snapshot() {
                return host.guarded(generation, target::snapshot);
            }

            @Override
            public dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.Form baseSnapshot() {
                return host.guarded(generation, target::baseSnapshot);
            }

            @Override
            public void setPositions(final List<Point2> positions) {
                host.guardedVoid(generation, () -> target.setPositions(positions));
            }

            @Override
            public void setDrawOrder(final int drawOrder) {
                host.guardedVoid(generation, () -> target.setDrawOrder(drawOrder));
            }
        };
    }
}

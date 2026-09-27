package dev.turboism.adapter.host;
import dev.turboism.sdk.cubism.clipmask.ClipMaskReplacement;
import dev.turboism.adapter.cubism.model.ModelObjectProviderUnavailableException;
import dev.turboism.adapter.cubism.model.RuntimeModelObjectCreateProvider;
import dev.turboism.adapter.cubism.editor.transaction.RuntimeAuthoringTransactionProvider;
import dev.turboism.adapter.cubism.edit.RuntimeEditSessionProvider;
import dev.turboism.adapter.cubism.warp.RuntimeWarpMirrorProvider;
import dev.turboism.sdk.cubism.mirror.WarpMirrorBlocker;
import dev.turboism.sdk.cubism.mirror.WarpMirrorBlockerCode;
import dev.turboism.sdk.cubism.mirror.WarpMirrorRequest;
import dev.turboism.sdk.cubism.mirror.WarpMirrorResult;
import dev.turboism.sdk.cubism.mirror.WarpMirrorService;

import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.id.ModelId;
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
import dev.turboism.sdk.cubism.model.ModelObjectCreateRequest;
import dev.turboism.sdk.cubism.model.ModelObjectReference;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.cubism.model.ParameterGroup;
import dev.turboism.sdk.cubism.model.ParameterGroups;
import dev.turboism.sdk.cubism.model.Parameters;
import dev.turboism.sdk.cubism.model.Part;
import dev.turboism.sdk.cubism.model.PartId;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOptions;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionResult;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionService;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionWork;
import dev.turboism.sdk.cubism.model.Parts;
import dev.turboism.adapter.cubism.NativeLabelColorAuthoring;
import dev.turboism.adapter.cubism.NativeLabelColorTarget;
import dev.turboism.sdk.ui.appearance.model.DeformerAppearance;
import dev.turboism.sdk.ui.appearance.model.DrawableAppearance;
import dev.turboism.sdk.ui.appearance.model.ParameterAppearance;
import dev.turboism.sdk.ui.appearance.model.ParameterGroupAppearance;
import dev.turboism.sdk.ui.appearance.model.PartAppearance;
import dev.turboism.sdk.ui.appearance.NativeLabelColor;
import dev.turboism.sdk.ui.appearance.NativeLabelColorState;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;


    final class SessionParts implements Parts {
    private final DynamicCubismModelAccess host;
    final long generation;
    final long modelGeneration;
    final ModelId modelId;
    final Parts delegate;

    SessionParts(
        final DynamicCubismModelAccess host,
        final long generation,
        final long modelGeneration,
        final ModelId modelId,
        final Parts delegate
    ) {
        this.host = Objects.requireNonNull(host, "host");
        this.generation = generation;
        this.modelGeneration = modelGeneration;
        this.modelId = Objects.requireNonNull(modelId, "modelId");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

        @Override public List<Part> all() {
            return host.guarded(generation, () -> delegate.all().stream()
                .map(value -> (Part) new SessionPart(
                    host, generation, modelGeneration, modelId, value
                )).toList());
        }

        @Override public Part find(final PartId id) {
            return host.guarded(
                generation,
                () -> new SessionPart(
                    host, generation, modelGeneration, modelId, delegate.find(id)
                )
            );
        }

        @Override public Part add(final PartId id) {
            return host.guarded(
                generation,
                () -> new SessionPart(
                    host, generation, modelGeneration, modelId, delegate.add(id)
                )
            );
        }

        @Override public Part add(final PartId id, final PartId parentId) {
            return host.guarded(
                generation,
                () -> new SessionPart(
                    host, generation, modelGeneration, modelId, delegate.add(id, parentId)
                )
            );
        }

        @Override public Part copy(final PartId id) {
            return host.guarded(
                generation,
                () -> new SessionPart(
                    host, generation, modelGeneration, modelId, delegate.copy(id)
                )
            );
        }

        @Override public void remove(final PartId id) {
            host.guardedVoid(generation, () -> delegate.remove(id));
        }

        @Override public Part create(
            final String name,
            final Part parent,
            final int index
        ) {
            return host.guarded(generation, () -> new SessionPart(
                host, generation,
                modelGeneration,
                modelId,
                delegate.create(name, host.unwrapPart(generation, parent), index)
            ));
        }

        @Override public void remove(final Part part) {
            host.guardedVoid(generation, () ->
                delegate.remove(host.unwrapPart(generation, part))
            );
        }

        @Override public Part create(final String name) {
            return create(name, null, -1);
        }

        @Override public Part add(final String id) {
            return add(new PartId(id));
        }

        @Override public Part add(final String id, final PartId parentId) {
            return add(new PartId(id), parentId);
        }
    }

    final class SessionPart implements Part {
    final DynamicCubismModelAccess host;
    final long generation;
    final long modelGeneration;
    final ModelId modelId;
    final Part delegate;

    SessionPart(
        final DynamicCubismModelAccess host,
        final long generation,
        final long modelGeneration,
        final ModelId modelId,
        final Part delegate
    ) {
        this.host = Objects.requireNonNull(host, "host");
        this.generation = generation;
        this.modelGeneration = modelGeneration;
        this.modelId = Objects.requireNonNull(modelId, "modelId");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

        @Override public PartId id() { return host.guarded(generation, delegate::id); }

        @Override public dev.turboism.sdk.cubism.model.MorphTargets morphTargets() {
            return host.guarded(generation, delegate::morphTargets);
        }

        @Override public PartAppearance ui() {
            final PartId id = id();
            return host.appearancePart(modelId, id, modelGeneration);
        }

        @Override public int index() { return host.guarded(generation, delegate::index); }
        @Override public String name() { return host.guarded(generation, delegate::name); }
        @Override public void setName(final String name) {
            host.guardedVoid(generation, () -> delegate.setName(name));
        }
        @Override public dev.turboism.sdk.cubism.model.AlphaComposition alphaComposition() {
            return host.guarded(generation, delegate::alphaComposition);
        }
        @Override public List<ArtMeshId> maskIds() {
            return host.guarded(generation, delegate::maskIds);
        }
        @Override public void setId(final dev.turboism.sdk.cubism.model.PartId id) {
            host.guardedVoid(generation, () -> delegate.setId(id));
        }
        @Override public void setMaskIds(final List<ArtMeshId> maskIds) {
            host.guardedVoid(generation, () -> delegate.setMaskIds(maskIds));
        }
        @Override public void setAlphaComposition(
            final dev.turboism.sdk.cubism.model.AlphaComposition composition
        ) {
            host.guardedVoid(generation, () -> delegate.setAlphaComposition(composition));
        }
        @Override public void setParent(final Part parent, final int index) {
            host.guardedVoid(generation, () ->
                delegate.setParent(host.unwrapPart(generation, parent), index)
            );
        }
        @Override public java.util.Optional<String> shortName() {
            return host.guarded(generation, delegate::shortName);
        }
        @Override public void setShortName(final java.util.Optional<String> value) {
            host.guardedVoid(generation, () -> delegate.setShortName(value));
        }
        @Override public java.util.Optional<PartId> parentId() {
            return host.guarded(generation, delegate::parentId);
        }
        @Override public List<PartId> childIds() {
            return host.guarded(generation, delegate::childIds);
        }
        @Override public boolean visible() { return host.guarded(generation, delegate::visible); }
        @Override public void setVisible(final boolean value) {
            host.guardedVoid(generation, () -> delegate.setVisible(value));
        }
        @Override public boolean visibleInHierarchy() {
            return host.guarded(generation, delegate::visibleInHierarchy);
        }
        @Override public boolean locked() { return host.guarded(generation, delegate::locked); }
        @Override public void setLocked(final boolean value) {
            host.guardedVoid(generation, () -> delegate.setLocked(value));
        }
        @Override public boolean lockedInHierarchy() {
            return host.guarded(generation, delegate::lockedInHierarchy);
        }
        @Override public java.util.Optional<Color> editColor() {
            return host.guarded(generation, delegate::editColor);
        }
        @Override public void setEditColor(final java.util.Optional<Color> value) {
            host.guardedVoid(generation, () -> delegate.setEditColor(value));
        }
        @Override public boolean sketch() { return host.guarded(generation, delegate::sketch); }
        @Override public void setSketch(final boolean value) {
            host.guardedVoid(generation, () -> delegate.setSketch(value));
        }
        @Override public int defaultOrder() { return host.guarded(generation, delegate::defaultOrder); }
        @Override public void setDefaultOrder(final int value) {
            host.guardedVoid(generation, () -> delegate.setDefaultOrder(value));
        }
        @Override public float getOpacity() { return host.guarded(generation, delegate::getOpacity); }
        @Override public int parentIndex() { return host.guarded(generation, delegate::parentIndex); }
        @Override public void setOpacity(final float opacity) {
            host.guardedVoid(generation, () -> delegate.setOpacity(opacity));
        }
    }

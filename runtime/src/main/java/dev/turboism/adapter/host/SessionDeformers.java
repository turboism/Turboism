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


    final class SessionDeformers implements Deformers {
    private final DynamicCubismModelAccess host;
    final long generation;
    final long modelGeneration;
    final ModelId modelId;
    final Deformers delegate;

    SessionDeformers(
        final DynamicCubismModelAccess host,
        final long generation,
        final long modelGeneration,
        final ModelId modelId,
        final Deformers delegate
    ) {
        this.host = Objects.requireNonNull(host, "host");
        this.generation = generation;
        this.modelGeneration = modelGeneration;
        this.modelId = Objects.requireNonNull(modelId, "modelId");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

        @Override public List<Deformer> all() {
            return host.guarded(generation, () -> delegate.all().stream()
                .map(value -> (Deformer) new SessionDeformer(
                    host, generation, modelGeneration, modelId, value
                )).toList());
        }

        @Override public Deformer find(final DeformerId id) {
            return host.guarded(
                generation,
                () -> new SessionDeformer(
                    host, generation, modelGeneration, modelId, delegate.find(id)
                )
            );
        }

        @Override public WarpDeformer createWarp(
            final String name,
            final Part parent,
            final int index,
            final int rows,
            final int columns
        ) {
            return host.guarded(generation, () -> new SessionWarpDeformer(
                host,
                generation,
                delegate.createWarp(
                    name,
                    host.unwrapPart(generation, parent),
                    index,
                    rows,
                    columns
                )
            ));
        }

        @Override public RotationDeformer createRotation(
            final String name,
            final Part parent,
            final int index
        ) {
            return host.guarded(generation, () -> new SessionRotationDeformer(
                host,
                generation,
                delegate.createRotation(
                    name,
                    host.unwrapPart(generation, parent),
                    index
                )
            ));
        }

        @Override public void remove(final Deformer deformer) {
            host.guardedVoid(generation, () ->
                delegate.remove(host.unwrapDeformer(generation, deformer))
            );
        }
        @Override public void applyToChildren(final Deformer deformer) {
            host.guardedVoid(generation, () ->
                delegate.applyToChildren(host.unwrapDeformer(generation, deformer))
            );
        }
    }

    final class SessionDeformer implements Deformer {
    final DynamicCubismModelAccess host;
    final long generation;
    final long modelGeneration;
    final ModelId modelId;
    final Deformer delegate;

    SessionDeformer(
        final DynamicCubismModelAccess host,
        final long generation,
        final long modelGeneration,
        final ModelId modelId,
        final Deformer delegate
    ) {
        this.host = Objects.requireNonNull(host, "host");
        this.generation = generation;
        this.modelGeneration = modelGeneration;
        this.modelId = Objects.requireNonNull(modelId, "modelId");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

        @Override public DeformerId id() { return host.guarded(generation, delegate::id); }

        @Override public DeformerAppearance ui() {
            final DeformerId id = id();
            return host.appearanceDeformer(modelId, id, modelGeneration);
        }

        @Override public int index() { return host.guarded(generation, delegate::index); }
        @Override public java.util.Optional<PartId> parentPartId() {
            return host.guarded(generation, delegate::parentPartId);
        }
        @Override public java.util.Optional<DeformerId> parentDeformerId() {
            return host.guarded(generation, delegate::parentDeformerId);
        }
        @Override public List<dev.turboism.sdk.cubism.id.ParameterId> parameterIds() {
            return host.guarded(generation, delegate::parameterIds);
        }
        @Override public String name() { return host.guarded(generation, delegate::name); }
        @Override public void setName(final String name) {
            host.guardedVoid(generation, () -> delegate.setName(name));
        }
        @Override public void setParent(final Part parent, final int index) {
            host.guardedVoid(generation, () ->
                delegate.setParent(host.unwrapPart(generation, parent), index)
            );
        }
        @Override public void setParent(final Deformer parent, final int index) {
            host.guardedVoid(generation, () ->
                delegate.setParent(host.unwrapDeformer(generation, parent), index)
            );
        }
        @Override public boolean visible() { return host.guarded(generation, delegate::visible); }
        @Override public void setVisible(final boolean visible) {
            host.guardedVoid(generation, () -> delegate.setVisible(visible));
        }
        @Override public boolean locked() { return host.guarded(generation, delegate::locked); }
        @Override public void setLocked(final boolean locked) {
            host.guardedVoid(generation, () -> delegate.setLocked(locked));
        }
        @Override public boolean visibleInHierarchy() {
            return host.guarded(generation, delegate::visibleInHierarchy);
        }
        @Override public boolean lockedInHierarchy() {
            return host.guarded(generation, delegate::lockedInHierarchy);
        }
        @Override public float getOpacity() { return host.guarded(generation, delegate::getOpacity); }
        @Override public void setOpacity(final float opacity) {
            host.guardedVoid(generation, () -> delegate.setOpacity(opacity));
        }
        @Override public Color multiplyColor() { return host.guarded(generation, delegate::multiplyColor); }
        @Override public Color screenColor() { return host.guarded(generation, delegate::screenColor); }
        @Override public int parentPartIndex() { return host.guarded(generation, delegate::parentPartIndex); }
        @Override public void setId(final DeformerId id) {
            host.guardedVoid(generation, () -> delegate.setId(id));
        }
        @Override public void setMultiplyColor(final Color color) {
            host.guardedVoid(generation, () -> delegate.setMultiplyColor(color));
        }
        @Override public void setScreenColor(final Color color) {
            host.guardedVoid(generation, () -> delegate.setScreenColor(color));
        }
        @Override public void setTargetDeformer(
            final java.util.Optional<DeformerId> targetDeformer
        ) {
            host.guardedVoid(generation, () -> delegate.setTargetDeformer(targetDeformer));
        }
        @Override public int parentDeformerIndex() { return host.guarded(generation, delegate::parentDeformerIndex); }
        @Override public dev.turboism.sdk.cubism.model.IntSequence parameters() {
            return new SessionIntSequence(host, generation, host.guarded(generation, delegate::parameters));
        }
        @Override public List<dev.turboism.sdk.cubism.model.ParameterBinding> getParameterBindings() {
            return host.guarded(generation, delegate::getParameterBindings);
        }
        @Override public List<dev.turboism.sdk.cubism.model.ParameterBinding> getNormalParameterBindings() {
            return host.guarded(generation, delegate::getNormalParameterBindings);
        }
        @Override public List<dev.turboism.sdk.cubism.model.ParameterBinding> getCombinedParameterBindings() {
            return host.guarded(generation, delegate::getCombinedParameterBindings);
        }
    }

    final class SessionWarpDeformers implements WarpDeformers {
        private final DynamicCubismModelAccess host;
        private final long generation;
        private final WarpDeformers delegate;
        SessionWarpDeformers(final DynamicCubismModelAccess host, final long generation, final WarpDeformers delegate) {
            this.host = Objects.requireNonNull(host, "host");
            this.generation = generation;
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }
        @Override public List<WarpDeformer> all() {
            return host.guarded(generation, () -> delegate.all().stream()
                .map(value -> (WarpDeformer) new SessionWarpDeformer(host, generation, value))
                .toList());
        }
        @Override public WarpDeformer find(final DeformerId id) {
            return host.guarded(
                generation,
                () -> new SessionWarpDeformer(host, generation, delegate.find(id))
            );
        }
    }

    final class SessionWarpDeformer implements WarpDeformer {
        final DynamicCubismModelAccess host;
        final long generation;
        final WarpDeformer delegate;
        SessionWarpDeformer(final DynamicCubismModelAccess host, final long generation, final WarpDeformer delegate) {
            this.host = Objects.requireNonNull(host, "host");
            this.generation = generation;
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }
        @Override public DeformerId id() { return host.guarded(generation, delegate::id); }

        @Override public int index() { return host.guarded(generation, delegate::index); }
        @Override public java.util.Optional<PartId> parentPartId() {
            return host.guarded(generation, delegate::parentPartId);
        }
        @Override public java.util.Optional<DeformerId> parentDeformerId() {
            return host.guarded(generation, delegate::parentDeformerId);
        }
        @Override public List<dev.turboism.sdk.cubism.id.ParameterId> parameterIds() {
            return host.guarded(generation, delegate::parameterIds);
        }
        @Override public String name() { return host.guarded(generation, delegate::name); }
        @Override public void setName(final String name) {
            host.guardedVoid(generation, () -> delegate.setName(name));
        }
        @Override public void setParent(final Part parent, final int index) {
            host.guardedVoid(generation, () ->
                delegate.setParent(host.unwrapPart(generation, parent), index)
            );
        }
        @Override public void setParent(final Deformer parent, final int index) {
            host.guardedVoid(generation, () ->
                delegate.setParent(host.unwrapDeformer(generation, parent), index)
            );
        }
        @Override public boolean visible() { return host.guarded(generation, delegate::visible); }
        @Override public void setVisible(final boolean visible) {
            host.guardedVoid(generation, () -> delegate.setVisible(visible));
        }
        @Override public boolean locked() { return host.guarded(generation, delegate::locked); }
        @Override public void setLocked(final boolean locked) {
            host.guardedVoid(generation, () -> delegate.setLocked(locked));
        }
        @Override public boolean visibleInHierarchy() {
            return host.guarded(generation, delegate::visibleInHierarchy);
        }
        @Override public boolean lockedInHierarchy() {
            return host.guarded(generation, delegate::lockedInHierarchy);
        }
        @Override public float getOpacity() { return host.guarded(generation, delegate::getOpacity); }
        @Override public void setOpacity(final float opacity) {
            host.guardedVoid(generation, () -> delegate.setOpacity(opacity));
        }
        @Override public Color multiplyColor() { return host.guarded(generation, delegate::multiplyColor); }
        @Override public Color screenColor() { return host.guarded(generation, delegate::screenColor); }
        @Override public int parentPartIndex() { return host.guarded(generation, delegate::parentPartIndex); }
        @Override public void setId(final DeformerId id) {
            host.guardedVoid(generation, () -> delegate.setId(id));
        }
        @Override public void setMultiplyColor(final Color color) {
            host.guardedVoid(generation, () -> delegate.setMultiplyColor(color));
        }
        @Override public void setScreenColor(final Color color) {
            host.guardedVoid(generation, () -> delegate.setScreenColor(color));
        }
        @Override public void setTargetDeformer(
            final java.util.Optional<DeformerId> targetDeformer
        ) {
            host.guardedVoid(generation, () -> delegate.setTargetDeformer(targetDeformer));
        }
        @Override public int parentDeformerIndex() { return host.guarded(generation, delegate::parentDeformerIndex); }
        @Override public dev.turboism.sdk.cubism.model.IntSequence parameters() {
            return new SessionIntSequence(host, generation, host.guarded(generation, delegate::parameters));
        }
        @Override public List<dev.turboism.sdk.cubism.model.ParameterBinding> getParameterBindings() {
            return host.guarded(generation, delegate::getParameterBindings);
        }
        @Override public List<dev.turboism.sdk.cubism.model.ParameterBinding> getNormalParameterBindings() {
            return host.guarded(generation, delegate::getNormalParameterBindings);
        }
        @Override public List<dev.turboism.sdk.cubism.model.ParameterBinding> getCombinedParameterBindings() {
            return host.guarded(generation, delegate::getCombinedParameterBindings);
        }
        @Override public WarpGrid grid() { return host.guarded(generation, delegate::grid); }
        @Override public void replaceGrid(final WarpGrid grid) {
            host.guardedVoid(generation, () -> delegate.replaceGrid(grid));
        }
    }

    final class SessionRotationDeformers implements RotationDeformers {
        private final DynamicCubismModelAccess host;
        private final long generation;
        private final RotationDeformers delegate;
        SessionRotationDeformers(
            final DynamicCubismModelAccess host,
            final long generation,
            final RotationDeformers delegate
        ) {
            this.host = Objects.requireNonNull(host, "host");
            this.generation = generation;
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }
        @Override public List<RotationDeformer> all() {
            return host.guarded(generation, () -> delegate.all().stream()
                .map(value -> (RotationDeformer) new SessionRotationDeformer(host, generation, value))
                .toList());
        }
        @Override public RotationDeformer find(final DeformerId id) {
            return host.guarded(
                generation,
                () -> new SessionRotationDeformer(host, generation, delegate.find(id))
            );
        }
    }

    final class SessionRotationDeformer implements RotationDeformer {
        final DynamicCubismModelAccess host;
        final long generation;
        final RotationDeformer delegate;
        SessionRotationDeformer(
            final DynamicCubismModelAccess host,
            final long generation,
            final RotationDeformer delegate
        ) {
            this.host = Objects.requireNonNull(host, "host");
            this.generation = generation;
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }
        @Override public DeformerId id() { return host.guarded(generation, delegate::id); }

        @Override public int index() { return host.guarded(generation, delegate::index); }
        @Override public java.util.Optional<PartId> parentPartId() {
            return host.guarded(generation, delegate::parentPartId);
        }
        @Override public java.util.Optional<DeformerId> parentDeformerId() {
            return host.guarded(generation, delegate::parentDeformerId);
        }
        @Override public List<dev.turboism.sdk.cubism.id.ParameterId> parameterIds() {
            return host.guarded(generation, delegate::parameterIds);
        }
        @Override public String name() { return host.guarded(generation, delegate::name); }
        @Override public void setName(final String name) {
            host.guardedVoid(generation, () -> delegate.setName(name));
        }
        @Override public void setParent(final Part parent, final int index) {
            host.guardedVoid(generation, () ->
                delegate.setParent(host.unwrapPart(generation, parent), index)
            );
        }
        @Override public void setParent(final Deformer parent, final int index) {
            host.guardedVoid(generation, () ->
                delegate.setParent(host.unwrapDeformer(generation, parent), index)
            );
        }
        @Override public boolean visible() { return host.guarded(generation, delegate::visible); }
        @Override public void setVisible(final boolean visible) {
            host.guardedVoid(generation, () -> delegate.setVisible(visible));
        }
        @Override public boolean locked() { return host.guarded(generation, delegate::locked); }
        @Override public void setLocked(final boolean locked) {
            host.guardedVoid(generation, () -> delegate.setLocked(locked));
        }
        @Override public boolean visibleInHierarchy() {
            return host.guarded(generation, delegate::visibleInHierarchy);
        }
        @Override public boolean lockedInHierarchy() {
            return host.guarded(generation, delegate::lockedInHierarchy);
        }
        @Override public float getOpacity() { return host.guarded(generation, delegate::getOpacity); }
        @Override public void setOpacity(final float opacity) {
            host.guardedVoid(generation, () -> delegate.setOpacity(opacity));
        }
        @Override public Color multiplyColor() { return host.guarded(generation, delegate::multiplyColor); }
        @Override public Color screenColor() { return host.guarded(generation, delegate::screenColor); }
        @Override public int parentPartIndex() { return host.guarded(generation, delegate::parentPartIndex); }
        @Override public void setId(final DeformerId id) {
            host.guardedVoid(generation, () -> delegate.setId(id));
        }
        @Override public void setMultiplyColor(final Color color) {
            host.guardedVoid(generation, () -> delegate.setMultiplyColor(color));
        }
        @Override public void setScreenColor(final Color color) {
            host.guardedVoid(generation, () -> delegate.setScreenColor(color));
        }
        @Override public void setTargetDeformer(
            final java.util.Optional<DeformerId> targetDeformer
        ) {
            host.guardedVoid(generation, () -> delegate.setTargetDeformer(targetDeformer));
        }
        @Override public int parentDeformerIndex() { return host.guarded(generation, delegate::parentDeformerIndex); }
        @Override public dev.turboism.sdk.cubism.model.IntSequence parameters() {
            return new SessionIntSequence(host, generation, host.guarded(generation, delegate::parameters));
        }
        @Override public List<dev.turboism.sdk.cubism.model.ParameterBinding> getParameterBindings() {
            return host.guarded(generation, delegate::getParameterBindings);
        }
        @Override public List<dev.turboism.sdk.cubism.model.ParameterBinding> getNormalParameterBindings() {
            return host.guarded(generation, delegate::getNormalParameterBindings);
        }
        @Override public List<dev.turboism.sdk.cubism.model.ParameterBinding> getCombinedParameterBindings() {
            return host.guarded(generation, delegate::getCombinedParameterBindings);
        }
        @Override public float baseAngle() { return host.guarded(generation, delegate::baseAngle); }
        @Override public void setBaseAngle(final float angle) {
            host.guardedVoid(generation, () -> delegate.setBaseAngle(angle));
        }
        @Override public RotationDeformerForm form() {
            return host.guarded(generation, delegate::form);
        }
        @Override public void replaceForm(final RotationDeformerForm form) {
            host.guardedVoid(generation, () -> delegate.replaceForm(form));
        }
    }

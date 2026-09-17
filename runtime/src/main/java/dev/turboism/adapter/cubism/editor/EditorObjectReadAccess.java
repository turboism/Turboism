package dev.turboism.adapter.cubism.editor;

import dev.turboism.adapter.cubism.editor.EditorObjectReadCore.*;
import static dev.turboism.adapter.cubism.editor.EditorObjectReadCore.*;
import dev.turboism.mapping.verification.selector.EditorDeformerInspectorSelectorContract;
import dev.turboism.mapping.verification.selector.EditorGlueInspectorSelectorContract;
import dev.turboism.mapping.verification.selector.EditorInspectorDrawableWriteNoAlphaCompositionSelectorContract;
import dev.turboism.mapping.verification.selector.EditorInspectorDrawableWriteSelectorContract;
import dev.turboism.mapping.verification.selector.EditorObjectReadSelectorContract;
import dev.turboism.mapping.verification.selector.EditorObjectWriteSelectorContract;
import dev.turboism.mapping.verification.selector.EditorParameterBindingReadSelectorContract;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.adapter.cubism.editor.transaction.EditorAuthoringTransactionCoordinator;
import dev.turboism.adapter.cubism.editor.transaction.EditorRefreshRequirement;
import dev.turboism.adapter.cubism.editor.transaction.EditorUndoContribution;
import dev.turboism.sdk.cubism.history.HistoryAction;
import dev.turboism.sdk.cubism.history.HistoryChange;
import dev.turboism.sdk.cubism.history.HistoryEntryDetail;
import dev.turboism.sdk.cubism.history.HistoryOrigin;
import dev.turboism.sdk.cubism.history.HistoryTarget;
import dev.turboism.adapter.cubism.editor.history.decoder.ArtMeshPropertyCapture;
import dev.turboism.mapping.verification.selector.EditorHistoryReadSelectorContract;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.id.ParameterBindingPointId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.BlendMode;
import dev.turboism.sdk.cubism.model.Color;
import dev.turboism.sdk.cubism.model.AlphaComposition;
import dev.turboism.sdk.cubism.model.ColorComposition;
import dev.turboism.sdk.cubism.model.Deformer;
import dev.turboism.sdk.cubism.model.Deformers;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.Drawables;
import dev.turboism.sdk.cubism.model.DrawableEvaluationState;
import dev.turboism.sdk.cubism.model.FloatSequence;
import dev.turboism.sdk.cubism.model.Glue;
import dev.turboism.sdk.cubism.model.GlueId;
import dev.turboism.sdk.cubism.model.Glues;
import dev.turboism.sdk.cubism.model.IntSequence;
import dev.turboism.sdk.cubism.model.MorphTargets;
import dev.turboism.sdk.cubism.model.ParameterBinding;
import dev.turboism.sdk.cubism.model.ParameterBindingFamily;
import dev.turboism.sdk.cubism.model.ParameterBindingPoint;
import dev.turboism.sdk.cubism.model.ParameterBindingTarget;
import dev.turboism.sdk.cubism.model.Part;
import dev.turboism.sdk.cubism.model.PartId;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.cubism.model.RotationDeformer;
import dev.turboism.sdk.cubism.model.RotationDeformerForm;
import dev.turboism.sdk.cubism.model.RotationDeformers;
import dev.turboism.sdk.cubism.model.WarpDeformer;
import dev.turboism.sdk.cubism.model.WarpDeformers;
import dev.turboism.sdk.cubism.model.WarpGrid;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import dev.turboism.sdk.cubism.clipmask.ClipMaskReplacement;

/** Exact-version Editor projection for ArtMesh, Deformer, and Glue authoring reads. */
final class EditorObjectReadAccess {

    @FunctionalInterface
    interface CurrentGuard {
        void requireCurrent(String identity, Object model);
    }

    private final VerifiedMemberResolver resolver;
    private final CurrentGuard currentGuard;
    private final EditorMorphTargetAccess morphTargetAccess;

    private final EditorObjectReadCore core;
    private final EditorObjectWriteAccess writes;
    private final EditorObjectInspectorAccess inspector;

    private final EditorObjectHierarchyEditAccess hierarchyEditAccess;

    EditorObjectReadAccess(
        final VerifiedMemberResolver resolver,
        final CurrentGuard currentGuard,
        final EditorMorphTargetAccess morphTargetAccess,
        final dev.turboism.adapter.cubism.core.CoreEvaluatedJoin evaluatedJoin
    ) {
        this(resolver, currentGuard, morphTargetAccess, evaluatedJoin, null, null, null, null);
    }

    EditorObjectReadAccess(
        final VerifiedMemberResolver resolver,
        final CurrentGuard currentGuard,
        final EditorMorphTargetAccess morphTargetAccess,
        final dev.turboism.adapter.cubism.core.CoreEvaluatedJoin evaluatedJoin,
        final java.util.function.Function<String, Boolean> lazyPublish
    ) {
        this(resolver, currentGuard, morphTargetAccess, evaluatedJoin, lazyPublish, null, null, null);
    }

    EditorObjectReadAccess(
        final VerifiedMemberResolver resolver,
        final CurrentGuard currentGuard,
        final EditorMorphTargetAccess morphTargetAccess,
        final dev.turboism.adapter.cubism.core.CoreEvaluatedJoin evaluatedJoin,
        final java.util.function.Function<String, Boolean> lazyPublish,
        final EditorObjectHierarchyEditAccess hierarchyEditAccess
    ) {
        this(
            resolver,
            currentGuard,
            morphTargetAccess,
            evaluatedJoin,
            lazyPublish,
            hierarchyEditAccess,
            null,
            null
        );
    }

    EditorObjectReadAccess(
        final VerifiedMemberResolver resolver,
        final CurrentGuard currentGuard,
        final EditorMorphTargetAccess morphTargetAccess,
        final dev.turboism.adapter.cubism.core.CoreEvaluatedJoin evaluatedJoin,
        final java.util.function.Function<String, Boolean> lazyPublish,
        final EditorObjectHierarchyEditAccess hierarchyEditAccess,
        final EditorAuthoringTransactionCoordinator authoringCoordinator,
        final Supplier<EditorAuthoringTransactionCoordinator.Binding> authoringBinding
    ) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.currentGuard = Objects.requireNonNull(currentGuard, "currentGuard");
        this.morphTargetAccess = Objects.requireNonNull(morphTargetAccess, "morphTargetAccess");
        this.core = new EditorObjectReadCore(resolver, currentGuard, evaluatedJoin, lazyPublish);
        this.writes = new EditorObjectWriteAccess(resolver, core, authoringCoordinator, authoringBinding);
        this.inspector = new EditorObjectInspectorAccess(resolver, currentGuard, core, authoringCoordinator, authoringBinding);
        this.hierarchyEditAccess = hierarchyEditAccess;
        if ((authoringCoordinator == null) != (authoringBinding == null)) {
            throw new IllegalArgumentException(
                "authoringCoordinator and authoringBinding must be supplied together"
            );
        }
    }

    Drawables drawables(final String identity, final Object source, final Object model) {
        core.requireAuthorized();
        return new EditorDrawables(identity, source, model);
    }

    Deformers deformers(final String identity, final Object source, final Object model) {
        core.requireAuthorized();
        return new EditorDeformers(identity, source, model);
    }

    WarpDeformers warpDeformers(final String identity, final Object source, final Object model) {
        core.requireAuthorized();
        return new EditorWarpDeformers(identity, source, model);
    }

    RotationDeformers rotationDeformers(
        final String identity,
        final Object source,
        final Object model
    ) {
        core.requireAuthorized();
        return new EditorRotationDeformers(identity, source, model);
    }

    Glues glues(final String identity, final Object source, final Object model) {
        core.requireAuthorized();
        return new EditorGlues(identity, source, model);
    }

    List<ParameterBinding> parameterBindings(
        final String identity,
        final Object source,
        final Object model,
        final ParameterId parameterId
    ) {
        Objects.requireNonNull(parameterId, "parameterId");
        requireBindingReadAuthorized();
        final ArrayList<ParameterBinding> result = new ArrayList<>();
        for (ObjectRef value : core.artMeshes(identity, source, model)) {
            parameterBindings(
                identity,
                source,
                model,
                value.source(),
                ParameterBindingTarget.artMesh(new ArtMeshId(value.id()))
            ).stream().filter(binding -> binding.parameterId().equals(parameterId)).forEach(result::add);
        }
        for (DeformerRef value : core.deformerRefs(identity, source, model)) {
            final ParameterBindingTarget target = value.kind() == Kind.WARP
                ? ParameterBindingTarget.warpDeformer(new DeformerId(value.id()))
                : ParameterBindingTarget.rotationDeformer(new DeformerId(value.id()));
            parameterBindings(identity, source, model, value.source(), target).stream()
                .filter(binding -> binding.parameterId().equals(parameterId))
                .forEach(result::add);
        }
        return List.copyOf(result);
    }

    Object bindingTargetSource(
        final String identity,
        final Object source,
        final Object model,
        final ParameterBindingTarget target
    ) {
        Objects.requireNonNull(target, "target");
        return switch (target.type()) {
            case ART_MESH -> core.artMeshes(identity, source, model).stream()
                .filter(value -> value.id().equals(target.id()))
                .map(ObjectRef::source)
                .findFirst()
                .orElseThrow(() -> stale("ArtMesh", target.id()));
            case WARP_DEFORMER, ROTATION_DEFORMER -> core.deformerRefs(identity, source, model).stream()
                .filter(value -> value.id().equals(target.id()))
                .filter(value -> value.kind() == (target.type() == dev.turboism.sdk.cubism.model.ParameterBindingTargetType.WARP_DEFORMER
                    ? Kind.WARP : Kind.ROTATION))
                .map(DeformerRef::source)
                .findFirst()
                .orElseThrow(() -> stale("Deformer", target.id()));
        };
    }

    private void requireBindingReadAuthorized() {
        if (!resolver.authorizesFeature(
            EditorParameterBindingReadSelectorContract.ADAPTER_SLICE_ID,
            EditorParameterBindingReadSelectorContract.CAPABILITY_ID,
            EditorParameterBindingReadSelectorContract.REQUIRED_ALIASES
        )) {
            throw new UnsupportedOperationException(
                "Editor parameter-binding reads require exact verified host evidence."
            );
        }
    }

    private void requireClipMaskWriteAuthorized() {
        if (!resolver.authorizesFeature(
            EditorObjectWriteSelectorContract.ADAPTER_SLICE_ID,
            EditorObjectWriteSelectorContract.CLIP_MASK_CAPABILITY_ID,
            EditorObjectWriteSelectorContract.CLIP_MASK_REQUIRED_ALIASES
        )) {
            throw new UnsupportedOperationException(
                "Editor clip-mask writes require exact verified host evidence."
            );
        }
    }


    List<ParameterBinding> parameterBindings(
        final String identity,
        final Object source,
        final Object model,
        final Object objectSource,
        final ParameterBindingTarget target
    ) {
        // One row per parameter, whatever container it appears in: the Editor can
        // hold the same parameter in the keyform grid (even several entries) and
        // in the morph-target-set. Keyform rows are inserted first (putIfAbsent
        // keeps the first keyform entry); morph rows are then put, replacing the
        // value for an existing key without moving it (the row keeps its keyform
        // position but becomes the BLEND_SHAPE binding) and appending new
        // parameters after. Morph (BLEND_SHAPE) always wins.
        final java.util.LinkedHashMap<ParameterId, ParameterBinding> byParameter
            = new java.util.LinkedHashMap<>();
        for (Object hostBinding : hostBindings(objectSource)) {
            final ParameterId parameterId = bindingParameterId(hostBinding);
            final List<?> hostKeys = list(
                resolver.invoke("cubism.editor-model.keyform-binding.keys", hostBinding),
                "Editor binding keys"
            );
            final ArrayList<ParameterBindingPoint> points = new ArrayList<>(hostKeys.size());
            for (int index = 0; index < hostKeys.size(); index++) {
                final float value = number(hostKeys.get(index), "Editor binding key");
                points.add(new ParameterBindingPoint(
                    new ParameterBindingPointId(parameterId.value() + ":" + index),
                    value
                ));
            }
            byParameter.putIfAbsent(parameterId, new ParameterBinding(
                target,
                parameterId,
                ParameterBindingFamily.KEYFORM_GRID,
                points
            ));
        }
        for (ParameterBinding morph : morphParameterBindings(
            identity, source, model, objectSource, target
        )) {
            byParameter.put(morph.parameterId(), morph);
        }
        return List.copyOf(byParameter.values());
    }

    /**
     * Morph-target bindings of one object source, keyform grid first, morph after.
     *
     * <p>Morph containers are an Editor concept the Core backend does not expose:
     * when the verified plan lacks the morph-target capability the morph portion
     * is dropped (fail soft) while the keyform portion keeps its fail behavior.</p>
     */
    private List<ParameterBinding> morphParameterBindings(
        final String identity,
        final Object source,
        final Object model,
        final Object objectSource,
        final ParameterBindingTarget target
    ) {
        final java.util.LinkedHashMap<ParameterId, List<ParameterBindingPoint>> pointsByParameter
            = new java.util.LinkedHashMap<>();
        try {
            final List<dev.turboism.sdk.cubism.model.MorphTarget> morphTargets =
                morphTargetAccess.morphTargets(identity, source, model, objectSource).all();
            for (int index = 0; index < morphTargets.size(); index++) {
                final dev.turboism.sdk.cubism.model.MorphTarget morphTarget = morphTargets.get(index);
                final ParameterId parameterId = morphTarget.parameterId();
                pointsByParameter.computeIfAbsent(parameterId, ignored -> new ArrayList<>()).add(
                    new ParameterBindingPoint(
                        new ParameterBindingPointId(parameterId.value() + ":morph:" + index),
                        morphTarget.keyValue()
                    )
                );
            }
        } catch (UnsupportedOperationException unsupported) {
            return List.of();
        }
        return pointsByParameter.entrySet().stream()
            .map(entry -> new ParameterBinding(
                target,
                entry.getKey(),
                ParameterBindingFamily.BLEND_SHAPE,
                List.copyOf(entry.getValue())
            ))
            .toList();
    }

    /**
     * Whether the parameter is marked Combined in the Editor. Resolution failures
     * (absent mapping, invalid host value) fail soft and report non-combined.
     */
    boolean parameterCombined(final Object model, final ParameterId parameterId) {
        try {
            final Object parameterSet = resolver.invoke("cubism.editor-model.model.parameter-set", model);
            final List<?> parameters = list(
                resolver.invoke("cubism.editor-model.parameter-set.parameters", parameterSet),
                "Editor parameter collection"
            );
            for (Object parameter : parameters) {
                if (!resolver.isInstance("cubism.editor-model.parameter.class", parameter)) {
                    continue;
                }
                final String id = text(
                    resolver.invoke("cubism.editor-model.id.value",
                        resolver.invoke("cubism.editor-model.parameter.id", parameter)),
                    "Editor parameter ID"
                );
                if (!parameterId.value().equals(id)) {
                    continue;
                }
                final Object source = resolver.invoke("cubism.editor-model.parameter.source", parameter);
                final Object raw = resolver.invoke("cubism.editor-model.parameter-source.combined", source);
                return raw instanceof Boolean combined && combined;
            }
        } catch (RuntimeException unavailable) {
            return false;
        }
        return false;
    }

    private List<ParameterId> parameterIds(final Object objectSource) {
        final java.util.HashSet<ParameterId> unique = new java.util.HashSet<>();
        final ArrayList<ParameterId> result = new ArrayList<>();
        for (Object hostBinding : hostBindings(objectSource)) {
            final ParameterId id = bindingParameterId(hostBinding);
            if (!unique.add(id)) throw unavailable("Editor keyform binding parameters are not unique.");
            result.add(id);
        }
        return List.copyOf(result);
    }

    private List<?> hostBindings(final Object objectSource) {
        requireBindingReadAuthorized();
        final Object grid = resolver.invoke(
            "cubism.editor-model.parameter-controllable.keyform-grid",
            objectSource
        );
        if (!resolver.isInstance("cubism.editor-model.keyform-grid.class", grid)) {
            throw unavailable("Editor keyform grid is unavailable.");
        }
        final List<?> bindings = list(
            resolver.invoke("cubism.editor-model.keyform-grid.bindings", grid),
            "Editor keyform bindings"
        );
        for (Object binding : bindings) {
            if (!resolver.isInstance("cubism.editor-model.keyform-binding.class", binding)) {
                throw unavailable("Editor keyform binding is invalid.");
            }
        }
        return bindings;
    }

    private ParameterId bindingParameterId(final Object hostBinding) {
        final Object hostId = resolver.invoke(
            "cubism.editor-model.keyform-binding.parameter-id",
            hostBinding
        );
        return new ParameterId(text(
            resolver.invoke("cubism.editor-model.id.value", hostId),
            "Editor binding parameter ID"
        ));
    }

    private abstract class ObjectView {
        final String identity;
        final Object modelSource;
        final Object model;
        final ObjectRef ref;
        ObjectView(final String identity, final Object modelSource, final Object model, final ObjectRef ref) {
            this.identity = identity;
            this.modelSource = modelSource;
            this.model = model;
            this.ref = ref;
        }
        ObjectRef current() { return core.currentArtMesh(identity, modelSource, model, ref); }
    }

    private final class EditorDrawable extends ObjectView implements Drawable, EditorNativeObjectRef {
        EditorDrawable(final String identity, final Object modelSource, final Object model, final ObjectRef ref) {
            super(identity, modelSource, model, ref);
        }

        private dev.turboism.adapter.cubism.core.CoreDrawableDefinition evaluated() {
            final ObjectRef value = current();
            return core.evaluated(identity, value.id());
        }

        @Override public Object nativeSource() { return ref.source(); }
        @Override public ArtMeshId id() { current(); return new ArtMeshId(ref.id()); }
        @Override public int index() { return core.artMeshIndex(identity, modelSource, model, current().source()); }
        @Override public boolean doubleSided() { return !culling(); }
        @Override public Optional<PartId> parentPartId() { return core.parentPartId(current().source()); }
        @Override public Optional<DeformerId> parentDeformerId() { return core.parentDeformerId(identity, modelSource, model, current().source()); }
        @Override public List<ParameterId> parameterIds() { return EditorObjectReadAccess.this.parameterIds(current().source()); }
        @Override public List<ArtMeshId> maskIds() {
            core.requireClipMaskReadAuthorized();
            return core.maskIds(identity, modelSource, model, current().source());
        }
        @Override public String guid() {
            current();
            return core.guidValue(resolver.invoke("cubism.editor-model.art-mesh-source.guid", current().source()));
        }
        @Override public String name() { return core.objectName(current().source(), ref.id()); }
        @Override public boolean visible() { return core.sourceFlag("cubism.editor-model.parameter-controllable-source.visible", current().source(), "ArtMesh visibility"); }
        @Override public void setVisible(final boolean visible) { final ObjectRef value = current(); writes.setSourceFlag(Kind.ART_MESH, modelSource, value.source(), "cubism.editor-model.parameter-controllable-source.visible", "cubism.editor-model.parameter-controllable-source.set-visible", visible, "Set ArtMesh visibility"); }
        @Override public boolean locked() { return core.sourceFlag("cubism.editor-model.parameter-controllable-source.locked", current().source(), "ArtMesh lock state"); }
        @Override public void setLocked(final boolean locked) { final ObjectRef value = current(); writes.setSourceFlag(Kind.ART_MESH, modelSource, value.source(), "cubism.editor-model.parameter-controllable-source.locked", "cubism.editor-model.parameter-controllable-source.set-locked", locked, "Set ArtMesh lock state"); }
        @Override public boolean visibleInHierarchy() { return core.sourceFlag("cubism.editor-model.parameter-controllable-source.visible-in-hierarchy", current().source(), "ArtMesh effective visibility"); }
        @Override public boolean lockedInHierarchy() { return core.sourceFlag("cubism.editor-model.parameter-controllable-source.locked-in-hierarchy", current().source(), "ArtMesh effective lock state"); }
        @Override public float getOpacity() { return number(resolver.invoke("cubism.editor-model.drawable-form.opacity", core.artMeshForm(current().instance())), "ArtMesh opacity"); }
        @Override public void setOpacity(final float opacity) { final ObjectRef value = current(); writes.setOpacity(Kind.ART_MESH, modelSource, value.source(), core.artMeshForm(value.instance()), "cubism.editor-model.drawable-form.opacity", "cubism.editor-model.drawable-form.set-opacity", opacity, "Set ArtMesh opacity"); }
        @Override public void setId(final String id) { final ObjectRef value = current(); writes.setId(modelSource, value.source(), id); }
        @Override public void setTargetDeformer(final Optional<DeformerId> targetDeformer) { final ObjectRef value = current(); writes.setTargetDeformer(identity, modelSource, model, value, targetDeformer); }
        @Override public void setClippingMaskIds(final List<ArtMeshId> maskIds) { final ObjectRef value = current(); writes.setClippingMaskIds(identity, modelSource, model, value, maskIds); }
        @Override public void setInvertedMask(final boolean inverted) { final ObjectRef value = current(); writes.setInvertedMask(modelSource, value.source(), inverted); }
        @Override public void setDrawOrder(final int drawOrder) { final ObjectRef value = current(); writes.setDrawOrder(modelSource, value.source(), core.artMeshForm(value.instance()), drawOrder); }
        @Override public void setMultiplyColor(final Color color) { final ObjectRef value = current(); writes.setColor(modelSource, value.source(), core.artMeshForm(value.instance()), "cubism.editor-model.drawable-form.multiply-color", color, "Set ArtMesh multiply color"); }
        @Override public void setScreenColor(final Color color) { final ObjectRef value = current(); writes.setColor(modelSource, value.source(), core.artMeshForm(value.instance()), "cubism.editor-model.drawable-form.screen-color", color, "Set ArtMesh screen color"); }
        @Override public void setColorComposition(final ColorComposition composition) { final ObjectRef value = current(); writes.setColorComposition(modelSource, value.source(), composition); }
        @Override public void setAlphaComposition(final AlphaComposition composition) { final ObjectRef value = current(); writes.setAlphaComposition(modelSource, value.source(), composition); }
        @Override public void setCulling(final boolean culling) { final ObjectRef value = current(); writes.setCulling(modelSource, value.source(), value.instance(), culling); }
        @Override public void setUserData(final String userData) { final ObjectRef value = current(); writes.setUserData(modelSource, value.source(), userData); }
        @Override public int drawOrder() { return integer(resolver.invoke("cubism.editor-model.drawable-form.draw-order", core.artMeshForm(current().instance())), "ArtMesh draw order"); }
        @Override public ArtMeshGeometry geometry() { final ObjectRef value = current(); return core.geometry(value.source(), value.instance()); }
        @Override public void replaceGeometry(final ArtMeshGeometry geometry) { final ObjectRef value = current(); writes.replaceArtMeshGeometry(modelSource, value, geometry); }
        @Override public boolean invertedMask() {
            core.requireClipMaskReadAuthorized();
            return core.sourceFlag(
                "cubism.editor-model.art-mesh-source.inverted-mask",
                current().source(),
                "ArtMesh inverted-mask state"
            );
        }
        @Override public boolean culling() { return core.sourceFlag("cubism.editor-model.art-mesh-source.culling", current().source(), "ArtMesh culling state"); }
        @Override public String userData() { final Object value = resolver.invoke("cubism.editor-model.art-mesh-source.user-data", current().source()); return value == null ? "" : string(value, "ArtMesh user data"); }
        @Override public FloatSequence vertexPositions() { return floatSequence(flatten(geometry().positions())); }
        @Override public FloatSequence vertexUvs() { return floatSequence(flatten(geometry().uvs())); }
        @Override public IntSequence indices() { return intSequence(geometry().triangleIndices()); }
        @Override public byte constantFlag() { return evaluated().constantFlag(); }
        @Override public byte dynamicFlag() { return evaluated().dynamicFlag(); }
        @Override public DrawableEvaluationState evaluationState() {
            final int flags = Byte.toUnsignedInt(evaluated().dynamicFlag());
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
        @Override public BlendMode blendMode() { return evaluated().blendMode(); }
        @Override public int textureIndex() { return evaluated().textureIndex(); }
        @Override public int renderOrder() { return evaluated().renderOrder(); }
        @Override public IntSequence masks() { final List<ArtMeshId> ids = maskIds(); return intSequence(core.artMeshIndices(identity, modelSource, model, ids)); }
        @Override public Color multiplyColor() { return evaluated().multiplyColor(); }
        @Override public Color screenColor() { return evaluated().screenColor(); }
        @Override public int parentPartIndex() { return core.parentPartIndex(modelSource, current().source()); }
        @Override public int parentDeformerIndex() { return core.parentDeformerIndex(identity, modelSource, model, current().source()); }
        @Override public IntSequence parameters() { final List<ParameterId> ids = parameterIds(); return intSequence(core.parameterIndices(model, ids)); }
        @Override public MorphTargets morphTargets() {
            final ObjectRef value = current();
            return morphTargetAccess.morphTargets(identity, modelSource, model, value.source());
        }

        @Override public void setName(final String name) {
            final ObjectRef value = current();
            requireHierarchyEditAccess();
            hierarchyEditAccess.setName(identity, modelSource, model, value.source(), name, "Drawable");
        }

        @Override public void setParent(final Part parent, final int index) {
            final ObjectRef value = current();
            final Object parentSource = nativeSourceOf(parent, "Part parent");
            core.requireCurrentPartSource(modelSource, parentSource);
            requireHierarchyEditAccess();
            hierarchyEditAccess.setParent(
                identity, modelSource, model, value.source(), parentSource, false, index, "Drawable"
            );
            current();
        }

        @Override public void setParent(final Deformer parent, final int index) {
            final ObjectRef value = current();
            final Object parentSource = nativeSourceOf(parent, "Deformer parent");
            core.requireCurrentDeformerSource(identity, modelSource, model, parentSource);
            requireHierarchyEditAccess();
            hierarchyEditAccess.setParent(
                identity, modelSource, model, value.source(), parentSource, true, index, "Drawable"
            );
            current();
        }

        @Override public List<ParameterBinding> getParameterBindings() {
            final ObjectRef value = current();
            return parameterBindings(
                identity,
                modelSource,
                model,
                value.source(),
                ParameterBindingTarget.artMesh(new ArtMeshId(value.id()))
            );
        }

        @Override public List<ParameterBinding> getNormalParameterBindings() {
            final ObjectRef value = current();
            return getParameterBindings().stream()
                .filter(binding -> binding.family() == ParameterBindingFamily.KEYFORM_GRID)
                .filter(binding -> !EditorObjectReadAccess.this.parameterCombined(model, binding.parameterId()))
                .toList();
        }

        @Override public List<ParameterBinding> getCombinedParameterBindings() {
            final ObjectRef value = current();
            return getParameterBindings().stream()
                .filter(binding -> binding.family() == ParameterBindingFamily.KEYFORM_GRID)
                .filter(binding -> EditorObjectReadAccess.this.parameterCombined(model, binding.parameterId()))
                .toList();
        }
    }

    private abstract class DeformerView implements Deformer, EditorNativeObjectRef {
        final String identity;
        final Object modelSource;
        final Object model;
        final DeformerRef ref;
        DeformerView(final String identity, final Object modelSource, final Object model, final DeformerRef ref) {
            this.identity = identity;
            this.modelSource = modelSource;
            this.model = model;
            this.ref = ref;
        }
        DeformerRef current() { return core.currentDeformer(identity, modelSource, model, ref); }

        @Override public Object nativeSource() { return ref.source(); }
        @Override public DeformerId id() { current(); return new DeformerId(ref.id()); }
        @Override public int index() { return core.deformerIndex(identity, modelSource, model, current().source()); }
        @Override public Optional<PartId> parentPartId() { return core.parentPartId(current().source()); }
        @Override public Optional<DeformerId> parentDeformerId() { return core.parentDeformerId(identity, modelSource, model, current().source()); }
        @Override public List<ParameterId> parameterIds() { return EditorObjectReadAccess.this.parameterIds(current().source()); }
        @Override public String name() { return core.objectName(current().source(), ref.id()); }
        @Override public void setName(final String name) {
            final DeformerRef value = current();
            // Two verified rename seams exist: the Inspector envelope (deformer-source
            // set-local-name + basic-setting undo + both palette refreshes) and the
            // hierarchy rename seam (shared parameter-controllable-source set-local-name).
            // Dispatch by the capability evidence actually loaded by the session.
            if (resolver.authorizesFeature(
                EditorDeformerInspectorSelectorContract.ADAPTER_SLICE_ID,
                EditorDeformerInspectorSelectorContract.CAPABILITY_ID,
                EditorDeformerInspectorSelectorContract.REQUIRED_ALIASES
            )) {
                inspector.setDeformerName(identity, modelSource, model, value, name);
            } else {
                requireHierarchyEditAccess();
                hierarchyEditAccess.setName(
                    identity, modelSource, model, value.source(), name, "Deformer"
                );
            }
        }
        @Override public void setId(final DeformerId id) {
            final DeformerRef value = current();
            inspector.setDeformerId(identity, modelSource, model, value, id);
        }
        @Override public void setTargetDeformer(final Optional<DeformerId> target) {
            final DeformerRef value = current();
            inspector.setDeformerTarget(identity, modelSource, model, value, target);
        }
        @Override public void setMultiplyColor(final Color color) {
            final DeformerRef value = current();
            inspector.setDeformerMultiplyColor(identity, modelSource, model, value, color);
        }
        @Override public void setScreenColor(final Color color) {
            final DeformerRef value = current();
            inspector.setDeformerScreenColor(identity, modelSource, model, value, color);
        }
        @Override public boolean visible() { return core.sourceFlag("cubism.editor-model.parameter-controllable-source.visible", current().source(), "Deformer visibility"); }
        @Override public void setVisible(final boolean visible) { final DeformerRef value = current(); writes.setSourceFlag(value.kind(), modelSource, value.source(), "cubism.editor-model.parameter-controllable-source.visible", "cubism.editor-model.parameter-controllable-source.set-visible", visible, "Set Deformer visibility"); }
        @Override public boolean locked() { return core.sourceFlag("cubism.editor-model.parameter-controllable-source.locked", current().source(), "Deformer lock state"); }
        @Override public void setLocked(final boolean locked) { final DeformerRef value = current(); writes.setSourceFlag(value.kind(), modelSource, value.source(), "cubism.editor-model.parameter-controllable-source.locked", "cubism.editor-model.parameter-controllable-source.set-locked", locked, "Set Deformer lock state"); }
        @Override public boolean visibleInHierarchy() { return core.sourceFlag("cubism.editor-model.parameter-controllable-source.visible-in-hierarchy", current().source(), "Deformer effective visibility"); }
        @Override public boolean lockedInHierarchy() { return core.sourceFlag("cubism.editor-model.parameter-controllable-source.locked-in-hierarchy", current().source(), "Deformer effective lock state"); }
        @Override public float getOpacity() { return number(resolver.invoke("cubism.editor-model.deformer-form.opacity", core.deformerForm(current().instance())), "Deformer opacity"); }
        @Override public void setOpacity(final float opacity) { final DeformerRef value = current(); writes.setOpacity(value.kind(), modelSource, value.source(), core.deformerForm(value.instance()), "cubism.editor-model.deformer-form.opacity", "cubism.editor-model.deformer-form.set-opacity", opacity, "Set Deformer opacity"); }
        @Override public Color multiplyColor() { throw unsupported("Deformer multiply color (Cubism Core exposes drawable-level colors only)"); }
        @Override public Color screenColor() { throw unsupported("Deformer screen color (Cubism Core exposes drawable-level colors only)"); }
        @Override public int parentPartIndex() { return core.parentPartIndex(modelSource, current().source()); }
        @Override public int parentDeformerIndex() { return core.parentDeformerIndex(identity, modelSource, model, current().source()); }
        @Override public IntSequence parameters() { final List<ParameterId> ids = parameterIds(); return intSequence(core.parameterIndices(model, ids)); }

        @Override public void setParent(final Part parent, final int index) {
            final DeformerRef value = current();
            final Object parentSource = nativeSourceOf(parent, "Part parent");
            core.requireCurrentPartSource(modelSource, parentSource);
            hierarchyEditAccess.setParent(
                identity, modelSource, model, value.source(), parentSource, false, index, "Deformer"
            );
            current();
        }

        @Override public void setParent(final Deformer parent, final int index) {
            final DeformerRef value = current();
            final Object parentSource = nativeSourceOf(parent, "Deformer parent");
            core.requireCurrentDeformerSource(identity, modelSource, model, parentSource);
            hierarchyEditAccess.setParent(
                identity, modelSource, model, value.source(), parentSource, true, index, "Deformer"
            );
            current();
        }

        @Override public List<ParameterBinding> getParameterBindings() {
            final DeformerRef value = current();
            final ParameterBindingTarget target = value.kind() == Kind.WARP
                ? ParameterBindingTarget.warpDeformer(new DeformerId(value.id()))
                : ParameterBindingTarget.rotationDeformer(new DeformerId(value.id()));
            return parameterBindings(identity, modelSource, model, value.source(), target);
        }

        @Override public List<ParameterBinding> getNormalParameterBindings() {
            return getParameterBindings().stream()
                .filter(binding -> binding.family() == ParameterBindingFamily.KEYFORM_GRID)
                .filter(binding -> !EditorObjectReadAccess.this.parameterCombined(model, binding.parameterId()))
                .toList();
        }

        @Override public List<ParameterBinding> getCombinedParameterBindings() {
            return getParameterBindings().stream()
                .filter(binding -> binding.family() == ParameterBindingFamily.KEYFORM_GRID)
                .filter(binding -> EditorObjectReadAccess.this.parameterCombined(model, binding.parameterId()))
                .toList();
        }
    }

    private final class EditorWarp extends DeformerView implements WarpDeformer {
        EditorWarp(final String identity, final Object modelSource, final Object model, final DeformerRef ref) { super(identity, modelSource, model, ref); }
        @Override public WarpGrid grid() { final DeformerRef value = current(); return core.warpGrid(value.source(), value.instance()); }
        @Override public void replaceGrid(final WarpGrid grid) { final DeformerRef value = current(); writes.replaceWarpGrid(modelSource, value, grid); }
    }

    private final class EditorRotation extends DeformerView implements RotationDeformer {
        EditorRotation(final String identity, final Object modelSource, final Object model, final DeformerRef ref) { super(identity, modelSource, model, ref); }
        @Override public float baseAngle() { return number(resolver.invoke("cubism.editor-model.rotation-source.base-angle", current().source()), "Rotation base angle"); }
        @Override public void setBaseAngle(final float angle) {
            if (!Float.isFinite(angle)) throw new IllegalArgumentException("angle must be finite");
            final DeformerRef value = current();
            writes.requireWriteAuthorized(Kind.ROTATION);
            if (Float.compare(baseAngle(), angle) == 0) return;
            writes.write(Kind.ROTATION, modelSource, value.source(), "Set Rotation base angle", () ->
                resolver.invoke("cubism.editor-model.rotation-source.set-base-angle", value.source(), Float.valueOf(angle))
            );
        }
        @Override public RotationDeformerForm form() { return core.rotationForm(current().instance()); }
        @Override public void replaceForm(final RotationDeformerForm form) { final DeformerRef value = current(); writes.replaceRotationForm(modelSource, value, form); }
    }

    private final class EditorGlue implements Glue {
        final String identity;
        final Object modelSource;
        final Object model;
        final GlueRef ref;
        EditorGlue(final String identity, final Object modelSource, final Object model, final GlueRef ref) {
            this.identity = identity;
            this.modelSource = modelSource;
            this.model = model;
            this.ref = ref;
        }
        GlueRef current() { return core.currentGlue(identity, modelSource, model, ref); }
        ObjectRef target(final String alias) { return core.glueTarget(identity, modelSource, model, current(), alias); }
        @Override public GlueId id() { current(); return new GlueId(ref.id()); }
        @Override public String name() { return inspector.glueName(current()); }
        @Override public void setName(final String name) {
            final GlueRef value = current();
            inspector.setGlueName(identity, modelSource, model, value, name);
        }
        @Override public void setId(final GlueId id) {
            final GlueRef value = current();
            inspector.setGlueId(identity, modelSource, model, value, id);
        }
        @Override public float intensity() {
            return inspector.glueIntensity(identity, modelSource, model, current());
        }
        @Override public void setIntensity(final float intensity) {
            final GlueRef value = current();
            inspector.setGlueIntensity(identity, modelSource, model, value, intensity);
        }
        @Override public void setDrawableA(final ArtMeshId id) {
            final GlueRef value = current();
            inspector.setGlueDrawableA(identity, modelSource, model, value, id);
        }
        @Override public void setDrawableB(final ArtMeshId id) {
            final GlueRef value = current();
            inspector.setGlueDrawableB(identity, modelSource, model, value, id);
        }
        @Override public int index() {
            final GlueRef value = current();
            final List<GlueRef> values = core.glueRefs(identity, modelSource, model);
            for (int index = 0; index < values.size(); index++) {
                if (values.get(index).source() == value.source()) return index;
            }
            throw unavailable("Editor Glue is outside the active model.");
        }
        @Override public ArtMeshId drawableAId() { return new ArtMeshId(target("cubism.editor-model.glue-source.target-art-mesh-a").id()); }
        @Override public ArtMeshId drawableBId() { return new ArtMeshId(target("cubism.editor-model.glue-source.target-art-mesh-b").id()); }
        @Override public List<ParameterId> parameterIds() { return EditorObjectReadAccess.this.parameterIds(current().source()); }
        @Override public int drawableA() { return core.artMeshIndex(identity, modelSource, model, target("cubism.editor-model.glue-source.target-art-mesh-a").source()); }
        @Override public int drawableB() { return core.artMeshIndex(identity, modelSource, model, target("cubism.editor-model.glue-source.target-art-mesh-b").source()); }
        @Override public IntSequence parameters() { return intSequence(core.parameterIndices(model, parameterIds())); }
    }

    private final class EditorDrawables implements Drawables {
        final String identity; final Object source; final Object model;
        EditorDrawables(final String identity, final Object source, final Object model) { this.identity = identity; this.source = source; this.model = model; }
        @Override public List<Drawable> all() { return core.artMeshes(identity, source, model).stream().map(ref -> (Drawable) new EditorDrawable(identity, source, model, ref)).toList(); }
        @Override public Drawable find(final ArtMeshId id) { Objects.requireNonNull(id, "id"); return core.artMeshes(identity, source, model).stream().filter(ref -> ref.id().equals(id.value())).findFirst().map(ref -> (Drawable) new EditorDrawable(identity, source, model, ref)).orElseThrow(() -> new NoSuchElementException("Cubism ArtMesh is absent: " + id.value())); }

        @Override public Drawable create(
            final String name,
            final Part parent,
            final int index,
            final ArtMeshGeometry geometry
        ) {
            final Object parentSource = nativeSourceOf(parent, "Part parent");
            core.requireCurrentPartSource(source, parentSource);
            final EditorObjectHierarchyEditAccess.CreatedSource created = hierarchyEditAccess.createArtMeshSource(
                identity,
                source,
                model,
                name,
                parentSource,
                false,
                index,
                geometry
            );
            return core.artMeshes(identity, source, model).stream()
                .filter(ref -> ref.source() == created.source())
                .findFirst()
                .map(ref -> (Drawable) new EditorDrawable(identity, source, model, ref))
                .orElseThrow(() -> unavailable(
                    "Created ArtMesh is absent after the Editor instance update."
                ));
        }

        @Override public void remove(final Drawable drawable) {
            Objects.requireNonNull(drawable, "drawable");
            final Object nodeSource = nativeSourceOf(drawable, "Drawable");
            core.requireCurrentArtMeshSource(identity, source, model, nodeSource);
            hierarchyEditAccess.remove(identity, source, model, nodeSource, "Drawable");
        }
    }

    private final class EditorDeformers implements Deformers {
        final String identity; final Object source; final Object model;
        EditorDeformers(final String identity, final Object source, final Object model) { this.identity = identity; this.source = source; this.model = model; }
        @Override public List<Deformer> all() { return core.deformerRefs(identity, source, model).stream().map(this::view).toList(); }
        @Override public Deformer find(final DeformerId id) { Objects.requireNonNull(id, "id"); return core.deformerRefs(identity, source, model).stream().filter(ref -> ref.id().equals(id.value())).findFirst().map(this::view).orElseThrow(() -> new NoSuchElementException("Cubism Deformer is absent: " + id.value())); }
        private Deformer view(final DeformerRef ref) { return ref.kind() == Kind.WARP ? new EditorWarp(identity, source, model, ref) : new EditorRotation(identity, source, model, ref); }

        @Override public WarpDeformer createWarp(
            final String name, final Part parent, final int index, final int rows, final int columns
        ) {
            final Object parentSource = nativeSourceOf(parent, "Part parent");
            core.requireCurrentPartSource(source, parentSource);
            final EditorObjectHierarchyEditAccess.CreatedSource created =
                hierarchyEditAccess.createWarpSource(
                    identity,
                    source,
                    model,
                    name,
                    parentSource,
                    false,
                    index,
                    EditorObjectHierarchyEditAccess.defaultWarpGrid(rows, columns)
                );
            return core.deformerRefs(identity, source, model).stream()
                .filter(ref -> ref.source() == created.source())
                .findFirst()
                .map(ref -> (WarpDeformer) new EditorWarp(identity, source, model, ref))
                .orElseThrow(() -> unavailable(
                    "Created Warp Deformer is absent after the Editor instance update."
                ));
        }

        @Override public RotationDeformer createRotation(
            final String name, final Part parent, final int index
        ) {
            final Object parentSource = nativeSourceOf(parent, "Part parent");
            core.requireCurrentPartSource(source, parentSource);
            final EditorObjectHierarchyEditAccess.CreatedSource created =
                hierarchyEditAccess.createRotationSource(
                    identity,
                    source,
                    model,
                    name,
                    parentSource,
                    false,
                    index,
                    new RotationDeformerForm(0F, 0F, 0F, 1F, false, false)
                );
            return core.deformerRefs(identity, source, model).stream()
                .filter(ref -> ref.source() == created.source())
                .findFirst()
                .map(ref -> (RotationDeformer) new EditorRotation(identity, source, model, ref))
                .orElseThrow(() -> unavailable(
                    "Created Rotation Deformer is absent after the Editor instance update."
                ));
        }

        @Override public void remove(final Deformer deformer) {
            Objects.requireNonNull(deformer, "deformer");
            final Object nodeSource = nativeSourceOf(deformer, "Deformer");
            core.requireCurrentDeformerSource(identity, source, model, nodeSource);
            hierarchyEditAccess.remove(identity, source, model, nodeSource, "Deformer");
        }
    }

    private final class EditorWarpDeformers implements WarpDeformers {
        final String identity; final Object source; final Object model;
        EditorWarpDeformers(final String identity, final Object source, final Object model) { this.identity = identity; this.source = source; this.model = model; }
        @Override public List<WarpDeformer> all() { return core.deformerRefs(identity, source, model).stream().filter(ref -> ref.kind() == Kind.WARP).map(ref -> (WarpDeformer) new EditorWarp(identity, source, model, ref)).toList(); }
        @Override public WarpDeformer find(final DeformerId id) { Objects.requireNonNull(id, "id"); return core.deformerRefs(identity, source, model).stream().filter(ref -> ref.kind() == Kind.WARP && ref.id().equals(id.value())).findFirst().map(ref -> (WarpDeformer) new EditorWarp(identity, source, model, ref)).orElseThrow(() -> new NoSuchElementException("Cubism Warp Deformer is absent: " + id.value())); }
    }

    private final class EditorRotationDeformers implements RotationDeformers {
        final String identity; final Object source; final Object model;
        EditorRotationDeformers(final String identity, final Object source, final Object model) { this.identity = identity; this.source = source; this.model = model; }
        @Override public List<RotationDeformer> all() { return core.deformerRefs(identity, source, model).stream().filter(ref -> ref.kind() == Kind.ROTATION).map(ref -> (RotationDeformer) new EditorRotation(identity, source, model, ref)).toList(); }
        @Override public RotationDeformer find(final DeformerId id) { Objects.requireNonNull(id, "id"); return core.deformerRefs(identity, source, model).stream().filter(ref -> ref.kind() == Kind.ROTATION && ref.id().equals(id.value())).findFirst().map(ref -> (RotationDeformer) new EditorRotation(identity, source, model, ref)).orElseThrow(() -> new NoSuchElementException("Cubism Rotation Deformer is absent: " + id.value())); }
    }

    private final class EditorGlues implements Glues {
        final String identity; final Object source; final Object model;
        EditorGlues(final String identity, final Object source, final Object model) { this.identity = identity; this.source = source; this.model = model; }
        @Override public List<Glue> all() { return core.glueRefs(identity, source, model).stream().map(ref -> (Glue) new EditorGlue(identity, source, model, ref)).toList(); }
        @Override public Glue find(final GlueId id) { Objects.requireNonNull(id, "id"); return core.glueRefs(identity, source, model).stream().filter(ref -> ref.id().equals(id.value())).findFirst().map(ref -> (Glue) new EditorGlue(identity, source, model, ref)).orElseThrow(() -> new NoSuchElementException("Cubism Glue is absent: " + id.value())); }
        @Override public Optional<String> providerVersion() {
            currentGuard.requireCurrent(identity, model);
            return Optional.of(resolver.cubismVersion());
        }
    }

    private static Object nativeSourceOf(final Object view, final String label) {
        if (view == null) return null;
        if (!(view instanceof EditorNativeObjectRef ref)) {
            throw new IllegalStateException(
                "The " + label + " is not bound to the active Editor model generation."
            );
        }
        return ref.nativeSource();
    }

    private void requireHierarchyEditAccess() {
        if (hierarchyEditAccess == null) {
            throw new UnsupportedOperationException(
                "Object-hierarchy editing is unavailable without the verified Editor hierarchy access."
            );
        }
    }

    void replaceArtMeshClipMasks(
        final String identity,
        final Object modelSource,
        final Object model,
        final List<ClipMaskReplacement> replacements
    ) {
        final List<ClipMaskReplacement> batch = List.copyOf(
            Objects.requireNonNull(replacements, "replacements")
        );
        if (batch.isEmpty()) {
            throw new IllegalArgumentException("replacements must not be empty");
        }
        currentGuard.requireCurrent(identity, model);
        requireClipMaskWriteAuthorized();

        final List<ObjectRef> meshes = core.artMeshes(identity, modelSource, model);
        final java.util.Map<String, ObjectRef> byId = new java.util.HashMap<>();
        for (ObjectRef mesh : meshes) {
            byId.put(mesh.id(), mesh);
        }

        final java.util.HashSet<String> targetIds = new java.util.HashSet<>();
        final ArrayList<ClipMaskPlan> plans = new ArrayList<>(batch.size());
        boolean changed = false;
        for (ClipMaskReplacement replacement : batch) {
            final String targetId = replacement.targetArtMeshId().value();
            if (!targetIds.add(targetId)) {
                throw new IllegalArgumentException("replacement targets must be unique");
            }
            final ObjectRef target = byId.get(targetId);
            if (target == null) {
                throw unavailable("Clip-mask target ArtMesh is outside the active model.");
            }
            final List<ArtMeshId> actualMasks = core.maskIds(identity, modelSource, model, target.source());
            final boolean actualInverted = core.sourceFlag(
                "cubism.editor-model.art-mesh-source.inverted-mask",
                target.source(),
                "ArtMesh inverted-mask state"
            );
            if (!actualMasks.equals(replacement.expectedMaskArtMeshIds())
                || actualInverted != replacement.expectedInverted()) {
                throw new IllegalStateException(
                    "Clip-mask expected state does not match ArtMesh " + targetId
                );
            }

            final ArrayList<Object> replacementGuids = new ArrayList<>(
                replacement.replacementMaskArtMeshIds().size()
            );
            for (ArtMeshId maskId : replacement.replacementMaskArtMeshIds()) {
                final ObjectRef mask = byId.get(maskId.value());
                if (mask == null) {
                    throw unavailable("Clip-mask source ArtMesh is outside the active model.");
                }
                replacementGuids.add(resolver.invoke(
                    "cubism.editor-model.art-mesh-source.guid",
                    mask.source()
                ));
            }
            final Object replacementClipGuidList = newClipGuidList(replacementGuids);
            final List<?> originalGuids = iterable(
                resolver.invoke(
                    "cubism.editor-model.art-mesh-source.clip-guid-list",
                    target.source()
                ),
                "Editor ArtMesh clipping masks"
            );
            final Object originalClipGuidList = newClipGuidList(originalGuids);
            changed |= !actualMasks.equals(replacement.replacementMaskArtMeshIds())
                || actualInverted != replacement.replacementInverted();
            plans.add(new ClipMaskPlan(
                target,
                actualMasks,
                actualInverted,
                replacement.replacementInverted(),
                originalClipGuidList,
                replacementClipGuidList
            ));
        }

        if (!changed) return;

        final ArrayList<Object> undoSources = new ArrayList<>(plans.size());
        for (ClipMaskPlan plan : plans) {
            undoSources.add(plan.target().source());
        }
        // Exact 5.2 and 5.3.02 evidence: handler Undo snapshots are target-scoped, so the
        // batch admits one snapshot per planned target in plan order inside the single edit
        // session; the host groups those snapshots into one Undo step.
        writes.writeClipMaskBatch(modelSource, undoSources, "Replace ArtMesh clip masks", () -> {
            final ArrayList<ClipMaskPlan> applied = new ArrayList<>(plans.size());
            try {
                for (ClipMaskPlan plan : plans) {
                    applied.add(plan);
                    resolver.invoke(
                        "cubism.editor-model.art-mesh-source.set-clip-guid-list",
                        plan.target().source(),
                        plan.replacementClipGuidList()
                    );
                    resolver.invoke(
                        "cubism.editor-model.art-mesh-source.set-inverted-mask",
                        plan.target().source(),
                        Boolean.valueOf(plan.replacementInverted())
                    );
                }
            } catch (RuntimeException failure) {
                try {
                    restoreClipMaskBatch(identity, modelSource, model, applied);
                } catch (RuntimeException rollbackFailure) {
                    final IllegalStateException combined = new IllegalStateException(
                        "Clip-mask batch mutation failed and rollback did not complete.",
                        failure
                    );
                    combined.addSuppressed(rollbackFailure);
                    throw combined;
                }
                throw failure;
            }
        });
    }

    private Object newClipGuidList(final List<?> values) {
        final Object result = resolver.construct(
            "cubism.editor-model.c-array-list.create",
            values
        );
        if (!resolver.isInstance("cubism.editor-model.c-array-list.class", result)) {
            throw unavailable("Editor clip-mask list type is invalid.");
        }
        return result;
    }

    private void restoreClipMaskBatch(
        final String identity,
        final Object modelSource,
        final Object model,
        final List<ClipMaskPlan> applied
    ) {
        RuntimeException failure = null;
        for (int index = applied.size() - 1; index >= 0; index--) {
            final ClipMaskPlan plan = applied.get(index);
            try {
                resolver.invoke(
                    "cubism.editor-model.art-mesh-source.set-clip-guid-list",
                    plan.target().source(),
                    plan.originalClipGuidList()
                );
            } catch (RuntimeException exception) {
                failure = appendFailure(failure, exception);
            }
            try {
                resolver.invoke(
                    "cubism.editor-model.art-mesh-source.set-inverted-mask",
                    plan.target().source(),
                    Boolean.valueOf(plan.originalInverted())
                );
            } catch (RuntimeException exception) {
                failure = appendFailure(failure, exception);
            }
        }
        for (ClipMaskPlan plan : applied) {
            try {
                if (!plan.originalMaskIds().equals(
                        core.maskIds(identity, modelSource, model, plan.target().source()))
                    || core.sourceFlag(
                        "cubism.editor-model.art-mesh-source.inverted-mask",
                        plan.target().source(),
                        "ArtMesh inverted-mask state"
                    ) != plan.originalInverted()) {
                    failure = appendFailure(
                        failure,
                        new IllegalStateException("Clip-mask rollback verification failed.")
                    );
                }
            } catch (RuntimeException exception) {
                failure = appendFailure(failure, exception);
            }
        }
        if (failure != null) throw failure;
    }

    private static RuntimeException appendFailure(
        final RuntimeException current,
        final RuntimeException next
    ) {
        if (current == null) return next;
        current.addSuppressed(next);
        return current;
    }

    private record ClipMaskPlan(
        ObjectRef target,
        List<ArtMeshId> originalMaskIds,
        boolean originalInverted,
        boolean replacementInverted,
        Object originalClipGuidList,
        Object replacementClipGuidList
    ) {
    }

}

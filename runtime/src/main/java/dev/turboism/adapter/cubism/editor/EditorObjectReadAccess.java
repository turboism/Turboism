package dev.turboism.adapter.cubism.editor;

import dev.turboism.adapter.cubism.editor.EditorObjectReadCore.*;
import static dev.turboism.adapter.cubism.editor.EditorObjectReadCore.*;
import dev.turboism.mapping.verification.selector.EditorDeformerInspectorSelectorContract;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.adapter.cubism.editor.transaction.EditorAuthoringTransactionCoordinator;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DeformerId;
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
import dev.turboism.sdk.cubism.model.ParameterBindingTarget;
import dev.turboism.sdk.cubism.model.Part;
import dev.turboism.sdk.cubism.model.PartId;
import dev.turboism.sdk.cubism.model.RotationDeformer;
import dev.turboism.sdk.cubism.model.RotationDeformerForm;
import dev.turboism.sdk.cubism.model.RotationDeformers;
import dev.turboism.sdk.cubism.model.WarpDeformer;
import dev.turboism.sdk.cubism.model.WarpDeformers;
import dev.turboism.sdk.cubism.model.WarpGrid;

import java.util.List;
import java.util.ArrayList;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
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
    private final EditorObjectClipMaskAccess clipMasks;
    private final EditorObjectBindingReadAccess bindingReads;
    private final EditorObjectInspectorAccess inspector;

    private final EditorObjectHierarchyEditAccess hierarchyEditAccess;

    EditorObjectReadAccess(
        final VerifiedMemberResolver resolver,
        final CurrentGuard currentGuard,
        final EditorMorphTargetAccess morphTargetAccess,
        final dev.turboism.adapter.cubism.core.CoreEvaluatedJoin evaluatedJoin
    ) {
        this(resolver, currentGuard, morphTargetAccess, evaluatedJoin, null, null, null);
    }

    EditorObjectReadAccess(
        final VerifiedMemberResolver resolver,
        final CurrentGuard currentGuard,
        final EditorMorphTargetAccess morphTargetAccess,
        final dev.turboism.adapter.cubism.core.CoreEvaluatedJoin evaluatedJoin,
        final EditorObjectHierarchyEditAccess hierarchyEditAccess,
        final EditorAuthoringTransactionCoordinator authoringCoordinator,
        final Supplier<EditorAuthoringTransactionCoordinator.Binding> authoringBinding
    ) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.currentGuard = Objects.requireNonNull(currentGuard, "currentGuard");
        this.morphTargetAccess = Objects.requireNonNull(morphTargetAccess, "morphTargetAccess");
        this.core = new EditorObjectReadCore(resolver, currentGuard, evaluatedJoin);
        this.writes = new EditorObjectWriteAccess(resolver, core, hierarchyEditAccess, authoringCoordinator, authoringBinding);
        this.inspector = new EditorObjectInspectorAccess(resolver, currentGuard, core, hierarchyEditAccess, authoringCoordinator, authoringBinding);
        this.clipMasks = new EditorObjectClipMaskAccess(resolver, currentGuard, core, writes);
        this.bindingReads = new EditorObjectBindingReadAccess(resolver, morphTargetAccess, core);
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

    void replaceArtMeshClipMasks(
        final String identity,
        final Object modelSource,
        final Object model,
        final List<ClipMaskReplacement> replacements
    ) {
        clipMasks.replaceArtMeshClipMasks(identity, modelSource, model, replacements);
    }

    List<ParameterBinding> parameterBindings(
        final String identity,
        final Object source,
        final Object model,
        final ParameterId parameterId
    ) {
        return bindingReads.parameterBindings(identity, source, model, parameterId);
    }

    Object bindingTargetSource(
        final String identity,
        final Object source,
        final Object model,
        final ParameterBindingTarget target
    ) {
        return bindingReads.bindingTargetSource(identity, source, model, target);
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
        @Override public List<ParameterId> parameterIds() { return bindingReads.parameterIds(current().source()); }
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
            return bindingReads.parameterBindings(identity,
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
                .filter(binding -> !bindingReads.parameterCombined(model, binding.parameterId()))
                .toList();
        }

        @Override public List<ParameterBinding> getCombinedParameterBindings() {
            final ObjectRef value = current();
            return getParameterBindings().stream()
                .filter(binding -> binding.family() == ParameterBindingFamily.KEYFORM_GRID)
                .filter(binding -> bindingReads.parameterCombined(model, binding.parameterId()))
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
        @Override public List<ParameterId> parameterIds() { return bindingReads.parameterIds(current().source()); }
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
            return bindingReads.parameterBindings(identity, modelSource, model, value.source(), target);
        }

        @Override public List<ParameterBinding> getNormalParameterBindings() {
            return getParameterBindings().stream()
                .filter(binding -> binding.family() == ParameterBindingFamily.KEYFORM_GRID)
                .filter(binding -> !bindingReads.parameterCombined(model, binding.parameterId()))
                .toList();
        }

        @Override public List<ParameterBinding> getCombinedParameterBindings() {
            return getParameterBindings().stream()
                .filter(binding -> binding.family() == ParameterBindingFamily.KEYFORM_GRID)
                .filter(binding -> bindingReads.parameterCombined(model, binding.parameterId()))
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
        @Override public List<ParameterId> parameterIds() { return bindingReads.parameterIds(current().source()); }
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
        @Override public void applyToChildren(final Deformer deformer) {
            Objects.requireNonNull(deformer, "deformer");
            final Object nodeSource = nativeSourceOf(deformer, "Deformer");
            core.requireCurrentDeformerSource(identity, source, model, nodeSource);
            requireHierarchyEditAccess();
            hierarchyEditAccess.applyToChildren(
                identity,
                source,
                model,
                nodeSource,
                () -> core.requireCurrentDeformerSource(identity, source, model, nodeSource)
            );
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


    /**
     * Enumerates every drawable ArtMesh and deformer of the bound model generation as
     * native source/instance pairs with hierarchy links, for cross-object authoring
     * operations such as the Warp mirror compensation.
     */
    List<NativeObjectRef> nativeObjects(
        final String identity,
        final Object modelSource,
        final Object model
    ) {
        final List<DeformerRef> deformers = core.deformerRefs(identity, modelSource, model);
        final java.util.IdentityHashMap<Object, String> deformerIds = new java.util.IdentityHashMap<>();
        for (DeformerRef deformer : deformers) deformerIds.put(deformer.source(), deformer.id());
        final ArrayList<NativeObjectRef> values = new ArrayList<>();
        for (DeformerRef deformer : deformers) {
            values.add(nativeObject(deformer.id(), deformer.source(), deformer.instance(), deformer.kind(), deformerIds));
        }
        for (ObjectRef mesh : core.artMeshes(identity, modelSource, model)) {
            values.add(nativeObject(mesh.id(), mesh.source(), mesh.instance(), Kind.ART_MESH, deformerIds));
        }
        return List.copyOf(values);
    }

    private NativeObjectRef nativeObject(
        final String id,
        final Object source,
        final Object instance,
        final Kind kind,
        final java.util.IdentityHashMap<Object, String> deformerIds
    ) {
        final Object target = core.targetDeformerSource(source);
        final String targetId = target == null ? null : deformerIds.get(target);
        final Object locked = resolver.invoke(
            "cubism.editor-model.parameter-controllable-source.locked-in-hierarchy", source
        );
        return new NativeObjectRef(id, source, instance, kind.name(), targetId, Boolean.TRUE.equals(locked));
    }

    /**
     * Native handle for one Editor object: identity, live source and instance, object kind
     * ({@code ART_MESH}/{@code WARP}/{@code ROTATION}), parent-deformer id, and the
     * effective (hierarchy-including) lock state.
     */
    record NativeObjectRef(
        String id,
        Object source,
        Object instance,
        String kind,
        String targetDeformerId,
        boolean locked
    ) { }
}

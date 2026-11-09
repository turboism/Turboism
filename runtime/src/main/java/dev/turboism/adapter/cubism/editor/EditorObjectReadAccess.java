package dev.turboism.adapter.cubism.editor;

import static dev.turboism.adapter.cubism.editor.EditorObjectReadCore.*;

import dev.turboism.adapter.cubism.editor.EditorObjectReadCore.*;
import dev.turboism.adapter.cubism.editor.host.EditorHostThread;
import dev.turboism.adapter.cubism.editor.transaction.EditorAuthoringTransactionCoordinator;
import dev.turboism.adapter.cubism.editor.transaction.EditorAuthoringWriter;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorHistoryReadSelectorContract;
import dev.turboism.sdk.cubism.clipmask.ClipMaskReplacement;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.AlphaComposition;
import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.BlendMode;
import dev.turboism.sdk.cubism.model.Color;
import dev.turboism.sdk.cubism.model.ColorComposition;
import dev.turboism.sdk.cubism.model.Deformer;
import dev.turboism.sdk.cubism.model.Deformers;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.DrawableEvaluationState;
import dev.turboism.sdk.cubism.model.Drawables;
import dev.turboism.sdk.cubism.model.FloatSequence;
import dev.turboism.sdk.cubism.model.Glues;
import dev.turboism.sdk.cubism.model.IntSequence;
import dev.turboism.sdk.cubism.model.MorphTargets;
import dev.turboism.sdk.cubism.model.ParameterBinding;
import dev.turboism.sdk.cubism.model.ParameterBindingFamily;
import dev.turboism.sdk.cubism.model.ParameterBindingTarget;
import dev.turboism.sdk.cubism.model.Part;
import dev.turboism.sdk.cubism.model.PartId;
import dev.turboism.sdk.cubism.model.RotationDeformers;
import dev.turboism.sdk.cubism.model.TexturePixels;
import dev.turboism.sdk.cubism.model.WarpDeformers;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Exact-version Editor projection for ArtMesh, Deformer, and Glue authoring reads.
 *
 * <p>This class owns the ArtMesh projections and composes the write families; the Deformer and
 * Glue read views live in {@link EditorDeformerAndGlueReadViews} and are reached through
 * {@link #deformers}, {@link #warpDeformers}, {@link #rotationDeformers}, and {@link #glues}.</p>
 */
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
    private final EditorDeformerAndGlueReadViews views;

    private final EditorObjectHierarchyEditAccess hierarchyEditAccess;
    private final EditorAuthoringTransactionCoordinator authoringCoordinator;
    private final Supplier<EditorAuthoringTransactionCoordinator.Binding> authoringBinding;

    EditorObjectReadAccess(
            final VerifiedMemberResolver resolver,
            final CurrentGuard currentGuard,
            final EditorMorphTargetAccess morphTargetAccess,
            final dev.turboism.adapter.cubism.core.CoreEvaluatedJoin evaluatedJoin) {
        this(resolver, currentGuard, morphTargetAccess, evaluatedJoin, null, null, null, null);
    }

    EditorObjectReadAccess(
            final VerifiedMemberResolver resolver,
            final CurrentGuard currentGuard,
            final EditorMorphTargetAccess morphTargetAccess,
            final dev.turboism.adapter.cubism.core.CoreEvaluatedJoin evaluatedJoin,
            final java.util.function.Function<String, Boolean> lazyPublish) {
        this(resolver, currentGuard, morphTargetAccess, evaluatedJoin, lazyPublish, null, null, null);
    }

    EditorObjectReadAccess(
            final VerifiedMemberResolver resolver,
            final CurrentGuard currentGuard,
            final EditorMorphTargetAccess morphTargetAccess,
            final dev.turboism.adapter.cubism.core.CoreEvaluatedJoin evaluatedJoin,
            final EditorObjectHierarchyEditAccess hierarchyEditAccess,
            final EditorAuthoringTransactionCoordinator authoringCoordinator,
            final Supplier<EditorAuthoringTransactionCoordinator.Binding> authoringBinding) {
        this(
                resolver,
                currentGuard,
                morphTargetAccess,
                evaluatedJoin,
                null,
                hierarchyEditAccess,
                authoringCoordinator,
                authoringBinding);
    }

    EditorObjectReadAccess(
            final VerifiedMemberResolver resolver,
            final CurrentGuard currentGuard,
            final EditorMorphTargetAccess morphTargetAccess,
            final dev.turboism.adapter.cubism.core.CoreEvaluatedJoin evaluatedJoin,
            final java.util.function.Function<String, Boolean> lazyPublish,
            final EditorObjectHierarchyEditAccess hierarchyEditAccess,
            final EditorAuthoringTransactionCoordinator authoringCoordinator,
            final Supplier<EditorAuthoringTransactionCoordinator.Binding> authoringBinding) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.currentGuard = Objects.requireNonNull(currentGuard, "currentGuard");
        this.morphTargetAccess = Objects.requireNonNull(morphTargetAccess, "morphTargetAccess");
        this.core = new EditorObjectReadCore(resolver, currentGuard, evaluatedJoin, lazyPublish);
        // The read access is the composition point for the object write families: one writer over
        // the model access's coordinator is enough, and a read-only composition (no coordinator)
        // simply never writes.
        final EditorAuthoringWriter authoringWriter = authoringCoordinator == null
                ? null
                : new CoordinatorAuthoringWriter(authoringCoordinator, resolver, authoringBinding);
        this.writes = new EditorObjectWriteAccess(
                resolver, core, hierarchyEditAccess, authoringCoordinator, authoringBinding, authoringWriter);
        final EditorObjectInspectorAccess inspector = new EditorObjectInspectorAccess(
                resolver,
                currentGuard,
                core,
                hierarchyEditAccess,
                authoringCoordinator,
                authoringBinding,
                authoringWriter);
        this.clipMasks = new EditorObjectClipMaskAccess(resolver, currentGuard, core, writes);
        this.bindingReads = new EditorObjectBindingReadAccess(resolver, morphTargetAccess, core);
        this.hierarchyEditAccess = hierarchyEditAccess;
        this.authoringCoordinator = authoringCoordinator;
        this.authoringBinding = authoringBinding;
        if ((authoringCoordinator == null) != (authoringBinding == null)) {
            throw new IllegalArgumentException("authoringCoordinator and authoringBinding must be supplied together");
        }
        this.views = new EditorDeformerAndGlueReadViews(
                resolver,
                currentGuard,
                core,
                writes,
                bindingReads,
                inspector,
                hierarchyEditAccess,
                authoringCoordinator,
                authoringBinding);
    }

    Drawables drawables(final String identity, final Object source, final Object model) {
        core.requireAuthorized();
        return new EditorDrawables(identity, source, model);
    }

    Deformers deformers(final String identity, final Object source, final Object model) {
        return views.deformers(identity, source, model);
    }

    WarpDeformers warpDeformers(final String identity, final Object source, final Object model) {
        return views.warpDeformers(identity, source, model);
    }

    RotationDeformers rotationDeformers(final String identity, final Object source, final Object model) {
        return views.rotationDeformers(identity, source, model);
    }

    Glues glues(final String identity, final Object source, final Object model) {
        return views.glues(identity, source, model);
    }

    void replaceArtMeshClipMasks(
            final String identity,
            final Object modelSource,
            final Object model,
            final List<ClipMaskReplacement> replacements) {
        clipMasks.replaceArtMeshClipMasks(identity, modelSource, model, replacements);
    }

    List<ParameterBinding> parameterBindings(
            final String identity, final Object source, final Object model, final ParameterId parameterId) {
        return bindingReads.parameterBindings(identity, source, model, parameterId);
    }

    Object bindingTargetSource(
            final String identity, final Object source, final Object model, final ParameterBindingTarget target) {
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

        ObjectRef current() {
            return core.currentArtMesh(identity, modelSource, model, ref);
        }
    }

    private final class EditorDrawable extends ObjectView implements Drawable, EditorNativeObjectRef {
        EditorDrawable(final String identity, final Object modelSource, final Object model, final ObjectRef ref) {
            super(identity, modelSource, model, ref);
        }

        private dev.turboism.adapter.cubism.core.CoreDrawableDefinition evaluated() {
            final ObjectRef value = current();
            return core.evaluated(identity, value.id());
        }

        @Override
        public Object nativeSource() {
            return ref.source();
        }

        @Override
        public ArtMeshId id() {
            current();
            return new ArtMeshId(ref.id());
        }

        @Override
        public int index() {
            return core.artMeshIndex(identity, modelSource, model, current().source());
        }

        @Override
        public boolean doubleSided() {
            return !culling();
        }

        @Override
        public Optional<PartId> parentPartId() {
            return core.parentPartId(current().source());
        }

        @Override
        public Optional<DeformerId> parentDeformerId() {
            return core.parentDeformerId(identity, modelSource, model, current().source());
        }

        @Override
        public List<ParameterId> parameterIds() {
            return bindingReads.parameterIds(current().source());
        }

        @Override
        public List<ArtMeshId> maskIds() {
            core.requireClipMaskReadAuthorized();
            return core.maskIds(identity, modelSource, model, current().source());
        }

        @Override
        public String guid() {
            current();
            return core.guidValue(resolver.invoke(
                    "cubism.editor-model.art-mesh-source.guid", current().source()));
        }

        @Override
        public String name() {
            return core.objectName(current().source(), ref.id());
        }

        @Override
        public boolean visible() {
            return core.sourceFlag(
                    "cubism.editor-model.parameter-controllable-source.visible",
                    current().source(),
                    "ArtMesh visibility");
        }

        @Override
        public void setVisible(final boolean visible) {
            final ObjectRef value = current();
            writes.setSourceFlag(
                    Kind.ART_MESH,
                    modelSource,
                    value.source(),
                    "cubism.editor-model.parameter-controllable-source.visible",
                    "cubism.editor-model.parameter-controllable-source.set-visible",
                    visible,
                    "Set ArtMesh visibility");
        }

        @Override
        public boolean locked() {
            return core.sourceFlag(
                    "cubism.editor-model.parameter-controllable-source.locked",
                    current().source(),
                    "ArtMesh lock state");
        }

        @Override
        public void setLocked(final boolean locked) {
            final ObjectRef value = current();
            writes.setSourceFlag(
                    Kind.ART_MESH,
                    modelSource,
                    value.source(),
                    "cubism.editor-model.parameter-controllable-source.locked",
                    "cubism.editor-model.parameter-controllable-source.set-locked",
                    locked,
                    "Set ArtMesh lock state");
        }

        @Override
        public boolean visibleInHierarchy() {
            return core.sourceFlag(
                    "cubism.editor-model.parameter-controllable-source.visible-in-hierarchy",
                    current().source(),
                    "ArtMesh effective visibility");
        }

        @Override
        public boolean lockedInHierarchy() {
            return core.sourceFlag(
                    "cubism.editor-model.parameter-controllable-source.locked-in-hierarchy",
                    current().source(),
                    "ArtMesh effective lock state");
        }

        @Override
        public float getOpacity() {
            return number(
                    resolver.invoke(
                            "cubism.editor-model.drawable-form.opacity",
                            core.artMeshForm(current().instance())),
                    "ArtMesh opacity");
        }

        @Override
        public void setOpacity(final float opacity) {
            final ObjectRef value = current();
            writes.setOpacity(
                    Kind.ART_MESH,
                    modelSource,
                    value.source(),
                    core.artMeshForm(value.instance()),
                    "cubism.editor-model.drawable-form.opacity",
                    "cubism.editor-model.drawable-form.set-opacity",
                    opacity,
                    "Set ArtMesh opacity");
        }

        @Override
        public void setId(final String id) {
            final ObjectRef value = current();
            writes.setId(modelSource, value.source(), id);
        }

        @Override
        public void setTargetDeformer(final Optional<DeformerId> targetDeformer) {
            final ObjectRef value = current();
            writes.setTargetDeformer(identity, modelSource, model, value, targetDeformer);
        }

        @Override
        public void setClippingMaskIds(final List<ArtMeshId> maskIds) {
            final ObjectRef value = current();
            writes.setClippingMaskIds(identity, modelSource, model, value, maskIds);
        }

        @Override
        public void setInvertedMask(final boolean inverted) {
            final ObjectRef value = current();
            writes.setInvertedMask(modelSource, value.source(), inverted);
        }

        @Override
        public void setDrawOrder(final int drawOrder) {
            final ObjectRef value = current();
            writes.setDrawOrder(modelSource, value.source(), core.artMeshForm(value.instance()), drawOrder);
        }

        @Override
        public void setMultiplyColor(final Color color) {
            final ObjectRef value = current();
            writes.setColor(
                    modelSource,
                    value.source(),
                    core.artMeshForm(value.instance()),
                    "cubism.editor-model.drawable-form.multiply-color",
                    color,
                    "Set ArtMesh multiply color");
        }

        @Override
        public void setScreenColor(final Color color) {
            final ObjectRef value = current();
            writes.setColor(
                    modelSource,
                    value.source(),
                    core.artMeshForm(value.instance()),
                    "cubism.editor-model.drawable-form.screen-color",
                    color,
                    "Set ArtMesh screen color");
        }

        @Override
        public void setColorComposition(final ColorComposition composition) {
            final ObjectRef value = current();
            writes.setColorComposition(modelSource, value.source(), composition);
        }

        @Override
        public void setAlphaComposition(final AlphaComposition composition) {
            final ObjectRef value = current();
            writes.setAlphaComposition(modelSource, value.source(), composition);
        }

        @Override
        public void setCulling(final boolean culling) {
            final ObjectRef value = current();
            writes.setCulling(modelSource, value.source(), value.instance(), culling);
        }

        @Override
        public void setUserData(final String userData) {
            final ObjectRef value = current();
            writes.setUserData(modelSource, value.source(), userData);
        }

        @Override
        public int drawOrder() {
            return integer(
                    resolver.invoke(
                            "cubism.editor-model.drawable-form.draw-order",
                            core.artMeshForm(current().instance())),
                    "ArtMesh draw order");
        }

        @Override
        public ArtMeshGeometry geometry() {
            final ObjectRef value = current();
            return core.geometry(value.source(), value.instance());
        }

        @Override
        public void replaceGeometry(final ArtMeshGeometry geometry) {
            final ObjectRef value = current();
            writes.replaceArtMeshGeometry(modelSource, value, geometry);
        }

        @Override
        public Optional<TexturePixels> texturePixels() {
            return core.texturePixels(current().source());
        }

        @Override
        public boolean invertedMask() {
            core.requireClipMaskReadAuthorized();
            return core.sourceFlag(
                    "cubism.editor-model.art-mesh-source.inverted-mask",
                    current().source(),
                    "ArtMesh inverted-mask state");
        }

        @Override
        public boolean culling() {
            return core.sourceFlag(
                    "cubism.editor-model.art-mesh-source.culling", current().source(), "ArtMesh culling state");
        }

        @Override
        public String userData() {
            final Object value = resolver.invoke(
                    "cubism.editor-model.art-mesh-source.user-data", current().source());
            return value == null ? "" : string(value, "ArtMesh user data");
        }

        @Override
        public FloatSequence vertexPositions() {
            return floatSequence(flatten(geometry().positions()));
        }

        @Override
        public FloatSequence vertexUvs() {
            return floatSequence(flatten(geometry().uvs()));
        }

        @Override
        public IntSequence indices() {
            return intSequence(geometry().triangleIndices());
        }

        @Override
        public byte constantFlag() {
            return evaluated().constantFlag();
        }

        @Override
        public byte dynamicFlag() {
            return evaluated().dynamicFlag();
        }

        @Override
        public DrawableEvaluationState evaluationState() {
            final int flags = Byte.toUnsignedInt(evaluated().dynamicFlag());
            return new DrawableEvaluationState(
                    (flags & 0x01) != 0,
                    (flags & 0x02) != 0,
                    (flags & 0x04) != 0,
                    (flags & 0x08) != 0,
                    (flags & 0x10) != 0,
                    (flags & 0x20) != 0,
                    (flags & 0x40) != 0);
        }

        @Override
        public BlendMode blendMode() {
            return evaluated().blendMode();
        }

        @Override
        public int textureIndex() {
            return evaluated().textureIndex();
        }

        @Override
        public int renderOrder() {
            return evaluated().renderOrder();
        }

        @Override
        public IntSequence masks() {
            final List<ArtMeshId> ids = maskIds();
            return intSequence(core.artMeshIndices(identity, modelSource, model, ids));
        }

        @Override
        public Color multiplyColor() {
            return evaluated().multiplyColor();
        }

        @Override
        public Color screenColor() {
            return evaluated().screenColor();
        }

        @Override
        public int parentPartIndex() {
            return core.parentPartIndex(modelSource, current().source());
        }

        @Override
        public int parentDeformerIndex() {
            return core.parentDeformerIndex(
                    identity, modelSource, model, current().source());
        }

        @Override
        public IntSequence parameters() {
            final List<ParameterId> ids = parameterIds();
            return intSequence(core.parameterIndices(model, ids));
        }

        @Override
        public MorphTargets morphTargets() {
            final ObjectRef value = current();
            return morphTargetAccess.morphTargets(identity, modelSource, model, value.source());
        }

        @Override
        public void setName(final String name) {
            final ObjectRef value = current();
            requireHierarchyEditAccess();
            hierarchyEditAccess.setName(identity, modelSource, model, value.source(), name, "Drawable");
        }

        @Override
        public void setParent(final Part parent, final int index) {
            final ObjectRef value = current();
            final Object parentSource = nativeSourceOf(parent, "Part parent");
            core.requireCurrentPartSource(modelSource, parentSource);
            requireHierarchyEditAccess();
            hierarchyEditAccess.setParent(
                    identity, modelSource, model, value.source(), parentSource, false, index, "Drawable");
            current();
        }

        @Override
        public void setParent(final Deformer parent, final int index) {
            final ObjectRef value = current();
            final Object parentSource = nativeSourceOf(parent, "Deformer parent");
            core.requireCurrentDeformerSource(identity, modelSource, model, parentSource);
            requireHierarchyEditAccess();
            hierarchyEditAccess.setParent(
                    identity, modelSource, model, value.source(), parentSource, true, index, "Drawable");
            current();
        }

        @Override
        public List<ParameterBinding> getParameterBindings() {
            final ObjectRef value = current();
            return bindingReads.parameterBindings(
                    identity,
                    modelSource,
                    model,
                    value.source(),
                    ParameterBindingTarget.artMesh(new ArtMeshId(value.id())));
        }

        @Override
        public List<ParameterBinding> getNormalParameterBindings() {
            final ObjectRef value = current();
            return getParameterBindings().stream()
                    .filter(binding -> binding.family() == ParameterBindingFamily.KEYFORM_GRID)
                    .filter(binding -> !bindingReads.parameterCombined(model, binding.parameterId()))
                    .toList();
        }

        @Override
        public List<ParameterBinding> getCombinedParameterBindings() {
            final ObjectRef value = current();
            return getParameterBindings().stream()
                    .filter(binding -> binding.family() == ParameterBindingFamily.KEYFORM_GRID)
                    .filter(binding -> bindingReads.parameterCombined(model, binding.parameterId()))
                    .toList();
        }

        @Override
        public dev.turboism.sdk.cubism.model.OrdinaryKeyformWriteSupport ordinaryKeyformWriteSupport() {
            return EditorHostThread.dispatch("Ordinary keyform write prerequisite observation", () -> {
                current();
                return EditorOrdinaryKeyformWriteSupport.read(resolver, Kind.ART_MESH, authoringCoordinator != null);
            });
        }

        @Override
        public dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot authoringSnapshot() {
            return EditorHostThread.dispatch("ArtMesh stored authoring snapshot", () -> {
                final ObjectRef value = current();
                return new EditorArtMeshAuthoringReadAccess(resolver).read(new ArtMeshId(value.id()), value.source());
            });
        }

        @Override
        public dev.turboism.sdk.cubism.model.ArtMeshEvaluationSnapshot evaluationSnapshot() {
            return EditorHostThread.dispatch("ArtMesh calculated-form observation", () -> {
                final ObjectRef value = current();
                return new EditorArtMeshEvaluationReadAccess(resolver)
                        .read(new ArtMeshId(value.id()), value.source(), value.instance(), model);
            });
        }

        @Override
        public dev.turboism.sdk.cubism.model.ArtMeshKeyform ordinaryKeyform(
                final List<dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.Coordinate> coordinates) {
            return EditorArtMeshKeyformAccess.open(
                    resolver,
                    new ArtMeshId(ref.id()),
                    () -> current().source(),
                    coordinates,
                    () -> {
                        if (authoringCoordinator == null
                                || !resolver.authorizesFeature(
                                        "adapter.editor-model.readwrite",
                                        "cubism.editor-history.read",
                                        EditorHistoryReadSelectorContract.REQUIRED_ALIASES)) {
                            throw new UnsupportedOperationException(
                                    "Stored keyform edits require the authoring transaction coordinator.");
                        }
                    },
                    keyformWriter());
        }

        @Override
        public void setOrdinaryKeyformPositions(
                final dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot expected,
                final List<dev.turboism.sdk.cubism.model.ArtMeshKeyformPositions> edits) {
            EditorArtMeshKeyformAccess.writeBatch(
                    resolver,
                    new ArtMeshId(ref.id()),
                    () -> current().source(),
                    expected,
                    edits,
                    () -> {
                        if (authoringCoordinator == null
                                || !resolver.authorizesFeature(
                                        "adapter.editor-model.readwrite",
                                        "cubism.editor-history.read",
                                        EditorHistoryReadSelectorContract.REQUIRED_ALIASES)) {
                            throw new UnsupportedOperationException(
                                    "Stored keyform batches require the authoring transaction coordinator.");
                        }
                    },
                    keyformWriter());
        }

        @Override
        public void setOrdinaryKeyformDrawOrders(
                final dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot expected,
                final List<dev.turboism.sdk.cubism.model.ArtMeshKeyformDrawOrder> edits) {
            EditorArtMeshKeyformAccess.writeOrders(
                    resolver,
                    new ArtMeshId(ref.id()),
                    () -> current().source(),
                    expected,
                    edits,
                    () -> {
                        if (authoringCoordinator == null
                                || !resolver.authorizesFeature(
                                        "adapter.editor-model.readwrite",
                                        "cubism.editor-history.read",
                                        EditorHistoryReadSelectorContract.REQUIRED_ALIASES)) {
                            throw new UnsupportedOperationException(
                                    "Stored keyform batches require the authoring transaction coordinator.");
                        }
                    },
                    keyformWriter());
        }

        @Override
        public void setOrdinaryKeyformValues(
                final dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot expected,
                final List<dev.turboism.sdk.cubism.model.ArtMeshKeyformValues> edits) {
            EditorArtMeshKeyformAccess.writeValues(
                    resolver,
                    new ArtMeshId(ref.id()),
                    () -> current().source(),
                    expected,
                    edits,
                    () -> {
                        if (authoringCoordinator == null
                                || !resolver.authorizesFeature(
                                        "adapter.editor-model.readwrite",
                                        "cubism.editor-history.read",
                                        EditorHistoryReadSelectorContract.REQUIRED_ALIASES)) {
                            throw new UnsupportedOperationException(
                                    "Stored keyform batches require the authoring transaction coordinator.");
                        }
                    },
                    keyformWriter());
        }

        @Override
        public void setOrdinaryKeyforms(
                final dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot expected,
                final List<dev.turboism.sdk.cubism.model.ArtMeshKeyformPositions> positions,
                final List<dev.turboism.sdk.cubism.model.ArtMeshKeyformDrawOrder> drawOrders) {
            EditorArtMeshKeyformAccess.writeCombined(
                    resolver,
                    new ArtMeshId(ref.id()),
                    () -> current().source(),
                    expected,
                    positions,
                    drawOrders,
                    () -> {
                        if (authoringCoordinator == null
                                || !resolver.authorizesFeature(
                                        "adapter.editor-model.readwrite",
                                        "cubism.editor-history.read",
                                        EditorHistoryReadSelectorContract.REQUIRED_ALIASES)) {
                            throw new UnsupportedOperationException(
                                    "Stored keyform batches require the authoring transaction coordinator.");
                        }
                    },
                    keyformWriter());
        }

        private EditorArtMeshKeyformAccess.Writer keyformWriter() {
            return new EditorArtMeshKeyformAccess.Writer() {
                @Override
                public void write(
                        final Object form,
                        final String property,
                        final String label,
                        final Runnable mutation,
                        final BooleanSupplier applied,
                        final Runnable compensation,
                        final BooleanSupplier restored) {
                    writes.writeArtMeshProperty(
                            EditorObjectWriteAccess.UndoKind.KEYFORM_EDIT,
                            modelSource,
                            current().source(),
                            form,
                            property,
                            label,
                            mutation,
                            applied,
                            compensation,
                            restored);
                }

                @Override
                public boolean ownsParentRecovery() {
                    return authoringCoordinator != null
                            && authoringCoordinator.ownsArtMeshParentRecovery(
                                    Objects.requireNonNull(authoringBinding.get()), identity + ":artmesh:" + ref.id());
                }
            };
        }

        @Override
        public dev.turboism.sdk.cubism.model.ArtMeshMorphKeyform morphKeyform(
                final String parameterGuid, final float keyValue) {
            return morphKeyform(parameterGuid, keyValue, false);
        }

        @Override
        public dev.turboism.sdk.cubism.model.ArtMeshMorphKeyform createMorphKeyform(
                final String parameterGuid, final float keyValue) {
            return morphKeyform(parameterGuid, keyValue, true);
        }

        private dev.turboism.sdk.cubism.model.ArtMeshMorphKeyform morphKeyform(
                final String parameterGuid, final float keyValue, final boolean create) {
            final Runnable requireTransaction = () -> {
                if (authoringCoordinator == null
                        || !resolver.authorizesFeature(
                                "adapter.editor-model.readwrite",
                                "cubism.editor-history.read",
                                EditorHistoryReadSelectorContract.REQUIRED_ALIASES)) {
                    throw new UnsupportedOperationException(
                            "Morph edits require the authoring transaction coordinator.");
                }
            };
            return EditorArtMeshMorphAccess.open(
                    resolver,
                    new ArtMeshId(ref.id()),
                    () -> current().source(),
                    modelSource,
                    model,
                    parameterGuid,
                    keyValue,
                    create,
                    requireTransaction,
                    contribution -> {
                        requireTransaction.run();
                        authoringCoordinator.mutate(
                                Objects.requireNonNull(authoringBinding.get(), "authoring binding"), contribution);
                    },
                    () -> {
                        current();
                        resolver.invoke("cubism.editor-model.model-source.update-instances", modelSource);
                        writes.refresh(
                                resolver.invokeStatic("cubism.editor-model.app-controller.instance"),
                                Kind.ART_MESH,
                                true);
                    });
        }
    }

    private final class EditorDrawables implements Drawables {
        final String identity;
        final Object source;
        final Object model;

        EditorDrawables(final String identity, final Object source, final Object model) {
            this.identity = identity;
            this.source = source;
            this.model = model;
        }

        @Override
        public List<Drawable> all() {
            return core.artMeshes(identity, source, model).stream()
                    .map(ref -> (Drawable) new EditorDrawable(identity, source, model, ref))
                    .toList();
        }

        @Override
        public Drawable find(final ArtMeshId id) {
            Objects.requireNonNull(id, "id");
            return core.artMeshes(identity, source, model).stream()
                    .filter(ref -> ref.id().equals(id.value()))
                    .findFirst()
                    .map(ref -> (Drawable) new EditorDrawable(identity, source, model, ref))
                    .orElseThrow(() -> new NoSuchElementException("Cubism ArtMesh is absent: " + id.value()));
        }

        @Override
        public dev.turboism.sdk.cubism.model.ArtMeshSourceTriangulation previewSourceTriangulation(
                final ArtMeshGeometry geometry) {
            return EditorHostThread.dispatch("Source triangulation preview", () -> {
                currentGuard.requireCurrent(identity, model);
                return new EditorSourceTriangulationAccess(resolver).preview(geometry);
            });
        }

        @Override
        public void select(final List<ArtMeshId> ids) {
            final List<ArtMeshId> checked = List.copyOf(Objects.requireNonNull(ids, "ids"));
            if (new java.util.HashSet<>(checked).size() != checked.size()) {
                throw new IllegalArgumentException("Duplicate ArtMesh selection ID.");
            }
            final var meshes = core.artMeshes(identity, source, model);
            final List<Object> sources = checked.stream()
                    .map(id -> meshes.stream()
                            .filter(ref -> ref.id().equals(id.value()))
                            .findFirst()
                            .orElseThrow(() -> new NoSuchElementException("Cubism ArtMesh is absent: " + id.value()))
                            .source())
                    .toList();
            hierarchyEditAccess.selectArtMeshSources(identity, model, sources);
        }

        @Override
        public Drawable create(final String name, final Part parent, final int index, final ArtMeshGeometry geometry) {
            return createWithSourceGeometry(
                    name,
                    parent,
                    index,
                    geometry,
                    Objects.requireNonNull(geometry, "geometry").positions());
        }

        @Override
        public Drawable createWithSourceGeometry(
                final String name,
                final Part parent,
                final int index,
                final ArtMeshGeometry geometry,
                final List<dev.turboism.sdk.cubism.model.Point2> defaultPositions) {
            final Object parentSource = nativeSourceOf(parent, "Part parent");
            core.requireCurrentPartSource(source, parentSource);
            final EditorObjectHierarchyEditAccess.CreatedSource created = hierarchyEditAccess.createArtMeshSource(
                    identity, source, model, name, parentSource, false, index, geometry, defaultPositions);
            return core.artMeshes(identity, source, model).stream()
                    .filter(ref -> ref.source() == created.source())
                    .findFirst()
                    .map(ref -> (Drawable) new EditorDrawable(identity, source, model, ref))
                    .orElseThrow(() -> unavailable("Created ArtMesh is absent after the Editor instance update."));
        }

        @Override
        public List<Drawable> createManyWithSourceGeometry(final List<Drawables.SourceGeometryRequest> requests) {
            final var checked = List.copyOf(requests);
            return EditorHostThread.dispatch("Create ArtMesh batch", () -> {
                final var created = hierarchyEditAccess.createArtMeshSources(identity, source, model, checked);
                final var current = new java.util.IdentityHashMap<Object, ObjectRef>();
                for (var ref : core.artMeshes(identity, source, model)) current.put(ref.source(), ref);
                return created.stream()
                        .map(item -> {
                            final var ref = current.get(item.source());
                            if (ref == null)
                                throw unavailable("Created ArtMesh is absent after the batch instance update.");
                            return (Drawable) new EditorDrawable(identity, source, model, ref);
                        })
                        .toList();
            });
        }

        @Override
        public void remove(final Drawable drawable) {
            Objects.requireNonNull(drawable, "drawable");
            final Object nodeSource = nativeSourceOf(drawable, "Drawable");
            core.requireCurrentArtMeshSource(identity, source, model, nodeSource);
            hierarchyEditAccess.remove(identity, source, model, nodeSource, "Drawable");
        }
    }

    private static Object nativeSourceOf(final Object view, final String label) {
        if (view == null) return null;
        if (!(view instanceof EditorNativeObjectRef ref)) {
            throw new IllegalStateException("The " + label + " is not bound to the active Editor model generation.");
        }
        return ref.nativeSource();
    }

    private void requireHierarchyEditAccess() {
        if (hierarchyEditAccess == null) {
            throw new UnsupportedOperationException(
                    "Object-hierarchy editing is unavailable without the verified Editor hierarchy access.");
        }
    }
    /**
     * Enumerates every drawable ArtMesh and deformer of the bound model generation as
     * native source/instance pairs with hierarchy links, for cross-object authoring
     * operations such as the Warp mirror compensation.
     */
    List<NativeObjectRef> nativeObjects(final String identity, final Object modelSource, final Object model) {
        final List<DeformerRef> deformers = core.deformerRefs(identity, modelSource, model);
        final java.util.IdentityHashMap<Object, String> deformerIds = new java.util.IdentityHashMap<>();
        for (DeformerRef deformer : deformers) deformerIds.put(deformer.source(), deformer.id());
        final ArrayList<NativeObjectRef> values = new ArrayList<>();
        for (DeformerRef deformer : deformers) {
            values.add(
                    nativeObject(deformer.id(), deformer.source(), deformer.instance(), deformer.kind(), deformerIds));
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
            final java.util.IdentityHashMap<Object, String> deformerIds) {
        final Object target = core.targetDeformerSource(source);
        final String targetId = target == null ? null : deformerIds.get(target);
        final Object locked =
                resolver.invoke("cubism.editor-model.parameter-controllable-source.locked-in-hierarchy", source);
        return new NativeObjectRef(id, source, instance, kind.name(), targetId, Boolean.TRUE.equals(locked));
    }

    /**
     * Native handle for one Editor object: identity, live source and instance, object kind
     * ({@code ART_MESH}/{@code WARP}/{@code ROTATION}), parent-deformer id, and the
     * effective (hierarchy-including) lock state.
     */
    record NativeObjectRef(
            String id, Object source, Object instance, String kind, String targetDeformerId, boolean locked) {}
}

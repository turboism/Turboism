package dev.turboism.adapter.cubism.editor;

import static dev.turboism.adapter.cubism.editor.EditorObjectReadCore.*;

import dev.turboism.adapter.cubism.editor.EditorObjectReadCore.*;
import dev.turboism.adapter.cubism.editor.host.EditorHostThread;
import dev.turboism.adapter.cubism.editor.transaction.EditorAuthoringTransactionCoordinator;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorDeformerInspectorSelectorContract;
import dev.turboism.mapping.verification.selector.EditorHistoryReadSelectorContract;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.Color;
import dev.turboism.sdk.cubism.model.Deformer;
import dev.turboism.sdk.cubism.model.Deformers;
import dev.turboism.sdk.cubism.model.Glue;
import dev.turboism.sdk.cubism.model.GlueId;
import dev.turboism.sdk.cubism.model.Glues;
import dev.turboism.sdk.cubism.model.IntSequence;
import dev.turboism.sdk.cubism.model.ParameterBinding;
import dev.turboism.sdk.cubism.model.ParameterBindingFamily;
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
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Exact-version read views for the Editor's Deformer and Glue families: the warp and rotation
 * deformer views, the glue view, and the collections that enumerate and create them.
 *
 * <p>{@link EditorObjectReadAccess} stays the composition point (it owns the write families and
 * the ArtMesh projections) and delegates the deformer/glue families here. Every view re-resolves
 * its live instance through {@link EditorObjectReadCore} per access, so a stale SDK handle fails
 * closed instead of reading another generation.</p>
 */
final class EditorDeformerAndGlueReadViews {

    private final VerifiedMemberResolver resolver;
    private final EditorObjectReadAccess.CurrentGuard currentGuard;
    private final EditorObjectReadCore core;
    private final EditorObjectWriteAccess writes;
    private final EditorObjectBindingReadAccess bindingReads;
    private final EditorObjectInspectorAccess inspector;
    private final EditorObjectHierarchyEditAccess hierarchyEditAccess;
    private final EditorAuthoringTransactionCoordinator authoringCoordinator;
    private final Supplier<EditorAuthoringTransactionCoordinator.Binding> authoringBinding;

    EditorDeformerAndGlueReadViews(
            final VerifiedMemberResolver resolver,
            final EditorObjectReadAccess.CurrentGuard currentGuard,
            final EditorObjectReadCore core,
            final EditorObjectWriteAccess writes,
            final EditorObjectBindingReadAccess bindingReads,
            final EditorObjectInspectorAccess inspector,
            final EditorObjectHierarchyEditAccess hierarchyEditAccess,
            final EditorAuthoringTransactionCoordinator authoringCoordinator,
            final Supplier<EditorAuthoringTransactionCoordinator.Binding> authoringBinding) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.currentGuard = Objects.requireNonNull(currentGuard, "currentGuard");
        this.core = Objects.requireNonNull(core, "core");
        this.writes = Objects.requireNonNull(writes, "writes");
        this.bindingReads = Objects.requireNonNull(bindingReads, "bindingReads");
        this.inspector = Objects.requireNonNull(inspector, "inspector");
        this.hierarchyEditAccess = hierarchyEditAccess;
        this.authoringCoordinator = authoringCoordinator;
        this.authoringBinding = authoringBinding;
    }

    Deformers deformers(final String identity, final Object source, final Object model) {
        core.requireAuthorized();
        return new EditorDeformers(identity, source, model);
    }

    WarpDeformers warpDeformers(final String identity, final Object source, final Object model) {
        core.requireAuthorized();
        return new EditorWarpDeformers(identity, source, model);
    }

    RotationDeformers rotationDeformers(final String identity, final Object source, final Object model) {
        core.requireAuthorized();
        return new EditorRotationDeformers(identity, source, model);
    }

    Glues glues(final String identity, final Object source, final Object model) {
        core.requireAuthorized();
        return new EditorGlues(identity, source, model);
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

        DeformerRef current() {
            return core.currentDeformer(identity, modelSource, model, ref);
        }

        @Override
        public Object nativeSource() {
            return ref.source();
        }

        @Override
        public DeformerId id() {
            current();
            return new DeformerId(ref.id());
        }

        @Override
        public int index() {
            return core.deformerIndex(identity, modelSource, model, current().source());
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
        public String name() {
            return core.objectName(current().source(), ref.id());
        }

        @Override
        public void setName(final String name) {
            final DeformerRef value = current();
            // Two verified rename seams exist: the Inspector envelope (deformer-source
            // set-local-name + basic-setting undo + both palette refreshes) and the
            // hierarchy rename seam (shared parameter-controllable-source set-local-name).
            // Dispatch by the capability evidence actually loaded by the session.
            if (resolver.authorizesFeature(
                    EditorDeformerInspectorSelectorContract.ADAPTER_SLICE_ID,
                    EditorDeformerInspectorSelectorContract.CAPABILITY_ID,
                    EditorDeformerInspectorSelectorContract.REQUIRED_ALIASES)) {
                inspector.setDeformerName(identity, modelSource, model, value, name);
            } else {
                requireHierarchyEditAccess();
                hierarchyEditAccess.setName(identity, modelSource, model, value.source(), name, "Deformer");
            }
        }

        @Override
        public void setId(final DeformerId id) {
            final DeformerRef value = current();
            inspector.setDeformerId(identity, modelSource, model, value, id);
        }

        @Override
        public void setTargetDeformer(final Optional<DeformerId> target) {
            final DeformerRef value = current();
            inspector.setDeformerTarget(identity, modelSource, model, value, target);
        }

        @Override
        public void setMultiplyColor(final Color color) {
            final DeformerRef value = current();
            inspector.setDeformerMultiplyColor(identity, modelSource, model, value, color);
        }

        @Override
        public void setScreenColor(final Color color) {
            final DeformerRef value = current();
            inspector.setDeformerScreenColor(identity, modelSource, model, value, color);
        }

        @Override
        public boolean visible() {
            return core.sourceFlag(
                    "cubism.editor-model.parameter-controllable-source.visible",
                    current().source(),
                    "Deformer visibility");
        }

        @Override
        public void setVisible(final boolean visible) {
            final DeformerRef value = current();
            writes.setSourceFlag(
                    value.kind(),
                    modelSource,
                    value.source(),
                    "cubism.editor-model.parameter-controllable-source.visible",
                    "cubism.editor-model.parameter-controllable-source.set-visible",
                    visible,
                    "Set Deformer visibility");
        }

        @Override
        public boolean locked() {
            return core.sourceFlag(
                    "cubism.editor-model.parameter-controllable-source.locked",
                    current().source(),
                    "Deformer lock state");
        }

        @Override
        public void setLocked(final boolean locked) {
            final DeformerRef value = current();
            writes.setSourceFlag(
                    value.kind(),
                    modelSource,
                    value.source(),
                    "cubism.editor-model.parameter-controllable-source.locked",
                    "cubism.editor-model.parameter-controllable-source.set-locked",
                    locked,
                    "Set Deformer lock state");
        }

        @Override
        public boolean visibleInHierarchy() {
            return core.sourceFlag(
                    "cubism.editor-model.parameter-controllable-source.visible-in-hierarchy",
                    current().source(),
                    "Deformer effective visibility");
        }

        @Override
        public boolean lockedInHierarchy() {
            return core.sourceFlag(
                    "cubism.editor-model.parameter-controllable-source.locked-in-hierarchy",
                    current().source(),
                    "Deformer effective lock state");
        }

        @Override
        public float getOpacity() {
            return number(
                    resolver.invoke(
                            "cubism.editor-model.deformer-form.opacity",
                            core.deformerForm(current().instance())),
                    "Deformer opacity");
        }

        @Override
        public void setOpacity(final float opacity) {
            final DeformerRef value = current();
            writes.setOpacity(
                    value.kind(),
                    modelSource,
                    value.source(),
                    core.deformerForm(value.instance()),
                    "cubism.editor-model.deformer-form.opacity",
                    "cubism.editor-model.deformer-form.set-opacity",
                    opacity,
                    "Set Deformer opacity");
        }

        @Override
        public Color multiplyColor() {
            throw unsupported("Deformer multiply color (Cubism Core exposes drawable-level colors only)");
        }

        @Override
        public Color screenColor() {
            throw unsupported("Deformer screen color (Cubism Core exposes drawable-level colors only)");
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
        public void setParent(final Part parent, final int index) {
            final DeformerRef value = current();
            final Object parentSource = nativeSourceOf(parent, "Part parent");
            core.requireCurrentPartSource(modelSource, parentSource);
            hierarchyEditAccess.setParent(
                    identity, modelSource, model, value.source(), parentSource, false, index, "Deformer");
            current();
        }

        @Override
        public void setParent(final Deformer parent, final int index) {
            final DeformerRef value = current();
            final Object parentSource = nativeSourceOf(parent, "Deformer parent");
            core.requireCurrentDeformerSource(identity, modelSource, model, parentSource);
            hierarchyEditAccess.setParent(
                    identity, modelSource, model, value.source(), parentSource, true, index, "Deformer");
            current();
        }

        @Override
        public List<ParameterBinding> getParameterBindings() {
            final DeformerRef value = current();
            final ParameterBindingTarget target = value.kind() == Kind.WARP
                    ? ParameterBindingTarget.warpDeformer(new DeformerId(value.id()))
                    : ParameterBindingTarget.rotationDeformer(new DeformerId(value.id()));
            return bindingReads.parameterBindings(identity, modelSource, model, value.source(), target);
        }

        @Override
        public List<ParameterBinding> getNormalParameterBindings() {
            return getParameterBindings().stream()
                    .filter(binding -> binding.family() == ParameterBindingFamily.KEYFORM_GRID)
                    .filter(binding -> !bindingReads.parameterCombined(model, binding.parameterId()))
                    .toList();
        }

        @Override
        public List<ParameterBinding> getCombinedParameterBindings() {
            return getParameterBindings().stream()
                    .filter(binding -> binding.family() == ParameterBindingFamily.KEYFORM_GRID)
                    .filter(binding -> bindingReads.parameterCombined(model, binding.parameterId()))
                    .toList();
        }
    }

    private final class EditorWarp extends DeformerView implements WarpDeformer {
        EditorWarp(final String identity, final Object modelSource, final Object model, final DeformerRef ref) {
            super(identity, modelSource, model, ref);
        }

        @Override
        public WarpGrid grid() {
            final DeformerRef value = current();
            return core.warpGrid(value.source(), value.instance());
        }

        @Override
        public void replaceGrid(final WarpGrid grid) {
            final DeformerRef value = current();
            writes.replaceWarpGrid(modelSource, value, grid);
        }

        @Override
        public dev.turboism.sdk.cubism.model.OrdinaryKeyformWriteSupport ordinaryKeyformWriteSupport() {
            return EditorHostThread.dispatch("Ordinary keyform write prerequisite observation", () -> {
                current();
                return EditorOrdinaryKeyformWriteSupport.read(resolver, Kind.WARP, authoringCoordinator != null);
            });
        }

        @Override
        public dev.turboism.sdk.cubism.model.WarpAuthoringSnapshot authoringSnapshot() {
            return EditorHostThread.dispatch(
                    "Warp stored authoring snapshot",
                    () -> new EditorWarpAuthoringReadAccess(resolver)
                            .read(new DeformerId(ref.id()), current().source()));
        }

        @Override
        public dev.turboism.sdk.cubism.model.WarpMorphSnapshot morphSnapshot() {
            return EditorHostThread.dispatch(
                    "Warp opacity morph snapshot",
                    () -> new EditorWarpMorphState(resolver, current().source(), modelSource, model)
                            .snapshot(new DeformerId(ref.id())));
        }

        @Override
        public void createOpacityMorphKeyform(
                final ParameterId parameterId, final float keyValue, final float absoluteOpacity) {
            createWarpMorph(parameterId, keyValue, absoluteOpacity, null);
        }

        @Override
        public void createMorphKeyform(
                final ParameterId parameterId, final float keyValue, final List<Point2> controls) {
            createWarpMorph(parameterId, keyValue, null, List.copyOf(controls));
        }

        private void createWarpMorph(
                final ParameterId parameterId,
                final float keyValue,
                final Float absoluteOpacity,
                final List<Point2> controls) {
            final Runnable requireTransaction = () -> {
                if (authoringCoordinator == null
                        || !resolver.authorizesFeature(
                                "adapter.editor-model.readwrite",
                                "cubism.editor-history.read",
                                EditorHistoryReadSelectorContract.REQUIRED_ALIASES))
                    throw new UnsupportedOperationException("Warp opacity morph requires authoring coordinator");
            };
            // Reject stale public handles now; captured history callbacks use source identity.
            EditorHostThread.dispatch(
                    "Warp morph source identity", () -> current().source());
            final java.util.function.Supplier<Object> replaySource =
                    () -> core.replayDeformerSource(identity, modelSource, model, ref);
            EditorWarpMorphAccess.create(
                    resolver,
                    new DeformerId(ref.id()),
                    replaySource,
                    modelSource,
                    model,
                    parameterId,
                    keyValue,
                    absoluteOpacity,
                    controls,
                    requireTransaction,
                    () -> authoringCoordinator.isCreatedTargetRestored(
                            Objects.requireNonNull(authoringBinding.get(), "authoring binding"), ref.source()),
                    contribution -> {
                        requireTransaction.run();
                        authoringCoordinator.mutate(
                                Objects.requireNonNull(authoringBinding.get(), "authoring binding"), contribution);
                    },
                    () -> {
                        replaySource.get();
                        resolver.invoke("cubism.editor-model.model-source.update-instances", modelSource);
                        writes.refresh(
                                resolver.invokeStatic("cubism.editor-model.app-controller.instance"), Kind.WARP, true);
                    });
        }

        @Override
        public void setOrdinaryKeyformPositions(
                final dev.turboism.sdk.cubism.model.WarpAuthoringSnapshot expected,
                final List<dev.turboism.sdk.cubism.model.WarpKeyformPositions> edits) {
            EditorWarpKeyformAccess.writeBatch(
                    resolver,
                    new DeformerId(ref.id()),
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
                                    "Stored Warp batches require the authoring transaction coordinator");
                        }
                    },
                    (form, property, label, mutation, applied, compensation, restored) -> writes.writeWarpKeyforms(
                            modelSource, current().source(), label, mutation, applied, compensation, restored));
        }
    }

    private final class EditorRotation extends DeformerView implements RotationDeformer {
        EditorRotation(final String identity, final Object modelSource, final Object model, final DeformerRef ref) {
            super(identity, modelSource, model, ref);
        }

        @Override
        public float baseAngle() {
            return number(
                    resolver.invoke(
                            "cubism.editor-model.rotation-source.base-angle",
                            current().source()),
                    "Rotation base angle");
        }

        @Override
        public void setBaseAngle(final float angle) {
            if (!Float.isFinite(angle)) throw new IllegalArgumentException("angle must be finite");
            final DeformerRef value = current();
            writes.requireWriteAuthorized(Kind.ROTATION);
            if (Float.compare(baseAngle(), angle) == 0) return;
            writes.write(
                    Kind.ROTATION,
                    modelSource,
                    value.source(),
                    "Set Rotation base angle",
                    () -> resolver.invoke(
                            "cubism.editor-model.rotation-source.set-base-angle",
                            value.source(),
                            Float.valueOf(angle)));
        }

        @Override
        public RotationDeformerForm form() {
            return core.rotationForm(current().instance());
        }

        @Override
        public void replaceForm(final RotationDeformerForm form) {
            final DeformerRef value = current();
            writes.replaceRotationForm(modelSource, value, form);
        }

        @Override
        public dev.turboism.sdk.cubism.model.OrdinaryKeyformWriteSupport ordinaryKeyformWriteSupport() {
            return EditorHostThread.dispatch("Ordinary keyform write prerequisite observation", () -> {
                current();
                return EditorOrdinaryKeyformWriteSupport.read(resolver, Kind.ROTATION, authoringCoordinator != null);
            });
        }

        @Override
        public dev.turboism.sdk.cubism.model.RotationAuthoringSnapshot authoringSnapshot() {
            return EditorHostThread.dispatch(
                    "Rotation stored authoring snapshot",
                    () -> new EditorRotationAuthoringReadAccess(resolver)
                            .read(new DeformerId(ref.id()), current().source()));
        }

        @Override
        public void setOrdinaryKeyformForms(
                final dev.turboism.sdk.cubism.model.RotationAuthoringSnapshot expected,
                final List<dev.turboism.sdk.cubism.model.RotationKeyformForm> edits) {
            writes.requireWriteAuthorized(Kind.ROTATION);
            EditorRotationKeyformAccess.writeBatch(
                    resolver,
                    new DeformerId(ref.id()),
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
                                    "Stored Rotation batches require the authoring transaction coordinator");
                        }
                    },
                    (form, property, label, mutation, applied, compensation, restored) -> writes.writeRotationKeyforms(
                            modelSource, current().source(), label, mutation, applied, compensation, restored));
        }
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

        GlueRef current() {
            return core.currentGlue(identity, modelSource, model, ref);
        }

        ObjectRef target(final String alias) {
            return core.glueTarget(identity, modelSource, model, current(), alias);
        }

        @Override
        public GlueId id() {
            current();
            return new GlueId(ref.id());
        }

        @Override
        public String name() {
            return inspector.glueName(current());
        }

        @Override
        public void setName(final String name) {
            final GlueRef value = current();
            inspector.setGlueName(identity, modelSource, model, value, name);
        }

        @Override
        public void setId(final GlueId id) {
            final GlueRef value = current();
            inspector.setGlueId(identity, modelSource, model, value, id);
        }

        @Override
        public float intensity() {
            return inspector.glueIntensity(identity, modelSource, model, current());
        }

        @Override
        public void setIntensity(final float intensity) {
            final GlueRef value = current();
            inspector.setGlueIntensity(identity, modelSource, model, value, intensity);
        }

        @Override
        public void setDrawableA(final ArtMeshId id) {
            final GlueRef value = current();
            inspector.setGlueDrawableA(identity, modelSource, model, value, id);
        }

        @Override
        public void setDrawableB(final ArtMeshId id) {
            final GlueRef value = current();
            inspector.setGlueDrawableB(identity, modelSource, model, value, id);
        }

        @Override
        public int index() {
            final GlueRef value = current();
            final List<GlueRef> values = core.glueRefs(identity, modelSource, model);
            for (int index = 0; index < values.size(); index++) {
                if (values.get(index).source() == value.source()) return index;
            }
            throw unavailable("Editor Glue is outside the active model.");
        }

        @Override
        public ArtMeshId drawableAId() {
            return new ArtMeshId(
                    target("cubism.editor-model.glue-source.target-art-mesh-a").id());
        }

        @Override
        public ArtMeshId drawableBId() {
            return new ArtMeshId(
                    target("cubism.editor-model.glue-source.target-art-mesh-b").id());
        }

        @Override
        public List<ParameterId> parameterIds() {
            return bindingReads.parameterIds(current().source());
        }

        @Override
        public int drawableA() {
            return core.artMeshIndex(
                    identity,
                    modelSource,
                    model,
                    target("cubism.editor-model.glue-source.target-art-mesh-a").source());
        }

        @Override
        public int drawableB() {
            return core.artMeshIndex(
                    identity,
                    modelSource,
                    model,
                    target("cubism.editor-model.glue-source.target-art-mesh-b").source());
        }

        @Override
        public IntSequence parameters() {
            return intSequence(core.parameterIndices(model, parameterIds()));
        }
    }

    private final class EditorDeformers implements Deformers {
        final String identity;
        final Object source;
        final Object model;

        EditorDeformers(final String identity, final Object source, final Object model) {
            this.identity = identity;
            this.source = source;
            this.model = model;
        }

        @Override
        public List<Deformer> all() {
            return core.deformerRefs(identity, source, model).stream()
                    .map(this::view)
                    .toList();
        }

        @Override
        public Deformer find(final DeformerId id) {
            Objects.requireNonNull(id, "id");
            return core.deformerRefs(identity, source, model).stream()
                    .filter(ref -> ref.id().equals(id.value()))
                    .findFirst()
                    .map(this::view)
                    .orElseThrow(() -> new NoSuchElementException("Cubism Deformer is absent: " + id.value()));
        }

        private Deformer view(final DeformerRef ref) {
            return ref.kind() == Kind.WARP
                    ? new EditorWarp(identity, source, model, ref)
                    : new EditorRotation(identity, source, model, ref);
        }

        @Override
        public WarpDeformer createWarp(
                final String name, final Part parent, final int index, final int rows, final int columns) {
            final Object parentSource = nativeSourceOf(parent, "Part parent");
            core.requireCurrentPartSource(source, parentSource);
            final EditorObjectHierarchyEditAccess.CreatedSource created = hierarchyEditAccess.createWarpSource(
                    identity,
                    source,
                    model,
                    name,
                    parentSource,
                    false,
                    index,
                    EditorObjectHierarchyEditAccess.defaultWarpGrid(rows, columns));
            return core.deformerRefs(identity, source, model).stream()
                    .filter(ref -> ref.source() == created.source())
                    .findFirst()
                    .map(ref -> (WarpDeformer) new EditorWarp(identity, source, model, ref))
                    .orElseThrow(
                            () -> unavailable("Created Warp Deformer is absent after the Editor instance update."));
        }

        @Override
        public WarpDeformer createWarp(
                final String name,
                final Part parent,
                final int index,
                final Optional<DeformerId> target,
                final WarpGrid grid) {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(grid, "grid");
            return EditorHostThread.dispatch("Create initialized Warp", () -> {
                final Object parentSource = nativeSourceOf(parent, "Part parent");
                core.requireCurrentPartSource(source, parentSource);
                final Object targetSource = target.map(id -> core.deformerRefs(identity, source, model).stream()
                                .filter(ref -> ref.id().equals(id.value()))
                                .findFirst()
                                .orElseThrow(() -> new NoSuchElementException("Warp target is absent: " + id.value()))
                                .source())
                        .orElse(null);
                final var created = hierarchyEditAccess.createWarpSource(
                        identity, source, model, name, parentSource, false, index, grid, targetSource);
                return core.deformerRefs(identity, source, model).stream()
                        .filter(ref -> ref.source() == created.source())
                        .findFirst()
                        .map(ref -> (WarpDeformer) new EditorWarp(identity, source, model, ref))
                        .orElseThrow(() -> unavailable("Created Warp is absent after instance update."));
            });
        }

        @Override
        public RotationDeformer createRotation(final String name, final Part parent, final int index) {
            final Object parentSource = nativeSourceOf(parent, "Part parent");
            core.requireCurrentPartSource(source, parentSource);
            final EditorObjectHierarchyEditAccess.CreatedSource created = hierarchyEditAccess.createRotationSource(
                    identity,
                    source,
                    model,
                    name,
                    parentSource,
                    false,
                    index,
                    new RotationDeformerForm(0F, 0F, 0F, 1F, false, false));
            return core.deformerRefs(identity, source, model).stream()
                    .filter(ref -> ref.source() == created.source())
                    .findFirst()
                    .map(ref -> (RotationDeformer) new EditorRotation(identity, source, model, ref))
                    .orElseThrow(
                            () -> unavailable("Created Rotation Deformer is absent after the Editor instance update."));
        }

        @Override
        public void remove(final Deformer deformer) {
            Objects.requireNonNull(deformer, "deformer");
            final Object nodeSource = nativeSourceOf(deformer, "Deformer");
            core.requireCurrentDeformerSource(identity, source, model, nodeSource);
            hierarchyEditAccess.remove(identity, source, model, nodeSource, "Deformer");
        }

        @Override
        public void applyToChildren(final Deformer deformer) {
            Objects.requireNonNull(deformer, "deformer");
            final Object nodeSource = nativeSourceOf(deformer, "Deformer");
            core.requireCurrentDeformerSource(identity, source, model, nodeSource);
            requireHierarchyEditAccess();
            hierarchyEditAccess.applyToChildren(
                    identity,
                    source,
                    model,
                    nodeSource,
                    () -> core.requireCurrentDeformerSource(identity, source, model, nodeSource));
        }
    }

    private final class EditorWarpDeformers implements WarpDeformers {
        final String identity;
        final Object source;
        final Object model;

        EditorWarpDeformers(final String identity, final Object source, final Object model) {
            this.identity = identity;
            this.source = source;
            this.model = model;
        }

        @Override
        public List<WarpDeformer> all() {
            return core.deformerRefs(identity, source, model).stream()
                    .filter(ref -> ref.kind() == Kind.WARP)
                    .map(ref -> (WarpDeformer) new EditorWarp(identity, source, model, ref))
                    .toList();
        }

        @Override
        public WarpDeformer find(final DeformerId id) {
            Objects.requireNonNull(id, "id");
            return core.deformerRefs(identity, source, model).stream()
                    .filter(ref -> ref.kind() == Kind.WARP && ref.id().equals(id.value()))
                    .findFirst()
                    .map(ref -> (WarpDeformer) new EditorWarp(identity, source, model, ref))
                    .orElseThrow(() -> new NoSuchElementException("Cubism Warp Deformer is absent: " + id.value()));
        }
    }

    private final class EditorRotationDeformers implements RotationDeformers {
        final String identity;
        final Object source;
        final Object model;

        EditorRotationDeformers(final String identity, final Object source, final Object model) {
            this.identity = identity;
            this.source = source;
            this.model = model;
        }

        @Override
        public List<RotationDeformer> all() {
            return core.deformerRefs(identity, source, model).stream()
                    .filter(ref -> ref.kind() == Kind.ROTATION)
                    .map(ref -> (RotationDeformer) new EditorRotation(identity, source, model, ref))
                    .toList();
        }

        @Override
        public RotationDeformer find(final DeformerId id) {
            Objects.requireNonNull(id, "id");
            return core.deformerRefs(identity, source, model).stream()
                    .filter(ref -> ref.kind() == Kind.ROTATION && ref.id().equals(id.value()))
                    .findFirst()
                    .map(ref -> (RotationDeformer) new EditorRotation(identity, source, model, ref))
                    .orElseThrow(() -> new NoSuchElementException("Cubism Rotation Deformer is absent: " + id.value()));
        }
    }

    private final class EditorGlues implements Glues {
        final String identity;
        final Object source;
        final Object model;

        EditorGlues(final String identity, final Object source, final Object model) {
            this.identity = identity;
            this.source = source;
            this.model = model;
        }

        @Override
        public List<Glue> all() {
            return core.glueRefs(identity, source, model).stream()
                    .map(ref -> (Glue) new EditorGlue(identity, source, model, ref))
                    .toList();
        }

        @Override
        public Glue find(final GlueId id) {
            Objects.requireNonNull(id, "id");
            return core.glueRefs(identity, source, model).stream()
                    .filter(ref -> ref.id().equals(id.value()))
                    .findFirst()
                    .map(ref -> (Glue) new EditorGlue(identity, source, model, ref))
                    .orElseThrow(() -> new NoSuchElementException("Cubism Glue is absent: " + id.value()));
        }

        @Override
        public Optional<String> providerVersion() {
            currentGuard.requireCurrent(identity, model);
            return Optional.of(resolver.cubismVersion());
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
}

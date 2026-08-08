package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.EditorObjectReadSelectorContract;
import dev.turboism.mapping.verification.EditorDeformerInspectorSelectorContract;
import dev.turboism.mapping.verification.EditorGlueInspectorSelectorContract;
import dev.turboism.mapping.verification.EditorObjectWriteSelectorContract;
import dev.turboism.mapping.verification.EditorParameterBindingReadSelectorContract;
import dev.turboism.mapping.verification.EditorInspectorDrawableWrite52SelectorContract;
import dev.turboism.mapping.verification.EditorInspectorDrawableWriteSelectorContract;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
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
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

/** Exact-version Editor projection for ArtMesh, Deformer, and Glue authoring reads. */
final class EditorObjectReadAccess {

    @FunctionalInterface
    interface CurrentGuard {
        void requireCurrent(String identity, Object model);
    }

    private final VerifiedMemberResolver resolver;
    private final CurrentGuard currentGuard;
    private final EditorMorphTargetAccess morphTargetAccess;

    private final dev.turboism.adapter.cubism.core.CoreEvaluatedJoin evaluatedJoin;

    /**
     * Optional lazy-publish hook, invoked at most once per binding identity when the first
     * evaluated read fails with MODEL_UNAVAILABLE. Returns true when the model was
     * published and the read may be retried once; null disables lazy publication.
     */
    private final java.util.function.Function<String, Boolean> lazyPublish;

    private final EditorObjectHierarchyEditAccess hierarchyEditAccess;

    EditorObjectReadAccess(
        final VerifiedMemberResolver resolver,
        final CurrentGuard currentGuard,
        final EditorMorphTargetAccess morphTargetAccess,
        final dev.turboism.adapter.cubism.core.CoreEvaluatedJoin evaluatedJoin
    ) {
        this(resolver, currentGuard, morphTargetAccess, evaluatedJoin, null, null);
    }

    EditorObjectReadAccess(
        final VerifiedMemberResolver resolver,
        final CurrentGuard currentGuard,
        final EditorMorphTargetAccess morphTargetAccess,
        final dev.turboism.adapter.cubism.core.CoreEvaluatedJoin evaluatedJoin,
        final java.util.function.Function<String, Boolean> lazyPublish
    ) {
        this(resolver, currentGuard, morphTargetAccess, evaluatedJoin, lazyPublish, null);
    }

    EditorObjectReadAccess(
        final VerifiedMemberResolver resolver,
        final CurrentGuard currentGuard,
        final EditorMorphTargetAccess morphTargetAccess,
        final dev.turboism.adapter.cubism.core.CoreEvaluatedJoin evaluatedJoin,
        final java.util.function.Function<String, Boolean> lazyPublish,
        final EditorObjectHierarchyEditAccess hierarchyEditAccess
    ) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.currentGuard = Objects.requireNonNull(currentGuard, "currentGuard");
        this.morphTargetAccess = Objects.requireNonNull(morphTargetAccess, "morphTargetAccess");
        this.evaluatedJoin = evaluatedJoin;
        this.lazyPublish = lazyPublish;
        this.hierarchyEditAccess = hierarchyEditAccess;
    }

    Drawables drawables(final String identity, final Object source, final Object model) {
        requireAuthorized();
        return new EditorDrawables(identity, source, model);
    }

    Deformers deformers(final String identity, final Object source, final Object model) {
        requireAuthorized();
        return new EditorDeformers(identity, source, model);
    }

    WarpDeformers warpDeformers(final String identity, final Object source, final Object model) {
        requireAuthorized();
        return new EditorWarpDeformers(identity, source, model);
    }

    RotationDeformers rotationDeformers(
        final String identity,
        final Object source,
        final Object model
    ) {
        requireAuthorized();
        return new EditorRotationDeformers(identity, source, model);
    }

    Glues glues(final String identity, final Object source, final Object model) {
        requireAuthorized();
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
        for (ObjectRef value : artMeshes(identity, source, model)) {
            parameterBindings(
                value.source(),
                ParameterBindingTarget.artMesh(new ArtMeshId(value.id()))
            ).stream().filter(binding -> binding.parameterId().equals(parameterId)).forEach(result::add);
        }
        for (DeformerRef value : deformerRefs(identity, source, model)) {
            final ParameterBindingTarget target = value.kind() == Kind.WARP
                ? ParameterBindingTarget.warpDeformer(new DeformerId(value.id()))
                : ParameterBindingTarget.rotationDeformer(new DeformerId(value.id()));
            parameterBindings(value.source(), target).stream()
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
            case ART_MESH -> artMeshes(identity, source, model).stream()
                .filter(value -> value.id().equals(target.id()))
                .map(ObjectRef::source)
                .findFirst()
                .orElseThrow(() -> stale("ArtMesh", target.id()));
            case WARP_DEFORMER, ROTATION_DEFORMER -> deformerRefs(identity, source, model).stream()
                .filter(value -> value.id().equals(target.id()))
                .filter(value -> value.kind() == (target.type() == dev.turboism.sdk.cubism.model.ParameterBindingTargetType.WARP_DEFORMER
                    ? Kind.WARP : Kind.ROTATION))
                .map(DeformerRef::source)
                .findFirst()
                .orElseThrow(() -> stale("Deformer", target.id()));
        };
    }

    private void requireAuthorized() {
        if (!resolver.authorizesFeature(
            EditorObjectReadSelectorContract.ADAPTER_SLICE_ID,
            EditorObjectReadSelectorContract.CAPABILITY_ID,
            EditorObjectReadSelectorContract.REQUIRED_ALIASES
        )) {
            throw new UnsupportedOperationException(
                "Editor ArtMesh and Deformer reads require exact verified host evidence."
            );
        }
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

    private void requireWriteAuthorized(final Kind kind) {
        final String capability = switch (kind) {
            case ART_MESH -> EditorObjectWriteSelectorContract.ART_MESH_CAPABILITY_ID;
            case WARP -> EditorObjectWriteSelectorContract.WARP_CAPABILITY_ID;
            case ROTATION -> EditorObjectWriteSelectorContract.ROTATION_CAPABILITY_ID;
        };
        final java.util.Set<String> aliases = switch (kind) {
            case ART_MESH -> EditorObjectWriteSelectorContract.ART_MESH_REQUIRED_ALIASES;
            case WARP -> EditorObjectWriteSelectorContract.WARP_REQUIRED_ALIASES;
            case ROTATION -> EditorObjectWriteSelectorContract.ROTATION_REQUIRED_ALIASES;
        };
        if (!resolver.authorizesFeature(EditorObjectWriteSelectorContract.ADAPTER_SLICE_ID, capability, aliases)) {
            throw new UnsupportedOperationException(
                "Editor " + kind.label + " writes require exact verified host evidence."
            );
        }
    }

    /** Undo envelope family mirrored from the Inspector prepareUndo routing. */
    private enum UndoKind {
        ALL_EDIT,
        BASIC_SETTING,
        KEYFORM_EDIT
    }

    private boolean isCubism52() {
        return resolver.isExactCubismVersion(EditorInspectorDrawableWrite52SelectorContract.CUBISM_VERSION);
    }

    private void requireInspectorWriteAuthorized() {
        final boolean authorized = isCubism52()
            ? resolver.authorizesFeature(
                EditorInspectorDrawableWrite52SelectorContract.ADAPTER_SLICE_ID,
                EditorInspectorDrawableWrite52SelectorContract.CAPABILITY_ID,
                EditorInspectorDrawableWrite52SelectorContract.REQUIRED_ALIASES
            )
            : resolver.authorizesFeature(
                EditorInspectorDrawableWriteSelectorContract.ADAPTER_SLICE_ID,
                EditorInspectorDrawableWriteSelectorContract.CAPABILITY_ID,
                EditorInspectorDrawableWriteSelectorContract.REQUIRED_ALIASES
            );
        if (!authorized) {
            throw new UnsupportedOperationException(
                "Editor ArtMesh Inspector writes require exact verified host evidence."
            );
        }
    }

    private List<ObjectRef> artMeshes(final String identity, final Object source, final Object model) {
        currentGuard.requireCurrent(identity, model);
        final List<?> sources = list(
            resolver.invoke("cubism.editor-model.model-source.all-art-meshes", source),
            "Editor ArtMesh source collection"
        );
        final List<?> instances = list(
            resolver.invoke("cubism.editor-model.model.all-art-meshes", model),
            "Editor ArtMesh instance collection"
        );
        final java.util.IdentityHashMap<Object, Object> instancesBySource = new java.util.IdentityHashMap<>();
        for (Object instance : instances) {
            if (!resolver.isInstance("cubism.editor-model.art-mesh.class", instance)) {
                throw unavailable("Editor ArtMesh instance type is invalid.");
            }
            final Object objectSource = resolver.invoke("cubism.editor-model.art-mesh.source", instance);
            if (!containsIdentity(sources, objectSource)
                || !resolver.isInstance("cubism.editor-model.art-mesh-source.class", objectSource)
                || instancesBySource.put(objectSource, instance) != null) {
                throw unavailable("Editor ArtMesh source/instance binding is invalid.");
            }
        }
        final ArrayList<ObjectRef> values = new ArrayList<>(sources.size());
        final java.util.HashSet<String> ids = new java.util.HashSet<>();
        for (Object objectSource : sources) {
            if (!resolver.isInstance("cubism.editor-model.art-mesh-source.class", objectSource)) {
                throw unavailable("Editor ArtMesh source type is invalid.");
            }
            final Object instance = instancesBySource.remove(objectSource);
            if (instance == null) throw unavailable("Editor ArtMesh source has no active instance.");
            final String id = objectId(objectSource);
            if (!ids.add(id)) throw unavailable("Editor ArtMesh identifiers are not unique.");
            values.add(new ObjectRef(id, objectSource, instance));
        }
        if (!instancesBySource.isEmpty()) throw unavailable("Editor ArtMesh instance has no source.");
        return List.copyOf(values);
    }

    private List<DeformerRef> deformerRefs(final String identity, final Object source, final Object model) {
        currentGuard.requireCurrent(identity, model);
        final List<?> sources = list(
            resolver.invoke("cubism.editor-model.model-source.all-deformers", source),
            "Editor Deformer source collection"
        );
        final List<?> instances = list(
            resolver.invoke("cubism.editor-model.model.all-deformers", model),
            "Editor Deformer instance collection"
        );
        final java.util.IdentityHashMap<Object, Object> instancesBySource = new java.util.IdentityHashMap<>();
        for (Object instance : instances) {
            final Object objectSource = resolver.invoke("cubism.editor-model.deformer.source", instance);
            if (!containsIdentity(sources, objectSource)
                || instancesBySource.put(objectSource, instance) != null) {
                throw unavailable("Editor Deformer source/instance binding is invalid.");
            }
        }
        final ArrayList<DeformerRef> values = new ArrayList<>(sources.size());
        final java.util.HashSet<String> ids = new java.util.HashSet<>();
        for (Object objectSource : sources) {
            final Object instance = instancesBySource.remove(objectSource);
            if (instance == null) throw unavailable("Editor Deformer source has no active instance.");
            final Kind kind;
            if (resolver.isInstance("cubism.editor-model.warp.class", instance)
                && resolver.isInstance("cubism.editor-model.warp-source.class", objectSource)) {
                kind = Kind.WARP;
            } else if (resolver.isInstance("cubism.editor-model.rotation.class", instance)
                && resolver.isInstance("cubism.editor-model.rotation-source.class", objectSource)) {
                kind = Kind.ROTATION;
            } else {
                throw unavailable("Editor Deformer type is unsupported by this verified projection.");
            }
            final String id = objectId(objectSource);
            if (!ids.add(id)) throw unavailable("Editor Deformer identifiers are not unique.");
            values.add(new DeformerRef(id, objectSource, instance, kind));
        }
        if (!instancesBySource.isEmpty()) throw unavailable("Editor Deformer instance has no source.");
        return List.copyOf(values);
    }

    private List<GlueRef> glueRefs(final String identity, final Object source, final Object model) {
        currentGuard.requireCurrent(identity, model);
        final List<?> sources = list(
            resolver.invoke("cubism.editor-model.model-source.all-glues", source),
            "Editor Glue source collection"
        );
        final ArrayList<GlueRef> values = new ArrayList<>(sources.size());
        final java.util.HashSet<String> ids = new java.util.HashSet<>();
        for (Object glueSource : sources) {
            if (!resolver.isInstance("cubism.editor-model.glue-source.class", glueSource)) {
                throw unavailable("Editor Glue source type is invalid.");
            }
            final String id = objectId(glueSource);
            if (!ids.add(id)) throw unavailable("Editor Glue identifiers are not unique.");
            values.add(new GlueRef(id, glueSource));
        }
        return List.copyOf(values);
    }

    private ObjectRef currentArtMesh(
        final String identity,
        final Object modelSource,
        final Object model,
        final ObjectRef expected
    ) {
        return artMeshes(identity, modelSource, model).stream()
            .filter(value -> value.id().equals(expected.id()))
            .filter(value -> value.source() == expected.source() && value.instance() == expected.instance())
            .findFirst()
            .orElseThrow(() -> stale("ArtMesh", expected.id()));
    }

    private DeformerRef currentDeformer(
        final String identity,
        final Object modelSource,
        final Object model,
        final DeformerRef expected
    ) {
        return deformerRefs(identity, modelSource, model).stream()
            .filter(value -> value.id().equals(expected.id()))
            .filter(value -> value.source() == expected.source() && value.instance() == expected.instance())
            .filter(value -> value.kind() == expected.kind())
            .findFirst()
            .orElseThrow(() -> stale("Deformer", expected.id()));
    }

    private GlueRef currentGlue(
        final String identity,
        final Object modelSource,
        final Object model,
        final GlueRef expected
    ) {
        return glueRefs(identity, modelSource, model).stream()
            .filter(value -> value.id().equals(expected.id()) && value.source() == expected.source())
            .findFirst()
            .orElseThrow(() -> stale("Glue", expected.id()));
    }

    private Optional<PartId> parentPartId(final Object objectSource) {
        final Object parent = parentPartSource(objectSource);
        return parent == null ? Optional.empty() : Optional.of(partId(parent));
    }

    private int parentPartIndex(final Object modelSource, final Object objectSource) {
        final Object parent = parentPartSource(objectSource);
        if (parent == null) return -1;
        final List<?> parts = list(
            resolver.invoke("cubism.editor-model.model-source.parts", modelSource),
            "Editor Part source collection"
        );
        for (int index = 0; index < parts.size(); index++) {
            final Object candidate = parts.get(index);
            if (!resolver.isInstance("cubism.editor-model.part-source.class", candidate)) {
                throw unavailable("Editor Part source type is invalid.");
            }
            if (candidate == parent) return index;
        }
        throw unavailable("Editor object parent Part is outside the active model.");
    }

    private Object parentPartSource(final Object objectSource) {
        final Object parent = resolver.invoke("cubism.editor-model.part-source.parent", objectSource);
        if (parent != null && !resolver.isInstance("cubism.editor-model.part-source.class", parent)) {
            throw unavailable("Editor object parent Part is invalid.");
        }
        return parent;
    }

    private PartId partId(final Object partSource) {
        final Object id = resolver.invoke("cubism.editor-model.part-source.id", partSource);
        return new PartId(text(
            resolver.invoke("cubism.editor-model.part-id.value", id),
            "Editor Part ID"
        ));
    }

    private Optional<DeformerId> parentDeformerId(
        final String identity,
        final Object modelSource,
        final Object model,
        final Object objectSource
    ) {
        final Object target = targetDeformerSource(objectSource);
        if (target == null) return Optional.empty();
        return Optional.of(new DeformerId(deformerRef(identity, modelSource, model, target).id()));
    }

    private int parentDeformerIndex(
        final String identity,
        final Object modelSource,
        final Object model,
        final Object objectSource
    ) {
        final Object target = targetDeformerSource(objectSource);
        if (target == null) return -1;
        final List<DeformerRef> values = deformerRefs(identity, modelSource, model);
        for (int index = 0; index < values.size(); index++) {
            if (values.get(index).source() == target) return index;
        }
        throw unavailable("Editor object target Deformer is outside the active model.");
    }

    private Object targetDeformerSource(final Object objectSource) {
        return resolver.invoke(
            "cubism.editor-model.parameter-controllable-source.target-deformer-source",
            objectSource
        );
    }

    private DeformerRef deformerRef(
        final String identity,
        final Object modelSource,
        final Object model,
        final Object source
    ) {
        return deformerRefs(identity, modelSource, model).stream()
            .filter(value -> value.source() == source)
            .findFirst()
            .orElseThrow(() -> unavailable("Editor object target Deformer is outside the active model."));
    }

    private List<ArtMeshId> maskIds(
        final String identity,
        final Object modelSource,
        final Object model,
        final Object artMeshSource
    ) {
        final List<ObjectRef> meshes = artMeshes(identity, modelSource, model);
        final java.util.HashMap<String, ArtMeshId> idsByGuid = new java.util.HashMap<>();
        for (ObjectRef mesh : meshes) {
            final String guid = guidValue(resolver.invoke(
                "cubism.editor-model.art-mesh-source.guid",
                mesh.source()
            ));
            if (idsByGuid.put(guid, new ArtMeshId(mesh.id())) != null) {
                throw unavailable("Editor ArtMesh GUIDs are not unique.");
            }
        }
        final List<?> masks = iterable(
            resolver.invoke("cubism.editor-model.art-mesh-source.clip-guid-list", artMeshSource),
            "Editor ArtMesh clipping masks"
        );
        final ArrayList<ArtMeshId> result = new ArrayList<>(masks.size());
        for (Object mask : masks) {
            final String guid = guidValue(mask);
            final ArtMeshId id = idsByGuid.get(guid);
            if (id == null) throw unavailable("Editor ArtMesh clipping mask is outside the active model.");
            result.add(id);
        }
        return List.copyOf(result);
    }

    private String guidValue(final Object guid) {
        return text(resolver.invoke("cubism.editor-model.guid.value", guid), "Editor GUID");
    }

    private List<Integer> parameterIndices(final Object model, final List<ParameterId> ids) {
        final Object parameterSet = resolver.invoke("cubism.editor-model.model.parameter-set", model);
        final List<?> parameters = list(
            resolver.invoke("cubism.editor-model.parameter-set.parameters", parameterSet),
            "Editor parameter collection"
        );
        final java.util.HashMap<String, Integer> indices = new java.util.HashMap<>();
        for (int index = 0; index < parameters.size(); index++) {
            final Object parameter = parameters.get(index);
            if (!resolver.isInstance("cubism.editor-model.parameter.class", parameter)) {
                throw unavailable("Editor parameter collection contains an invalid value.");
            }
            final String id = text(resolver.invoke(
                "cubism.editor-model.id.value",
                resolver.invoke("cubism.editor-model.parameter.id", parameter)
            ), "Editor parameter ID");
            if (indices.put(id, index) != null) {
                throw unavailable("Editor parameter identifiers are not unique.");
            }
        }
        return ids.stream().map(id -> {
            final Integer index = indices.get(id.value());
            if (index == null) throw unavailable("Editor parameter binding targets an absent parameter.");
            return index;
        }).toList();
    }

    private int artMeshIndex(
        final String identity,
        final Object modelSource,
        final Object model,
        final Object source
    ) {
        final List<ObjectRef> values = artMeshes(identity, modelSource, model);
        for (int index = 0; index < values.size(); index++) {
            if (values.get(index).source() == source) return index;
        }
        throw unavailable("Editor ArtMesh is outside the active model.");
    }

    private int deformerIndex(
        final String identity,
        final Object modelSource,
        final Object model,
        final Object source
    ) {
        final List<DeformerRef> values = deformerRefs(identity, modelSource, model);
        for (int index = 0; index < values.size(); index++) {
            if (values.get(index).source() == source) return index;
        }
        throw unavailable("Editor Deformer is outside the active model.");
    }

    private List<Integer> artMeshIndices(
        final String identity,
        final Object modelSource,
        final Object model,
        final List<ArtMeshId> ids
    ) {
        final List<ObjectRef> meshes = artMeshes(identity, modelSource, model);
        final java.util.HashMap<String, Integer> indices = new java.util.HashMap<>();
        for (int index = 0; index < meshes.size(); index++) indices.put(meshes.get(index).id(), index);
        return ids.stream().map(id -> {
            final Integer index = indices.get(id.value());
            if (index == null) throw unavailable("Editor ArtMesh relation targets an absent ArtMesh.");
            return index;
        }).toList();
    }

    private ObjectRef glueTarget(
        final String identity,
        final Object modelSource,
        final Object model,
        final GlueRef glue,
        final String alias
    ) {
        final Object target = resolver.invoke(alias, glue.source());
        if (target == null) throw unavailable("Editor Glue target ArtMesh is unavailable.");
        return artMeshes(identity, modelSource, model).stream()
            .filter(value -> value.source() == target)
            .findFirst()
            .orElseThrow(() -> unavailable("Editor Glue target ArtMesh is outside the active model."));
    }

    private String objectId(final Object source) {
        final Object id = resolver.invoke("cubism.editor-model.parameter-controllable-source.id", source);
        return text(resolver.invoke("cubism.editor-model.id.value", id), "Editor object ID");
    }

    private String objectName(final Object source, final String id) {
        final Object value = resolver.invoke(
            "cubism.editor-model.parameter-controllable-source.local-name",
            source
        );
        if (value == null) return id;
        if (!(value instanceof String name)) throw unavailable("Editor object name is invalid.");
        return name.isBlank() ? id : name;
    }

    private boolean sourceFlag(final String alias, final Object source, final String label) {
        return flag(resolver.invoke(alias, source), label);
    }

    private Object artMeshForm(final Object instance) {
        final Object form = resolver.invoke("cubism.editor-model.art-mesh.current-keyform", instance);
        if (form == null) throw unavailable("Editor ArtMesh current keyform is unavailable.");
        return form;
    }

    private Object deformerForm(final Object instance) {
        final Object form = resolver.invoke("cubism.editor-model.deformer.current-keyform", instance);
        if (form == null) throw unavailable("Editor Deformer current keyform is unavailable.");
        return form;
    }

    private ArtMeshGeometry geometry(final Object source, final Object instance) {
        final float[] positions = floats(
            resolver.invoke("cubism.editor-model.art-mesh-form.positions", artMeshForm(instance)),
            "Editor ArtMesh positions"
        );
        final float[] uvs = floats(
            resolver.invoke("cubism.editor-model.art-mesh-source.uvs", source),
            "Editor ArtMesh UVs"
        );
        final int[] indices = ints(
            resolver.invoke("cubism.editor-model.art-mesh-source.indices", source),
            "Editor ArtMesh indices"
        );
        return new ArtMeshGeometry(points(positions, "ArtMesh positions"), points(uvs, "ArtMesh UVs"), boxed(indices));
    }

    private WarpGrid warpGrid(final Object source, final Object instance) {
        final int rows = integer(
            resolver.invoke("cubism.editor-model.warp-source.row", source),
            "Editor Warp row count"
        );
        final int columns = integer(
            resolver.invoke("cubism.editor-model.warp-source.col", source),
            "Editor Warp column count"
        );
        final boolean quad = flag(
            resolver.invoke("cubism.editor-model.warp-source.quad-transform", source),
            "Editor Warp quad-transform flag"
        );
        final float[] positions = floats(
            resolver.invoke("cubism.editor-model.warp-form.positions", deformerForm(instance)),
            "Editor Warp control points"
        );
        return new WarpGrid(rows, columns, quad, points(positions, "Warp control points"));
    }

    private RotationDeformerForm rotationForm(final Object instance) {
        final Object form = deformerForm(instance);
        return new RotationDeformerForm(
            number(resolver.invoke("cubism.editor-model.rotation-form.angle", form), "Rotation angle"),
            number(resolver.invoke("cubism.editor-model.rotation-form.origin-x", form), "Rotation origin X"),
            number(resolver.invoke("cubism.editor-model.rotation-form.origin-y", form), "Rotation origin Y"),
            number(resolver.invoke("cubism.editor-model.rotation-form.scale", form), "Rotation scale"),
            flag(resolver.invoke("cubism.editor-model.rotation-form.reflect-x", form), "Rotation reflection X"),
            flag(resolver.invoke("cubism.editor-model.rotation-form.reflect-y", form), "Rotation reflection Y")
        );
    }

    private static float[] flattenArray(final List<Point2> points) {
        final float[] values = new float[points.size() * 2];
        for (int index = 0; index < points.size(); index++) {
            values[index * 2] = points.get(index).x();
            values[index * 2 + 1] = points.get(index).y();
        }
        return values;
    }

    private static int[] indexArray(final List<Integer> indices) {
        return indices.stream().mapToInt(Integer::intValue).toArray();
    }

    private void replaceArtMeshGeometry(
        final Object modelSource,
        final ObjectRef value,
        final ArtMeshGeometry geometry
    ) {
        Objects.requireNonNull(geometry, "geometry");
        requireWriteAuthorized(Kind.ART_MESH);
        if (geometry.equals(EditorObjectReadAccess.this.geometry(value.source(), value.instance()))) return;
        final Object form = artMeshForm(value.instance());
        final float[] originalSourcePositions = floats(
            resolver.invoke("cubism.editor-model.art-mesh-source.positions", value.source()),
            "Editor ArtMesh source positions"
        ).clone();
        final float[] originalFormPositions = floats(
            resolver.invoke("cubism.editor-model.art-mesh-form.positions", form),
            "Editor ArtMesh form positions"
        ).clone();
        final float[] originalUvs = floats(
            resolver.invoke("cubism.editor-model.art-mesh-source.uvs", value.source()),
            "Editor ArtMesh UVs"
        ).clone();
        final int[] originalIndices = ints(
            resolver.invoke("cubism.editor-model.art-mesh-source.indices", value.source()),
            "Editor ArtMesh indices"
        ).clone();
        final float[] positions = flattenArray(geometry.positions());
        final float[] uvs = flattenArray(geometry.uvs());
        final int[] indices = indexArray(geometry.triangleIndices());
        write(Kind.ART_MESH, modelSource, value.source(), "Replace ArtMesh geometry", () -> {
            try {
                resolver.invoke("cubism.editor-model.art-mesh-source.set-positions", value.source(), positions.clone());
                resolver.invoke("cubism.editor-model.art-mesh-source.set-uvs", value.source(), uvs.clone());
                resolver.invoke("cubism.editor-model.art-mesh-source.set-indices", value.source(), indices.clone());
                resolver.invoke(
                    "cubism.editor-model.art-mesh-form.set-positions",
                    form,
                    positions.clone()
                );
            } catch (RuntimeException failure) {
                compensate(
                    "ArtMesh geometry",
                    failure,
                    () -> {
                        resolver.invoke("cubism.editor-model.art-mesh-source.set-positions", value.source(), originalSourcePositions.clone());
                        resolver.invoke("cubism.editor-model.art-mesh-source.set-uvs", value.source(), originalUvs.clone());
                        resolver.invoke("cubism.editor-model.art-mesh-source.set-indices", value.source(), originalIndices.clone());
                        resolver.invoke("cubism.editor-model.art-mesh-form.set-positions", form, originalFormPositions.clone());
                    },
                    () -> java.util.Arrays.equals(originalSourcePositions, floats(
                        resolver.invoke("cubism.editor-model.art-mesh-source.positions", value.source()),
                        "Editor ArtMesh source positions"
                    )) && java.util.Arrays.equals(originalUvs, floats(
                        resolver.invoke("cubism.editor-model.art-mesh-source.uvs", value.source()),
                        "Editor ArtMesh UVs"
                    )) && java.util.Arrays.equals(originalIndices, ints(
                        resolver.invoke("cubism.editor-model.art-mesh-source.indices", value.source()),
                        "Editor ArtMesh indices"
                    )) && java.util.Arrays.equals(originalFormPositions, floats(
                        resolver.invoke("cubism.editor-model.art-mesh-form.positions", form),
                        "Editor ArtMesh form positions"
                    ))
                );
            }
        });
    }

    private void replaceWarpGrid(
        final Object modelSource,
        final DeformerRef value,
        final WarpGrid grid
    ) {
        Objects.requireNonNull(grid, "grid");
        requireWriteAuthorized(Kind.WARP);
        if (grid.equals(warpGrid(value.source(), value.instance()))) return;
        final Object form = deformerForm(value.instance());
        final int originalRows = integer(
            resolver.invoke("cubism.editor-model.warp-source.row", value.source()),
            "Editor Warp row count"
        );
        final int originalColumns = integer(
            resolver.invoke("cubism.editor-model.warp-source.col", value.source()),
            "Editor Warp column count"
        );
        final boolean originalQuad = flag(
            resolver.invoke("cubism.editor-model.warp-source.quad-transform", value.source()),
            "Editor Warp quad-transform flag"
        );
        final float[] originalPositions = floats(
            resolver.invoke("cubism.editor-model.warp-form.positions", form),
            "Editor Warp control points"
        ).clone();
        final float[] positions = flattenArray(grid.controlPoints());
        write(Kind.WARP, modelSource, value.source(), "Replace Warp grid", () -> {
            try {
                resolver.invoke("cubism.editor-model.warp-source.set-row", value.source(), Integer.valueOf(grid.rows()));
                resolver.invoke("cubism.editor-model.warp-source.set-col", value.source(), Integer.valueOf(grid.columns()));
                resolver.invoke("cubism.editor-model.warp-source.set-quad-transform", value.source(), Boolean.valueOf(grid.quadTransform()));
                resolver.invoke(
                    "cubism.editor-model.warp-form.set-positions",
                    form,
                    positions.clone()
                );
            } catch (RuntimeException failure) {
                compensate(
                    "Warp grid",
                    failure,
                    () -> {
                        resolver.invoke("cubism.editor-model.warp-source.set-row", value.source(), Integer.valueOf(originalRows));
                        resolver.invoke("cubism.editor-model.warp-source.set-col", value.source(), Integer.valueOf(originalColumns));
                        resolver.invoke("cubism.editor-model.warp-source.set-quad-transform", value.source(), Boolean.valueOf(originalQuad));
                        resolver.invoke("cubism.editor-model.warp-form.set-positions", form, originalPositions.clone());
                    },
                    () -> originalRows == integer(
                        resolver.invoke("cubism.editor-model.warp-source.row", value.source()),
                        "Editor Warp row count"
                    ) && originalColumns == integer(
                        resolver.invoke("cubism.editor-model.warp-source.col", value.source()),
                        "Editor Warp column count"
                    ) && originalQuad == flag(
                        resolver.invoke("cubism.editor-model.warp-source.quad-transform", value.source()),
                        "Editor Warp quad-transform flag"
                    ) && java.util.Arrays.equals(originalPositions, floats(
                        resolver.invoke("cubism.editor-model.warp-form.positions", form),
                        "Editor Warp control points"
                    ))
                );
            }
        });
    }

    private void replaceRotationForm(
        final Object modelSource,
        final DeformerRef value,
        final RotationDeformerForm form
    ) {
        Objects.requireNonNull(form, "form");
        requireWriteAuthorized(Kind.ROTATION);
        final RotationDeformerForm original = rotationForm(value.instance());
        if (form.equals(original)) return;
        final Object current = deformerForm(value.instance());
        write(Kind.ROTATION, modelSource, value.source(), "Replace Rotation form", () -> {
            try {
                setRotationForm(current, form);
            } catch (RuntimeException failure) {
                compensate(
                    "Rotation form",
                    failure,
                    () -> setRotationForm(current, original),
                    () -> original.equals(rotationForm(value.instance()))
                );
            }
        });
    }

    private void setRotationForm(final Object current, final RotationDeformerForm form) {
        resolver.invoke("cubism.editor-model.rotation-form.set-angle", current, Float.valueOf(form.angle()));
        resolver.invoke("cubism.editor-model.rotation-form.set-origin-x", current, Float.valueOf(form.originX()));
        resolver.invoke("cubism.editor-model.rotation-form.set-origin-y", current, Float.valueOf(form.originY()));
        resolver.invoke("cubism.editor-model.rotation-form.set-scale", current, Float.valueOf(form.scale()));
        resolver.invoke("cubism.editor-model.rotation-form.set-reflect-x", current, Boolean.valueOf(form.reflectedX()));
        resolver.invoke("cubism.editor-model.rotation-form.set-reflect-y", current, Boolean.valueOf(form.reflectedY()));
    }

    private static void compensate(
        final String label,
        final RuntimeException failure,
        final Runnable restore,
        final java.util.function.BooleanSupplier restored
    ) {
        try {
            restore.run();
            if (!restored.getAsBoolean()) {
                throw new IllegalStateException(label + " rollback verification failed.");
            }
        } catch (RuntimeException rollbackFailure) {
            final IllegalStateException combined = new IllegalStateException(
                label + " mutation failed and rollback did not complete.",
                failure
            );
            combined.addSuppressed(rollbackFailure);
            throw combined;
        }
        throw failure;
    }

    private void write(
        final Kind kind,
        final Object modelSource,
        final Object objectSource,
        final String action,
        final Runnable mutation
    ) {
        requireWriteAuthorized(kind);
        writeEnvelope(UndoKind.ALL_EDIT, kind, modelSource, objectSource, action, mutation, false);
    }

    /** Inspector-write envelope: requires the dedicated inspector capability and mirrors the Inspector undo kinds. */
    private void writeInspector(
        final UndoKind undoKind,
        final Object modelSource,
        final Object objectSource,
        final String action,
        final Runnable mutation
    ) {
        requireInspectorWriteAuthorized();
        writeEnvelope(undoKind, Kind.ART_MESH, modelSource, objectSource, action, mutation, true);
    }

    private void writeEnvelope(
        final UndoKind undoKind,
        final Kind kind,
        final Object modelSource,
        final Object objectSource,
        final String action,
        final Runnable mutation,
        final boolean inspectorRefresh
    ) {
        final Object app = resolver.invokeStatic("cubism.editor-model.app-controller.instance");
        final Object document = resolver.invoke(
            "cubism.editor-model.app-controller.current-document", app
        );
        final String sourceId = objectId(objectSource);
        final long trace = EditorObjectValidationTrace.begin(
            kind.label,
            action,
            sourceId,
            document,
            modelSource
        );
        final Object editMode = resolver.invoke(
            "cubism.editor-model.modeling-document.edit-mode", document
        );
        final Object edit = resolver.invoke(
            "cubism.editor-model.edit-mode.begin", editMode, action
        );
        EditorObjectValidationTrace.event(trace, "edit-begin", kind.label, action, sourceId, document, modelSource, "");
        boolean completed = false;
        try {
            final Object handler = resolver.invoke(
                "cubism.editor-model.parameter-controllable-source.handler", objectSource
            );
            if (!resolver.isInstance("cubism.editor-model.parameter-controllable-handler.class", handler)) {
                throw unavailable("Editor object Undo handler is unavailable.");
            }
            final Object objectUndo = resolver.invoke(
                undoAlias(undoKind),
                handler,
                action
            );
            final Object accepted = resolver.invoke(
                "cubism.editor-model.undo.add", edit, objectUndo, Boolean.TRUE
            );
            if (!(accepted instanceof Boolean value) || !value) {
                throw new IllegalStateException("Cubism rejected the Editor object Undo entry.");
            }
            EditorObjectValidationTrace.event(trace, "undo-admitted", kind.label, action, sourceId, document, modelSource, "accepted=true kind=" + undoKind.name());
            final java.util.concurrent.atomic.AtomicInteger listenerCount = new java.util.concurrent.atomic.AtomicInteger();
            final Object listener = resolver.createFunctionalProxy(
                "cubism.editor-model.undo-listener.class",
                ignored -> {
                    resolver.invoke("cubism.editor-model.model-source.update-instances", modelSource);
                    refresh(app, kind, inspectorRefresh);
                    EditorObjectValidationTrace.event(
                        trace,
                        "undo-listener",
                        kind.label,
                        action,
                        sourceId,
                        document,
                        modelSource,
                        "callback=" + listenerCount.incrementAndGet() + " updated=true refreshed=true"
                    );
                    return null;
                }
            );
            resolver.invoke("cubism.editor-model.undo.add-listener", objectUndo, listener);
            mutation.run();
            EditorObjectValidationTrace.event(trace, "mutation", kind.label, action, sourceId, document, modelSource, "completed=true");
            resolver.invoke("cubism.editor-model.model-source.update-instances", modelSource);
            EditorObjectValidationTrace.event(trace, "instances-updated", kind.label, action, sourceId, document, modelSource, "completed=true");
            refresh(app, kind, inspectorRefresh);
            EditorObjectValidationTrace.event(trace, "refresh", kind.label, action, sourceId, document, modelSource, "palette=true canvas=true");
            resolver.invoke("cubism.editor-model.modeling-document.mark-dirty", document);
            EditorObjectValidationTrace.event(trace, "dirty", kind.label, action, sourceId, document, modelSource, "marked=true");
            completed = true;
        } finally {
            resolver.invoke(
                "cubism.editor-model.edit-mode.end",
                editMode,
                Boolean.valueOf(!completed),
                null
            );
            EditorObjectValidationTrace.event(
                trace,
                "edit-end",
                kind.label,
                action,
                sourceId,
                document,
                modelSource,
                "completed=" + completed + " aborted=" + !completed
            );
        }
    }

    private static String undoAlias(final UndoKind kind) {
        return switch (kind) {
            case ALL_EDIT -> "cubism.editor-model.parameter-controllable-handler.create-undo-for-all-edit";
            case BASIC_SETTING -> "cubism.editor-model.parameter-controllable-handler.create-undo-for-basic-setting";
            case KEYFORM_EDIT -> "cubism.editor-model.parameter-controllable-handler.create-undo-for-keyform-edit";
        };
    }

    // ===== Editor Inspector family writes: Deformer and Glue =====

    void setDeformerName(
        final String identity,
        final Object modelSource,
        final Object model,
        final DeformerRef ref,
        final String name
    ) {
        requireDeformerInspectorAuthorization();
        final DeformerRef value = currentDeformer(identity, modelSource, model, ref);
        final String requested = Objects.requireNonNull(name, "name");
        if (requested.isBlank()) throw new IllegalArgumentException("name must not be blank");
        if (requested.equals(objectName(value.source(), value.id()))) return;
        writeInspector(modelSource, value.source(), "Turboism: Set Deformer Name", () ->
            resolver.invoke(
                "cubism.editor-model.deformer-source.set-local-name",
                value.source(),
                requested
            )
        );
    }

    void setDeformerId(
        final String identity,
        final Object modelSource,
        final Object model,
        final DeformerRef ref,
        final DeformerId id
    ) {
        requireDeformerInspectorAuthorization();
        final DeformerRef value = currentDeformer(identity, modelSource, model, ref);
        final String newId = Objects.requireNonNull(id, "id").value();
        if (newId.equals(value.id())) return;
        if (newId.isEmpty()) throw new IllegalArgumentException("id must not be blank");
        if (!InspectorIdRules.isValidCubismId(newId)) {
            throw new IllegalArgumentException("id violates Cubism ID rules: " + newId);
        }
        if (duplicateObjectId(identity, modelSource, model, newId)) {
            throw new IllegalArgumentException("Cubism object ID is already present: " + newId);
        }
        writeInspector(modelSource, value.source(), "Turboism: Set Deformer ID", () -> {
            final Object hostId = resolver.construct("cubism.editor-model.deformer-id.create", newId);
            resolver.invoke("cubism.editor-model.deformer-source.set-id", value.source(), hostId);
            verifyModel(modelSource);
        });
    }

    void setDeformerTarget(
        final String identity,
        final Object modelSource,
        final Object model,
        final DeformerRef ref,
        final Optional<DeformerId> target
    ) {
        requireDeformerInspectorAuthorization();
        final DeformerRef value = currentDeformer(identity, modelSource, model, ref);
        final Optional<DeformerId> requested = Objects.requireNonNull(target, "target");
        final boolean detach = requested.isEmpty();
        if (!detach && requested.orElseThrow().value().equals(value.id())) {
            throw new IllegalArgumentException("a Deformer cannot target itself");
        }
        final Object targetGuid;
        if (detach) {
            targetGuid = rootDeformerGuid();
        } else {
            final String targetId = requested.orElseThrow().value();
            final Object targetSource = deformerRefs(identity, modelSource, model).stream()
                .filter(candidate -> candidate.id().equals(targetId))
                .map(DeformerRef::source)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                    "Cubism Deformer is absent from the active model: " + targetId
                ));
            if (targetIsDescendantOf(value.source(), targetSource)) {
                throw new IllegalArgumentException(
                    "a Deformer cannot target its own descendant: " + targetId
                );
            }
            targetGuid = resolver.invoke("cubism.editor-model.deformer-source.guid", targetSource);
        }
        final Object currentGuid = resolver.invoke(
            "cubism.editor-model.deformer-source.guid", value.source()
        );
        if (currentGuid == targetGuid) return;
        changeDeformerTarget(modelSource, model, value.source(), targetGuid);
    }

    void setDeformerMultiplyColor(
        final String identity,
        final Object modelSource,
        final Object model,
        final DeformerRef ref,
        final Color color
    ) {
        setDeformerColor(identity, modelSource, model, ref, color, true);
    }

    void setDeformerScreenColor(
        final String identity,
        final Object modelSource,
        final Object model,
        final DeformerRef ref,
        final Color color
    ) {
        setDeformerColor(identity, modelSource, model, ref, color, false);
    }

    void setGlueName(
        final String identity,
        final Object modelSource,
        final Object model,
        final GlueRef ref,
        final String name
    ) {
        requireGlueInspectorAuthorization();
        final GlueRef value = currentGlue(identity, modelSource, model, ref);
        final String requested = Objects.requireNonNull(name, "name");
        if (requested.isEmpty()) throw new IllegalArgumentException("name must not be empty");
        if (requested.equals(glueName(value))) return;
        writeInspector(modelSource, value.source(), "Turboism: Set Glue Name", () -> {
            resolver.invoke("cubism.editor-model.glue-source.set-local-name", value.source(), requested);
            verifyModel(modelSource);
        });
    }

    void setGlueId(
        final String identity,
        final Object modelSource,
        final Object model,
        final GlueRef ref,
        final GlueId id
    ) {
        requireGlueInspectorAuthorization();
        final GlueRef value = currentGlue(identity, modelSource, model, ref);
        final String newId = Objects.requireNonNull(id, "id").value();
        if (newId.equals(value.id())) return;
        if (newId.isEmpty()) throw new IllegalArgumentException("id must not be blank");
        if (!InspectorIdRules.isValidCubismId(newId)) {
            throw new IllegalArgumentException("id violates Cubism ID rules: " + newId);
        }
        if (duplicateObjectId(identity, modelSource, model, newId)) {
            throw new IllegalArgumentException("Cubism object ID is already present: " + newId);
        }
        writeInspector(modelSource, value.source(), "Turboism: Set Glue ID", () -> {
            final Object hostId = resolver.construct("cubism.editor-model.glue-id.create", newId);
            resolver.invoke("cubism.editor-model.glue-source.set-id", value.source(), hostId);
            verifyModel(modelSource);
        });
    }

    void setGlueIntensity(
        final String identity,
        final Object modelSource,
        final Object model,
        final GlueRef ref,
        final float intensity
    ) {
        requireGlueInspectorAuthorization();
        final GlueRef value = currentGlue(identity, modelSource, model, ref);
        if (!Float.isFinite(intensity)) throw new IllegalArgumentException("intensity must be finite");
        if (intensity < 0.0F || intensity > 1.0F) {
            throw new IllegalArgumentException("intensity must be within [0,1]");
        }
        if (Float.compare(glueIntensity(identity, modelSource, model, value), intensity) == 0) return;
        writeInspector(modelSource, value.source(), "Turboism: Set Glue Intensity", () ->
            resolver.invoke(
                "cubism.editor-model.glue-form.set-intensity",
                glueForm(identity, modelSource, model, value),
                Float.valueOf(intensity)
            )
        );
    }

    void setGlueDrawableA(
        final String identity,
        final Object modelSource,
        final Object model,
        final GlueRef ref,
        final ArtMeshId id
    ) {
        setGlueDrawable(identity, modelSource, model, ref, id, true);
    }

    void setGlueDrawableB(
        final String identity,
        final Object modelSource,
        final Object model,
        final GlueRef ref,
        final ArtMeshId id
    ) {
        setGlueDrawable(identity, modelSource, model, ref, id, false);
    }

    float glueIntensity(
        final String identity,
        final Object modelSource,
        final Object model,
        final GlueRef ref
    ) {
        final GlueRef value = currentGlue(identity, modelSource, model, ref);
        return number(
            resolver.invoke(
                "cubism.editor-model.glue-form.intensity",
                glueForm(identity, modelSource, model, value)
            ),
            "Glue intensity"
        );
    }

    String glueName(final GlueRef ref) {
        final Object value = resolver.invoke(
            "cubism.editor-model.glue-source.local-name", ref.source()
        );
        if (value == null) return ref.id();
        if (!(value instanceof String name)) throw unavailable("Editor Glue name is invalid.");
        return name.isBlank() ? ref.id() : name;
    }

    private void setDeformerColor(
        final String identity,
        final Object modelSource,
        final Object model,
        final DeformerRef ref,
        final Color color,
        final boolean multiply
    ) {
        requireDeformerInspectorAuthorization();
        final DeformerRef value = currentDeformer(identity, modelSource, model, ref);
        final Color requested = Objects.requireNonNull(color, "color");
        requireColorChannels(requested);
        requireColorSupportVersion(modelSource);
        final Object form = deformerForm(value.instance());
        final Object hostColor = resolver.invoke(
            multiply
                ? "cubism.editor-model.deformer-form.multiply-color"
                : "cubism.editor-model.deformer-form.screen-color",
            form
        );
        if (hostColor == null) throw unavailable("Editor Deformer color is unavailable.");
        if (sameColor(hostColor, requested)) return;
        writeInspector(
            modelSource,
            value.source(),
            multiply ? "Turboism: Set Deformer Multiply Color" : "Turboism: Set Deformer Screen Color",
            () -> {
                resolver.invoke("cubism.editor-model.float-color.set-red", hostColor, Float.valueOf(requested.red()));
                resolver.invoke("cubism.editor-model.float-color.set-green", hostColor, Float.valueOf(requested.green()));
                resolver.invoke("cubism.editor-model.float-color.set-blue", hostColor, Float.valueOf(requested.blue()));
                resolver.invoke("cubism.editor-model.float-color.set-alpha", hostColor, Float.valueOf(requested.alpha()));
            }
        );
    }

    private void setGlueDrawable(
        final String identity,
        final Object modelSource,
        final Object model,
        final GlueRef ref,
        final ArtMeshId id,
        final boolean targetA
    ) {
        requireGlueInspectorAuthorization();
        final GlueRef value = currentGlue(identity, modelSource, model, ref);
        final ArtMeshId requested = Objects.requireNonNull(id, "id");
        final Object targetSource = artMeshes(identity, modelSource, model).stream()
            .filter(candidate -> candidate.id().equals(requested.value()))
            .map(ObjectRef::source)
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "Cubism ArtMesh is absent from the active model: " + requested.value()
            ));
        final Object current = resolver.invoke(
            targetA
                ? "cubism.editor-model.glue-source.target-art-mesh-a"
                : "cubism.editor-model.glue-source.target-art-mesh-b",
            value.source()
        );
        if (current == targetSource) return;
        final Object targetGuid = resolver.invoke(
            "cubism.editor-model.art-mesh-source.guid", targetSource
        );
        writeInspector(modelSource, value.source(),
            targetA ? "Turboism: Set Glue Drawable A" : "Turboism: Set Glue Drawable B",
            () -> resolver.invoke(
                targetA
                    ? "cubism.editor-model.glue-source.set-target-art-mesh-a"
                    : "cubism.editor-model.glue-source.set-target-art-mesh-b",
                value.source(),
                targetGuid
            )
        );
    }

    private void writeInspector(
        final Object modelSource,
        final Object objectSource,
        final String action,
        final Runnable mutation
    ) {
        final Object app = resolver.invokeStatic("cubism.editor-model.app-controller.instance");
        final Object document = resolver.invoke(
            "cubism.editor-model.app-controller.current-document", app
        );
        final Object editMode = resolver.invoke(
            "cubism.editor-model.modeling-document.edit-mode", document
        );
        final Object edit = resolver.invoke("cubism.editor-model.edit-mode.begin", editMode, action);
        boolean completed = false;
        try {
            final Object handler = resolver.invoke(
                "cubism.editor-model.parameter-controllable-source.handler", objectSource
            );
            if (!resolver.isInstance("cubism.editor-model.parameter-controllable-handler.class", handler)) {
                throw unavailable("Editor object Undo handler is unavailable.");
            }
            final Object objectUndo = resolver.invoke(
                "cubism.editor-model.parameter-controllable-handler.create-undo-for-all-edit",
                handler,
                action
            );
            final Object accepted = resolver.invoke(
                "cubism.editor-model.undo.add", edit, objectUndo, Boolean.TRUE
            );
            if (!(accepted instanceof Boolean value) || !value) {
                throw new IllegalStateException("Cubism rejected the Editor Inspector Undo entry.");
            }
            final Object listener = resolver.createFunctionalProxy(
                "cubism.editor-model.undo-listener.class",
                ignored -> {
                    resolver.invoke("cubism.editor-model.model-source.update-instances", modelSource);
                    refreshBoth(app);
                    return null;
                }
            );
            resolver.invoke("cubism.editor-model.undo.add-listener", objectUndo, listener);
            mutation.run();
            resolver.invoke("cubism.editor-model.model-source.update-instances", modelSource);
            refreshBoth(app);
            resolver.invoke("cubism.editor-model.modeling-document.mark-dirty", document);
            completed = true;
        } finally {
            resolver.invoke(
                "cubism.editor-model.edit-mode.end",
                editMode,
                Boolean.valueOf(!completed),
                null
            );
        }
    }

    private void changeDeformerTarget(
        final Object modelSource,
        final Object model,
        final Object deformerSource,
        final Object targetGuid
    ) {
        final Object app = resolver.invokeStatic("cubism.editor-model.app-controller.instance");
        final Object document = resolver.invoke(
            "cubism.editor-model.app-controller.current-document", app
        );
        final Object editMode = resolver.invoke(
            "cubism.editor-model.modeling-document.edit-mode", document
        );
        final Object edit = resolver.invoke(
            "cubism.editor-model.edit-mode.begin", editMode, "Turboism: Change Deformer Target"
        );
        boolean completed = false;
        try {
            final Object handler = resolver.invoke(
                "cubism.editor-model.parameter-controllable-source.handler", deformerSource
            );
            if (!resolver.isInstance("cubism.editor-model.parameter-controllable-handler.class", handler)) {
                throw unavailable("Editor Deformer Undo handler is unavailable.");
            }
            final Object changeUndo = resolver.invoke(
                "cubism.editor-model.parameter-controllable-handler.change-target-deformer",
                handler,
                model,
                targetGuid,
                Boolean.FALSE
            );
            final Object accepted = resolver.invoke(
                "cubism.editor-model.undo.add", edit, changeUndo, Boolean.TRUE
            );
            if (!(accepted instanceof Boolean value) || !value) {
                throw new IllegalStateException("Cubism rejected the Deformer target Undo entry.");
            }
            final Object listener = resolver.createFunctionalProxy(
                "cubism.editor-model.undo-listener.class",
                ignored -> {
                    resolver.invoke("cubism.editor-model.model-source.update-instances", modelSource);
                    refreshBoth(app);
                    return null;
                }
            );
            resolver.invoke("cubism.editor-model.undo.add-listener", changeUndo, listener);
            resolver.invoke("cubism.editor-model.model-source.update-instances", modelSource);
            refreshBoth(app);
            resolver.invoke("cubism.editor-model.modeling-document.mark-dirty", document);
            completed = true;
        } finally {
            resolver.invoke(
                "cubism.editor-model.edit-mode.end",
                editMode,
                Boolean.valueOf(!completed),
                null
            );
        }
    }

    private void refreshBoth(final Object app) {
        final Object completePack = resolver.invoke(
            "cubism.editor-model.app-controller.complete-pack", app
        );
        resolver.invoke(
            "cubism.editor-model.complete-pack.update-part-palette",
            completePack,
            Boolean.TRUE
        );
        resolver.invoke(
            "cubism.editor-model.complete-pack.update-deformer-palette",
            completePack,
            Boolean.TRUE
        );
        resolver.invoke(
            "cubism.editor-model.complete-pack.repaint-canvas",
            completePack,
            Boolean.TRUE
        );
    }

    private void verifyModel(final Object modelSource) {
        resolver.invokeStatic(
            "cubism.editor-model.model-source.verify",
            modelSource,
            Boolean.TRUE,
            null,
            Integer.valueOf(2),
            null
        );
    }

    private Object glueForm(
        final String identity,
        final Object modelSource,
        final Object model,
        final GlueRef ref
    ) {
        currentGlue(identity, modelSource, model, ref);
        final Object idObject = resolver.invoke(
            "cubism.editor-model.parameter-controllable-source.id", ref.source()
        );
        final Object instance = resolver.invoke(
            "cubism.editor-model.model.get-object", model, idObject
        );
        if (instance == null) throw unavailable("Editor Glue instance is unavailable.");
        final Object form = resolver.invoke(
            "cubism.editor-model.glue.current-keyform", instance
        );
        if (form == null) throw unavailable("Editor Glue current keyform is unavailable.");
        return form;
    }

    private boolean targetIsDescendantOf(final Object ownSource, final Object targetSource) {
        Object cursor = targetSource;
        int guard = 0;
        while (cursor != null) {
            if (cursor == ownSource) return true;
            cursor = resolver.invoke(
                "cubism.editor-model.parameter-controllable-source.target-deformer-source",
                cursor
            );
            if (++guard > 4096) {
                throw new IllegalStateException("Deformer ancestor chain is cyclic.");
            }
        }
        return false;
    }

    private Object rootDeformerGuid() {
        final Object companion = resolver.readStaticField("cubism.editor-model.deformer-guid.companion");
        return resolver.invoke("cubism.editor-model.deformer-guid.root", companion);
    }

    private boolean sameColor(final Object hostColor, final Color requested) {
        return number(resolver.invoke("cubism.editor-model.float-color.red", hostColor), "color red") == requested.red()
            && number(resolver.invoke("cubism.editor-model.float-color.green", hostColor), "color green") == requested.green()
            && number(resolver.invoke("cubism.editor-model.float-color.blue", hostColor), "color blue") == requested.blue()
            && number(resolver.invoke("cubism.editor-model.float-color.alpha", hostColor), "color alpha") == requested.alpha();
    }

    private static void requireColorChannels(final Color color) {
        for (float channel : new float[]{color.red(), color.green(), color.blue(), color.alpha()}) {
            if (channel < 0.0F || channel > 1.0F) {
                throw new IllegalArgumentException("color channels must be within [0,1]");
            }
        }
    }

    private void requireColorSupportVersion(final Object modelSource) {
        final Object version = resolver.invoke(
            "cubism.editor-model.model-source.target-version", modelSource
        );
        final Object number = resolver.invoke("cubism.editor-model.target-version.number", version);
        if (!(number instanceof Integer value) || value < InspectorIdRules.CUBISM_42_TARGET_VERSION) {
            throw new IllegalStateException(
                "Deformer colors require a Cubism 4.2+ model target version (CUB3-3264/3265)."
            );
        }
    }

    private boolean duplicateObjectId(
        final String identity,
        final Object modelSource,
        final Object model,
        final String candidate
    ) {
        currentGuard.requireCurrent(identity, model);
        for (String collectionAlias : new String[]{
            "cubism.editor-model.model-source.parts",
            "cubism.editor-model.model-source.all-deformers",
            "cubism.editor-model.model-source.all-glues",
            "cubism.editor-model.model-source.all-art-meshes"
        }) {
            final List<?> values = list(
                resolver.invoke(collectionAlias, modelSource),
                "Editor object source collection"
            );
            for (Object value : values) {
                final Object idObject = resolver.invoke(
                    "cubism.editor-model.parameter-controllable-source.id", value
                );
                final String idText = text(
                    resolver.invoke("cubism.editor-model.id.value", idObject),
                    "Editor object ID"
                );
                if (idText.equals(candidate)) return true;
            }
        }
        return false;
    }

    private void requireDeformerInspectorAuthorization() {
        if (!resolver.authorizesFeature(
            EditorDeformerInspectorSelectorContract.ADAPTER_SLICE_ID,
            EditorDeformerInspectorSelectorContract.CAPABILITY_ID,
            EditorDeformerInspectorSelectorContract.REQUIRED_ALIASES
        )) {
            throw new UnsupportedOperationException(
                "Editor Deformer Inspector writes require exact verified host evidence."
            );
        }
    }

    private void requireGlueInspectorAuthorization() {
        if (!resolver.authorizesFeature(
            EditorGlueInspectorSelectorContract.ADAPTER_SLICE_ID,
            EditorGlueInspectorSelectorContract.CAPABILITY_ID,
            EditorGlueInspectorSelectorContract.REQUIRED_ALIASES
        )) {
            throw new UnsupportedOperationException(
                "Editor Glue Inspector writes require exact verified host evidence."
            );
        }
    }

    private void refresh(final Object app, final Kind kind) {
        refresh(app, kind, false);
    }

    private void refresh(final Object app, final Kind kind, final boolean inspector) {
        final Object completePack = resolver.invoke(
            "cubism.editor-model.app-controller.complete-pack", app
        );
        resolver.invoke(
            kind == Kind.ART_MESH
                ? "cubism.editor-model.complete-pack.update-part-palette"
                : "cubism.editor-model.complete-pack.update-deformer-palette",
            completePack,
            Boolean.TRUE
        );
        if (inspector) {
            resolver.invoke(
                "cubism.editor-model.complete-pack.update-deformer-palette",
                completePack,
                Boolean.TRUE
            );
        }
        resolver.invoke(
            "cubism.editor-model.complete-pack.repaint-canvas", completePack, Boolean.TRUE
        );
    }

    private void setSourceFlag(
        final Kind kind,
        final Object modelSource,
        final Object objectSource,
        final String readAlias,
        final String writeAlias,
        final boolean value,
        final String action
    ) {
        requireWriteAuthorized(kind);
        if (sourceFlag(readAlias, objectSource, action) == value) return;
        write(kind, modelSource, objectSource, action, () ->
            resolver.invoke(writeAlias, objectSource, Boolean.valueOf(value))
        );
    }

    private void setOpacity(
        final Kind kind,
        final Object modelSource,
        final Object objectSource,
        final Object form,
        final String readAlias,
        final String writeAlias,
        final float value,
        final String action
    ) {
        if (!Float.isFinite(value)) throw new IllegalArgumentException("opacity must be finite");
        requireWriteAuthorized(kind);
        if (Float.compare(number(resolver.invoke(readAlias, form), action), value) == 0) return;
        write(kind, modelSource, objectSource, action, () ->
            resolver.invoke(writeAlias, form, Float.valueOf(value))
        );
    }

    private static final java.util.regex.Pattern ID_FORBIDDEN_START =
        java.util.regex.Pattern.compile("^[0-9]");
    private static final java.util.regex.Pattern ID_ALLOWED =
        java.util.regex.Pattern.compile("^[0-9a-zA-Z_@]+$");
    private static final int ID_MAX_LENGTH = 64;
    private static final int TARGET_VERSION_SDK40 = 400_000;
    private static final int TARGET_VERSION_SDK42 = 4_020_000;

    private int targetVersionNumber(final Object modelSource) {
        final Object version = resolver.invoke(
            "cubism.editor-model.model-source.target-version", modelSource
        );
        return integer(
            resolver.invoke("cubism.editor-model.target-version.number", version),
            "Editor model target version"
        );
    }

    private void setId(
        final Object modelSource,
        final Object objectSource,
        final String id
    ) {
        if (id == null) throw new IllegalArgumentException("id must not be null");
        final String current = objectId(objectSource);
        if (id.equals(current)) return;
        if (id.trim().isEmpty()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (ID_FORBIDDEN_START.matcher(id).find()
            || !ID_ALLOWED.matcher(id).matches()
            || id.length() >= ID_MAX_LENGTH) {
            throw new IllegalArgumentException(
                "id must not start with a digit, must match [0-9a-zA-Z_@]+, and must be shorter than 64 characters"
            );
        }
        final Object modelHandler = resolver.invoke(
            "cubism.editor-model.model-source.handler", modelSource
        );
        final Object idMap = resolver.invoke(
            "cubism.editor-model.model-handler.id-map", modelHandler
        );
        final Object duplicate = resolver.invoke(
            "cubism.editor-model.id-map.contains", idMap, id
        );
        if (duplicate instanceof Boolean used && used) {
            throw new IllegalArgumentException("id is already used by another model object: " + id);
        }
        writeInspector(UndoKind.BASIC_SETTING, modelSource, objectSource, "Set ArtMesh ID", () -> {
            final Object drawableId = resolver.construct(
                "cubism.editor-model.drawable-id.create", id
            );
            resolver.invoke(
                "cubism.editor-model.drawable-source.set-id", objectSource, drawableId
            );
            resolver.invokeStatic(
                "cubism.editor-model.model-source.verify",
                modelSource,
                Boolean.TRUE,
                null,
                Integer.valueOf(2),
                null
            );
            final Object app = resolver.invokeStatic("cubism.editor-model.app-controller.instance");
            final Object completePack = resolver.invoke(
                "cubism.editor-model.app-controller.complete-pack", app
            );
            final Object updateManager = resolver.invoke(
                "cubism.editor-model.complete-pack.update-manager", completePack
            );
            resolver.invoke(
                "cubism.editor-model.update-manager.update-part", updateManager, Boolean.TRUE
            );
            resolver.invoke(
                "cubism.editor-model.update-manager.update-deformer", updateManager, Boolean.TRUE
            );
        });
    }

    private void setTargetDeformer(
        final String identity,
        final Object modelSource,
        final Object model,
        final ObjectRef current,
        final Optional<DeformerId> targetDeformer
    ) {
        requireInspectorWriteAuthorized();
        final Object target;
        if (targetDeformer.isPresent()) {
            final DeformerId targetId = targetDeformer.get();
            final Object source = deformerSourceById(identity, modelSource, model, targetId);
            if (source == null) {
                throw new NoSuchElementException(
                    "No Editor Deformer has id " + targetId.value()
                );
            }
            target = resolver.construct("cubism.editor-model.deformer-id.create", targetId.value());
        } else {
            target = null;
        }
        final Object app = resolver.invokeStatic("cubism.editor-model.app-controller.instance");
        final Object document = resolver.invoke(
            "cubism.editor-model.app-controller.current-document", app
        );
        final Object editMode = resolver.invoke(
            "cubism.editor-model.modeling-document.edit-mode", document
        );
        final Object edit = resolver.invoke(
            "cubism.editor-model.edit-mode.begin", editMode, "Set ArtMesh target Deformer"
        );
        boolean completed = false;
        try {
            final Object handler = resolver.invoke(
                "cubism.editor-model.parameter-controllable-source.handler", current.source()
            );
            if (!resolver.isInstance("cubism.editor-model.parameter-controllable-handler.class", handler)) {
                throw unavailable("Editor object Undo handler is unavailable.");
            }
            final Object changeUndo;
            if (target == null) {
                final Object companion = resolver.readStaticField(
                    "cubism.editor-model.deformer-guid.companion"
                );
                final Object rootGuid = resolver.invoke(
                    "cubism.editor-model.deformer-guid.root", companion
                );
                changeUndo = resolver.invoke(
                    "cubism.editor-model.parameter-controllable-handler.change-target-deformer-guid",
                    handler,
                    model,
                    rootGuid,
                    Boolean.FALSE
                );
            } else {
                changeUndo = resolver.invoke(
                    "cubism.editor-model.parameter-controllable-handler.change-target-deformer",
                    handler,
                    model,
                    target
                );
            }
            final Object accepted = resolver.invoke(
                "cubism.editor-model.undo.add", edit, changeUndo, Boolean.TRUE
            );
            if (!(accepted instanceof Boolean value) || !value) {
                throw new IllegalStateException("Cubism rejected the target Deformer Undo entry.");
            }
            final Object listener = resolver.createFunctionalProxy(
                "cubism.editor-model.undo-listener.class",
                ignored -> {
                    resolver.invoke("cubism.editor-model.model-source.update-instances", modelSource);
                    refresh(app, Kind.ART_MESH, true);
                    return null;
                }
            );
            resolver.invoke("cubism.editor-model.undo.add-listener", changeUndo, listener);
            resolver.invoke("cubism.editor-model.model-source.update-instances", modelSource);
            refresh(app, Kind.ART_MESH, true);
            resolver.invoke("cubism.editor-model.modeling-document.mark-dirty", document);
            completed = true;
        } finally {
            resolver.invoke(
                "cubism.editor-model.edit-mode.end", editMode, Boolean.valueOf(!completed), null
            );
        }
    }

    private Object deformerSourceById(
        final String identity,
        final Object modelSource,
        final Object model,
        final DeformerId id
    ) {
        currentGuard.requireCurrent(identity, model);
        final List<?> sources = list(
            resolver.invoke("cubism.editor-model.model-source.all-deformers", modelSource),
            "Editor Deformer source collection"
        );
        for (Object source : sources) {
            if (id.value().equals(objectId(source))) {
                return source;
            }
        }
        return null;
    }

    private void setClippingMaskIds(
        final String identity,
        final Object modelSource,
        final Object model,
        final ObjectRef current,
        final List<ArtMeshId> maskIds
    ) {
        requireInspectorWriteAuthorized();
        final ArrayList<Object> resolved = new ArrayList<>(maskIds.size());
        for (ArtMeshId maskId : maskIds) {
            if (maskId == null) throw new IllegalArgumentException("mask IDs must not contain null");
            final Object object = resolver.invoke(
                "cubism.editor-model.model-source.get-object", modelSource, maskId.value()
            );
            if (object == null || !resolver.isInstance("cubism.editor-model.drawable-guid.class",
                resolver.invoke("cubism.editor-model.parameter-controllable-source.guid", object))) {
                throw new IllegalArgumentException(
                    "clipping mask ID does not resolve to a Drawable: " + maskId.value()
                );
            }
            resolved.add(resolver.invoke(
                "cubism.editor-model.parameter-controllable-source.guid", object
            ));
        }
        writeInspector(UndoKind.BASIC_SETTING, modelSource, current.source(), "Set ArtMesh clipping masks", () -> {
            final Object clipList = resolver.invoke(
                "cubism.editor-model.art-mesh-source.clip-guid-list", current.source()
            );
            resolver.invoke("cubism.editor-model.id-list.clear", clipList);
            if (!resolved.isEmpty()) {
                resolver.invoke("cubism.editor-model.id-list.add-all", clipList, resolved);
            }
        });
    }

    private void setInvertedMask(
        final Object modelSource,
        final Object objectSource,
        final boolean value
    ) {
        requireInspectorWriteAuthorized();
        if (sourceFlag("cubism.editor-model.art-mesh-source.inverted-mask", objectSource, "ArtMesh inverted-mask state") == value) {
            return;
        }
        if (value && targetVersionNumber(modelSource) < TARGET_VERSION_SDK40) {
            throw new UnsupportedOperationException(
                "Inverted clipping masks require a Cubism 4.0+ model target (CUB3-2528)."
            );
        }
        writeInspector(UndoKind.BASIC_SETTING, modelSource, objectSource, "Set ArtMesh inverted mask", () ->
            resolver.invoke(
                "cubism.editor-model.art-mesh-source.set-invert-clipping-mask",
                objectSource,
                Boolean.valueOf(value)
            )
        );
    }

    private void setDrawOrder(
        final Object modelSource,
        final Object objectSource,
        final Object form,
        final int value
    ) {
        requireInspectorWriteAuthorized();
        final int clamped = Math.max(0, Math.min(1000, value));
        if (integer(resolver.invoke("cubism.editor-model.drawable-form.draw-order", form), "ArtMesh draw order") == clamped) {
            return;
        }
        writeInspector(UndoKind.KEYFORM_EDIT, modelSource, objectSource, "Set ArtMesh draw order", () ->
            resolver.invoke(
                "cubism.editor-model.drawable-form.set-draw-order", form, Integer.valueOf(clamped)
            )
        );
    }

    private void setColor(
        final Object modelSource,
        final Object objectSource,
        final Object form,
        final String colorAlias,
        final Color color,
        final String action
    ) {
        requireInspectorWriteAuthorized();
        if (targetVersionNumber(modelSource) < TARGET_VERSION_SDK42) {
            throw new UnsupportedOperationException(
                action + " requires a Cubism 4.2+ model target (CUB3-3264/CUB3-3265)."
            );
        }
        final Object hostColor = resolver.invoke(colorAlias, form);
        if (hostColor == null) {
            throw unavailable("Editor drawable color is unavailable.");
        }
        if (equalsColor(hostColor, color)) return;
        writeInspector(UndoKind.KEYFORM_EDIT, modelSource, objectSource, action, () -> {
            resolver.invoke(
                "cubism.editor-model.float-color.set-red", hostColor, Float.valueOf(color.red())
            );
            resolver.invoke(
                "cubism.editor-model.float-color.set-green", hostColor, Float.valueOf(color.green())
            );
            resolver.invoke(
                "cubism.editor-model.float-color.set-blue", hostColor, Float.valueOf(color.blue())
            );
            resolver.invoke(
                "cubism.editor-model.float-color.set-alpha", hostColor, Float.valueOf(color.alpha())
            );
        });
    }

    private boolean equalsColor(final Object hostColor, final Color color) {
        final float red = number(resolver.invoke("cubism.editor-model.float-color.red", hostColor), "ArtMesh color red");
        final float green = number(resolver.invoke("cubism.editor-model.float-color.green", hostColor), "ArtMesh color green");
        final float blue = number(resolver.invoke("cubism.editor-model.float-color.blue", hostColor), "ArtMesh color blue");
        final float alpha = number(resolver.invoke("cubism.editor-model.float-color.alpha", hostColor), "ArtMesh color alpha");
        return Float.compare(red, color.red()) == 0
            && Float.compare(green, color.green()) == 0
            && Float.compare(blue, color.blue()) == 0
            && Float.compare(alpha, color.alpha()) == 0;
    }

    private void setColorComposition(
        final Object modelSource,
        final Object objectSource,
        final ColorComposition composition
    ) {
        requireInspectorWriteAuthorized();
        final Object hostValue = hostEnumValue(
            "cubism.editor-model.color-composition.values",
            "Color composition",
            composition.name()
        );
        writeInspector(UndoKind.BASIC_SETTING, modelSource, objectSource, "Set ArtMesh color composition", () ->
            resolver.invoke(
                "cubism.editor-model.art-mesh-source.set-color-composition",
                objectSource,
                hostValue
            )
        );
    }

    private void setAlphaComposition(
        final Object modelSource,
        final Object objectSource,
        final AlphaComposition composition
    ) {
        if (isCubism52()) {
            throw new UnsupportedOperationException(
                "ArtMesh alpha composition is unavailable on Cubism 5.2 hosts (AlphaComposition introduced in 5.3)."
            );
        }
        requireInspectorWriteAuthorized();
        final Object hostValue = hostEnumValue(
            "cubism.editor-model.alpha-composition.values",
            "Alpha composition",
            composition.name()
        );
        writeInspector(UndoKind.BASIC_SETTING, modelSource, objectSource, "Set ArtMesh alpha composition", () ->
            resolver.invoke(
                "cubism.editor-model.art-mesh-source.set-alpha-composition",
                objectSource,
                hostValue
            )
        );
    }

    private Object hostEnumValue(final String valuesAlias, final String label, final String name) {
        final Object values = resolver.invokeStatic(valuesAlias);
        if (!(values instanceof Object[] hostValues)) {
            throw unavailable("Editor " + label + " host values are unavailable.");
        }
        for (Object hostValue : hostValues) {
            if (hostValue != null && name.equals(hostValue.toString())) {
                return hostValue;
            }
        }
        throw new UnsupportedOperationException(
            label + " " + name + " is not supported by this Cubism host."
        );
    }

    private void setCulling(
        final Object modelSource,
        final Object objectSource,
        final Object artMesh,
        final boolean value
    ) {
        requireInspectorWriteAuthorized();
        if (sourceFlag("cubism.editor-model.art-mesh-source.culling", objectSource, "ArtMesh culling state") == value) {
            return;
        }
        writeInspector(UndoKind.BASIC_SETTING, modelSource, objectSource, "Set ArtMesh culling", () -> {
            resolver.invoke(
                "cubism.editor-model.art-mesh-source.set-culling", objectSource, Boolean.valueOf(value)
            );
            resolver.invoke("cubism.editor-model.art-mesh.setup-shader", artMesh, new Object[]{null});
        });
    }

    private void setUserData(
        final Object modelSource,
        final Object objectSource,
        final String userData
    ) {
        requireInspectorWriteAuthorized();
        if (userData == null) throw new IllegalArgumentException("userData must not be null");
        writeInspector(UndoKind.BASIC_SETTING, modelSource, objectSource, "Set ArtMesh user data", () ->
            resolver.invoke(
                "cubism.editor-model.art-mesh-source.set-user-data", objectSource, userData
            )
        );
    }

    private List<ParameterBinding> parameterBindings(
        final Object objectSource,
        final ParameterBindingTarget target
    ) {
        final List<?> hostBindings = hostBindings(objectSource);
        final ArrayList<ParameterBinding> result = new ArrayList<>(hostBindings.size());
        for (Object hostBinding : hostBindings) {
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
            result.add(new ParameterBinding(
                target,
                parameterId,
                ParameterBindingFamily.KEYFORM_GRID,
                points
            ));
        }
        return List.copyOf(result);
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
        ObjectRef current() { return currentArtMesh(identity, modelSource, model, ref); }
    }

    private final class EditorDrawable extends ObjectView implements Drawable, EditorNativeObjectRef {
        EditorDrawable(final String identity, final Object modelSource, final Object model, final ObjectRef ref) {
            super(identity, modelSource, model, ref);
        }

        private dev.turboism.adapter.cubism.core.CoreDrawableDefinition evaluated() {
            final ObjectRef value = current();
            return EditorObjectReadAccess.this.evaluated(identity, value.id());
        }

        @Override public Object nativeSource() { return ref.source(); }
        @Override public ArtMeshId id() { current(); return new ArtMeshId(ref.id()); }
        @Override public int index() { return artMeshIndex(identity, modelSource, model, current().source()); }
        @Override public boolean doubleSided() { return !culling(); }
        @Override public Optional<PartId> parentPartId() { return EditorObjectReadAccess.this.parentPartId(current().source()); }
        @Override public Optional<DeformerId> parentDeformerId() { return EditorObjectReadAccess.this.parentDeformerId(identity, modelSource, model, current().source()); }
        @Override public List<ParameterId> parameterIds() { return EditorObjectReadAccess.this.parameterIds(current().source()); }
        @Override public List<ArtMeshId> maskIds() { return EditorObjectReadAccess.this.maskIds(identity, modelSource, model, current().source()); }
        @Override public String guid() {
            current();
            return guidValue(resolver.invoke("cubism.editor-model.art-mesh-source.guid", current().source()));
        }
        @Override public String name() { return objectName(current().source(), ref.id()); }
        @Override public boolean visible() { return sourceFlag("cubism.editor-model.parameter-controllable-source.visible", current().source(), "ArtMesh visibility"); }
        @Override public void setVisible(final boolean visible) { final ObjectRef value = current(); setSourceFlag(Kind.ART_MESH, modelSource, value.source(), "cubism.editor-model.parameter-controllable-source.visible", "cubism.editor-model.parameter-controllable-source.set-visible", visible, "Set ArtMesh visibility"); }
        @Override public boolean locked() { return sourceFlag("cubism.editor-model.parameter-controllable-source.locked", current().source(), "ArtMesh lock state"); }
        @Override public void setLocked(final boolean locked) { final ObjectRef value = current(); setSourceFlag(Kind.ART_MESH, modelSource, value.source(), "cubism.editor-model.parameter-controllable-source.locked", "cubism.editor-model.parameter-controllable-source.set-locked", locked, "Set ArtMesh lock state"); }
        @Override public boolean visibleInHierarchy() { return sourceFlag("cubism.editor-model.parameter-controllable-source.visible-in-hierarchy", current().source(), "ArtMesh effective visibility"); }
        @Override public boolean lockedInHierarchy() { return sourceFlag("cubism.editor-model.parameter-controllable-source.locked-in-hierarchy", current().source(), "ArtMesh effective lock state"); }
        @Override public float getOpacity() { return number(resolver.invoke("cubism.editor-model.drawable-form.opacity", artMeshForm(current().instance())), "ArtMesh opacity"); }
        @Override public void setOpacity(final float opacity) { final ObjectRef value = current(); EditorObjectReadAccess.this.setOpacity(Kind.ART_MESH, modelSource, value.source(), artMeshForm(value.instance()), "cubism.editor-model.drawable-form.opacity", "cubism.editor-model.drawable-form.set-opacity", opacity, "Set ArtMesh opacity"); }
        @Override public void setId(final String id) { final ObjectRef value = current(); EditorObjectReadAccess.this.setId(modelSource, value.source(), id); }
        @Override public void setTargetDeformer(final Optional<DeformerId> targetDeformer) { final ObjectRef value = current(); EditorObjectReadAccess.this.setTargetDeformer(identity, modelSource, model, value, targetDeformer); }
        @Override public void setClippingMaskIds(final List<ArtMeshId> maskIds) { final ObjectRef value = current(); EditorObjectReadAccess.this.setClippingMaskIds(identity, modelSource, model, value, maskIds); }
        @Override public void setInvertedMask(final boolean inverted) { final ObjectRef value = current(); EditorObjectReadAccess.this.setInvertedMask(modelSource, value.source(), inverted); }
        @Override public void setDrawOrder(final int drawOrder) { final ObjectRef value = current(); EditorObjectReadAccess.this.setDrawOrder(modelSource, value.source(), artMeshForm(value.instance()), drawOrder); }
        @Override public void setMultiplyColor(final Color color) { final ObjectRef value = current(); EditorObjectReadAccess.this.setColor(modelSource, value.source(), artMeshForm(value.instance()), "cubism.editor-model.drawable-form.multiply-color", color, "Set ArtMesh multiply color"); }
        @Override public void setScreenColor(final Color color) { final ObjectRef value = current(); EditorObjectReadAccess.this.setColor(modelSource, value.source(), artMeshForm(value.instance()), "cubism.editor-model.drawable-form.screen-color", color, "Set ArtMesh screen color"); }
        @Override public void setColorComposition(final ColorComposition composition) { final ObjectRef value = current(); EditorObjectReadAccess.this.setColorComposition(modelSource, value.source(), composition); }
        @Override public void setAlphaComposition(final AlphaComposition composition) { final ObjectRef value = current(); EditorObjectReadAccess.this.setAlphaComposition(modelSource, value.source(), composition); }
        @Override public void setCulling(final boolean culling) { final ObjectRef value = current(); EditorObjectReadAccess.this.setCulling(modelSource, value.source(), value.instance(), culling); }
        @Override public void setUserData(final String userData) { final ObjectRef value = current(); EditorObjectReadAccess.this.setUserData(modelSource, value.source(), userData); }
        @Override public int drawOrder() { return integer(resolver.invoke("cubism.editor-model.drawable-form.draw-order", artMeshForm(current().instance())), "ArtMesh draw order"); }
        @Override public ArtMeshGeometry geometry() { final ObjectRef value = current(); return EditorObjectReadAccess.this.geometry(value.source(), value.instance()); }
        @Override public void replaceGeometry(final ArtMeshGeometry geometry) { final ObjectRef value = current(); replaceArtMeshGeometry(modelSource, value, geometry); }
        @Override public boolean invertedMask() { return sourceFlag("cubism.editor-model.art-mesh-source.inverted-mask", current().source(), "ArtMesh inverted-mask state"); }
        @Override public boolean culling() { return sourceFlag("cubism.editor-model.art-mesh-source.culling", current().source(), "ArtMesh culling state"); }
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
        @Override public IntSequence masks() { final List<ArtMeshId> ids = maskIds(); return intSequence(artMeshIndices(identity, modelSource, model, ids)); }
        @Override public Color multiplyColor() { return evaluated().multiplyColor(); }
        @Override public Color screenColor() { return evaluated().screenColor(); }
        @Override public int parentPartIndex() { return EditorObjectReadAccess.this.parentPartIndex(modelSource, current().source()); }
        @Override public int parentDeformerIndex() { return EditorObjectReadAccess.this.parentDeformerIndex(identity, modelSource, model, current().source()); }
        @Override public IntSequence parameters() { final List<ParameterId> ids = parameterIds(); return intSequence(parameterIndices(model, ids)); }
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
            requireCurrentPartSource(modelSource, parentSource);
            requireHierarchyEditAccess();
            hierarchyEditAccess.setParent(
                identity, modelSource, model, value.source(), parentSource, false, index, "Drawable"
            );
            current();
        }

        @Override public void setParent(final Deformer parent, final int index) {
            final ObjectRef value = current();
            final Object parentSource = nativeSourceOf(parent, "Deformer parent");
            requireCurrentDeformerSource(identity, modelSource, model, parentSource);
            requireHierarchyEditAccess();
            hierarchyEditAccess.setParent(
                identity, modelSource, model, value.source(), parentSource, true, index, "Drawable"
            );
            current();
        }

        @Override public List<ParameterBinding> getParameterBindings() {
            final ObjectRef value = current();
            return parameterBindings(value.source(), ParameterBindingTarget.artMesh(new ArtMeshId(value.id())));
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
        DeformerRef current() { return currentDeformer(identity, modelSource, model, ref); }

        @Override public Object nativeSource() { return ref.source(); }
        @Override public DeformerId id() { current(); return new DeformerId(ref.id()); }
        @Override public int index() { return deformerIndex(identity, modelSource, model, current().source()); }
        @Override public Optional<PartId> parentPartId() { return EditorObjectReadAccess.this.parentPartId(current().source()); }
        @Override public Optional<DeformerId> parentDeformerId() { return EditorObjectReadAccess.this.parentDeformerId(identity, modelSource, model, current().source()); }
        @Override public List<ParameterId> parameterIds() { return EditorObjectReadAccess.this.parameterIds(current().source()); }
        @Override public String name() { return objectName(current().source(), ref.id()); }
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
                EditorObjectReadAccess.this.setDeformerName(identity, modelSource, model, value, name);
            } else {
                requireHierarchyEditAccess();
                hierarchyEditAccess.setName(
                    identity, modelSource, model, value.source(), name, "Deformer"
                );
            }
        }
        @Override public void setId(final DeformerId id) {
            final DeformerRef value = current();
            EditorObjectReadAccess.this.setDeformerId(identity, modelSource, model, value, id);
        }
        @Override public void setTargetDeformer(final Optional<DeformerId> target) {
            final DeformerRef value = current();
            EditorObjectReadAccess.this.setDeformerTarget(identity, modelSource, model, value, target);
        }
        @Override public void setMultiplyColor(final Color color) {
            final DeformerRef value = current();
            EditorObjectReadAccess.this.setDeformerMultiplyColor(identity, modelSource, model, value, color);
        }
        @Override public void setScreenColor(final Color color) {
            final DeformerRef value = current();
            EditorObjectReadAccess.this.setDeformerScreenColor(identity, modelSource, model, value, color);
        }
        @Override public boolean visible() { return sourceFlag("cubism.editor-model.parameter-controllable-source.visible", current().source(), "Deformer visibility"); }
        @Override public void setVisible(final boolean visible) { final DeformerRef value = current(); setSourceFlag(value.kind(), modelSource, value.source(), "cubism.editor-model.parameter-controllable-source.visible", "cubism.editor-model.parameter-controllable-source.set-visible", visible, "Set Deformer visibility"); }
        @Override public boolean locked() { return sourceFlag("cubism.editor-model.parameter-controllable-source.locked", current().source(), "Deformer lock state"); }
        @Override public void setLocked(final boolean locked) { final DeformerRef value = current(); setSourceFlag(value.kind(), modelSource, value.source(), "cubism.editor-model.parameter-controllable-source.locked", "cubism.editor-model.parameter-controllable-source.set-locked", locked, "Set Deformer lock state"); }
        @Override public boolean visibleInHierarchy() { return sourceFlag("cubism.editor-model.parameter-controllable-source.visible-in-hierarchy", current().source(), "Deformer effective visibility"); }
        @Override public boolean lockedInHierarchy() { return sourceFlag("cubism.editor-model.parameter-controllable-source.locked-in-hierarchy", current().source(), "Deformer effective lock state"); }
        @Override public float getOpacity() { return number(resolver.invoke("cubism.editor-model.deformer-form.opacity", deformerForm(current().instance())), "Deformer opacity"); }
        @Override public void setOpacity(final float opacity) { final DeformerRef value = current(); EditorObjectReadAccess.this.setOpacity(value.kind(), modelSource, value.source(), deformerForm(value.instance()), "cubism.editor-model.deformer-form.opacity", "cubism.editor-model.deformer-form.set-opacity", opacity, "Set Deformer opacity"); }
        @Override public Color multiplyColor() { throw unsupported("Deformer multiply color (Cubism Core exposes drawable-level colors only)"); }
        @Override public Color screenColor() { throw unsupported("Deformer screen color (Cubism Core exposes drawable-level colors only)"); }
        @Override public int parentPartIndex() { return EditorObjectReadAccess.this.parentPartIndex(modelSource, current().source()); }
        @Override public int parentDeformerIndex() { return EditorObjectReadAccess.this.parentDeformerIndex(identity, modelSource, model, current().source()); }
        @Override public IntSequence parameters() { final List<ParameterId> ids = parameterIds(); return intSequence(parameterIndices(model, ids)); }

        @Override public void setParent(final Part parent, final int index) {
            final DeformerRef value = current();
            final Object parentSource = nativeSourceOf(parent, "Part parent");
            requireCurrentPartSource(modelSource, parentSource);
            hierarchyEditAccess.setParent(
                identity, modelSource, model, value.source(), parentSource, false, index, "Deformer"
            );
            current();
        }

        @Override public void setParent(final Deformer parent, final int index) {
            final DeformerRef value = current();
            final Object parentSource = nativeSourceOf(parent, "Deformer parent");
            requireCurrentDeformerSource(identity, modelSource, model, parentSource);
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
            return parameterBindings(value.source(), target);
        }
    }

    private final class EditorWarp extends DeformerView implements WarpDeformer {
        EditorWarp(final String identity, final Object modelSource, final Object model, final DeformerRef ref) { super(identity, modelSource, model, ref); }
        @Override public WarpGrid grid() { final DeformerRef value = current(); return warpGrid(value.source(), value.instance()); }
        @Override public void replaceGrid(final WarpGrid grid) { final DeformerRef value = current(); replaceWarpGrid(modelSource, value, grid); }
    }

    private final class EditorRotation extends DeformerView implements RotationDeformer {
        EditorRotation(final String identity, final Object modelSource, final Object model, final DeformerRef ref) { super(identity, modelSource, model, ref); }
        @Override public float baseAngle() { return number(resolver.invoke("cubism.editor-model.rotation-source.base-angle", current().source()), "Rotation base angle"); }
        @Override public void setBaseAngle(final float angle) {
            if (!Float.isFinite(angle)) throw new IllegalArgumentException("angle must be finite");
            final DeformerRef value = current();
            requireWriteAuthorized(Kind.ROTATION);
            if (Float.compare(baseAngle(), angle) == 0) return;
            write(Kind.ROTATION, modelSource, value.source(), "Set Rotation base angle", () ->
                resolver.invoke("cubism.editor-model.rotation-source.set-base-angle", value.source(), Float.valueOf(angle))
            );
        }
        @Override public RotationDeformerForm form() { return rotationForm(current().instance()); }
        @Override public void replaceForm(final RotationDeformerForm form) { final DeformerRef value = current(); replaceRotationForm(modelSource, value, form); }
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
        GlueRef current() { return currentGlue(identity, modelSource, model, ref); }
        ObjectRef target(final String alias) { return glueTarget(identity, modelSource, model, current(), alias); }
        @Override public GlueId id() { current(); return new GlueId(ref.id()); }
        @Override public String name() { return glueName(current()); }
        @Override public void setName(final String name) {
            final GlueRef value = current();
            EditorObjectReadAccess.this.setGlueName(identity, modelSource, model, value, name);
        }
        @Override public void setId(final GlueId id) {
            final GlueRef value = current();
            EditorObjectReadAccess.this.setGlueId(identity, modelSource, model, value, id);
        }
        @Override public float intensity() {
            return glueIntensity(identity, modelSource, model, current());
        }
        @Override public void setIntensity(final float intensity) {
            final GlueRef value = current();
            EditorObjectReadAccess.this.setGlueIntensity(identity, modelSource, model, value, intensity);
        }
        @Override public void setDrawableA(final ArtMeshId id) {
            final GlueRef value = current();
            EditorObjectReadAccess.this.setGlueDrawableA(identity, modelSource, model, value, id);
        }
        @Override public void setDrawableB(final ArtMeshId id) {
            final GlueRef value = current();
            EditorObjectReadAccess.this.setGlueDrawableB(identity, modelSource, model, value, id);
        }
        @Override public int index() {
            final GlueRef value = current();
            final List<GlueRef> values = glueRefs(identity, modelSource, model);
            for (int index = 0; index < values.size(); index++) {
                if (values.get(index).source() == value.source()) return index;
            }
            throw unavailable("Editor Glue is outside the active model.");
        }
        @Override public ArtMeshId drawableAId() { return new ArtMeshId(target("cubism.editor-model.glue-source.target-art-mesh-a").id()); }
        @Override public ArtMeshId drawableBId() { return new ArtMeshId(target("cubism.editor-model.glue-source.target-art-mesh-b").id()); }
        @Override public List<ParameterId> parameterIds() { return EditorObjectReadAccess.this.parameterIds(current().source()); }
        @Override public int drawableA() { return artMeshIndex(identity, modelSource, model, target("cubism.editor-model.glue-source.target-art-mesh-a").source()); }
        @Override public int drawableB() { return artMeshIndex(identity, modelSource, model, target("cubism.editor-model.glue-source.target-art-mesh-b").source()); }
        @Override public IntSequence parameters() { return intSequence(parameterIndices(model, parameterIds())); }
    }

    private final class EditorDrawables implements Drawables {
        final String identity; final Object source; final Object model;
        EditorDrawables(final String identity, final Object source, final Object model) { this.identity = identity; this.source = source; this.model = model; }
        @Override public List<Drawable> all() { return artMeshes(identity, source, model).stream().map(ref -> (Drawable) new EditorDrawable(identity, source, model, ref)).toList(); }
        @Override public Drawable find(final ArtMeshId id) { Objects.requireNonNull(id, "id"); return artMeshes(identity, source, model).stream().filter(ref -> ref.id().equals(id.value())).findFirst().map(ref -> (Drawable) new EditorDrawable(identity, source, model, ref)).orElseThrow(() -> new NoSuchElementException("Cubism ArtMesh is absent: " + id.value())); }

        @Override public Drawable create(
            final String name,
            final Part parent,
            final int index,
            final ArtMeshGeometry geometry
        ) {
            final Object parentSource = nativeSourceOf(parent, "Part parent");
            requireCurrentPartSource(source, parentSource);
            final Object created = hierarchyEditAccess.createArtMeshSource(
                identity,
                source,
                model,
                name,
                parentSource,
                index,
                geometry
            );
            return artMeshes(identity, source, model).stream()
                .filter(ref -> ref.source() == created)
                .findFirst()
                .map(ref -> (Drawable) new EditorDrawable(identity, source, model, ref))
                .orElseThrow(() -> unavailable(
                    "Created ArtMesh is absent after the Editor instance update."
                ));
        }

        @Override public void remove(final Drawable drawable) {
            Objects.requireNonNull(drawable, "drawable");
            final Object nodeSource = nativeSourceOf(drawable, "Drawable");
            requireCurrentArtMeshSource(identity, source, model, nodeSource);
            hierarchyEditAccess.remove(identity, source, model, nodeSource, "Drawable");
        }
    }

    private final class EditorDeformers implements Deformers {
        final String identity; final Object source; final Object model;
        EditorDeformers(final String identity, final Object source, final Object model) { this.identity = identity; this.source = source; this.model = model; }
        @Override public List<Deformer> all() { return deformerRefs(identity, source, model).stream().map(this::view).toList(); }
        @Override public Deformer find(final DeformerId id) { Objects.requireNonNull(id, "id"); return deformerRefs(identity, source, model).stream().filter(ref -> ref.id().equals(id.value())).findFirst().map(this::view).orElseThrow(() -> new NoSuchElementException("Cubism Deformer is absent: " + id.value())); }
        private Deformer view(final DeformerRef ref) { return ref.kind() == Kind.WARP ? new EditorWarp(identity, source, model, ref) : new EditorRotation(identity, source, model, ref); }

        @Override public WarpDeformer createWarp(
            final String name, final Part parent, final int index, final int rows, final int columns
        ) {
            final Object parentSource = nativeSourceOf(parent, "Part parent");
            requireCurrentPartSource(source, parentSource);
            final Object created = hierarchyEditAccess.createWarpSource(
                identity, source, model, name, parentSource, index, rows, columns
            );
            return deformerRefs(identity, source, model).stream()
                .filter(ref -> ref.source() == created)
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
            requireCurrentPartSource(source, parentSource);
            final Object created = hierarchyEditAccess.createRotationSource(
                identity, source, model, name, parentSource, index
            );
            return deformerRefs(identity, source, model).stream()
                .filter(ref -> ref.source() == created)
                .findFirst()
                .map(ref -> (RotationDeformer) new EditorRotation(identity, source, model, ref))
                .orElseThrow(() -> unavailable(
                    "Created Rotation Deformer is absent after the Editor instance update."
                ));
        }

        @Override public void remove(final Deformer deformer) {
            Objects.requireNonNull(deformer, "deformer");
            final Object nodeSource = nativeSourceOf(deformer, "Deformer");
            requireCurrentDeformerSource(identity, source, model, nodeSource);
            hierarchyEditAccess.remove(identity, source, model, nodeSource, "Deformer");
        }
    }

    private final class EditorWarpDeformers implements WarpDeformers {
        final String identity; final Object source; final Object model;
        EditorWarpDeformers(final String identity, final Object source, final Object model) { this.identity = identity; this.source = source; this.model = model; }
        @Override public List<WarpDeformer> all() { return deformerRefs(identity, source, model).stream().filter(ref -> ref.kind() == Kind.WARP).map(ref -> (WarpDeformer) new EditorWarp(identity, source, model, ref)).toList(); }
        @Override public WarpDeformer find(final DeformerId id) { Objects.requireNonNull(id, "id"); return deformerRefs(identity, source, model).stream().filter(ref -> ref.kind() == Kind.WARP && ref.id().equals(id.value())).findFirst().map(ref -> (WarpDeformer) new EditorWarp(identity, source, model, ref)).orElseThrow(() -> new NoSuchElementException("Cubism Warp Deformer is absent: " + id.value())); }
    }

    private final class EditorRotationDeformers implements RotationDeformers {
        final String identity; final Object source; final Object model;
        EditorRotationDeformers(final String identity, final Object source, final Object model) { this.identity = identity; this.source = source; this.model = model; }
        @Override public List<RotationDeformer> all() { return deformerRefs(identity, source, model).stream().filter(ref -> ref.kind() == Kind.ROTATION).map(ref -> (RotationDeformer) new EditorRotation(identity, source, model, ref)).toList(); }
        @Override public RotationDeformer find(final DeformerId id) { Objects.requireNonNull(id, "id"); return deformerRefs(identity, source, model).stream().filter(ref -> ref.kind() == Kind.ROTATION && ref.id().equals(id.value())).findFirst().map(ref -> (RotationDeformer) new EditorRotation(identity, source, model, ref)).orElseThrow(() -> new NoSuchElementException("Cubism Rotation Deformer is absent: " + id.value())); }
    }

    private final class EditorGlues implements Glues {
        final String identity; final Object source; final Object model;
        EditorGlues(final String identity, final Object source, final Object model) { this.identity = identity; this.source = source; this.model = model; }
        @Override public List<Glue> all() { return glueRefs(identity, source, model).stream().map(ref -> (Glue) new EditorGlue(identity, source, model, ref)).toList(); }
        @Override public Glue find(final GlueId id) { Objects.requireNonNull(id, "id"); return glueRefs(identity, source, model).stream().filter(ref -> ref.id().equals(id.value())).findFirst().map(ref -> (Glue) new EditorGlue(identity, source, model, ref)).orElseThrow(() -> new NoSuchElementException("Cubism Glue is absent: " + id.value())); }
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

    private void requireCurrentPartSource(final Object modelSource, final Object partSource) {
        if (partSource == null) return;
        final List<?> parts = list(
            resolver.invoke("cubism.editor-model.model-source.parts", modelSource),
            "Editor Part source collection"
        );
        for (Object candidate : parts) {
            if (candidate == partSource) return;
        }
        throw stale("Part", objectId(partSource));
    }

    private void requireCurrentDeformerSource(
        final String identity, final Object modelSource, final Object model, final Object deformerSource
    ) {
        if (deformerSource == null) return;
        deformerRefs(identity, modelSource, model).stream()
            .filter(value -> value.source() == deformerSource)
            .findFirst()
            .orElseThrow(() -> stale("Deformer", objectId(deformerSource)));
    }


    private void requireHierarchyEditAccess() {
        if (hierarchyEditAccess == null) {
            throw new UnsupportedOperationException(
                "Object-hierarchy editing is unavailable without the verified Editor hierarchy access."
            );
        }
    }

    private void requireCurrentArtMeshSource(
        final String identity, final Object modelSource, final Object model, final Object artMeshSource
    ) {
        artMeshes(identity, modelSource, model).stream()
            .filter(value -> value.source() == artMeshSource)
            .findFirst()
            .orElseThrow(() -> stale("ArtMesh", objectId(artMeshSource)));
    }

    private static List<?> list(final Object value, final String label) {
        if (!(value instanceof List<?> list)) throw unavailable(label + " is unavailable.");
        return List.copyOf(list);
    }
    private static List<?> iterable(final Object value, final String label) {
        if (!(value instanceof Iterable<?> iterable)) throw unavailable(label + " is unavailable.");
        final ArrayList<Object> copy = new ArrayList<>();
        iterable.forEach(copy::add);
        return List.copyOf(copy);
    }
    private static boolean containsIdentity(final List<?> values, final Object expected) { for (Object value : values) if (value == expected) return true; return false; }
    private static String text(final Object value, final String label) { final String result = string(value, label); if (result.isBlank()) throw unavailable(label + " is blank."); return result; }
    private static String string(final Object value, final String label) { if (!(value instanceof String result)) throw unavailable(label + " is invalid."); return result; }
    private static boolean flag(final Object value, final String label) { if (!(value instanceof Boolean result)) throw unavailable(label + " is invalid."); return result; }
    private static int integer(final Object value, final String label) { if (!(value instanceof Integer result)) throw unavailable(label + " is invalid."); return result; }
    private static float number(final Object value, final String label) { if (!(value instanceof Float result) || !Float.isFinite(result)) throw unavailable(label + " is invalid."); return result; }
    private static float[] floats(final Object value, final String label) { if (!(value instanceof float[] values)) throw unavailable(label + " is invalid."); final float[] copy = values.clone(); for (float item : copy) if (!Float.isFinite(item)) throw unavailable(label + " contains a non-finite value."); return copy; }
    private static int[] ints(final Object value, final String label) { if (!(value instanceof int[] values)) throw unavailable(label + " is invalid."); return values.clone(); }
    private static List<Point2> points(final float[] values, final String label) { if (values.length % 2 != 0) throw unavailable(label + " does not contain XY pairs."); final ArrayList<Point2> points = new ArrayList<>(values.length / 2); for (int index = 0; index < values.length; index += 2) points.add(new Point2(values[index], values[index + 1])); return List.copyOf(points); }
    private static List<Integer> boxed(final int[] values) { return java.util.Arrays.stream(values).boxed().toList(); }
    private static List<Float> flatten(final List<Point2> points) { final ArrayList<Float> values = new ArrayList<>(points.size() * 2); for (Point2 point : points) { values.add(point.x()); values.add(point.y()); } return List.copyOf(values); }
    private static FloatSequence floatSequence(final List<Float> values) { final float[] copy = new float[values.size()]; for (int index = 0; index < copy.length; index++) copy[index] = values.get(index); return new FloatSequence() { @Override public int size() { return copy.length; } @Override public float get(final int index) { return copy[index]; } }; }
    private static IntSequence intSequence(final List<Integer> values) { final int[] copy = values.stream().mapToInt(Integer::intValue).toArray(); return new IntSequence() { @Override public int size() { return copy.length; } @Override public int get(final int index) { return copy[index]; } }; }
    private static UnsupportedOperationException unsupported(final String feature) { return new UnsupportedOperationException(feature + " is unavailable without verified Editor semantics."); }
    private static IllegalStateException unavailable(final String message) { return new IllegalStateException(message); }
    private static IllegalStateException stale(final String kind, final String id) { return new IllegalStateException(kind + " reference is stale: " + id); }

    private dev.turboism.adapter.cubism.core.CoreDrawableDefinition evaluated(
        final String identity,
        final String id
    ) {
        if (evaluatedJoin == null) {
            throw new IllegalStateException(
                "Core evaluated data is unavailable: no Core evaluated join is installed."
            );
        }
        try {
            return evaluatedJoin.evaluated(identity).drawable(id);
        } catch (IllegalStateException unavailable) {
            if (lazyPublish == null
                || !unavailable.getMessage().contains("No verified active Core model")) {
                throw unavailable;
            }
            if (!lazyPublish.apply(identity)) {
                throw unavailable;
            }
            // Retried once after a successful lazy publish; any further failure propagates.
            return evaluatedJoin.evaluated(identity).drawable(id);
        }
    }

    private enum Kind {
        ART_MESH("ArtMesh"), WARP("Warp Deformer"), ROTATION("Rotation Deformer");
        final String label;
        Kind(final String label) { this.label = label; }
    }
    private record ObjectRef(String id, Object source, Object instance) { }
    private record DeformerRef(String id, Object source, Object instance, Kind kind) { }
    private record GlueRef(String id, Object source) { }
}

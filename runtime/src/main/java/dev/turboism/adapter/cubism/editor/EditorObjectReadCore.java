package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.selector.EditorClipMaskReadSelectorContract;
import dev.turboism.mapping.verification.selector.EditorObjectReadSelectorContract;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.FloatSequence;
import dev.turboism.sdk.cubism.model.IntSequence;
import dev.turboism.sdk.cubism.model.PartId;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.cubism.model.RotationDeformerForm;
import dev.turboism.sdk.cubism.model.WarpGrid;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Exact-version Editor read projection for ArtMesh, Deformer, and Glue object state. */
final class EditorObjectReadCore {

    private final VerifiedMemberResolver resolver;
    private final EditorObjectReadAccess.CurrentGuard currentGuard;
    private final dev.turboism.adapter.cubism.core.CoreEvaluatedJoin evaluatedJoin;
    private final java.util.function.Function<String, Boolean> lazyPublish;

    EditorObjectReadCore(
        final VerifiedMemberResolver resolver,
        final EditorObjectReadAccess.CurrentGuard currentGuard,
        final dev.turboism.adapter.cubism.core.CoreEvaluatedJoin evaluatedJoin,
        final java.util.function.Function<String, Boolean> lazyPublish
    ) {
        this.resolver = resolver;
        this.currentGuard = currentGuard;
        this.evaluatedJoin = evaluatedJoin;
        this.lazyPublish = lazyPublish;
    }

    void requireAuthorized() {
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

    void requireClipMaskReadAuthorized() {
        if (!resolver.authorizesFeature(
            EditorObjectReadSelectorContract.ADAPTER_SLICE_ID,
            EditorObjectReadSelectorContract.CAPABILITY_ID,
            EditorObjectReadSelectorContract.REQUIRED_ALIASES
        ) || !resolver.authorizesFeature(
            EditorClipMaskReadSelectorContract.ADAPTER_SLICE_ID,
            EditorClipMaskReadSelectorContract.CAPABILITY_ID,
            EditorClipMaskReadSelectorContract.REQUIRED_ALIASES
        )) {
            throw new UnsupportedOperationException(
                "Editor ArtMesh clip-mask reads require independent exact verified host evidence."
            );
        }
    }

    List<ObjectRef> artMeshes(final String identity, final Object source, final Object model) {
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

    List<DeformerRef> deformerRefs(final String identity, final Object source, final Object model) {
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

    List<GlueRef> glueRefs(final String identity, final Object source, final Object model) {
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

    ObjectRef currentArtMesh(
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

    DeformerRef currentDeformer(
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

    GlueRef currentGlue(
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

    Optional<PartId> parentPartId(final Object objectSource) {
        final Object parent = parentPartSource(objectSource);
        return parent == null ? Optional.empty() : Optional.of(partId(parent));
    }

    int parentPartIndex(final Object modelSource, final Object objectSource) {
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

    Optional<DeformerId> parentDeformerId(
        final String identity,
        final Object modelSource,
        final Object model,
        final Object objectSource
    ) {
        final Object target = targetDeformerSource(objectSource);
        if (target == null) return Optional.empty();
        return Optional.of(new DeformerId(deformerRef(identity, modelSource, model, target).id()));
    }

    int parentDeformerIndex(
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

    List<ArtMeshId> maskIds(
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

    String guidValue(final Object guid) {
        return text(resolver.invoke("cubism.editor-model.guid.value", guid), "Editor GUID");
    }

    List<Integer> parameterIndices(final Object model, final List<ParameterId> ids) {
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

    int artMeshIndex(
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

    int deformerIndex(
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

    List<Integer> artMeshIndices(
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

    ObjectRef glueTarget(
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

    String objectId(final Object source) {
        final Object id = resolver.invoke("cubism.editor-model.parameter-controllable-source.id", source);
        return text(resolver.invoke("cubism.editor-model.id.value", id), "Editor object ID");
    }

    String objectName(final Object source, final String id) {
        final Object value = resolver.invoke(
            "cubism.editor-model.parameter-controllable-source.local-name",
            source
        );
        if (value == null) return id;
        if (!(value instanceof String name)) throw unavailable("Editor object name is invalid.");
        return name.isBlank() ? id : name;
    }

    boolean sourceFlag(final String alias, final Object source, final String label) {
        return flag(resolver.invoke(alias, source), label);
    }

    Object artMeshForm(final Object instance) {
        final Object form = resolver.invoke("cubism.editor-model.art-mesh.current-keyform", instance);
        if (form == null) throw unavailable("Editor ArtMesh current keyform is unavailable.");
        return form;
    }

    Object deformerForm(final Object instance) {
        final Object form = resolver.invoke("cubism.editor-model.deformer.current-keyform", instance);
        if (form == null) throw unavailable("Editor Deformer current keyform is unavailable.");
        return form;
    }

    ArtMeshGeometry geometry(final Object source, final Object instance) {
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
        final List<Point2> positionPoints = points(positions, "ArtMesh positions");
        final List<Point2> uvPoints = points(uvs, "ArtMesh UVs");
        if (positionPoints.size() != uvPoints.size()) {
            throw unavailable("Editor ArtMesh positions and UVs have different vertex counts.");
        }
        validateTriangleIndices(indices, positionPoints.size(), "Editor ArtMesh indices");
        return new ArtMeshGeometry(positionPoints, uvPoints, boxed(indices));
    }

    WarpGrid warpGrid(final Object source, final Object instance) {
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

    RotationDeformerForm rotationForm(final Object instance) {
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

    static float[] flattenArray(final List<Point2> points) {
        final float[] values = new float[points.size() * 2];
        for (int index = 0; index < points.size(); index++) {
            values[index * 2] = points.get(index).x();
            values[index * 2 + 1] = points.get(index).y();
        }
        return values;
    }

    static int[] indexArray(final List<Integer> indices) {
        return indices.stream().mapToInt(Integer::intValue).toArray();
    }

    Object deformerSourceById(
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

    void requireCurrentPartSource(final Object modelSource, final Object partSource) {
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

    void requireCurrentDeformerSource(
        final String identity, final Object modelSource, final Object model, final Object deformerSource
    ) {
        if (deformerSource == null) return;
        deformerRefs(identity, modelSource, model).stream()
            .filter(value -> value.source() == deformerSource)
            .findFirst()
            .orElseThrow(() -> stale("Deformer", objectId(deformerSource)));
    }

    void requireCurrentArtMeshSource(
        final String identity, final Object modelSource, final Object model, final Object artMeshSource
    ) {
        artMeshes(identity, modelSource, model).stream()
            .filter(value -> value.source() == artMeshSource)
            .findFirst()
            .orElseThrow(() -> stale("ArtMesh", objectId(artMeshSource)));
    }

    static List<?> list(final Object value, final String label) {
        if (!(value instanceof List<?> list)) throw unavailable(label + " is unavailable.");
        return List.copyOf(list);
    }

    static List<?> iterable(final Object value, final String label) {
        if (!(value instanceof Iterable<?> iterable)) throw unavailable(label + " is unavailable.");
        final ArrayList<Object> copy = new ArrayList<>();
        iterable.forEach(copy::add);
        return List.copyOf(copy);
    }

    static boolean containsIdentity(final List<?> values, final Object expected) { for (Object value : values) if (value == expected) return true; return false; }

    static String text(final Object value, final String label) { final String result = string(value, label); if (result.isBlank()) throw unavailable(label + " is blank."); return result; }

    static String string(final Object value, final String label) { if (!(value instanceof String result)) throw unavailable(label + " is invalid."); return result; }

    static boolean flag(final Object value, final String label) { if (!(value instanceof Boolean result)) throw unavailable(label + " is invalid."); return result; }

    static int integer(final Object value, final String label) { if (!(value instanceof Integer result)) throw unavailable(label + " is invalid."); return result; }

    static float number(final Object value, final String label) { if (!(value instanceof Float result) || !Float.isFinite(result)) throw unavailable(label + " is invalid."); return result; }

    static float[] floats(final Object value, final String label) { if (!(value instanceof float[] values)) throw unavailable(label + " is invalid."); final float[] copy = values.clone(); for (float item : copy) if (!Float.isFinite(item)) throw unavailable(label + " contains a non-finite value."); return copy; }

    static int[] ints(final Object value, final String label) { if (!(value instanceof int[] values)) throw unavailable(label + " is invalid."); return values.clone(); }

    static List<Point2> points(final float[] values, final String label) { if (values.length % 2 != 0) throw unavailable(label + " does not contain XY pairs."); final ArrayList<Point2> points = new ArrayList<>(values.length / 2); for (int index = 0; index < values.length; index += 2) points.add(new Point2(values[index], values[index + 1])); return List.copyOf(points); }

    static void validateTriangleIndices(final int[] values, final int vertexCount, final String label) { if (values.length % 3 != 0) throw unavailable(label + " does not contain triangle triples."); for (int value : values) if (value < 0 || value >= vertexCount) throw unavailable(label + " contains an out-of-range vertex index."); }

    static List<Integer> boxed(final int[] values) { return java.util.Arrays.stream(values).boxed().toList(); }

    static List<Float> flatten(final List<Point2> points) { final ArrayList<Float> values = new ArrayList<>(points.size() * 2); for (Point2 point : points) { values.add(point.x()); values.add(point.y()); } return List.copyOf(values); }

    static FloatSequence floatSequence(final List<Float> values) { final float[] copy = new float[values.size()]; for (int index = 0; index < copy.length; index++) copy[index] = values.get(index); return new FloatSequence() { @Override public int size() { return copy.length; } @Override public float get(final int index) { return copy[index]; } }; }

    static IntSequence intSequence(final List<Integer> values) { final int[] copy = values.stream().mapToInt(Integer::intValue).toArray(); return new IntSequence() { @Override public int size() { return copy.length; } @Override public int get(final int index) { return copy[index]; } }; }

    static UnsupportedOperationException unsupported(final String feature) { return new UnsupportedOperationException(feature + " is unavailable without verified Editor semantics."); }

    static IllegalStateException unavailable(final String message) { return new IllegalStateException(message); }

    static IllegalStateException stale(final String kind, final String id) { return new IllegalStateException(kind + " reference is stale: " + id); }

    dev.turboism.adapter.cubism.core.CoreDrawableDefinition evaluated(
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

    enum Kind {
        ART_MESH("ArtMesh"), WARP("Warp Deformer"), ROTATION("Rotation Deformer");
        final String label;
        Kind(final String label) { this.label = label; }
    }

    record ObjectRef(String id, Object source, Object instance) { }

    record DeformerRef(String id, Object source, Object instance, Kind kind) { }

    record GlueRef(String id, Object source) { }
}

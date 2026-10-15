package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorArtMeshMorphSelectorContract;
import dev.turboism.mapping.verification.selector.EditorObjectWriteSelectorContract;
import dev.turboism.mapping.verification.selector.EditorWarpAuthoringSelectorContract;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.cubism.model.WarpMorphSnapshot;
import java.util.*;
import javax.swing.SwingUtilities;

/** A separate neutral-Warp morph domain. Ordinary Warp and root-ArtMesh guards stay unchanged. */
final class EditorWarpMorphState {
    static final String M = "cubism.editor-model.", H = "cubism.editor-history.semantic.";
    final Object source, parent, grid, forms, targets, targetSet, constraints;
    final int rows, columns;
    final boolean quad;
    final String sourceGuid, baseGuid;
    final List<Object> extensions, formRefs, targetRefs;
    final Map<String, Stored> stored;
    final Map<String, Parameter> parameters;
    final List<Key> keys;

    static void authorize(VerifiedMemberResolver r) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Warp morph requires EDT");
        if (!EditorReviewedGeneration.admits(r)
                || !r.authorizesFeature(
                        EditorArtMeshMorphSelectorContract.ADAPTER_SLICE_ID,
                        EditorArtMeshMorphSelectorContract.CAPABILITY_ID,
                        EditorArtMeshMorphSelectorContract.REQUIRED_ALIASES)
                || !r.authorizesFeature(
                        EditorWarpAuthoringSelectorContract.ADAPTER_SLICE_ID,
                        EditorWarpAuthoringSelectorContract.WRITE_CAPABILITY_ID,
                        EditorObjectWriteSelectorContract.WARP_REQUIRED_ALIASES)) throw unsupported();
        for (String capability : EditorWarpAuthoringSelectorContract.CAPABILITY_IDS)
            if (!r.authorizesFeature(
                    EditorWarpAuthoringSelectorContract.ADAPTER_SLICE_ID,
                    capability,
                    EditorWarpAuthoringSelectorContract.READ_ALIASES)) throw unsupported();
        // These protection reads are already reviewed members of the same admitted generation.
        if (!r.authorizesFeature(
                EditorWarpAuthoringSelectorContract.ADAPTER_SLICE_ID,
                "cubism.editor-history.semantic-read",
                Set.of(
                        H + "deformer-form.source",
                        M + "deformer-form.opacity",
                        M + "deformer-form.multiply-color",
                        M + "deformer-form.screen-color"))) throw unsupported();
    }

    EditorWarpMorphState(VerifiedMemberResolver r, Object source, Object modelSource, Object model) {
        authorize(r);
        this.source = source;
        if (!Boolean.FALSE.equals(call(r, M + "parameter-controllable-source.locked-in-hierarchy", source))
                || call(r, M + "parameter-controllable-source.extended-morph-set", source) != null) throw unsupported();
        parent = call(r, M + "parameter-controllable-source.target-deformer-source", source);
        extensions = list(call(r, M + "parameter-controllable-source.extensions", source));
        for (Object extension : extensions)
            if (r.isInstance(M + "extended-interpolation.class", extension)) throw unsupported();
        sourceGuid = guid(r, call(r, M + "deformer-source.guid", source));
        rows = integer(call(r, M + "warp-source.row", source));
        columns = integer(call(r, M + "warp-source.col", source));
        quad = bool(call(r, M + "warp-source.quad-transform", source));
        long count = ((long) rows + 1) * ((long) columns + 1);
        if (rows < 1 || columns < 1 || count > 1_000_000) throw unsupported();
        grid = call(r, M + "parameter-controllable.keyform-grid", source);
        if (!list(call(r, M + "keyform-grid.bindings", grid)).isEmpty()) throw unsupported();
        forms = call(r, M + "warp-source.keyforms", source);
        targetSet = call(r, M + "parameter-controllable.morph-target-set", source);
        if (call(r, M + "morph-target-set.owner", targetSet) != source) throw unsupported();
        targets = call(r, M + "morph-target-set.morph-targets", targetSet);
        if (!Boolean.FALSE.equals(call(r, M + "native-list.immutable", forms))
                || !Boolean.FALSE.equals(call(r, M + "native-list.immutable", targets))) throw unsupported();
        constraints = call(r, M + "morph-target-set.constraints", targetSet);
        if (!list(call(r, M + "morph-constraints.entries", constraints)).isEmpty()) throw unsupported();
        formRefs = list(forms);
        targetRefs = list(targets);
        if (formRefs.isEmpty() || formRefs.size() > 1_000_000 / count) throw unsupported();
        var formMap = new LinkedHashMap<String, Stored>();
        String base = null;
        for (Object form : formRefs) {
            if (call(r, H + "deformer-form.source", form) != source) throw unsupported();
            String id = formGuid(r, call(r, H + "form.guid", form));
            Object raw = call(r, M + "warp-form.positions", form);
            if (!(raw instanceof float[] values) || values.length != count * 2) throw unsupported();
            var points = new ArrayList<Point2>();
            for (int i = 0; i < values.length; i += 2) points.add(new Point2(values[i], values[i + 1]));
            var state = new Stored(
                    new WarpMorphSnapshot.Form(id, points, number(call(r, M + "deformer-form.opacity", form))),
                    color(r, call(r, M + "deformer-form.multiply-color", form)),
                    color(r, call(r, M + "deformer-form.screen-color", form)),
                    nullableText(call(r, M + "form.name", form)),
                    nullableText(call(r, M + "form.notes", form)));
            formMap.putIfAbsent(id, state);
            var cells = list(call(r, H + "keyform-grid.forms-for-guid", grid, call(r, H + "form.guid", form)));
            if (!cells.isEmpty()) {
                if (base != null || cells.size() != 1) throw unsupported();
                var access = call(r, H + "keyform-on-grid.access-key", cells.get(0));
                if (!list(call(r, H + "keyform-access-key.coordinates", access)).isEmpty()) throw unsupported();
                base = id;
            }
        }
        if (base == null) throw unsupported();
        baseGuid = base;
        stored = Map.copyOf(formMap);
        var ps = new LinkedHashMap<String, Parameter>();
        for (Object p : list(call(r, M + "parameter-set.parameters", call(r, M + "model.parameter-set", model)))) {
            Object def = call(r, M + "parameter.source", p);
            if (call(r, M + "parameter-source.model-source", def) != modelSource) throw unsupported();
            String id = guid(r, call(r, M + "parameter-source.guid", def));
            var parameter = new Parameter(
                    def,
                    text(call(r, M + "id.value", call(r, M + "parameter-source.id", def))),
                    nullableText(call(r, M + "parameter-source.name", def)),
                    bool(call(r, M + "parameter-source.morph-target", def)),
                    bool(call(r, M + "parameter-source.repeat", def)),
                    number(call(r, M + "parameter-source.minimum", def)),
                    number(call(r, M + "parameter-source.default", def)),
                    number(call(r, M + "parameter-source.maximum", def)),
                    number(call(r, M + "parameter-source.snap-epsilon", def)),
                    number(call(r, M + "parameter.value", p)));
            if (ps.put(id, parameter) != null) throw unsupported();
        }
        parameters = Map.copyOf(ps);
        var ks = new ArrayList<Key>();
        for (Object target : targetRefs) {
            if (call(r, M + "morph-target.owner", target) != source) throw unsupported();
            ks.add(new Key(
                    guid(r, call(r, M + "morph-target.parameter-guid", target)),
                    number(call(r, M + "morph-target.key-value", target)),
                    formGuid(r, call(r, M + "morph-target.keyform-guid", target))));
        }
        keys = List.copyOf(ks);
    }

    Parameter parameter(String guid) {
        Parameter p = parameters.get(guid);
        if (p == null) throw new NoSuchElementException("morph parameter GUID does not exist");
        if (!p.morph || p.repeat || p.def != 0f || p.min > 0f || p.max < 0f || p.min == p.max || p.epsilon < 0f)
            throw unsupported();
        return p;
    }

    void validate() {
        if (formRefs.size() != stored.size()) throw unsupported();
        var forms = new HashSet<String>();
        forms.add(baseGuid);
        var params = new HashMap<String, List<Float>>();
        for (Key key : keys) {
            Parameter p = parameter(key.parameterGuid);
            validateKey(key.value, p);
            var prior = params.computeIfAbsent(key.parameterGuid, ignored -> new ArrayList<>());
            if (prior.stream().anyMatch(v -> Math.abs(v - key.value) <= p.epsilon)
                    || !forms.add(key.formGuid)
                    || !stored.containsKey(key.formGuid)) throw unsupported();
            prior.add(key.value);
        }
        if (!forms.equals(stored.keySet())) throw unsupported();
    }

    WarpMorphSnapshot snapshot(DeformerId id) {
        validate();
        return new WarpMorphSnapshot(
                id,
                sourceGuid,
                stored.get(baseGuid).form,
                keys.stream()
                        .map(k -> new WarpMorphSnapshot.Key(k.parameterGuid, k.value, stored.get(k.formGuid).form))
                        .toList());
    }

    boolean protectedSame(EditorWarpMorphState other) {
        return source == other.source
                && parent == other.parent
                && grid == other.grid
                && forms == other.forms
                && targets == other.targets
                && targetSet == other.targetSet
                && constraints == other.constraints
                && rows == other.rows
                && columns == other.columns
                && quad == other.quad
                && sourceGuid.equals(other.sourceGuid)
                && baseGuid.equals(other.baseGuid)
                && identities(extensions, other.extensions)
                && parameters.equals(other.parameters)
                && stored.get(baseGuid).equals(other.stored.get(baseGuid))
                && formRefs.get(0) == other.formRefs.get(0);
    }

    boolean sameValues(EditorWarpMorphState other) {
        return protectedSame(other) && stored.equals(other.stored) && keys.equals(other.keys);
    }

    boolean same(EditorWarpMorphState other) {
        return sameValues(other) && identities(formRefs, other.formRefs) && identities(targetRefs, other.targetRefs);
    }

    boolean created(
            EditorWarpMorphState other,
            String parameter,
            float key,
            List<Point2> points,
            float opacity,
            Object returned) {
        other.validate();
        if (!protectedSame(other)
                || other.formRefs.size() != formRefs.size() + 1
                || other.targetRefs.size() != targetRefs.size() + 1
                || other.keys.size() != keys.size() + 1
                || other.targetRefs.get(targetRefs.size()) != returned) return false;
        if (!keys.equals(other.keys.subList(0, keys.size()))
                || !identities(formRefs, other.formRefs.subList(0, formRefs.size()))
                || !identities(targetRefs, other.targetRefs.subList(0, targetRefs.size()))) return false;
        for (var e : stored.entrySet()) if (!e.getValue().equals(other.stored.get(e.getKey()))) return false;
        Key added = other.keys.get(keys.size());
        if (!added.parameterGuid.equals(parameter) || added.value != key || stored.containsKey(added.formGuid))
            return false;
        Stored value = other.stored.get(added.formGuid), base = stored.get(baseGuid);
        return value.form.opacity() == opacity
                && value.form.controlPoints().equals(points)
                && value.multiply.equals(base.multiply)
                && value.screen.equals(base.screen);
    }

    static void validateKey(float key, Parameter p) {
        if (!Float.isFinite(key) || Math.abs(key) <= p.epsilon || key < p.min || key > p.max)
            throw new IllegalArgumentException("morph key outside range or too close to zero");
    }

    record Key(String parameterGuid, float value, String formGuid) {}

    record Stored(WarpMorphSnapshot.Form form, List<Float> multiply, List<Float> screen, String name, String notes) {}

    record Parameter(
            Object source,
            String id,
            String name,
            boolean morph,
            boolean repeat,
            float min,
            float def,
            float max,
            float epsilon,
            float value) {}

    private static boolean identities(List<?> a, List<?> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (a.get(i) != b.get(i)) return false;
        return true;
    }

    static Object call(VerifiedMemberResolver r, String alias, Object owner, Object... args) {
        return r.invoke(alias, Objects.requireNonNull(owner, alias), args);
    }

    private static String guid(VerifiedMemberResolver r, Object value) {
        return text(call(r, M + "guid.value", value));
    }

    private static String formGuid(VerifiedMemberResolver r, Object value) {
        return text(call(r, M + "form-guid.value", value));
    }

    private static List<Float> color(VerifiedMemberResolver r, Object c) {
        return List.of(
                number(call(r, M + "float-color.red", c)),
                number(call(r, M + "float-color.green", c)),
                number(call(r, M + "float-color.blue", c)),
                number(call(r, M + "float-color.alpha", c)));
    }

    private static List<Object> list(Object value) {
        if (!(value instanceof List<?> list)) throw unsupported();
        return new ArrayList<>(list);
    }

    private static float number(Object v) {
        if (v instanceof Float f && Float.isFinite(f)) return f;
        throw unsupported();
    }

    private static int integer(Object v) {
        if (v instanceof Integer i) return i;
        throw unsupported();
    }

    private static boolean bool(Object v) {
        if (v instanceof Boolean b) return b;
        throw unsupported();
    }

    private static String nullableText(Object v) {
        if (v == null || v instanceof String) return (String) v;
        throw unsupported();
    }

    private static String text(Object v) {
        if (v instanceof String s && !s.isBlank()) return s;
        throw unsupported();
    }

    static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("unsupported neutral Warp opacity morph domain");
    }
}

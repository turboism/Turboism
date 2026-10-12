package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorArtMeshMorphSelectorContract;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot;
import dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.Coordinate;
import dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.Form;
import dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.MorphKey;
import dev.turboism.sdk.cubism.model.Point2;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import javax.swing.SwingUtilities;

/** Native references never escape this package; values include the observable protection domain. */
final class EditorArtMeshMorphState {
    final ArtMeshAuthoringSnapshot snapshot;
    final Map<String, Attributes> attributes;
    final Map<String, Parameter> parameters;
    final Map<String, Float> values;
    final Map<String, Object> parameterObjects;
    final Map<String, Object> formObjects;
    final List<Object> targetObjects;
    final List<Object> extensions;
    final Object formList;
    final Object targetList;
    final Object targetSet;
    final Object constraints;

    private EditorArtMeshMorphState(
            final ArtMeshAuthoringSnapshot snapshot,
            final Map<String, Attributes> attributes,
            final Map<String, Parameter> parameters,
            final Map<String, Float> values,
            final Map<String, Object> parameterObjects,
            final Map<String, Object> formObjects,
            final List<Object> targetObjects,
            final List<Object> extensions,
            final Object formList,
            final Object targetList,
            final Object targetSet,
            final Object constraints) {
        this.snapshot = snapshot;
        this.attributes = Map.copyOf(attributes);
        this.parameters = Map.copyOf(parameters);
        this.values = Map.copyOf(values);
        this.parameterObjects = Map.copyOf(parameterObjects);
        this.formObjects = Map.copyOf(formObjects);
        this.targetObjects = List.copyOf(targetObjects);
        this.extensions = List.copyOf(extensions);
        this.formList = formList;
        this.targetList = targetList;
        this.targetSet = targetSet;
        this.constraints = constraints;
    }

    static void requireAuthorized(final VerifiedMemberResolver resolver) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("morph reads require EDT");
        if (!EditorReviewedGeneration.admits(resolver)
                || !resolver.authorizesFeature(
                        EditorArtMeshMorphSelectorContract.ADAPTER_SLICE_ID,
                        EditorArtMeshMorphSelectorContract.CAPABILITY_ID,
                        EditorArtMeshMorphSelectorContract.REQUIRED_ALIASES)) throw unsupported();
    }

    /** Draw-order writes additionally require the stored-order member; other operations do not. */
    static void requireOrderAuthorized(final VerifiedMemberResolver resolver) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("morph writes require EDT");
        if (!EditorReviewedGeneration.admits(resolver)
                || !resolver.authorizesFeature(
                        EditorArtMeshMorphSelectorContract.ADAPTER_SLICE_ID,
                        EditorArtMeshMorphSelectorContract.CAPABILITY_ID,
                        EditorArtMeshMorphSelectorContract.DRAW_ORDER_REQUIRED_ALIASES)) throw unsupported();
    }

    static EditorArtMeshMorphState read(
            final VerifiedMemberResolver r,
            final ArtMeshId id,
            final Object source,
            final Object modelSource,
            final Object model) {
        requireAuthorized(r);
        if (Boolean.TRUE.equals(
                        call(r, "cubism.editor-model.parameter-controllable-source.locked-in-hierarchy", source))
                || call(r, "cubism.editor-model.parameter-controllable-source.target-deformer-source", source) != null
                || call(r, "cubism.editor-model.parameter-controllable-source.extended-morph-set", source) != null)
            throw unsupported();
        final Object root = call(r, "cubism.editor-model.model-source.root-part", modelSource);
        if (list(call(r, "cubism.editor-model.part-source.children", root)).stream()
                .noneMatch(child -> child == source)) throw unsupported();
        final List<Object> extensions =
                list(call(r, "cubism.editor-model.parameter-controllable-source.extensions", source));
        for (final Object extension : extensions) {
            if (r.isInstance("cubism.editor-model.extended-interpolation.class", extension)) throw unsupported();
        }
        final Object forms = call(r, "cubism.editor-model.art-mesh-source.keyforms", source);
        final Object set = call(r, "cubism.editor-model.parameter-controllable.morph-target-set", source);
        if (call(r, "cubism.editor-model.morph-target-set.owner", set) != source) throw unsupported();
        final Object targets = call(r, "cubism.editor-model.morph-target-set.morph-targets", set);
        if (!Boolean.FALSE.equals(call(r, "cubism.editor-model.native-list.immutable", forms))
                || !Boolean.FALSE.equals(call(r, "cubism.editor-model.native-list.immutable", targets)))
            throw unsupported();
        final Object constraints = call(r, "cubism.editor-model.morph-target-set.constraints", set);
        if (!list(call(r, "cubism.editor-model.morph-constraints.entries", constraints))
                .isEmpty()) throw unsupported();
        final var snapshot = new EditorArtMeshAuthoringReadAccess(r).read(id, source);
        final var attributes = new LinkedHashMap<String, Attributes>();
        final var formObjects = new LinkedHashMap<String, Object>();
        for (final Object form : list(forms)) {
            if (r.invoke("cubism.editor-history.semantic.art-mesh-form.source", form) != source) throw unsupported();
            final String guid = text(call(
                    r,
                    "cubism.editor-model.form-guid.value",
                    r.invoke("cubism.editor-history.semantic.form.guid", form)));
            if (formObjects.put(guid, form) != null) throw unsupported();
            attributes.put(
                    guid,
                    new Attributes(
                            number(call(r, "cubism.editor-model.drawable-form.opacity", form)),
                            color(r, call(r, "cubism.editor-model.drawable-form.multiply-color", form)),
                            color(r, call(r, "cubism.editor-model.drawable-form.screen-color", form)),
                            text(call(
                                    r,
                                    "cubism.editor-model.coord-type.label",
                                    call(r, "cubism.editor-model.drawable-form.coord-type", form))),
                            nullableText(call(r, "cubism.editor-model.form.name", form)),
                            nullableText(call(r, "cubism.editor-model.form.notes", form))));
        }
        final var parameters = new LinkedHashMap<String, Parameter>();
        final var values = new LinkedHashMap<String, Float>();
        final var parameterObjects = new LinkedHashMap<String, Object>();
        for (final Object parameter : list(call(
                r,
                "cubism.editor-model.parameter-set.parameters",
                call(r, "cubism.editor-model.model.parameter-set", model)))) {
            final Object definition = call(r, "cubism.editor-model.parameter.source", parameter);
            if (call(r, "cubism.editor-model.parameter-source.model-source", definition) != modelSource)
                throw unsupported();
            final String guid = text(call(
                    r,
                    "cubism.editor-model.guid.value",
                    call(r, "cubism.editor-model.parameter-source.guid", definition)));
            final Parameter value = new Parameter(
                    text(call(
                            r,
                            "cubism.editor-model.id.value",
                            call(r, "cubism.editor-model.parameter-source.id", definition))),
                    nullableText(call(r, "cubism.editor-model.parameter-source.name", definition)),
                    bool(call(r, "cubism.editor-model.parameter-source.morph-target", definition)),
                    bool(call(r, "cubism.editor-model.parameter-source.repeat", definition)),
                    number(call(r, "cubism.editor-model.parameter-source.minimum", definition)),
                    number(call(r, "cubism.editor-model.parameter-source.default", definition)),
                    number(call(r, "cubism.editor-model.parameter-source.maximum", definition)),
                    number(call(r, "cubism.editor-model.parameter-source.snap-epsilon", definition)));
            if (parameters.put(guid, value) != null) throw unsupported();
            values.put(guid, number(call(r, "cubism.editor-model.parameter.value", parameter)));
            parameterObjects.put(guid, definition);
        }
        final List<Object> targetObjects = list(targets);
        for (final Object target : targetObjects) {
            if (call(r, "cubism.editor-model.morph-target.owner", target) != source) throw unsupported();
        }
        return new EditorArtMeshMorphState(
                snapshot,
                attributes,
                parameters,
                values,
                parameterObjects,
                formObjects,
                targetObjects,
                extensions,
                forms,
                targets,
                set,
                constraints);
    }

    /** Admission is stricter than recovery: a partial add may leave a temporary orphan form. */
    String validateDomain(final String parameterGuid) {
        parameter(parameterGuid);
        final var defaults = snapshot.axes().stream()
                .map(axis -> new Coordinate(axis.parameterGuid(), axis.defaultValue()))
                .toList();
        final String base =
                EditorArtMeshKeyformAccess.select(snapshot, defaults).formGuid();
        final Map<String, Integer> refs = new HashMap<>();
        snapshot.cells().forEach(cell -> refs.merge(cell.formGuid(), 1, Integer::sum));
        final Map<String, List<Float>> keys = new HashMap<>();
        for (final MorphKey key : snapshot.morphKeys()) {
            final Parameter definition = parameter(key.parameterGuid());
            final float value = key.keyValue().orElseThrow(EditorArtMeshMorphState::unsupported);
            validateKey(value, definition.epsilon());
            final var prior = keys.computeIfAbsent(key.parameterGuid(), ignored -> new ArrayList<>());
            if (prior.stream().anyMatch(other -> Math.abs(other - value) <= definition.epsilon())) throw unsupported();
            prior.add(value);
            refs.merge(key.formGuid(), 1, Integer::sum);
        }
        if (refs.size() != snapshot.forms().size() || refs.values().stream().anyMatch(count -> count != 1))
            throw unsupported();
        return base;
    }

    Parameter parameter(final String guid) {
        final Parameter p = parameters.get(guid);
        if (p == null) throw new NoSuchElementException("morph parameter GUID does not exist");
        if (!p.morph()
                || p.repeat()
                || p.minimum() != 0f
                || p.defaultValue() != 0f
                || p.maximum() != 1f
                || p.epsilon() < 0f
                || p.epsilon() >= 1f
                || snapshot.axes().stream()
                        .anyMatch(axis -> axis.parameterGuid().equals(guid))) throw unsupported();
        return p;
    }

    static void validateKey(final float key, final float epsilon) {
        if (!Float.isFinite(key) || key <= epsilon || key > 1f) {
            throw new IllegalArgumentException("morph key must be a non-basic value in (epsilon, 1]");
        }
    }

    MorphKey target(final String guid, final float value) {
        return snapshot.morphKeys().stream()
                .filter(key -> key.parameterGuid().equals(guid)
                        && key.keyValue()
                                .filter(v -> Float.compare(v, value) == 0)
                                .isPresent())
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("morph key does not exist"));
    }

    Form form(final String guid) {
        return snapshot.forms().stream()
                .filter(form -> form.formGuid().equals(guid))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("morph form membership changed"));
    }

    boolean sameBindings(final EditorArtMeshMorphState other) {
        return formList == other.formList
                && targetList == other.targetList
                && targetSet == other.targetSet
                && constraints == other.constraints
                && identities(extensions, other.extensions)
                && objectMap(parameterObjects, other.parameterObjects)
                && parameters.equals(other.parameters);
    }

    boolean sameLayout(final EditorArtMeshMorphState other) {
        return sameBindings(other)
                && snapshot.artMeshId().equals(other.snapshot.artMeshId())
                && snapshot.sourceGuid().equals(other.snapshot.sourceGuid())
                && snapshot.sourceGeometry().equals(other.snapshot.sourceGeometry())
                && snapshot.axes().equals(other.snapshot.axes())
                && snapshot.cells().equals(other.snapshot.cells())
                && snapshot.morphKeys().equals(other.snapshot.morphKeys())
                && objectMap(formObjects, other.formObjects)
                && identities(targetObjects, other.targetObjects);
    }

    boolean sameValues(final EditorArtMeshMorphState other) {
        return sameBindings(other)
                && snapshot.equals(other.snapshot)
                && attributes.equals(other.attributes)
                && values.equals(other.values);
    }

    /** Only the morph membership/content domain may be rebuilt by captured native snapshots. */
    boolean protectedUnchanged(final EditorArtMeshMorphState other) {
        if (!sameBindings(other)
                || !snapshot.artMeshId().equals(other.snapshot.artMeshId())
                || !snapshot.sourceGuid().equals(other.snapshot.sourceGuid())
                || !snapshot.sourceGeometry().equals(other.snapshot.sourceGeometry())
                || !snapshot.axes().equals(other.snapshot.axes())
                || !snapshot.cells().equals(other.snapshot.cells())
                || !values.equals(other.values)) return false;
        for (final var cell : snapshot.cells()) {
            final String guid = cell.formGuid();
            if (formObjects.get(guid) != other.formObjects.get(guid)
                    || !form(guid).equals(other.form(guid))
                    || !attributes.get(guid).equals(other.attributes.get(guid))) return false;
        }
        return true;
    }

    boolean onlyPositionsChanged(final EditorArtMeshMorphState other, final String guid, final List<Point2> input) {
        if (!sameLayout(other) || !attributes.equals(other.attributes) || !values.equals(other.values)) return false;
        return snapshot.forms().stream().allMatch(form -> {
            final Form expected = form.formGuid().equals(guid) ? new Form(guid, input, form.drawOrder()) : form;
            return expected.equals(other.form(form.formGuid()));
        });
    }

    /** Independent postcondition: every stored form stays intact except the target's absolute order. */
    boolean onlyDrawOrderChanged(final EditorArtMeshMorphState other, final String guid, final int order) {
        if (!sameLayout(other) || !attributes.equals(other.attributes) || !values.equals(other.values)) return false;
        return snapshot.forms().stream().allMatch(form -> {
            final Form expected = form.formGuid().equals(guid) ? new Form(guid, form.positions(), order) : form;
            return expected.equals(other.form(form.formGuid()));
        });
    }

    /** Independent absolute seed: blendable fields interpolate; the new form keeps the base coordinate type. */
    Seed seed(final String parameterGuid, final float key, final String baseGuid) {
        final var knots = new ArrayList<Knot>();
        knots.add(new Knot(0f, form(baseGuid)));
        snapshot.morphKeys().stream()
                .filter(k -> k.parameterGuid().equals(parameterGuid))
                .forEach(k -> knots.add(new Knot(k.keyValue().orElseThrow(), form(k.formGuid()))));
        knots.sort(Comparator.comparingDouble(Knot::key));
        for (int i = 1; i < knots.size(); i++) {
            final Knot lo = knots.get(i - 1), hi = knots.get(i);
            if (key <= hi.key()) {
                return seedBetween(lo.form(), hi.form(), (key - lo.key()) / (hi.key() - lo.key()), baseGuid);
            }
        }
        final Form last = knots.get(knots.size() - 1).form();
        return seedBetween(last, last, 0f, baseGuid);
    }

    private Seed seedBetween(final Form lo, final Form hi, final float t, final String baseGuid) {
        final var points = new ArrayList<Point2>();
        for (int v = 0; v < lo.positions().size(); v++) {
            final Point2 a = lo.positions().get(v), b = hi.positions().get(v);
            final Point2 base = form(baseGuid).positions().get(v);
            points.add(
                    new Point2(roundTrip(base.x(), mix(a.x(), b.x(), t)), roundTrip(base.y(), mix(a.y(), b.y(), t))));
        }
        final Attributes a = attributes.get(lo.formGuid()), b = attributes.get(hi.formGuid());
        // ACDrawableForm.blend consumes the public integer drawOrder, not its private float accumulator.
        // Three postBlend stages: knot interpolation, base+interpolated-base, outer zero/blend(1).
        final int interpolatedOrder = postOrder(mix(lo.drawOrder(), hi.drawOrder(), t));
        final int order = postOrder(postOrder(roundTrip(form(baseGuid).drawOrder(), interpolatedOrder)));
        final Attributes base = attributes.get(baseGuid);
        return new Seed(
                List.copyOf(points),
                order,
                roundTrip(base.opacity(), mix(a.opacity(), b.opacity(), t)),
                mixColor(base.multiply(), a.multiply(), b.multiply(), t),
                mixColor(base.screen(), a.screen(), b.screen(), t),
                base.coordinateType());
    }

    private static float mix(final float a, final float b, final float t) {
        return (0f + a * (1f - t)) + b * t;
    }

    private static int postOrder(final float value) {
        return (int) (value + .001f * Math.signum(value));
    }
    // y.a(base, targets, parameter, key) does not return the raw interpolator result.
    private static float roundTrip(final float base, final float interpolated) {
        return 0f + ((base + interpolated) + base * -1f);
    }

    private static List<Float> mixColor(
            final List<Float> base, final List<Float> a, final List<Float> b, final float t) {
        final var result = new ArrayList<Float>();
        for (int i = 0; i < 4; i++) result.add(roundTrip(base.get(i), mix(a.get(i), b.get(i), t)));
        return List.copyOf(result);
    }

    record Seed(
            List<Point2> positions,
            int drawOrder,
            float opacity,
            List<Float> multiply,
            List<Float> screen,
            String coordinateType) {
        boolean matches(final Form form, final Attributes attrs) {
            return positions.equals(form.positions())
                    && drawOrder == form.drawOrder()
                    && Float.compare(opacity, attrs.opacity()) == 0
                    && multiply.equals(attrs.multiply())
                    && screen.equals(attrs.screen())
                    && coordinateType.equals(attrs.coordinateType());
            // Native creation deliberately names/annotates the new form; only existing names/notes are protected.
        }
    }

    private record Knot(float key, Form form) {}

    record Parameter(
            String id,
            String name,
            boolean morph,
            boolean repeat,
            float minimum,
            float defaultValue,
            float maximum,
            float epsilon) {}

    record Attributes(
            float opacity,
            List<Float> multiply,
            List<Float> screen,
            String coordinateType,
            String name,
            String notes) {}

    static Object call(final VerifiedMemberResolver r, final String suffix, final Object owner, final Object... args) {
        return r.invoke(suffix, Objects.requireNonNull(owner, suffix), args);
    }

    private static List<Float> color(final VerifiedMemberResolver r, final Object color) {
        return List.of(
                number(call(r, "cubism.editor-model.float-color.red", color)),
                number(call(r, "cubism.editor-model.float-color.green", color)),
                number(call(r, "cubism.editor-model.float-color.blue", color)),
                number(call(r, "cubism.editor-model.float-color.alpha", color)));
    }

    private static List<Object> list(final Object raw) {
        if (!(raw instanceof List<?> list)) throw unsupported();
        return new ArrayList<>(list);
    }

    private static boolean bool(final Object raw) {
        if (!(raw instanceof Boolean value)) throw unsupported();
        return value;
    }

    private static float number(final Object raw) {
        if (!(raw instanceof Float value) || !Float.isFinite(value)) throw unsupported();
        return value;
    }

    private static String nullableText(final Object raw) {
        if (raw == null) return null;
        if (raw instanceof String value) return value;
        throw unsupported();
    }

    private static String text(final Object raw) {
        final String text = nullableText(raw);
        if (text == null || text.isBlank()) throw unsupported();
        return text;
    }

    private static boolean identities(final List<Object> a, final List<Object> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (a.get(i) != b.get(i)) return false;
        return true;
    }

    private static boolean objectMap(final Map<String, Object> a, final Map<String, Object> b) {
        return a.keySet().equals(b.keySet()) && a.entrySet().stream().allMatch(e -> b.get(e.getKey()) == e.getValue());
    }

    static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("unsupported root ArtMesh morph authoring domain");
    }
}

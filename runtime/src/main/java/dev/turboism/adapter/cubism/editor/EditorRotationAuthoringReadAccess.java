package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorRotationAuthoringSelectorContract;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.RotationAuthoringSnapshot;
import dev.turboism.sdk.cubism.model.RotationAuthoringSnapshot.Axis;
import dev.turboism.sdk.cubism.model.RotationAuthoringSnapshot.Cell;
import dev.turboism.sdk.cubism.model.RotationAuthoringSnapshot.Coordinate;
import dev.turboism.sdk.cubism.model.RotationAuthoringSnapshot.Form;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import javax.swing.SwingUtilities;

/** Stored Rotation observation, on the Editor thread. No evaluation or mutation selectors. */
final class EditorRotationAuthoringReadAccess {
    private final VerifiedMemberResolver resolver;

    EditorRotationAuthoringReadAccess(VerifiedMemberResolver resolver) {
        this.resolver = java.util.Objects.requireNonNull(resolver);
    }

    RotationAuthoringSnapshot read(DeformerId id, Object source) {
        if (!SwingUtilities.isEventDispatchThread())
            throw new IllegalStateException("stored Rotation reads require Editor thread");
        if (!EditorReviewedGeneration.admits(resolver)) throw unavailable();
        for (String capability : EditorRotationAuthoringSelectorContract.CAPABILITY_IDS)
            if (!resolver.authorizesFeature(
                    EditorRotationAuthoringSelectorContract.ADAPTER_SLICE_ID,
                    capability,
                    EditorRotationAuthoringSelectorContract.READ_ALIASES)) throw unavailable();
        String sourceGuid = text(resolver.invoke(
                "cubism.editor-model.guid.value", resolver.invoke("cubism.editor-model.deformer-source.guid", source)));
        float baseAngle = number(resolver.invoke("cubism.editor-model.rotation-source.base-angle", source));
        List<?> nativeForms = list(resolver.invoke("cubism.editor-model.rotation-source.keyforms", source));
        if (nativeForms.size() > RotationAuthoringSnapshot.MAX_FORMS)
            throw new IllegalArgumentException("Rotation stored form budget exceeded");
        Object morph = resolver.invoke("cubism.editor-model.parameter-controllable.morph-target-set", source);
        if (morph != null
                && !list(resolver.invoke("cubism.editor-model.morph-target-set.morph-targets", morph))
                        .isEmpty())
            throw new UnsupportedOperationException("morph-enabled Rotation stored authoring is unavailable");
        Object grid = resolver.invoke("cubism.editor-model.parameter-controllable.keyform-grid", source);
        var axes = new ArrayList<Axis>();
        var byBinding = new IdentityHashMap<Object, Axis>();
        for (Object binding : list(resolver.invoke("cubism.editor-model.keyform-grid.bindings", grid))) {
            Object parameter = resolver.invoke("cubism.editor-history.semantic.keyform-binding.parameter", binding);
            var keys = new ArrayList<Float>();
            for (Object key : list(resolver.invoke("cubism.editor-model.keyform-binding.keys", binding)))
                keys.add(number(key));
            Axis axis = new Axis(
                    text(resolver.invoke(
                            "cubism.editor-model.guid.value",
                            resolver.invoke("cubism.editor-model.keyform-binding.parameter-guid", binding))),
                    new ParameterId(text(resolver.invoke(
                            "cubism.editor-model.id.value",
                            resolver.invoke("cubism.editor-model.keyform-binding.parameter-id", binding)))),
                    number(resolver.invoke("cubism.editor-model.parameter-source.minimum", parameter)),
                    number(resolver.invoke("cubism.editor-model.parameter-source.default", parameter)),
                    number(resolver.invoke("cubism.editor-model.parameter-source.maximum", parameter)),
                    keys);
            axes.add(axis);
            if (byBinding.put(binding, axis) != null)
                throw new IllegalStateException("duplicate Rotation grid binding");
        }
        var forms = new ArrayList<Form>();
        var cells = new ArrayList<Cell>();
        for (Object form : nativeForms) {
            Object guid = resolver.invoke("cubism.editor-history.semantic.form.guid", form);
            String value = text(resolver.invoke("cubism.editor-model.form-guid.value", guid));
            forms.add(new Form(value, value(form)));
            for (Object row :
                    list(resolver.invoke("cubism.editor-history.semantic.keyform-grid.forms-for-guid", grid, guid))) {
                Object access = resolver.invoke("cubism.editor-history.semantic.keyform-on-grid.access-key", row);
                var coordinates = new ArrayList<Coordinate>();
                for (Object coordinate : list(
                        resolver.invoke("cubism.editor-history.semantic.keyform-access-key.coordinates", access))) {
                    Object binding =
                            resolver.invoke("cubism.editor-history.semantic.key-on-parameter.binding", coordinate);
                    Axis axis = byBinding.get(binding);
                    if (axis == null) throw new IllegalStateException("foreign Rotation grid binding");
                    coordinates.add(new Coordinate(
                            axis.parameterGuid(),
                            number(resolver.invoke(
                                    "cubism.editor-history.semantic.key-on-parameter.value", coordinate))));
                }
                cells.add(new Cell(value, coordinates));
            }
        }
        return new RotationAuthoringSnapshot(id, sourceGuid, baseAngle, axes, cells, forms);
    }

    private dev.turboism.sdk.cubism.model.RotationDeformerForm value(Object form) {
        return new dev.turboism.sdk.cubism.model.RotationDeformerForm(
                number(resolver.invoke("cubism.editor-model.rotation-form.angle", form)),
                number(resolver.invoke("cubism.editor-model.rotation-form.origin-x", form)),
                number(resolver.invoke("cubism.editor-model.rotation-form.origin-y", form)),
                number(resolver.invoke("cubism.editor-model.rotation-form.scale", form)),
                flag(resolver.invoke("cubism.editor-model.rotation-form.reflect-x", form)),
                flag(resolver.invoke("cubism.editor-model.rotation-form.reflect-y", form)));
    }

    private static boolean flag(Object raw) {
        if (raw instanceof Boolean value) return value;
        throw new IllegalStateException("Rotation reflection is not a boolean");
    }

    private static float number(Object raw) {
        if (raw instanceof Float value && Float.isFinite(value)) return value;
        throw new IllegalStateException("Rotation coordinate is not a finite float");
    }

    private static String text(Object raw) {
        if (raw instanceof String value && !value.isBlank()) return value;
        throw new IllegalStateException("missing Rotation identity");
    }

    private static List<?> list(Object raw) {
        if (raw instanceof List<?> value) return List.copyOf(value);
        throw new IllegalStateException("Rotation collection is not a list");
    }

    private static UnsupportedOperationException unavailable() {
        return new UnsupportedOperationException("verified stored Rotation reads are unavailable");
    }
}

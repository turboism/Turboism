package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorWarpAuthoringSelectorContract;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.cubism.model.WarpAuthoringSnapshot;
import dev.turboism.sdk.cubism.model.WarpAuthoringSnapshot.Axis;
import dev.turboism.sdk.cubism.model.WarpAuthoringSnapshot.Cell;
import dev.turboism.sdk.cubism.model.WarpAuthoringSnapshot.Coordinate;
import dev.turboism.sdk.cubism.model.WarpAuthoringSnapshot.Form;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import javax.swing.SwingUtilities;

/** Stored Warp observation, on the Editor thread. No evaluation or mutation selectors. */
final class EditorWarpAuthoringReadAccess {
    private final VerifiedMemberResolver resolver;

    EditorWarpAuthoringReadAccess(VerifiedMemberResolver resolver) {
        this.resolver = java.util.Objects.requireNonNull(resolver);
    }

    WarpAuthoringSnapshot read(DeformerId id, Object source) {
        if (!SwingUtilities.isEventDispatchThread())
            throw new IllegalStateException("stored Warp reads require Editor thread");
        if (!EditorReviewedGeneration.admits(resolver)) throw unavailable();
        for (String capability : EditorWarpAuthoringSelectorContract.CAPABILITY_IDS)
            if (!resolver.authorizesFeature(
                    EditorWarpAuthoringSelectorContract.ADAPTER_SLICE_ID,
                    capability,
                    EditorWarpAuthoringSelectorContract.READ_ALIASES)) throw unavailable();
        String sourceGuid = text(resolver.invoke(
                "cubism.editor-model.guid.value", resolver.invoke("cubism.editor-model.deformer-source.guid", source)));
        int rows = integer(resolver.invoke("cubism.editor-model.warp-source.row", source));
        int columns = integer(resolver.invoke("cubism.editor-model.warp-source.col", source));
        Object quadValue = resolver.invoke("cubism.editor-model.warp-source.quad-transform", source);
        if (!(quadValue instanceof Boolean quad)) throw new IllegalStateException("invalid Warp quad flag");
        long count = ((long) rows + 1) * ((long) columns + 1);
        List<?> nativeForms = list(resolver.invoke("cubism.editor-model.warp-source.keyforms", source));
        if (rows < 1
                || columns < 1
                || count > WarpAuthoringSnapshot.MAX_CONTROL_SAMPLES
                || nativeForms.size() > WarpAuthoringSnapshot.MAX_CONTROL_SAMPLES / count)
            throw new IllegalArgumentException("Warp stored control sample budget exceeded");
        Object morph = resolver.invoke("cubism.editor-model.parameter-controllable.morph-target-set", source);
        if (morph != null
                && !list(resolver.invoke("cubism.editor-model.morph-target-set.morph-targets", morph))
                        .isEmpty())
            throw new UnsupportedOperationException("morph-enabled Warp stored authoring is unavailable");
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
            if (byBinding.put(binding, axis) != null) throw new IllegalStateException("duplicate Warp grid binding");
        }
        var forms = new ArrayList<Form>();
        var cells = new ArrayList<Cell>();
        for (Object form : nativeForms) {
            Object guid = resolver.invoke("cubism.editor-history.semantic.form.guid", form);
            String value = text(resolver.invoke("cubism.editor-model.form-guid.value", guid));
            forms.add(new Form(value, points(resolver.invoke("cubism.editor-model.warp-form.positions", form), count)));
            for (Object row :
                    list(resolver.invoke("cubism.editor-history.semantic.keyform-grid.forms-for-guid", grid, guid))) {
                Object access = resolver.invoke("cubism.editor-history.semantic.keyform-on-grid.access-key", row);
                var coordinates = new ArrayList<Coordinate>();
                for (Object coordinate : list(
                        resolver.invoke("cubism.editor-history.semantic.keyform-access-key.coordinates", access))) {
                    Object binding =
                            resolver.invoke("cubism.editor-history.semantic.key-on-parameter.binding", coordinate);
                    Axis axis = byBinding.get(binding);
                    if (axis == null) throw new IllegalStateException("foreign Warp grid binding");
                    coordinates.add(new Coordinate(
                            axis.parameterGuid(),
                            number(resolver.invoke(
                                    "cubism.editor-history.semantic.key-on-parameter.value", coordinate))));
                }
                cells.add(new Cell(value, coordinates));
            }
        }
        return new WarpAuthoringSnapshot(id, sourceGuid, rows, columns, quad, axes, cells, forms);
    }

    private static List<Point2> points(Object raw, long count) {
        if (!(raw instanceof float[] array) || array.length != count * 2)
            throw new IllegalStateException("wrong Warp control size");
        var result = new ArrayList<Point2>(array.length / 2);
        for (int i = 0; i < array.length; i += 2) result.add(new Point2(array[i], array[i + 1]));
        return List.copyOf(result);
    }

    private static int integer(Object raw) {
        if (raw instanceof Integer value) return value;
        throw new IllegalStateException("Warp grid size is not an int");
    }

    private static float number(Object raw) {
        if (raw instanceof Float value && Float.isFinite(value)) return value;
        throw new IllegalStateException("Warp coordinate is not a finite float");
    }

    private static String text(Object raw) {
        if (raw instanceof String value && !value.isBlank()) return value;
        throw new IllegalStateException("missing Warp identity");
    }

    private static List<?> list(Object raw) {
        if (raw instanceof List<?> value) return List.copyOf(value);
        throw new IllegalStateException("Warp collection is not a list");
    }

    private static UnsupportedOperationException unavailable() {
        return new UnsupportedOperationException("verified stored Warp reads are unavailable");
    }
}

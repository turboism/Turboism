package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorWarpAuthoringSelectorContract;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.cubism.model.WarpAuthoringSnapshot;
import dev.turboism.sdk.cubism.model.WarpAuthoringSnapshot.Cell;
import dev.turboism.sdk.cubism.model.WarpAuthoringSnapshot.Coordinate;
import dev.turboism.sdk.cubism.model.WarpAuthoringSnapshot.Form;
import dev.turboism.sdk.cubism.model.WarpKeyformPositions;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** One compare-and-set operation on independently stored non-default Warp forms. */
final class EditorWarpKeyformAccess {
    @FunctionalInterface
    interface Writer {
        void write(
                Object form,
                String property,
                String label,
                Runnable mutation,
                java.util.function.BooleanSupplier applied,
                Runnable compensation,
                java.util.function.BooleanSupplier restored);
    }

    private EditorWarpKeyformAccess() {}

    static void writeBatch(
            VerifiedMemberResolver resolver,
            DeformerId id,
            Supplier<Object> currentSource,
            WarpAuthoringSnapshot expected,
            List<WarpKeyformPositions> edits,
            Runnable requireTransaction,
            Writer writer) {
        Objects.requireNonNull(expected, "expected");
        var input = List.copyOf(edits);
        if (input.size() > 4096
                || input.stream().mapToLong(e -> e.controlPoints().size()).sum()
                        > WarpAuthoringSnapshot.MAX_CONTROL_SAMPLES)
            throw new IllegalArgumentException("Warp batch target/control budget exceeded");
        EditorHostThread.dispatch("Warp stored keyform batch", () -> {
            if (!resolver.isExactCubismVersion("5.2.03", "5.3.02", "5.3.03")
                    || !resolver.authorizesFeature(
                            EditorWarpAuthoringSelectorContract.ADAPTER_SLICE_ID,
                            EditorWarpAuthoringSelectorContract.WRITE_CAPABILITY_ID,
                            EditorWarpAuthoringSelectorContract.WRITE_ALIASES))
                throw new UnsupportedOperationException("verified Warp stored writes unavailable");
            requireTransaction.run();
            Object source = currentSource.get();
            Supplier<WarpAuthoringSnapshot> read = () -> {
                if (currentSource.get() != source) throw stale();
                return new EditorWarpAuthoringReadAccess(resolver).read(id, source);
            };
            if (!read.get().equals(expected)) throw stale();
            var replacements = replacements(expected, input);
            var after = new WarpAuthoringSnapshot(
                    expected.deformerId(),
                    expected.sourceGuid(),
                    expected.rows(),
                    expected.columns(),
                    expected.quadTransform(),
                    expected.axes(),
                    expected.cells(),
                    expected.forms().stream()
                            .map(f ->
                                    new Form(f.formGuid(), replacements.getOrDefault(f.formGuid(), f.controlPoints())))
                            .toList());
            if (after.equals(expected)) return null;
            var nativeForms = forms(resolver, source);
            if (!nativeForms
                    .keySet()
                    .equals(expected.forms().stream().map(Form::formGuid).collect(java.util.stream.Collectors.toSet())))
                throw stale();
            Runnable identityCheck = () -> {
                if (currentSource.get() != source) throw stale();
                var now = forms(resolver, source);
                if (!now.keySet().equals(nativeForms.keySet())
                        || now.entrySet().stream().anyMatch(e -> e.getValue() != nativeForms.get(e.getKey())))
                    throw stale();
            };
            writer.write(
                    nativeForms.get(replacements.keySet().iterator().next()),
                    "keyformPositionsBatch",
                    "Set Warp ordinary keyform controls",
                    () -> {
                        identityCheck.run();
                        if (!read.get().equals(expected)) throw stale();
                        for (var form : expected.forms()) {
                            var positions = replacements.get(form.formGuid());
                            if (positions != null && !positions.equals(form.controlPoints()))
                                set(resolver, nativeForms.get(form.formGuid()), positions);
                        }
                    },
                    () -> {
                        identityCheck.run();
                        return read.get().equals(after);
                    },
                    () -> {
                        identityCheck.run();
                        var now = read.get();
                        if (!sameLayout(expected, now)) throw stale();
                        // Native abort can already have restored earlier contributions. Restore
                        // the complete independent ordinary domain, ending at the root before image.
                        var editable = independentNonDefault(expected);
                        for (int i = 0; i < expected.forms().size(); i++) {
                            var old = expected.forms().get(i);
                            var live = now.forms().get(i);
                            if (!editable.contains(old.formGuid()) && !old.equals(live)) throw stale();
                        }
                        for (int i = 0; i < expected.forms().size(); i++) {
                            var old = expected.forms().get(i);
                            if (!old.equals(now.forms().get(i)))
                                set(resolver, nativeForms.get(old.formGuid()), old.controlPoints());
                        }
                    },
                    () -> {
                        identityCheck.run();
                        return read.get().equals(expected);
                    });
            return null;
        });
    }

    static Map<String, List<Point2>> replacements(WarpAuthoringSnapshot before, List<WarpKeyformPositions> edits) {
        var cells = new HashMap<Map<String, Float>, Cell>();
        for (var cell : before.cells())
            if (cells.putIfAbsent(tuple(cell.coordinates()), cell) != null)
                throw new IllegalArgumentException("duplicate Warp cell tuple");
        var defaults = new HashMap<String, Float>();
        for (var axis : before.axes()) {
            if (!axis.keys().contains(axis.defaultValue())
                    || new HashSet<>(axis.keys()).size() != axis.keys().size())
                throw new IllegalArgumentException("ambiguous Warp keys or missing exact default");
            defaults.put(axis.parameterGuid(), axis.defaultValue());
        }
        var base = cells.get(defaults);
        if (base == null) throw new IllegalArgumentException("missing exact default Warp cell");
        if (before.cells().stream()
                        .filter(c -> c.formGuid().equals(base.formGuid()))
                        .count()
                != 1) throw new IllegalArgumentException("shared default Warp form");
        var editable = independentNonDefault(before);
        var result = new HashMap<String, List<Point2>>();
        for (var edit : List.copyOf(edits)) {
            var cell = cells.get(tuple(edit.coordinates()));
            if (cell == null
                    || !cell.formGuid().equals(edit.formGuid())
                    || !editable.contains(edit.formGuid())
                    || edit.controlPoints().size() != ((long) before.rows() + 1) * ((long) before.columns() + 1)
                    || result.putIfAbsent(edit.formGuid(), edit.controlPoints()) != null)
                throw new IllegalArgumentException("missing/shared/default/mismatched Warp batch target");
        }
        return Map.copyOf(result);
    }

    private static java.util.Set<String> independentNonDefault(WarpAuthoringSnapshot snapshot) {
        var references = new HashMap<String, Integer>();
        var defaults = new HashMap<String, Float>();
        for (var axis : snapshot.axes()) defaults.put(axis.parameterGuid(), axis.defaultValue());
        for (var cell : snapshot.cells()) references.merge(cell.formGuid(), 1, Integer::sum);
        for (var cell : snapshot.cells())
            if (tuple(cell.coordinates()).equals(defaults)) references.remove(cell.formGuid());
        return references.entrySet().stream()
                .filter(e -> e.getValue() == 1)
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toSet());
    }

    private static Map<String, Float> tuple(List<Coordinate> coordinates) {
        var values = new HashMap<String, Float>();
        for (var coordinate : coordinates)
            if (values.putIfAbsent(coordinate.parameterGuid(), coordinate.value()) != null)
                throw new IllegalArgumentException("duplicate Warp coordinate dimension");
        return Map.copyOf(values);
    }

    private static Map<String, Object> forms(VerifiedMemberResolver resolver, Object source) {
        Object raw = resolver.invoke("cubism.editor-model.warp-source.keyforms", source);
        if (!(raw instanceof List<?> list)) throw stale();
        var result = new HashMap<String, Object>();
        for (Object form : list) {
            Object value = resolver.invoke(
                    "cubism.editor-model.form-guid.value",
                    resolver.invoke("cubism.editor-history.semantic.form.guid", form));
            if (!(value instanceof String guid) || result.putIfAbsent(guid, form) != null) throw stale();
        }
        return result;
    }

    private static void set(VerifiedMemberResolver resolver, Object form, List<Point2> points) {
        var values = new float[points.size() * 2];
        for (int i = 0; i < points.size(); i++) {
            values[2 * i] = points.get(i).x();
            values[2 * i + 1] = points.get(i).y();
        }
        resolver.invoke("cubism.editor-model.warp-form.set-positions", form, values);
    }

    private static boolean sameLayout(WarpAuthoringSnapshot a, WarpAuthoringSnapshot b) {
        return a.deformerId().equals(b.deformerId())
                && a.sourceGuid().equals(b.sourceGuid())
                && a.rows() == b.rows()
                && a.columns() == b.columns()
                && a.quadTransform() == b.quadTransform()
                && a.axes().equals(b.axes())
                && a.cells().equals(b.cells())
                && a.forms().stream()
                        .map(Form::formGuid)
                        .toList()
                        .equals(b.forms().stream().map(Form::formGuid).toList());
    }

    private static IllegalStateException stale() {
        return new IllegalStateException("stored Warp source/form/layout changed");
    }
}

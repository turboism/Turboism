package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorRotationAuthoringSelectorContract;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.model.RotationAuthoringSnapshot;
import dev.turboism.sdk.cubism.model.RotationAuthoringSnapshot.Cell;
import dev.turboism.sdk.cubism.model.RotationAuthoringSnapshot.Coordinate;
import dev.turboism.sdk.cubism.model.RotationAuthoringSnapshot.Form;
import dev.turboism.sdk.cubism.model.RotationDeformerForm;
import dev.turboism.sdk.cubism.model.RotationKeyformForm;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** One compare-and-set operation on independently stored non-default Rotation forms. */
final class EditorRotationKeyformAccess {
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

    private EditorRotationKeyformAccess() {}

    static void writeBatch(
            VerifiedMemberResolver resolver,
            DeformerId id,
            Supplier<Object> currentSource,
            RotationAuthoringSnapshot expected,
            List<RotationKeyformForm> edits,
            Runnable requireTransaction,
            Writer writer) {
        Objects.requireNonNull(expected, "expected");
        var input = List.copyOf(edits);
        if (input.size() > RotationAuthoringSnapshot.MAX_FORMS)
            throw new IllegalArgumentException("Rotation batch target/control budget exceeded");
        EditorHostThread.dispatch("Rotation stored keyform batch", () -> {
            if (!EditorReviewedGeneration.admits(resolver)
                    || !resolver.authorizesFeature(
                            EditorRotationAuthoringSelectorContract.ADAPTER_SLICE_ID,
                            EditorRotationAuthoringSelectorContract.WRITE_CAPABILITY_ID,
                            EditorRotationAuthoringSelectorContract.WRITE_ALIASES))
                throw new UnsupportedOperationException("verified Rotation stored writes unavailable");
            requireTransaction.run();
            Object source = currentSource.get();
            Supplier<RotationAuthoringSnapshot> read = () -> {
                if (currentSource.get() != source) throw stale();
                return new EditorRotationAuthoringReadAccess(resolver).read(id, source);
            };
            if (!read.get().equals(expected)) throw stale();
            var replacements = replacements(expected, input);
            var after = new RotationAuthoringSnapshot(
                    expected.deformerId(),
                    expected.sourceGuid(),
                    expected.baseAngle(),
                    expected.axes(),
                    expected.cells(),
                    expected.forms().stream()
                            .map(f -> new Form(f.formGuid(), replacements.getOrDefault(f.formGuid(), f.value())))
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
                    "keyformFormsBatch",
                    "Set Rotation ordinary keyform forms",
                    () -> {
                        identityCheck.run();
                        if (!read.get().equals(expected)) throw stale();
                        for (var form : expected.forms()) {
                            var positions = replacements.get(form.formGuid());
                            if (positions != null && !positions.equals(form.value()))
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
                                set(resolver, nativeForms.get(old.formGuid()), old.value());
                        }
                    },
                    () -> {
                        identityCheck.run();
                        return read.get().equals(expected);
                    });
            return null;
        });
    }

    static Map<String, RotationDeformerForm> replacements(
            RotationAuthoringSnapshot before, List<RotationKeyformForm> edits) {
        var cells = new HashMap<Map<String, Float>, Cell>();
        for (var cell : before.cells())
            if (cells.putIfAbsent(tuple(cell.coordinates()), cell) != null)
                throw new IllegalArgumentException("duplicate Rotation cell tuple");
        var defaults = new HashMap<String, Float>();
        for (var axis : before.axes()) {
            if (!axis.keys().contains(axis.defaultValue())
                    || new HashSet<>(axis.keys()).size() != axis.keys().size())
                throw new IllegalArgumentException("ambiguous Rotation keys or missing exact default");
            defaults.put(axis.parameterGuid(), axis.defaultValue());
        }
        var base = cells.get(defaults);
        if (base == null) throw new IllegalArgumentException("missing exact default Rotation cell");
        if (before.cells().stream()
                        .filter(c -> c.formGuid().equals(base.formGuid()))
                        .count()
                != 1) throw new IllegalArgumentException("shared default Rotation form");
        var editable = independentNonDefault(before);
        var result = new HashMap<String, RotationDeformerForm>();
        for (var edit : List.copyOf(edits)) {
            var cell = cells.get(tuple(edit.coordinates()));
            if (cell == null
                    || !cell.formGuid().equals(edit.formGuid())
                    || !editable.contains(edit.formGuid())
                    || result.putIfAbsent(edit.formGuid(), edit.value()) != null)
                throw new IllegalArgumentException("missing/shared/default/mismatched Rotation batch target");
        }
        return Map.copyOf(result);
    }

    private static java.util.Set<String> independentNonDefault(RotationAuthoringSnapshot snapshot) {
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
                throw new IllegalArgumentException("duplicate Rotation coordinate dimension");
        return Map.copyOf(values);
    }

    private static Map<String, Object> forms(VerifiedMemberResolver resolver, Object source) {
        Object raw = resolver.invoke("cubism.editor-model.rotation-source.keyforms", source);
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

    private static void set(VerifiedMemberResolver resolver, Object form, RotationDeformerForm value) {
        resolver.invoke("cubism.editor-model.rotation-form.set-angle", form, value.angle());
        resolver.invoke("cubism.editor-model.rotation-form.set-origin-x", form, value.originX());
        resolver.invoke("cubism.editor-model.rotation-form.set-origin-y", form, value.originY());
        resolver.invoke("cubism.editor-model.rotation-form.set-scale", form, value.scale());
        resolver.invoke("cubism.editor-model.rotation-form.set-reflect-x", form, value.reflectedX());
        resolver.invoke("cubism.editor-model.rotation-form.set-reflect-y", form, value.reflectedY());
    }

    private static boolean sameLayout(RotationAuthoringSnapshot a, RotationAuthoringSnapshot b) {
        return a.deformerId().equals(b.deformerId())
                && a.sourceGuid().equals(b.sourceGuid())
                && a.baseAngle() == b.baseAngle()
                && a.axes().equals(b.axes())
                && a.cells().equals(b.cells())
                && a.forms().stream()
                        .map(Form::formGuid)
                        .toList()
                        .equals(b.forms().stream().map(Form::formGuid).toList());
    }

    private static IllegalStateException stale() {
        return new IllegalStateException("stored Rotation source/form/layout changed");
    }
}

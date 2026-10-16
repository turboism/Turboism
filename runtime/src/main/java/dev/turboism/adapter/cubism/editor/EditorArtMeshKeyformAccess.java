package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorArtMeshKeyformSelectorContract;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot;
import dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.Cell;
import dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.Coordinate;
import dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.Form;
import dev.turboism.sdk.cubism.model.ArtMeshKeyform;
import dev.turboism.sdk.cubism.model.ArtMeshKeyformPositions;
import dev.turboism.sdk.cubism.model.Point2;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Stored-form targeting; native transaction ownership remains with EditorObjectReadAccess. */
final class EditorArtMeshKeyformAccess implements ArtMeshKeyform {
    @FunctionalInterface
    interface Writer {
        void write(
                Object form,
                String property,
                String label,
                Runnable mutation,
                BooleanSupplier applied,
                Runnable compensation,
                BooleanSupplier restored);

        default boolean ownsParentRecovery() {
            return false;
        }
    }

    private final VerifiedMemberResolver resolver;
    private final ArtMeshId id;
    private final Supplier<Object> currentSource;
    private final Object source;
    private final Object form;
    private final ArtMeshAuthoringSnapshot layout;
    private final Selection selection;
    private final Runnable requireTransaction;
    private final Writer writer;

    static ArtMeshKeyform open(
            final VerifiedMemberResolver resolver,
            final ArtMeshId id,
            final Supplier<Object> currentSource,
            final List<Coordinate> coordinates,
            final Runnable requireTransaction,
            final Writer writer) {
        final List<Coordinate> input = List.copyOf(coordinates);
        return EditorHostThread.dispatch(
                "ArtMesh ordinary keyform lookup",
                () -> new EditorArtMeshKeyformAccess(resolver, id, currentSource, input, requireTransaction, writer));
    }

    static void writeBatch(
            final VerifiedMemberResolver resolver,
            final ArtMeshId id,
            final Supplier<Object> currentSource,
            final ArtMeshAuthoringSnapshot expected,
            final List<ArtMeshKeyformPositions> edits,
            final Runnable requireTransaction,
            final Writer writer) {
        writeBatch(resolver, id, currentSource, expected, edits, Map.of(), requireTransaction, writer);
    }

    static void writeCombined(
            final VerifiedMemberResolver resolver,
            final ArtMeshId id,
            final Supplier<Object> currentSource,
            final ArtMeshAuthoringSnapshot expected,
            final List<ArtMeshKeyformPositions> positions,
            final List<dev.turboism.sdk.cubism.model.ArtMeshKeyformDrawOrder> drawOrders,
            final Runnable requireTransaction,
            final Writer writer) {
        final var input = List.copyOf(positions);
        final var orderInput = List.copyOf(drawOrders);
        if (input.size() > 4096 || input.size() != orderInput.size())
            throw new IllegalArgumentException("combined keyform targets must match within batch budget");
        final var targets = new HashMap<String, Map<String, Float>>();
        for (var edit : input)
            if (targets.putIfAbsent(edit.formGuid(), tuple(edit.coordinates())) != null)
                throw new IllegalArgumentException("duplicate combined keyform target");
        final var orders = new HashMap<String, Integer>();
        for (var edit : orderInput) {
            if (!tuple(edit.coordinates()).equals(targets.get(edit.formGuid()))
                    || orders.putIfAbsent(edit.formGuid(), edit.drawOrder()) != null)
                throw new IllegalArgumentException("combined keyform tuples and form GUIDs must match");
        }
        writeBatch(resolver, id, currentSource, expected, input, orders, requireTransaction, writer);
    }

    private static void writeBatch(
            final VerifiedMemberResolver resolver,
            final ArtMeshId id,
            final Supplier<Object> currentSource,
            final ArtMeshAuthoringSnapshot expected,
            final List<ArtMeshKeyformPositions> edits,
            final Map<String, Integer> orders,
            final Runnable requireTransaction,
            final Writer writer) {
        Objects.requireNonNull(expected, "expected");
        final var input = List.copyOf(edits);
        if (input.size() > 4096
                || input.stream().mapToLong(e -> e.positions().size()).sum() > 2_000_000) {
            throw new IllegalArgumentException("keyform batch exceeds target/vertex budget");
        }
        EditorHostThread.dispatch("ArtMesh ordinary keyform position batch", () -> {
            requireWriteAuthorized(resolver);
            requireTransaction.run();
            final Object source = currentSource.get();
            if (!new EditorArtMeshAuthoringReadAccess(resolver).read(id, source).equals(expected)) throw stale();
            final var defaults = expected.axes().stream()
                    .map(a -> new Coordinate(a.parameterGuid(), a.defaultValue()))
                    .toList();
            final var access =
                    new EditorArtMeshKeyformAccess(resolver, id, currentSource, defaults, requireTransaction, writer);
            if (access.source != source || !access.layout.equals(expected)) throw stale();
            access.writeBatch(input, orders, !orders.isEmpty());
            return null;
        });
    }

    static void writeOrders(
            final VerifiedMemberResolver resolver,
            final ArtMeshId id,
            final Supplier<Object> currentSource,
            final ArtMeshAuthoringSnapshot expected,
            final List<dev.turboism.sdk.cubism.model.ArtMeshKeyformDrawOrder> edits,
            final Runnable requireTransaction,
            final Writer writer) {
        Objects.requireNonNull(expected, "expected");
        final var input = List.copyOf(edits);
        if (input.size() > 4096
                || (long) input.size() * expected.sourceGeometry().positions().size() > 2_000_000)
            throw new IllegalArgumentException("keyform batch exceeds target/vertex budget");
        EditorHostThread.dispatch("ArtMesh ordinary draw order batch", () -> {
            requireWriteAuthorized(resolver);
            requireTransaction.run();
            final Object source = currentSource.get();
            if (!new EditorArtMeshAuthoringReadAccess(resolver).read(id, source).equals(expected)) throw stale();
            final var defaults = expected.axes().stream()
                    .map(a -> new Coordinate(a.parameterGuid(), a.defaultValue()))
                    .toList();
            final var access =
                    new EditorArtMeshKeyformAccess(resolver, id, currentSource, defaults, requireTransaction, writer);
            if (access.source != source || !access.layout.equals(expected)) throw stale();
            final var orders = new HashMap<String, Integer>();
            final var forms = new HashMap<String, Form>();
            expected.forms().forEach(f -> forms.put(f.formGuid(), f));
            final var positions = new java.util.ArrayList<ArtMeshKeyformPositions>();
            for (var edit : input) {
                final var before = forms.get(edit.formGuid());
                if (before == null || orders.putIfAbsent(edit.formGuid(), edit.drawOrder()) != null)
                    throw new IllegalArgumentException("missing or duplicate draw order target");
                positions.add(new ArtMeshKeyformPositions(edit.coordinates(), edit.formGuid(), before.positions()));
            }
            access.writeBatch(positions, orders);
            return null;
        });
    }

    private void writeBatch(final List<ArtMeshKeyformPositions> edits) {
        writeBatch(edits, Map.of());
    }

    private void writeBatch(final List<ArtMeshKeyformPositions> edits, final Map<String, Integer> orders) {
        writeBatch(edits, orders, false);
    }

    private void writeBatch(
            final List<ArtMeshKeyformPositions> edits, final Map<String, Integer> orders, final boolean combined) {
        // Build the tuple/reference index once: no per-target host snapshot or grid scan.
        final var cells = new HashMap<Map<String, Float>, Cell>();
        final var references = new HashMap<String, Integer>();
        for (var cell : layout.cells()) {
            if (cells.put(tuple(cell.coordinates()), cell) != null)
                throw new IllegalArgumentException("duplicate grid tuple");
            references.merge(cell.formGuid(), 1, Integer::sum);
        }
        layout.morphKeys().forEach(k -> references.merge(k.formGuid(), 1, Integer::sum));
        final var replacements = new HashMap<String, List<Point2>>();
        for (var edit : edits) {
            final var coordinates = tuple(edit.coordinates());
            if (coordinates.size() != layout.axes().size()
                    || layout.axes().stream()
                            .anyMatch(a -> !coordinates.containsKey(a.parameterGuid())
                                    || !a.keys().contains(coordinates.get(a.parameterGuid())))) {
                throw new IllegalArgumentException("batch requires every declared parameter/key");
            }
            final var cell = cells.get(coordinates);
            if (cell == null
                    || !cell.formGuid().equals(edit.formGuid())
                    || !Integer.valueOf(1).equals(references.get(edit.formGuid()))) {
                throw new IllegalArgumentException("missing, mismatched or shared batch target");
            }
            if (selection.defaultFormGuid().equals(edit.formGuid())) {
                throw new UnsupportedOperationException("default keyform position editing is not available");
            }
            if (edit.positions().size() != layout.sourceGeometry().positions().size()
                    || replacements.putIfAbsent(edit.formGuid(), edit.positions()) != null) {
                throw new IllegalArgumentException("duplicate batch target or wrong vertex count");
            }
        }
        final var expected = new ArtMeshAuthoringSnapshot(
                layout.artMeshId(),
                layout.sourceGuid(),
                layout.sourceGeometry(),
                layout.axes(),
                layout.cells(),
                layout.forms().stream()
                        .map(f -> new Form(
                                f.formGuid(),
                                replacements.getOrDefault(f.formGuid(), f.positions()),
                                orders.getOrDefault(f.formGuid(), f.drawOrder())))
                        .toList(),
                layout.morphKeys());
        if (expected.equals(layout)) return;
        final Map<String, Object> nativeForms = nativeForms();
        if (!nativeForms
                .keySet()
                .equals(layout.forms().stream().map(Form::formGuid).collect(java.util.stream.Collectors.toSet()))) {
            throw stale();
        }
        writer.write(
                form,
                combined ? "keyformValuesBatch" : orders.isEmpty() ? "keyformPositionsBatch" : "keyformDrawOrdersBatch",
                combined
                        ? "Set ArtMesh ordinary keyform values"
                        : orders.isEmpty()
                                ? "Set ArtMesh ordinary keyform positions"
                                : "Set ArtMesh ordinary keyform draw orders",
                () -> {
                    if (!read().equals(layout)) throw stale();
                    requireFormIdentities(nativeForms);
                    for (var old : layout.forms()) {
                        final var positions = replacements.get(old.formGuid());
                        if (positions != null && !positions.equals(old.positions()))
                            setNativePositions(nativeForms.get(old.formGuid()), positions);
                        final var order = orders.get(old.formGuid());
                        if (order != null && order != old.drawOrder())
                            setNativeDrawOrder(nativeForms.get(old.formGuid()), order);
                    }
                },
                () -> {
                    requireFormIdentities(nativeForms);
                    return read().equals(expected);
                },
                () -> {
                    requireFormIdentities(nativeForms);
                    restoreSnapshot(layout);
                },
                () -> read().equals(layout));
    }

    private Map<String, Object> nativeForms() {
        if (currentSource.get() != source) throw stale();
        final Object raw = resolver.invoke("cubism.editor-model.art-mesh-source.keyforms", source);
        if (!(raw instanceof List<?> forms)) throw stale();
        final var result = new HashMap<String, Object>();
        for (var candidate : forms) {
            final Object guid = resolver.invoke("cubism.editor-history.semantic.form.guid", candidate);
            final Object value = resolver.invoke("cubism.editor-model.form-guid.value", guid);
            if (!(value instanceof String text) || result.putIfAbsent(text, candidate) != null) throw stale();
        }
        return result;
    }

    private void requireFormIdentities(final Map<String, Object> expected) {
        final var current = nativeForms();
        if (!current.keySet().equals(expected.keySet())
                || expected.entrySet().stream().anyMatch(e -> current.get(e.getKey()) != e.getValue())) {
            throw stale();
        }
    }

    private EditorArtMeshKeyformAccess(
            final VerifiedMemberResolver resolver,
            final ArtMeshId id,
            final Supplier<Object> currentSource,
            final List<Coordinate> coordinates,
            final Runnable requireTransaction,
            final Writer writer) {
        this.resolver = Objects.requireNonNull(resolver);
        this.id = Objects.requireNonNull(id);
        this.currentSource = Objects.requireNonNull(currentSource);
        this.source = Objects.requireNonNull(currentSource.get());
        this.requireTransaction = Objects.requireNonNull(requireTransaction);
        this.writer = Objects.requireNonNull(writer);
        this.layout = read();
        this.selection = select(layout, coordinates);
        this.form = nativeForm(selection.formGuid());
    }

    @Override
    public List<Coordinate> coordinates() {
        return EditorHostThread.dispatch("ArtMesh keyform coordinates", () -> {
            checked();
            return selection.coordinates();
        });
    }

    @Override
    public Form snapshot() {
        return EditorHostThread.dispatch("ArtMesh keyform snapshot", () -> target(checked()));
    }

    @Override
    public boolean defaultKeyform() {
        return EditorHostThread.dispatch("ArtMesh default keyform identity", () -> {
            checked();
            return selection.formGuid().equals(selection.defaultFormGuid());
        });
    }

    @Override
    public void setPositions(final List<Point2> positions) {
        final List<Point2> input = List.copyOf(positions);
        EditorHostThread.dispatch("ArtMesh stored keyform position edit", () -> {
            requireWriteAuthorized();
            requireTransaction.run();
            final ArtMeshAuthoringSnapshot before = checked();
            if (selection.formGuid().equals(selection.defaultFormGuid())) {
                throw new UnsupportedOperationException("default keyform position editing is not available");
            }
            final Form old = target(before);
            if (input.size() != old.positions().size()) {
                throw new IllegalArgumentException("keyform vertex count differs from source topology");
            }
            if (old.positions().equals(input)) return null;
            write(
                    before,
                    withPositions(before, selection.formGuid(), input),
                    "keyformPositions",
                    "Set ArtMesh keyform positions",
                    () -> setNativePositions(form, input));
            return null;
        });
    }

    @Override
    public void setDrawOrder(final int drawOrder) {
        if (drawOrder < 0 || drawOrder > 1000) {
            throw new IllegalArgumentException("keyform draw order must be between 0 and 1000");
        }
        EditorHostThread.dispatch("ArtMesh stored keyform draw order edit", () -> {
            requireWriteAuthorized();
            requireTransaction.run();
            final ArtMeshAuthoringSnapshot before = checked();
            if (selection.formGuid().equals(selection.defaultFormGuid())) {
                throw new UnsupportedOperationException("default keyform draw order editing is not available");
            }
            if (target(before).drawOrder() == drawOrder) return null;
            final var forms = before.forms().stream()
                    .map(value -> value.formGuid().equals(selection.formGuid())
                            ? new Form(value.formGuid(), value.positions(), drawOrder)
                            : value)
                    .toList();
            final var expected = new ArtMeshAuthoringSnapshot(
                    before.artMeshId(),
                    before.sourceGuid(),
                    before.sourceGeometry(),
                    before.axes(),
                    before.cells(),
                    forms,
                    before.morphKeys());
            write(
                    before,
                    expected,
                    "drawOrder",
                    "Set ArtMesh keyform draw order",
                    () -> setNativeDrawOrder(form, drawOrder));
            return null;
        });
    }

    private void write(
            final ArtMeshAuthoringSnapshot before,
            final ArtMeshAuthoringSnapshot expected,
            final String property,
            final String label,
            final Runnable mutation) {
        writer.write(
                form,
                property,
                label + " [" + selection.formGuid() + "]",
                () -> {
                    checked();
                    mutation.run();
                },
                () -> read().equals(expected),
                () -> restoreSnapshot(before),
                () -> read().equals(before));
    }

    private void restoreSnapshot(final ArtMeshAuthoringSnapshot before) {
        final ArtMeshAuthoringSnapshot now = read();
        if (!sameLayout(before, now)) throw stale();
        final Map<String, Integer> references = new HashMap<>();
        before.cells().forEach(cell -> references.merge(cell.formGuid(), 1, Integer::sum));
        before.morphKeys().forEach(key -> references.remove(key.formGuid()));
        references.remove(selection.defaultFormGuid());
        // KEYFORM_EDIT snapshots every member. Restore its ordinary editable domain, not just
        // our last field: native abort may already have reverted earlier contributions. Reverse
        // compensation rebuilds each complete before image, ending at the root before image.
        // Never repair default/shared/morph/non-grid forms or uncertain structural side effects.
        for (int i = 0; i < before.forms().size(); i++) {
            final Form old = before.forms().get(i);
            if (!writer.ownsParentRecovery()
                    && !Integer.valueOf(1).equals(references.get(old.formGuid()))
                    && !old.equals(now.forms().get(i))) {
                return; // Complete restored() remains false; the coordinator reports recovery failure.
            }
        }
        for (int i = 0; i < before.forms().size(); i++) {
            final Form old = before.forms().get(i);
            final Form current = now.forms().get(i);
            if (!old.positions().equals(current.positions())) {
                setNativePositions(nativeForm(old.formGuid()), old.positions());
            }
            if (old.drawOrder() != current.drawOrder()) {
                setNativeDrawOrder(nativeForm(old.formGuid()), old.drawOrder());
            }
        }
    }

    private ArtMeshAuthoringSnapshot checked() {
        final ArtMeshAuthoringSnapshot now = read();
        if (!sameLayout(layout, now)
                || !selection.equals(select(now, selection.coordinates()))
                || nativeForm(selection.formGuid()) != form) throw stale();
        return now;
    }

    private ArtMeshAuthoringSnapshot read() {
        if (currentSource.get() != source) throw stale();
        return new EditorArtMeshAuthoringReadAccess(resolver).read(id, source);
    }

    private Object nativeForm(final String guid) {
        final Object raw = resolver.invoke("cubism.editor-model.art-mesh-source.keyforms", source);
        if (!(raw instanceof List<?> forms)) throw stale();
        Object found = null;
        for (final Object candidate : forms) {
            final Object nativeGuid = resolver.invoke("cubism.editor-history.semantic.form.guid", candidate);
            final Object value = resolver.invoke("cubism.editor-model.form-guid.value", nativeGuid);
            if (guid.equals(value)) {
                if (found != null) throw stale();
                found = candidate;
            }
        }
        if (found == null) throw stale();
        return found;
    }

    private void setNativePositions(final Object target, final List<Point2> positions) {
        final float[] values = new float[positions.size() * 2];
        for (int i = 0; i < positions.size(); i++) {
            values[i * 2] = positions.get(i).x();
            values[i * 2 + 1] = positions.get(i).y();
        }
        resolver.invoke("cubism.editor-model.art-mesh-form.set-positions", target, values);
    }

    private void setNativeDrawOrder(final Object target, final int drawOrder) {
        resolver.invoke("cubism.editor-model.drawable-form.set-draw-order", target, drawOrder);
    }

    private void requireWriteAuthorized() {
        requireWriteAuthorized(resolver);
    }

    private static void requireWriteAuthorized(final VerifiedMemberResolver resolver) {
        if (!EditorReviewedGeneration.admits(resolver)
                || !resolver.authorizesFeature(
                        EditorArtMeshKeyformSelectorContract.ADAPTER_SLICE_ID,
                        EditorArtMeshKeyformSelectorContract.CAPABILITY_ID,
                        EditorArtMeshKeyformSelectorContract.REQUIRED_ALIASES)) {
            throw new UnsupportedOperationException("verified stored keyform editing is unavailable");
        }
    }

    private Form target(final ArtMeshAuthoringSnapshot snapshot) {
        return snapshot.forms().stream()
                .filter(value -> value.formGuid().equals(selection.formGuid()))
                .findFirst()
                .orElseThrow(EditorArtMeshKeyformAccess::stale);
    }

    static Selection select(final ArtMeshAuthoringSnapshot snapshot, final List<Coordinate> coordinates) {
        final Map<String, Float> requested = tuple(coordinates);
        final Map<String, Float> defaults = new HashMap<>();
        if (requested.size() != snapshot.axes().size()) {
            throw new IllegalArgumentException("ordinary keyform requires every bound dimension");
        }
        for (final var axis : snapshot.axes()) {
            if (new HashSet<>(axis.keys()).size() != axis.keys().size()) {
                throw new IllegalArgumentException("ambiguous declared keys");
            }
            final Float key = requested.get(axis.parameterGuid());
            if (key == null || !axis.keys().contains(key)) {
                throw new IllegalArgumentException("ordinary coordinate is not a declared key");
            }
            if (!axis.keys().contains(axis.defaultValue())) {
                throw new IllegalArgumentException("declared default is not an exact key");
            }
            defaults.put(axis.parameterGuid(), axis.defaultValue());
        }
        final Map<Map<String, Float>, Cell> cells = new HashMap<>();
        final Map<String, Integer> references = new HashMap<>();
        for (final Cell cell : snapshot.cells()) {
            if (cells.put(tuple(cell.coordinates()), cell) != null) {
                throw new IllegalArgumentException("duplicate ordinary keyform tuple");
            }
            references.merge(cell.formGuid(), 1, Integer::sum);
        }
        snapshot.morphKeys().forEach(key -> references.merge(key.formGuid(), 1, Integer::sum));
        final Cell base = cells.get(defaults);
        if (base == null) throw new IllegalArgumentException("exact default cell is missing");
        final Cell target = cells.get(requested);
        if (target == null) throw new NoSuchElementException("ordinary keyform cell is missing");
        if (references.get(target.formGuid()) != 1 || references.get(base.formGuid()) != 1) {
            throw new IllegalArgumentException("ordinary keyform aliases another cell or morph target");
        }
        return new Selection(
                coordinates.stream()
                        .sorted(Comparator.comparing(Coordinate::parameterGuid))
                        .toList(),
                target.formGuid(),
                base.formGuid());
    }

    private static Map<String, Float> tuple(final List<Coordinate> coordinates) {
        final Map<String, Float> result = new HashMap<>();
        for (final Coordinate coordinate : List.copyOf(coordinates)) {
            if (result.put(coordinate.parameterGuid(), coordinate.value()) != null) {
                throw new IllegalArgumentException("duplicate ordinary coordinate dimension");
            }
        }
        return Map.copyOf(result);
    }

    static boolean sameLayout(final ArtMeshAuthoringSnapshot before, final ArtMeshAuthoringSnapshot after) {
        return before.artMeshId().equals(after.artMeshId())
                && before.sourceGuid().equals(after.sourceGuid())
                && before.sourceGeometry().equals(after.sourceGeometry())
                && before.axes().equals(after.axes())
                && before.cells().equals(after.cells())
                && before.morphKeys().equals(after.morphKeys())
                && before.forms().stream()
                        .map(Form::formGuid)
                        .toList()
                        .equals(after.forms().stream().map(Form::formGuid).toList());
    }

    private static ArtMeshAuthoringSnapshot withPositions(
            final ArtMeshAuthoringSnapshot snapshot, final String guid, final List<Point2> positions) {
        return new ArtMeshAuthoringSnapshot(
                snapshot.artMeshId(),
                snapshot.sourceGuid(),
                snapshot.sourceGeometry(),
                snapshot.axes(),
                snapshot.cells(),
                snapshot.forms().stream()
                        .map(form -> form.formGuid().equals(guid) ? new Form(guid, positions, form.drawOrder()) : form)
                        .toList(),
                snapshot.morphKeys());
    }

    private static IllegalStateException stale() {
        return new IllegalStateException("stored ArtMesh keyform reference or layout is stale");
    }

    record Selection(List<Coordinate> coordinates, String formGuid, String defaultFormGuid) {}
}

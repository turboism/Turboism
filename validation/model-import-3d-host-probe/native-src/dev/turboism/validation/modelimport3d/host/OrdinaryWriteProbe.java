package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot;
import dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.Coordinate;
import dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.Form;
import dev.turboism.sdk.cubism.model.ArtMeshKeyform;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOptions;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOutcome;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionResult;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;

/** N3a: stored-form/history evidence only. Never treats current-keyform geometry as evaluated output. */
final class OrdinaryWriteProbe {
    static final List<Point2> A = List.of(new Point2(48, 40), new Point2(304, 40),
        new Point2(304, 168), new Point2(48, 168));
    static final List<Point2> B = List.of(new Point2(-4, 64), new Point2(252, 64),
        new Point2(252, 192), new Point2(-4, 192));
    private final Supplier<CubismModel> current;
    private final Supplier<HistorySnapshot> history;
    private final ArtMeshId id;
    private final ParameterId x, y;
    private final Map<String, Object> data;
    private final Map<String, Object> states = new LinkedHashMap<>();
    private final List<Map<String, Object>> checks = new ArrayList<>();

    private OrdinaryWriteProbe(Supplier<CubismModel> current, Supplier<HistorySnapshot> history,
            ArtMeshId id, ParameterId x, ParameterId y, Map<String, Object> data) {
        this.current = current; this.history = history; this.id = id; this.x = x; this.y = y; this.data = data;
        data.put("status", "RUNNING"); data.put("checks", checks); data.put("states", states);
    }

    static void run(Supplier<CubismModel> current, Supplier<HistorySnapshot> history, IntConsumer restore,
            AuthoringTransactionService transactions, ArtMeshId id, ParameterId x, ParameterId y,
            Map<String, Object> data) {
        var probe = new OrdinaryWriteProbe(current, history, id, x, y, data);
        try {
            NativeAssertions.require(SwingUtilities.isEventDispatchThread(), "N3a requires EDT");
            probe.matrix(transactions, restore);
            data.put("status", "PASS");
        } catch (RuntimeException | Error failure) {
            data.put("status", "FAIL"); data.put("failure", failure.toString()); throw failure;
        }
        // HostProbe's outer finally owns task restoration even if this slice fails halfway.
    }

    private void matrix(AuthoringTransactionService transactions, IntConsumer restore) {
        var before = snapshot(); NativeAssertions.grid(before, x.value(), y.value());
        var h0 = history.get(); state("before", before, h0);
        var ca = coordinates(before, x.value(), y.value(), 1);
        var cb = coordinates(before, x.value(), y.value(), -1);
        var cd = coordinates(before, x.value(), y.value(), 0);
        String ga = formGuid(before, ca), gb = formGuid(before, cb), gd = formGuid(before, cd);
        var a = target(ca); var b = target(cb); var d = target(cd);
        data.put("targets", List.of(targetData(ca, ga), targetData(cb, gb), targetData(cd, gd)));
        check("targetIdentity", !a.defaultKeyform() && !b.defaultKeyform() && d.defaultKeyform()
            && a.snapshot().formGuid().equals(ga) && b.snapshot().formGuid().equals(gb)
            && d.snapshot().formGuid().equals(gd) && a.coordinates().equals(sorted(ca))
            && b.coordinates().equals(sorted(cb)), "complete GUID targets match independently read cells");
        check("valuesAtDefault", parameters().equals(List.of(0f, 0f)), "writes begin with sliders at (0,0)");

        var written = transactions.execute(AuthoringTransactionOptions.of("MI3D N3a two targets"), () -> {
            target(ca).setPositions(A); target(cb).setPositions(B); return null;
        });
        result("write", written); var h1 = history.get(); var s1 = snapshot(); state("written", s1, h1);
        check("writeCommitted", written.outcome() == AuthoringTransactionOutcome.COMMITTED,
            "two target writes commit in one native transaction");
        check("singleUndo", h1.position() == h0.position() + 1, "two writes add exactly one history position");
        var expected = changed(before, ga, A, gb, B);
        check("writeIsolated", expected.equals(s1), "only two target position arrays change");

        var noop = transactions.execute(AuthoringTransactionOptions.of("MI3D N3a no change"), () -> {
            target(ca).setPositions(A); target(cb).setPositions(B); return null;
        });
        result("noop", noop); state("noop", snapshot(), history.get());
        check("noop", noop.outcome() == AuthoringTransactionOutcome.NO_CHANGE
            && expected.equals(snapshot()) && h1.equals(history.get()), "same values leave full state/history unchanged");
        restore.accept(h0.position()); state("undone", snapshot(), history.get());
        check("writeUndo", before.equals(snapshot()), "one Undo restores all stored forms and source");
        restore.accept(h1.position()); state("redone", snapshot(), history.get());
        check("writeRedo", expected.equals(snapshot()), "one Redo restores full written snapshot");

        var rollbackBefore = snapshot(); var rollbackHistory = history.get();
        state("rollbackBefore", rollbackBefore, rollbackHistory);
        boolean[] sentinel = {false};
        var rolledBack = transactions.execute(AuthoringTransactionOptions.of("MI3D N3a callback failure"), () -> {
            target(ca).setPositions(B); target(cb).setPositions(A);
            var mutated = snapshot(); data.put("rollbackMutated", NativeAssertions.snapshot(mutated));
            NativeAssertions.require(changed(before, ga, B, gb, A).equals(mutated), "callback writes not observed");
            sentinel[0] = true; data.put("callbackFailure", "MI3D_N3A_EXPECTED_ABORT");
            throw new IllegalArgumentException("MI3D_N3A_EXPECTED_ABORT");
        });
        result("rollback", rolledBack); state("rollbackAfter", snapshot(), history.get());
        check("callbackRolledBack", sentinel[0] && rolledBack.outcome() == AuthoringTransactionOutcome.ROLLED_BACK,
            "deliberate callback failure produces typed ROLLED_BACK");
        check("rollbackState", rollbackBefore.equals(snapshot()) && rollbackHistory.equals(history.get()),
            "independent whole-root snapshot/history recovered");

        var rejectedBefore = snapshot(); var rejectedHistory = history.get();
        state("rejectionsBefore", rejectedBefore, rejectedHistory);
        rejection("defaultRejected", UnsupportedOperationException.class, () -> target(cd).setPositions(A));
        rejection("incompleteRejected", IllegalArgumentException.class, () -> target(ca.subList(0, 1)));
        rejection("topologyRejected", IllegalArgumentException.class, () -> target(ca).setPositions(A.subList(0, 3)));
        state("rejectionsAfter", snapshot(), history.get());
        check("rejectionsUnchanged", rejectedBefore.equals(snapshot()) && rejectedHistory.equals(history.get()),
            "all rejected requests leave full state/history unchanged");
        restore.accept(h0.position()); state("restored", snapshot(), history.get());
        check("restored", before.equals(snapshot()) && history.get().position() == h0.position(),
            "N3a restores its starting stored state and history position");
        check("valuesUnchanged", parameters().equals(List.of(0f, 0f)), "no slider write was used to select a target");
    }

    private ArtMeshAuthoringSnapshot snapshot() { return current.get().drawables().find(id).authoringSnapshot(); }
    private ArtMeshKeyform target(List<Coordinate> c) { return current.get().drawables().find(id).ordinaryKeyform(c); }
    private List<Float> parameters() {
        return List.of(current.get().parameters().find(x).getValue(), current.get().parameters().find(y).getValue());
    }
    private void state(String name, ArtMeshAuthoringSnapshot s, HistorySnapshot h) {
        states.put(name, Map.of("snapshot", NativeAssertions.snapshot(s), "history", historyData(h), "parameters", parameters()));
    }
    private void check(String id, boolean actual, String expected) {
        NativeAssertions.require(checks.stream().noneMatch(c -> id.equals(c.get("id"))), "duplicate N3a check");
        checks.add(Map.of("id", id, "status", actual ? "PASS" : "FAIL", "actual", actual,
            "expected", expected, "evidenceKind", "EXACT_HOST"));
        NativeAssertions.require(actual, "N3a " + id + ": " + expected);
    }
    private void rejection(String id, Class<? extends RuntimeException> type, Runnable operation) {
        String actual = "NOT_REJECTED";
        try { operation.run(); } catch (RuntimeException failure) { actual = failure.getClass().getName(); }
        data.put(id, actual);
        check(id, actual.equals(type.getName()), "rejected with " + type.getName());
    }
    private void result(String name, AuthoringTransactionResult<?> result) {
        var row = new LinkedHashMap<String, Object>(); row.put("outcome", result.outcome().name());
        row.put("diagnosticId", result.diagnosticId().orElse(null));
        row.put("receipt", result.receipt().map(r -> {
            var receipt = new LinkedHashMap<String, Object>(); receipt.put("transactionId", r.transactionId());
            receipt.put("label", r.label()); receipt.put("historyBefore", historyData(r.historyBefore()));
            receipt.put("historyAfter", historyData(r.historyAfter())); receipt.put("historyEntryId", r.historyEntryId().orElse(null));
            return receipt;
        }).orElse(null)); data.put(name, row);
    }
    static List<Coordinate> coordinates(ArtMeshAuthoringSnapshot s, String x, String y, float value) {
        String gx = axis(s, x), gy = axis(s, y);
        NativeAssertions.require(!gx.equals(gy), "duplicate axis");
        // Deliberately Y then X; callers may not depend on the source binding order.
        return List.of(new Coordinate(gy, 0), new Coordinate(gx, value));
    }
    private static String axis(ArtMeshAuthoringSnapshot s, String id) {
        var found = s.axes().stream().filter(a -> a.parameterId().value().equals(id)).toList();
        NativeAssertions.require(found.size() == 1, "axis identity missing/ambiguous"); return found.get(0).parameterGuid();
    }
    static String formGuid(ArtMeshAuthoringSnapshot s, List<Coordinate> coordinates) {
        var matches = s.cells().stream().filter(c -> sorted(c.coordinates()).equals(sorted(coordinates))).toList();
        NativeAssertions.require(matches.size() == 1, "tuple missing/ambiguous"); String guid = matches.get(0).formGuid();
        NativeAssertions.require(s.cells().stream().filter(c -> c.formGuid().equals(guid)).count() == 1
            && s.morphKeys().stream().noneMatch(m -> m.formGuid().equals(guid)), "aliased target is not writable");
        return guid;
    }
    private static List<Coordinate> sorted(List<Coordinate> coordinates) {
        return coordinates.stream().sorted(Comparator.comparing(Coordinate::parameterGuid)).toList();
    }
    private static Map<String, Object> targetData(List<Coordinate> coordinates, String guid) {
        return Map.of("formGuid", guid, "coordinates", coordinates.stream()
            .map(c -> Map.of("guid", c.parameterGuid(), "value", c.value())).toList());
    }
    static ArtMeshAuthoringSnapshot changed(ArtMeshAuthoringSnapshot s, String ga, List<Point2> a,
            String gb, List<Point2> b) {
        NativeAssertions.require(!ga.equals(gb) && s.forms().stream().anyMatch(f -> f.formGuid().equals(ga))
            && s.forms().stream().anyMatch(f -> f.formGuid().equals(gb)), "two existing independent forms required");
        var forms = s.forms().stream().map(f -> new Form(f.formGuid(),
            f.formGuid().equals(ga) ? a : f.formGuid().equals(gb) ? b : f.positions(), f.drawOrder())).toList();
        return new ArtMeshAuthoringSnapshot(s.artMeshId(), s.sourceGuid(), s.sourceGeometry(), s.axes(), s.cells(), forms, s.morphKeys());
    }
    private static Map<String, Object> historyData(HistorySnapshot h) {
        return Map.of("availability", h.availability().name(), "generation", h.generation(), "revision", h.revision(),
            "position", h.position(), "documentBindingId", h.documentBindingId(), "managerBindingId", h.managerBindingId(),
            "entries", h.entries().stream().map(Object::toString).toList(),
            "entryIds", h.entries().stream().map(e -> e.entryId().map(i -> i.value()).orElse(null)).toList());
    }
}

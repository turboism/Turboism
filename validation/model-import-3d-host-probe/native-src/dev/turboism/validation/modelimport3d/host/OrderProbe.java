package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.command.EditorCanvasSettingsRequest;
import dev.turboism.sdk.cubism.command.EditorCommandResult;
import dev.turboism.sdk.cubism.command.EditorCommandService;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.id.ParameterBindingPointId;
import dev.turboism.sdk.cubism.model.*;
import dev.turboism.sdk.cubism.transaction.*;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/** SDK-only ordinary order matrix. Freshness comes from a consumed pass, never the expected order. */
final class OrderProbe {
    private static final ParameterId X = new ParameterId("ParamMI3DN2X"), Y = new ParameterId("ParamMI3DN2Y");
    private final Supplier<CubismModel> current;
    private final Supplier<HistorySnapshot> history;
    private final IntConsumer restore;
    private final Map<String, Object> data;
    private final List<Map<String, Object>> checks = new ArrayList<>(), samples = new ArrayList<>();
    private ArtMeshId id;
    private ArtMeshAuthoringSnapshot baseline, written;
    private HistorySnapshot writeBefore, writeAfter;

    private OrderProbe(Supplier<CubismModel> current, Supplier<HistorySnapshot> history,
            IntConsumer restore, Map<String, Object> data) {
        this.current = current; this.history = history; this.restore = restore; this.data = data;
        data.put("status", "RUNNING"); data.put("checks", checks); data.put("samples", samples);
    }

    static void run(Supplier<CubismModel> current, Supplier<HistorySnapshot> history, IntConsumer restore,
            EditorCommandService commands, AuthoringTransactionService transactions, Map<String, Object> data) throws Exception {
        NativeAssertions.require(!SwingUtilities.isEventDispatchThread(), "N3c wait must not block EDT");
        var p = new OrderProbe(current, history, restore, data);
        HistorySnapshot root = null; Map<String, Object> counts = null, canvas = null;
        Throwable primary = null;
        try {
            data.put("configuration", EvaluationProbe.configuration());
            root = edt(history::get); counts = edt(() -> counts(current.get())); canvas = edt(() -> canvas(current.get()));
            data.put("initialHistory", historyData(root)); data.put("initialCounts", counts); data.put("initialCanvas", canvas);
            edt(() -> { p.setup(commands); return null; });
            edt(() -> { p.nonCurrent(transactions); return null; });
            // Parameter value changes also enter history. Keep x=1 across the following order Undo/Redo.
            p.step("baseline", 1, 500, false, () -> p.setX(1));
            p.step("written", 1, 1000, true, () -> {
                p.writeBefore = history.get();
                var result = transactions.execute(AuthoringTransactionOptions.of("MI3D N3c order targets"), () -> {
                    p.target(1).setDrawOrder(1000); p.target(-1).setDrawOrder(0); return null;
                });
                p.writeAfter = history.get(); data.put("writeResult", resultData(result));
                data.put("writeBefore", historyData(p.writeBefore)); data.put("writeAfter", historyData(p.writeAfter));
                p.check("singleUndo", result.outcome() == AuthoringTransactionOutcome.COMMITTED
                    && p.writeAfter.position() == p.writeBefore.position() + 1);
            });
            p.step("undone", 1, 500, false, () -> restore.accept(p.writeBefore.position()));
            p.step("redone", 1, 1000, true, () -> restore.accept(p.writeAfter.position()));
            p.step("positiveMid", .5f, 750, true, () -> p.setX(.5f));
            p.step("defaultAfterPositive", 0, 500, true, () -> p.setX(0));
            p.step("negativeEnd", -1, 0, true, () -> p.setX(-1));
            p.step("negativeMid", -.5f, 250, true, () -> p.setX(-.5f));
            p.step("defaultFinal", 0, 500, true, () -> p.setX(0));
        } catch (Exception | Error failure) {
            primary = failure; data.put("status", "FAIL"); data.put("failure", failure.toString()); throw failure;
        } finally {
            if (root != null) {
                final int position = root.position();
                try {
                    edt(() -> { restore.accept(position); return null; });
                    var finalCounts = edt(() -> counts(current.get())); var finalCanvas = edt(() -> canvas(current.get()));
                    var h = edt(history::get);
                    data.put("restoredCounts", finalCounts); data.put("restoredCanvas", finalCanvas);
                    data.put("restoredHistory", historyData(h));
                    p.check("restored", counts.equals(finalCounts) && canvas.equals(finalCanvas) && h.position() == position);
                    data.put("finallyRestored", true);
                } catch (Exception | Error failure) {
                    data.put("status", "FAIL"); data.put("finallyRestored", false); data.put("recoveryFailure", failure.toString());
                    if (primary != null) primary.addSuppressed(failure); else throw failure;
                }
            }
        }
        data.put("status", "PASS"); data.put("failure", null);
    }

    private void setup(EditorCommandService commands) {
        current.get(); var result = commands.execute(new EditorCanvasSettingsRequest(1024, 1024));
        data.put("canvasCommand", result.status().name()); data.put("canvas", canvas(current.get()));
        check("canvas", result.status() == EditorCommandResult.Status.EXECUTED
            && current.get().canvas().widthPixels() == 1024 && current.get().canvas().heightPixels() == 1024);
        id = current.get().drawables().create("MI3D_N3C", null, -1, NativeAssertions.GEOMETRY).id();
        current.get().parameters().createMany(List.of(
            new ParameterDefinition(X, "MI3D N3c X", -1, 0, 1, ParameterType.NORMAL, false),
            new ParameterDefinition(Y, "MI3D N3c Y", -1, 0, 1, ParameterType.NORMAL, false)));
        for (var parameter : List.of(X, Y)) current.get().parameterBindings(parameter).bind(
            ParameterBindingTarget.artMesh(id), NativeAssertions.KEYS.stream()
                .map(v -> new ParameterBindingPoint(new ParameterBindingPointId("N3C_" + v), v)).toList());
        baseline = stored(); NativeAssertions.grid(baseline, X.value(), Y.value());
        written = changed(changed(baseline, guid(1), null, 1000), guid(-1), null, 0);
        data.put("baseline", NativeAssertions.snapshot(baseline)); data.put("expectedWritten", NativeAssertions.snapshot(written));
        data.put("fixtureHistory", historyData(history.get())); data.put("fixtureCounts", counts(current.get()));
        check("fixture", baseline.forms().size() == 9 && baseline.cells().stream().map(c -> c.formGuid()).distinct().count() == 9
            && baseline.forms().stream().allMatch(f -> f.drawOrder() == 500) && current.get().deformers().all().isEmpty());
    }

    private void nonCurrent(AuthoringTransactionService transactions) {
        var row = new LinkedHashMap<String, Object>(); data.put("nonCurrent", row);
        var before = history.get(); row.put("before", state());
        var result = transactions.execute(AuthoringTransactionOptions.of("MI3D N3c noncurrent targets"), () -> {
            target(1).setDrawOrder(1000); target(-1).setDrawOrder(0); return null;
        });
        var after = history.get(); row.put("result", resultData(result)); row.put("written", state());
        check("nonCurrentCommitted", result.outcome() == AuthoringTransactionOutcome.COMMITTED
            && after.position() == before.position() + 1 && stored().equals(written)
            && current.get().parameters().find(X).getValue() == 0 && current.get().parameters().find(Y).getValue() == 0);
        controls(transactions);
        restore.accept(before.position()); row.put("undone", state());
        NativeAssertions.require(stored().equals(baseline), "N3c noncurrent Undo");
        restore.accept(after.position()); row.put("redone", state());
        NativeAssertions.require(stored().equals(written), "N3c noncurrent Redo");
        restore.accept(before.position()); row.put("restored", state());
        check("nonCurrentUndoRedo", stored().equals(baseline) && history.get().position() == before.position());
    }

    private void controls(AuthoringTransactionService transactions) {
        var controls = new LinkedHashMap<String, Object>(); data.put("controls", controls);
        var before = state();
        var noop = transactions.execute(AuthoringTransactionOptions.of("MI3D N3c noop"), () -> {
            target(1).setDrawOrder(1000); target(-1).setDrawOrder(0); return null;
        });
        var after = state(); controls.put("noop", Map.of("before", before, "after", after, "result", resultData(noop)));
        check("noop", noop.outcome() == AuthoringTransactionOutcome.NO_CHANGE && before.equals(after));
        for (boolean orderFirst : List.of(false, true)) for (boolean same : List.of(false, true)) {
            String name = "rollback-" + orderFirst + "-" + same;
            var row = new LinkedHashMap<String, Object>(); controls.put(name, row); row.put("before", state());
            int[] steps = {0};
            var result = transactions.execute(AuthoringTransactionOptions.of("MI3D N3c " + name), () -> {
                if (orderFirst) target(1).setDrawOrder(600); else target(1).setPositions(OrdinaryWriteProbe.A);
                steps[0]++;
                if (orderFirst) target(same ? 1 : -1).setPositions(OrdinaryWriteProbe.A);
                else target(same ? 1 : -1).setDrawOrder(600);
                steps[0]++; row.put("mutated", state()); row.put("callbackFailure", "N3C_ABORT");
                throw new IllegalStateException("N3C_ABORT");
            });
            row.put("steps", steps[0]); row.put("result", resultData(result)); row.put("after", state());
            check(name, steps[0] == 2 && result.outcome() == AuthoringTransactionOutcome.ROLLED_BACK
                && row.get("before").equals(row.get("after")));
        }
        var triple = new LinkedHashMap<String, Object>(); controls.put("rollback-triple", triple); triple.put("before", state());
        int[] steps = {0};
        var repeated = transactions.execute(AuthoringTransactionOptions.of("MI3D N3c rollback-triple"), () -> {
            target(1).setDrawOrder(600); steps[0]++;
            target(1).setPositions(OrdinaryWriteProbe.A); steps[0]++;
            target(1).setDrawOrder(700); steps[0]++;
            triple.put("mutated", state()); triple.put("callbackFailure", "N3C_ABORT");
            throw new IllegalStateException("N3C_ABORT");
        });
        triple.put("steps", steps[0]); triple.put("result", resultData(repeated)); triple.put("after", state());
        check("rollback-triple", steps[0] == 3 && repeated.outcome() == AuthoringTransactionOutcome.ROLLED_BACK
            && triple.get("before").equals(triple.get("after")));
        var rejects = new LinkedHashMap<String, Object>(); data.put("rejections", rejects);
        for (int invalid : List.of(-1, 1001, Integer.MIN_VALUE, Integer.MAX_VALUE))
            reject(rejects, "range" + invalid, IllegalArgumentException.class, () -> target(1).setDrawOrder(invalid));
        reject(rejects, "default", UnsupportedOperationException.class, () -> target(0).setDrawOrder(600));
        reject(rejects, "defaultNoop", UnsupportedOperationException.class, () -> target(0).setDrawOrder(500));
        reject(rejects, "incomplete", IllegalArgumentException.class,
            () -> current.get().drawables().find(id).ordinaryKeyform(coordinates(1).subList(0, 1)).setDrawOrder(600));
    }

    private void reject(Map<String, Object> rejects, String name, Class<? extends RuntimeException> expected, Runnable action) {
        var before = state(); String actual = "NOT_REJECTED";
        try { action.run(); } catch (RuntimeException error) { actual = error.getClass().getName(); }
        var after = state(); rejects.put(name, Map.of("before", before, "after", after, "exception", actual));
        check(name, actual.equals(expected.getName()) && before.equals(after));
    }

    private void step(String name, float x, int order, boolean isWritten, Runnable action) throws Exception {
        var row = new LinkedHashMap<String, Object>(); samples.add(row);
        row.put("id", name); row.put("x", x); row.put("y", 0); row.put("expectedOrder", order);
        row.put("waitLimitMillis", EvaluationProbe.WAIT_MILLIS); row.put("status", "RUNNING");
        var expected = isWritten ? written : baseline;
        try {
            var requested = edt(() -> {
                current.get(); row.put("before", EvaluationProbe.evaluation(observe())); action.run();
                var h = history.get(); var s = stored(); var e = observe();
                NativeAssertions.require(s.equals(expected), "N3c stored pollution");
                NativeAssertions.require(current.get().parameters().find(X).getValue() == x
                    && current.get().parameters().find(Y).getValue() == 0, "N3c parameter intent");
                row.put("history", historyData(h)); row.put("stored", NativeAssertions.snapshot(s));
                row.put("requested", EvaluationProbe.evaluation(e)); return e;
            });
            var actual = EvaluationProbe.awaitConsumed(requested, () -> edt(() -> {
                var h0 = history.get(); var s = stored(); var e = observe(); var h1 = history.get();
                row.put("readHistoryBefore", historyData(h0)); row.put("readHistoryAfter", historyData(h1));
                row.put("observedStored", NativeAssertions.snapshot(s));
                NativeAssertions.require(h0.equals(h1) && historyData(h1).equals(row.get("history")) && s.equals(expected),
                    "N3c sampling changed history or stored model"); return e;
            }), row::putAll, System::nanoTime, Thread::sleep);
            row.put("actual", EvaluationProbe.evaluation(actual));
            row.put("maxErrorPixels", EvaluationProbe.maxError(actual, NativeAssertions.GEOMETRY.positions()));
            check(name, EvaluationProbe.validGeometry(actual, NativeAssertions.GEOMETRY.positions(), order));
            row.put("status", "PASS");
        } catch (Exception | Error error) {
            row.put("status", "FAIL"); row.put("failure", error.toString()); throw error;
        }
    }

    static ArtMeshAuthoringSnapshot changed(ArtMeshAuthoringSnapshot s, String guid, List<Point2> positions, Integer order) {
        NativeAssertions.require(order == null || order >= 0 && order <= 1000, "N3c invalid expected order");
        String base = OrdinaryWriteProbe.formGuid(s, OrdinaryWriteProbe.coordinates(s, X.value(), Y.value(), 0));
        NativeAssertions.require(!base.equals(guid) && s.forms().stream().filter(f -> f.formGuid().equals(guid)).count() == 1
            && s.cells().stream().filter(c -> c.formGuid().equals(guid)).count() == 1
            && s.morphKeys().stream().noneMatch(m -> m.formGuid().equals(guid)), "N3c ambiguous/default expected target");
        var forms = s.forms().stream().map(f -> f.formGuid().equals(guid)
            ? new ArtMeshAuthoringSnapshot.Form(guid, positions == null ? f.positions() : positions, order == null ? f.drawOrder() : order) : f).toList();
        return new ArtMeshAuthoringSnapshot(s.artMeshId(), s.sourceGuid(), s.sourceGeometry(), s.axes(), s.cells(), forms, s.morphKeys());
    }
    private void setX(float x) { current.get().parameters().find(X).setValue(x); }
    private ArtMeshAuthoringSnapshot stored() { return current.get().drawables().find(id).authoringSnapshot(); }
    private ArtMeshEvaluationSnapshot observe() { return current.get().drawables().find(id).evaluationSnapshot(); }
    private List<ArtMeshAuthoringSnapshot.Coordinate> coordinates(float x) {
        return OrdinaryWriteProbe.coordinates(baseline, X.value(), Y.value(), x);
    }
    private String guid(float x) { return OrdinaryWriteProbe.formGuid(baseline, coordinates(x)); }
    private ArtMeshKeyform target(float x) { return current.get().drawables().find(id).ordinaryKeyform(coordinates(x)); }
    private Map<String, Object> state() {
        current.get(); return Map.of("snapshot", NativeAssertions.snapshot(stored()), "history", historyData(history.get()),
            "parameters", List.of(current.get().parameters().find(X).getValue(), current.get().parameters().find(Y).getValue()));
    }
    private void check(String id, boolean actual) {
        NativeAssertions.require(checks.stream().noneMatch(c -> id.equals(c.get("id"))), "duplicate N3c check");
        checks.add(Map.of("id", id, "actual", actual, "status", actual ? "PASS" : "FAIL", "evidenceKind", "EXACT_HOST"));
        NativeAssertions.require(actual, "N3c " + id);
    }
    private static Map<String, Object> counts(CubismModel m) {
        return Map.of("meshes", m.drawables().all().size(), "deformers", m.deformers().all().size(),
            "parameters", m.parameters().all().size(), "parts", m.parts().all().size());
    }
    private static Map<String, Object> canvas(CubismModel m) {
        var c = m.canvas(); return Map.of("width", c.widthPixels(), "height", c.heightPixels(),
            "originX", c.originXPixels(), "originY", c.originYPixels(), "pixelsPerUnit", c.pixelsPerUnit());
    }
    private static Map<String, Object> historyData(HistorySnapshot h) {
        return Map.of("availability", h.availability().name(), "generation", h.generation(), "revision", h.revision(),
            "position", h.position(), "documentBindingId", h.documentBindingId(), "managerBindingId", h.managerBindingId(),
            "entries", h.entries().stream().map(Object::toString).toList(),
            "entryIds", h.entries().stream().map(e -> e.entryId().map(i -> i.value()).orElse(null)).toList());
    }
    private static Map<String, Object> resultData(AuthoringTransactionResult<?> result) {
        var row = new LinkedHashMap<String, Object>(); row.put("outcome", result.outcome().name());
        row.put("diagnosticId", result.diagnosticId().orElse(null));
        row.put("receipt", result.receipt().map(r -> {
            var receipt = new LinkedHashMap<String, Object>(); receipt.put("transactionId", r.transactionId());
            receipt.put("label", r.label()); receipt.put("historyBefore", historyData(r.historyBefore()));
            receipt.put("historyAfter", historyData(r.historyAfter())); receipt.put("historyEntryId", r.historyEntryId().orElse(null));
            return receipt;
        }).orElse(null)); return row;
    }
    private static <T> T edt(Callable<T> action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return action.call();
        var task = new FutureTask<T>(action); SwingUtilities.invokeAndWait(task); return task.get();
    }
}

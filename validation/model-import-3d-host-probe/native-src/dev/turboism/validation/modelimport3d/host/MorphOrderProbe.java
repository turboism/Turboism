package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.command.EditorCanvasSettingsRequest;
import dev.turboism.sdk.cubism.command.EditorCommandResult;
import dev.turboism.sdk.cubism.command.EditorCommandService;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ParameterBindingPointId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot;
import dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.Form;
import dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.MorphKey;
import dev.turboism.sdk.cubism.model.ArtMeshEvaluationSnapshot;
import dev.turboism.sdk.cubism.model.ArtMeshMorphKeyform;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.ParameterBindingPoint;
import dev.turboism.sdk.cubism.model.ParameterBindingTarget;
import dev.turboism.sdk.cubism.model.ParameterDefinition;
import dev.turboism.sdk.cubism.model.ParameterType;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOptions;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOutcome;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionResult;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;

/** SDK-only N4b morph draw-order probe: stored order, truncation, isolation and rollback. */
final class MorphOrderProbe {
    private static final ParameterId X = new ParameterId("ParamMI3DN4BX"), Y = new ParameterId("ParamMI3DN4BY");
    private static final ParameterId M = new ParameterId("ParamMI3DN4BM"), N = new ParameterId("ParamMI3DN4BN");
    // Frozen expected content; the independent Python oracle owns the same literal values.
    static final List<Point2> B = NativeAssertions.GEOMETRY.positions();
    static final List<Point2> V = MorphProbe.V;
    static final int WRITE_A = 1000, WRITE_B = 0, TRUNC = 701, SECOND = 299, MIDWRITE = 800;
    static final int[] TRIPLE_ORDERS = {700, 300, 900};
    private final Supplier<CubismModel> current;
    private final Supplier<HistorySnapshot> history;
    private final IntConsumer restore;
    private final Map<String, Object> data;
    private final List<Map<String, Object>> checks = new ArrayList<>(), samples = new ArrayList<>();
    private final HistoryBank bank = new HistoryBank();
    private ArtMeshId id;
    private String xGuid, yGuid, mGuid, nGuid;
    private String mForm, nForm, halfForm;
    private ArtMeshAuthoringSnapshot baseline, created, evalWritten, truncWritten, halfWritten, mixedWritten;
    private ArtMeshMorphKeyform staleMember;

    private MorphOrderProbe(Supplier<CubismModel> current, Supplier<HistorySnapshot> history,
            IntConsumer restore, Map<String, Object> data) {
        this.current = current; this.history = history; this.restore = restore; this.data = data;
        data.put("status", "RUNNING"); data.put("checks", checks); data.put("samples", samples);
    }

    /** Deduplicates complete facade/receipt history payloads for this section's transport only. */
    static final class HistoryBank {
        private final Map<String, Map<String, Object>> entries = new LinkedHashMap<>();
        private final Map<String, String> ids = new LinkedHashMap<>();
        Map<String, Object> ref(String domain, HistorySnapshot h) {
            var value = historyData(h);
            var key = domain + "" + NativeReport.json(value);
            var id = ids.computeIfAbsent(key, k -> "h" + ids.size());
            entries.computeIfAbsent(id, i -> {
                var entry = new LinkedHashMap<String, Object>();
                entry.put("historyDomain", domain); entry.put("history", value); return entry;
            });
            // Only retained references count against the 512 budget; comparisons and overwritten
            // polling rows create tags that never reach the report and must not spend it.
            NativeAssertions.require(entries.size() <= 256, "N4b historyBank entry budget exceeded");
            var ref = new LinkedHashMap<String, Object>();
            ref.put("historyRef", id); ref.put("historyDomain", domain); return ref;
        }
        /** Budgets the references actually retained in the finished section and prunes the rest. */
        Map<String, Map<String, Object>> finish(Object root) {
            var used = new java.util.LinkedHashSet<String>(); int[] count = {0};
            collect(root, used, count);
            NativeAssertions.require(count[0] <= 512, "N4b retained historyRef budget exceeded");
            for (var id : used) NativeAssertions.require(entries.containsKey(id), "N4b historyRef outside historyBank");
            entries.keySet().retainAll(used);
            return entries;
        }
        private static void collect(Object node, java.util.Set<String> used, int[] count) {
            if (node instanceof Map<?, ?> map) {
                if (map.size() == 2 && map.containsKey("historyRef") && map.containsKey("historyDomain")) {
                    used.add(String.valueOf(map.get("historyRef"))); count[0]++; return;
                }
                for (var value : map.values()) collect(value, used, count);
            } else if (node instanceof List<?> list) {
                for (var value : list) collect(value, used, count);
            }
        }
        Map<String, Map<String, Object>> entries() { return entries; }
    }

    static void run(Supplier<CubismModel> current, Supplier<HistorySnapshot> history, IntConsumer restore,
            EditorCommandService commands, AuthoringTransactionService transactions, Map<String, Object> data) throws Exception {
        NativeAssertions.require(!SwingUtilities.isEventDispatchThread(), "N4b wait must not block EDT");
        var p = new MorphOrderProbe(current, history, restore, data);
        HistorySnapshot root = null; Map<String, Object> counts = null, canvas = null;
        Throwable primary = null;
        try {
            data.put("configuration", EvaluationProbe.configuration());
            root = edt(history::get); counts = edt(() -> counts(current.get())); canvas = edt(() -> canvas(current.get()));
            data.put("initialHistory", p.hist(root)); data.put("initialCounts", counts); data.put("initialCanvas", canvas);
            edt(() -> { p.setup(commands); p.creates(transactions); return null; });
            edt(() -> { p.nonCurrent(transactions); return null; });
            p.evaluation(transactions);
            edt(() -> { p.truncation(transactions); return null; });
            p.singleKeySamples();
            edt(() -> { p.memberWrites(transactions); return null; });
            p.twoKeySamples();
            edt(() -> { p.controls(transactions); p.rejections(); return null; });
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
                    data.put("restoredHistory", p.hist(h));
                    p.check("restored", counts.equals(finalCounts) && canvas.equals(finalCanvas) && h.position() == position);
                    data.put("finallyRestored", true);
                } catch (Exception | Error failure) {
                    data.put("status", "FAIL"); data.put("finallyRestored", false); data.put("recoveryFailure", failure.toString());
                    if (primary != null) primary.addSuppressed(failure); else throw failure;
                }
            }
            try {
                data.put("historyBank", p.bank.finish(data));
            } catch (RuntimeException bankFailure) {
                data.put("historyBank", p.bank.entries());
                if (primary != null) primary.addSuppressed(bankFailure);
                else { data.put("status", "FAIL"); data.put("failure", bankFailure.toString()); throw bankFailure; }
            }
        }
        data.put("status", "PASS"); data.put("failure", null);
    }

    private void setup(EditorCommandService commands) {
        current.get(); var result = commands.execute(new EditorCanvasSettingsRequest(1024, 1024));
        data.put("canvasCommand", result.status().name()); data.put("canvas", canvas(current.get()));
        check("canvas", result.status() == EditorCommandResult.Status.EXECUTED
            && current.get().canvas().widthPixels() == 1024 && current.get().canvas().heightPixels() == 1024);
        id = current.get().drawables().create("MI3D_N4B", null, -1, NativeAssertions.GEOMETRY).id();
        current.get().parameters().createMany(List.of(
            new ParameterDefinition(X, "MI3D N4b X", -1, 0, 1, ParameterType.NORMAL, false),
            new ParameterDefinition(Y, "MI3D N4b Y", -1, 0, 1, ParameterType.NORMAL, false)));
        for (var parameter : List.of(X, Y)) current.get().parameterBindings(parameter).bind(
            ParameterBindingTarget.artMesh(id), NativeAssertions.KEYS.stream()
                .map(v -> new ParameterBindingPoint(new ParameterBindingPointId("N4B_" + v), v)).toList());
        // Morph parameters are their own committed operation, never part of a member transaction.
        current.get().parameters().createMany(List.of(
            new ParameterDefinition(M, "MI3D N4b M", 0, 0, 1, ParameterType.BLEND_SHAPE, false),
            new ParameterDefinition(N, "MI3D N4b N", 0, 0, 1, ParameterType.BLEND_SHAPE, false)));
        baseline = stored(); NativeAssertions.grid(baseline, X.value(), Y.value());
        data.put("baselineMorphKeys", NativeAssertions.snapshot(baseline).get("morphKeys"));
        check("baselineMorphKeys", baseline.morphKeys().isEmpty());
        xGuid = guid(X); yGuid = guid(Y); mGuid = guid(M); nGuid = guid(N);
        data.put("morphParameters", Map.of(
            "X", Map.of("id", X.value(), "guid", xGuid), "Y", Map.of("id", Y.value(), "guid", yGuid),
            "M", Map.of("id", M.value(), "guid", mGuid), "N", Map.of("id", N.value(), "guid", nGuid)));
        data.put("baseline", NativeAssertions.snapshot(baseline));
        data.put("fixtureCounts", counts(current.get())); data.put("fixtureHistory", hist(history.get()));
        check("fixture", baseline.forms().size() == 9
            && baseline.cells().stream().map(c -> c.formGuid()).distinct().count() == 9
            && baseline.forms().stream().allMatch(f -> f.drawOrder() == 500)
            && current.get().deformers().all().isEmpty()
            && current.get().parameters().find(X).getValue() == 0 && current.get().parameters().find(Y).getValue() == 0
            && current.get().parameters().find(M).getValue() == 0 && current.get().parameters().find(N).getValue() == 0);
    }

    private void creates(AuthoringTransactionService transactions) {
        var row = new LinkedHashMap<String, Object>(); data.put("creates", row);
        var before = history.get();
        var seeds = new ArrayList<Form>(); int[] steps = {0};
        var result = transactions.execute(AuthoringTransactionOptions.of("MI3D N4b create M1 N1"), () -> {
            seeds.add(mesh().createMorphKeyform(mGuid, 1f).snapshot()); steps[0]++;
            seeds.add(mesh().createMorphKeyform(nGuid, 1f).snapshot()); steps[0]++;
            return null;
        });
        var after = history.get(); created = stored();
        mForm = MorphProbe.morphKey(created, mGuid, 1f).formGuid();
        nForm = MorphProbe.morphKey(created, nGuid, 1f).formGuid();
        var expected = withMember(withMember(baseline, mGuid, 1f, mForm, B, 500), nGuid, 1f, nForm, B, 500);
        row.put("result", resultData(result)); row.put("steps", steps[0]);
        row.put("seedM", formData(seeds.get(0))); row.put("seedN", formData(seeds.get(1)));
        row.put("triples", List.of(
            Map.of("parameterGuid", mGuid, "keyValue", 1f, "formGuid", mForm),
            Map.of("parameterGuid", nGuid, "keyValue", 1f, "formGuid", nForm)));
        row.put("before", hist(before)); row.put("after", hist(after));
        row.put("snapshot", NativeAssertions.snapshot(created));
        check("createsCommitted", result.outcome() == AuthoringTransactionOutcome.COMMITTED
            && steps[0] == 2 && after.position() == before.position() + 1);
        check("createsSeed", seeds.get(0).positions().equals(B) && seeds.get(0).drawOrder() == 500
            && seeds.get(1).positions().equals(B) && seeds.get(1).drawOrder() == 500);
        check("createsCounts", created.forms().size() == 11 && created.morphKeys().size() == 2
            && baseline.forms().stream().noneMatch(f -> f.formGuid().equals(mForm) || f.formGuid().equals(nForm))
            && created.equals(expected));
    }

    private void nonCurrent(AuthoringTransactionService transactions) {
        var row = new LinkedHashMap<String, Object>(); data.put("nonCurrent", row);
        row.put("before", state());
        var hBefore = history.get();
        var handles = new AtomicReference<ArtMeshMorphKeyform>();
        var result = transactions.execute(AuthoringTransactionOptions.of("MI3D N4b noncurrent order"), () -> {
            var m = mesh().morphKeyform(mGuid, 1f); var n = mesh().morphKeyform(nGuid, 1f);
            handles.set(m);
            m.setDrawOrder(WRITE_A); row.put("midMutation", state());
            n.setDrawOrder(WRITE_B); row.put("mutated", state()); return null;
        });
        var hAfter = history.get();
        row.put("result", resultData(result));
        row.put("historyBefore", hist(hBefore)); row.put("historyAfter", hist(hAfter));
        row.put("written", state());
        evalWritten = ordered(ordered(created, mForm, WRITE_A), nForm, WRITE_B);
        check("nonCurrentCommitted", result.outcome() == AuthoringTransactionOutcome.COMMITTED
            && hAfter.position() == hBefore.position() + 1 && stored().equals(evalWritten));
        var m1 = handles.get();
        Form detached = null; String postCommit = "VALID";
        try { detached = m1.snapshot(); } catch (IllegalStateException expected) { postCommit = "STALE"; }
        row.put("handleAfterCommit", postCommit);
        check("nonCurrentHandleValid", postCommit.equals("VALID") && detached != null);
        var noop = transactions.execute(AuthoringTransactionOptions.of("MI3D N4b noop"), () -> {
            mesh().morphKeyform(mGuid, 1f).setDrawOrder(WRITE_A);
            mesh().morphKeyform(nGuid, 1f).setDrawOrder(WRITE_B); return null;
        });
        String noopValid = "VALID";
        try { m1.snapshot(); } catch (IllegalStateException expected) { noopValid = "STALE"; }
        var noopRow = new LinkedHashMap<String, Object>(); row.put("noop", noopRow);
        noopRow.put("result", resultData(noop)); noopRow.put("state", state()); noopRow.put("handle", noopValid);
        check("nonCurrentNoop", noop.outcome() == AuthoringTransactionOutcome.NO_CHANGE
            && noopValid.equals("VALID") && stored().equals(evalWritten));
        var undoRow = new LinkedHashMap<String, Object>(); row.put("undoCycle", undoRow);
        restore.accept(hBefore.position());
        String stale = "VALID";
        try { m1.snapshot(); } catch (IllegalStateException expected) { stale = "STALE"; }
        var fresh = mesh().morphKeyform(mGuid, 1f);
        undoRow.put("oldHandle", stale); undoRow.put("detached", formData(detached));
        undoRow.put("fresh", formData(fresh.snapshot()));
        undoRow.put("freshHandle", Map.of("parameterGuid", fresh.parameterGuid(), "keyValue", fresh.keyValue()));
        undoRow.put("state", state());
        check("nonCurrentStale", stale.equals("STALE") && stored().equals(created)
            && fresh.snapshot().drawOrder() == 500 && fresh.snapshot().positions().equals(B));
        for (int round = 1; round <= 2; round++) {
            restore.accept(hAfter.position()); undoRow.put("redone" + round, state());
            check("nonCurrentRedo" + round, stored().equals(evalWritten));
            restore.accept(hBefore.position()); undoRow.put("undone" + round, state());
            check("nonCurrentUndo" + round, stored().equals(created));
        }
        restore.accept(hAfter.position()); undoRow.put("redone3", state());
        restore.accept(hBefore.position()); undoRow.put("final", state());
        check("nonCurrentFinal", stored().equals(created));
    }

    private void evaluation(AuthoringTransactionService transactions) throws Exception {
        var row = new LinkedHashMap<String, Object>(); data.put("evalOrder", row);
        var anchor = new AtomicReference<HistorySnapshot>();
        var result = new AtomicReference<AuthoringTransactionResult<?>>();
        var before = new AtomicReference<HistorySnapshot>(); var after = new AtomicReference<HistorySnapshot>();
        step("evalBaseline", 1, 0, 500, created, () -> setM(1));
        step("evalWritten", 1, 0, 1000, evalWritten, () -> {
            before.set(history.get()); anchor.set(before.get());
            result.set(transactions.execute(AuthoringTransactionOptions.of("MI3D N4b evaluated order"), () -> {
                mesh().morphKeyform(mGuid, 1f).setDrawOrder(WRITE_A);
                mesh().morphKeyform(nGuid, 1f).setDrawOrder(WRITE_B); return null;
            }));
            after.set(history.get());
        });
        row.put("result", resultData(result.get()));
        row.put("anchor", hist(anchor.get())); row.put("before", hist(before.get())); row.put("after", hist(after.get()));
        check("evalWriteCommitted", result.get().outcome() == AuthoringTransactionOutcome.COMMITTED
            && after.get().position() == before.get().position() + 1);
        step("evalUndone", 1, 0, 500, created, () -> restore.accept(anchor.get().position()));
        step("evalRedone", 1, 0, 1000, evalWritten, () -> restore.accept(after.get().position()));
        step("evalMid", .5f, 0, 750, evalWritten, () -> setM(.5f));
        step("evalDefault", 0, 0, 500, evalWritten, () -> setM(0));
        step("evalNWritten", 0, 1, 0, evalWritten, () -> setN(1));
        step("evalNMid", 0, .5f, 250, evalWritten, () -> setN(.5f));
    }

    private void truncation(AuthoringTransactionService transactions) {
        var row = new LinkedHashMap<String, Object>(); data.put("truncation", row);
        row.put("reset", resetPhase());
        check("truncReset", parameters().equals(List.of(0f, 0f, 0f, 0f)));
        var tBefore = history.get(); row.put("writeBefore", hist(tBefore));
        var tResult = transactions.execute(AuthoringTransactionOptions.of("MI3D N4b truncating order"), () -> {
            mesh().morphKeyform(mGuid, 1f).setDrawOrder(TRUNC); return null;
        });
        var tAfter = history.get(); row.put("writeAfter", hist(tAfter));
        truncWritten = ordered(ordered(created, mForm, TRUNC), nForm, WRITE_B);
        row.put("writeResult", resultData(tResult)); row.put("written", state());
        check("truncWriteCommitted", tResult.outcome() == AuthoringTransactionOutcome.COMMITTED
            && tAfter.position() == tBefore.position() + 1 && stored().equals(truncWritten));
    }

    private void memberWrites(AuthoringTransactionService transactions) {
        var row = new LinkedHashMap<String, Object>(); data.put("secondKey", row);
        row.put("reset", resetPhase());
        check("secondKeyReset", parameters().equals(List.of(0f, 0f, 0f, 0f)));
        var cBefore = history.get(); row.put("createBefore", hist(cBefore));
        var seed = new AtomicReference<Form>();
        var cResult = transactions.execute(AuthoringTransactionOptions.of("MI3D N4b create M.5"), () -> {
            seed.set(mesh().createMorphKeyform(mGuid, .5f).snapshot()); return null;
        });
        var cAfter = history.get(); row.put("createAfter", hist(cAfter));
        halfForm = MorphProbe.morphKey(stored(), mGuid, .5f).formGuid();
        var withHalf = withMember(truncWritten, mGuid, .5f, halfForm, seed.get().positions(), seed.get().drawOrder());
        row.put("createResult", resultData(cResult)); row.put("seed", formData(seed.get()));
        row.put("triple", Map.of("parameterGuid", mGuid, "keyValue", .5f, "formGuid", halfForm));
        row.put("snapshot", NativeAssertions.snapshot(stored()));
        check("halfCreateCommitted", cResult.outcome() == AuthoringTransactionOutcome.COMMITTED
            && cAfter.position() == cBefore.position() + 1 && stored().equals(withHalf));
        check("halfSeed", seed.get().drawOrder() == 600 && seed.get().positions().equals(B));
        check("halfCounts", stored().forms().size() == 12 && stored().morphKeys().size() == 3);
        var wBefore = history.get(); row.put("writeBefore", hist(wBefore));
        var wResult = transactions.execute(AuthoringTransactionOptions.of("MI3D N4b write M.5 order"), () -> {
            mesh().morphKeyform(mGuid, .5f).setDrawOrder(SECOND); return null;
        });
        var wAfter = history.get(); row.put("writeAfter", hist(wAfter));
        halfWritten = ordered(withHalf, halfForm, SECOND);
        row.put("writeResult", resultData(wResult)); row.put("written", state());
        check("halfWriteCommitted", wResult.outcome() == AuthoringTransactionOutcome.COMMITTED
            && wAfter.position() == wBefore.position() + 1 && stored().equals(halfWritten));
    }

    private void singleKeySamples() throws Exception {
        // Single-key samples must run while (M,.5) does not yet exist.
        step("truncMid", .5f, 0, 600, truncWritten, () -> setM(.5f));
        step("truncEnd", 1, 0, 701, truncWritten, () -> setM(1));
    }

    private void twoKeySamples() throws Exception {
        step("twoKeyZero", 0, 0, 500, halfWritten, () -> setM(0));
        step("twoKeyQuarter", .25f, 0, 399, halfWritten, () -> setM(.25f));
        step("twoKeyHalf", .5f, 0, 299, halfWritten, () -> setM(.5f));
        step("twoKeyThreeQuarter", .75f, 0, 500, halfWritten, () -> setM(.75f));
        step("twoKeyEnd", 1, 0, 701, halfWritten, () -> setM(1));
    }

    private void controls(AuthoringTransactionService transactions) {
        var controls = new LinkedHashMap<String, Object>(); data.put("controls", controls);
        controls.put("reset", resetPhase());
        check("controlsReset", parameters().equals(List.of(0f, 0f, 0f, 0f)));
        controls.put("before", state());
        for (boolean orderFirst : List.of(false, true)) for (boolean same : List.of(false, true)) {
            String name = "pair-" + orderFirst + "-" + same;
            var row = new LinkedHashMap<String, Object>(); controls.put(name, row); row.put("before", state());
            int[] steps = {0};
            var result = transactions.execute(AuthoringTransactionOptions.of("MI3D N4b " + name), () -> {
                if (orderFirst) mesh().morphKeyform(mGuid, 1f).setDrawOrder(MIDWRITE);
                else mesh().morphKeyform(same ? mGuid : nGuid, 1f).setPositions(V);
                steps[0]++;
                if (orderFirst) mesh().morphKeyform(same ? mGuid : nGuid, 1f).setPositions(V);
                else mesh().morphKeyform(mGuid, 1f).setDrawOrder(MIDWRITE);
                steps[0]++; row.put("mutated", state()); row.put("callbackFailure", "N4B_ABORT");
                throw new IllegalStateException("N4B_ABORT");
            });
            row.put("steps", steps[0]); row.put("result", resultData(result)); row.put("after", state());
            check(name, steps[0] == 2 && result.outcome() == AuthoringTransactionOutcome.ROLLED_BACK
                && row.get("before").equals(row.get("after")));
        }
        var triple = new LinkedHashMap<String, Object>(); controls.put("triple", triple); triple.put("before", state());
        int[] tSteps = {0};
        var tResult = transactions.execute(AuthoringTransactionOptions.of("MI3D N4b triple"), () -> {
            for (int order : TRIPLE_ORDERS) { mesh().morphKeyform(mGuid, 1f).setDrawOrder(order); tSteps[0]++; }
            triple.put("mutated", state()); triple.put("callbackFailure", "N4B_ABORT");
            throw new IllegalStateException("N4B_ABORT");
        });
        triple.put("steps", tSteps[0]); triple.put("result", resultData(tResult)); triple.put("after", state());
        check("triple", tSteps[0] == 3 && tResult.outcome() == AuthoringTransactionOutcome.ROLLED_BACK
            && triple.get("before").equals(triple.get("after")));
        memberMixed(transactions, controls);
    }

    private void memberMixed(AuthoringTransactionService transactions, Map<String, Object> controls) {
        var commit = new LinkedHashMap<String, Object>(); controls.put("mixedCommit", commit);
        var cBefore = history.get(); commit.put("before", state());
        var seed = new AtomicReference<Form>(); int[] steps = {0};
        var result = transactions.execute(AuthoringTransactionOptions.of("MI3D N4b mixed member"), () -> {
            mesh().createMorphKeyform(mGuid, .25f); steps[0]++;
            seed.set(mesh().morphKeyform(mGuid, .25f).snapshot());
            mesh().morphKeyform(mGuid, .25f).setDrawOrder(700); steps[0]++;
            mesh().morphKeyform(mGuid, .25f).setPositions(V); steps[0]++;
            mesh().morphKeyform(mGuid, 1f).setDrawOrder(900); steps[0]++;
            commit.put("mutated", state()); return null;
        });
        var cAfter = history.get(); mixedWritten = stored();
        var quarterForm = MorphProbe.morphKey(mixedWritten, mGuid, .25f).formGuid();
        var expectedMixed = ordered(withMember(halfWritten, mGuid, .25f, quarterForm, V, 700), mForm, 900);
        commit.put("result", resultData(result)); commit.put("steps", steps[0]);
        commit.put("seed", formData(seed.get()));
        commit.put("triple", Map.of("parameterGuid", mGuid, "keyValue", .25f, "formGuid", quarterForm));
        commit.put("after", state());
        check("mixedCommitted", result.outcome() == AuthoringTransactionOutcome.COMMITTED
            && steps[0] == 4 && cAfter.position() == cBefore.position() + 1 && mixedWritten.equals(expectedMixed));
        check("mixedSeed", seed.get().positions().equals(B) && seed.get().drawOrder() == 399);
        check("mixedCounts", mixedWritten.forms().size() == 13 && mixedWritten.morphKeys().size() == 4);
        for (int round = 1; round <= 2; round++) {
            restore.accept(cBefore.position()); commit.put("undone" + round, state());
            check("mixedUndo" + round, stored().equals(halfWritten));
            restore.accept(cAfter.position()); commit.put("redone" + round, state());
            check("mixedRedo" + round, stored().equals(mixedWritten));
        }
        restore.accept(cBefore.position()); commit.put("final", state());
        check("mixedFinal", stored().equals(halfWritten));
        var rolled = new LinkedHashMap<String, Object>(); controls.put("mixedRollback", rolled);
        rolled.put("before", state());
        int[] rbSteps = {0}; var rbSeed = new AtomicReference<Form>();
        var staleCreate = new AtomicReference<ArtMeshMorphKeyform>();
        var preFailureN = new AtomicReference<ArtMeshMorphKeyform>();
        var preDetachedN = new AtomicReference<Form>();
        var rbResult = transactions.execute(AuthoringTransactionOptions.of("MI3D N4b mixed rollback"), () -> {
            staleCreate.set(mesh().createMorphKeyform(mGuid, .25f)); rbSteps[0]++;
            rbSeed.set(mesh().morphKeyform(mGuid, .25f).snapshot());
            mesh().morphKeyform(mGuid, .25f).setDrawOrder(700); rbSteps[0]++;
            mesh().morphKeyform(mGuid, .25f).setPositions(V); rbSteps[0]++;
            mesh().morphKeyform(mGuid, 1f).setDrawOrder(900); rbSteps[0]++;
            rolled.put("mutated", state());
            var fresh = mesh().morphKeyform(nGuid, 1f);
            Form detached = null; String valid;
            try { detached = fresh.snapshot(); valid = "VALID"; }
            catch (IllegalStateException alreadyStale) { valid = "STALE"; }
            preFailureN.set(fresh); preDetachedN.set(detached);
            var pre = new LinkedHashMap<String, Object>(); rolled.put("preFailure", pre);
            pre.put("nHandle", valid);
            pre.put("handle", detached == null ? null
                : Map.of("parameterGuid", fresh.parameterGuid(), "keyValue", fresh.keyValue()));
            pre.put("detached", detached == null ? null : formData(detached));
            rolled.put("callbackFailure", "N4B_ABORT");
            throw new IllegalStateException("N4B_ABORT");
        });
        staleMember = staleCreate.get();
        rolled.put("steps", rbSteps[0]); rolled.put("result", resultData(rbResult));
        rolled.put("seed", formData(rbSeed.get()));
        rolled.put("triple", Map.of("parameterGuid", mGuid, "keyValue", .25f,
            "formGuid", rbSeed.get().formGuid()));
        rolled.put("after", state());
        String nStale = "VALID";
        try { preFailureN.get().snapshot(); } catch (IllegalStateException expected) { nStale = "STALE"; }
        var relooked = mesh().morphKeyform(nGuid, 1f);
        var post = new LinkedHashMap<String, Object>(); rolled.put("postFailure", post);
        post.put("nHandle", nStale);
        post.put("relooked", formData(relooked.snapshot()));
        post.put("relookedHandle", Map.of("parameterGuid", relooked.parameterGuid(), "keyValue", relooked.keyValue()));
        post.put("detached", preDetachedN.get() == null ? null : formData(preDetachedN.get()));
        check("mixedRollback", rbSteps[0] == 4 && rbResult.outcome() == AuthoringTransactionOutcome.ROLLED_BACK
            && rolled.get("before").equals(rolled.get("after")));
        @SuppressWarnings("unchecked") var pre = (Map<String, Object>) rolled.get("preFailure");
        check("mixedStaleN", pre != null && "VALID".equals(pre.get("nHandle")) && nStale.equals("STALE")
            && relooked.snapshot().equals(preDetachedN.get())
            && relooked.parameterGuid().equals(nGuid) && relooked.keyValue() == 1f);
    }

    private void rejections() {
        var rejects = new LinkedHashMap<String, Object>(); data.put("rejections", rejects);
        for (int invalid : List.of(-1, 1001, Integer.MIN_VALUE, Integer.MAX_VALUE))
            reject(rejects, "orderRange" + invalid, IllegalArgumentException.class,
                () -> mesh().morphKeyform(nGuid, 1f).setDrawOrder(invalid));
        reject(rejects, "staleRead", IllegalStateException.class, () -> staleMember.snapshot());
        reject(rejects, "staleOrderWrite", IllegalStateException.class, () -> staleMember.setDrawOrder(500));
        reject(rejects, "stalePositionWrite", IllegalStateException.class, () -> staleMember.setPositions(V));
    }

    private void reject(Map<String, Object> rejects, String name, Class<? extends RuntimeException> expected, Runnable action) {
        var before = state(); String actual = "NOT_REJECTED";
        try { action.run(); } catch (RuntimeException error) { actual = error.getClass().getName(); }
        var after = state(); rejects.put(name, Map.of("before", before, "after", after, "exception", actual));
        check(name, actual.equals(expected.getName()) && before.equals(after));
    }

    private void step(String name, float m, float n, int order, ArtMeshAuthoringSnapshot expectedStored,
            Runnable action) throws Exception {
        var row = new LinkedHashMap<String, Object>(); samples.add(row);
        row.put("id", name); row.put("m", m); row.put("n", n); row.put("x", 0); row.put("y", 0);
        row.put("expectedOrder", order); row.put("expectedPositions", positions(B));
        row.put("waitLimitMillis", EvaluationProbe.WAIT_MILLIS); row.put("status", "RUNNING");
        try {
            var requested = edt(() -> {
                current.get(); row.put("before", EvaluationProbe.evaluation(observe()));
                var paramsBefore = parameters(); var hBefore = history.get();
                action.run();
                var h = history.get(); var s = stored(); var e = observe();
                NativeAssertions.require(s.equals(expectedStored), "N4b stored pollution");
                NativeAssertions.require(value(e, X) == 0 && value(e, Y) == 0 && value(e, M) == m && value(e, N) == n,
                    "N4b parameter intent");
                row.put("parametersBefore", paramsBefore); row.put("parametersAfter", parameters());
                row.put("actionHistoryBefore", hist(hBefore)); row.put("actionHistoryAfter", hist(h));
                row.put("history", hist(h)); row.put("stored", NativeAssertions.snapshot(s));
                row.put("requested", EvaluationProbe.evaluation(e)); return e;
            });
            var actual = EvaluationProbe.awaitConsumed(requested, () -> edt(() -> {
                var h0 = history.get(); var s = stored(); var e = observe(); var h1 = history.get();
                row.put("readHistoryBefore", hist(h0)); row.put("readHistoryAfter", hist(h1));
                row.put("observedStored", NativeAssertions.snapshot(s));
                NativeAssertions.require(h0.equals(h1) && hist(h1).equals(row.get("history")) && s.equals(expectedStored),
                    "N4b sampling changed history or stored model"); return e;
            }), row::putAll, System::nanoTime, Thread::sleep);
            row.put("actual", EvaluationProbe.evaluation(actual));
            row.put("maxErrorPixels", EvaluationProbe.maxError(actual, B));
            check(name, EvaluationProbe.validGeometry(actual, B, order));
            row.put("status", "PASS");
        } catch (Exception | Error error) {
            row.put("status", "FAIL"); row.put("failure", error.toString()); checkFailure(name); throw error;
        }
    }

    static ArtMeshAuthoringSnapshot ordered(ArtMeshAuthoringSnapshot s, String guid, int order) {
        NativeAssertions.require(order >= 0 && order <= 1000, "N4b invalid expected order");
        var member = s.morphKeys().stream().filter(k -> k.formGuid().equals(guid)).toList();
        var forms = s.forms().stream().filter(f -> f.formGuid().equals(guid)).toList();
        NativeAssertions.require(member.size() == 1 && member.get(0).keyValue().isPresent() && forms.size() == 1
            && s.cells().stream().noneMatch(c -> c.formGuid().equals(guid)),
            "N4b expected order target is not exactly one morph member");
        var next = s.forms().stream().map(f -> f.formGuid().equals(guid)
            ? new Form(guid, f.positions(), order) : f).toList();
        return new ArtMeshAuthoringSnapshot(s.artMeshId(), s.sourceGuid(), s.sourceGeometry(),
            s.axes(), s.cells(), next, s.morphKeys());
    }

    static ArtMeshAuthoringSnapshot withMember(ArtMeshAuthoringSnapshot s, String paramGuid, float key,
            String formGuid, List<Point2> positions, int order) {
        NativeAssertions.require(s.forms().stream().noneMatch(f -> f.formGuid().equals(formGuid))
            && s.morphKeys().stream().noneMatch(k -> k.formGuid().equals(formGuid))
            && s.morphKeys().stream().noneMatch(k -> k.parameterGuid().equals(paramGuid)
                && k.keyValue().isPresent() && k.keyValue().get() == key)
            && s.cells().stream().noneMatch(c -> c.formGuid().equals(formGuid))
            && !Float.isNaN(key) && key > 0 && key <= 1 && positions.size() == 4 && order >= 0 && order <= 1000,
            "N4b invalid expected member");
        var forms = new ArrayList<>(s.forms()); forms.add(new Form(formGuid, List.copyOf(positions), order));
        var keys = new ArrayList<>(s.morphKeys());
        keys.add(new MorphKey(paramGuid, formGuid, Optional.of(key)));
        return new ArtMeshAuthoringSnapshot(s.artMeshId(), s.sourceGuid(), s.sourceGeometry(),
            s.axes(), s.cells(), List.copyOf(forms), List.copyOf(keys));
    }

    private Drawable mesh() { return current.get().drawables().find(id); }
    private ArtMeshAuthoringSnapshot stored() { return mesh().authoringSnapshot(); }
    private ArtMeshEvaluationSnapshot observe() { return mesh().evaluationSnapshot(); }
    private void setM(float value) { current.get().parameters().find(M).setValue(value); }
    private void setN(float value) { current.get().parameters().find(N).setValue(value); }
    private List<Float> parameters() {
        return List.of(current.get().parameters().find(X).getValue(), current.get().parameters().find(Y).getValue(),
            current.get().parameters().find(M).getValue(), current.get().parameters().find(N).getValue());
    }
    /** Records one M/N phase reset with per-parameter history anchors (a real transition, not a claim). */
    private Map<String, Object> resetPhase() {
        var row = new LinkedHashMap<String, Object>();
        row.put("parametersBefore", parameters());
        var h0 = history.get(); setM(0); var h1 = history.get(); setN(0); var h2 = history.get();
        row.put("writes", List.of(
            Map.of("parameter", "M", "historyBefore", hist(h0), "historyAfter", hist(h1)),
            Map.of("parameter", "N", "historyBefore", hist(h1), "historyAfter", hist(h2))));
        row.put("parametersAfter", parameters());
        return row;
    }
    private String guid(ParameterId parameter) {
        var found = observe().liveParameters().parameters().stream().filter(v -> v.parameterId().equals(parameter)).toList();
        NativeAssertions.require(found.size() == 1, "parameter GUID unresolved/ambiguous");
        return found.get(0).parameterGuid();
    }
    private Map<String, Object> hist(HistorySnapshot h) { return bank.ref("FACADE", h); }
    private Map<String, Object> state() {
        current.get();
        var row = new LinkedHashMap<String, Object>();
        row.put("snapshot", NativeAssertions.snapshot(stored())); row.put("history", hist(history.get()));
        row.put("parameters", List.of(current.get().parameters().find(X).getValue(), current.get().parameters().find(Y).getValue(),
            current.get().parameters().find(M).getValue(), current.get().parameters().find(N).getValue()));
        return row;
    }
    private static float value(ArtMeshEvaluationSnapshot e, ParameterId parameter) {
        var matches = e.liveParameters().parameters().stream().filter(v -> v.parameterId().equals(parameter)).toList();
        NativeAssertions.require(matches.size() == 1, "parameter identity ambiguous/missing"); return matches.get(0).value();
    }
    private static Map<String, Object> formData(Form f) {
        var row = new LinkedHashMap<String, Object>();
        row.put("formGuid", f.formGuid()); row.put("positions", positions(f.positions())); row.put("drawOrder", f.drawOrder());
        return row;
    }
    private void check(String id, boolean actual) {
        NativeAssertions.require(checks.stream().noneMatch(c -> id.equals(c.get("id"))), "duplicate N4b check");
        checks.add(Map.of("id", id, "actual", actual, "status", actual ? "PASS" : "FAIL", "evidenceKind", "EXACT_HOST"));
        NativeAssertions.require(actual, "N4b " + id);
    }
    private void checkFailure(String id) {
        if (checks.stream().noneMatch(c -> id.equals(c.get("id")))) checks.add(Map.of("id", id,
            "status", "FAIL", "actual", false, "expected", "native sample completed", "evidenceKind", "EXACT_HOST"));
    }
    private static List<List<Float>> positions(List<Point2> points) { return points.stream().map(p -> List.of(p.x(), p.y())).toList(); }
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
    private Map<String, Object> resultData(AuthoringTransactionResult<?> result) {
        var row = new LinkedHashMap<String, Object>(); row.put("outcome", result.outcome().name());
        row.put("diagnosticId", result.diagnosticId().orElse(null));
        row.put("receipt", result.receipt().map(r -> {
            var receipt = new LinkedHashMap<String, Object>(); receipt.put("transactionId", r.transactionId());
            receipt.put("label", r.label()); receipt.put("historyBefore", bank.ref("TRANSACTION", r.historyBefore()));
            receipt.put("historyAfter", bank.ref("TRANSACTION", r.historyAfter())); receipt.put("historyEntryId", r.historyEntryId().orElse(null));
            return receipt;
        }).orElse(null)); return row;
    }
    private static <T> T edt(Callable<T> action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return action.call();
        var task = new FutureTask<T>(action); SwingUtilities.invokeAndWait(task); return task.get();
    }
}

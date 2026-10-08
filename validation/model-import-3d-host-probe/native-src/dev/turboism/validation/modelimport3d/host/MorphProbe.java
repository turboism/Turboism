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
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;

/** SDK-only N4a morph probe: member creation, absolute-position writes, Undo/Redo and rollback. */
final class MorphProbe {
    private static final ParameterId X = new ParameterId("ParamMI3DN2X"), Y = new ParameterId("ParamMI3DN2Y");
    private static final ParameterId M = new ParameterId("ParamMI3DN4M"), N = new ParameterId("ParamMI3DN4N");
    // Frozen expected content; the independent Python oracle owns the same literal values.
    static final List<Point2> B = NativeAssertions.GEOMETRY.positions();
    static final List<Point2> U = EvaluationProbe.points(48, 40, 304, 40, 304, 168, 48, 168);
    static final List<Point2> H = EvaluationProbe.points(32, 32, 288, 32, 288, 160, 32, 160);
    static final List<Point2> U2 = EvaluationProbe.points(80, 56, 336, 56, 336, 184, 80, 184);
    static final List<Point2> V = EvaluationProbe.points(8, 64, 264, 64, 264, 192, 8, 192);
    static final List<Point2> W = EvaluationProbe.points(96, 8, 352, 8, 352, 136, 96, 136);
    static final List<Point2> SEED25 = EvaluationProbe.points(24, 28, 280, 28, 280, 156, 24, 156);
    static final List<Point2> SEED75 = EvaluationProbe.points(56, 44, 312, 44, 312, 172, 56, 172);
    private final Supplier<CubismModel> current;
    private final Supplier<HistorySnapshot> history;
    private final IntConsumer restore;
    private final Map<String, Object> data;
    private final List<Map<String, Object>> checks = new ArrayList<>(), samples = new ArrayList<>();
    private ArtMeshId id;
    private String xGuid, yGuid, mGuid, nGuid;
    private String uForm, hForm, nForm;
    private ArtMeshAuthoringSnapshot baseline, written, withHalf, isolated, withN, committed;
    private ArtMeshMorphKeyform staleHandle;
    private Form detachedWritten;
    private HistorySnapshot writeBefore, writeAfter;

    private MorphProbe(Supplier<CubismModel> current, Supplier<HistorySnapshot> history,
            IntConsumer restore, Map<String, Object> data) {
        this.current = current; this.history = history; this.restore = restore; this.data = data;
        data.put("status", "RUNNING"); data.put("checks", checks); data.put("samples", samples);
    }

    static void run(Supplier<CubismModel> current, Supplier<HistorySnapshot> history, IntConsumer restore,
            EditorCommandService commands, AuthoringTransactionService transactions, Map<String, Object> data) throws Exception {
        NativeAssertions.require(!SwingUtilities.isEventDispatchThread(), "N4a wait must not block EDT");
        var p = new MorphProbe(current, history, restore, data);
        HistorySnapshot root = null; Map<String, Object> counts = null, canvas = null;
        Throwable primary = null;
        try {
            data.put("configuration", EvaluationProbe.configuration());
            root = edt(history::get); counts = edt(() -> counts(current.get())); canvas = edt(() -> canvas(current.get()));
            data.put("initialHistory", historyData(root)); data.put("initialCounts", counts); data.put("initialCanvas", canvas);
            edt(() -> { p.setup(commands); return null; });
            p.matrix(transactions);
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
        id = current.get().drawables().create("MI3D_N4A", null, -1, NativeAssertions.GEOMETRY).id();
        current.get().parameters().createMany(List.of(
            new ParameterDefinition(X, "MI3D N4a X", -1, 0, 1, ParameterType.NORMAL, false),
            new ParameterDefinition(Y, "MI3D N4a Y", -1, 0, 1, ParameterType.NORMAL, false)));
        for (var parameter : List.of(X, Y)) current.get().parameterBindings(parameter).bind(
            ParameterBindingTarget.artMesh(id), NativeAssertions.KEYS.stream()
                .map(v -> new ParameterBindingPoint(new ParameterBindingPointId("N4A_" + v), v)).toList());
        // Morph parameters are their own committed operation, never part of a member transaction.
        current.get().parameters().createMany(List.of(
            new ParameterDefinition(M, "MI3D N4a M", 0, 0, 1, ParameterType.BLEND_SHAPE, false),
            new ParameterDefinition(N, "MI3D N4a N", 0, 0, 1, ParameterType.BLEND_SHAPE, false)));
        baseline = stored(); NativeAssertions.grid(baseline, X.value(), Y.value());
        // An auto-materialized base/unknown target fails this check; it is recorded, never deleted.
        data.put("baselineMorphKeys", NativeAssertions.snapshot(baseline).get("morphKeys"));
        check("baselineMorphKeys", baseline.morphKeys().isEmpty());
        // Native parameter GUIDs come from live evaluation parameters; a ParameterId is never a GUID.
        xGuid = guid(X); yGuid = guid(Y); mGuid = guid(M); nGuid = guid(N);
        data.put("morphParameters", Map.of(
            "X", Map.of("id", X.value(), "guid", xGuid), "Y", Map.of("id", Y.value(), "guid", yGuid),
            "M", Map.of("id", M.value(), "guid", mGuid), "N", Map.of("id", N.value(), "guid", nGuid)));
        data.put("baseline", NativeAssertions.snapshot(baseline));
        data.put("fixtureCounts", counts(current.get())); data.put("fixtureHistory", historyData(history.get()));
        check("fixture", baseline.forms().size() == 9
            && baseline.cells().stream().map(c -> c.formGuid()).distinct().count() == 9
            && baseline.forms().stream().allMatch(f -> f.drawOrder() == 500)
            && current.get().deformers().all().isEmpty()
            && current.get().parameters().find(X).getValue() == 0 && current.get().parameters().find(Y).getValue() == 0
            && current.get().parameters().find(M).getValue() == 0 && current.get().parameters().find(N).getValue() == 0);
    }

    private void matrix(AuthoringTransactionService transactions) throws Exception {
        // Six linear samples: no second non-basic key may exist before this matrix finishes.
        step("baseline", 1, baseline, B, () -> setM(1));
        step("written", 1, null, U, () -> {
            writeBefore = history.get();
            var created = new AtomicReference<ArtMeshMorphKeyform>();
            var seed = new AtomicReference<Form>(); var storedForm = new AtomicReference<Form>();
            var createdIdentity = new AtomicReference<Map<String, Object>>();
            var result = transactions.execute(AuthoringTransactionOptions.of("MI3D N4a create M1 write U"), () -> {
                var handle = mesh().createMorphKeyform(mGuid, 1f);
                created.set(handle); seed.set(handle.snapshot());
                createdIdentity.set(Map.of("parameterGuid", handle.parameterGuid(), "keyValue", handle.keyValue()));
                handle.setPositions(U); storedForm.set(handle.snapshot()); return null;
            });
            writeAfter = history.get(); written = stored(); staleHandle = created.get(); detachedWritten = storedForm.get();
            var row = new LinkedHashMap<String, Object>(); data.put("linearCreate", row);
            row.put("result", resultData(result)); row.put("seed", formData(seed.get())); row.put("writtenForm", formData(storedForm.get()));
            row.put("handle", createdIdentity.get());
            var key = morphKey(written, mGuid, 1f); uForm = key.formGuid();
            row.put("triple", Map.of("parameterGuid", key.parameterGuid(), "keyValue", key.keyValue().orElse(null), "formGuid", uForm));
            row.put("before", historyData(writeBefore)); row.put("after", historyData(writeAfter));
            row.put("snapshot", NativeAssertions.snapshot(written));
            check("createCommitted", result.outcome() == AuthoringTransactionOutcome.COMMITTED
                && writeAfter.position() == writeBefore.position() + 1);
            check("createCounts", written.forms().size() == baseline.forms().size() + 1 && written.morphKeys().size() == 1);
            check("createSeed", seed.get().positions().equals(B) && seed.get().drawOrder() == 500);
            check("createTriple", form(written, uForm).positions().equals(U) && form(written, uForm).drawOrder() == 500
                && baseline.forms().stream().noneMatch(f -> f.formGuid().equals(uForm)));
        });
        step("undone", 1, baseline, B, () -> restore.accept(writeBefore.position()));
        edt(() -> {
            // Deletion-state observation: the member is gone here; the same handle must reject now,
            // not only after the later Redo clones the target records.
            var handle = new LinkedHashMap<String, Object>(); data.put("undoneHandle", handle);
            String stale = "VALID";
            try { staleHandle.snapshot(); } catch (IllegalStateException expected) { stale = "STALE"; }
            handle.put("oldHandle", stale);
            check("undoneStale", stale.equals("STALE"));
            return null;
        });
        step("redone", 1, written, U, () -> restore.accept(writeAfter.position()));
        edt(() -> {
            var handle = new LinkedHashMap<String, Object>(); data.put("redoneHandle", handle);
            String stale = "VALID";
            try { staleHandle.snapshot(); } catch (IllegalStateException expected) { stale = "STALE"; }
            var fresh = mesh().morphKeyform(mGuid, 1f);
            handle.put("oldHandle", stale); handle.put("fresh", formData(fresh.snapshot()));
            handle.put("freshHandle", Map.of("parameterGuid", fresh.parameterGuid(), "keyValue", fresh.keyValue()));
            handle.put("detached", formData(detachedWritten));
            check("redoneStale", stale.equals("STALE") && fresh.snapshot().equals(detachedWritten)
                && fresh.snapshot().formGuid().equals(uForm));
            var base = fresh.baseSnapshot();
            var cell = baseline.cells().stream().filter(c -> sorted(c.coordinates())
                .equals(sorted(OrdinaryWriteProbe.coordinates(baseline, X.value(), Y.value(), 0)))).toList();
            NativeAssertions.require(cell.size() == 1, "default tuple missing/ambiguous");
            var expectedBase = form(baseline, cell.get(0).formGuid());
            data.put("baseSnapshot", formData(base));
            check("baseIdentity", base.equals(expectedBase));
            return null;
        });
        step("mid", .5f, written, H, () -> setM(.5f));
        step("zero", 0, written, B, () -> setM(0));
        edt(() -> { isolation(transactions); return null; });
        step("isolatedWritten", 1, isolated, U2, () -> setM(1));
        step("isolatedMid", .5f, isolated, H, () -> setM(.5f));
        step("isolatedZero", 0, isolated, B, () -> setM(0));
        edt(() -> { controls(transactions); rejections(); return null; });
    }

    private void isolation(AuthoringTransactionService transactions) {
        var row = new LinkedHashMap<String, Object>(); data.put("isolation", row);
        var create = new LinkedHashMap<String, Object>(); row.put("create", create);
        var createBefore = history.get();
        var seed = new AtomicReference<Form>();
        var result = transactions.execute(AuthoringTransactionOptions.of("MI3D N4a create M.5"), () -> {
            var handle = mesh().createMorphKeyform(mGuid, .5f); seed.set(handle.snapshot()); return null;
        });
        var createAfter = history.get(); withHalf = stored();
        var halfKey = morphKey(withHalf, mGuid, .5f); hForm = halfKey.formGuid();
        create.put("result", resultData(result)); create.put("seed", formData(seed.get()));
        create.put("triple", Map.of("parameterGuid", halfKey.parameterGuid(), "keyValue", halfKey.keyValue().orElse(null), "formGuid", hForm));
        create.put("before", historyData(createBefore)); create.put("after", historyData(createAfter));
        create.put("snapshot", NativeAssertions.snapshot(withHalf));
        check("isolationCreateCommitted", result.outcome() == AuthoringTransactionOutcome.COMMITTED
            && createAfter.position() == createBefore.position() + 1);
        check("isolationCreateSeed", seed.get().positions().equals(H) && seed.get().drawOrder() == 500);
        check("isolationCreateCounts", withHalf.forms().size() == 11 && withHalf.morphKeys().size() == 2);
        check("isolationCreateTriple", form(withHalf, hForm).positions().equals(H)
            && form(withHalf, uForm).positions().equals(U));
        var write = new LinkedHashMap<String, Object>(); row.put("write", write);
        write.put("before", state());
        var hBefore = history.get();
        var writtenResult = transactions.execute(AuthoringTransactionOptions.of("MI3D N4a write U2"), () -> {
            mesh().morphKeyform(mGuid, 1f).setPositions(U2); return null;
        });
        var hAfter = history.get(); isolated = stored();
        write.put("result", resultData(writtenResult)); write.put("written", state());
        check("isolationWriteCommitted", writtenResult.outcome() == AuthoringTransactionOutcome.COMMITTED
            && hAfter.position() == hBefore.position() + 1);
        restore.accept(hBefore.position()); write.put("undone", state());
        check("isolationWriteUndo", stored().equals(withHalf));
        restore.accept(hAfter.position()); write.put("redone", state());
        check("isolationWriteRedo", stored().equals(isolated));
    }

    private void controls(AuthoringTransactionService transactions) {
        var controls = new LinkedHashMap<String, Object>(); data.put("controls", controls);
        controls.put("before", state());
        var createN = new LinkedHashMap<String, Object>(); controls.put("createN", createN);
        var nBefore = history.get();
        var nSeed = new AtomicReference<Form>();
        var nResult = transactions.execute(AuthoringTransactionOptions.of("MI3D N4a create N1"), () -> {
            var handle = mesh().createMorphKeyform(nGuid, 1f); nSeed.set(handle.snapshot()); return null;
        });
        var nAfter = history.get(); withN = stored();
        var nKey = morphKey(withN, nGuid, 1f); nForm = nKey.formGuid();
        createN.put("result", resultData(nResult)); createN.put("seed", formData(nSeed.get()));
        createN.put("triple", Map.of("parameterGuid", nKey.parameterGuid(), "keyValue", nKey.keyValue().orElse(null), "formGuid", nForm));
        createN.put("before", historyData(nBefore)); createN.put("after", historyData(nAfter));
        createN.put("snapshot", NativeAssertions.snapshot(withN));
        check("createNCommitted", nResult.outcome() == AuthoringTransactionOutcome.COMMITTED
            && nAfter.position() == nBefore.position() + 1);
        check("createNCounts", withN.forms().size() == 12 && withN.morphKeys().size() == 3);
        check("createNSeed", nSeed.get().positions().equals(B) && nSeed.get().drawOrder() == 500);
        check("createNTriple", form(withN, nForm).positions().equals(B));
        var committedRow = new LinkedHashMap<String, Object>(); controls.put("committed", committedRow);
        var cBefore = history.get(); committedRow.put("before", state());
        var seeds = new ArrayList<Form>(); int[] steps = {0};
        var committedResult = transactions.execute(AuthoringTransactionOptions.of("MI3D N4a four-step"), () -> {
            var h25 = mesh().createMorphKeyform(mGuid, .25f); seeds.add(h25.snapshot()); steps[0]++;
            mesh().morphKeyform(mGuid, .25f).setPositions(V); steps[0]++;
            var h75 = mesh().createMorphKeyform(mGuid, .75f); seeds.add(h75.snapshot()); steps[0]++;
            mesh().morphKeyform(mGuid, .75f).setPositions(W); steps[0]++;
            committedRow.put("mutated", state()); return null;
        });
        var cAfter = history.get(); committed = stored();
        committedRow.put("result", resultData(committedResult)); committedRow.put("steps", steps[0]);
        committedRow.put("seed25", formData(seeds.get(0))); committedRow.put("seed75", formData(seeds.get(1)));
        committedRow.put("after", state());
        var vKey = morphKey(committed, mGuid, .25f); var wKey = morphKey(committed, mGuid, .75f);
        committedRow.put("triples", List.of(
            Map.of("parameterGuid", vKey.parameterGuid(), "keyValue", vKey.keyValue().orElse(null), "formGuid", vKey.formGuid()),
            Map.of("parameterGuid", wKey.parameterGuid(), "keyValue", wKey.keyValue().orElse(null), "formGuid", wKey.formGuid())));
        check("committedCommitted", committedResult.outcome() == AuthoringTransactionOutcome.COMMITTED
            && cAfter.position() == cBefore.position() + 1);
        check("committedSteps", steps[0] == 4);
        check("committedSeed25", seeds.get(0).positions().equals(SEED25) && seeds.get(0).drawOrder() == 500);
        check("committedSeed75", seeds.get(1).positions().equals(SEED75) && seeds.get(1).drawOrder() == 500);
        check("committedCounts", committed.forms().size() == 14 && committed.morphKeys().size() == 5);
        check("committedTriples", form(committed, vKey.formGuid()).positions().equals(V)
            && form(committed, wKey.formGuid()).positions().equals(W));
        for (int round = 1; round <= 2; round++) {
            restore.accept(cBefore.position()); committedRow.put("undone" + round, state());
            check("committedUndo" + round, stored().equals(withN));
            restore.accept(cAfter.position()); committedRow.put("redone" + round, state());
            check("committedRedo" + round, stored().equals(committed));
        }
        restore.accept(cBefore.position()); committedRow.put("final", state());
        check("committedFinal", stored().equals(withN));
        var rolledBack = new LinkedHashMap<String, Object>(); controls.put("rolledBack", rolledBack);
        rolledBack.put("before", state());
        int[] rbSteps = {0}; var rbSeeds = new ArrayList<Form>();
        var preFailureN = new AtomicReference<ArtMeshMorphKeyform>();
        var preDetachedN = new AtomicReference<Form>();
        var rolled = transactions.execute(AuthoringTransactionOptions.of("MI3D N4a four-step rollback"), () -> {
            var h25 = mesh().createMorphKeyform(mGuid, .25f); rbSeeds.add(h25.snapshot()); rbSteps[0]++;
            mesh().morphKeyform(mGuid, .25f).setPositions(V); rbSteps[0]++;
            var h75 = mesh().createMorphKeyform(mGuid, .75f); rbSeeds.add(h75.snapshot()); rbSteps[0]++;
            mesh().morphKeyform(mGuid, .75f).setPositions(W); rbSteps[0]++;
            rolledBack.put("mutated", state());
            // Causal anchor: the tested N handle is acquired only after the final membership
            // mutation, so its later rejection can only come from this failure's compensation.
            var fresh = mesh().morphKeyform(nGuid, 1f);
            Form detached = null; String valid;
            try { detached = fresh.snapshot(); valid = "VALID"; }
            catch (IllegalStateException alreadyStale) { valid = "STALE"; }
            preFailureN.set(fresh); preDetachedN.set(detached);
            var pre = new LinkedHashMap<String, Object>(); rolledBack.put("preFailure", pre);
            pre.put("nHandle", valid);
            pre.put("handle", detached == null ? null
                : Map.of("parameterGuid", fresh.parameterGuid(), "keyValue", fresh.keyValue()));
            pre.put("detached", detached == null ? null : formData(detached));
            rolledBack.put("callbackFailure", "N4A_ABORT");
            throw new IllegalStateException("N4A_ABORT");
        });
        rolledBack.put("steps", rbSteps[0]); rolledBack.put("result", resultData(rolled));
        rolledBack.put("seed25", formData(rbSeeds.get(0))); rolledBack.put("seed75", formData(rbSeeds.get(1)));
        rolledBack.put("after", state());
        String nStale = "VALID";
        try { preFailureN.get().snapshot(); } catch (IllegalStateException expected) { nStale = "STALE"; }
        var restored = mesh().morphKeyform(nGuid, 1f);
        var post = new LinkedHashMap<String, Object>(); rolledBack.put("postFailure", post);
        post.put("nHandle", nStale);
        post.put("relooked", formData(restored.snapshot()));
        post.put("relookedHandle", Map.of("parameterGuid", restored.parameterGuid(), "keyValue", restored.keyValue()));
        post.put("detached", preDetachedN.get() == null ? null : formData(preDetachedN.get()));
        @SuppressWarnings("unchecked") var pre = (Map<String, Object>) rolledBack.get("preFailure");
        check("rollbackFourStep", rbSteps[0] == 4 && rolled.outcome() == AuthoringTransactionOutcome.ROLLED_BACK
            && rolledBack.get("before").equals(rolledBack.get("after")));
        check("rollbackStaleN", pre != null && "VALID".equals(pre.get("nHandle")) && nStale.equals("STALE")
            && restored.snapshot().equals(preDetachedN.get())
            && restored.snapshot().formGuid().equals(nForm)
            && restored.parameterGuid().equals(nGuid) && restored.keyValue() == 1f);
        var write = new LinkedHashMap<String, Object>(); controls.put("positionWrite", write);
        var freshN = mesh().morphKeyform(nGuid, 1f); var detachedN = freshN.snapshot();
        write.put("detachedN", formData(detachedN));
        write.put("detachedNHandle", Map.of("parameterGuid", freshN.parameterGuid(), "keyValue", freshN.keyValue()));
        var noopBefore = state();
        var noop = transactions.execute(AuthoringTransactionOptions.of("MI3D N4a noop"), () -> {
            mesh().morphKeyform(mGuid, 1f).setPositions(U2); return null;
        });
        var noopAfter = state();
        String nStillValid = "VALID";
        try { freshN.snapshot(); } catch (IllegalStateException expected) { nStillValid = "STALE"; }
        write.put("noop", Map.of("result", resultData(noop), "before", noopBefore, "after", noopAfter, "nHandle", nStillValid));
        check("noop", noop.outcome() == AuthoringTransactionOutcome.NO_CHANGE
            && noopBefore.equals(noopAfter) && nStillValid.equals("VALID"));
        var wBefore = history.get();
        var positionResult = transactions.execute(AuthoringTransactionOptions.of("MI3D N4a position write"), () -> {
            mesh().morphKeyform(mGuid, 1f).setPositions(V); return null;
        });
        var wAfter = history.get();
        write.put("result", resultData(positionResult)); write.put("written", state());
        check("positionWriteCommitted", positionResult.outcome() == AuthoringTransactionOutcome.COMMITTED
            && wAfter.position() == wBefore.position() + 1);
        // The tested handle must still be valid after the normal commit; only the Undo below may stale it.
        String validAfterCommit = "VALID";
        try { freshN.snapshot(); } catch (IllegalStateException expected) { validAfterCommit = "STALE"; }
        write.put("nHandleAfterCommit", validAfterCommit);
        check("positionWriteValidN", validAfterCommit.equals("VALID"));
        restore.accept(wBefore.position());
        String staleAfterUndo = "VALID";
        try { freshN.snapshot(); } catch (IllegalStateException expected) { staleAfterUndo = "STALE"; }
        var relooked = mesh().morphKeyform(nGuid, 1f);
        var undo = new LinkedHashMap<String, Object>(); write.put("writeUndo", undo);
        undo.put("nHandle", staleAfterUndo); undo.put("relooked", formData(relooked.snapshot()));
        undo.put("relookedHandle", Map.of("parameterGuid", relooked.parameterGuid(), "keyValue", relooked.keyValue()));
        undo.put("state", state());
        check("positionWriteStaleN", staleAfterUndo.equals("STALE") && relooked.snapshot().equals(detachedN)
            && stored().equals(withN));
    }

    private void rejections() {
        var rejects = new LinkedHashMap<String, Object>(); data.put("rejections", rejects);
        reject(rejects, "createZero", IllegalArgumentException.class, () -> mesh().createMorphKeyform(mGuid, 0f));
        reject(rejects, "createNegative", IllegalArgumentException.class, () -> mesh().createMorphKeyform(mGuid, -.5f));
        reject(rejects, "createAboveOne", IllegalArgumentException.class, () -> mesh().createMorphKeyform(mGuid, 1.5f));
        reject(rejects, "createNaN", IllegalArgumentException.class, () -> mesh().createMorphKeyform(mGuid, Float.NaN));
        reject(rejects, "createInfinity", IllegalArgumentException.class, () -> mesh().createMorphKeyform(mGuid, Float.POSITIVE_INFINITY));
        reject(rejects, "createDuplicate", IllegalArgumentException.class, () -> mesh().createMorphKeyform(mGuid, 1f));
        reject(rejects, "createMissingParameter", NoSuchElementException.class, () -> mesh().createMorphKeyform("mi3d-n4a-absent-guid", .5f));
        reject(rejects, "lookupZero", IllegalArgumentException.class, () -> mesh().morphKeyform(mGuid, 0f));
        reject(rejects, "lookupMissing", NoSuchElementException.class, () -> mesh().morphKeyform(mGuid, .9f));
        reject(rejects, "lookupOrdinary", UnsupportedOperationException.class, () -> mesh().morphKeyform(xGuid, .5f));
        reject(rejects, "wrongVertexCount", IllegalArgumentException.class,
            () -> mesh().morphKeyform(mGuid, 1f).setPositions(U.subList(0, 1)));
        reject(rejects, "staleRead", IllegalStateException.class, () -> staleHandle.snapshot());
        reject(rejects, "staleWrite", IllegalStateException.class, () -> staleHandle.setPositions(U));
        // Epsilon-near runs last: if a host snap epsilon is smaller than assumed, this can only
        // fail its own check and cannot corrupt any earlier evidence; final Undo still restores.
        reject(rejects, "createEpsilonNear", IllegalArgumentException.class, () -> mesh().createMorphKeyform(mGuid, .9999f));
    }

    private void reject(Map<String, Object> rejects, String name, Class<? extends RuntimeException> expected, Runnable action) {
        var before = state(); String actual = "NOT_REJECTED";
        try { action.run(); } catch (RuntimeException error) { actual = error.getClass().getName(); }
        var after = state(); rejects.put(name, Map.of("before", before, "after", after, "exception", actual));
        check(name, actual.equals(expected.getName()) && before.equals(after));
    }

    private void step(String name, float m, ArtMeshAuthoringSnapshot expectedStored, List<Point2> expected,
            Runnable action) throws Exception {
        var row = new LinkedHashMap<String, Object>(); samples.add(row);
        row.put("id", name); row.put("m", m); row.put("x", 0); row.put("y", 0); row.put("n", 0); row.put("status", "RUNNING");
        row.put("expectedPositions", positions(expected)); row.put("waitLimitMillis", EvaluationProbe.WAIT_MILLIS);
        try {
            var requested = edt(() -> {
                current.get(); row.put("before", EvaluationProbe.evaluation(observe())); action.run();
                var h = history.get(); var s = stored(); var e = observe();
                if (expectedStored != null) NativeAssertions.require(s.equals(expectedStored), "operation changed unexpected stored fields");
                NativeAssertions.require(value(e, X) == 0 && value(e, Y) == 0 && value(e, M) == m && value(e, N) == 0,
                    "requested parameter value mismatch");
                row.put("requested", EvaluationProbe.evaluation(e)); row.put("history", historyData(h));
                row.put("stored", NativeAssertions.snapshot(s)); return e;
            });
            var actual = EvaluationProbe.awaitConsumed(requested, () -> edt(() -> {
                var h0 = history.get(); var s = stored(); var e = observe(); var h1 = history.get();
                row.put("readHistoryBefore", historyData(h0)); row.put("readHistoryAfter", historyData(h1));
                row.put("observedStored", NativeAssertions.snapshot(s));
                NativeAssertions.require(h0.equals(h1) && historyData(h1).equals(row.get("history")),
                    "evaluation read/wait changed history");
                if (expectedStored != null) NativeAssertions.require(s.equals(expectedStored), "evaluation read/wait changed stored model");
                return e;
            }), row::putAll, System::nanoTime, Thread::sleep);
            row.put("actual", EvaluationProbe.evaluation(actual));
            row.put("maxErrorPixels", EvaluationProbe.maxError(actual, expected));
            check(name, EvaluationProbe.validGeometry(actual, expected, 500));
            row.put("status", "PASS");
        } catch (Exception | Error failure) {
            row.put("status", "FAIL"); row.put("failure", failure.toString()); checkFailure(name); throw failure;
        }
    }

    private Drawable mesh() { return current.get().drawables().find(id); }
    private ArtMeshAuthoringSnapshot stored() { return mesh().authoringSnapshot(); }
    private ArtMeshEvaluationSnapshot observe() { return mesh().evaluationSnapshot(); }
    private void setM(float value) { current.get().parameters().find(M).setValue(value); }
    private String guid(ParameterId parameter) {
        var found = observe().liveParameters().parameters().stream().filter(v -> v.parameterId().equals(parameter)).toList();
        NativeAssertions.require(found.size() == 1, "parameter GUID unresolved/ambiguous");
        return found.get(0).parameterGuid();
    }
    static MorphKey morphKey(ArtMeshAuthoringSnapshot s, String parameterGuid, float key) {
        var found = s.morphKeys().stream().filter(k -> k.parameterGuid().equals(parameterGuid)
            && k.keyValue().equals(Optional.of(key))).toList();
        NativeAssertions.require(found.size() == 1, "morph triple missing/ambiguous"); return found.get(0);
    }
    static Form form(ArtMeshAuthoringSnapshot s, String formGuid) {
        var found = s.forms().stream().filter(f -> f.formGuid().equals(formGuid)).toList();
        NativeAssertions.require(found.size() == 1, "form missing/ambiguous"); return found.get(0);
    }
    private static List<ArtMeshAuthoringSnapshot.Coordinate> sorted(List<ArtMeshAuthoringSnapshot.Coordinate> c) {
        return c.stream().sorted(java.util.Comparator.comparing(ArtMeshAuthoringSnapshot.Coordinate::parameterGuid)).toList();
    }
    private static float value(ArtMeshEvaluationSnapshot e, ParameterId parameter) {
        var matches = e.liveParameters().parameters().stream().filter(v -> v.parameterId().equals(parameter)).toList();
        NativeAssertions.require(matches.size() == 1, "parameter identity ambiguous/missing"); return matches.get(0).value();
    }
    private Map<String, Object> state() {
        current.get();
        var row = new LinkedHashMap<String, Object>();
        row.put("snapshot", NativeAssertions.snapshot(stored())); row.put("history", historyData(history.get()));
        row.put("parameters", List.of(current.get().parameters().find(X).getValue(), current.get().parameters().find(Y).getValue(),
            current.get().parameters().find(M).getValue(), current.get().parameters().find(N).getValue()));
        return row;
    }
    private static Map<String, Object> formData(Form f) {
        var row = new LinkedHashMap<String, Object>();
        row.put("formGuid", f.formGuid()); row.put("positions", positions(f.positions())); row.put("drawOrder", f.drawOrder());
        return row;
    }
    private void check(String id, boolean actual) {
        NativeAssertions.require(checks.stream().noneMatch(c -> id.equals(c.get("id"))), "duplicate N4a check");
        checks.add(Map.of("id", id, "actual", actual, "status", actual ? "PASS" : "FAIL", "evidenceKind", "EXACT_HOST"));
        NativeAssertions.require(actual, "N4a " + id);
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

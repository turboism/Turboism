package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.history.HistoryEntry;
import dev.turboism.sdk.cubism.history.HistoryEntryId;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot;
import dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.Axis;
import dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.Cell;
import dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.Coordinate;
import dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.Form;
import dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.MorphKey;
import dev.turboism.sdk.cubism.model.ArtMeshEvaluationSnapshot;
import dev.turboism.sdk.cubism.model.ArtMeshEvaluationSnapshot.ParameterState;
import dev.turboism.sdk.cubism.model.ArtMeshEvaluationSnapshot.ParameterValue;
import dev.turboism.sdk.cubism.model.Point2;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Synthetic assertions for the N4b morph-order oracle helpers and history bank (not a host run). */
final class MorphOrderAssertionsTest {
    private static int checks;

    private MorphOrderAssertionsTest() { }

    static void run() throws Exception {
        check(MorphOrderProbe.B.equals(MorphProbe.B) && MorphOrderProbe.V.equals(MorphProbe.V)
            && MorphOrderProbe.WRITE_A == 1000 && MorphOrderProbe.WRITE_B == 0
            && MorphOrderProbe.TRUNC == 701 && MorphOrderProbe.SECOND == 299 && MorphOrderProbe.MIDWRITE == 800
            && MorphOrderProbe.TRIPLE_ORDERS.length == 3, "N4b frozen fixture constants drifted");
        var s = snap();
        var single = MorphOrderProbe.withMember(s, "g-m", 1f, "f-m", MorphOrderProbe.B, 500);
        check(single.forms().size() == 10 && single.morphKeys().size() == 1
            && !single.equals(s) && single.cells().equals(s.cells()), "withMember baseline");
        var both = MorphOrderProbe.withMember(single, "g-n", 1f, "f-n", MorphOrderProbe.B, 500);
        check(both.forms().size() == 11 && both.morphKeys().size() == 2, "withMember second target");
        var written = MorphOrderProbe.ordered(both, "f-m", 1000);
        check(written.forms().stream().filter(f -> f.formGuid().equals("f-m"))
            .findFirst().orElseThrow().drawOrder() == 1000
            && written.morphKeys().equals(both.morphKeys())
            && written.cells().equals(both.cells()) && !written.equals(both), "ordered changes only order");
        for (var bad : List.<Runnable>of(
            () -> MorphOrderProbe.ordered(both, "f-m", -1), () -> MorphOrderProbe.ordered(both, "f-m", 1001),
            () -> MorphOrderProbe.ordered(both, "f-src", 100), () -> MorphOrderProbe.ordered(both, "f-0-0", 100),
            () -> MorphOrderProbe.ordered(both, "absent", 100),
            () -> MorphOrderProbe.withMember(both, "g-m", 1f, "f-m", MorphOrderProbe.B, 500),
            () -> MorphOrderProbe.withMember(both, "g-m", 1f, "f-q", MorphOrderProbe.B, 500),
            () -> MorphOrderProbe.withMember(both, "g-m", .5f, "f-0-0", MorphOrderProbe.B, 500),
            () -> MorphOrderProbe.withMember(single, "g-m", 0f, "f-q", MorphOrderProbe.B, 500),
            () -> MorphOrderProbe.withMember(single, "g-m", Float.NaN, "f-q", MorphOrderProbe.B, 500),
            () -> MorphOrderProbe.withMember(single, "g-m", 1.5f, "f-q", MorphOrderProbe.B, 500),
            () -> MorphOrderProbe.withMember(single, "g-m", .5f, "f-q", MorphOrderProbe.B, -1),
            () -> MorphOrderProbe.withMember(single, "g-m", .5f, "f-q", MorphOrderProbe.B, 1001),
            () -> MorphOrderProbe.withMember(single, "g-m", .5f, "f-q", List.of(new Point2(1, 1)), 100)))
            reject(bad::run);
        var nullKeyed = MorphOrderProbe.withMember(single, "g-n", 1f, "f-null", MorphOrderProbe.B, 500);
        var aliased = new ArtMeshAuthoringSnapshot(nullKeyed.artMeshId(), nullKeyed.sourceGuid(),
            nullKeyed.sourceGeometry(), nullKeyed.axes(), nullKeyed.cells(), nullKeyed.forms(),
            List.of(new MorphKey("g-m", "f-m", Optional.of(1f)),
                new MorphKey("g-n", "f-null", Optional.of(1f)), new MorphKey("g-n", "f-null", Optional.empty())));
        reject(() -> MorphOrderProbe.ordered(aliased, "f-null", 100));
        var bank = new MorphOrderProbe.HistoryBank();
        var h1 = history(1); var h2 = history(2);
        var r1 = bank.ref("FACADE", h1); var r2 = bank.ref("FACADE", h1); var r3 = bank.ref("FACADE", h2);
        var r4 = bank.ref("TRANSACTION", h1);
        check(r1.equals(r2) && !r1.equals(r3) && !r1.equals(r4) && bank.entries().size() == 3,
            "history bank dedup/domain identity");
        check(r1.keySet().equals(java.util.Set.of("historyRef", "historyDomain"))
            && "FACADE".equals(r1.get("historyDomain")) && "TRANSACTION".equals(r4.get("historyDomain")),
            "history ref shape");
        var entry = bank.entries().get(r1.get("historyRef"));
        check(entry.keySet().equals(java.util.Set.of("historyDomain", "history"))
            && "FACADE".equals(entry.get("historyDomain")), "history bank entry shape");
        var spam = new MorphOrderProbe.HistoryBank();
        for (int i = 0; i < 600; i++) spam.ref("FACADE", h1);
        check(spam.entries().size() == 1, "unretained ref calls must not spend the budget");
        var retained = new java.util.ArrayList<Object>();
        for (int i = 0; i < 512; i++) retained.add(spam.ref("FACADE", h1));
        check(spam.finish(retained).size() == 1, "512 retained references accepted");
        var excess = new MorphOrderProbe.HistoryBank();
        var big = new java.util.ArrayList<Object>();
        for (int i = 0; i < 513; i++) big.add(excess.ref("FACADE", h1));
        reject(() -> excess.finish(big));
        var pruned = new MorphOrderProbe.HistoryBank();
        var keep = pruned.ref("FACADE", h1); pruned.ref("FACADE", h2);
        check(pruned.finish(List.of(keep)).size() == 1 && pruned.entries().containsKey(keep.get("historyRef")),
            "finish prunes references the report does not retain");
        var overflow = new MorphOrderProbe.HistoryBank();
        for (int i = 0; i < 256; i++) overflow.ref("FACADE", history(i));
        reject(() -> overflow.ref("FACADE", history(999)));
        // Real awaiter path with injected clock/sleeper (no host, no wall-clock wait): many
        // overwritten sampling rounds must not spend the retained-reference budget.
        var old = observation(7); var fresh = observation(8);
        var slowBank = new MorphOrderProbe.HistoryBank();
        var report = new LinkedHashMap<String, Object>();
        var calls = new AtomicInteger(); var refCalls = new AtomicInteger(); var now = new AtomicLong();
        var actual = EvaluationProbe.awaitConsumed(old, () -> {
            report.put("readHistoryBefore", slowBank.ref("FACADE", history(1)));
            report.put("readHistoryAfter", slowBank.ref("FACADE", history(1)));
            var temporary = slowBank.ref("FACADE", history(1));
            if (!temporary.equals(report.get("readHistoryBefore"))) throw new AssertionError();
            refCalls.addAndGet(3);
            return calls.incrementAndGet() == 550 ? fresh : old;
        }, report::putAll, now::get, millis -> {
            if (millis != 25) throw new AssertionError("poll interval changed");
            now.addAndGet(millis * 1_000_000);
        });
        check(actual == fresh && calls.get() == 550 && refCalls.get() == 1650
            && "CONSUMED".equals(report.get("waitReason"))
            && Integer.valueOf(550).equals(report.get("attempts"))
            && now.get() == 13_725_000_000L && slowBank.finish(report).size() == 1,
            "slow consumption must not fail early on temporary refs");
        System.out.println("N4B OFFLINE_FIXTURE slowConsumption PASS attempts=550 refCalls=1650 "
            + "bankEntries=1 retainedRefs=2 simulatedMillis=13725; clock/sleeper injected; hostExecuted=false");
        var timeout = new LinkedHashMap<String, Object>(); var tick = new AtomicLong();
        try {
            EvaluationProbe.awaitConsumed(old, () -> old, timeout::putAll, tick::get, millis -> {
                if (millis != 25) throw new AssertionError("poll interval changed");
                tick.addAndGet(millis * 1_000_000);
            });
            throw new AssertionError("timeout missing");
        } catch (IllegalStateException expected) {
            check("TIMEOUT".equals(timeout.get("waitReason")) && timeout.get("lastObservation") != null
                && tick.get() == 15_000_000_000L, "timeout protocol drift");
        }
        System.out.println("N4B OFFLINE_FIXTURE timeout PASS simulatedMillis=15000 "
            + "lastObservation retained; hostExecuted=false");
        System.out.println("N4B OFFLINE_FIXTURE checks=" + checks + " PASS; hostExecuted=false");
    }

    private static ArtMeshAuthoringSnapshot snap() {
        var axes = List.of(new Axis("g-x", new ParameterId("ParamMI3DN4BX"), -1, 0, 1, List.of(-1f, 0f, 1f)),
            new Axis("g-y", new ParameterId("ParamMI3DN4BY"), -1, 0, 1, List.of(-1f, 0f, 1f)));
        var cells = new ArrayList<Cell>(); var forms = new ArrayList<Form>();
        for (int xi = 0; xi < 3; xi++) for (int yi = 0; yi < 3; yi++) {
            var guid = "f-" + xi + "-" + yi;
            cells.add(new Cell(guid, List.of(new Coordinate("g-x", xi - 1), new Coordinate("g-y", yi - 1))));
            forms.add(new Form(guid, MorphOrderProbe.B, 500));
        }
        return new ArtMeshAuthoringSnapshot(new ArtMeshId("m-n4b"), "f-src", NativeAssertions.GEOMETRY,
            axes, cells, forms, List.of());
    }

    private static ArtMeshEvaluationSnapshot observation(int consumedVersion) {
        var values = List.of(new ParameterValue("gm", new ParameterId("M"), .5f));
        return new ArtMeshEvaluationSnapshot(new ArtMeshId("mesh"), "source", Optional.empty(),
            new ParameterState(777, values), new ParameterState(consumedVersion, values), 10);
    }

    private static HistorySnapshot history(int position) {
        var entries = new ArrayList<HistoryEntry>();
        for (int i = 0; i < position; i++)
            entries.add(new HistoryEntry(i, "entry " + i, true, Optional.empty(),
                Optional.of(new HistoryEntryId("e" + i)), Optional.empty()));
        return new HistorySnapshot(HistorySnapshot.Availability.AVAILABLE, 1, position, position,
            entries, true, true, "doc", "mgr");
    }

    private static void check(boolean value, String message) { NativeAssertions.require(value, message); checks++; }

    private static void reject(Runnable action) {
        try { action.run(); } catch (IllegalStateException | IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError("invalid morph-order fixture accepted");
    }
}

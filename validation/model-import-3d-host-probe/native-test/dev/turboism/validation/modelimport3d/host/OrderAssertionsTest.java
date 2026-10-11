package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot;
import dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.*;
import dev.turboism.sdk.cubism.model.ArtMeshEvaluationSnapshot;
import dev.turboism.sdk.cubism.model.Point2;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/** Pure SDK values and the shared bounded waiter; never instantiates a host plugin. */
public final class OrderAssertionsTest {
    private static int checks;
    private static final List<Point2> BASE = List.of(new Point2(16, 24), new Point2(272, 24), new Point2(272, 152), new Point2(16, 152));

    public static void run() throws Exception {
        var cells = new ArrayList<Cell>(); var forms = new ArrayList<Form>();
        for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++) {
            String guid = x + ":" + y;
            cells.add(new Cell(guid, List.of(new Coordinate("gy", y), new Coordinate("gx", x))));
            forms.add(new Form(guid, BASE, 500));
        }
        var s = fixture(cells, forms, List.of());
        var written = OrderProbe.changed(OrderProbe.changed(s, "1:0", null, 1000), "-1:0", null, 0);
        for (var form : written.forms()) check(form.positions().equals(BASE) && form.drawOrder()
            == (form.formGuid().equals("1:0") ? 1000 : form.formGuid().equals("-1:0") ? 0 : 500), "frozen isolated order");
        check(s.forms().equals(forms) && s.sourceGeometry().equals(written.sourceGeometry())
            && s.axes().equals(written.axes()) && s.cells().equals(written.cells()), "source and original detached");
        check(OrderProbe.changed(written, "1:0", null, 1000).equals(written), "no-change oracle");
        for (int bad : List.of(-1, 1001, Integer.MIN_VALUE, Integer.MAX_VALUE)) reject(() -> OrderProbe.changed(s, "1:0", null, bad));
        reject(() -> OrderProbe.changed(s, "0:0", null, 500));
        reject(() -> OrderProbe.changed(s, "missing", null, 600));
        reject(() -> OrderProbe.changed(s, "1:0", BASE.subList(0, 3), null));
        for (var key : List.of(Optional.<Float>empty(), Optional.of(0f), Optional.of(.5f))) {
            var alias = fixture(cells, forms, List.of(new MorphKey("gm", "1:0", key)));
            reject(() -> OrderProbe.changed(alias, "1:0", null, 600));
        }
        var shared = new ArrayList<>(cells); shared.set(0, new Cell("1:0", shared.get(0).coordinates()));
        reject(() -> OrderProbe.changed(fixture(shared, forms, List.of()), "1:0", null, 600));
        var old = observation(10, 750); var wrong = observation(11, 749); var good = observation(12, 750);
        check(EvaluationProbe.consumptionReason(old, old).equals("WAITING_PASS"), "right order but stale stamp not accepted");
        check(EvaluationProbe.consumptionReason(old, wrong).equals("CONSUMED")
            && !EvaluationProbe.validGeometry(wrong, BASE, 750), "new pass with wrong order fails immediately");
        check(EvaluationProbe.validGeometry(good, BASE, 750), "midpoint exact order");
        var calls = new AtomicInteger(); var progress = new LinkedHashMap<String, Object>();
        var observed = EvaluationProbe.awaitConsumed(old, () -> calls.incrementAndGet() == 1 ? wrong : good,
            progress::putAll, () -> 0L, ms -> {});
        check(observed == wrong && calls.get() == 1, "never wait away a first-consumed order error");
        var unrun = new NativeReport("synthetic-only");
        check(unrun.order.get("status").equals("NOT_RUN") && unrun.order.get("samples").equals(List.of())
            && unrun.order.get("checks").equals(List.of()), "unobserved order is NOT_RUN");
        System.out.println("N3C OFFLINE_FIXTURE checks=" + checks + " PASS; hostExecuted=false");
    }
    private static ArtMeshAuthoringSnapshot fixture(List<Cell> cells, List<Form> forms, List<MorphKey> morph) {
        return new ArtMeshAuthoringSnapshot(new ArtMeshId("order-mesh"), "order-source", NativeAssertions.GEOMETRY,
            List.of(new Axis("gx", new ParameterId("ParamMI3DN2X"), -1, 0, 1, List.of(-1f, 0f, 1f)),
                new Axis("gy", new ParameterId("ParamMI3DN2Y"), -1, 0, 1, List.of(-1f, 0f, 1f))), cells, forms, morph);
    }
    private static ArtMeshEvaluationSnapshot observation(int consumed, int order) {
        var values = List.of(new ArtMeshEvaluationSnapshot.ParameterValue("gx", new ParameterId("x"), .5f));
        return new ArtMeshEvaluationSnapshot(new ArtMeshId("order-mesh"), "order-source",
            Optional.of(new ArtMeshEvaluationSnapshot.Form(BASE, order, "Canvas", true)),
            new ArtMeshEvaluationSnapshot.ParameterState(100, values), new ArtMeshEvaluationSnapshot.ParameterState(consumed, values), 100);
    }
    private static void check(boolean value, String message) { NativeAssertions.require(value, message); checks++; }
    private static void reject(Runnable action) {
        try { action.run(); } catch (IllegalStateException | IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError("invalid order fixture accepted");
    }
}

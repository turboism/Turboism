package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.ArtMeshEvaluationSnapshot;
import dev.turboism.sdk.cubism.model.ArtMeshEvaluationSnapshot.Form;
import dev.turboism.sdk.cubism.model.ArtMeshEvaluationSnapshot.ParameterState;
import dev.turboism.sdk.cubism.model.ArtMeshEvaluationSnapshot.ParameterValue;
import dev.turboism.sdk.cubism.model.Point2;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Pure SDK value/clock tests, never loads or evaluates Cubism classes. */
public final class EvaluationAssertionsTest {
    private static int checks;
    private static final List<Point2> EXPECTED = List.of(new Point2(32, 32), new Point2(288, 32), new Point2(288, 160), new Point2(32, 160));

    public static void run() throws Exception {
        var old = observation(77, 7, .5f, .5f, EXPECTED);
        var fresh = observation(77, 8, .5f, .5f, EXPECTED);
        check(EvaluationProbe.consumptionReason(old, old).equals("WAITING_PASS"), "correct geometry cannot certify a pass");
        check(EvaluationProbe.consumptionReason(old, fresh).equals("CONSUMED"), "independent counter domains accepted");
        var wrong = observation(77, 8, .5f, .5f, NativeAssertions.GEOMETRY.positions());
        check(EvaluationProbe.consumptionReason(old, wrong).equals("CONSUMED")
            && !EvaluationProbe.validGeometry(wrong, EXPECTED, 500), "bad geometry is rejected after consumption, never waited away");
        check(EvaluationProbe.validGeometry(fresh, EXPECTED, 500), "frozen midpoint matches");
        check(EvaluationProbe.consumptionReason(old, observation(77, 8, .5f, 0, EXPECTED)).equals("WAITING_CONSUMPTION"), "stale values wait");
        check(EvaluationProbe.consumptionReason(old, observation(78, 8, 1, 1, EXPECTED)).equals("LIVE_CHANGED"), "other live write rejected");
        check(EvaluationProbe.consumptionReason(old, new ArtMeshEvaluationSnapshot(new ArtMeshId("other"), "source",
            fresh.calculatedForm(), fresh.liveParameters(), fresh.consumedParameters(), 10)).equals("IDENTITY_CHANGED"), "wrong object rejected");
        var empty = new ArtMeshEvaluationSnapshot(old.artMeshId(), old.sourceGuid(), Optional.empty(),
            old.liveParameters(), new ParameterState(8, List.of()), 10);
        check(EvaluationProbe.consumptionReason(old, empty).equals("WAITING_CONSUMPTION"), "empty consumed is not fresh");
        var noForm = new ArtMeshEvaluationSnapshot(old.artMeshId(), old.sourceGuid(), Optional.empty(),
            fresh.liveParameters(), fresh.consumedParameters(), 10);
        check(EvaluationProbe.consumptionReason(old, noForm).equals("CONSUMED")
            && !EvaluationProbe.validGeometry(noForm, EXPECTED, 500), "missing form is not success");
        check(EvaluationProbe.consumptionReason(observation(1, Integer.MAX_VALUE, .5f, .5f, EXPECTED),
            observation(1, Integer.MIN_VALUE, .5f, .5f, EXPECTED)).equals("CONSUMED"), "native signed overflow retained");
        var perturbed = new ArrayList<>(EXPECTED); perturbed.set(0, new Point2(32.05f, 32.05f));
        check(EvaluationProbe.validGeometry(observation(1, 2, .5f, .5f, perturbed), EXPECTED, 500), "Euclidean error inside .1");
        perturbed.set(0, new Point2(32.08f, 32.08f));
        check(!EvaluationProbe.validGeometry(observation(1, 2, .5f, .5f, perturbed), EXPECTED, 500), "two .08 errors exceed Euclidean .1");
        check(!EvaluationProbe.validGeometry(fresh, EXPECTED, 501), "order must remain unchanged");
        var badSpace = new ArtMeshEvaluationSnapshot(old.artMeshId(), old.sourceGuid(),
            Optional.of(new Form(EXPECTED, 500, "Basic Coord", true)), fresh.liveParameters(), fresh.consumedParameters(), 10);
        check(!EvaluationProbe.validGeometry(badSpace, EXPECTED, 500), "unknown coordinate contract rejected");
        var calls = new AtomicInteger(); var clock = new AtomicLong(); var progress = new LinkedHashMap<String, Object>();
        var actual = EvaluationProbe.awaitConsumed(old, () -> calls.incrementAndGet() == 1 ? old : wrong,
            progress::putAll, clock::get, ms -> clock.addAndGet(ms * 1_000_000));
        check(actual == wrong && calls.get() == 2 && progress.get("waitReason").equals("CONSUMED"),
            "awaiter fixes FIRST consumed observation even if geometry wrong");
        var timeout = new LinkedHashMap<String, Object>(); clock.set(0);
        reject(() -> EvaluationProbe.awaitConsumed(old, () -> old, timeout::putAll, clock::get,
            ms -> clock.addAndGet(15_000_000_000L)), "TIMEOUT");
        check(timeout.get("waitReason").equals("TIMEOUT") && timeout.get("lastObservation") != null, "timeout retains last observation");
        calls.set(0); Thread.currentThread().interrupt();
        try {
            EvaluationProbe.awaitConsumed(old, () -> { calls.incrementAndGet(); return fresh; }, progress::putAll, clock::get, ms -> {});
            throw new AssertionError("interrupted read accepted");
        } catch (InterruptedException expected) { check(calls.get() == 0, "interrupted worker does not sample"); }
        finally { Thread.interrupted(); }
        for (String property : List.of("sun.java2d.opengl", "jogl.gljpanel.noogl")) {
            String saved = System.getProperty(property);
            try {
                for (String value : List.of("true", "True", "", "unknown")) {
                    System.setProperty(property, value); reject(EvaluationProbe::configuration, "unreviewed rendering configuration");
                    check(System.getProperty(property).equals(value), "configuration reader does not mutate properties");
                }
            } finally { if (saved == null) System.clearProperty(property); else System.setProperty(property, saved); }
        }
        var unrun = new NativeReport("synthetic-only");
        check(unrun.evaluation.get("status").equals("NOT_RUN") && unrun.evaluation.get("samples").equals(List.of()), "unobserved evaluation is NOT_RUN");
        System.out.println("N3B OFFLINE_FIXTURE checks=" + checks + " PASS; hostExecuted=false");
    }

    private static ArtMeshEvaluationSnapshot observation(int liveVersion, int consumedVersion, float live, float consumed, List<Point2> positions) {
        return new ArtMeshEvaluationSnapshot(new ArtMeshId("mesh"), "source", Optional.of(new Form(positions, 500, "DeformerLocal", true)),
            new ParameterState(liveVersion, List.of(new ParameterValue("gx", new ParameterId("x"), live))),
            new ParameterState(consumedVersion, List.of(new ParameterValue("gx", new ParameterId("x"), consumed))), 10);
    }
    private static void check(boolean value, String message) { NativeAssertions.require(value, message); checks++; }
    @FunctionalInterface private interface Operation { Object run() throws Exception; }
    private static void reject(Operation operation, String message) throws Exception {
        try { operation.run(); } catch (IllegalStateException expected) {
            check(expected.getMessage().contains(message), "precise refusal: " + message); return;
        }
        throw new AssertionError("expected rejection: " + message);
    }
}

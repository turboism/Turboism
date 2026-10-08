package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.command.EditorCanvasSettingsRequest;
import dev.turboism.sdk.cubism.command.EditorCommandResult;
import dev.turboism.sdk.cubism.command.EditorCommandService;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ParameterBindingPointId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot;
import dev.turboism.sdk.cubism.model.ArtMeshEvaluationSnapshot;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.ParameterBindingPoint;
import dev.turboism.sdk.cubism.model.ParameterBindingTarget;
import dev.turboism.sdk.cubism.model.ParameterDefinition;
import dev.turboism.sdk.cubism.model.ParameterType;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOptions;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOutcome;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionService;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;

/** SDK-only N3b observer. Paint is allowed to run between EDT samples; never polls expected geometry. */
final class EvaluationProbe {
    static final String JOGL_SHA = "7dbedb4ba89d9744aa8f8e3710436ef349547058c78e5b3beb813b27b382b0b9";
    static final long WAIT_MILLIS = 15_000;
    private static final ParameterId X = new ParameterId("ParamMI3DN2X"), Y = new ParameterId("ParamMI3DN2Y");
    private final Supplier<CubismModel> current;
    private final Supplier<HistorySnapshot> history;
    private final IntConsumer restore;
    private final Map<String, Object> data;
    private final List<Map<String, Object>> samples = new ArrayList<>(), checks = new ArrayList<>();
    private ArtMeshId id;
    private ArtMeshAuthoringSnapshot baseline, written;
    private HistorySnapshot writeBefore, writeAfter;

    private EvaluationProbe(Supplier<CubismModel> current, Supplier<HistorySnapshot> history,
            IntConsumer restore, Map<String, Object> data) {
        this.current = current; this.history = history; this.restore = restore; this.data = data;
        data.put("status", "RUNNING"); data.put("samples", samples); data.put("checks", checks);
    }

    static void run(Supplier<CubismModel> current, Supplier<HistorySnapshot> history, IntConsumer restore,
            EditorCommandService commands, AuthoringTransactionService transactions, Map<String, Object> data) throws Exception {
        NativeAssertions.require(!SwingUtilities.isEventDispatchThread(), "N3b waiting must not block EDT");
        var probe = new EvaluationProbe(current, history, restore, data);
        HistorySnapshot root = null; Map<String, Object> counts = null, canvas = null;
        Throwable primary = null;
        try {
            data.put("configuration", configuration());
            root = edt(history::get); counts = edt(() -> counts(current.get())); canvas = edt(() -> canvas(current.get()));
            data.put("initialHistory", historyData(root)); data.put("initialCounts", counts); data.put("initialCanvas", canvas);
            probe.setup(commands);
            probe.matrix(transactions);
        } catch (Exception | Error failure) {
            primary = failure; data.put("status", "FAIL"); data.put("failure", failure.toString()); throw failure;
        } finally {
            if (root != null) {
                final int position = root.position();
                try {
                    edt(() -> { restore.accept(position); return null; });
                    var finalCounts = edt(() -> counts(current.get())); var finalCanvas = edt(() -> canvas(current.get()));
                    var finalHistory = edt(history::get);
                    data.put("restoredCounts", finalCounts); data.put("restoredCanvas", finalCanvas);
                    data.put("restoredHistory", historyData(finalHistory));
                    probe.check("restored", finalCounts.equals(counts) && finalCanvas.equals(canvas)
                        && finalHistory.position() == position, "native Undo restores initial counts/canvas/position");
                    data.put("finallyRestored", true);
                } catch (Exception | Error recovery) {
                    data.put("finallyRestored", false); data.put("recoveryFailure", recovery.toString());
                    data.put("status", "FAIL");
                    if (primary != null) primary.addSuppressed(recovery); else throw recovery;
                }
            }
        }
        data.put("status", "PASS"); data.put("failure", null);
    }

    private void setup(EditorCommandService commands) throws Exception {
        edt(() -> {
            current.get(); var result = commands.execute(new EditorCanvasSettingsRequest(1024, 1024));
            data.put("canvasCommand", result.status().name()); data.put("canvas", canvas(current.get()));
            check("canvas", result.status() == EditorCommandResult.Status.EXECUTED
                && current.get().canvas().widthPixels() == 1024 && current.get().canvas().heightPixels() == 1024,
                "1024 pixel canvas established before geometry creation");
            id = current.get().drawables().create("MI3D_N3B", null, -1, NativeAssertions.GEOMETRY).id();
            current.get().parameters().createMany(List.of(
                new ParameterDefinition(X, "MI3D N3b X", -1, 0, 1, ParameterType.NORMAL, false),
                new ParameterDefinition(Y, "MI3D N3b Y", -1, 0, 1, ParameterType.NORMAL, false)));
            for (var parameter : List.of(X, Y)) current.get().parameterBindings(parameter).bind(
                ParameterBindingTarget.artMesh(id), NativeAssertions.KEYS.stream()
                    .map(v -> new ParameterBindingPoint(new ParameterBindingPointId("N3B_" + v), v)).toList());
            baseline = stored(); NativeAssertions.grid(baseline, X.value(), Y.value());
            var a = coordinates(1); var b = coordinates(-1);
            String ga = OrdinaryWriteProbe.formGuid(baseline, a), gb = OrdinaryWriteProbe.formGuid(baseline, b);
            OrdinaryWriteProbe.formGuid(baseline, coordinates(0));
            written = OrdinaryWriteProbe.changed(baseline, ga, OrdinaryWriteProbe.A, gb, OrdinaryWriteProbe.B);
            data.put("baseline", NativeAssertions.snapshot(baseline)); data.put("expectedWritten", NativeAssertions.snapshot(written));
            data.put("fixtureCounts", counts(current.get())); data.put("fixtureHistory", historyData(history.get()));
            check("fixture", baseline.forms().size() == 9 && baseline.cells().stream().map(c -> c.formGuid()).distinct().count() == 9
                && current.get().deformers().all().isEmpty(), "nine independent stored forms in an undeformed root fixture");
            return null;
        });
    }

    private void matrix(AuthoringTransactionService transactions) throws Exception {
        step("baseline", 1, baseline, NativeAssertions.GEOMETRY.positions(), () -> setX(1));
        step("written", 1, written, OrdinaryWriteProbe.A, () -> {
            writeBefore = history.get();
            var result = transactions.execute(AuthoringTransactionOptions.of("MI3D N3b evaluated targets"), () -> {
                current.get().drawables().find(id).ordinaryKeyform(coordinates(1)).setPositions(OrdinaryWriteProbe.A);
                current.get().drawables().find(id).ordinaryKeyform(coordinates(-1)).setPositions(OrdinaryWriteProbe.B);
                return null;
            });
            writeAfter = history.get();
            data.put("writeOutcome", result.outcome().name());
            data.put("writeEntryId", result.receipt().flatMap(r -> r.historyEntryId()).orElse(null));
            data.put("writeBefore", historyData(writeBefore)); data.put("writeAfter", historyData(writeAfter));
            check("singleUndo", result.outcome() == AuthoringTransactionOutcome.COMMITTED
                && writeAfter.position() == writeBefore.position() + 1, "two target writes commit in one history position");
        });
        step("undone", 1, baseline, NativeAssertions.GEOMETRY.positions(), () -> restore.accept(writeBefore.position()));
        step("redone", 1, written, OrdinaryWriteProbe.A, () -> restore.accept(writeAfter.position()));
        step("positiveMid", .5f, written, points(32, 32, 288, 32, 288, 160, 32, 160), () -> setX(.5f));
        step("defaultAfterPositive", 0, written, NativeAssertions.GEOMETRY.positions(), () -> setX(0));
        step("negativeEnd", -1, written, OrdinaryWriteProbe.B, () -> setX(-1));
        step("negativeMid", -.5f, written, points(6, 44, 262, 44, 262, 172, 6, 172), () -> setX(-.5f));
        step("defaultFinal", 0, written, NativeAssertions.GEOMETRY.positions(), () -> setX(0));
    }

    private void step(String name, float x, ArtMeshAuthoringSnapshot expectedStored, List<Point2> expected, Runnable action) throws Exception {
        var row = new LinkedHashMap<String, Object>(); samples.add(row);
        row.put("id", name); row.put("x", x); row.put("y", 0); row.put("status", "RUNNING");
        row.put("expectedPositions", positions(expected)); row.put("waitLimitMillis", WAIT_MILLIS);
        try {
            var requested = edt(() -> {
                current.get(); row.put("before", evaluation(observe())); action.run();
                var h = history.get(); var s = stored(); var e = observe();
                NativeAssertions.require(s.equals(expectedStored), "operation changed unexpected stored fields");
                NativeAssertions.require(value(e, X) == x && value(e, Y) == 0, "requested parameter value mismatch");
                row.put("requested", evaluation(e)); row.put("history", historyData(h));
                row.put("stored", NativeAssertions.snapshot(s)); return e;
            });
            var actual = awaitConsumed(requested, () -> edt(() -> {
                var beforeHistory = history.get(); var s = stored(); var e = observe(); var afterHistory = history.get();
                row.put("readHistoryBefore", historyData(beforeHistory)); row.put("readHistoryAfter", historyData(afterHistory));
                row.put("observedStored", NativeAssertions.snapshot(s));
                NativeAssertions.require(beforeHistory.equals(afterHistory) && historyData(afterHistory).equals(row.get("history")),
                    "evaluation read/wait changed history");
                NativeAssertions.require(s.equals(expectedStored), "evaluation read/wait changed stored model");
                return e;
            }), row::putAll, System::nanoTime, Thread::sleep);
            row.put("actual", evaluation(actual)); row.put("maxErrorPixels", maxError(actual, expected));
            NativeAssertions.require(validGeometry(actual, expected, expectedStored.forms().get(0).drawOrder()), "native calculated geometry mismatch");
            row.put("status", "PASS"); check(name, true, "fresh consumed parameters and independently expected calculated geometry");
        } catch (Exception | Error failure) {
            row.put("status", "FAIL"); row.put("failure", failure.toString());
            checkFailure(name); throw failure;
        }
    }

    @FunctionalInterface interface Sleeper { void sleep(long millis) throws InterruptedException; }

    static ArtMeshEvaluationSnapshot awaitConsumed(ArtMeshEvaluationSnapshot requested,
            Callable<ArtMeshEvaluationSnapshot> sample, Consumer<Map<String, Object>> progress,
            LongSupplier clock, Sleeper sleeper) throws Exception {
        long start = clock.getAsLong(); int attempts = 0;
        while (true) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("N3b sample interrupted");
            var actual = sample.call(); attempts++;
            long elapsed = (clock.getAsLong() - start) / 1_000_000;
            String reason = elapsed >= WAIT_MILLIS ? "TIMEOUT" : consumptionReason(requested, actual);
            progress.accept(Map.of("attempts", attempts, "elapsedMillis", elapsed, "waitReason", reason,
                "lastObservation", evaluation(actual)));
            if (reason.equals("CONSUMED")) return actual;
            if (reason.equals("IDENTITY_CHANGED") || reason.equals("LIVE_CHANGED") || reason.equals("TIMEOUT"))
                throw new IllegalStateException("N3b wait: " + reason);
            sleeper.sleep(25);
        }
    }

    static String consumptionReason(ArtMeshEvaluationSnapshot requested, ArtMeshEvaluationSnapshot actual) {
        if (!requested.artMeshId().equals(actual.artMeshId()) || !requested.sourceGuid().equals(actual.sourceGuid())) return "IDENTITY_CHANGED";
        if (!values(requested.liveParameters()).equals(values(actual.liveParameters()))) return "LIVE_CHANGED";
        if (actual.passEntryTimeMillis() < 0 || actual.consumedParameters().updateVersion() == requested.consumedParameters().updateVersion())
            return "WAITING_PASS";
        if (!values(actual.liveParameters()).equals(values(actual.consumedParameters()))) return "WAITING_CONSUMPTION";
        return "CONSUMED"; // Deliberately does not inspect calculated geometry (not even presence).
    }

    static double maxError(ArtMeshEvaluationSnapshot observation, List<Point2> expected) {
        if (observation.calculatedForm().isEmpty()) return -1;
        var actual = observation.calculatedForm().orElseThrow().positions();
        if (actual.size() != expected.size()) return -1;
        double error = 0;
        for (int i = 0; i < actual.size(); i++) error = Math.max(error,
            Math.hypot(actual.get(i).x() - expected.get(i).x(), actual.get(i).y() - expected.get(i).y()));
        return error;
    }

    static boolean validGeometry(ArtMeshEvaluationSnapshot observation, List<Point2> expected, int order) {
        double error = maxError(observation, expected);
        return error >= 0 && error <= .1 && observation.calculatedForm().filter(f -> f.interpolated()
            && f.drawOrder() == order && List.of("DeformerLocal", "Canvas").contains(f.coordinateType())).isPresent();
    }

    private void setX(float value) { current.get().parameters().find(X).setValue(value); }
    private ArtMeshAuthoringSnapshot stored() { return current.get().drawables().find(id).authoringSnapshot(); }
    private ArtMeshEvaluationSnapshot observe() { return current.get().drawables().find(id).evaluationSnapshot(); }
    private List<ArtMeshAuthoringSnapshot.Coordinate> coordinates(float x) {
        return OrdinaryWriteProbe.coordinates(baseline, X.value(), Y.value(), x);
    }
    private void check(String id, boolean actual, String expected) {
        NativeAssertions.require(checks.stream().noneMatch(c -> id.equals(c.get("id"))), "duplicate N3b check");
        checks.add(Map.of("id", id, "status", actual ? "PASS" : "FAIL", "actual", actual,
            "expected", expected, "evidenceKind", "EXACT_HOST"));
        NativeAssertions.require(actual, "N3b " + id + ": " + expected);
    }
    private void checkFailure(String id) {
        if (checks.stream().noneMatch(c -> id.equals(c.get("id")))) checks.add(Map.of("id", id,
            "status", "FAIL", "actual", false, "expected", "native sample completed", "evidenceKind", "EXACT_HOST"));
    }
    private static float value(ArtMeshEvaluationSnapshot e, ParameterId id) {
        var matches = e.liveParameters().parameters().stream().filter(p -> p.parameterId().equals(id)).toList();
        NativeAssertions.require(matches.size() == 1, "parameter identity ambiguous/missing"); return matches.get(0).value();
    }
    static List<Point2> points(float... xy) {
        var result = new ArrayList<Point2>(); for (int i = 0; i < xy.length; i += 2) result.add(new Point2(xy[i], xy[i + 1]));
        return List.copyOf(result);
    }
    private static List<List<Float>> positions(List<Point2> points) { return points.stream().map(p -> List.of(p.x(), p.y())).toList(); }
    private static List<Map<String, Object>> values(ArtMeshEvaluationSnapshot.ParameterState s) {
        return s.parameters().stream().sorted(Comparator.comparing(p -> p.parameterGuid()))
            .map(p -> Map.<String, Object>of("guid", p.parameterGuid(), "id", p.parameterId().value(), "value", p.value())).toList();
    }
    static Map<String, Object> evaluation(ArtMeshEvaluationSnapshot e) {
        var result = new LinkedHashMap<String, Object>();
        result.put("artMeshId", e.artMeshId().value()); result.put("sourceGuid", e.sourceGuid());
        result.put("passEntryTimeMillis", e.passEntryTimeMillis());
        result.put("live", Map.of("updateVersion", e.liveParameters().updateVersion(), "parameters", values(e.liveParameters())));
        result.put("consumed", Map.of("updateVersion", e.consumedParameters().updateVersion(), "parameters", values(e.consumedParameters())));
        result.put("calculated", e.calculatedForm().map(f -> Map.<String, Object>of("positions", positions(f.positions()),
            "drawOrder", f.drawOrder(), "coordinateType", f.coordinateType(), "interpolated", f.interpolated())).orElse(null));
        return result;
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
    static Map<String, Object> configuration() throws Exception {
        var props = new LinkedHashMap<String, Object>();
        for (String name : List.of("sun.java2d.opengl", "jogl.gljpanel.noogl")) {
            String value = System.getProperty(name); props.put(name, value);
            NativeAssertions.require(value == null || value.equals("false"), "unreviewed rendering configuration: " + name);
        }
        var classpath = List.of(System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(File.pathSeparator)));
        var paths = classpath.stream().map(p -> Path.of(p).toAbsolutePath()).toList();
        var jogl = paths.stream().filter(p -> p.toString().replace('\\', '/').endsWith("/app/lib/jogl/jogl-all.jar")).toList();
        var cubism = paths.stream().filter(p -> p.toString().replace('\\', '/').endsWith("/app/lib/Live2D_Cubism.jar")).toList();
        NativeAssertions.require(jogl.size() == 1 && cubism.size() == 1, "official classpath artifact identity missing/ambiguous");
        Path jar = jogl.get(0);
        NativeAssertions.require(!jar.toString().contains(".."), "invalid official classpath path");
        for (Path p = jar; p != null; p = p.getParent()) NativeAssertions.require(!Files.isSymbolicLink(p), "symlink official artifact");
        NativeAssertions.require(Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS) && Files.size(jar) <= 32L * 1024 * 1024,
            "invalid official JOGL artifact size");
        byte[] bytes;
        try (var input = Files.newInputStream(jar)) { bytes = input.readNBytes(32 * 1024 * 1024 + 1); }
        NativeAssertions.require(bytes.length <= 32 * 1024 * 1024, "official JOGL artifact grew beyond limit");
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        NativeAssertions.require(sha.equals(JOGL_SHA), "official JOGL artifact differs from static investigation");
        NativeAssertions.require(jar.getParent().getParent().resolve("Live2D_Cubism.jar").normalize()
            .equals(cubism.get(0).normalize()), "Cubism and JOGL not from same official installation");
        return Map.of("properties", props, "inputArguments", ManagementFactory.getRuntimeMXBean().getInputArguments(),
            "classpath", classpath, "joglPath", jar.toString(), "cubismPath", cubism.get(0).toString(), "joglSha256", sha,
            "workingDirectory", System.getProperty("user.dir"), "backend", "OFFSCREEN", "backendEvidence", "CONFIGURATION_DERIVED");
    }
    private static <T> T edt(Callable<T> operation) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return operation.call();
        var task = new FutureTask<T>(operation); SwingUtilities.invokeAndWait(task); return task.get();
    }
}

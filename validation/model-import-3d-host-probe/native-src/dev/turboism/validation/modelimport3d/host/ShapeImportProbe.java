package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot;
import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.id.ParameterBindingPointId;
import dev.turboism.sdk.cubism.model.ArtMeshKeyform;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.ParameterBindingPoint;
import dev.turboism.sdk.cubism.model.ParameterBindingTarget;
import dev.turboism.sdk.cubism.model.ParameterDefinition;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOptions;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOutcome;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionResult;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;

/**
 * B0 shape-import probe: a minimal 3D surface (one 3D triangle + one 3D quad) is
 * orthographically projected per source-design §4.1 into canvas-space keyform
 * positions for the yaw keys -30/0/+30, imported natively as two ArtMeshes
 * (create + replaceGeometry) and bound to a freshly created rotation parameter
 * (three ordinary keyforms). Verifies native readback, isolation, and undo/redo.
 * No textures, no deformers, no GLB parsing, no save/reopen (frozen B0 scope).
 *
 * <p>Threading mirrors N3b: the probe runs off the EDT and dispatches each SDK
 * touch through {@link #edt}; the caller thread is the probe worker.</p>
 */
final class ShapeImportProbe {
    private static final float CX = 512f, CY = 512f, SCALE = 200f;

    /** 3D source vertices (x, y, z); yaw θ: x' = x·cosθ + z·sinθ, canvas (CX+SCALE·x', CY−SCALE·y). */
    private static final float[][] TRI3D = {{0.9f, 0.6f, 0.4f}, {-0.9f, 0.6f, -0.4f}, {0f, -0.9f, 0.2f}};
    private static final float[][] QUAD3D = {{0.8f, 0.8f, 0.3f}, {-0.8f, 0.8f, 0.1f},
        {-0.8f, -0.8f, -0.3f}, {0.8f, -0.8f, -0.1f}};
    private static final List<Integer> TRI_IDX = List.of(0, 1, 2);
    private static final List<Integer> QUAD_IDX = List.of(0, 1, 2, 0, 2, 3);

    private final Supplier<CubismModel> current;
    private final Supplier<HistorySnapshot> history;
    private final IntConsumer restore;
    private final AuthoringTransactionService transactions;
    private final Map<String, Object> data;
    private final List<Map<String, Object>> checks = new ArrayList<>();

    private ShapeImportProbe(Supplier<CubismModel> current, Supplier<HistorySnapshot> history,
            IntConsumer restore, AuthoringTransactionService transactions, Map<String, Object> data) {
        this.current = current; this.history = history; this.restore = restore;
        this.transactions = transactions; this.data = data;
        data.put("status", "RUNNING"); data.put("checks", checks);
    }

    static void run(Supplier<CubismModel> current, Supplier<HistorySnapshot> history,
            IntConsumer restore, AuthoringTransactionService transactions, Map<String, Object> data)
            throws Exception {
        NativeAssertions.require(!SwingUtilities.isEventDispatchThread(), "B0 probe must not block EDT");
        var p = new ShapeImportProbe(current, history, restore, transactions, data);
        HistorySnapshot root = null;
        Throwable primary = null;
        try {
            root = edt(history::get);
            data.put("initialHistory", historyData(root));
            try {
                p.execute();
            } catch (Exception | Error failure) {
                primary = failure; data.put("status", "FAIL"); data.put("failure", failure.toString()); throw failure;
            }
        } finally {
            if (root != null) {
                final int position = root.position();
                try {
                    edt(() -> { restore.accept(position); return null; });
                    var h = edt(history::get);
                    data.put("restoredHistory", historyData(h));
                    p.check("historyPositionRestored", h.position() == position, "history restored to root");
                    data.put("finallyRestored", true);
                } catch (Exception | Error failure) {
                    data.put("status", "FAIL"); data.put("finallyRestored", false);
                    data.put("recoveryFailure", failure.toString());
                    if (primary != null) primary.addSuppressed(failure); else throw failure;
                }
            }
        }
        data.put("status", "PASS"); data.put("failure", null);
    }

    private void execute() throws Exception {
        CubismModel model = edt(current::get);

        // (1) The rotation parameter, freshly created: yaw keys -30/0/+30.
        var rotId = new ParameterId("ParamMI3DB0RotX");
        edt(() -> model.parameters().createMany(List.of(
            new ParameterDefinition(rotId, "MI3D B0 RotX", -30f, 0f, 30f,
                dev.turboism.sdk.cubism.model.ParameterType.NORMAL, false))));
        var rot = edt(() -> model.parameters().find(rotId));
        check("rotationParameterCreated", rot != null,
            "rotation parameter exists for the imported keyforms");

        // (2) Import the two surfaces: create with geometry (default pose = 0° projection).
        var triGeometry = geometry(zero(TRI3D), uvs(TRI3D, 0.2f), TRI_IDX);
        var quadGeometry = geometry(zero(QUAD3D), uvs(QUAD3D, 0.6f), QUAD_IDX);
        // Join-free import: the N2-proven creation path takes the full projected
        // geometry directly — no current()/evaluated join, no replaceGeometry.
        final Drawable[] created = new Drawable[2];
        // The ambient authoring scope is a ThreadLocal bound to the host (EDT)
        // thread: the whole transaction — callback included — must run there so
        // nested create/mutate calls see the same scope.
        var geometryTx = edt(() -> transactions.execute(
            AuthoringTransactionOptions.of("MI3D B0 import geometry"), () -> {
                created[0] = createMeshWithGeometry("MI3D_B0_TRI", triGeometry);
                created[1] = createMeshWithGeometry("MI3D_B0_QUAD", quadGeometry);
                return null;
            }));
        Drawable tri = created[0];
        Drawable quad = created[1];
        data.put("geometryTransaction", result(geometryTx));
        check("geometryCommitted", geometryTx.outcome() == AuthoringTransactionOutcome.COMMITTED
                && tri != null && quad != null,
            "both surface geometries commit in one native transaction");
        check("geometryReadback", close(edt(tri::geometry), triGeometry)
            && close(edt(quad::geometry), quadGeometry), "native readback matches the projection within 0.1px");

        // (3) Object-level binding: each mesh gets its own declared-key binding to
        // the rotation parameter (three key tuples per mesh; the 0° cell is the
        // default form and cannot be position-edited, so only -30/+30 are written).
        var bindPoints = List.of(
            new ParameterBindingPoint(new ParameterBindingPointId("B0_NEG"), -30f),
            new ParameterBindingPoint(new ParameterBindingPointId("B0_ZERO"), 0f),
            new ParameterBindingPoint(new ParameterBindingPointId("B0_POS"), 30f));
        var triId = tri.id(); var quadId = quad.id();
        edt(() -> {
            model.parameterBindings(rotId).bind(ParameterBindingTarget.artMesh(triId), bindPoints);
            model.parameterBindings(rotId).bind(ParameterBindingTarget.artMesh(quadId), bindPoints);
            return null;
        });
        // The display name and the host-minted object id are distinct identities:
        // report both so the oracle never has to guess the id from the name.
        data.put("meshes", List.of(
            meshData("MI3D_B0_TRI", triId), meshData("MI3D_B0_QUAD", quadId)));
        data.put("bindings", List.of(
            bindingData(triId, rotId), bindingData(quadId, rotId)));

        // Per-object complete tuples: each mesh's own bound axis supplies its
        // parameter GUID; the declared keys must have materialized on that axis.
        var triAxis = axis(edt(tri::authoringSnapshot), rotId);
        var quadAxis = axis(edt(quad::authoringSnapshot), rotId);
        var triNeg = List.of(new ArtMeshAuthoringSnapshot.Coordinate(triAxis, -30f));
        var triZero = List.of(new ArtMeshAuthoringSnapshot.Coordinate(triAxis, 0f));
        var triPos = List.of(new ArtMeshAuthoringSnapshot.Coordinate(triAxis, 30f));
        var quadNeg = List.of(new ArtMeshAuthoringSnapshot.Coordinate(quadAxis, -30f));
        var quadZero = List.of(new ArtMeshAuthoringSnapshot.Coordinate(quadAxis, 0f));
        var quadPos = List.of(new ArtMeshAuthoringSnapshot.Coordinate(quadAxis, 30f));
        data.put("triKeyformCoordinates",
            List.of(tuples(triNeg), tuples(triZero), tuples(triPos)));
        data.put("quadKeyformCoordinates",
            List.of(tuples(quadNeg), tuples(quadZero), tuples(quadPos)));
        var keyformTx = edt(() -> transactions.execute(
            AuthoringTransactionOptions.of("MI3D B0 bind rotation keys"), () -> {
                keyform(tri, triNeg).setPositions(project(TRI3D, -30));
                keyform(tri, triPos).setPositions(project(TRI3D, 30));
                keyform(quad, quadNeg).setPositions(project(QUAD3D, -30));
                keyform(quad, quadPos).setPositions(project(QUAD3D, 30));
                return null;
            }));
        data.put("keyformTransaction", result(keyformTx));
        check("keyformsCommitted", keyformTx.outcome() == AuthoringTransactionOutcome.COMMITTED,
            "four rotation keyform writes commit in one native transaction");

        // (4) Native readback of every bound key + the untouched default pose.
        var triNegRead = edt(() -> keyform(tri, triNeg).snapshot().positions());
        var triPosRead = edt(() -> keyform(tri, triPos).snapshot().positions());
        var quadNegRead = edt(() -> keyform(quad, quadNeg).snapshot().positions());
        var quadPosRead = edt(() -> keyform(quad, quadPos).snapshot().positions());
        var readbackOk = close(triNegRead, project(TRI3D, -30)) && close(triPosRead, project(TRI3D, 30))
            && close(quadNegRead, project(QUAD3D, -30)) && close(quadPosRead, project(QUAD3D, 30));
        check("keyformReadback", readbackOk, "all four rotation keyforms read back at the projected poses (triNeg="
            + triNegRead + ")");
        var triAfter = edt(tri::authoringSnapshot);
        var quadAfter = edt(quad::authoringSnapshot);
        check("defaultFormIsZeroPose",
            edt(() -> keyform(tri, triZero).defaultKeyform())
                && edt(() -> keyform(quad, quadZero).defaultKeyform())
                && close(edt(() -> keyform(tri, triZero).snapshot().positions()), triGeometry.positions())
                && close(edt(() -> keyform(quad, quadZero).snapshot().positions()), quadGeometry.positions())
                && triAfter.sourceGeometry().equals(triGeometry)
                && quadAfter.sourceGeometry().equals(quadGeometry)
                && triAfter.forms().size() == 3 && quadAfter.forms().size() == 3,
            "keyform writes leave the default 0° pose untouched");
        data.put("protectedDomain", Map.of(
            "tri", protectedData(triAfter, edt(() -> keyform(tri, triZero).snapshot().positions())),
            "quad", protectedData(quadAfter, edt(() -> keyform(quad, quadZero).snapshot().positions()))));

        // (5) One Undo rolls the keyform transaction back; one Redo restores it.
        var hBefore = edt(history::get);
        var snapshotBefore = NativeAssertions.snapshot(edt(tri::authoringSnapshot));
        edt(() -> { restore.accept(hBefore.position() - 1); return null; });
        var undonePos = edt(() -> keyform(tri, triPos).snapshot().positions());
        check("keyformUndo", !close(undonePos, project(TRI3D, 30)),
            "one Undo removes the non-default rotation keyforms");
        edt(() -> { restore.accept(hBefore.position()); return null; });
        check("keyformRedo", close(edt(() -> keyform(tri, triPos).snapshot().positions()), project(TRI3D, 30)),
            "one Redo restores the written keyforms");
        check("undoStable", snapshotBefore.equals(NativeAssertions.snapshot(edt(tri::authoringSnapshot))),
            "undo/redo keeps authoring identity stable");
    }

    private ArtMeshKeyform keyform(Drawable mesh, List<ArtMeshAuthoringSnapshot.Coordinate> coords) {
        return mesh.ordinaryKeyform(coords);
    }

    private Drawable createMeshWithGeometry(String name, ArtMeshGeometry geometry) throws Exception {
        return edt(() -> current.get().drawables().create(name, null, -1, geometry));
    }

    private static Map<String, Object> meshData(String name, ArtMeshId meshId) {
        var row = new LinkedHashMap<String, Object>();
        row.put("name", name); row.put("id", meshId.value()); return row;
    }

    private static Map<String, Object> bindingData(ArtMeshId meshId, ParameterId parameterId) {
        var row = new LinkedHashMap<String, Object>();
        row.put("mesh", meshId.value()); row.put("parameter", parameterId.value());
        row.put("keys", List.of(-30f, 0f, 30f)); return row;
    }

    /** Serializes one complete keyform coordinate tuple for the report. */
    private static List<Map<String, Object>> tuples(List<ArtMeshAuthoringSnapshot.Coordinate> tuple) {
        return tuple.stream().map(c -> Map.of(
            "guid", (Object) c.parameterGuid(), "value", c.value())).toList();
    }

    private static Map<String, Object> protectedData(ArtMeshAuthoringSnapshot snapshot,
            List<Point2> defaultFormPositions) {
        var row = new LinkedHashMap<String, Object>();
        row.put("uvs", points(snapshot.sourceGeometry().uvs()));
        row.put("indices", snapshot.sourceGeometry().triangleIndices());
        row.put("sourcePositions", points(snapshot.sourceGeometry().positions()));
        row.put("defaultFormPositions", points(defaultFormPositions));
        row.put("formCount", snapshot.forms().size());
        return row;
    }

    private static List<List<Float>> points(List<Point2> source) {
        return source.stream().map(p -> List.of(p.x(), p.y())).toList();
    }

    /**
     * Resolves this object's own bound axis for the parameter and requires the
     * declared keys to have materialized. Unbound objects expose no axis and
     * fail closed; model-wide parameter listings are never substituted.
     */
    private static String axis(ArtMeshAuthoringSnapshot s, ParameterId id) {
        var found = s.axes().stream().filter(a -> a.parameterId().value().equals(id.value())).toList();
        NativeAssertions.require(found.size() == 1, "B0 axis identity missing/ambiguous");
        var axis = found.get(0);
        NativeAssertions.require(Set.copyOf(axis.keys()).equals(Set.of(-30f, 0f, 30f)),
            "B0 declared rotation keys missing from the bound axis");
        return axis.parameterGuid();
    }

    /** Canvas-space projection of the pose θ (degrees yaw); 0° pose is the base geometry. */
    private static List<Point2> project(float[][] vertices, double theta) {
        var source = new ArrayList<SurfaceProjection.Vertex>(vertices.length);
        for (float[] v : vertices) {
            source.add(new SurfaceProjection.Vertex(v[0], v[1], v[2]));
        }
        return SurfaceProjection.project(source,
            new SurfaceProjection.Camera(CX, CY, SCALE, new SurfaceProjection.Vertex(0, 0, 0)),
            new SurfaceProjection.Pose(theta, 0)).positions();
    }

    private static List<Point2> zero(float[][] vertices) { return project(vertices, 0); }

    private static List<Point2> uvs(float[][] vertices, float uShift) {
        var out = new ArrayList<Point2>(vertices.length);
        for (int i = 0; i < vertices.length; i++) {
            out.add(new Point2(uShift + 0.1f * i, 0.5f - 0.4f * (i % 2)));
        }
        return out;
    }

    private ArtMeshGeometry geometry(List<Point2> positions, List<Point2> uvs, List<Integer> indices) {
        return new ArtMeshGeometry(positions, uvs, indices);
    }

    /** Per-vertex Euclidean distance within 0.1 canvas pixels (SC-001 budget). */
    private static boolean close(ArtMeshGeometry observed, ArtMeshGeometry expected) {
        return observed.uvs().equals(expected.uvs())
            && observed.triangleIndices().equals(expected.triangleIndices())
            && close(observed.positions(), expected.positions());
    }

    private static boolean close(List<Point2> observed, List<Point2> expected) {
        if (observed.size() != expected.size()) return false;
        for (int i = 0; i < observed.size(); i++) {
            double dx = observed.get(i).x() - expected.get(i).x();
            double dy = observed.get(i).y() - expected.get(i).y();
            if (Math.hypot(dx, dy) > 0.1d) return false;
        }
        return true;
    }

    private static <T> T edt(Callable<T> action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return action.call();
        var task = new FutureTask<T>(action); SwingUtilities.invokeAndWait(task); return task.get();
    }

    private void check(String id, boolean actual, String expected) {
        NativeAssertions.require(checks.stream().noneMatch(c -> id.equals(c.get("id"))), "duplicate B0 check");
        checks.add(Map.of("id", id, "status", actual ? "PASS" : "FAIL", "actual", actual,
            "expected", expected, "evidenceKind", "EXACT_HOST"));
        NativeAssertions.require(actual, "B0 " + id + ": " + expected);
    }

    private Map<String, Object> result(AuthoringTransactionResult<?> r) {
        var row = new LinkedHashMap<String, Object>();
        row.put("outcome", r.outcome().name()); row.put("diagnosticId", r.diagnosticId().orElse(null));
        return row;
    }

    private static Map<String, Object> historyData(HistorySnapshot h) {
        var data = new LinkedHashMap<String, Object>();
        data.put("availability", h.availability().name()); data.put("generation", h.generation());
        data.put("revision", h.revision()); data.put("position", h.position());
        data.put("documentBindingId", h.documentBindingId()); data.put("managerBindingId", h.managerBindingId());
        data.put("entries", h.entries().stream().map(Object::toString).toList()); return data;
    }
}

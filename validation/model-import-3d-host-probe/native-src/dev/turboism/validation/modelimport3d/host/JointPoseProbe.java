package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.command.EditorCanvasSettingsRequest;
import dev.turboism.sdk.cubism.command.EditorCommandResult;
import dev.turboism.sdk.cubism.command.EditorCommandService;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ParameterBindingPointId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.ParameterBindingPoint;
import dev.turboism.sdk.cubism.model.ParameterBindingTarget;
import dev.turboism.sdk.cubism.model.ParameterDefinition;
import dev.turboism.sdk.cubism.model.ParameterType;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOptions;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOutcome;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;

/** SDK-only joint-pose geometry gate. No texture, occlusion or full-import atomicity claims. */
final class JointPoseProbe {
    static final ParameterId YAW = new ParameterId("ParamMI3DJointYaw"), PITCH = new ParameterId("ParamMI3DJointPitch");
    static final SurfaceProjection.Camera CAMERA = new SurfaceProjection.Camera(512, 512, 200,
        new SurfaceProjection.Vertex(0, 0, 0));
    static final List<List<SurfaceProjection.Vertex>> SOURCES = List.of(
        List.of(new SurfaceProjection.Vertex(.3, .2, .1), new SurfaceProjection.Vertex(-.3, .2, .1),
            new SurfaceProjection.Vertex(0, -.2, -.1)),
        List.of(new SurfaceProjection.Vertex(.3, .2, .1), new SurfaceProjection.Vertex(0, -.2, -.1),
            new SurfaceProjection.Vertex(.3, -.2, -.2)));
    static final List<Point2> UVS = List.of(new Point2(0, 0), new Point2(1, 0), new Point2(0, 1));

    static void run(Supplier<CubismModel> current, Supplier<HistorySnapshot> history, IntConsumer restore,
            EditorCommandService commands, AuthoringTransactionService transactions, Map<String, Object> data) throws Exception {
        NativeAssertions.require(!SwingUtilities.isEventDispatchThread(), "joint-pose probe must not wait on EDT");
        data.put("status", "RUNNING");
        var samples = new ArrayList<Map<String, Object>>(); data.put("samples", samples);
        // All numeric inputs and float conversions are admitted before host mutation.
        var grid = SurfaceKeyPlan.linear(SOURCES.stream().flatMap(List::stream).toList(), CAMERA, 30, 30, .05, 4096);
        var yawKeys = SurfaceKeyformBake.bindingKeys(grid.yawKeys());
        var pitchKeys = SurfaceKeyformBake.bindingKeys(grid.pitchKeys());
        data.put("yawKeys", yawKeys); data.put("pitchKeys", pitchKeys);
        data.put("idealErrorBoundPixels", grid.errorBoundPixels());
        data.put("maxNativeErrorPixels", .1);
        data.put("rotationOrder", "Ry(yaw)*Rx(pitch)");
        data.put("camera", List.of(512, 512, 200, 0, 0, 0));
        data.put("sources", SOURCES.stream().map(s -> s.stream().map(v -> List.of(v.x(), v.y(), v.z())).toList()).toList());
        HistorySnapshot root = edt(history::get);
        var initialCounts = edt(() -> counts(current.get()));
        var initialCanvas = edt(() -> canvas(current.get()));
        data.put("initialHistory", historyData(root)); data.put("initialCounts", initialCounts);
        data.put("initialCanvas", initialCanvas);
        Throwable primary = null;
        try {
            edt(() -> {
                var result = commands.execute(new EditorCanvasSettingsRequest(1024, 1024));
                NativeAssertions.require(result.status() == EditorCommandResult.Status.EXECUTED
                    && current.get().canvas().widthPixels() == 1024 && current.get().canvas().heightPixels() == 1024,
                    "joint-pose canvas setup failed");
                current.get().parameters().createMany(List.of(
                    new ParameterDefinition(YAW, "MI3D Joint Yaw", -30, 0, 30, ParameterType.NORMAL, false),
                    new ParameterDefinition(PITCH, "MI3D Joint Pitch", -30, 0, 30, ParameterType.NORMAL, false)));
                return null;
            });
            var ids = new ArrayList<ArtMeshId>();
            var plans = new ArrayList<SurfaceKeyformBake.Plan>();
            for (int i = 0; i < SOURCES.size(); i++) {
                final int surface = i;
                var id = edt(() -> {
                    var geometry = new ArtMeshGeometry(SurfaceProjection.project(SOURCES.get(surface), CAMERA,
                        new SurfaceProjection.Pose(0, 0)).positions(), UVS, List.of(0, 1, 2));
                    var mesh = current.get().drawables().create("MI3D_JOINT_" + surface, null, -1, geometry);
                    for (var axis : List.of(YAW, PITCH)) {
                        var keys = axis.equals(YAW) ? yawKeys : pitchKeys;
                        var points = new ArrayList<ParameterBindingPoint>();
                        for (int k = 0; k < keys.size(); k++) points.add(new ParameterBindingPoint(
                            new ParameterBindingPointId("JOINT_" + axis.value() + "_" + k), keys.get(k)));
                        current.get().parameterBindings(axis).bind(ParameterBindingTarget.artMesh(mesh.id()), points);
                    }
                    return mesh.id();
                });
                ids.add(id);
                plans.add(SurfaceKeyformBake.compile(edt(() -> current.get().drawables().find(id).authoringSnapshot()),
                    YAW, PITCH, SOURCES.get(i), CAMERA, grid));
            }
            data.put("meshIds", ids.stream().map(ArtMeshId::value).toList());
            data.put("before", plans.stream().map(p -> NativeAssertions.snapshot(p.before())).toList());
            var beforeWrite = edt(history::get);
            var result = edt(() -> transactions.execute(AuthoringTransactionOptions.of("MI3D joint pose bake"), () -> {
                for (int i = 0; i < ids.size(); i++) plans.get(i).write(current.get().drawables().find(ids.get(i)));
                return null;
            }));
            var afterWrite = edt(history::get);
            data.put("writeOutcome", result.outcome().name());
            data.put("writeBefore", historyData(beforeWrite)); data.put("writeAfter", historyData(afterWrite));
            NativeAssertions.require(result.outcome() == AuthoringTransactionOutcome.COMMITTED
                && afterWrite.position() == beforeWrite.position() + 1, "joint bake was not one committed history entry");
            data.put("written", snapshots(current, ids));
            edt(() -> { for (int i = 0; i < ids.size(); i++) plans.get(i).verify(current.get().drawables().find(ids.get(i))); return null; });
            edt(() -> { restore.accept(beforeWrite.position()); return null; });
            data.put("undone", snapshots(current, ids));
            edt(() -> {
                for (int i = 0; i < ids.size(); i++) NativeAssertions.require(
                    current.get().drawables().find(ids.get(i)).authoringSnapshot().equals(plans.get(i).before()),
                    "joint bake Undo changed protected fields or failed to restore positions");
                restore.accept(afterWrite.position());
                for (int i = 0; i < ids.size(); i++) plans.get(i).verify(current.get().drawables().find(ids.get(i)));
                return null;
            });
            data.put("redone", snapshots(current, ids));
            var samplePoses = poses(yawKeys, pitchKeys);
            for (int s = 0; s < samplePoses.size(); s++) {
                var pose = samplePoses.get(s);
                var row = new LinkedHashMap<String, Object>(); samples.add(row);
                row.put("index", s); row.put("pose", List.of(pose.yaw(), pose.pitch())); row.put("status", "RUNNING");
                var observations = new ArrayList<Map<String, Object>>(); row.put("meshes", observations);
                var requested = edt(() -> {
                    current.get().parameters().find(YAW).setValue((float) pose.yaw());
                    current.get().parameters().find(PITCH).setValue((float) pose.pitch());
                    return ids.stream().map(id -> current.get().drawables().find(id).evaluationSnapshot()).toList();
                });
                var h = edt(history::get);
                row.put("historyBefore", historyData(h));
                for (int i = 0; i < ids.size(); i++) {
                    var id = ids.get(i);
                    var detail = new LinkedHashMap<String, Object>(); observations.add(detail);
                    detail.put("requested", EvaluationProbe.evaluation(requested.get(i)));
                    var actual = EvaluationProbe.awaitConsumed(requested.get(i),
                        () -> edt(() -> current.get().drawables().find(id).evaluationSnapshot()),
                        detail::putAll, System::nanoTime, Thread::sleep);
                    detail.put("actual", EvaluationProbe.evaluation(actual));
                    // Wait predicate does not inspect expected geometry; stale/wrong calculated forms fail here.
                    var expected = SurfaceProjection.project(SOURCES.get(i), CAMERA, pose).positions();
                    detail.put("maxErrorPixels", EvaluationProbe.maxError(actual, expected));
                    NativeAssertions.require(EvaluationProbe.validGeometry(actual, expected, plans.get(i).before().forms().get(0).drawOrder()),
                        "native joint-pose projection exceeds 0.1px or has wrong coordinate/order state");
                    for (var axis : List.of(YAW, PITCH)) {
                        var values = actual.liveParameters().parameters().stream().filter(p -> p.parameterId().equals(axis)).toList();
                        float expectedValue = (float) (axis.equals(YAW) ? pose.yaw() : pose.pitch());
                        NativeAssertions.require(values.size() == 1 && values.get(0).value() == expectedValue,
                            "joint-pose requested value was clamped or not applied");
                    }
                }
                var afterRead = edt(history::get);
                row.put("historyAfter", historyData(afterRead));
                NativeAssertions.require(h.equals(afterRead), "joint evaluation mutated history");
                row.put("status", "PASS");
            }
            edt(() -> { for (int i = 0; i < ids.size(); i++) plans.get(i).verify(current.get().drawables().find(ids.get(i))); return null; });
            data.put("afterEvaluation", snapshots(current, ids));
        } catch (Exception | Error failure) {
            primary = failure; data.put("status", "FAIL"); data.put("failure", failure.toString()); throw failure;
        } finally {
            try {
                edt(() -> { restore.accept(root.position()); return null; });
                var restored = edt(history::get);
                var restoredCounts = edt(() -> counts(current.get()));
                var restoredCanvas = edt(() -> canvas(current.get()));
                data.put("restoredHistory", historyData(restored)); data.put("restoredCounts", restoredCounts);
                data.put("restoredCanvas", restoredCanvas);
                NativeAssertions.require(restored.position() == root.position() && restoredCounts.equals(initialCounts)
                    && restoredCanvas.equals(initialCanvas),
                    "joint-pose restoration failed");
                data.put("finallyRestored", true);
            } catch (Exception | Error failure) {
                data.put("finallyRestored", false); data.put("status", "FAIL"); data.put("recoveryFailure", failure.toString());
                if (primary != null) primary.addSuppressed(failure); else throw failure;
            }
        }
        data.put("status", "PASS"); data.put("failure", null);
    }

    static List<SurfaceProjection.Pose> poses(List<Float> yaw, List<Float> pitch) {
        var result = new ArrayList<SurfaceProjection.Pose>();
        // Cartesian product covers every key, cell center, edge midpoint and domain boundary.
        for (float y : withMidpoints(yaw)) for (float p : withMidpoints(pitch)) result.add(new SurfaceProjection.Pose(y, p));
        var random = new Random(4012026);
        for (int i = 0; i < 32; i++) result.add(new SurfaceProjection.Pose((float) (-30 + 60 * random.nextDouble()),
            (float) (-30 + 60 * random.nextDouble())));
        return List.copyOf(result);
    }

    private static List<Float> withMidpoints(List<Float> keys) {
        var result = new ArrayList<Float>();
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) result.add((keys.get(i - 1) + keys.get(i)) / 2);
            result.add(keys.get(i));
        }
        return result;
    }

    private static List<Map<String, Object>> snapshots(Supplier<CubismModel> current, List<ArtMeshId> ids) throws Exception {
        return edt(() -> ids.stream().map(id -> NativeAssertions.snapshot(current.get().drawables().find(id).authoringSnapshot())).toList());
    }

    private static Map<String, Object> counts(CubismModel m) {
        return Map.of("meshes", m.drawables().all().size(), "parameters", m.parameters().all().size(),
            "deformers", m.deformers().all().size(), "parts", m.parts().all().size());
    }

    private static Map<String, Object> historyData(HistorySnapshot h) {
        return Map.of("availability", h.availability().name(), "generation", h.generation(), "revision", h.revision(),
            "position", h.position(), "documentBindingId", h.documentBindingId(), "managerBindingId", h.managerBindingId());
    }

    private static Map<String, Object> canvas(CubismModel model) {
        var c = model.canvas();
        return Map.of("width", c.widthPixels(), "height", c.heightPixels(), "originX", c.originXPixels(),
            "originY", c.originYPixels(), "pixelsPerUnit", c.pixelsPerUnit());
    }

    private static <T> T edt(Callable<T> action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return action.call();
        var task = new FutureTask<T>(action); SwingUtilities.invokeAndWait(task); return task.get();
    }
}

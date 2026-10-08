package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.command.EditorCanvasSettingsRequest;
import dev.turboism.sdk.cubism.command.EditorCommandResult;
import dev.turboism.sdk.cubism.command.EditorCommandService;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.CubismModel;
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

/** Actual authorized cube geometry gate. No texture, occlusion or whole-import atomicity claims. */
final class CubeGeometryProbe {
    static final ParameterId YAW = new ParameterId("ParamMI3DCubeYaw"), PITCH = new ParameterId("ParamMI3DCubePitch");
    static final SurfaceProjection.Camera CAMERA = new SurfaceProjection.Camera(512, 512, 200,
        new SurfaceProjection.Vertex(0, 0, 0));
    static final String INPUT_SHA256 = "985496727e505bcc1b5c748415373090c11b1058bb20ac57a4434914e3717630";

    static void run(byte[] glb, Supplier<CubismModel> current, Supplier<HistorySnapshot> history, IntConsumer restore,
            EditorCommandService commands, AuthoringTransactionService transactions, Map<String, Object> data) throws Exception {
        run(glb,current,history,restore,commands,transactions,data,null);
    }

    @FunctionalInterface
    interface AfterGeometry {
        void run(List<GlbSurfaceReader.Surface> surfaces, SurfaceImportPlan.Plan plan,
                 SurfaceImportGeometry.Created created) throws Exception;
    }

    static void run(byte[] glb, Supplier<CubismModel> current, Supplier<HistorySnapshot> history, IntConsumer restore,
            EditorCommandService commands, AuthoringTransactionService transactions, Map<String, Object> data,
            AfterGeometry afterGeometry) throws Exception {
        run(glb,current,history,restore,commands,transactions,data,afterGeometry,SurfaceImportGeometry.UvMode.SOURCE);
    }

    static void run(byte[] glb, Supplier<CubismModel> current, Supplier<HistorySnapshot> history, IntConsumer restore,
            EditorCommandService commands, AuthoringTransactionService transactions, Map<String, Object> data,
            AfterGeometry afterGeometry, SurfaceImportGeometry.UvMode uvMode) throws Exception {
        NativeAssertions.require(!SwingUtilities.isEventDispatchThread(), "cube-geometry probe must not wait on EDT");
        data.put("status", "RUNNING");
        var samples = new ArrayList<Map<String, Object>>(); data.put("samples", samples);
        // Decode the exact authorized bytes and admit all numerical inputs before host mutation.
        var digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(glb));
        NativeAssertions.require(digest.equals(INPUT_SHA256), "cube input bytes differ from authorized fixture");
        var surfaces = GlbSurfaceReader.read(glb);
        var proof = ClosedConvexSurfaceProof.verify(surfaces);
        var importPlan = SurfaceImportPlan.compile(surfaces, CAMERA, 30, 30, .05, 2_000_000);
        NativeAssertions.require(importPlan.patches().size() == 6 && importPlan.keys().size() == 2025,
            "unexpected authorized cube plan");
        var sources = importPlan.patches().stream().map(p -> p.source().positions()).toList();
        var grid = importPlan.grid();
        data.put("inputSha256", digest);
        if(uvMode==SurfaceImportGeometry.UvMode.FLAT_COLOR_INTERIOR)
            data.put("renderUvTransform",List.of(.5,.25,.5,.25));
        data.put("convexProof", Map.of("sourceSlots", proof.sourceSlots(), "vertices", proof.geometricVertices(),
            "triangles", proof.triangles(), "edges", proof.edges(), "sixVolume", proof.sixVolume().toPlainString()));
        data.put("patches", importPlan.patches().stream().map(p -> Map.of(
            "surfaceIndex", p.surfaceIndex(), "node", p.source().node(), "mesh", p.source().mesh(),
            "primitive", p.source().primitive(), "sourceVertices", p.source().sourceVertices(),
            "sourceTriangles", p.source().sourceTriangles(), "indices", p.source().indices(),
            "uvs", p.source().textureUvs().stream().map(v -> List.of(v.x(), v.y())).toList(),
            "neutralCollapsedTriangles", p.neutralCollapsedTriangles())).toList());
        var yawKeys = SurfaceKeyformBake.bindingKeys(grid.yawKeys());
        var pitchKeys = SurfaceKeyformBake.bindingKeys(grid.pitchKeys());
        data.put("yawKeys", yawKeys); data.put("pitchKeys", pitchKeys);
        data.put("idealErrorBoundPixels", grid.errorBoundPixels());
        data.put("maxNativeErrorPixels", .1);
        data.put("rotationOrder", "Ry(yaw)*Rx(pitch)");
        data.put("camera", List.of(512, 512, 200, 0, 0, 0));
        data.put("sources", sources.stream().map(s -> s.stream().map(v -> List.of(v.x(), v.y(), v.z())).toList()).toList());
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
                    "cube-geometry canvas setup failed");
                current.get().parameters().createMany(List.of(
                    new ParameterDefinition(YAW, "MI3D Cube Yaw", -30, 0, 30, ParameterType.NORMAL, false),
                    new ParameterDefinition(PITCH, "MI3D Cube Pitch", -30, 0, 30, ParameterType.NORMAL, false)));
                return null;
            });
            var created = edt(() -> SurfaceImportGeometry.create(current.get(), importPlan, YAW, PITCH, null, "MI3D_CUBE", uvMode));
            var ids = created.patches().stream().map(SurfaceImportGeometry.BoundPatch::meshId).toList();
            var plans = created.patches().stream().map(SurfaceImportGeometry.BoundPatch::bake).toList();
            data.put("meshIds", ids.stream().map(ArtMeshId::value).toList());
            data.put("before", plans.stream().map(p -> NativeAssertions.snapshot(p.before())).toList());
            var beforeWrite = edt(history::get);
            var result = edt(() -> transactions.execute(AuthoringTransactionOptions.of("MI3D cube geometry bake"), () -> {
                created.bake(current.get());
                return null;
            }));
            var afterWrite = edt(history::get);
            data.put("writeOutcome", result.outcome().name());
            data.put("writeBefore", historyData(beforeWrite)); data.put("writeAfter", historyData(afterWrite));
            NativeAssertions.require(result.outcome() == AuthoringTransactionOutcome.COMMITTED
                && afterWrite.position() == beforeWrite.position() + 1, "cube bake was not one committed history entry");
            data.put("written", snapshots(current, ids));
            edt(() -> { for (int i = 0; i < ids.size(); i++) plans.get(i).verify(current.get().drawables().find(ids.get(i))); return null; });
            edt(() -> { restore.accept(beforeWrite.position()); return null; });
            data.put("undone", snapshots(current, ids));
            edt(() -> {
                for (int i = 0; i < ids.size(); i++) NativeAssertions.require(
                    current.get().drawables().find(ids.get(i)).authoringSnapshot().equals(plans.get(i).before()),
                    "cube bake Undo changed protected fields or failed to restore positions");
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
                    var expected = SurfaceProjection.project(sources.get(i), CAMERA, pose).positions();
                    detail.put("maxErrorPixels", EvaluationProbe.maxError(actual, expected));
                    NativeAssertions.require(EvaluationProbe.validGeometry(actual, expected, plans.get(i).before().forms().get(0).drawOrder()),
                        "native cube-geometry projection exceeds 0.1px or has wrong coordinate/order state");
                    for (var axis : List.of(YAW, PITCH)) {
                        var values = actual.liveParameters().parameters().stream().filter(p -> p.parameterId().equals(axis)).toList();
                        float expectedValue = (float) (axis.equals(YAW) ? pose.yaw() : pose.pitch());
                        NativeAssertions.require(values.size() == 1 && values.get(0).value() == expectedValue,
                            "cube-geometry requested value was clamped or not applied");
                    }
                }
                var afterRead = edt(history::get);
                row.put("historyAfter", historyData(afterRead));
                NativeAssertions.require(h.equals(afterRead), "cube evaluation mutated history");
                row.put("status", "PASS");
            }
            edt(() -> { for (int i = 0; i < ids.size(); i++) plans.get(i).verify(current.get().drawables().find(ids.get(i))); return null; });
            data.put("afterEvaluation", snapshots(current, ids));
            if(afterGeometry!=null) {
                afterGeometry.run(surfaces,importPlan,created);
                edt(() -> {for(var patch:created.patches())patch.bake().verify(current.get().drawables().find(patch.meshId()));return null;});
            }
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
                    "cube-geometry restoration failed");
                data.put("finallyRestored", true);
            } catch (Exception | Error failure) {
                data.put("finallyRestored", false); data.put("status", "FAIL"); data.put("recoveryFailure", failure.toString());
                if (primary != null) primary.addSuppressed(failure); else throw failure;
            }
        }
        data.put("status", "PASS"); data.put("failure", null);
    }

    static List<SurfaceProjection.Pose> poses(List<Float> yaw, List<Float> pitch) {
        var result = new java.util.LinkedHashSet<SurfaceProjection.Pose>();
        // Frozen critical/domain grid, both axes through every stored key, diagonal cell centers,
        // then a deterministic random inventory. No selection depends on native output.
        var critical = List.of(-30f, -15f, -1f, -.25f, 0f, .25f, 1f, 15f, 30f);
        for (float y : critical) for (float p : critical) result.add(new SurfaceProjection.Pose(y,p));
        for (float y : yaw) result.add(new SurfaceProjection.Pose(y,0));
        for (float p : pitch) result.add(new SurfaceProjection.Pose(0,p));
        NativeAssertions.require(yaw.equals(pitch), "cube pose inventory requires identical axes");
        for (int i=1; i<yaw.size(); i++) {
            float midpoint = (yaw.get(i-1)+yaw.get(i))/2;
            result.add(new SurfaceProjection.Pose(midpoint,midpoint));
            result.add(new SurfaceProjection.Pose(midpoint,-midpoint));
        }
        var random = new Random(4012027);
        for (int i=0; i<128; i++) result.add(new SurfaceProjection.Pose((float)(-30+60*random.nextDouble()),
            (float)(-30+60*random.nextDouble())));
        return List.copyOf(result);
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

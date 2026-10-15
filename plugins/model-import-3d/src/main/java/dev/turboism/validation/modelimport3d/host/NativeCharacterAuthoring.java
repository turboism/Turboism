package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.id.*;
import dev.turboism.sdk.cubism.model.*;
import java.util.*;
import java.util.Optional;

/**
 * Native Cubism ArtMesh author for humanoid character models.
 *
 * <p>Constructs native ArtMeshes with exact source geometry (source indices and UVs),
 * applies double-sided rendering, binds XYZ rotations (X and Y to 5x5 keyforms, Z to native
 * rotation deformer), writes keyform positions and depth drawOrders, and enforces budget
 * protection and exact readback verification.
 */
public final class NativeCharacterAuthoring {
    private NativeCharacterAuthoring() {}

    @FunctionalInterface
    public interface MeshMaterialBinder {
        void bind(ArtMeshId meshId, int sourceMeshIndex);
    }

    public record Created(
            PartId rootPartId,
            DeformerId rollDeformerId,
            List<ArtMeshId> meshIds,
            Map<ArtMeshId, Integer> meshToSourceIndex) {
        public Created {
            meshIds = List.copyOf(meshIds);
            meshToSourceIndex = Map.copyOf(meshToSourceIndex);
        }
    }

    public static Created assemble(CubismModel model, NativeCharacterPlan.Plan plan, MeshMaterialBinder binder) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(binder, "binder");

        var request = plan.request();
        var camera = plan.camera();

        // 1. Parameter conflict validation
        var axes = new ArrayList<ParameterId>();
        axes.add(request.yaw());
        axes.add(request.pitch());
        request.roll().ifPresent(axes::add);
        for (var param : model.parameters().all()) {
            if (axes.contains(param.id())) {
                throw new IllegalArgumentException("Import parameter ID already exists in model: " + param.id());
            }
        }

        // 2. Budget preflight
        if (plan.totalKeyformSlots() > NativeCharacterPlan.MAX_TOTAL_KEYFORM_SLOTS) {
            throw new IllegalArgumentException("Plan exceeds 6M save budget: " + plan.totalKeyformSlots());
        }

        // 3. Create root Part
        var rootPart = model.parts().create(request.name(), null, -1);

        // 4. Create Roll (Z-rotation) Deformer if requested
        RotationDeformer rollDeformer = null;
        if (request.roll().isPresent()) {
            rollDeformer = SurfaceRotationAuthoring.create(model, rootPart, request);
        }

        float ox = (float) (camera.centerX() + camera.scale() * camera.pivot().x());
        float oy = (float) (camera.centerY() - camera.scale() * camera.pivot().y());

        // 5. Create ArtMeshes for all partitions
        var meshIds = new ArrayList<ArtMeshId>();
        var patchParts = new ArrayList<PartId>();
        var meshToSourceIndex = new LinkedHashMap<ArtMeshId, Integer>();

        int neutralKeyIndex = NativeCharacterPlan.KEYS.indexOf(0f) * NativeCharacterPlan.KEYS.size()
                + NativeCharacterPlan.KEYS.indexOf(0f);

        for (int pIdx = 0; pIdx < plan.partitions().size(); pIdx++) {
            var partition = plan.partitions().get(pIdx);
            var patchPart = model.parts().create(request.name() + " 分区 " + pIdx, rootPart, -1);
            patchParts.add(patchPart.id());

            var neutralSample =
                    SurfaceProjection.project(partition.positions(), camera, new SurfaceProjection.Pose(0, 0));
            var defaultCanvasPositions = neutralSample.positions();

            var geometry = new ArtMeshGeometry(defaultCanvasPositions, partition.uvs(), partition.indices());

            var mesh = model.drawables()
                    .createWithSourceGeometry(
                            request.name() + " " + partition.name() + " " + pIdx,
                            patchPart,
                            -1,
                            geometry,
                            defaultCanvasPositions);

            mesh.setCulling(false); // GLB primitives require double-sided rendering
            int initialOrder = plan.orders().get(neutralKeyIndex).get(pIdx);
            mesh.setDrawOrder(initialOrder);

            meshIds.add(mesh.id());
            meshToSourceIndex.put(mesh.id(), partition.sourceMeshIndex());

            // Notify material binder
            binder.bind(mesh.id(), partition.sourceMeshIndex());

            // Readback verify source geometry
            var snapshot = mesh.authoringSnapshot();
            if (!snapshot.sourceGeometry().equals(geometry)) {
                throw new IllegalStateException("Source geometry readback changed for partition " + pIdx);
            }
        }

        // 6. Create Yaw and Pitch parameters
        model.parameters()
                .createMany(List.of(
                        new ParameterDefinition(
                                request.yaw(), request.name() + " Y 旋转", -180, 0, 180, ParameterType.NORMAL, false),
                        new ParameterDefinition(
                                request.pitch(), request.name() + " X 旋转", -180, 0, 180, ParameterType.NORMAL, false)));

        // 7. Bind deformer, parameters and write 5x5 keyform positions and orders
        var banks = new ArrayList<ArtMeshAuthoringSnapshot>();
        for (int pIdx = 0; pIdx < meshIds.size(); pIdx++) {
            var meshId = meshIds.get(pIdx);
            var mesh = model.drawables().find(meshId);
            var partition = plan.partitions().get(pIdx);

            if (rollDeformer != null) {
                mesh.setTargetDeformer(Optional.of(rollDeformer.id()));
            }

            for (var axis : List.of(request.yaw(), request.pitch())) {
                var points = new ArrayList<ParameterBindingPoint>();
                for (int k = 0; k < NativeCharacterPlan.KEYS.size(); k++) {
                    points.add(new ParameterBindingPoint(
                            new ParameterBindingPointId("CHAR_" + pIdx + "_" + axis.value() + "_" + k),
                            NativeCharacterPlan.KEYS.get(k)));
                }
                model.parameterBindings(axis).bind(ParameterBindingTarget.artMesh(mesh.id()), points);
            }

            var bound = mesh.authoringSnapshot();
            if (bound.forms().size() != 25
                    || bound.cells().size() != 25
                    || bound.axes().size() != 2) {
                throw new IllegalStateException("ArtMesh 25-form grid required, found forms="
                        + bound.forms().size());
            }

            var yawAxis = bound.axes().stream()
                    .filter(a -> a.parameterId().equals(request.yaw()))
                    .findFirst()
                    .orElseThrow();
            var pitchAxis = bound.axes().stream()
                    .filter(a -> a.parameterId().equals(request.pitch()))
                    .findFirst()
                    .orElseThrow();

            var posEdits = new ArrayList<ArtMeshKeyformPositions>();
            var orderEdits = new ArrayList<ArtMeshKeyformDrawOrder>();
            var desiredPos = new HashMap<String, List<Point2>>();
            var desiredOrders = new HashMap<String, Integer>();

            for (var cell : bound.cells()) {
                float y = cell.coordinates().stream()
                        .filter(c -> c.parameterGuid().equals(yawAxis.parameterGuid()))
                        .findFirst()
                        .orElseThrow()
                        .value();
                float x = cell.coordinates().stream()
                        .filter(c -> c.parameterGuid().equals(pitchAxis.parameterGuid()))
                        .findFirst()
                        .orElseThrow()
                        .value();

                int keyIdx = NativeCharacterPlan.KEYS.indexOf(y) * NativeCharacterPlan.KEYS.size()
                        + NativeCharacterPlan.KEYS.indexOf(x);
                int order = plan.orders().get(keyIdx).get(pIdx);

                List<Point2> positions;
                if (y != 0 || x != 0) {
                    var sample =
                            SurfaceProjection.project(partition.positions(), camera, new SurfaceProjection.Pose(y, x));
                    positions = rollDeformer != null ? local(sample.positions(), ox, oy) : sample.positions();
                    posEdits.add(new ArtMeshKeyformPositions(cell.coordinates(), cell.formGuid(), positions));
                    orderEdits.add(new ArtMeshKeyformDrawOrder(cell.coordinates(), cell.formGuid(), order));
                } else {
                    positions = bound.forms().stream()
                            .filter(f -> f.formGuid().equals(cell.formGuid()))
                            .findFirst()
                            .orElseThrow()
                            .positions();
                }
                desiredPos.put(cell.formGuid(), positions);
                desiredOrders.put(cell.formGuid(), order);
            }

            mesh.setOrdinaryKeyformPositions(bound, posEdits);
            var positioned = mesh.authoringSnapshot();
            mesh.setOrdinaryKeyformDrawOrders(positioned, orderEdits);

            var expected = new ArtMeshAuthoringSnapshot(
                    bound.artMeshId(),
                    bound.sourceGuid(),
                    bound.sourceGeometry(),
                    bound.axes(),
                    bound.cells(),
                    bound.forms().stream()
                            .map(f -> new ArtMeshAuthoringSnapshot.Form(
                                    f.formGuid(), desiredPos.get(f.formGuid()), desiredOrders.get(f.formGuid())))
                            .toList(),
                    bound.morphKeys());

            if (!expected.equals(mesh.authoringSnapshot())) {
                throw new IllegalStateException("ArtMesh keyform readback mismatch for partition " + pIdx);
            }
            banks.add(expected);
        }

        // 8. Outer placement wrapper
        if (rollDeformer != null) {
            SurfacePlacementAuthoring.wrap(model, rootPart, request.name(), rollDeformer);
        }

        // 9. Set interpolation on parameter bindings
        for (int pIdx = 0; pIdx < meshIds.size(); pIdx++) {
            var mesh = model.drawables().find(meshIds.get(pIdx));
            var sampling = plan.sampling().get(pIdx);
            model.parameterBindings(request.yaw())
                    .setInterpolation(ParameterBindingTarget.artMesh(mesh.id()), sampling.yaw());
            model.parameterBindings(request.pitch())
                    .setInterpolation(ParameterBindingTarget.artMesh(mesh.id()), sampling.pitch());
        }

        DeformerId rollId = rollDeformer != null ? rollDeformer.id() : null;
        return new Created(rootPart.id(), rollId, meshIds, meshToSourceIndex);
    }

    private static List<Point2> local(List<Point2> world, float ox, float oy) {
        var res = new ArrayList<Point2>(world.size());
        for (var p : world) {
            res.add(new Point2(p.x() - ox, p.y() - oy));
        }
        return res;
    }
}

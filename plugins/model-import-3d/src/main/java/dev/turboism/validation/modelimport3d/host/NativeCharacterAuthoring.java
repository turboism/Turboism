package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.id.*;
import dev.turboism.sdk.cubism.model.*;
import java.util.*;
import java.util.Optional;

/**
 * Native Cubism ArtMesh author for humanoid character models.
 *
 * <p>Constructs native ArtMeshes with exact source geometry (source indices and UVs),
 * maps source UVs to texture dimensions on the source image plane, respects source material
 * double-sided culling, binds XYZ rotations (X and Y to 5x5 keyforms, Z to native
 * rotation deformer), writes keyform positions and depth drawOrders, and enforces budget
 * protection and exact readback verification.
 */
public final class NativeCharacterAuthoring {
    private NativeCharacterAuthoring() {}

    /** Binds a newly created mesh to its source primitive material. */
    @FunctionalInterface
    public interface MeshMaterialBinder {
        /** Registers or reuses the source primitive image for this mesh. */
        void bind(ArtMeshId meshId, int sourceMeshIndex);
    }

    /** Pixel dimensions of the baked source image used for UV reconstruction. */
    public record ImageDimensions(int width, int height) {
        public ImageDimensions {
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("Image dimensions must be positive: " + width + "x" + height);
            }
        }
    }

    /** Supplies source image dimensions for each scene primitive. */
    @FunctionalInterface
    public interface ImageDimensionProvider {
        /** Returns the baked image dimensions for a source primitive. */
        ImageDimensions dimensions(int sourceMeshIndex);
    }

    /** Stable identities of the native hierarchy and its source primitive mapping. */
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

    /** Assembles using a 1024 pixel source image; the caller owns the transaction. */
    public static Created assemble(CubismModel model, NativeCharacterPlan.Plan plan, MeshMaterialBinder binder) {
        return assemble(model, plan, binder, idx -> new ImageDimensions(1024, 1024));
    }

    /** Assembles native geometry and materials; the caller owns the transaction. */
    public static Created assemble(
            CubismModel model,
            NativeCharacterPlan.Plan plan,
            MeshMaterialBinder binder,
            ImageDimensionProvider imageDimensions) {
        return assemble(model, plan, binder, imageDimensions, idx -> "Mesh " + idx);
    }

    /** Shares one opacity parent across all partitions in each source appearance group. */
    public static Created assemble(
            CubismModel model,
            NativeCharacterPlan.Plan plan,
            MeshMaterialBinder binder,
            ImageDimensionProvider imageDimensions,
            java.util.function.IntFunction<String> appearanceGroups) {
        return assemble(model, plan, binder, imageDimensions, appearanceGroups, idx -> "");
    }

    /** Groups native ArtMeshes below anatomical Warp parents and outfit opacity Warp parents. */
    public static Created assemble(
            CubismModel model,
            NativeCharacterPlan.Plan plan,
            MeshMaterialBinder binder,
            ImageDimensionProvider imageDimensions,
            java.util.function.IntFunction<String> appearanceGroups,
            java.util.function.IntFunction<String> bodyParts) {
        return assemble(model, plan, binder, imageDimensions, appearanceGroups, bodyParts, created -> {});
    }

    /** Completes composed source writes before the expensive interpolation caches are initialized. */
    static Created assemble(
            CubismModel model,
            NativeCharacterPlan.Plan plan,
            MeshMaterialBinder binder,
            ImageDimensionProvider imageDimensions,
            java.util.function.IntFunction<String> appearanceGroups,
            java.util.function.IntFunction<String> bodyParts,
            java.util.function.Consumer<Created> sourceWrites) {
        return assemble(
                model, plan, binder, imageDimensions, appearanceGroups, bodyParts, sourceWrites, Optional.empty());
    }

    static Created assemble(
            CubismModel model,
            NativeCharacterPlan.Plan plan,
            MeshMaterialBinder binder,
            ImageDimensionProvider imageDimensions,
            java.util.function.IntFunction<String> appearanceGroups,
            java.util.function.IntFunction<String> bodyParts,
            java.util.function.Consumer<Created> sourceWrites,
            Optional<ParameterGroupId> parameterGroup) {
        Objects.requireNonNull(sourceWrites, "sourceWrites");
        Objects.requireNonNull(bodyParts, "bodyParts");
        Objects.requireNonNull(appearanceGroups, "appearanceGroups");
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
        if (plan.totalCacheSlots() > NativeCharacterPlan.MAX_CACHE_SLOTS) {
            throw new IllegalArgumentException("Plan exceeds 64M cache budget: " + plan.totalCacheSlots());
        }

        // 3. Create root Part
        var rootPart = model.parts().create(request.name(), null, -1);

        // 4. Create Roll (Z-rotation) Deformer if requested
        RotationDeformer rollDeformer = null;
        if (request.roll().isPresent()) {
            rollDeformer = SurfaceRotationAuthoring.create(model, rootPart, request, parameterGroup);
        }

        float ox = (float) (camera.centerX() + camera.scale() * camera.pivot().x());
        float oy = (float) (camera.centerY() - camera.scale() * camera.pivot().y());

        // 5. Create ArtMeshes for all partitions
        var meshIds = new ArrayList<ArtMeshId>();
        var patchParts = new ArrayList<PartId>();
        var meshToSourceIndex = new LinkedHashMap<ArtMeshId, Integer>();
        var opacityParents = new LinkedHashMap<List<String>, WarpDeformer>();
        var bodyParents = new LinkedHashMap<String, WarpDeformer>();
        var bodyFrames = new LinkedHashMap<String, Frame>();
        for (var partition : plan.partitions()) {
            String part = bodyParts.apply(partition.sourceMeshIndex());
            String group = appearanceGroups.apply(partition.sourceMeshIndex());
            String key = part.isBlank() ? group : part;
            var frame = bodyFrames.computeIfAbsent(key, ignored -> new Frame());
            var projected = SurfaceProjection.project(partition.positions(), camera, new SurfaceProjection.Pose(0, 0));
            for (var point : projected.positions())
                frame.include(point.x() - (rollDeformer != null ? ox : 0), point.y() - (rollDeformer != null ? oy : 0));
        }
        bodyFrames.values().forEach(Frame::pad);

        int neutralKeyIndex = NativeCharacterPlan.KEYS.indexOf(0f) * NativeCharacterPlan.KEYS.size()
                + NativeCharacterPlan.KEYS.indexOf(0f);

        for (int pIdx = 0; pIdx < plan.partitions().size(); pIdx++) {
            var partition = plan.partitions().get(pIdx);
            var patchPart = model.parts().create(request.name() + " 分区 " + pIdx, rootPart, -1);
            patchParts.add(patchPart.id());

            var neutralSample =
                    SurfaceProjection.project(partition.positions(), camera, new SurfaceProjection.Pose(0, 0));
            var defaultCanvasPositions = neutralSample.positions();

            var dim = imageDimensions != null
                    ? imageDimensions.dimensions(partition.sourceMeshIndex())
                    : new ImageDimensions(1024, 1024);
            if (dim == null) dim = new ImageDimensions(1024, 1024);

            var imagePositions = new ArrayList<Point2>(partition.uvs().size());
            for (var uv : partition.uvs()) {
                imagePositions.add(new Point2(uv.x() * dim.width(), uv.y() * dim.height()));
            }

            var geometry = new ArtMeshGeometry(imagePositions, partition.uvs(), partition.indices());

            var mesh = model.drawables()
                    .createWithSourceGeometry(
                            request.name() + " " + partition.name() + " " + pIdx,
                            patchPart,
                            -1,
                            geometry,
                            defaultCanvasPositions);

            mesh.setCulling(!partition.doubleSided());
            mesh.setVisible(partition.visible());
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
                .createMany(
                        List.of(
                                new ParameterDefinition(
                                        request.yaw(),
                                        request.name() + " Y 旋转",
                                        -180,
                                        0,
                                        180,
                                        ParameterType.NORMAL,
                                        false),
                                new ParameterDefinition(
                                        request.pitch(),
                                        request.name() + " X 旋转",
                                        -180,
                                        0,
                                        180,
                                        ParameterType.NORMAL,
                                        false)),
                        parameterGroup);

        // Complete native parent conversion while every ArtMesh still has one form.
        // Later hierarchy writes must not repeatedly rebuild earlier meshes' 25-form banks.
        for (int pIdx = 0; pIdx < meshIds.size(); pIdx++) {
            var meshId = meshIds.get(pIdx);
            var mesh = model.drawables().find(meshId);
            var partition = plan.partitions().get(pIdx);

            if (!partition.visible()) continue;

            String group = Objects.requireNonNull(appearanceGroups.apply(partition.sourceMeshIndex()));
            String bodyPart = Objects.requireNonNull(bodyParts.apply(partition.sourceMeshIndex()));
            String bodyKey = bodyPart.isBlank() ? group : bodyPart;
            Frame frame = bodyFrames.get(bodyKey);
            var bodyParent = bodyParents.get(bodyKey);
            if (bodyParent == null) {
                bodyParent = model.deformers().createWarp(bodyKey, rootPart, -1, 2, 2);
                if (rollDeformer != null) bodyParent.setTargetDeformer(Optional.of(rollDeformer.id()));
                bodyParent.replaceGrid(frame.grid());
                bodyParent.setOpacity(1);
                bodyParents.put(bodyKey, bodyParent);
            }
            WarpDeformer opacityParent = bodyParent;
            if (!bodyPart.isBlank()) {
                var key = List.of(bodyKey, group);
                opacityParent = opacityParents.get(key);
                if (opacityParent == null) {
                    opacityParent = model.deformers().createWarp(group, rootPart, -1, 2, 2);
                    opacityParent.setTargetDeformer(Optional.of(bodyParent.id()));
                    opacityParent.replaceGrid(Frame.unitGrid());
                    opacityParent.setOpacity(1);
                    opacityParents.put(key, opacityParent);
                }
            }
            mesh.setTargetDeformer(Optional.of(opacityParent.id()));
        }

        // 7. Expand the completed hierarchy into the 5x5 rotation banks.
        var banks = new ArrayList<ArtMeshAuthoringSnapshot>();
        for (int pIdx = 0; pIdx < meshIds.size(); pIdx++) {
            var mesh = model.drawables().find(meshIds.get(pIdx));
            var partition = plan.partitions().get(pIdx);
            if (!partition.visible()) {
                banks.add(mesh.authoringSnapshot());
                continue;
            }
            String group = Objects.requireNonNull(appearanceGroups.apply(partition.sourceMeshIndex()));
            String bodyPart = Objects.requireNonNull(bodyParts.apply(partition.sourceMeshIndex()));
            Frame frame = bodyFrames.get(bodyPart.isBlank() ? group : bodyPart);

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
                    positions = frame.normalize(
                            rollDeformer != null ? local(sample.positions(), ox, oy) : sample.positions());
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

        // Verify placement did not mutate stored authoring snapshots
        for (int pIdx = 0; pIdx < meshIds.size(); pIdx++) {
            if (!banks.get(pIdx)
                    .equals(model.drawables().find(meshIds.get(pIdx)).authoringSnapshot())) {
                throw new IllegalStateException("Placement changed direct source banks for partition " + pIdx);
            }
        }

        DeformerId rollId = rollDeformer != null ? rollDeformer.id() : null;
        var created = new Created(rootPart.id(), rollId, meshIds, meshToSourceIndex);
        // Atlas and local Warp writes can rebuild the entire model. Initialize caches
        // only after those writes, so they do not repeatedly rebuild earlier caches.
        sourceWrites.accept(created);

        // 9. Set interpolation on parameter bindings and verify patch refresh scope
        for (int pIdx = 0; pIdx < meshIds.size(); pIdx++) {
            var mesh = model.drawables().find(meshIds.get(pIdx));
            if (!plan.partitions().get(pIdx).visible()) continue;
            var beforeInterpolation = mesh.authoringSnapshot();
            var sampling = plan.sampling().get(pIdx);
            model.parameterBindings(request.yaw())
                    .setInterpolation(ParameterBindingTarget.artMesh(mesh.id()), sampling.yaw());
            model.parameterBindings(request.pitch())
                    .setInterpolation(ParameterBindingTarget.artMesh(mesh.id()), sampling.pitch());

            if (!beforeInterpolation.equals(mesh.authoringSnapshot())) {
                throw new IllegalStateException("Interpolation changed source banks for partition " + pIdx);
            }
            if (!mesh.parentPartId().equals(Optional.of(patchParts.get(pIdx)))) {
                throw new IllegalStateException(
                        "Interpolation changed local patch refresh scope for partition " + pIdx);
            }
        }

        return created;
    }

    static final class Frame {
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;

        void include(float x, float y) {
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
        }

        void pad() {
            minX -= 16;
            minY -= 16;
            maxX += 16;
            maxY += 16;
        }

        WarpGrid grid() {
            return grid(minX, minY, maxX, maxY);
        }

        static WarpGrid unitGrid() {
            return grid(0, 0, 1, 1);
        }

        static WarpGrid grid(float x0, float y0, float x1, float y1) {
            var points = new ArrayList<Point2>();
            for (int y = 0; y <= 2; y++)
                for (int x = 0; x <= 2; x++) points.add(new Point2(x0 + (x1 - x0) * x / 2, y0 + (y1 - y0) * y / 2));
            return new WarpGrid(2, 2, true, points);
        }

        List<Point2> normalize(List<Point2> positions) {
            return positions.stream()
                    .map(p -> new Point2((p.x() - minX) / (maxX - minX), (p.y() - minY) / (maxY - minY)))
                    .toList();
        }
    }

    private static List<Point2> local(List<Point2> world, float ox, float oy) {
        var res = new ArrayList<Point2>(world.size());
        for (var p : world) {
            res.add(new Point2(p.x() - ox, p.y() - oy));
        }
        return res;
    }
}

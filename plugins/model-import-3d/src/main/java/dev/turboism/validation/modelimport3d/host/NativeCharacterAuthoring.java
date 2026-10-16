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
    static final int MESH_CREATION_BATCH_SIZE = 32;

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
            Map<ArtMeshId, Integer> meshToSourceIndex,
            OptionalDouble maxNeutralConversionErrorPixels) {
        /** Compatibility result for callers without a native-parent coordinate measurement. */
        public Created(
                PartId rootPartId,
                DeformerId rollDeformerId,
                List<ArtMeshId> meshIds,
                Map<ArtMeshId, Integer> meshToSourceIndex) {
            this(rootPartId, rollDeformerId, meshIds, meshToSourceIndex, OptionalDouble.empty());
        }

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
        return assemble(
                model,
                plan,
                binder,
                imageDimensions,
                appearanceGroups,
                bodyParts,
                sourceWrites,
                parameterGroup,
                created -> {},
                (partition, points) -> points);
    }

    static Created assemble(
            CubismModel model,
            NativeCharacterPlan.Plan plan,
            MeshMaterialBinder binder,
            ImageDimensionProvider imageDimensions,
            java.util.function.IntFunction<String> appearanceGroups,
            java.util.function.IntFunction<String> bodyParts,
            java.util.function.Consumer<Created> sourceWrites,
            Optional<ParameterGroupId> parameterGroup,
            java.util.function.Consumer<Created> beforeRotation,
            java.util.function.BiFunction<Integer, List<Point2>, List<Point2>> rotationLocalizer) {
        return assemble(
                model,
                plan,
                binder,
                imageDimensions,
                appearanceGroups,
                bodyParts,
                sourceWrites,
                parameterGroup,
                beforeRotation,
                rotationLocalizer,
                Runnable::run);
    }

    static Created assemble(
            CubismModel model,
            NativeCharacterPlan.Plan plan,
            MeshMaterialBinder binder,
            ImageDimensionProvider imageDimensions,
            java.util.function.IntFunction<String> appearanceGroups,
            java.util.function.IntFunction<String> bodyParts,
            java.util.function.Consumer<Created> sourceWrites,
            Optional<ParameterGroupId> parameterGroup,
            java.util.function.Consumer<Created> beforeRotation,
            java.util.function.BiFunction<Integer, List<Point2>, List<Point2>> rotationLocalizer,
            java.util.function.Consumer<Runnable> sourceBatch) {
        return assemble(
                model,
                plan,
                binder,
                imageDimensions,
                appearanceGroups,
                bodyParts,
                sourceWrites,
                parameterGroup,
                beforeRotation,
                rotationLocalizer,
                sourceBatch,
                hierarchy -> hierarchy.parents(),
                null);
    }

    /** Parent identities are complete before any imported ArtMesh is materialized. */
    record Hierarchy(PartId rootPartId, List<Integer> sourceIndices, List<Optional<DeformerId>> parents) {
        Hierarchy {
            sourceIndices = List.copyOf(sourceIndices);
            parents = List.copyOf(parents);
            if (sourceIndices.size() != parents.size()) throw new IllegalArgumentException("Hierarchy size differs");
        }
    }

    static Created assemble(
            CubismModel model,
            NativeCharacterPlan.Plan plan,
            MeshMaterialBinder binder,
            ImageDimensionProvider imageDimensions,
            java.util.function.IntFunction<String> appearanceGroups,
            java.util.function.IntFunction<String> bodyParts,
            java.util.function.Consumer<Created> sourceWrites,
            Optional<ParameterGroupId> parameterGroup,
            java.util.function.Consumer<Created> beforeRotation,
            java.util.function.BiFunction<Integer, List<Point2>, List<Point2>> rotationLocalizer,
            java.util.function.Consumer<Runnable> sourceBatch,
            java.util.function.Function<Hierarchy, List<Optional<DeformerId>>> prepareHierarchy,
            java.util.function.BiFunction<Integer, List<Point2>, List<Point2>> rotationRestorer) {
        Objects.requireNonNull(beforeRotation, "beforeRotation");
        Objects.requireNonNull(rotationLocalizer, "rotationLocalizer");
        Objects.requireNonNull(sourceWrites, "sourceWrites");
        Objects.requireNonNull(bodyParts, "bodyParts");
        Objects.requireNonNull(appearanceGroups, "appearanceGroups");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(binder, "binder");
        Objects.requireNonNull(prepareHierarchy, "prepareHierarchy");
        Objects.requireNonNull(sourceBatch, "sourceBatch");

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

        if (plan.orders().size() != plan.keys().size() * plan.keys().size()
                || plan.orders().stream()
                        .anyMatch(row -> row.size() != plan.partitions().size()))
            throw new IllegalArgumentException("Native character plan has incomplete visibility orders");

        // 3. Create root Part
        var rootPart = model.parts().create(request.name(), null, -1);
        // Part creation rebuilds native model instances. Create every isolated patch
        // container before the imported geometry and its deformation hierarchy exist.
        var patchParts = model
                .parts()
                .createMany(
                        java.util.stream.IntStream.range(0, plan.partitions().size())
                                .mapToObj(index -> request.name() + " 分区 " + index)
                                .toList(),
                        rootPart)
                .stream()
                .map(Part::id)
                .toList();

        // 4. Create Roll (Z-rotation) Deformer if requested
        RotationDeformer rollDeformer = null;
        if (request.roll().isPresent()) {
            rollDeformer = SurfaceRotationAuthoring.create(model, rootPart, request, parameterGroup);
        }

        float ox = (float) (camera.centerX() + camera.scale() * camera.pivot().x());
        float oy = (float) (camera.centerY() - camera.scale() * camera.pivot().y());

        // 5. Create ArtMeshes for all partitions
        var meshIds = new ArrayList<ArtMeshId>();
        var meshToSourceIndex = new LinkedHashMap<ArtMeshId, Integer>();
        var opacityParents = new LinkedHashMap<List<String>, DeformerId>();
        var bodyParents = new LinkedHashMap<String, DeformerId>();
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

        // Build all deformation parents while the imported geometry is still absent.
        var parentIds = new ArrayList<Optional<DeformerId>>();
        for (int pIdx = 0; pIdx < plan.partitions().size(); pIdx++) {
            var partition = plan.partitions().get(pIdx);

            if (!partition.visible()) {
                parentIds.add(Optional.empty());
                continue;
            }

            String group = Objects.requireNonNull(appearanceGroups.apply(partition.sourceMeshIndex()));
            String bodyPart = Objects.requireNonNull(bodyParts.apply(partition.sourceMeshIndex()));
            String bodyKey = bodyPart.isBlank() ? group : bodyPart;
            Frame frame = bodyFrames.get(bodyKey);
            var bodyId = bodyParents.get(bodyKey);
            if (bodyId == null) {
                var bodyParent = model.deformers()
                        .createWarp(
                                bodyKey,
                                rootPart,
                                -1,
                                rollDeformer == null ? Optional.empty() : Optional.of(rollDeformer.id()),
                                frame.grid());
                bodyId = bodyParent.id();
                bodyParent.setOpacity(1);
                bodyParents.put(bodyKey, bodyId);
            }
            var opacityId = bodyId;
            if (!bodyPart.isBlank()) {
                var key = List.of(bodyKey, group);
                opacityId = opacityParents.get(key);
                if (opacityId == null) {
                    var opacityParent =
                            model.deformers().createWarp(group, rootPart, -1, Optional.of(bodyId), Frame.unitGrid());
                    opacityId = opacityParent.id();
                    opacityParent.setOpacity(1);
                    opacityParents.put(key, opacityId);
                }
            }
            parentIds.add(Optional.of(opacityId));
        }

        var finalParents = List.copyOf(prepareHierarchy.apply(new Hierarchy(
                rootPart.id(),
                plan.partitions().stream()
                        .map(NativeCharacterPlan.Partition::sourceMeshIndex)
                        .toList(),
                parentIds)));
        if (finalParents.size() != plan.partitions().size())
            throw new IllegalStateException("Prepared parent count differs from partitions");

        int neutralKeyIndex = NativeCharacterPlan.KEYS.indexOf(0f) * NativeCharacterPlan.KEYS.size()
                + NativeCharacterPlan.KEYS.indexOf(0f);

        double maxNeutralErrorPixels = 0;
        for (int batchStart = 0; batchStart < plan.partitions().size(); batchStart += MESH_CREATION_BATCH_SIZE) {
            int batchEnd = Math.min(plan.partitions().size(), batchStart + MESH_CREATION_BATCH_SIZE);
            var inputs = new ArrayList<Drawables.SourceGeometryRequest>();
            for (int pIdx = batchStart; pIdx < batchEnd; pIdx++) {
                var partition = plan.partitions().get(pIdx);
                var neutralSample =
                        SurfaceProjection.project(partition.positions(), camera, new SurfaceProjection.Pose(0, 0));
                var dim = imageDimensions != null
                        ? imageDimensions.dimensions(partition.sourceMeshIndex())
                        : new ImageDimensions(1024, 1024);
                if (dim == null) dim = new ImageDimensions(1024, 1024);
                var imagePositions = new ArrayList<Point2>(partition.uvs().size());
                for (var uv : partition.uvs())
                    imagePositions.add(new Point2(uv.x() * dim.width(), uv.y() * dim.height()));
                inputs.add(new Drawables.SourceGeometryRequest(
                        request.name() + " " + partition.name() + " " + pIdx,
                        Optional.of(patchParts.get(pIdx)),
                        new ArtMeshGeometry(imagePositions, partition.uvs(), partition.indices()),
                        neutralSample.positions()));
            }
            // Limit each publication to a small batch: later native conversions should
            // not evaluate all future mesh instances before those meshes need to exist.
            var createdMeshes = model.drawables().createManyWithSourceGeometry(inputs);
            if (createdMeshes.size() != inputs.size())
                throw new IllegalStateException("Created mesh batch size differs");
            for (int offset = 0; offset < inputs.size(); offset++) {
                int pIdx = batchStart + offset;
                var partition = plan.partitions().get(pIdx);
                var defaultCanvasPositions = inputs.get(offset).defaultPositions();
                var geometry = inputs.get(offset).sourceGeometry();
                var mesh = createdMeshes.get(offset);

                mesh.setCulling(!partition.doubleSided());
                mesh.setVisible(partition.visible());

                var meshId = mesh.id();
                meshIds.add(meshId);
                meshToSourceIndex.put(meshId, partition.sourceMeshIndex());

                // Image binding requires an unparented ArtMesh; keep it before native conversion.
                binder.bind(meshId, partition.sourceMeshIndex());

                // One native conversion goes directly from canvas to the completed parent chain.
                if (finalParents.get(pIdx).isPresent()) mesh.setTargetDeformer(finalParents.get(pIdx));

                // No native writes occur between the two readback checks below.
                var snapshot = mesh.authoringSnapshot();
                if (rotationRestorer != null && partition.visible()) {
                    String group = appearanceGroups.apply(partition.sourceMeshIndex());
                    String part = bodyParts.apply(partition.sourceMeshIndex());
                    var frame = bodyFrames.get(part.isBlank() ? group : part);
                    var bodyLocal = frame.normalize(
                            rollDeformer != null ? local(defaultCanvasPositions, ox, oy) : defaultCanvasPositions);
                    var expected = rotationLocalizer.apply(pIdx, bodyLocal);
                    var forms = snapshot.forms();
                    if (forms.size() != 1) throw new IllegalStateException("New mesh must have one form");
                    var actual = forms.get(0).positions();
                    if (expected.size() != actual.size())
                        throw new IllegalStateException("Parent changed vertex slots");
                    // Local Warp units vary with patch size. Verify in canvas pixels, not
                    // an arbitrary normalized-coordinate epsilon that amplifies tiny patches.
                    var restored = rotationRestorer.apply(pIdx, actual);
                    if (restored.size() != bodyLocal.size())
                        throw new IllegalStateException("Restored vertex slots differ");
                    for (int v = 0; v < restored.size(); v++) {
                        double dx = (restored.get(v).x() - bodyLocal.get(v).x()) * (frame.maxX - frame.minX);
                        double dy = (restored.get(v).y() - bodyLocal.get(v).y()) * (frame.maxY - frame.minY);
                        double error = Math.hypot(dx, dy);
                        if (!Double.isFinite(error) || error > .001)
                            throw new IllegalStateException(
                                    "Final parent neutral coordinate conversion differs for partition "
                                            + pIdx + ": canvasError=" + error + "px, expected=" + expected.get(v)
                                            + ", actual=" + actual.get(v));
                        maxNeutralErrorPixels = Math.max(maxNeutralErrorPixels, error);
                    }
                }

                // Readback verify source geometry from the same post-conversion state.
                if (!snapshot.sourceGeometry().equals(geometry)) {
                    throw new IllegalStateException("Source geometry readback changed for partition " + pIdx);
                }
            }
        }

        // Render order does not participate in native parent conversion. Populate all
        // initial stored scalars together, then evaluate once before binding rotation banks.
        sourceBatch.accept(() -> {
            for (int pIdx = 0; pIdx < meshIds.size(); pIdx++)
                model.drawables()
                        .find(meshIds.get(pIdx))
                        .setDrawOrder(plan.orders().get(neutralKeyIndex).get(pIdx));
        });

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

        DeformerId rollId = rollDeformer != null ? rollDeformer.id() : null;
        var created = new Created(
                rootPart.id(),
                rollId,
                meshIds,
                meshToSourceIndex,
                rotationRestorer == null ? OptionalDouble.empty() : OptionalDouble.of(maxNeutralErrorPixels));
        // Complete local source-control parents with one form per mesh, before native
        // reparenting would otherwise convert all 25 rotation forms repeatedly.
        beforeRotation.accept(created);

        // 7. Expand the completed hierarchy into the 5x5 rotation banks.
        final boolean hasRoll = rollDeformer != null;
        var banks = new ArrayList<ArtMeshAuthoringSnapshot>();
        // This whole phase touches stored banks only. One barrier precedes placement,
        // atlas work and every later evaluated-instance read.
        sourceBatch.accept(() -> {
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

                final int partitionIndex = pIdx;
                for (var axis : List.of(request.yaw(), request.pitch())) {
                    var points = new ArrayList<ParameterBindingPoint>();
                    for (int k = 0; k < NativeCharacterPlan.KEYS.size(); k++) {
                        points.add(new ParameterBindingPoint(
                                new ParameterBindingPointId("CHAR_" + partitionIndex + "_" + axis.value() + "_" + k),
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
                    int order = plan.orders().get(keyIdx).get(partitionIndex);

                    List<Point2> positions;
                    if (y != 0 || x != 0) {
                        var sample = SurfaceProjection.project(
                                partition.positions(), camera, new SurfaceProjection.Pose(y, x));
                        positions = frame.normalize(hasRoll ? local(sample.positions(), ox, oy) : sample.positions());
                        positions = rotationLocalizer.apply(partitionIndex, positions);
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

                mesh.setOrdinaryKeyforms(bound, posEdits, orderEdits);

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
                    throw new IllegalStateException(
                            "ArtMesh keyform readback mismatch for partition " + partitionIndex);
                }
                banks.add(expected);
            }
        });

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

        // Atlas and local Warp writes can rebuild the entire model. Initialize caches
        // only after those writes, so they do not repeatedly rebuild earlier caches.
        sourceWrites.accept(created);

        // 9. Set interpolation on parameter bindings and verify patch refresh scope
        for (int pIdx = 0; pIdx < meshIds.size(); pIdx++) {
            var mesh = model.drawables().find(meshIds.get(pIdx));
            if (!plan.partitions().get(pIdx).visible()) continue;
            var beforeInterpolation = mesh.authoringSnapshot();
            var sampling = plan.sampling().get(pIdx);
            model.parameterBindingBatch()
                    .setInterpolations(
                            ParameterBindingTarget.artMesh(mesh.id()),
                            Map.of(request.yaw(), sampling.yaw(), request.pitch(), sampling.pitch()));

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

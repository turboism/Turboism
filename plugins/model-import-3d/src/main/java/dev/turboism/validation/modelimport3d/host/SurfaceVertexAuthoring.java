package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.id.*;
import dev.turboism.sdk.cubism.model.*;
import dev.turboism.sdk.cubism.transaction.*;
import java.util.*;
import java.util.function.Supplier;

/** Direct original-vertex projection candidate, independent of UV parameterization.
 * Visibility is bounded patch painter approximation and still requires native acceptance. */
final class SurfaceVertexAuthoring {
    private SurfaceVertexAuthoring() {}

    static final List<Float> KEYS = List.of(-180f, -135f, -90f, -45f, 0f, 45f, 90f, 135f, 180f);
    static final ParameterInterpolation INTERPOLATION =
            new ParameterInterpolation(ParameterInterpolation.Method.ELLIPSE, 3, 1);
    static final long MAX_CACHE_SLOTS = 64_000_000L;

    record Prepared(
            SurfaceImport.Request request,
            SurfaceVertexPlan.Plan geometry,
            SurfaceWarpImagePlan.Plan images,
            List<List<Integer>> orders,
            double interpolationErrorBoundPixels,
            List<Sampling> sampling) {
        Prepared {
            sampling = List.copyOf(sampling);
        }
    }

    record Sampling(ParameterInterpolation yaw, ParameterInterpolation pitch, long grid, double errorBoundPixels) {}

    static double chordError(double radius, int inserts) {
        return radius * (1 - Math.cos(Math.toRadians(45d / (inserts + 1) / 2)));
    }
    /** Minimize derived lattice cells while retaining the existing global error budget. */
    static Sampling sampling(double radius, double maximumError) {
        if (!Double.isFinite(radius) || radius < 0 || !Double.isFinite(maximumError) || maximumError < .01)
            throw new IllegalArgumentException("invalid interpolation sampling budget");
        Sampling best = null;
        for (int yaw = 0; yaw <= INTERPOLATION.insertPointCount(); yaw++)
            for (int pitch = 0; pitch <= INTERPOLATION.insertPointCount(); pitch++) {
                double error = chordError(radius, yaw) + chordError(radius, pitch) + .01;
                if (error > maximumError) continue;
                long grid = (KEYS.size() + (KEYS.size() - 1L) * yaw) * (KEYS.size() + (KEYS.size() - 1L) * pitch);
                if (best == null || grid < best.grid() || grid == best.grid() && error < best.errorBoundPixels())
                    best = new Sampling(
                            new ParameterInterpolation(ParameterInterpolation.Method.ELLIPSE, yaw, 1),
                            new ParameterInterpolation(ParameterInterpolation.Method.ELLIPSE, pitch, 1),
                            grid,
                            error);
            }
        if (best == null) throw new IllegalArgumentException("direct joint interpolation sampling budget exceeded");
        return best;
    }

    record Created(
            PartId partId,
            DeformerId rollId,
            List<ArtMeshId> meshes,
            List<ModelImageBinding> images,
            List<ArtMeshAuthoringSnapshot> banks) {
        Created {
            meshes = List.copyOf(meshes);
            images = List.copyOf(images);
            banks = List.copyOf(banks);
        }
    }

    static Prepared prepareFramed(byte[] bytes, SurfaceImport.Request request, int maximumMeshes)
            throws java.io.IOException {
        var sources = GlbSurfaceReader.read(bytes);
        var camera = SurfaceAutoCamera.fit(sources, 512, 512, 400);
        var framed = new SurfaceImport.Request(
                request.name(),
                request.yaw(),
                request.pitch(),
                camera,
                request.yawLimit(),
                request.pitchLimit(),
                request.maxProjectedVertices(),
                request.roll(),
                request.labels());
        return prepareDecoded(sources, framed, maximumMeshes);
    }

    static Prepared prepareDecoded(
            List<GlbSurfaceReader.Surface> sources, SurfaceImport.Request request, int maximumMeshes)
            throws java.io.IOException {
        if (request.roll().isEmpty() || request.yawLimit() != 180 || request.pitchLimit() != 180)
            throw new IllegalArgumentException("direct projection requires XYZ full circle");
        if (sources.stream().anyMatch(GlbSurfaceReader.Surface::doubleSided))
            throw new IllegalArgumentException("direct partition projection requires source front-face culling");
        var geometry = SurfaceVertexPlan.compile(sources, maximumMeshes);
        return prepareGeometry(request, geometry, SurfaceWarpImagePlan.compileVertex(geometry, request.camera()));
    }

    static Prepared prepareEditableDecoded(
            List<GlbSurfaceReader.Surface> sources,
            SurfaceImport.Request request,
            int originalMeshes,
            int maximumEditableMeshes)
            throws java.io.IOException {
        if (request.roll().isEmpty() || request.yawLimit() != 180 || request.pitchLimit() != 180)
            throw new IllegalArgumentException("editable direct projection requires XYZ full circle");
        if (sources.stream().anyMatch(GlbSurfaceReader.Surface::doubleSided))
            throw new IllegalArgumentException("editable direct projection requires source front-face culling");
        var editable = SurfaceWarpImagePlan.compileEditableVertex(
                SurfaceVertexPlan.compile(sources, originalMeshes), request.camera(), maximumEditableMeshes);
        return prepareGeometry(request, editable.geometry(), editable.images());
    }

    static double projectedRadius(SurfaceVertexPlan.Mesh mesh, SurfaceProjection.Camera camera) {
        double radius = 0;
        var pivot = camera.pivot();
        for (var v : mesh.positions())
            radius = Math.max(
                    radius,
                    camera.scale()
                            * Math.sqrt(Math.pow(v.x() - pivot.x(), 2)
                                    + Math.pow(v.y() - pivot.y(), 2)
                                    + Math.pow(v.z() - pivot.z(), 2)));
        return radius;
    }

    private static Prepared prepareGeometry(
            SurfaceImport.Request request, SurfaceVertexPlan.Plan geometry, SurfaceWarpImagePlan.Plan images) {
        double radius = geometry.meshes().stream()
                .mapToDouble(m -> projectedRadius(m, request.camera()))
                .max()
                .orElse(0);
        return prepareGeometry(
                request, geometry, images, 2 * chordError(radius, INTERPOLATION.insertPointCount()) + .01);
    }

    static Prepared prepareGeometry(
            SurfaceImport.Request request,
            SurfaceVertexPlan.Plan geometry,
            SurfaceWarpImagePlan.Plan images,
            double maximumInterpolationErrorPixels) {
        if (!Double.isFinite(maximumInterpolationErrorPixels)
                || maximumInterpolationErrorPixels < .01
                || maximumInterpolationErrorPixels > 5)
            throw new IllegalArgumentException("invalid combined interpolation allowance");
        var radii = new ArrayList<Double>();
        var camera = request.camera();
        for (var mesh : geometry.meshes()) {
            double meshRadius = projectedRadius(mesh, camera);
            radii.add(meshRadius);
        }
        var sampling = radii.stream()
                .map(r -> sampling(r, maximumInterpolationErrorPixels))
                .toList();
        double error =
                sampling.stream().mapToDouble(Sampling::errorBoundPixels).max().orElse(.01);
        long cacheSlots = 0;
        for (int i = 0; i < geometry.meshes().size(); i++) {
            long slots = geometry.meshes().get(i).positions().size(),
                    grid = sampling.get(i).grid();
            cacheSlots = Math.addExact(cacheSlots, Math.multiplyExact(grid, slots));
            if (grid * slots > 32_000_000L || 80L * slots > 2_000_000L)
                throw new IllegalArgumentException("direct mesh interpolation/batch budget exceeded");
        }
        if (cacheSlots > MAX_CACHE_SLOTS
                || geometry.repeatedSlots() * KEYS.size() * KEYS.size()
                        > Math.min(SurfaceWarpLayerAuthoring.MAX_REPEATED_LAYER_SLOTS, request.maxProjectedVertices()))
            throw new IllegalArgumentException("direct source/cache serialization budget exceeded");
        var centers = new ArrayList<SurfaceProjection.Vertex>();
        for (var mesh : geometry.meshes()) {
            double x = 0, y = 0, z = 0;
            for (int slot : mesh.indices()) {
                var v = mesh.positions().get(slot);
                x += v.x();
                y += v.y();
                z += v.z();
            }
            int n = mesh.indices().size();
            centers.add(new SurfaceProjection.Vertex(x / n, y / n, z / n));
        }
        var orders = new ArrayList<List<Integer>>();
        for (float yaw : KEYS)
            for (float pitch : KEYS) {
                var depths = SurfaceProjection.project(centers, camera, new SurfaceProjection.Pose(yaw, pitch))
                        .depths();
                var sorted = new ArrayList<Integer>();
                for (int i = 0; i < centers.size(); i++) sorted.add(i);
                sorted.sort(Comparator.comparingDouble((Integer i) -> depths.get(i))
                        .reversed()
                        .thenComparingInt(Integer::intValue));
                var ranks = new ArrayList<>(Collections.nCopies(centers.size(), 0));
                for (int i = 0; i < sorted.size(); i++)
                    ranks.set(
                            sorted.get(i),
                            sorted.size() == 1 ? 500 : (int) Math.round(1000d * i / (sorted.size() - 1)));
                orders.add(List.copyOf(ranks));
            }
        return new Prepared(request, geometry, images, List.copyOf(orders), error, sampling);
    }

    static AuthoringTransactionResult<Created> apply(
            Prepared p, Supplier<CubismModel> current, AuthoringTransactionService transactions) {
        return transactions.execute(
                AuthoringTransactionOptions.of(
                        "Import source vertices " + p.request().name()),
                () -> assemble(current.get(), p));
    }

    static Created assemble(CubismModel model, Prepared p) {
        var request = p.request();
        var axes = List.of(request.yaw(), request.pitch(), request.roll().orElseThrow());
        if (model.parameters().all().stream().anyMatch(parameter -> axes.contains(parameter.id())))
            throw new IllegalArgumentException("import parameter ID already exists");
        var part = model.parts().create(request.name(), null, -1);
        var ids = new ArrayList<ArtMeshId>();
        var images = new ArrayList<ModelImageBinding>();
        var patchParts = new ArrayList<PartId>();
        var registeredImages = new HashMap<Integer, ModelImageBinding>();
        int neutral = KEYS.indexOf(0f) * KEYS.size() + KEYS.indexOf(0f);
        for (int i = 0; i < p.geometry().meshes().size(); i++) {
            // The Editor refreshes extended interpolation on every sibling when
            // one mesh is dragged. Keep each patch in an unbound child Part so
            // a local edit does not rebuild the whole imported model's lattice.
            var patchPart = model.parts().create(request.name() + " 曲面 " + i, part, -1);
            patchParts.add(patchPart.id());
            var input = p.images().inputs.get(i);
            var mesh = model.drawables()
                    .createWithSourceGeometry(
                            request.name() + " 曲面 " + i, patchPart, -1, input.imageGeometry(), input.defaultLocal());
            mesh.setCulling(true);
            mesh.setDrawOrder(p.orders().get(neutral).get(i));
            ids.add(mesh.id());
            ModelImageBinding binding;
            int imageIndex = p.images().meshImages.get(i);
            var registered = registeredImages.get(imageIndex);
            if (registered == null) {
                binding = model.textures().registerImageAndBind(p.images().newGrant(imageIndex), mesh.id());
                registeredImages.put(imageIndex, binding);
            } else binding = model.textures().bindModelImage(registered.modelImageId(), mesh.id());
            images.add(binding);
            mesh.setDrawOrder(p.orders().get(neutral).get(i));
            mesh.setCulling(true);
            if (!mesh.authoringSnapshot().sourceGeometry().equals(input.imageGeometry()))
                throw new IllegalStateException("source image registration changed source UV/topology");
        }
        var roll = SurfaceRotationAuthoring.create(model, part, request);
        float ox = (float) (request.camera().centerX()
                + request.camera().scale() * request.camera().pivot().x());
        float oy = (float) (request.camera().centerY()
                - request.camera().scale() * request.camera().pivot().y());
        model.parameters()
                .createMany(List.of(
                        new ParameterDefinition(
                                request.yaw(), request.rotationName("Y"), -180, 0, 180, ParameterType.NORMAL, false),
                        new ParameterDefinition(
                                request.pitch(),
                                request.rotationName("X"),
                                -180,
                                0,
                                180,
                                ParameterType.NORMAL,
                                false)));
        var banks = new ArrayList<ArtMeshAuthoringSnapshot>();
        for (int i = 0; i < ids.size(); i++) {
            var mesh = model.drawables().find(ids.get(i));
            var source = p.geometry().meshes().get(i);
            var image = p.images().inputs.get(i);
            var before = mesh.authoringSnapshot();
            mesh.setTargetDeformer(Optional.of(roll.id()));
            var parented = mesh.authoringSnapshot();
            if (!mesh.parentPartId().equals(Optional.of(patchParts.get(i))))
                throw new IllegalStateException("Roll parenting changed local patch refresh scope");
            if (!before.sourceGeometry().equals(parented.sourceGeometry())
                    || parented.forms().size() != 1)
                throw new IllegalStateException("Roll parenting changed source UV/topology");
            var defaultLocal = local(image.defaultLocal(), ox, oy);
            checkPositions(defaultLocal, parented.forms().get(0).positions(), .001);
            for (var axis : List.of(request.yaw(), request.pitch())) {
                var points = new ArrayList<ParameterBindingPoint>();
                for (int k = 0; k < KEYS.size(); k++)
                    points.add(new ParameterBindingPoint(
                            new ParameterBindingPointId("VERTEX_" + i + "_" + axis.value() + "_" + k), KEYS.get(k)));
                model.parameterBindings(axis).bind(ParameterBindingTarget.artMesh(mesh.id()), points);
            }
            var bound = mesh.authoringSnapshot();
            if (bound.forms().size() != 81
                    || bound.cells().size() != 81
                    || bound.axes().size() != 2)
                throw new IllegalStateException("complete direct source form grid required");
            var yaw = bound.axes().stream()
                    .filter(a -> a.parameterId().equals(request.yaw()))
                    .findFirst()
                    .orElseThrow();
            var pitch = bound.axes().stream()
                    .filter(a -> a.parameterId().equals(request.pitch()))
                    .findFirst()
                    .orElseThrow();
            var edits = new ArrayList<ArtMeshKeyformPositions>();
            var orderEdits = new ArrayList<ArtMeshKeyformDrawOrder>();
            var desired = new HashMap<String, List<Point2>>();
            var desiredOrder = new HashMap<String, Integer>();
            for (var cell : bound.cells()) {
                float y = cell.coordinates().stream()
                        .filter(c -> c.parameterGuid().equals(yaw.parameterGuid()))
                        .findFirst()
                        .orElseThrow()
                        .value();
                float x = cell.coordinates().stream()
                        .filter(c -> c.parameterGuid().equals(pitch.parameterGuid()))
                        .findFirst()
                        .orElseThrow()
                        .value();
                var positions = local(
                        SurfaceProjection.project(
                                        source.positions(), request.camera(), new SurfaceProjection.Pose(y, x))
                                .positions(),
                        ox,
                        oy);
                int order = p.orders()
                        .get(KEYS.indexOf(y) * KEYS.size() + KEYS.indexOf(x))
                        .get(i);
                if (y != 0 || x != 0) {
                    edits.add(new ArtMeshKeyformPositions(cell.coordinates(), cell.formGuid(), positions));
                    orderEdits.add(new ArtMeshKeyformDrawOrder(cell.coordinates(), cell.formGuid(), order));
                } else
                    positions = bound.forms().stream()
                            .filter(f -> f.formGuid().equals(cell.formGuid()))
                            .findFirst()
                            .orElseThrow()
                            .positions();
                if (desired.put(cell.formGuid(), positions) != null)
                    throw new IllegalStateException("shared direct form");
                desiredOrder.put(cell.formGuid(), order);
            }
            mesh.setOrdinaryKeyformPositions(bound, edits);
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
                                    f.formGuid(), desired.get(f.formGuid()), desiredOrder.get(f.formGuid())))
                            .toList(),
                    bound.morphKeys());
            if (!expected.equals(mesh.authoringSnapshot()))
                throw new IllegalStateException("direct source form readback changed");
            var texture = model.textures().textureInput(mesh.id()).orElseThrow();
            if (!texture.modelImageId().equals(Optional.of(images.get(i).modelImageId())))
                throw new IllegalStateException("direct source texture changed");
            banks.add(expected);
        }
        SurfacePlacementAuthoring.wrap(model, part, request, roll);
        for (int i = 0; i < ids.size(); i++)
            if (!banks.get(i).equals(model.drawables().find(ids.get(i)).authoringSnapshot()))
                throw new IllegalStateException("placement changed direct source banks");
        // Finish source-grid edits before enabling caches: subsequent parameter
        // binding edits can make the Editor rebuild already enabled extensions.
        for (int i = 0; i < ids.size(); i++) {
            var mesh = model.drawables().find(ids.get(i));
            var density = p.sampling().get(i);
            model.parameterBindings(request.yaw())
                    .setInterpolation(ParameterBindingTarget.artMesh(mesh.id()), density.yaw());
            model.parameterBindings(request.pitch())
                    .setInterpolation(ParameterBindingTarget.artMesh(mesh.id()), density.pitch());
            if (!banks.get(i).equals(mesh.authoringSnapshot()))
                throw new IllegalStateException("interpolation changed source banks");
            if (!mesh.parentPartId().equals(Optional.of(patchParts.get(i))))
                throw new IllegalStateException("interpolation changed local patch refresh scope");
        }
        return new Created(part.id(), roll.id(), ids, images, banks);
    }

    private static List<Point2> local(List<Point2> world, float x, float y) {
        return world.stream().map(v -> new Point2(v.x() - x, v.y() - y)).toList();
    }

    private static void checkPositions(List<Point2> a, List<Point2> b, double tolerance) {
        if (a.size() != b.size()) throw new IllegalStateException("direct vertex count changed");
        for (int i = 0; i < a.size(); i++)
            if (Math.hypot(a.get(i).x() - b.get(i).x(), a.get(i).y() - b.get(i).y()) > tolerance)
                throw new IllegalStateException("direct parent pixel drift exceeds budget");
    }
}

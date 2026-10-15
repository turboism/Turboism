package dev.turboism.validation.modelimport3d.host;

import dev.turboism.plugin.modelimport3d.viewer.ViewerScene;
import dev.turboism.sdk.cubism.model.ParameterInterpolation;
import dev.turboism.sdk.cubism.model.Point2;
import java.util.*;

/**
 * Bounded geometry planner for native 3D humanoid character authoring in Cubism.
 *
 * <p>Preserves exact triangle topology (no silent face drops) while partitioning
 * large meshes along spatial dimensions to provide a bounded painter's
 * algorithm approximation for dynamic internal occlusion across full-sphere XYZ rotation.
 * This is a visibility approximation, not an exact occlusion proof. Keyforms use a unified
 * 5x5 grid (25 forms) bounded strictly within the 6,000,000 slot serialization budget
 * and 64,000,000 slot cache budget.
 */
public final class NativeCharacterPlan {
    private NativeCharacterPlan() {}

    public static final List<Float> KEYS = List.of(-180f, -90f, 0f, 90f, 180f);
    public static final long MAX_TOTAL_KEYFORM_SLOTS = 6_000_000L;
    public static final long MAX_CACHE_SLOTS = 64_000_000L;
    public static final long MAX_MESH_CACHE_SLOTS = 32_000_000L;
    public static final long MAX_MESH_BATCH_SLOTS = 2_000_000L;
    public static final int TARGET_MAX_TRIANGLES_PER_PARTITION = 1200;
    public static final double DEFAULT_FRAMING_RADIUS_PIXELS = 400.0;

    public static final ParameterInterpolation DEFAULT_INTERPOLATION =
            new ParameterInterpolation(ParameterInterpolation.Method.ELLIPSE, 3, 1f);

    /** One source-preserving spatial partition with explicit source visibility. */
    public record Partition(
            int partitionIndex,
            int sourceMeshIndex,
            int materialIndex,
            boolean doubleSided,
            boolean visible,
            String name,
            List<SurfaceProjection.Vertex> positions,
            List<Point2> uvs,
            List<Integer> indices,
            List<Integer> sourceSlotMapping,
            SurfaceProjection.Vertex center,
            SurfaceProjection.Vertex normal,
            double radius,
            int triangleCount,
            List<Integer> sourceTriangles) {
        public Partition(
                int partitionIndex,
                int sourceMeshIndex,
                int materialIndex,
                boolean doubleSided,
                boolean visible,
                String name,
                List<SurfaceProjection.Vertex> positions,
                List<Point2> uvs,
                List<Integer> indices,
                List<Integer> sourceSlotMapping,
                SurfaceProjection.Vertex center,
                SurfaceProjection.Vertex normal,
                double radius,
                int triangleCount) {
            this(
                    partitionIndex,
                    sourceMeshIndex,
                    materialIndex,
                    doubleSided,
                    visible,
                    name,
                    positions,
                    uvs,
                    indices,
                    sourceSlotMapping,
                    center,
                    normal,
                    radius,
                    triangleCount,
                    List.of());
        }

        public Partition {
            sourceTriangles = sourceTriangles.stream().sorted().toList();
            positions = List.copyOf(positions);
            uvs = List.copyOf(uvs);
            indices = List.copyOf(indices);
            sourceSlotMapping = List.copyOf(sourceSlotMapping);
        }
    }

    /** Axis interpolation densities, cache grid size and theoretical chord error bound. */
    public record Sampling(
            ParameterInterpolation yaw, ParameterInterpolation pitch, long grid, double errorBoundPixels) {}

    /** Bounded native authoring plan; painter ordering remains an approximation. */
    public record Plan(
            SurfaceImport.Request request,
            SurfaceProjection.Camera camera,
            List<Float> keys,
            List<Partition> partitions,
            List<List<Integer>> orders,
            List<Sampling> sampling,
            double visibilityLimit,
            long totalSourceSlots,
            long totalPartitionSlots,
            long totalTriangles,
            long totalKeyformSlots,
            long totalCacheSlots,
            double maxInterpolationErrorPixels) {
        public Plan {
            keys = List.copyOf(keys);
            partitions = List.copyOf(partitions);
            orders = List.copyOf(orders);
            sampling = List.copyOf(sampling);
        }
    }

    /**
     * Computes the theoretical chord error for 90-degree keyframe intervals.
     * With {@code inserts} intermediate points, the sub-interval is 90 / (inserts + 1) degrees,
     * and the sagitta half-angle is 45 / (inserts + 1) degrees.
     */
    public static double chordError(double radius, int inserts) {
        if (radius <= 0 || inserts < 0) return 0.0;
        return radius * (1.0 - Math.cos(Math.toRadians(45.0 / (inserts + 1))));
    }

    /**
     * Fits an orthographic camera to the Scene's visible world bounds.
     * Bounding box center and radius are computed exclusively from visible meshes (mesh.visible() == true)
     * so that invisible helpers/colliders outside the character boundary do not distort framing.
     */
    public static SurfaceProjection.Camera fitCamera(
            ViewerScene scene, double centerX, double centerY, double framingRadiusPixels) {
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        long visibleVertices = 0;

        for (var mesh : scene.meshes()) {
            if (mesh.defaultOpacity() <= 0f) continue;
            float[] pos = mesh.positions();
            int vCount = pos.length / 3;
            for (int i = 0; i < vCount; i++) {
                double x = pos[i * 3];
                double y = pos[i * 3 + 1];
                double z = pos[i * 3 + 2];
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
                minZ = Math.min(minZ, z);
                maxZ = Math.max(maxZ, z);
                visibleVertices++;
            }
        }

        // Fallback to all meshes if no visible meshes are present
        if (visibleVertices == 0) {
            for (var mesh : scene.meshes()) {
                float[] pos = mesh.positions();
                int vCount = pos.length / 3;
                for (int i = 0; i < vCount; i++) {
                    double x = pos[i * 3];
                    double y = pos[i * 3 + 1];
                    double z = pos[i * 3 + 2];
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                    minZ = Math.min(minZ, z);
                    maxZ = Math.max(maxZ, z);
                }
            }
        }

        var pivot = new SurfaceProjection.Vertex((minX + maxX) / 2.0, (minY + maxY) / 2.0, (minZ + maxZ) / 2.0);

        double radius = 0.0;
        for (var mesh : scene.meshes()) {
            if (visibleVertices > 0 && mesh.defaultOpacity() <= 0f) continue;
            float[] pos = mesh.positions();
            int vCount = pos.length / 3;
            for (int i = 0; i < vCount; i++) {
                double dx = pos[i * 3] - pivot.x();
                double dy = pos[i * 3 + 1] - pivot.y();
                double dz = pos[i * 3 + 2] - pivot.z();
                radius = Math.max(radius, Math.hypot(Math.hypot(dx, dy), dz));
            }
        }
        if (!Double.isFinite(radius) || radius <= 0) {
            throw new IllegalArgumentException("Scene framing radius is degenerate");
        }
        double scale = framingRadiusPixels / radius;
        return new SurfaceProjection.Camera(centerX - scale * pivot.x(), centerY + scale * pivot.y(), scale, pivot);
    }

    /**
     * Determines optimal ellipse interpolation density under a maximum error bound.
     * Throws if the requested maximumError cannot be satisfied under the maximum allowed inserts.
     */
    public static Sampling sampling(double radius, double maximumError) {
        if (!Double.isFinite(radius) || radius < 0 || !Double.isFinite(maximumError) || maximumError < 0.01) {
            throw new IllegalArgumentException("invalid interpolation sampling budget");
        }
        Sampling best = null;
        int maxInserts = DEFAULT_INTERPOLATION.insertPointCount(); // 3 inserts -> 17x17 = 289 cells per partition
        for (int yaw = 0; yaw <= maxInserts; yaw++) {
            for (int pitch = 0; pitch <= maxInserts; pitch++) {
                double error = chordError(radius, yaw) + chordError(radius, pitch) + 0.01;
                if (error > maximumError) continue;
                long grid = (KEYS.size() + (KEYS.size() - 1L) * yaw) * (KEYS.size() + (KEYS.size() - 1L) * pitch);
                if (best == null || grid < best.grid() || (grid == best.grid() && error < best.errorBoundPixels())) {
                    best = new Sampling(
                            new ParameterInterpolation(ParameterInterpolation.Method.ELLIPSE, yaw, 1f),
                            new ParameterInterpolation(ParameterInterpolation.Method.ELLIPSE, pitch, 1f),
                            grid,
                            error);
                }
            }
        }
        if (best == null) {
            double minAchievable = chordError(radius, maxInserts) + chordError(radius, maxInserts) + 0.01;
            throw new IllegalArgumentException("Interpolation error bound cannot satisfy " + maximumError
                    + " px under budget (min achievable: " + minAchievable + " px for radius " + radius + ")");
        }
        return best;
    }

    private record Face(int triangleIndex, double cx, double cy, double cz, double nx, double ny, double nz) {}

    /**
     * Compiles a complete bounded geometry plan from the decoded scene.
     */
    public static Plan prepare(ViewerScene scene, SurfaceImport.Request request) {
        return prepare(scene, request, java.util.Map.of());
    }

    static Plan prepare(
            ViewerScene scene,
            SurfaceImport.Request request,
            java.util.Map<Integer, java.util.Set<List<Integer>>> refinements) {
        Objects.requireNonNull(scene, "scene");
        Objects.requireNonNull(request, "request");

        // Fit camera to the scene's bounding sphere and align with framedRequest
        var camera =
                fitCamera(scene, request.camera().centerX(), request.camera().centerY(), DEFAULT_FRAMING_RADIUS_PIXELS);
        var framedRequest = new SurfaceImport.Request(
                request.name(),
                request.yaw(),
                request.pitch(),
                camera,
                request.yawLimit(),
                request.pitchLimit(),
                request.maxProjectedVertices(),
                request.roll());

        var rawMeshes = scene.meshes();
        long sourceSlots = 0;
        long sourceTriangles = 0;
        for (var m : rawMeshes) {
            sourceSlots += m.positions().length / 3;
            sourceTriangles += m.indices().length / 3;
        }

        var partitions = new ArrayList<Partition>();
        int partitionCounter = 0;

        for (int mIdx = 0; mIdx < rawMeshes.size(); mIdx++) {
            var mesh = rawMeshes.get(mIdx);
            var subClusters = clusterMesh(
                    mesh,
                    mesh.controlSamples().isEmpty() ? TARGET_MAX_TRIANGLES_PER_PARTITION : 300,
                    refinements.getOrDefault(mIdx, java.util.Set.of()));

            boolean doubleSided = false;
            if (mesh.materialIndex() >= 0
                    && mesh.materialIndex() < scene.materials().size()) {
                doubleSided = scene.materials().get(mesh.materialIndex()).doubleSided();
            }

            float[] rawPos = mesh.positions();
            float[] rawUv = mesh.uvs();
            int[] rawInd = mesh.indices();

            for (var cluster : subClusters) {
                var uniqueSlots = new LinkedHashMap<Integer, Integer>();
                var partPositions = new ArrayList<SurfaceProjection.Vertex>();
                var partUvs = new ArrayList<Point2>();
                var partIndices = new ArrayList<Integer>(cluster.size() * 3);
                var sourceSlotMapping = new ArrayList<Integer>();

                double sumX = 0, sumY = 0, sumZ = 0;
                double sumNx = 0, sumNy = 0, sumNz = 0;

                for (Face f : cluster) {
                    sumNx += f.nx;
                    sumNy += f.ny;
                    sumNz += f.nz;
                    for (int c = 0; c < 3; c++) {
                        int origSlot = rawInd[f.triangleIndex * 3 + c];
                        Integer localSlot = uniqueSlots.get(origSlot);
                        if (localSlot == null) {
                            localSlot = uniqueSlots.size();
                            uniqueSlots.put(origSlot, localSlot);
                            double px = rawPos[origSlot * 3];
                            double py = rawPos[origSlot * 3 + 1];
                            double pz = rawPos[origSlot * 3 + 2];
                            partPositions.add(new SurfaceProjection.Vertex(px, py, pz));
                            float u = rawUv != null && origSlot * 2 < rawUv.length ? rawUv[origSlot * 2] : 0f;
                            float v = rawUv != null && origSlot * 2 + 1 < rawUv.length ? rawUv[origSlot * 2 + 1] : 0f;
                            partUvs.add(new Point2(u, v));
                            sourceSlotMapping.add(origSlot);
                            sumX += px;
                            sumY += py;
                            sumZ += pz;
                        }
                        partIndices.add(localSlot);
                    }
                }

                // Neutral painter order changes face traversal only; source slots and winding remain intact.
                var neutralFaces = new ArrayList<>(cluster);
                neutralFaces.sort(Comparator.comparingDouble(Face::cz).thenComparingInt(Face::triangleIndex));
                partIndices.clear();
                for (Face face : neutralFaces)
                    for (int c = 0; c < 3; c++) partIndices.add(uniqueSlots.get(rawInd[face.triangleIndex * 3 + c]));

                int vCount = partPositions.size();
                SurfaceProjection.Vertex center = new SurfaceProjection.Vertex(
                        vCount > 0 ? sumX / vCount : 0, vCount > 0 ? sumY / vCount : 0, vCount > 0 ? sumZ / vCount : 0);

                double nLen = Math.sqrt(sumNx * sumNx + sumNy * sumNy + sumNz * sumNz);
                SurfaceProjection.Vertex normal = nLen > 1e-9
                        ? new SurfaceProjection.Vertex(sumNx / nLen, sumNy / nLen, sumNz / nLen)
                        : new SurfaceProjection.Vertex(0, 0, 1);

                double maxR = 0;
                for (var p : partPositions) {
                    double dist = Math.hypot(Math.hypot(p.x() - center.x(), p.y() - center.y()), p.z() - center.z());
                    if (dist > maxR) maxR = dist;
                }

                partitions.add(new Partition(
                        partitionCounter++,
                        mIdx,
                        mesh.materialIndex(),
                        doubleSided,
                        mesh.visible(),
                        mesh.name(),
                        partPositions,
                        partUvs,
                        partIndices,
                        sourceSlotMapping,
                        center,
                        normal,
                        maxR,
                        cluster.size(),
                        neutralFaces.stream().map(Face::triangleIndex).toList()));
            }
        }

        // Verify exact triangle retention
        long partitionedTriangles =
                partitions.stream().mapToLong(Partition::triangleCount).sum();
        if (partitionedTriangles != sourceTriangles) {
            throw new IllegalStateException(
                    "Partitioned triangles mismatch: " + partitionedTriangles + " vs source " + sourceTriangles);
        }

        long totalPartitionSlots =
                partitions.stream().mapToLong(p -> p.positions().size()).sum();
        long totalKeyformSlots = partitions.stream()
                .mapToLong(p -> (long) p.positions().size() * (p.visible() ? KEYS.size() * KEYS.size() : 1))
                .sum();

        if (totalKeyformSlots > MAX_TOTAL_KEYFORM_SLOTS) {
            throw new IllegalArgumentException("Native character geometry exceeds 6M save budget: " + totalKeyformSlots
                    + " > " + MAX_TOTAL_KEYFORM_SLOTS);
        }

        var orders = NativeCharacterVisibility.compile(partitions, camera, scene);

        // Achievable chord error bound for 5x5 keys under framing radius (e.g. 400px)
        double frameRadiusBound =
                2 * chordError(DEFAULT_FRAMING_RADIUS_PIXELS, DEFAULT_INTERPOLATION.insertPointCount()) + 0.01;

        // Per-partition sampling and error measurement with strict budget guards
        var samplingList = new ArrayList<Sampling>(partitions.size());
        double maxError = 0.0;
        var pivot = camera.pivot();
        long totalCacheSlots = 0;

        for (var p : partitions) {
            Sampling s;
            if (p.visible()) {
                double maxProjR = 0;
                for (var v : p.positions()) {
                    double r = camera.scale()
                            * Math.sqrt(Math.pow(v.x() - pivot.x(), 2)
                                    + Math.pow(v.y() - pivot.y(), 2)
                                    + Math.pow(v.z() - pivot.z(), 2));
                    if (r > maxProjR) maxProjR = r;
                }
                s = sampling(maxProjR, frameRadiusBound);
            } else {
                s = new Sampling(DEFAULT_INTERPOLATION, DEFAULT_INTERPOLATION, 1L, 0.0);
            }

            long partSlots = p.positions().size();
            long meshCacheSlots = (long) partSlots * s.grid();
            if (meshCacheSlots > MAX_MESH_CACHE_SLOTS || 24L * partSlots > MAX_MESH_BATCH_SLOTS) {
                throw new IllegalArgumentException(
                        "Direct mesh interpolation/batch budget exceeded for partition " + p.partitionIndex());
            }

            samplingList.add(s);
            totalCacheSlots += meshCacheSlots;
            if (s.errorBoundPixels() > maxError) {
                maxError = s.errorBoundPixels();
            }
        }

        if (totalCacheSlots > MAX_CACHE_SLOTS) {
            throw new IllegalArgumentException(
                    "Native character cache slots exceed 64M budget: " + totalCacheSlots + " > " + MAX_CACHE_SLOTS);
        }

        var bounds = scene.bounds();
        double visibilityLimit = Math.max(
                bounds.maxX() - bounds.minX(), Math.max(bounds.maxY() - bounds.minY(), bounds.maxZ() - bounds.minZ()));

        return new Plan(
                framedRequest,
                camera,
                KEYS,
                partitions,
                orders,
                samplingList,
                visibilityLimit,
                sourceSlots,
                totalPartitionSlots,
                sourceTriangles,
                totalKeyformSlots,
                totalCacheSlots,
                maxError);
    }

    private static List<List<Face>> clusterMesh(
            ViewerScene.Mesh mesh, int maxTriangles, java.util.Set<List<Integer>> refinements) {
        int tCount = mesh.indices().length / 3;
        float[] pos = mesh.positions();
        int[] ind = mesh.indices();

        List<Face> faces = new ArrayList<>(tCount);
        for (int t = 0; t < tCount; t++) {
            int i0 = ind[t * 3];
            int i1 = ind[t * 3 + 1];
            int i2 = ind[t * 3 + 2];

            double x0 = pos[i0 * 3], y0 = pos[i0 * 3 + 1], z0 = pos[i0 * 3 + 2];
            double x1 = pos[i1 * 3], y1 = pos[i1 * 3 + 1], z1 = pos[i1 * 3 + 2];
            double x2 = pos[i2 * 3], y2 = pos[i2 * 3 + 1], z2 = pos[i2 * 3 + 2];

            double cx = (x0 + x1 + x2) / 3.0;
            double cy = (y0 + y1 + y2) / 3.0;
            double cz = (z0 + z1 + z2) / 3.0;

            double ax = x1 - x0, ay = y1 - y0, az = z1 - z0;
            double bx = x2 - x0, by = y2 - y0, bz = z2 - z0;
            double nx = ay * bz - az * by;
            double ny = az * bx - ax * bz;
            double nz = ax * by - ay * bx;
            double nLen = Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (nLen > 1e-9) {
                nx /= nLen;
                ny /= nLen;
                nz /= nLen;
            } else {
                nx = ny = nz = 0;
            }
            faces.add(new Face(t, cx, cy, cz, nx, ny, nz));
        }

        List<List<Face>> result = new ArrayList<>();
        Queue<List<Face>> queue = new ArrayDeque<>();
        queue.add(faces);

        while (!queue.isEmpty()) {
            List<Face> current = queue.poll();
            if (current.size() <= maxTriangles
                    && !refinements.contains(
                            current.stream().map(Face::triangleIndex).sorted().toList())) {
                result.add(current);
                continue;
            }

            double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
            double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
            double minZ = Double.POSITIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
            double minNz = Double.POSITIVE_INFINITY, maxNz = Double.NEGATIVE_INFINITY;

            for (Face f : current) {
                minX = Math.min(minX, f.cx);
                maxX = Math.max(maxX, f.cx);
                minY = Math.min(minY, f.cy);
                maxY = Math.max(maxY, f.cy);
                minZ = Math.min(minZ, f.cz);
                maxZ = Math.max(maxZ, f.cz);
                minNz = Math.min(minNz, f.nz);
                maxNz = Math.max(maxNz, f.nz);
            }

            double dx = maxX - minX;
            double dy = maxY - minY;
            double dz = maxZ - minZ;
            double maxSpatial = Math.max(dx, Math.max(dy, dz));
            double dnz = (maxNz - minNz) * 0.4 * maxSpatial;

            int axis;
            if (dx >= dy && dx >= dz && dx >= dnz) axis = 0;
            else if (dy >= dz && dy >= dnz) axis = 1;
            else if (dz >= dnz) axis = 2;
            else axis = 3;

            current.sort((a, b) -> {
                double va = axis == 0 ? a.cx : axis == 1 ? a.cy : axis == 2 ? a.cz : a.nz;
                double vb = axis == 0 ? b.cx : axis == 1 ? b.cy : axis == 2 ? b.cz : b.nz;
                return Double.compare(va, vb);
            });

            int mid = current.size() / 2;
            queue.add(new ArrayList<>(current.subList(0, mid)));
            queue.add(new ArrayList<>(current.subList(mid, current.size())));
        }

        return result;
    }
}

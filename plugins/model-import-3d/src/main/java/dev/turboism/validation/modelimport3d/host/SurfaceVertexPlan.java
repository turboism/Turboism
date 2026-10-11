package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.model.Point2;
import java.util.*;

/** Source-face partition for direct vertex binding. No fitting, simplification or visibility claim. */
final class SurfaceVertexPlan {
    private SurfaceVertexPlan() {}

    record Slot(int surface, int sourceSlot) {}

    record Triangle(int surface, int sourceTriangle) {}

    record Mesh(
            List<SurfaceProjection.Vertex> positions,
            List<Point2> uvs,
            List<Slot> slots,
            List<Integer> indices,
            List<Triangle> triangles) {
        Mesh {
            positions = List.copyOf(positions);
            uvs = List.copyOf(uvs);
            slots = List.copyOf(slots);
            indices = List.copyOf(indices);
            triangles = List.copyOf(triangles);
        }
    }

    record Plan(List<GlbSurfaceReader.Surface> sources, List<Mesh> meshes, long repeatedSlots) {
        Plan {
            sources = List.copyOf(sources);
            meshes = List.copyOf(meshes);
        }
    }

    private record Face(int surface, int triangle, double[] feature) {}

    private record Leaf(List<Face> faces, int dimension, double score) {}

    private record Visit(int triangle, int side, double distance) {}

    static Plan compile(List<GlbSurfaceReader.Surface> input, int maximumMeshes) {
        if (input.isEmpty() || input.size() > maximumMeshes || maximumMeshes < 1 || maximumMeshes > 1001)
            throw new IllegalArgumentException("source partition mesh budget");
        var sources = List.copyOf(input);
        var leaves = new ArrayList<Leaf>();
        var graphs = new ArrayList<int[][]>();
        long triangles = 0, slots = 0;
        for (int surface = 0; surface < sources.size(); surface++) {
            var source = sources.get(surface);
            slots += source.positions().size();
            triangles += source.indices().size() / 3;
            if (source.positions().isEmpty()
                    || source.positions().size() != source.textureUvs().size()
                    || source.indices().isEmpty()
                    || source.indices().size() % 3 != 0
                    || triangles > 200000
                    || slots > 100000)
                throw new IllegalArgumentException("complete bounded source faces/slots required");
            double[] low = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY},
                    high = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
            for (var p : source.positions()) {
                double[] v = {p.x(), p.y(), p.z()};
                for (int a = 0; a < 3; a++) {
                    low[a] = Math.min(low[a], v[a]);
                    high[a] = Math.max(high[a], v[a]);
                }
            }
            double extent = Math.max(high[0] - low[0], Math.max(high[1] - low[1], high[2] - low[2]));
            if (!(extent > 0)) throw new IllegalArgumentException("zero source extent");
            var faces = new ArrayList<Face>();
            var referenced = new BitSet(source.positions().size());
            for (int triangle = 0; triangle < source.indices().size() / 3; triangle++) {
                var v = new SurfaceProjection.Vertex[3];
                double[] f = new double[6];
                for (int c = 0; c < 3; c++) {
                    int slot = source.indices().get(3 * triangle + c);
                    if (slot < 0 || slot >= source.positions().size())
                        throw new IllegalArgumentException("source index outside slots");
                    referenced.set(slot);
                    v[c] = source.positions().get(slot);
                    f[0] += v[c].x() / 3;
                    f[1] += v[c].y() / 3;
                    f[2] += v[c].z() / 3;
                }
                for (int a = 0; a < 3; a++) f[a] = (f[a] - low[a]) / extent;
                double ax = v[1].x() - v[0].x(), ay = v[1].y() - v[0].y(), az = v[1].z() - v[0].z();
                double bx = v[2].x() - v[0].x(), by = v[2].y() - v[0].y(), bz = v[2].z() - v[0].z();
                double nx = ay * bz - az * by,
                        ny = az * bx - ax * bz,
                        nz = ax * by - ay * bx,
                        n = Math.sqrt(nx * nx + ny * ny + nz * nz);
                // Degenerate source triangles are retained with zero normal; they are never repaired.
                if (n > 0) {
                    f[3] = .5 * nx / n;
                    f[4] = .5 * ny / n;
                    f[5] = .5 * nz / n;
                }
                faces.add(new Face(surface, triangle, f));
            }
            if (referenced.cardinality() != source.positions().size())
                throw new IllegalArgumentException("unused source slots need explicit preservation support");
            int[][] adjacency = adjacency(source);
            graphs.add(adjacency);
            var visited = new BitSet(faces.size());
            for (int first = 0; first < faces.size(); first++)
                if (!visited.get(first)) {
                    var component = new ArrayList<Face>();
                    var queue = new ArrayDeque<Integer>();
                    queue.add(first);
                    visited.set(first);
                    while (!queue.isEmpty()) {
                        int at = queue.remove();
                        component.add(faces.get(at));
                        for (int peer : adjacency[at])
                            if (!visited.get(peer)) {
                                visited.set(peer);
                                queue.add(peer);
                            }
                    }
                    leaves.add(leaf(component));
                }
        }
        if (leaves.size() > maximumMeshes)
            throw new IllegalArgumentException("connected source components exceed mesh budget");
        while (leaves.size() < maximumMeshes) {
            int at = -1;
            double score = -1;
            for (int i = 0; i < leaves.size(); i++) {
                var l = leaves.get(i);
                if (l.faces().size() > 1 && l.score() > score) {
                    at = i;
                    score = l.score();
                }
            }
            if (at < 0) break;
            var old = leaves.remove(at);
            var halves = splitConnected(old, graphs.get(old.faces().get(0).surface()));
            leaves.add(leaf(halves.get(0)));
            leaves.add(leaf(halves.get(1)));
        }
        leaves.sort(Comparator.comparingInt((Leaf l) -> l.faces().get(0).surface())
                .thenComparingInt(
                        l -> l.faces().stream().mapToInt(Face::triangle).min().orElseThrow()));
        var meshes = new ArrayList<Mesh>();
        long total = 0;
        for (var leaf : leaves) {
            var ordered = new ArrayList<>(leaf.faces());
            ordered.sort(Comparator.comparingInt(Face::triangle));
            var ids = new LinkedHashMap<Slot, Integer>();
            var positions = new ArrayList<SurfaceProjection.Vertex>();
            var uv = new ArrayList<Point2>();
            var indices = new ArrayList<Integer>();
            var faces = new ArrayList<Triangle>();
            for (var f : ordered) {
                var source = sources.get(f.surface());
                faces.add(new Triangle(f.surface(), f.triangle()));
                for (int c = 0; c < 3; c++) {
                    var key = new Slot(f.surface(), source.indices().get(3 * f.triangle() + c));
                    Integer id = ids.get(key);
                    if (id == null) {
                        id = ids.size();
                        ids.put(key, id);
                        positions.add(source.positions().get(key.sourceSlot()));
                        uv.add(source.textureUvs().get(key.sourceSlot()));
                    }
                    indices.add(id);
                }
            }
            if (ids.size() > 65535 || (total += ids.size()) > 100000)
                throw new IllegalArgumentException("partition exceeds native/aggregate source slot budget");
            meshes.add(new Mesh(positions, uv, new ArrayList<>(ids.keySet()), indices, faces));
        }
        return new Plan(sources, meshes, total);
    }

    private static int[][] adjacency(GlbSurfaceReader.Surface source) {
        var points = new HashMap<SurfaceProjection.Vertex, Integer>();
        int[] vertex = new int[source.positions().size()];
        for (int i = 0; i < vertex.length; i++) {
            var original = source.positions().get(i);
            var p = new SurfaceProjection.Vertex(
                    original.x() == 0 ? 0 : original.x(),
                    original.y() == 0 ? 0 : original.y(),
                    original.z() == 0 ? 0 : original.z());
            Integer id = points.get(p);
            if (id == null) {
                id = points.size();
                points.put(p, id);
            }
            vertex[i] = id;
        }
        int count = source.indices().size() / 3;
        var edges = new HashMap<Long, List<Integer>>();
        var neighbors = new ArrayList<Set<Integer>>();
        for (int i = 0; i < count; i++) neighbors.add(new TreeSet<>());
        for (int triangle = 0; triangle < count; triangle++) {
            var seen = new HashSet<Long>();
            for (int c = 0; c < 3; c++) {
                int a = vertex[source.indices().get(triangle * 3 + c)],
                        b = vertex[source.indices().get(triangle * 3 + (c + 1) % 3)];
                if (a == b) continue;
                long key = ((long) Math.min(a, b) << 32) | Integer.toUnsignedLong(Math.max(a, b));
                if (!seen.add(key)) continue;
                edges.computeIfAbsent(key, ignored -> new ArrayList<>()).add(triangle);
            }
        }
        for (var faces : edges.values())
            if (faces.size() > 32)
                throw new IllegalArgumentException("source non-manifold edge exceeds adjacency budget");
        for (var faces : edges.values())
            for (int a : faces)
                for (int b : faces)
                    if (a != b) {
                        neighbors.get(a).add(b);
                        neighbors.get(b).add(a);
                    }
        return neighbors.stream()
                .map(set -> set.stream().mapToInt(Integer::intValue).toArray())
                .toArray(int[][]::new);
    }

    private static List<List<Face>> splitConnected(Leaf leaf, int[][] graph) {
        var faces = leaf.faces();
        int axis = leaf.dimension();
        var ordered = new ArrayList<>(faces);
        ordered.sort(Comparator.comparingDouble((Face f) -> f.feature()[axis]).thenComparingInt(Face::triangle));
        int first = ordered.get(0).triangle(),
                last = ordered.get(ordered.size() - 1).triangle();
        var byId = new Face[graph.length];
        for (var face : faces) byId[face.triangle()] = face;
        double[] distance = new double[graph.length];
        Arrays.fill(distance, Double.POSITIVE_INFINITY);
        int[] side = new int[graph.length];
        Arrays.fill(side, -1);
        var queue = new PriorityQueue<Visit>(Comparator.comparingDouble(Visit::distance)
                .thenComparingInt(Visit::side)
                .thenComparingInt(Visit::triangle));
        distance[first] = distance[last] = 0;
        side[first] = 0;
        side[last] = 1;
        queue.add(new Visit(first, 0, 0));
        queue.add(new Visit(last, 1, 0));
        while (!queue.isEmpty()) {
            var visit = queue.remove();
            int at = visit.triangle();
            if (visit.distance() != distance[at] || visit.side() != side[at]) continue;
            for (int peer : graph[at])
                if (byId[peer] != null) {
                    double length = 0;
                    for (int a = 0; a < 6; a++) {
                        double delta = byId[at].feature()[a] - byId[peer].feature()[a];
                        length += delta * delta;
                    }
                    double candidate = visit.distance() + Math.sqrt(length) + 1e-8;
                    if (candidate < distance[peer] || candidate == distance[peer] && visit.side() < side[peer]) {
                        distance[peer] = candidate;
                        side[peer] = visit.side();
                        queue.add(new Visit(peer, visit.side(), candidate));
                    }
                }
        }
        var left = new ArrayList<Face>();
        var right = new ArrayList<Face>();
        for (var face : faces) {
            if (side[face.triangle()] < 0) throw new IllegalStateException("disconnected partition leaf");
            (side[face.triangle()] == 0 ? left : right).add(face);
        }
        if (left.isEmpty() || right.isEmpty()) throw new IllegalStateException("empty connected partition");
        return List.of(left, right);
    }

    private static Leaf leaf(List<Face> faces) {
        double[] low = new double[6], high = new double[6];
        Arrays.fill(low, Double.POSITIVE_INFINITY);
        Arrays.fill(high, Double.NEGATIVE_INFINITY);
        for (var f : faces)
            for (int a = 0; a < 6; a++) {
                low[a] = Math.min(low[a], f.feature()[a]);
                high[a] = Math.max(high[a], f.feature()[a]);
            }
        int axis = 0;
        for (int a = 1; a < 6; a++) if (high[a] - low[a] > high[axis] - low[axis]) axis = a;
        return new Leaf(List.copyOf(faces), axis, (high[axis] - low[axis]) * Math.sqrt(faces.size()));
    }
}

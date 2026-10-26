package dev.turboism.plugin.mcp;

import dev.turboism.sdk.cubism.model.*;
import dev.turboism.sdk.json.Json;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Deterministic stored ArtMesh diagnostics. Never mixes source and stored coordinate spaces. */
final class McpGeometryQuality {
    static final long MAX_WORK = 2_000_000;
    static final List<String> IMPLEMENTED = List.of(
            "EMPTY_GEOMETRY",
            "UNUSED_VERTICES",
            "DUPLICATE_TRIANGLES",
            "NON_MANIFOLD_EDGES",
            "TOPOLOGY_WINDING",
            "TRIANGLE_AREA",
            "TRIANGLE_ASPECT",
            "REFERENCE_WINDING",
            "REFERENCE_EDGE_RATIO",
            "TRIANGLE_INTERIOR_OVERLAP");
    static final List<String> NOT_IMPLEMENTED = List.of(
            "TRIANGLE_BOUNDARY_CONTACTS",
            "CROSS_OBJECT_INTERSECTIONS",
            "SEAM_CRACKS",
            "UV_AND_ATLAS_QUALITY",
            "EVALUATED_GEOMETRY",
            "INTERPOLATED_POSE_CONTINUITY",
            "CANVAS_BOUNDS",
            "MASK_AND_OCCLUSION",
            "VISUAL_ART_QUALITY");

    record Thresholds(
            double minimumAreaRatio,
            double maximumEdgeStretch,
            double minimumEdgeCompression,
            double maximumTriangleAspectRatio) {
        Thresholds {
            if (!Double.isFinite(minimumAreaRatio)
                    || minimumAreaRatio < 0
                    || minimumAreaRatio > 1
                    || !Double.isFinite(maximumEdgeStretch)
                    || maximumEdgeStretch < 1
                    || maximumEdgeStretch > 1000
                    || !Double.isFinite(minimumEdgeCompression)
                    || minimumEdgeCompression <= 0
                    || minimumEdgeCompression > 1
                    || !Double.isFinite(maximumTriangleAspectRatio)
                    || maximumTriangleAspectRatio < 1
                    || maximumTriangleAspectRatio > 1000)
                throw new IllegalArgumentException("Invalid geometry thresholds");
        }
    }

    record Report(
            List<Map<String, Object>> findings,
            int findingCount,
            boolean findingsTruncated,
            long skippedComparisons,
            int vertices,
            int triangles,
            int uniqueEdges,
            int checkedGeometries,
            List<String> checkedForms,
            boolean intersectionCheckComplete,
            long intersectionPairsTested,
            long intersectionSweepVisits,
            long estimatedWork,
            String verdict) {}

    static Report analyze(
            ArtMeshAuthoringSnapshot snapshot,
            boolean includeSource,
            String referenceGuid,
            List<String> formGuids,
            Thresholds thresholds,
            int maxFindings) {
        Objects.requireNonNull(thresholds);
        if (maxFindings < 1
                || maxFindings > 200
                || formGuids.size() > 32
                || new HashSet<>(formGuids).size() != formGuids.size()
                || (!includeSource && formGuids.isEmpty()))
            throw new IllegalArgumentException("Invalid validation scope");
        var forms = new HashMap<String, ArtMeshAuthoringSnapshot.Form>();
        snapshot.forms().forEach(f -> forms.put(f.formGuid(), f));
        var ordinary = new HashSet<String>();
        snapshot.cells().forEach(c -> ordinary.add(c.formGuid()));
        var morph = new HashSet<String>();
        snapshot.morphKeys().forEach(k -> morph.add(k.formGuid()));
        if (formGuids.isEmpty() != (referenceGuid == null))
            throw new IllegalArgumentException("Reference required only for stored comparisons");
        var requested = new ArrayList<>(formGuids);
        if (referenceGuid != null) requested.add(referenceGuid);
        for (String guid : requested) {
            if (!forms.containsKey(guid)) throw new NoSuchElementException("Missing stored form");
            if (!ordinary.contains(guid) || morph.contains(guid))
                throw new UnsupportedOperationException(
                        "Morph/non-grid form coordinates are not ordinary absolute geometry");
        }
        var geometry = snapshot.sourceGeometry();
        int triangles = geometry.triangleIndices().size() / 3,
                vertices = geometry.positions().size();
        long baseWork = 2L * (vertices + 4L * triangles) * (formGuids.size() + 3);
        if (baseWork > MAX_WORK) throw new BudgetExceeded();
        var sink = new Findings(maxFindings);
        var edges = topology(geometry, sink);
        if (includeSource)
            checkTriangles("source", null, geometry.positions(), geometry.triangleIndices(), thresholds, sink);
        long skipped = 0;
        List<Point2> reference =
                referenceGuid == null ? List.of() : forms.get(referenceGuid).positions();
        double refEpsilon = referenceGuid == null ? 0 : diagonalSquared(reference) * thresholds.minimumAreaRatio();
        var refAreas = new double[triangles];
        if (referenceGuid != null) {
            for (int i = 0; i < triangles; i++) {
                var indices = triangle(geometry.triangleIndices(), i);
                refAreas[i] = cross(reference, indices);
                if (Math.abs(refAreas[i]) <= refEpsilon)
                    sink.add(
                            "REFERENCE_TRIANGLE_DEGENERATE",
                            "WARNING",
                            "reference",
                            referenceGuid,
                            i,
                            indices,
                            Math.abs(refAreas[i]),
                            refEpsilon);
            }
            for (var edge : edges.values())
                if (lengthSquared(reference, edge.a, edge.b) == 0)
                    sink.add(
                            "REFERENCE_EDGE_ZERO",
                            "WARNING",
                            "reference",
                            referenceGuid,
                            null,
                            List.of(edge.a, edge.b),
                            0D,
                            null);
        }
        for (String guid : formGuids) {
            var positions = forms.get(guid).positions();
            checkTriangles("stored", guid, positions, geometry.triangleIndices(), thresholds, sink);
            double epsilon = diagonalSquared(positions) * thresholds.minimumAreaRatio();
            for (int i = 0; i < triangles; i++) {
                var indices = triangle(geometry.triangleIndices(), i);
                double area = cross(positions, indices);
                if (Math.abs(refAreas[i]) <= refEpsilon || Math.abs(area) <= epsilon) {
                    skipped++;
                    continue;
                }
                if (Math.signum(area) != Math.signum(refAreas[i]))
                    sink.add(
                            "WINDING_REVERSED",
                            "WARNING",
                            "stored",
                            guid,
                            i,
                            indices,
                            Math.signum(area),
                            Math.signum(refAreas[i]));
            }
            for (var edge : edges.values()) {
                double before = lengthSquared(reference, edge.a, edge.b);
                if (before == 0) {
                    skipped++;
                    continue;
                }
                double ratio = Math.sqrt(lengthSquared(positions, edge.a, edge.b) / before);
                if (ratio > thresholds.maximumEdgeStretch())
                    sink.add(
                            "EDGE_STRETCHED",
                            "WARNING",
                            "stored",
                            guid,
                            null,
                            List.of(edge.a, edge.b),
                            ratio,
                            thresholds.maximumEdgeStretch());
                if (ratio < thresholds.minimumEdgeCompression())
                    sink.add(
                            "EDGE_COMPRESSED",
                            "WARNING",
                            "stored",
                            guid,
                            null,
                            List.of(edge.a, edge.b),
                            ratio,
                            thresholds.minimumEdgeCompression());
            }
        }
        var overlaps = new Overlaps(MAX_WORK - baseWork, geometry.triangleIndices(), sink);
        if (includeSource) overlaps.check("source", null, geometry.positions());
        for (String guid : formGuids)
            overlaps.check("stored", guid, forms.get(guid).positions());
        boolean truncated = sink.total != sink.rows.size();
        String verdict = truncated || skipped > 0 || !overlaps.complete
                ? "INCOMPLETE"
                : sink.total > 0 ? "FINDINGS" : "NO_FINDINGS_FOR_IMPLEMENTED_CHECKS";
        return new Report(
                List.copyOf(sink.rows),
                sink.total,
                truncated,
                skipped,
                vertices,
                triangles,
                edges.size(),
                formGuids.size() + (includeSource ? 1 : 0),
                List.copyOf(formGuids),
                overlaps.complete,
                overlaps.pairs,
                overlaps.visits,
                baseWork + overlaps.work,
                verdict);
    }

    private static Map<Long, Edge> topology(ArtMeshGeometry geometry, Findings sink) {
        var edges = new LinkedHashMap<Long, Edge>();
        var used = new boolean[geometry.positions().size()];
        var seen = new HashSet<Triangle>();
        if (used.length == 0 || geometry.triangleIndices().isEmpty())
            sink.add("EMPTY_GEOMETRY", "ERROR", "topology", null, null, List.of(), 0D, 1D);
        for (int i = 0; i < geometry.triangleIndices().size() / 3; i++) {
            var indices = triangle(geometry.triangleIndices(), i);
            var sorted = indices.stream().mapToInt(Integer::intValue).sorted().toArray();
            if (!seen.add(new Triangle(sorted[0], sorted[1], sorted[2])))
                sink.add("DUPLICATE_TRIANGLE", "WARNING", "topology", null, i, indices, 2D, 1D);
            indices.forEach(v -> used[v] = true);
            for (int j = 0; j < 3; j++) {
                int a = indices.get(j), b = indices.get((j + 1) % 3), min = Math.min(a, b), max = Math.max(a, b);
                var edge =
                        edges.computeIfAbsent(((long) min << 32) | (max & 0xffffffffL), ignored -> new Edge(min, max));
                edge.count++;
                edge.balance += a <= b ? 1 : -1;
            }
        }
        for (int i = 0; i < used.length; i++)
            if (!used[i]) sink.add("UNUSED_VERTEX", "WARNING", "topology", null, null, List.of(i), 0D, 1D);
        for (var edge : edges.values()) {
            if (edge.count > 2)
                sink.add(
                        "NON_MANIFOLD_EDGE",
                        "WARNING",
                        "topology",
                        null,
                        null,
                        List.of(edge.a, edge.b),
                        (double) edge.count,
                        2D);
            if (edge.count == 2 && edge.balance != 0)
                sink.add(
                        "INCONSISTENT_TOPOLOGY_WINDING",
                        "WARNING",
                        "topology",
                        null,
                        null,
                        List.of(edge.a, edge.b),
                        (double) edge.balance,
                        0D);
        }
        return edges;
    }

    private static void checkTriangles(
            String scope,
            String guid,
            List<Point2> positions,
            List<Integer> topology,
            Thresholds thresholds,
            Findings sink) {
        double epsilon = diagonalSquared(positions) * thresholds.minimumAreaRatio();
        for (int i = 0; i < topology.size() / 3; i++) {
            var indices = triangle(topology, i);
            double area = Math.abs(cross(positions, indices));
            if (area <= epsilon) {
                sink.add("DEGENERATE_TRIANGLE", "WARNING", scope, guid, i, indices, area, epsilon);
                continue;
            }
            double sum = lengthSquared(positions, indices.get(0), indices.get(1))
                    + lengthSquared(positions, indices.get(1), indices.get(2))
                    + lengthSquared(positions, indices.get(2), indices.get(0));
            double aspect = sum / (2 * Math.sqrt(3) * area);
            if (aspect > thresholds.maximumTriangleAspectRatio())
                sink.add(
                        "TRIANGLE_ASPECT_HIGH",
                        "WARNING",
                        scope,
                        guid,
                        i,
                        indices,
                        aspect,
                        thresholds.maximumTriangleAspectRatio());
        }
    }

    private static double diagonalSquared(List<Point2> points) {
        if (points.isEmpty()) return 0;
        double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY, minY = minX, maxY = maxX;
        for (var p : points) {
            minX = Math.min(minX, p.x());
            maxX = Math.max(maxX, p.x());
            minY = Math.min(minY, p.y());
            maxY = Math.max(maxY, p.y());
        }
        return (maxX - minX) * (maxX - minX) + (maxY - minY) * (maxY - minY);
    }

    private static double lengthSquared(List<Point2> points, int a, int b) {
        double x = (double) points.get(a).x() - points.get(b).x(),
                y = (double) points.get(a).y() - points.get(b).y();
        return x * x + y * y;
    }

    private static double cross(List<Point2> points, List<Integer> indices) {
        var a = points.get(indices.get(0));
        var b = points.get(indices.get(1));
        var c = points.get(indices.get(2));
        return ((double) b.x() - a.x()) * ((double) c.y() - a.y())
                - ((double) b.y() - a.y()) * ((double) c.x() - a.x());
    }

    private static List<Integer> triangle(List<Integer> indices, int index) {
        return indices.subList(index * 3, index * 3 + 3);
    }

    private record Triangle(int a, int b, int c) {}

    private static final class Edge {
        final int a, b;
        int count, balance;

        Edge(int a, int b) {
            this.a = a;
            this.b = b;
        }
    }

    private static final class Overlaps {
        final long budget;
        final List<Integer> topology;
        final Findings sink;
        boolean complete = true;
        long pairs, visits, work;

        Overlaps(long budget, List<Integer> topology, Findings sink) {
            this.budget = budget;
            this.topology = topology;
            this.sink = sink;
        }

        void check(String scope, String guid, List<Point2> positions) {
            if (!complete) return;
            var result = McpTriangleOverlap.check(
                    positions,
                    topology,
                    budget - work,
                    (first, second) -> sink.add(
                            "TRIANGLE_INTERIOR_OVERLAP",
                            "WARNING",
                            scope,
                            guid,
                            first,
                            second,
                            triangle(topology, first),
                            null,
                            null));
            complete = result.complete();
            pairs += result.pairsTested();
            visits += result.sweepVisits();
            work += result.workUsed();
        }
    }

    private static final class Findings {
        final int max;
        final List<Map<String, Object>> rows = new ArrayList<>();
        int total, bytes;

        Findings(int max) {
            this.max = max;
        }

        void add(
                String code,
                String severity,
                String scope,
                String guid,
                Integer triangle,
                List<Integer> indices,
                Double observed,
                Double threshold) {
            add(code, severity, scope, guid, triangle, null, indices, observed, threshold);
        }

        void add(
                String code,
                String severity,
                String scope,
                String guid,
                Integer triangle,
                Integer related,
                List<Integer> indices,
                Double observed,
                Double threshold) {
            total++;
            if (rows.size() >= max) return;
            var row = new LinkedHashMap<String, Object>();
            row.put("code", code);
            row.put("severity", severity);
            row.put("scope", scope);
            row.put("formGuid", guid);
            row.put("triangleIndex", triangle);
            row.put("relatedTriangleIndex", related);
            row.put("indices", List.copyOf(indices));
            row.put("observed", observed);
            row.put("threshold", threshold);
            int size = Json.stringify(row).getBytes(StandardCharsets.UTF_8).length;
            if (bytes + size > 160 * 1024) return;
            rows.add(Collections.unmodifiableMap(row));
            bytes += size;
        }
    }

    static final class BudgetExceeded extends IllegalArgumentException {}
}

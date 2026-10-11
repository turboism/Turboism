package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.ArtMeshSourceTriangulation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Bounded native-engine planning. Splits only contiguous source faces, preserving
 * their painter sequence and provenance. A preview is not an editing acceptance. */
final class SurfaceEditableTopologyPlan {
    private SurfaceEditableTopologyPlan() {}

    record Limits(int maximumParts, int maximumQueries, int maximumTotalSlots) {
        Limits {
            if (maximumParts < 1 || maximumQueries < 1 || maximumTotalSlots < 3)
                throw new IllegalArgumentException("positive topology planning budgets required");
        }
    }

    record Part(int firstFace, int endFace, List<Integer> sourceSlots, ArtMeshGeometry geometry) {
        Part {
            sourceSlots = List.copyOf(sourceSlots);
            Objects.requireNonNull(geometry);
        }
    }

    record Plan(List<Part> parts, int nativeQueries, int totalSlots) {
        Plan {
            parts = List.copyOf(parts);
        }
    }

    static Plan compile(
            ArtMeshGeometry source,
            Limits limits,
            Function<ArtMeshGeometry, ArtMeshSourceTriangulation> nativePreview) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(limits);
        Objects.requireNonNull(nativePreview);
        if (source.triangleIndices().isEmpty()) throw new IllegalArgumentException("source faces required");
        var planner = new Planner(source, limits, nativePreview);
        planner.split(0, source.triangleIndices().size() / 3, true);
        return new Plan(planner.parts, planner.queries, planner.slots);
    }

    private record Face(int a, int b, int c) {
        static Face of(int a, int b, int c) {
            // Only cyclic rotations are equivalent; reversed winding remains distinct.
            if (b <= a && b <= c && (b != a || c < b)) return new Face(b, c, a);
            if (c <= a && c <= b && (c != a || a < b)) return new Face(c, a, b);
            return new Face(a, b, c);
        }
    }

    private static Map<Face, Integer> faces(List<Integer> indices) {
        var result = new HashMap<Face, Integer>();
        for (int i = 0; i < indices.size(); i += 3)
            result.merge(Face.of(indices.get(i), indices.get(i + 1), indices.get(i + 2)), 1, Integer::sum);
        return result;
    }

    private static final class Planner {
        final ArtMeshGeometry source;
        final Limits limits;
        final Function<ArtMeshGeometry, ArtMeshSourceTriangulation> preview;
        final List<Part> parts = new ArrayList<>();
        int queries, slots;

        Planner(ArtMeshGeometry source, Limits limits, Function<ArtMeshGeometry, ArtMeshSourceTriangulation> preview) {
            this.source = source;
            this.limits = limits;
            this.preview = preview;
        }

        void split(int start, int end, boolean whole) {
            if (queries >= limits.maximumQueries())
                throw new IllegalArgumentException("native topology query budget exceeded");
            Part candidate = part(start, end, whole);
            queries++;
            var actual = Objects.requireNonNull(preview.apply(candidate.geometry()), "native topology result");
            boolean exact = candidate.geometry().positions().equals(actual.positions())
                    && faces(candidate.geometry().triangleIndices()).equals(faces(actual.triangleIndices()));
            if (!exact) {
                if (end - start == 1)
                    throw new IllegalArgumentException("native source plane rejects original face " + start
                            + "; a different source plane and texture mapping are required");
                int middle = start + (end - start) / 2;
                split(start, middle, false);
                split(middle, end, false);
                return;
            }
            if (parts.size() >= limits.maximumParts()
                    || candidate.sourceSlots().size() > limits.maximumTotalSlots() - slots)
                throw new IllegalArgumentException("editable topology parts/slots budget exceeded");
            parts.add(candidate);
            slots += candidate.sourceSlots().size();
        }

        Part part(int start, int end, boolean whole) {
            if (whole) {
                var all = new ArrayList<Integer>(source.positions().size());
                for (int i = 0; i < source.positions().size(); i++) all.add(i);
                return new Part(start, end, all, source);
            }
            var remap = new LinkedHashMap<Integer, Integer>();
            var indices = new ArrayList<Integer>((end - start) * 3);
            for (int i = start * 3; i < end * 3; i++) {
                int original = source.triangleIndices().get(i);
                indices.add(remap.computeIfAbsent(original, key -> remap.size()));
            }
            var originals = List.copyOf(remap.keySet());
            var positions = originals.stream().map(source.positions()::get).toList();
            var uvs = originals.stream().map(source.uvs()::get).toList();
            return new Part(start, end, originals, new ArtMeshGeometry(positions, uvs, indices));
        }
    }
}

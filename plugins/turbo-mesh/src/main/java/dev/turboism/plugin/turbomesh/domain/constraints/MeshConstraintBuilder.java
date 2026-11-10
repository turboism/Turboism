package dev.turboism.plugin.turbomesh.domain.constraints;

import dev.turboism.plugin.turbomesh.domain.features.FeatureField;
import dev.turboism.plugin.turbomesh.domain.features.MeshFeatureAnalyzer;
import dev.turboism.sdk.cubism.model.Point2;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns sampled boundary rings and feature analysis into an explicit constraint set: outer
 * silhouettes, holes, and protected corner struts. The output is the single source of truth for
 * what the triangulation must preserve — sampling never weakens it.
 */
public final class MeshConstraintBuilder {

    /** Explicit protection set for one placement run. */
    public record MeshConstraints(
            List<List<Point2>> outerRings,
            List<List<Point2>> holeRings,
            List<MeshFeatureAnalyzer.Corner> corners,
            List<FeatureField.ConstraintSegment> hardEdges) {}

    private MeshConstraintBuilder() {}

    /**
     * Classifies sampled rings by nesting depth (even depth = silhouette, odd = hole) and emits
     * one hard segment per ring edge plus corner struts. Ring order is preserved.
     */
    public static MeshConstraints build(
            final List<List<Point2>> sampledPolys, final List<MeshFeatureAnalyzer.Corner> corners) {
        final List<List<Point2>> outer = new ArrayList<>();
        final List<List<Point2>> holes = new ArrayList<>();
        for (int i = 0; i < sampledPolys.size(); i++) {
            int depth = 0;
            for (int j = 0; j < sampledPolys.size(); j++) {
                if (i != j && ringHoldsRing(sampledPolys.get(j), sampledPolys.get(i))) {
                    depth++;
                }
            }
            (depth % 2 == 0 ? outer : holes).add(sampledPolys.get(i));
        }
        final List<FeatureField.ConstraintSegment> edges = new ArrayList<>();
        for (final List<Point2> ring : outer) {
            emitRing(ring, FeatureField.ConstraintSegment.Kind.BOUNDARY, edges);
        }
        for (final List<Point2> ring : holes) {
            emitRing(ring, FeatureField.ConstraintSegment.Kind.HOLE, edges);
        }
        // Corner struts: tiny segments centered on the corner, kept only as diagnostic/priority
        // metadata — the corner vertex itself is protected by the boundary sampler, and the two
        // adjacent ring edges already form hard constraints through it.
        for (final MeshFeatureAnalyzer.Corner corner : corners) {
            edges.add(new FeatureField.ConstraintSegment(
                    corner.at(), corner.at(), FeatureField.ConstraintSegment.Kind.CORNER));
        }
        return new MeshConstraints(outer, holes, corners, edges);
    }

    private static void emitRing(
            final List<Point2> ring,
            final FeatureField.ConstraintSegment.Kind kind,
            final List<FeatureField.ConstraintSegment> out) {
        for (int i = 0; i < ring.size(); i++) {
            out.add(new FeatureField.ConstraintSegment(ring.get(i), ring.get((i + 1) % ring.size()), kind));
        }
    }

    /** Whether {@code inner}'s first edge midpoint lies inside {@code ring}. */
    private static boolean ringHoldsRing(final List<Point2> ring, final List<Point2> inner) {
        final Point2 a = inner.get(0);
        final Point2 b = inner.get(1 % inner.size());
        return pointInRing(ring, (a.x() + b.x()) / 2.0, (a.y() + b.y()) / 2.0);
    }

    /** Even-odd ray-cast containment. */
    private static boolean pointInRing(final List<Point2> ring, final double u, final double v) {
        boolean inside = false;
        for (int i = 0, j = ring.size() - 1; i < ring.size(); j = i++) {
            final Point2 a = ring.get(i);
            final Point2 b = ring.get(j);
            if ((a.y() > v) != (b.y() > v) && u < (b.x() - a.x()) * (v - a.y()) / (b.y() - a.y()) + a.x()) {
                inside = !inside;
            }
        }
        return inside;
    }
}

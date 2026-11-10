package dev.turboism.plugin.turbomesh.domain.features;

import dev.turboism.sdk.cubism.model.Point2;
import java.util.List;

/**
 * Density and direction field consumed by adaptive interior sampling. Values are sampled in UV
 * space; all implementations must be deterministic and pure — the same construction inputs
 * always yield the same answers.
 */
public interface FeatureField {

    /** Normalized interest in placing vertices near {@code (u, v)}: {@code 0} sparse, {@code 1} dense. */
    double densityAt(double u, double v);

    /** Preferred edge direction through {@code (u, v)} in radians (atan2 convention, UV axes). */
    double directionAt(double u, double v);

    /** How much the local direction may steer the mesh: {@code 0} ignore, {@code 1} trust fully. */
    double directionConfidenceAt(double u, double v);

    /** Segments that must survive triangulation verbatim; never weakened by sampling. */
    List<ConstraintSegment> hardEdges();

    /** One protected straight segment between two UV points. */
    record ConstraintSegment(Point2 a, Point2 b, Kind kind) {

        /** Why the segment is protected; drives diagnostics and priority, not semantics. */
        public enum Kind {
            /** Outer silhouette edge. */
            BOUNDARY,
            /** Hole edge — the hole must stay unfilled. */
            HOLE,
            /** Short strut guarding a detected corner. */
            CORNER,
            /** Feature line from the artwork or shape structure. */
            STRUCTURE
        }
    }
}

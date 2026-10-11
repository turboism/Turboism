package dev.turboism.validation.modelimport3d.host;

import java.util.ArrayList;
import java.util.List;

/**
 * Bounded key-density plan for ideal bilinear interpolation of SurfaceProjection.
 * This does not configure Cubism or certify its native evaluator. The caller must
 * leave an error margin for float storage/native evaluation and validate that path.
 */
final class SurfaceKeyPlan {
    private SurfaceKeyPlan() {}

    record Grid(List<Double> yawKeys, List<Double> pitchKeys, double errorBoundPixels) {
        Grid {
            yawKeys = List.copyOf(yawKeys);
            pitchKeys = List.copyOf(pitchKeys);
        }
    }

    static Grid linear(List<SurfaceProjection.Vertex> source, SurfaceProjection.Camera camera,
            double yawLimit, double pitchLimit, double tolerancePixels, int maxSamples) {
        if (!Double.isFinite(yawLimit) || yawLimit < 0 || yawLimit > 180
                || !Double.isFinite(pitchLimit) || pitchLimit < 0 || pitchLimit > 180
                || !Double.isFinite(tolerancePixels) || tolerancePixels <= 0 || maxSamples < 1) {
            throw new IllegalArgumentException("invalid sampling domain/budget");
        }
        if (source.isEmpty()) throw new IllegalArgumentException("source has no vertices");
        double radius = 0;
        for (var v : source) {
            var p = camera.pivot();
            radius = Math.max(radius, Math.hypot(Math.hypot(v.x() - p.x(), v.y() - p.y()), v.z() - p.z()));
        }
        double curvature = radius * camera.scale();
        if (!Double.isFinite(curvature)) throw new IllegalArgumentException("projection radius overflow");
        int axes = (yawLimit > 0 ? 1 : 0) + (pitchLimit > 0 ? 1 : 0);
        double perAxis = tolerancePixels / Math.max(1, axes);
        int yawSegments = segments(yawLimit, curvature, perAxis, maxSamples);
        int pitchSegments = segments(pitchLimit, curvature, perAxis, maxSamples);
        if ((long) (yawSegments + 1) * (pitchSegments + 1) > maxSamples) {
            throw new IllegalArgumentException("joint key grid exceeds sample budget");
        }
        double yawStep = yawSegments == 0 ? 0 : Math.toRadians(2 * yawLimit / yawSegments);
        double pitchStep = pitchSegments == 0 ? 0 : Math.toRadians(2 * pitchLimit / pitchSegments);
        // For each axis ||P''|| <= scale * radius. Linear error <= M*h^2/8.
        // Tensor interpolation is a convex combination: the two error bounds add.
        double bound = curvature * (yawStep * yawStep + pitchStep * pitchStep) / 8;
        if (bound > tolerancePixels) throw new IllegalArgumentException("sampling bound exceeds tolerance");
        return new Grid(keys(yawLimit, yawSegments), keys(pitchLimit, pitchSegments), bound);
    }

    private static int segments(double limit, double curvature, double tolerance, int budget) {
        if (limit == 0) return 0;
        double required = Math.toRadians(2 * limit) * Math.sqrt(curvature / (8 * tolerance));
        // Even segment count keeps the neutral key exactly zero.
        double even = Math.max(2, 2 * Math.ceil(required / 2));
        if (!Double.isFinite(even) || even >= budget || even >= Integer.MAX_VALUE) {
            throw new IllegalArgumentException("axis keys exceed sample budget");
        }
        return (int) even;
    }

    private static List<Double> keys(double limit, int segments) {
        if (segments == 0) return List.of(0d);
        var keys = new ArrayList<Double>(segments + 1);
        for (int i = 0; i <= segments; i++) {
            keys.add(i == segments / 2 ? 0d : limit * (2d * i / segments - 1));
        }
        return keys;
    }
}

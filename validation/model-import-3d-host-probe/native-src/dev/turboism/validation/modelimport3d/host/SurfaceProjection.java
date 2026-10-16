package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.model.Point2;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Pure projection for the shape probe; no host writes or visibility decisions.
 * Source positions are in a right-handed frame, after static node transforms.
 * Pose is R_y(yaw) * R_x(pitch) about one fixed pivot, in degrees.
 * The camera looks along -Z: screen X is +X, screen Y is -Y, depth is +Z
 * (larger is nearer). Canvas scale and center never depend on the pose.
 * Input order and duplicate positions are preserved, including UV seam vertices.
 */
final class SurfaceProjection {
    private SurfaceProjection() {}

    record Vertex(double x, double y, double z) {
        Vertex { finite(x); finite(y); finite(z); }
    }

    record Pose(double yaw, double pitch) {
        Pose { finite(yaw); finite(pitch); }
    }

    record Camera(double centerX, double centerY, double scale, Vertex pivot) {
        Camera {
            finite(centerX); finite(centerY); finite(scale);
            if (scale <= 0) throw new IllegalArgumentException("scale must be positive");
            Objects.requireNonNull(pivot);
        }
    }

    record Sample(Pose pose, List<Point2> positions, List<Double> depths) {
        Sample {
            Objects.requireNonNull(pose);
            positions = List.copyOf(positions);
            depths = List.copyOf(depths);
            if (positions.size() != depths.size()) {
                throw new IllegalArgumentException("position/depth count mismatch");
            }
            depths.forEach(SurfaceProjection::finite);
        }
    }

    static Sample project(List<Vertex> vertices, Camera camera, Pose pose) {
        Objects.requireNonNull(camera);
        Objects.requireNonNull(pose);
        double yaw = Math.toRadians(pose.yaw()), pitch = Math.toRadians(pose.pitch());
        double cy = Math.cos(yaw), sy = Math.sin(yaw);
        double cp = Math.cos(pitch), sp = Math.sin(pitch);
        var positions = new ArrayList<Point2>(vertices.size());
        var depths = new ArrayList<Double>(vertices.size());
        Vertex pivot = camera.pivot();
        for (Vertex v : vertices) {
            double x = v.x() - pivot.x(), y = v.y() - pivot.y(), z = v.z() - pivot.z();
            double py = y * cp - z * sp, pz = y * sp + z * cp;
            double rx = x * cy + pz * sy + pivot.x();
            double ry = py + pivot.y();
            double rz = -x * sy + pz * cy + pivot.z();
            // Point2 rejects non-finite values, including float conversion overflow.
            positions.add(new Point2((float) (camera.centerX() + camera.scale() * rx),
                (float) (camera.centerY() - camera.scale() * ry)));
            finite(rz);
            depths.add(rz);
        }
        return new Sample(pose, positions, depths);
    }

    private static void finite(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("projection input/result must be finite");
    }
}

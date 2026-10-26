package dev.turboism.plugin.mcp;

import dev.turboism.sdk.cubism.model.*;
import java.util.*;

/** Explicit image-defined depth template; never infers anatomy, parent transforms or angle units. */
final class McpWarpHeadVolumePlan {
    static final String POLICY = "PROJECT_HEAD_VOLUME_FROM_DEFAULT";

    enum Component {
        YAW,
        PITCH,
        ROLL
    }

    record Axis(String parameterGuid, Component component, double degreesPerParameterUnit) {
        Axis {
            if (Objects.requireNonNull(parameterGuid).isBlank()
                    || component == null
                    || !Double.isFinite(degreesPerParameterUnit)) throw new IllegalArgumentException();
        }
    }

    record Profile(
            double centerX, double centerY, double radiusX, double radiusY, double depth, double cameraDistance) {
        Profile {
            for (double v : new double[] {centerX, centerY, radiusX, radiusY, depth, cameraDistance})
                if (!Double.isFinite(v)) throw new IllegalArgumentException();
            if (radiusX <= 0
                    || radiusY <= 0
                    || depth < 0
                    || cameraDistance <= depth
                    || cameraDistance * .01 <= 0
                    || cameraDistance - depth < cameraDistance * .01) throw new IllegalArgumentException();
        }
    }

    static List<Map<String, Object>> edits(WarpAuthoringSnapshot snapshot, List<Axis> input, Profile profile) {
        var byGuid = new HashMap<String, Axis>();
        var components = EnumSet.noneOf(Component.class);
        for (var axis : input)
            if (byGuid.putIfAbsent(axis.parameterGuid(), axis) != null || !components.add(axis.component()))
                throw new IllegalArgumentException();
        // Reuse native grid identity/default/shared/form eligibility and point budgets. A zero
        // displacement field gives each target the stored default controls, without a host call.
        var zero = List.of(new McpLandmarkRigPlan.Anchor(0, 0, 0));
        var seed = McpWarpRigPlan.edits(
                snapshot,
                input.stream()
                        .map(a -> new McpLandmarkRigPlan.Axis(a.parameterGuid(), zero, zero))
                        .toList());
        var defaults = new HashMap<String, Float>();
        snapshot.axes().forEach(a -> defaults.put(a.parameterGuid(), a.defaultValue()));
        String defaultGuid = snapshot.cells().stream()
                .filter(cell -> cell.coordinates().stream().allMatch(c -> c.value() == defaults.get(c.parameterGuid())))
                .findFirst()
                .orElseThrow()
                .formGuid();
        var reference = snapshot.forms().stream()
                .filter(f -> f.formGuid().equals(defaultGuid))
                .findFirst()
                .orElseThrow()
                .controlPoints();
        var result = new ArrayList<Map<String, Object>>();
        for (var target : seed) {
            double yaw = 0, pitch = 0, roll = 0;
            @SuppressWarnings("unchecked")
            var coordinates = (List<Map<String, Object>>) target.get("coordinates");
            for (var c : coordinates) {
                String guid = (String) c.get("parameterGuid");
                var axis = byGuid.get(guid);
                double degrees =
                        (((Number) c.get("value")).doubleValue() - defaults.get(guid)) * axis.degreesPerParameterUnit();
                if (!Double.isFinite(degrees) || Math.abs(degrees) > (axis.component() == Component.ROLL ? 180 : 85))
                    throw new IllegalArgumentException();
                switch (axis.component()) {
                    case YAW -> yaw = degrees;
                    case PITCH -> pitch = degrees;
                    case ROLL -> roll = degrees;
                }
            }
            var points = new ArrayList<Map<String, Object>>();
            for (var point : reference) {
                double x = point.x(), y = point.y();
                Point2 projected = project(x, y, profile, yaw, pitch, roll);
                points.add(Map.of("x", projected.x(), "y", projected.y()));
            }
            result.add(Map.of(
                    "formGuid",
                    target.get("formGuid"),
                    "coordinates",
                    coordinates,
                    "controlPoints",
                    List.copyOf(points)));
        }
        McpKeyformWritePlan.prepare(snapshot, result);
        return List.copyOf(result);
    }

    private static Point2 project(double x, double y, Profile p, double yaw, double pitch, double roll) {
        if (yaw == 0 && pitch == 0 && roll == 0) return new Point2((float) x, (float) y);
        double u = (x - p.centerX()) / p.radiusX(), v = (y - p.centerY()) / p.radiusY();
        double z = p.depth() * Math.sqrt(Math.max(0, 1 - u * u - v * v));
        double d = p.cameraDistance();
        if (!Double.isFinite(z) || d - z < .01 * d) throw new IllegalArgumentException();
        double scale = (d - z) / d;
        double wx = (x - p.centerX()) * scale, wy = (y - p.centerY()) * scale;
        double cy = Math.cos(Math.toRadians(yaw)), sy = Math.sin(Math.toRadians(yaw));
        double cp = Math.cos(Math.toRadians(pitch)), sp = Math.sin(Math.toRadians(pitch));
        double cr = Math.cos(Math.toRadians(roll)), sr = Math.sin(Math.toRadians(roll));
        double xx = cy * wx + sy * z, zz = -sy * wx + cy * z;
        double yy = cp * wy - sp * zz;
        zz = sp * wy + cp * zz;
        double xr = cr * xx - sr * yy, yr = sr * xx + cr * yy;
        double distance = d - zz;
        if (!Double.isFinite(zz) || !Double.isFinite(distance) || distance < .01 * d)
            throw new IllegalArgumentException();
        // Divide first to avoid unnecessary overflow of d*x, then round once to native float.
        double perspective = d / distance;
        float px = (float) (p.centerX() + perspective * xr), py = (float) (p.centerY() + perspective * yr);
        if (!Float.isFinite(px) || !Float.isFinite(py)) throw new IllegalArgumentException();
        return new Point2(px, py);
    }
}

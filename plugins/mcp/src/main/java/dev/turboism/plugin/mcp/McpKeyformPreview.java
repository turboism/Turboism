package dev.turboism.plugin.mcp;

import dev.turboism.sdk.cubism.model.*;
import java.util.*;

/** Compact exact before/expected-after differences in each object's native stored space. */
final class McpKeyformPreview {
    static Map<String, Object> summary(McpKeyformWritePlan.Plan plan) {
        var ids = new LinkedHashSet<String>();
        var rows = new ArrayList<Map<String, Object>>();
        int points = 0, changedPoints = 0, orders = 0, opacities = 0, reflections = 0;
        double maxPoint = 0, maxAngle = 0, maxOrigin = 0, maxScale = 0;
        if (plan instanceof McpKeyformWritePlan.Mesh mesh) {
            mesh.edits().forEach(e -> ids.add(e.formGuid()));
            mesh.drawOrders().forEach(e -> ids.add(e.formGuid()));
            mesh.values().forEach(e -> ids.add(e.formGuid()));
            points = mesh.edits().stream().mapToInt(e -> e.positions().size()).sum();
            var before = new HashMap<String, ArtMeshAuthoringSnapshot.Form>();
            mesh.before().forms().forEach(f -> before.put(f.formGuid(), f));
            var after = new HashMap<String, ArtMeshAuthoringSnapshot.Form>();
            mesh.after().forms().forEach(f -> after.put(f.formGuid(), f));
            for (String id : ids) {
                var a = before.get(id);
                var b = after.get(id);
                var fields = new ArrayList<String>();
                var diff = points(a.positions(), b.positions());
                changedPoints += diff.count();
                maxPoint = Math.max(maxPoint, diff.maximum());
                if (diff.count() > 0) fields.add("positions");
                if (a.drawOrder() != b.drawOrder()) {
                    orders++;
                    fields.add("drawOrder");
                }
                if (!a.opacity().equals(b.opacity())) {
                    opacities++;
                    fields.add("opacity");
                }
                rows.add(Map.of("formGuid", id, "changedFields", List.copyOf(fields)));
            }
        } else if (plan instanceof McpKeyformWritePlan.Warp warp) {
            warp.edits().forEach(e -> ids.add(e.formGuid()));
            points = warp.edits().stream()
                    .mapToInt(e -> e.controlPoints().size())
                    .sum();
            var before = new HashMap<String, WarpAuthoringSnapshot.Form>();
            warp.before().forms().forEach(f -> before.put(f.formGuid(), f));
            var after = new HashMap<String, WarpAuthoringSnapshot.Form>();
            warp.after().forms().forEach(f -> after.put(f.formGuid(), f));
            for (String id : ids) {
                var diff = points(before.get(id).controlPoints(), after.get(id).controlPoints());
                changedPoints += diff.count();
                maxPoint = Math.max(maxPoint, diff.maximum());
                rows.add(Map.of(
                        "formGuid", id, "changedFields", diff.count() > 0 ? List.of("controlPoints") : List.of()));
            }
        } else if (plan instanceof McpKeyformWritePlan.Rotation rotation) {
            rotation.edits().forEach(e -> ids.add(e.formGuid()));
            var before = new HashMap<String, RotationDeformerForm>();
            rotation.before().forms().forEach(f -> before.put(f.formGuid(), f.value()));
            var after = new HashMap<String, RotationDeformerForm>();
            rotation.after().forms().forEach(f -> after.put(f.formGuid(), f.value()));
            for (String id : ids) {
                var a = before.get(id);
                var b = after.get(id);
                var fields = new ArrayList<String>();
                double angle = Math.abs((double) a.angle() - b.angle());
                maxAngle = Math.max(maxAngle, angle);
                double origin = Math.hypot((double) a.originX() - b.originX(), (double) a.originY() - b.originY());
                maxOrigin = Math.max(maxOrigin, origin);
                double scale = Math.abs((double) a.scale() - b.scale());
                maxScale = Math.max(maxScale, scale);
                if (Float.compare(a.angle(), b.angle()) != 0) fields.add("angle");
                if (!a.origin().equals(b.origin())) fields.add("origin");
                if (Float.compare(a.scale(), b.scale()) != 0) fields.add("scale");
                if (a.reflectedX() != b.reflectedX()) {
                    reflections++;
                    fields.add("reflectedX");
                }
                if (a.reflectedY() != b.reflectedY()) {
                    reflections++;
                    fields.add("reflectedY");
                }
                rows.add(Map.of("formGuid", id, "changedFields", List.copyOf(fields)));
            }
        } else throw new IllegalArgumentException();
        var result = new LinkedHashMap<String, Object>();
        result.put("requestedForms", ids.size());
        result.put(
                "changedForms",
                rows.stream()
                        .filter(r -> !((List<?>) r.get("changedFields")).isEmpty())
                        .count());
        result.put("budgetedPoints", points);
        result.put("changedPoints", changedPoints);
        result.put("maxPointDisplacement", maxPoint);
        result.put("changedDrawOrders", orders);
        result.put("changedOpacities", opacities);
        result.put("changedReflectionFlags", reflections);
        result.put("maxAngleDelta", maxAngle);
        result.put("maxOriginDisplacement", maxOrigin);
        result.put("maxScaleDelta", maxScale);
        result.put("forms", List.copyOf(rows));
        return Collections.unmodifiableMap(result);
    }

    private record PointDiff(int count, double maximum) {}

    private static PointDiff points(List<Point2> before, List<Point2> after) {
        if (before.size() != after.size()) throw new IllegalArgumentException();
        int count = 0;
        double maximum = 0;
        for (int i = 0; i < before.size(); i++)
            if (!before.get(i).equals(after.get(i))) {
                count++;
                maximum = Math.max(
                        maximum,
                        Math.hypot(
                                (double) before.get(i).x() - after.get(i).x(),
                                (double) before.get(i).y() - after.get(i).y()));
            }
        return new PointDiff(count, maximum);
    }
}

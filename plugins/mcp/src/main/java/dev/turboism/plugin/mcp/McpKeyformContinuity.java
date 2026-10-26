package dev.turboism.plugin.mcp;

import dev.turboism.sdk.cubism.model.*;
import java.util.*;

/** Bounded secant-slope diagnostics of stored ordinary forms, never native interpolation. */
final class McpKeyformContinuity {
    static final List<String> IMPLEMENTED = List.of(
            "STORED_POSITION_SECANT_SLOPE_CHANGE", "STORED_ROTATION_ANGLE_SECANT_SLOPE_CHANGE",
            "STORED_ROTATION_SCALE_SECANT_SLOPE_CHANGE", "STORED_ROTATION_REFLECTION_CHANGE");
    static final List<String> OMITTED = List.of(
            "NATIVE_INTERPOLATION_CONTINUITY",
            "BETWEEN_KEY_GEOMETRY",
            "EVALUATED_GEOMETRY",
            "PARENT_TRANSFORMS",
            "MIXED_AXIS_DERIVATIVES",
            "DRAW_ORDER_AND_OPACITY",
            "MORPH_COMBINATIONS",
            "VISUAL_ART_QUALITY");

    record Thresholds(double position, double angle, double scale) {
        Thresholds {
            for (double value : new double[] {position, angle, scale})
                if (!Double.isFinite(value) || value < 0 || value > 1e12) throw new IllegalArgumentException();
        }
    }

    record Report(
            List<Map<String, Object>> findings,
            long findingCount,
            int triples,
            long pointComparisons,
            double maximumPositionSlopeChange,
            double maximumAngleSlopeChange,
            double maximumScaleSlopeChange) {}

    static Report analyze(Object snapshot, Thresholds thresholds, int maximumFindings) {
        if (maximumFindings < 1 || maximumFindings > 200) throw new IllegalArgumentException();
        var view = McpBindingGridSnapshot.view(snapshot);
        var axes = view.axes().values().stream()
                .sorted(Comparator.comparing(McpBindingGridSnapshot.Axis::guid))
                .toList();
        if (axes.isEmpty() || axes.size() > 3) throw new UnsupportedOperationException();
        int expected = 1;
        for (var axis : axes) {
            identity(axis.guid());
            identity(axis.id());
            if (axis.keys().size() < 3
                    || axis.keys().size() > 9
                    || axis.minimum() >= axis.maximum()
                    || axis.keys().get(0) < axis.minimum()
                    || axis.keys().get(axis.keys().size() - 1) > axis.maximum())
                throw new UnsupportedOperationException();
            expected = Math.multiplyExact(expected, axis.keys().size());
        }
        if (expected > McpBindingGridSnapshot.MAX_CELLS) throw new McpBindingGridSnapshot.Budget();
        var byTuple = new HashMap<Map<String, Float>, McpBindingGridSnapshot.Cell>();
        var used = new HashSet<String>();
        for (var cell : view.cells()) {
            identity(cell.guid());
            if (!cell.coordinates().keySet().equals(view.axes().keySet())
                    || byTuple.putIfAbsent(cell.coordinates(), cell) != null
                    || !view.forms().containsKey(cell.guid())) throw new IllegalArgumentException();
            used.add(cell.guid());
            for (var axis : axes)
                if (!axis.keys().contains(cell.coordinates().get(axis.guid()))) throw new IllegalArgumentException();
        }
        if (byTuple.size() != expected || !used.equals(view.forms().keySet()))
            throw new UnsupportedOperationException();
        var sink = new Sink(maximumFindings);
        int triples = 0;
        long comparisons = 0;
        double maxPosition = 0, maxAngle = 0, maxScale = 0;
        // Sorting tuples by numeric coordinates keeps findings stable across native collection order.
        var cells = view.cells().stream()
                .sorted((a, b) -> {
                    for (var axis : axes) {
                        int order = Float.compare(
                                a.coordinates().get(axis.guid()),
                                b.coordinates().get(axis.guid()));
                        if (order != 0) return order;
                    }
                    return 0;
                })
                .toList();
        for (var axis : axes) {
            for (var center : cells) {
                int index = axis.keys().indexOf(center.coordinates().get(axis.guid()));
                if (index == 0 || index == axis.keys().size() - 1) continue;
                var leftTuple = new HashMap<>(center.coordinates());
                var rightTuple = new HashMap<>(center.coordinates());
                float leftKey = axis.keys().get(index - 1),
                        centerKey = axis.keys().get(index),
                        rightKey = axis.keys().get(index + 1);
                leftTuple.put(axis.guid(), leftKey);
                rightTuple.put(axis.guid(), rightKey);
                var left = byTuple.get(leftTuple);
                var right = byTuple.get(rightTuple);
                if (left == null || right == null) throw new IllegalArgumentException();
                var a = view.forms().get(left.guid());
                var b = view.forms().get(center.guid());
                var c = view.forms().get(right.guid());
                double range = (double) axis.maximum() - axis.minimum();
                double h0 = ((double) centerKey - leftKey) / range;
                double h1 = ((double) rightKey - centerKey) / range;
                triples++;
                var location = new LinkedHashMap<String, Object>();
                location.put("parameterGuid", axis.guid());
                location.put(
                        "centerCoordinates",
                        axes.stream()
                                .map(x -> Map.of(
                                        "parameterGuid",
                                        x.guid(),
                                        "value",
                                        center.coordinates().get(x.guid())))
                                .toList());
                location.put("formGuids", List.of(left.guid(), center.guid(), right.guid()));
                location.put("axisValues", List.of(leftKey, centerKey, rightKey));
                if (a.rotation() == null) {
                    if (a.points().size() != b.points().size()
                            || a.points().size() != c.points().size()) throw new IllegalArgumentException();
                    for (int i = 0; i < a.points().size(); i++) {
                        double value = pointJump(
                                a.points().get(i), b.points().get(i), c.points().get(i), h0, h1);
                        maxPosition = Math.max(maxPosition, value);
                        comparisons++;
                        if (value > thresholds.position())
                            sink.add(location, "POSITION_SLOPE_CHANGE", "positions", i, value, thresholds.position());
                    }
                } else {
                    var ar = a.rotation();
                    var br = b.rotation();
                    var cr = c.rotation();
                    double position = pointJump(ar.origin(), br.origin(), cr.origin(), h0, h1);
                    double angle = Math.abs(jump(ar.angle(), br.angle(), cr.angle(), h0, h1));
                    double scale = Math.abs(jump(ar.scale(), br.scale(), cr.scale(), h0, h1));
                    maxPosition = Math.max(maxPosition, position);
                    maxAngle = Math.max(maxAngle, angle);
                    maxScale = Math.max(maxScale, scale);
                    comparisons++;
                    if (position > thresholds.position())
                        sink.add(location, "POSITION_SLOPE_CHANGE", "origin", null, position, thresholds.position());
                    if (angle > thresholds.angle())
                        sink.add(location, "ANGLE_SLOPE_CHANGE", "angle", null, angle, thresholds.angle());
                    if (scale > thresholds.scale())
                        sink.add(location, "SCALE_SLOPE_CHANGE", "scale", null, scale, thresholds.scale());
                    if (ar.reflectedX() != br.reflectedX() || br.reflectedX() != cr.reflectedX())
                        sink.add(location, "REFLECTION_CHANGE", "reflectedX", null, 1, 0);
                    if (ar.reflectedY() != br.reflectedY() || br.reflectedY() != cr.reflectedY())
                        sink.add(location, "REFLECTION_CHANGE", "reflectedY", null, 1, 0);
                }
            }
        }
        return new Report(
                List.copyOf(sink.findings), sink.count, triples, comparisons, maxPosition, maxAngle, maxScale);
    }

    static void identity(String value) {
        if (value == null
                || value.isBlank()
                || value.length() > 4096
                || value.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i)))
                    throw new IllegalArgumentException();
            } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException();
        }
    }

    private static double pointJump(Point2 a, Point2 b, Point2 c, double h0, double h1) {
        return Math.hypot(jump(a.x(), b.x(), c.x(), h0, h1), jump(a.y(), b.y(), c.y(), h0, h1));
    }

    private static double jump(double a, double b, double c, double h0, double h1) {
        return (c - b) / h1 - (b - a) / h0;
    }

    private static final class Sink {
        final int maximum;
        final List<Map<String, Object>> findings = new ArrayList<>();
        long count;

        Sink(int maximum) {
            this.maximum = maximum;
        }

        void add(
                Map<String, Object> location,
                String code,
                String component,
                Integer index,
                double observed,
                double threshold) {
            count++;
            if (findings.size() == maximum) return;
            var row = new LinkedHashMap<>(location);
            row.put("code", code);
            row.put("severity", "WARNING");
            row.put("component", component);
            row.put("pointIndex", index);
            row.put("observed", observed);
            row.put("threshold", threshold);
            findings.add(Collections.unmodifiableMap(row));
        }
    }
}

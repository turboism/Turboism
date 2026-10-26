package dev.turboism.plugin.mcp;

import dev.turboism.sdk.cubism.model.*;
import java.util.*;

/** Landmark-driven displacement fields in native stored coordinates; no anatomical inference. */
final class McpLandmarkRigPlan {
    static final int MAX_POINTS = 4096;

    record NativeAxis(String parameterGuid, float minimum, float defaultValue, float maximum, List<Float> keys) {}

    record NativeCoordinate(String parameterGuid, float value) {}

    record NativeCell(String formGuid, List<NativeCoordinate> coordinates) {}

    record Anchor(int index, double dx, double dy) {}

    record Axis(String guid, List<Anchor> negative, List<Anchor> positive) {}

    record Corner(Map<String, Boolean> positiveSides, List<Anchor> anchors) {}

    private record Vector(double x, double y) {}

    private record CornerField(Map<String, Boolean> positiveSides, List<Vector> vectors) {}

    private record Field(NativeAxis axis, List<Vector> negative, List<Vector> positive, float low, float high) {}

    static List<Map<String, Object>> edits(
            List<NativeAxis> axes,
            List<NativeCell> cells,
            Map<String, List<Point2>> forms,
            List<Axis> input,
            List<Corner> corners,
            String positionField) {
        if (corners.size() > 20 || (!corners.isEmpty() && axes.size() < 2)) throw new IllegalArgumentException();
        if (axes.isEmpty() || axes.size() > 3 || input.size() != axes.size()) throw new UnsupportedOperationException();
        var defaults = new HashMap<String, Float>();
        long expectedCells = 1;
        for (var axis : axes) {
            if (axis.keys().size() < 3
                    || axis.keys().size() > 65
                    || new HashSet<>(axis.keys()).size() != axis.keys().size()
                    || !axis.keys().contains(axis.defaultValue())) throw new IllegalArgumentException();
            defaults.put(axis.parameterGuid(), axis.defaultValue());
            expectedCells *= axis.keys().size();
        }
        if (expectedCells > 65 || cells.size() != expectedCells) throw new IllegalArgumentException();
        var cellMap = new HashMap<Map<String, Float>, String>();
        var referenced = new HashSet<String>();
        for (var cell : cells) {
            var tuple = new HashMap<String, Float>();
            cell.coordinates().forEach(c -> tuple.put(c.parameterGuid(), c.value()));
            if (cellMap.putIfAbsent(Map.copyOf(tuple), cell.formGuid()) != null || !referenced.add(cell.formGuid()))
                throw new IllegalArgumentException();
        }
        String defaultGuid = cellMap.get(defaults);
        if (defaultGuid == null) throw new IllegalArgumentException();
        var reference = forms.get(defaultGuid);
        if (reference == null || reference.isEmpty()) throw new IllegalArgumentException();
        if ((expectedCells - 1) * reference.size() > MAX_POINTS) throw new IllegalArgumentException();
        var requested = new HashMap<String, Axis>();
        for (var axis : input)
            if (requested.putIfAbsent(axis.guid(), axis) != null) throw new IllegalArgumentException();
        if (!requested.keySet().equals(defaults.keySet())) throw new IllegalArgumentException();
        var fields = new ArrayList<Field>();
        for (var axis : axes) {
            float low = Collections.min(axis.keys()), high = Collections.max(axis.keys());
            if (!(low < axis.defaultValue() && high > axis.defaultValue())
                    || low < axis.minimum()
                    || high > axis.maximum()) throw new IllegalArgumentException();
            var request = requested.get(axis.parameterGuid());
            fields.add(new Field(
                    axis,
                    interpolate(reference, request.negative()),
                    interpolate(reference, request.positive()),
                    low,
                    high));
        }
        var cornerFields = new ArrayList<CornerField>();
        var quadrants = new HashSet<Map<String, Boolean>>();
        for (var corner : corners) {
            if (corner.positiveSides().size() < 2
                    || corner.positiveSides().size() > 3
                    || !defaults.keySet().containsAll(corner.positiveSides().keySet())
                    || corner.positiveSides().values().stream().anyMatch(Objects::isNull)
                    || !quadrants.add(Map.copyOf(corner.positiveSides()))) throw new IllegalArgumentException();
            cornerFields.add(
                    new CornerField(Map.copyOf(corner.positiveSides()), interpolate(reference, corner.anchors())));
        }
        var edits = new ArrayList<Map<String, Object>>();
        for (var cell : cells) {
            if (cell.formGuid().equals(defaultGuid)) continue;
            var coordinates = new HashMap<String, Float>();
            cell.coordinates().forEach(c -> coordinates.put(c.parameterGuid(), c.value()));
            var points = new ArrayList<Map<String, Object>>();
            for (int i = 0; i < reference.size(); i++) {
                double x = reference.get(i).x(), y = reference.get(i).y();
                for (var field : fields) {
                    double normalized = normalized(field, coordinates);
                    boolean positive = normalized >= 0;
                    double factor = Math.abs(normalized);
                    var d = (positive ? field.positive() : field.negative()).get(i);
                    x += factor * d.x();
                    y += factor * d.y();
                }
                for (var corner : cornerFields) {
                    double weight = 1;
                    for (var field : fields) {
                        var side = corner.positiveSides().get(field.axis().parameterGuid());
                        if (side == null) continue;
                        double normalized = normalized(field, coordinates);
                        boolean positive = side;
                        weight *= Math.max(0, positive ? normalized : -normalized);
                    }
                    var d = corner.vectors().get(i);
                    x += weight * d.x();
                    y += weight * d.y();
                }
                float fx = (float) x, fy = (float) y;
                if (!Float.isFinite(fx) || !Float.isFinite(fy)) throw new IllegalArgumentException();
                points.add(Map.of("x", fx, "y", fy));
            }
            edits.add(Map.of(
                    "formGuid",
                    cell.formGuid(),
                    "coordinates",
                    cell.coordinates().stream()
                            .map(c -> Map.of("parameterGuid", c.parameterGuid(), "value", c.value()))
                            .toList(),
                    positionField,
                    List.copyOf(points)));
        }
        return List.copyOf(edits);
    }

    private static double normalized(Field field, Map<String, Float> coordinates) {
        double delta = (double) coordinates.get(field.axis().parameterGuid())
                - field.axis().defaultValue();
        return delta >= 0
                ? delta / ((double) field.high() - field.axis().defaultValue())
                : delta / ((double) field.axis().defaultValue() - field.low());
    }

    /** Normalized inverse-square distance weights; exact anchors reproduce specified displacement. */
    private static List<Vector> interpolate(List<Point2> reference, List<Anchor> anchors) {
        if (anchors.isEmpty() || anchors.size() > 16) throw new IllegalArgumentException();
        var indices = new HashSet<Integer>();
        for (var a : anchors) {
            if (a.index() < 0
                    || a.index() >= reference.size()
                    || !indices.add(a.index())
                    || !Double.isFinite(a.dx())
                    || !Double.isFinite(a.dy())
                    || !Float.isFinite((float) a.dx())
                    || !Float.isFinite((float) a.dy())) throw new IllegalArgumentException();
        }
        // Coincident anchor positions cannot express independent landmark constraints.
        for (int i = 0; i < anchors.size(); i++)
            for (int j = 0; j < i; j++)
                if (distance(
                                reference.get(anchors.get(i).index()),
                                reference.get(anchors.get(j).index()))
                        == 0) throw new IllegalArgumentException();
        var result = new ArrayList<Vector>();
        for (var point : reference) {
            double nearest = Double.POSITIVE_INFINITY;
            Anchor exact = null;
            for (var anchor : anchors) {
                double d = distance(point, reference.get(anchor.index()));
                if (d == 0) {
                    exact = anchor;
                    break;
                }
                nearest = Math.min(nearest, d);
            }
            double dx = 0, dy = 0, total = 0;
            if (exact != null) {
                dx = exact.dx();
                dy = exact.dy();
            } else {
                for (var anchor : anchors) {
                    double weight = nearest / distance(point, reference.get(anchor.index()));
                    total += weight;
                    dx += weight * anchor.dx();
                    dy += weight * anchor.dy();
                }
                dx /= total;
                dy /= total;
            }
            result.add(new Vector(dx, dy));
        }
        return List.copyOf(result);
    }

    private static double distance(Point2 a, Point2 b) {
        double x = (double) a.x() - b.x(), y = (double) a.y() - b.y();
        return x * x + y * y;
    }
}

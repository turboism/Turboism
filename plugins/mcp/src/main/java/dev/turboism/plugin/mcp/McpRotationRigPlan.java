package dev.turboism.plugin.mcp;

import dev.turboism.sdk.cubism.model.*;
import java.util.*;

/** Pure default-relative angle replacement on an explicit ordinary parameter grid. */
final class McpRotationRigPlan {
    record Axis(String parameterGuid, double gain) {
        Axis {
            if (Objects.requireNonNull(parameterGuid).isBlank() || !Double.isFinite(gain))
                throw new IllegalArgumentException();
        }
    }

    // Preserve the original input contract: one GUID never silently selects a subset of axes.
    static List<Map<String, Object>> edits(RotationAuthoringSnapshot snapshot, String parameterGuid, double gain) {
        return edits(snapshot, List.of(new Axis(parameterGuid, gain)));
    }

    static List<Map<String, Object>> edits(RotationAuthoringSnapshot snapshot, List<Axis> input) {
        if (snapshot.axes().isEmpty()
                || snapshot.axes().size() > 3
                || input.size() != snapshot.axes().size()) throw new UnsupportedOperationException();
        var gains = new HashMap<String, Double>();
        for (var axis : input)
            if (gains.putIfAbsent(axis.parameterGuid(), axis.gain()) != null) throw new IllegalArgumentException();
        var axisIds = snapshot.axes().stream()
                .map(RotationAuthoringSnapshot.Axis::parameterGuid)
                .toList();
        if (!gains.keySet().equals(new HashSet<>(axisIds))) throw new UnsupportedOperationException();
        int count = 1;
        for (var axis : snapshot.axes()) {
            var keys = new HashSet<Float>();
            if (axis.keys().size() < 2 || axis.keys().size() > 65) throw new IllegalArgumentException();
            for (float key : axis.keys())
                if (key < axis.minimum() || key > axis.maximum() || !keys.add(zero(key)))
                    throw new IllegalArgumentException();
            if (!keys.contains(zero(axis.defaultValue()))) throw new IllegalArgumentException();
            count *= keys.size();
            if (count > 65) throw new IllegalArgumentException();
        }
        if (snapshot.cells().size() != count) throw new IllegalArgumentException();
        var cells = new HashMap<List<Float>, RotationAuthoringSnapshot.Cell>();
        var referenced = new HashSet<String>();
        for (var cell : snapshot.cells()) {
            var coordinates = new HashMap<String, Float>();
            cell.coordinates().forEach(c -> coordinates.put(c.parameterGuid(), zero(c.value())));
            var tuple = axisIds.stream().map(coordinates::get).toList();
            if (cells.putIfAbsent(tuple, cell) != null || !referenced.add(cell.formGuid()))
                throw new IllegalArgumentException();
        }
        // Unique valid tuples at Cartesian cardinality prove that the whole grid is present.
        var defaultTuple =
                snapshot.axes().stream().map(a -> zero(a.defaultValue())).toList();
        var defaultCell = cells.get(defaultTuple);
        if (defaultCell == null) throw new IllegalArgumentException();
        var forms = new HashMap<String, RotationDeformerForm>();
        snapshot.forms().forEach(f -> forms.put(f.formGuid(), f.value()));
        var reference = forms.get(defaultCell.formGuid());
        var result = new ArrayList<Map<String, Object>>();
        // Stable native cell order; coordinate order and caller axis order carry no identity.
        for (var cell : snapshot.cells()) {
            if (cell.formGuid().equals(defaultCell.formGuid())) continue;
            var coordinates = new HashMap<String, Float>();
            cell.coordinates().forEach(c -> coordinates.put(c.parameterGuid(), c.value()));
            double angle = reference.angle();
            for (var axis : snapshot.axes()) {
                angle += ((double) coordinates.get(axis.parameterGuid()) - axis.defaultValue())
                        * gains.get(axis.parameterGuid());
                if (!Double.isFinite(angle)) throw new IllegalArgumentException();
            }
            float nativeAngle = (float) angle;
            if (!Float.isFinite(nativeAngle)) throw new IllegalArgumentException();
            var old = forms.get(cell.formGuid());
            result.add(Map.of(
                    "formGuid", cell.formGuid(),
                    "coordinates",
                            snapshot.axes().stream()
                                    .map(a -> Map.<String, Object>of(
                                            "parameterGuid",
                                            a.parameterGuid(),
                                            "value",
                                            coordinates.get(a.parameterGuid())))
                                    .toList(),
                    "form",
                            Map.of(
                                    "angle",
                                    nativeAngle,
                                    "originX",
                                    old.originX(),
                                    "originY",
                                    old.originY(),
                                    "scale",
                                    old.scale(),
                                    "reflectedX",
                                    old.reflectedX(),
                                    "reflectedY",
                                    old.reflectedY())));
        }
        // Reuse all ordinary writer tuple/default/shared-form protections.
        McpKeyformWritePlan.prepare(snapshot, result);
        return List.copyOf(result);
    }

    private static float zero(float value) {
        return value == 0 ? 0f : value;
    }
}

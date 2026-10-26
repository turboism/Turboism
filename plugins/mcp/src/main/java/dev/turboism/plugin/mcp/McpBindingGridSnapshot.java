package dev.turboism.plugin.mcp;

import dev.turboism.sdk.cubism.model.*;
import java.util.*;

/** Pure checks of actual stored cells/forms, never inferred grids or evaluated shapes. */
final class McpBindingGridSnapshot {
    static final int MAX_CELLS = 27, MAX_POINTS = 4096, MAX_SAMPLES = 32768;

    record Axis(String guid, String id, float minimum, float defaultValue, float maximum, List<Float> keys) {
        Axis {
            minimum = zero(minimum);
            defaultValue = zero(defaultValue);
            maximum = zero(maximum);
            keys = keys.stream().map(McpBindingGridSnapshot::zero).sorted().toList();
            if (keys.isEmpty() || new HashSet<>(keys).size() != keys.size()) throw new IllegalArgumentException();
        }

        Map<String, Object> wire() {
            return Map.of(
                    "parameterGuid",
                    guid,
                    "parameterId",
                    id,
                    "minimum",
                    minimum,
                    "defaultValue",
                    defaultValue,
                    "maximum",
                    maximum,
                    "keys",
                    keys);
        }
    }

    record Cell(String guid, Map<String, Float> coordinates) {}

    record Payload(List<Point2> points, Integer drawOrder, Optional<Float> opacity, RotationDeformerForm rotation) {}

    record View(Object source, Map<String, Axis> axes, List<Cell> cells, Map<String, Payload> forms, int points) {}

    static View view(Object value) {
        return view(value, MAX_CELLS, MAX_SAMPLES);
    }

    static View view(Object value, int maxCells, int maxSamples) {
        var axes = new LinkedHashMap<String, Axis>();
        var cells = new ArrayList<Cell>();
        var forms = new LinkedHashMap<String, Payload>();
        Object source;
        int points;
        if (value instanceof ArtMeshAuthoringSnapshot s) {
            if (!s.morphKeys().isEmpty()) throw new UnsupportedOperationException();
            source = List.of(s.artMeshId(), s.sourceGuid(), s.sourceGeometry());
            points = s.sourceGeometry().positions().size();
            for (var a : s.axes())
                add(
                        axes,
                        new Axis(
                                a.parameterGuid(),
                                a.parameterId().value(),
                                a.minimum(),
                                a.defaultValue(),
                                a.maximum(),
                                a.keys()));
            for (var c : s.cells()) {
                var coordinates = new LinkedHashMap<String, Float>();
                for (var c0 : c.coordinates()) coordinates.put(c0.parameterGuid(), zero(c0.value()));
                cells.add(new Cell(c.formGuid(), Map.copyOf(coordinates)));
            }
            for (var f : s.forms())
                forms.put(f.formGuid(), new Payload(f.positions(), f.drawOrder(), f.opacity(), null));
        } else if (value instanceof WarpAuthoringSnapshot s) {
            source = List.of(s.deformerId(), s.sourceGuid(), s.rows(), s.columns(), s.quadTransform());
            points = Math.multiplyExact(s.rows() + 1, s.columns() + 1);
            for (var a : s.axes())
                add(
                        axes,
                        new Axis(
                                a.parameterGuid(),
                                a.parameterId().value(),
                                a.minimum(),
                                a.defaultValue(),
                                a.maximum(),
                                a.keys()));
            for (var c : s.cells()) {
                var coordinates = new LinkedHashMap<String, Float>();
                for (var c0 : c.coordinates()) coordinates.put(c0.parameterGuid(), zero(c0.value()));
                cells.add(new Cell(c.formGuid(), Map.copyOf(coordinates)));
            }
            for (var f : s.forms())
                forms.put(f.formGuid(), new Payload(f.controlPoints(), null, Optional.empty(), null));
        } else if (value instanceof RotationAuthoringSnapshot s) {
            source = List.of(s.deformerId(), s.sourceGuid(), s.baseAngle());
            points = 1;
            for (var a : s.axes())
                add(
                        axes,
                        new Axis(
                                a.parameterGuid(),
                                a.parameterId().value(),
                                a.minimum(),
                                a.defaultValue(),
                                a.maximum(),
                                a.keys()));
            for (var c : s.cells()) {
                var coordinates = new LinkedHashMap<String, Float>();
                for (var c0 : c.coordinates()) coordinates.put(c0.parameterGuid(), zero(c0.value()));
                cells.add(new Cell(c.formGuid(), Map.copyOf(coordinates)));
            }
            for (var f : s.forms()) forms.put(f.formGuid(), new Payload(List.of(), null, Optional.empty(), f.value()));
        } else throw new UnsupportedOperationException();
        if (points < 1
                || points > MAX_POINTS
                || forms.size() > maxCells
                || cells.size() > maxCells
                || (long) points * forms.size() > maxSamples) throw new Budget();
        return new View(source, Map.copyOf(axes), List.copyOf(cells), Map.copyOf(forms), points);
    }

    static Payload initial(Object value) {
        var v = view(value);
        if (!v.axes().isEmpty()
                || v.cells().size() != 1
                || v.forms().size() != 1
                || !v.cells().get(0).coordinates().isEmpty()
                || !v.forms().containsKey(v.cells().get(0).guid())) throw new UnsupportedOperationException();
        return v.forms().values().iterator().next();
    }

    static List<Map<String, Object>> verify(Object original, Object actual, List<Axis> axes) {
        var before = view(original);
        var after = view(actual);
        var payload = initial(original);
        var expectedAxes = new LinkedHashMap<String, Axis>();
        for (var axis : axes) add(expectedAxes, axis);
        int count = 1;
        for (var a : axes) count = Math.multiplyExact(count, a.keys().size());
        if (count > MAX_CELLS || (long) count * before.points() > MAX_SAMPLES) throw new Budget();
        if (!before.source().equals(after.source())
                || !expectedAxes.equals(after.axes())
                || after.cells().size() != count
                || after.forms().size() != count
                || !after.forms().values().stream().allMatch(payload::equals)) throw new Mismatch();
        var tuples = new HashSet<Map<String, Float>>();
        var used = new HashSet<String>();
        var rows = new ArrayList<Map<String, Object>>();
        for (var cell : after.cells()) {
            if (cell.guid().length() > 128 || cell.guid().chars().anyMatch(Character::isISOControl))
                throw new Mismatch();
            if (!tuples.add(cell.coordinates())
                    || !used.add(cell.guid())
                    || !after.forms().containsKey(cell.guid())
                    || !cell.coordinates().keySet().equals(expectedAxes.keySet())) throw new Mismatch();
            boolean isDefault = true;
            for (var a : axes) {
                Float key = cell.coordinates().get(a.guid());
                if (!a.keys().contains(key)) throw new Mismatch();
                isDefault &= key.equals(a.defaultValue());
            }
            rows.add(Map.of(
                    "formGuid",
                    cell.guid(),
                    "coordinates",
                    axes.stream()
                            .map(a -> Map.of(
                                    "parameterGuid",
                                    a.guid(),
                                    "value",
                                    cell.coordinates().get(a.guid())))
                            .toList(),
                    "defaultCell",
                    isDefault));
        }
        // Cardinality + distinct full tuples + declared values proves Cartesian completeness.
        return List.copyOf(rows);
    }

    static List<Map<String, Object>> projection(Object snapshot) {
        if (snapshot instanceof ArtMeshAuthoringSnapshot s) return McpAuthoringProjection.mesh(s);
        if (snapshot instanceof WarpAuthoringSnapshot s) return McpAuthoringProjection.warp(s);
        return McpAuthoringProjection.rotation((RotationAuthoringSnapshot) snapshot);
    }

    private static void add(Map<String, Axis> axes, Axis a) {
        if (axes.putIfAbsent(a.guid(), a) != null) throw new IllegalArgumentException();
    }

    static float zero(float v) {
        if (!Float.isFinite(v)) throw new IllegalArgumentException();
        return v == 0f ? 0f : v;
    }

    static final class Budget extends RuntimeException {}

    static final class Mismatch extends RuntimeException {}
}

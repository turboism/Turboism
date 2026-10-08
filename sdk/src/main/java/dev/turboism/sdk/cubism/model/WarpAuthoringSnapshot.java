package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.id.ParameterId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Detached ordinary Warp forms and their actual grid coordinates, never an evaluated form.
 * Shared form references remain visible; writers must reject ambiguous targets.
 * Morph-enabled Warp sources are outside this observation contract. */
public record WarpAuthoringSnapshot(DeformerId deformerId, String sourceGuid,
        int rows, int columns, boolean quadTransform, List<Axis> axes, List<Cell> cells, List<Form> forms) {
    public static final long MAX_CONTROL_SAMPLES = 20_000_000;
    public WarpAuthoringSnapshot {
        Objects.requireNonNull(deformerId, "deformerId");
        sourceGuid = identity(sourceGuid);
        if (rows < 1 || columns < 1) throw new IllegalArgumentException("positive Warp grid required");
        long count = ((long)rows + 1) * ((long)columns + 1);
        axes = List.copyOf(axes); cells = List.copyOf(cells); forms = List.copyOf(forms);
        if (count > MAX_CONTROL_SAMPLES || forms.size() > MAX_CONTROL_SAMPLES / count)
            throw new IllegalArgumentException("Warp stored control sample budget exceeded");
        var dimensions = new HashMap<String, Axis>();
        for (var axis : axes) if (dimensions.putIfAbsent(axis.parameterGuid(), axis) != null)
            throw new IllegalArgumentException("duplicate Warp axis GUID");
        var guids = new HashSet<String>();
        for (var form : forms) {
            if (!guids.add(form.formGuid()) || form.controlPoints().size() != count)
                throw new IllegalArgumentException("duplicate Warp form or wrong grid size");
        }
        for (var cell : cells) {
            if (!guids.contains(cell.formGuid())) throw new IllegalArgumentException("missing Warp form");
            var seen = new HashSet<String>();
            for (var coordinate : cell.coordinates()) {
                var axis = dimensions.get(coordinate.parameterGuid());
                if (axis == null || !seen.add(coordinate.parameterGuid()) || !axis.keys().contains(coordinate.value()))
                    throw new IllegalArgumentException("invalid Warp cell coordinate");
            }
            if (seen.size() != axes.size()) throw new IllegalArgumentException("incomplete Warp cell");
        }
    }
    /** A declared ordinary parameter dimension; GUIDs distinguish duplicate parameter IDs. */
    public record Axis(String parameterGuid, ParameterId parameterId, float minimum, float defaultValue,
            float maximum, List<Float> keys) {
        /** Validates detached GUID identity, parameter range and finite declared keys. */
        public Axis {
            parameterGuid = identity(parameterGuid); Objects.requireNonNull(parameterId, "parameterId");
            finite(minimum); finite(defaultValue); finite(maximum);
            if (minimum > defaultValue || defaultValue > maximum) throw new IllegalArgumentException("invalid Warp axis range");
            keys = List.copyOf(keys); keys.forEach(WarpAuthoringSnapshot::finite);
        }
    }
    /** One exact declared key, addressed by parameter GUID. */
    public record Coordinate(String parameterGuid, float value) {
        /** Validates a parameter GUID and finite key value. */
        public Coordinate { parameterGuid = identity(parameterGuid); finite(value); }
    }
    /** One actual grid row; several rows may reference one stored form. */
    public record Cell(String formGuid, List<Coordinate> coordinates) {
        /** Copies one actual grid row without resolving shared form references. */
        public Cell { formGuid = identity(formGuid); coordinates = List.copyOf(coordinates); }
    }
    /** Detached native stored control points, independent of live slider values. */
    public record Form(String formGuid, List<Point2> controlPoints) {
        /** Copies stored control points in native row-major order. */
        public Form { formGuid = identity(formGuid); controlPoints = List.copyOf(controlPoints); }
    }
    private static String identity(String value) {
        if (Objects.requireNonNull(value, "identity").isBlank()) throw new IllegalArgumentException("blank Warp identity");
        return value;
    }
    private static void finite(float value) {
        if (!Float.isFinite(value)) throw new IllegalArgumentException("nonfinite Warp value");
    }
}

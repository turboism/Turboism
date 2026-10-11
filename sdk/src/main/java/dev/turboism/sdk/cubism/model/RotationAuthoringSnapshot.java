package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.id.ParameterId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Detached ordinary Rotation forms and their exact parameter grid coordinates. */
@CubismEditor({"5.2.03", "5.3.02", "5.3.03"})
public record RotationAuthoringSnapshot(
        DeformerId deformerId,
        String sourceGuid,
        float baseAngle,
        List<Axis> axes,
        List<Cell> cells,
        List<Form> forms) {
    public static final int MAX_FORMS = 4096;

    public RotationAuthoringSnapshot {
        Objects.requireNonNull(deformerId, "deformerId");
        sourceGuid = identity(sourceGuid);
        finite(baseAngle);
        axes = List.copyOf(axes);
        cells = List.copyOf(cells);
        forms = List.copyOf(forms);
        if (forms.size() > MAX_FORMS)
            throw new IllegalArgumentException("Rotation stored control sample budget exceeded");
        var dimensions = new HashMap<String, Axis>();
        for (var axis : axes)
            if (dimensions.putIfAbsent(axis.parameterGuid(), axis) != null)
                throw new IllegalArgumentException("duplicate Rotation axis GUID");
        var guids = new HashSet<String>();
        for (var form : forms) {
            if (!guids.add(form.formGuid())) throw new IllegalArgumentException("duplicate Rotation form");
        }
        for (var cell : cells) {
            if (!guids.contains(cell.formGuid())) throw new IllegalArgumentException("missing Rotation form");
            var seen = new HashSet<String>();
            for (var coordinate : cell.coordinates()) {
                var axis = dimensions.get(coordinate.parameterGuid());
                if (axis == null
                        || !seen.add(coordinate.parameterGuid())
                        || !axis.keys().contains(coordinate.value()))
                    throw new IllegalArgumentException("invalid Rotation cell coordinate");
            }
            if (seen.size() != axes.size()) throw new IllegalArgumentException("incomplete Rotation cell");
        }
    }
    /** A declared ordinary parameter dimension; GUIDs distinguish duplicate parameter IDs. */
    public record Axis(
            String parameterGuid,
            ParameterId parameterId,
            float minimum,
            float defaultValue,
            float maximum,
            List<Float> keys) {
        /** Validates detached GUID identity, parameter range and finite declared keys. */
        public Axis {
            parameterGuid = identity(parameterGuid);
            Objects.requireNonNull(parameterId, "parameterId");
            finite(minimum);
            finite(defaultValue);
            finite(maximum);
            if (minimum > defaultValue || defaultValue > maximum)
                throw new IllegalArgumentException("invalid Rotation axis range");
            keys = List.copyOf(keys);
            keys.forEach(RotationAuthoringSnapshot::finite);
        }
    }
    /** One exact declared key, addressed by parameter GUID. */
    public record Coordinate(String parameterGuid, float value) {
        /** Validates a parameter GUID and finite key value. */
        public Coordinate {
            parameterGuid = identity(parameterGuid);
            finite(value);
        }
    }
    /** One actual grid row; several rows may reference one stored form. */
    public record Cell(String formGuid, List<Coordinate> coordinates) {
        /** Copies one actual grid row without resolving shared form references. */
        public Cell {
            formGuid = identity(formGuid);
            coordinates = List.copyOf(coordinates);
        }
    }
    /** Detached native stored rotation values, independent of live slider values. */
    public record Form(String formGuid, RotationDeformerForm value) {
        /** Validates the stored form identity and rotation values. */
        public Form {
            formGuid = identity(formGuid);
            Objects.requireNonNull(value, "value");
        }
    }

    private static String identity(String value) {
        if (Objects.requireNonNull(value, "identity").isBlank())
            throw new IllegalArgumentException("blank Rotation identity");
        return value;
    }

    private static void finite(float value) {
        if (!Float.isFinite(value)) throw new IllegalArgumentException("nonfinite Rotation value");
    }
}

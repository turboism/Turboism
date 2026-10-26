package dev.turboism.plugin.mcp;

import dev.turboism.sdk.cubism.model.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Explicit projections of stored authoring data. No reflection, evaluation or selected-form reads. */
final class McpAuthoringProjection {
    static final int MAX_ROWS = 100_000;

    private McpAuthoringProjection() {}

    static List<Map<String, Object>> mesh(ArtMeshAuthoringSnapshot snapshot) {
        var rows = new Rows();
        McpKeyformWritePlan.eligibility(snapshot).forEach(rows::add);
        points(rows, "source_position", "", snapshot.sourceGeometry().positions());
        points(rows, "source_uv", "", snapshot.sourceGeometry().uvs());
        var indices = snapshot.sourceGeometry().triangleIndices();
        for (int index = 0; index < indices.size(); index += 3)
            rows.add(Map.of(
                    "kind",
                    "source_triangle",
                    "index",
                    index / 3,
                    "a",
                    indices.get(index),
                    "b",
                    indices.get(index + 1),
                    "c",
                    indices.get(index + 2)));
        for (var axis : snapshot.axes())
            rows.add(axis(
                    axis.parameterGuid(),
                    axis.parameterId().value(),
                    axis.minimum(),
                    axis.defaultValue(),
                    axis.maximum(),
                    axis.keys()));
        for (var cell : snapshot.cells())
            rows.add(cell(
                    cell.formGuid(),
                    cell.coordinates().stream()
                            .map(c -> coordinate(c.parameterGuid(), c.value()))
                            .toList()));
        for (var form : snapshot.forms()) {
            var row = new LinkedHashMap<String, Object>();
            row.put("kind", "mesh_form");
            row.put("formGuid", form.formGuid());
            row.put("drawOrder", form.drawOrder());
            row.put("pointCount", form.positions().size());
            row.put("opacity", form.opacity().orElse(null));
            rows.add(java.util.Collections.unmodifiableMap(row));
            points(rows, "form_point", form.formGuid(), form.positions());
        }
        for (var morph : snapshot.morphKeys()) {
            var row = new LinkedHashMap<String, Object>();
            row.put("kind", "morph_key");
            row.put("parameterGuid", morph.parameterGuid());
            row.put("formGuid", morph.formGuid());
            row.put("keyValue", morph.keyValue().orElse(null));
            rows.add(java.util.Collections.unmodifiableMap(row));
        }
        return rows.finish();
    }

    static List<Map<String, Object>> warp(WarpAuthoringSnapshot snapshot) {
        var rows = new Rows();
        McpKeyformWritePlan.eligibility(snapshot).forEach(rows::add);
        rows.add(Map.of(
                "kind",
                "warp_grid",
                "rows",
                snapshot.rows(),
                "columns",
                snapshot.columns(),
                "quadTransform",
                snapshot.quadTransform()));
        for (var axis : snapshot.axes())
            rows.add(axis(
                    axis.parameterGuid(),
                    axis.parameterId().value(),
                    axis.minimum(),
                    axis.defaultValue(),
                    axis.maximum(),
                    axis.keys()));
        for (var cell : snapshot.cells())
            rows.add(cell(
                    cell.formGuid(),
                    cell.coordinates().stream()
                            .map(c -> coordinate(c.parameterGuid(), c.value()))
                            .toList()));
        for (var form : snapshot.forms()) {
            rows.add(Map.of(
                    "kind",
                    "warp_form",
                    "formGuid",
                    form.formGuid(),
                    "pointCount",
                    form.controlPoints().size()));
            points(rows, "form_point", form.formGuid(), form.controlPoints());
        }
        return rows.finish();
    }

    static List<Map<String, Object>> rotation(RotationAuthoringSnapshot snapshot) {
        var rows = new Rows();
        McpKeyformWritePlan.eligibility(snapshot).forEach(rows::add);
        rows.add(Map.of("kind", "rotation_source", "baseAngle", snapshot.baseAngle()));
        for (var axis : snapshot.axes())
            rows.add(axis(
                    axis.parameterGuid(),
                    axis.parameterId().value(),
                    axis.minimum(),
                    axis.defaultValue(),
                    axis.maximum(),
                    axis.keys()));
        for (var cell : snapshot.cells())
            rows.add(cell(
                    cell.formGuid(),
                    cell.coordinates().stream()
                            .map(c -> coordinate(c.parameterGuid(), c.value()))
                            .toList()));
        for (var form : snapshot.forms()) {
            var value = form.value();
            rows.add(Map.of(
                    "kind",
                    "rotation_form",
                    "formGuid",
                    form.formGuid(),
                    "angle",
                    value.angle(),
                    "originX",
                    value.originX(),
                    "originY",
                    value.originY(),
                    "scale",
                    value.scale(),
                    "reflectedX",
                    value.reflectedX(),
                    "reflectedY",
                    value.reflectedY()));
        }
        return rows.finish();
    }

    private static Map<String, Object> axis(
            String guid, String id, float minimum, float defaultValue, float maximum, List<Float> keys) {
        if (keys.size() > 4096) throw new BudgetExceeded("Axis key budget exceeded");
        return Map.of(
                "kind",
                "axis",
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

    private static Map<String, Object> coordinate(String guid, float value) {
        return Map.of("parameterGuid", guid, "value", value);
    }

    private static Map<String, Object> cell(String guid, List<Map<String, Object>> coordinates) {
        if (coordinates.size() > 64) throw new BudgetExceeded("Cell axis budget exceeded");
        return Map.of("kind", "cell", "formGuid", guid, "coordinates", coordinates);
    }

    private static void points(Rows rows, String kind, String guid, List<Point2> points) {
        if ((long) rows.values.size() + points.size() > MAX_ROWS)
            throw new BudgetExceeded("Snapshot row budget exceeded");
        for (int index = 0; index < points.size(); index++) {
            var point = points.get(index);
            if (guid.isEmpty()) rows.add(Map.of("kind", kind, "index", index, "x", point.x(), "y", point.y()));
            else rows.add(Map.of("kind", kind, "formGuid", guid, "index", index, "x", point.x(), "y", point.y()));
        }
    }

    private static final class Rows {
        final ArrayList<Map<String, Object>> values = new ArrayList<>();

        void add(Map<String, Object> value) {
            if (values.size() >= MAX_ROWS) throw new BudgetExceeded("Snapshot row budget exceeded");
            values.add(value);
        }

        List<Map<String, Object>> finish() {
            return List.copyOf(values);
        }
    }

    static final class BudgetExceeded extends IllegalArgumentException {
        BudgetExceeded(String message) {
            super(message);
        }
    }

    static Map<String, Object> rowSchema() {
        var alternatives = new ArrayList<Map<String, Object>>();
        for (String kind : List.of("source_position", "source_uv"))
            alternatives.add(row(kind, Map.of("index", integer(), "x", number(), "y", number())));
        alternatives.add(
                row("source_triangle", Map.of("index", integer(), "a", integer(), "b", integer(), "c", integer())));
        alternatives.add(
                row("form_point", Map.of("formGuid", string(), "index", integer(), "x", number(), "y", number())));
        alternatives.add(row(
                "axis",
                Map.of(
                        "parameterGuid",
                        string(),
                        "parameterId",
                        string(),
                        "minimum",
                        number(),
                        "defaultValue",
                        number(),
                        "maximum",
                        number(),
                        "keys",
                        Map.of("type", "array", "maxItems", 4096, "items", number()))));
        alternatives.add(row(
                "cell",
                Map.of(
                        "formGuid",
                        string(),
                        "coordinates",
                        Map.of(
                                "type",
                                "array",
                                "maxItems",
                                64,
                                "items",
                                object(Map.of("parameterGuid", string(), "value", number()))))));
        var supportFields = new LinkedHashMap<String, Object>();
        supportFields.put("hostVersion", Map.of("type", "string"));
        for (String field : List.of(
                "reviewedHost", "writerSelectors", "historySelectors", "transactionCoordinator", "writePermission"))
            supportFields.put(field, Map.of("enum", List.of("UNKNOWN", "PRESENT", "ABSENT")));
        var operations = new LinkedHashMap<String, Object>();
        for (var operation : OrdinaryKeyformWriteSupport.Operation.values())
            operations.put(operation.name(), Map.of("enum", List.of("UNKNOWN", "PRESENT", "ABSENT")));
        supportFields.put(
                "operationSelectors",
                Map.of("type", "object", "properties", operations, "additionalProperties", false));
        alternatives.add(row("ordinary_write_support", supportFields));
        alternatives.add(row(
                "ordinary_write_eligibility",
                Map.of(
                        "formGuid",
                        string(),
                        "snapshotEligible",
                        Map.of("type", "boolean"),
                        "reason",
                        Map.of(
                                "enum",
                                List.of(
                                        "INDEPENDENT_NON_DEFAULT_ORDINARY",
                                        "DEFAULT_FORM",
                                        "MORPH_REFERENCED_FORM",
                                        "NO_ORDINARY_CELL",
                                        "SHARED_FORM",
                                        "MISSING_DEFAULT_KEY_OR_AMBIGUOUS_AXIS",
                                        "DUPLICATE_CELL_TUPLE",
                                        "MISSING_EXACT_DEFAULT_CELL",
                                        "SHARED_OR_MORPH_DEFAULT_FORM")),
                        "hostWriteAdmission",
                        Map.of("const", "UNPROVEN"))));
        alternatives.add(row(
                "mesh_form",
                Map.of(
                        "formGuid",
                        string(),
                        "drawOrder",
                        Map.of("type", "integer"),
                        "pointCount",
                        integer(),
                        "opacity",
                        Map.of("type", List.of("number", "null")))));
        alternatives.add(row("warp_form", Map.of("formGuid", string(), "pointCount", integer())));
        alternatives.add(row(
                "warp_grid",
                Map.of("rows", integer(), "columns", integer(), "quadTransform", Map.of("type", "boolean"))));
        alternatives.add(row("rotation_source", Map.of("baseAngle", number())));
        alternatives.add(row(
                "rotation_form",
                Map.of(
                        "formGuid",
                        string(),
                        "angle",
                        number(),
                        "originX",
                        number(),
                        "originY",
                        number(),
                        "scale",
                        number(),
                        "reflectedX",
                        Map.of("type", "boolean"),
                        "reflectedY",
                        Map.of("type", "boolean"))));
        alternatives.add(row(
                "morph_key",
                Map.of(
                        "parameterGuid",
                        string(),
                        "formGuid",
                        string(),
                        "keyValue",
                        Map.of("type", List.of("number", "null")))));
        return Map.of("oneOf", alternatives);
    }

    private static Map<String, Object> row(String kind, Map<String, Object> fields) {
        var result = new LinkedHashMap<>(fields);
        result.put("kind", Map.of("const", kind));
        return object(result);
    }

    static Map<String, Object> object(Map<String, Object> fields) {
        return Map.of(
                "type",
                "object",
                "properties",
                fields,
                "required",
                List.copyOf(fields.keySet()),
                "additionalProperties",
                false);
    }

    static Map<String, Object> string() {
        return Map.of("type", "string", "minLength", 1, "maxLength", 4096);
    }

    static Map<String, Object> integer() {
        return Map.of("type", "integer", "minimum", 0);
    }

    private static Map<String, Object> number() {
        return Map.of("type", "number");
    }
}

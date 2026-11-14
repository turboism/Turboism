package dev.turboism.plugin.mcp;

import static dev.turboism.plugin.mcp.McpSchemas.*;

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
            alternatives.add(
                    row(kind, Map.of("index", nonNegativeInteger(), "x", numberSchema(), "y", numberSchema())));
        alternatives.add(row(
                "source_triangle",
                Map.of(
                        "index",
                        nonNegativeInteger(),
                        "a",
                        nonNegativeInteger(),
                        "b",
                        nonNegativeInteger(),
                        "c",
                        nonNegativeInteger())));
        alternatives.add(row(
                "form_point",
                Map.of("formGuid", id(), "index", nonNegativeInteger(), "x", numberSchema(), "y", numberSchema())));
        alternatives.add(row(
                "axis",
                Map.of(
                        "parameterGuid",
                        id(),
                        "parameterId",
                        id(),
                        "minimum",
                        numberSchema(),
                        "defaultValue",
                        numberSchema(),
                        "maximum",
                        numberSchema(),
                        "keys",
                        Map.of("type", "array", "maxItems", 4096, "items", numberSchema()))));
        alternatives.add(row(
                "cell",
                Map.of(
                        "formGuid",
                        id(),
                        "coordinates",
                        Map.of(
                                "type",
                                "array",
                                "maxItems",
                                64,
                                "items",
                                object(Map.of("parameterGuid", id(), "value", numberSchema()))))));
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
                        id(),
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
                        id(),
                        "drawOrder",
                        Map.of("type", "integer"),
                        "pointCount",
                        nonNegativeInteger(),
                        "opacity",
                        Map.of("type", List.of("number", "null")))));
        alternatives.add(row("warp_form", Map.of("formGuid", id(), "pointCount", nonNegativeInteger())));
        alternatives.add(row(
                "warp_grid",
                Map.of(
                        "rows",
                        nonNegativeInteger(),
                        "columns",
                        nonNegativeInteger(),
                        "quadTransform",
                        Map.of("type", "boolean"))));
        alternatives.add(row("rotation_source", Map.of("baseAngle", numberSchema())));
        alternatives.add(row(
                "rotation_form",
                Map.of(
                        "formGuid",
                        id(),
                        "angle",
                        numberSchema(),
                        "originX",
                        numberSchema(),
                        "originY",
                        numberSchema(),
                        "scale",
                        numberSchema(),
                        "reflectedX",
                        Map.of("type", "boolean"),
                        "reflectedY",
                        Map.of("type", "boolean"))));
        alternatives.add(row(
                "morph_key",
                Map.of(
                        "parameterGuid",
                        id(),
                        "formGuid",
                        id(),
                        "keyValue",
                        Map.of("type", List.of("number", "null")))));
        return Map.of("oneOf", alternatives);
    }

    private static Map<String, Object> row(String kind, Map<String, Object> fields) {
        var result = new LinkedHashMap<>(fields);
        result.put("kind", Map.of("const", kind));
        return object(result);
    }
}

package dev.turboism.plugin.mcp;

import dev.turboism.sdk.cubism.model.*;
import java.math.BigDecimal;
import java.util.*;

/** Pure preflight for ordinary stored writes. No SDK setters or UI state are touched here. */
final class McpKeyformWritePlan {
    static final int MAX_EDITS = 64;
    static final int MAX_POINTS = 16_384;

    sealed interface Plan permits Mesh, Warp, Rotation {
        Object before();

        Object after();
    }

    record Mesh(
            ArtMeshAuthoringSnapshot before,
            ArtMeshAuthoringSnapshot after,
            List<ArtMeshKeyformPositions> edits,
            List<ArtMeshKeyformDrawOrder> drawOrders,
            List<ArtMeshKeyformValues> values)
            implements Plan {}

    record Warp(WarpAuthoringSnapshot before, WarpAuthoringSnapshot after, List<WarpKeyformPositions> edits)
            implements Plan {}

    record Rotation(RotationAuthoringSnapshot before, RotationAuthoringSnapshot after, List<RotationKeyformForm> edits)
            implements Plan {}

    static Plan prepare(Object snapshot, Object rawEdits) {
        var edits = list(rawEdits, MAX_EDITS);
        if (snapshot instanceof ArtMeshAuthoringSnapshot mesh) return mesh(mesh, edits);
        if (snapshot instanceof WarpAuthoringSnapshot warp) return warp(warp, edits);
        if (snapshot instanceof RotationAuthoringSnapshot rotation) return rotation(rotation, edits);
        throw new IllegalArgumentException("Unsupported stored snapshot");
    }

    private static Mesh mesh(ArtMeshAuthoringSnapshot before, List<?> raw) {
        var grid = grid(before);
        var positions = new HashMap<String, List<Point2>>();
        var orders = new HashMap<String, Integer>();
        var opacities = new HashMap<String, Float>();
        var tuples = new LinkedHashMap<String, List<ArtMeshAuthoringSnapshot.Coordinate>>();
        var existing = new HashMap<String, ArtMeshAuthoringSnapshot.Form>();
        before.forms().forEach(f -> existing.put(f.formGuid(), f));
        for (Object value : raw) {
            if (!(value instanceof Map<?, ?> candidate)) throw new IllegalArgumentException("Invalid mesh edit");
            boolean hasPositions = candidate.containsKey("positions"),
                    hasOrder = candidate.containsKey("drawOrder"),
                    hasOpacity = candidate.containsKey("opacity");
            if (!hasPositions && !hasOrder && !hasOpacity)
                throw new IllegalArgumentException("Mesh edit requires positions, drawOrder or opacity");
            var keys = new ArrayList<>(List.of("formGuid", "coordinates"));
            if (hasPositions) keys.add("positions");
            if (hasOrder) keys.add("drawOrder");
            if (hasOpacity) keys.add("opacity");
            var edit = fields(value, keys.toArray(String[]::new));
            var coordinates = coordinates(edit.get("coordinates"));
            String guid = grid.target(edit, coordinates);
            if (!existing.containsKey(guid)) throw new IllegalArgumentException("Missing stored form");
            tuples.put(
                    guid,
                    coordinates.entrySet().stream()
                            .map(c -> new ArtMeshAuthoringSnapshot.Coordinate(c.getKey(), c.getValue()))
                            .toList());
            if (hasPositions) {
                var points = points(edit.get("positions"));
                if (points.size() != before.sourceGeometry().positions().size())
                    throw new IllegalArgumentException("Wrong vertex count");
                positions.put(guid, points);
            }
            if (hasOrder) orders.put(guid, drawOrder(edit.get("drawOrder")));
            if (hasOpacity) {
                float requestedOpacity = opacity(edit.get("opacity"));
                float old = existing.get(guid)
                        .opacity()
                        .orElseThrow(() -> new UnsupportedOperationException("Stored ordinary opacity is unknown"));
                if (old < 0 || old > 1) throw new UnsupportedOperationException("Unsupported stored ordinary opacity");
                opacities.put(guid, requestedOpacity);
            }
        }
        // Combined SDK writes require identical form sets. Fill omitted fields from the
        // immutable expected state, never from a fresh pose or a partially written object.
        var edits = new ArrayList<ArtMeshKeyformPositions>();
        var drawOrders = new ArrayList<ArtMeshKeyformDrawOrder>();
        var values = new ArrayList<ArtMeshKeyformValues>();
        int total = 0;
        for (var entry : tuples.entrySet()) {
            String guid = entry.getKey();
            var old = existing.get(guid);
            if (!positions.isEmpty() || !opacities.isEmpty()) {
                var replacement = positions.getOrDefault(guid, old.positions());
                total = pointBudget(total, replacement.size());
                edits.add(new ArtMeshKeyformPositions(entry.getValue(), guid, replacement));
            }
            if (!orders.isEmpty())
                drawOrders.add(new ArtMeshKeyformDrawOrder(
                        entry.getValue(), guid, orders.getOrDefault(guid, old.drawOrder())));
            if (!opacities.isEmpty())
                values.add(new ArtMeshKeyformValues(
                        entry.getValue(),
                        guid,
                        java.util.Optional.ofNullable(positions.get(guid)),
                        java.util.Optional.ofNullable(orders.get(guid)),
                        java.util.Optional.ofNullable(opacities.get(guid))));
        }
        var after = new ArtMeshAuthoringSnapshot(
                before.artMeshId(),
                before.sourceGuid(),
                before.sourceGeometry(),
                before.axes(),
                before.cells(),
                before.forms().stream()
                        .map(f -> new ArtMeshAuthoringSnapshot.Form(
                                f.formGuid(),
                                positions.getOrDefault(f.formGuid(), f.positions()),
                                orders.getOrDefault(f.formGuid(), f.drawOrder()),
                                opacities.containsKey(f.formGuid())
                                        ? java.util.Optional.of(opacities.get(f.formGuid()))
                                        : f.opacity()))
                        .toList(),
                before.morphKeys());
        return new Mesh(before, after, List.copyOf(edits), List.copyOf(drawOrders), List.copyOf(values));
    }

    private static float opacity(Object raw) {
        if (!(raw instanceof Number n))
            throw new IllegalArgumentException("Ordinary opacity must be a finite number in 0..1");
        try {
            var exact = new BigDecimal(n.toString());
            if (exact.signum() < 0 || exact.compareTo(BigDecimal.ONE) > 0)
                throw new IllegalArgumentException("Ordinary opacity outside 0..1");
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Ordinary opacity must be finite");
        }
        return number(raw);
    }

    private static int drawOrder(Object raw) {
        if (!(raw instanceof Number number)) throw new IllegalArgumentException("Draw order must be an integer");
        try {
            int value = new BigDecimal(number.toString()).intValueExact();
            if (value < 0 || value > 1000) throw new IllegalArgumentException("Draw order outside 0..1000");
            return value;
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException("Draw order must be an integer", failure);
        }
    }

    private static Warp warp(WarpAuthoringSnapshot before, List<?> raw) {
        var grid = grid(before);
        var edits = new ArrayList<WarpKeyformPositions>();
        var replacements = new HashMap<String, List<Point2>>();
        int total = 0;
        for (Object value : raw) {
            var edit = fields(value, "formGuid", "coordinates", "controlPoints");
            var coordinates = coordinates(edit.get("coordinates"));
            String guid = grid.target(edit, coordinates);
            var points = points(edit.get("controlPoints"));
            if (points.size() != ((long) before.rows() + 1) * ((long) before.columns() + 1))
                throw new IllegalArgumentException("Wrong control point count");
            total = pointBudget(total, points.size());
            replacements.put(guid, points);
            edits.add(new WarpKeyformPositions(
                    coordinates.entrySet().stream()
                            .map(c -> new WarpAuthoringSnapshot.Coordinate(c.getKey(), c.getValue()))
                            .toList(),
                    guid,
                    points));
        }
        var after = new WarpAuthoringSnapshot(
                before.deformerId(),
                before.sourceGuid(),
                before.rows(),
                before.columns(),
                before.quadTransform(),
                before.axes(),
                before.cells(),
                before.forms().stream()
                        .map(f -> new WarpAuthoringSnapshot.Form(
                                f.formGuid(), replacements.getOrDefault(f.formGuid(), f.controlPoints())))
                        .toList());
        return new Warp(before, after, List.copyOf(edits));
    }

    private static Rotation rotation(RotationAuthoringSnapshot before, List<?> raw) {
        var grid = grid(before);
        var edits = new ArrayList<RotationKeyformForm>();
        var replacements = new HashMap<String, RotationDeformerForm>();
        for (Object value : raw) {
            var edit = fields(value, "formGuid", "coordinates", "form");
            var coordinates = coordinates(edit.get("coordinates"));
            String guid = grid.target(edit, coordinates);
            var form = fields(edit.get("form"), "angle", "originX", "originY", "scale", "reflectedX", "reflectedY");
            var replacement = new RotationDeformerForm(
                    number(form.get("angle")),
                    number(form.get("originX")),
                    number(form.get("originY")),
                    number(form.get("scale")),
                    bool(form.get("reflectedX")),
                    bool(form.get("reflectedY")));
            replacements.put(guid, replacement);
            edits.add(new RotationKeyformForm(
                    coordinates.entrySet().stream()
                            .map(c -> new RotationAuthoringSnapshot.Coordinate(c.getKey(), c.getValue()))
                            .toList(),
                    guid,
                    replacement));
        }
        var after = new RotationAuthoringSnapshot(
                before.deformerId(),
                before.sourceGuid(),
                before.baseAngle(),
                before.axes(),
                before.cells(),
                before.forms().stream()
                        .map(f -> new RotationAuthoringSnapshot.Form(
                                f.formGuid(), replacements.getOrDefault(f.formGuid(), f.value())))
                        .toList());
        return new Rotation(before, after, List.copyOf(edits));
    }

    private static Grid grid(Object snapshot) {
        var grid = new Grid();
        if (snapshot instanceof ArtMeshAuthoringSnapshot mesh) {
            mesh.axes().forEach(a -> grid.axis(a.parameterGuid(), a.defaultValue(), a.keys()));
            mesh.cells().forEach(c -> grid.cell(c.formGuid(), tupleMesh(c.coordinates())));
            mesh.morphKeys().forEach(m -> {
                grid.references.merge(m.formGuid(), 1, Integer::sum);
                grid.morphForms.add(m.formGuid());
            });
        } else if (snapshot instanceof WarpAuthoringSnapshot warp) {
            warp.axes().forEach(a -> grid.axis(a.parameterGuid(), a.defaultValue(), a.keys()));
            warp.cells().forEach(c -> grid.cell(c.formGuid(), tupleWarp(c.coordinates())));
        } else if (snapshot instanceof RotationAuthoringSnapshot rotation) {
            rotation.axes().forEach(a -> grid.axis(a.parameterGuid(), a.defaultValue(), a.keys()));
            rotation.cells().forEach(c -> grid.cell(c.formGuid(), tupleRotation(c.coordinates())));
        } else throw new IllegalArgumentException("Unsupported stored snapshot");
        grid.validate();
        return grid;
    }

    /** Snapshot rules only, not host/version/permission/transaction admission. */
    static List<Map<String, Object>> eligibility(Object snapshot) {
        List<String> forms;
        if (snapshot instanceof ArtMeshAuthoringSnapshot mesh)
            forms = mesh.forms().stream()
                    .map(ArtMeshAuthoringSnapshot.Form::formGuid)
                    .toList();
        else if (snapshot instanceof WarpAuthoringSnapshot warp)
            forms = warp.forms().stream()
                    .map(WarpAuthoringSnapshot.Form::formGuid)
                    .toList();
        else if (snapshot instanceof RotationAuthoringSnapshot rotation)
            forms = rotation.forms().stream()
                    .map(RotationAuthoringSnapshot.Form::formGuid)
                    .toList();
        else throw new IllegalArgumentException("Unsupported stored snapshot");
        Grid valid;
        String failure = null;
        try {
            valid = grid(snapshot);
        } catch (GridRejected rejected) {
            valid = null;
            failure = rejected.code;
        }
        final Grid grid = valid;
        final String problem = failure;
        return forms.stream()
                .map(guid -> {
                    String reason = grid == null ? problem : grid.rejection(guid);
                    return Map.<String, Object>of(
                            "kind",
                            "ordinary_write_eligibility",
                            "formGuid",
                            guid,
                            "snapshotEligible",
                            reason == null,
                            "reason",
                            reason == null ? "INDEPENDENT_NON_DEFAULT_ORDINARY" : reason,
                            "hostWriteAdmission",
                            "UNPROVEN");
                })
                .toList();
    }

    private static final class GridRejected extends IllegalArgumentException {
        final String code;

        GridRejected(String code) {
            super(code);
            this.code = code;
        }
    }

    /** Exact declared defaults and independent cell references, never nearest/first-key inference. */
    private static final class Grid {
        final Map<String, Float> defaults = new HashMap<>();
        final Map<Map<String, Float>, String> cells = new HashMap<>();
        final Map<String, Integer> references = new HashMap<>();
        final Set<String> edited = new HashSet<>();
        final Set<String> morphForms = new HashSet<>();
        String defaultForm;

        void axis(String guid, float defaultValue, List<Float> keys) {
            if (!keys.contains(defaultValue)
                    || new HashSet<>(keys).size() != keys.size()
                    || defaults.putIfAbsent(guid, defaultValue) != null)
                throw new GridRejected("MISSING_DEFAULT_KEY_OR_AMBIGUOUS_AXIS");
        }

        void cell(String guid, Map<String, Float> tuple) {
            if (cells.putIfAbsent(tuple, guid) != null) throw new GridRejected("DUPLICATE_CELL_TUPLE");
            references.merge(guid, 1, Integer::sum);
        }

        void validate() {
            defaultForm = cells.get(defaults);
            if (defaultForm == null) throw new GridRejected("MISSING_EXACT_DEFAULT_CELL");
            if (references.get(defaultForm) != 1) throw new GridRejected("SHARED_OR_MORPH_DEFAULT_FORM");
        }

        String rejection(String guid) {
            if (guid.equals(defaultForm)) return "DEFAULT_FORM";
            if (morphForms.contains(guid)) return "MORPH_REFERENCED_FORM";
            int referencesCount = references.getOrDefault(guid, 0);
            if (referencesCount == 0) return "NO_ORDINARY_CELL";
            if (referencesCount != 1) return "SHARED_FORM";
            return null;
        }

        String target(Map<String, Object> edit, Map<String, Float> coordinates) {
            String guid = text(edit.get("formGuid"));
            if (!coordinates.keySet().equals(defaults.keySet())
                    || !guid.equals(cells.get(coordinates))
                    || rejection(guid) != null
                    || !edited.add(guid))
                throw new IllegalArgumentException(
                        "Incomplete, missing, shared, default, morph or duplicate form target");
            return guid;
        }
    }

    private static Map<String, Float> tupleMesh(List<ArtMeshAuthoringSnapshot.Coordinate> coordinates) {
        var tuple = new HashMap<String, Float>();
        coordinates.forEach(c -> put(tuple, c.parameterGuid(), c.value()));
        return Map.copyOf(tuple);
    }

    private static Map<String, Float> tupleWarp(List<WarpAuthoringSnapshot.Coordinate> coordinates) {
        var tuple = new HashMap<String, Float>();
        coordinates.forEach(c -> put(tuple, c.parameterGuid(), c.value()));
        return Map.copyOf(tuple);
    }

    private static Map<String, Float> tupleRotation(List<RotationAuthoringSnapshot.Coordinate> coordinates) {
        var tuple = new HashMap<String, Float>();
        coordinates.forEach(c -> put(tuple, c.parameterGuid(), c.value()));
        return Map.copyOf(tuple);
    }

    private static Map<String, Float> coordinates(Object raw) {
        var result = new LinkedHashMap<String, Float>();
        for (Object value : list(raw, 64)) {
            var c = fields(value, "parameterGuid", "value");
            put(result, text(c.get("parameterGuid")), keyNumber(c.get("value")));
        }
        return Collections.unmodifiableMap(result);
    }

    private static void put(Map<String, Float> tuple, String guid, float value) {
        if (tuple.putIfAbsent(guid, value) != null) throw new IllegalArgumentException("Duplicate axis");
    }

    private static List<Point2> points(Object raw) {
        var result = new ArrayList<Point2>();
        for (Object value : list(raw, MAX_POINTS)) {
            var p = fields(value, "x", "y");
            result.add(new Point2(number(p.get("x")), number(p.get("y"))));
        }
        return List.copyOf(result);
    }

    private static int pointBudget(int previous, int count) {
        if (previous + count > MAX_POINTS) throw new IllegalArgumentException("Write point budget exceeded");
        return previous + count;
    }

    private static List<?> list(Object raw, int max) {
        if (!(raw instanceof List<?> list) || list.isEmpty() || list.size() > max)
            throw new IllegalArgumentException("Invalid or excessive list");
        return list;
    }

    private static Map<String, Object> fields(Object raw, String... fields) {
        if (!(raw instanceof Map<?, ?> map) || !map.keySet().equals(Set.of(fields)))
            throw new IllegalArgumentException("Missing or unknown field");
        var result = new LinkedHashMap<String, Object>();
        map.forEach((k, v) -> result.put((String) k, v));
        return result;
    }

    private static String text(Object raw) {
        if (!(raw instanceof String value) || value.isBlank() || value.length() > 4096)
            throw new IllegalArgumentException("Invalid identity");
        return value;
    }

    private static float number(Object raw) {
        if (!(raw instanceof Number number)) throw new IllegalArgumentException("Expected numeric coordinate");
        float result = number.floatValue();
        if (!Float.isFinite(result)) throw new IllegalArgumentException("Nonfinite coordinate");
        return result;
    }

    private static float keyNumber(Object raw) {
        float result = number(raw);
        Number number = (Number) raw;
        // A distinct JSON tuple must not silently round onto another declared native float key.
        if (new BigDecimal(number.toString()).compareTo(new BigDecimal(Float.toString(result))) != 0)
            throw new IllegalArgumentException("Coordinate must round-trip through native float text");
        return result;
    }

    private static boolean bool(Object raw) {
        if (!(raw instanceof Boolean value)) throw new IllegalArgumentException("Expected boolean");
        return value;
    }
}

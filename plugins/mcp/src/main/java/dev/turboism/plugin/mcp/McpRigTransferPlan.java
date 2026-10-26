package dev.turboism.plugin.mcp;

import dev.turboism.sdk.cubism.model.*;
import java.util.*;

/** Explicit tuple and point correspondences; copies stored deltas, never bindings or inferred anatomy. */
final class McpRigTransferPlan {
    static final int MAX_CELLS = 65, MAX_STORED_SAMPLES = 262144, MAX_PLANNED_POINTS = 4096;

    record Key(float source, float target) {
        Key {
            source = McpBindingGridSnapshot.zero(source);
            target = McpBindingGridSnapshot.zero(target);
        }
    }

    record Axis(String sourceGuid, String targetGuid, List<Key> keys) {
        Axis {
            McpKeyformContinuity.identity(sourceGuid);
            McpKeyformContinuity.identity(targetGuid);
            keys = List.copyOf(keys);
            if (keys.size() < 2 || keys.size() > 65) throw new IllegalArgumentException();
        }
    }

    record Transform(double xx, double xy, double yx, double yy) {
        Transform {
            for (double v : new double[] {xx, xy, yx, yy})
                if (!Double.isFinite(v) || Math.abs(v) > 1e6) throw new IllegalArgumentException();
            if (xx * yy - xy * yx == 0) throw new IllegalArgumentException();
        }

        Point2 point(Point2 targetDefault, Point2 sourceDefault, Point2 source) {
            double x = (double) source.x() - sourceDefault.x(), y = (double) source.y() - sourceDefault.y();
            return new Point2(
                    nativeFloat(targetDefault.x() + xx * x + xy * y), nativeFloat(targetDefault.y() + yx * x + yy * y));
        }
    }

    record Grid(
            McpBindingGridSnapshot.View view,
            Map<Map<String, Float>, McpBindingGridSnapshot.Cell> cells,
            McpBindingGridSnapshot.Cell defaultCell) {}

    record Result(
            List<Map<String, Object>> edits,
            String sourceDefaultFormGuid,
            String targetDefaultFormGuid,
            int sourceCells,
            int targetCells,
            int plannedPoints) {}

    static Result plan(
            Object source,
            Object target,
            List<Axis> mapping,
            Transform transform,
            List<Integer> pointMap,
            Double angleGain) {
        if (!source.getClass().equals(target.getClass())) throw new UnsupportedOperationException();
        var s = grid(source);
        var t = grid(target);
        if ((long) s.view().points() * s.view().forms().size()
                        + (long) t.view().points() * t.view().forms().size()
                > MAX_STORED_SAMPLES) throw new Budget();
        if (s.view().axes().size() != mapping.size() || t.view().axes().size() != mapping.size())
            throw new IllegalArgumentException();
        var byTarget = new HashMap<String, Axis>();
        var sourceGuids = new HashSet<String>();
        var sourceValues = new HashMap<String, Map<Float, Float>>();
        for (var axis : mapping) {
            var sa = s.view().axes().get(axis.sourceGuid());
            var ta = t.view().axes().get(axis.targetGuid());
            if (sa == null
                    || ta == null
                    || !sourceGuids.add(axis.sourceGuid())
                    || byTarget.putIfAbsent(axis.targetGuid(), axis) != null) throw new IllegalArgumentException();
            var sourceKeys = new HashSet<Float>();
            var targetKeys = new HashMap<Float, Float>();
            for (var key : axis.keys())
                if (!sourceKeys.add(key.source()) || targetKeys.putIfAbsent(key.target(), key.source()) != null)
                    throw new IllegalArgumentException();
            if (!sourceKeys.equals(new HashSet<>(sa.keys()))
                    || !targetKeys.keySet().equals(new HashSet<>(ta.keys()))
                    || !Objects.equals(targetKeys.get(ta.defaultValue()), sa.defaultValue()))
                throw new IllegalArgumentException();
            sourceValues.put(axis.targetGuid(), Map.copyOf(targetKeys));
        }
        boolean rotation = source instanceof RotationAuthoringSnapshot;
        if (rotation) {
            if (!pointMap.isEmpty() || angleGain == null || !Double.isFinite(angleGain) || Math.abs(angleGain) > 1000)
                throw new IllegalArgumentException();
        } else {
            if (angleGain != null
                    || s.view().points() != t.view().points()
                    || pointMap.size() != t.view().points()) throw new IllegalArgumentException();
            var seen = new HashSet<Integer>();
            for (int p : pointMap)
                if (p < 0 || p >= s.view().points() || !seen.add(p)) throw new IllegalArgumentException();
        }
        var sd = s.view().forms().get(s.defaultCell().guid());
        var td = t.view().forms().get(t.defaultCell().guid());
        int plannedPoints = (t.cells().size() - 1) * t.view().points();
        if (plannedPoints > MAX_PLANNED_POINTS) throw new Budget();
        var targetAxes = t.view().axes().keySet().stream().sorted().toList();
        var cells = t.view().cells().stream()
                .sorted((a, b) -> {
                    for (String guid : targetAxes) {
                        int c = Float.compare(
                                a.coordinates().get(guid), b.coordinates().get(guid));
                        if (c != 0) return c;
                    }
                    return 0;
                })
                .toList();
        var edits = new ArrayList<Map<String, Object>>();
        for (var cell : cells) {
            if (cell.coordinates().equals(t.defaultCell().coordinates())) continue;
            var tuple = new HashMap<String, Float>();
            for (String targetGuid : targetAxes)
                tuple.put(
                        byTarget.get(targetGuid).sourceGuid(),
                        sourceValues.get(targetGuid).get(cell.coordinates().get(targetGuid)));
            var sourceCell = s.cells().get(tuple);
            if (sourceCell == null) throw new IllegalArgumentException();
            var payload = s.view().forms().get(sourceCell.guid());
            var edit = new LinkedHashMap<String, Object>();
            edit.put("formGuid", cell.guid());
            edit.put(
                    "coordinates",
                    targetAxes.stream()
                            .map(guid -> Map.of(
                                    "parameterGuid",
                                    guid,
                                    "value",
                                    cell.coordinates().get(guid)))
                            .toList());
            if (rotation) {
                var a = sd.rotation();
                var b = payload.rotation();
                var d = td.rotation();
                var pivot = transform.point(d.origin(), a.origin(), b.origin());
                var form = new RotationDeformerForm(
                        nativeFloat(d.angle() + angleGain * ((double) b.angle() - a.angle())),
                        pivot.x(),
                        pivot.y(),
                        nativeFloat(d.scale() * ((double) b.scale() / a.scale())),
                        d.reflectedX() ^ a.reflectedX() ^ b.reflectedX(),
                        d.reflectedY() ^ a.reflectedY() ^ b.reflectedY());
                edit.put(
                        "form",
                        Map.of(
                                "angle",
                                form.angle(),
                                "originX",
                                form.originX(),
                                "originY",
                                form.originY(),
                                "scale",
                                form.scale(),
                                "reflectedX",
                                form.reflectedX(),
                                "reflectedY",
                                form.reflectedY()));
            } else {
                var positions = new ArrayList<Map<String, Object>>();
                for (int i = 0; i < pointMap.size(); i++) {
                    int from = pointMap.get(i);
                    var p = transform.point(
                            td.points().get(i),
                            sd.points().get(from),
                            payload.points().get(from));
                    positions.add(Map.of("x", p.x(), "y", p.y()));
                }
                edit.put(
                        source instanceof ArtMeshAuthoringSnapshot ? "positions" : "controlPoints",
                        List.copyOf(positions));
            }
            edits.add(Collections.unmodifiableMap(edit));
        }
        // Target default/shared/Morph protection and complete per-type typed field validation remain authoritative.
        McpKeyformWritePlan.prepare(target, edits);
        return new Result(
                List.copyOf(edits),
                s.defaultCell().guid(),
                t.defaultCell().guid(),
                s.cells().size(),
                t.cells().size(),
                plannedPoints);
    }

    private static Grid grid(Object snapshot) {
        var v = McpBindingGridSnapshot.view(snapshot, MAX_CELLS, MAX_STORED_SAMPLES);
        if (v.axes().isEmpty() || v.axes().size() > 3) throw new UnsupportedOperationException();
        long count = 1;
        var defaults = new HashMap<String, Float>();
        for (var axis : v.axes().values()) {
            McpKeyformContinuity.identity(axis.guid());
            McpKeyformContinuity.identity(axis.id());
            if (axis.keys().size() < 2
                    || axis.keys().size() > 65
                    || !axis.keys().contains(axis.defaultValue())
                    || axis.keys().get(0) < axis.minimum()
                    || axis.keys().get(axis.keys().size() - 1) > axis.maximum()) throw new IllegalArgumentException();
            count *= axis.keys().size();
            defaults.put(axis.guid(), axis.defaultValue());
        }
        if (count > MAX_CELLS) throw new Budget();
        var cells = new HashMap<Map<String, Float>, McpBindingGridSnapshot.Cell>();
        var used = new HashSet<String>();
        for (var cell : v.cells()) {
            McpKeyformContinuity.identity(cell.guid());
            if (!cell.coordinates().keySet().equals(v.axes().keySet())
                    || cells.putIfAbsent(cell.coordinates(), cell) != null
                    || !v.forms().containsKey(cell.guid())) throw new IllegalArgumentException();
            for (var axis : v.axes().values())
                if (!axis.keys().contains(cell.coordinates().get(axis.guid()))) throw new IllegalArgumentException();
            used.add(cell.guid());
        }
        if (count != cells.size() || !used.equals(v.forms().keySet()) || !cells.containsKey(defaults))
            throw new UnsupportedOperationException();
        return new Grid(v, Map.copyOf(cells), cells.get(defaults));
    }

    private static float nativeFloat(double value) {
        float f = (float) value;
        if (!Float.isFinite(f)) throw new IllegalArgumentException();
        return f;
    }

    static final class Budget extends RuntimeException {}
}

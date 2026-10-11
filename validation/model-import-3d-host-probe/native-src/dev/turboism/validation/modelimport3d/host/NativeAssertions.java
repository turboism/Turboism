package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot;
import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.Point2;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Fixed N2 expectations; no evaluated-geometry or morph-write claims. */
final class NativeAssertions {
    static final List<Float> KEYS = List.of(-1f, 0f, 1f);
    static final ArtMeshGeometry GEOMETRY = new ArtMeshGeometry(
        List.of(new Point2(16, 24), new Point2(272, 24), new Point2(272, 152), new Point2(16, 152)),
        List.of(new Point2(0, 0), new Point2(1, 0), new Point2(1, 1), new Point2(0, 1)),
        List.of(0, 1, 2, 0, 2, 3));

    static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalStateException(reason);
    }

    static void created(ArtMeshAuthoringSnapshot snapshot) {
        require(snapshot.sourceGeometry().equals(GEOMETRY), "source geometry differs from fixed input");
        require(snapshot.axes().isEmpty(), "created mesh has unexpected bindings");
        require(!snapshot.forms().isEmpty(), "created mesh has no stored form");
        require(snapshot.forms().stream().allMatch(f -> f.positions().equals(GEOMETRY.positions())),
            "created form geometry differs from fixed input");
        require(snapshot.morphKeys().isEmpty(), "unexpected morph keys");
    }

    static void grid(ArtMeshAuthoringSnapshot snapshot, String xId, String yId) {
        require(snapshot.sourceGeometry().equals(GEOMETRY), "grid changed source geometry");
        require(snapshot.axes().size() == 2, "expected two axes");
        var byId = new LinkedHashMap<String, String>();
        for (var axis : snapshot.axes()) {
            require(axis.minimum() == -1f && axis.defaultValue() == 0f && axis.maximum() == 1f,
                "axis range/default differs");
            require(axis.keys().size() == 3 && Set.copyOf(axis.keys()).equals(Set.copyOf(KEYS)), "axis keys differ");
            require(byId.put(axis.parameterId().value(), axis.parameterGuid()) == null, "duplicate parameter ID");
        }
        require(byId.keySet().equals(Set.of(xId, yId)), "unexpected parameter identity");
        var observed = new TreeSet<String>();
        for (var cell : snapshot.cells()) {
            var tuple = new LinkedHashMap<String, Float>();
            for (var coordinate : cell.coordinates()) {
                require(tuple.put(coordinate.parameterGuid(), coordinate.value()) == null, "duplicate coordinate");
            }
            require(tuple.keySet().equals(Set.copyOf(byId.values())), "incomplete tuple");
            observed.add(tuple.get(byId.get(xId)) + "," + tuple.get(byId.get(yId)));
        }
        var expected = new TreeSet<String>();
        for (float x : KEYS) for (float y : KEYS) expected.add(x + "," + y);
        require(snapshot.cells().size() == 9 && observed.equals(expected), "grid is not the complete nine tuples");
        require(snapshot.forms().stream().allMatch(f -> f.positions().equals(GEOMETRY.positions())),
            "binding changed stored positions");
        require(snapshot.morphKeys().isEmpty(), "binding created unexpected morph keys");
        // Several cells may share one stored form: observed, not treated as independently writable.
    }

    static Map<String, Object> snapshot(ArtMeshAuthoringSnapshot s) {
        var result = new LinkedHashMap<String, Object>();
        result.put("artMeshId", s.artMeshId().value()); result.put("sourceGuid", s.sourceGuid());
        result.put("positions", points(s.sourceGeometry().positions()));
        result.put("uvs", points(s.sourceGeometry().uvs()));
        result.put("indices", s.sourceGeometry().triangleIndices());
        result.put("axes", s.axes().stream().map(a -> Map.of(
            "guid", a.parameterGuid(), "id", a.parameterId().value(), "minimum", a.minimum(),
            "default", a.defaultValue(), "maximum", a.maximum(), "keys", a.keys())).toList());
        result.put("cells", s.cells().stream().map(c -> Map.of("formGuid", c.formGuid(), "coordinates",
            c.coordinates().stream().map(v -> Map.of("guid", v.parameterGuid(), "value", v.value())).toList())).toList());
        result.put("forms", s.forms().stream().map(f -> Map.of("guid", f.formGuid(), "positions",
            points(f.positions()), "drawOrder", f.drawOrder())).toList());
        var morph = new ArrayList<Map<String, Object>>();
        for (var key : s.morphKeys()) {
            var row = new LinkedHashMap<String, Object>();
            row.put("parameterGuid", key.parameterGuid()); row.put("formGuid", key.formGuid());
            row.put("keyValue", key.keyValue().orElse(null)); row.put("basicIdentity", "UNKNOWN"); morph.add(row);
        }
        result.put("morphKeys", morph);
        return result;
    }

    private static List<List<Float>> points(List<Point2> points) {
        return points.stream().map(p -> List.of(p.x(), p.y())).toList();
    }
}

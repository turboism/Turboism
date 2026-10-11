package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot;
import dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.Coordinate;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.Point2;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/** Precomputes a complete joint-pose bake before the caller's native transaction. */
final class SurfaceKeyformBake {
    private SurfaceKeyformBake() {}

    record Target(List<Coordinate> coordinates, String formGuid, boolean defaultForm, List<Point2> positions) {
        Target { coordinates = List.copyOf(coordinates); positions = List.copyOf(positions); }
    }

    record Plan(ArtMeshAuthoringSnapshot before, List<Target> targets, ArtMeshAuthoringSnapshot expected) {
        Plan { targets = List.copyOf(targets); }

        /** Must run within the caller's EDT authoring transaction. Does not claim batch atomicity. */
        void write(Drawable mesh) {
            require(mesh.authoringSnapshot().equals(before), "bake source/grid changed before write");
            mesh.setOrdinaryKeyformPositions(before, targets.stream().filter(t -> !t.defaultForm())
                .map(t -> new dev.turboism.sdk.cubism.model.ArtMeshKeyformPositions(
                    t.coordinates(), t.formGuid(), t.positions())).toList());
        }

        void verify(Drawable mesh) {
            require(mesh.authoringSnapshot().equals(expected), "bake readback/protected state mismatch");
        }
    }

    static Plan compile(ArtMeshAuthoringSnapshot before, ParameterId yaw, ParameterId pitch,
            List<SurfaceProjection.Vertex> source, SurfaceProjection.Camera camera, SurfaceKeyPlan.Grid grid) {
        require(!yaw.equals(pitch), "joint axes must be distinct");
        require(before.axes().size() == 2 && before.morphKeys().isEmpty(), "bake requires exactly two ordinary axes");
        var ya = axis(before, yaw, grid.yawKeys());
        var pa = axis(before, pitch, grid.pitchKeys());
        var zero = SurfaceProjection.project(source, camera, new SurfaceProjection.Pose(0, 0)).positions();
        // Texture-source positions may be unfolded image coordinates. Only the default
        // ordinary form must equal the neutral 3D projection; it is never edited here.
        require(before.sourceGeometry().positions().size()==zero.size(), "source vertex correspondence differs");
        int count = Math.multiplyExact(ya.keys().size(), pa.keys().size());
        require(count <= 4096 && before.cells().size() == count && before.forms().size() == count,
            "joint grid incomplete or exceeds bake budget");
        var cells = new HashMap<List<Coordinate>, String>();
        var guids = new HashSet<String>();
        for (var cell : before.cells()) {
            require(cells.putIfAbsent(normalize(cell.coordinates()), cell.formGuid()) == null
                && guids.add(cell.formGuid()), "duplicate tuple or shared keyform");
        }
        var forms = new HashMap<String, ArtMeshAuthoringSnapshot.Form>();
        before.forms().forEach(f -> forms.put(f.formGuid(), f));
        var targets = new ArrayList<Target>(count);
        var replacements = new HashMap<String, List<Point2>>();
        for (float y : ya.keys()) for (float p : pa.keys()) {
            var coordinates = normalize(List.of(new Coordinate(ya.parameterGuid(), y), new Coordinate(pa.parameterGuid(), p)));
            String guid = cells.get(coordinates);
            require(guid != null, "joint tuple missing");
            var positions = SurfaceProjection.project(source, camera, new SurfaceProjection.Pose(y, p)).positions();
            boolean isDefault = y == 0f && p == 0f;
            if (isDefault) require(forms.get(guid).positions().equals(zero), "default form is not neutral");
            targets.add(new Target(coordinates, guid, isDefault, positions));
            replacements.put(guid, positions);
        }
        var expected = new ArtMeshAuthoringSnapshot(before.artMeshId(), before.sourceGuid(), before.sourceGeometry(),
            before.axes(), before.cells(), before.forms().stream().map(f -> new ArtMeshAuthoringSnapshot.Form(
                f.formGuid(), replacements.get(f.formGuid()), f.drawOrder())).toList(), before.morphKeys());
        return new Plan(before, targets, expected);
    }

    /** Validate SDK float conversion before binding or any other host mutation. */
    static List<Float> bindingKeys(List<Double> keys) {
        require(!keys.isEmpty() && keys.size() <= 4096, "invalid key count");
        var result = new ArrayList<Float>();
        for (double key : keys) {
            float value = (float) key;
            require(Float.isFinite(value), "non-finite binding key");
            require(result.isEmpty() || value - result.get(result.size() - 1) > 0.000001f,
                "binding keys collapse under native float/epsilon semantics");
            result.add(value == 0 ? 0f : value);
        }
        require(result.contains(0f), "neutral binding key missing");
        return List.copyOf(result);
    }

    private static ArtMeshAuthoringSnapshot.Axis axis(ArtMeshAuthoringSnapshot before, ParameterId id, List<Double> keys) {
        var axes = before.axes().stream().filter(a -> a.parameterId().equals(id)).toList();
        require(axes.size() == 1, "axis identity missing/ambiguous");
        var axis = axes.get(0);
        var expected = bindingKeys(keys);
        require(axis.defaultValue() == 0 && new HashSet<>(axis.keys()).size() == axis.keys().size()
            && axis.keys().stream().sorted().toList().equals(expected)
            && axis.minimum() == expected.get(0) && axis.maximum() == expected.get(expected.size() - 1),
            "axis domain/keys differ from bake plan");
        return axis;
    }

    private static List<Coordinate> normalize(List<Coordinate> coordinates) {
        return coordinates.stream().sorted(Comparator.comparing(Coordinate::parameterGuid)).toList();
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalArgumentException(reason);
    }
}

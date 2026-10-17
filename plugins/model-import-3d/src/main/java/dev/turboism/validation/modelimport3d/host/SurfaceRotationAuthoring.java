package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.id.ParameterBindingPointId;
import dev.turboism.sdk.cubism.model.*;
import java.util.*;

/** Native scalar Rotation angle interpolation is exact; it needs no XYZ key lattice. */
final class SurfaceRotationAuthoring {
    private SurfaceRotationAuthoring() {}

    static final List<Float> KEYS = List.of(-180f, -90f, 0f, 90f, 180f);

    static RotationDeformer create(CubismModel model, Part parent, SurfaceImport.Request request) {
        return create(model, parent, request, Optional.empty());
    }

    static RotationDeformer create(
            CubismModel model,
            Part parent,
            SurfaceImport.Request request,
            Optional<dev.turboism.sdk.cubism.id.ParameterGroupId> parameterGroup) {
        var axis = request.roll().orElseThrow();
        if (model.parameters().all().stream().anyMatch(p -> p.id().equals(axis)))
            throw new IllegalArgumentException("Roll parameter ID already exists");
        var camera = request.camera();
        float x = (float) (camera.centerX() + camera.scale() * camera.pivot().x());
        float y = (float) (camera.centerY() - camera.scale() * camera.pivot().y());
        var rotation = model.deformers().createRotation(request.rotationName("Z"), parent, -1);
        var neutral = new RotationDeformerForm(0, x, y, 1, false, false);
        rotation.replaceForm(neutral);
        model.parameters()
                .createMany(
                        List.of(new ParameterDefinition(
                                axis, request.rotationName("Z"), -180, 0, 180, ParameterType.NORMAL, false)),
                        parameterGroup);
        var points = new ArrayList<ParameterBindingPoint>();
        for (int k = 0; k < KEYS.size(); k++)
            points.add(new ParameterBindingPoint(
                    new ParameterBindingPointId("ROLL_" + axis.value() + "_" + k), KEYS.get(k)));
        model.parameterBindings(axis).bind(ParameterBindingTarget.rotationDeformer(rotation.id()), points);
        var before = rotation.authoringSnapshot();
        if (before.axes().size() != 1
                || !before.axes().get(0).parameterId().equals(axis)
                || !before.axes().get(0).keys().equals(KEYS)
                || before.axes().get(0).defaultValue() != 0
                || before.cells().size() != 5
                || before.forms().size() != 5
                || before.forms().stream().anyMatch(f -> !f.value().equals(neutral)))
            throw new IllegalStateException("independent native Roll form grid required");
        var edits = new ArrayList<RotationKeyformForm>();
        var desired = new HashMap<String, RotationDeformerForm>();
        var seen = new HashSet<Float>();
        for (var cell : before.cells()) {
            if (cell.coordinates().size() != 1
                    || !cell.coordinates()
                            .get(0)
                            .parameterGuid()
                            .equals(before.axes().get(0).parameterGuid()))
                throw new IllegalStateException("Roll grid coordinate changed");
            float value = cell.coordinates().get(0).value();
            if (!seen.add(value) || !KEYS.contains(value)) throw new IllegalStateException("Roll grid key changed");
            // Source frame Y is inverted by projection, so positive source Z uses -angle on canvas.
            var form = new RotationDeformerForm(value == 0 ? 0 : -value, x, y, 1, false, false);
            if (desired.put(cell.formGuid(), form) != null) throw new IllegalStateException("shared Roll form");
            if (value != 0) edits.add(new RotationKeyformForm(cell.coordinates(), cell.formGuid(), form));
        }
        rotation.setOrdinaryKeyformForms(before, edits);
        var expected = new RotationAuthoringSnapshot(
                before.deformerId(),
                before.sourceGuid(),
                before.baseAngle(),
                before.axes(),
                before.cells(),
                before.forms().stream()
                        .map(f -> new RotationAuthoringSnapshot.Form(f.formGuid(), desired.get(f.formGuid())))
                        .toList());
        if (!expected.equals(rotation.authoringSnapshot()))
            throw new IllegalStateException("Roll form readback changed");
        return rotation;
    }
}

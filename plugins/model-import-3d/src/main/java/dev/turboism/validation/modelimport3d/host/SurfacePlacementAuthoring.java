package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.model.*;
import java.util.Optional;

/** Unbound outer rigid transform. Child chart coordinates remain in their projection atlas. */
final class SurfacePlacementAuthoring {
    private SurfacePlacementAuthoring() {}

    static RotationDeformer wrap(CubismModel model, Part part, SurfaceImport.Request request, Deformer root) {
        var camera = request.camera();
        return wrapAt(
                model,
                part,
                request.placementName(),
                root,
                (float) (camera.centerX() + camera.scale() * camera.pivot().x()),
                (float) (camera.centerY() - camera.scale() * camera.pivot().y()));
    }

    static RotationDeformer wrap(CubismModel model, Part part, String name, Deformer root) {
        float x = 0, y = 0;
        if (root instanceof RotationDeformer rotation) {
            var neutral = rotation.authoringSnapshot().forms().get(0).value();
            x = neutral.originX();
            y = neutral.originY();
        }
        return wrapAt(model, part, name + " 整体定位", root, x, y);
    }

    private static RotationDeformer wrapAt(CubismModel model, Part part, String name, Deformer root, float x, float y) {
        if (root.parentDeformerId().isPresent())
            throw new IllegalStateException("projection root already has a parent");
        var placement = model.deformers().createRotation(name, part, -1);
        var neutral = new RotationDeformerForm(0, x, y, 1, false, false);
        placement.replaceForm(neutral);
        var bank = placement.authoringSnapshot();
        if (placement.parentDeformerId().isPresent()
                || !bank.axes().isEmpty()
                || bank.forms().size() != 1
                || !bank.forms().get(0).value().equals(neutral))
            throw new IllegalStateException("unbound centered placement required");
        attachCentered(root, placement, x, y);
        return placement;
    }

    private static void attachCentered(Deformer root, RotationDeformer placement, float x, float y) {
        Object before = stored(root);
        // Some host paths retain stored coordinates when changing the parent. Rebase
        // explicitly in that case; never translate twice when the host already did it.
        root.setTargetDeformer(Optional.of(placement.id()));
        var expected = rebased(before, x, y);
        var actual = stored(root);
        if (!actual.equals(expected)) {
            if (!actual.equals(before)) throw new IllegalStateException("unexpected projection parent conversion");
            writeRebased(root, expected);
        }
        if (!root.parentDeformerId().equals(Optional.of(placement.id())) || !expected.equals(stored(root)))
            throw new IllegalStateException("centered placement changed projection forms");
    }

    private static void writeRebased(Deformer root, Object expected) {
        if (root instanceof RotationDeformer rotation) {
            var before = rotation.authoringSnapshot();
            var target = (RotationAuthoringSnapshot) expected;
            var defaults = new java.util.HashMap<String, Float>();
            for (var axis : before.axes()) defaults.put(axis.parameterGuid(), axis.defaultValue());
            String base = before.axes().isEmpty()
                    ? before.forms().get(0).formGuid()
                    : before.cells().stream()
                            .filter(cell -> cell.coordinates().stream()
                                    .allMatch(c -> c.value() == defaults.get(c.parameterGuid())))
                            .findFirst()
                            .orElseThrow()
                            .formGuid();
            var values = new java.util.HashMap<String, RotationDeformerForm>();
            for (var form : target.forms()) values.put(form.formGuid(), form.value());
            // The ordinary batch deliberately excludes the default form. Write the
            // current neutral form through the host's normal form-editing path first.
            rotation.replaceForm(values.get(base));
            var afterBase = rotation.authoringSnapshot();
            var edits = afterBase.cells().stream()
                    .filter(cell -> !cell.formGuid().equals(base))
                    .map(cell ->
                            new RotationKeyformForm(cell.coordinates(), cell.formGuid(), values.get(cell.formGuid())))
                    .toList();
            if (!edits.isEmpty()) rotation.setOrdinaryKeyformForms(afterBase, edits);
        } else {
            var warp = (WarpDeformer) root;
            var before = warp.authoringSnapshot();
            var target = (WarpAuthoringSnapshot) expected;
            var defaults = new java.util.HashMap<String, Float>();
            for (var axis : before.axes()) defaults.put(axis.parameterGuid(), axis.defaultValue());
            String base = before.axes().isEmpty()
                    ? before.forms().get(0).formGuid()
                    : before.cells().stream()
                            .filter(cell -> cell.coordinates().stream()
                                    .allMatch(c -> c.value() == defaults.get(c.parameterGuid())))
                            .findFirst()
                            .orElseThrow()
                            .formGuid();
            var values = new java.util.HashMap<String, java.util.List<Point2>>();
            for (var form : target.forms()) values.put(form.formGuid(), form.controlPoints());
            warp.replaceGrid(new WarpGrid(target.rows(), target.columns(), target.quadTransform(), values.get(base)));
            var afterBase = warp.authoringSnapshot();
            var edits = afterBase.cells().stream()
                    .filter(cell -> !cell.formGuid().equals(base))
                    .map(cell ->
                            new WarpKeyformPositions(cell.coordinates(), cell.formGuid(), values.get(cell.formGuid())))
                    .toList();
            if (!edits.isEmpty()) warp.setOrdinaryKeyformPositions(afterBase, edits);
        }
    }

    static Object rebased(Object before, float x, float y) {
        if (before instanceof RotationAuthoringSnapshot b)
            return new RotationAuthoringSnapshot(
                    b.deformerId(),
                    b.sourceGuid(),
                    b.baseAngle(),
                    b.axes(),
                    b.cells(),
                    b.forms().stream()
                            .map(f -> {
                                var v = f.value();
                                return new RotationAuthoringSnapshot.Form(
                                        f.formGuid(),
                                        new RotationDeformerForm(
                                                v.angle(),
                                                v.originX() - x,
                                                v.originY() - y,
                                                v.scale(),
                                                v.reflectedX(),
                                                v.reflectedY()));
                            })
                            .toList());
        var b = (WarpAuthoringSnapshot) before;
        return new WarpAuthoringSnapshot(
                b.deformerId(),
                b.sourceGuid(),
                b.rows(),
                b.columns(),
                b.quadTransform(),
                b.axes(),
                b.cells(),
                b.forms().stream()
                        .map(f -> new WarpAuthoringSnapshot.Form(
                                f.formGuid(),
                                f.controlPoints().stream()
                                        .map(p -> new Point2(p.x() - x, p.y() - y))
                                        .toList()))
                        .toList());
    }

    /** Map the normalized projection workspace into a document, without changing stored projection banks. */
    static RotationDeformerForm canvasForm(double width, double height) {
        if (!Double.isFinite(width) || !Double.isFinite(height) || width <= 0 || height <= 0)
            throw new IllegalArgumentException("Canvas dimensions must be finite and positive");
        float scale = (float) (Math.min(width, height) / 1024.0);
        return new RotationDeformerForm(0, (float) (width / 2), (float) (height / 2), scale, false, false);
    }

    static java.util.Set<dev.turboism.sdk.cubism.id.DeformerId> deformerIds(CubismModel model) {
        var ids = new java.util.HashSet<dev.turboism.sdk.cubism.id.DeformerId>();
        for (var d : model.deformers().all()) ids.add(d.id());
        for (var d : model.rotationDeformers().all()) ids.add(d.id());
        return ids;
    }

    static void fitCanvas(
            CubismModel model,
            java.util.Set<dev.turboism.sdk.cubism.id.DeformerId> existing,
            java.util.List<dev.turboism.sdk.cubism.id.ArtMeshId> meshIds,
            String name) {
        var form = canvasForm(model.canvas().widthPixels(), model.canvas().heightPixels());
        if (model.canvas().widthPixels() == 1024 && model.canvas().heightPixels() == 1024) return;
        var roots = new java.util.LinkedHashMap<dev.turboism.sdk.cubism.id.DeformerId, Deformer>();
        for (var d : model.deformers().all())
            if (!existing.contains(d.id()) && d.parentDeformerId().isEmpty()) roots.put(d.id(), d);
        for (var d : model.rotationDeformers().all())
            if (!existing.contains(d.id()) && d.parentDeformerId().isEmpty()) roots.put(d.id(), d);
        RotationDeformer placement;
        if (roots.size() == 1
                && roots.values().iterator().next() instanceof RotationDeformer rotation
                && rotation.authoringSnapshot().axes().isEmpty()) {
            placement = rotation;
        } else {
            placement = model.deformers().createRotation(name, null, -1);
            placement.replaceForm(new RotationDeformerForm(0, 512, 512, 1, false, false));
            for (var d : roots.values()) attachCentered(d, placement, 512, 512);
            for (var id : meshIds) {
                var mesh = model.drawables().find(id);
                if (mesh.parentDeformerId().isEmpty()) mesh.setTargetDeformer(Optional.of(placement.id()));
            }
        }
        placement.replaceForm(form);
        if (!placement.authoringSnapshot().forms().get(0).value().equals(form))
            throw new IllegalStateException("Canvas placement readback failed");
    }

    private static Object stored(Deformer deformer) {
        if (deformer instanceof WarpDeformer warp) return warp.authoringSnapshot();
        if (deformer instanceof RotationDeformer rotation) return rotation.authoringSnapshot();
        throw new IllegalArgumentException("Warp or Rotation projection root required");
    }
}

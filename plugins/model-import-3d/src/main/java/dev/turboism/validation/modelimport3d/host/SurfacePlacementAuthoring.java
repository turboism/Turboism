package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.model.*;
import java.util.Optional;

/** Unbound outer rigid transform. Child chart coordinates remain in their projection atlas. */
final class SurfacePlacementAuthoring {
    private SurfacePlacementAuthoring() {}

    static RotationDeformer wrap(CubismModel model, Part part, String name, Deformer projectionRoot) {
        if (projectionRoot.parentDeformerId().isPresent())
            throw new IllegalStateException("projection root already has a parent");
        Object before = stored(projectionRoot);
        var placement = model.deformers().createRotation(name + " 整体定位", part, -1);
        var identity = new RotationDeformerForm(0, 0, 0, 1, false, false);
        placement.replaceForm(identity);
        var bank = placement.authoringSnapshot();
        if (!placement.parentDeformerId().isEmpty()
                || !bank.axes().isEmpty()
                || bank.forms().size() != 1
                || !bank.forms().get(0).value().equals(identity))
            throw new IllegalStateException("unbound identity placement required");
        projectionRoot.setTargetDeformer(Optional.of(placement.id()));
        if (!projectionRoot.parentDeformerId().equals(Optional.of(placement.id()))
                || !before.equals(stored(projectionRoot)))
            throw new IllegalStateException("identity placement changed stored projection forms");
        return placement;
    }

    /** Map the normalized projection workspace into a document, without changing stored projection banks. */
    static RotationDeformerForm canvasForm(double width, double height) {
        if (!Double.isFinite(width) || !Double.isFinite(height) || width <= 0 || height <= 0)
            throw new IllegalArgumentException("Canvas dimensions must be finite and positive");
        float scale = (float) (Math.min(width, height) / 1024.0);
        return new RotationDeformerForm(
                0, (float) (width / 2 - 512 * scale), (float) (height / 2 - 512 * scale), scale, false, false);
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
        if (form.equals(new RotationDeformerForm(0, 0, 0, 1, false, false))) return;
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
            placement = model.deformers().createRotation(name + " 整体定位", null, -1);
            for (var d : roots.values()) d.setTargetDeformer(Optional.of(placement.id()));
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

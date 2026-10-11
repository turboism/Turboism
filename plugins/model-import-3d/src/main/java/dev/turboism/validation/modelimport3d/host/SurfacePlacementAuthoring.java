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

    private static Object stored(Deformer deformer) {
        if (deformer instanceof WarpDeformer warp) return warp.authoringSnapshot();
        if (deformer instanceof RotationDeformer rotation) return rotation.authoringSnapshot();
        throw new IllegalArgumentException("Warp or Rotation projection root required");
    }
}

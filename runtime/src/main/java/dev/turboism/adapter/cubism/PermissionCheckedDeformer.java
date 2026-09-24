package dev.turboism.adapter.cubism;

import dev.turboism.sdk.cubism.event.CubismOperation;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** Permission-checked Deformer views for one model generation. */
class PermissionCheckedDeformer implements dev.turboism.sdk.cubism.model.Deformer {
    protected final CubismFacadeImpl facade;
    private final Object wrapperOwner;
    protected final dev.turboism.sdk.cubism.model.Deformer delegate;
    PermissionCheckedDeformer(final CubismFacadeImpl facade, final Object wrapperOwner,
        final dev.turboism.sdk.cubism.model.Deformer delegate
    ) {
        this.facade = facade;
        this.wrapperOwner = Objects.requireNonNull(wrapperOwner, "wrapperOwner");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }
    @Override public dev.turboism.sdk.ui.appearance.model.DeformerAppearance ui() {
        facade.requireModelRead("deformer.ui");
        return delegate.ui();
    }
    @Override public dev.turboism.sdk.cubism.id.DeformerId id() { facade.requireModelRead("deformer.id"); return delegate.id(); }
    @Override public int index() {
        facade.requireModelRead("deformer.index");
        return delegate.index();
    }
    @Override public Optional<dev.turboism.sdk.cubism.model.PartId> parentPartId() {
        facade.requireModelRead("deformer.parentPartId");
        return delegate.parentPartId();
    }
    @Override public Optional<dev.turboism.sdk.cubism.id.DeformerId> parentDeformerId() {
        facade.requireModelRead("deformer.parentDeformerId");
        return delegate.parentDeformerId();
    }
    @Override public List<dev.turboism.sdk.cubism.id.ParameterId> parameterIds() {
        facade.requireModelRead("deformer.parameterIds");
        return delegate.parameterIds();
    }
    @Override public String name() { facade.requireModelRead("deformer.name"); return delegate.name(); }
    @Override public void setName(final String name) {
        facade.requireModelWrite("deformer.setName");
        delegate.setName(name);
    }
    @Override public void setParent(
        final dev.turboism.sdk.cubism.model.Part parent,
        final int index
    ) {
        facade.requireModelWrite("deformer.setParentPart");
        delegate.setParent(facade.unwrapPart(wrapperOwner, parent), index);
    }
    @Override public void setParent(
        final dev.turboism.sdk.cubism.model.Deformer parent,
        final int index
    ) {
        facade.requireModelWrite("deformer.setParentDeformer");
        delegate.setParent(facade.unwrapDeformer(parent), index);
    }
    @Override public boolean visible() { facade.requireModelRead("deformer.visible"); return delegate.visible(); }
    @Override public void setVisible(final boolean visible) {
        facade.requireModelWrite("deformer.setVisible");
        facade.runSemantic(
            CubismOperation.SET_DEFORMER_VISIBLE,
            id().value(),
            delegate::visible,
            () -> facade.editorObjectLifecycle.deformer().setVisible(this, visible, delegate::setVisible)
        );
    }
    @Override public boolean locked() { facade.requireModelRead("deformer.locked"); return delegate.locked(); }
    @Override public void setLocked(final boolean locked) {
        facade.requireModelWrite("deformer.setLocked");
        facade.runSemantic(
            CubismOperation.SET_DEFORMER_LOCKED,
            id().value(),
            delegate::locked,
            () -> facade.editorObjectLifecycle.deformer().setLocked(this, locked, delegate::setLocked)
        );
    }
    @Override public boolean visibleInHierarchy() { facade.requireModelRead("deformer.visibleInHierarchy"); return delegate.visibleInHierarchy(); }
    @Override public boolean lockedInHierarchy() { facade.requireModelRead("deformer.lockedInHierarchy"); return delegate.lockedInHierarchy(); }
    @Override public float getOpacity() { facade.requireModelRead("deformer.getOpacity"); return delegate.getOpacity(); }
    @Override public void setOpacity(final float opacity) {
        facade.requireModelWrite("deformer.setOpacity");
        facade.runSemantic(
            CubismOperation.SET_DEFORMER_OPACITY,
            id().value(),
            delegate::getOpacity,
            () -> facade.editorObjectLifecycle.deformer().setOpacity(this, opacity, delegate::setOpacity)
        );
    }
    @Override public dev.turboism.sdk.cubism.model.Color multiplyColor() {
        facade.requireModelRead("deformer.multiplyColor");
        return delegate.multiplyColor();
    }
    @Override public dev.turboism.sdk.cubism.model.Color screenColor() {
        facade.requireModelRead("deformer.screenColor");
        return delegate.screenColor();
    }
    @Override public int parentPartIndex() { facade.requireModelRead("deformer.parentPartIndex"); return delegate.parentPartIndex(); }
    @Override public int parentDeformerIndex() { facade.requireModelRead("deformer.parentDeformerIndex"); return delegate.parentDeformerIndex(); }
    @Override public dev.turboism.sdk.cubism.model.IntSequence parameters() {
        facade.requireModelRead("deformer.parameters");
        return delegate.parameters();
    }
    @Override public List<dev.turboism.sdk.cubism.model.ParameterBinding> getParameterBindings() {
        facade.requireModelRead("deformer.getParameterBindings");
        return delegate.getParameterBindings();
    }
    @Override public List<dev.turboism.sdk.cubism.model.ParameterBinding> getNormalParameterBindings() {
        facade.requireModelRead("deformer.getNormalParameterBindings");
        return delegate.getNormalParameterBindings();
    }
    @Override public List<dev.turboism.sdk.cubism.model.ParameterBinding> getCombinedParameterBindings() {
        facade.requireModelRead("deformer.getCombinedParameterBindings");
        return delegate.getCombinedParameterBindings();
    }
    @Override public void setId(final dev.turboism.sdk.cubism.id.DeformerId id) {
        facade.requireModelWrite("deformer.setId");
        delegate.setId(id);
    }
    @Override public void setMultiplyColor(final dev.turboism.sdk.cubism.model.Color color) {
        facade.requireModelWrite("deformer.setMultiplyColor");
        delegate.setMultiplyColor(color);
    }
    @Override public void setScreenColor(final dev.turboism.sdk.cubism.model.Color color) {
        facade.requireModelWrite("deformer.setScreenColor");
        delegate.setScreenColor(color);
    }
    @Override public void setTargetDeformer(
        final Optional<dev.turboism.sdk.cubism.id.DeformerId> targetDeformer
    ) {
        facade.requireModelWrite("deformer.setTargetDeformer");
        delegate.setTargetDeformer(targetDeformer);
    }
}

final class PermissionCheckedWarpDeformer extends PermissionCheckedDeformer
    implements dev.turboism.sdk.cubism.model.WarpDeformer {
    final dev.turboism.sdk.cubism.model.WarpDeformer warp;
    PermissionCheckedWarpDeformer(final CubismFacadeImpl facade, final Object wrapperOwner,
        final dev.turboism.sdk.cubism.model.WarpDeformer delegate
    ) {
        super(facade, wrapperOwner, delegate);
        this.warp = delegate;
    }
    @Override public dev.turboism.sdk.cubism.model.WarpGrid grid() { facade.requireModelRead("warpDeformer.grid"); return warp.grid(); }
    @Override public void replaceGrid(final dev.turboism.sdk.cubism.model.WarpGrid grid) {
        facade.requireModelWrite("warpDeformer.replaceGrid");
        facade.runSemantic(
            CubismOperation.REPLACE_WARP_DEFORMER_GRID,
            id().value(),
            warp::grid,
            () -> facade.editorObjectLifecycle.deformer().replaceGrid(this, grid, warp::replaceGrid)
        );
    }
}

final class PermissionCheckedRotationDeformer extends PermissionCheckedDeformer
    implements dev.turboism.sdk.cubism.model.RotationDeformer {
    final dev.turboism.sdk.cubism.model.RotationDeformer rotation;
    PermissionCheckedRotationDeformer(final CubismFacadeImpl facade, final Object wrapperOwner,
        final dev.turboism.sdk.cubism.model.RotationDeformer delegate
    ) {
        super(facade, wrapperOwner, delegate);
        this.rotation = delegate;
    }
    @Override public float baseAngle() { facade.requireModelRead("rotationDeformer.baseAngle"); return rotation.baseAngle(); }
    @Override public void setBaseAngle(final float angle) {
        facade.requireModelWrite("rotationDeformer.setBaseAngle");
        facade.runSemantic(
            CubismOperation.SET_ROTATION_DEFORMER_BASE_ANGLE,
            id().value(),
            rotation::baseAngle,
            () -> facade.editorObjectLifecycle.deformer().setBaseAngle(this, angle, rotation::setBaseAngle)
        );
    }
    @Override public dev.turboism.sdk.cubism.model.RotationDeformerForm form() {
        facade.requireModelRead("rotationDeformer.form");
        return rotation.form();
    }
    @Override public void replaceForm(
        final dev.turboism.sdk.cubism.model.RotationDeformerForm form
    ) {
        facade.requireModelWrite("rotationDeformer.replaceForm");
        facade.runSemantic(
            CubismOperation.REPLACE_ROTATION_DEFORMER_FORM,
            id().value(),
            rotation::form,
            () -> facade.editorObjectLifecycle.deformer().replaceForm(this, form, rotation::replaceForm)
        );
    }
}

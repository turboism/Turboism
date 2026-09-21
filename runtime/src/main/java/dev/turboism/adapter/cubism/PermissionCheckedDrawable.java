package dev.turboism.adapter.cubism;

import dev.turboism.sdk.cubism.event.CubismOperation;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** Permission-checked {@link dev.turboism.sdk.cubism.model.Drawable} view for one model generation. */
final class PermissionCheckedDrawable
    implements dev.turboism.sdk.cubism.model.Drawable {
    private final CubismFacadeImpl facade;
    private final Object wrapperOwner;
    final dev.turboism.sdk.cubism.model.Drawable delegate;
    PermissionCheckedDrawable(final CubismFacadeImpl facade, final Object wrapperOwner,
        final dev.turboism.sdk.cubism.model.Drawable delegate
    ) {
        this.facade = facade;
        this.wrapperOwner = Objects.requireNonNull(wrapperOwner, "wrapperOwner");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }
    @Override public dev.turboism.sdk.ui.appearance.model.DrawableAppearance ui() {
        facade.requireModelRead("artMesh.ui");
        return delegate.ui();
    }
    @Override public dev.turboism.sdk.cubism.id.ArtMeshId id() {
        facade.requireModelRead("artMesh.id");
        return delegate.id();
    }
    @Override public int index() {
        facade.requireModelRead("artMesh.index");
        return delegate.index();
    }
    @Override public boolean doubleSided() {
        facade.requireModelRead("artMesh.doubleSided");
        return delegate.doubleSided();
    }
    @Override public dev.turboism.sdk.cubism.model.DrawableEvaluationState evaluationState() {
        facade.requireModelRead("artMesh.evaluationState");
        return delegate.evaluationState();
    }
    @Override public Optional<dev.turboism.sdk.cubism.model.PartId> parentPartId() {
        facade.requireModelRead("artMesh.parentPartId");
        return delegate.parentPartId();
    }
    @Override public Optional<dev.turboism.sdk.cubism.id.DeformerId> parentDeformerId() {
        facade.requireModelRead("artMesh.parentDeformerId");
        return delegate.parentDeformerId();
    }
    @Override public List<dev.turboism.sdk.cubism.id.ParameterId> parameterIds() {
        facade.requireModelRead("artMesh.parameterIds");
        return delegate.parameterIds();
    }
    @Override public List<dev.turboism.sdk.cubism.id.ArtMeshId> maskIds() {
        facade.requireModelRead("artMesh.maskIds");
        return delegate.maskIds();
    }

    @Override public String name() {
        facade.requireModelRead("artMesh.name");
        return delegate.name();
    }
    @Override public String guid() {
        facade.requireModelRead("artMesh.guid");
        return delegate.guid();
    }
    @Override public void setName(final String name) {
        facade.requireModelWrite("artMesh.setName");
        delegate.setName(name);
    }
    @Override public void setId(final String id) {
        facade.requireModelWrite("artMesh.setId");
        delegate.setId(id);
    }
    @Override public void setTargetDeformer(
        final Optional<dev.turboism.sdk.cubism.id.DeformerId> targetDeformer
    ) {
        facade.requireModelWrite("artMesh.setTargetDeformer");
        delegate.setTargetDeformer(targetDeformer);
    }
    @Override public void setClippingMaskIds(
        final List<dev.turboism.sdk.cubism.id.ArtMeshId> maskIds
    ) {
        facade.requireModelWrite("artMesh.setClippingMaskIds");
        delegate.setClippingMaskIds(maskIds);
    }
    @Override public void setInvertedMask(final boolean inverted) {
        facade.requireModelWrite("artMesh.setInvertedMask");
        delegate.setInvertedMask(inverted);
    }
    @Override public void setDrawOrder(final int drawOrder) {
        facade.requireModelWrite("artMesh.setDrawOrder");
        delegate.setDrawOrder(drawOrder);
    }
    @Override public void setMultiplyColor(final dev.turboism.sdk.cubism.model.Color color) {
        facade.requireModelWrite("artMesh.setMultiplyColor");
        delegate.setMultiplyColor(color);
    }
    @Override public void setScreenColor(final dev.turboism.sdk.cubism.model.Color color) {
        facade.requireModelWrite("artMesh.setScreenColor");
        delegate.setScreenColor(color);
    }
    @Override public void setColorComposition(
        final dev.turboism.sdk.cubism.model.ColorComposition composition
    ) {
        facade.requireModelWrite("artMesh.setColorComposition");
        delegate.setColorComposition(composition);
    }
    @Override public void setAlphaComposition(
        final dev.turboism.sdk.cubism.model.AlphaComposition composition
    ) {
        facade.requireModelWrite("artMesh.setAlphaComposition");
        delegate.setAlphaComposition(composition);
    }
    @Override public void setCulling(final boolean culling) {
        facade.requireModelWrite("artMesh.setCulling");
        delegate.setCulling(culling);
    }
    @Override public void setUserData(final String userData) {
        facade.requireModelWrite("artMesh.setUserData");
        delegate.setUserData(userData);
    }
    @Override public void setParent(
        final dev.turboism.sdk.cubism.model.Part parent,
        final int index
    ) {
        facade.requireModelWrite("artMesh.setParentPart");
        delegate.setParent(facade.unwrapPart(wrapperOwner, parent), index);
    }
    @Override public void setParent(
        final dev.turboism.sdk.cubism.model.Deformer parent,
        final int index
    ) {
        facade.requireModelWrite("artMesh.setParentDeformer");
        delegate.setParent(facade.unwrapDeformer(parent), index);
    }
    @Override public boolean visible() {
        facade.requireModelRead("artMesh.visible");
        return delegate.visible();
    }
    @Override public void setVisible(final boolean visible) {
        facade.requireModelWrite("artMesh.setVisible");
        facade.runSemantic(
            CubismOperation.SET_DRAWABLE_VISIBLE,
            id().value(),
            delegate::visible,
            () -> facade.editorObjectLifecycle.drawable().setVisible(this, visible, delegate::setVisible)
        );
    }
    @Override public boolean locked() {
        facade.requireModelRead("artMesh.locked");
        return delegate.locked();
    }
    @Override public void setLocked(final boolean locked) {
        facade.requireModelWrite("artMesh.setLocked");
        facade.runSemantic(
            CubismOperation.SET_DRAWABLE_LOCKED,
            id().value(),
            delegate::locked,
            () -> facade.editorObjectLifecycle.drawable().setLocked(this, locked, delegate::setLocked)
        );
    }
    @Override public boolean visibleInHierarchy() {
        facade.requireModelRead("artMesh.visibleInHierarchy");
        return delegate.visibleInHierarchy();
    }
    @Override public boolean lockedInHierarchy() {
        facade.requireModelRead("artMesh.lockedInHierarchy");
        return delegate.lockedInHierarchy();
    }
    @Override public byte constantFlag() {
        facade.requireModelRead("artMesh.constantFlag");
        return delegate.constantFlag();
    }
    @Override public byte dynamicFlag() {
        facade.requireModelRead("artMesh.dynamicFlag");
        return delegate.dynamicFlag();
    }
    @Override public dev.turboism.sdk.cubism.model.BlendMode blendMode() {
        facade.requireModelRead("artMesh.blendMode");
        return delegate.blendMode();
    }
    @Override public int textureIndex() {
        facade.requireModelRead("artMesh.textureIndex");
        return delegate.textureIndex();
    }
    @Override public int drawOrder() {
        facade.requireModelRead("artMesh.drawOrder");
        return delegate.drawOrder();
    }
    @Override public int renderOrder() {
        facade.requireModelRead("artMesh.renderOrder");
        return delegate.renderOrder();
    }
    @Override public float getOpacity() {
        facade.requireModelRead("artMesh.getOpacity");
        return delegate.getOpacity();
    }
    @Override public void setOpacity(final float opacity) {
        facade.requireModelWrite("artMesh.setOpacity");
        facade.runSemantic(
            CubismOperation.SET_DRAWABLE_OPACITY,
            id().value(),
            delegate::getOpacity,
            () -> facade.editorObjectLifecycle.drawable().setOpacity(this, opacity, delegate::setOpacity)
        );
    }
    @Override public dev.turboism.sdk.cubism.model.ArtMeshGeometry geometry() {
        facade.requireModelRead("artMesh.geometry");
        return delegate.geometry();
    }
    @Override public void replaceGeometry(
        final dev.turboism.sdk.cubism.model.ArtMeshGeometry geometry
    ) {
        facade.requireModelWrite("artMesh.replaceGeometry");
        facade.runSemantic(
            CubismOperation.REPLACE_DRAWABLE_GEOMETRY,
            id().value(),
            delegate::geometry,
            () -> facade.editorObjectLifecycle.drawable().replaceGeometry(
                this,
                geometry,
                delegate::replaceGeometry
            )
        );
    }
    @Override public dev.turboism.sdk.cubism.model.IntSequence masks() {
        facade.requireModelRead("artMesh.masks");
        return delegate.masks();
    }
    @Override public boolean invertedMask() {
        facade.requireModelRead("artMesh.invertedMask");
        return delegate.invertedMask();
    }
    @Override public boolean culling() {
        facade.requireModelRead("artMesh.culling");
        return delegate.culling();
    }
    @Override public String userData() {
        facade.requireModelRead("artMesh.userData");
        return delegate.userData();
    }
    @Override public dev.turboism.sdk.cubism.model.FloatSequence vertexPositions() {
        facade.requireModelRead("artMesh.vertexPositions");
        return delegate.vertexPositions();
    }
    @Override public dev.turboism.sdk.cubism.model.FloatSequence vertexUvs() {
        facade.requireModelRead("artMesh.vertexUvs");
        return delegate.vertexUvs();
    }
    @Override public dev.turboism.sdk.cubism.model.IntSequence indices() {
        facade.requireModelRead("artMesh.indices");
        return delegate.indices();
    }

    @Override public dev.turboism.sdk.cubism.model.MorphTargets morphTargets() {
        facade.requireModelRead("artMesh.morphTargets");
        return delegate.morphTargets();
    }
    @Override public dev.turboism.sdk.cubism.model.Color multiplyColor() {
        facade.requireModelRead("artMesh.multiplyColor");
        return delegate.multiplyColor();
    }
    @Override public dev.turboism.sdk.cubism.model.Color screenColor() {
        facade.requireModelRead("artMesh.screenColor");
        return delegate.screenColor();
    }
    @Override public int parentPartIndex() {
        facade.requireModelRead("artMesh.parentPartIndex");
        return delegate.parentPartIndex();
    }
    @Override public int parentDeformerIndex() {
        facade.requireModelRead("artMesh.parentDeformerIndex");
        return delegate.parentDeformerIndex();
    }
    @Override public dev.turboism.sdk.cubism.model.IntSequence parameters() {
        facade.requireModelRead("artMesh.parameters");
        return delegate.parameters();
    }
    @Override public List<dev.turboism.sdk.cubism.model.ParameterBinding> getParameterBindings() {
        facade.requireModelRead("artMesh.getParameterBindings");
        return delegate.getParameterBindings();
    }
    @Override public List<dev.turboism.sdk.cubism.model.ParameterBinding> getNormalParameterBindings() {
        facade.requireModelRead("artMesh.getNormalParameterBindings");
        return delegate.getNormalParameterBindings();
    }
    @Override public List<dev.turboism.sdk.cubism.model.ParameterBinding> getCombinedParameterBindings() {
        facade.requireModelRead("artMesh.getCombinedParameterBindings");
        return delegate.getCombinedParameterBindings();
    }
}

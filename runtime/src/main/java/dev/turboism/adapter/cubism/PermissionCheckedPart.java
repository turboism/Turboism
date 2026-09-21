package dev.turboism.adapter.cubism;

import dev.turboism.sdk.cubism.event.CubismOperation;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** Permission-checked {@link dev.turboism.sdk.cubism.model.Part} view for one model generation. */
final class PermissionCheckedPart implements dev.turboism.sdk.cubism.model.Part {
    private final CubismFacadeImpl facade;
    final Object owner;
    final dev.turboism.sdk.cubism.model.Part delegate;

    PermissionCheckedPart(final CubismFacadeImpl facade, final Object owner,
        final dev.turboism.sdk.cubism.model.Part delegate
    ) {
        this.facade = facade;
        this.owner = Objects.requireNonNull(owner, "owner");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }
    @Override public dev.turboism.sdk.ui.appearance.model.PartAppearance ui() {
        facade.requireModelRead("part.ui");
        return delegate.ui();
    }

    @Override public dev.turboism.sdk.cubism.model.PartId id() { facade.requireModelRead("part.id"); return delegate.id(); }
    @Override public int index() {
        facade.requireModelRead("part.index");
        return delegate.index();
    }
    @Override public Optional<String> shortName() {
        facade.requireModelRead("part.shortName");
        return delegate.shortName();
    }
    @Override public void setShortName(final Optional<String> value) {
        facade.requireModelWrite("part.setShortName");
        final Optional<String> checked = Objects.requireNonNull(value, "value");
        if (checked.filter(String::isBlank).isPresent()) {
            throw new IllegalArgumentException("short name must not be blank");
        }
        delegate.setShortName(checked);
    }
    @Override public Optional<dev.turboism.sdk.cubism.model.PartId> parentId() {
        facade.requireModelRead("part.parentId");
        return delegate.parentId();
    }
    @Override public List<dev.turboism.sdk.cubism.model.PartId> childIds() {
        facade.requireModelRead("part.childIds");
        return delegate.childIds();
    }

    @Override public dev.turboism.sdk.cubism.model.MorphTargets morphTargets() {
        facade.requireModelRead("part.morphTargets");
        return delegate.morphTargets();
    }
    @Override public boolean visible() {
        facade.requireModelRead("part.visible");
        return delegate.visible();
    }
    @Override public void setVisible(final boolean value) {
        facade.requireModelWrite("part.setVisible");
        delegate.setVisible(value);
    }
    @Override public boolean visibleInHierarchy() {
        facade.requireModelRead("part.visibleInHierarchy");
        return delegate.visibleInHierarchy();
    }
    @Override public boolean locked() {
        facade.requireModelRead("part.locked");
        return delegate.locked();
    }
    @Override public void setLocked(final boolean value) {
        facade.requireModelWrite("part.setLocked");
        delegate.setLocked(value);
    }
    @Override public boolean lockedInHierarchy() {
        facade.requireModelRead("part.lockedInHierarchy");
        return delegate.lockedInHierarchy();
    }
    @Override public Optional<dev.turboism.sdk.cubism.model.Color> editColor() {
        facade.requireModelRead("part.editColor");
        return delegate.editColor();
    }
    @Override public void setEditColor(
        final Optional<dev.turboism.sdk.cubism.model.Color> value
    ) {
        facade.requireModelWrite("part.setEditColor");
        delegate.setEditColor(Objects.requireNonNull(value, "value"));
    }
    @Override public boolean sketch() {
        facade.requireModelRead("part.sketch");
        return delegate.sketch();
    }
    @Override public void setSketch(final boolean value) {
        facade.requireModelWrite("part.setSketch");
        delegate.setSketch(value);
    }
    @Override public int defaultOrder() {
        facade.requireModelRead("part.defaultOrder");
        return delegate.defaultOrder();
    }
    @Override public void setDefaultOrder(final int value) {
        facade.requireModelWrite("part.setDefaultOrder");
        delegate.setDefaultOrder(value);
    }
    @Override public String name() { facade.requireModelRead("part.name"); return delegate.name(); }
    @Override public void setName(final String name) {
        facade.requireModelWrite("part.setName");
        facade.runSemantic(
            CubismOperation.SET_PART_NAME,
            id().value(),
            delegate::name,
            () -> facade.partLifecycle.setName(this, name, delegate::setName)
        );
    }
    @Override public dev.turboism.sdk.cubism.model.AlphaComposition alphaComposition() {
        facade.requireModelRead("part.alphaComposition");
        return delegate.alphaComposition();
    }
    @Override public List<dev.turboism.sdk.cubism.id.ArtMeshId> maskIds() {
        facade.requireModelRead("part.maskIds");
        return delegate.maskIds();
    }
    @Override public void setId(final dev.turboism.sdk.cubism.model.PartId id) {
        facade.requireModelWrite("part.setId");
        delegate.setId(id);
    }
    @Override public void setMaskIds(final List<dev.turboism.sdk.cubism.id.ArtMeshId> maskIds) {
        facade.requireModelWrite("part.setMaskIds");
        delegate.setMaskIds(maskIds);
    }
    @Override public void setAlphaComposition(
        final dev.turboism.sdk.cubism.model.AlphaComposition composition
    ) {
        facade.requireModelWrite("part.setAlphaComposition");
        delegate.setAlphaComposition(composition);
    }
    @Override public void setParent(
        final dev.turboism.sdk.cubism.model.Part parent,
        final int index
    ) {
        facade.requireModelWrite("part.setParent");
        delegate.setParent(facade.unwrapPart(owner, parent), index);
    }
    @Override public float getOpacity() { facade.requireModelRead("part.getOpacity"); return delegate.getOpacity(); }
    @Override public int parentIndex() { facade.requireModelRead("part.parentIndex"); return delegate.parentIndex(); }
    @Override public void setOpacity(final float opacity) {
        facade.requireModelWrite("part.setOpacity");
        facade.runSemantic(
            CubismOperation.SET_PART_OPACITY,
            id().value(),
            delegate::getOpacity,
            () -> facade.partLifecycle.setOpacity(this, opacity, delegate::setOpacity)
        );
    }
}

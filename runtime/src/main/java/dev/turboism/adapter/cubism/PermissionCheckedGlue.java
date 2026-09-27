package dev.turboism.adapter.cubism;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Permission-checked {@link dev.turboism.sdk.cubism.model.Glue} view for one model generation. */
final class PermissionCheckedGlue implements dev.turboism.sdk.cubism.model.Glue {
    private final CubismFacadeImpl facade;
    private final dev.turboism.sdk.cubism.model.Glue delegate;

    PermissionCheckedGlue(final CubismFacadeImpl facade, final dev.turboism.sdk.cubism.model.Glue delegate) {
        this.facade = facade;
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override public dev.turboism.sdk.cubism.model.GlueId id() { facade.requireModelRead("glue.id"); return delegate.id(); }
    @Override public int index() {
        facade.requireModelRead("glue.index");
        return delegate.index();
    }
    @Override public int drawableA() { facade.requireModelRead("glue.drawableA"); return delegate.drawableA(); }
    @Override public int drawableB() { facade.requireModelRead("glue.drawableB"); return delegate.drawableB(); }
    @Override public dev.turboism.sdk.cubism.model.IntSequence parameters() {
        facade.requireModelRead("glue.parameters");
        return delegate.parameters();
    }
    @Override public dev.turboism.sdk.cubism.id.ArtMeshId drawableAId() {
        facade.requireModelRead("glue.drawableAId");
        return delegate.drawableAId();
    }
    @Override public dev.turboism.sdk.cubism.id.ArtMeshId drawableBId() {
        facade.requireModelRead("glue.drawableBId");
        return delegate.drawableBId();
    }
    @Override public List<dev.turboism.sdk.cubism.id.ParameterId> parameterIds() {
        facade.requireModelRead("glue.parameterIds");
        return delegate.parameterIds();
    }
    @Override public String name() {
        facade.requireModelRead("glue.name");
        return delegate.name();
    }
    @Override public float intensity() {
        facade.requireModelRead("glue.intensity");
        return delegate.intensity();
    }
    @Override public void setName(final String name) {
        facade.requireModelWrite("glue.setName");
        delegate.setName(name);
    }
    @Override public void setId(final dev.turboism.sdk.cubism.model.GlueId id) {
        facade.requireModelWrite("glue.setId");
        delegate.setId(id);
    }
    @Override public void setIntensity(final float intensity) {
        facade.requireModelWrite("glue.setIntensity");
        delegate.setIntensity(intensity);
    }
    @Override public void setDrawableA(final dev.turboism.sdk.cubism.id.ArtMeshId id) {
        facade.requireModelWrite("glue.setDrawableA");
        delegate.setDrawableA(id);
    }
    @Override public void setDrawableB(final dev.turboism.sdk.cubism.id.ArtMeshId id) {
        facade.requireModelWrite("glue.setDrawableB");
        delegate.setDrawableB(id);
    }
}

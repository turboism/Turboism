package dev.turboism.adapter.host;

import dev.turboism.sdk.cubism.model.Canvas;
import java.util.List;
import java.util.Objects;

final class SessionModelTextures implements dev.turboism.sdk.cubism.model.ModelTextures {
    private final DynamicCubismModelAccess host;
    final long generation;
    final dev.turboism.sdk.cubism.model.ModelTextures delegate;

    SessionModelTextures(
            final DynamicCubismModelAccess host,
            final long generation,
            final dev.turboism.sdk.cubism.model.ModelTextures delegate) {
        this.host = Objects.requireNonNull(host, "host");
        this.generation = generation;
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public List<dev.turboism.sdk.cubism.model.RawTexture> rawImages() {
        return host.guarded(generation, delegate::rawImages);
    }

    @Override
    public List<dev.turboism.sdk.cubism.model.ModelImageGroup> modelImageGroups() {
        return host.guarded(generation, delegate::modelImageGroups);
    }

    @Override
    public List<dev.turboism.sdk.cubism.model.AtlasTexture> textureAtlases() {
        return host.guarded(generation, delegate::textureAtlases);
    }

    @Override
    public void addModelImageGroup(final String name) {
        host.guardedVoid(generation, () -> delegate.addModelImageGroup(name));
    }

    @Override
    public void removeModelImage(final dev.turboism.sdk.cubism.id.ModelImageId id) {
        host.guardedVoid(generation, () -> delegate.removeModelImage(id));
    }

    @Override
    public dev.turboism.sdk.cubism.id.TextureAtlasId addTextureAtlas(
            final String name, final int widthPixels, final int heightPixels) {
        return host.guarded(generation, () -> delegate.addTextureAtlas(name, widthPixels, heightPixels));
    }

    @Override
    public void removeTextureAtlas(final dev.turboism.sdk.cubism.id.TextureAtlasId id) {
        host.guardedVoid(generation, () -> delegate.removeTextureAtlas(id));
    }

    @Override
    public void removeRawImage(final dev.turboism.sdk.cubism.id.RawImageId id) {
        host.guardedVoid(generation, () -> delegate.removeRawImage(id));
    }
}

final class SessionCanvas implements Canvas {
    private final DynamicCubismModelAccess host;
    private final long generation;
    private final Canvas delegate;

    SessionCanvas(final DynamicCubismModelAccess host, final long generation, final Canvas delegate) {
        this.host = Objects.requireNonNull(host, "host");
        this.generation = generation;
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public float widthPixels() {
        return host.guarded(generation, delegate::widthPixels);
    }

    @Override
    public float heightPixels() {
        return host.guarded(generation, delegate::heightPixels);
    }

    @Override
    public float originXPixels() {
        return host.guarded(generation, delegate::originXPixels);
    }

    @Override
    public float originYPixels() {
        return host.guarded(generation, delegate::originYPixels);
    }

    @Override
    public float pixelsPerUnit() {
        return host.guarded(generation, delegate::pixelsPerUnit);
    }
}

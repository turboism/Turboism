package dev.turboism.adapter.host;

import java.util.List;
import java.util.Objects;

final class SessionModelTextures
        implements dev.turboism.sdk.cubism.model.ModelTextures,
                dev.turboism.core.runtime.psd.PsdExportHost,
                dev.turboism.core.runtime.psd.PsdReplaceHost,
                dev.turboism.core.runtime.psd.PsdSessionBoundHost {
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
    public Observation exportPsdTo(
            final dev.turboism.sdk.cubism.id.RawImageId source,
            final java.nio.file.Path destination,
            final Runnable admission) {
        Objects.requireNonNull(admission, "admission");
        return host.guarded(generation, () -> {
            if (!(delegate instanceof dev.turboism.core.runtime.psd.PsdExportHost exportHost)) {
                return Observation.unavailable();
            }
            return exportHost.exportPsdTo(source, destination, () -> host.guardedVoid(generation, admission));
        });
    }

    @Override
    public String sessionIdentity() {
        return host.guarded(generation, () -> {
            if (!(delegate instanceof dev.turboism.core.runtime.psd.PsdSessionBoundHost bound)) {
                throw new IllegalStateException("The captured model session cannot prove a PSD edit binding.");
            }
            return bound.sessionIdentity();
        });
    }

    @Override
    public long generation() {
        return generation;
    }

    @Override
    public dev.turboism.core.runtime.psd.PsdReplaceHost.Replacement replaceWithStagedPsd(
            final dev.turboism.sdk.cubism.id.RawImageId target,
            final java.nio.file.Path stage,
            final String sourceFileName,
            final Runnable admission) {
        Objects.requireNonNull(admission, "admission");
        return host.guarded(generation, () -> {
            if (!(delegate instanceof dev.turboism.core.runtime.psd.PsdReplaceHost replaceHost)) {
                return dev.turboism.core.runtime.psd.PsdReplaceHost.Replacement.unavailable();
            }
            return replaceHost.replaceWithStagedPsd(
                    target, stage, sourceFileName, () -> host.guardedVoid(generation, admission));
        });
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
    public dev.turboism.sdk.cubism.model.TextureRelationsSnapshot relations() {
        return host.guarded(generation, delegate::relations);
    }

    @Override
    public dev.turboism.sdk.cubism.model.TextureSourcesSnapshot sources(
            final dev.turboism.sdk.cubism.model.TextureSourceQuery query) {
        return host.guarded(generation, () -> delegate.sources(Objects.requireNonNull(query, "query")));
    }

    @Override
    public java.util.concurrent.CompletionStage<dev.turboism.sdk.cubism.psd.PsdExportResult> exportRawImagePsd(
            final dev.turboism.sdk.cubism.id.RawImageId source) {
        return host.guarded(generation, () -> delegate.exportRawImagePsd(Objects.requireNonNull(source, "source")));
    }

    @Override
    public java.util.concurrent.CompletionStage<dev.turboism.sdk.cubism.psd.PsdReplaceResult> replaceRawImagePsd(
            final dev.turboism.sdk.cubism.id.RawImageId target,
            final dev.turboism.sdk.cubism.psd.PsdEditFile file,
            final dev.turboism.sdk.cubism.psd.PsdFileRevision revision) {
        return host.guarded(
                generation,
                () -> delegate.replaceRawImagePsd(
                        Objects.requireNonNull(target, "target"),
                        Objects.requireNonNull(file, "file"),
                        Objects.requireNonNull(revision, "revision")));
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
